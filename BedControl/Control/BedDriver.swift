import BedKit
import Foundation

/// A partial snapshot of bed state from one poll. Nil fields were not read this time.
struct BedReading {
    var firmness: [BedSide: Int] = [:]
    /// Pump-wide busy flag (the protocol does not say which side is inflating).
    var pumpBusy: Bool?
    var head: [BedSide: Int] = [:]
    var foot: [BedSide: Int] = [:]
    var lightOn: Bool?
    var moving: Bool?
    var activePreset: [BedSide: BedPreset] = [:]
}

/// A preset button. Partner Snore sends Snore to the other side, as the official app does.
enum PresetOption: Hashable, Identifiable {
    case preset(BedPreset)
    case partnerSnore

    var id: String {
        switch self {
        case .preset(let preset): return preset.title
        case .partnerSnore: return "Partner Snore"
        }
    }

    var title: String {
        switch self {
        case .preset(let preset): return preset.title
        case .partnerSnore: return "Partner"
        }
    }

    var symbol: String {
        switch self {
        case .preset(let preset): return preset.symbol
        case .partnerSnore: return "person.2.wave.2.fill"
        }
    }

    var accessibilityName: String {
        switch self {
        case .preset(let preset): return "\(preset.title) preset"
        case .partnerSnore: return "Partner Snore (raises the other side's head)"
        }
    }
}

/// What the controller needs from a protocol. One implementation per BLE protocol.
@MainActor
protocol BedDriver: AnyObject {
    var kind: BedProtocol { get }
    /// Preset buttons for this foundation, in the official app's order.
    var presetOptions: [PresetOption] { get }
    /// False only when the bed reports it has no under-bed light.
    var hasUnderBedLight: Bool { get }
    var diagnostics: [(String, String)] { get }

    /// Subscribe, handshake/authenticate and read configuration.
    func start() async throws
    /// Full state poll.
    func refresh(includeLight: Bool) async throws -> BedReading
    func activate(_ preset: BedPreset, side: BedSide) async throws
    /// Starts a firmness change. `interruptRunning` first idles an adjustment in progress.
    func setFirmness(_ value: Int, side: BedSide, interruptRunning: Bool) async throws
    func setLight(_ on: Bool) async throws
    /// The app's hold-to-move: target 100 (up) or 0 (down), then status polls every ~333 ms
    /// until the task is cancelled, motion stops, a fault appears, or the safety deadline
    /// passes. Does not stop the motor: the caller always follows with `releaseHold`.
    func hold(_ axis: FoundationAxis, side: BedSide, direction: MoveDirection,
              update: @escaping (BedReading) -> Void) async throws
    /// Stops held movement and returns the final reading.
    func releaseHold(_ axis: FoundationAxis, side: BedSide) async -> BedReading?
    /// Stops all motion and any pump adjustment immediately, abandoning queued commands.
    func stopAll() async throws
    func handleNotification(_ characteristic: BedCharacteristic, _ data: Data)
    func reset()
}

enum HoldLimits {
    /// Safety deadline for a single press-and-hold (full travel takes well under this).
    static let maxHold: Duration = .seconds(45)
    /// The official app's poll interval while a held move runs.
    static let pollInterval: Duration = .milliseconds(333)
}
