import BedKit
import Foundation

/// MCR (BAM) bed driver, following the official app (docs/PROTOCOL-FROM-APK.md §2, §4).
@MainActor
final class MCRDriver: BedDriver {
    let kind = BedProtocol.mcr
    let session: MCRSession
    private let ble: BLEManager
    private let log: DiagnosticsLog
    private let mapping: () -> SideMapping
    private(set) var system: MCRSystemStatus?
    private var nodes: [UInt8] = []

    init(ble: BLEManager, log: DiagnosticsLog, clientID: [UInt8], macFallback: UInt16?,
         mapping: @escaping () -> SideMapping) {
        self.ble = ble
        self.log = log
        self.mapping = mapping
        session = MCRSession(link: ble, clientID: clientID, macFallback: macFallback, log: { log.append($0) })
    }

    /// Presets the app offers for the reported foundation; a 360 FlexFit 2 split head
    /// (the owner's bed) if system status could not be read.
    var presetOptions: [PresetOption] {
        let presets = system?.presets ?? [.favorite, .flat, .zeroG, .snore]
        var options = presets.map(PresetOption.preset)
        if system?.offersPartnerSnore ?? true { options.append(.partnerSnore) }
        return options
    }

    var hasUnderBedLight: Bool { system?.hasUnderBedLight ?? true }

    var diagnostics: [(String, String)] {
        var rows: [(String, String)] = [("Protocol", kind.displayName)]
        if let bind = session.bind {
            rows.append(("Bed address", String(format: "0x%04X", bind.bedAddress)))
            rows.append(("Client address", String(format: "0x%04X", bind.clientAddress)))
        }
        if !nodes.isEmpty { rows.append(("Nodes", nodes.hexString)) }
        if let system { rows.append(("Foundation", system.description)) }
        let map = mapping()
        rows.append(("Sides", "Left = MCR \(map.mcrSide(for: .left).rawValue), Right = MCR \(map.mcrSide(for: .right).rawValue)"))
        return rows
    }

    func start() async throws {
        try await Task.sleep(for: .milliseconds(128))  // the app's SERVICE_DISCOVERY_DELAY
        try await ble.enableNotifications(.mcrNotify)
        try await session.performBind()
        // Configuration reads are informational; failures do not block control.
        do { nodes = try await session.readNodeList() } catch { log.info("MCR", "node list unavailable: \(error.localizedDescription)") }
        do { system = try await session.readSystemStatus() } catch { log.info("MCR", "system status unavailable: \(error.localizedDescription)") }
    }

    func refresh(includeLight: Bool) async throws -> BedReading {
        var reading = BedReading()
        let pump = try await session.readPumpStatus()
        let map = mapping()
        for side in BedSide.allCases { reading.firmness[side] = pump.sleepNumber(map.mcrSide(for: side)) }
        reading.pumpBusy = pump.busy
        do {
            apply(try await session.readFoundationStatus(), to: &reading)
        } catch BedError.malformedResponse(let why) {
            // Keep the pump result (the firmness queue depends on it) even if the foundation
            // reply is unusable, e.g. out-of-range positions while homing.
            log.error("MCR", "foundation status unusable: \(why)")
        }
        if includeLight && hasUnderBedLight {
            do { reading.lightOn = try await session.readUnderBedLight().on } catch { log.info("MCR", "light read failed: \(error.localizedDescription)") }
        }
        return reading
    }

    func activate(_ preset: BedPreset, side: BedSide) async throws {
        let wire = mapping().mcrSide(for: side)
        if preset != .flat, let status = try? await session.readFoundationStatus(), status.needsHoming {
            throw BedError.commandFailed("the foundation needs homing; press Flat first")
        }
        try await session.activatePreset(preset, side: wire)
    }

    func setFirmness(_ value: Int, side: BedSide, interruptRunning: Bool) async throws {
        try await session.setSleepNumber(value, side: mapping().mcrSide(for: side), interruptRunning: interruptRunning)
    }

    func setLight(_ on: Bool) async throws {
        try await session.setUnderBedLight(on: on)
    }

    func hold(_ axis: FoundationAxis, side: BedSide, direction: MoveDirection,
              update: @escaping (BedReading) -> Void) async throws {
        // Each sleeper's own side, for the foot too: the firmware maps it onto the shared motor.
        let wire = mapping().mcrSide(for: side)
        var status = try await session.readFoundationStatus()
        var reading = BedReading()
        apply(status, to: &reading)
        update(reading)
        try check(status, axis: axis, side: wire)
        if system?.hasUnderBedLight ?? true {  // 360 bases: the app checks obstruction sensors first
            let pinch = try await session.readPinchState()
            if pinch.disconnected(axis, side: wire) || pinch.continuousPinch(axis, side: wire) {
                throw BedError.commandFailed("the \(axis.rawValue) obstruction sensor reports a fault")
            }
        }
        let target = direction.holdTarget
        if (direction == .up && status.position(axis, side: wire) >= 100) || (direction == .down && status.position(axis, side: wire) <= 0) {
            return
        }
        try await session.setFoundationTarget(side: wire, axis: axis, position: target)

        let clock = ContinuousClock()
        let started = clock.now
        let deadline = started + HoldLimits.maxHold
        while clock.now < deadline {
            try await Task.sleep(for: HoldLimits.pollInterval)
            // "Keep adjusting" read (sub side+2): the app's poll while a micro-adjust runs.
            status = try await session.readFoundationStatus(keepAdjusting: wire)
            reading = BedReading()
            apply(status, to: &reading)
            update(reading)
            try check(status, axis: axis, side: wire)
            // Give the motor a moment to start before treating "not moving" as finished.
            if !status.moving && clock.now - started > .seconds(1) {
                log.info("MCR", "hold: foundation reports stopped")
                return
            }
        }
        log.info("MCR", "hold: safety deadline reached")
    }

    private func check(_ status: MCRFoundationStatus, axis: FoundationAxis, side: MCRWireSide) throws {
        if status.needsHoming {
            throw BedError.commandFailed("the foundation needs homing; press Flat first")
        }
        if let fault = status.actuator(axis, side: side)?.fault {
            throw BedError.commandFailed("\(axis.rawValue) actuator: \(fault)")
        }
    }

    func releaseHold(_ axis: FoundationAxis, side: BedSide) async -> BedReading? {
        await stopConfirmed(sides: [mapping().mcrSide(for: side)])
    }

    func stopAll() async throws {
        var firstError: Error?
        do { try await session.stopPump() } catch { firstError = error }
        _ = await stopConfirmed(sides: MCRWireSide.allCases)
        if let firstError { throw firstError }
    }

    /// Sends the stop key, then checks the foundation; re-sends while it still reports motion.
    @discardableResult
    private func stopConfirmed(sides: [MCRWireSide]) async -> BedReading? {
        var last: MCRFoundationStatus?
        for attempt in 1...3 {
            do {
                try await session.stopFoundation(sides: sides)
            } catch {
                log.error("MCR", "stop attempt \(attempt) failed: \(error.localizedDescription)")
            }
            try? await Task.sleep(for: .milliseconds(300))
            last = try? await session.send(try MCRCommands.readFoundationStatus(), generation: session.stopGeneration)
                .flatMapStatus()
            if let last, !last.moving { break }
            log.error("MCR", "foundation still moving after stop attempt \(attempt)")
        }
        guard let last else { return nil }
        var reading = BedReading()
        apply(last, to: &reading)
        return reading
    }

    func favoriteOps(for side: BedSide) -> FavoriteBedOps {
        MCRFavoriteOps(session: session, side: mapping().mcrSide(for: side))
    }

    func handleNotification(_ characteristic: BedCharacteristic, _ data: Data) {
        guard characteristic == .mcrNotify else { return }
        session.handleNotification(data)
    }

    func reset() {
        session.reset()
    }

    private func apply(_ status: MCRFoundationStatus, to reading: inout BedReading) {
        let map = mapping()
        for side in BedSide.allCases {
            let wire = map.mcrSide(for: side)
            reading.head[side] = status.head(wire)
            reading.foot[side] = status.foot(wire)
            if let preset = status.preset(wire) { reading.activePreset[side] = preset }
        }
        reading.moving = status.moving
    }
}

private extension MCRFrame {
    func flatMapStatus() throws -> MCRFoundationStatus { try MCRFoundationStatus.parse(self) }
}
