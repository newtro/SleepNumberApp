import XCTest
@testable import BedKit

/// The foundation SET (0x42 func 17) must always carry the full 12-byte masked payload: a short
/// or zero-filled payload can be read by the bed as "drive a motor to an extreme".
final class MCRSafetyTests: XCTestCase {
    func testEveryBuildableTargetHasFullMaskedPayload() throws {
        for side in MCRWireSide.allCases {
            for axis in FoundationAxis.allCases {
                for position in 0...100 {
                    let request = try MCRCommands.setFoundationTarget(side: side, axis: axis, position: position)
                    XCTAssertEqual(request.node, .foundation)
                    XCTAssertEqual(request.function, 17)
                    XCTAssertEqual(request.payload.count, 12)
                    let offset = axis == .head ? 0 : 2
                    for (index, byte) in request.payload.enumerated() {
                        switch index {
                        case offset: XCTAssertEqual(byte, UInt8(position))
                        case offset + 1: XCTAssertEqual(byte, 0)
                        default: XCTAssertEqual(byte, 0xFF, "byte \(index) must be masked")
                        }
                    }
                    let frame = try request.frame(client: 0xA1B2, bed: 0x32D3)
                    XCTAssertEqual(frame.encoded.count, 14 + 12)
                    XCTAssertEqual(frame.encoded[11], (side.rawValue << 4) | 12)
                    XCTAssertNoThrow(try MCRSafety.validateWireFrame(frame.encoded))
                }
            }
        }
    }

    func testOutOfRangePositionsAreRejected() {
        for position in [-1, 101, 255, 1000, Int.min, Int.max] {
            XCTAssertThrowsError(try MCRCommands.setFoundationTarget(side: .zero, axis: .head, position: position))
            XCTAssertThrowsError(try MCRCommands.setFoundationTarget(side: .one, axis: .foot, position: position))
        }
    }

    func testValidatorRejectsMalformedPayloads() {
        let bad: [[UInt8]] = [
            [],                                         // empty: motors to extremes
            [100, 0, 0, 0],                             // legacy 4-byte form
            Array(repeating: 0, count: 12),             // all zero: both axes to 0
            Array(repeating: 0xFF, count: 12),          // nothing selected
            [50, 0, 50, 0] + Array(repeating: 0xFF, count: 8),   // two axes at once
            [101, 0] + Array(repeating: 0xFF, count: 10),        // out of range
            [50, 1] + Array(repeating: 0xFF, count: 10),         // non-zero high byte
            [50, 0] + Array(repeating: 0xFF, count: 9) + [0x00], // unmasked trailing byte
            [50, 0] + Array(repeating: 0xFF, count: 11),         // 13 bytes
            [0xFF, 0x00] + Array(repeating: 0xFF, count: 10),    // half-masked head
        ]
        for payload in bad {
            XCTAssertThrowsError(try MCRSafety.validateFoundationTarget(payload), "\(payload.hexString)")
        }
    }

    func testInternalInitializerAlsoEnforcesTheRule() {
        // Even BedKit-internal code cannot build an unsafe request.
        XCTAssertThrowsError(try MCRRequest(node: .foundation, function: MCRFunction.set, side: 0, payload: [100, 0, 0, 0], label: "bad"))
        XCTAssertThrowsError(try MCRRequest(node: .foundation, function: MCRFunction.set, side: 0, payload: [], label: "bad"))
        XCTAssertThrowsError(try MCRRequest(node: .pump, function: MCRFunction.set, side: 0, payload: [0, 3], label: "bad"))
    }

    func testWireValidatorCatchesHandCraftedFrames() throws {
        // A raw frame built around the public frame type is still caught before the write.
        let raw = try MCRFrame(command: 0x42, destination: 0x32D3, status: 0x42, function: 17, side: 0, payload: [100, 0, 0, 0])
        XCTAssertThrowsError(try MCRSafety.validateWireFrame(raw.encoded))
        let empty = try MCRFrame(command: 0x42, destination: 0x32D3, status: 0x42, function: 17, side: 1)
        XCTAssertThrowsError(try MCRSafety.validateWireFrame(empty.encoded))
        // Replies (response bit set) and other functions pass.
        let ack = try MCRFrame(command: 0x41, destination: 0x32D3, status: 0x41, function: 0x80 | 17, side: 0)
        XCTAssertNoThrow(try MCRSafety.validateWireFrame(ack.encoded))
    }

    @MainActor
    func testSessionRefusesToWriteUnsafeFrames() async throws {
        // The session validates wire bytes before every write; the fake link must never see a bad SET.
        let link = FakeLink()
        let session = MCRSession(link: link, clientID: Array(repeating: 9, count: 8), log: { _ in })
        link.onNotify = { session.handleNotification($0) }
        link.responder = { data, _ in
            let frame = try! MCRFrame.decode(data)
            if frame.functionCode == 0 {
                return [Data(hex: "16 16 01 32 d3 00 00 01 32 d3 80 0a 01 02 03 04 05 06 07 08 a1 b2 29 44")!]
            }
            return [try! MCRFrame(command: frame.command - 1, source: 0x32D3, destination: 0xA1B2, status: frame.command - 1,
                                  sourceEcho: 0x32D3, function: 0x80 | frame.functionCode, side: frame.side).encoded]
        }
        try await session.performBind()
        try await session.setFoundationTarget(side: .one, axis: .head, position: 100)
        for write in link.writes {
            XCTAssertNoThrow(try MCRSafety.validateWireFrame(write.data))
        }
        XCTAssertEqual(link.writes.last?.data.hexString,
                       try MCRCommands.setFoundationTarget(side: .one, axis: .head, position: 100)
                           .frame(client: 0xA1B2, bed: 0x32D3).encoded.hexString)
    }
}
