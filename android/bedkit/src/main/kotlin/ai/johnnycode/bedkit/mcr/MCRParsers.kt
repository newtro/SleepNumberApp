package ai.johnnycode.bedkit.mcr

import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.BedPreset
import ai.johnnycode.bedkit.hex

// Port of BedKit/Sources/MCR/MCRParsers.swift.

/** Result of the bind handshake (`blelib/connection/b.a()`). */
data class MCRBindResult(
    /** Bed address: reply header bytes 1-2. Goes in the destination field of later requests. */
    val bedAddress: Int,
    /** Our assigned address: reply payload bytes 8-9. Goes in the source fields. */
    val clientAddress: Int,
) {
    companion object {
        /** `macFallback` (last two MAC octets) is used only if the reply's source field is zero. */
        fun parse(reply: MCRFrame, macFallback: Int? = null): MCRBindResult {
            val client = if (reply.payload.size >= 10) (reply.payload[8] shl 8) or reply.payload[9] else 0
            val bed = listOf(reply.source, macFallback ?: 0).firstOrNull { it != 0 }
                ?: throw BedError.MalformedResponse("bind reply carried no bed address")
            return MCRBindResult(bed, client)
        }
    }
}

/**
 * Pump status (pump cmd 0x12): `[busy, SN right, SN left]`; reply sub-nibble = chamber type. The busy
 * flag is pump-wide; the app does not report which side is inflating.
 */
data class MCRPumpStatus(
    val busy: Boolean,
    val sleepNumberRight: Int,
    val sleepNumberLeft: Int,
    val chamberType: Int,
) {
    fun sleepNumber(side: MCRWireSide): Int = if (side == MCRWireSide.ZERO) sleepNumberRight else sleepNumberLeft

    companion object {
        fun parse(frame: MCRFrame): MCRPumpStatus {
            val p = frame.payload
            if (p.size < 3) throw BedError.MalformedResponse("pump status needs 3+ bytes, got ${p.size}")
            return MCRPumpStatus(p[0] != 0, p[1], p[2], frame.side)
        }
    }
}

/** One actuator's status byte (foundation status bytes 9-12). */
data class MCRActuatorStatus(val raw: Int) {
    val moving get() = raw and 0x01 != 0
    val limitMissing get() = raw and 0x02 != 0
    val limitUnexpected get() = raw and 0x04 != 0
    val currentUnder get() = raw and 0x08 != 0
    val currentOver get() = raw and 0x10 != 0
    val movementNone get() = raw and 0x20 != 0
    val movementUnexpected get() = raw and 0x40 != 0

    val fault: String?
        get() = when {
            limitMissing -> "limit switch missing"
            limitUnexpected -> "unexpected limit switch"
            currentUnder -> "motor current low"
            currentOver -> "motor current high"
            movementUnexpected -> "unexpected movement"
            else -> null
        }
}

/**
 * Foundation status (foundation cmd 0x12, 15 bytes; `C2991o`).
 *
 * 0 flags (bit0 moving, bit5 needs homing, bit6 configured); 1 right head; 2 left head; 3 right foot;
 * 4 left foot; 5-6 / 7-8 right / left preset timer LE; 9-12 actuator status (right head, left head,
 * right foot, left foot); 13 timer presets; 14 current presets (low nibble right, high nibble left).
 */
data class MCRFoundationStatus(
    val flags: Int,
    val headRight: Int,
    val headLeft: Int,
    val footRight: Int,
    val footLeft: Int,
    /** RH, LH, RF, LF when present. */
    val actuators: List<MCRActuatorStatus>,
    val presetRight: BedPreset?,
    val presetLeft: BedPreset?,
) {
    val moving: Boolean get() = flags and 0x01 != 0 || actuators.any { it.moving }
    val needsHoming: Boolean get() = flags and 0x20 != 0
    val configured: Boolean get() = flags and 0x40 != 0

    fun head(side: MCRWireSide) = if (side == MCRWireSide.ZERO) headRight else headLeft
    fun foot(side: MCRWireSide) = if (side == MCRWireSide.ZERO) footRight else footLeft
    fun position(axis: FoundationAxis, side: MCRWireSide) = if (axis == FoundationAxis.HEAD) head(side) else foot(side)
    fun preset(side: MCRWireSide) = if (side == MCRWireSide.ZERO) presetRight else presetLeft

    fun actuator(axis: FoundationAxis, side: MCRWireSide): MCRActuatorStatus? {
        val index = (if (axis == FoundationAxis.HEAD) 0 else 2) + side.raw
        return actuators.getOrNull(index)
    }

    companion object {
        fun parse(frame: MCRFrame): MCRFoundationStatus {
            val p = frame.payload
            if (p.size < 5) throw BedError.MalformedResponse("foundation status needs 5+ bytes, got ${p.size}")
            for (index in 1..4) {
                if (p[index] > 100) throw BedError.MalformedResponse("foundation position byte $index = ${p[index]} is out of range")
            }
            val actuators = if (p.size >= 13) p.subList(9, 13).map { MCRActuatorStatus(it) } else emptyList()
            return MCRFoundationStatus(
                flags = p[0],
                headRight = p[1], headLeft = p[2],
                footRight = p[3], footLeft = p[4],
                actuators = actuators,
                presetRight = if (p.size >= 15) BedPreset.fromId(p[14] and 0x0F) else null,
                presetLeft = if (p.size >= 15) BedPreset.fromId(p[14] shr 4) else null,
            )
        }
    }
}

/** Under-bed light outlet status (foundation cmd 0x14, outlet 3): `[on, timer LE]`. */
data class MCRLightStatus(val on: Boolean, val timerMinutes: Int) {
    companion object {
        fun parse(frame: MCRFrame): MCRLightStatus {
            val p = frame.payload
            val first = p.firstOrNull() ?: throw BedError.MalformedResponse("light status was empty")
            val timer = if (p.size >= 3) p[1] or (p[2] shl 8) else 0
            return MCRLightStatus(first != 0, timer)
        }
    }
}

/** Foundation system status (foundation cmd 0x25; `C2993q`, `device/model/a`). */
data class MCRSystemStatus(val raw: List<Int>) {
    enum class Configuration(val raw: Int) { SINGLE(0), SPLIT_HEAD(1), SPLIT_KING(2), EASTERN_KING(3) }
    enum class Model(val label: String) { FF1("FF1"), LEGACY_FF2("legacy FF2"), LEGACY_FF3("legacy FF3"), FF2("360 FF2"), FF3("360 FF3") }

    val configuration: Configuration? get() = raw.firstOrNull()?.let { r -> Configuration.entries.firstOrNull { it.raw == r } }
    private val flags: Int get() = if (raw.size > 3) raw[3] else 0
    val dualBoard get() = flags and 0x01 != 0
    val hasMassageAndLighting get() = flags and 0x02 != 0
    val hasFootControl get() = flags and 0x04 != 0
    val hasFootWarming get() = flags and 0x08 != 0
    val hasUnderBedLight get() = flags and 0x10 != 0
    val leftBoardUnderperforming get() = raw.size > 6 && raw[6] and 0x04 != 0
    val rightBoardUnderperforming get() = raw.size > 6 && raw[6] and 0x08 != 0
    val isSplit get() = configuration == Configuration.SPLIT_HEAD || configuration == Configuration.SPLIT_KING

    /** FlexFit classification (`device/model/a.b()`). */
    val model: Model
        get() = when {
            hasFootControl && hasUnderBedLight -> if (hasFootWarming) Model.FF3 else Model.FF2
            hasFootControl -> if (hasMassageAndLighting) Model.LEGACY_FF3 else Model.LEGACY_FF2
            else -> Model.FF1
        }

    /** Presets the official app offers for this foundation, in its order. */
    val presets: List<BedPreset>
        get() = when (model) {
            Model.FF3, Model.LEGACY_FF3 -> listOf(BedPreset.FAVORITE, BedPreset.FLAT, BedPreset.ZERO_G, BedPreset.SNORE, BedPreset.WATCH_TV, BedPreset.READ)
            Model.FF2, Model.LEGACY_FF2 -> listOf(BedPreset.FAVORITE, BedPreset.FLAT, BedPreset.ZERO_G, BedPreset.SNORE)
            Model.FF1 -> if (hasUnderBedLight) listOf(BedPreset.FAVORITE, BedPreset.FLAT, BedPreset.SNORE) else listOf(BedPreset.FAVORITE, BedPreset.FLAT)
        }

    /** The app offers Partner Snore (Snore sent to the other side) on split bases. */
    val offersPartnerSnore: Boolean get() = isSplit && BedPreset.SNORE in presets

    override fun toString(): String {
        val config = configuration?.name?.lowercase() ?: "unknown"
        val features = buildList {
            if (dualBoard) add("dual board")
            if (hasFootControl) add("foot")
            if (hasUnderBedLight) add("under-bed light")
            if (hasFootWarming) add("foot warming")
            if (hasMassageAndLighting) add("massage")
        }
        return "${model.label}, $config, ${features.joinToString(", ")}, raw [${raw.hex()}]"
    }

    companion object {
        fun parse(frame: MCRFrame): MCRSystemStatus {
            if (frame.payload.size < 4) throw BedError.MalformedResponse("system status needs 4+ bytes")
            return MCRSystemStatus(frame.payload)
        }
    }
}

/** Obstruction sensors (foundation cmd 0x28, 5 bytes; `C2996u`). */
data class MCRPinchState(val raw: List<Int>) {
    fun disconnected(axis: FoundationAxis, side: MCRWireSide): Boolean {
        val bit = if (axis == FoundationAxis.HEAD) (if (side == MCRWireSide.ZERO) 2 else 3) else (if (side == MCRWireSide.ZERO) 0 else 1)
        return raw[0] and (1 shl bit) != 0
    }

    fun continuousPinch(axis: FoundationAxis, side: MCRWireSide): Boolean {
        val bit = if (axis == FoundationAxis.HEAD) (if (side == MCRWireSide.ZERO) 6 else 7) else (if (side == MCRWireSide.ZERO) 4 else 5)
        return raw[0] and (1 shl bit) != 0
    }

    companion object {
        fun parse(frame: MCRFrame): MCRPinchState {
            if (frame.payload.size < 5) throw BedError.MalformedResponse("pinch state needs 5 bytes")
            return MCRPinchState(frame.payload)
        }
    }
}
