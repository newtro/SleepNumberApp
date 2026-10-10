import BedKit
import Foundation

/// A partial snapshot of bed state from one poll. Nil fields were not read this time.
struct BedReading {
    var firmness: [BedSide: Int] = [:]
    var pumping: [BedSide: Bool] = [:]
    var head: [BedSide: Int] = [:]
    var foot: Int?
    var lightOn: Bool?
    var moving: Bool?
    var activePreset: [BedSide: BedPreset] = [:]
}

/// What the controller needs from a protocol. One implementation per BLE protocol.
@MainActor
protocol BedDriver: AnyObject {
    var kind: BedProtocol { get }
    /// Presets this bed is known to support (all of them unless the bed says otherwise).
    var supportedPresets: [BedPreset] { get }
    var diagnostics: [(String, String)] { get }

    /// Subscribe, handshake/authenticate and read configuration.
    func start() async throws
    /// Full state poll.
    func refresh(includeLight: Bool) async throws -> BedReading
    func activate(_ preset: BedPreset, side: BedSide) async throws
    func setFirmness(_ value: Int, side: BedSide) async throws
    func setLight(_ on: Bool) async throws
    /// Moves one axis in bounded increments until the task is cancelled, the axis reaches its
    /// limit, it stalls, or the safety deadline passes. Does not stop the motor: the caller
    /// always follows with `releaseHold`.
    func hold(_ axis: FoundationAxis, side: BedSide, direction: MoveDirection,
              update: @escaping (BedReading) -> Void) async throws
    /// Stops held movement and returns the final reading.
    func releaseHold(_ axis: FoundationAxis, side: BedSide) async -> BedReading?
    /// Stops all motion (and any pump adjustment) immediately.
    func stopAll() async throws
    func handleNotification(_ characteristic: BedCharacteristic, _ data: Data)
    func reset()
}

enum HoldLimits {
    /// Safety deadline for a single press-and-hold.
    static let maxHold: Duration = .seconds(45)
    /// Abort if the measured position has not changed for this long while held.
    static let stall: Duration = .seconds(4)
}
