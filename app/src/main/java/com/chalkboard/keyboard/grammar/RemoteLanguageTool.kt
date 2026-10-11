package com.chalkboard.keyboard.grammar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * Full grammar checking against a self-hosted LanguageTool instance.
 *
 * Exactly one request shape is used: `POST /v2/check` with `text` and `language`, which
 * is the only endpoint the deployment exposes (see `backend/README.md`). The text being
 * checked is the only thing sent — no identifiers, no device information, no history —
 * and it is sent only when the user taps the grammar key.
 *
 * Deliberately built on [HttpURLConnection] rather than an HTTP client library: this
 * runs inside a keyboard process that must start fast, and one POST does not justify
 * the dependency.
 */
class RemoteLanguageTool(
    private val baseUrl: String,
    private val language: String = "en-US",
) : GrammarChecker {

    override val source: GrammarSource = GrammarSource.SERVER

    override suspend fun isAvailable(): Boolean =
        check("").isSuccess

    override suspend fun check(text: String): Result<List<Correction>> =
        withContext(Dispatchers.IO) {
            try {
                val body = buildString {
                    append("text=").append(URLEncoder.encode(text, "UTF-8"))
                    append("&language=").append(URLEncoder.encode(language, "UTF-8"))
                }
                val connection = (URL("$baseUrl/v2/check").openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = "POST"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        doOutput = true
                        setRequestProperty(
                            "Content-Type",
                            "application/x-www-form-urlencoded; charset=UTF-8",
                        )
                        setRequestProperty("Accept", "application/json")
                    }

                try {
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        return@withContext Result.failure(GrammarError.Server(code))
                    }
                    val payload = connection.inputStream.bufferedReader().use { it.readText() }
                    Result.success(parse(payload, text))
                } finally {
                    connection.disconnect()
                }
            } catch (e: SocketTimeoutException) {
                Result.failure(GrammarError.Timeout)
            } catch (e: IOException) {
                Result.failure(GrammarError.Offline)
            } catch (e: Exception) {
                Result.failure(GrammarError.Malformed(e.message ?: "unreadable response"))
            }
        }

    /**
     * Turns LanguageTool's `matches` array into [Correction]s.
     *
     * Matches with no suggested replacement are dropped: this keyboard applies fixes
     * rather than annotating problems, so a match we cannot act on is noise.
     */
    internal fun parse(payload: String, text: String): List<Correction> {
        val matches = JSONObject(payload).optJSONArray("matches") ?: return emptyList()
        return buildList {
            for (i in 0 until matches.length()) {
                val match = matches.optJSONObject(i) ?: continue
                val replacements = match.optJSONArray("replacements") ?: continue
                val replacement = replacements.optJSONObject(0)?.optString("value").orEmpty()
                if (replacement.isEmpty()) continue

                val offset = match.optInt("offset", -1)
                val length = match.optInt("length", 0)
                if (offset < 0 || length <= 0 || offset + length > text.length) continue

                add(
                    Correction(
                        range = offset until (offset + length),
                        original = text.substring(offset, offset + length),
                        replacement = replacement,
                        ruleId = match.optJSONObject("rule")?.optString("id").orEmpty(),
                        message = match.optString("message"),
                    )
                )
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 8_000
    }
}
