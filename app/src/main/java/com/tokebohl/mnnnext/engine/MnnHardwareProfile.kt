package com.tokebohl.mnnnext.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

data class MnnEngineConfig(
    val modelDir: String,
    val backendType: String = "opencl",
    val threadCount: Int = 5,
    val precision: String = "low",
    val powerMode: String = "high",
    val useMmap: Boolean = true,
    val reuseKv: Boolean = true,
    val maxNewTokens: Int = 1024
) {
    fun resolveConfigFilePath(): String {
        val dir = File(modelDir)
        if (dir.isFile && dir.name.endsWith(".json")) {
            return dir.absolutePath
        }
        val configFile = File(dir, "config.json")
        if (!configFile.exists() && dir.exists() && dir.isDirectory) {
            runCatching { configFile.writeText(buildDefaultJson(backendType)) }
        }
        return configFile.absolutePath
    }

    fun toRuntimeConfigJson(overrideBackend: String = backendType): String {
        val dir = File(modelDir).let { if (it.isFile) it.parentFile ?: it else it }
        val configFile = File(dir, "config.json")
        if (configFile.exists()) {
            return runCatching {
                val existing = JSONObject(configFile.readText())
                existing.put("backend_type", overrideBackend)
                existing.put("thread_num", threadCount)
                existing.put("precision", precision)
                existing.put("power", powerMode)
                existing.put("memory", "low")
                existing.put("use_mmap", useMmap)
                existing.put("reuse_kv", reuseKv)
                existing.put("max_new_tokens", maxNewTokens)
                existing.toString()
            }.getOrElse { buildDefaultJson(overrideBackend) }
        }
        return buildDefaultJson(overrideBackend)
    }

    private fun buildDefaultJson(backend: String): String {
        val dir = File(modelDir).let { if (it.isFile) it.parentFile ?: it else it }
        return JSONObject().apply {
            put("llm_model", "${dir.absolutePath}/llm.mnn")
            put("llm_weight", "${dir.absolutePath}/llm.mnn.weight")
            put("backend_type", backend)
            put("thread_num", threadCount)
            put("precision", precision)
            put("power", powerMode)
            put("memory", "low")
            put("use_mmap", useMmap)
            put("max_new_tokens", maxNewTokens)
            put("reuse_kv", reuseKv)
        }.toString()
    }
}

class MnnLlmRunner(private val context: Context) {

    interface TokenStreamListener {
        fun onToken(token: String)
        fun onComplete(fullResponse: String, tokensPerSec: Float)
        fun onError(error: Throwable)
    }

    @Volatile
    var isInitialized: Boolean = false
        private set

    var activeConfig: MnnEngineConfig? = null
        private set

    var activeBackendName: String = "unloaded"
        private set

    fun discoverLocalModels(): List<File> {
        val rootCandidates = listOf(
            File(Environment.getExternalStorageDirectory(), "MNNModels"),
            File("/sdcard/MNNModels"),
            File(context.getExternalFilesDir(null), "MNNModels")
        )
        val discovered = mutableListOf<File>()
        for (root in rootCandidates) {
            if (root.exists() && root.isDirectory) {
                root.listFiles()?.forEach { child ->
                    if (child.isDirectory && (File(child, "config.json").exists() || File(child, "llm.mnn").exists())) {
                        if (discovered.none { it.absolutePath == child.absolutePath }) {
                            discovered.add(child)
                        }
                    }
                }
            }
        }
        return discovered
    }

    fun initialize(
        modelDir: File,
        backendType: String = "opencl",
        threadCount: Int = 5,
        maxNewTokens: Int = 1024,
        reuseKv: Boolean = true
    ): Result<MnnEngineConfig> = runCatching {
        if (!isNativeLibLoaded) {
            throw IllegalStateException("Native bibliotek 'libmnn_llm_jni.so' er ikke indlæst.")
        }

        val targetDir = if (modelDir.isFile) modelDir.parentFile ?: modelDir else modelDir
        require(targetDir.exists()) {
            "Model-mappe findes ikke: ${targetDir.absolutePath}"
        }

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { actManager.getMemoryInfo(it) }
        val availableGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)

        val config = MnnEngineConfig(
            modelDir = targetDir.absolutePath,
            backendType = backendType.lowercase().trim(),
            threadCount = threadCount.coerceIn(1, 8),
            useMmap = availableGb < 12.0,
            reuseKv = reuseKv,
            maxNewTokens = maxNewTokens.coerceIn(64, 4096)
        )

        val configFilePath = config.resolveConfigFilePath()
        val primaryJson = config.toRuntimeConfigJson(config.backendType)
        val fallbackCpuJson = config.toRuntimeConfigJson("cpu")

        val success = nativeInitLlm(
            configFilePath,
            primaryJson,
            fallbackCpuJson,
            config.backendType
        )
        if (!success) {
            val nativeErr = runCatching { nativeGetLastError() }.getOrDefault("Ukendt C++ JNI fejl")
            throw IllegalStateException("Kunne ikke initialisere MNN LLM fra ${targetDir.absolutePath}: $nativeErr")
        }

        val embedCandidate = File(targetDir, "embedding.mnn").takeIf { it.exists() }
            ?: File("/sdcard/MNNModels/embedding.mnn").takeIf { it.exists() }
        if (embedCandidate != null) {
            runCatching { nativeLoadEmbeddingModel(embedCandidate.absolutePath) }
        }

        activeBackendName = runCatching { nativeGetActiveBackend() }.getOrDefault(config.backendType)
        isInitialized = true
        activeConfig = config.copy(backendType = activeBackendName)
        activeConfig!!
    }

    fun generateStream(prompt: String, listener: TokenStreamListener) {
        if (!isInitialized) {
            listener.onError(IllegalStateException("MNN Engine er ikke indlæst. Vælg en MNN-model under fanen 10T & HW og tryk 'Indlæs Model'."))
            return
        }

        val startTime = System.nanoTime()
        val fullBuilder = StringBuilder()
        var tokenCount = 0
        val maxTokens = activeConfig?.maxNewTokens ?: 1024

        try {
            nativeSubmitPrompt(prompt, maxTokens) { tokenPiece ->
                if (tokenPiece == "<|im_end|>" || tokenPiece == "<|endoftext|>" || tokenPiece == "<eop>") {
                    return@nativeSubmitPrompt false
                }
                tokenCount++
                fullBuilder.append(tokenPiece)
                listener.onToken(tokenPiece)
                true
            }
            val elapsedSec = ((System.nanoTime() - startTime) / 1_000_000_000.0f).coerceAtLeast(0.001f)
            listener.onComplete(fullBuilder.toString(), tokenCount / elapsedSec)
        } catch (t: Throwable) {
            listener.onError(t)
        }
    }

    fun cancelGeneration() {
        if (isNativeLibLoaded) {
            runCatching { nativeCancelGeneration() }
        }
    }

    fun resetContext() {
        if (isInitialized && isNativeLibLoaded) {
            runCatching { nativeResetKVCache() }
        }
    }

    fun unloadModel() {
        if (isNativeLibLoaded) {
            runCatching { nativeUnloadLlm() }
        }
        isInitialized = false
        activeBackendName = "unloaded"
    }

    fun computeEmbedding(tokenIds: IntArray): FloatArray? {
        if (!isNativeLibLoaded) return null
        return runCatching { nativeComputeEmbedding(tokenIds) }.getOrNull()
    }

    private external fun nativeInitLlm(
        configPath: String,
        runtimeConfigJson: String,
        fallbackCpuConfigJson: String,
        requestedBackend: String
    ): Boolean

    private external fun nativeSubmitPrompt(
        prompt: String,
        maxNewTokens: Int,
        callback: (String) -> Boolean
    )

    private external fun nativeCancelGeneration()
    private external fun nativeResetKVCache()
    private external fun nativeUnloadLlm()
    private external fun nativeGetActiveBackend(): String
    private external fun nativeGetLastError(): String
    private external fun nativeLoadEmbeddingModel(modelPath: String): Boolean
    private external fun nativeComputeEmbedding(tokenIds: IntArray): FloatArray

    companion object {
        var isNativeLibLoaded: Boolean = false
            private set

        init {
            try {
                runCatching { System.loadLibrary("MNN") }
                runCatching { System.loadLibrary("MNN_Express") }
                runCatching { System.loadLibrary("MNN_CL") }
                runCatching { System.loadLibrary("MNN_Vulkan") }
                runCatching { System.loadLibrary("llm") }
                System.loadLibrary("mnn_llm_jni")
                isNativeLibLoaded = true
            } catch (_: UnsatisfiedLinkError) {
                isNativeLibLoaded = false
            }
        }
    }
}
