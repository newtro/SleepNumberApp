package ai.johnnycode.bedkit.driver

import ai.johnnycode.bedkit.BedCharacteristic
import ai.johnnycode.bedkit.BedPreset
import ai.johnnycode.bedkit.BedProtocol
import ai.johnnycode.bedkit.BedSide
import ai.johnnycode.bedkit.MoveDirection
import ai.johnnycode.bedkit.favorites.FavoriteBedOps
import ai.johnnycode.bedkit.mcr.FoundationAxis

// Port of BedControl/Control/BedDriver.swift. On iOS the drivers live in the app target; here they
// live in bedkit so they run in JVM unit tests against a fake link.

/** A partial snapshot of bed state from one poll. Empty/null fields were not read this time. */
data class BedReading(
    val firmness: MutableMap<BedSide, Int> = mutableMapOf(),
    /** Pump-wide busy flag (the protocol does not say which side is inflating). */
    var pumpBusy: Boolean? = null,
    val head: MutableMap<BedSide, Int> = mutableMapOf(),
    val foot: MutableMap<BedSide, Int> = mutableMapOf(),
    var lightOn: Boolean? = null,
    var moving: Boolean? = null,
    val activePreset: MutableMap<BedSide, BedPreset> = mutableMapOf(),
)

/** A preset button. Partner Snore sends Snore to the other side, as the official app does. */
sealed class PresetOption {
    data class Preset(val preset: BedPreset) : PresetOption()
    data object PartnerSnore : PresetOption()

    val id: String get() = when (this) {
        is Preset -> preset.title
        PartnerSnore -> "Partner Snore"
    }

    val title: String get() = when (this) {
        is Preset -> preset.title
        PartnerSnore -> "Partner"
    }

    val accessibilityName: String get() = when (this) {
        is Preset -> "${preset.title} preset"
        PartnerSnore -> "Partner Snore (raises the other side's head)"
    }
}

/** What the controller needs from a protocol. One implementation per BLE protocol. */
interface BedDriver {
    val kind: BedProtocol

    /** Preset buttons for this foundation, in the official app's order. */
    val presetOptions: List<PresetOption>

    /** False only when the bed reports it has no under-bed light. */
    val hasUnderBedLight: Boolean
    val diagnostics: List<Pair<String, String>>

    /** Subscribe, handshake/authenticate and read configuration. */
    suspend fun start()

    /** Full state poll. */
    suspend fun refresh(includeLight: Boolean): BedReading
    suspend fun activate(preset: BedPreset, side: BedSide)

    /** Starts a firmness change. `interruptRunning` first idles an adjustment in progress. */
    suspend fun setFirmness(value: Int, side: BedSide, interruptRunning: Boolean)
    suspend fun setLight(on: Boolean)

    /**
     * The app's hold-to-move: target 100 (up) or 0 (down), then status polls every ~333 ms until the
     * coroutine is cancelled, motion stops, a fault appears, or the safety deadline passes. Does not
     * stop the motor: the caller always follows with [releaseHold].
     */
    suspend fun hold(axis: FoundationAxis, side: BedSide, direction: MoveDirection, update: (BedReading) -> Unit)

    /** Stops held movement (confirmed: re-sent while still moving) and returns the final reading. Never throws. */
    suspend fun releaseHold(axis: FoundationAxis, side: BedSide): BedReading?

    /** Stops all motion (confirmed) and any pump adjustment immediately, abandoning queued commands. */
    suspend fun stopAll()

    /** Operations for applying a saved favorite on one side. */
    fun favoriteOps(side: BedSide): FavoriteBedOps
    fun handleNotification(characteristic: BedCharacteristic, data: ByteArray)
    fun reset()
}

object HoldLimits {
    /** Safety deadline for a single press-and-hold (full travel takes well under this). */
    const val MAX_HOLD_MS = 45_000L

    /** The official app's poll interval while a held move runs. */
    const val POLL_INTERVAL_MS = 333L

    /** Motion must have had this long to start before "not moving" counts as finished. */
    const val START_GRACE_MS = 1_000L
}

/** Monotonic milliseconds; injectable so tests can use virtual time. */
typealias Clock = () -> Long

val systemClock: Clock = { System.nanoTime() / 1_000_000 }
