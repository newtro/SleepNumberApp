import BedKit
import Foundation
import Observation

/// In-memory ring buffer of protocol frames and connection events for the Diagnostics screen.
@MainActor
@Observable
final class DiagnosticsLog {
    struct Entry: Identifiable, Equatable {
        let id: Int
        let date: Date
        let event: FrameEvent

        var line: String {
            let hex = event.bytes.map { " | \($0.hexString)" } ?? ""
            return "\(DiagnosticsLog.timeFormatter.string(from: date)) \(event.direction.rawValue) [\(event.channel)] \(event.text)\(hex)"
        }
    }

    private(set) var entries: [Entry] = []
    var maxEntries = 4000
    private var nextID = 0

    nonisolated static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()

    func append(_ event: FrameEvent) {
        nextID += 1
        entries.append(Entry(id: nextID, date: Date(), event: event))
        if entries.count > maxEntries {
            entries.removeFirst(entries.count - maxEntries)
        }
    }

    func info(_ channel: String, _ text: String) { append(FrameEvent(.info, channel: channel, text: text)) }
    func error(_ channel: String, _ text: String) { append(FrameEvent(.error, channel: channel, text: text)) }

    func clear() { entries.removeAll() }

    func export(header: String) -> String {
        ([header, ""] + entries.map(\.line)).joined(separator: "\n")
    }
}
