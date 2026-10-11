package ai.johnnycode.bedkit

// Port of BedKit/Sources/Common/Common.swift.

// MARK: - Hex helpers

/** Space-separated lowercase hex, e.g. "16 16 02 00". */
fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

/** Same for unsigned byte values held as ints (0..255). */
fun List<Int>.hex(): String = joinToString(" ") { "%02x".format(it and 0xFF) }

/** Unsigned byte at [index]. */
fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

fun ByteArray.toUnsignedList(): List<Int> = map { it.toInt() and 0xFF }

fun List<Int>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

/** Parses "16 16 02", "161602" or "16:16:02". Returns null on any invalid digit. */
fun hexToBytes(hex: String): ByteArray? {
    val digits = hex.filter { !it.isWhitespace() && it != ':' && it != '-' }
    if (digits.length % 2 != 0) return null
    val out = ByteArray(digits.length / 2)
    for (i in out.indices) {
        val byte = digits.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
        out[i] = byte.toByte()
    }
    return out
}

/** Content equality for nullable byte arrays. */
fun bytesEqual(a: ByteArray?, b: ByteArray?): Boolean = if (a == null || b == null) a === b else a.contentEquals(b)

/** Test/config helper: hex that must parse. */
fun hexBytes(hex: String): ByteArray = requireNotNull(hexToBytes(hex)) { "bad hex: $hex" }

// MARK: - Errors

/** Every failure the protocol layer can report, phrased so the UI can show it directly. */
sealed class BedError(override val message: String) : Exception(message) {
    data object NotConnected : BedError("Not connected to the bed.")
    data class Timeout(val what: String) : BedError("The bed did not answer ($what).")
    data class MalformedResponse(val what: String) : BedError("Unexpected reply from the bed: $what")
    data class InvalidArgument(val what: String) : BedError("Invalid value: $what")
    data class UnsafeCommand(val what: String) : BedError("Blocked an unsafe command: $what")

    /** Fuzion Auth returned the all-zero UUID: the bed already has its maximum number of BLE sessions. */
    data object ConnectionLimit : BedError(
        "The bed has reached its Bluetooth connection limit. Close the Sleep Number app (and any other phone or hub connected to the bed), then retry."
    )

    /** Fuzion Auth returned UUID one, a short value, or the link lacks encryption. */
    data class AuthenticationRejected(val why: String) : BedError(
        "The bed rejected this phone ($why). Press the pair button on the bed's pump or remote, close the Sleep Number app, then retry."
    )

    /** The bed requires a bond (pairing) this phone does not have. */
    data object BondRequired : BedError(
        "The bed needs to be paired with this phone. Press the pair button on the bed's pump or remote, close the official Sleep Number app, then retry and accept the pairing prompt."
    )

    /** The bed deleted its copy of our pairing keys (or the bond is otherwise broken). */
    data object PairingRemoved : BedError(
        "The bed forgot this phone's pairing. Open Settings > Connected devices, tap the gear next to the bed, choose Unpair (Forget), then press the bed's pair button and retry."
    )

    data class CommandFailed(val what: String) : BedError("The bed refused the command: $what")
    data class UnsupportedCommand(val what: String) : BedError("This bed does not support $what.")
    data class Transport(val what: String) : BedError("Bluetooth error: $what")
    data object Cancelled : BedError("Cancelled.")
}

// MARK: - Frame logging

/** One entry for the diagnostics log. The protocol layer emits these; the app stores and shows them. */
data class FrameEvent(
    val direction: Direction,
    val channel: String,
    val bytes: ByteArray? = null,
    val text: String,
) {
    enum class Direction(val label: String) { TX("TX"), RX("RX"), INFO("INFO"), ERROR("ERR") }

    override fun equals(other: Any?): Boolean =
        other is FrameEvent && direction == other.direction && channel == other.channel &&
            text == other.text && bytesEqual(bytes, other.bytes)

    override fun hashCode(): Int = ((direction.hashCode() * 31 + channel.hashCode()) * 31 + text.hashCode()) * 31 +
        (bytes?.contentHashCode() ?: 0)
}

typealias FrameLogger = (FrameEvent) -> Unit

// MARK: - Transport abstraction

/** Characteristics the sessions use. The app's BLE layer maps these to real GATT characteristics. */
enum class BedCharacteristic { MCR_NOTIFY, MCR_WRITE, FUZION_AUTH, FUZION_BAMKEY, FUZION_BULK }

/**
 * The only thing the protocol sessions need from Bluetooth. Implemented by the app's BLE layer
 * (and by fakes in unit tests), so all protocol logic stays free of Android Bluetooth APIs.
 *
 * Threading: like the iOS @MainActor design, every call into sessions and drivers happens on one
 * thread (the Android main thread); implementations deliver notifications on that thread too.
 */
interface GattLink {
    /**
     * Writes one complete protocol frame. The link chunks it (MCR: 20 bytes; Fuzion: MTU-4/5) and
     * must never interleave chunks of two frames.
     */
    suspend fun write(frame: ByteArray, characteristic: BedCharacteristic)

    /** Performs a GATT read and returns the value. */
    suspend fun read(characteristic: BedCharacteristic): ByteArray
}

/** A link that can also subscribe to notifications (what drivers need at start-up). */
interface BedLink : GattLink {
    suspend fun enableNotifications(characteristic: BedCharacteristic)
}
