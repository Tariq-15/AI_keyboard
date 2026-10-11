package com.chalkboard.keyboard.grammar

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-tier ordering, and what a server failure is allowed to claim.
 *
 * The offline tier costs nothing and answers instantly, so it runs first and the server
 * is only paid for when it found nothing. That ordering is the reason a server outage
 * can never take spelling with it — which is exactly what the section E1 copy promises,
 * so it is worth a test rather than a comment.
 */
class GrammarServiceTest {

    private class FakeChecker(
        override val source: GrammarSource,
        private val result: Result<List<Correction>>,
    ) : GrammarChecker {
        var calls = 0
            private set

        override suspend fun isAvailable(): Boolean = true

        override suspend fun check(text: String): Result<List<Correction>> {
            calls++
            return result
        }
    }

    private fun correction(original: String, replacement: String) = Correction(
        range = 0 until original.length,
        original = original,
        replacement = replacement,
        ruleId = "TEST",
    )

    private fun checker(source: GrammarSource, vararg corrections: Correction) =
        FakeChecker(source, Result.success(corrections.toList()))

    private fun failing(error: Throwable) =
        FakeChecker(GrammarSource.SERVER, Result.failure(error))

    @Test
    fun `on-device hit never pays for a round trip`() = runTest {
        val remote = checker(GrammarSource.SERVER, correction("x", "y"))
        val service = GrammarService(
            onDevice = checker(GrammarSource.ON_DEVICE, correction("teh", "the")),
            remote = remote,
        )

        val outcome = service.check("teh cat")

        assertTrue(outcome is GrammarService.Outcome.Corrections)
        assertEquals(
            GrammarSource.ON_DEVICE,
            (outcome as GrammarService.Outcome.Corrections).source,
        )
        assertEquals("the server must not be consulted", 0, remote.calls)
    }

    @Test
    fun `no server configured reports clean rather than failed`() = runTest {
        val service = GrammarService(
            onDevice = checker(GrammarSource.ON_DEVICE),
            remote = null,
        )

        val outcome = service.check("all fine here")

        assertTrue(outcome is GrammarService.Outcome.Clean)
        assertEquals(
            GrammarSource.ON_DEVICE,
            (outcome as GrammarService.Outcome.Clean).source,
        )
    }

    @Test
    fun `server failure carries the original error for the caller to word`() = runTest {
        val error = GrammarError.Offline
        val service = GrammarService(
            onDevice = checker(GrammarSource.ON_DEVICE),
            remote = failing(error),
        )

        val outcome = service.check("something to check")

        assertTrue(outcome is GrammarService.Outcome.Failed)
        assertSame(error, (outcome as GrammarService.Outcome.Failed).error)
    }

    @Test
    fun `no-op corrections are not offered as corrections`() = runTest {
        val service = GrammarService(
            onDevice = checker(GrammarSource.ON_DEVICE, correction("the", "the")),
            remote = null,
        )

        assertTrue(service.check("the cat") is GrammarService.Outcome.Clean)
    }

    @Test
    fun `blank text short-circuits both tiers`() = runTest {
        val onDevice = checker(GrammarSource.ON_DEVICE, correction("x", "y"))
        val remote = checker(GrammarSource.SERVER, correction("x", "y"))

        val outcome = GrammarService(onDevice, remote).check("   ")

        assertTrue(outcome is GrammarService.Outcome.Clean)
        assertEquals(0, onDevice.calls)
        assertEquals(0, remote.calls)
    }
}
