package com.chalkboard.keyboard.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Exercises the real bundled dictionary on the JVM — no device, no emulator.
 *
 * This is possible because [NgramDictionary] works against a plain ByteBuffer; on the
 * device that buffer is a mapping into the APK, here it is a mapping of the same file
 * on disk. The format and every lookup path are identical.
 */
class NgramDictionaryTest {

    companion object {
        private const val PATH = "src/main/res/raw/ngram_dictionary.bin"
        private var dictionary: NgramDictionary? = null

        @BeforeClass
        @JvmStatic
        fun mapDictionary() {
            val file = File(PATH)
            if (!file.exists()) return
            val channel = RandomAccessFile(file, "r").channel
            dictionary = NgramDictionary.fromBuffer(
                channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            )
        }
    }

    private fun dict(): NgramDictionary {
        // Skip rather than fail when the dictionary has not been generated yet; the
        // build step is documented in tools/build_dictionary.py.
        assumeTrue("dictionary not built", dictionary != null)
        return dictionary!!
    }

    @Test
    fun `resolves common words`() {
        val dictionary = dict()
        for (word in listOf("the", "keyboard", "tomorrow", "don't", NgramDictionary.SENTENCE_START)) {
            assertNotEquals("expected '$word' in the vocabulary", -1, dictionary.idOf(word))
        }
    }

    @Test
    fun `rejects words outside the vocabulary`() {
        assertEquals(-1, dict().idOf("zzzzqqqx"))
    }

    @Test
    fun `word ids round-trip`() {
        val dictionary = dict()
        for (word in listOf("a", "the", "keyboard", "yesterday")) {
            val id = dictionary.idOf(word)
            assertNotEquals(-1, id)
            assertEquals(word, dictionary.wordAt(id))
        }
    }

    @Test
    fun `predicts a continuation from bigram context`() {
        val candidates = dict().lookup(listOf("i", "am"))
        assertTrue("expected candidates after 'i am'", candidates.isNotEmpty())
    }

    @Test
    fun `restricts candidates to the typed prefix`() {
        val candidates = dict().lookup(listOf("i", "want"), prefix = "to")
        assertTrue("expected candidates for prefix 'to'", candidates.isNotEmpty())
        assertTrue(
            "every candidate must start with the prefix: $candidates",
            candidates.all { it.word.startsWith("to") },
        )
    }

    @Test
    fun `prefix search does not match words shorter than the prefix`() {
        // "to" must not be returned for the prefix "tom" — the boundary case that a
        // naive byte comparison gets wrong.
        val candidates = dict().lookup(emptyList(), prefix = "tom")
        assertTrue(candidates.none { it.word == "to" })
        assertTrue(candidates.all { it.word.length >= 3 })
    }

    @Test
    fun `returns cold-start candidates with no context and no prefix`() {
        assertTrue(dict().lookup(emptyList()).isNotEmpty())
    }

    @Test
    fun `never offers the sentence-start marker as a word`() {
        val dictionary = dict()
        // The marker is a legitimate continuation of itself in the corpus (the padding
        // produces the pair), so it reaches the suggestion strip unless filtered.
        val start = listOf(NgramDictionary.SENTENCE_START, NgramDictionary.SENTENCE_START)
        for (context in listOf(start, listOf(NgramDictionary.SENTENCE_START), emptyList())) {
            val candidates = dictionary.lookup(context, limit = 24)
            assertTrue(
                "marker leaked into $candidates",
                candidates.none { it.word == NgramDictionary.SENTENCE_START },
            )
        }
    }

    @Test
    fun `lookup stays well under a frame`() {
        val dictionary = dict()
        val contexts = listOf(
            listOf("i", "am") to "",
            listOf("i", "want") to "to",
            listOf("the") to "keyb",
            emptyList<String>() to "th",
        )
        // Warm the JIT and the page cache first; the budget is about steady-state typing.
        repeat(200) { contexts.forEach { (context, prefix) -> dictionary.lookup(context, prefix) } }

        val started = System.nanoTime()
        val iterations = 500
        repeat(iterations) {
            contexts.forEach { (context, prefix) -> dictionary.lookup(context, prefix) }
        }
        val perLookupMs = (System.nanoTime() - started) / 1_000_000.0 / (iterations * contexts.size)
        assertTrue("lookup averaged %.3f ms, budget is 16 ms".format(perLookupMs), perLookupMs < 16.0)
    }
}
