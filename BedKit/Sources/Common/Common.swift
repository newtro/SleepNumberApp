import Foundation

// MARK: - Hex helpers

public extension Data {
    /// Space-separated lowercase hex, e.g. "16 16 02 00".
    var hexString: String { map { String(format: "%02x", $0) }.joined(separator: " ") }

    /// Parses "16 16 02", "161602" or "16:16:02". Returns nil on any invalid digit.
    init?(hex: String) {
        let digits = hex.filter { !$0.isWhitespace && $0 != ":" && $0 != "-" }
        guard digits.count % 2 == 0 else { return nil }
        var bytes: [UInt8] = []
        bytes.reserveCapacity(digits.count / 2)
        var index = digits.startIndex
        while index < digits.endIndex {
            let next = digits.index(index, offsetBy: 2)
            guard let byte = UInt8(digits[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }
}

public extension Array where Element == UInt8 {
    var hexString: String { Data(self).hexString }
}

// MARK: - Errors

/// Every failure the protocol layer can report, phrased so the UI can show it directly.
public enum BedError: Error, Equatable, LocalizedError {
    case notConnected
    case timeout(String)
    case malformedResponse(String)
    case invalidArgument(String)
    case unsafeCommand(String)
    /// Fuzion Auth returned the all-zero UUID: the bed already has its maximum number of BLE sessions.
    case connectionLimit
    /// Fuzion Auth returned UUID one, a short value, or the link lacks encryption.
    case authenticationRejected(String)
    /// The bed requires a bond (pairing) this phone does not have.
    case bondRequired
    /// iOS reports the bed deleted its copy of our pairing keys.
    case pairingRemoved
    case commandFailed(String)
    case unsupportedCommand(String)
    case transport(String)
    case cancelled

    public var errorDescription: String? {
        switch self {
        case .notConnected: return "Not connected to the bed."
        case .timeout(let what): return "The bed did not answer (\(what))."
        case .malformedResponse(let what): return "Unexpected reply from the bed: \(what)"
        case .invalidArgument(let what): return "Invalid value: \(what)"
        case .unsafeCommand(let what): return "Blocked an unsafe command: \(what)"
        case .connectionLimit:
            return "The bed has reached its Bluetooth connection limit. Close the Sleep Number app (and any other phone or hub connected to the bed), then retry."
        case .authenticationRejected(let why):
            return "The bed rejected this phone (\(why)). Press the pair button on the bed's pump or remote, close the Sleep Number app, then retry."
        case .bondRequired:
            return "The bed needs to be paired with this phone. Press the pair button on the bed's pump or remote, close the official Sleep Number app, then retry and accept the pairing prompt."
        case .pairingRemoved:
            return "The bed forgot this phone's pairing. Open iOS Settings > Bluetooth, tap (i) next to the bed, choose Forget This Device, then press the bed's pair button and retry."
        case .commandFailed(let what): return "The bed refused the command: \(what)"
        case .unsupportedCommand(let what): return "This bed does not support \(what)."
        case .transport(let what): return "Bluetooth error: \(what)"
        case .cancelled: return "Cancelled."
        }
    }
}

// MARK: - Frame logging

/// One entry for the diagnostics log. The protocol layer emits these; the app stores and shows them.
public struct FrameEvent: Sendable, Equatable {
    public enum Direction: String, Sendable { case tx = "TX", rx = "RX", info = "INFO", error = "ERR" }
    public var direction: Direction
    public var channel: String
    public var bytes: Data?
    public var text: String

    public init(_ direction: Direction, channel: String, bytes: Data? = nil, text: String) {
        self.direction = direction
        self.channel = channel
        self.bytes = bytes
        self.text = text
    }
}

public typealias FrameLogger = @MainActor (FrameEvent) -> Void

// MARK: - Transport abstraction

/// Characteristics the sessions use. The app's CoreBluetooth layer maps these to real CBCharacteristics.
public enum BedCharacteristic: String, Sendable, CaseIterable {
    case mcrNotify
    case mcrWrite
    case fuzionAuth
    case fuzionBamKey
    case fuzionBulk
}

/// The only thing the protocol sessions need from Bluetooth. Implemented by the app's BLE layer
/// (and by fakes in unit tests), so all protocol logic stays free of CoreBluetooth.
@MainActor
public protocol GATTLink: AnyObject {
    /// Writes one complete protocol frame. The link chunks it (MCR: 20 bytes; Fuzion: the
    /// characteristic's write length) and must never interleave chunks of two frames.
    func write(_ frame: Data, to characteristic: BedCharacteristic) async throws
    /// Performs a GATT read and returns the value.
    func read(_ characteristic: BedCharacteristic) async throws -> Data
}

// MARK: - Async mutex

/// FIFO mutex for MainActor code: serializes request/response exchanges on one characteristic.
@MainActor
final class AsyncMutex {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func lock() async {
        if !locked {
            locked = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func unlock() {
        if waiters.isEmpty {
            locked = false
        } else {
            waiters.removeFirst().resume()
        }
    }
}
