package ai.johnnycode.bedcontrol.ble

import ai.johnnycode.bedkit.BedError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android GATT allows one outstanding operation. This queue runs one at a time and pairs each with its
 * callback. Plain Kotlin (no Android types) so it is unit-testable; [BleManager] feeds it callbacks.
 *
 * - The pending operation is registered only after `start()` succeeded, and a started operation always
 *   waits for its own callback (bounded by its timeout) even when the caller is cancelled.
 * - When an operation times out, its key is *poisoned* until its late reply arrives; that reply is
 *   consumed and dropped, never used to complete a later operation. There is no wall-clock expiry:
 *   Android's GATT client refuses a new operation (busy) until the previous one's callback has cleared
 *   its busy flag, and it clears that flag just before dispatching the callback. So if a new `start()`
 *   succeeds while a key is poisoned, the late reply is already on its way and, because callbacks reach
 *   us in order on one thread, it arrives before the new operation's own reply.
 * - While any reply is still owed, `start()` keeps being retried for up to [POISONED_START_WINDOW_MS]
 *   (the stall stop must get out as soon as the late reply frees the stack), otherwise briefly.
 *
 * Main thread only (callbacks must be delivered on the same thread as callers).
 */
class GattOpQueue(
    private val log: (String) -> Unit = {},
    private val onTimeout: (kind: String) -> Unit = {},
    /** Monotonic milliseconds (injectable for tests). */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Pending(val key: String, val deferred: CompletableDeferred<ByteArray>)

    private val mutex = Mutex()
    private var pending: Pending? = null

    /** Per key, the number of late replies still owed by timed-out operations. */
    private val poisoned = mutableMapOf<String, Int>()

    /** Number of late callbacks still expected from timed-out operations (for tests/diagnostics). */
    val poisonedCount: Int get() = poisoned.values.sum()

    companion object {
        /** How long `start()` is retried while the stack is busy normally (about 375 ms in 5 tries). */
        const val START_WINDOW_MS = 375L

        /** How long `start()` is retried while a timed-out operation's reply is still owed. */
        const val POISONED_START_WINDOW_MS = 2_000L
    }

    private fun key(kind: String, id: String?) = "$kind:${id ?: "-"}"

    /**
     * Starts an operation with [start] (false = the stack refused/busy; retried) and waits for its
     * callback. Throws [BedError.Timeout] if none arrives within [timeoutMs].
     */
    suspend fun run(kind: String, id: String?, timeoutMs: Long, start: () -> Boolean): ByteArray = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val opKey = key(kind, id)
        val window = if (poisoned.isEmpty()) START_WINDOW_MS else POISONED_START_WINDOW_MS
        val deadline = now() + window
        var attempt = 0
        while (!start()) {
            attempt++
            if (now() >= deadline) {
                throw BedError.Transport(
                    if (poisoned.isEmpty()) "$kind could not be started"
                    else "$kind could not be started: an earlier Bluetooth operation never completed",
                )
            }
            delay(minOf(25L * attempt, 100L))
        }
        // Registered only now: nothing can be delivered between start() returning and this line
        // (callbacks are posted to this same thread).
        val deferred = CompletableDeferred<ByteArray>()
        pending = Pending(opKey, deferred)
        val result = withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMs) { runCatching { deferred.await() } }
        }
        if (result == null) {
            if (pending?.deferred === deferred) pending = null
            poisoned[opKey] = (poisoned[opKey] ?: 0) + 1
            log("$kind timed out after $timeoutMs ms; its late reply will be discarded")
            onTimeout(kind)
            throw BedError.Timeout("BLE $kind")
        }
        currentCoroutineContext().ensureActive()
        result.getOrElse { throw (it as? BedError) ?: BedError.Transport(it.message ?: it.toString()) }
    }

    /**
     * Delivers a GATT callback. [error] non-null means the operation failed. Returns true if it was
     * matched to the pending operation, false if it was dropped (a timed-out operation's late reply, or
     * unexpected).
     */
    fun complete(kind: String, id: String?, value: ByteArray, error: BedError? = null): Boolean {
        val opKey = key(kind, id)
        val owed = poisoned[opKey] ?: 0
        if (owed > 0) {
            if (owed == 1) poisoned.remove(opKey) else poisoned[opKey] = owed - 1
            log("late $kind reply from a timed-out operation discarded")
            return false
        }
        val op = pending
        if (op == null || op.key != opKey) {
            log("unexpected $kind callback ignored")
            return false
        }
        pending = null
        if (error == null) op.deferred.complete(value) else op.deferred.completeExceptionally(error)
        return true
    }

    /** The link is gone: fail the pending operation and forget owed replies. */
    fun reset(error: BedError) {
        pending?.deferred?.completeExceptionally(error)
        pending = null
        poisoned.clear()
    }
}
