package com.chalkboard.keyboard.grammar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing only — no sockets. The response shape is LanguageTool's documented
 * `POST /v2/check` payload.
 *
 * `org.json` is stubbed to throw on Android unit tests unless `unitTests.isReturnDefault
 * Values` is set, so the build file enables it and these run against the real JSON
 * implementation on the classpath.
 */
class RemoteLanguageToolTest {

    private val checker = RemoteLanguageTool("https://example.invalid")

    @Test
    fun `parses a match into a correction`() {
        val text = "I has a cat"
        val payload = """
            {"matches":[{
              "message":"Possible agreement error",
              "offset":2,"length":3,
              "replacements":[{"value":"have"},{"value":"had"}],
              "rule":{"id":"HE_VERB_AGR"}
            }]}
        """.trimIndent()

        val corrections = checker.parse(payload, text)
        assertEquals(1, corrections.size)
        val correction = corrections.single()
        assertEquals(2..4, correction.range)
        assertEquals("has", correction.original)
        assertEquals("have", correction.replacement)
        assertEquals("HE_VERB_AGR", correction.ruleId)
        assertEquals("I have a cat", applyCorrections(text, corrections))
    }

    @Test
    fun `takes the first replacement when several are offered`() {
        val payload = """
            {"matches":[{"offset":0,"length":3,
             "replacements":[{"value":"the"},{"value":"ten"}],"rule":{"id":"X"}}]}
        """.trimIndent()
        assertEquals("the", checker.parse(payload, "teh cat").single().replacement)
    }

    @Test
    fun `drops matches with no replacement to offer`() {
        // This keyboard applies fixes rather than annotating problems, so a match it
        // cannot act on is noise.
        val payload = """
            {"matches":[{"offset":0,"length":3,"replacements":[],"rule":{"id":"X"}}]}
        """.trimIndent()
        assertTrue(checker.parse(payload, "teh cat").isEmpty())
    }

    @Test
    fun `drops matches whose offsets fall outside the text`() {
        val payload = """
            {"matches":[{"offset":50,"length":3,
             "replacements":[{"value":"the"}],"rule":{"id":"X"}}]}
        """.trimIndent()
        assertTrue(checker.parse(payload, "short").isEmpty())
    }

    @Test
    fun `a clean response yields no corrections`() {
        assertTrue(checker.parse("""{"matches":[]}""", "all fine").isEmpty())
    }

    @Test
    fun `a response without a matches array yields no corrections`() {
        assertTrue(checker.parse("""{"software":{"name":"LanguageTool"}}""", "text").isEmpty())
    }
}
