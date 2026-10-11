package ai.johnnycode.bedkit

import ai.johnnycode.bedkit.fuzion.CRC32
import ai.johnnycode.bedkit.fuzion.FuzionActuator
import ai.johnnycode.bedkit.fuzion.FuzionAuth
import ai.johnnycode.bedkit.fuzion.FuzionCommand
import ai.johnnycode.bedkit.fuzion.FuzionFrame
import ai.johnnycode.bedkit.fuzion.FuzionLightLevel
import ai.johnnycode.bedkit.fuzion.FuzionMovementStatus
import ai.johnnycode.bedkit.fuzion.FuzionReassembler
import ai.johnnycode.bedkit.fuzion.FuzionResponse
import ai.johnnycode.bedkit.fuzion.FuzionSession
import ai.johnnycode.bedkit.fuzion.FuzionSide
import ai.johnnycode.bedkit.fuzion.FuzionSleepNumberControls
import ai.johnnycode.bedkit.fuzion.FuzionSystemConfiguration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of BedKit/Tests/FuzionTests.swift. Blob vectors came from ha-adjustable-bed's `_build_bamkey_blob`. */
class FuzionFrameTest {
    @Test
    fun crc32StandardVector() {
        assertEquals(0xCBF4_3926L, CRC32.checksum("123456789".toByteArray()))
        assertEquals(0L, CRC32.checksum(ByteArray(0)))
        // Same as java.util.zip.CRC32, which the official app uses.
        val reference = java.util.zip.CRC32().apply { update("ACHA ".toByteArray()) }.value
        assertEquals(reference, CRC32.checksum("ACHA ".toByteArray()))
    }

    @Test
    fun encodeVectors() {
        assertEquals("66 55 7a 49 6f 4e 13 00 00 00 41 43 48 41 20 42 8f 7b af", FuzionCommand.haltAllActuators().frame.hex())
        assertEquals(
            "66 55 7a 49 6f 4e 1c 00 00 00 41 53 54 50 20 6c 65 66 74 20 66 6c 61 74 4c 86 07 51",
            FuzionCommand.setPreset(FuzionSide.LEFT, BedPreset.FLAT).frame.hex(),
        )
        assertEquals(
            "66 55 7a 49 6f 4e 21 00 00 00 41 53 54 4d 20 72 69 67 68 74 20 68 65 61 64 20 31 30 30 11 f7 95 64",
            FuzionCommand.microAdjust(FuzionSide.RIGHT, FuzionActuator.HEAD, 100).frame.hex(),
        )
    }

    @Test
    fun commandText() {
        assertEquals("ACHA ", FuzionCommand.haltAllActuators().text)
        assertEquals("SYCG ", FuzionCommand.systemConfiguration().text)
        assertEquals("ASTP right zero_g", FuzionCommand.setPreset(FuzionSide.RIGHT, BedPreset.ZERO_G).text)
        assertEquals("ASTP left watch_tv", FuzionCommand.setPreset(FuzionSide.LEFT, BedPreset.WATCH_TV).text)
        assertEquals("ASTM left foot 0", FuzionCommand.microAdjust(FuzionSide.LEFT, FuzionActuator.FOOT, 0).text)
        assertEquals("ACTM ", FuzionCommand.actuatorMovementStatus().text)
        assertEquals("ACTS right head 40", FuzionCommand.setActuatorTarget(FuzionSide.RIGHT, FuzionActuator.HEAD, 40).text)
        assertEquals("PSNS left 45", FuzionCommand.startSleepNumberAdjustment(FuzionSide.LEFT, 45).text)
        assertEquals("SNCG right", FuzionCommand.sleepNumberControls(FuzionSide.RIGHT).text)
        assertEquals("ACTG left foot", FuzionCommand.actuatorPosition(FuzionSide.LEFT, FuzionActuator.FOOT).text)
        assertEquals("UBLS high 15", FuzionCommand.setUnderBedLight(FuzionLightLevel.HIGH, 15).text)
    }

    @Test
    fun commandValidation() {
        assertFailsWith<BedError> { FuzionCommand.startSleepNumberAdjustment(FuzionSide.LEFT, 47) }
        assertFailsWith<BedError> { FuzionCommand.startSleepNumberAdjustment(FuzionSide.LEFT, 0) }
        assertFailsWith<BedError> { FuzionCommand.setActuatorTarget(FuzionSide.LEFT, FuzionActuator.HEAD, 101) }
        assertFailsWith<BedError> { FuzionCommand.setActuatorTarget(FuzionSide.LEFT, FuzionActuator.HEAD, -1) }
        assertFailsWith<BedError> { FuzionCommand.setUnderBedLight(FuzionLightLevel.LOW, 181) }
        assertFailsWith<BedError> { FuzionCommand.microAdjust(FuzionSide.LEFT, FuzionActuator.HEAD, 101) }
    }

    @Test
    fun decodeRoundTripAndRejection() {
        val blob = hexBytes("66 55 7a 49 6f 4e 15 00 00 00 50 41 53 53 3a 34 35 75 da 4d 8b")
        assertEquals("PASS:45", FuzionFrame.decode(blob))
        assertEquals("SNCG left", FuzionFrame.decode(FuzionFrame.encode("SNCG left")))

        val badCrc = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<BedError> { FuzionFrame.decode(badCrc) }
        assertFailsWith<BedError> { FuzionFrame.decode(blob.copyOf(blob.size - 1)) }
        val badPreamble = blob.copyOf().also { it[0] = 0x46 }
        assertFailsWith<BedError> { FuzionFrame.decode(badPreamble) }
    }

    @Test
    fun reassemblerAcrossFragments() {
        val blob = FuzionFrame.encode("PASS:0 30 45")
        val reassembler = FuzionReassembler()
        val parts = FakeLink.chunks(blob, 4)
        for (part in parts.dropLast(1)) assertEquals(FuzionReassembler.Progress.NeedMore, reassembler.append(part))
        assertEquals(FuzionReassembler.Progress.Complete("PASS:0 30 45"), reassembler.append(parts.last()))
    }

    @Test
    fun reassemblerRejectsGarbage() {
        val reassembler = FuzionReassembler()
        assertFailsWith<BedError> { reassembler.append("garbage!!!xx".toByteArray()) }
        assertFailsWith<BedError> { reassembler.append(ByteArray(0)) }
        val overlong = FuzionReassembler()
        assertFailsWith<BedError> { overlong.append(FuzionFrame.encode("PASS:") + byteArrayOf(0)) }
    }

    @Test
    fun responseParsing() {
        val command = FuzionCommand.sleepNumberControls(FuzionSide.LEFT)
        assertEquals(listOf("0", "30", "45"), FuzionResponse.parse("PASS:0 30 45", command))
        assertEquals(emptyList(), FuzionResponse.parse("PASS:ACK", command))
        assertEquals(emptyList(), FuzionResponse.parse("PASS:", command))
        assertEquals(BedError.UnsupportedCommand("SNCG"), assertFailsWith<BedError> { FuzionResponse.parse("FAIL:0", command) })
        assertFailsWith<BedError> { FuzionResponse.parse("FAIL:1", command) }
        assertFailsWith<BedError> { FuzionResponse.parse("FAIL:2", command) }
        assertFailsWith<BedError> { FuzionResponse.parse("WAT", command) }

        val controls = FuzionSleepNumberControls.parse(listOf("1", "30", "45"), command)
        assertTrue(controls.adjusting)
        assertEquals(45, controls.user)
        assertFailsWith<BedError> { FuzionSleepNumberControls.parse(listOf("1", "30"), command) }
    }

    @Test
    fun movementStatusParsing() {
        val status = FuzionMovementStatus.parse(listOf("0", "1", "0", "0"))
        assertTrue(status.rightFoot)
        assertTrue(status.any)
        assertTrue(status.moving(FuzionSide.RIGHT, FuzionActuator.FOOT))
        assertFalse(status.moving(FuzionSide.LEFT, FuzionActuator.HEAD))
        assertFailsWith<BedError> { FuzionMovementStatus.parse(listOf("0", "1")) }
        assertFailsWith<BedError> { FuzionMovementStatus.parse(listOf("0", "1", "2", "0")) }
    }

    @Test
    fun systemConfigurationParsing() {
        val tokens = listOf("dual", "yes", "yes", "yes", "no", "none", "yes", "yes", "yes", "yes", "yes", "yes", "yes", "yes", "no", "no")
        val config = FuzionSystemConfiguration.parse(tokens)
        assertFalse(config.isSingleChamber)
        assertTrue(config.hasUnderBedLight)
        assertTrue(config.supports(BedPreset.ZERO_G))
        assertFalse(config.supports(BedPreset.WATCH_TV))
        assertTrue(config.isSplit)
        assertFailsWith<BedError> { FuzionSystemConfiguration.parse(tokens.take(15)) }
    }

    @Test
    fun sessionValidation() {
        assertEquals(BedError.ConnectionLimit, assertFailsWith<BedError> { FuzionAuth.validateSession(ByteArray(16)) })
        val one = ByteArray(16).also { it[15] = 1 }
        assertTrue(assertFailsWith<BedError> { FuzionAuth.validateSession(one) } is BedError.AuthenticationRejected)
        assertFailsWith<BedError> { FuzionAuth.validateSession(byteArrayOf(0, 0)) }
        assertFailsWith<BedError> { FuzionAuth.validateSession(ByteArray(0)) }
        val good = ByteArray(16) { (it + 1).toByte() }
        assertTrue(good.contentEquals(FuzionAuth.validateSession(good)))
    }
}

class FuzionSessionTest {
    private val sessionId = ByteArray(16) { (0xA0 + it).toByte() }

    private fun TestScope.setup(): Pair<FakeLink, FuzionSession> {
        val link = FakeLink(backgroundScope)
        val session = FuzionSession(link, FuzionSession.Timing(responseTimeoutMs = 300, authTimeoutMs = 300, authAttempts = 2)) {}
        link.onNotify = { session.handleNotification(it) }
        return link to session
    }

    @Test
    fun authenticateStoresSession() = runTest {
        val (link, session) = setup()
        link.queueReads(BedCharacteristic.FUZION_AUTH, listOf(sessionId))
        assertTrue(sessionId.contentEquals(session.authenticate()))
        assertTrue(session.isHint(sessionId))
        assertTrue(session.isHint(ByteArray(16)))
        assertFalse(session.isHint(ByteArray(15)))
    }

    @Test
    fun connectionLimitIsNotRetried() = runTest {
        val (link, session) = setup()
        link.queueReads(BedCharacteristic.FUZION_AUTH, listOf(ByteArray(16), sessionId))
        assertEquals(BedError.ConnectionLimit, assertFailsWith<BedError> { session.authenticate() })
        assertEquals(1, link.readCount)
    }

    @Test
    fun bondErrorPropagates() = runTest {
        val (link, session) = setup()
        link.readError = BedError.BondRequired
        assertEquals(BedError.BondRequired, assertFailsWith<BedError> { session.authenticate() })
    }

    @Test
    fun commandRoundTripWithChunkedRead() = runTest {
        val (link, session) = setup()
        link.queueReads(BedCharacteristic.FUZION_AUTH, listOf(sessionId))
        session.authenticate()
        link.responder = { _, c -> if (c == BedCharacteristic.FUZION_BAMKEY) listOf(sessionId) else emptyList() }
        link.queueReads(BedCharacteristic.FUZION_BAMKEY, FakeLink.chunks(FuzionFrame.encode("PASS:0 30 45"), 8))
        assertEquals(45, session.sleepNumberControls(FuzionSide.LEFT).user)
        assertTrue(FuzionCommand.sleepNumberControls(FuzionSide.LEFT).frame.contentEquals(link.writes.last().data))
    }

    @Test
    fun urgentHaltDropsQueuedCommands() = runTest {
        val (link, session) = setup()
        link.queueReads(BedCharacteristic.FUZION_AUTH, listOf(sessionId))
        session.authenticate()
        val before = session.stopGeneration
        session.sendUrgent(FuzionCommand.haltAllActuators())
        assertEquals(before + 1, session.stopGeneration)
        assertEquals(BedError.Cancelled, assertFailsWith<BedError> { session.send(FuzionCommand.setPreset(FuzionSide.LEFT, BedPreset.ZERO_G), before) })
        assertEquals(1, link.writes.size)
    }

    @Test
    fun commandTimesOutWithoutHint() = runTest {
        val (link, session) = setup()
        link.queueReads(BedCharacteristic.FUZION_AUTH, listOf(sessionId))
        session.authenticate()
        assertTrue(assertFailsWith<BedError> { session.send(FuzionCommand.haltAllActuators()) } is BedError.Timeout)
    }

    @Test
    fun foreignNotificationsDoNotCompleteCommands() = runTest {
        val (link, session) = setup()
        link.queueReads(BedCharacteristic.FUZION_AUTH, listOf(sessionId))
        session.authenticate()
        link.responder = { _, _ -> listOf(ByteArray(16) { 0x55 }) }
        assertTrue(assertFailsWith<BedError> { session.send(FuzionCommand.haltAllActuators()) } is BedError.Timeout)
    }

    @Test
    fun failResponseSurfacesAsError() = runTest {
        val (link, session) = setup()
        link.queueReads(BedCharacteristic.FUZION_AUTH, listOf(sessionId))
        session.authenticate()
        link.responder = { _, _ -> listOf(sessionId) }
        link.queueReads(BedCharacteristic.FUZION_BAMKEY, listOf(FuzionFrame.encode("FAIL:0")))
        assertEquals(BedError.UnsupportedCommand("ACTM"), assertFailsWith<BedError> { session.send(FuzionCommand.actuatorMovementStatus()) })
    }
}
