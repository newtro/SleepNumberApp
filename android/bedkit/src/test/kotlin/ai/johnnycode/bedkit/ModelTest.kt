package ai.johnnycode.bedkit

import ai.johnnycode.bedkit.fuzion.FuzionSide
import ai.johnnycode.bedkit.mcr.MCRWireSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of BedKit/Tests/ModelTests.swift. */
class ModelTest {
    @Test
    fun sideMappingFollowsTheApp() {
        // Official app: right = 0, left = 1.
        val normal = SideMapping()
        assertEquals(MCRWireSide.ZERO, normal.mcrSide(BedSide.RIGHT))
        assertEquals(MCRWireSide.ONE, normal.mcrSide(BedSide.LEFT))
        assertEquals(BedSide.RIGHT, normal.side(MCRWireSide.ZERO))
        assertEquals(FuzionSide.LEFT, normal.fuzionSide(BedSide.LEFT))

        val swapped = SideMapping(swapped = true)
        assertEquals(MCRWireSide.ZERO, swapped.mcrSide(BedSide.LEFT))
        assertEquals(MCRWireSide.ONE, swapped.mcrSide(BedSide.RIGHT))
        assertEquals(BedSide.LEFT, swapped.side(MCRWireSide.ZERO))
        assertEquals(FuzionSide.RIGHT, swapped.fuzionSide(BedSide.LEFT))
    }

    @Test
    fun holdTargetsMatchTheApp() {
        assertEquals(100, MoveDirection.UP.holdTarget)
        assertEquals(0, MoveDirection.DOWN.holdTarget)
    }

    @Test
    fun protocolDetectionFromServices() {
        assertEquals(BedProtocol.MCR, BedProtocol.detect(listOf("ffffd1fd-388d-938b-344a-939d1f6efee0")))
        assertEquals(BedProtocol.FUZION, BedProtocol.detect(listOf("180A", "09D23FAE-90E6-44C2-95B6-0B3D0F1ABF25")))
        assertNull(BedProtocol.detect(listOf("180A", "1800")))
    }

    @Test
    fun detectorMatchesOwnersMacName() {
        val result = BedDetector.classify(AdvertisementInfo(name = "64:db:a0:9a:32:d3"))
        assertTrue(result.isCandidate)
        assertEquals(listOf(CandidateReason.SELECT_COMFORT_MAC), result.reasons)
        assertEquals(BedProtocol.MCR, result.likelyProtocol)
        assertEquals(0x32D3, BedDetector.mcrAddress("64:db:a0:9a:32:d3"))
    }

    @Test
    fun detectorOtherSignals() {
        assertEquals(listOf(CandidateReason.SMART_BED_NAME), BedDetector.classify(AdvertisementInfo(name = "Smart bed 1A2B")).reasons)
        assertEquals(listOf(CandidateReason.MAC_NAME), BedDetector.classify(AdvertisementInfo(name = "AA:BB:CC:DD:EE:FF")).reasons)
        assertEquals(BedProtocol.MCR, BedDetector.classify(AdvertisementInfo(name = null, serviceUuids = listOf(BedProtocol.MCR_SERVICE_UUID))).likelyProtocol)
        assertEquals(BedProtocol.FUZION, BedDetector.classify(AdvertisementInfo(name = null, serviceUuids = listOf(BedProtocol.FUZION_SERVICE_UUID))).likelyProtocol)
        assertEquals(
            listOf(CandidateReason.MANUFACTURER_DATA),
            BedDetector.classify(AdvertisementInfo(name = null, manufacturerData = byteArrayOf(0x4E, 0x53, 0x01))).reasons,
        )
        assertFalse(BedDetector.classify(AdvertisementInfo(name = "JBL Flip 5")).isCandidate)
        assertFalse(BedDetector.classify(AdvertisementInfo(name = "64:db:a0:9a:32")).isCandidate)
        assertFalse(BedDetector.isMacLike("64:db:a0:9a:32:zz"))
        assertTrue(
            BedDetector.classify(AdvertisementInfo(name = "64:DB:A0:9A:32:D3")).score >
                BedDetector.classify(AdvertisementInfo(name = "AA:BB:CC:DD:EE:FF")).score,
        )
    }

    @Test
    fun androidAddressOuiIdentifiesUnnamedMcrBed() {
        val result = BedDetector.classify(AdvertisementInfo(name = null, address = "64:DB:A0:9A:32:D3"))
        assertEquals(listOf(CandidateReason.SELECT_COMFORT_MAC), result.reasons)
        assertEquals(BedProtocol.MCR, result.likelyProtocol)
        // Not counted twice when the name already matched.
        assertEquals(
            listOf(CandidateReason.SELECT_COMFORT_MAC),
            BedDetector.classify(AdvertisementInfo(name = "64:db:a0:9a:32:d3", address = "64:DB:A0:9A:32:D3")).reasons,
        )
        assertFalse(BedDetector.classify(AdvertisementInfo(name = null, address = "11:22:33:44:55:66")).isCandidate)
        assertEquals(
            listOf(0x4E, 0x53, 0x01),
            AdvertisementInfo.manufacturerData(0x534E, byteArrayOf(0x01)).toUnsignedList(),
        )
    }

    @Test
    fun hexHelpers() {
        assertEquals("16 16 0a", hexToBytes("16 16 0a")?.hex())
        assertTrue(byteArrayOf(0x16, 0x16, 0x0A).contentEquals(hexToBytes("16160A")))
        assertNull(hexToBytes("1"))
        assertNull(hexToBytes("zz"))
    }

    @Test
    fun sleepNumberNormalization() {
        assertEquals(45, SleepNumber.normalize(47))
        assertEquals(50, SleepNumber.normalize(48))
        assertEquals(5, SleepNumber.normalize(-3))
        assertEquals(100, SleepNumber.normalize(250))
        assertTrue(SleepNumber.isValid(5))
        assertFalse(SleepNumber.isValid(47))
    }
}
