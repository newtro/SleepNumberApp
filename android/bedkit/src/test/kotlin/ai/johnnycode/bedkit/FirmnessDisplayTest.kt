package ai.johnnycode.bedkit

import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of BedKit/Tests/FirmnessDisplayTests.swift. */
class FirmnessDisplayTest {
    private val L = BedSide.LEFT
    private val R = BedSide.RIGHT

    @Test
    fun busyKeepsOtherSideAtLastIdleValue() {
        assertEquals(mapOf(L to 45, R to 55),
            FirmnessDisplay.merge(mapOf(L to 40, R to 55), mapOf(L to 45, R to 100), true, L))
    }

    @Test
    fun idleTakesBothSides() {
        assertEquals(mapOf(L to 50, R to 55),
            FirmnessDisplay.merge(mapOf(L to 45, R to 55), mapOf(L to 50, R to 55), false, L))
    }

    @Test
    fun busyWithUnknownSideUpdatesNothing() {
        assertEquals(mapOf(L to 40, R to 55),
            FirmnessDisplay.merge(mapOf(L to 40, R to 55), mapOf(L to 100, R to 60), true, null))
    }

    @Test
    fun busyBeforeAnyIdleReadingLeavesOtherSideUnknown() {
        assertEquals(mapOf(L to 30), FirmnessDisplay.merge(emptyMap(), mapOf(L to 30, R to 100), true, L))
    }

    @Test
    fun noPumpInfoTakesReading() {
        assertEquals(mapOf(L to 45, R to 50),
            FirmnessDisplay.merge(mapOf(L to 40), mapOf(L to 45, R to 50), null, null))
    }
}
