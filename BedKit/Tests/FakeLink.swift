import Foundation
@testable import BedKit

/// In-memory GATT link: records writes, answers them with scripted notifications and serves
/// scripted reads. Notifications are delivered asynchronously, like CoreBluetooth would.
@MainActor
final class FakeLink: GATTLink {
    struct Write: Equatable {
        let data: Data
        let characteristic: BedCharacteristic
    }

    private(set) var writes: [Write] = []
    /// Returns the notifications to emit in reply to a write.
    var responder: ((Data, BedCharacteristic) -> [Data])?
    var onNotify: ((Data) -> Void)?
    /// Values returned by successive reads, per characteristic.
    var readQueue: [BedCharacteristic: [Data]] = [:]
    var readError: BedError?
    private(set) var readCount = 0

    func write(_ frame: Data, to characteristic: BedCharacteristic) async throws {
        writes.append(Write(data: frame, characteristic: characteristic))
        let replies = responder?(frame, characteristic) ?? []
        guard !replies.isEmpty else { return }
        Task { @MainActor in
            await Task.yield()
            for reply in replies { self.onNotify?(reply) }
        }
    }

    func read(_ characteristic: BedCharacteristic) async throws -> Data {
        readCount += 1
        if let readError { throw readError }
        guard var queue = readQueue[characteristic], !queue.isEmpty else {
            throw BedError.transport("no scripted read for \(characteristic)")
        }
        let value = queue.removeFirst()
        readQueue[characteristic] = queue
        return value
    }

    /// Splits data into 20-byte notifications, the size an MTU-23 link delivers.
    nonisolated static func chunks(_ data: Data, size: Int = 20) -> [Data] {
        stride(from: 0, to: data.count, by: size).map { data.subdata(in: $0..<min($0 + size, data.count)) }
    }
}
