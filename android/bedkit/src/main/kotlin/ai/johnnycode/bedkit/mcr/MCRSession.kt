package ai.johnnycode.bedkit.mcr

import ai.johnnycode.bedkit.BedCharacteristic
import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.BedPreset
import ai.johnnycode.bedkit.FrameEvent
import ai.johnnycode.bedkit.FrameLogger
import ai.johnnycode.bedkit.GattLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

// Port of BedKit/Sources/MCR/MCRSession.swift.

/**
 * Request/response engine for the MCR protocol over an abstract [GattLink].
 *
 * Mirrors the official app (`blelib/mcr/manager`): one request in flight, 900 ms timeout, up to 3
 * retries on timeout. A reply matches only if it has the response bit, the same node family and
 * command, and (unless its destination is 0) is addressed to us from the bed. The bed never pushes
 * state; anything that does not match is logged and dropped.
 *
 * Not thread-safe: call it from one thread (the app's main thread / the test dispatcher).
 */
class MCRSession(
    private val link: GattLink,
    /** The persisted random 8-byte client ID sent in the bind. */
    private val clientId: List<Int>,
    private val macFallback: Int? = null,
    private val timing: Timing = Timing(),
    private val log: FrameLogger,
) {
    data class Timing(val responseTimeoutMs: Long = 900, val retries: Int = 3)

    private class Pending(
        val id: Long,
        val matches: (MCRFrame) -> Boolean,
        val deferred: CompletableDeferred<MCRFrame>,
    )

    var bind: MCRBindResult? = null
        private set
    val isBound: Boolean get() = bind != null

    private val parser = MCRStreamParser()
    private val mutex = Mutex()
    private var pending: Pending? = null
    private var nextId = 0L

    /**
     * Incremented by every stop. A request started before a stop never goes out (or retries) after it,
     * so a queued preset or a lost-reply retry cannot restart motion after STOP.
     */
    var stopGeneration = 0L
        private set

    // MARK: Notifications

    /** Feed every notification from the MCR notify characteristic here. */
    fun handleNotification(data: ByteArray) {
        log(FrameEvent(FrameEvent.Direction.RX, "MCR", data, "notify ${data.size}b"))
        for (frame in parser.feed(data)) {
            log(FrameEvent(FrameEvent.Direction.RX, "MCR", frame.encoded(), frame.toString()))
            val current = pending
            if (current != null && current.matches(frame)) {
                pending = null
                current.deferred.complete(frame)
            } else {
                log(FrameEvent(FrameEvent.Direction.INFO, "MCR", text = "unmatched reply dropped: $frame"))
            }
        }
    }

    /** Fails any in-flight request (call on disconnect). */
    fun reset(error: BedError = BedError.NotConnected) {
        parser.reset()
        bind = null
        failPending(null, error)
    }

    // MARK: Handshake

    /** Runs the bind and stores the assigned addresses. */
    suspend fun performBind(): MCRBindResult {
        val request = MCRCommands.bind(clientId)
        var lastError: BedError = BedError.Timeout("bind")
        for (attempt in 1..(timing.retries + 1)) {
            try {
                // Before binding both addresses are 0, so a reply addressed to 0 is accepted (the app's
                // rule); require only the response bit and the bind command.
                val reply = exchange(request, 0, 0, timing.responseTimeoutMs) {
                    it.isResponse && it.functionCode == MCRFunction.BIND
                }
                val result = MCRBindResult.parse(reply, macFallback)
                bind = result
                log(FrameEvent(FrameEvent.Direction.INFO, "MCR", text = "bound: bed %04X, client %04X".format(result.bedAddress, result.clientAddress)))
                return result
            } catch (error: BedError) {
                if (error == BedError.Cancelled || error == BedError.NotConnected) throw error
                lastError = error
                log(FrameEvent(FrameEvent.Direction.ERROR, "MCR", text = "bind attempt $attempt failed: ${error.message}"))
            }
        }
        throw lastError
    }

    // MARK: Requests

    /**
     * Sends a request and returns the matching reply, retrying on timeout.
     * @param generation the [stopGeneration] the caller's operation started under; the request is
     *   abandoned with [BedError.Cancelled] if a stop happened since. Defaults to now.
     */
    suspend fun send(
        request: MCRRequest,
        timeoutMs: Long? = null,
        retries: Int? = null,
        generation: Long? = null,
    ): MCRFrame {
        val bind = bind ?: throw BedError.NotConnected
        val gen = generation ?: stopGeneration
        val attempts = (retries ?: timing.retries) + 1
        var lastError: BedError = BedError.Timeout(request.label)
        for (attempt in 1..attempts) {
            try {
                val reply = exchange(request, bind.clientAddress, bind.bedAddress, timeoutMs ?: timing.responseTimeoutMs, gen) {
                    replyMatches(it, request, bind)
                }
                when (reply.side) {
                    0x0E -> throw BedError.CommandFailed("${request.label}: bad parameter")
                    0x0F -> throw BedError.CommandFailed("${request.label}: NACK")
                    else -> return reply
                }
            } catch (timeout: BedError.Timeout) {
                lastError = timeout
                if (attempt < attempts) {
                    log(FrameEvent(FrameEvent.Direction.INFO, "MCR", text = "no reply to ${request.label}; retry $attempt"))
                }
            }
        }
        throw lastError
    }

    /**
     * Writes a request immediately, bypassing the request queue and without waiting for a reply. Used
     * for STOP so it never waits behind a slow poll.
     */
    suspend fun sendUrgent(request: MCRRequest) {
        val bind = bind ?: throw BedError.NotConnected
        val data = request.frame(bind.clientAddress, bind.bedAddress).encoded()
        MCRSafety.validateWireFrame(data)
        log(FrameEvent(FrameEvent.Direction.TX, "MCR", data, "URGENT ${request.label}"))
        link.write(data, BedCharacteristic.MCR_WRITE)
    }

    private suspend fun exchange(
        request: MCRRequest,
        client: Int,
        bed: Int,
        timeoutMs: Long,
        generation: Long? = null,
        matches: (MCRFrame) -> Boolean,
    ): MCRFrame = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (generation != null && generation != stopGeneration) {
            log(FrameEvent(FrameEvent.Direction.INFO, "MCR", text = "dropped ${request.label}: STOP was pressed after it was queued"))
            throw BedError.Cancelled
        }
        val data = request.frame(client, bed).encoded()
        MCRSafety.validateWireFrame(data)

        val id = ++nextId
        val deferred = CompletableDeferred<MCRFrame>()
        failPending(null, BedError.Cancelled)
        pending = Pending(id, matches, deferred)
        log(FrameEvent(FrameEvent.Direction.TX, "MCR", data, request.label))
        try {
            try {
                link.write(data, BedCharacteristic.MCR_WRITE)
            } catch (e: BedError) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BedError.Transport(e.message ?: e.toString())
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: throw BedError.Timeout(request.label)
        } finally {
            if (pending?.id == id) pending = null
        }
    }

    private fun failPending(id: Long?, error: BedError) {
        val current = pending ?: return
        if (id != null && current.id != id) return
        pending = null
        current.deferred.completeExceptionally(error)
    }

    // MARK: Typed operations

    suspend fun readPumpStatus(generation: Long? = null): MCRPumpStatus =
        MCRPumpStatus.parse(send(MCRCommands.readPumpStatus(), generation = generation))

    suspend fun readFoundationStatus(keepAdjusting: MCRWireSide? = null, generation: Long? = null): MCRFoundationStatus =
        MCRFoundationStatus.parse(send(MCRCommands.readFoundationStatus(keepAdjusting), generation = generation))

    suspend fun readUnderBedLight(): MCRLightStatus = MCRLightStatus.parse(send(MCRCommands.readUnderBedLight()))

    suspend fun setUnderBedLight(on: Boolean) {
        send(MCRCommands.setUnderBedLight(on))
    }

    suspend fun activatePreset(preset: BedPreset, side: MCRWireSide) {
        send(MCRCommands.activatePreset(preset, side))
    }

    /** Bumps the stop generation (abandoning queued requests) without sending anything. */
    fun invalidateQueued() {
        stopGeneration++
    }

    /**
     * Starts a firmness adjustment. With `interruptRunning`, first sends ForceIdle and waits 500 ms, as
     * the app does when it cancels an adjustment in progress.
     */
    suspend fun setSleepNumber(value: Int, side: MCRWireSide, interruptRunning: Boolean, generation: Long? = null) {
        val request = MCRCommands.setSleepNumber(side, value)
        val gen = generation ?: stopGeneration
        if (interruptRunning) {
            send(MCRCommands.pumpForceIdle(), generation = gen)
            delay(500)
        }
        send(request, generation = gen)
    }

    suspend fun setFoundationTarget(side: MCRWireSide, axis: FoundationAxis, position: Int) {
        send(MCRCommands.setFoundationTarget(side, axis, position))
    }

    /** Absolute set-and-go (`SetFoundationPositionCall`, Sleep Expert keys MFU[R|L] / MFF[R|L]). */
    suspend fun setPosition(side: MCRWireSide, axis: FoundationAxis, position: Int, generation: Long? = null) {
        send(MCRCommands.setPosition(side, axis, position), generation = generation)
    }

    suspend fun readSystemStatus(): MCRSystemStatus = MCRSystemStatus.parse(send(MCRCommands.readSystemStatus()))

    suspend fun readPinchState(): MCRPinchState = MCRPinchState.parse(send(MCRCommands.readPinchState()))

    suspend fun readNodeList(): List<Int> = send(MCRCommands.readNodeList()).payload

    /**
     * Stops foundation movement with the app's stop key (MFHR/MFHL "110") for each side. Each stop is
     * written immediately (bypassing any queued request), then sent again through the queue so the
     * bed's acknowledgement is checked. Every side is attempted.
     */
    suspend fun stopFoundation(sides: List<MCRWireSide> = MCRWireSide.entries) {
        stopGeneration++
        var firstError: Exception? = null
        for (side in sides) {
            try {
                sendUrgent(MCRCommands.stopMotion(side))
            } catch (e: BedError) {
                firstError = firstError ?: e
            }
        }
        for (side in sides) {
            try {
                send(MCRCommands.stopMotion(side), retries = 1)
            } catch (e: BedError) {
                firstError = firstError ?: e
            }
        }
        firstError?.let { throw it }
    }

    /** Interrupts a firmness adjustment immediately. */
    suspend fun stopPump() {
        stopGeneration++
        sendUrgent(MCRCommands.pumpForceIdle())
    }

    companion object {
        /** Reply correlation per `mcr/manager/a`. */
        fun replyMatches(frame: MCRFrame, request: MCRRequest, bind: MCRBindResult): Boolean {
            if (!frame.isResponse || frame.functionCode != request.function ||
                frame.command and 0xF0 != request.node.raw and 0xF0
            ) return false
            if (frame.destination == 0) return true
            return frame.destination == bind.clientAddress && frame.sourceEcho == bind.bedAddress
        }
    }
}
