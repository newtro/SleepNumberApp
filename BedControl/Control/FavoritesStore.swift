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

    static func load(bedID: String) -> FavoritesDocument {
        guard let url = try? url(for: bedID), let data = try? Data(contentsOf: url),
              let document = try? FavoritesDocument.decode(data) else {
            return FavoritesDocument(bedID: bedID)
        }
        return document
    }

    static func save(_ document: FavoritesDocument) throws {
        try document.encoded().write(to: try url(for: document.bedID), options: [.atomic])
    }
}
