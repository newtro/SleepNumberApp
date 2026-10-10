import Foundation

extension MCRSafety {
    /// Last-line check on raw bytes about to be written to the MCR characteristic: if the bytes
    /// form a foundation SET frame, its payload must pass `validateFoundationTarget`.
    public static func validateWireFrame(_ data: Data) throws {
        let frame = try MCRFrame.decode(data)
        if frame.command == MCRNode.foundation.rawValue && frame.functionCode == MCRFunction.set && !frame.isResponse {
            try validateFoundationTarget(frame.payload)
        }
    }
}

/// Result of the bind handshake.
public struct MCRBindResult: Equatable, Sendable {
    /// Address the bed wants in the `sub` field of every later request.
    public var bedAddress: UInt16
    /// Client address from reply payload bytes 8-9, when present (logged; target/echo stay 0).
    public var clientAddress: UInt16?

    /// Reads the reply. The bed address is the reply's `target` field (BedRemote, ha-adjustable-bed
    /// agree); if that is zero, falls back to payload bytes 8-9, then to the MAC-derived address.
    public static func parse(_ reply: MCRFrame, macFallback: UInt16? = nil) throws -> MCRBindResult {
        let payloadAddress: UInt16? = reply.payload.count >= 10
            ? UInt16(reply.payload[8]) << 8 | UInt16(reply.payload[9]) : nil
        let candidates = [reply.target, payloadAddress ?? 0, macFallback ?? 0]
        guard let address = candidates.first(where: { $0 != 0 }) else {
            throw BedError.malformedResponse("bind reply carried no bed address")
        }
        return MCRBindResult(bedAddress: address, clientAddress: payloadAddress)
    }
}

/// Pump status (pump func 18).
public struct MCRPumpStatus: Equatable, Sendable {
    public var active: Bool
    public var sleepNumber0: Int
    public var sleepNumber1: Int
    public var pumping0: Bool
    public var pumping1: Bool
    /// Reply side nibble: 1 means a dual-chamber pump (ha-adjustable-bed).
    public var dualChamber: Bool

    public var anyPumping: Bool { pumping0 || pumping1 }

    public func sleepNumber(_ side: MCRWireSide) -> Int { side == .zero ? sleepNumber0 : sleepNumber1 }
    public func pumping(_ side: MCRWireSide) -> Bool { side == .zero ? pumping0 : pumping1 }

    public static func parse(_ frame: MCRFrame) throws -> MCRPumpStatus {
        let p = frame.payload
        guard p.count >= 3 else { throw BedError.malformedResponse("pump status needs 3+ bytes, got \(p.count)") }
        return MCRPumpStatus(active: p[0] != 0,
                             sleepNumber0: Int(p[1]),
                             sleepNumber1: Int(p[2]),
                             pumping0: p.count > 3 && p[3] != 0,
                             pumping1: p.count > 4 && p[4] != 0,
                             dualChamber: frame.side == 1)
    }
}

/// Foundation status (foundation func 18, 15-byte payload).
///
/// Byte 0 flags (bit0 moving, bit5 needs homing, bit6 configured); bytes 1-4 head side0, head
/// side1, foot side0, foot side1 (a shared foot reports the same value twice); bytes 9-12
/// per-actuator flags; byte 14 low nibble = side 0 preset, high nibble = side 1 preset.
public struct MCRFoundationStatus: Equatable, Sendable {
    public var flags: UInt8
    public var head0: Int
    public var head1: Int
    public var foot0: Int
    public var foot1: Int
    public var actuatorFlags: [UInt8]
    public var preset0: BedPreset?
    public var preset1: BedPreset?

    public var moving: Bool { flags & 0x01 != 0 || actuatorFlags.contains { $0 & 0x01 != 0 } }
    public var needsHoming: Bool { flags & 0x20 != 0 }
    public var configured: Bool { flags & 0x40 != 0 }

    public func head(_ side: MCRWireSide) -> Int { side == .zero ? head0 : head1 }
    public func foot(_ side: MCRWireSide) -> Int { side == .zero ? foot0 : foot1 }
    public func position(_ axis: FoundationAxis, side: MCRWireSide) -> Int {
        axis == .head ? head(side) : foot(side)
    }

    public static func parse(_ frame: MCRFrame) throws -> MCRFoundationStatus {
        let p = frame.payload
        guard p.count >= 5 else { throw BedError.malformedResponse("foundation status needs 5+ bytes, got \(p.count)") }
        for index in 1...4 where p[index] > 100 {
            throw BedError.malformedResponse("foundation position byte \(index) = \(p[index]) is out of range")
        }
        let flags = p.count >= 13 ? Array(p[9...12]) : []
        return MCRFoundationStatus(flags: p[0],
                                   head0: Int(p[1]), head1: Int(p[2]),
                                   foot0: Int(p[3]), foot1: Int(p[4]),
                                   actuatorFlags: flags,
                                   preset0: p.count >= 15 ? BedPreset(rawValue: Int(p[14] & 0x0F)) : nil,
                                   preset1: p.count >= 15 ? BedPreset(rawValue: Int(p[14] >> 4)) : nil)
    }
}

/// Under-bed light outlet status (foundation func 20, outlet 3): [on, level/timer, ...].
public struct MCRLightStatus: Equatable, Sendable {
    public var on: Bool
    public static func parse(_ frame: MCRFrame) throws -> MCRLightStatus {
        guard let first = frame.payload.first else { throw BedError.malformedResponse("light status was empty") }
        return MCRLightStatus(on: first != 0)
    }
}

/// Foundation system status (func 37). Decoded for diagnostics only; the feature-bit meanings
/// differ between references (see docs/PROTOCOL-NOTES.md), so nothing in the UI is gated on it.
public struct MCRSystemStatus: Equatable, Sendable, CustomStringConvertible {
    public var raw: [UInt8]
    public var configuration: Int { raw.first.map(Int.init) ?? -1 }
    public var featureFlags: UInt8 { raw.count > 3 ? raw[3] : 0 }

    public static func parse(_ frame: MCRFrame) throws -> MCRSystemStatus {
        guard frame.payload.count >= 4 else { throw BedError.malformedResponse("system status needs 4+ bytes") }
        return MCRSystemStatus(raw: frame.payload)
    }

    public var description: String {
        let configName: String
        switch configuration {
        case 0: configName = "single"
        case 1: configName = "split head"
        case 2: configName = "split king"
        default: configName = "unknown"
        }
        return "configuration \(configuration) (\(configName)), feature flags 0x\(String(format: "%02X", featureFlags)), raw [\(raw.hexString)]"
    }
}
