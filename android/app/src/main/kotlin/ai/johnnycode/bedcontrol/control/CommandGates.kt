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

    // Same wording as the iOS app.
    const val NOT_CONNECTED = "Not connected to the bed."
    const val WAIT_FOR_MOVEMENT = "Wait for the current movement to finish."
    const val WAIT_FOR_COMMAND = "Wait for the current command or firmness change to finish, or press STOP."
    const val WAIT_FOR_BED_TO_STOP = "Wait for the bed to stop moving, or press STOP."

    /** Why a preset / firmness / light command cannot run now, or null. */
    fun commandBlocker(s: State): String? = when {
        !s.connected -> NOT_CONNECTED
        s.holdActive -> "Release the movement button first."
        s.applyingFavorite -> "A favorite is being applied. Press STOP to cancel it first."
        else -> null
    }

    /**
     * Why Apply is unavailable right now, or null (docs/FAVORITES-SPEC.md §1.4, iOS order and wording).
     * Refuses while our own move or pump work is active or the bed reports motion.
     */
    fun applyBlocker(s: State): String? = when {
        !s.connected -> NOT_CONNECTED
        s.holdActive || s.applyingFavorite -> WAIT_FOR_MOVEMENT
        s.commandsInFlight > 0 || s.adjustingFirmness -> WAIT_FOR_COMMAND
        s.moving -> WAIT_FOR_BED_TO_STOP
        else -> null
    }
}
