import BedKit
import Foundation

/// MCR (BAM) bed driver: binary frames on ffffd1fd-…fee2, replies on …fee1.
@MainActor
final class MCRDriver: BedDriver {
    let kind = BedProtocol.mcr
    let session: MCRSession
    private let ble: BLEManager
    private let log: DiagnosticsLog
    private let mapping: () -> SideMapping
    private let planner = MotionPlanner(step: 10, retargetMargin: 3)
    private var system: MCRSystemStatus?
    private var nodes: [UInt8] = []

    /// FlexFit 2 has Flat/Zero G/Snore/Favorite; Read and TV are shown too because the i8 FF2
    /// report says they work (see docs/PROTOCOL-NOTES.md).
    var supportedPresets: [BedPreset] { [.flat, .zeroG, .snore, .favorite, .read, .watchTV] }

    init(ble: BLEManager, log: DiagnosticsLog, macFallback: UInt16?, mapping: @escaping () -> SideMapping) {
        self.ble = ble
        self.log = log
        self.mapping = mapping
        session = MCRSession(link: ble, macFallback: macFallback, log: { log.append($0) })
    }

    var diagnostics: [(String, String)] {
        var rows: [(String, String)] = [("Protocol", kind.displayName)]
        if let bind = session.bind {
            rows.append(("Bed address", String(format: "0x%04X", bind.bedAddress)))
            if let client = bind.clientAddress { rows.append(("Bind reply address", String(format: "0x%04X", client))) }
        }
        if !nodes.isEmpty { rows.append(("Nodes", nodes.hexString)) }
        if let system { rows.append(("Foundation system", system.description)) }
        let map = mapping()
        rows.append(("Left side", "MCR side \(map.mcrSide(for: .left).rawValue)"))
        return rows
    }

    func start() async throws {
        try await ble.enableNotifications(.mcrNotify)
        try await Task.sleep(for: .milliseconds(128))  // app's post-subscribe delay
        try await session.performBind()
        // Configuration reads are informational; failures do not block control.
        do { nodes = try await session.readNodeList() } catch { log.info("MCR", "node list unavailable: \(error.localizedDescription)") }
        do { system = try await session.readSystemStatus() } catch { log.info("MCR", "system status unavailable: \(error.localizedDescription)") }
    }

    func refresh(includeLight: Bool) async throws -> BedReading {
        var reading = BedReading()
        let pump = try await session.readPumpStatus()
        apply(pump, to: &reading)
        apply(try await session.readFoundationStatus(), to: &reading)
        if includeLight {
            do { reading.lightOn = try await session.readUnderBedLight().on } catch { log.info("MCR", "light read failed: \(error.localizedDescription)") }
        }
        return reading
    }

    func activate(_ preset: BedPreset, side: BedSide) async throws {
        try await session.activatePreset(preset, side: mapping().mcrSide(for: side))
    }

    func setFirmness(_ value: Int, side: BedSide) async throws {
        try await session.setSleepNumber(value, side: mapping().mcrSide(for: side))
    }

    func setLight(_ on: Bool) async throws {
        try await session.setUnderBedLight(on: on)
    }

    /// The shared foot is addressed through wire side 0 and read from foot byte 0.
    private func wireSide(_ axis: FoundationAxis, _ side: BedSide) -> MCRWireSide {
        axis == .foot ? .zero : mapping().mcrSide(for: side)
    }

    func hold(_ axis: FoundationAxis, side: BedSide, direction: MoveDirection,
              update: @escaping (BedReading) -> Void) async throws {
        let wire = wireSide(axis, side)
        var status = try await session.readFoundationStatus()
        var reading = BedReading()
        apply(status, to: &reading)
        update(reading)
        if status.needsHoming {
            throw BedError.commandFailed("the foundation needs homing; run the Flat preset first")
        }
        var current = status.position(axis, side: wire)
        guard var target = planner.nextTarget(from: current, direction: direction) else { return }
        try await session.setFoundationTarget(side: wire, axis: axis, position: target)

        let clock = ContinuousClock()
        let deadline = clock.now + HoldLimits.maxHold
        var lastChange = clock.now
        while !Task.isCancelled && clock.now < deadline {
            try await Task.sleep(for: .milliseconds(333))
            // The side+2 selector is the app's "continued adjustment" read during a hold.
            status = try await session.readFoundationStatus(keepAdjusting: wire)
            reading = BedReading()
            apply(status, to: &reading)
            update(reading)
            let measured = status.position(axis, side: wire)
            if measured != current {
                current = measured
                lastChange = clock.now
            } else if clock.now - lastChange > HoldLimits.stall {
                log.info("MCR", "hold stopped: \(axis.rawValue) did not move for \(HoldLimits.stall)")
                return
            }
            if planner.shouldRetarget(current: current, target: target, direction: direction) {
                guard let next = planner.nextTarget(from: current, direction: direction) else { return }
                if next != target {
                    target = next
                    try await session.setFoundationTarget(side: wire, axis: axis, position: target)
                }
            }
        }
    }

    func releaseHold(_ axis: FoundationAxis, side: BedSide) async -> BedReading? {
        do {
            try await session.stopFoundation(sides: [wireSide(axis, side)])
        } catch {
            log.error("MCR", "stop failed: \(error.localizedDescription)")
        }
        try? await Task.sleep(for: .milliseconds(400))
        guard let status = try? await session.readFoundationStatus() else { return nil }
        var reading = BedReading()
        apply(status, to: &reading)
        return reading
    }

    func stopAll() async throws {
        var firstError: Error?
        do { try await session.stopFoundation() } catch { firstError = error }
        do { try await session.stopPump() } catch { firstError = firstError ?? error }
        if let firstError { throw firstError }
    }

    func handleNotification(_ characteristic: BedCharacteristic, _ data: Data) {
        guard characteristic == .mcrNotify else { return }
        session.handleNotification(data)
    }

    func reset() {
        session.reset()
    }

    // MARK: Mapping

    private func apply(_ pump: MCRPumpStatus, to reading: inout BedReading) {
        let map = mapping()
        for side in BedSide.allCases {
            let wire = map.mcrSide(for: side)
            reading.firmness[side] = pump.sleepNumber(wire)
            reading.pumping[side] = pump.pumping(wire)
        }
    }

    private func apply(_ status: MCRFoundationStatus, to reading: inout BedReading) {
        let map = mapping()
        for side in BedSide.allCases {
            let wire = map.mcrSide(for: side)
            reading.head[side] = status.head(wire)
            if let preset = wire == .zero ? status.preset0 : status.preset1 {
                reading.activePreset[side] = preset
            }
        }
        reading.foot = status.foot0
        reading.moving = status.moving
    }
}
