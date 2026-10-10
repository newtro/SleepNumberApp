import Foundation

/// Which of Sleep Number's two BLE protocols a bed speaks.
public enum BedProtocol: String, Codable, Sendable, CaseIterable {
    /// Older BAM / MCR binary UART protocol (i8, 360 FlexFit 2 with BAM module).
    case mcr
    /// Newer Fuzion "BamKey" text protocol (Climate 360, newer FlexFit).
    case fuzion

    public var displayName: String {
        switch self {
        case .mcr: return "MCR (BAM)"
        case .fuzion: return "Fuzion"
        }
    }

    public static let mcrServiceUUID = "FFFFD1FD-388D-938B-344A-939D1F6EFEE0"
    public static let mcrNotifyUUID = "FFFFD1FD-388D-938B-344A-939D1F6EFEE1"
    public static let mcrWriteUUID = "FFFFD1FD-388D-938B-344A-939D1F6EFEE2"

    public static let fuzionServiceUUID = "09D23FAE-90E6-44C2-95B6-0B3D0F1ABF25"
    public static let fuzionAuthUUID = "8D4675A5-B5FA-42B2-B587-0EE71C46B709"
    public static let fuzionBamKeyUUID = "421E00F3-AE76-4C49-AB6E-39E4DF4A5333"
    public static let fuzionBulkUUID = "0EC9A5A3-8AC3-4582-92F3-1666421F323D"

    /// Protocol for a discovered primary service UUID, if it is one of ours.
    public static func forService(_ uuid: String) -> BedProtocol? {
        switch uuid.uppercased() {
        case mcrServiceUUID: return .mcr
        case fuzionServiceUUID: return .fuzion
        default: return nil
        }
    }

    /// Picks the protocol from the set of services a connected bed exposes. MCR wins if both
    /// were ever present, because its service is unique to the older BAM module.
    public static func detect(serviceUUIDs: [String]) -> BedProtocol? {
        let found = Set(serviceUUIDs.compactMap(forService))
        if found.contains(.mcr) { return .mcr }
        if found.contains(.fuzion) { return .fuzion }
        return nil
    }
}

/// A side of the bed as the app shows it: left/right as you lie in bed on your back.
public enum BedSide: String, Codable, Sendable, CaseIterable, Identifiable {
    case left, right
    public var id: String { rawValue }
    public var title: String { self == .left ? "Left" : "Right" }
    public var other: BedSide { self == .left ? .right : .left }
}

/// Maps the app's sides to each protocol's wire sides.
///
/// MCR: right = 0, left = 1 (`Bed.Side.toDeviceValue()` in the official app). `swapped` flips
/// it, so a bed or a sleeper whose idea of "left" differs can be fixed from Settings.
public struct SideMapping: Equatable, Sendable, Codable {
    public var swapped: Bool

    public init(swapped: Bool = false) { self.swapped = swapped }

    public func mcrSide(for side: BedSide) -> MCRWireSide {
        switch (side, swapped) {
        case (.right, false), (.left, true): return .zero
        case (.left, false), (.right, true): return .one
        }
    }

    public func side(forMCR wire: MCRWireSide) -> BedSide {
        mcrSide(for: .left) == wire ? .left : .right
    }

    public func fuzionSide(for side: BedSide) -> FuzionSide {
        switch (side, swapped) {
        case (.left, false), (.right, true): return .left
        case (.right, false), (.left, true): return .right
        }
    }
}

/// Foundation presets. Raw values are the MCR preset IDs.
public enum BedPreset: Int, CaseIterable, Identifiable, Sendable, Codable {
    case favorite = 1
    case read = 2
    case watchTV = 3
    case flat = 4
    case zeroG = 5
    case snore = 6

    public var id: Int { rawValue }

    public var title: String {
        switch self {
        case .favorite: return "Favorite"
        case .read: return "Read"
        case .watchTV: return "TV"
        case .flat: return "Flat"
        case .zeroG: return "Zero G"
        case .snore: return "Snore"
        }
    }

    public var symbol: String {
        switch self {
        case .favorite: return "star.fill"
        case .read: return "book.fill"
        case .watchTV: return "tv.fill"
        case .flat: return "bed.double.fill"
        case .zeroG: return "figure.mind.and.body"
        case .snore: return "zzz"
        }
    }

    /// Fuzion ArticulationPreset token.
    public var fuzionToken: String {
        switch self {
        case .favorite: return "favorite"
        case .read: return "read"
        case .watchTV: return "watch_tv"
        case .flat: return "flat"
        case .zeroG: return "zero_g"
        case .snore: return "snore"
        }
    }

    public init?(fuzionToken: String) {
        guard let match = BedPreset.allCases.first(where: { $0.fuzionToken == fuzionToken }) else { return nil }
        self = match
    }
}

/// Sleep Number firmness: 5...100 in steps of 5.
public enum SleepNumber {
    public static let range = 5...100
    public static let step = 5

    /// Clamps and snaps to the nearest valid setting.
    public static func normalize(_ value: Int) -> Int {
        let clamped = min(max(value, range.lowerBound), range.upperBound)
        return Int((Double(clamped) / Double(step)).rounded()) * step
    }

    public static func isValid(_ value: Int) -> Bool {
        range.contains(value) && value % step == 0
    }
}

public enum MoveDirection: Sendable {
    case up, down

    /// The official app's hold-to-move target: 100 for up, 0 for down.
    public var holdTarget: Int { self == .up ? 100 : 0 }
}
