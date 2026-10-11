package ai.johnnycode.bedkit.fuzion

import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.BedPreset
import ai.johnnycode.bedkit.SleepNumber
import ai.johnnycode.bedkit.mcr.FoundationAxis

// Port of BedKit/Sources/Fuzion/FuzionCommands.swift.

enum class FuzionSide(val raw: String) { LEFT("left"), RIGHT("right") }

enum class FuzionActuator(val raw: String) {
    HEAD("head"), FOOT("foot");

    companion object {
        fun of(axis: FoundationAxis) = if (axis == FoundationAxis.HEAD) HEAD else FOOT
    }
}

enum class FuzionLightLevel(val raw: String) {
    OFF("off"), LOW("low"), MEDIUM("medium"), HIGH("high");

    companion object {
        fun parse(raw: String) = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * A BamKey request: a 4-letter key plus space-separated arguments. The wire text is `"KEY arg1 arg2"`,
 * and `"KEY "` (trailing space) when there are no arguments.
 */
data class FuzionCommand internal constructor(val key: String, val arguments: List<String> = emptyList()) {
    val text: String get() = "$key ${arguments.joinToString(" ")}"
    val frame: ByteArray get() = FuzionFrame.encode(text)
    override fun toString() = text

    companion object {
        /** SYCG: system configuration (chamber type, actuators present, presets enabled…). */
        fun systemConfiguration() = FuzionCommand("SYCG")

        /** SNCG side -> [adjusting 0/1, ambient sleep number, user sleep number]. */
        fun sleepNumberControls(side: FuzionSide) = FuzionCommand("SNCG", listOf(side.raw))

        /** PSNS side value: start a firmness adjustment. */
        fun startSleepNumberAdjustment(side: FuzionSide, value: Int): FuzionCommand {
            if (!SleepNumber.isValid(value)) throw BedError.InvalidArgument("Sleep Number $value must be 5...100 in steps of 5")
            return FuzionCommand("PSNS", listOf(side.raw, value.toString()))
        }

        /** PSNI: interrupt a running firmness adjustment. */
        fun interruptSleepNumberAdjustment() = FuzionCommand("PSNI")

        /** ASTP side preset: recall a preset (`SetFuzionFlexFitPresetCall`). */
        fun setPreset(side: FuzionSide, preset: BedPreset) = FuzionCommand("ASTP", listOf(side.raw, preset.fuzionToken))

        /** ASTM side actuator target: micro-adjust used for hold-to-move (target 100 up / 0 down). */
        fun microAdjust(side: FuzionSide, actuator: FuzionActuator, target: Int): FuzionCommand {
            if (target !in 0..100) throw BedError.InvalidArgument("${actuator.raw} target $target must be 0...100")
            return FuzionCommand("ASTM", listOf(side.raw, actuator.raw, target.toString()))
        }

        /** ACTS side actuator position: move one actuator to an absolute 0...100 target. */
        fun setActuatorTarget(side: FuzionSide, actuator: FuzionActuator, position: Int): FuzionCommand {
            if (position !in 0..100) throw BedError.InvalidArgument("${actuator.raw} position $position must be 0...100")
            return FuzionCommand("ACTS", listOf(side.raw, actuator.raw, position.toString()))
        }

        /** ACTG side actuator -> [position]. */
        fun actuatorPosition(side: FuzionSide, actuator: FuzionActuator) = FuzionCommand("ACTG", listOf(side.raw, actuator.raw))

        /** ACTM -> [right_head, right_foot, left_head, left_foot] moving flags. */
        fun actuatorMovementStatus() = FuzionCommand("ACTM")

        /** ACHA: halt all actuators (global — stops both sides). */
        fun haltAllActuators() = FuzionCommand("ACHA")

        /** AGCP side -> [current preset]. */
        fun currentPreset(side: FuzionSide) = FuzionCommand("AGCP", listOf(side.raw))

        /** UBLG -> [level, timer minutes]. */
        fun underBedLightSettings() = FuzionCommand("UBLG")

        /** UBLS level timer. */
        fun setUnderBedLight(level: FuzionLightLevel, timerMinutes: Int): FuzionCommand {
            if (timerMinutes !in 0..180) throw BedError.InvalidArgument("light timer $timerMinutes must be 0...180 minutes")
            return FuzionCommand("UBLS", listOf(level.raw, timerMinutes.toString()))
        }
    }
}

/** Parsed BamKey response text. */
object FuzionResponse {
    /** `PASS:...` -> argument tokens. `FAIL:0` unknown key, `FAIL:1` device timeout, `FAIL:2` generic error. */
    fun parse(text: String, command: FuzionCommand): List<String> {
        val response = text.trim()
        val payload = when {
            response.startsWith("[") -> response
            response.startsWith("PASS:ACK") -> response.removePrefix("PASS:ACK")
            response.startsWith("PASS:") -> response.removePrefix("PASS:")
            response.startsWith("FAIL:0") -> throw BedError.UnsupportedCommand(command.key)
            response.startsWith("FAIL:1") -> throw BedError.Timeout("${command.key} (bed reported a device timeout)")
            response.startsWith("FAIL") -> throw BedError.CommandFailed("${command.key}: $response")
            else -> throw BedError.MalformedResponse("${command.key} returned \"$response\"")
        }
        return payload.split(" ").filter { it.isNotEmpty() }
    }

    fun int(tokens: List<String>, index: Int, command: FuzionCommand): Int =
        tokens.getOrNull(index)?.toIntOrNull()
            ?: throw BedError.MalformedResponse("${command.key} field $index missing or not a number: $tokens")
}

/** ACTM reply: moving flags in the fixed order right head, right foot, left head, left foot. */
data class FuzionMovementStatus(
    val rightHead: Boolean,
    val rightFoot: Boolean,
    val leftHead: Boolean,
    val leftFoot: Boolean,
) {
    val any: Boolean get() = rightHead || rightFoot || leftHead || leftFoot

    fun moving(side: FuzionSide, actuator: FuzionActuator): Boolean = when (side) {
        FuzionSide.RIGHT -> if (actuator == FuzionActuator.HEAD) rightHead else rightFoot
        FuzionSide.LEFT -> if (actuator == FuzionActuator.HEAD) leftHead else leftFoot
    }

    companion object {
        fun parse(tokens: List<String>): FuzionMovementStatus {
            if (tokens.size != 4 || !tokens.all { it == "0" || it == "1" }) {
                throw BedError.MalformedResponse("ACTM returned $tokens")
            }
            return FuzionMovementStatus(tokens[0] == "1", tokens[1] == "1", tokens[2] == "1", tokens[3] == "1")
        }
    }
}

/** SNCG reply. */
data class FuzionSleepNumberControls(val adjusting: Boolean, val ambient: Int, val user: Int) {
    companion object {
        fun parse(tokens: List<String>, command: FuzionCommand): FuzionSleepNumberControls {
            if (tokens.size != 3) throw BedError.MalformedResponse("SNCG returned ${tokens.size} fields: $tokens")
            return FuzionSleepNumberControls(
                tokens[0] == "1",
                FuzionResponse.int(tokens, 1, command),
                FuzionResponse.int(tokens, 2, command),
            )
        }
    }
}

/** SYCG reply: 16 fields in the app's constructor order. */
data class FuzionSystemConfiguration(val fields: Map<String, String>) {
    val isSingleChamber: Boolean get() = fields["chamber_type"] == "single"
    val hasUnderBedLight: Boolean get() = fields["underbed_light"] != "no"

    /** Split means both sides report at least one actuator. */
    val isSplit: Boolean
        get() = (fields["left_head_actuator"] == "yes" || fields["left_foot_actuator"] == "yes") &&
            (fields["right_head_actuator"] == "yes" || fields["right_foot_actuator"] == "yes")

    fun supports(preset: BedPreset): Boolean {
        val key = when (preset) {
            BedPreset.FLAT -> "flat_preset"
            BedPreset.FAVORITE -> "favorite_preset"
            BedPreset.SNORE -> "snore_preset"
            BedPreset.ZERO_G -> "zero_gravity_preset"
            BedPreset.WATCH_TV -> "watch_tv_preset"
            BedPreset.READ -> "read_preset"
        }
        return fields[key] != "no"
    }

    companion object {
        val FIELD_NAMES = listOf(
            "chamber_type", "pressure_control", "articulation", "underbed_light", "rapid_sleep_setting",
            "thermal_control", "right_head_actuator", "right_foot_actuator", "left_head_actuator",
            "left_foot_actuator", "flat_preset", "favorite_preset", "snore_preset", "zero_gravity_preset",
            "watch_tv_preset", "read_preset",
        )

        fun parse(tokens: List<String>): FuzionSystemConfiguration {
            if (tokens.size != FIELD_NAMES.size) {
                throw BedError.MalformedResponse("SYCG returned ${tokens.size} fields, expected 16: $tokens")
            }
            return FuzionSystemConfiguration(FIELD_NAMES.zip(tokens).toMap())
        }
    }
}
