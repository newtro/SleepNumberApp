package ai.johnnycode.bedkit

import ai.johnnycode.bedkit.mcr.MCRCommands
import ai.johnnycode.bedkit.mcr.MCRFrame
import ai.johnnycode.bedkit.mcr.MCRSession
import ai.johnnycode.bedkit.mcr.MCRWireSide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of BedKit/Tests/MCRSessionTests.swift. */
class MCRSessionTest {
    private val bed = 0x32D3
    private val client = 0xA1B2
    private val clientId = listOf(1, 2, 3, 4, 5, 6, 7, 8)

    private class Env(val link: FakeLink, var session: MCRSession, val events: MutableList<FrameEvent>)

    private fun TestScope.env(macFallback: Int? = null): Env {
        val link = FakeLink(backgroundScope)
        val events = mutableListOf<FrameEvent>()
        val session = MCRSession(link, clientId, macFallback, MCRSession.Timing(responseTimeoutMs = 120, retries = 3)) { events += it }
        val env = Env(link, session, events)
        link.onNotify = { env.session.handleNotification(it) }
        return env
    }

    /** A reply as the bed sends it: bed address in source fields, ours in destination. */
    private fun reply(request: ByteArray, payload: List<Int> = emptyList(), side: Int? = null, destination: Int? = null): ByteArray {
        val frame = MCRFrame.decode(request)
        return MCRFrame(
            command = frame.command - 1, source = bed, destination = destination ?: client,
            status = frame.command - 1, sourceEcho = bed, function = 0x80 or frame.functionCode,
            side = side ?: frame.side, payload = payload,
        ).encoded()
    }

    private fun bindReply() = hexBytes("16 16 01 32 d3 00 00 01 32 d3 80 0a 01 02 03 04 05 06 07 08 a1 b2 29 44")

    private fun Env.answerEverything(payload: List<Int> = emptyList()) {
        link.responder = { data, _ ->
            if (MCRFrame.decode(data).functionCode == 0) listOf(bindReply()) else listOf(reply(data, payload))
        }
    }

    private fun Env.frames() = link.writes.drop(1).map { MCRFrame.decode(it.data) }

    @Test
    fun bindSendsClientIdAndStoresAddresses() = runTest {
        val e = env()
        e.link.responder = { _, _ -> listOf(bindReply()) }
        val result = e.session.performBind()
        assertEquals(bed, result.bedAddress)
        assertEquals(client, result.clientAddress)
        assertEquals("16 16 02 00 00 00 00 02 00 00 00 08 01 02 03 04 05 06 07 08 00 fe", e.link.writes.first().data.hex())
        assertEquals(BedCharacteristic.MCR_WRITE, e.link.writes.first().characteristic)
    }

    @Test
    fun bindFailsCleanlyWithoutReply() = runTest {
        val e = env()
        val error = assertFailsWith<BedError> { e.session.performBind() }
        assertEquals(BedError.Timeout("Bind"), error)
        assertEquals(4, e.link.writes.size) // 1 try + 3 retries
        assertFalse(e.session.isBound)
    }

    @Test
    fun bindUsesMacFallbackWhenReplyHasNoBedAddress() = runTest {
        val e = env(macFallback = 0x05D3)
        e.link.responder = { _, _ ->
            listOf(MCRFrame(command = 1, destination = 0, status = 1, function = 0x80, side = 0, payload = List(10) { 0 }).encoded())
        }
        assertEquals(0x05D3, e.session.performBind().bedAddress)
    }

    @Test
    fun requestsBeforeBindAreRefused() = runTest {
        val e = env()
        assertEquals(BedError.NotConnected, assertFailsWith<BedError> { e.session.readPumpStatus() })
    }

    @Test
    fun requestsCarryAssignedAddresses() = runTest {
        val e = env()
        e.link.responder = { data, _ ->
            if (MCRFrame.decode(data).functionCode == 0) listOf(bindReply())
            else FakeLink.chunks(reply(data, listOf(0, 40, 55), side = 1), 7)
        }
        e.session.performBind()
        val status = e.session.readPumpStatus()
        assertEquals(40, status.sleepNumber(MCRWireSide.ZERO))
        assertEquals(55, status.sleepNumber(MCRWireSide.ONE))
        assertEquals("16 16 02 a1 b2 32 d3 02 a1 b2 12 00 16 65", e.link.writes.last().data.hex())
    }

    @Test
    fun repliesForSomeoneElseAreIgnored() = runTest {
        val e = env()
        e.link.responder = { data, _ ->
            if (MCRFrame.decode(data).functionCode == 0) listOf(bindReply())
            // A reply addressed to another client, then ours.
            else listOf(reply(data, listOf(1, 0, 0), destination = 0x7777), reply(data, listOf(0, 0, 0)))
        }
        e.session.performBind()
        assertFalse(e.session.readUnderBedLight().on)
        assertTrue(e.events.any { it.text.contains("unmatched") })
    }

    @Test
    fun nackSubNibbleSurfacesAsError() = runTest {
        val e = env()
        e.link.responder = { data, _ ->
            if (MCRFrame.decode(data).functionCode == 0) listOf(bindReply()) else listOf(reply(data, side = 0x0F))
        }
        e.session.performBind()
        assertTrue(assertFailsWith<BedError> { e.session.send(MCRCommands.stopMotion(MCRWireSide.ZERO)) } is BedError.CommandFailed)
    }

    @Test
    fun foundationStatusKeepAdjustingSelector() = runTest {
        val e = env()
        e.answerEverything(hexBytes("43 10 20 00 00 00 00 00 00 00 00 00 00 00 00").toUnsignedList())
        e.session.performBind()
        val status = e.session.readFoundationStatus(keepAdjusting = MCRWireSide.ZERO)
        assertTrue(status.moving)
        assertEquals(32, status.headLeft)
        assertEquals(2, MCRFrame.decode(e.link.writes.last().data).side)
    }

    @Test
    fun timeoutRetriesThreeTimesThenFails() = runTest {
        val e = env()
        e.link.responder = { data, _ -> if (MCRFrame.decode(data).functionCode == 0) listOf(bindReply()) else emptyList() }
        e.session.performBind()
        assertEquals(BedError.Timeout("Read light"), assertFailsWith<BedError> { e.session.readUnderBedLight() })
        assertEquals(1 + 4, e.link.writes.size) // bind + 1 try + 3 retries
    }

    @Test
    fun setSleepNumberOnIdlePumpSendsOnlyTheSet() = runTest {
        val e = env()
        e.answerEverything()
        e.session.performBind()
        e.session.setSleepNumber(50, MCRWireSide.ONE, interruptRunning = false)
        val frames = e.frames()
        assertEquals(listOf(0x11), frames.map { it.functionCode })
        assertEquals(1, frames[0].side)
        assertEquals(listOf(0, 50), frames[0].payload)
    }

    @Test
    fun setSleepNumberInterruptingIdlesFirst() = runTest {
        val e = env()
        e.answerEverything()
        e.session.performBind()
        e.session.setSleepNumber(50, MCRWireSide.ZERO, interruptRunning = true)
        assertEquals(listOf(0x02, 0x11), e.frames().map { it.functionCode })
    }

    @Test
    fun stopSendsKeyWritesUrgentlyThenConfirmed() = runTest {
        val e = env()
        e.answerEverything()
        e.session.performBind()
        e.session.stopFoundation()
        val frames = e.frames()
        assertEquals(listOf(0x52, 0x52, 0x52, 0x52), frames.map { it.command })
        assertEquals(
            listOf("MFHR110", "MFHL110", "MFHR110", "MFHL110"),
            frames.map { String(it.payload.toByteArray(), Charsets.UTF_8) },
        )
        assertFalse(frames.any { it.command == 0x42 }, "no binary foundation stop")
    }

    @Test
    fun queuedMotionIsDroppedAfterStop() = runTest {
        // A slow poll holds the queue; a preset waits behind it; STOP is pressed.
        val e = env()
        e.link.responder = { data, _ ->
            val frame = MCRFrame.decode(data)
            when (frame.functionCode) {
                0 -> listOf(bindReply())
                0x12 -> emptyList() // poll never answers (times out)
                else -> listOf(reply(data))
            }
        }
        e.session.performBind()
        val poll = launch { runCatching { e.session.readFoundationStatus() } }
        val preset = async { runCatching { e.session.activatePreset(BedPreset.ZERO_G, MCRWireSide.ZERO) } }
        delay(30)
        e.session.stopFoundation()
        assertEquals(BedError.Cancelled, preset.await().exceptionOrNull(), "preset must not be sent after STOP")
        poll.join()
        assertTrue(e.link.writes.mapNotNull { runCatching { MCRFrame.decode(it.data) }.getOrNull() }.none { it.functionCode == 0x15 })
    }

    @Test
    fun cancellationReleasesTheQueue() = runTest {
        val e = env()
        e.link.responder = { data, _ -> if (MCRFrame.decode(data).functionCode == 0) listOf(bindReply()) else emptyList() }
        e.session.performBind()
        val task = async { e.session.send(MCRCommands.readPumpStatus(), timeoutMs = 30_000, retries = 0) }
        delay(20)
        task.cancel()
        assertFailsWith<CancellationException> { task.await() }
        e.link.responder = { data, _ -> listOf(reply(data, listOf(0, 0, 0))) }
        assertFalse(e.session.readUnderBedLight().on)
    }

    @Test
    fun resetFailsTheInFlightRequest() = runTest {
        val e = env()
        e.link.responder = { data, _ -> if (MCRFrame.decode(data).functionCode == 0) listOf(bindReply()) else emptyList() }
        e.session.performBind()
        val task = async { runCatching { e.session.send(MCRCommands.readPumpStatus(), timeoutMs = 30_000, retries = 0) } }
        delay(20)
        e.session.reset()
        assertEquals(BedError.NotConnected, task.await().exceptionOrNull())
        assertFalse(e.session.isBound)
    }
}
