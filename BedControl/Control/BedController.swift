import BedKit
import CoreBluetooth
import Foundation
import Observation
import UIKit

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
    /// One-side-at-a-time firmness state (pure, tested in BedKit).
    private(set) var firmnessQueue = FirmnessScheduler()
    var pumpBusy: Bool { firmnessQueue.pumpBusy }
    /// Side whose firmness change this app started and is still running.
    var adjustingSide: BedSide? { firmnessQueue.adjustingSide }
    var queuedFirmness: [BedSide: Int] { firmnessQueue.queued }
    var requestedFirmness: [BedSide: Int] { firmnessQueue.requested }
    private(set) var head: [BedSide: Int] = [:]
    private(set) var foot: [BedSide: Int] = [:]
    private(set) var lightOn: Bool?
    private(set) var moving = false
    private(set) var activePreset: [BedSide: BedPreset] = [:]
    private(set) var activeHold: HoldKey?
    private(set) var lastUpdated: Date?
    var banner: String?

    struct FavoriteRun: Equatable {
        let side: BedSide
        let favorite: BedFavorite
        var status: String
        var stepIndex: Int
        var stepCount: Int
    }

    /// Saved favorites for the current bed.
    private(set) var favorites = FavoritesDocument(bedID: "none")
    /// The favorite being applied, with live progress.
    private(set) var applyingFavorite: FavoriteRun?

    var swapSides: Bool {
        didSet {
            UserDefaults.standard.set(swapSides, forKey: Keys.swapSides)
            log.info("Settings", "swap sides = \(swapSides)")
        }
    }

    /// Which sleeper's side the shared foot control commands (the app sends foot moves with
    /// the sleeper's own side; the firmware drives the single foot motor).
    var footSide: BedSide {
        didSet { UserDefaults.standard.set(footSide.rawValue, forKey: Keys.footSide) }
    }

    let log = DiagnosticsLog()
    let ble: BLEManager

    @ObservationIgnored private var driver: BedDriver?
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    @ObservationIgnored private var holdTask: Task<Void, Never>?
    @ObservationIgnored private var holdID = 0
    @ObservationIgnored private var favoriteTask: Task<Void, Never>?
    @ObservationIgnored private var commandTasks: [Int: Task<Void, Never>] = [:]
    @ObservationIgnored private var nextCommandID = 0
    @ObservationIgnored private var reconnectTask: Task<Void, Never>?
    @ObservationIgnored private var pollCycle = 0
    @ObservationIgnored private var autoReconnect = true
    @ObservationIgnored private var pendingConnectName: String?

    private enum Keys {
        static let savedBed = "savedBed"
        static let swapSides = "swapSides"
        static let footSide = "footSide"
        static let mcrClientID = "mcrClientID"
    }

    var mapping: SideMapping { SideMapping(swapped: swapSides) }
    var isReady: Bool { phase == .ready }
    var presetOptions: [PresetOption] {
        driver?.presetOptions ?? [.preset(.favorite), .preset(.flat), .preset(.zeroG), .preset(.snore), .partnerSnore]
    }
    var hasUnderBedLight: Bool { driver?.hasUnderBedLight ?? true }
    var diagnostics: [(String, String)] { driver?.diagnostics ?? [] }

    init() {
        swapSides = UserDefaults.standard.bool(forKey: Keys.swapSides)
        footSide = BedSide(rawValue: UserDefaults.standard.string(forKey: Keys.footSide) ?? "") ?? .left
        if let data = UserDefaults.standard.data(forKey: Keys.savedBed),
           let bed = try? JSONDecoder().decode(SavedBed.self, from: data) {
            savedBed = bed
        }
        ble = BLEManager(log: log)
        ble.onStateChange = { [weak self] state in self?.bluetoothChanged(state) }
        ble.onReady = { [weak self] kind in self?.linkReady(kind) }
        ble.onDisconnect = { [weak self] error in self?.linkLost(error) }
        ble.onNotification = { [weak self] characteristic, data in self?.driver?.handleNotification(characteristic, data) }
        ble.onWriteStall = { [weak self] in
            guard let self, let driver = self.driver, self.activeHold != nil || self.moving else { return }
            self.holdTask?.cancel()
            Task { try? await driver.stopAll() }
        }
        phase = savedBed == nil ? .noBed : .searching(savedBed?.name ?? "bed")
        reloadFavorites()
    }

    /// The official app's persisted random 64-bit MCR client ID, created once.
    private var mcrClientID: [UInt8] {
        if let data = UserDefaults.standard.data(forKey: Keys.mcrClientID), data.count == 8 {
            return [UInt8](data)
        }
        var id = [UInt8](repeating: 0, count: 8)
        repeat {
            id = (0..<8).map { _ in UInt8.random(in: 0...255) }
        } while id.allSatisfy { $0 == 0 }
        UserDefaults.standard.set(Data(id), forKey: Keys.mcrClientID)
        return id
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
        autoReconnect = false
        Task {
            await stopMotionBeforeLeaving()
            tearDownDriver()
            ble.disconnect()
            let bed = SavedBed(id: device.id, name: device.name, lastProtocol: device.classification.likelyProtocol)
            save(bed)
            autoReconnect = true
            connect(id: device.id, name: device.name)
        }
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
        Task {
            await stopMotionBeforeLeaving()
            tearDownDriver()
            ble.disconnect()
            phase = savedBed == nil ? .noBed : .failed("Disconnected. Tap Retry to reconnect.")
        }
    }

    func forgetBed() {
        autoReconnect = false
        Task {
            await stopMotionBeforeLeaving()
            tearDownDriver()
            ble.disconnect()
            savedBed = nil
            UserDefaults.standard.removeObject(forKey: Keys.savedBed)
            reloadFavorites()
            phase = .noBed
        }
    }

    /// Sends (and waits up to 3 s for) the stop sequence before the link is dropped, if
    /// anything may be moving or pumping.
    private func stopMotionBeforeLeaving() async {
        guard let driver, activeHold != nil || moving || pumpBusy || applyingFavorite != nil else { return }
        holdTask?.cancel()
        favoriteTask?.cancel()
        let stop = Task { try? await driver.stopAll() }
        let timeout = Task {
            try? await Task.sleep(for: .seconds(3))
            stop.cancel()
        }
        _ = await stop.value
        timeout.cancel()
    }

    private func connect(id: UUID, name: String) {
        reconnectTask?.cancel()
        guard ble.knows(id) else {
            autoReconnect = false
            phase = .failed("This phone no longer knows the bed. Choose it again from the scan list.")
            return
        }
        pendingConnectName = name
        phase = .searching(name)
        ble.connect(id: id, name: name)
    }

    private func save(_ bed: SavedBed) {
        let changed = savedBed?.id != bed.id
        savedBed = bed
        if changed { reloadFavorites() }
        if let data = try? JSONEncoder().encode(bed) {
            UserDefaults.standard.set(data, forKey: Keys.savedBed)
        }
    }

    private func linkReady(_ kind: BedProtocol) {
        protocolKind = kind
        let name = savedBed?.name ?? pendingConnectName ?? "bed"
        phase = .connecting(kind == .mcr ? "Binding with \(name)…" : "Authenticating with \(name)…")
        tearDownDriver()
        let newDriver: BedDriver
        switch kind {
        case .mcr:
            newDriver = MCRDriver(ble: ble, log: log, clientID: mcrClientID,
                                  macFallback: BedDetector.mcrAddress(fromMACName: name),
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
        favoriteTask?.cancel()
        favoriteTask = nil
        applyingFavorite = nil
        holdTask?.cancel()
        holdTask = nil
        activeHold = nil
        pollTask?.cancel()
        pollTask = nil
        cancelCommands()
        driver?.reset()
        driver = nil
        firmnessQueue.clear(includingRunning: true)
    }

    // MARK: Polling

    private func startPolling() {
        pollTask?.cancel()
        pollCycle = 0
        pollTask = Task {
            while !Task.isCancelled {
                if activeHold == nil && applyingFavorite == nil {
                    await refreshNow()
                    processFirmnessQueue()
                }
                // While an adjustment runs, poll every second like the app; otherwise relax.
                let interval: Duration = adjustingSide != nil || pumpBusy ? .seconds(1)
                    : moving || !queuedFirmness.isEmpty ? .seconds(2)
                    : (protocolKind == .fuzion ? .seconds(10) : .seconds(5))
                try? await Task.sleep(for: interval)
            }
        }
    }

    /// One poll. Safe to call any time (it never moves the bed).
    func refreshNow() async {
        guard let driver, phase == .ready else { return }
        pollCycle += 1
        let started = Date()
        do {
            let reading = try await driver.refresh(includeLight: pollCycle % 3 == 1)
            apply(reading, pollStarted: started)
            lastUpdated = Date()
        } catch BedError.cancelled {
        } catch {
            log.error("App", "poll failed: \(error.localizedDescription)")
        }
    }

    private func apply(_ reading: BedReading, pollStarted: Date? = nil) {
        firmness = FirmnessDisplay.merge(current: firmness, reading: reading.firmness,
                                         pumpBusy: reading.pumpBusy, adjustingSide: adjustingSide)
        if let busy = reading.pumpBusy {
            // An adjustment ends only on an idle reading from a poll that started >= 1 s after
            // the bed acknowledged the set (see FirmnessScheduler).
            if let finished = firmnessQueue.pumpReading(busy: busy, pollStarted: pollStarted ?? .distantPast) {
                log.info("App", "\(finished.title) firmness adjustment finished")
            }
        }
        for (side, value) in reading.head { head[side] = value }
        for (side, value) in reading.foot { foot[side] = value }
        if let lightOn = reading.lightOn { self.lightOn = lightOn }
        if let moving = reading.moving { self.moving = moving }
        for (side, preset) in reading.activePreset { activePreset[side] = preset }
    }

    // MARK: Commands

    /// Runs a command as a tracked task so STOP can cancel it.
    private func perform(_ label: String, _ action: @escaping (BedDriver) async throws -> Void) {
        if let reason = commandBlockedReason {
            banner = reason
            return
        }
        guard let driver else { return }
        nextCommandID += 1
        let id = nextCommandID
        commandTasks[id] = Task {
            defer { commandTasks[id] = nil }
            do {
                try await action(driver)
            } catch is CancellationError {
            } catch BedError.cancelled {
                log.info("App", "\(label) cancelled")
            } catch {
                let message = (error as? BedError)?.localizedDescription ?? error.localizedDescription
                log.error("App", "\(label) failed: \(message)")
                banner = "\(label) failed: \(message)"
            }
        }
    }

    private func cancelCommands() {
        commandTasks.values.forEach { $0.cancel() }
        commandTasks.removeAll()
    }

    func activate(_ option: PresetOption, side: BedSide) {
        let (preset, target): (BedPreset, BedSide)
        switch option {
        case .preset(let chosen): (preset, target) = (chosen, side)
        case .partnerSnore: (preset, target) = (.snore, side.other)
        }
        perform("\(option.title) (\(side.title))") { driver in
            try await driver.activate(preset, side: target)
            self.moving = true
            self.activePreset[target] = nil
        }
    }

    func toggleLight() {
        let target = !(lightOn ?? false)
        perform(target ? "Light on" : "Light off") { driver in
            try await driver.setLight(target)
            self.lightOn = target
        }
    }

    /// Requests a firmness change. The pump serves one side at a time, so a request waits while
    /// the pump is busy with the other side (or with an adjustment this app did not start).
    /// A new value for the side already adjusting interrupts and replaces it, as the app does.
    /// Why a command cannot be sent right now, or nil if it can.
    private var commandBlockedReason: String? {
        if driver == nil || phase != .ready { return "Not connected to the bed." }
        if activeHold != nil { return "Release the movement button first." }
        if applyingFavorite != nil { return "A favorite is being applied. Press STOP to cancel it first." }
        return nil
    }

    func setFirmness(_ value: Int, side: BedSide) {
        // Guards are checked before any state changes (a refused request leaves no trace).
        switch firmnessQueue.request(value, side: side, blockedReason: commandBlockedReason) {
        case .refused(let reason):
            banner = reason
        case .queued:
            log.info("App", "\(side.title) firmness \(firmnessQueue.queued[side] ?? value) queued until the pump is idle")
        case .send(let side, let value, let interrupt):
            sendFirmness(value, side: side, interrupt: interrupt)
        }
    }

    /// Sends a set the scheduler has already marked as sending.
    private func sendFirmness(_ value: Int, side: BedSide, interrupt: Bool) {
        guard let driver else {
            firmnessQueue.sendFailed(side: side)
            return
        }
        nextCommandID += 1
        let id = nextCommandID
        commandTasks[id] = Task {
            defer { commandTasks[id] = nil }
            do {
                try await driver.setFirmness(value, side: side, interruptRunning: interrupt)
                firmnessQueue.acknowledged(side: side)
            } catch {
                firmnessQueue.sendFailed(side: side)
                if !FavoriteApplier.isCancellation(error) {
                    let message = (error as? BedError)?.localizedDescription ?? error.localizedDescription
                    log.error("App", "firmness \(value) (\(side.title)) failed: \(message)")
                    banner = "Firmness \(value) (\(side.title)) failed: \(message)"
                }
            }
        }
    }

    private func processFirmnessQueue() {
        guard commandBlockedReason == nil else { return }
        if case .send(let side, let value, let interrupt) = firmnessQueue.nextQueued() {
            sendFirmness(value, side: side, interrupt: interrupt)
        }
    }

    func cancelQueuedFirmness(_ side: BedSide) {
        firmnessQueue.cancelQueued(side: side)
    }

    // MARK: Press and hold

    func beginHold(_ axis: FoundationAxis, side: BedSide, direction: MoveDirection) {
        guard let driver, phase == .ready else {
            banner = "Not connected to the bed."
            return
        }
        guard activeHold == nil else { return }
        guard applyingFavorite == nil else {
            banner = "A favorite is being applied. Press STOP to cancel it first."
            return
        }
        let key = HoldKey(axis: axis, side: side, direction: direction)
        activeHold = key
        moving = true
        holdID += 1
        let id = holdID
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
            // Always release, in a task that does not inherit this one's cancellation, and keep
            // running long enough to send the stop even if the app is being backgrounded.
            let background = UIApplication.shared.beginBackgroundTask(withName: "Stop bed movement")
            let reading = await Task { @MainActor in await driver.releaseHold(axis, side: side) }.value
            if background != .invalid { UIApplication.shared.endBackgroundTask(background) }
            if let reading { apply(reading) }
            if holdID == id {
                activeHold = nil
                holdTask = nil
            }
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
        favoriteTask?.cancel()
        holdTask?.cancel()
        cancelCommands()
        firmnessQueue.clear(includingRunning: true)
        guard let driver else { return }
        Task {
            do {
                try await driver.stopAll()
            } catch BedError.cancelled {
            } catch {
                banner = "STOP could not be sent: \(error.localizedDescription)"
            }
            try? await Task.sleep(for: .milliseconds(500))
            await refreshNow()
        }
    }

    // MARK: Favorites

    private func reloadFavorites() {
        let loaded = FavoritesStore.load(bedID: savedBed?.id.uuidString ?? "none")
        favorites = loaded.document
        if let problem = loaded.problem {
            log.error("Favorites", problem)
            banner = problem
        }
    }

    private func persistFavorites() {
        do {
            try FavoritesStore.save(favorites)
        } catch {
            banner = "Could not save favorites: \(error.localizedDescription)"
            log.error("Favorites", "save failed: \(error.localizedDescription)")
        }
    }

    /// A new favorite pre-filled with live values read from the bed (falls back to the last
    /// known values if the read fails).
    func captureFavorite(for side: BedSide) async -> BedFavorite {
        await refreshNow()
        let count = favorites[side].count + 1
        return BedFavorite(name: "\(side.title) \(count)",
                           head: head[side] ?? 0,
                           foot: foot[side] ?? foot[side.other] ?? 0,
                           sleepNumber: firmness[side] ?? 50)
    }

    func saveFavorite(_ favorite: BedFavorite, side: BedSide) {
        if !favorites.update(favorite, on: side) { favorites.add(favorite, to: side) }
        persistFavorites()
        log.info("Favorites", "saved \(side.title) \"\(favorite.name)\" head \(favorite.head) foot \(favorite.foot) SN \(favorite.sleepNumber)")
    }

    func deleteFavorite(_ id: UUID, side: BedSide) {
        favorites.delete(id: id, from: side)
        persistFavorites()
    }

    func moveFavorites(side: BedSide, from offsets: IndexSet, to destination: Int) {
        favorites.move(on: side, fromOffsets: offsets, toOffset: destination)
        persistFavorites()
    }

    /// Applies a favorite: head, foot, then firmness, with progress. STOP cancels it.
    func applyFavorite(_ favorite: BedFavorite, side: BedSide) {
        guard let driver, phase == .ready else {
            banner = "Not connected to the bed."
            return
        }
        guard activeHold == nil, applyingFavorite == nil else {
            banner = "Wait for the current movement to finish."
            return
        }
        guard commandTasks.isEmpty, adjustingSide == nil else {
            banner = "Wait for the current command or firmness change to finish, or press STOP."
            return
        }
        guard !moving else {
            banner = "Wait for the bed to stop moving, or press STOP."
            return
        }
        // Queued firmness requests are dropped: the favorite sets this side's firmness itself.
        firmnessQueue.clear(includingRunning: false)
        let ops = driver.favoriteOps(for: side)
        let applier = FavoriteApplier()
        applyingFavorite = FavoriteRun(side: side, favorite: favorite, status: "Starting…", stepIndex: 0, stepCount: 3)
        log.info("Favorites", "apply \(side.title) \"\(favorite.name)\"")
        favoriteTask = Task {
            // If iOS is about to suspend the app, cancel (which sends a confirmed stop) rather
            // than be killed mid-move.
            let background = UIApplication.shared.beginBackgroundTask(withName: "Apply favorite") {
                self.log.info("Favorites", "background time expiring; cancelling the favorite")
                self.cancelFavorite()
            }
            defer { if background != .invalid { UIApplication.shared.endBackgroundTask(background) } }
            do {
                try await applier.apply(favorite, ops: ops) { progress in
                    self.handle(progress, side: side)
                }
                log.info("Favorites", "applied \"\(favorite.name)\"")
            } catch let error where FavoriteApplier.isCancellation(error) {
                log.info("Favorites", "apply cancelled")
            } catch {
                let message = (error as? BedError)?.localizedDescription ?? error.localizedDescription
                log.error("Favorites", "apply failed: \(message)")
                // Any failure after a move or set started has already told the bed to stop.
                banner = "\(favorite.name) did not finish: \(message). The bed was told to stop."
            }
            applyingFavorite = nil
            favoriteTask = nil
            await refreshNow()
        }
    }

    /// Cancels the favorite being applied (sends the side's stop).
    func cancelFavorite() {
        favoriteTask?.cancel()
    }

    private func handle(_ progress: FavoriteApplier.Progress, side: BedSide) {
        guard var run = applyingFavorite else { return }
        switch progress {
        case .step(let index, let count, let step):
            run.stepIndex = index
            run.stepCount = count
            run.status = step.title
        case .moving(let step, let current):
            run.status = "\(step.title): now \(current)%"
            if case .head = step { head[side] = current } else { foot[side] = current }
            moving = true
        case .skipped(let step):
            run.status = "\(step.title): already there"
        case .waitingForPump:
            run.status = "Waiting for the pump to finish the other side…"
        case .adjusting(let step, let current):
            run.status = "\(step.title): now \(current)"
            firmness[side] = current
            firmnessQueue.setPumpBusy(true)
        case .finished:
            run.status = "Done"
            moving = false
        }
        applyingFavorite = run
    }

    // MARK: Diagnostics

    func diagnosticsHeader() -> String {
        let info = Bundle.main.infoDictionary
        var lines = ["Bed Control \(info?["CFBundleShortVersionString"] ?? "?") (\(info?["CFBundleVersion"] ?? "?"))",
                     "Bed: \(savedBed?.name ?? "none") \(savedBed?.id.uuidString ?? "")",
                     "Phase: \(phase)", "Swap sides: \(swapSides)", "Foot side: \(footSide.title)"]
        lines += diagnostics.map { "\($0.0): \($0.1)" }
        return lines.joined(separator: "\n")
    }
}
