package com.chalkboard.keyboard.grammar

/**
 * Anything that can propose corrections for a span of text.
 *
 * Checks run only when the user taps the grammar key — never per keystroke. That is a
 * privacy property as much as a battery one: for the server-backed implementation it is
 * the difference between sending a sentence on request and continuously transmitting
 * everything typed. Nothing in this package may be wired to a text-changed callback.
 */
interface GrammarChecker {

    val source: GrammarSource

    /** True when this checker can run right now — bundled data present, server reachable. */
    suspend fun isAvailable(): Boolean

    /**
     * Corrections for [text], with offsets into [text]. Returns an empty list when the
     * text is already correct; failures are reported as [Result.failure].
     */
    suspend fun check(text: String): Result<List<Correction>>
}

/** Why a check could not run. Mapped to user-facing copy in [GrammarService]. */
sealed class GrammarError : Exception() {
    /** No route to the configured server. */
    data object Offline : GrammarError()

    /** Server answered, but not with a result. */
    data class Server(val code: Int) : GrammarError()

    /** Server took too long. */
    data object Timeout : GrammarError()

    /** Response was not in the shape we expect. */
    data class Malformed(override val message: String) : GrammarError()
}
