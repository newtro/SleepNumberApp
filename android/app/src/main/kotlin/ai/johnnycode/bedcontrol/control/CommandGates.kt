package ai.johnnycode.bedcontrol.control

/**
 * Whether a new command or a favorite may start, from a snapshot of controller state. Pure so it is
 * unit-testable. Guards are always checked before any state is changed.
 */
object CommandGates {
    data class State(
        val connected: Boolean,
        val holdActive: Boolean,
        val applyingFavorite: Boolean,
        /** App commands (presets, firmness sets, light) still in flight. */
        val commandsInFlight: Int,
        /** A firmness adjustment this app started is still running. */
        val adjustingFirmness: Boolean,
        /** The foundation reports motion (e.g. a preset still travelling). */
        val moving: Boolean,
    )

    const val NOT_CONNECTED = "Not connected to the bed."
    const val WAIT_FOR_MOVEMENT = "Wait for the current movement to finish."

    /** Why a preset / firmness / light command cannot run now, or null. */
    fun commandBlocker(s: State): String? = when {
        !s.connected -> NOT_CONNECTED
        s.holdActive -> "Release the movement button first."
        s.applyingFavorite -> "A favorite is being applied. Press STOP to cancel it first."
        else -> null
    }

    /** Why Apply is unavailable right now, or null. Refuses while our own move or pump work is active. */
    fun applyBlocker(s: State): String? = when {
        !s.connected -> NOT_CONNECTED
        s.holdActive || s.moving -> WAIT_FOR_MOVEMENT
        s.applyingFavorite -> "A favorite is already being applied."
        s.commandsInFlight > 0 -> "Wait for the current command to finish."
        s.adjustingFirmness -> "Wait for the firmness adjustment to finish (or press STOP)."
        else -> null
    }
}
