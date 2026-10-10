import Foundation

/// MCR subsystems ("nodes"). Header byte 0 (command) and byte 5 (status) both carry the node
/// on requests; replies come back with the low bit cleared (0x02 -> 0x01, 0x42 -> 0x41).
public enum MCRNode: UInt8, Sendable {
    case pump = 0x02
    case foundation = 0x42
    case sleepExpert = 0x52
    case deviceManagement = 0x72
}

public enum MCRFunction {
    public static let bind: UInt8 = 0
    public static let forceIdle: UInt8 = 2
    public static let set: UInt8 = 17
    public static let read: UInt8 = 18
    public static let outletWrite: UInt8 = 19
    public static let outletRead: UInt8 = 20
    public static let preset: UInt8 = 21
    public static let seShortWrite: UInt8 = 27
    public static let systemStatus: UInt8 = 37
}

public enum FoundationAxis: String, Sendable, CaseIterable {
    case head, foot
    var payloadOffset: Int { self == .head ? 0 : 2 }
}

/// An address-free MCR request. The session fills in addresses at send time.
///
/// The initializer is internal: code outside BedKit can only obtain requests from the vetted
/// builders in `MCRCommands`, so it cannot hand-craft a foundation SET with a bad payload.
public struct MCRRequest: Equatable, Sendable, CustomStringConvertible {
    public let node: MCRNode
    public let function: UInt8
    public let side: UInt8
    public let payload: [UInt8]
    public let label: String

    init(node: MCRNode, function: UInt8, side: UInt8, payload: [UInt8] = [], label: String) throws {
        guard side <= 0x0F, payload.count <= MCRFrame.maxPayload else {
            throw BedError.invalidArgument("MCR side/payload out of range")
        }
        self.node = node
        self.function = function
        self.side = side
        self.payload = payload
        self.label = label
        try MCRSafety.validate(self)
    }

    /// Wire frame. `subAddress` is the bed address from the bind reply; target/echo stay 0.
    public func frame(subAddress: UInt16, clientAddress: UInt16 = 0) throws -> MCRFrame {
        try MCRSafety.validate(self)
        return try MCRFrame(command: node.rawValue, target: clientAddress, subAddress: subAddress,
                            status: node.rawValue, echo: clientAddress, function: function,
                            side: side, payload: payload)
    }

    public var description: String { "\(label) (node \(String(format: "%02X", node.rawValue)) func \(function) side \(side))" }
}

/// Safety rules checked on every MCR request, both when it is built and again just before
/// it is encoded for the wire.
public enum MCRSafety {
    /// Foundation SET (0x42 / func 17) payload: 12 bytes, 2 bytes per axis, `ff ff` = leave alone.
    public static let foundationTargetLength = 12

    public static func validate(_ request: MCRRequest) throws {
        if request.node == .foundation && request.function == MCRFunction.set {
            try validateFoundationTarget(request.payload)
        }
        if request.node == .pump && request.function == MCRFunction.set {
            guard request.payload.count == 2, request.payload[0] == 0,
                  SleepNumber.isValid(Int(request.payload[1])) else {
                throw BedError.unsafeCommand("pump SET payload must be [0, 5...100 step 5]")
            }
        }
    }

    /// A foundation SET must be exactly 12 bytes, move exactly one axis to 0...100 with a zero
    /// high byte, and mask every other byte with 0xFF. Anything else could be read by the bed
    /// as "drive this motor to an extreme".
    public static func validateFoundationTarget(_ payload: [UInt8]) throws {
        guard payload.count == foundationTargetLength else {
            throw BedError.unsafeCommand("foundation SET payload must be 12 bytes, got \(payload.count)")
        }
        let activeAxes = FoundationAxis.allCases.filter { axis in
            let offset = axis.payloadOffset
            return !(payload[offset] == 0xFF && payload[offset + 1] == 0xFF)
        }
        guard activeAxes.count == 1, let axis = activeAxes.first else {
            throw BedError.unsafeCommand("foundation SET must move exactly one axis")
        }
        let offset = axis.payloadOffset
        guard payload[offset] <= 100, payload[offset + 1] == 0 else {
            throw BedError.unsafeCommand("foundation SET position must be 0...100")
        }
        for index in 4..<foundationTargetLength where payload[index] != 0xFF {
            throw BedError.unsafeCommand("foundation SET byte \(index) must be the 0xFF mask")
        }
    }
}

/// Vetted builders for every MCR request the app sends.
public enum MCRCommands {
    /// Session bind (handshake). Zero addresses and an 8-byte client token; the all-zero token
    /// is what the live-tested implementations send and the bed accepts.
    public static func bind(token: [UInt8] = Array(repeating: 0, count: 8)) throws -> MCRRequest {
        guard token.count == 8 else { throw BedError.invalidArgument("bind token must be 8 bytes") }
        return try MCRRequest(node: .pump, function: MCRFunction.bind, side: 0, payload: token, label: "Bind")
    }

    /// Pump status: [active, sleepNumber side0, sleepNumber side1, pumping side0, pumping side1].
    public static func readPumpStatus() throws -> MCRRequest {
        try MCRRequest(node: .pump, function: MCRFunction.read, side: 0x0F, label: "Read pump")
    }

    /// Interrupts any in-progress firmness adjustment.
    public static func pumpForceIdle() throws -> MCRRequest {
        try MCRRequest(node: .pump, function: MCRFunction.forceIdle, side: 0, label: "Pump idle")
    }

    /// Starts a firmness adjustment on one side. Only one side may adjust at a time.
    public static func setSleepNumber(side: MCRWireSide, value: Int) throws -> MCRRequest {
        guard SleepNumber.isValid(value) else {
            throw BedError.invalidArgument("Sleep Number \(value) must be 5...100 in steps of 5")
        }
        return try MCRRequest(node: .pump, function: MCRFunction.set, side: side.rawValue,
                              payload: [0x00, UInt8(value)], label: "Set firmness \(value)")
    }

    /// Foundation status. `keepAdjusting` selects the side whose held movement should continue
    /// (selector side+2, the app's "continued adjustment" read); nil is a plain read (selector 0).
    public static func readFoundationStatus(keepAdjusting side: MCRWireSide? = nil) throws -> MCRRequest {
        let selector = side.map { $0.rawValue + 2 } ?? 0
        return try MCRRequest(node: .foundation, function: MCRFunction.read, side: selector,
                              label: side == nil ? "Read foundation" : "Keep adjusting \(selector - 2)")
    }

    public static func activatePreset(_ preset: BedPreset, side: MCRWireSide) throws -> MCRRequest {
        try MCRRequest(node: .foundation, function: MCRFunction.preset, side: side.rawValue,
                       payload: [UInt8(preset.rawValue), 0x00], label: "Preset \(preset.title)")
    }

    /// The only way to build a foundation SET. Builds the full 12-byte masked payload.
    public static func setFoundationTarget(side: MCRWireSide, axis: FoundationAxis, position: Int) throws -> MCRRequest {
        guard (0...100).contains(position) else {
            throw BedError.invalidArgument("\(axis.rawValue) position \(position) must be 0...100")
        }
        var payload = [UInt8](repeating: 0xFF, count: MCRSafety.foundationTargetLength)
        payload[axis.payloadOffset] = UInt8(position)
        payload[axis.payloadOffset + 1] = 0x00
        return try MCRRequest(node: .foundation, function: MCRFunction.set, side: side.rawValue,
                              payload: payload, label: "Target \(axis.rawValue) \(position)")
    }

    /// Releases held movement on one side: Sleep Expert short write MFHR/MFHL "110" (app STOP).
    /// R/L follow the app's naming, where wire side 0 is "R".
    public static func stopMotion(side: MCRWireSide) throws -> MCRRequest {
        let key = side == .zero ? "MFHR" : "MFHL"
        return try MCRRequest(node: .sleepExpert, function: MCRFunction.seShortWrite, side: 0,
                              payload: Array((key + "110").utf8), label: "Stop \(key)")
    }

    /// Foundation ForceIdle (0x42 func 2), seen in BedRemote's traces of the official app.
    public static func foundationForceIdle() throws -> MCRRequest {
        try MCRRequest(node: .foundation, function: MCRFunction.forceIdle, side: 0, label: "Foundation idle")
    }

    public static let underBedLightOutlet: UInt8 = 3

    public static func readUnderBedLight() throws -> MCRRequest {
        try MCRRequest(node: .foundation, function: MCRFunction.outletRead, side: underBedLightOutlet,
                       label: "Read light")
    }

    public static func setUnderBedLight(on: Bool) throws -> MCRRequest {
        try MCRRequest(node: .foundation, function: MCRFunction.outletWrite, side: underBedLightOutlet,
                       payload: [on ? 1 : 0, 0, 0], label: on ? "Light on" : "Light off")
    }

    /// Static foundation configuration (side 0 only).
    public static func readSystemStatus() throws -> MCRRequest {
        try MCRRequest(node: .foundation, function: MCRFunction.systemStatus, side: 0, label: "Read system")
    }

    /// Node list: which MCR subsystems are present (reported as reply codes, e.g. 0x41 foundation).
    public static func readNodeList() throws -> MCRRequest {
        try MCRRequest(node: .deviceManagement, function: MCRFunction.read, side: 0, label: "Read nodes")
    }
}
