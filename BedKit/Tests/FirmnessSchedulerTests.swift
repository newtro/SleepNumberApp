import XCTest
@testable import BedKit

final class FirmnessSchedulerTests: XCTestCase {
    func testRefusedRequestLeavesNoState() {
        var scheduler = FirmnessScheduler()
        XCTAssertEqual(scheduler.request(50, side: .left, blockedReason: "A favorite is being applied."),
                       .refused("A favorite is being applied."))
        XCTAssertEqual(scheduler, FirmnessScheduler(), "a refused request must not mark a side as adjusting")
        // So a later request for the other side is sent, not queued forever.
        XCTAssertEqual(scheduler.request(60, side: .right, blockedReason: nil), .send(side: .right, value: 60, interrupt: false))
    }

    func testOtherSideWaitsUntilAcknowledgedAdjustmentFinishes() {
        var scheduler = FirmnessScheduler()
        let t0 = Date()
        XCTAssertEqual(scheduler.request(47, side: .left, blockedReason: nil), .send(side: .left, value: 45, interrupt: false))
        XCTAssertEqual(scheduler.request(70, side: .right, blockedReason: nil), .queued)
        XCTAssertNil(scheduler.nextQueued(), "pump still marked busy")

        // An idle reading from a poll that started before the acknowledgement does not count.
        XCTAssertNil(scheduler.pumpReading(busy: false, pollStarted: t0))
        XCTAssertEqual(scheduler.adjustingSide, .left)
        scheduler.acknowledged(side: .left, at: t0.addingTimeInterval(2))
        XCTAssertNil(scheduler.pumpReading(busy: false, pollStarted: t0.addingTimeInterval(2.5)), "less than 1 s after ack")
        XCTAssertNil(scheduler.pumpReading(busy: true, pollStarted: t0.addingTimeInterval(4)))
        XCTAssertEqual(scheduler.pumpReading(busy: false, pollStarted: t0.addingTimeInterval(6)), .left)
        XCTAssertNil(scheduler.requested[.left])

        XCTAssertEqual(scheduler.nextQueued(), .send(side: .right, value: 70, interrupt: false))
        XCTAssertEqual(scheduler.adjustingSide, .right)
        XCTAssertNil(scheduler.queued[.right])
    }

    func testSameSideInterruptsAndReplaces() {
        var scheduler = FirmnessScheduler()
        _ = scheduler.request(40, side: .right, blockedReason: nil)
        XCTAssertEqual(scheduler.request(55, side: .right, blockedReason: nil), .send(side: .right, value: 55, interrupt: true))
    }

    func testBusyPumpFromElsewhereQueues() {
        var scheduler = FirmnessScheduler()
        scheduler.pumpReading(busy: true, pollStarted: Date())
        XCTAssertEqual(scheduler.request(30, side: .left, blockedReason: nil), .queued)
        scheduler.pumpReading(busy: false, pollStarted: Date())
        XCTAssertEqual(scheduler.nextQueued(), .send(side: .left, value: 30, interrupt: false))
    }

    func testFailedSendFreesThePump() {
        var scheduler = FirmnessScheduler()
        _ = scheduler.request(40, side: .left, blockedReason: nil)
        scheduler.sendFailed(side: .left)
        XCTAssertNil(scheduler.adjustingSide)
        XCTAssertNil(scheduler.requested[.left])
    }

    func testClearKeepsOrDropsTheRunningChange() {
        var scheduler = FirmnessScheduler()
        _ = scheduler.request(40, side: .left, blockedReason: nil)
        _ = scheduler.request(80, side: .right, blockedReason: nil)
        scheduler.clear(includingRunning: false)
        XCTAssertTrue(scheduler.queued.isEmpty)
        XCTAssertNil(scheduler.requested[.right], "dropped queued requests leave no stale target")
        XCTAssertEqual(scheduler.requested[.left], 40)
        XCTAssertEqual(scheduler.adjustingSide, .left)
        scheduler.clear(includingRunning: true)
        XCTAssertNil(scheduler.adjustingSide)
        XCTAssertTrue(scheduler.requested.isEmpty)
    }
}
