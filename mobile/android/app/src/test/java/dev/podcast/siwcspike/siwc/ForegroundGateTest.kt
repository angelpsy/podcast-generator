package dev.podcast.siwcspike.siwc

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ForegroundGateTest {
    @Test fun waitsForReturnBeforeCodeExchangeCanProceed() {
        val gate = ForegroundGate()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            val exchange = executor.submit<Boolean> {
                started.countDown()
                gate.await(2000) { false }
                true
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            try { exchange.get(30, TimeUnit.MILLISECONDS); fail("Exchange ran in background") }
            catch (_: TimeoutException) { /* Expected: callback alone cannot start exchange. */ }
            gate.setForeground(true)
            assertTrue(exchange.get(1, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }

    @Test fun pauseClosesGateAndTimeoutDoesNotAuthorizeExchange() {
        val gate = ForegroundGate()
        gate.setForeground(true)
        gate.await(10) { false }
        gate.setForeground(false)
        try { gate.await(10) { false }; fail("Must time out") }
        catch (error: SafeFailure) { assertEquals("app_return_timeout", error.safeCode) }
    }

    @Test fun cancellationWinsEvenAfterResume() {
        val gate = ForegroundGate()
        gate.setForeground(true)
        try { gate.await(10) { true }; fail("Must cancel") }
        catch (error: SafeFailure) { assertEquals("cancelled", error.safeCode) }
    }
}
