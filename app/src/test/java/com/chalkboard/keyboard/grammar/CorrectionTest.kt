package com.chalkboard.keyboard.grammar

import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionTest {

    private fun correction(range: IntRange, original: String, replacement: String) =
        Correction(range, original, replacement, ruleId = "TEST")

    @Test
    fun `applies a single correction`() {
        val text = "I hav a cat"
        val result = applyCorrections(text, listOf(correction(2..4, "hav", "have")))
        assertEquals("I have a cat", result)
    }

    @Test
    fun `applies several corrections without shifting each other's offsets`() {
        val text = "teh cat sat onn teh mat"
        val result = applyCorrections(
            text,
            listOf(
                correction(0..2, "teh", "the"),
                correction(12..14, "onn", "on"),
                correction(16..18, "teh", "the"),
            ),
        )
        assertEquals("the cat sat on the mat", result)
    }

    @Test
    fun `applies corrections given out of order`() {
        val text = "teh cat sat onn"
        val result = applyCorrections(
            text,
            listOf(correction(12..14, "onn", "on"), correction(0..2, "teh", "the")),
        )
        assertEquals("the cat sat on", result)
    }

    @Test
    fun `handles replacements of a different length than the original`() {
        val text = "its a dog"
        val result = applyCorrections(text, listOf(correction(0..2, "its", "it is")))
        assertEquals("it is a dog", result)
    }

    @Test
    fun `ignores no-op corrections`() {
        val text = "already fine"
        val result = applyCorrections(text, listOf(correction(0..6, "already", "already")))
        assertEquals(text, result)
    }

    @Test
    fun `empty correction list leaves the text alone`() {
        assertEquals("untouched", applyCorrections("untouched", emptyList()))
    }
}
