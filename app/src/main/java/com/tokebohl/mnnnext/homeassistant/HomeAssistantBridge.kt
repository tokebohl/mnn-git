package com.tokebohl.mnnnext.homeassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HomeAssistantBridge(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        val HA_TAG_REGEX = Regex("\\[HA_ACTION:\\s*([a-z0-9_]+)\\.([a-z0-9_]+)\\s*\\|\\s*([a-z0-9_.]+)\\s*]", RegexOption.IGNORE_CASE)
    }

    suspend fun executeServiceCall(
        baseUrl: String,
        bearerToken: String,
        domain: String,
        service: String,
        entityId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val cleanToken = bearerToken.trim()
        if (cleanUrl.isEmpty() || cleanToken.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Home Assistant URL eller Token mangler."))
        }

        val endpoint = "$cleanUrl/api/services/$domain/$service"
        val payload = JSONObject().apply {
            put("entity_id", entityId)
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $cleanToken")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("Home Assistant HTTP ${response.code}: ${response.message}")
                }
                "Home Assistant udførte $domain.$service på $entityId"
            }
        }
    }

    suspend fun checkHealth(baseUrl: String, bearerToken: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val cleanToken = bearerToken.trim()
        if (cleanUrl.isEmpty() || cleanToken.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Angiv både URL og Long-Lived Access Token"))
        }

        val request = Request.Builder()
            .url("$cleanUrl/api/")
            .addHeader("Authorization", "Bearer $cleanToken")
            .get()
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}")
                }
                "Home Assistant Online (HTTP ${response.code})"
            }
        }
    }
}
