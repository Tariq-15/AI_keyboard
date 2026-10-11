package com.chalkboard.keyboard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * `Retry-After` is what turns "Rate limited" into "Rate limited — try again in 2 min"
 * (section E2), and it arrives in two different formats depending on the provider.
 *
 * The rule under test is that a value we cannot read produces no promise at all: a wait
 * stated wrongly is worse than a wait not stated, because the user acts on it.
 */
class RetryAfterTest {

    private class FakeConnection(private val headers: Map<String, String>) :
        HttpURLConnection(URL("https://example.invalid/")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getHeaderField(name: String): String? = headers[name]
    }

    private fun retryAfter(value: String?): Int? =
        FakeConnection(value?.let { mapOf("Retry-After" to it) } ?: emptyMap())
            .retryAfterSeconds()

    @Test
    fun `plain seconds are read as seconds`() {
        assertEquals(120, retryAfter("120"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(45, retryAfter("  45 "))
    }

    @Test
    fun `absent header states no wait`() {
        assertNull(retryAfter(null))
    }

    @Test
    fun `empty header states no wait`() {
        assertNull(retryAfter(""))
    }

    @Test
    fun `zero and negative are dropped rather than shown as an instant retry`() {
        assertNull(retryAfter("0"))
        assertNull(retryAfter("-30"))
    }

    @Test
    fun `unparseable value is dropped rather than guessed at`() {
        assertNull(retryAfter("soon"))
    }

    @Test
    fun `http date in the future becomes the remaining seconds`() {
        val future = ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5)
        val seconds = retryAfter(DateTimeFormatter.RFC_1123_DATE_TIME.format(future))
        // Second-level rounding either way around the call, so allow a small window.
        assertEquals(300.0, requireNotNull(seconds).toDouble(), 5.0)
    }

    @Test
    fun `http date already past is dropped`() {
        val past = ZonedDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(5)
        assertNull(retryAfter(DateTimeFormatter.RFC_1123_DATE_TIME.format(past)))
    }
}
