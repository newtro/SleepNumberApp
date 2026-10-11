import BedKit
import Foundation

/// Persists one `FavoritesDocument` per bed as JSON in
/// Application Support/Favorites/<bed identifier>.json.
enum FavoritesStore {
    static func directory() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        let folder = base.appendingPathComponent("Favorites", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    static func url(for bedID: String) throws -> URL {
        let safe = bedID.filter { $0.isLetter || $0.isNumber || $0 == "-" }
        return try directory().appendingPathComponent("\(safe.isEmpty ? "bed" : safe).json")
    }

    /// Loads the bed's favorites. A missing file means none. A file that exists but cannot be
    /// read (corrupt, or written by a newer app version) is renamed to
    /// `<name>.unreadable-<timestamp>.json` so the next save can never overwrite it; the
    /// returned message says where it went.
    static func load(bedID: String) -> (document: FavoritesDocument, problem: String?) {
        let empty = FavoritesDocument(bedID: bedID)
        guard let url = try? url(for: bedID), FileManager.default.fileExists(atPath: url.path) else {
            return (empty, nil)
        }
        do {
            return (try FavoritesDocument.decode(try Data(contentsOf: url)), nil)
        } catch {
            let stamp = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-")
            let aside = url.deletingPathExtension().appendingPathExtension("unreadable-\(stamp).json")
            do {
                try FileManager.default.moveItem(at: url, to: aside)
                return (empty, "Saved favorites could not be read (\(error.localizedDescription)). The file was kept as \(aside.lastPathComponent).")
            } catch let moveError {
                return (empty, "Saved favorites could not be read or moved aside: \(moveError.localizedDescription)")
            }
        }
    }

    static func save(_ document: FavoritesDocument) throws {
        try document.encoded().write(to: try url(for: document.bedID), options: [.atomic])
    }
}
