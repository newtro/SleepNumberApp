import Foundation

/// Request/response engine for the MCR protocol over an abstract `GATTLink`.
///
/// Mirrors the official app (`blelib/mcr/manager`): one request in flight, 900 ms timeout,
/// up to 3 retries on timeout. A reply matches only if it has the response bit, the same node
/// family and command, and (unless its destination is 0) is addressed to us from the bed.
/// The bed never pushes state; anything that does not match is logged and dropped.
@MainActor
public final class MCRSession {
    public struct Timing: Sendable {
        public var responseTimeout: Duration
        public var retries: Int
        public init(responseTimeout: Duration = .milliseconds(900), retries: Int = 3) {
            self.responseTimeout = responseTimeout
            self.retries = retries
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
    private let clientID: [UInt8]
    private let macFallback: UInt16?
    private var parser = MCRStreamParser()
    private let mutex = AsyncMutex()
    private var pending: Pending?
    private var nextID: UInt64 = 0
    /// Incremented by every stop. A request started before a stop never goes out (or retries)
    /// after it, so a queued preset or a lost-reply retry cannot restart motion after STOP.
    public private(set) var stopGeneration: UInt64 = 0

    /// - Parameter clientID: the persisted random 8-byte client ID sent in the bind.
    public init(link: GATTLink, clientID: [UInt8], macFallback: UInt16? = nil, timing: Timing = Timing(),
                log: @escaping FrameLogger) {
        self.link = link
        self.clientID = clientID
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

    /// Runs the bind and stores the assigned addresses.
    @discardableResult
    public func performBind() async throws -> MCRBindResult {
        let request = try MCRCommands.bind(clientID: clientID)
        var lastError: Error = BedError.timeout("bind")
        for attempt in 1...(timing.retries + 1) {
            do {
                // Before binding both addresses are 0, so a reply addressed to 0 is accepted
                // (the app's rule); require only the response bit and the bind command.
                let reply = try await exchange(request, client: 0, bed: 0, timeout: timing.responseTimeout) {
                    $0.isResponse && $0.functionCode == MCRFunction.bind
                }
                let result = try MCRBindResult.parse(reply, macFallback: macFallback)
                bind = result
                log(FrameEvent(.info, channel: "MCR", text: String(format: "bound: bed %04X, client %04X",
                                                                   result.bedAddress, result.clientAddress)))
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
    /// - Parameter generation: the `stopGeneration` the caller's operation started under; the
    ///   request is abandoned with `.cancelled` if a stop happened since. Defaults to now.
    @discardableResult
    public func send(_ request: MCRRequest, timeout: Duration? = nil, retries: Int? = nil,
                     generation: UInt64? = nil) async throws -> MCRFrame {
        guard let bind else { throw BedError.notConnected }
        let generation = generation ?? stopGeneration
        let attempts = (retries ?? timing.retries) + 1
        var lastError: Error = BedError.timeout(request.label)
        for attempt in 1...attempts {
            do {
                let reply = try await exchange(request, client: bind.clientAddress, bed: bind.bedAddress,
                                               timeout: timeout ?? timing.responseTimeout,
                                               generation: generation) { frame in
                    MCRSession.reply(frame, matches: request, bind: bind)
                }
                switch reply.side {
                case 0x0E: throw BedError.commandFailed("\(request.label): bad parameter")
                case 0x0F: throw BedError.commandFailed("\(request.label): NACK")
                default: return reply
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

    /// Reply correlation per `mcr/manager/a`.
    nonisolated static func reply(_ frame: MCRFrame, matches request: MCRRequest, bind: MCRBindResult) -> Bool {
        guard frame.isResponse,
              frame.functionCode == request.function,
              frame.command & 0xF0 == request.node.rawValue & 0xF0 else { return false }
        if frame.destination == 0 { return true }
        let fromBed = frame.sourceEcho == bind.bedAddress || (bind.bedAddressFromMAC && frame.sourceEcho == 0)
        return frame.destination == bind.clientAddress && fromBed
    }

    /// Writes a request immediately, bypassing the request queue and without waiting for a
    /// reply. Used for STOP so it never waits behind a slow poll.
    public func sendUrgent(_ request: MCRRequest) async throws {
        guard let bind, let link else { throw BedError.notConnected }
        let data = try request.frame(client: bind.clientAddress, bed: bind.bedAddress).encoded
        try MCRSafety.validateWireFrame(data)
        log(FrameEvent(.tx, channel: "MCR", bytes: data, text: "URGENT \(request.label)"))
        try await link.write(data, to: .mcrWrite)
    }

    private func exchange(_ request: MCRRequest, client: UInt16, bed: UInt16, timeout: Duration,
                          generation: UInt64? = nil,
                          matches: @escaping (MCRFrame) -> Bool) async throws -> MCRFrame {
        await mutex.lock()
        defer { mutex.unlock() }
        try Task.checkCancellation()
        if let generation, generation != stopGeneration, !request.isStop {
            log(FrameEvent(.info, channel: "MCR", text: "dropped \(request.label): STOP was pressed after it was queued"))
            throw BedError.cancelled
        }
        guard let link else { throw BedError.notConnected }
        let data = try request.frame(client: client, bed: bed).encoded
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

    /// Bumps the stop generation (abandoning queued requests) without sending anything.
    public func invalidateQueued() {
        stopGeneration &+= 1
    }

    /// Starts a firmness adjustment. With `interruptRunning`, first sends ForceIdle and waits
    /// 500 ms, as the app does when it cancels an adjustment in progress.
    public func setSleepNumber(_ value: Int, side: MCRWireSide, interruptRunning: Bool) async throws {
        let request = try MCRCommands.setSleepNumber(side: side, value: value)
        let generation = stopGeneration
        if interruptRunning {
            try await send(try MCRCommands.pumpForceIdle(), generation: generation)
            try await Task.sleep(for: .milliseconds(500))
        }
        try await send(request, generation: generation)
    }

    public func setFoundationTarget(side: MCRWireSide, axis: FoundationAxis, position: Int) async throws {
        try await send(try MCRCommands.setFoundationTarget(side: side, axis: axis, position: position))
    }

    public func readSystemStatus() async throws -> MCRSystemStatus {
        try MCRSystemStatus.parse(try await send(try MCRCommands.readSystemStatus()))
    }

    public func readPinchState() async throws -> MCRPinchState {
        try MCRPinchState.parse(try await send(try MCRCommands.readPinchState()))
    }

    public func readNodeList() async throws -> [UInt8] {
        try await send(try MCRCommands.readNodeList()).payload
    }

    /// Stops foundation movement with the app's stop key (MFHR/MFHL "110") for each side.
    /// Each stop is written immediately (bypassing any queued request), then sent again through
    /// the queue so the bed's acknowledgement is checked. Every side is attempted.
    public func stopFoundation(sides: [MCRWireSide] = MCRWireSide.allCases) async throws {
        stopGeneration &+= 1
        var firstError: Error?
        for side in sides {
            do { try await sendUrgent(try MCRCommands.stopMotion(side: side)) } catch { firstError = firstError ?? error }
        }
        for side in sides {
            do { try await send(try MCRCommands.stopMotion(side: side), retries: 1) } catch { firstError = firstError ?? error }
        }
        if let firstError { throw firstError }
    }

    /// Stops the given sides, then reads foundation status and re-sends the stop (up to 3
    /// attempts) while it still reports motion. Never throws; returns the last status read.
    /// Call it from a task that is not cancelled (the status reads honour cancellation).
    @discardableResult
    public func stopFoundationConfirmed(sides: [MCRWireSide] = MCRWireSide.allCases) async -> MCRFoundationStatus? {
        var last: MCRFoundationStatus?
        for attempt in 1...3 {
            do {
                try await stopFoundation(sides: sides)
            } catch {
                log(FrameEvent(.error, channel: "MCR", text: "stop attempt \(attempt) failed: \(error.localizedDescription)"))
            }
            try? await Task.sleep(for: .milliseconds(300))
            if let frame = try? await send(try MCRCommands.readFoundationStatus(), generation: stopGeneration) {
                last = try? MCRFoundationStatus.parse(frame)
            }
            if let last, !last.moving { return last }
            log(FrameEvent(.error, channel: "MCR", text: "foundation still moving (or unreadable) after stop attempt \(attempt)"))
        }
        return last
    }

    /// Interrupts a firmness adjustment immediately.
    public func stopPump() async throws {
        stopGeneration &+= 1
        try await sendUrgent(try MCRCommands.pumpForceIdle())
    }
}
