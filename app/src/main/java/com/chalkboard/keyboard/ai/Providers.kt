package com.chalkboard.keyboard.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The providers a user can bring a key for.
 *
 * Three of the four speak the OpenAI chat-completions shape and differ only by base URL
 * and default model, so they are configuration of one client. Gemini has its own request
 * and response shape and needs a real implementation.
 */

class OpenAIProvider : AIProvider {
    override val id = "openai"
    override val displayName = "OpenAI"
    override val keyUrl = "https://platform.openai.com/api-keys"

    private val client = OpenAICompatibleClient(id, "https://api.openai.com/v1", "gpt-4o-mini")

    override suspend fun improveText(text: String, apiKey: String) =
        client.improveText(text, apiKey)

    override suspend fun validateKey(apiKey: String) = client.validateKey(apiKey)
}

class GroqProvider : AIProvider {
    override val id = "groq"
    override val displayName = "Groq"
    override val keyUrl = "https://console.groq.com/keys"

    private val client = OpenAICompatibleClient(
        providerId = id,
        baseUrl = "https://api.groq.com/openai/v1",
        model = "llama-3.3-70b-versatile",
    )

    override suspend fun improveText(text: String, apiKey: String) =
        client.improveText(text, apiKey)

    override suspend fun validateKey(apiKey: String) = client.validateKey(apiKey)
}

/**
 * Any other OpenAI-compatible endpoint — a local model, a proxy, a different vendor.
 * Base URL and model come from the user, so this is the escape hatch that keeps the
 * provider list from needing to grow.
 */
class CustomEndpointProvider(
    private val baseUrl: String,
    private val model: String,
) : AIProvider {
    override val id = ID
    override val displayName = "Custom endpoint"
    override val keyUrl = ""

    private val client = OpenAICompatibleClient(id, baseUrl.trimEnd('/'), model)

    override suspend fun improveText(text: String, apiKey: String) =
        client.improveText(text, apiKey)

    override suspend fun validateKey(apiKey: String) = client.validateKey(apiKey)

    companion object {
        const val ID = "custom"
    }
}

/**
 * Google Gemini. Its own request shape, and the key goes in the query string rather than
 * an Authorization header.
 */
class GeminiProvider : AIProvider {
    override val id = "gemini"
    override val displayName = "Gemini"
    override val keyUrl = "https://aistudio.google.com/apikey"

    override suspend fun improveText(text: String, apiKey: String): Result<String> {
        val body = JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", AIProvider.SYSTEM_PROMPT)),
                ),
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", text)),
                    )
                ),
            )
        }

        return request("$BASE/models/$MODEL:generateContent?key=${encode(apiKey)}", body)
            .mapCatching { payload ->
                JSONObject(payload)
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw AIError.Malformed(id, "no candidate in response")
            }
    }

    /** Listing models is the cheapest call that proves the key is accepted. */
    override suspend fun validateKey(apiKey: String): Result<Unit> =
        request("$BASE/models?key=${encode(apiKey)}", body = null).map { }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private suspend fun request(url: String, body: JSONObject?): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = if (body == null) "GET" else "POST"
                    connectTimeout = 5_000
                    readTimeout = 20_000
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
                        in 200..299 -> Result.success(
                            connection.inputStream.bufferedReader().use { it.readText() }
                        )

                        400, 401, 403 -> Result.failure(AIError.InvalidKey(id))
                        429 -> Result.failure(
                            AIError.RateLimited(id, connection.retryAfterSeconds())
                        )

                        else -> Result.failure(AIError.Server(id, code))
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                Result.failure(AIError.Offline(id))
            } catch (e: Exception) {
                Result.failure(AIError.Malformed(id, e.message ?: "unreadable response"))
            }
        }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val MODEL = "gemini-2.5-flash"
    }
}
