import XCTest
@testable import BedKit

@MainActor
final class MCRSessionTests: XCTestCase {
    let bed: UInt16 = 0x32D3
    var link: FakeLink!
    var session: MCRSession!
    var events: [FrameEvent] = []

    override func setUp() async throws {
        link = FakeLink()
        events = []
        session = MCRSession(link: link,
                             timing: .init(responseTimeout: .milliseconds(150), retries: 2, bindTimeout: .milliseconds(150), bindAttempts: 2),
                             log: { [weak self] in self?.events.append($0) })
        link.onNotify = { [weak self] in self?.session.handleNotification($0) }
    }

    private func reply(to request: Data, payload: [UInt8] = [], side: UInt8? = nil) -> Data {
        let frame = try! MCRFrame.decode(request)
        return try! MCRFrame(command: frame.command - 1, target: bed, subAddress: 0, status: frame.command - 1, echo: bed,
                             function: 0x80 | frame.functionCode, side: side ?? frame.side, payload: payload).encoded
    }

    private func bindReply() -> Data {
        try! MCRFrame(command: 1, target: bed, subAddress: 0, status: 1, echo: bed, function: 0x80, side: 0,
                      payload: Array(repeating: 0, count: 8) + [0x32, 0xD3]).encoded
    }

    func testBindSendsKnownVectorAndStoresAddress() async throws {
        link.responder = { _, _ in [self.bindReply()] }
        let result = try await session.performBind()
        XCTAssertEqual(result.bedAddress, bed)
        XCTAssertEqual(link.writes.first?.data.hexString,
                       "16 16 02 00 00 00 00 02 00 00 00 08 00 00 00 00 00 00 00 00 00 86")
        XCTAssertEqual(link.writes.first?.characteristic, .mcrWrite)
    }

    func testBindFailsCleanlyWithoutReply() async {
        do {
            try await session.performBind()
            XCTFail("bind should time out")
        } catch {
            XCTAssertEqual(error as? BedError, .timeout("Bind"))
        }
        XCTAssertEqual(link.writes.count, 2)  // two attempts
        XCTAssertFalse(session.isBound)
    }

    func testBindUsesMACFallbackWhenReplyHasNoAddress() async throws {
        session = MCRSession(link: link, macFallback: 0x05D3, log: { _ in })
        link.onNotify = { [weak self] in self?.session.handleNotification($0) }
        link.responder = { _, _ in
            [try! MCRFrame(command: 1, subAddress: 0, status: 1, function: 0x80, side: 0, payload: Array(repeating: 0, count: 8)).encoded]
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

    func testPumpStatusFromFragmentedReply() async throws {
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            if frame.functionCode == 0 { return [self.bindReply()] }
            return FakeLink.chunks(self.reply(to: data, payload: [1, 40, 55, 1, 0], side: 1), size: 7)
        }
        try await session.performBind()
        let status = try await session.readPumpStatus()
        XCTAssertEqual(status.sleepNumber0, 40)
        XCTAssertEqual(status.sleepNumber1, 55)
        XCTAssertTrue(status.pumping0)
        // Request carried the bed address in sub and the all-sides selector.
        XCTAssertEqual(link.writes.last?.data.hexString, "16 16 02 00 00 32 d3 02 00 00 12 f0 07 82")
    }

    func testFoundationStatusKeepAdjustingSelector() async throws {
        let payload = [UInt8](Data(hex: "43 10 20 00 00 00 00 00 00 00 00 00 00 00 00")!)
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            return frame.functionCode == 0 ? [self.bindReply()] : [self.reply(to: data, payload: payload, side: 0)]
        }
        try await session.performBind()
        let status = try await session.readFoundationStatus(keepAdjusting: .zero)
        XCTAssertTrue(status.moving)
        XCTAssertEqual(status.head1, 32)
        XCTAssertEqual(try MCRFrame.decode(link.writes.last!.data).side, 2)
    }

    func testTimeoutRetriesThenFails() async throws {
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
        XCTAssertEqual(link.writes.count, 1 + 3)  // bind + 1 try + 2 retries
    }

    func testUnmatchedRepliesAreIgnored() async throws {
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            if frame.functionCode == 0 { return [self.bindReply()] }
            // A stray pump reply arrives before the real light reply.
            let stray = try! MCRFrame(command: 1, target: self.bed, subAddress: 0, status: 1, echo: self.bed,
                                      function: 0x80 | 20, side: 3, payload: [9]).encoded
            return [stray, self.reply(to: data, payload: [1, 10, 0])]
        }
        try await session.performBind()
        let light = try await session.readUnderBedLight()
        XCTAssertTrue(light.on)
        XCTAssertTrue(events.contains { $0.text.contains("unmatched") })
    }

    func testSetSleepNumberIdlesPumpFirst() async throws {
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            return frame.functionCode == 0 ? [self.bindReply()] : [self.reply(to: data)]
        }
        try await session.performBind()
        try await session.setSleepNumber(50, side: .one)
        let functions = link.writes.dropFirst().map { try! MCRFrame.decode($0.data) }.map { ($0.command, $0.functionCode, $0.side, $0.payload) }
        XCTAssertEqual(functions.count, 2)
        XCTAssertEqual(functions[0].1, 2)
        XCTAssertEqual(functions[1].1, 17)
        XCTAssertEqual(functions[1].2, 1)
        XCTAssertEqual(functions[1].3, [0, 50])
    }

    func testStopFoundationSendsReleaseForBothSidesThenIdle() async throws {
        link.responder = { data, _ in
            (try! MCRFrame.decode(data)).functionCode == 0 ? [self.bindReply()] : []
        }
        try await session.performBind()
        try await session.stopFoundation()
        let frames = link.writes.dropFirst().map { try! MCRFrame.decode($0.data) }
        XCTAssertEqual(frames.map(\.command), [0x52, 0x52, 0x42])
        XCTAssertEqual(frames[0].payload, Array("MFHR110".utf8))
        XCTAssertEqual(frames[1].payload, Array("MFHL110".utf8))
        XCTAssertEqual(frames[2].functionCode, 2)
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
        // The queue is free again: the next request is written immediately.
        link.responder = { data, _ in [self.reply(to: data, payload: [0, 0, 0])] }
        let light = try await session.readUnderBedLight()
        XCTAssertFalse(light.on)
    }
}
