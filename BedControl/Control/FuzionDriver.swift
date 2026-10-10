import BedKit
import Foundation

/// Fuzion (BamKey) bed driver: framed text commands on the BamKey characteristic after a
/// bonded Auth read.
@MainActor
final class FuzionDriver: BedDriver {
    let kind = BedProtocol.fuzion
    let session: FuzionSession
    private let ble: BLEManager
    private let log: DiagnosticsLog
    private let mapping: () -> SideMapping
    private let planner = MotionPlanner(step: 10, retargetMargin: 3)
    private var config: FuzionSystemConfiguration?
    private var lightTimer = 0
    private var lastLightLevel: FuzionLightLevel = .high

    init(ble: BLEManager, log: DiagnosticsLog, mapping: @escaping () -> SideMapping) {
        self.ble = ble
        self.log = log
        self.mapping = mapping
        session = FuzionSession(link: ble, log: { log.append($0) })
    }

    var supportedPresets: [BedPreset] {
        let all: [BedPreset] = [.flat, .zeroG, .snore, .favorite, .read, .watchTV]
        guard let config else { return all }
        return all.filter(config.supports)
    }

    var diagnostics: [(String, String)] {
        var rows: [(String, String)] = [("Protocol", kind.displayName)]
        if let id = session.sessionID { rows.append(("Session", id.hexString)) }
        if let config {
            rows += FuzionSystemConfiguration.fieldNames.map { ($0, config.fields[$0] ?? "?") }
        }
        rows.append(("Left side", "Fuzion \(mapping().fuzionSide(for: .left).rawValue)"))
        return rows
    }

    /// The side whose foot actuator drives the shared foot (first one SYCG reports present).
    private var footSide: FuzionSide {
        guard let config else { return .left }
        if config.fields["left_foot_actuator"] != "no" { return .left }
        return .right
    }

    func start() async throws {
        ble.isFuzionHint = { [weak session] data in session?.isHint(data) ?? false }
        // Reading Auth is what makes iOS pair with the bed if it requires encryption.
        let id = try await session.authenticate()
        log.info("Fuzion", "session \(id.hexString)")
        try await ble.enableNotifications(.fuzionBamKey)
        if ble.has(.fuzionBulk) {
            do { try await ble.enableNotifications(.fuzionBulk) } catch { log.info("Fuzion", "bulk notify unavailable: \(error.localizedDescription)") }
        }
        do {
            config = try await session.systemConfiguration()
        } catch {
            log.info("Fuzion", "SYCG unavailable: \(error.localizedDescription)")
        }
    }

    func refresh(includeLight: Bool) async throws -> BedReading {
        var reading = BedReading()
        let map = mapping()
        for side in BedSide.allCases {
            let wire = map.fuzionSide(for: side)
            if config?.isSingleChamber != true || wire == .right {
                let controls = try await session.sleepNumberControls(wire)
                reading.firmness[side] = controls.user
                reading.pumping[side] = controls.adjusting
            }
            reading.head[side] = try await session.actuatorPosition(wire, actuator: .head)
        }
        reading.foot = try await session.actuatorPosition(footSide, actuator: .foot)
        if includeLight {
            do {
                let light = try await session.underBedLight()
                lightTimer = light.timer
                if light.level != .off { lastLightLevel = light.level }
                reading.lightOn = light.level != .off
            } catch {
                log.info("Fuzion", "light read failed: \(error.localizedDescription)")
            }
        }
        return reading
    }

    func activate(_ preset: BedPreset, side: BedSide) async throws {
        try await session.send(.setPreset(mapping().fuzionSide(for: side), preset: preset))
    }

    func setFirmness(_ value: Int, side: BedSide) async throws {
        try await session.send(try .startSleepNumberAdjustment(mapping().fuzionSide(for: side), value: value))
    }

    func setLight(_ on: Bool) async throws {
        try await session.send(try .setUnderBedLight(level: on ? lastLightLevel : .off, timerMinutes: lightTimer))
    }

    private func wireSide(_ axis: FoundationAxis, _ side: BedSide) -> FuzionSide {
        axis == .foot ? footSide : mapping().fuzionSide(for: side)
    }

    func hold(_ axis: FoundationAxis, side: BedSide, direction: MoveDirection,
              update: @escaping (BedReading) -> Void) async throws {
        let wire = wireSide(axis, side)
        let actuator = FuzionActuator(axis)
        var current = try await session.actuatorPosition(wire, actuator: actuator)
        guard var target = planner.nextTarget(from: current, direction: direction) else { return }
        try await session.send(try .setActuatorTarget(wire, actuator: actuator, position: target))

        let clock = ContinuousClock()
        let deadline = clock.now + HoldLimits.maxHold
        var lastChange = clock.now
        while !Task.isCancelled && clock.now < deadline {
            try await Task.sleep(for: .milliseconds(300))
            let measured = try await session.actuatorPosition(wire, actuator: actuator)
            var reading = BedReading()
            if axis == .foot { reading.foot = measured } else { reading.head[side] = measured }
            reading.moving = true
            update(reading)
            if measured != current {
                current = measured
                lastChange = clock.now
            } else if clock.now - lastChange > HoldLimits.stall {
                log.info("Fuzion", "hold stopped: \(axis.rawValue) did not move for \(HoldLimits.stall)")
                return
            }
            if planner.shouldRetarget(current: current, target: target, direction: direction) {
                guard let next = planner.nextTarget(from: current, direction: direction) else { return }
                if next != target {
                    target = next
                    try await session.send(try .setActuatorTarget(wire, actuator: actuator, position: target))
                }
            }
        }
    }

    func releaseHold(_ axis: FoundationAxis, side: BedSide) async -> BedReading? {
        // ACHA is the app's only halt; it is global, so it also stops the other side.
        do { try await session.sendUrgent(.haltAllActuators()) } catch { log.error("Fuzion", "halt failed: \(error.localizedDescription)") }
        try? await Task.sleep(for: .milliseconds(300))
        let wire = wireSide(axis, side)
        guard let position = try? await session.actuatorPosition(wire, actuator: FuzionActuator(axis)) else { return nil }
        var reading = BedReading()
        if axis == .foot { reading.foot = position } else { reading.head[side] = position }
        reading.moving = false
        return reading
    }

    func stopAll() async throws {
        var firstError: Error?
        do { try await session.sendUrgent(.haltAllActuators()) } catch { firstError = error }
        do { try await session.sendUrgent(.interruptSleepNumberAdjustment()) } catch { firstError = firstError ?? error }
        if let firstError { throw firstError }
    }

    func handleNotification(_ characteristic: BedCharacteristic, _ data: Data) {
        switch characteristic {
        case .fuzionBamKey: session.handleNotification(data)
        case .fuzionBulk: log.append(FrameEvent(.rx, channel: "Bulk", bytes: data, text: "bulk notify \(data.count)b"))
        default: break
        }
    }

    func reset() {
        session.reset()
        ble.isFuzionHint = nil
    }
}
