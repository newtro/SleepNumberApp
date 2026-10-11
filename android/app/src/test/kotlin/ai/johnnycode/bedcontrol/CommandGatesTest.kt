package ai.johnnycode.bedcontrol

import ai.johnnycode.bedcontrol.control.CommandGates
import ai.johnnycode.bedcontrol.control.CommandGates.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandGatesTest {
    private val idle = State(connected = true, holdActive = false, applyingFavorite = false, commandsInFlight = 0, adjustingFirmness = false, moving = false)

    @Test
    fun applyAllowedOnlyWhenIdle() {
        assertNull(CommandGates.applyBlocker(idle))
        assertEquals(CommandGates.NOT_CONNECTED, CommandGates.applyBlocker(idle.copy(connected = false)))
        assertEquals(CommandGates.WAIT_FOR_BED_TO_STOP, CommandGates.applyBlocker(idle.copy(moving = true)))
        assertEquals(CommandGates.WAIT_FOR_MOVEMENT, CommandGates.applyBlocker(idle.copy(holdActive = true)))
        assertEquals(CommandGates.WAIT_FOR_MOVEMENT, CommandGates.applyBlocker(idle.copy(applyingFavorite = true)))
        assertEquals(CommandGates.WAIT_FOR_COMMAND, CommandGates.applyBlocker(idle.copy(commandsInFlight = 1)))
        assertEquals(CommandGates.WAIT_FOR_COMMAND, CommandGates.applyBlocker(idle.copy(adjustingFirmness = true)))
        assertEquals(CommandGates.NOT_CONNECTED, CommandGates.applyBlocker(idle.copy(connected = false, moving = true)))
    }

    @Test
    fun commandsBlockedDuringHoldOrFavorite() {
        assertNull(CommandGates.commandBlocker(idle))
        assertNull(CommandGates.commandBlocker(idle.copy(moving = true)), "a preset may replace a moving preset, as in iOS")
        assertNotNull(CommandGates.commandBlocker(idle.copy(holdActive = true)))
        assertNotNull(CommandGates.commandBlocker(idle.copy(applyingFavorite = true)))
        assertEquals(CommandGates.NOT_CONNECTED, CommandGates.commandBlocker(idle.copy(connected = false)))
    }

    private fun assertNull(value: Any?, message: String) = org.junit.Assert.assertNull(message, value)
}
