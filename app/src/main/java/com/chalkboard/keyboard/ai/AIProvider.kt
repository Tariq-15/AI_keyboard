package com.chalkboard.keyboard.ai

/**
 * A text-rewriting service the user has chosen and pays for themselves.
 *
 * Bring-your-own-key by design: the app ships with no keys, makes no requests until the
 * user adds one, and costs the app's owner nothing per user. A provider with no stored
 * key does not exist as far as the keyboard is concerned — the rewrite key is absent
 * from the layout entirely rather than shown disabled.
 */
interface AIProvider {

    /** Stable identifier, used as the key-store entry name. Never change these. */
    val id: String

    /** Name shown in settings, spelled as the vendor spells it. */
    val displayName: String

    /** Where the user gets a key. Shown in the add-key screen. */
    val keyUrl: String

    /** Rewrites [text], returning only the rewritten text. */
    suspend fun improveText(text: String, apiKey: String): Result<String>

    /** One cheap call to confirm the key works, made when the key is added. */
    suspend fun validateKey(apiKey: String): Result<Unit>

    companion object {
        /**
         * Sent to every provider, unchanged, so results are comparable between them.
         * Deliberately narrow: this rewrites what the user wrote, it does not answer
         * questions or continue their sentence.
         */
        const val SYSTEM_PROMPT =
            "Rewrite the following text to improve clarity and tone. " +
                "Return only the rewritten text, no explanation."
    }
}

/**
 * Failures worth telling the user apart.
 *
 * Every one that came from a request names the provider it came from, because the copy
 * says so out loud: "This key was rejected by OpenAI" is actionable in a way that
 * "Invalid API key" is not, and the user may have several providers configured.
 */
sealed class AIError : Exception() {
    /** Key rejected — 401/403. */
    data class InvalidKey(val provider: String) : AIError()

    /**
     * 429. Never retried automatically (section 2.5). [retryAfterSeconds] carries the
     * provider's own `Retry-After` when it sent one, so the wait can be stated rather
     * than left to the user to discover by trying again.
     */
    data class RateLimited(val provider: String, val retryAfterSeconds: Int? = null) : AIError()

    /** Any other non-2xx. */
    data class Server(val provider: String, val code: Int) : AIError()

    /** No route to the host. May be the provider or may be this device — see Connectivity. */
    data class Offline(val provider: String) : AIError()

    /** Reply arrived but was not in the expected shape. */
    data class Malformed(val provider: String, override val message: String) : AIError()

    /** Nothing is configured, so there is nowhere to send anything. */
    data object NoProvider : AIError()
}
