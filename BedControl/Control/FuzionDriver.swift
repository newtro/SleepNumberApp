import BedKit
import Foundation

/// Fuzion (BamKey) bed driver, following the official app (docs/PROTOCOL-FROM-APK.md §3, §4).
@MainActor
final class FuzionDriver: BedDriver {
    let kind = BedProtocol.fuzion
    let session: FuzionSession
    private let ble: BLEManager
    private let log: DiagnosticsLog
    private let mapping: () -> SideMapping
    private var config: FuzionSystemConfiguration?
    private var lightTimer = 0
    private var lastLightLevel: FuzionLightLevel = .high

    init(ble: BLEManager, log: DiagnosticsLog, mapping: @escaping () -> SideMapping) {
        self.ble = ble
        self.log = log
        self.mapping = mapping
        session = FuzionSession(link: ble, log: { log.append($0) })
    }

    var presetOptions: [PresetOption] {
        let order: [BedPreset] = [.favorite, .flat, .zeroG, .snore, .watchTV, .read]
        let presets = config.map { config in order.filter(config.supports) } ?? order
        var options = presets.map(PresetOption.preset)
        if presets.contains(.snore) && (config?.isSplit ?? true) { options.append(.partnerSnore) }
        return options
    }

    var hasUnderBedLight: Bool { config?.hasUnderBedLight ?? true }

    var diagnostics: [(String, String)] {
        var rows: [(String, String)] = [("Protocol", kind.displayName)]
        if let id = session.sessionID { rows.append(("Session", id.hexString)) }
        if let config {
            rows += FuzionSystemConfiguration.fieldNames.map { ($0, config.fields[$0] ?? "?") }
        }
        rows.append(("Sides", "Left = Fuzion \(mapping().fuzionSide(for: .left).rawValue)"))
        return rows
    }

    func start() async throws {
        ble.isFuzionHint = { [weak session] data in session?.isHint(data) ?? false }
        // Reading Auth is what makes iOS pair with the bed if it requires encryption.
        let id = try await session.authenticate()
        log.info("Fuzion", "session \(id.hexString)")
        try await ble.enableNotifications(.fuzionBamKey)
        do {
            config = try await session.systemConfiguration()
        } catch {
            log.info("Fuzion", "SYCG unavailable: \(error.localizedDescription)")
        }
    }

    private func hasActuator(_ side: FuzionSide, _ actuator: FuzionActuator) -> Bool {
        config?.fields["\(side.rawValue)_\(actuator.rawValue)_actuator"] != "no"
    }

    func refresh(includeLight: Bool) async throws -> BedReading {
        var reading = BedReading()
        let map = mapping()
        var busy = false
        for side in BedSide.allCases {
            let wire = map.fuzionSide(for: side)
            if config?.isSingleChamber != true || wire == .right {
                let controls = try await session.sleepNumberControls(wire)
                reading.firmness[side] = controls.user
                busy = busy || controls.adjusting
            }
            if hasActuator(wire, .head) { reading.head[side] = try await session.actuatorPosition(wire, actuator: .head) }
            if hasActuator(wire, .foot) { reading.foot[side] = try await session.actuatorPosition(wire, actuator: .foot) }
        }
        reading.pumpBusy = busy
        do { reading.moving = try await session.movementStatus().any } catch { log.info("Fuzion", "ACTM failed: \(error.localizedDescription)") }
        if includeLight && hasUnderBedLight {
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

    func setFirmness(_ value: Int, side: BedSide, interruptRunning: Bool) async throws {
        let generation = session.stopGeneration
        if interruptRunning {
            try await session.send(.interruptSleepNumberAdjustment(), generation: generation)
            try await Task.sleep(for: .milliseconds(500))
        }
        try await session.send(try .startSleepNumberAdjustment(mapping().fuzionSide(for: side), value: value), generation: generation)
    }

    func setLight(_ on: Bool) async throws {
        try await session.send(try .setUnderBedLight(level: on ? lastLightLevel : .off, timerMinutes: lightTimer))
    }

    func hold(_ axis: FoundationAxis, side: BedSide, direction: MoveDirection,
              update: @escaping (BedReading) -> Void) async throws {
        // Each sleeper's own side, for the foot too (the app never redirects foot commands).
        let wire = mapping().fuzionSide(for: side)
        let actuator = FuzionActuator(axis)
        try await session.send(try .microAdjust(wire, actuator: actuator, target: direction.holdTarget))

        let clock = ContinuousClock()
        let started = clock.now
        let deadline = started + HoldLimits.maxHold
        while clock.now < deadline {
            try await Task.sleep(for: HoldLimits.pollInterval)
            let movement = try await session.movementStatus()
            let position = try await session.actuatorPosition(wire, actuator: actuator)
            var reading = BedReading()
            if axis == .foot { reading.foot[side] = position } else { reading.head[side] = position }
            reading.moving = movement.any
            update(reading)
            // Give the motor a moment to start before treating "not moving" as finished.
            if !movement.any && clock.now - started > .seconds(1) {
                log.info("Fuzion", "hold: actuators report stopped")
                return
            }
        }
        log.info("Fuzion", "hold: safety deadline reached")
    }

    func releaseHold(_ axis: FoundationAxis, side: BedSide) async -> BedReading? {
        // ACHA is the app's release; it halts every actuator, so it also stops the other side.
        await haltConfirmed()
        let wire = mapping().fuzionSide(for: side)
        guard let position = try? await session.actuatorPosition(wire, actuator: FuzionActuator(axis)) else { return nil }
        var reading = BedReading()
        if axis == .foot { reading.foot[side] = position } else { reading.head[side] = position }
        reading.moving = false
        return reading
    }

    func stopAll() async throws {
        var firstError: Error?
        do { try await session.sendUrgent(.interruptSleepNumberAdjustment()) } catch { firstError = error }
        await haltConfirmed()
        if let firstError { throw firstError }
    }

    private func haltConfirmed() async {
        await session.haltConfirmed()
    }

    func favoriteOps(for side: BedSide) -> FavoriteBedOps {
        FuzionFavoriteOps(session: session, side: mapping().fuzionSide(for: side))
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
