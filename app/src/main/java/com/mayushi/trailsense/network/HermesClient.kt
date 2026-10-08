package com.mayushi.trailsense.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

data class HermesResult(val text: String, val promptTokens: Int, val completionTokens: Int)

class HermesClient(private val client: OkHttpClient = defaultClient()) {
    fun analyze(
        endpoint: String,
        bearerKey: String,
        instructions: String,
        payload: String,
        sessionId: String,
        imagePath: String? = null,
        imageMimeType: String? = null,
    ): HermesResult {
        val base = EndpointPolicy.normalizeAndValidate(endpoint)
        val userContent: Any = if (imagePath != null) {
            val file = File(imagePath)
            if (!file.isFile || file.length() > MAX_IMAGE_BYTES) throw IOException("Скриншот недоступен или слишком большой")
            val data = Base64.getEncoder().encodeToString(file.readBytes())
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", payload))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${imageMimeType ?: "image/jpeg"};base64,$data")))
        } else payload
        val body = JSONObject()
            .put("model", "ksyusha")
            .put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", userContent)))
        val request = Request.Builder()
            .url("$base/v1/chat/completions")
            .header("Authorization", "Bearer $bearerKey")
            .header("X-Hermes-Session-Id", sessionId.take(128))
            .header("X-Hermes-Session-Key", "app:trailsense:mayushi")
            .header("Idempotency-Key", UUID.nameUUIDFromBytes((sessionId + payload).toByteArray()).toString())
            .post(body.toString().toRequestBody(JSON)).build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Hermes ответил ${response.code}: ${responseBody.take(300)}")
            val json = JSONObject(responseBody)
            val text = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val usage = json.optJSONObject("usage")
            return HermesResult(text, usage?.optInt("prompt_tokens") ?: 0, usage?.optInt("completion_tokens") ?: 0)
        }
    }

    fun health(endpoint: String, bearerKey: String): Boolean {
        val base = EndpointPolicy.normalizeAndValidate(endpoint)
        val request = Request.Builder().url("$base/health/detailed").header("Authorization", "Bearer $bearerKey").get().build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private fun defaultClient() = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build()
    }
}
