import Foundation

/// MCR wire side selector (upper nibble of header byte 9) for the two sides of a split bed.
public enum MCRWireSide: UInt8, Sendable, CaseIterable {
    case zero = 0
    case one = 1
}

/// The MCR weighted checksum: s += b; r += s; result r & 0xFFFF, over header + payload
/// (not the 16 16 sync bytes). Transmitted big-endian.
public enum MCRChecksum {
    public static func compute<S: Sequence>(_ bytes: S) -> UInt16 where S.Element == UInt8 {
        var s: UInt32 = 0
        var r: UInt32 = 0
        for byte in bytes {
            s = s &+ UInt32(byte)
            r = r &+ s
        }
        return UInt16(truncatingIfNeeded: r)
    }
}

/// One MCR frame (layout per the official app, `ne.r`):
/// `16 16 | node src_hi src_lo dst_hi dst_lo node src_hi src_lo cmd (sub<<4|len) | payload | ck_hi ck_lo`
/// Requests carry our client address in src (bytes 1-2 and 6-7) and the bed's in dst (3-4).
/// Replies swap them: the bed's address in 1-2 and 6-7, ours in 3-4.
public struct MCRFrame: Equatable, Sendable, CustomStringConvertible {
    public static let sync: [UInt8] = [0x16, 0x16]
    public static let headerLength = 10
    public static let overhead = 14  // sync + header + checksum
    public static let maxPayload = 15

    /// Header byte 0: node class (requests use the node with low nibble 2, e.g. 0x42).
    public let command: UInt8
    /// Header bytes 1-2.
    public let source: UInt16
    /// Header bytes 3-4.
    public let destination: UInt16
    /// Header byte 5: node class, repeated.
    public let status: UInt8
    /// Header bytes 6-7: source address again.
    public let sourceEcho: UInt16
    /// Header byte 8: command, with 0x80 set on replies.
    public let function: UInt8
    /// High nibble of header byte 9: side (right 0, left 1), outlet, or selector.
    public let side: UInt8
    public let payload: [UInt8]

    public var functionCode: UInt8 { function & 0x7F }
    public var isResponse: Bool { function & 0x80 != 0 }

    public init(command: UInt8, source: UInt16 = 0, destination: UInt16, status: UInt8, sourceEcho: UInt16 = 0,
                function: UInt8, side: UInt8, payload: [UInt8] = []) throws {
        guard payload.count <= MCRFrame.maxPayload else {
            throw BedError.invalidArgument("MCR payload is \(payload.count) bytes; the limit is 15")
        }
        guard side <= 0x0F else {
            throw BedError.invalidArgument("MCR side selector \(side) does not fit in a nibble")
        }
        self.command = command
        self.source = source
        self.destination = destination
        self.status = status
        self.sourceEcho = sourceEcho
        self.function = function
        self.side = side
        self.payload = payload
    }

    public var headerAndPayload: [UInt8] {
        [command,
         UInt8(source >> 8), UInt8(source & 0xFF),
         UInt8(destination >> 8), UInt8(destination & 0xFF),
         status,
         UInt8(sourceEcho >> 8), UInt8(sourceEcho & 0xFF),
         function,
         (side << 4) | UInt8(payload.count)] + payload
    }

    public var encoded: Data {
        let body = headerAndPayload
        let crc = MCRChecksum.compute(body)
        return Data(MCRFrame.sync + body + [UInt8(crc >> 8), UInt8(crc & 0xFF)])
    }

    /// Decodes exactly one complete frame, validating sync, length and checksum.
    public static func decode(_ data: Data) throws -> MCRFrame {
        let bytes = [UInt8](data)
        guard bytes.count >= overhead else { throw BedError.malformedResponse("MCR frame shorter than 14 bytes") }
        guard bytes[0] == 0x16, bytes[1] == 0x16 else { throw BedError.malformedResponse("MCR frame missing 16 16 sync") }
        let length = Int(bytes[11] & 0x0F)
        guard bytes.count == overhead + length else {
            throw BedError.malformedResponse("MCR frame length \(bytes.count) does not match payload length \(length)")
        }
        let body = Array(bytes[2..<(bytes.count - 2)])
        let expected = UInt16(bytes[bytes.count - 2]) << 8 | UInt16(bytes[bytes.count - 1])
        guard MCRChecksum.compute(body) == expected else { throw BedError.malformedResponse("MCR checksum mismatch") }
        return try MCRFrame(command: body[0],
                            source: UInt16(body[1]) << 8 | UInt16(body[2]),
                            destination: UInt16(body[3]) << 8 | UInt16(body[4]),
                            status: body[5],
                            sourceEcho: UInt16(body[6]) << 8 | UInt16(body[7]),
                            function: body[8],
                            side: body[9] >> 4,
                            payload: Array(body[10..<(10 + length)]))
    }

    public var description: String {
        let kind: String
        switch command & 0xF0 {
        case 0x00: kind = "pump"
        case 0x40: kind = "foundation"
        case 0x30: kind = "senseAndDo"
        case 0x50: kind = "sleepExpert"
        case 0x70: kind = "proxy"
        case 0x90: kind = "outlet"
        default: kind = String(format: "node%02X", command)
        }
        let ascii = payload.allSatisfy { $0 >= 0x20 && $0 < 0x7F } && !payload.isEmpty
            ? " \"\(String(decoding: payload, as: UTF8.self))\"" : ""
        return String(format: "%@ node=%02X cmd=0x%02X%@ sub=%d src=%04X dst=%04X",
                      kind, command, functionCode, isResponse ? "(R)" : "", side, source, destination)
            + " [\(payload.hexString)]" + ascii
    }
}

/// Reassembles MCR frames from a stream of BLE notifications. Notifications are at most 20
/// bytes at the default MTU, so one frame can span several; continuation chunks carry no sync.
public struct MCRStreamParser: Sendable {
    private var buffer: [UInt8] = []
    /// Bytes thrown away while resynchronizing (garbage or corrupt frames).
    public private(set) var discardedBytes = 0
    private static let maxBuffer = 512

    public init() {}

    public mutating func reset() {
        buffer.removeAll()
    }

    public mutating func feed(_ data: Data) -> [MCRFrame] {
        buffer.append(contentsOf: data)
        var frames: [MCRFrame] = []
        while true {
            guard let start = syncIndex() else {
                // Keep a trailing 0x16: it may be the first half of the next sync.
                let keep = buffer.last == 0x16 ? 1 : 0
                discardedBytes += buffer.count - keep
                buffer = keep == 1 ? [0x16] : []
                break
            }
            if start > 0 {
                discardedBytes += start
                buffer.removeFirst(start)
            }
            guard buffer.count >= 12 else { break }
            let total = MCRFrame.overhead + Int(buffer[11] & 0x0F)
            guard buffer.count >= total else {
                if buffer.count > MCRStreamParser.maxBuffer {
                    discardedBytes += 1
                    buffer.removeFirst()
                    continue
                }
                break
            }
            if let frame = try? MCRFrame.decode(Data(buffer[0..<total])) {
                frames.append(frame)
                buffer.removeFirst(total)
            } else {
                // Corrupt candidate: skip one byte and look for the next sync.
                discardedBytes += 1
                buffer.removeFirst()
            }
        }
        return frames
    }

    private func syncIndex() -> Int? {
        guard buffer.count >= 2 else { return nil }
        for index in 0..<(buffer.count - 1) where buffer[index] == 0x16 && buffer[index + 1] == 0x16 {
            return index
        }
        return nil
    }
}
