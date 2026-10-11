package com.chalkboard.keyboard.prediction

import android.content.Context
import android.util.Log
import com.chalkboard.keyboard.R
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The bundled n-gram model, read straight out of the APK.
 *
 * The file is memory-mapped rather than parsed: startup cost is a few page faults
 * instead of building a 40k-entry map, and the pages are file-backed so the kernel can
 * evict them under pressure. That matters here and matters more on iOS, where the
 * keyboard extension has a hard memory ceiling — the same format ports directly.
 *
 * This requires the resource to be stored uncompressed in the APK; see the
 * `androidResources.noCompress` entry in `app/build.gradle.kts`.
 *
 * Format is documented in `tools/build_dictionary.py`. In short: word ids are assigned
 * in UTF-8 byte order, so a prefix match is a binary search over the id space, and the
 * bigram/trigram tables are sorted by context, so continuations are a contiguous range.
 */
class NgramDictionary private constructor(private val buffer: ByteBuffer) {

    private val wordCount = buffer.getInt(8)
    private val unigramTopCount = buffer.getInt(12)
    private val bigramCount = buffer.getInt(16)
    private val trigramCount = buffer.getInt(20)

    private val wordOffsetsPos = buffer.getInt(24)
    private val wordBlobPos = buffer.getInt(28)
    private val unigramFreqPos = buffer.getInt(36)
    private val unigramTopPos = buffer.getInt(40)
    private val bigramPos = buffer.getInt(44)
    private val trigramPos = buffer.getInt(48)

    /** A candidate word with the raw corpus frequency behind it. */
    data class Candidate(val word: String, val score: Int)

    /**
     * Id of the sentence-start marker, excluded from every result.
     *
     * The marker is a real vocabulary entry — it has to be, so that openers get a proper
     * distribution — and the corpus contains the pair (<s>, <s>) from the two-token
     * padding, which makes it a legitimate continuation of itself. Without this filter it
     * is offered to the user as a word.
     */
    private val sentenceStartId = idOf(SENTENCE_START)

    // --- Vocabulary ---------------------------------------------------------------

    private fun wordStart(id: Int): Int = buffer.getInt(wordOffsetsPos + id * 4)

    fun wordAt(id: Int): String {
        val start = wordStart(id)
        val end = wordStart(id + 1)
        val bytes = ByteArray(end - start)
        for (i in bytes.indices) bytes[i] = buffer.get(wordBlobPos + start + i)
        return String(bytes, Charsets.UTF_8)
    }

    /** Byte-wise comparison of the stored word [id] against [needle]'s UTF-8 bytes. */
    private fun compareWord(id: Int, needle: ByteArray, prefixOnly: Boolean): Int {
        val start = wordBlobPos + wordStart(id)
        val length = wordStart(id + 1) - wordStart(id)
        val limit = maxOf(length, needle.size)
        for (i in 0 until limit) {
            val a = if (i < length) buffer.get(start + i).toInt() and 0xFF else -1
            val b = if (i < needle.size) needle[i].toInt() and 0xFF else -1
            if (a != b) {
                // Under prefixOnly a word that merely runs past the needle is a match;
                // one that runs out early is not, and still sorts before it.
                if (prefixOnly && b == -1) return 0
                return a - b
            }
        }
        return 0
    }

    /** Word id for an exact match, or -1. */
    fun idOf(word: String): Int {
        val needle = word.toByteArray(Charsets.UTF_8)
        var low = 0
        var high = wordCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareWord(mid, needle, prefixOnly = false)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Inclusive id range of every word starting with [prefix], or null if none. */
    private fun prefixRange(prefix: String): IntRange? {
        if (prefix.isEmpty()) return null
        val needle = prefix.toByteArray(Charsets.UTF_8)

        var low = 0
        var high = wordCount
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareWord(mid, needle, prefixOnly = true) < 0) low = mid + 1 else high = mid
        }
        val first = low
        if (first >= wordCount || compareWord(first, needle, prefixOnly = true) != 0) return null

        high = wordCount
        low = first
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareWord(mid, needle, prefixOnly = true) <= 0) low = mid + 1 else high = mid
        }
        return first until low
    }

    private fun unigramFreq(id: Int): Int = buffer.getInt(unigramFreqPos + id * 4)

    /** True if [word] is in the bundled vocabulary. Used by the on-device spell checker. */
    fun isKnown(word: String): Boolean = idOf(word) >= 0

    /** Corpus frequency of [word], or 0 if it is not in the vocabulary. */
    fun frequencyOf(word: String): Int {
        val id = idOf(word)
        return if (id >= 0) unigramFreq(id) else 0
    }

    /**
     * How strongly [word] is expected after [previous], in raw bigram units. Zero when
     * the pair was never seen. Lets the spell checker prefer a correction that fits the
     * sentence over one that is merely a common word.
     */
    fun bigramFrequency(previous: String, word: String): Int {
        val prev = idOf(previous)
        val next = idOf(word)
        if (prev < 0 || next < 0) return 0
        var index = lowerBound(bigramCount, BIGRAM_RECORD, bigramPos) { pos ->
            val a = buffer.getInt(pos)
            if (a != prev) a - prev else buffer.getInt(pos + 4) - next
        }
        if (index >= bigramCount) return 0
        val pos = bigramPos + index * BIGRAM_RECORD
        return if (buffer.getInt(pos) == prev && buffer.getInt(pos + 4) == next) {
            buffer.getInt(pos + 8)
        } else {
            0
        }
    }

    // --- Lookups ------------------------------------------------------------------

    /**
     * Continuations of [context] — the last one or two committed words — optionally
     * restricted to those starting with [prefix], the partial word being typed.
     *
     * Backoff order is trigram, then bigram, then the prefix range, then the global
     * top words. Scores from different orders are not comparable, so
     * [PredictionEngine] normalises them; this returns them in corpus units.
     */
    fun lookup(context: List<String>, prefix: String = "", limit: Int = 16): List<Candidate> {
        val prefixIds = if (prefix.isEmpty()) null else prefixRange(prefix)
        if (prefix.isNotEmpty() && prefixIds == null) return emptyList()

        val results = LinkedHashMap<Int, Int>(limit * 2)

        if (context.size >= 2) {
            val w1 = idOf(context[context.size - 2])
            val w2 = idOf(context[context.size - 1])
            if (w1 >= 0 && w2 >= 0) collectTrigrams(w1, w2, prefixIds, results)
        }
        if (results.size < limit && context.isNotEmpty()) {
            val prev = idOf(context.last())
            if (prev >= 0) collectBigrams(prev, prefixIds, results)
        }
        if (results.size < limit && prefixIds != null) {
            // Nothing contextual matched the prefix; fall back to how common the word is.
            prefixIds.asSequence()
                .sortedByDescending { unigramFreq(it) }
                .take(limit - results.size)
                .forEach { id -> results.putIfAbsent(id, unigramFreq(id)) }
        }
        if (results.isEmpty() && prefix.isEmpty()) {
            for (i in 0 until minOf(unigramTopCount, limit)) {
                val id = buffer.getInt(unigramTopPos + i * 4)
                results.putIfAbsent(id, unigramFreq(id))
            }
        }

        return results.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { Candidate(wordAt(it.key), it.value) }
    }

    private fun collectTrigrams(
        w1: Int,
        w2: Int,
        prefixIds: IntRange?,
        into: MutableMap<Int, Int>,
    ) {
        var index = lowerBound(trigramCount, TRIGRAM_RECORD, trigramPos) { pos ->
            val a = buffer.getInt(pos)
            if (a != w1) a - w1 else buffer.getInt(pos + 4) - w2
        }
        while (index < trigramCount) {
            val pos = trigramPos + index * TRIGRAM_RECORD
            if (buffer.getInt(pos) != w1 || buffer.getInt(pos + 4) != w2) break
            val id = buffer.getInt(pos + 8)
            if (id != sentenceStartId && (prefixIds == null || id in prefixIds)) {
                // Trigram evidence outranks bigram evidence for the same word.
                into[id] = buffer.getInt(pos + 12) * TRIGRAM_WEIGHT
            }
            index++
        }
    }

    private fun collectBigrams(prev: Int, prefixIds: IntRange?, into: MutableMap<Int, Int>) {
        var index = lowerBound(bigramCount, BIGRAM_RECORD, bigramPos) { pos ->
            buffer.getInt(pos) - prev
        }
        while (index < bigramCount) {
            val pos = bigramPos + index * BIGRAM_RECORD
            if (buffer.getInt(pos) != prev) break
            val id = buffer.getInt(pos + 4)
            if (id != sentenceStartId && (prefixIds == null || id in prefixIds)) {
                into.putIfAbsent(id, buffer.getInt(pos + 8))
            }
            index++
        }
    }

    /** First record index whose [compare] is >= 0. Records are sorted by that key. */
    private inline fun lowerBound(
        count: Int,
        recordSize: Int,
        base: Int,
        compare: (pos: Int) -> Int,
    ): Int {
        var low = 0
        var high = count
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compare(base + mid * recordSize) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        private const val TAG = "NgramDictionary"
        private const val BIGRAM_RECORD = 12
        private const val TRIGRAM_RECORD = 16
        private const val MAGIC = 0x4442_4B43 // "CKBD" little-endian
        private const val SUPPORTED_VERSION = 1

        /**
         * Weight applied to trigram frequencies before they are compared with bigram
         * ones. A trigram match is rarer and more specific, so it should win a tie
         * against a merely-common bigram continuation.
         */
        private const val TRIGRAM_WEIGHT = 4

        /** Marker for the start of a sentence; present in the bundled vocabulary. */
        const val SENTENCE_START = "<s>"

        /**
         * Wraps an already-mapped (or heap) buffer, validating the header. Exposed so
         * JVM tests can exercise the real dictionary file without a device.
         */
        fun fromBuffer(buffer: ByteBuffer): NgramDictionary {
            val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
            require(ordered.getInt(0) == MAGIC) { "not a Chalkboard dictionary" }
            require(ordered.getShort(4).toInt() == SUPPORTED_VERSION) {
                "unsupported dictionary version ${ordered.getShort(4)}"
            }
            return NgramDictionary(ordered)
        }

        /**
         * Maps the bundled dictionary. Returns null if the resource is missing or
         * unreadable — prediction then degrades to the personal store alone rather than
         * taking the keyboard down with it.
         */
        fun load(context: Context): NgramDictionary? = try {
            context.resources.openRawResourceFd(R.raw.ngram_dictionary).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    fromBuffer(
                        stream.channel
                            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    )
                }
            }
        } catch (e: Exception) {
            // A compressed resource is the likely cause: check noCompress in the build file.
            Log.e(TAG, "Could not map the bundled dictionary", e)
            null
        }
    }
}
