import Foundation

/// Request/response engine for the MCR protocol over an abstract `GATTLink`.
///
/// One request is outstanding at a time. Replies are matched by node family (high nibble of
/// the command byte), function code and the response bit; the bed never pushes unsolicited
/// state, so anything that does not match is logged and dropped.
@MainActor
public final class MCRSession {
    public struct Timing: Sendable {
        public var responseTimeout: Duration
        public var retries: Int
        public var bindTimeout: Duration
        public var bindAttempts: Int
        public init(responseTimeout: Duration = .milliseconds(1200), retries: Int = 2,
                    bindTimeout: Duration = .seconds(2), bindAttempts: Int = 3) {
            self.responseTimeout = responseTimeout
            self.retries = retries
            self.bindTimeout = bindTimeout
            self.bindAttempts = bindAttempts
        }
    }

    private struct Pending {
        let id: UInt64
        let matches: (MCRFrame) -> Bool
        let continuation: CheckedContinuation<MCRFrame, Error>
    }

    public private(set) var bind: MCRBindResult?
    public var isBound: Bool { bind != nil }

    private weak var link: GATTLink?
    private let log: FrameLogger
    private let timing: Timing
    private let macFallback: UInt16?
    private var parser = MCRStreamParser()
    private let mutex = AsyncMutex()
    private var pending: Pending?
    private var nextID: UInt64 = 0

    public init(link: GATTLink, macFallback: UInt16? = nil, timing: Timing = Timing(), log: @escaping FrameLogger) {
        self.link = link
        self.macFallback = macFallback
        self.timing = timing
        self.log = log
    }

    // MARK: Notifications

    /// Feed every notification from the MCR notify characteristic here.
    public func handleNotification(_ data: Data) {
        log(FrameEvent(.rx, channel: "MCR", bytes: data, text: "notify \(data.count)b"))
        for frame in parser.feed(data) {
            log(FrameEvent(.rx, channel: "MCR", bytes: frame.encoded, text: frame.description))
            if let pending, pending.matches(frame) {
                self.pending = nil
                pending.continuation.resume(returning: frame)
            } else {
                log(FrameEvent(.info, channel: "MCR", text: "unmatched reply dropped: \(frame.description)"))
            }
        }
    }

    /// Fails any in-flight request (call on disconnect).
    public func reset(error: BedError = .notConnected) {
        parser.reset()
        bind = nil
        failPending(error)
    }

    // MARK: Handshake

    /// Runs the bind handshake and stores the bed address for later requests.
    @discardableResult
    public func performBind() async throws -> MCRBindResult {
        let request = try MCRCommands.bind()
        var lastError: Error = BedError.timeout("bind")
        for attempt in 1...timing.bindAttempts {
            do {
                // Some firmware echoes the bind without the response bit; accept any frame.
                let reply = try await exchange(request, subAddress: 0, timeout: timing.bindTimeout) { _ in true }
                let result = try MCRBindResult.parse(reply, macFallback: macFallback)
                bind = result
                log(FrameEvent(.info, channel: "MCR", text: String(format: "bound: bed address %04X, client %@",
                                                                   result.bedAddress,
                                                                   result.clientAddress.map { String(format: "%04X", $0) } ?? "n/a")))
                return result
            } catch let error as BedError where error == .cancelled || error == .notConnected {
                throw error
            } catch {
                lastError = error
                log(FrameEvent(.error, channel: "MCR", text: "bind attempt \(attempt) failed: \(error.localizedDescription)"))
            }
        }
        throw lastError
    }

    // MARK: Requests

    /// Sends a request and returns the matching reply, retrying on timeout.
    @discardableResult
    public func send(_ request: MCRRequest, timeout: Duration? = nil, retries: Int? = nil) async throws -> MCRFrame {
        guard let bind else { throw BedError.notConnected }
        let attempts = (retries ?? timing.retries) + 1
        var lastError: Error = BedError.timeout(request.label)
        for attempt in 1...attempts {
            do {
                return try await exchange(request, subAddress: bind.bedAddress, timeout: timeout ?? timing.responseTimeout) { frame in
                    frame.isResponse
                        && (frame.command & 0xF0) == (request.node.rawValue & 0xF0)
                        && frame.functionCode == request.function
                }
            } catch BedError.timeout(let what) {
                lastError = BedError.timeout(what)
                if attempt < attempts {
                    log(FrameEvent(.info, channel: "MCR", text: "no reply to \(request.label); retry \(attempt)"))
                }
            }
        }
        throw lastError
    }

    /// Writes a request immediately, bypassing the request queue and without waiting for a
    /// reply. Used for STOP so it never waits behind a slow poll.
    public func sendUrgent(_ request: MCRRequest) async throws {
        guard let bind, let link else { throw BedError.notConnected }
        let data = try request.frame(subAddress: bind.bedAddress).encoded
        try MCRSafety.validateWireFrame(data)
        log(FrameEvent(.tx, channel: "MCR", bytes: data, text: "URGENT \(request.label)"))
        try await link.write(data, to: .mcrWrite)
    }

    private func exchange(_ request: MCRRequest, subAddress: UInt16, timeout: Duration,
                          matches: @escaping (MCRFrame) -> Bool) async throws -> MCRFrame {
        await mutex.lock()
        defer { mutex.unlock() }
        try Task.checkCancellation()
        guard let link else { throw BedError.notConnected }
        let data = try request.frame(subAddress: subAddress).encoded
        try MCRSafety.validateWireFrame(data)

        nextID &+= 1
        let id = nextID
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<MCRFrame, Error>) in
                failPending(.cancelled)
                pending = Pending(id: id, matches: matches, continuation: continuation)
                log(FrameEvent(.tx, channel: "MCR", bytes: data, text: request.label))
                Task { @MainActor in
                    do {
                        try await link.write(data, to: .mcrWrite)
                    } catch {
                        self.failPending(id: id, error as? BedError ?? .transport(error.localizedDescription))
                        return
                    }
                    try? await Task.sleep(for: timeout)
                    self.failPending(id: id, .timeout(request.label))
                }
            }
        } onCancel: {
            Task { @MainActor in self.failPending(id: id, .cancelled) }
        }
    }

    private func failPending(id: UInt64? = nil, _ error: BedError) {
        guard let pending, id == nil || pending.id == id else { return }
        self.pending = nil
        pending.continuation.resume(throwing: error)
    }

    private func failPending(_ error: BedError) { failPending(id: nil, error) }

    // MARK: Typed operations

    public func readPumpStatus() async throws -> MCRPumpStatus {
        try MCRPumpStatus.parse(try await send(try MCRCommands.readPumpStatus()))
    }

    public func readFoundationStatus(keepAdjusting side: MCRWireSide? = nil) async throws -> MCRFoundationStatus {
        try MCRFoundationStatus.parse(try await send(try MCRCommands.readFoundationStatus(keepAdjusting: side)))
    }

    public func readUnderBedLight() async throws -> MCRLightStatus {
        try MCRLightStatus.parse(try await send(try MCRCommands.readUnderBedLight()))
    }

    public func setUnderBedLight(on: Bool) async throws {
        try await send(try MCRCommands.setUnderBedLight(on: on))
    }

    public func activatePreset(_ preset: BedPreset, side: MCRWireSide) async throws {
        try await send(try MCRCommands.activatePreset(preset, side: side))
    }

    /// Interrupts any running adjustment, then starts one for `side`.
    public func setSleepNumber(_ value: Int, side: MCRWireSide) async throws {
        let request = try MCRCommands.setSleepNumber(side: side, value: value)
        try await send(try MCRCommands.pumpForceIdle())
        try await send(request)
    }

    public func setFoundationTarget(side: MCRWireSide, axis: FoundationAxis, position: Int) async throws {
        try await send(try MCRCommands.setFoundationTarget(side: side, axis: axis, position: position))
    }

    public func readSystemStatus() async throws -> MCRSystemStatus {
        try MCRSystemStatus.parse(try await send(try MCRCommands.readSystemStatus()))
    }

    public func readNodeList() async throws -> [UInt8] {
        try await send(try MCRCommands.readNodeList()).payload
    }

    /// Stops foundation movement: the app's per-side release (MFHR/MFHL), then ForceIdle.
    /// Every frame is attempted even if an earlier one fails.
    public func stopFoundation(sides: [MCRWireSide] = MCRWireSide.allCases) async throws {
        var firstError: Error?
        for side in sides {
            do { try await sendUrgent(try MCRCommands.stopMotion(side: side)) } catch { firstError = firstError ?? error }
        }
        do { try await sendUrgent(try MCRCommands.foundationForceIdle()) } catch { firstError = firstError ?? error }
        if let firstError { throw firstError }
    }

    public func stopPump() async throws {
        try await sendUrgent(try MCRCommands.pumpForceIdle())
    }
}
