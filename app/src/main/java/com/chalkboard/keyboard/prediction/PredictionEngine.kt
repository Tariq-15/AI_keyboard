package com.chalkboard.keyboard.prediction

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

/**
 * Combines the bundled corpus with what this user actually types.
 *
 * The two sources are not comparable in raw units — corpus frequencies run to the
 * millions, a personal count of 4 is already meaningful — so each source is normalised
 * against its own maximum and then weighted. A word the user types often should outrank
 * a word that is merely common in English, which is what [PERSONAL_WEIGHT] buys.
 *
 * [predict] is synchronous and allocation-light so it can be measured; the IME calls it
 * off the main thread anyway, since it touches SQLite.
 */
class PredictionEngine(
    private val dictionary: NgramDictionary?,
    private val personal: PersonalLearningStore,
    /** Where the last decay timestamp is kept. Null in tests, which skips maintenance. */
    private val prefs: SharedPreferences? = null,
) {

    /**
     * Top candidates for the current caret position.
     *
     * @param context the last committed words, oldest first. Use
     *   [NgramDictionary.SENTENCE_START] for the start of a sentence.
     * @param prefix the partial word being typed, or empty between words.
     */
    fun predict(context: List<String>, prefix: String = "", limit: Int = 3): List<String> {
        val normalizedPrefix = prefix.lowercase()
        val scores = HashMap<String, Float>(limit * 8)

        dictionary?.lookup(context, normalizedPrefix, limit = CANDIDATE_POOL)?.let { corpus ->
            val max = corpus.maxOfOrNull { it.score }?.toFloat() ?: 0f
            if (max > 0f) {
                corpus.forEach { candidate ->
                    scores.merge(candidate.word, candidate.score / max) { a, b -> maxOf(a, b) }
                }
            }
        }

        val previous = context.lastOrNull() ?: NgramDictionary.SENTENCE_START
        val learned = personal.lookup(previous, normalizedPrefix, limit = CANDIDATE_POOL)
            .ifEmpty { personal.lookupByPrefix(normalizedPrefix, limit = CANDIDATE_POOL) }
        val personalMax = learned.maxOfOrNull { it.count }?.toFloat() ?: 0f
        if (personalMax > 0f) {
            learned.forEach { entry ->
                val score = PERSONAL_WEIGHT * (entry.count / personalMax)
                scores.merge(entry.word, score) { a, b -> a + b }
            }
        }

        // The word already typed in full is not a useful suggestion.
        scores.remove(normalizedPrefix)

        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }

    /**
     * Runs the personal store's decay pass at most once a day. Called once when the IME
     * starts, on the prediction thread — never on the typing path.
     */
    fun maintenance() {
        val store = prefs ?: return
        val now = System.currentTimeMillis()
        if (now - store.getLong(KEY_LAST_DECAY, 0L) < DECAY_INTERVAL_MS) return
        personal.decay()
        store.edit().putLong(KEY_LAST_DECAY, now).apply()
    }

    /**
     * Builds a grammar service that shares this engine's dictionary and personal store,
     * so the on-device spell tier costs no extra memory.
     */
    fun newGrammarService(context: Context): com.chalkboard.keyboard.grammar.GrammarService =
        com.chalkboard.keyboard.grammar.GrammarService.create(context, dictionary, personal)

    /** Records a committed word so the personal layer learns from it. */
    fun learn(word: String, previous: String) {
        if (word.isBlank() || !word.all { it.isLetter() || it == '\'' }) return
        personal.record(word, previous)
    }

    companion object {
        /**
         * How much a fully-familiar personal word outweighs the most likely corpus
         * candidate. Above ~2 the personal layer dominates as soon as it has any data,
         * which is the intended behaviour: this is the user's own vocabulary.
         */
        private const val PERSONAL_WEIGHT = 2.5f

        /** Pool pulled from each source before merging and truncating to `limit`. */
        private const val CANDIDATE_POOL = 16

        private const val PREFS_NAME = "prediction"
        private const val KEY_LAST_DECAY = "last_decay"
        private val DECAY_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)

        fun create(context: Context): PredictionEngine = PredictionEngine(
            dictionary = NgramDictionary.load(context),
            personal = PersonalLearningStore(context),
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
}
