package com.chalkboard.keyboard.prediction

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.TimeUnit

/**
 * What this user actually types, learned locally.
 *
 * This data never leaves the device. It is not synced, not backed up (`allowBackup` is
 * false in the manifest), and not readable by any other app. Nothing in this class opens
 * a network connection, and nothing should ever be added to it that does.
 *
 * Entries decay so the store tracks current habits rather than accumulating forever:
 * counts are halved for anything untouched for [DECAY_AFTER_DAYS], and rows that decay
 * to nothing are dropped.
 */
class PersonalLearningStore(context: Context) : java.io.Closeable {

    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    word TEXT NOT NULL,
                    prev_word TEXT NOT NULL,
                    count INTEGER NOT NULL DEFAULT 1,
                    last_used INTEGER NOT NULL,
                    PRIMARY KEY (word, prev_word)
                )
                """.trimIndent()
            )
            // Lookups are always "given prev_word, what came next" — index that direction.
            db.execSQL("CREATE INDEX idx_prev ON $TABLE (prev_word, count DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    /**
     * Records that [word] was committed after [previous].
     *
     * [previous] is [NgramDictionary.SENTENCE_START] at the start of a sentence, so
     * sentence-openers are learned the same way as mid-sentence pairs.
     */
    fun record(word: String, previous: String) {
        if (word.isBlank()) return
        val normalized = word.lowercase()
        val prev = previous.lowercase()
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()

        val updated = db.compileStatement(
            "UPDATE $TABLE SET count = count + 1, last_used = ? WHERE word = ? AND prev_word = ?"
        ).use { statement ->
            statement.bindLong(1, now)
            statement.bindString(2, normalized)
            statement.bindString(3, prev)
            statement.executeUpdateDelete()
        }
        if (updated == 0) {
            db.insertWithOnConflict(
                TABLE,
                null,
                ContentValues().apply {
                    put("word", normalized)
                    put("prev_word", prev)
                    put("count", 1)
                    put("last_used", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    /**
     * Words this user has typed after [previous], optionally restricted to those
     * starting with [prefix]. Ordered most-used first.
     */
    fun lookup(previous: String, prefix: String = "", limit: Int = 8): List<Entry> {
        val args = mutableListOf(previous.lowercase())
        var where = "prev_word = ?"
        if (prefix.isNotEmpty()) {
            where += " AND word LIKE ? ESCAPE '\\'"
            args += escapeLike(prefix.lowercase()) + "%"
        }
        return helper.readableDatabase.query(
            TABLE,
            arrayOf("word", "count"),
            where,
            args.toTypedArray(),
            null,
            null,
            "count DESC, last_used DESC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Entry(cursor.getString(0), cursor.getInt(1)))
                }
            }
        }
    }

    /** Words this user has typed at all, matching [prefix]. Used when context misses. */
    fun lookupByPrefix(prefix: String, limit: Int = 8): List<Entry> {
        if (prefix.isEmpty()) return emptyList()
        return helper.readableDatabase.rawQuery(
            """
            SELECT word, SUM(count) AS total FROM $TABLE
            WHERE word LIKE ? ESCAPE '\'
            GROUP BY word ORDER BY total DESC LIMIT ?
            """.trimIndent(),
            arrayOf(escapeLike(prefix.lowercase()) + "%", limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Entry(cursor.getString(0), cursor.getInt(1)))
                }
            }
        }
    }

    /**
     * Halves counts for entries untouched for [DECAY_AFTER_DAYS] and deletes what is
     * left at zero. Cheap enough to run on the IME's own thread at most once a day.
     */
    fun decay() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(DECAY_AFTER_DAYS)
        helper.writableDatabase.apply {
            execSQL("UPDATE $TABLE SET count = count / 2 WHERE last_used < ?", arrayOf(cutoff))
            execSQL("DELETE FROM $TABLE WHERE count <= 0")
        }
    }

    /**
     * How many distinct words have been learned, for the settings row that says so.
     *
     * Distinct words rather than rows: the table is keyed by (word, previous), so a word
     * used after five different ones occupies five rows, and "142 words saved" has to
     * mean words the user would recognise as words.
     */
    fun distinctWordCount(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(DISTINCT word) FROM $TABLE", null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /** Erases everything learned. Wired to a settings control in phase 4. */
    fun clear() {
        helper.writableDatabase.delete(TABLE, null, null)
    }

    override fun close() = helper.close()

    data class Entry(val word: String, val count: Int)

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val DB_NAME = "personal_learning.db"
        const val DB_VERSION = 1
        const val TABLE = "word_pairs"
        const val DECAY_AFTER_DAYS = 45L
    }
}
