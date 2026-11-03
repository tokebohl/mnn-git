package com.tokebohl.mnnnext.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tokebohl.mnnnext.engine.MnnLlmRunner
import com.tokebohl.mnnnext.homeassistant.HomeAssistantBridge
import com.tokebohl.mnnnext.memory.JarvisMemoryStore
import com.tokebohl.mnnnext.network.HayaiTtsBridge
import com.tokebohl.mnnnext.security.SecureCredentialStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisBrainService : Service() {

    companion object {
        private const val TAG = "JarvisBrainService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "jarvis_brain_channel"
        private val MEMORY_TAG_REGEX = Regex("\\[REMEMBER:\\s*(.*?)]", RegexOption.IGNORE_CASE)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    lateinit var credentialStore: SecureCredentialStore
        private set
    lateinit var llmRunner: MnnLlmRunner
        private set
    lateinit var ttsBridge: HayaiTtsBridge
        private set
    lateinit var memoryStore: JarvisMemoryStore
        private set
    lateinit var homeAssistantBridge: HomeAssistantBridge
        private set

    private val conversationContext = mutableListOf<Pair<String, String>>()
    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    var isTtsStreamingEnabled: Boolean = true

    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): JarvisBrainService = this@JarvisBrainService
    }

    override fun onCreate() {
        super.onCreate()
        startPersistentForeground()
        acquireCpuWakeLock()

        credentialStore = SecureCredentialStore(this)
        llmRunner = MnnLlmRunner(this)
        memoryStore = JarvisMemoryStore(this) { llmRunner }
        homeAssistantBridge = HomeAssistantBridge()
        ttsBridge = HayaiTtsBridge(this, serviceScope, credentialStore).also {
            it.startAutoConnect()
        }
        appendLog("JarvisBrainService startet på OnePlus 12R. Auto-Discovery efter OnePlus 10T aktiv.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun appendLog(message: String) {
        val ts = timeFormat.format(Date())
        val entry = "[$ts] $message"
        Log.i(TAG, message)
        val updated = (_diagnosticLogs.value + entry).takeLast(120)
        _diagnosticLogs.value = updated
    }

    fun loadBrainModel(
        modelDirectory: File,
        backend: String = "opencl",
        threads: Int = 5,
        maxNewTokens: Int = 1024,
        reuseKv: Boolean = true,
        onReady: (Boolean, String) -> Unit
    ) {
        appendLog("Indlæser MNN model: ${modelDirectory.absolutePath} (Backend=$backend, Tråde=$threads)")
        serviceScope.launch(Dispatchers.IO) {
            val result = llmRunner.initialize(
                modelDir = modelDirectory,
                backendType = backend,
                threadCount = threads,
                maxNewTokens = maxNewTokens,
                reuseKv = reuseKv
            )
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { cfg ->
                        credentialStore.lastModelDir = modelDirectory.absolutePath
                        credentialStore.preferredBackend = backend
                        credentialStore.preferredThreads = threads
                        val msg = "Aktiv (${cfg.threadCount} kerner, ${cfg.backendType.uppercase()}, FP16, mmap=${cfg.useMmap}, KV-reuse=${cfg.reuseKv})"
                        appendLog("Model klar: $msg")
                        onReady(true, msg)
                    },
                    onFailure = { err ->
                        val msg = err.message ?: "Ukendt fejl ved indlæsning"
                        appendLog("FEJL ved model-indlæsning: $msg")
                        onReady(false, msg)
                    }
                )
            }
        }
    }

    fun processUserInput(
        userInput: String,
        onTokenGenerated: (String) -> Unit = {},
        onFinished: (String, Float) -> Unit = { _, _ -> }
    ) {
        serviceScope.launch(Dispatchers.Default) {
            _isGenerating.value = true
            val autoSaved = memoryStore.extractUserFactsAutomatically(userInput)
            if (autoSaved.isNotEmpty()) {
                appendLog("Gemte ${autoSaved.size} nye fakta fra brugerbesked i SQLite FTS5/vektor-hukommelse.")
            }

            val relevantMemories = memoryStore.recallRelevant(userInput, limit = 6)
            val fullPrompt = buildJarvisPrompt(userInput, relevantMemories.map { it.content })

            if (isTtsStreamingEnabled) {
                ttsBridge.startStreamSession()
            }

            llmRunner.generateStream(fullPrompt, object : MnnLlmRunner.TokenStreamListener {
                override fun onToken(token: String) {
                    onTokenGenerated(token)
                    if (isTtsStreamingEnabled && !token.contains("[REMEMBER") && !token.contains("[HA_ACTION")) {
                        ttsBridge.feedToken(token)
                    }
                }

                override fun onComplete(fullResponse: String, tokensPerSec: Float) {
                    _isGenerating.value = false
                    if (isTtsStreamingEnabled) {
                        ttsBridge.flushRemainingTokens()
                    }
                    extractAndSaveMemories(fullResponse)
                    executeHomeAssistantCommands(fullResponse)

                    val cleanResponse = fullResponse
                        .replace(MEMORY_TAG_REGEX, "")
                        .replace(HomeAssistantBridge.HA_TAG_REGEX, "")
                        .trim()

                    synchronized(conversationContext) {
                        conversationContext.add("user" to userInput)
                        conversationContext.add("assistant" to cleanResponse)
                        while (conversationContext.size > 12) {
                            conversationContext.removeAt(0)
                        }
                    }

                    appendLog("Svar genereret (${"%.1f".format(tokensPerSec)} tok/s).")
                    onFinished(cleanResponse, tokensPerSec)
                }

                override fun onError(error: Throwable) {
                    _isGenerating.value = false
                    if (isTtsStreamingEnabled) {
                        ttsBridge.flushRemainingTokens()
                    }
                    val errMsg = error.message ?: "Ukendt inferens-fejl"
                    appendLog("MNN inferens fejl: $errMsg")
                    onFinished("Fejl i MNN Engine: $errMsg", 0f)
                }
            })
        }
    }

    fun cancelActiveGeneration() {
        appendLog("Bruger afbrød MNN generering og HayaiTTS tale.")
        llmRunner.cancelGeneration()
        ttsBridge.cancelPlayback()
        _isGenerating.value = false
    }

    fun resetConversationAndKvCache() {
        synchronized(conversationContext) {
            conversationContext.clear()
        }
        llmRunner.resetContext()
        appendLog("Samtalehistorik og MNN KV-Cache nulstillet.")
    }

    private fun buildJarvisPrompt(userInput: String, memories: List<String>): String {
        val memoryBlock = if (memories.isEmpty()) {
            "Ingen tidligere minder registreret endnu."
        } else {
            memories.joinToString("\n") { "- $it" }
        }

        val recentTurns = synchronized(conversationContext) {
            conversationContext.takeLast(6).joinToString("\n") { (role, text) ->
                "<|im_start|>$role\n$text<|im_end|>"
            }
        }

        return buildString {
            appendLine("<|im_start|>system")
            appendLine("Du er Jarvis, en lynhurtig, fuldautomatisk offline AI-hjerne, der kører på brugerens OnePlus 12R og taler direkte gennem OnePlus 10T (HayaiTTS-Next).")
            appendLine("Svar kortfattet, skarpt, naturligt og mundret på dansk, da dine sætninger streames direkte til tale.")
            appendLine("Hvis brugeren fortæller ny vigtig information, som du skal huske fremover, skal du tilføje [REMEMBER: kort præcist faktum] til allerallersidst i dit svar.")
            appendLine("Hvis brugeren beder om at styre smart-home via Home Assistant på Raspberry Pi, tilføj [HA_ACTION: domain.service | entity_id] til sidst i svaret.")
            appendLine()
            appendLine("Minder fra langtidshukommelsen:")
            appendLine(memoryBlock)
            appendLine("<|im_end|>")
            if (recentTurns.isNotEmpty()) {
                appendLine(recentTurns)
            }
            appendLine("<|im_start|>user")
            appendLine(userInput)
            appendLine("<|im_end|>")
            append("<|im_start|>assistant")
        }
    }

    private fun extractAndSaveMemories(response: String) {
        MEMORY_TAG_REGEX.findAll(response).forEach { match ->
            val fact = match.groupValues[1].trim()
            if (fact.isNotEmpty()) {
                memoryStore.remember(content = fact, category = "auto_extracted", importance = 1.5f)
                appendLog("Jarvis gemte nyt minde: $fact")
            }
        }
    }

    private fun executeHomeAssistantCommands(response: String) {
        val matches = HomeAssistantBridge.HA_TAG_REGEX.findAll(response).toList()
        if (matches.isEmpty()) return

        serviceScope.launch(Dispatchers.IO) {
            for (match in matches) {
                val domain = match.groupValues[1]
                val service = match.groupValues[2]
                val entityId = match.groupValues[3]
                val res = homeAssistantBridge.executeServiceCall(
                    baseUrl = credentialStore.homeAssistantUrl,
                    bearerToken = credentialStore.homeAssistantToken,
                    domain = domain,
                    service = service,
                    entityId = entityId
                )
                res.fold(
                    onSuccess = { appendLog(it) },
                    onFailure = { appendLog("Home Assistant fejl: ${it.message}") }
                )
            }
        }
    }

    private fun acquireCpuWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:BrainCpuWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startPersistentForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis AI Core",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Core Aktiv (OnePlus 12R)")
            .setContentText("MNN LLM + HayaiTTS-Next (/v1/tts/stream) kører")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        ttsBridge.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}
