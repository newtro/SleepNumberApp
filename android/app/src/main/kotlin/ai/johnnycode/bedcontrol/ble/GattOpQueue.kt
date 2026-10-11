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
 * - A started operation always waits for its own callback (bounded by its timeout) even when the caller
 *   is cancelled, so a cancelled caller cannot desync the queue.
 * - When an operation times out, its key is *poisoned*: the next callback of that kind for that
 *   characteristic is treated as the late reply of the timed-out operation and dropped, so it can never
 *   complete a later operation (for example the stop sent after a stalled write). [onTimeout] is told so
 *   the owner can send a stop and then drop the link.
 *
 * Main thread only (callbacks must be delivered on the same thread as callers).
 */
class GattOpQueue(
    private val log: (String) -> Unit = {},
    private val onTimeout: (kind: String) -> Unit = {},
) {
    private class Pending(val key: String, val deferred: CompletableDeferred<ByteArray>)

    private val mutex = Mutex()
    private var pending: Pending? = null
    private val poisoned = mutableMapOf<String, Int>()

    /** Number of late callbacks still expected from timed-out operations (for tests/diagnostics). */
    val poisonedCount: Int get() = poisoned.values.sum()

    private fun key(kind: String, id: String?) = "$kind:${id ?: "-"}"

    /**
     * Starts an operation with [start] (false = the stack refused; retried briefly) and waits for its
     * callback. Throws [BedError.Timeout] if none arrives within [timeoutMs].
     */
    suspend fun run(kind: String, id: String?, timeoutMs: Long, start: () -> Boolean): ByteArray = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val opKey = key(kind, id)
        val deferred = CompletableDeferred<ByteArray>()
        pending = Pending(opKey, deferred)
        var started = false
        for (attempt in 1..5) {
            started = start()
            if (started) break
            delay(25L * attempt) // the stack is momentarily busy (e.g. a system-initiated operation)
        }
        if (!started) {
            if (pending?.deferred === deferred) pending = null
            throw BedError.Transport("$kind could not be started")
        }
        val result = withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMs) { runCatching { deferred.await() } }
        }
        if (result == null) {
            if (pending?.deferred === deferred) pending = null
            poisoned[opKey] = (poisoned[opKey] ?: 0) + 1
            log("$kind timed out after $timeoutMs ms; a late $kind callback will be discarded")
            onTimeout(kind)
            throw BedError.Timeout("BLE $kind")
        }
        currentCoroutineContext().ensureActive()
        result.getOrElse { throw (it as? BedError) ?: BedError.Transport(it.message ?: it.toString()) }
    }

    /**
     * Delivers a GATT callback. [error] non-null means the operation failed. Returns true if it was
     * matched to the pending operation, false if it was dropped (poisoned late reply or unexpected).
     */
    fun complete(kind: String, id: String?, value: ByteArray, error: BedError? = null): Boolean {
        val opKey = key(kind, id)
        val late = poisoned[opKey] ?: 0
        if (late > 0) {
            if (late == 1) poisoned.remove(opKey) else poisoned[opKey] = late - 1
            log("late $kind callback from a timed-out operation discarded")
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

    /** The link is gone: fail the pending operation and forget poisoned keys. */
    fun reset(error: BedError) {
        pending?.deferred?.completeExceptionally(error)
        pending = null
        poisoned.clear()
    }
}
