package ai.johnnycode.bedkit

import ai.johnnycode.bedkit.mcr.FoundationAxis
import ai.johnnycode.bedkit.mcr.MCRBindResult
import ai.johnnycode.bedkit.mcr.MCRChecksum
import ai.johnnycode.bedkit.mcr.MCRCommands
import ai.johnnycode.bedkit.mcr.MCRFoundationStatus
import ai.johnnycode.bedkit.mcr.MCRFrame
import ai.johnnycode.bedkit.mcr.MCRLightStatus
import ai.johnnycode.bedkit.mcr.MCRNode
import ai.johnnycode.bedkit.mcr.MCRPinchState
import ai.johnnycode.bedkit.mcr.MCRPumpStatus
import ai.johnnycode.bedkit.mcr.MCRRequest
import ai.johnnycode.bedkit.mcr.MCRStreamParser
import ai.johnnycode.bedkit.mcr.MCRSystemStatus
import ai.johnnycode.bedkit.mcr.MCRWireSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of BedKit/Tests/MCRFrameTests.swift. Vectors were generated independently in Python from the
 * frame layout in docs/PROTOCOL-FROM-APK.md §2.1 (client 0xA1B2 in bytes 1-2/6-7, bed 0x32D3 in 3-4).
 */
class MCRFrameTest {
    private val bed = 0x32D3
    private val client = 0xA1B2

    private fun hex(request: MCRRequest) = request.frame(client, bed).encoded().hex()

    @Test
    fun checksumKnownVector() {
        val body = listOf(0x02, 0, 0, 0, 0, 0x02, 0, 0, 0, 0x08) + List(8) { 0 }
        assertEquals(0x0086, MCRChecksum.compute(body))
        assertEquals(0, MCRChecksum.compute(emptyList()))
        assertEquals(1 + 3 + 6, MCRChecksum.compute(listOf(1, 2, 3)))
    }

    @Test
    fun zeroPayloadHandshakeVector() {
        assertEquals(
            "16 16 02 00 00 00 00 02 00 00 00 08 00 00 00 00 00 00 00 00 00 86",
            MCRCommands.bind().frame(0, 0).encoded().hex(),
        )
    }

    @Test
    fun handshakeWithClientIdVector() {
        assertEquals(
            "16 16 02 00 00 00 00 02 00 00 00 08 01 02 03 04 05 06 07 08 00 fe",
            MCRCommands.bind(listOf(1, 2, 3, 4, 5, 6, 7, 8)).frame(0, 0).encoded().hex(),
        )
        assertFailsWith<BedError> { MCRCommands.bind(listOf(1, 2, 3)) }
    }

    @Test
    fun commandVectors() {
        assertEquals("16 16 02 a1 b2 32 d3 02 a1 b2 12 00 16 65", hex(MCRCommands.readPumpStatus()))
        assertEquals("16 16 02 a1 b2 32 d3 02 a1 b2 11 12 00 2d 1e 46", hex(MCRCommands.setSleepNumber(MCRWireSide.ONE, 45)))
        assertEquals(
            "16 16 42 a1 b2 32 d3 42 a1 b2 11 0c 64 00 ff ff ff ff ff ff ff ff ff ff 89 38",
            hex(MCRCommands.setFoundationTarget(MCRWireSide.ZERO, FoundationAxis.HEAD, 100)),
        )
        assertEquals(
            "16 16 42 a1 b2 32 d3 42 a1 b2 11 1c ff ff 00 00 ff ff ff ff ff ff ff ff 89 54",
            hex(MCRCommands.setFoundationTarget(MCRWireSide.ONE, FoundationAxis.FOOT, 0)),
        )
        assertEquals("16 16 42 a1 b2 32 d3 42 a1 b2 12 30 1a 55", hex(MCRCommands.readFoundationStatus(MCRWireSide.ONE)))
        assertEquals(
            "16 16 52 a1 b2 32 d3 52 a1 b2 1b 07 4d 46 48 4c 31 31 30 41 c1",
            hex(MCRCommands.stopMotion(MCRWireSide.ONE)),
        )
        assertEquals(
            "16 16 52 a1 b2 32 d3 52 a1 b2 1b 08 4d 46 55 52 33 35 5f 30 49 02",
            hex(MCRCommands.setPosition(MCRWireSide.ZERO, FoundationAxis.HEAD, 35)),
        )
        assertEquals("16 16 42 a1 b2 32 d3 42 a1 b2 15 02 06 00 22 c5", hex(MCRCommands.activatePreset(BedPreset.SNORE, MCRWireSide.ZERO)))
        assertEquals("16 16 42 a1 b2 32 d3 42 a1 b2 13 33 01 00 00 27 bc", hex(MCRCommands.setUnderBedLight(true)))
    }

    @Test
    fun sleepExpertKeysFollowWireSide() {
        fun ascii(r: MCRRequest) = String(r.payload.toByteArray(), Charsets.UTF_8)
        assertEquals("MFHR110", ascii(MCRCommands.stopMotion(MCRWireSide.ZERO)))
        assertEquals("MFHL110", ascii(MCRCommands.stopMotion(MCRWireSide.ONE)))
        assertEquals("MFFL7_0", ascii(MCRCommands.setPosition(MCRWireSide.ONE, FoundationAxis.FOOT, 7)))
        assertEquals("MFUR100_0", ascii(MCRCommands.setPosition(MCRWireSide.ZERO, FoundationAxis.HEAD, 100)))
        assertFailsWith<BedError> { MCRCommands.setPosition(MCRWireSide.ZERO, FoundationAxis.HEAD, 101) }
        val stop = MCRCommands.stopMotion(MCRWireSide.ZERO)
        assertEquals(MCRNode.SLEEP_EXPERT, stop.node)
        assertEquals(0x1B, stop.function)
        assertEquals(0, stop.side)
    }

    @Test
    fun decodeRoundTripAndRejection() {
        val original = MCRCommands.activatePreset(BedPreset.FLAT, MCRWireSide.ONE).frame(client, bed)
        assertEquals(original, MCRFrame.decode(original.encoded()))
        assertEquals(client, original.source)
        assertEquals(client, original.sourceEcho)
        assertEquals(bed, original.destination)

        val corrupt = original.encoded()
        corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1].toInt() xor 0xFF).toByte()
        assertFailsWith<BedError> { MCRFrame.decode(corrupt) }
        assertFailsWith<BedError> { MCRFrame.decode(byteArrayOf(0x16, 0x16, 0x02)) }
        assertFailsWith<BedError> { MCRFrame.decode(original.encoded().copyOf(original.encoded().size - 1)) }
    }

    @Test
    fun frameRejectsOversizedPayloadAndSide() {
        assertFailsWith<BedError> { MCRFrame(command = 2, destination = 0, status = 2, function = 0, side = 0, payload = List(16) { 0 }) }
        assertFailsWith<BedError> { MCRFrame(command = 2, destination = 0, status = 2, function = 0, side = 16) }
    }

    @Test
    fun streamParserReassemblesFragmentedReply() {
        val reply = MCRFrame(
            command = 0x41, source = bed, destination = client, status = 0x41, sourceEcho = bed,
            function = 0x80 or 0x12, side = 0, payload = hexBytes("42 2e 2e 4c 4c 00 00 00 00 00 00 00 00 00 55").toUnsignedList(),
        ).encoded()
        val parser = MCRStreamParser()
        assertEquals(emptyList(), parser.feed(reply.copyOfRange(0, 20)))
        val frames = parser.feed(reply.copyOfRange(20, reply.size))
        assertEquals(1, frames.size)
        assertEquals(0x12, frames[0].functionCode)
        assertTrue(frames[0].isResponse)
        assertEquals(15, frames[0].payload.size)
    }

    @Test
    fun streamParserSkipsGarbageAndSplitsBackToBackFrames() {
        val a = MCRCommands.readPumpStatus().frame(client, bed).encoded()
        val b = MCRCommands.setUnderBedLight(false).frame(client, bed).encoded()
        val parser = MCRStreamParser()
        val bad = a.copyOf()
        bad[bad.size - 2] = (bad[bad.size - 2].toInt() xor 0x01).toByte()
        val stream = byteArrayOf(0x00, 0x16, 0x99.toByte(), 0x16) + a + b + bad + a
        val frames = parser.feed(stream)
        assertEquals(listOf(0x12, 0x13, 0x12), frames.map { it.functionCode })
        assertTrue(parser.discardedBytes > 0)
    }

    @Test
    fun streamParserHandlesSyncBytesInsidePayload() {
        val frame = MCRFrame(
            command = 0x41, source = bed, destination = client, status = 0x41, sourceEcho = bed,
            function = 0x80 or 0x14, side = 3, payload = listOf(0x16, 0x16, 0x00),
        )
        val parser = MCRStreamParser()
        val frames = mutableListOf<MCRFrame>()
        for (byte in frame.encoded()) frames += parser.feed(byteArrayOf(byte))
        assertEquals(listOf(frame), frames)
    }

    @Test
    fun bindReplyParsing() {
        // Reply: bed address in header bytes 1-2, our address in payload bytes 8-9.
        val reply = MCRFrame.decode(hexBytes("16 16 01 32 d3 00 00 01 32 d3 80 0a 01 02 03 04 05 06 07 08 a1 b2 29 44"))
        val result = MCRBindResult.parse(reply)
        assertEquals(bed, result.bedAddress)
        assertEquals(client, result.clientAddress)

        val bare = MCRFrame(command = 1, destination = 0, status = 1, function = 0x80, side = 0, payload = List(8) { 0 })
        val fallback = MCRBindResult.parse(bare, macFallback = 0x32D3)
        assertEquals(0x32D3, fallback.bedAddress)
        assertEquals(0, fallback.clientAddress)
        assertFailsWith<BedError> { MCRBindResult.parse(bare) }
    }

    @Test
    fun pumpStatusParsing() {
        // [busy, right (side 0), left (side 1)]; reply sub-nibble = chamber type.
        val frame = MCRFrame.decode(hexBytes("16 16 01 32 d3 a1 b2 01 32 d3 92 13 00 28 37 22 07"))
        val status = MCRPumpStatus.parse(frame)
        assertFalse(status.busy)
        assertEquals(40, status.sleepNumber(MCRWireSide.ZERO))
        assertEquals(55, status.sleepNumber(MCRWireSide.ONE))
        assertEquals(1, status.chamberType)
        val busy = MCRFrame(command = 1, destination = 0, status = 1, function = 0x80 or 0x12, side = 0, payload = listOf(1, 40, 55))
        assertTrue(MCRPumpStatus.parse(busy).busy)
        val short = MCRFrame(command = 1, destination = 0, status = 1, function = 0x80 or 0x12, side = 0, payload = listOf(1))
        assertFailsWith<BedError> { MCRPumpStatus.parse(short) }
    }

    private fun foundationReply(payload: List<Int>) =
        MCRFrame(command = 0x41, destination = 0, status = 0x41, function = 0x80 or 0x12, side = 0, payload = payload)

    @Test
    fun foundationStatusParsing() {
        // i8 capture after Zero G (sleepnumber-ble), decoded with the app's layout.
        val payload = hexBytes("42 2e 2e 4c 4c 00 00 00 00 00 00 00 00 00 55").toUnsignedList()
        val frame = MCRFrame(
            command = 0x41, source = bed, destination = client, status = 0x41, sourceEcho = bed,
            function = 0x80 or 0x12, side = 0, payload = payload,
        )
        val status = MCRFoundationStatus.parse(frame)
        assertEquals(46, status.head(MCRWireSide.ZERO))
        assertEquals(46, status.head(MCRWireSide.ONE))
        assertEquals(76, status.foot(MCRWireSide.ZERO))
        assertEquals(76, status.foot(MCRWireSide.ONE))
        assertFalse(status.moving)
        assertTrue(status.configured)
        assertFalse(status.needsHoming)
        assertEquals(BedPreset.ZERO_G, status.preset(MCRWireSide.ZERO))
        assertEquals(BedPreset.ZERO_G, status.preset(MCRWireSide.ONE))

        val moving = payload.toMutableList().also { it[10] = 0x01 } // left head actuator moving
        val movingStatus = MCRFoundationStatus.parse(foundationReply(moving))
        assertTrue(movingStatus.moving)
        assertEquals(true, movingStatus.actuator(FoundationAxis.HEAD, MCRWireSide.ONE)?.moving)
        assertEquals(false, movingStatus.actuator(FoundationAxis.HEAD, MCRWireSide.ZERO)?.moving)

        val fault = payload.toMutableList().also { it[11] = 0x10 }
        assertEquals("motor current high", MCRFoundationStatus.parse(foundationReply(fault)).actuator(FoundationAxis.FOOT, MCRWireSide.ZERO)?.fault)

        val homing = payload.toMutableList().also { it[0] = 0x62 }
        assertTrue(MCRFoundationStatus.parse(foundationReply(homing)).needsHoming)

        val bogus = payload.toMutableList().also { it[2] = 0xC8 }
        assertFailsWith<BedError> { MCRFoundationStatus.parse(foundationReply(bogus)) }
    }

    @Test
    fun systemStatusParsingMatchesApp() {
        // sleepnumber-ble's i8 sample: config 1, flags 0x15 = dual + foot + under-bed light -> 360 FF2.
        val frame = MCRFrame(command = 0x41, destination = 0, status = 0x41, function = 0x80 or 0x25, side = 0, payload = listOf(1, 100, 100, 0x15, 0x14, 0, 0))
        val system = MCRSystemStatus.parse(frame)
        assertEquals(MCRSystemStatus.Configuration.SPLIT_HEAD, system.configuration)
        assertTrue(system.hasUnderBedLight)
        assertFalse(system.hasFootWarming)
        assertTrue(system.hasFootControl)
        assertEquals(MCRSystemStatus.Model.FF2, system.model)
        assertEquals(listOf(BedPreset.FAVORITE, BedPreset.FLAT, BedPreset.ZERO_G, BedPreset.SNORE), system.presets)
        assertTrue(system.offersPartnerSnore)

        val ff3 = MCRSystemStatus.parse(MCRFrame(command = 0x41, destination = 0, status = 0x41, function = 0xA5, side = 0, payload = listOf(0, 0, 0, 0x1C, 0, 0, 0x0C)))
        assertEquals(MCRSystemStatus.Model.FF3, ff3.model)
        assertTrue(BedPreset.READ in ff3.presets)
        assertFalse(ff3.offersPartnerSnore)
        assertTrue(ff3.leftBoardUnderperforming)
        assertTrue(ff3.rightBoardUnderperforming)
    }

    @Test
    fun lightAndPinchParsing() {
        val on = MCRFrame(command = 0x41, destination = 0, status = 0x41, function = 0x80 or 0x14, side = 3, payload = listOf(1, 0x78, 0))
        val light = MCRLightStatus.parse(on)
        assertTrue(light.on)
        assertEquals(120, light.timerMinutes)
        val off = MCRFrame(command = 0x41, destination = 0, status = 0x41, function = 0x80 or 0x14, side = 3, payload = listOf(0, 0, 0))
        assertFalse(MCRLightStatus.parse(off).on)

        val pinch = MCRPinchState.parse(
            MCRFrame(command = 0x41, destination = 0, status = 0x41, function = 0x80 or 0x28, side = 0, payload = listOf(0b1000_0100, 0, 0, 0, 0)),
        )
        assertTrue(pinch.disconnected(FoundationAxis.HEAD, MCRWireSide.ZERO))
        assertFalse(pinch.disconnected(FoundationAxis.HEAD, MCRWireSide.ONE))
        assertTrue(pinch.continuousPinch(FoundationAxis.HEAD, MCRWireSide.ONE))
        assertFalse(pinch.continuousPinch(FoundationAxis.FOOT, MCRWireSide.ZERO))
    }

    @Test
    fun firmnessValidation() {
        assertFailsWith<BedError> { MCRCommands.setSleepNumber(MCRWireSide.ZERO, 47) }
        assertFailsWith<BedError> { MCRCommands.setSleepNumber(MCRWireSide.ZERO, 0) }
        assertFailsWith<BedError> { MCRCommands.setSleepNumber(MCRWireSide.ZERO, 105) }
        MCRCommands.setSleepNumber(MCRWireSide.ONE, 5)
        MCRCommands.setSleepNumber(MCRWireSide.ONE, 100)
        assertEquals(45, SleepNumber.normalize(47))
        assertEquals(50, SleepNumber.normalize(48))
        assertEquals(5, SleepNumber.normalize(-3))
        assertEquals(100, SleepNumber.normalize(250))
    }
}
