package ai.johnnycode.bedkit.mcr

import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.BedPreset
import ai.johnnycode.bedkit.SleepNumber

// Port of BedKit/Sources/MCR/MCRCommands.swift.

/**
 * MCR node classes as used in requests (header bytes 0 and 5). Replies carry the node's own class
 * ID with the low nibble 1 (0x01, 0x41, 0x51, 0x71). Source: `device/model/Node`.
 */
enum class MCRNode(val raw: Int) {
    PUMP(0x02),
    FOUNDATION(0x42),
    SLEEP_EXPERT(0x52),
    PROXY(0x72),
}

/** MCR commands (header byte 8). Source: docs/PROTOCOL-FROM-APK.md §2.4. */
object MCRFunction {
    const val BIND = 0x00
    const val FORCE_IDLE = 0x02
    const val SET = 0x11
    const val READ = 0x12
    const val OUTLET_WRITE = 0x13
    const val OUTLET_READ = 0x14
    const val PRESET = 0x15
    const val SE_SHORT_WRITE = 0x1B
    const val SYSTEM_STATUS = 0x25
    const val PINCH_STATE = 0x28
}

enum class FoundationAxis(val title: String) {
    HEAD("head"),
    FOOT("foot");

    internal val payloadOffset: Int get() = if (this == HEAD) 0 else 2

    /** Sleep Expert key letter for absolute positioning: MFU* head, MFF* foot. */
    internal val positionKeyLetter: String get() = if (this == HEAD) "U" else "F"
}

/**
 * An address-free MCR request. The session fills in addresses at send time.
 *
 * The constructor is internal: code outside bedkit can only obtain requests from the vetted builders
 * in [MCRCommands], so it cannot hand-craft a foundation SET with a bad payload.
 */
class MCRRequest internal constructor(
    val node: MCRNode,
    val function: Int,
    val side: Int,
    val payload: List<Int> = emptyList(),
    val label: String,
) {
    init {
        if (side !in 0..0x0F || payload.size > MCRFrame.MAX_PAYLOAD) {
            throw BedError.InvalidArgument("MCR side/payload out of range")
        }
        MCRSafety.validate(this)
    }

    /** Wire frame: our client address in source (bytes 1-2, 6-7), the bed's in destination (3-4). */
    fun frame(client: Int, bed: Int): MCRFrame {
        MCRSafety.validate(this)
        return MCRFrame(
            command = node.raw, source = client, destination = bed, status = node.raw,
            sourceEcho = client, function = function, side = side, payload = payload,
        )
    }

    override fun equals(other: Any?): Boolean = other is MCRRequest && node == other.node &&
        function == other.function && side == other.side && payload == other.payload && label == other.label

    override fun hashCode(): Int = ((node.hashCode() * 31 + function) * 31 + side) * 31 + payload.hashCode()

    override fun toString(): String = "%s (node %02X cmd 0x%02X sub %d)".format(label, node.raw, function, side)
}

/** Safety rules checked on every MCR request, both when it is built and again just before it is encoded. */
object MCRSafety {
    /** Foundation SET (0x42 / cmd 0x11) payload: 12 bytes, every field 0xFF except the axis being moved. */
    const val FOUNDATION_TARGET_LENGTH = 12

    fun validate(request: MCRRequest) {
        if (request.node == MCRNode.FOUNDATION && request.function == MCRFunction.SET) {
            validateFoundationTarget(request.payload)
        }
        if (request.node == MCRNode.PUMP && request.function == MCRFunction.SET) {
            val p = request.payload
            if (p.size != 2 || p[0] != 0 || !SleepNumber.isValid(p[1])) {
                throw BedError.UnsafeCommand("pump SET payload must be [0, 5...100 step 5]")
            }
        }
    }

    /**
     * A foundation SET must be exactly 12 bytes and move exactly one axis: `[target 0...100, 0x00 apply
     * flag]` at offset 0 (head) or 2 (foot), with every other byte 0xFF. Anything else could be read by
     * the bed as "drive this motor" or as massage/timer fields.
     */
    fun validateFoundationTarget(payload: List<Int>) {
        if (payload.size != FOUNDATION_TARGET_LENGTH) {
            throw BedError.UnsafeCommand("foundation SET payload must be 12 bytes, got ${payload.size}")
        }
        val activeAxes = FoundationAxis.entries.filter { axis ->
            val offset = axis.payloadOffset
            !(payload[offset] == 0xFF && payload[offset + 1] == 0xFF)
        }
        if (activeAxes.size != 1) throw BedError.UnsafeCommand("foundation SET must move exactly one axis")
        val offset = activeAxes[0].payloadOffset
        if (payload[offset] !in 0..100 || payload[offset + 1] != 0) {
            throw BedError.UnsafeCommand("foundation SET target must be 0...100 with apply flag 0")
        }
        for (index in 4 until FOUNDATION_TARGET_LENGTH) {
            if (payload[index] != 0xFF) throw BedError.UnsafeCommand("foundation SET byte $index must be the 0xFF mask")
        }
    }

    /**
     * Last-line check on raw bytes about to be written to the MCR characteristic: if the bytes form a
     * foundation SET frame, its payload must pass [validateFoundationTarget].
     */
    fun validateWireFrame(data: ByteArray) {
        val frame = MCRFrame.decode(data)
        if (frame.command == MCRNode.FOUNDATION.raw && frame.functionCode == MCRFunction.SET && !frame.isResponse) {
            validateFoundationTarget(frame.payload)
        }
    }
}

/** Vetted builders for every MCR request the app sends. */
object MCRCommands {
    /**
     * Session bind: pump node, cmd 0, zero addresses, 8-byte client ID (big-endian). The official app
     * sends a random 64-bit ID generated once and persisted (`he.b.e`).
     */
    fun bind(clientId: List<Int> = List(8) { 0 }): MCRRequest {
        if (clientId.size != 8) throw BedError.InvalidArgument("bind client ID must be 8 bytes")
        return MCRRequest(MCRNode.PUMP, MCRFunction.BIND, 0, clientId, "Bind")
    }

    /** Pump status: reply `[busy, SN right (side 0), SN left (side 1)]`; reply sub-nibble = chamber type. */
    fun readPumpStatus() = MCRRequest(MCRNode.PUMP, MCRFunction.READ, 0, label = "Read pump")

    /** Interrupts a firmness adjustment in progress (`ForceIdleCall`). */
    fun pumpForceIdle() = MCRRequest(MCRNode.PUMP, MCRFunction.FORCE_IDLE, 0, label = "Pump idle")

    /** Starts a firmness adjustment on one side (`SetSleepNumberCall`). */
    fun setSleepNumber(side: MCRWireSide, value: Int): MCRRequest {
        if (!SleepNumber.isValid(value)) throw BedError.InvalidArgument("Sleep Number $value must be 5...100 in steps of 5")
        return MCRRequest(MCRNode.PUMP, MCRFunction.SET, side.raw, listOf(0x00, value), "Set firmness $value")
    }

    /**
     * Foundation status. `keepAdjusting` selects the app's "keep adjusting" variant used while a held
     * move runs (sub-nibble side+2: 2 right, 3 left); null is a plain read (sub 0).
     */
    fun readFoundationStatus(keepAdjusting: MCRWireSide? = null): MCRRequest {
        val selector = keepAdjusting?.let { it.raw + 2 } ?: 0
        return MCRRequest(
            MCRNode.FOUNDATION, MCRFunction.READ, selector,
            label = if (keepAdjusting == null) "Read foundation" else "Keep adjusting $selector",
        )
    }

    fun activatePreset(preset: BedPreset, side: MCRWireSide) =
        MCRRequest(MCRNode.FOUNDATION, MCRFunction.PRESET, side.raw, listOf(preset.id, 0x00), "Preset ${preset.title}")

    /**
     * Micro-adjust (`MicroAdjustFoundationCall`): the only way to build a foundation SET. The app uses
     * target 100 for a held "up" and 0 for "down". Builds the full 0xFF-masked payload.
     */
    fun setFoundationTarget(side: MCRWireSide, axis: FoundationAxis, position: Int): MCRRequest {
        if (position !in 0..100) throw BedError.InvalidArgument("${axis.title} position $position must be 0...100")
        val payload = MutableList(MCRSafety.FOUNDATION_TARGET_LENGTH) { 0xFF }
        payload[axis.payloadOffset] = position
        payload[axis.payloadOffset + 1] = 0x00
        return MCRRequest(MCRNode.FOUNDATION, MCRFunction.SET, side.raw, payload, "Micro-adjust ${axis.title} -> $position")
    }

    /** Sleep Expert short write: key (4 ASCII) + value, node 0x52 cmd 0x1B. */
    internal fun sleepExpertWrite(key: String, value: String, label: String): MCRRequest {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        if (keyBytes.size != 4 || keyBytes.size + valueBytes.size > MCRFrame.MAX_PAYLOAD) {
            throw BedError.InvalidArgument("Sleep Expert key must be 4 bytes and fit 15 with its value")
        }
        val payload = (keyBytes + valueBytes).map { it.toInt() and 0xFF }
        return MCRRequest(MCRNode.SLEEP_EXPERT, MCRFunction.SE_SHORT_WRITE, 0, payload, label)
    }

    /** Side letter in Sleep Expert keys: R for side 0 (right), L for side 1 (left). */
    internal fun keyLetter(side: MCRWireSide) = if (side == MCRWireSide.ZERO) "R" else "L"

    /** Stops foundation movement on one side (`StopFoundationMovementCall`): MFHR/MFHL = "110". */
    fun stopMotion(side: MCRWireSide): MCRRequest {
        val key = "MFH" + keyLetter(side)
        return sleepExpertWrite(key, "110", "Stop $key")
    }

    /** Absolute position (`SetFoundationPositionCall`): MFU[R|L] (head) or MFF[R|L] (foot) = "<pos>_0". */
    fun setPosition(side: MCRWireSide, axis: FoundationAxis, position: Int): MCRRequest {
        if (position !in 0..100) throw BedError.InvalidArgument("${axis.title} position $position must be 0...100")
        val key = "MF" + axis.positionKeyLetter + keyLetter(side)
        return sleepExpertWrite(key, "${position}_0", "Position $key $position")
    }

    const val UNDER_BED_LIGHT_OUTLET = 3

    /** Outlet status: reply `[on, timer LE]`. */
    fun readUnderBedLight() =
        MCRRequest(MCRNode.FOUNDATION, MCRFunction.OUTLET_READ, UNDER_BED_LIGHT_OUTLET, label = "Read light")

    /** Outlet write `[mode, timer LE]`; timer 0 = no auto-off. */
    fun setUnderBedLight(on: Boolean) = MCRRequest(
        MCRNode.FOUNDATION, MCRFunction.OUTLET_WRITE, UNDER_BED_LIGHT_OUTLET,
        listOf(if (on) 1 else 0, 0, 0), if (on) "Light on" else "Light off",
    )

    /** Foundation system status: configuration and feature flags. */
    fun readSystemStatus() = MCRRequest(MCRNode.FOUNDATION, MCRFunction.SYSTEM_STATUS, 0, label = "Read system")

    /** Obstruction (pinch) sensor state, read before held moves on 360 bases. */
    fun readPinchState() = MCRRequest(MCRNode.FOUNDATION, MCRFunction.PINCH_STATE, 0, label = "Read pinch")

    /** Node list: node class IDs present (e.g. 0x01 pump, 0x41 foundation, 0x51 Sleep Expert). */
    fun readNodeList() = MCRRequest(MCRNode.PROXY, MCRFunction.READ, 0, label = "Read nodes")
}
