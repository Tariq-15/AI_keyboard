package com.chalkboard.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLayoutsTest {

    private fun KeyboardLayout.characters(): List<String> =
        rows.flatMap { it.keys }.mapNotNull { (it.action as? KeyAction.Character)?.text }

    @Test
    fun `number row adds one row of digits above the letters`() {
        val without = KeyLayouts.qwerty(numberRow = false)
        val with = KeyLayouts.qwerty(numberRow = true)

        assertEquals(without.rowCount + 1, with.rowCount)
        assertEquals("1234567890", with.rows.first().keys.joinToString("") { it.label.orEmpty() })
    }

    @Test
    fun `every letter has a hint and no hint is offered twice`() {
        for (numberRow in listOf(false, true)) {
            val letters = KeyLayouts.qwerty(numberRow).rows.flatMap { it.keys }
                .filter { key -> key.label?.singleOrNull()?.isLetter() == true }
            val hints = letters.map { it.longPressText }

            assertEquals(26, letters.size)
            assertTrue("a letter has no hint (numberRow=$numberRow)", hints.none { it == null })
            assertEquals("duplicate hint (numberRow=$numberRow)", hints.size, hints.distinct().size)
        }
    }

    @Test
    fun `a web address can be typed without the second symbols page`() {
        // The first build had no slash and no underscore anywhere on the keyboard.
        val firstPage = KeyLayouts.symbolsPage().characters()
        assertTrue("/" in firstPage)
        assertTrue("_" in firstPage)
    }

    @Test
    fun `the bottom row holds the same keys on every page`() {
        val bottoms = KeyboardPage.entries.map { page ->
            KeyLayouts.forPage(page, numberRow = true).rows.last().keys.drop(1).map { it.action }
        }
        assertEquals(1, bottoms.distinct().size)
    }
}
