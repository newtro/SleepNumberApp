package ai.johnnycode.bedkit

import ai.johnnycode.bedkit.mcr.FoundationAxis
import ai.johnnycode.bedkit.mcr.MCRCommands
import ai.johnnycode.bedkit.mcr.MCRFrame
import ai.johnnycode.bedkit.mcr.MCRFunction
import ai.johnnycode.bedkit.mcr.MCRNode
import ai.johnnycode.bedkit.mcr.MCRRequest
import ai.johnnycode.bedkit.mcr.MCRSafety
import ai.johnnycode.bedkit.mcr.MCRSession
import ai.johnnycode.bedkit.mcr.MCRWireSide
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Port of BedKit/Tests/MCRSafetyTests.swift. The foundation SET (0x42 func 17) must always carry the
 * full 12-byte masked payload: a short or zero-filled payload can be read by the bed as "drive a motor
 * to an extreme". This is the 0xFF don't-move invariant.
 */
class MCRSafetyTest {
    @Test
    fun everyBuildableTargetHasFullMaskedPayload() {
        for (side in MCRWireSide.entries) {
            for (axis in FoundationAxis.entries) {
                for (position in 0..100) {
                    val request = MCRCommands.setFoundationTarget(side, axis, position)
                    assertEquals(MCRNode.FOUNDATION, request.node)
                    assertEquals(17, request.function)
                    assertEquals(12, request.payload.size)
                    val offset = if (axis == FoundationAxis.HEAD) 0 else 2
                    request.payload.forEachIndexed { index, byte ->
                        when (index) {
                            offset -> assertEquals(position, byte)
                            offset + 1 -> assertEquals(0, byte)
                            else -> assertEquals(0xFF, byte, "byte $index must be masked")
                        }
                    }
                    val frame = request.frame(0xA1B2, 0x32D3)
                    assertEquals(14 + 12, frame.encoded().size)
                    assertEquals((side.raw shl 4) or 12, frame.encoded().u(11))
                    MCRSafety.validateWireFrame(frame.encoded())
                }
            }
        }
    }

    @Test
    fun outOfRangePositionsAreRejected() {
        for (position in listOf(-1, 101, 255, 1000, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<BedError> { MCRCommands.setFoundationTarget(MCRWireSide.ZERO, FoundationAxis.HEAD, position) }
            assertFailsWith<BedError> { MCRCommands.setFoundationTarget(MCRWireSide.ONE, FoundationAxis.FOOT, position) }
        }
    }

    @Test
    fun validatorRejectsMalformedPayloads() {
        val ff = 0xFF
        val bad: List<List<Int>> = listOf(
            emptyList(), // empty: motors to extremes
            listOf(100, 0, 0, 0), // legacy 4-byte form
            List(12) { 0 }, // all zero: both axes to 0
            List(12) { ff }, // nothing selected
            listOf(50, 0, 50, 0) + List(8) { ff }, // two axes at once
            listOf(101, 0) + List(10) { ff }, // out of range
            listOf(50, 1) + List(10) { ff }, // non-zero high byte
            listOf(50, 0) + List(9) { ff } + listOf(0x00), // unmasked trailing byte
            listOf(50, 0) + List(11) { ff }, // 13 bytes
            listOf(ff, 0x00) + List(10) { ff }, // half-masked head
        )
        for (payload in bad) {
            assertFailsWith<BedError>(payload.hex()) { MCRSafety.validateFoundationTarget(payload) }
        }
    }

    @Test
    fun internalConstructorAlsoEnforcesTheRule() {
        // Even bedkit-internal code cannot build an unsafe request.
        assertFailsWith<BedError> { MCRRequest(MCRNode.FOUNDATION, MCRFunction.SET, 0, listOf(100, 0, 0, 0), "bad") }
        assertFailsWith<BedError> { MCRRequest(MCRNode.FOUNDATION, MCRFunction.SET, 0, emptyList(), "bad") }
        assertFailsWith<BedError> { MCRRequest(MCRNode.PUMP, MCRFunction.SET, 0, listOf(0, 3), "bad") }
    }

    @Test
    fun wireValidatorCatchesHandCraftedFrames() {
        // A raw frame built around the public frame type is still caught before the write.
        val raw = MCRFrame(command = 0x42, destination = 0x32D3, status = 0x42, function = 17, side = 0, payload = listOf(100, 0, 0, 0))
        assertFailsWith<BedError> { MCRSafety.validateWireFrame(raw.encoded()) }
        val empty = MCRFrame(command = 0x42, destination = 0x32D3, status = 0x42, function = 17, side = 1)
        assertFailsWith<BedError> { MCRSafety.validateWireFrame(empty.encoded()) }
        // Replies (response bit set) and other functions pass.
        val ack = MCRFrame(command = 0x41, destination = 0x32D3, status = 0x41, function = 0x80 or 17, side = 0)
        MCRSafety.validateWireFrame(ack.encoded())
    }

    @Test
    fun sessionRefusesToWriteUnsafeFrames() = runTest {
        // The session validates wire bytes before every write; the fake link must never see a bad SET.
        val link = FakeLink(backgroundScope)
        val session = MCRSession(link, List(8) { 9 }, log = {})
        link.onNotify = { session.handleNotification(it) }
        link.responder = { data, _ ->
            val frame = MCRFrame.decode(data)
            if (frame.functionCode == 0) {
                listOf(hexBytes("16 16 01 32 d3 00 00 01 32 d3 80 0a 01 02 03 04 05 06 07 08 a1 b2 29 44"))
            } else {
                listOf(
                    MCRFrame(
                        command = frame.command - 1, source = 0x32D3, destination = 0xA1B2, status = frame.command - 1,
                        sourceEcho = 0x32D3, function = 0x80 or frame.functionCode, side = frame.side,
                    ).encoded(),
                )
            }
        }
        session.performBind()
        session.setFoundationTarget(MCRWireSide.ONE, FoundationAxis.HEAD, 100)
        for (write in link.writes) MCRSafety.validateWireFrame(write.data)
        assertEquals(
            MCRCommands.setFoundationTarget(MCRWireSide.ONE, FoundationAxis.HEAD, 100).frame(0xA1B2, 0x32D3).encoded().hex(),
            link.writes.last().data.hex(),
        )
    }
}
