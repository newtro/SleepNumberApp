import XCTest
@testable import BedKit

/// Blob vectors were generated with ha-adjustable-bed's `_build_bamkey_blob` (binascii.crc32).
final class FuzionFrameTests: XCTestCase {
    func testCRC32StandardVector() {
        XCTAssertEqual(CRC32.checksum(Array("123456789".utf8)), 0xCBF4_3926)
        XCTAssertEqual(CRC32.checksum([]), 0)
    }

    func testEncodeVectors() {
        XCTAssertEqual(FuzionCommand.haltAllActuators().frame.hexString,
                       "66 55 7a 49 6f 4e 13 00 00 00 41 43 48 41 20 42 8f 7b af")
        XCTAssertEqual(FuzionCommand.setPreset(.left, preset: .flat).frame.hexString,
                       "66 55 7a 49 6f 4e 1e 00 00 00 41 43 53 50 20 6c 65 66 74 20 66 6c 61 74 20 30 e9 32 f2 41")
    }

    func testCommandText() throws {
        XCTAssertEqual(FuzionCommand.haltAllActuators().text, "ACHA ")
        XCTAssertEqual(FuzionCommand.systemConfiguration().text, "SYCG ")
        XCTAssertEqual(FuzionCommand.setPreset(.right, preset: .zeroG).text, "ACSP right zero_g 0")
        XCTAssertEqual(FuzionCommand.setPreset(.left, preset: .watchTV).text, "ACSP left watch_tv 0")
        XCTAssertEqual(try FuzionCommand.setActuatorTarget(.right, actuator: .head, position: 40).text, "ACTS right head 40")
        XCTAssertEqual(try FuzionCommand.startSleepNumberAdjustment(.left, value: 45).text, "PSNS left 45")
        XCTAssertEqual(FuzionCommand.sleepNumberControls(.right).text, "SNCG right")
        XCTAssertEqual(FuzionCommand.actuatorPosition(.left, actuator: .foot).text, "ACTG left foot")
        XCTAssertEqual(try FuzionCommand.setUnderBedLight(level: .high, timerMinutes: 15).text, "UBLS high 15")
    }

    func testCommandValidation() {
        XCTAssertThrowsError(try FuzionCommand.startSleepNumberAdjustment(.left, value: 47))
        XCTAssertThrowsError(try FuzionCommand.startSleepNumberAdjustment(.left, value: 0))
        XCTAssertThrowsError(try FuzionCommand.setActuatorTarget(.left, actuator: .head, position: 101))
        XCTAssertThrowsError(try FuzionCommand.setActuatorTarget(.left, actuator: .head, position: -1))
        XCTAssertThrowsError(try FuzionCommand.setUnderBedLight(level: .low, timerMinutes: 181))
    }

    func testDecodeRoundTripAndRejection() throws {
        let blob = Data(hex: "66 55 7a 49 6f 4e 15 00 00 00 50 41 53 53 3a 34 35 75 da 4d 8b")!
        XCTAssertEqual(try FuzionFrame.decode(blob), "PASS:45")
        XCTAssertEqual(try FuzionFrame.decode(FuzionFrame.encode("SNCG left")), "SNCG left")

        var badCRC = [UInt8](blob)
        badCRC[badCRC.count - 1] ^= 1
        XCTAssertThrowsError(try FuzionFrame.decode(Data(badCRC)))
        XCTAssertThrowsError(try FuzionFrame.decode(blob.dropLast()))
        var badPreamble = [UInt8](blob)
        badPreamble[0] = 0x46
        XCTAssertThrowsError(try FuzionFrame.decode(Data(badPreamble)))
    }

    func testReassemblerAcrossFragments() throws {
        let blob = FuzionFrame.encode("PASS:0 30 45")
        var reassembler = FuzionReassembler()
        let parts = FakeLink.chunks(blob, size: 4)
        for part in parts.dropLast() {
            XCTAssertEqual(try reassembler.append(part), .needMore)
        }
        XCTAssertEqual(try reassembler.append(parts.last!), .complete("PASS:0 30 45"))
    }

    func testReassemblerRejectsGarbage() {
        var reassembler = FuzionReassembler()
        XCTAssertThrowsError(try reassembler.append(Data("garbage!!!xx".utf8)))
        XCTAssertThrowsError(try reassembler.append(Data()))
        var overlong = FuzionReassembler()
        let blob = FuzionFrame.encode("PASS:")
        XCTAssertThrowsError(try overlong.append(blob + Data([0x00])))
    }

    func testResponseParsing() throws {
        let command = FuzionCommand.sleepNumberControls(.left)
        XCTAssertEqual(try FuzionResponse.parse("PASS:0 30 45", for: command), ["0", "30", "45"])
        XCTAssertEqual(try FuzionResponse.parse("PASS:ACK", for: command), [])
        XCTAssertEqual(try FuzionResponse.parse("PASS:", for: command), [])
        XCTAssertThrowsError(try FuzionResponse.parse("FAIL:0", for: command)) {
            XCTAssertEqual($0 as? BedError, .unsupportedCommand("SNCG"))
        }
        XCTAssertThrowsError(try FuzionResponse.parse("FAIL:1", for: command))
        XCTAssertThrowsError(try FuzionResponse.parse("FAIL:2", for: command))
        XCTAssertThrowsError(try FuzionResponse.parse("WAT", for: command))

        let controls = try FuzionSleepNumberControls.parse(["1", "30", "45"], command: command)
        XCTAssertTrue(controls.adjusting)
        XCTAssertEqual(controls.user, 45)
        XCTAssertThrowsError(try FuzionSleepNumberControls.parse(["1", "30"], command: command))
    }

    func testSystemConfigurationParsing() throws {
        let tokens = ["dual", "yes", "yes", "yes", "no", "none", "yes", "yes", "yes", "yes",
                      "yes", "yes", "yes", "yes", "no", "no"]
        let config = try FuzionSystemConfiguration.parse(tokens)
        XCTAssertFalse(config.isSingleChamber)
        XCTAssertTrue(config.hasUnderBedLight)
        XCTAssertTrue(config.supports(.zeroG))
        XCTAssertFalse(config.supports(.watchTV))
        XCTAssertThrowsError(try FuzionSystemConfiguration.parse(Array(tokens.prefix(15))))
    }

    func testSessionValidation() throws {
        XCTAssertThrowsError(try FuzionAuth.validateSession(Data(count: 16))) {
            XCTAssertEqual($0 as? BedError, .connectionLimit)
        }
        var one = Data(count: 16)
        one[15] = 1
        XCTAssertThrowsError(try FuzionAuth.validateSession(one)) {
            guard case .authenticationRejected = $0 as? BedError else { return XCTFail("\($0)") }
        }
        XCTAssertThrowsError(try FuzionAuth.validateSession(Data([0, 0])))
        XCTAssertThrowsError(try FuzionAuth.validateSession(Data()))
        let good = Data((1...16).map { UInt8($0) })
        XCTAssertEqual(try FuzionAuth.validateSession(good), good)
    }
}

@MainActor
final class FuzionSessionTests: XCTestCase {
    let sessionID = Data((0..<16).map { UInt8(0xA0 + $0) })
    var link: FakeLink!
    var session: FuzionSession!

    override func setUp() async throws {
        link = FakeLink()
        session = FuzionSession(link: link, timing: .init(responseTimeout: .milliseconds(300), authTimeout: .milliseconds(300), authAttempts: 2),
                                log: { _ in })
        link.onNotify = { [weak self] in self?.session.handleNotification($0) }
    }

    func testAuthenticateStoresSession() async throws {
        link.readQueue[.fuzionAuth] = [sessionID]
        let auth = try await session.authenticate()
        XCTAssertEqual(auth, sessionID)
        XCTAssertTrue(session.isHint(sessionID))
        XCTAssertTrue(session.isHint(Data(count: 16)))
        XCTAssertFalse(session.isHint(Data(count: 15)))
    }

    func testConnectionLimitIsNotRetried() async {
        link.readQueue[.fuzionAuth] = [Data(count: 16), sessionID]
        do {
            _ = try await session.authenticate()
            XCTFail()
        } catch {
            XCTAssertEqual(error as? BedError, .connectionLimit)
        }
        XCTAssertEqual(link.readCount, 1)
    }

    func testBondErrorPropagates() async {
        link.readError = .bondRequired
        do {
            _ = try await session.authenticate()
            XCTFail()
        } catch {
            XCTAssertEqual(error as? BedError, .bondRequired)
        }
    }

    func testCommandRoundTripWithChunkedRead() async throws {
        link.readQueue[.fuzionAuth] = [sessionID]
        try await session.authenticate()
        let reply = FuzionFrame.encode("PASS:0 30 45")
        link.responder = { [sessionID] _, characteristic in
            characteristic == .fuzionBamKey ? [sessionID] : []
        }
        link.readQueue[.fuzionBamKey] = FakeLink.chunks(reply, size: 8)
        let controls = try await session.sleepNumberControls(.left)
        XCTAssertEqual(controls.user, 45)
        XCTAssertEqual(link.writes.last?.data, FuzionCommand.sleepNumberControls(.left).frame)
    }

    func testCommandTimesOutWithoutHint() async throws {
        link.readQueue[.fuzionAuth] = [sessionID]
        try await session.authenticate()
        do {
            try await session.send(.haltAllActuators())
            XCTFail()
        } catch {
            guard case .timeout = error as? BedError else { return XCTFail("\(error)") }
        }
    }

    func testForeignNotificationsDoNotCompleteCommands() async throws {
        link.readQueue[.fuzionAuth] = [sessionID]
        try await session.authenticate()
        link.responder = { _, _ in [Data(repeating: 0x55, count: 16)] }
        do {
            try await session.send(.haltAllActuators())
            XCTFail()
        } catch {
            guard case .timeout = error as? BedError else { return XCTFail("\(error)") }
        }
    }

    func testFailResponseSurfacesAsError() async throws {
        link.readQueue[.fuzionAuth] = [sessionID]
        try await session.authenticate()
        link.responder = { [sessionID] _, _ in [sessionID] }
        link.readQueue[.fuzionBamKey] = [FuzionFrame.encode("FAIL:0")]
        do {
            try await session.send(.actuatorMovementStatus())
            XCTFail()
        } catch {
            XCTAssertEqual(error as? BedError, .unsupportedCommand("ACTM"))
        }
    }
}
