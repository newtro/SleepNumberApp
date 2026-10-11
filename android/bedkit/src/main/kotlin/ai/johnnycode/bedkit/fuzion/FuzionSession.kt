package ai.johnnycode.bedkit.fuzion

import ai.johnnycode.bedkit.BedCharacteristic
import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.FrameEvent
import ai.johnnycode.bedkit.FrameLogger
import ai.johnnycode.bedkit.GattLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

// Port of BedKit/Sources/Fuzion/FuzionSession.swift.

/**
 * Request/response engine for the Fuzion BamKey protocol over an abstract [GattLink].
 *
 * Flow per command: write the framed text to BamKey (chunked by the link) -> wait for a BamKey
 * notification carrying our 16-byte session UUID (the "response ready" hint) -> read BamKey until one
 * complete blob is assembled -> parse PASS/FAIL.
 */
class FuzionSession(
    private val link: GattLink,
    private val timing: Timing = Timing(),
    private val log: FrameLogger,
) {
    data class Timing(
        val responseTimeoutMs: Long = 7128,
        val authTimeoutMs: Long = 10_000,
        val authAttempts: Int = 3,
    )

    var sessionId: ByteArray? = null
        private set

    private val mutex = Mutex()
    private var hint: CompletableDeferred<Unit>? = null

    /** Incremented by every urgent stop; commands queued before it are abandoned. */
    var stopGeneration = 0L
        private set

    /**
     * True if `data` is a response-ready hint (our session UUID, or the all-zero UUID the app also
     * accepts).
     */
    fun isHint(data: ByteArray): Boolean {
        val id = sessionId ?: return false
        if (data.size != 16) return false
        return data.contentEquals(id) || data.all { it.toInt() == 0 }
    }

    // MARK: Authentication

    /** Reads Auth (which triggers pairing if the bed demands encryption) and validates the session. */
    suspend fun authenticate(): ByteArray {
        var lastError: BedError = BedError.Timeout("Auth read")
        for (attempt in 1..timing.authAttempts) {
            try {
                val value = withTimeoutOrNull(timing.authTimeoutMs) { link.read(BedCharacteristic.FUZION_AUTH) }
                    ?: throw BedError.Timeout("Auth read")
                log(FrameEvent(FrameEvent.Direction.RX, "Auth", value, "Auth ${value.size}b"))
                val session = FuzionAuth.validateSession(value)
                sessionId = session
                return session
            } catch (error: BedError) {
                when (error) {
                    is BedError.Timeout, is BedError.Transport -> {
                        lastError = error
                        log(FrameEvent(FrameEvent.Direction.ERROR, "Auth", text = "attempt $attempt: ${error.message}"))
                    }
                    else -> throw error // limit, rejection, bond problems: retrying will not help
                }
            }
        }
        throw lastError
    }

    fun reset(error: BedError = BedError.NotConnected) {
        sessionId = null
        hint?.completeExceptionally(error)
        hint = null
    }

    // MARK: Notifications

    /** Feed every BamKey notification here. */
    fun handleNotification(data: ByteArray) {
        if (!isHint(data)) {
            log(FrameEvent(FrameEvent.Direction.RX, "BamKey", data, "notify (not a session hint, ignored)"))
            return
        }
        log(FrameEvent(FrameEvent.Direction.RX, "BamKey", data, "response ready"))
        hint?.complete(Unit)
    }

    // MARK: Commands

    /** Sends one command and returns its PASS argument tokens. */
    suspend fun send(command: FuzionCommand, generation: Long? = null): List<String> {
        val gen = generation ?: stopGeneration
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (gen != stopGeneration) {
                log(FrameEvent(FrameEvent.Direction.INFO, "BamKey", text = "dropped ${command.key}: STOP was pressed after it was queued"))
                throw BedError.Cancelled
            }
            if (sessionId == null) throw BedError.NotConnected

            // Armed before the write, so a hint that arrives during the write is not missed.
            val ready = CompletableDeferred<Unit>()
            hint = ready
            try {
                log(FrameEvent(FrameEvent.Direction.TX, "BamKey", command.frame, command.text))
                try {
                    link.write(command.frame, BedCharacteristic.FUZION_BAMKEY)
                } catch (e: BedError) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw BedError.Transport(e.message ?: e.toString())
                }
                withTimeoutOrNull(timing.responseTimeoutMs) { ready.await() }
                    ?: throw BedError.Timeout("${command.key} response notification")

                val reassembler = FuzionReassembler()
                val text = withTimeoutOrNull(timing.responseTimeoutMs) {
                    var result: String? = null
                    for (i in 0 until 64) {
                        val chunk = link.read(BedCharacteristic.FUZION_BAMKEY)
                        val progress = reassembler.append(chunk)
                        if (progress is FuzionReassembler.Progress.Complete) {
                            result = progress.text
                            break
                        }
                    }
                    result ?: throw BedError.MalformedResponse("${command.key} response never completed")
                } ?: throw BedError.Timeout("${command.key} read")
                log(FrameEvent(FrameEvent.Direction.RX, "BamKey", text = "${command.key} -> $text"))
                FuzionResponse.parse(text, command)
            } finally {
                if (hint === ready) hint = null
            }
        }
    }

    /** Writes immediately, bypassing the queue and without reading a reply (STOP path). */
    suspend fun sendUrgent(command: FuzionCommand) {
        if (sessionId == null) throw BedError.NotConnected
        stopGeneration++
        log(FrameEvent(FrameEvent.Direction.TX, "BamKey", command.frame, "URGENT ${command.text}"))
        link.write(command.frame, BedCharacteristic.FUZION_BAMKEY)
    }

    // MARK: Typed operations

    suspend fun systemConfiguration(): FuzionSystemConfiguration =
        FuzionSystemConfiguration.parse(send(FuzionCommand.systemConfiguration()))

    suspend fun sleepNumberControls(side: FuzionSide, generation: Long? = null): FuzionSleepNumberControls {
        val command = FuzionCommand.sleepNumberControls(side)
        return FuzionSleepNumberControls.parse(send(command, generation), command)
    }

    suspend fun actuatorPosition(side: FuzionSide, actuator: FuzionActuator, generation: Long? = null): Int {
        val command = FuzionCommand.actuatorPosition(side, actuator)
        val tokens = send(command, generation)
        if (tokens.size != 1) throw BedError.MalformedResponse("ACTG returned $tokens")
        return FuzionResponse.int(tokens, 0, command).coerceIn(0, 100)
    }

    suspend fun movementStatus(generation: Long? = null): FuzionMovementStatus =
        FuzionMovementStatus.parse(send(FuzionCommand.actuatorMovementStatus(), generation))

    suspend fun underBedLight(): Pair<FuzionLightLevel, Int> {
        val command = FuzionCommand.underBedLightSettings()
        val tokens = send(command)
        val level = if (tokens.size == 2) FuzionLightLevel.parse(tokens[0].lowercase()) else null
        if (level == null) throw BedError.MalformedResponse("UBLG returned $tokens")
        return level to maxOf(0, FuzionResponse.int(tokens, 1, command))
    }
}
