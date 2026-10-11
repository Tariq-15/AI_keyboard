package com.chalkboard.keyboard.grammar

import com.chalkboard.keyboard.prediction.NgramDictionary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Runs the offline spell tier against the real bundled dictionary, on the JVM.
 * The personal store is absent here (it needs Android SQLite), which is the same state
 * the checker is in on a fresh install.
 */
class OnDeviceSpellCheckerTest {

    companion object {
        private var dictionary: NgramDictionary? = null

        @BeforeClass
        @JvmStatic
        fun mapDictionary() {
            val file = File("src/main/res/raw/ngram_dictionary.bin")
            if (!file.exists()) return
            val channel = RandomAccessFile(file, "r").channel
            dictionary = NgramDictionary.fromBuffer(
                channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            )
        }
    }

    private fun checker(): OnDeviceSpellChecker {
        assumeTrue("dictionary not built", dictionary != null)
        return OnDeviceSpellChecker(dictionary, personal = null)
    }

    private fun correct(text: String): String = runBlocking {
        val corrections = checker().check(text).getOrThrow()
        applyCorrections(text, corrections)
    }

    private fun correctWithHistory(text: String, history: Map<String, Int>): String =
        runBlocking {
            assumeTrue("dictionary not built", dictionary != null)
            val checker = OnDeviceSpellChecker(dictionary) { word -> history[word] ?: 0 }
            applyCorrections(text, checker.check(text).getOrThrow())
        }

    @Test
    fun `typing a typo once does not make it a known word`() {
        // The personal store learns every committed word, including the typo the user is
        // about to ask to have fixed. Found on-device: the keyboard reported "nothing to
        // correct" for a misspelling it had just learned from that very sentence.
        assertEquals(
            "I have the cat",
            correctWithHistory("I have teh cat", mapOf("teh" to 1)),
        )
    }

    @Test
    fun `a word used repeatedly is accepted as deliberate`() {
        // Names, jargon and intentional spellings must survive.
        val text = "ping zliska now"
        assertEquals(text, correctWithHistory(text, mapOf("zliska" to 3)))
    }

    @Test
    fun `corrects a transposition`() {
        assertEquals("I have the cat", correct("I have teh cat"))
    }

    @Test
    fun `corrects a doubled letter`() {
        assertEquals("the cat sat on the mat", correct("the cat sat onn the mat"))
    }

    @Test
    fun `corrects a missing letter`() {
        assertEquals("I want to know", correct("I want to kow"))
    }

    @Test
    fun `leaves correct text alone`() {
        val text = "the quick brown fox jumps over the lazy dog"
        assertEquals(text, correct(text))
    }

    @Test
    fun `preserves capitalisation of the original word`() {
        assertEquals("The cat", correct("Teh cat"))
    }

    @Test
    fun `ignores acronyms`() {
        // All-caps is far more likely an acronym than a misspelling.
        assertEquals("send the PDF now", correct("send the PDF now"))
    }

    @Test
    fun `ignores words containing digits`() {
        assertEquals("build 35a is ready", correct("build 35a is ready"))
    }

    @Test
    fun `ignores very short words`() {
        // Two-letter strings have too many edit-1 neighbours to guess safely.
        assertEquals("go xy now", correct("go xy now"))
    }

    @Test
    fun `leaves a word alone when nothing plausible is within one edit`() {
        val text = "the zxqwvb device"
        assertEquals(text, correct(text))
    }

    @Test
    fun `reports the span it wants to change`() = runBlocking {
        val corrections = checker().check("I have teh cat").getOrThrow()
        val correction = corrections.single()
        assertEquals("teh", correction.original)
        assertEquals(7..9, correction.range)
        assertEquals("SPELL_UNKNOWN", correction.ruleId)
    }

    @Test
    fun `handles punctuation around words`() {
        assertTrue(correct("hello, teh world!").contains("the"))
    }

    @Test
    fun `keeps apostrophes inside words`() {
        val text = "I don't know"
        assertEquals(text, correct(text))
    }
}
