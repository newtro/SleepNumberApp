package ai.johnnycode.bedkit.fuzion

import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.u

// Port of BedKit/Sources/Fuzion/FuzionFrame.swift.

/** Standard CRC-32 (IEEE 802.3, reflected, poly 0xEDB88320) — the same as zlib / java.util.zip.CRC32. */
object CRC32 {
    private val table = LongArray(256) { index ->
        var value = index.toLong()
        repeat(8) { value = if (value and 1L != 0L) (value ushr 1) xor 0xEDB8_8320L else value ushr 1 }
        value
    }

    fun checksum(bytes: ByteArray, length: Int = bytes.size): Long {
        var crc = 0xFFFF_FFFFL
        for (i in 0 until length) {
            crc = table[((crc xor (bytes[i].toLong() and 0xFF)) and 0xFF).toInt()] xor (crc ushr 8)
        }
        return crc xor 0xFFFF_FFFFL
    }
}

/**
 * Fuzion blob framing: `"fUzIoN" | uint32 LE total length | UTF-8 text | uint32 LE CRC32`.
 * The CRC covers everything before it (preamble, length and text).
 */
object FuzionFrame {
    val PREAMBLE: ByteArray = "fUzIoN".toByteArray(Charsets.US_ASCII)
    const val HEADER_LENGTH = 10
    const val MINIMUM_LENGTH = 14

    fun encode(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val total = HEADER_LENGTH + body.size + 4
        val head = PREAMBLE + littleEndian(total.toLong()) + body
        return head + littleEndian(CRC32.checksum(head))
    }

    /** Decodes one complete blob, validating preamble, length and CRC. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size < MINIMUM_LENGTH) throw BedError.MalformedResponse("Fuzion blob too short (${bytes.size} bytes)")
        if (!bytes.copyOfRange(0, 6).contentEquals(PREAMBLE)) throw BedError.MalformedResponse("Fuzion blob missing fUzIoN preamble")
        val total = readLE(bytes, 6)
        if (total != bytes.size.toLong()) {
            throw BedError.MalformedResponse("Fuzion blob length ${bytes.size} != header length $total")
        }
        val expected = readLE(bytes, bytes.size - 4)
        if (CRC32.checksum(bytes, bytes.size - 4) != expected) throw BedError.MalformedResponse("Fuzion blob CRC mismatch")
        return String(bytes, HEADER_LENGTH, bytes.size - 4 - HEADER_LENGTH, Charsets.UTF_8)
    }

    internal fun littleEndian(value: Long): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    internal fun readLE(bytes: ByteArray, offset: Int): Long =
        bytes.u(offset).toLong() or (bytes.u(offset + 1).toLong() shl 8) or
            (bytes.u(offset + 2).toLong() shl 16) or (bytes.u(offset + 3).toLong() shl 24)
}

/** Accumulates BamKey read fragments until one complete blob is present. */
class FuzionReassembler {
    sealed interface Progress {
        data object NeedMore : Progress
        data class Complete(val text: String) : Progress
    }

    private var buffer = ByteArray(0)

    fun reset() {
        buffer = ByteArray(0)
    }

    fun append(chunk: ByteArray): Progress {
        if (chunk.isEmpty()) throw BedError.MalformedResponse("empty BamKey fragment")
        buffer += chunk
        if (buffer.size < FuzionFrame.HEADER_LENGTH) return Progress.NeedMore
        if (!buffer.copyOfRange(0, 6).contentEquals(FuzionFrame.PREAMBLE)) {
            reset()
            throw BedError.MalformedResponse("BamKey response missing fUzIoN preamble")
        }
        val total = FuzionFrame.readLE(buffer, 6)
        if (total < FuzionFrame.MINIMUM_LENGTH || total > MAX_LENGTH || buffer.size > total) {
            reset()
            throw BedError.MalformedResponse("BamKey response has invalid length $total")
        }
        if (buffer.size.toLong() != total) return Progress.NeedMore
        val complete = buffer
        reset()
        return Progress.Complete(FuzionFrame.decode(complete))
    }

    companion object {
        const val MAX_LENGTH = 64 * 1024
    }
}

/** Validation of the 16-byte session UUID read from the Auth characteristic. */
object FuzionAuth {
    fun validateSession(value: ByteArray): ByteArray {
        if (value.size != 16) {
            throw BedError.AuthenticationRejected("Auth returned ${value.size} bytes instead of a 16-byte session")
        }
        if (value.all { it.toInt() == 0 }) throw BedError.ConnectionLimit
        if (value.copyOfRange(0, 15).all { it.toInt() == 0 } && value[15].toInt() == 1) {
            throw BedError.AuthenticationRejected("Auth returned session UUID 1")
        }
        return value
    }
}
