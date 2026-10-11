package com.chalkboard.keyboard.ai

import android.content.Context
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.net.Connectivity
import com.chalkboard.keyboard.ui.Notice

/**
 * Which providers exist, which have keys, and which one the rewrite key uses.
 *
 * The keyboard asks this one question before drawing its layout: is there any usable
 * provider at all? If not, the rewrite key is left out of the layout entirely and the
 * suggestion strip reclaims the space — no disabled ghost button (section 2.6, B5).
 */
class ProviderRegistry(
    private val context: Context,
    private val keys: SecureKeyStore,
) {

    private val settings =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Every provider the app knows how to talk to, in the order settings lists them. */
    fun allProviders(): List<AIProvider> = listOf(
        GeminiProvider(),
        GroqProvider(),
        OpenAIProvider(),
        customProvider(),
    )

    private fun customProvider(): AIProvider = CustomEndpointProvider(
        baseUrl = settings.getString(KEY_CUSTOM_URL, "").orEmpty(),
        model = settings.getString(KEY_CUSTOM_MODEL, "").orEmpty(),
    )

    fun provider(id: String): AIProvider? = allProviders().firstOrNull { it.id == id }

    fun hasKey(providerId: String): Boolean = keys.hasKey(providerId)

    /** Providers with a stored key, in list order. */
    fun configured(): List<AIProvider> = allProviders().filter { keys.hasKey(it.id) }

    /** True when the rewrite key should appear on the keyboard at all. */
    fun isRewriteAvailable(): Boolean = configured().isNotEmpty()

    /**
     * The provider the rewrite key uses: the user's choice if it still has a key,
     * otherwise the first configured one, so removing a key cannot leave the keyboard
     * pointing at a provider that no longer works.
     */
    fun active(): AIProvider? {
        val chosen = settings.getString(KEY_ACTIVE, null)
        return configured().firstOrNull { it.id == chosen } ?: configured().firstOrNull()
    }

    fun setActive(providerId: String) {
        settings.edit().putString(KEY_ACTIVE, providerId).apply()
    }

    fun setCustomEndpoint(baseUrl: String, model: String) {
        settings.edit()
            .putString(KEY_CUSTOM_URL, baseUrl.trim())
            .putString(KEY_CUSTOM_MODEL, model.trim())
            .apply()
    }

    fun customEndpoint(): Pair<String, String> =
        settings.getString(KEY_CUSTOM_URL, "").orEmpty() to
            settings.getString(KEY_CUSTOM_MODEL, "").orEmpty()

    /**
     * The last four characters of a stored key, for the masked line in section D3.
     *
     * Written when the key is stored and kept in plain preferences on purpose: it is not
     * secret, and holding it here means the settings screens can identify which key is
     * which without ever asking the key store to decrypt one.
     */
    fun keyHint(providerId: String): String? =
        settings.getString(hintKeyFor(providerId), null)?.takeIf { it.isNotEmpty() }

    /** Validates then stores. A key that does not work is never saved. */
    suspend fun addKey(providerId: String, apiKey: String): Result<Unit> {
        val provider = provider(providerId) ?: return Result.failure(AIError.NoProvider)
        val trimmed = apiKey.trim()
        return provider.validateKey(trimmed).onSuccess {
            keys.store(providerId, trimmed)
            settings.edit().putString(hintKeyFor(providerId), trimmed.takeLast(KEY_HINT_LENGTH)).apply()
            clearFailure(providerId)
            if (settings.getString(KEY_ACTIVE, null) == null) setActive(providerId)
        }.onFailure { error ->
            recordFailure(providerId, error)
        }
    }

    fun removeKey(providerId: String) {
        keys.remove(providerId)
        val edit = settings.edit().remove(hintKeyFor(providerId))
        if (settings.getString(KEY_ACTIVE, null) == providerId) edit.remove(KEY_ACTIVE)
        edit.apply()
        clearFailure(providerId)
    }

    /** Rewrites [text] with the active provider. */
    suspend fun rewrite(text: String): Result<String> {
        val provider = active() ?: return Result.failure(AIError.NoProvider)
        val key = keys.keyFor(provider.id)
            ?: return Result.failure(AIError.InvalidKey(provider.id))
        return provider.improveText(text, key)
            .onSuccess { clearFailure(provider.id) }
            .onFailure { error -> recordFailure(provider.id, error) }
    }

    // --- Failure memory -----------------------------------------------------------

    /**
     * Remembers why a provider last failed, so the state survives leaving the keyboard
     * and shows up against the right card in settings (sections E2 and E3). A key that
     * was accepted when it was added and is rejected weeks later is a fact about that
     * provider, not a transient toast the user has to catch as it goes past.
     */
    private fun recordFailure(providerId: String, error: Throwable) {
        val kind = when (error) {
            is AIError.InvalidKey -> FAILURE_INVALID_KEY
            is AIError.RateLimited -> FAILURE_RATE_LIMITED
            is AIError.Server -> FAILURE_SERVER
            is AIError.Offline -> FAILURE_OFFLINE
            is AIError.Malformed -> FAILURE_MALFORMED
            else -> return
        }
        settings.edit()
            .putString(failureKeyFor(providerId), kind)
            .putLong(failureAtKeyFor(providerId), System.currentTimeMillis())
            .putInt(
                failureRetryKeyFor(providerId),
                (error as? AIError.RateLimited)?.retryAfterSeconds ?: 0,
            )
            .apply()
    }

    private fun clearFailure(providerId: String) {
        settings.edit()
            .remove(failureKeyFor(providerId))
            .remove(failureAtKeyFor(providerId))
            .remove(failureRetryKeyFor(providerId))
            .apply()
    }

    /**
     * What to show against this provider's card, or null when it is simply working.
     *
     * A rate limit expires on its own: once the wait the provider asked for has passed
     * there is nothing left to report, so the notice disappears without the user having
     * to dismiss a warning about something that is no longer true.
     */
    fun lastFailure(providerId: String): Notice? {
        val kind = settings.getString(failureKeyFor(providerId), null) ?: return null
        val name = displayName(providerId)

        if (kind == FAILURE_RATE_LIMITED) {
            val remaining = remainingRateLimitSeconds(providerId)
            if (remaining == EXPIRED) {
                clearFailure(providerId)
                return null
            }
            return rateLimitNotice(name, remaining)
        }

        return when (kind) {
            FAILURE_INVALID_KEY -> Notice(
                titleRes = R.string.notice_ai_invalid_key_title,
                bodyRes = R.string.notice_ai_invalid_key_body,
                severity = Notice.Severity.FAILURE,
                bodyArgs = listOf(name),
            )

            FAILURE_SERVER -> Notice(
                titleRes = R.string.notice_ai_server_title,
                bodyRes = R.string.notice_ai_server_body,
                severity = Notice.Severity.FAILURE,
                titleArgs = listOf(name),
            )

            FAILURE_OFFLINE -> offlineNotice()

            else -> Notice(
                titleRes = R.string.notice_ai_unreadable_title,
                bodyRes = R.string.notice_ai_unreadable_body,
                severity = Notice.Severity.FAILURE,
                titleArgs = listOf(name),
            )
        }
    }

    /**
     * Seconds left on a recorded rate limit: null when the provider never said how long,
     * [EXPIRED] once the wait has passed.
     *
     * A provider that sends a 429 without a `Retry-After` still gets an expiry, from
     * [UNSTATED_RATE_LIMIT_SECONDS] — otherwise the card would go on warning about a
     * limit that lifted hours ago, until the user happened to try again.
     */
    private fun remainingRateLimitSeconds(providerId: String): Int? {
        val recordedAt = settings.getLong(failureAtKeyFor(providerId), 0L)
        if (recordedAt <= 0L) return null
        val elapsed = (System.currentTimeMillis() - recordedAt) / 1_000L

        val requested = settings.getInt(failureRetryKeyFor(providerId), 0)
        if (requested <= 0) {
            return if (elapsed >= UNSTATED_RATE_LIMIT_SECONDS) EXPIRED else null
        }

        val remaining = requested - elapsed
        return if (remaining <= 0L) EXPIRED else remaining.toInt()
    }

    // --- User-facing copy ---------------------------------------------------------

    /**
     * What to say about a failure. Names the provider, states what happened and what
     * still works — and never retries a rate limit in a loop (section 2.5).
     */
    fun noticeFor(error: Throwable): Notice = when (error) {
        is AIError.InvalidKey -> Notice(
            titleRes = R.string.notice_ai_invalid_key_title,
            bodyRes = R.string.notice_ai_invalid_key_body,
            severity = Notice.Severity.FAILURE,
            bodyArgs = listOf(displayName(error.provider)),
        )

        is AIError.RateLimited ->
            rateLimitNotice(displayName(error.provider), error.retryAfterSeconds)

        // Unreachable host and no network at all look identical from a socket, and the
        // right message for each is the opposite of the other's — see Connectivity.
        is AIError.Offline ->
            if (Connectivity.hasNetwork(context)) {
                Notice(
                    titleRes = R.string.notice_ai_server_title,
                    bodyRes = R.string.notice_ai_server_body,
                    severity = Notice.Severity.FAILURE,
                    titleArgs = listOf(displayName(error.provider)),
                )
            } else {
                offlineNotice()
            }

        is AIError.Server -> Notice(
            titleRes = R.string.notice_ai_server_title,
            bodyRes = R.string.notice_ai_server_body,
            severity = Notice.Severity.FAILURE,
            titleArgs = listOf(displayName(error.provider)),
        )

        AIError.NoProvider -> Notice(
            titleRes = R.string.notice_ai_none_title,
            bodyRes = R.string.notice_ai_none_body,
            severity = Notice.Severity.NEUTRAL,
        )

        is AIError.Malformed -> Notice(
            titleRes = R.string.notice_ai_unreadable_title,
            bodyRes = R.string.notice_ai_unreadable_body,
            severity = Notice.Severity.FAILURE,
            titleArgs = listOf(displayName(error.provider)),
        )

        // Not an AIError at all — nothing is known about who failed or why, so the copy
        // claims nothing beyond the one thing that is certain: the text is untouched.
        else -> Notice(
            titleRes = R.string.notice_ai_unknown_title,
            bodyRes = R.string.notice_ai_unknown_body,
            severity = Notice.Severity.FAILURE,
        )
    }

    private fun offlineNotice() = Notice(
        titleRes = R.string.notice_offline_title,
        bodyRes = R.string.notice_offline_ai_body,
        severity = Notice.Severity.NEUTRAL,
    )

    private fun rateLimitNotice(providerName: String, retryAfterSeconds: Int?): Notice {
        val wait = retryAfterSeconds?.let(::formatDuration)
        return Notice(
            titleRes = if (wait == null) {
                R.string.notice_ai_rate_limited_title
            } else {
                R.string.notice_ai_rate_limited_title_retry
            },
            bodyRes = R.string.notice_ai_rate_limited_body,
            severity = Notice.Severity.FAILURE,
            titleArgs = listOfNotNull(wait),
            bodyArgs = listOf(providerName),
        )
    }

    /** "45 sec" under a minute, "2 min" above it, always rounded up so it is never early. */
    private fun formatDuration(seconds: Int): String =
        if (seconds < SECONDS_PER_MINUTE) {
            context.resources.getQuantityString(R.plurals.duration_seconds, seconds, seconds)
        } else {
            val minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
            context.resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
        }

    private fun displayName(providerId: String): String =
        provider(providerId)?.displayName ?: providerId

    private fun hintKeyFor(providerId: String) = "key_hint_$providerId"
    private fun failureKeyFor(providerId: String) = "failure_$providerId"
    private fun failureAtKeyFor(providerId: String) = "failure_at_$providerId"
    private fun failureRetryKeyFor(providerId: String) = "failure_retry_$providerId"

    companion object {
        private const val PREFS_NAME = "ai_providers"
        private const val KEY_ACTIVE = "active_provider"
        private const val KEY_CUSTOM_URL = "custom_base_url"
        private const val KEY_CUSTOM_MODEL = "custom_model"

        private const val KEY_HINT_LENGTH = 4
        private const val SECONDS_PER_MINUTE = 60

        /** [remainingRateLimitSeconds] result meaning the wait is over. */
        private const val EXPIRED = 0

        /** How long a 429 with no `Retry-After` is assumed to last. Fifteen minutes. */
        private const val UNSTATED_RATE_LIMIT_SECONDS = 15L * SECONDS_PER_MINUTE

        private const val FAILURE_INVALID_KEY = "invalid_key"
        private const val FAILURE_RATE_LIMITED = "rate_limited"
        private const val FAILURE_SERVER = "server"
        private const val FAILURE_OFFLINE = "offline"
        private const val FAILURE_MALFORMED = "malformed"

        fun create(context: Context): ProviderRegistry =
            ProviderRegistry(context.applicationContext, SecureKeyStore.create(context))
    }
}
