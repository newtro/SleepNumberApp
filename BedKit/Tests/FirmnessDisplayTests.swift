import XCTest
@testable import BedKit

final class FirmnessDisplayTests: XCTestCase {
    func testBusyKeepsOtherSideAtLastIdleValue() {
        let merged = FirmnessDisplay.merge(current: [.left: 40, .right: 55], reading: [.left: 45, .right: 100],
                                           pumpBusy: true, adjustingSide: .left)
        XCTAssertEqual(merged, [.left: 45, .right: 55])
    }

    func testIdleTakesBothSides() {
        let merged = FirmnessDisplay.merge(current: [.left: 45, .right: 55], reading: [.left: 50, .right: 55],
                                           pumpBusy: false, adjustingSide: .left)
        XCTAssertEqual(merged, [.left: 50, .right: 55])
    }

    func testBusyWithUnknownSideUpdatesNothing() {
        let merged = FirmnessDisplay.merge(current: [.left: 40, .right: 55], reading: [.left: 100, .right: 60],
                                           pumpBusy: true, adjustingSide: nil)
        XCTAssertEqual(merged, [.left: 40, .right: 55])
    }

    func testBusyBeforeAnyIdleReadingLeavesOtherSideUnknown() {
        let merged = FirmnessDisplay.merge(current: [:], reading: [.left: 30, .right: 100],
                                           pumpBusy: true, adjustingSide: .left)
        XCTAssertEqual(merged, [.left: 30])
    }

    func testNoPumpInfoTakesReading() {
        let merged = FirmnessDisplay.merge(current: [.left: 40], reading: [.left: 45, .right: 50],
                                           pumpBusy: nil, adjustingSide: nil)
        XCTAssertEqual(merged, [.left: 45, .right: 50])
    }
}
