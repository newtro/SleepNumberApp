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
import ai.johnnycode.bedkit.favorites.Favorite
import ai.johnnycode.bedkit.favorites.FavoriteBedOps
import ai.johnnycode.bedkit.favorites.PositionReading
import ai.johnnycode.bedkit.favorites.PumpReading
import ai.johnnycode.bedkit.fuzion.FuzionActuator
import ai.johnnycode.bedkit.fuzion.FuzionCommand
import ai.johnnycode.bedkit.fuzion.FuzionLightLevel
import ai.johnnycode.bedkit.fuzion.FuzionMovementStatus
import ai.johnnycode.bedkit.fuzion.FuzionResponse
import ai.johnnycode.bedkit.fuzion.FuzionSession
import ai.johnnycode.bedkit.fuzion.FuzionSide
import ai.johnnycode.bedkit.fuzion.FuzionSleepNumberControls
import ai.johnnycode.bedkit.fuzion.FuzionSystemConfiguration
import ai.johnnycode.bedkit.hex
import ai.johnnycode.bedkit.mcr.FoundationAxis
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Port of BedControl/Control/FuzionDriver.swift (including the review-round-2 confirmed halt).

/** Fuzion (BamKey) bed driver, following the official app (docs/PROTOCOL-FROM-APK.md §3, §4). */
class FuzionDriver(
    private val link: BedLink,
    private val log: FrameLogger,
    private val mapping: () -> SideMapping,
    timing: FuzionSession.Timing = FuzionSession.Timing(),
    private val clock: Clock = systemClock,
) : BedDriver {
    override val kind = BedProtocol.FUZION
    val session = FuzionSession(link, timing, log)
    private var config: FuzionSystemConfiguration? = null
    private var lightTimer = 0
    private var lastLightLevel = FuzionLightLevel.HIGH

    private fun info(text: String) = log(FrameEvent(FrameEvent.Direction.INFO, "Fuzion", text = text))
    private fun error(text: String) = log(FrameEvent(FrameEvent.Direction.ERROR, "Fuzion", text = text))

    override val presetOptions: List<PresetOption>
        get() {
            val order = listOf(BedPreset.FAVORITE, BedPreset.FLAT, BedPreset.ZERO_G, BedPreset.SNORE, BedPreset.WATCH_TV, BedPreset.READ)
            val presets = config?.let { c -> order.filter { c.supports(it) } } ?: order
            val options: MutableList<PresetOption> = presets.map { PresetOption.Preset(it) }.toMutableList()
            if (BedPreset.SNORE in presets && (config?.isSplit ?: true)) options += PresetOption.PartnerSnore
            return options
        }

    override val hasUnderBedLight: Boolean get() = config?.hasUnderBedLight ?: true

    override val diagnostics: List<Pair<String, String>>
        get() = buildList {
            add("Protocol" to kind.displayName)
            session.sessionId?.let { add("Session" to it.hex()) }
            config?.let { c -> FuzionSystemConfiguration.FIELD_NAMES.forEach { add(it to (c.fields[it] ?: "?")) } }
            add("Sides" to "Left = Fuzion ${mapping().fuzionSide(BedSide.LEFT).raw}")
        }

    override suspend fun start() {
        // Reading Auth is what makes the phone pair with the bed if it requires encryption.
        val id = session.authenticate()
        info("session ${id.hex()}")
        link.enableNotifications(BedCharacteristic.FUZION_BAMKEY)
        try {
            config = session.systemConfiguration()
        } catch (e: BedError) {
            info("SYCG unavailable: ${e.message}")
        }
    }

    private fun hasActuator(side: FuzionSide, actuator: FuzionActuator): Boolean =
        config?.fields?.get("${side.raw}_${actuator.raw}_actuator") != "no"

    override suspend fun refresh(includeLight: Boolean): BedReading {
        val reading = BedReading()
        val map = mapping()
        var busy = false
        for (side in BedSide.entries) {
            val wire = map.fuzionSide(side)
            if (config?.isSingleChamber != true || wire == FuzionSide.RIGHT) {
                val controls = session.sleepNumberControls(wire)
                reading.firmness[side] = controls.user
                busy = busy || controls.adjusting
            }
            if (hasActuator(wire, FuzionActuator.HEAD)) reading.head[side] = session.actuatorPosition(wire, FuzionActuator.HEAD)
            if (hasActuator(wire, FuzionActuator.FOOT)) reading.foot[side] = session.actuatorPosition(wire, FuzionActuator.FOOT)
        }
        reading.pumpBusy = busy
        try {
            reading.moving = session.movementStatus().any
        } catch (e: BedError) {
            info("ACTM failed: ${e.message}")
        }
        if (includeLight && hasUnderBedLight) {
            try {
                val (level, timer) = session.underBedLight()
                lightTimer = timer
                if (level != FuzionLightLevel.OFF) lastLightLevel = level
                reading.lightOn = level != FuzionLightLevel.OFF
            } catch (e: BedError) {
                info("light read failed: ${e.message}")
            }
        }
        return reading
    }

    override suspend fun activate(preset: BedPreset, side: BedSide) {
        session.send(FuzionCommand.setPreset(mapping().fuzionSide(side), preset))
    }

    override suspend fun setFirmness(value: Int, side: BedSide, interruptRunning: Boolean) {
        val gen = session.stopGeneration
        val command = FuzionCommand.startSleepNumberAdjustment(mapping().fuzionSide(side), value)
        if (interruptRunning) {
            session.send(FuzionCommand.interruptSleepNumberAdjustment(), gen)
            delay(500)
        }
        session.send(command, gen)
    }

    override suspend fun setLight(on: Boolean) {
        session.send(FuzionCommand.setUnderBedLight(if (on) lastLightLevel else FuzionLightLevel.OFF, lightTimer))
    }

    override suspend fun hold(axis: FoundationAxis, side: BedSide, direction: MoveDirection, update: (BedReading) -> Unit) {
        // Each sleeper's own side, for the foot too (the app never redirects foot commands).
        val wire = mapping().fuzionSide(side)
        val actuator = FuzionActuator.of(axis)
        session.send(FuzionCommand.microAdjust(wire, actuator, direction.holdTarget))

        val started = clock()
        while (clock() - started < HoldLimits.MAX_HOLD_MS) {
            delay(HoldLimits.POLL_INTERVAL_MS)
            val movement = session.movementStatus()
            val position = session.actuatorPosition(wire, actuator)
            val reading = BedReading()
            if (axis == FoundationAxis.FOOT) reading.foot[side] = position else reading.head[side] = position
            reading.moving = movement.any
            update(reading)
            if (!movement.any && clock() - started > HoldLimits.START_GRACE_MS) {
                info("hold: actuators report stopped")
                return
            }
        }
        info("hold: safety deadline reached")
    }

    override suspend fun releaseHold(axis: FoundationAxis, side: BedSide): BedReading? = withContext(NonCancellable) {
        // ACHA is the app's release; it halts every actuator, so it also stops the other side.
        haltConfirmed()
        val wire = mapping().fuzionSide(side)
        val position = try { session.actuatorPosition(wire, FuzionActuator.of(axis)) } catch (_: BedError) { null }
        position?.let {
            BedReading().apply {
                if (axis == FoundationAxis.FOOT) foot[side] = it else head[side] = it
                moving = false
            }
        }
    }

    override suspend fun stopAll() {
        var firstError: BedError? = null
        withContext(NonCancellable) {
            try {
                session.sendUrgent(FuzionCommand.interruptSleepNumberAdjustment())
            } catch (e: BedError) {
                firstError = e
            }
            haltConfirmed()
        }
        firstError?.let { throw it }
    }

    /**
     * ACHA written immediately, then through the queue until PASS, then re-sent while ACTM still reports
     * movement (the app sends ACHA through its mutex; this does both). True when confirmed stopped.
     */
    private suspend fun haltConfirmed(): Boolean = withContext(NonCancellable) {
        try {
            session.sendUrgent(FuzionCommand.haltAllActuators())
        } catch (e: BedError) {
            error("urgent halt failed: ${e.message}")
        }
        for (attempt in 1..STOP_ATTEMPTS) {
            try {
                session.send(FuzionCommand.haltAllActuators(), isStop = true)
                delay(STOP_CONFIRM_DELAY_MS)
                val movement = FuzionMovementStatus.parse(session.send(FuzionCommand.actuatorMovementStatus(), isStop = true))
                if (!movement.any) return@withContext true
                error("still moving after halt attempt $attempt")
            } catch (e: BedError) {
                error("halt attempt $attempt failed: ${e.message}")
            }
        }
        false
    }

    /** Confirmed PSNI: interrupt, re-read SNCG, resend while still adjusting. */
    private suspend fun idlePumpConfirmed(side: FuzionSide): Boolean = withContext(NonCancellable) {
        try {
            session.sendUrgent(FuzionCommand.interruptSleepNumberAdjustment())
        } catch (e: BedError) {
            error("urgent PSNI failed: ${e.message}")
        }
        for (attempt in 1..STOP_ATTEMPTS) {
            try {
                session.send(FuzionCommand.interruptSleepNumberAdjustment(), isStop = true)
                delay(STOP_CONFIRM_DELAY_MS)
                val command = FuzionCommand.sleepNumberControls(side)
                val controls = FuzionSleepNumberControls.parse(session.send(command, isStop = true), command)
                if (!controls.adjusting) return@withContext true
                error("pump still adjusting after PSNI attempt $attempt")
            } catch (e: BedError) {
                error("PSNI attempt $attempt failed: ${e.message}")
            }
        }
        false
    }

    override fun favoriteOps(side: BedSide): FavoriteBedOps = Ops(mapping().fuzionSide(side))

    /** Fuzion favorite operations: absolute moves use ACTS, progress ACTG + ACTM, firmness PSNS + SNCG. */
    private inner class Ops(private val side: FuzionSide) : FavoriteBedOps {
        private val generation = session.stopGeneration

        override suspend fun startMove(axis: FoundationAxis, position: Int) {
            session.send(FuzionCommand.setActuatorTarget(side, FuzionActuator.of(axis), position), generation)
        }

        override suspend fun readPosition(axis: FoundationAxis): PositionReading {
            val actuator = FuzionActuator.of(axis)
            val movement = FuzionMovementStatus.parse(session.send(FuzionCommand.actuatorMovementStatus(), generation))
            val command = FuzionCommand.actuatorPosition(side, actuator)
            val tokens = session.send(command, generation)
            if (tokens.size != 1) throw BedError.MalformedResponse("ACTG returned $tokens")
            val position = Favorite.clampPosition(FuzionResponse.int(tokens, 0, command))
            return PositionReading(position, movement.moving(side, actuator))
        }

        override suspend fun stopMove(): Boolean = haltConfirmed()

        override suspend fun startSleepNumber(value: Int) {
            session.send(FuzionCommand.startSleepNumberAdjustment(side, SleepNumber.normalize(value)), generation)
        }

        override suspend fun readPump(): PumpReading {
            val controls = session.sleepNumberControls(side, generation)
            return PumpReading(controls.user, controls.adjusting)
        }

        override suspend fun stopPump(): Boolean = idlePumpConfirmed(side)
    }

    override fun handleNotification(characteristic: BedCharacteristic, data: ByteArray) {
        when (characteristic) {
            BedCharacteristic.FUZION_BAMKEY -> session.handleNotification(data)
            BedCharacteristic.FUZION_BULK -> log(FrameEvent(FrameEvent.Direction.RX, "Bulk", data, "bulk notify ${data.size}b"))
            else -> Unit
        }
    }

    override fun reset() = session.reset()
}
