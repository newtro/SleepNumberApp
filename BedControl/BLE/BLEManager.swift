import BedKit
import CoreBluetooth
import Foundation
import Observation

/// A device seen while scanning.
struct ScannedDevice: Identifiable, Equatable {
    let id: UUID
    var name: String
    var rssi: Int
    var classification: CandidateClassification
    var lastSeen: Date
}

/// CoreBluetooth transport: scanning, connecting, GATT discovery, chunked writes with flow
/// control, reads, and notification routing. Implements BedKit's `GATTLink`; all protocol
/// logic lives in BedKit and the drivers. Runs entirely on the main queue.
@MainActor
@Observable
final class BLEManager: NSObject, GATTLink {
    enum LinkState: Equatable {
        case idle
        case connecting(String)
        case discovering
        case ready(BedProtocol)
    }

    private(set) var bluetoothState: CBManagerState = .unknown
    private(set) var isScanning = false
    private(set) var devices: [ScannedDevice] = []
    private(set) var linkState: LinkState = .idle
    private(set) var connectedID: UUID?
    var showAllDevices = false

    // Controller hooks
    @ObservationIgnored var onStateChange: ((CBManagerState) -> Void)?
    @ObservationIgnored var onReady: ((BedProtocol) -> Void)?
    @ObservationIgnored var onDisconnect: ((BedError?) -> Void)?
    @ObservationIgnored var onNotification: ((BedCharacteristic, Data) -> Void)?
    /// Tells BamKey notifications ("response ready" hints) apart from read results.
    @ObservationIgnored var isFuzionHint: ((Data) -> Bool)?

    @ObservationIgnored private let log: DiagnosticsLog
    @ObservationIgnored private var central: CBCentralManager!
    @ObservationIgnored private var peripheral: CBPeripheral?
    @ObservationIgnored private var characteristics: [BedCharacteristic: CBCharacteristic] = [:]
    @ObservationIgnored private var pendingReads: [BedCharacteristic: [CheckedContinuation<Data, Error>]] = [:]
    @ObservationIgnored private var pendingWrite: CheckedContinuation<Void, Error>?
    @ObservationIgnored private var writeSequence: UInt64 = 0
    @ObservationIgnored private var notifyWaiters: [BedCharacteristic: CheckedContinuation<Void, Error>] = [:]
    @ObservationIgnored private var readyToSendWaiters: [CheckedContinuation<Void, Never>] = []
    @ObservationIgnored private let writeMutex = WriteMutex()
    @ObservationIgnored private var discoveryTimeout: Task<Void, Never>?
    @ObservationIgnored private var userDisconnect = false

    static let mcrChunkSize = 20

    init(log: DiagnosticsLog) {
        self.log = log
        super.init()
        central = CBCentralManager(delegate: self, queue: .main,
                                   options: [CBCentralManagerOptionShowPowerAlertKey: true])
    }

    // MARK: Scanning

    func startScan() {
        guard bluetoothState == .poweredOn else { return }
        devices.removeAll()
        // nil services: MCR beds often advertise only their MAC as a name, without the service UUID.
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        isScanning = true
        log.info("BLE", "scan started")
    }

    func stopScan() {
        guard isScanning else { return }
        central.stopScan()
        isScanning = false
        log.info("BLE", "scan stopped")
    }

    var visibleDevices: [ScannedDevice] {
        devices
            .filter { showAllDevices || $0.classification.isCandidate }
            .sorted { ($0.classification.score, $0.rssi) > ($1.classification.score, $1.rssi) }
    }

    // MARK: Connection

    /// Connects to a known peripheral. CoreBluetooth keeps the request pending until the bed is
    /// in range, which is what auto-reconnect wants; `disconnect()` cancels it.
    func connect(id: UUID, name: String) {
        guard bluetoothState == .poweredOn else { return }
        guard let target = central.retrievePeripherals(withIdentifiers: [id]).first else {
            log.error("BLE", "peripheral \(id) is unknown to iOS; scan for it again")
            onDisconnect?(.transport("This phone no longer knows the bed. Scan for it again."))
            return
        }
        cleanupConnection(error: nil, notify: false)
        stopScan()
        userDisconnect = false
        peripheral = target
        target.delegate = self
        connectedID = id
        linkState = .connecting(name)
        log.info("BLE", "connecting to \(name) (\(id.uuidString))")
        central.connect(target, options: nil)
    }

    func disconnect() {
        userDisconnect = true
        if let peripheral {
            central.cancelPeripheralConnection(peripheral)
        }
        cleanupConnection(error: nil, notify: false)
        connectedID = nil
        linkState = .idle
    }

    /// Subscribes to a characteristic and waits for the subscription to be confirmed.
    func enableNotifications(_ characteristic: BedCharacteristic) async throws {
        guard let peripheral, let target = characteristics[characteristic] else { throw BedError.notConnected }
        if target.isNotifying { return }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            notifyWaiters[characteristic]?.resume(throwing: BedError.cancelled)
            notifyWaiters[characteristic] = continuation
            peripheral.setNotifyValue(true, for: target)
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(15))
                if let waiter = self.notifyWaiters.removeValue(forKey: characteristic) {
                    waiter.resume(throwing: BedError.timeout("subscribe to \(characteristic.rawValue)"))
                }
            }
        }
        log.info("BLE", "notifications on \(characteristic.rawValue)")
    }

    func has(_ characteristic: BedCharacteristic) -> Bool { characteristics[characteristic] != nil }

    // MARK: GATTLink

    func write(_ frame: Data, to characteristic: BedCharacteristic) async throws {
        await writeMutex.lock()
        defer { writeMutex.unlock() }
        guard let peripheral, let target = characteristics[characteristic] else { throw BedError.notConnected }
        let withoutResponse = target.properties.contains(.writeWithoutResponse)
        let type: CBCharacteristicWriteType = withoutResponse ? .withoutResponse : .withResponse
        let chunkSize = characteristic == .mcrWrite
            ? BLEManager.mcrChunkSize
            : max(20, peripheral.maximumWriteValueLength(for: type))
        var offset = 0
        while offset < frame.count {
            let end = min(offset + chunkSize, frame.count)
            let chunk = frame.subdata(in: offset..<end)
            if withoutResponse {
                var waited = 0
                while !peripheral.canSendWriteWithoutResponse {
                    guard self.peripheral === peripheral, waited < 20 else { throw BedError.timeout("BLE write buffer") }
                    await waitUntilReadyToSend()
                    waited += 1
                }
                peripheral.writeValue(chunk, for: target, type: .withoutResponse)
            } else {
                writeSequence &+= 1
                let sequence = writeSequence
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    pendingWrite = continuation
                    peripheral.writeValue(chunk, for: target, type: .withResponse)
                    Task { @MainActor in
                        try? await Task.sleep(for: .seconds(5))
                        if self.writeSequence == sequence, let waiting = self.pendingWrite {
                            self.pendingWrite = nil
                            waiting.resume(throwing: BedError.timeout("BLE write"))
                        }
                    }
                }
            }
            offset = end
        }
    }

    func read(_ characteristic: BedCharacteristic) async throws -> Data {
        guard let peripheral, let target = characteristics[characteristic] else { throw BedError.notConnected }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                pendingReads[characteristic, default: []].append(continuation)
                peripheral.readValue(for: target)
            }
        } onCancel: {
            Task { @MainActor in self.failReads(characteristic, .cancelled) }
        }
    }

    private func waitUntilReadyToSend() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            readyToSendWaiters.append(continuation)
            // peripheralIsReady can be missed; re-check periodically.
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(100))
                self.resumeReadyToSend()
            }
        }
    }

    private func resumeReadyToSend() {
        let waiters = readyToSendWaiters
        readyToSendWaiters.removeAll()
        waiters.forEach { $0.resume() }
    }

    private func failReads(_ characteristic: BedCharacteristic, _ error: BedError) {
        let waiters = pendingReads.removeValue(forKey: characteristic) ?? []
        waiters.forEach { $0.resume(throwing: error) }
    }

    private func cleanupConnection(error: BedError?, notify: Bool) {
        discoveryTimeout?.cancel()
        let failure = error ?? .notConnected
        for key in Array(pendingReads.keys) { failReads(key, failure) }
        pendingWrite?.resume(throwing: failure)
        pendingWrite = nil
        for (_, waiter) in notifyWaiters { waiter.resume(throwing: failure) }
        notifyWaiters.removeAll()
        resumeReadyToSend()
        characteristics.removeAll()
        peripheral?.delegate = nil
        peripheral = nil
        if notify { onDisconnect?(error) }
    }

    private func identify(_ characteristic: CBCharacteristic) -> BedCharacteristic? {
        switch characteristic.uuid.uuidString.uppercased() {
        case BedProtocol.mcrNotifyUUID: return .mcrNotify
        case BedProtocol.mcrWriteUUID: return .mcrWrite
        case BedProtocol.fuzionAuthUUID: return .fuzionAuth
        case BedProtocol.fuzionBamKeyUUID: return .fuzionBamKey
        case BedProtocol.fuzionBulkUUID: return .fuzionBulk
        default: return nil
        }
    }

    /// Maps CoreBluetooth errors to user-actionable BedErrors (bonding problems in particular).
    static func mapError(_ error: Error) -> BedError {
        let nsError = error as NSError
        if nsError.domain == CBATTErrorDomain, let code = CBATTError.Code(rawValue: nsError.code) {
            switch code {
            case .insufficientAuthentication, .insufficientEncryption, .insufficientAuthorization,
                 .insufficientEncryptionKeySize:
                return .bondRequired
            default:
                return .transport("ATT error \(nsError.code): \(nsError.localizedDescription)")
            }
        }
        if nsError.domain == CBErrorDomain, let code = CBError.Code(rawValue: nsError.code) {
            switch code {
            case .peerRemovedPairingInformation: return .pairingRemoved
            case .encryptionTimedOut: return .bondRequired
            default: return .transport(nsError.localizedDescription)
            }
        }
        return .transport(nsError.localizedDescription)
    }
}

// MARK: - CBCentralManagerDelegate

extension BLEManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            bluetoothState = central.state
            log.info("BLE", "Bluetooth state \(central.state.rawValue)")
            if central.state != .poweredOn {
                isScanning = false
                if peripheral != nil {
                    cleanupConnection(error: .transport("Bluetooth turned off"), notify: true)
                    linkState = .idle
                }
            }
            onStateChange?(central.state)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        MainActor.assumeIsolated {
            let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
            let name = localName ?? peripheral.name
            let services = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? [])
                + (advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey] as? [CBUUID] ?? [])
            let info = AdvertisementInfo(name: name,
                                         serviceUUIDs: services.map(\.uuidString),
                                         manufacturerData: advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data)
            let classification = BedDetector.classify(info)
            guard classification.isCandidate || name != nil else { return }
            let rssi = RSSI.intValue == 127 ? -100 : RSSI.intValue
            let display = name ?? "Unnamed device"
            if let index = devices.firstIndex(where: { $0.id == peripheral.identifier }) {
                // Throttle UI churn from duplicate advertisements.
                guard Date().timeIntervalSince(devices[index].lastSeen) > 1 || devices[index].name != display else { return }
                devices[index].rssi = rssi
                devices[index].name = display
                devices[index].lastSeen = Date()
                if classification.score > devices[index].classification.score {
                    devices[index].classification = classification
                }
            } else {
                devices.append(ScannedDevice(id: peripheral.identifier, name: display, rssi: rssi,
                                             classification: classification, lastSeen: Date()))
                if classification.isCandidate {
                    log.info("Scan", "candidate \(display) rssi \(rssi): \(classification.reasons.map(\.rawValue).joined(separator: ", "))")
                }
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated {
            guard peripheral === self.peripheral else { return }
            log.info("BLE", "connected; discovering services")
            linkState = .discovering
            peripheral.discoverServices([CBUUID(string: BedProtocol.mcrServiceUUID),
                                         CBUUID(string: BedProtocol.fuzionServiceUUID)])
            discoveryTimeout?.cancel()
            discoveryTimeout = Task { @MainActor in
                try? await Task.sleep(for: .seconds(15))
                guard !Task.isCancelled, self.linkState == .discovering, let current = self.peripheral else { return }
                self.log.error("BLE", "service discovery timed out")
                central.cancelPeripheralConnection(current)
                self.cleanupConnection(error: .timeout("service discovery"), notify: true)
                self.linkState = .idle
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            guard peripheral === self.peripheral else { return }
            let mapped = error.map(BLEManager.mapError) ?? .transport("connection failed")
            log.error("BLE", "connect failed: \(mapped.localizedDescription)")
            cleanupConnection(error: mapped, notify: true)
            linkState = .idle
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            guard peripheral === self.peripheral else { return }
            let mapped = error.map(BLEManager.mapError)
            log.info("BLE", "disconnected\(mapped.map { ": \($0.localizedDescription)" } ?? "")")
            let wasUser = userDisconnect
            cleanupConnection(error: mapped, notify: !wasUser)
            linkState = .idle
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BLEManager: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            guard peripheral === self.peripheral else { return }
            if let error {
                fail(BLEManager.mapError(error), "service discovery")
                return
            }
            let uuids = (peripheral.services ?? []).map(\.uuid.uuidString)
            log.info("BLE", "services: \(uuids.joined(separator: ", "))")
            guard let kind = BedProtocol.detect(serviceUUIDs: uuids),
                  let service = peripheral.services?.first(where: { BedProtocol.forService($0.uuid.uuidString) == kind }) else {
                fail(.unsupportedCommand("Sleep Number control (no MCR or Fuzion service found)"), "detect")
                return
            }
            log.info("BLE", "detected protocol \(kind.displayName)")
            let wanted: [String] = kind == .mcr
                ? [BedProtocol.mcrNotifyUUID, BedProtocol.mcrWriteUUID]
                : [BedProtocol.fuzionAuthUUID, BedProtocol.fuzionBamKeyUUID, BedProtocol.fuzionBulkUUID]
            peripheral.discoverCharacteristics(wanted.map { CBUUID(string: $0) }, for: service)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            guard peripheral === self.peripheral else { return }
            if let error {
                fail(BLEManager.mapError(error), "characteristic discovery")
                return
            }
            for characteristic in service.characteristics ?? [] {
                if let id = identify(characteristic) {
                    characteristics[id] = characteristic
                    log.info("BLE", "characteristic \(id.rawValue) properties 0x\(String(characteristic.properties.rawValue, radix: 16))")
                }
            }
            guard let kind = BedProtocol.forService(service.uuid.uuidString) else { return }
            let required: [BedCharacteristic] = kind == .mcr ? [.mcrNotify, .mcrWrite] : [.fuzionAuth, .fuzionBamKey]
            let missing = required.filter { characteristics[$0] == nil }
            guard missing.isEmpty else {
                fail(.malformedResponse("bed is missing \(missing.map(\.rawValue).joined(separator: ", "))"), "characteristics")
                return
            }
            discoveryTimeout?.cancel()
            linkState = .ready(kind)
            onReady?(kind)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard peripheral === self.peripheral, let id = identify(characteristic) else { return }
            if let error {
                let mapped = BLEManager.mapError(error)
                if let waiter = pendingReads[id]?.first {
                    pendingReads[id]?.removeFirst()
                    waiter.resume(throwing: mapped)
                } else {
                    log.error(id.rawValue, "value error: \(mapped.localizedDescription)")
                }
                return
            }
            let value = characteristic.value ?? Data()
            let isHint = id == .fuzionBamKey && (isFuzionHint?(value) ?? false)
            if !isHint, let waiter = pendingReads[id]?.first {
                pendingReads[id]?.removeFirst()
                waiter.resume(returning: value)
            } else {
                onNotification?(id, value)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard let waiter = pendingWrite else { return }
            pendingWrite = nil
            if let error {
                waiter.resume(throwing: BLEManager.mapError(error))
            } else {
                waiter.resume()
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard let id = identify(characteristic), let waiter = notifyWaiters.removeValue(forKey: id) else { return }
            if let error {
                waiter.resume(throwing: BLEManager.mapError(error))
            } else {
                waiter.resume()
            }
        }
    }

    nonisolated func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        MainActor.assumeIsolated { resumeReadyToSend() }
    }

    private func fail(_ error: BedError, _ stage: String) {
        log.error("BLE", "\(stage) failed: \(error.localizedDescription)")
        if let peripheral { central.cancelPeripheralConnection(peripheral) }
        cleanupConnection(error: error, notify: true)
        linkState = .idle
    }
}

/// Serializes whole-frame writes so chunks of two frames never interleave.
@MainActor
private final class WriteMutex {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func lock() async {
        if !locked {
            locked = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func unlock() {
        if waiters.isEmpty { locked = false } else { waiters.removeFirst().resume() }
    }
}
