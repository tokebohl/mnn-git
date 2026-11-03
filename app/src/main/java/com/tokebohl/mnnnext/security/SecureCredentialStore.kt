package com.tokebohl.mnnnext.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context.applicationContext,
                "jarvis_secure_keystore_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.applicationContext.getSharedPreferences("jarvis_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    var hayaiApiKey: String
        get() = prefs.getString("hayai_api_key", "") ?: ""
        set(value) = prefs.edit().putString("hayai_api_key", value.trim()).apply()

    var hayaiHost: String
        get() = prefs.getString("hayai_host", "192.168.1.100") ?: "192.168.1.100"
        set(value) = prefs.edit().putString("hayai_host", value.trim()).apply()

    var hayaiPort: Int
        get() = prefs.getInt("hayai_port", 8000)
        set(value) = prefs.edit().putInt("hayai_port", value).apply()

    var hayaiVoice: String
        get() = prefs.getString("hayai_voice", "default") ?: "default"
        set(value) = prefs.edit().putString("hayai_voice", value.trim().ifEmpty { "default" }).apply()

    var homeAssistantUrl: String
        get() = prefs.getString("ha_url", "http://192.168.1.50:8123") ?: "http://192.168.1.50:8123"
        set(value) = prefs.edit().putString("ha_url", value.trim().removeSuffix("/")).apply()

    var homeAssistantToken: String
        get() = prefs.getString("ha_token", "") ?: ""
        set(value) = prefs.edit().putString("ha_token", value.trim()).apply()

    var lastModelDir: String
        get() = prefs.getString("last_model_dir", "/sdcard/MNNModels/Qwen2.5-7B-Instruct-MNN")
            ?: "/sdcard/MNNModels/Qwen2.5-7B-Instruct-MNN"
        set(value) = prefs.edit().putString("last_model_dir", value.trim()).apply()

    var preferredBackend: String
        get() = prefs.getString("preferred_backend", "opencl") ?: "opencl"
        set(value) = prefs.edit().putString("preferred_backend", value.trim()).apply()

    var preferredThreads: Int
        get() = prefs.getInt("preferred_threads", 5)
        set(value) = prefs.edit().putInt("preferred_threads", value.coerceIn(1, 8)).apply()
}
