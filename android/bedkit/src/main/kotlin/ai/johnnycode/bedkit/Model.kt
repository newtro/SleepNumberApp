package ai.johnnycode.bedkit

import ai.johnnycode.bedkit.fuzion.FuzionSide
import ai.johnnycode.bedkit.mcr.MCRWireSide
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Port of BedKit/Sources/Common/Model.swift.

/** Which of Sleep Number's two BLE protocols a bed speaks. */
@Serializable
enum class BedProtocol(val displayName: String) {
    /** Older BAM / MCR binary UART protocol (i8, 360 FlexFit 2 with BAM module). */
    @SerialName("mcr") MCR("MCR (BAM)"),

    /** Newer Fuzion "BamKey" text protocol (Climate 360, newer FlexFit). */
    @SerialName("fuzion") FUZION("Fuzion");

    companion object {
        const val MCR_SERVICE_UUID = "FFFFD1FD-388D-938B-344A-939D1F6EFEE0"
        const val MCR_NOTIFY_UUID = "FFFFD1FD-388D-938B-344A-939D1F6EFEE1"
        const val MCR_WRITE_UUID = "FFFFD1FD-388D-938B-344A-939D1F6EFEE2"

        const val FUZION_SERVICE_UUID = "09D23FAE-90E6-44C2-95B6-0B3D0F1ABF25"
        const val FUZION_AUTH_UUID = "8D4675A5-B5FA-42B2-B587-0EE71C46B709"
        const val FUZION_BAMKEY_UUID = "421E00F3-AE76-4C49-AB6E-39E4DF4A5333"
        const val FUZION_BULK_UUID = "0EC9A5A3-8AC3-4582-92F3-1666421F323D"

        /** Protocol for a discovered primary service UUID, if it is one of ours. */
        fun forService(uuid: String): BedProtocol? = when (uuid.uppercase()) {
            MCR_SERVICE_UUID -> MCR
            FUZION_SERVICE_UUID -> FUZION
            else -> null
        }

        /**
         * Picks the protocol from the set of services a connected bed exposes. MCR wins if both
         * were ever present, because its service is unique to the older BAM module.
         */
        fun detect(serviceUuids: List<String>): BedProtocol? {
            val found = serviceUuids.mapNotNull(::forService).toSet()
            return when {
                MCR in found -> MCR
                FUZION in found -> FUZION
                else -> null
            }
        }
    }
}

/** A side of the bed as the app shows it: left/right as you lie in bed on your back. */
@Serializable
enum class BedSide(val title: String) {
    @SerialName("left") LEFT("Left"),
    @SerialName("right") RIGHT("Right");

    val other: BedSide get() = if (this == LEFT) RIGHT else LEFT
}

/**
 * Maps the app's sides to each protocol's wire sides.
 *
 * MCR: right = 0, left = 1 (`Bed.Side.toDeviceValue()` in the official app). `swapped` flips it,
 * so a bed or a sleeper whose idea of "left" differs can be fixed from Settings.
 */
data class SideMapping(val swapped: Boolean = false) {
    fun mcrSide(side: BedSide): MCRWireSide = when {
        side == BedSide.RIGHT && !swapped || side == BedSide.LEFT && swapped -> MCRWireSide.ZERO
        else -> MCRWireSide.ONE
    }

    fun side(forMcr: MCRWireSide): BedSide = if (mcrSide(BedSide.LEFT) == forMcr) BedSide.LEFT else BedSide.RIGHT

    fun fuzionSide(side: BedSide): FuzionSide = when {
        side == BedSide.LEFT && !swapped || side == BedSide.RIGHT && swapped -> FuzionSide.LEFT
        else -> FuzionSide.RIGHT
    }
}

/** Foundation presets. `id` values are the MCR preset IDs. */
enum class BedPreset(val id: Int, val title: String, val fuzionToken: String) {
    FAVORITE(1, "Favorite", "favorite"),
    READ(2, "Read", "read"),
    WATCH_TV(3, "TV", "watch_tv"),
    FLAT(4, "Flat", "flat"),
    ZERO_G(5, "Zero G", "zero_g"),
    SNORE(6, "Snore", "snore");

    companion object {
        fun fromId(id: Int): BedPreset? = entries.firstOrNull { it.id == id }
        fun fromFuzionToken(token: String): BedPreset? = entries.firstOrNull { it.fuzionToken == token }
    }
}

/** Sleep Number firmness: 5..100 in steps of 5. */
object SleepNumber {
    val range = 5..100
    const val STEP = 5

    /** Clamps and snaps to the nearest valid setting (ties round away from zero, like Swift's rounded()). */
    fun normalize(value: Int): Int {
        val clamped = value.coerceIn(range)
        return Math.round(clamped.toDouble() / STEP).toInt() * STEP
    }

    fun isValid(value: Int): Boolean = value in range && value % STEP == 0
}

enum class MoveDirection {
    UP, DOWN;

    /** The official app's hold-to-move target: 100 for up, 0 for down. */
    val holdTarget: Int get() = if (this == UP) 100 else 0
}
