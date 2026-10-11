package com.chalkboard.keyboard.ime

import com.chalkboard.keyboard.prediction.NgramDictionary.Companion.SENTENCE_START
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordTrackerTest {

    private val tracker = WordTracker()

    private fun type(text: String) = text.map { tracker.onTextCommitted(it.toString()) }

    @Test
    fun `accumulates the partial word`() {
        type("hel")
        assertEquals("hel", tracker.current)
        assertEquals(listOf(SENTENCE_START, SENTENCE_START), tracker.context)
    }

    @Test
    fun `a space completes the word and moves it into context`() {
        type("hello ")
        assertEquals("", tracker.current)
        assertEquals(listOf(SENTENCE_START, "hello"), tracker.context)
    }

    @Test
    fun `context keeps only the last two words`() {
        type("one two three ")
        assertEquals(listOf("two", "three"), tracker.context)
    }

    @Test
    fun `completion reports the preceding word, not itself`() {
        type("hello ")
        val completion = tracker.onTextCommitted("world ")
        assertEquals("world", completion?.word)
        assertEquals("hello", completion?.previous)
    }

    @Test
    fun `the first word of a sentence follows the start marker`() {
        val completion = tracker.onTextCommitted("hello ")
        assertEquals(SENTENCE_START, completion?.previous)
    }

    @Test
    fun `a full stop starts a new sentence`() {
        type("one two. ")
        assertEquals(listOf(SENTENCE_START, SENTENCE_START), tracker.context)
        assertEquals("", tracker.current)
    }

    @Test
    fun `apostrophes stay inside a word`() {
        type("don't ")
        assertEquals(listOf(SENTENCE_START, "don't"), tracker.context)
    }

    @Test
    fun `backspace shortens the partial word`() {
        type("hello")
        tracker.onBackspace()
        assertEquals("hell", tracker.current)
    }

    @Test
    fun `backspace past the partial word drops context rather than guessing`() {
        type("hello ")
        tracker.onBackspace()
        assertEquals(listOf(SENTENCE_START, SENTENCE_START), tracker.context)
    }

    @Test
    fun `no completion is reported when nothing was in progress`() {
        assertNull(tracker.onTextCommitted(" "))
    }

    @Test
    fun `accepting a suggestion completes it`() {
        type("wor")
        val completion = tracker.onSuggestionAccepted("world")
        assertEquals("world", completion?.word)
        assertEquals("", tracker.current)
        assertEquals(listOf(SENTENCE_START, "world"), tracker.context)
    }

    @Test
    fun `seeds from existing field text`() {
        tracker.resetFrom("I went to the sto")
        assertEquals("sto", tracker.current)
        assertEquals(listOf("to", "the"), tracker.context)
    }

    @Test
    fun `seeding after a space leaves no partial word`() {
        tracker.resetFrom("I went to the ")
        assertEquals("", tracker.current)
        assertEquals(listOf("to", "the"), tracker.context)
    }

    @Test
    fun `seeding starts fresh after a sentence boundary`() {
        tracker.resetFrom("That was fine. ")
        assertEquals("", tracker.current)
        assertEquals(listOf(SENTENCE_START, SENTENCE_START), tracker.context)
    }
}
