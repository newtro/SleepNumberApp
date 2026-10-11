package ai.johnnycode.bedkit.mcr

import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.hex
import ai.johnnycode.bedkit.toByteArray
import ai.johnnycode.bedkit.u

// Port of BedKit/Sources/MCR/MCRFrame.swift.

/** MCR wire side selector (upper nibble of header byte 9) for the two sides of a split bed. */
enum class MCRWireSide(val raw: Int) { ZERO(0), ONE(1) }

/**
 * The MCR weighted checksum: s += b; r += s; result r & 0xFFFF, over header + payload
 * (not the 16 16 sync bytes). Transmitted big-endian.
 */
object MCRChecksum {
    fun compute(bytes: List<Int>): Int {
        var s = 0L
        var r = 0L
        for (byte in bytes) {
            s = (s + (byte and 0xFF)) and 0xFFFF_FFFFL
            r = (r + s) and 0xFFFF_FFFFL
        }
        return (r and 0xFFFF).toInt()
    }
}

/**
 * One MCR frame (layout per the official app, `ne.r`):
 * `16 16 | node src_hi src_lo dst_hi dst_lo node src_hi src_lo cmd (sub<<4|len) | payload | ck_hi ck_lo`.
 * Requests carry our client address in src (bytes 1-2 and 6-7) and the bed's in dst (3-4).
 * Replies swap them: the bed's address in 1-2 and 6-7, ours in 3-4.
 *
 * All byte-valued fields are unsigned ints (0..255 / 0..65535); payload is a value-semantic list.
 */
data class MCRFrame(
    /** Header byte 0: node class (requests use the node with low nibble 2, e.g. 0x42). */
    val command: Int,
    /** Header bytes 1-2. */
    val source: Int = 0,
    /** Header bytes 3-4. */
    val destination: Int,
    /** Header byte 5: node class, repeated. */
    val status: Int,
    /** Header bytes 6-7: source address again. */
    val sourceEcho: Int = 0,
    /** Header byte 8: command, with 0x80 set on replies. */
    val function: Int,
    /** High nibble of header byte 9: side (right 0, left 1), outlet, or selector. */
    val side: Int,
    val payload: List<Int> = emptyList(),
) {
    init {
        if (payload.size > MAX_PAYLOAD) throw BedError.InvalidArgument("MCR payload is ${payload.size} bytes; the limit is 15")
        if (side !in 0..0x0F) throw BedError.InvalidArgument("MCR side selector $side does not fit in a nibble")
    }

    val functionCode: Int get() = function and 0x7F
    val isResponse: Boolean get() = function and 0x80 != 0

    val headerAndPayload: List<Int>
        get() = listOf(
            command and 0xFF,
            (source shr 8) and 0xFF, source and 0xFF,
            (destination shr 8) and 0xFF, destination and 0xFF,
            status and 0xFF,
            (sourceEcho shr 8) and 0xFF, sourceEcho and 0xFF,
            function and 0xFF,
            (side shl 4) or payload.size,
        ) + payload.map { it and 0xFF }

    fun encoded(): ByteArray {
        val body = headerAndPayload
        val crc = MCRChecksum.compute(body)
        return (SYNC + body + listOf(crc shr 8, crc and 0xFF)).toByteArray()
    }

    override fun toString(): String {
        val kind = when (command and 0xF0) {
            0x00 -> "pump"
            0x40 -> "foundation"
            0x30 -> "senseAndDo"
            0x50 -> "sleepExpert"
            0x70 -> "proxy"
            0x90 -> "outlet"
            else -> "node%02X".format(command)
        }
        val ascii = if (payload.isNotEmpty() && payload.all { it in 0x20 until 0x7F })
            " \"${String(payload.toByteArray(), Charsets.UTF_8)}\"" else ""
        return "%s node=%02X cmd=0x%02X%s sub=%d src=%04X dst=%04X".format(
            kind, command, functionCode, if (isResponse) "(R)" else "", side, source, destination,
        ) + " [${payload.hex()}]" + ascii
    }

    companion object {
        val SYNC = listOf(0x16, 0x16)
        const val HEADER_LENGTH = 10
        const val OVERHEAD = 14 // sync + header + checksum
        const val MAX_PAYLOAD = 15

        /** Decodes exactly one complete frame, validating sync, length and checksum. */
        fun decode(bytes: ByteArray): MCRFrame {
            if (bytes.size < OVERHEAD) throw BedError.MalformedResponse("MCR frame shorter than 14 bytes")
            if (bytes.u(0) != 0x16 || bytes.u(1) != 0x16) throw BedError.MalformedResponse("MCR frame missing 16 16 sync")
            val length = bytes.u(11) and 0x0F
            if (bytes.size != OVERHEAD + length) {
                throw BedError.MalformedResponse("MCR frame length ${bytes.size} does not match payload length $length")
            }
            val body = (2 until bytes.size - 2).map { bytes.u(it) }
            val expected = (bytes.u(bytes.size - 2) shl 8) or bytes.u(bytes.size - 1)
            if (MCRChecksum.compute(body) != expected) throw BedError.MalformedResponse("MCR checksum mismatch")
            return MCRFrame(
                command = body[0],
                source = (body[1] shl 8) or body[2],
                destination = (body[3] shl 8) or body[4],
                status = body[5],
                sourceEcho = (body[6] shl 8) or body[7],
                function = body[8],
                side = body[9] shr 4,
                payload = body.subList(10, 10 + length).toList(),
            )
        }
    }
}

/**
 * Reassembles MCR frames from a stream of BLE notifications. Notifications are at most 20 bytes at
 * the default MTU, so one frame can span several; continuation chunks carry no sync.
 */
class MCRStreamParser {
    private val buffer = ArrayList<Int>()

    /** Bytes thrown away while resynchronizing (garbage or corrupt frames). */
    var discardedBytes = 0
        private set

    fun reset() = buffer.clear()

    fun feed(data: ByteArray): List<MCRFrame> {
        data.forEach { buffer.add(it.toInt() and 0xFF) }
        val frames = mutableListOf<MCRFrame>()
        while (true) {
            val start = syncIndex()
            if (start == null) {
                // Keep a trailing 0x16: it may be the first half of the next sync.
                val keep = if (buffer.lastOrNull() == 0x16) 1 else 0
                discardedBytes += buffer.size - keep
                buffer.clear()
                if (keep == 1) buffer.add(0x16)
                break
            }
            if (start > 0) {
                discardedBytes += start
                buffer.subList(0, start).clear()
            }
            if (buffer.size < 12) break
            val total = MCRFrame.OVERHEAD + (buffer[11] and 0x0F)
            if (buffer.size < total) {
                if (buffer.size > MAX_BUFFER) {
                    discardedBytes += 1
                    buffer.removeAt(0)
                    continue
                }
                break
            }
            val frame = try {
                MCRFrame.decode(buffer.subList(0, total).toByteArray())
            } catch (_: BedError) {
                null
            }
            if (frame != null) {
                frames += frame
                buffer.subList(0, total).clear()
            } else {
                // Corrupt candidate: skip one byte and look for the next sync.
                discardedBytes += 1
                buffer.removeAt(0)
            }
        }
        return frames
    }

    private fun syncIndex(): Int? {
        if (buffer.size < 2) return null
        for (index in 0 until buffer.size - 1) {
            if (buffer[index] == 0x16 && buffer[index + 1] == 0x16) return index
        }
        return null
    }

    private companion object {
        const val MAX_BUFFER = 512
    }
}
