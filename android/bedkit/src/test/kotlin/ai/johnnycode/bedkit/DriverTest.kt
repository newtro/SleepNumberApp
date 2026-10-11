package ai.johnnycode.bedkit

import ai.johnnycode.bedkit.driver.HoldLimits
import ai.johnnycode.bedkit.driver.MCRDriver
import ai.johnnycode.bedkit.mcr.FoundationAxis
import ai.johnnycode.bedkit.mcr.MCRSafety
import ai.johnnycode.bedkit.mcr.MCRSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DriverTest {
    private fun TestScope.mcr(): Triple<FakeLink, SimulatedMcrBed, MCRDriver> {
        val link = FakeLink(backgroundScope) { testScheduler.currentTime }
        val bed = SimulatedMcrBed(link)
        val driver = MCRDriver(
            link, {}, List(8) { it + 1 }, null, { SideMapping() },
            MCRSession.Timing(responseTimeoutMs = 120, retries = 3), clock = { testScheduler.currentTime },
        )
        link.onNotify = { driver.handleNotification(BedCharacteristic.MCR_NOTIFY, it) }
        return Triple(link, bed, driver)
    }

    @Test
    fun startSubscribesBindsAndReadsConfiguration() = runTest {
        val (link, bed, driver) = mcr()
        driver.start()
        assertEquals(listOf(BedCharacteristic.MCR_NOTIFY), link.subscribed)
        assertEquals(listOf(0x00, 0x12, 0x25), bed.frames().map { it.functionCode })
        assertEquals(listOf(0x02, 0x72, 0x42), bed.frames().map { it.command })
        assertEquals("360 FF2", driver.system?.model?.label)
        assertEquals(5, driver.presetOptions.size) // Favorite, Flat, Zero G, Snore, Partner
    }

    @Test
    fun holdSendsMaskedMicroAdjustThenKeepAdjustingPollsThenStopKeyOnRelease() = runTest {
        val (link, bed, driver) = mcr()
        driver.start()
        val before = link.writes.size
        // Left head up: left = wire side 1.
        val hold = launch { driver.hold(FoundationAxis.HEAD, BedSide.LEFT, MoveDirection.UP) {} }
        advanceTimeBy(1_100)
        hold.cancelAndJoin()
        driver.releaseHold(FoundationAxis.HEAD, BedSide.LEFT)

        val writes = link.writes.drop(before)
        val frames = writes.map { ai.johnnycode.bedkit.mcr.MCRFrame.decode(it.data) }
        // Status, pinch check, micro-adjust to 100 on side 1 with the 0xFF mask.
        assertEquals(0x12, frames[0].functionCode); assertEquals(0, frames[0].side)
        assertEquals(0x28, frames[1].functionCode)
        assertEquals(0x11, frames[2].functionCode); assertEquals(1, frames[2].side)
        assertEquals(listOf(100, 0) + List(10) { 0xFF }, frames[2].payload)
        writes.forEach { MCRSafety.validateWireFrame(it.data) }
        // Keep-adjusting polls (sub side+2 = 3) every ~333 ms.
        val polls = writes.indices.filter { frames[it].command == 0x42 && frames[it].functionCode == 0x12 && frames[it].side == 3 }
        assertTrue(polls.size >= 3, "expected keep-adjusting polls, got ${polls.size}")
        val gaps = polls.zipWithNext { a, b -> writes[b].at - writes[a].at }
        assertTrue(gaps.all { it == HoldLimits.POLL_INTERVAL_MS }, "poll gaps $gaps")
        // Release: the stop key for side 1 (urgent, then confirmed), then a plain status read.
        val tail = frames.takeLast(3)
        assertEquals(listOf("MFHL110", "MFHL110"), tail.take(2).map { String(it.payload.toByteArray(), Charsets.UTF_8) })
        assertEquals(0x12, tail[2].functionCode); assertEquals(0, tail[2].side)
        assertTrue(!bed.moving)
    }

    @Test
    fun holdDoesNotDriveAnActuatorAlreadyAtItsLimit() = runTest {
        val (link, bed, driver) = mcr()
        driver.start()
        bed.head[0] = 100
        driver.hold(FoundationAxis.HEAD, BedSide.RIGHT, MoveDirection.UP) {}
        assertTrue(bed.frames().none { it.command == 0x42 && it.functionCode == 0x11 })
    }

    @Test
    fun stopAllIdlesThePumpThenSendsConfirmedStopKeys() = runTest {
        val (link, bed, driver) = mcr()
        driver.start()
        bed.keyWrites.clear()
        val before = link.writes.size
        driver.stopAll()
        val frames = link.writes.drop(before).map { ai.johnnycode.bedkit.mcr.MCRFrame.decode(it.data) }
        assertEquals(listOf(0x02, 0x02), listOf(frames[0].command, frames[0].functionCode), "urgent pump idle first")
        assertEquals(listOf("MFHR110", "MFHL110", "MFHR110", "MFHL110"), bed.keyWrites)
        assertEquals(listOf(0x42, 0x12), listOf(frames.last().command, frames.last().functionCode), "confirming status read")
        assertEquals(6, frames.size)
    }

    @Test
    fun releaseResendsTheStopWhileStillMoving() = runTest {
        val (_, bed, driver) = mcr()
        driver.start()
        bed.headTarget[1] = 100
        bed.ignoreStops = 2
        bed.keyWrites.clear()
        val reading = driver.releaseHold(FoundationAxis.HEAD, BedSide.LEFT)
        assertEquals(listOf("MFHL110", "MFHL110", "MFHL110", "MFHL110"), bed.keyWrites)
        assertEquals(false, reading?.moving)
    }

    @Test
    fun stopFramesAreNeverDroppedByTheStopGeneration() = runTest {
        val (_, bed, driver) = mcr()
        driver.start()
        val stale = driver.session.stopGeneration
        driver.session.invalidateQueued()
        bed.keyWrites.clear()
        driver.session.send(ai.johnnycode.bedkit.mcr.MCRCommands.stopMotion(ai.johnnycode.bedkit.mcr.MCRWireSide.ONE), generation = stale)
        assertEquals(listOf("MFHL110"), bed.keyWrites)
        assertEquals(
            BedError.Cancelled,
            kotlin.test.assertFailsWith<BedError> {
                driver.session.send(ai.johnnycode.bedkit.mcr.MCRCommands.setPosition(ai.johnnycode.bedkit.mcr.MCRWireSide.ONE, FoundationAxis.HEAD, 10), generation = stale)
            },
        )
    }
}
