package ai.johnnycode.bedcontrol

import ai.johnnycode.bedcontrol.ble.GattOpQueue
import ai.johnnycode.bedkit.BedError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GattOpQueueTest {
    private val a = byteArrayOf(1)
    private val b = byteArrayOf(2)

    @Test
    fun pairsEachOperationWithItsCallback() = runTest {
        val queue = GattOpQueue(now = { testScheduler.currentTime })
        val op = async { queue.run("write", "x", 1_500) { true } }
        runCurrent()
        assertTrue(queue.complete("write", "x", a))
        assertArrayEquals(a, op.await())
    }

    @Test
    fun lateCallbackOfATimedOutOperationCannotCompleteTheNextOne() = runTest {
        val timeouts = mutableListOf<String>()
        val queue = GattOpQueue(onTimeout = { timeouts += it }, now = { testScheduler.currentTime })
        // A stalled write (no callback) times out and poisons its key.
        val stalled = async { runCatching { queue.run("write", "x", 1_500) { true } } }
        advanceTimeBy(1_600)
        assertTrue(stalled.await().exceptionOrNull() is BedError.Timeout)
        assertEquals(listOf("write"), timeouts)
        assertEquals(1, queue.poisonedCount)

        // The stop write goes out next; the stalled write's late callback arrives first and is dropped.
        val stop = async { queue.run("write", "x", 1_500) { true } }
        runCurrent()
        assertFalse(queue.complete("write", "x", a))
        assertTrue(stop.isActive)
        assertTrue(queue.complete("write", "x", b))
        assertArrayEquals(b, stop.await())
        assertEquals(0, queue.poisonedCount)
    }

    @Test
    fun poisonExpiresSoALostLateCallbackCannotStallTheStop() = runTest {
        val queue = GattOpQueue(now = { testScheduler.currentTime })
        val stalled = async { runCatching { queue.run("write", "x", 1_500) { true } } }
        advanceTimeBy(1_600)
        stalled.await()
        // The late callback never arrives. After the poison window the stop's own callback counts.
        advanceTimeBy(GattOpQueue.POISON_WINDOW_MS)
        val stop = async { queue.run("write", "x", 1_500) { true } }
        runCurrent()
        assertTrue(queue.complete("write", "x", b))
        assertArrayEquals(b, stop.await())
        assertEquals(0, queue.poisonedCount)
    }

    @Test
    fun poisoningIsPerKindAndCharacteristic() = runTest {
        val queue = GattOpQueue(now = { testScheduler.currentTime })
        val stalled = async { runCatching { queue.run("read", "auth", 1_000) { true } } }
        advanceTimeBy(1_100)
        stalled.await()
        val write = async { queue.run("write", "x", 1_500) { true } }
        runCurrent()
        assertTrue(queue.complete("write", "x", a))
        assertArrayEquals(a, write.await())
        assertEquals(1, queue.poisonedCount)
    }

    @Test
    fun cancelledCallerStillWaitsForItsCallback() = runTest {
        val queue = GattOpQueue(now = { testScheduler.currentTime })
        val op = async { queue.run("read", "x", 5_000) { true } }
        runCurrent()
        op.cancel()
        val next = async { queue.run("read", "x", 5_000) { true } }
        runCurrent()
        // The first operation's callback goes to the first operation, not the next one.
        assertTrue(queue.complete("read", "x", a))
        try {
            op.await()
            fail()
        } catch (_: CancellationException) {
        }
        runCurrent()
        assertTrue(queue.complete("read", "x", b))
        assertArrayEquals(b, next.await())
    }

    @Test
    fun resetFailsThePendingOperationAndClearsPoison() = runTest {
        val queue = GattOpQueue(now = { testScheduler.currentTime })
        val stalled = async { runCatching { queue.run("write", "x", 100) { true } } }
        advanceTimeBy(200)
        stalled.await()
        val op = async { runCatching { queue.run("write", "y", 5_000) { true } } }
        runCurrent()
        queue.reset(BedError.NotConnected)
        assertEquals(BedError.NotConnected, op.await().exceptionOrNull())
        assertEquals(0, queue.poisonedCount)
    }

    @Test
    fun refusedStartIsRetriedThenFails() = runTest {
        val queue = GattOpQueue(now = { testScheduler.currentTime })
        var tries = 0
        val result = runCatching { queue.run("write", "x", 1_000) { tries++; false } }
        assertEquals(5, tries)
        assertTrue(result.exceptionOrNull() is BedError.Transport)
    }
}
