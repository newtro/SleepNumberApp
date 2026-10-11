import Foundation

/// MCR node classes as used in requests (header bytes 0 and 5). Replies carry the node's own
/// class ID with the low nibble 1 (0x01, 0x41, 0x51, 0x71). Source: `device/model/Node`.
public enum MCRNode: UInt8, Sendable {
    case pump = 0x02
    case foundation = 0x42
    case sleepExpert = 0x52
    case proxy = 0x72
}

/// MCR commands (header byte 8). Source: docs/PROTOCOL-FROM-APK.md §2.4.
public enum MCRFunction {
    public static let bind: UInt8 = 0x00
    public static let forceIdle: UInt8 = 0x02
    public static let set: UInt8 = 0x11
    public static let read: UInt8 = 0x12
    public static let outletWrite: UInt8 = 0x13
    public static let outletRead: UInt8 = 0x14
    public static let preset: UInt8 = 0x15
    public static let seShortWrite: UInt8 = 0x1B
    public static let systemStatus: UInt8 = 0x25
    public static let pinchState: UInt8 = 0x28
}

public enum FoundationAxis: String, Sendable, CaseIterable {
    case head, foot
    var payloadOffset: Int { self == .head ? 0 : 2 }
    /// Sleep Expert key letter for absolute positioning: MFU* head, MFF* foot.
    var positionKeyLetter: String { self == .head ? "U" : "F" }
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
    /// Stop frames are never dropped by the session's stop generation check.
    public let isStop: Bool

    init(node: MCRNode, function: UInt8, side: UInt8, payload: [UInt8] = [], label: String, isStop: Bool = false) throws {
        guard side <= 0x0F, payload.count <= MCRFrame.maxPayload else {
            throw BedError.invalidArgument("MCR side/payload out of range")
        }
        self.node = node
        self.function = function
        self.side = side
        self.payload = payload
        self.label = label
        self.isStop = isStop
        try MCRSafety.validate(self)
    }

    /// Wire frame: our client address in source (bytes 1-2, 6-7), the bed's in destination (3-4).
    public func frame(client: UInt16, bed: UInt16) throws -> MCRFrame {
        try MCRSafety.validate(self)
        return try MCRFrame(command: node.rawValue, source: client, destination: bed,
                            status: node.rawValue, sourceEcho: client, function: function,
                            side: side, payload: payload)
    }

    public var description: String { "\(label) (node \(String(format: "%02X", node.rawValue)) cmd 0x\(String(format: "%02X", function)) sub \(side))" }
}

/// Safety rules checked on every MCR request, both when it is built and again just before
/// it is encoded for the wire.
public enum MCRSafety {
    /// Foundation SET (0x42 / cmd 0x11) payload: 12 bytes, every field 0xFF ("don't change")
    /// except the one axis being moved.
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

    /// A foundation SET must be exactly 12 bytes and move exactly one axis: `[target 0...100,
    /// 0x00 apply flag]` at offset 0 (head) or 2 (foot), with every other byte 0xFF. Anything
    /// else could be read by the bed as "drive this motor" or as massage/timer fields.
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
            throw BedError.unsafeCommand("foundation SET target must be 0...100 with apply flag 0")
        }
        for index in 4..<foundationTargetLength where payload[index] != 0xFF {
            throw BedError.unsafeCommand("foundation SET byte \(index) must be the 0xFF mask")
        }
    }
}

/// Vetted builders for every MCR request the app sends.
public enum MCRCommands {
    /// Session bind: pump node, cmd 0, zero addresses, 8-byte client ID (big-endian). The
    /// official app sends a random 64-bit ID generated once and persisted (`he.b.e`).
    public static func bind(clientID: [UInt8] = Array(repeating: 0, count: 8)) throws -> MCRRequest {
        guard clientID.count == 8 else { throw BedError.invalidArgument("bind client ID must be 8 bytes") }
        return try MCRRequest(node: .pump, function: MCRFunction.bind, side: 0, payload: clientID, label: "Bind")
    }

    /// Pump status: reply `[busy, SN right (side 0), SN left (side 1)]`; reply sub-nibble = chamber type.
    public static func readPumpStatus() throws -> MCRRequest {
        try MCRRequest(node: .pump, function: MCRFunction.read, side: 0, label: "Read pump")
    }

    /// Interrupts a firmness adjustment in progress (`ForceIdleCall`).
    public static func pumpForceIdle() throws -> MCRRequest {
        try MCRRequest(node: .pump, function: MCRFunction.forceIdle, side: 0, label: "Pump idle", isStop: true)
    }

    /// Starts a firmness adjustment on one side (`SetSleepNumberCall`).
    public static func setSleepNumber(side: MCRWireSide, value: Int) throws -> MCRRequest {
        guard SleepNumber.isValid(value) else {
            throw BedError.invalidArgument("Sleep Number \(value) must be 5...100 in steps of 5")
        }
        return try MCRRequest(node: .pump, function: MCRFunction.set, side: side.rawValue,
                              payload: [0x00, UInt8(value)], label: "Set firmness \(value)")
    }

    /// Foundation status. `keepAdjusting` selects the app's "keep adjusting" variant used while a
    /// held move runs (sub-nibble side+2: 2 right, 3 left); nil is a plain read (sub 0).
    public static func readFoundationStatus(keepAdjusting side: MCRWireSide? = nil) throws -> MCRRequest {
        let selector = side.map { $0.rawValue + 2 } ?? 0
        return try MCRRequest(node: .foundation, function: MCRFunction.read, side: selector,
                              label: side == nil ? "Read foundation" : "Keep adjusting \(selector)")
    }

    public static func activatePreset(_ preset: BedPreset, side: MCRWireSide) throws -> MCRRequest {
        try MCRRequest(node: .foundation, function: MCRFunction.preset, side: side.rawValue,
                       payload: [UInt8(preset.rawValue), 0x00], label: "Preset \(preset.title)")
    }

    /// Micro-adjust (`MicroAdjustFoundationCall`): the only way to build a foundation SET. The app
    /// uses target 100 for a held "up" and 0 for "down". Builds the full 0xFF-masked payload.
    public static func setFoundationTarget(side: MCRWireSide, axis: FoundationAxis, position: Int) throws -> MCRRequest {
        guard (0...100).contains(position) else {
            throw BedError.invalidArgument("\(axis.rawValue) position \(position) must be 0...100")
        }
        var payload = [UInt8](repeating: 0xFF, count: MCRSafety.foundationTargetLength)
        payload[axis.payloadOffset] = UInt8(position)
        payload[axis.payloadOffset + 1] = 0x00
        return try MCRRequest(node: .foundation, function: MCRFunction.set, side: side.rawValue,
                              payload: payload, label: "Micro-adjust \(axis.rawValue) -> \(position)")
    }

    /// Sleep Expert short write: key (4 ASCII) + value, node 0x52 cmd 0x1B.
    static func sleepExpertWrite(key: String, value: String, label: String, isStop: Bool = false) throws -> MCRRequest {
        let keyBytes = Array(key.utf8)
        let valueBytes = Array(value.utf8)
        guard keyBytes.count == 4, keyBytes.count + valueBytes.count <= MCRFrame.maxPayload else {
            throw BedError.invalidArgument("Sleep Expert key must be 4 bytes and fit 15 with its value")
        }
        return try MCRRequest(node: .sleepExpert, function: MCRFunction.seShortWrite, side: 0,
                              payload: keyBytes + valueBytes, label: label, isStop: isStop)
    }

    /// Side letter in Sleep Expert keys: R for side 0 (right), L for side 1 (left).
    static func keyLetter(_ side: MCRWireSide) -> String { side == .zero ? "R" : "L" }

    /// Stops foundation movement on one side (`StopFoundationMovementCall`): MFHR/MFHL = "110".
    public static func stopMotion(side: MCRWireSide) throws -> MCRRequest {
        let key = "MFH" + keyLetter(side)
        return try sleepExpertWrite(key: key, value: "110", label: "Stop \(key)", isStop: true)
    }

    /// Absolute position (`SetFoundationPositionCall`): MFU*/MFF* = "<pos>_0".
    public static func setPosition(side: MCRWireSide, axis: FoundationAxis, position: Int) throws -> MCRRequest {
        guard (0...100).contains(position) else {
            throw BedError.invalidArgument("\(axis.rawValue) position \(position) must be 0...100")
        }
        let key = "MF" + axis.positionKeyLetter + keyLetter(side)
        return try sleepExpertWrite(key: key, value: "\(position)_0", label: "Position \(key) \(position)")
    }

    public static let underBedLightOutlet: UInt8 = 3

    /// Outlet status: reply `[on, timer LE]`.
    public static func readUnderBedLight() throws -> MCRRequest {
        try MCRRequest(node: .foundation, function: MCRFunction.outletRead, side: underBedLightOutlet,
                       label: "Read light")
    }

    /// Outlet write `[mode, timer LE]`; timer 0 = no auto-off.
    public static func setUnderBedLight(on: Bool) throws -> MCRRequest {
        try MCRRequest(node: .foundation, function: MCRFunction.outletWrite, side: underBedLightOutlet,
                       payload: [on ? 1 : 0, 0, 0], label: on ? "Light on" : "Light off")
    }

    /// Foundation system status: configuration and feature flags.
    public static func readSystemStatus() throws -> MCRRequest {
        try MCRRequest(node: .foundation, function: MCRFunction.systemStatus, side: 0, label: "Read system")
    }

    /// Obstruction (pinch) sensor state, read before held moves on 360 bases.
    public static func readPinchState() throws -> MCRRequest {
        try MCRRequest(node: .foundation, function: MCRFunction.pinchState, side: 0, label: "Read pinch")
    }

    /// Node list: node class IDs present (e.g. 0x01 pump, 0x41 foundation, 0x51 Sleep Expert).
    public static func readNodeList() throws -> MCRRequest {
        try MCRRequest(node: .proxy, function: MCRFunction.read, side: 0, label: "Read nodes")
    }
}
