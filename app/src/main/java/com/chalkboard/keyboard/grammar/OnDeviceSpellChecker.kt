package com.chalkboard.keyboard.grammar

import com.chalkboard.keyboard.prediction.NgramDictionary

/**
 * Spelling correction with no network, built on the dictionary already bundled for
 * prediction.
 *
 * This is not a grammar checker and does not pretend to be one: it catches misspelled
 * words, not agreement or tense. Full grammar needs [RemoteLanguageTool]. The split is
 * deliberate — LanguageTool's Java library pulls gRPC, Netty and Lucene and does not
 * belong in a keyboard process, so the offline tier is the part that can genuinely be
 * done offline, and the user is told which tier produced a result.
 *
 * Candidates are edit-distance-1 variants (the four Damerau operations) filtered to
 * words that exist in the vocabulary, then ranked by how well they fit: a word the user
 * has typed before beats a word that merely follows the previous word, which beats a
 * word that is merely common.
 */
class OnDeviceSpellChecker(
    private val dictionary: NgramDictionary?,
    private val personal: PersonalWordCounts?,
) : GrammarChecker {

    /**
     * How often the user has committed a given word. An interface so the checker can be
     * tested against a known history without an Android database.
     */
    fun interface PersonalWordCounts {
        fun uses(word: String): Int
    }

    override val source: GrammarSource = GrammarSource.ON_DEVICE

    override suspend fun isAvailable(): Boolean = dictionary != null

    override suspend fun check(text: String): Result<List<Correction>> {
        val dictionary = this.dictionary
            ?: return Result.failure(GrammarError.Malformed("no bundled dictionary"))

        val corrections = mutableListOf<Correction>()
        var previous = NgramDictionary.SENTENCE_START

        for (token in tokenize(text)) {
            val word = token.text
            val lower = word.lowercase()

            // Skip anything we have no business judging: short words, words with digits,
            // ALL-CAPS (likely an acronym), words in the vocabulary, and words the user
            // has typed often enough for it to be deliberate.
            val judgeable = word.length >= MIN_LENGTH &&
                word.none { it.isDigit() } &&
                word != word.uppercase() &&
                !dictionary.isKnown(lower) &&
                personalUses(lower) < PERSONAL_TRUST_USES

            if (judgeable) {
                bestCandidate(dictionary, lower, previous)?.let { candidate ->
                    corrections += Correction(
                        range = token.range,
                        original = word,
                        replacement = matchCase(word, candidate),
                        ruleId = RULE_ID,
                        message = "Not in dictionary",
                    )
                }
            }
            previous = lower
        }
        return Result.success(corrections)
    }

    /**
     * How many times the user has committed [word].
     *
     * The personal store learns every word as it is typed, including the typo you are
     * about to ask to have fixed — so "the user has typed this" cannot on its own mean
     * "this is a real word". A single use is not evidence of intent; repeated use is,
     * and that is what protects names, jargon and deliberate spellings.
     */
    private fun personalUses(word: String): Int = personal?.uses(word) ?: 0

    private fun bestCandidate(
        dictionary: NgramDictionary,
        word: String,
        previous: String,
    ): String? =
        edits(word)
            .asSequence()
            .filter { dictionary.isKnown(it) }
            .distinct()
            .maxByOrNull { candidate -> score(dictionary, candidate, previous) }

    private fun score(dictionary: NgramDictionary, candidate: String, previous: String): Long {
        val unigram = dictionary.frequencyOf(candidate).toLong()
        val bigram = dictionary.bigramFrequency(previous, candidate).toLong()
        val personalUse = personalUses(candidate).toLong()
        // Fit with the sentence outweighs raw commonness; the user's own usage outweighs both.
        return personalUse * PERSONAL_WEIGHT + bigram * BIGRAM_WEIGHT + unigram
    }

    /** All edit-distance-1 variants: deletion, transposition, substitution, insertion. */
    private fun edits(word: String): List<String> {
        val result = ArrayList<String>(word.length * (2 * ALPHABET.length + 2))
        for (i in word.indices) {
            result += word.substring(0, i) + word.substring(i + 1)
            if (i < word.length - 1) {
                result += word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
            }
            for (c in ALPHABET) {
                result += word.substring(0, i) + c + word.substring(i + 1)
                result += word.substring(0, i) + c + word.substring(i)
            }
        }
        for (c in ALPHABET) result += word + c
        return result
    }

    /** Gives [candidate] the capitalisation pattern of [original]. */
    private fun matchCase(original: String, candidate: String): String = when {
        original.all { it.isUpperCase() } -> candidate.uppercase()
        original.firstOrNull()?.isUpperCase() == true ->
            candidate.replaceFirstChar { it.uppercase() }
        else -> candidate
    }

    private data class Token(val text: String, val range: IntRange)

    /** Words as the dictionary defines them: letters plus internal apostrophes. */
    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var start = -1
        text.forEachIndexed { index, char ->
            val isWordChar = char.isLetter() || (char == '\'' && start >= 0)
            if (isWordChar) {
                if (start < 0) start = index
            } else if (start >= 0) {
                tokens += Token(text.substring(start, index), start until index)
                start = -1
            }
        }
        if (start >= 0) tokens += Token(text.substring(start), start until text.length)
        return tokens.map { token ->
            // A trailing apostrophe belongs to the punctuation, not the word.
            if (token.text.endsWith("'")) {
                Token(token.text.dropLast(1), token.range.first until token.range.last)
            } else {
                token
            }
        }.filter { it.text.isNotEmpty() }
    }

    private companion object {
        const val RULE_ID = "SPELL_UNKNOWN"
        const val MIN_LENGTH = 3
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz'"
        const val PERSONAL_WEIGHT = 5_000L
        const val BIGRAM_WEIGHT = 250L

        /** Uses before a word the dictionary does not know is accepted as deliberate. */
        const val PERSONAL_TRUST_USES = 3
    }
}
