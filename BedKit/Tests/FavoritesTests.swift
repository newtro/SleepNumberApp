import XCTest
@testable import BedKit

final class FavoritesModelTests: XCTestCase {
    func testValuesAreClampedAndSnapped() {
        let favorite = BedFavorite(name: "  Reading  ", head: 140, foot: -12, sleepNumber: 47)
        XCTAssertEqual(favorite.name, "Reading")
        XCTAssertEqual(favorite.head, 100)
        XCTAssertEqual(favorite.foot, 0)
        XCTAssertEqual(favorite.sleepNumber, 45)
        XCTAssertEqual(BedFavorite(name: "", head: 0, foot: 0, sleepNumber: 0).name, "Favorite")
        XCTAssertEqual(BedFavorite(name: "", head: 0, foot: 0, sleepNumber: 0).sleepNumber, 5)
        XCTAssertEqual(BedFavorite(name: String(repeating: "x", count: 99), head: 0, foot: 0, sleepNumber: 50).name.count, 40)

        var edited = favorite
        edited.setHead(-1)
        edited.setFoot(101)
        edited.setSleepNumber(103)
        XCTAssertEqual([edited.head, edited.foot, edited.sleepNumber], [0, 100, 100])
    }

    func testDecodingClampsOutOfRangeValues() throws {
        let json = """
        {"version":1,"bedID":"B","left":[{"id":"6F9619FF-8B86-D011-B42D-00CF4FC964FF","name":"Hacked",
        "head":250,"foot":-3,"sleepNumber":42,"createdAt":"2026-10-10T20:00:00Z"}],"right":[]}
        """
        let document = try FavoritesDocument.decode(Data(json.utf8))
        XCTAssertEqual(document.left.first?.head, 100)
        XCTAssertEqual(document.left.first?.foot, 0)
        XCTAssertEqual(document.left.first?.sleepNumber, 40)
    }

    func testDocumentRoundTripAndEditing() throws {
        var document = FavoritesDocument(bedID: "11111111-2222-3333-4444-555555555555")
        let a = BedFavorite(name: "A", head: 10, foot: 20, sleepNumber: 30)
        let b = BedFavorite(name: "B", head: 40, foot: 0, sleepNumber: 60)
        let c = BedFavorite(name: "C", head: 70, foot: 5, sleepNumber: 90)
        document.add(a, to: .left)
        document.add(b, to: .left)
        document.add(c, to: .left)
        document.add(b, to: .right)

        var renamed = b
        renamed.name = "Bee"
        XCTAssertTrue(document.update(renamed, on: .left))
        XCTAssertFalse(document.update(BedFavorite(name: "ghost", head: 0, foot: 0, sleepNumber: 50), on: .left))

        document.move(on: .left, fromOffsets: IndexSet(integer: 2), toOffset: 0)
        XCTAssertEqual(document.left.map(\.name), ["C", "A", "Bee"])
        document.move(on: .left, fromOffsets: IndexSet(integer: 0), toOffset: 3)
        XCTAssertEqual(document.left.map(\.name), ["A", "Bee", "C"])

        document.delete(id: a.id, from: .left)
        XCTAssertEqual(document.left.map(\.name), ["Bee", "C"])
        XCTAssertEqual(document.right.map(\.name), ["B"], "sides are independent")

        let decoded = try FavoritesDocument.decode(try document.encoded())
        XCTAssertEqual(decoded.left.map(\.id), document.left.map(\.id))
        XCTAssertEqual(decoded.left.map(\.head), document.left.map(\.head))
        XCTAssertEqual(decoded.bedID, document.bedID)
        XCTAssertEqual(decoded.version, 1)
    }

    func testNewerFileVersionIsRejected() {
        let json = #"{"version":2,"bedID":"B","left":[],"right":[]}"#
        XCTAssertThrowsError(try FavoritesDocument.decode(Data(json.utf8)))
    }

    func testPlanOrder() {
        let favorite = BedFavorite(name: "X", head: 30, foot: 15, sleepNumber: 55)
        XCTAssertEqual(FavoriteApplier.plan(favorite), [.head(30), .foot(15), .firmness(55)])
    }
}

/// Simulated bed: axes move 10 points per position read toward their target; the pump finishes
/// after a number of polls.
@MainActor
class FakeFavoriteOps: FavoriteBedOps {
    var positions: [FoundationAxis: Int] = [.head: 0, .foot: 0]
    var targets: [FoundationAxis: Int] = [:]
    var sleepNumber = 50
    var pumpBusyPolls = 0
    var stallAt: [FoundationAxis: Int] = [:]
    var calls: [String] = []
    var onRead: (() -> Void)?

    func startMove(_ axis: FoundationAxis, to position: Int) async throws {
        calls.append("move \(axis.rawValue) \(position)")
        targets[axis] = position
    }

    func readPosition(_ axis: FoundationAxis) async throws -> (position: Int, moving: Bool) {
        onRead?()
        guard let target = targets[axis], let current = positions[axis] else { return (positions[axis] ?? 0, false) }
        if let stall = stallAt[axis], current == stall { return (current, false) }
        if current == target { targets[axis] = nil; return (current, false) }
        let next = current < target ? min(current + 10, target) : max(current - 10, target)
        positions[axis] = next
        return (next, next != target)
    }

    func stopMove() async { calls.append("stopMove") }

    func startSleepNumber(_ value: Int) async throws {
        calls.append("sleepNumber \(value)")
        sleepNumber = value
        pumpBusyPolls = max(pumpBusyPolls, 2)
    }

    func readPump() async throws -> (sleepNumber: Int, busy: Bool) {
        onRead?()
        if pumpBusyPolls > 0 { pumpBusyPolls -= 1; return (sleepNumber, true) }
        return (sleepNumber, false)
    }

    func stopPump() async { calls.append("stopPump") }
}

@MainActor
final class FavoriteApplierTests: XCTestCase {
    var applier: FavoriteApplier!

    override func setUp() async throws {
        var timing = FavoriteApplier.Timing()
        timing.movePoll = .milliseconds(1)
        timing.settleDelay = .milliseconds(1)
        timing.pumpStartDelay = .milliseconds(1)
        timing.pumpPoll = .milliseconds(1)
        timing.moveTimeout = .milliseconds(300)
        timing.pumpTimeout = .milliseconds(300)
        applier = FavoriteApplier(timing: timing)
    }

    func testAppliesHeadFootThenFirmness() async throws {
        let ops = FakeFavoriteOps()
        ops.positions = [.head: 0, .foot: 50]
        var progress: [FavoriteApplier.Progress] = []
        try await applier.apply(BedFavorite(name: "X", head: 40, foot: 20, sleepNumber: 65), ops: ops) { progress.append($0) }
        XCTAssertEqual(ops.calls, ["move head 40", "move foot 20", "sleepNumber 65"])
        XCTAssertEqual(ops.positions[.head], 40)
        XCTAssertEqual(ops.positions[.foot], 20)
        XCTAssertEqual(progress.first, .step(index: 1, of: 3, .head(40)))
        XCTAssertEqual(progress.last, .finished)
        XCTAssertTrue(progress.contains(.adjusting(.firmness(65), current: 65)))
    }

    func testStepsAlreadyThereAreSkipped() async throws {
        let ops = FakeFavoriteOps()
        ops.positions = [.head: 41, .foot: 20]
        ops.sleepNumber = 65
        var progress: [FavoriteApplier.Progress] = []
        try await applier.apply(BedFavorite(name: "X", head: 40, foot: 20, sleepNumber: 65), ops: ops) { progress.append($0) }
        XCTAssertEqual(ops.calls, [])
        XCTAssertEqual(progress.filter { if case .skipped = $0 { return true } else { return false } }.count, 3)
    }

    func testWaitsForBusyPumpBeforeSetting() async throws {
        let ops = FakeFavoriteOps()
        ops.pumpBusyPolls = 3
        var progress: [FavoriteApplier.Progress] = []
        try await applier.apply(BedFavorite(name: "X", head: 0, foot: 0, sleepNumber: 80), ops: ops) { progress.append($0) }
        XCTAssertTrue(progress.contains(.waitingForPump))
        XCTAssertEqual(ops.calls, ["sleepNumber 80"])
    }

    func testStoppedShortIsAnErrorAndLaterStepsDoNotRun() async {
        let ops = FakeFavoriteOps()
        ops.stallAt[.head] = 30
        do {
            try await applier.apply(BedFavorite(name: "X", head: 60, foot: 10, sleepNumber: 70), ops: ops) { _ in }
            XCTFail()
        } catch {
            guard case .commandFailed = error as? BedError else { return XCTFail("\(error)") }
        }
        XCTAssertEqual(ops.calls, ["move head 60"])
    }

    func testCancellationDuringMoveStopsAndSkipsRemainingSteps() async throws {
        let ops = FakeFavoriteOps()
        var reads = 0
        var task: Task<Void, Error>!
        ops.onRead = {
            reads += 1
            if reads == 3 { task.cancel() }
        }
        task = Task { try await self.applier.apply(BedFavorite(name: "X", head: 90, foot: 40, sleepNumber: 70), ops: ops) { _ in } }
        do {
            try await task.value
            XCTFail("apply should be cancelled")
        } catch {
            XCTAssertTrue(FavoriteApplier.isCancellation(error), "\(error)")
        }
        XCTAssertEqual(ops.calls, ["move head 90", "stopMove"])
    }

    func testCancellationDuringFirmnessInterruptsThePump() async throws {
        let ops = FakeFavoriteOps()
        ops.pumpBusyPolls = 0
        var pumpReads = 0
        var task: Task<Void, Error>!
        ops.onRead = {
            if ops.calls.contains("sleepNumber 30") {
                pumpReads += 1
                if pumpReads == 1 { task.cancel() }
            }
        }
        task = Task { try await self.applier.apply(BedFavorite(name: "X", head: 0, foot: 0, sleepNumber: 30), ops: ops) { _ in } }
        do {
            try await task.value
            XCTFail()
        } catch {
            XCTAssertTrue(FavoriteApplier.isCancellation(error), "\(error)")
        }
        XCTAssertEqual(ops.calls.last, "stopPump")
    }

    func testMoveTimeoutStopsTheSide() async {
        let ops = FakeFavoriteOps()
        // Always "moving" but never arriving.
        final class Stuck: FakeFavoriteOps {
            override func readPosition(_ axis: FoundationAxis) async throws -> (position: Int, moving: Bool) { (10, true) }
        }
        let stuck = Stuck()
        _ = ops
        do {
            try await applier.apply(BedFavorite(name: "X", head: 80, foot: 0, sleepNumber: 50), ops: stuck) { _ in }
            XCTFail()
        } catch {
            guard case .timeout = error as? BedError else { return XCTFail("\(error)") }
        }
        XCTAssertEqual(stuck.calls, ["move head 80", "stopMove"])
    }
}

@MainActor
final class FavoriteOpsWireTests: XCTestCase {
    let bindReply = Data(hex: "16 16 01 32 d3 00 00 01 32 d3 80 0a 01 02 03 04 05 06 07 08 a1 b2 29 44")!

    private func boundSession(_ link: FakeLink) async throws -> MCRSession {
        let session = MCRSession(link: link, clientID: [1, 2, 3, 4, 5, 6, 7, 8],
                                 timing: .init(responseTimeout: .milliseconds(100), retries: 0), log: { _ in })
        link.onNotify = { session.handleNotification($0) }
        link.responder = { [bindReply] data, _ in
            let frame = try! MCRFrame.decode(data)
            if frame.functionCode == 0 { return [bindReply] }
            return [try! MCRFrame(command: frame.command - 1, source: 0x32D3, destination: 0xA1B2, status: frame.command - 1,
                                  sourceEcho: 0x32D3, function: 0x80 | frame.functionCode, side: frame.side).encoded]
        }
        try await session.performBind()
        return session
    }

    func testMCRFavoriteMovesUseSleepExpertSetPositionKeys() async throws {
        let link = FakeLink()
        let session = try await boundSession(link)
        let right = MCRFavoriteOps(session: session, side: .zero)
        try await right.startMove(.head, to: 35)
        XCTAssertEqual(link.writes.last?.data.hexString,
                       "16 16 52 a1 b2 32 d3 52 a1 b2 1b 08 4d 46 55 52 33 35 5f 30 49 02")
        let left = MCRFavoriteOps(session: session, side: .one)
        try await left.startMove(.foot, to: 0)
        XCTAssertEqual(String(decoding: try MCRFrame.decode(link.writes.last!.data).payload, as: UTF8.self), "MFFL0_0")
        try await left.startSleepNumber(45)
        let set = try MCRFrame.decode(link.writes.last!.data)
        XCTAssertEqual([set.command, set.functionCode, set.side], [0x02, 0x11, 1])
        XCTAssertEqual(set.payload, [0, 45])
    }

    func testFavoriteOpsStopWorkingAfterStop() async throws {
        let link = FakeLink()
        let session = try await boundSession(link)
        let ops = MCRFavoriteOps(session: session, side: .zero)
        try await session.stopFoundation()
        do {
            try await ops.startMove(.head, to: 50)
            XCTFail("a move queued before STOP must not be sent")
        } catch {
            XCTAssertEqual(error as? BedError, .cancelled)
        }
        XCTAssertFalse(link.writes.contains { (try? MCRFrame.decode($0.data))?.payload.starts(with: Array("MFUR".utf8)) == true })
    }

    func testStopFramesAreNeverDroppedByAnOlderGeneration() async throws {
        let link = FakeLink()
        let session = try await boundSession(link)
        let old = session.stopGeneration
        session.invalidateQueued()
        try await session.send(try MCRCommands.stopMotion(side: .one), generation: old)
        XCTAssertEqual(String(decoding: try MCRFrame.decode(link.writes.last!.data).payload, as: UTF8.self), "MFHL110")
    }

    func testMACFallbackAddressStillMatchesReplies() async throws {
        let link = FakeLink()
        let session = MCRSession(link: link, clientID: [1, 2, 3, 4, 5, 6, 7, 8], macFallback: 0x32D3,
                                 timing: .init(responseTimeout: .milliseconds(100), retries: 0), log: { _ in })
        link.onNotify = { session.handleNotification($0) }
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            if frame.functionCode == 0 {
                // Bind reply without a bed address in the source field.
                return [try! MCRFrame(command: 1, destination: 0, status: 1, function: 0x80, side: 0,
                                      payload: [1, 2, 3, 4, 5, 6, 7, 8, 0xA1, 0xB2]).encoded]
            }
            // The bed answers with source fields 0.
            return [try! MCRFrame(command: frame.command - 1, destination: 0xA1B2, status: frame.command - 1,
                                  function: 0x80 | frame.functionCode, side: frame.side, payload: [1, 0, 0]).encoded]
        }
        let bind = try await session.performBind()
        XCTAssertTrue(bind.bedAddressFromMAC)
        let light = try await session.readUnderBedLight()
        XCTAssertTrue(light.on)
    }
}
