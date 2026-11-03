#include <jni.h>
#include <string>
#include <memory>
#include <mutex>
#include <atomic>
#include <streambuf>
#include <ostream>
#include <fstream>
#include <vector>
#include <cmath>
#include <android/log.h>

#if defined(HAS_MNN_LIB)
#include "llm/llm.hpp"
#include <MNN/Interpreter.hpp>
#include <MNN/expr/Expr.hpp>
#include <MNN/expr/Module.hpp>
#include <MNN/expr/Executor.hpp>
#endif

#define LOG_TAG "MNN_NEXT_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::mutex g_llmMutex;
    std::mutex g_embedMutex;
    std::atomic<bool> g_cancelRequested{false};
    std::string g_activeBackend = "unloaded";
    std::string g_lastNativeError = "";

#if defined(HAS_MNN_LIB)
    std::unique_ptr<MNN::Transformer::Llm> g_llmInstance = nullptr;
    std::shared_ptr<MNN::Express::Module> g_embeddingModule = nullptr;
#endif

    class JniTokenStreamBuf : public std::streambuf {
    public:
        JniTokenStreamBuf(JNIEnv* env, jobject callbackObj, jmethodID invokeMethod)
            : m_env(env), m_callbackObj(callbackObj), m_invokeMethod(invokeMethod) {}

        bool isStopped() const {
            return m_stopped || g_cancelRequested.load(std::memory_order_relaxed);
        }

    protected:
        std::streamsize xsputn(const char* s, std::streamsize n) override {
            if (isStopped()) return 0;
            if (n <= 0) return n;

            m_pendingBytes.insert(m_pendingBytes.end(), s, s + n);
            size_t validLen = findValidUtf8PrefixLength(m_pendingBytes);

            if (validLen > 0) {
                std::string validUtf8(m_pendingBytes.begin(), m_pendingBytes.begin() + validLen);
                m_pendingBytes.erase(m_pendingBytes.begin(), m_pendingBytes.begin() + validLen);
                emitTokenToKotlin(validUtf8);
            }
            return isStopped() ? 0 : n;
        }

        int overflow(int c) override {
            if (isStopped()) return EOF;
            if (c != EOF) {
                char ch = static_cast<char>(c);
                return xsputn(&ch, 1) == 1 ? c : EOF;
            }
            return c;
        }

    private:
        JNIEnv* m_env;
        jobject m_callbackObj;
        jmethodID m_invokeMethod;
        std::vector<char> m_pendingBytes;
        bool m_stopped = false;

        static size_t findValidUtf8PrefixLength(const std::vector<char>& buf) {
            size_t len = buf.size();
            if (len == 0) return 0;

            for (size_t back = 1; back <= 3 && back <= len; ++back) {
                unsigned char b = static_cast<unsigned char>(buf[len - back]);
                if ((b & 0x80) == 0x00) return len;
                if ((b & 0xC0) == 0xC0) {
                    size_t expectedSeqLen = 0;
                    if ((b & 0xE0) == 0xC0) expectedSeqLen = 2;
                    else if ((b & 0xF0) == 0xE0) expectedSeqLen = 3;
                    else if ((b & 0xF8) == 0xF0) expectedSeqLen = 4;

                    if (back < expectedSeqLen) return len - back;
                    return len;
                }
            }
            return len;
        }

        void emitTokenToKotlin(const std::string& tokenPiece) {
            if (isStopped()) return;
            jstring jToken = m_env->NewStringUTF(tokenPiece.c_str());
            if (!jToken) return;

            jobject boolResult = m_env->CallObjectMethod(m_callbackObj, m_invokeMethod, jToken);
            if (m_env->ExceptionCheck()) {
                m_env->ExceptionDescribe();
                m_env->ExceptionClear();
                m_stopped = true;
                g_cancelRequested.store(true, std::memory_order_relaxed);
            } else if (boolResult != nullptr) {
                jclass boolClass = m_env->GetObjectClass(boolResult);
                jmethodID boolValueMid = m_env->GetMethodID(boolClass, "booleanValue", "()Z");
                jboolean keepGoing = m_env->CallBooleanMethod(boolResult, boolValueMid);
                if (!keepGoing) {
                    m_stopped = true;
                    g_cancelRequested.store(true, std::memory_order_relaxed);
                }
                m_env->DeleteLocalRef(boolClass);
                m_env->DeleteLocalRef(boolResult);
            }
            m_env->DeleteLocalRef(jToken);
        }
    };
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tokebohl_mnnnext_engine_MnnLlmRunner_nativeInitLlm(
        JNIEnv* env,
        jobject /* this */,
        jstring configPathJstr,
        jstring runtimeConfigJsonJstr,
        jstring fallbackCpuConfigJsonJstr,
        jstring requestedBackendJstr) {

    std::lock_guard<std::mutex> lock(g_llmMutex);
    const char* pathChars = env->GetStringUTFChars(configPathJstr, nullptr);
    const char* runtimeChars = env->GetStringUTFChars(runtimeConfigJsonJstr, nullptr);
    const char* fallbackChars = env->GetStringUTFChars(fallbackCpuConfigJsonJstr, nullptr);
    const char* backendChars = env->GetStringUTFChars(requestedBackendJstr, nullptr);

    std::string configPath(pathChars ? pathChars : "");
    std::string runtimeConfigJson(runtimeChars ? runtimeChars : "");
    std::string fallbackCpuJson(fallbackChars ? fallbackChars : "");
    std::string requestedBackend(backendChars ? backendChars : "cpu");

    if (pathChars) env->ReleaseStringUTFChars(configPathJstr, pathChars);
    if (runtimeChars) env->ReleaseStringUTFChars(runtimeConfigJsonJstr, runtimeChars);
    if (fallbackChars) env->ReleaseStringUTFChars(fallbackCpuConfigJsonJstr, fallbackChars);
    if (backendChars) env->ReleaseStringUTFChars(requestedBackendJstr, backendChars);

#if defined(HAS_MNN_LIB)
    try {
        g_llmInstance.reset();
        g_llmInstance.reset(MNN::Transformer::Llm::createLLM(configPath));
        if (!g_llmInstance) {
            g_lastNativeError = "MNN::Transformer::Llm::createLLM returned nullptr for path: " + configPath;
            LOGE("%s", g_lastNativeError.c_str());
            return JNI_FALSE;
        }
        if (!runtimeConfigJson.empty()) {
            g_llmInstance->set_config(runtimeConfigJson);
        }
        g_llmInstance->load();
        g_activeBackend = requestedBackend;
        g_lastNativeError = "";
        LOGI("MNN LLM loaded with Snapdragon 8 Gen 2 backend: %s", g_activeBackend.c_str());
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Exception loading MNN LLM on %s: %s", requestedBackend.c_str(), e.what());
        if (requestedBackend != "cpu" && !fallbackCpuJson.empty()) {
            LOGI("Attempting automatic fallback to CPU backend (5 fast Kryo cores)...");
            try {
                g_llmInstance.reset(MNN::Transformer::Llm::createLLM(configPath));
                if (g_llmInstance) {
                    g_llmInstance->set_config(fallbackCpuJson);
                    g_llmInstance->load();
                    g_activeBackend = "cpu (fallback)";
                    g_lastNativeError = "";
                    return JNI_TRUE;
                }
            } catch (const std::exception& fallbackEx) {
                g_lastNativeError = std::string("GPU & CPU Fallback failed: ") + fallbackEx.what();
                LOGE("%s", g_lastNativeError.c_str());
            }
        } else {
            g_lastNativeError = e.what();
        }
        g_llmInstance.reset();
        g_activeBackend = "error";
        return JNI_FALSE;
    }
#else
    g_lastNativeError = "Native MNN libraries (libllm.so / libMNN.so) were not linked at build time. Build via GitHub Actions or copy libllm.so to app/src/main/jniLibs/arm64-v8a.";
    g_activeBackend = "missing_native_libs";
    LOGE("%s", g_lastNativeError.c_str());
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_tokebohl_mnnnext_engine_MnnLlmRunner_nativeSubmitPrompt(
        JNIEnv* env,
        jobject /* this */,
        jstring promptJstr,
        jint maxNewTokens,
        jobject callbackLambda) {

    std::lock_guard<std::mutex> lock(g_llmMutex);
    g_cancelRequested.store(false, std::memory_order_relaxed);

    const char* promptChars = env->GetStringUTFChars(promptJstr, nullptr);
    if (!promptChars) return;
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(promptJstr, promptChars);

    jclass fnClass = env->GetObjectClass(callbackLambda);
    jmethodID invokeMid = env->GetMethodID(fnClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (!invokeMid) {
        env->DeleteLocalRef(fnClass);
        return;
    }

    JniTokenStreamBuf streamBuf(env, callbackLambda, invokeMid);
    std::ostream responseStream(&streamBuf);

#if defined(HAS_MNN_LIB)
    if (!g_llmInstance) {
        g_lastNativeError = "Llm instance is null during nativeSubmitPrompt.";
        env->DeleteLocalRef(fnClass);
        return;
    }
    try {
        g_llmInstance->response(prompt, &responseStream, "<eop>", static_cast<int>(maxNewTokens));
        responseStream.flush();
    } catch (const std::exception& e) {
        g_lastNativeError = e.what();
        LOGE("Exception during MNN generation: %s", e.what());
    }
#else
    (void)maxNewTokens;
    g_lastNativeError = "Cannot generate: libllm.so not linked.";
#endif

    env->DeleteLocalRef(fnClass);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tokebohl_mnnnext_engine_MnnLlmRunner_nativeCancelGeneration(
        JNIEnv* /* env */,
        jobject /* this */) {
    g_cancelRequested.store(true, std::memory_order_relaxed);
    LOGI("MNN token generation cancellation requested.");
}

extern "C" JNIEXPORT void JNICALL
Java_com_tokebohl_mnnnext_engine_MnnLlmRunner_nativeResetKVCache(
        JNIEnv* /* env */,
        jobject /* this */) {

    std::lock_guard<std::mutex> lock(g_llmMutex);
#if defined(HAS_MNN_LIB)
    if (g_llmInstance) {
        g_llmInstance->reset();
        LOGI("MNN KV-Cache reset completed.");
    }
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_tokebohl_mnnnext_engine_MnnLlmRunner_nativeUnloadLlm(
        JNIEnv* /* env */,
        jobject /* this */) {

    std::lock_guard<std::mutex> lock(g_llmMutex);
#if defined(HAS_MNN_LIB)
    g_llmInstance.reset();
#endif
    g_activeBackend = "unloaded";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tokebohl_mnnnext_engine_MnnLlmRunner_nativeGetActiveBackend(
        JNIEnv* env,
        jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_llmMutex);
    return env->NewStringUTF(g_activeBackend.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tokebohl_mnnnext_engine_MnnLlmRunner_nativeGetLastError(
        JNIEnv* env,
        jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_llmMutex);
    return env->NewStringUTF(g_lastNativeError.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tokebohl_mnnnext_engine_MnnLlmRunner_nativeLoadEmbeddingModel(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPathJstr) {

    std::lock_guard<std::mutex> lock(g_embedMutex);
    const char* pathChars = env->GetStringUTFChars(modelPathJstr, nullptr);
    if (!pathChars) return JNI_FALSE;
    std::string modelPath(pathChars);
    env->ReleaseStringUTFChars(modelPathJstr, pathChars);

#if defined(HAS_MNN_LIB)
    try {
        MNN::Express::Module::Config config;
        config.rearrange = true;
        g_embeddingModule.reset(MNN::Express::Module::load(
            {"input_ids", "attention_mask"},
            {"sentence_embedding"},
            modelPath.c_str(),
            &config
        ));
        return g_embeddingModule != nullptr ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("Failed to load MNN embedding model (%s): %s", modelPath.c_str(), e.what());
        return JNI_FALSE;
    }
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tokebohl_mnnnext_engine_MnnLlmRunner_nativeComputeEmbedding(
        JNIEnv* env,
        jobject /* this */,
        jintArray tokenIdsJarr) {

    std::lock_guard<std::mutex> lock(g_embedMutex);
    jsize len = env->GetArrayLength(tokenIdsJarr);
    jint* tokens = env->GetIntArrayElements(tokenIdsJarr, nullptr);
    constexpr int kDim = 384;
    std::vector<float> vec(kDim, 0.0f);

#if defined(HAS_MNN_LIB)
    if (g_embeddingModule != nullptr && len > 0) {
        try {
            auto inputIds = MNN::Express::_Input({1, len}, MNN::Express::NCHW, halide_type_of<int>());
            auto mask = MNN::Express::_Input({1, len}, MNN::Express::NCHW, halide_type_of<int>());
            int* idPtr = inputIds->writeMap<int>();
            int* maskPtr = mask->writeMap<int>();
            for (int i = 0; i < len; ++i) {
                idPtr[i] = tokens[i];
                maskPtr[i] = 1;
            }
            auto outputs = g_embeddingModule->onForward({inputIds, mask});
            if (!outputs.empty()) {
                const float* outPtr = outputs[0]->readMap<float>();
                int outSize = outputs[0]->getInfo()->size;
                int copyCount = outSize < kDim ? outSize : kDim;
                for (int i = 0; i < copyCount; ++i) {
                    vec[i] = outPtr[i];
                }
            }
        } catch (const std::exception& e) {
            LOGE("Embedding forward exception: %s", e.what());
        }
    }
#endif

    float normSq = 0.0f;
    for (float v : vec) normSq += v * v;
    if (normSq < 1e-6f && tokens != nullptr) {
        for (int i = 0; i < len; ++i) {
            uint32_t h = static_cast<uint32_t>(tokens[i]) * 2654435761u;
            vec[h % kDim] += 1.0f;
            vec[(h >> 8) % kDim] += 0.5f;
        }
        normSq = 0.0f;
        for (float v : vec) normSq += v * v;
    }

    if (tokens != nullptr) {
        env->ReleaseIntArrayElements(tokenIdsJarr, tokens, JNI_ABORT);
    }

    float norm = std::sqrt(normSq);
    if (norm > 1e-6f) {
        for (float& v : vec) v /= norm;
    }

    jfloatArray outArr = env->NewFloatArray(kDim);
    env->SetFloatArrayRegion(outArr, 0, kDim, vec.data());
    return outArr;
}
