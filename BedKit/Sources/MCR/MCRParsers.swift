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

/// Result of the bind handshake (`blelib/connection/b.a()`).
public struct MCRBindResult: Equatable, Sendable {
    /// Bed address: reply header bytes 1-2. Goes in the destination field of later requests.
    public var bedAddress: UInt16
    /// Our assigned address: reply payload bytes 8-9. Goes in the source fields.
    public var clientAddress: UInt16

    /// `macFallback` (last two MAC octets) is used only if the reply's source field is zero.
    public static func parse(_ reply: MCRFrame, macFallback: UInt16? = nil) throws -> MCRBindResult {
        let client: UInt16 = reply.payload.count >= 10
            ? UInt16(reply.payload[8]) << 8 | UInt16(reply.payload[9]) : 0
        guard let bed = [reply.source, macFallback ?? 0].first(where: { $0 != 0 }) else {
            throw BedError.malformedResponse("bind reply carried no bed address")
        }
        return MCRBindResult(bedAddress: bed, clientAddress: client)
    }
}

/// Pump status (pump cmd 0x12): `[busy, SN right, SN left]`; reply sub-nibble = chamber type.
/// The busy flag is pump-wide; the app does not report which side is inflating.
public struct MCRPumpStatus: Equatable, Sendable {
    public var busy: Bool
    public var sleepNumberRight: Int
    public var sleepNumberLeft: Int
    public var chamberType: UInt8

    public func sleepNumber(_ side: MCRWireSide) -> Int { side == .zero ? sleepNumberRight : sleepNumberLeft }

    public static func parse(_ frame: MCRFrame) throws -> MCRPumpStatus {
        let p = frame.payload
        guard p.count >= 3 else { throw BedError.malformedResponse("pump status needs 3+ bytes, got \(p.count)") }
        return MCRPumpStatus(busy: p[0] != 0, sleepNumberRight: Int(p[1]), sleepNumberLeft: Int(p[2]),
                             chamberType: frame.side)
    }
}

/// One actuator's status byte (foundation status bytes 9-12).
public struct MCRActuatorStatus: Equatable, Sendable {
    public var raw: UInt8
    public var moving: Bool { raw & 0x01 != 0 }
    public var limitMissing: Bool { raw & 0x02 != 0 }
    public var limitUnexpected: Bool { raw & 0x04 != 0 }
    public var currentUnder: Bool { raw & 0x08 != 0 }
    public var currentOver: Bool { raw & 0x10 != 0 }
    public var movementNone: Bool { raw & 0x20 != 0 }
    public var movementUnexpected: Bool { raw & 0x40 != 0 }
    public var fault: String? {
        if limitMissing { return "limit switch missing" }
        if limitUnexpected { return "unexpected limit switch" }
        if currentUnder { return "motor current low" }
        if currentOver { return "motor current high" }
        if movementUnexpected { return "unexpected movement" }
        return nil
    }
}

/// Foundation status (foundation cmd 0x12, 15 bytes; `C2991o`).
///
/// 0 flags (bit0 moving, bit5 needs homing, bit6 configured); 1 right head; 2 left head;
/// 3 right foot; 4 left foot; 5-6 / 7-8 right / left preset timer LE; 9-12 actuator status
/// (right head, left head, right foot, left foot); 13 timer presets; 14 current presets
/// (low nibble right, high nibble left).
public struct MCRFoundationStatus: Equatable, Sendable {
    public var flags: UInt8
    public var headRight: Int
    public var headLeft: Int
    public var footRight: Int
    public var footLeft: Int
    public var actuators: [MCRActuatorStatus]  // RH, LH, RF, LF when present
    public var presetRight: BedPreset?
    public var presetLeft: BedPreset?

    public var moving: Bool { flags & 0x01 != 0 || actuators.contains(where: \.moving) }
    public var needsHoming: Bool { flags & 0x20 != 0 }
    public var configured: Bool { flags & 0x40 != 0 }

    public func head(_ side: MCRWireSide) -> Int { side == .zero ? headRight : headLeft }
    public func foot(_ side: MCRWireSide) -> Int { side == .zero ? footRight : footLeft }
    public func position(_ axis: FoundationAxis, side: MCRWireSide) -> Int {
        axis == .head ? head(side) : foot(side)
    }
    public func preset(_ side: MCRWireSide) -> BedPreset? { side == .zero ? presetRight : presetLeft }

    public func actuator(_ axis: FoundationAxis, side: MCRWireSide) -> MCRActuatorStatus? {
        let index = (axis == .head ? 0 : 2) + Int(side.rawValue)
        return index < actuators.count ? actuators[index] : nil
    }

    public static func parse(_ frame: MCRFrame) throws -> MCRFoundationStatus {
        let p = frame.payload
        guard p.count >= 5 else { throw BedError.malformedResponse("foundation status needs 5+ bytes, got \(p.count)") }
        for index in 1...4 where p[index] > 100 {
            throw BedError.malformedResponse("foundation position byte \(index) = \(p[index]) is out of range")
        }
        let actuators = p.count >= 13 ? p[9...12].map { MCRActuatorStatus(raw: $0) } : []
        return MCRFoundationStatus(flags: p[0],
                                   headRight: Int(p[1]), headLeft: Int(p[2]),
                                   footRight: Int(p[3]), footLeft: Int(p[4]),
                                   actuators: actuators,
                                   presetRight: p.count >= 15 ? BedPreset(rawValue: Int(p[14] & 0x0F)) : nil,
                                   presetLeft: p.count >= 15 ? BedPreset(rawValue: Int(p[14] >> 4)) : nil)
    }
}

/// Under-bed light outlet status (foundation cmd 0x14, outlet 3): `[on, timer LE]`.
public struct MCRLightStatus: Equatable, Sendable {
    public var on: Bool
    public var timerMinutes: Int

    public static func parse(_ frame: MCRFrame) throws -> MCRLightStatus {
        let p = frame.payload
        guard let first = p.first else { throw BedError.malformedResponse("light status was empty") }
        let timer = p.count >= 3 ? Int(p[1]) | Int(p[2]) << 8 : 0
        return MCRLightStatus(on: first != 0, timerMinutes: timer)
    }
}

/// Foundation system status (foundation cmd 0x25; `C2993q`, `device/model/a`).
public struct MCRSystemStatus: Equatable, Sendable, CustomStringConvertible {
    public enum Configuration: Int, Sendable { case single = 0, splitHead = 1, splitKing = 2, easternKing = 3 }
    public enum Model: String, Sendable { case ff1 = "FF1", legacyFF2 = "legacy FF2", legacyFF3 = "legacy FF3", ff2 = "360 FF2", ff3 = "360 FF3" }

    public var raw: [UInt8]

    public var configuration: Configuration? { raw.first.flatMap { Configuration(rawValue: Int($0)) } }
    private var flags: UInt8 { raw.count > 3 ? raw[3] : 0 }
    public var dualBoard: Bool { flags & 0x01 != 0 }
    public var hasMassageAndLighting: Bool { flags & 0x02 != 0 }
    public var hasFootControl: Bool { flags & 0x04 != 0 }
    public var hasFootWarming: Bool { flags & 0x08 != 0 }
    public var hasUnderBedLight: Bool { flags & 0x10 != 0 }
    public var leftBoardUnderperforming: Bool { raw.count > 6 && raw[6] & 0x04 != 0 }
    public var rightBoardUnderperforming: Bool { raw.count > 6 && raw[6] & 0x08 != 0 }
    public var isSplit: Bool { configuration == .splitHead || configuration == .splitKing }

    /// FlexFit classification (`device/model/a.b()`).
    public var model: Model {
        if hasFootControl && hasUnderBedLight { return hasFootWarming ? .ff3 : .ff2 }
        if hasFootControl { return hasMassageAndLighting ? .legacyFF3 : .legacyFF2 }
        return .ff1
    }

    /// Presets the official app offers for this foundation, in its order.
    public var presets: [BedPreset] {
        switch model {
        case .ff3, .legacyFF3: return [.favorite, .flat, .zeroG, .snore, .watchTV, .read]
        case .ff2, .legacyFF2: return [.favorite, .flat, .zeroG, .snore]
        case .ff1: return hasUnderBedLight ? [.favorite, .flat, .snore] : [.favorite, .flat]
        }
    }

    /// The app offers Partner Snore (Snore sent to the other side) on split bases.
    public var offersPartnerSnore: Bool { isSplit && presets.contains(.snore) }

    public static func parse(_ frame: MCRFrame) throws -> MCRSystemStatus {
        guard frame.payload.count >= 4 else { throw BedError.malformedResponse("system status needs 4+ bytes") }
        return MCRSystemStatus(raw: frame.payload)
    }

    public var description: String {
        let config = configuration.map { "\($0)" } ?? "unknown"
        var features: [String] = []
        if dualBoard { features.append("dual board") }
        if hasFootControl { features.append("foot") }
        if hasUnderBedLight { features.append("under-bed light") }
        if hasFootWarming { features.append("foot warming") }
        if hasMassageAndLighting { features.append("massage") }
        return "\(model.rawValue), \(config), \(features.joined(separator: ", ")), raw [\(raw.hexString)]"
    }
}

/// Obstruction sensors (foundation cmd 0x28, 5 bytes; `C2996u`).
public struct MCRPinchState: Equatable, Sendable {
    public var raw: [UInt8]

    public func disconnected(_ axis: FoundationAxis, side: MCRWireSide) -> Bool {
        let bit: UInt8 = axis == .head ? (side == .zero ? 2 : 3) : (side == .zero ? 0 : 1)
        return raw[0] & (1 << bit) != 0
    }

    public func continuousPinch(_ axis: FoundationAxis, side: MCRWireSide) -> Bool {
        let bit: UInt8 = axis == .head ? (side == .zero ? 6 : 7) : (side == .zero ? 4 : 5)
        return raw[0] & (1 << bit) != 0
    }

    public static func parse(_ frame: MCRFrame) throws -> MCRPinchState {
        guard frame.payload.count >= 5 else { throw BedError.malformedResponse("pinch state needs 5 bytes") }
        return MCRPinchState(raw: frame.payload)
    }
}
