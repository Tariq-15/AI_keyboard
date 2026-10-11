package com.chalkboard.keyboard.grammar

/**
 * One proposed change to a span of text.
 *
 * [range] indexes the text that was checked, so corrections can be applied back-to-front
 * without invalidating each other's offsets.
 */
data class Correction(
    val range: IntRange,
    val original: String,
    val replacement: String,
    /** Rule that fired, e.g. "MORFOLOGIK_RULE_EN_US" or "SPELL_UNKNOWN". Shown to nobody; used for logging and tests. */
    val ruleId: String,
    /** Short explanation from the checker, if it gave one. */
    val message: String = "",
) {
    val isNoOp: Boolean get() = original == replacement
}

/** Where corrections are currently coming from. Surfaced in settings, never silently switched. */
enum class GrammarSource {
    /** Bundled dictionary; spelling only, no network. */
    ON_DEVICE,

    /** Self-hosted LanguageTool; full grammar. Requires network. */
    SERVER,
}

/** How far the grammar key is allowed to go. Switched by a long press on the key. */
enum class GrammarMode {
    /** Fix what is wrong and nothing else: no added punctuation, no changed casing. */
    CASUAL,

    /** Also tidy capitalisation, punctuation and chat shorthand. */
    FORMAL,
}

/**
 * Applies corrections to [text]. Back-to-front so earlier offsets stay valid.
 */
fun applyCorrections(text: String, corrections: List<Correction>): String {
    if (corrections.isEmpty()) return text
    val builder = StringBuilder(text)
    corrections
        .filterNot { it.isNoOp }
        .sortedByDescending { it.range.first }
        .forEach { correction ->
            val end = (correction.range.last + 1).coerceAtMost(builder.length)
            val start = correction.range.first.coerceIn(0, end)
            builder.replace(start, end, correction.replacement)
        }
    return builder.toString()
}
