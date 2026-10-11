package ai.johnnycode.bedcontrol

import ai.johnnycode.bedcontrol.ble.GattOpQueue
import ai.johnnycode.bedkit.BedError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The fake "stack" mimics Android's GATT client: one outstanding operation; start() is refused (busy)
 * until the outstanding operation's callback has been delivered.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GattOpQueueTest {
    private val a = byteArrayOf(1)
    private val b = byteArrayOf(2)

    private class Stack {
        var busy = false
        var starts = 0
        var refusals = 0
        fun start(): Boolean {
            if (busy) { refusals++; return false }
            busy = true
            starts++
            return true
        }
        /** The stack clears busy, then delivers the callback. */
        fun reply(queue: GattOpQueue, kind: String, id: String, value: ByteArray): Boolean {
            busy = false
            return queue.complete(kind, id, value)
        }
    }

    private fun TestScope.queue(timeouts: MutableList<String> = mutableListOf()) =
        GattOpQueue(onTimeout = { timeouts += it }, now = { testScheduler.currentTime })

    @Test
    fun pairsEachOperationWithItsCallback() = runTest {
        val stack = Stack()
        val q = queue()
        val op = async { q.run("write", "x", 1_500) { stack.start() } }
        runCurrent()
        assertTrue(stack.reply(q, "write", "x", a))
        assertArrayEquals(a, op.await())
    }

    @Test
    fun stallStopStartsOnceTheLateReplyClearsTheStack() = runTest {
        val stack = Stack()
        val timeouts = mutableListOf<String>()
        val q = queue(timeouts)
        val stalled = async { runCatching { q.run("write", "x", 1_500) { stack.start() } } }
        advanceTimeBy(1_600)
        assertTrue(stalled.await().exceptionOrNull() is BedError.Timeout)
        assertEquals(listOf("write"), timeouts)
        assertEquals(1, q.poisonedCount)

        // The stop's first chunk: refused while the stalled write is still outstanding.
        val stop = async { q.run("write", "x", 1_500) { stack.start() } }
        advanceTimeBy(800)
        assertTrue(stack.refusals > 0)
        assertEquals(1, stack.starts)
        // Late reply arrives (well past the old ~375 ms retry window): discarded, and it frees the stack.
        assertFalse(stack.reply(q, "write", "x", a))
        assertEquals(0, q.poisonedCount)
        advanceTimeBy(150)
        assertEquals(2, stack.starts)
        assertTrue(stop.isActive)
        assertTrue(stack.reply(q, "write", "x", b))
        assertArrayEquals(b, stop.await())
    }

    @Test
    fun lateReplyNeverCompletesAnOperationStartedRightAfterIt() = runTest {
        // Race: the stack cleared busy for the late reply, a new op started on this thread before the
        // late reply's posted callback ran. The late reply must be dropped; the new op waits for its own.
        val q = queue()
        val stalled = async { runCatching { q.run("write", "x", 1_500) { true } } }
        advanceTimeBy(1_600)
        stalled.await()
        val next = async { q.run("write", "x", 1_500) { true } }
        runCurrent()
        assertFalse(q.complete("write", "x", a)) // the late reply, delivered first
        assertTrue(next.isActive)
        assertTrue(q.complete("write", "x", b))
        assertArrayEquals(b, next.await())
        assertEquals(0, q.poisonedCount)
    }

    @Test
    fun poisonHasNoWallClockExpiry() = runTest {
        val q = queue()
        val stalled = async { runCatching { q.run("write", "x", 1_000) { true } } }
        advanceTimeBy(1_100)
        stalled.await()
        advanceTimeBy(60_000)
        assertEquals(1, q.poisonedCount)
        assertFalse(q.complete("write", "x", a))
        assertEquals(0, q.poisonedCount)
    }

    @Test
    fun stopGivesUpAfterTheWindowIfTheStackStaysBusy() = runTest {
        val stack = Stack()
        val q = queue()
        val stalled = async { runCatching { q.run("write", "x", 1_500) { stack.start() } } }
        advanceTimeBy(1_600)
        stalled.await()
        val begin = testScheduler.currentTime
        val stop = async { runCatching { q.run("write", "x", 1_500) { stack.start() } } }
        advanceTimeBy(GattOpQueue.POISONED_START_WINDOW_MS + 200)
        assertTrue(stop.await().exceptionOrNull() is BedError.Transport)
        assertTrue(testScheduler.currentTime - begin >= GattOpQueue.POISONED_START_WINDOW_MS)
    }

    @Test
    fun noPendingIsRegisteredWhenStartIsRefused() = runTest {
        val q = queue()
        var tries = 0
        val result = runCatching { q.run("write", "x", 1_000) { tries++; false } }
        assertTrue(result.exceptionOrNull() is BedError.Transport)
        assertTrue(tries >= 5)
        assertTrue(testScheduler.currentTime <= GattOpQueue.START_WINDOW_MS + 100)
        assertFalse("a stray callback finds no pending operation", q.complete("write", "x", a))
    }

    @Test
    fun poisoningIsPerKindAndCharacteristic() = runTest {
        val q = queue()
        val stalled = async { runCatching { q.run("read", "auth", 1_000) { true } } }
        advanceTimeBy(1_100)
        stalled.await()
        val write = async { q.run("write", "x", 1_500) { true } }
        runCurrent()
        assertTrue(q.complete("write", "x", a))
        assertArrayEquals(a, write.await())
        assertEquals(1, q.poisonedCount)
    }

    @Test
    fun cancelledCallerStillWaitsForItsCallback() = runTest {
        val q = queue()
        val op = async { q.run("read", "x", 5_000) { true } }
        runCurrent()
        op.cancel()
        val next = async { q.run("read", "x", 5_000) { true } }
        runCurrent()
        assertTrue(q.complete("read", "x", a)) // goes to the first operation
        try {
            op.await()
            fail()
        } catch (_: CancellationException) {
        }
        runCurrent()
        assertTrue(q.complete("read", "x", b))
        assertArrayEquals(b, next.await())
    }

    @Test
    fun resetFailsThePendingOperationAndClearsPoison() = runTest {
        val q = queue()
        val stalled = async { runCatching { q.run("write", "x", 100) { true } } }
        advanceTimeBy(200)
        stalled.await()
        val op = async { runCatching { q.run("write", "y", 5_000) { true } } }
        runCurrent()
        q.reset(BedError.NotConnected)
        assertEquals(BedError.NotConnected, op.await().exceptionOrNull())
        assertEquals(0, q.poisonedCount)
    }
}
