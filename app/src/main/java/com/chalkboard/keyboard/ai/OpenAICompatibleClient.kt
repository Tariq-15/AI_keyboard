package com.chalkboard.keyboard.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One implementation of the OpenAI chat-completions shape, shared by every provider that
 * speaks it — OpenAI, Groq, and any custom endpoint the user points at.
 *
 * They differ only in base URL and default model, which is exactly the kind of
 * difference that belongs in configuration rather than in three near-identical classes.
 */
internal class OpenAICompatibleClient(
    private val providerId: String,
    private val baseUrl: String,
    private val model: String,
) {

    suspend fun improveText(text: String, apiKey: String): Result<String> {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.3)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", AIProvider.SYSTEM_PROMPT)
                    )
                    put(JSONObject().put("role", "user").put("content", text))
                },
            )
        }

        return post("$baseUrl/chat/completions", body, apiKey).mapCatching { payload ->
            JSONObject(payload)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw AIError.Malformed(providerId, "no completion in response")
        }
    }

    /** Lists models: the cheapest call that proves the key is accepted. */
    suspend fun validateKey(apiKey: String): Result<Unit> =
        get("$baseUrl/models", apiKey).map { }

    private suspend fun post(url: String, body: JSONObject, apiKey: String): Result<String> =
        request(url, apiKey, body)

    private suspend fun get(url: String, apiKey: String): Result<String> =
        request(url, apiKey, body = null)

    private suspend fun request(
        url: String,
        apiKey: String,
        body: JSONObject?,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
            }
            try {
                body?.let { payload ->
                    connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                }
                when (val code = connection.responseCode) {
                    in 200..299 ->
                        Result.success(connection.inputStream.bufferedReader().use { it.readText() })

                    401, 403 -> Result.failure(AIError.InvalidKey(providerId))
                    429 -> Result.failure(
                        AIError.RateLimited(providerId, connection.retryAfterSeconds())
                    )

                    else -> Result.failure(AIError.Server(providerId, code))
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Result.failure(AIError.Offline(providerId))
        } catch (e: Exception) {
            Result.failure(AIError.Malformed(providerId, e.message ?: "unreadable response"))
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

/**
 * The wait a 429 asked for, in seconds, or null if it did not ask for one.
 *
 * `Retry-After` is defined as either a delay in seconds or an HTTP date, and both turn
 * up in the wild. A value that parses as neither is dropped rather than guessed at: the
 * message then degrades to a plain "Rate limited" instead of promising a wait that is
 * not real. Past-dated and non-positive values are dropped for the same reason.
 */
internal fun HttpURLConnection.retryAfterSeconds(): Int? {
    val raw = getHeaderField(RETRY_AFTER)?.trim().orEmpty()
    if (raw.isEmpty()) return null
    raw.toIntOrNull()?.let { return it.takeIf { seconds -> seconds > 0 } }

    val epochMillis = getHeaderFieldDate(RETRY_AFTER, 0L).takeIf { it > 0L } ?: return null
    val remaining = (epochMillis - System.currentTimeMillis()) / 1_000L
    return remaining.toInt().takeIf { remaining > 0L && remaining <= Int.MAX_VALUE }
}

private const val RETRY_AFTER = "Retry-After"
