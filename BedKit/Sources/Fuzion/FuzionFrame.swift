import Foundation

/// Standard CRC-32 (IEEE 802.3, reflected, poly 0xEDB88320) — the same as zlib/binascii.crc32.
public enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { index -> UInt32 in
        var value = UInt32(index)
        for _ in 0..<8 {
            value = value & 1 != 0 ? (value >> 1) ^ 0xEDB8_8320 : value >> 1
        }
        return value
    }

    public static func checksum<S: Sequence>(_ bytes: S) -> UInt32 where S.Element == UInt8 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in bytes {
            crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}

/// Fuzion blob framing: `"fUzIoN" | uint32 LE total length | UTF-8 text | uint32 LE CRC32`.
/// The CRC covers everything before it (preamble, length and text).
public enum FuzionFrame {
    public static let preamble: [UInt8] = Array("fUzIoN".utf8)
    public static let headerLength = 10
    public static let minimumLength = 14

    public static func encode(_ text: String) -> Data {
        let body = Array(text.utf8)
        let total = UInt32(headerLength + body.count + 4)
        var bytes = preamble + littleEndian(total) + body
        bytes += littleEndian(CRC32.checksum(bytes))
        return Data(bytes)
    }

    /// Decodes one complete blob, validating preamble, length and CRC.
    public static func decode(_ data: Data) throws -> String {
        let bytes = [UInt8](data)
        guard bytes.count >= minimumLength else { throw BedError.malformedResponse("Fuzion blob too short (\(bytes.count) bytes)") }
        guard Array(bytes[0..<6]) == preamble else { throw BedError.malformedResponse("Fuzion blob missing fUzIoN preamble") }
        let total = Int(readLE(bytes, at: 6))
        guard total == bytes.count else {
            throw BedError.malformedResponse("Fuzion blob length \(bytes.count) != header length \(total)")
        }
        let expected = readLE(bytes, at: bytes.count - 4)
        guard CRC32.checksum(bytes[0..<(bytes.count - 4)]) == expected else {
            throw BedError.malformedResponse("Fuzion blob CRC mismatch")
        }
        return String(decoding: bytes[headerLength..<(bytes.count - 4)], as: UTF8.self)
    }

    static func littleEndian(_ value: UInt32) -> [UInt8] {
        [UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8(value >> 24)]
    }

    static func readLE(_ bytes: [UInt8], at offset: Int) -> UInt32 {
        UInt32(bytes[offset]) | UInt32(bytes[offset + 1]) << 8 | UInt32(bytes[offset + 2]) << 16 | UInt32(bytes[offset + 3]) << 24
    }
}

/// Accumulates BamKey read fragments until one complete blob is present.
public struct FuzionReassembler: Sendable {
    public enum Progress: Equatable, Sendable {
        case needMore
        case complete(String)
    }

    private var buffer: [UInt8] = []
    public static let maxLength = 64 * 1024

    public init() {}

    public mutating func reset() { buffer.removeAll() }

    public mutating func append(_ chunk: Data) throws -> Progress {
        guard !chunk.isEmpty else { throw BedError.malformedResponse("empty BamKey fragment") }
        buffer.append(contentsOf: chunk)
        guard buffer.count >= FuzionFrame.headerLength else { return .needMore }
        guard Array(buffer[0..<6]) == FuzionFrame.preamble else {
            buffer.removeAll()
            throw BedError.malformedResponse("BamKey response missing fUzIoN preamble")
        }
        let total = Int(FuzionFrame.readLE(buffer, at: 6))
        guard total >= FuzionFrame.minimumLength, total <= FuzionReassembler.maxLength, buffer.count <= total else {
            buffer.removeAll()
            throw BedError.malformedResponse("BamKey response has invalid length \(total)")
        }
        guard buffer.count == total else { return .needMore }
        defer { buffer.removeAll() }
        return .complete(try FuzionFrame.decode(Data(buffer)))
    }
}

/// Validation of the 16-byte session UUID read from the Auth characteristic.
public enum FuzionAuth {
    public static func validateSession(_ value: Data) throws -> Data {
        guard value.count == 16 else {
            throw BedError.authenticationRejected("Auth returned \(value.count) bytes instead of a 16-byte session")
        }
        if value.allSatisfy({ $0 == 0 }) { throw BedError.connectionLimit }
        if value.dropLast().allSatisfy({ $0 == 0 }) && value.last == 1 {
            throw BedError.authenticationRejected("Auth returned session UUID 1")
        }
        return value
    }
}
