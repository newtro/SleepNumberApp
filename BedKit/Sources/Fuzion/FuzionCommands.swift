import Foundation

public enum FuzionSide: String, Sendable, CaseIterable {
    case left, right
}

public enum FuzionActuator: String, Sendable {
    case head, foot

    public init(_ axis: FoundationAxis) {
        self = axis == .head ? .head : .foot
    }
}

public enum FuzionLightLevel: String, Sendable, CaseIterable {
    case off, low, medium, high
}

/// A BamKey request: a 4-letter key plus space-separated arguments. The wire text is
/// `"KEY arg1 arg2"`, and `"KEY "` (trailing space) when there are no arguments.
public struct FuzionCommand: Equatable, Sendable, CustomStringConvertible {
    public let key: String
    public let arguments: [String]

    init(_ key: String, _ arguments: [String] = []) {
        self.key = key
        self.arguments = arguments
    }

    public var text: String { "\(key) \(arguments.joined(separator: " "))" }
    public var frame: Data { FuzionFrame.encode(text) }
    public var description: String { text }

    // MARK: Builders (only artifact-documented commands)

    /// SYCG: system configuration (chamber type, actuators present, presets enabled…).
    public static func systemConfiguration() -> FuzionCommand { FuzionCommand("SYCG") }

    /// SNCG side -> [adjusting 0/1, ambient sleep number, user sleep number].
    public static func sleepNumberControls(_ side: FuzionSide) -> FuzionCommand { FuzionCommand("SNCG", [side.rawValue]) }

    /// PSNS side value: start a firmness adjustment.
    public static func startSleepNumberAdjustment(_ side: FuzionSide, value: Int) throws -> FuzionCommand {
        guard SleepNumber.isValid(value) else {
            throw BedError.invalidArgument("Sleep Number \(value) must be 5...100 in steps of 5")
        }
        return FuzionCommand("PSNS", [side.rawValue, String(value)])
    }

    /// PSNI: interrupt a running firmness adjustment.
    public static func interruptSleepNumberAdjustment() -> FuzionCommand { FuzionCommand("PSNI") }

    /// ASTP side preset: recall a preset (`SetFuzionFlexFitPresetCall`, the app's preset button).
    public static func setPreset(_ side: FuzionSide, preset: BedPreset) -> FuzionCommand {
        FuzionCommand("ASTP", [side.rawValue, preset.fuzionToken])
    }

    /// ASTM side actuator target: micro-adjust used for hold-to-move (target 100 up / 0 down).
    public static func microAdjust(_ side: FuzionSide, actuator: FuzionActuator, target: Int) throws -> FuzionCommand {
        guard (0...100).contains(target) else {
            throw BedError.invalidArgument("\(actuator.rawValue) target \(target) must be 0...100")
        }
        return FuzionCommand("ASTM", [side.rawValue, actuator.rawValue, String(target)])
    }

    /// ACTS side actuator position: move one actuator to an absolute 0...100 target.
    public static func setActuatorTarget(_ side: FuzionSide, actuator: FuzionActuator, position: Int) throws -> FuzionCommand {
        guard (0...100).contains(position) else {
            throw BedError.invalidArgument("\(actuator.rawValue) position \(position) must be 0...100")
        }
        return FuzionCommand("ACTS", [side.rawValue, actuator.rawValue, String(position)])
    }

    /// ACTG side actuator -> [position].
    public static func actuatorPosition(_ side: FuzionSide, actuator: FuzionActuator) -> FuzionCommand {
        FuzionCommand("ACTG", [side.rawValue, actuator.rawValue])
    }

    /// ACTM -> [right_head, right_foot, left_head, left_foot] moving flags.
    public static func actuatorMovementStatus() -> FuzionCommand { FuzionCommand("ACTM") }

    /// ACHA: halt all actuators (global — stops both sides).
    public static func haltAllActuators() -> FuzionCommand { FuzionCommand("ACHA") }

    /// AGCP side -> [current preset].
    public static func currentPreset(_ side: FuzionSide) -> FuzionCommand { FuzionCommand("AGCP", [side.rawValue]) }

    /// UBLG -> [level, timer minutes].
    public static func underBedLightSettings() -> FuzionCommand { FuzionCommand("UBLG") }

    /// UBLS level timer.
    public static func setUnderBedLight(level: FuzionLightLevel, timerMinutes: Int) throws -> FuzionCommand {
        guard (0...180).contains(timerMinutes) else {
            throw BedError.invalidArgument("light timer \(timerMinutes) must be 0...180 minutes")
        }
        return FuzionCommand("UBLS", [level.rawValue, String(timerMinutes)])
    }
}

/// Parsed BamKey response text.
public enum FuzionResponse {
    /// `PASS:...` -> argument tokens. `FAIL:0` unknown key, `FAIL:1` device timeout, `FAIL:2` generic error.
    public static func parse(_ text: String, for command: FuzionCommand) throws -> [String] {
        let response = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let payload: String
        if response.hasPrefix("[") {
            payload = response
        } else if response.hasPrefix("PASS:ACK") {
            payload = String(response.dropFirst("PASS:ACK".count))
        } else if response.hasPrefix("PASS:") {
            payload = String(response.dropFirst("PASS:".count))
        } else if response.hasPrefix("FAIL:0") {
            throw BedError.unsupportedCommand(command.key)
        } else if response.hasPrefix("FAIL:1") {
            throw BedError.timeout("\(command.key) (bed reported a device timeout)")
        } else if response.hasPrefix("FAIL") {
            throw BedError.commandFailed("\(command.key): \(response)")
        } else {
            throw BedError.malformedResponse("\(command.key) returned \"\(response)\"")
        }
        return payload.split(separator: " ").map(String.init).filter { !$0.isEmpty }
    }

    public static func int(_ tokens: [String], at index: Int, for command: FuzionCommand) throws -> Int {
        guard index < tokens.count, let value = Int(tokens[index]) else {
            throw BedError.malformedResponse("\(command.key) field \(index) missing or not a number: \(tokens)")
        }
        return value
    }
}

/// ACTM reply: moving flags in the fixed order right head, right foot, left head, left foot.
public struct FuzionMovementStatus: Equatable, Sendable {
    public var rightHead: Bool
    public var rightFoot: Bool
    public var leftHead: Bool
    public var leftFoot: Bool
    public var any: Bool { rightHead || rightFoot || leftHead || leftFoot }

    public func moving(_ side: FuzionSide, _ actuator: FuzionActuator) -> Bool {
        switch (side, actuator) {
        case (.right, .head): return rightHead
        case (.right, .foot): return rightFoot
        case (.left, .head): return leftHead
        case (.left, .foot): return leftFoot
        }
    }

    public static func parse(_ tokens: [String]) throws -> FuzionMovementStatus {
        guard tokens.count == 4, tokens.allSatisfy({ $0 == "0" || $0 == "1" }) else {
            throw BedError.malformedResponse("ACTM returned \(tokens)")
        }
        return FuzionMovementStatus(rightHead: tokens[0] == "1", rightFoot: tokens[1] == "1",
                                    leftHead: tokens[2] == "1", leftFoot: tokens[3] == "1")
    }
}

/// SNCG reply.
public struct FuzionSleepNumberControls: Equatable, Sendable {
    public var adjusting: Bool
    public var ambient: Int
    public var user: Int

    public static func parse(_ tokens: [String], command: FuzionCommand) throws -> FuzionSleepNumberControls {
        guard tokens.count == 3 else { throw BedError.malformedResponse("SNCG returned \(tokens.count) fields: \(tokens)") }
        return FuzionSleepNumberControls(adjusting: tokens[0] == "1",
                                         ambient: try FuzionResponse.int(tokens, at: 1, for: command),
                                         user: try FuzionResponse.int(tokens, at: 2, for: command))
    }
}

/// SYCG reply: 16 fields in the app's constructor order.
public struct FuzionSystemConfiguration: Equatable, Sendable {
    public static let fieldNames = [
        "chamber_type", "pressure_control", "articulation", "underbed_light", "rapid_sleep_setting",
        "thermal_control", "right_head_actuator", "right_foot_actuator", "left_head_actuator",
        "left_foot_actuator", "flat_preset", "favorite_preset", "snore_preset", "zero_gravity_preset",
        "watch_tv_preset", "read_preset",
    ]
    public var fields: [String: String]

    public static func parse(_ tokens: [String]) throws -> FuzionSystemConfiguration {
        guard tokens.count == fieldNames.count else {
            throw BedError.malformedResponse("SYCG returned \(tokens.count) fields, expected 16: \(tokens)")
        }
        return FuzionSystemConfiguration(fields: Dictionary(uniqueKeysWithValues: zip(fieldNames, tokens)))
    }

    public var isSingleChamber: Bool { fields["chamber_type"] == "single" }
    public var hasUnderBedLight: Bool { fields["underbed_light"] != "no" }
    /// Split means both sides report at least one actuator.
    public var isSplit: Bool {
        (fields["left_head_actuator"] == "yes" || fields["left_foot_actuator"] == "yes")
            && (fields["right_head_actuator"] == "yes" || fields["right_foot_actuator"] == "yes")
    }

    public func supports(_ preset: BedPreset) -> Bool {
        let key: String
        switch preset {
        case .flat: key = "flat_preset"
        case .favorite: key = "favorite_preset"
        case .snore: key = "snore_preset"
        case .zeroG: key = "zero_gravity_preset"
        case .watchTV: key = "watch_tv_preset"
        case .read: key = "read_preset"
        }
        return fields[key] != "no"
    }
}
