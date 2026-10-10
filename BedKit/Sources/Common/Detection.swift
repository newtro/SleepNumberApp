import Foundation

/// Advertisement fields the scanner sees, independent of CoreBluetooth types.
public struct AdvertisementInfo: Sendable, Equatable {
    public var name: String?
    public var serviceUUIDs: [String]
    /// Raw manufacturer data as iOS reports it: 2-byte little-endian company ID, then payload.
    public var manufacturerData: Data?

    public init(name: String?, serviceUUIDs: [String] = [], manufacturerData: Data? = nil) {
        self.name = name
        self.serviceUUIDs = serviceUUIDs
        self.manufacturerData = manufacturerData
    }
}

/// Why a device was offered as a possible Sleep Number bed.
public enum CandidateReason: String, Sendable, Equatable, CaseIterable {
    case mcrService = "MCR service"
    case fuzionService = "Fuzion service"
    case smartBedName = "\"Smart bed\" name"
    case selectComfortMAC = "Select Comfort MAC name"
    case macName = "MAC-style name"
    case manufacturerData = "Sleep Number manufacturer data"
}

public struct CandidateClassification: Sendable, Equatable {
    public var reasons: [CandidateReason]
    /// Best guess before connecting. The real decision is made from discovered services.
    public var likelyProtocol: BedProtocol?
    public var isCandidate: Bool { !reasons.isEmpty }
    /// Higher is a stronger match; used to sort the scan list.
    public var score: Int {
        reasons.reduce(0) { total, reason in
            switch reason {
            case .mcrService, .fuzionService: return total + 100
            case .selectComfortMAC: return total + 60
            case .manufacturerData: return total + 40
            case .smartBedName: return total + 40
            case .macName: return total + 10
            }
        }
    }
}

public enum BedDetector {
    /// Company identifier the user reported for Sleep Number advertisements ("SN").
    public static let manufacturerID: UInt16 = 0x534E
    /// IEEE OUI registered to Select Comfort (Sleep Number); MCR beds advertise their MAC as name.
    public static let selectComfortOUIs: Set<String> = ["64:DB:A0"]

    public static func isMACLike(_ name: String) -> Bool {
        let parts = name.split(separator: ":", omittingEmptySubsequences: false)
        let dashParts = name.split(separator: "-", omittingEmptySubsequences: false)
        let groups = parts.count == 6 ? parts : (dashParts.count == 6 ? dashParts : [])
        guard groups.count == 6 else { return false }
        return groups.allSatisfy { $0.count == 2 && UInt8($0, radix: 16) != nil }
    }

    /// The MCR bed address MCR-era beds derive from the last two MAC octets ("…:32:d3" -> 0x32D3).
    public static func mcrAddress(fromMACName name: String) -> UInt16? {
        guard isMACLike(name) else { return nil }
        let groups = name.replacingOccurrences(of: "-", with: ":").split(separator: ":")
        guard let high = UInt8(groups[4], radix: 16), let low = UInt8(groups[5], radix: 16) else { return nil }
        return UInt16(high) << 8 | UInt16(low)
    }

    public static func classify(_ info: AdvertisementInfo) -> CandidateClassification {
        var reasons: [CandidateReason] = []
        var likely: BedProtocol?
        let services = Set(info.serviceUUIDs.map { $0.uppercased() })
        if services.contains(BedProtocol.mcrServiceUUID) {
            reasons.append(.mcrService)
            likely = .mcr
        }
        if services.contains(BedProtocol.fuzionServiceUUID) {
            reasons.append(.fuzionService)
            likely = likely ?? .fuzion
        }
        if let name = info.name?.trimmingCharacters(in: .whitespaces), !name.isEmpty {
            if name.lowercased().hasPrefix("smart bed") || name.lowercased().hasPrefix("smartbed") {
                reasons.append(.smartBedName)
            }
            if isMACLike(name) {
                let oui = name.replacingOccurrences(of: "-", with: ":").uppercased().prefix(8)
                if selectComfortOUIs.contains(String(oui)) {
                    reasons.append(.selectComfortMAC)
                    likely = likely ?? .mcr
                } else {
                    reasons.append(.macName)
                }
            }
        }
        if let data = info.manufacturerData, data.count >= 2 {
            let company = UInt16(data[data.startIndex]) | UInt16(data[data.startIndex + 1]) << 8
            if company == manufacturerID {
                reasons.append(.manufacturerData)
            }
        }
        return CandidateClassification(reasons: reasons, likelyProtocol: likely)
    }
}
