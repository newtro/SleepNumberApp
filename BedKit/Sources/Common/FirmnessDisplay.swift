import Foundation

/// Which pump readings may update the displayed Sleep Numbers.
///
/// Observed on a 360 p6 (MCR): while the pump adjusts one chamber, pump status reports 100 for
/// the other chamber until the adjustment finishes, then the real value again. So while the pump
/// is busy only the side being adjusted is taken from the reading; every other side keeps its
/// last idle value. When the adjusting side is unknown (a favorite's firmness step, or another
/// app or remote started the pump), no side is updated until the pump is idle.
public enum FirmnessDisplay {
    public static func merge(current: [BedSide: Int], reading: [BedSide: Int],
                             pumpBusy: Bool?, adjustingSide: BedSide?) -> [BedSide: Int] {
        guard pumpBusy == true else { return current.merging(reading) { _, new in new } }
        var result = current
        if let side = adjustingSide, let value = reading[side] { result[side] = value }
        return result
    }
}
