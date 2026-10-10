import XCTest
@testable import BedKit

@MainActor
final class MCRSessionTests: XCTestCase {
    let bed: UInt16 = 0x32D3
    let client: UInt16 = 0xA1B2
    let clientID: [UInt8] = [1, 2, 3, 4, 5, 6, 7, 8]
    var link: FakeLink!
    var session: MCRSession!
    var events: [FrameEvent] = []

    override func setUp() async throws {
        link = FakeLink()
        events = []
        session = MCRSession(link: link, clientID: clientID,
                             timing: .init(responseTimeout: .milliseconds(120), retries: 3),
                             log: { [weak self] in self?.events.append($0) })
        link.onNotify = { [weak self] in self?.session.handleNotification($0) }
    }

    /// A reply as the bed sends it: bed address in source fields, ours in destination.
    private func reply(to request: Data, payload: [UInt8] = [], side: UInt8? = nil, destination: UInt16? = nil) -> Data {
        let frame = try! MCRFrame.decode(request)
        return try! MCRFrame(command: frame.command - 1, source: bed, destination: destination ?? client,
                             status: frame.command - 1, sourceEcho: bed,
                             function: 0x80 | frame.functionCode, side: side ?? frame.side, payload: payload).encoded
    }

    private func bindReply() -> Data {
        Data(hex: "16 16 01 32 d3 00 00 01 32 d3 80 0a 01 02 03 04 05 06 07 08 a1 b2 29 44")!
    }

    private func answerEverything(payload: [UInt8] = []) {
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            return frame.functionCode == 0 ? [self.bindReply()] : [self.reply(to: data, payload: payload)]
        }
    }

    func testBindSendsClientIDAndStoresAddresses() async throws {
        link.responder = { _, _ in [self.bindReply()] }
        let result = try await session.performBind()
        XCTAssertEqual(result.bedAddress, bed)
        XCTAssertEqual(result.clientAddress, client)
        XCTAssertEqual(link.writes.first?.data.hexString,
                       "16 16 02 00 00 00 00 02 00 00 00 08 01 02 03 04 05 06 07 08 00 fe")
        XCTAssertEqual(link.writes.first?.characteristic, .mcrWrite)
    }

    func testBindFailsCleanlyWithoutReply() async {
        do {
            try await session.performBind()
            XCTFail("bind should time out")
        } catch {
            XCTAssertEqual(error as? BedError, .timeout("Bind"))
        }
        XCTAssertEqual(link.writes.count, 4)  // 1 try + 3 retries
        XCTAssertFalse(session.isBound)
    }

    func testBindUsesMACFallbackWhenReplyHasNoBedAddress() async throws {
        session = MCRSession(link: link, clientID: clientID, macFallback: 0x05D3, log: { _ in })
        link.onNotify = { [weak self] in self?.session.handleNotification($0) }
        link.responder = { _, _ in
            [try! MCRFrame(command: 1, destination: 0, status: 1, function: 0x80, side: 0, payload: Array(repeating: 0, count: 10)).encoded]
        }
        let bound = try await session.performBind()
        XCTAssertEqual(bound.bedAddress, 0x05D3)
    }

    func testRequestsBeforeBindAreRefused() async {
        do {
            _ = try await session.readPumpStatus()
            XCTFail()
        } catch {
            XCTAssertEqual(error as? BedError, .notConnected)
        }
    }

    func testRequestsCarryAssignedAddresses() async throws {
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            if frame.functionCode == 0 { return [self.bindReply()] }
            return FakeLink.chunks(self.reply(to: data, payload: [0, 40, 55], side: 1), size: 7)
        }
        try await session.performBind()
        let status = try await session.readPumpStatus()
        XCTAssertEqual(status.sleepNumber(.zero), 40)
        XCTAssertEqual(status.sleepNumber(.one), 55)
        XCTAssertEqual(link.writes.last?.data.hexString, "16 16 02 a1 b2 32 d3 02 a1 b2 12 00 16 65")
    }

    func testRepliesForSomeoneElseAreIgnored() async throws {
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            if frame.functionCode == 0 { return [self.bindReply()] }
            // A reply addressed to another client, then ours.
            return [self.reply(to: data, payload: [1, 0, 0], destination: 0x7777), self.reply(to: data, payload: [0, 0, 0])]
        }
        try await session.performBind()
        let light = try await session.readUnderBedLight()
        XCTAssertFalse(light.on)
        XCTAssertTrue(events.contains { $0.text.contains("unmatched") })
    }

    func testNackSubNibbleSurfacesAsError() async throws {
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            return frame.functionCode == 0 ? [self.bindReply()] : [self.reply(to: data, side: 0x0F)]
        }
        try await session.performBind()
        do {
            try await session.send(try MCRCommands.stopMotion(side: .zero))
            XCTFail()
        } catch {
            guard case .commandFailed = error as? BedError else { return XCTFail("\(error)") }
        }
    }

    func testFoundationStatusKeepAdjustingSelector() async throws {
        answerEverything(payload: [UInt8](Data(hex: "43 10 20 00 00 00 00 00 00 00 00 00 00 00 00")!))
        try await session.performBind()
        let status = try await session.readFoundationStatus(keepAdjusting: .zero)
        XCTAssertTrue(status.moving)
        XCTAssertEqual(status.headLeft, 32)
        XCTAssertEqual(try MCRFrame.decode(link.writes.last!.data).side, 2)
    }

    func testTimeoutRetriesThreeTimesThenFails() async throws {
        link.responder = { data, _ in
            (try! MCRFrame.decode(data)).functionCode == 0 ? [self.bindReply()] : []
        }
        try await session.performBind()
        do {
            _ = try await session.readUnderBedLight()
            XCTFail()
        } catch {
            XCTAssertEqual(error as? BedError, .timeout("Read light"))
        }
        XCTAssertEqual(link.writes.count, 1 + 4)  // bind + 1 try + 3 retries
    }

    func testSetSleepNumberOnIdlePumpSendsOnlyTheSet() async throws {
        answerEverything()
        try await session.performBind()
        try await session.setSleepNumber(50, side: .one, interruptRunning: false)
        let frames = link.writes.dropFirst().map { try! MCRFrame.decode($0.data) }
        XCTAssertEqual(frames.map(\.functionCode), [0x11])
        XCTAssertEqual(frames[0].side, 1)
        XCTAssertEqual(frames[0].payload, [0, 50])
    }

    func testSetSleepNumberInterruptingIdlesFirst() async throws {
        answerEverything()
        try await session.performBind()
        try await session.setSleepNumber(50, side: .zero, interruptRunning: true)
        let frames = link.writes.dropFirst().map { try! MCRFrame.decode($0.data) }
        XCTAssertEqual(frames.map(\.functionCode), [0x02, 0x11])
    }

    func testStopSendsKeyWritesUrgentlyThenConfirmed() async throws {
        answerEverything()
        try await session.performBind()
        try await session.stopFoundation()
        let frames = link.writes.dropFirst().map { try! MCRFrame.decode($0.data) }
        XCTAssertEqual(frames.map(\.command), [0x52, 0x52, 0x52, 0x52])
        XCTAssertEqual(frames.map { String(decoding: $0.payload, as: UTF8.self) }, ["MFHR110", "MFHL110", "MFHR110", "MFHL110"])
        XCTAssertFalse(frames.contains { $0.command == 0x42 }, "no binary foundation stop")
    }

    func testQueuedMotionIsDroppedAfterStop() async throws {
        // A slow poll holds the queue; a preset waits behind it; STOP is pressed.
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            if frame.functionCode == 0 { return [self.bindReply()] }
            if frame.functionCode == 0x12 { return [] }  // poll never answers (times out)
            return [self.reply(to: data)]
        }
        try await session.performBind()
        let poll = Task { try? await self.session.readFoundationStatus() }
        let preset = Task { try await self.session.activatePreset(.zeroG, side: .zero) }
        try await Task.sleep(for: .milliseconds(30))
        try await session.stopFoundation()
        do {
            try await preset.value
            XCTFail("preset must not be sent after STOP")
        } catch {
            XCTAssertEqual(error as? BedError, .cancelled)
        }
        _ = await poll.value
        let presets = link.writes.compactMap { try? MCRFrame.decode($0.data) }.filter { $0.functionCode == 0x15 }
        XCTAssertTrue(presets.isEmpty)
    }

    func testCancellationReleasesTheQueue() async throws {
        link.responder = { data, _ in
            (try! MCRFrame.decode(data)).functionCode == 0 ? [self.bindReply()] : []
        }
        try await session.performBind()
        let task = Task { try await self.session.send(try MCRCommands.readPumpStatus(), timeout: .seconds(30), retries: 0) }
        try await Task.sleep(for: .milliseconds(20))
        task.cancel()
        do {
            _ = try await task.value
            XCTFail()
        } catch {
            XCTAssertEqual(error as? BedError, .cancelled)
        }
        link.responder = { data, _ in [self.reply(to: data, payload: [0, 0, 0])] }
        let light = try await session.readUnderBedLight()
        XCTAssertFalse(light.on)
    }
}
