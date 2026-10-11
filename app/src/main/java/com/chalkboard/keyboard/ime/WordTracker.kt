package com.chalkboard.keyboard.ime

import com.chalkboard.keyboard.prediction.NgramDictionary

/**
 * Tracks the word being typed and the two words before it.
 *
 * Kept separate from the IME because it is pure logic with no Android dependency, which
 * makes the edge cases — punctuation ending a sentence, backspacing into a finished
 * word, committing a suggestion — testable without a device.
 *
 * Word characters are letters and the apostrophe, matching the tokeniser that built the
 * dictionary (`tools/build_dictionary.py`); anything else ends the current word.
 */
class WordTracker {

    /** The partial word at the caret. Empty between words. */
    var current: String = ""
        private set

    private val history = ArrayDeque<String>()

    /** The last two committed words, oldest first, padded with sentence-start markers. */
    val context: List<String>
        get() = when (history.size) {
            0 -> listOf(NgramDictionary.SENTENCE_START, NgramDictionary.SENTENCE_START)
            1 -> listOf(NgramDictionary.SENTENCE_START, history.last())
            else -> history.toList().takeLast(2)
        }

    /**
     * A word the user just finished, together with the word that preceded it. Both are
     * needed to learn a pair, and [previous] must be captured before the completed word
     * enters the history — otherwise a word ends up recorded as following itself.
     */
    data class Completion(val word: String, val previous: String)

    /**
     * Feeds committed text through the tracker.
     *
     * @return the word completed by this input, or null if none was. The IME passes it
     *   to [com.chalkboard.keyboard.prediction.PredictionEngine.learn].
     */
    fun onTextCommitted(text: String): Completion? {
        var completed: Completion? = null
        for (char in text) {
            if (isWordChar(char)) {
                current += char
            } else {
                completed = finishWord() ?: completed
                if (isSentenceEnd(char)) history.clear()
            }
        }
        return completed
    }

    /**
     * Handles one backspace. Only the partial word is tracked precisely; deleting back
     * into already-committed text resets the context rather than guessing at it, since
     * the IME cannot cheaply reconstruct what was there.
     */
    fun onBackspace() {
        if (current.isNotEmpty()) {
            current = current.dropLast(1)
        } else {
            history.clear()
        }
    }

    /** Replaces the partial word with a chosen suggestion and commits it. */
    fun onSuggestionAccepted(word: String): Completion? {
        current = word
        return finishWord()
    }

    /** Drops all state, e.g. when the input field changes or the caret is moved. */
    fun reset() {
        current = ""
        history.clear()
    }

    /**
     * Rebuilds state from the text preceding the caret. Used when the IME attaches to a
     * field that already has content, so the first suggestion is not made blind.
     */
    fun resetFrom(textBeforeCursor: CharSequence?) {
        reset()
        val text = textBeforeCursor?.toString() ?: return
        val sentence = text.takeLastWhile { !isSentenceEnd(it) }
        val words = sentence.split(*NON_WORD_SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty()) return

        val endsMidWord = sentence.lastOrNull()?.let { isWordChar(it) } == true
        val committed = if (endsMidWord) words.dropLast(1) else words
        committed.takeLast(2).forEach { history.addLast(it) }
        current = if (endsMidWord) words.last() else ""
    }

    private fun finishWord(): Completion? {
        if (current.isEmpty()) return null
        val word = current
        val previous = history.lastOrNull() ?: NgramDictionary.SENTENCE_START
        current = ""
        history.addLast(word)
        while (history.size > MAX_HISTORY) history.removeFirst()
        return Completion(word, previous)
    }

    private fun isWordChar(char: Char): Boolean = char.isLetter() || char == '\''

    private fun isSentenceEnd(char: Char): Boolean = char in ".!?\n"

    private companion object {
        const val MAX_HISTORY = 2
        val NON_WORD_SPLIT = charArrayOf(
            ' ', '\t', ',', ';', ':', '"', '(', ')', '[', ']', '{', '}', '-', '/', '\\',
        )
    }
}
