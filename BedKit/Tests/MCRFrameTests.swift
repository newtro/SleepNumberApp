import XCTest
@testable import BedKit

/// Vectors were generated independently from the reference Python implementations
/// (sleepnumber-ble `_build_mcr` / ha-adjustable-bed `_build_frame` with zero target/echo).
final class MCRFrameTests: XCTestCase {
    let bed: UInt16 = 0x32D3

    private func hex(_ request: MCRRequest, sub: UInt16? = nil) throws -> String {
        try request.frame(subAddress: sub ?? bed).encoded.hexString
    }

    func testChecksumKnownVector() {
        let body: [UInt8] = [0x02, 0, 0, 0, 0, 0x02, 0, 0, 0, 0x08] + Array(repeating: 0, count: 8)
        XCTAssertEqual(MCRChecksum.compute(body), 0x0086)
        XCTAssertEqual(MCRChecksum.compute([]), 0)
        XCTAssertEqual(MCRChecksum.compute([1, 2, 3]), 1 + 3 + 6)
    }

    func testZeroPayloadHandshakeVector() throws {
        XCTAssertEqual(try hex(MCRCommands.bind(), sub: 0),
                       "16 16 02 00 00 00 00 02 00 00 00 08 00 00 00 00 00 00 00 00 00 86")
    }

    func testCommandVectors() throws {
        XCTAssertEqual(try hex(MCRCommands.readPumpStatus()), "16 16 02 00 00 32 d3 02 00 00 12 f0 07 82")
        XCTAssertEqual(try hex(MCRCommands.activatePreset(.zeroG, side: .one)),
                       "16 16 42 00 00 32 d3 42 00 00 15 12 05 00 0d d4")
        XCTAssertEqual(try hex(MCRCommands.setSleepNumber(side: .zero, value: 45)),
                       "16 16 02 00 00 32 d3 02 00 00 11 02 00 2d 08 f7")
        XCTAssertEqual(try hex(MCRCommands.setFoundationTarget(side: .zero, axis: .head, position: 100)),
                       "16 16 42 00 00 32 d3 42 00 00 11 0c 64 00 ff ff ff ff ff ff ff ff ff ff 59 9d")
        XCTAssertEqual(try hex(MCRCommands.setFoundationTarget(side: .zero, axis: .foot, position: 30)),
                       "16 16 42 00 00 32 d3 42 00 00 11 0c ff ff 1e 00 ff ff ff ff ff ff ff ff 5a 15")
        XCTAssertEqual(try hex(MCRCommands.setUnderBedLight(on: true)),
                       "16 16 42 00 00 32 d3 42 00 00 13 33 01 00 00 0f f7")
        XCTAssertEqual(try hex(MCRCommands.stopMotion(side: .zero)),
                       "16 16 52 00 00 32 d3 52 00 00 1b 07 4d 46 48 52 31 31 30 1f 7c")
        XCTAssertEqual(try hex(MCRCommands.readFoundationStatus(keepAdjusting: .one)),
                       "16 16 42 00 00 32 d3 42 00 00 12 30 0a 82")
    }

    func testStopKeysFollowWireSide() throws {
        XCTAssertEqual(try MCRCommands.stopMotion(side: .zero).payload, Array("MFHR110".utf8))
        XCTAssertEqual(try MCRCommands.stopMotion(side: .one).payload, Array("MFHL110".utf8))
    }

    func testDecodeRoundTripAndRejection() throws {
        let original = try MCRCommands.activatePreset(.flat, side: .one).frame(subAddress: bed)
        XCTAssertEqual(try MCRFrame.decode(original.encoded), original)

        var corrupt = [UInt8](original.encoded)
        corrupt[corrupt.count - 1] ^= 0xFF
        XCTAssertThrowsError(try MCRFrame.decode(Data(corrupt)))
        XCTAssertThrowsError(try MCRFrame.decode(Data([0x16, 0x16, 0x02])))
        XCTAssertThrowsError(try MCRFrame.decode(original.encoded.dropLast()))
    }

    func testFrameRejectsOversizedPayloadAndSide() {
        XCTAssertThrowsError(try MCRFrame(command: 2, subAddress: 0, status: 2, function: 0, side: 0, payload: Array(repeating: 0, count: 16)))
        XCTAssertThrowsError(try MCRFrame(command: 2, subAddress: 0, status: 2, function: 0, side: 16))
    }

    func testStreamParserReassemblesFragmentedReply() throws {
        let reply = Data(hex: "16 16 41 32 d3 32 d3 41 32 d3 92 0f 42 2e 2e 4c 4c 00 00 00 00 00 00 00 00 00 55 65 bc")!
        var parser = MCRStreamParser()
        XCTAssertEqual(parser.feed(reply.prefix(20)), [])
        let frames = parser.feed(reply.dropFirst(20))
        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(frames.first?.functionCode, 18)
        XCTAssertTrue(frames.first?.isResponse ?? false)
        XCTAssertEqual(frames.first?.payload.count, 15)
    }

    func testStreamParserSkipsGarbageAndSplitsBackToBackFrames() throws {
        let a = try MCRCommands.readPumpStatus().frame(subAddress: bed).encoded
        let b = try MCRCommands.setUnderBedLight(on: false).frame(subAddress: bed).encoded
        var parser = MCRStreamParser()
        var stream = Data([0x00, 0x16, 0x99, 0x16]) + a + b
        // Corrupt frame (bad checksum) followed by a good one: the parser must resync.
        var bad = [UInt8](a)
        bad[bad.count - 2] ^= 0x01
        stream += Data(bad) + a
        let frames = parser.feed(stream)
        XCTAssertEqual(frames.map(\.functionCode), [18, 19, 18])
        XCTAssertGreaterThan(parser.discardedBytes, 0)
    }

    func testStreamParserHandlesSyncBytesInsidePayload() throws {
        // Payload containing 16 16 must not confuse framing.
        let frame = try MCRFrame(command: 0x41, target: bed, subAddress: bed, status: 0x41, echo: bed,
                                 function: 0x80 | 20, side: 3, payload: [0x16, 0x16, 0x00])
        var parser = MCRStreamParser()
        let encoded = frame.encoded
        var frames: [MCRFrame] = []
        for byte in encoded { frames += parser.feed(Data([byte])) }
        XCTAssertEqual(frames, [frame])
    }

    func testBindReplyParsing() throws {
        let reply = try MCRFrame.decode(Data(hex: "16 16 01 32 d3 00 00 01 32 d3 80 0a 00 00 00 00 00 00 00 00 32 d3 27 c7")!)
        let result = try MCRBindResult.parse(reply)
        XCTAssertEqual(result.bedAddress, 0x32D3)
        XCTAssertEqual(result.clientAddress, 0x32D3)

        let bare = try MCRFrame(command: 1, subAddress: 0, status: 1, function: 0x80, side: 0, payload: Array(repeating: 0, count: 8))
        XCTAssertEqual(try MCRBindResult.parse(bare, macFallback: 0x32D3).bedAddress, 0x32D3)
        XCTAssertThrowsError(try MCRBindResult.parse(bare))
    }

    func testPumpStatusParsing() throws {
        let frame = try MCRFrame(command: 1, target: bed, subAddress: 0, status: 1, echo: bed, function: 0x80 | 18,
                                 side: 1, payload: [1, 45, 60, 0, 1])
        let status = try MCRPumpStatus.parse(frame)
        XCTAssertEqual(status.sleepNumber(.zero), 45)
        XCTAssertEqual(status.sleepNumber(.one), 60)
        XCTAssertFalse(status.pumping(.zero))
        XCTAssertTrue(status.pumping(.one))
        XCTAssertTrue(status.dualChamber)
        let short = try MCRFrame(command: 1, subAddress: 0, status: 1, function: 0x80 | 18, side: 0, payload: [1])
        XCTAssertThrowsError(try MCRPumpStatus.parse(short))
    }

    func testFoundationStatusParsingFromLiveCapture() throws {
        // Captured on an i8 after Zero G on side 0 (sleepnumber-ble PROTOCOL.md).
        let payload = [UInt8](Data(hex: "42 2e 2e 4c 4c 00 00 00 00 00 00 00 00 00 55")!)
        let frame = try MCRFrame(command: 0x41, target: bed, subAddress: bed, status: 0x41, echo: bed,
                                 function: 0x80 | 18, side: 0, payload: payload)
        let status = try MCRFoundationStatus.parse(frame)
        XCTAssertEqual(status.head(.zero), 46)
        XCTAssertEqual(status.head(.one), 46)
        XCTAssertEqual(status.foot(.zero), 76)
        XCTAssertFalse(status.moving)
        XCTAssertTrue(status.configured)
        XCTAssertEqual(status.preset0, .zeroG)
        XCTAssertEqual(status.preset1, .zeroG)

        var moving = payload
        moving[0] = 0x43
        let movingFrame = try MCRFrame(command: 0x41, subAddress: bed, status: 0x41, function: 0x80 | 18, side: 0, payload: moving)
        XCTAssertTrue(try MCRFoundationStatus.parse(movingFrame).moving)

        var flatLeft = payload
        flatLeft[1] = 0
        flatLeft[14] = 0x04
        let flatFrame = try MCRFrame(command: 0x41, subAddress: bed, status: 0x41, function: 0x80 | 18, side: 0, payload: flatLeft)
        let flat = try MCRFoundationStatus.parse(flatFrame)
        XCTAssertEqual(flat.preset0, .flat)
        XCTAssertNil(flat.preset1)

        var bogus = payload
        bogus[2] = 0xC8
        let bogusFrame = try MCRFrame(command: 0x41, subAddress: bed, status: 0x41, function: 0x80 | 18, side: 0, payload: bogus)
        XCTAssertThrowsError(try MCRFoundationStatus.parse(bogusFrame))
    }

    func testLightStatusParsing() throws {
        let on = try MCRFrame(command: 0x41, subAddress: bed, status: 0x41, function: 0x80 | 20, side: 3, payload: [1, 10, 0])
        XCTAssertTrue(try MCRLightStatus.parse(on).on)
        let off = try MCRFrame(command: 0x41, subAddress: bed, status: 0x41, function: 0x80 | 20, side: 3, payload: [0, 0, 0])
        XCTAssertFalse(try MCRLightStatus.parse(off).on)
    }

    func testFirmnessValidation() {
        XCTAssertThrowsError(try MCRCommands.setSleepNumber(side: .zero, value: 47))
        XCTAssertThrowsError(try MCRCommands.setSleepNumber(side: .zero, value: 0))
        XCTAssertThrowsError(try MCRCommands.setSleepNumber(side: .zero, value: 105))
        XCTAssertNoThrow(try MCRCommands.setSleepNumber(side: .one, value: 5))
        XCTAssertNoThrow(try MCRCommands.setSleepNumber(side: .one, value: 100))
        XCTAssertEqual(SleepNumber.normalize(47), 45)
        XCTAssertEqual(SleepNumber.normalize(48), 50)
        XCTAssertEqual(SleepNumber.normalize(-3), 5)
        XCTAssertEqual(SleepNumber.normalize(250), 100)
    }
}
