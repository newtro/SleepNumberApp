package ai.johnnycode.bedkit

// Port of BedKit/Sources/Common/FirmnessDisplay.swift.

/**
 * Which pump readings may update the displayed Sleep Numbers.
 *
 * Observed on a 360 p6 (MCR): while the pump adjusts one chamber, pump status reports 100 for the
 * other chamber until the adjustment finishes, then the real value again. So while the pump is busy
 * only the side being adjusted is taken from the reading; every other side keeps its last idle value.
 * When the adjusting side is unknown (a favorite's firmness step, or another app or remote started
 * the pump), no side is updated until the pump is idle.
 */
object FirmnessDisplay {
    fun merge(
        current: Map<BedSide, Int>,
        reading: Map<BedSide, Int>,
        pumpBusy: Boolean?,
        adjustingSide: BedSide?,
    ): Map<BedSide, Int> {
        if (pumpBusy != true) return current + reading
        val result = current.toMutableMap()
        if (adjustingSide != null) reading[adjustingSide]?.let { result[adjustingSide] = it }
        return result
    }
}
