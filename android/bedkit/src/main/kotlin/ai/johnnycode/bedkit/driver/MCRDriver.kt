package ai.johnnycode.bedkit.driver

import ai.johnnycode.bedkit.BedCharacteristic
import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.BedLink
import ai.johnnycode.bedkit.BedPreset
import ai.johnnycode.bedkit.BedProtocol
import ai.johnnycode.bedkit.BedSide
import ai.johnnycode.bedkit.FrameEvent
import ai.johnnycode.bedkit.FrameLogger
import ai.johnnycode.bedkit.MoveDirection
import ai.johnnycode.bedkit.SideMapping
import ai.johnnycode.bedkit.SleepNumber
import ai.johnnycode.bedkit.favorites.FavoriteBedOps
import ai.johnnycode.bedkit.favorites.PositionReading
import ai.johnnycode.bedkit.favorites.PumpReading
import ai.johnnycode.bedkit.hex
import ai.johnnycode.bedkit.mcr.FoundationAxis
import ai.johnnycode.bedkit.mcr.MCRCommands
import ai.johnnycode.bedkit.mcr.MCRFoundationStatus
import ai.johnnycode.bedkit.mcr.MCRPumpStatus
import ai.johnnycode.bedkit.mcr.MCRSession
import ai.johnnycode.bedkit.mcr.MCRSystemStatus
import ai.johnnycode.bedkit.mcr.MCRWireSide
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Port of BedControl/Control/MCRDriver.swift (including the review-round-2 confirmed stops).

/** Confirmed stops: attempts, and the wait before re-reading status. */
const val STOP_ATTEMPTS = 3
const val STOP_CONFIRM_DELAY_MS = 300L

/** MCR (BAM) bed driver, following the official app (docs/PROTOCOL-FROM-APK.md §2, §4). */
class MCRDriver(
    private val link: BedLink,
    private val log: FrameLogger,
    clientId: List<Int>,
    macFallback: Int?,
    private val mapping: () -> SideMapping,
    timing: MCRSession.Timing = MCRSession.Timing(),
    private val clock: Clock = systemClock,
) : BedDriver {
    override val kind = BedProtocol.MCR
    val session = MCRSession(link, clientId, macFallback, timing, log)
    var system: MCRSystemStatus? = null
        private set
    private var nodes: List<Int> = emptyList()

    private fun info(text: String) = log(FrameEvent(FrameEvent.Direction.INFO, "MCR", text = text))
    private fun error(text: String) = log(FrameEvent(FrameEvent.Direction.ERROR, "MCR", text = text))

    /** Presets the app offers for the reported foundation; a 360 FlexFit 2 split head if unknown. */
    override val presetOptions: List<PresetOption>
        get() {
            val presets = system?.presets ?: listOf(BedPreset.FAVORITE, BedPreset.FLAT, BedPreset.ZERO_G, BedPreset.SNORE)
            val options: MutableList<PresetOption> = presets.map { PresetOption.Preset(it) }.toMutableList()
            if (system?.offersPartnerSnore ?: true) options += PresetOption.PartnerSnore
            return options
        }

    override val hasUnderBedLight: Boolean get() = system?.hasUnderBedLight ?: true

    override val diagnostics: List<Pair<String, String>>
        get() = buildList {
            add("Protocol" to kind.displayName)
            session.bind?.let {
                add("Bed address" to "0x%04X%s".format(it.bedAddress, if (it.bedAddressFromMac) " (from MAC)" else ""))
                add("Client address" to "0x%04X".format(it.clientAddress))
            }
            if (nodes.isNotEmpty()) add("Nodes" to nodes.hex())
            system?.let { add("Foundation" to it.toString()) }
            val map = mapping()
            add("Sides" to "Left = MCR ${map.mcrSide(BedSide.LEFT).raw}, Right = MCR ${map.mcrSide(BedSide.RIGHT).raw}")
        }

    override suspend fun start() {
        delay(128) // the app's SERVICE_DISCOVERY_DELAY
        link.enableNotifications(BedCharacteristic.MCR_NOTIFY)
        session.performBind()
        // Configuration reads are informational; failures do not block control.
        try {
            nodes = session.readNodeList()
        } catch (e: BedError) {
            info("node list unavailable: ${e.message}")
        }
        try {
            system = session.readSystemStatus()
        } catch (e: BedError) {
            info("system status unavailable: ${e.message}")
        }
    }

    override suspend fun refresh(includeLight: Boolean): BedReading {
        val reading = BedReading()
        val pump = session.readPumpStatus()
        val map = mapping()
        for (side in BedSide.entries) reading.firmness[side] = pump.sleepNumber(map.mcrSide(side))
        reading.pumpBusy = pump.busy
        try {
            apply(session.readFoundationStatus(), reading)
        } catch (e: BedError.MalformedResponse) {
            // Keep the pump result (the firmness queue depends on it) even if the foundation reply is
            // unusable, e.g. out-of-range positions while homing.
            error("foundation status unusable: ${e.what}")
        }
        if (includeLight && hasUnderBedLight) {
            try {
                reading.lightOn = session.readUnderBedLight().on
            } catch (e: BedError) {
                info("light read failed: ${e.message}")
            }
        }
        return reading
    }

    override suspend fun activate(preset: BedPreset, side: BedSide) {
        val wire = mapping().mcrSide(side)
        if (preset != BedPreset.FLAT) {
            val status = try { session.readFoundationStatus() } catch (_: BedError) { null }
            if (status?.needsHoming == true) throw BedError.CommandFailed("the foundation needs homing; press Flat first")
        }
        session.activatePreset(preset, wire)
    }

    override suspend fun setFirmness(value: Int, side: BedSide, interruptRunning: Boolean) {
        session.setSleepNumber(value, mapping().mcrSide(side), interruptRunning)
    }

    override suspend fun setLight(on: Boolean) = session.setUnderBedLight(on)

    override suspend fun hold(axis: FoundationAxis, side: BedSide, direction: MoveDirection, update: (BedReading) -> Unit) {
        // Each sleeper's own side, for the foot too: the firmware maps it onto the shared motor.
        val wire = mapping().mcrSide(side)
        var status = session.readFoundationStatus()
        update(reading(status))
        check(status, axis, wire)
        if (system?.hasUnderBedLight ?: true) { // 360 bases: the app checks obstruction sensors first
            val pinch = session.readPinchState()
            if (pinch.disconnected(axis, wire) || pinch.continuousPinch(axis, wire)) {
                throw BedError.CommandFailed("the ${axis.title} obstruction sensor reports a fault")
            }
        }
        val current = status.position(axis, wire)
        if (direction == MoveDirection.UP && current >= 100 || direction == MoveDirection.DOWN && current <= 0) return
        session.setFoundationTarget(wire, axis, direction.holdTarget)

        val started = clock()
        while (clock() - started < HoldLimits.MAX_HOLD_MS) {
            delay(HoldLimits.POLL_INTERVAL_MS)
            // "Keep adjusting" read (sub side+2): the app's poll while a micro-adjust runs.
            status = session.readFoundationStatus(keepAdjusting = wire)
            update(reading(status))
            check(status, axis, wire)
            // Give the motor a moment to start before treating "not moving" as finished.
            if (!status.moving && clock() - started > HoldLimits.START_GRACE_MS) {
                info("hold: foundation reports stopped")
                return
            }
        }
        info("hold: safety deadline reached")
    }

    private fun check(status: MCRFoundationStatus, axis: FoundationAxis, side: MCRWireSide) {
        if (status.needsHoming) throw BedError.CommandFailed("the foundation needs homing; press Flat first")
        status.actuator(axis, side)?.fault?.let { throw BedError.CommandFailed("${axis.title} actuator: $it") }
    }

    override suspend fun releaseHold(axis: FoundationAxis, side: BedSide): BedReading? =
        stopConfirmed(listOf(mapping().mcrSide(side)))

    override suspend fun stopAll() {
        var firstError: BedError? = null
        withContext(NonCancellable) {
            try {
                session.stopPump()
            } catch (e: BedError) {
                firstError = e
            }
            stopConfirmed(MCRWireSide.entries)
        }
        firstError?.let { throw it }
    }

    /**
     * Sends the stop key (urgent, then through the queue), then checks the foundation; re-sends while it
     * still reports motion. Runs under NonCancellable. Returns the last reading, if any.
     */
    suspend fun stopConfirmed(sides: List<MCRWireSide>): BedReading? = withContext(NonCancellable) {
        var last: MCRFoundationStatus? = null
        for (attempt in 1..STOP_ATTEMPTS) {
            try {
                session.stopFoundation(sides)
            } catch (e: BedError) {
                error("stop attempt $attempt failed: ${e.message}")
            }
            delay(STOP_CONFIRM_DELAY_MS)
            last = try { session.readFoundationStatus() } catch (_: BedError) { null }
            if (last != null && !last.moving) break
            error("foundation still moving (or unreadable) after stop attempt $attempt")
        }
        last?.let { reading(it) }
    }

    /** Confirmed pump idle: ForceIdle, re-read, resend while still busy. */
    private suspend fun idlePumpConfirmed(): Boolean = withContext(NonCancellable) {
        for (attempt in 1..STOP_ATTEMPTS) {
            try {
                session.stopPump()
            } catch (e: BedError) {
                error("pump idle attempt $attempt failed: ${e.message}")
            }
            delay(STOP_CONFIRM_DELAY_MS)
            val busy = try { session.readPumpStatus().busy } catch (_: BedError) { null }
            if (busy == false) return@withContext true
            error("pump still busy (or unreadable) after idle attempt $attempt")
        }
        false
    }

    override fun favoriteOps(side: BedSide): FavoriteBedOps = Ops(mapping().mcrSide(side))

    /**
     * MCR favorite operations: absolute moves use the app's Sleep Expert keys (MFU[R|L] / MFF[R|L] =
     * "<pos>_0"), progress comes from foundation status, firmness uses pump cmd 0x11 and pump status.
     * Every request carries the stop generation captured at creation, so after STOP none goes out.
     */
    private inner class Ops(private val side: MCRWireSide) : FavoriteBedOps {
        private val generation = session.stopGeneration

        override suspend fun startMove(axis: FoundationAxis, position: Int) {
            session.send(MCRCommands.setPosition(side, axis, position), generation = generation)
        }

        override suspend fun readPosition(axis: FoundationAxis): PositionReading {
            val status = MCRFoundationStatus.parse(session.send(MCRCommands.readFoundationStatus(), generation = generation))
            if (status.needsHoming) throw BedError.CommandFailed("the foundation needs homing; press Flat first")
            status.actuator(axis, side)?.fault?.let { throw BedError.CommandFailed("${axis.title} actuator: $it") }
            return PositionReading(status.position(axis, side), status.moving)
        }

        override suspend fun stopMove(): Boolean = stopConfirmed(listOf(side))?.moving == false

        override suspend fun startSleepNumber(value: Int) {
            session.send(MCRCommands.setSleepNumber(side, SleepNumber.normalize(value)), generation = generation)
        }

        override suspend fun readPump(): PumpReading {
            val status = MCRPumpStatus.parse(session.send(MCRCommands.readPumpStatus(), generation = generation))
            return PumpReading(status.sleepNumber(side), status.busy)
        }

        override suspend fun stopPump(): Boolean = idlePumpConfirmed()
    }

    override fun handleNotification(characteristic: BedCharacteristic, data: ByteArray) {
        if (characteristic == BedCharacteristic.MCR_NOTIFY) session.handleNotification(data)
    }

    override fun reset() = session.reset()

    private fun reading(status: MCRFoundationStatus): BedReading = BedReading().also { apply(status, it) }

    private fun apply(status: MCRFoundationStatus, reading: BedReading) {
        val map = mapping()
        for (side in BedSide.entries) {
            val wire = map.mcrSide(side)
            reading.head[side] = status.head(wire)
            reading.foot[side] = status.foot(wire)
            status.preset(wire)?.let { reading.activePreset[side] = it }
        }
        reading.moving = status.moving
    }
}
