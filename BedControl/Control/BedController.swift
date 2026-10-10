import BedKit
import CoreBluetooth
import Foundation
import Observation

/// App state and behaviour: connection policy, protocol drivers, polling, press-and-hold
/// movement, the one-side-at-a-time firmness queue, and STOP.
@MainActor
@Observable
final class BedController {
    enum Phase: Equatable {
        case bluetoothUnavailable(String)
        case noBed
        case searching(String)
        case connecting(String)
        case ready
        case failed(String)
    }

    struct HoldKey: Equatable {
        let axis: FoundationAxis
        let side: BedSide
        let direction: MoveDirection
    }

    struct SavedBed: Codable, Equatable {
        var id: UUID
        var name: String
        var lastProtocol: BedProtocol?
    }

    // Observable state
    private(set) var phase: Phase = .noBed
    private(set) var savedBed: SavedBed?
    private(set) var protocolKind: BedProtocol?
    private(set) var firmness: [BedSide: Int] = [:]
    private(set) var pumping: [BedSide: Bool] = [:]
    private(set) var queuedFirmness: [BedSide: Int] = [:]
    private(set) var requestedFirmness: [BedSide: Int] = [:]
    private(set) var head: [BedSide: Int] = [:]
    private(set) var foot: Int?
    private(set) var lightOn: Bool?
    private(set) var moving = false
    private(set) var activePreset: [BedSide: BedPreset] = [:]
    private(set) var activeHold: HoldKey?
    private(set) var busyAction: String?
    private(set) var lastUpdated: Date?
    var banner: String?

    var swapSides: Bool {
        didSet {
            UserDefaults.standard.set(swapSides, forKey: Keys.swapSides)
            log.info("Settings", "swap sides = \(swapSides)")
        }
    }

    let log = DiagnosticsLog()
    let ble: BLEManager

    @ObservationIgnored private var driver: BedDriver?
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    @ObservationIgnored private var holdTask: Task<Void, Never>?
    @ObservationIgnored private var reconnectTask: Task<Void, Never>?
    @ObservationIgnored private var pollCycle = 0
    @ObservationIgnored private var lastFirmnessSent: Date?
    @ObservationIgnored private var autoReconnect = true
    @ObservationIgnored private var pendingConnectName: String?

    private enum Keys {
        static let savedBed = "savedBed"
        static let swapSides = "swapSides"
    }

    var mapping: SideMapping { SideMapping(swapped: swapSides) }
    var isReady: Bool { phase == .ready }
    var supportedPresets: [BedPreset] { driver?.supportedPresets ?? [.flat, .zeroG, .snore, .favorite, .read, .watchTV] }
    var diagnostics: [(String, String)] { driver?.diagnostics ?? [] }

    init() {
        swapSides = UserDefaults.standard.bool(forKey: Keys.swapSides)
        if let data = UserDefaults.standard.data(forKey: Keys.savedBed),
           let bed = try? JSONDecoder().decode(SavedBed.self, from: data) {
            savedBed = bed
        }
        ble = BLEManager(log: log)
        ble.onStateChange = { [weak self] state in self?.bluetoothChanged(state) }
        ble.onReady = { [weak self] kind in self?.linkReady(kind) }
        ble.onDisconnect = { [weak self] error in self?.linkLost(error) }
        ble.onNotification = { [weak self] characteristic, data in self?.driver?.handleNotification(characteristic, data) }
        phase = savedBed == nil ? .noBed : .searching(savedBed?.name ?? "bed")
    }

    // MARK: Connection policy

    private func bluetoothChanged(_ state: CBManagerState) {
        switch state {
        case .poweredOn:
            if let savedBed, driver == nil, autoReconnect {
                connect(id: savedBed.id, name: savedBed.name)
            } else if savedBed == nil {
                phase = .noBed
            }
        case .unauthorized:
            phase = .bluetoothUnavailable("Bluetooth permission is off. Allow it in Settings > Privacy & Security > Bluetooth > Bed Control.")
        case .poweredOff:
            phase = .bluetoothUnavailable("Bluetooth is off. Turn it on in Control Center or Settings.")
        case .unsupported:
            phase = .bluetoothUnavailable("This device does not support Bluetooth LE.")
        default:
            break
        }
    }

    /// Connects to a device picked from the scan list and remembers it.
    func choose(_ device: ScannedDevice) {
        let bed = SavedBed(id: device.id, name: device.name, lastProtocol: device.classification.likelyProtocol)
        save(bed)
        autoReconnect = true
        connect(id: device.id, name: device.name)
    }

    func retry() {
        guard let savedBed else { phase = .noBed; return }
        autoReconnect = true
        banner = nil
        connect(id: savedBed.id, name: savedBed.name)
    }

    func disconnect() {
        autoReconnect = false
        reconnectTask?.cancel()
        tearDownDriver()
        ble.disconnect()
        phase = savedBed == nil ? .noBed : .failed("Disconnected. Tap Connect to reconnect.")
    }

    func forgetBed() {
        disconnect()
        savedBed = nil
        UserDefaults.standard.removeObject(forKey: Keys.savedBed)
        phase = .noBed
    }

    private func connect(id: UUID, name: String) {
        reconnectTask?.cancel()
        pendingConnectName = name
        phase = .searching(name)
        ble.connect(id: id, name: name)
    }

    private func save(_ bed: SavedBed) {
        savedBed = bed
        if let data = try? JSONEncoder().encode(bed) {
            UserDefaults.standard.set(data, forKey: Keys.savedBed)
        }
    }

    private func linkReady(_ kind: BedProtocol) {
        protocolKind = kind
        let name = savedBed?.name ?? pendingConnectName ?? "bed"
        phase = .connecting(kind == .mcr ? "Handshaking with \(name)…" : "Authenticating with \(name)…")
        tearDownDriver()
        let newDriver: BedDriver
        switch kind {
        case .mcr:
            newDriver = MCRDriver(ble: ble, log: log, macFallback: BedDetector.mcrAddress(fromMACName: name),
                                  mapping: { [weak self] in self?.mapping ?? SideMapping() })
        case .fuzion:
            newDriver = FuzionDriver(ble: ble, log: log, mapping: { [weak self] in self?.mapping ?? SideMapping() })
        }
        driver = newDriver
        if var bed = savedBed, bed.lastProtocol != kind {
            bed.lastProtocol = kind
            save(bed)
        }
        Task {
            do {
                try await newDriver.start()
                guard driver === newDriver else { return }
                phase = .ready
                banner = nil
                log.info("App", "ready (\(kind.displayName))")
                startPolling()
            } catch {
                guard driver === newDriver else { return }
                let bedError = error as? BedError ?? .transport(error.localizedDescription)
                log.error("App", "start failed: \(bedError.localizedDescription)")
                failConnection(bedError)
            }
        }
    }

    /// Errors that need the user (pairing, connection limit) stop auto-reconnect.
    private func failConnection(_ error: BedError) {
        let needsUser: Bool
        switch error {
        case .bondRequired, .pairingRemoved, .connectionLimit, .authenticationRejected, .unsupportedCommand: needsUser = true
        default: needsUser = false
        }
        tearDownDriver()
        ble.disconnect()
        phase = .failed(error.localizedDescription)
        if needsUser {
            autoReconnect = false
        } else {
            scheduleReconnect(after: .seconds(5))
        }
    }

    private func linkLost(_ error: BedError?) {
        let wasReady = phase == .ready
        tearDownDriver()
        log.info("App", "link lost\(error.map { ": \($0.localizedDescription)" } ?? "")")
        if let error, error == .pairingRemoved || error == .bondRequired {
            phase = .failed(error.localizedDescription)
            autoReconnect = false
            return
        }
        if case .failed = phase, !autoReconnect { return }
        guard let savedBed, autoReconnect else {
            phase = .failed(error?.localizedDescription ?? "Disconnected.")
            return
        }
        if wasReady {
            // Reconnect right away; iOS keeps the request pending until the bed is back in range.
            connect(id: savedBed.id, name: savedBed.name)
        } else {
            phase = .failed(error?.localizedDescription ?? "Could not connect.")
            scheduleReconnect(after: .seconds(5))
        }
    }

    private func scheduleReconnect(after delay: Duration) {
        reconnectTask?.cancel()
        reconnectTask = Task {
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, autoReconnect, driver == nil, let savedBed else { return }
            connect(id: savedBed.id, name: savedBed.name)
        }
    }

    private func tearDownDriver() {
        holdTask?.cancel()
        holdTask = nil
        activeHold = nil
        pollTask?.cancel()
        pollTask = nil
        driver?.reset()
        driver = nil
        queuedFirmness.removeAll()
        busyAction = nil
    }

    // MARK: Polling

    private func startPolling() {
        pollTask?.cancel()
        pollCycle = 0
        pollTask = Task {
            while !Task.isCancelled {
                if activeHold == nil {
                    await refreshNow()
                    await processFirmnessQueue()
                }
                let busy = moving || pumping.values.contains(true) || !queuedFirmness.isEmpty
                let interval: Duration = busy ? .seconds(2) : (protocolKind == .fuzion ? .seconds(10) : .seconds(5))
                try? await Task.sleep(for: interval)
            }
        }
    }

    /// One poll. Safe to call any time (it never moves the bed).
    func refreshNow() async {
        guard let driver, phase == .ready else { return }
        pollCycle += 1
        do {
            let reading = try await driver.refresh(includeLight: pollCycle % 3 == 1)
            apply(reading)
            lastUpdated = Date()
        } catch BedError.cancelled {
        } catch {
            log.error("App", "poll failed: \(error.localizedDescription)")
        }
    }

    private func apply(_ reading: BedReading) {
        for (side, value) in reading.firmness {
            firmness[side] = value
            if requestedFirmness[side] == value { requestedFirmness[side] = nil }
        }
        for (side, value) in reading.pumping { pumping[side] = value }
        for (side, value) in reading.head { head[side] = value }
        if let foot = reading.foot { self.foot = foot }
        if let lightOn = reading.lightOn { self.lightOn = lightOn }
        if let moving = reading.moving { self.moving = moving }
        for (side, preset) in reading.activePreset { activePreset[side] = preset }
    }

    // MARK: Commands

    private func perform(_ label: String, _ action: @escaping (BedDriver) async throws -> Void) {
        guard let driver, phase == .ready else {
            banner = "Not connected to the bed."
            return
        }
        busyAction = label
        Task {
            defer { if busyAction == label { busyAction = nil } }
            do {
                try await action(driver)
            } catch {
                let message = (error as? BedError)?.localizedDescription ?? error.localizedDescription
                log.error("App", "\(label) failed: \(message)")
                banner = "\(label) failed: \(message)"
            }
        }
    }

    func activate(_ preset: BedPreset, side: BedSide) {
        perform("\(preset.title) (\(side.title))") { driver in
            try await driver.activate(preset, side: side)
            self.moving = true
            self.activePreset[side] = nil
        }
    }

    func toggleLight() {
        let target = !(lightOn ?? false)
        perform(target ? "Light on" : "Light off") { driver in
            try await driver.setLight(target)
            self.lightOn = target
        }
    }

    /// Requests a firmness change. If the other side's chamber is adjusting, the request waits:
    /// the pump serves one side at a time and a new set would interrupt the other side.
    func setFirmness(_ value: Int, side: BedSide) {
        let normalized = SleepNumber.normalize(value)
        requestedFirmness[side] = normalized
        if pumping[side.other] == true || queuedFirmness[side.other] != nil || recentlySentFirmness {
            queuedFirmness[side] = normalized
            log.info("App", "\(side.title) firmness \(normalized) queued until the pump is idle")
            return
        }
        sendFirmness(normalized, side: side)
    }

    private var recentlySentFirmness: Bool {
        guard let lastFirmnessSent else { return false }
        return Date().timeIntervalSince(lastFirmnessSent) < 6
    }

    private func sendFirmness(_ value: Int, side: BedSide) {
        queuedFirmness[side] = nil
        lastFirmnessSent = Date()
        perform("Firmness \(value) (\(side.title))") { driver in
            try await driver.setFirmness(value, side: side)
            self.pumping[side] = true
        }
    }

    private func processFirmnessQueue() async {
        guard !queuedFirmness.isEmpty, !pumping.values.contains(true), !recentlySentFirmness else { return }
        let side: BedSide = queuedFirmness[.left] != nil ? .left : .right
        if let value = queuedFirmness[side] { sendFirmness(value, side: side) }
    }

    func cancelQueuedFirmness(_ side: BedSide) {
        queuedFirmness[side] = nil
        requestedFirmness[side] = nil
    }

    // MARK: Press and hold

    func beginHold(_ axis: FoundationAxis, side: BedSide, direction: MoveDirection) {
        guard let driver, phase == .ready else {
            banner = "Not connected to the bed."
            return
        }
        guard activeHold == nil else { return }
        let key = HoldKey(axis: axis, side: side, direction: direction)
        activeHold = key
        moving = true
        log.info("App", "hold \(axis.rawValue) \(side.title) \(direction == .up ? "up" : "down")")
        holdTask = Task {
            do {
                try await driver.hold(axis, side: side, direction: direction) { reading in
                    self.apply(reading)
                }
            } catch is CancellationError {
            } catch BedError.cancelled {
            } catch {
                let message = (error as? BedError)?.localizedDescription ?? error.localizedDescription
                log.error("App", "hold failed: \(message)")
                banner = "Move failed: \(message)"
            }
            // Always release, in a task that does not inherit this one's cancellation.
            let reading = await Task { @MainActor in await driver.releaseHold(axis, side: side) }.value
            if let reading { apply(reading) }
            if activeHold == key { activeHold = nil }
            holdTask = nil
        }
    }

    func endHold() {
        guard activeHold != nil else { return }
        log.info("App", "hold released")
        holdTask?.cancel()
    }

    // MARK: STOP

    func stopEverything() {
        log.info("App", "STOP pressed")
        holdTask?.cancel()
        queuedFirmness.removeAll()
        requestedFirmness.removeAll()
        guard let driver else { return }
        Task {
            do {
                try await driver.stopAll()
            } catch {
                banner = "STOP could not be sent: \(error.localizedDescription)"
            }
            try? await Task.sleep(for: .milliseconds(500))
            await refreshNow()
        }
    }

    // MARK: Diagnostics

    func diagnosticsHeader() -> String {
        let info = Bundle.main.infoDictionary
        var lines = ["Bed Control \(info?["CFBundleShortVersionString"] ?? "?") (\(info?["CFBundleVersion"] ?? "?"))",
                     "Bed: \(savedBed?.name ?? "none") \(savedBed?.id.uuidString ?? "")",
                     "Phase: \(phase)", "Swap sides: \(swapSides)"]
        lines += diagnostics.map { "\($0.0): \($0.1)" }
        return lines.joined(separator: "\n")
    }
}
