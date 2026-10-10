import XCTest
@testable import BedKit

/// Vectors were generated independently in Python from the frame layout in
/// docs/PROTOCOL-FROM-APK.md §2.1 (client 0xA1B2 in bytes 1-2/6-7, bed 0x32D3 in bytes 3-4).
final class MCRFrameTests: XCTestCase {
    let bed: UInt16 = 0x32D3
    let client: UInt16 = 0xA1B2

    private func hex(_ request: MCRRequest) throws -> String {
        try request.frame(client: client, bed: bed).encoded.hexString
    }

    func testChecksumKnownVector() {
        let body: [UInt8] = [0x02, 0, 0, 0, 0, 0x02, 0, 0, 0, 0x08] + Array(repeating: 0, count: 8)
        XCTAssertEqual(MCRChecksum.compute(body), 0x0086)
        XCTAssertEqual(MCRChecksum.compute([]), 0)
        XCTAssertEqual(MCRChecksum.compute([1, 2, 3]), 1 + 3 + 6)
    }

    func testZeroPayloadHandshakeVector() throws {
        XCTAssertEqual(try MCRCommands.bind().frame(client: 0, bed: 0).encoded.hexString,
                       "16 16 02 00 00 00 00 02 00 00 00 08 00 00 00 00 00 00 00 00 00 86")
    }

    func testHandshakeWithClientIDVector() throws {
        XCTAssertEqual(try MCRCommands.bind(clientID: [1, 2, 3, 4, 5, 6, 7, 8]).frame(client: 0, bed: 0).encoded.hexString,
                       "16 16 02 00 00 00 00 02 00 00 00 08 01 02 03 04 05 06 07 08 00 fe")
        XCTAssertThrowsError(try MCRCommands.bind(clientID: [1, 2, 3]))
    }

    func testCommandVectors() throws {
        XCTAssertEqual(try hex(MCRCommands.readPumpStatus()), "16 16 02 a1 b2 32 d3 02 a1 b2 12 00 16 65")
        XCTAssertEqual(try hex(MCRCommands.setSleepNumber(side: .one, value: 45)),
                       "16 16 02 a1 b2 32 d3 02 a1 b2 11 12 00 2d 1e 46")
        XCTAssertEqual(try hex(MCRCommands.setFoundationTarget(side: .zero, axis: .head, position: 100)),
                       "16 16 42 a1 b2 32 d3 42 a1 b2 11 0c 64 00 ff ff ff ff ff ff ff ff ff ff 89 38")
        XCTAssertEqual(try hex(MCRCommands.setFoundationTarget(side: .one, axis: .foot, position: 0)),
                       "16 16 42 a1 b2 32 d3 42 a1 b2 11 1c ff ff 00 00 ff ff ff ff ff ff ff ff 89 54")
        XCTAssertEqual(try hex(MCRCommands.readFoundationStatus(keepAdjusting: .one)),
                       "16 16 42 a1 b2 32 d3 42 a1 b2 12 30 1a 55")
        XCTAssertEqual(try hex(MCRCommands.stopMotion(side: .one)),
                       "16 16 52 a1 b2 32 d3 52 a1 b2 1b 07 4d 46 48 4c 31 31 30 41 c1")
        XCTAssertEqual(try hex(MCRCommands.setPosition(side: .zero, axis: .head, position: 35)),
                       "16 16 52 a1 b2 32 d3 52 a1 b2 1b 08 4d 46 55 52 33 35 5f 30 49 02")
        XCTAssertEqual(try hex(MCRCommands.activatePreset(.snore, side: .zero)),
                       "16 16 42 a1 b2 32 d3 42 a1 b2 15 02 06 00 22 c5")
        XCTAssertEqual(try hex(MCRCommands.setUnderBedLight(on: true)),
                       "16 16 42 a1 b2 32 d3 42 a1 b2 13 33 01 00 00 27 bc")
    }

    func testSleepExpertKeysFollowWireSide() throws {
        XCTAssertEqual(try MCRCommands.stopMotion(side: .zero).payload, Array("MFHR110".utf8))
        XCTAssertEqual(try MCRCommands.stopMotion(side: .one).payload, Array("MFHL110".utf8))
        XCTAssertEqual(try MCRCommands.setPosition(side: .one, axis: .foot, position: 7).payload, Array("MFFL7_0".utf8))
        XCTAssertEqual(try MCRCommands.setPosition(side: .zero, axis: .head, position: 100).payload, Array("MFUR100_0".utf8))
        XCTAssertThrowsError(try MCRCommands.setPosition(side: .zero, axis: .head, position: 101))
        let stop = try MCRCommands.stopMotion(side: .zero)
        XCTAssertEqual(stop.node, .sleepExpert)
        XCTAssertEqual(stop.function, 0x1B)
        XCTAssertEqual(stop.side, 0)
    }

    func testDecodeRoundTripAndRejection() throws {
        let original = try MCRCommands.activatePreset(.flat, side: .one).frame(client: client, bed: bed)
        XCTAssertEqual(try MCRFrame.decode(original.encoded), original)
        XCTAssertEqual(original.source, client)
        XCTAssertEqual(original.sourceEcho, client)
        XCTAssertEqual(original.destination, bed)

        var corrupt = [UInt8](original.encoded)
        corrupt[corrupt.count - 1] ^= 0xFF
        XCTAssertThrowsError(try MCRFrame.decode(Data(corrupt)))
        XCTAssertThrowsError(try MCRFrame.decode(Data([0x16, 0x16, 0x02])))
        XCTAssertThrowsError(try MCRFrame.decode(original.encoded.dropLast()))
    }

    func testFrameRejectsOversizedPayloadAndSide() {
        XCTAssertThrowsError(try MCRFrame(command: 2, destination: 0, status: 2, function: 0, side: 0, payload: Array(repeating: 0, count: 16)))
        XCTAssertThrowsError(try MCRFrame(command: 2, destination: 0, status: 2, function: 0, side: 16))
    }

    func testStreamParserReassemblesFragmentedReply() throws {
        let reply = try MCRFrame(command: 0x41, source: bed, destination: client, status: 0x41, sourceEcho: bed,
                                 function: 0x80 | 0x12, side: 0,
                                 payload: [UInt8](Data(hex: "42 2e 2e 4c 4c 00 00 00 00 00 00 00 00 00 55")!)).encoded
        var parser = MCRStreamParser()
        XCTAssertEqual(parser.feed(reply.prefix(20)), [])
        let frames = parser.feed(reply.dropFirst(20))
        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(frames.first?.functionCode, 0x12)
        XCTAssertTrue(frames.first?.isResponse ?? false)
        XCTAssertEqual(frames.first?.payload.count, 15)
    }

    func testStreamParserSkipsGarbageAndSplitsBackToBackFrames() throws {
        let a = try MCRCommands.readPumpStatus().frame(client: client, bed: bed).encoded
        let b = try MCRCommands.setUnderBedLight(on: false).frame(client: client, bed: bed).encoded
        var parser = MCRStreamParser()
        var stream = Data([0x00, 0x16, 0x99, 0x16]) + a + b
        var bad = [UInt8](a)
        bad[bad.count - 2] ^= 0x01
        stream += Data(bad) + a
        let frames = parser.feed(stream)
        XCTAssertEqual(frames.map(\.functionCode), [0x12, 0x13, 0x12])
        XCTAssertGreaterThan(parser.discardedBytes, 0)
    }

    func testStreamParserHandlesSyncBytesInsidePayload() throws {
        let frame = try MCRFrame(command: 0x41, source: bed, destination: client, status: 0x41, sourceEcho: bed,
                                 function: 0x80 | 0x14, side: 3, payload: [0x16, 0x16, 0x00])
        var parser = MCRStreamParser()
        var frames: [MCRFrame] = []
        for byte in frame.encoded { frames += parser.feed(Data([byte])) }
        XCTAssertEqual(frames, [frame])
    }

    func testBindReplyParsing() throws {
        // Reply: bed address in header bytes 1-2, our address in payload bytes 8-9.
        let reply = try MCRFrame.decode(Data(hex: "16 16 01 32 d3 00 00 01 32 d3 80 0a 01 02 03 04 05 06 07 08 a1 b2 29 44")!)
        let result = try MCRBindResult.parse(reply)
        XCTAssertEqual(result.bedAddress, bed)
        XCTAssertEqual(result.clientAddress, client)

        let bare = try MCRFrame(command: 1, destination: 0, status: 1, function: 0x80, side: 0, payload: Array(repeating: 0, count: 8))
        let fallback = try MCRBindResult.parse(bare, macFallback: 0x32D3)
        XCTAssertEqual(fallback.bedAddress, 0x32D3)
        XCTAssertEqual(fallback.clientAddress, 0)
        XCTAssertThrowsError(try MCRBindResult.parse(bare))
    }

    func testPumpStatusParsing() throws {
        // [busy, right (side 0), left (side 1)]; reply sub-nibble = chamber type.
        let frame = try MCRFrame.decode(Data(hex: "16 16 01 32 d3 a1 b2 01 32 d3 92 13 00 28 37 22 07")!)
        let status = try MCRPumpStatus.parse(frame)
        XCTAssertFalse(status.busy)
        XCTAssertEqual(status.sleepNumber(.zero), 40)
        XCTAssertEqual(status.sleepNumber(.one), 55)
        XCTAssertEqual(status.chamberType, 1)
        let busy = try MCRFrame(command: 1, destination: 0, status: 1, function: 0x80 | 0x12, side: 0, payload: [1, 40, 55])
        XCTAssertTrue(try MCRPumpStatus.parse(busy).busy)
        let short = try MCRFrame(command: 1, destination: 0, status: 1, function: 0x80 | 0x12, side: 0, payload: [1])
        XCTAssertThrowsError(try MCRPumpStatus.parse(short))
    }

    func testFoundationStatusParsing() throws {
        // i8 capture after Zero G (sleepnumber-ble), decoded with the app's layout.
        let payload = [UInt8](Data(hex: "42 2e 2e 4c 4c 00 00 00 00 00 00 00 00 00 55")!)
        let frame = try MCRFrame(command: 0x41, source: bed, destination: client, status: 0x41, sourceEcho: bed,
                                 function: 0x80 | 0x12, side: 0, payload: payload)
        let status = try MCRFoundationStatus.parse(frame)
        XCTAssertEqual(status.head(.zero), 46)
        XCTAssertEqual(status.head(.one), 46)
        XCTAssertEqual(status.foot(.zero), 76)
        XCTAssertEqual(status.foot(.one), 76)
        XCTAssertFalse(status.moving)
        XCTAssertTrue(status.configured)
        XCTAssertFalse(status.needsHoming)
        XCTAssertEqual(status.preset(.zero), .zeroG)
        XCTAssertEqual(status.preset(.one), .zeroG)

        var moving = payload
        moving[10] = 0x01  // left head actuator moving
        let movingStatus = try MCRFoundationStatus.parse(try MCRFrame(command: 0x41, destination: 0, status: 0x41,
                                                                      function: 0x80 | 0x12, side: 0, payload: moving))
        XCTAssertTrue(movingStatus.moving)
        XCTAssertEqual(movingStatus.actuator(.head, side: .one)?.moving, true)
        XCTAssertEqual(movingStatus.actuator(.head, side: .zero)?.moving, false)

        var fault = payload
        fault[11] = 0x10
        let faultStatus = try MCRFoundationStatus.parse(try MCRFrame(command: 0x41, destination: 0, status: 0x41,
                                                                     function: 0x80 | 0x12, side: 0, payload: fault))
        XCTAssertEqual(faultStatus.actuator(.foot, side: .zero)?.fault, "motor current high")

        var homing = payload
        homing[0] = 0x62
        XCTAssertTrue(try MCRFoundationStatus.parse(try MCRFrame(command: 0x41, destination: 0, status: 0x41,
                                                                 function: 0x80 | 0x12, side: 0, payload: homing)).needsHoming)

        var bogus = payload
        bogus[2] = 0xC8
        XCTAssertThrowsError(try MCRFoundationStatus.parse(try MCRFrame(command: 0x41, destination: 0, status: 0x41,
                                                                        function: 0x80 | 0x12, side: 0, payload: bogus)))
    }

    func testSystemStatusParsingMatchesApp() throws {
        // sleepnumber-ble's i8 sample: config 1, flags 0x15 = dual + foot + under-bed light -> 360 FF2.
        let frame = try MCRFrame(command: 0x41, destination: 0, status: 0x41, function: 0x80 | 0x25, side: 0,
                                 payload: [1, 100, 100, 0x15, 0x14, 0, 0])
        let system = try MCRSystemStatus.parse(frame)
        XCTAssertEqual(system.configuration, .splitHead)
        XCTAssertTrue(system.hasUnderBedLight)
        XCTAssertFalse(system.hasFootWarming)
        XCTAssertTrue(system.hasFootControl)
        XCTAssertEqual(system.model, .ff2)
        XCTAssertEqual(system.presets, [.favorite, .flat, .zeroG, .snore])
        XCTAssertTrue(system.offersPartnerSnore)

        let ff3 = try MCRSystemStatus.parse(try MCRFrame(command: 0x41, destination: 0, status: 0x41, function: 0xA5, side: 0,
                                                         payload: [0, 0, 0, 0x1C, 0, 0, 0x0C]))
        XCTAssertEqual(ff3.model, .ff3)
        XCTAssertTrue(ff3.presets.contains(.read))
        XCTAssertFalse(ff3.offersPartnerSnore)
        XCTAssertTrue(ff3.leftBoardUnderperforming)
        XCTAssertTrue(ff3.rightBoardUnderperforming)
    }

    func testLightAndPinchParsing() throws {
        let on = try MCRFrame(command: 0x41, destination: 0, status: 0x41, function: 0x80 | 0x14, side: 3, payload: [1, 0x78, 0])
        let light = try MCRLightStatus.parse(on)
        XCTAssertTrue(light.on)
        XCTAssertEqual(light.timerMinutes, 120)
        let off = try MCRFrame(command: 0x41, destination: 0, status: 0x41, function: 0x80 | 0x14, side: 3, payload: [0, 0, 0])
        XCTAssertFalse(try MCRLightStatus.parse(off).on)

        let pinch = try MCRPinchState.parse(try MCRFrame(command: 0x41, destination: 0, status: 0x41, function: 0x80 | 0x28,
                                                         side: 0, payload: [0b1000_0100, 0, 0, 0, 0]))
        XCTAssertTrue(pinch.disconnected(.head, side: .zero))
        XCTAssertFalse(pinch.disconnected(.head, side: .one))
        XCTAssertTrue(pinch.continuousPinch(.head, side: .one))
        XCTAssertFalse(pinch.continuousPinch(.foot, side: .zero))
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
