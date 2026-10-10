import Foundation

/// Request/response engine for the Fuzion BamKey protocol over an abstract `GATTLink`.
///
/// Flow per command: write the framed text to BamKey (chunked by the link) -> wait for a
/// BamKey notification carrying our 16-byte session UUID (the "response ready" hint) -> read
/// BamKey until one complete blob is assembled -> parse PASS/FAIL.
@MainActor
public final class FuzionSession {
    public struct Timing: Sendable {
        public var responseTimeout: Duration
        public var authTimeout: Duration
        public var authAttempts: Int
        public init(responseTimeout: Duration = .milliseconds(7128), authTimeout: Duration = .seconds(10), authAttempts: Int = 3) {
            self.responseTimeout = responseTimeout
            self.authTimeout = authTimeout
            self.authAttempts = authAttempts
        }
    }

    public private(set) var sessionID: Data?

    private weak var link: GATTLink?
    private let log: FrameLogger
    private let timing: Timing
    private let mutex = AsyncMutex()
    private var hintWaiter: (id: UInt64, continuation: CheckedContinuation<Void, Error>)?
    private var hintArrived = false
    private var awaitingHint = false
    private var nextID: UInt64 = 0

    public init(link: GATTLink, timing: Timing = Timing(), log: @escaping FrameLogger) {
        self.link = link
        self.timing = timing
        self.log = log
    }

    /// True if `data` is a response-ready hint (our session UUID, or the all-zero UUID the app
    /// also accepts). The BLE layer uses this to tell notifications apart from read results.
    public func isHint(_ data: Data) -> Bool {
        guard let sessionID, data.count == 16 else { return false }
        return data == sessionID || data.allSatisfy { $0 == 0 }
    }

    // MARK: Authentication

    /// Reads Auth (which makes iOS pair if the bed demands encryption) and validates the session.
    public func authenticate() async throws -> Data {
        guard let link else { throw BedError.notConnected }
        var lastError: Error = BedError.timeout("Auth read")
        for attempt in 1...timing.authAttempts {
            do {
                let value = try await withTimeout(timing.authTimeout, label: "Auth read") {
                    try await link.read(.fuzionAuth)
                }
                log(FrameEvent(.rx, channel: "Auth", bytes: value, text: "Auth \(value.count)b"))
                let session = try FuzionAuth.validateSession(value)
                sessionID = session
                return session
            } catch let error as BedError {
                switch error {
                case .timeout, .transport:
                    lastError = error
                    log(FrameEvent(.error, channel: "Auth", text: "attempt \(attempt): \(error.localizedDescription)"))
                default:
                    throw error  // limit, rejection, bond problems: retrying will not help
                }
            }
        }
        throw lastError
    }

    public func reset(error: BedError = .notConnected) {
        sessionID = nil
        failHint(error)
    }

    // MARK: Notifications

    /// Feed every BamKey notification here.
    public func handleNotification(_ data: Data) {
        guard isHint(data) else {
            log(FrameEvent(.rx, channel: "BamKey", bytes: data, text: "notify (not a session hint, ignored)"))
            return
        }
        log(FrameEvent(.rx, channel: "BamKey", bytes: data, text: "response ready"))
        guard awaitingHint else { return }
        if let waiter = hintWaiter {
            hintWaiter = nil
            waiter.continuation.resume()
        } else {
            hintArrived = true
        }
    }

    // MARK: Commands

    /// Sends one command and returns its PASS argument tokens.
    @discardableResult
    public func send(_ command: FuzionCommand) async throws -> [String] {
        await mutex.lock()
        defer { mutex.unlock() }
        try Task.checkCancellation()
        guard let link, sessionID != nil else { throw BedError.notConnected }

        hintArrived = false
        awaitingHint = true
        defer { awaitingHint = false }
        log(FrameEvent(.tx, channel: "BamKey", bytes: command.frame, text: command.text))
        try await link.write(command.frame, to: .fuzionBamKey)
        try await waitForHint(label: command.key)

        var reassembler = FuzionReassembler()
        let text: String = try await withTimeout(timing.responseTimeout, label: "\(command.key) read") {
            for _ in 0..<64 {
                let chunk = try await link.read(.fuzionBamKey)
                if case .complete(let text) = try reassembler.append(chunk) { return text }
            }
            throw BedError.malformedResponse("\(command.key) response never completed")
        }
        log(FrameEvent(.rx, channel: "BamKey", text: "\(command.key) -> \(text)"))
        return try FuzionResponse.parse(text, for: command)
    }

    /// Writes immediately, bypassing the queue and without reading a reply (STOP path).
    public func sendUrgent(_ command: FuzionCommand) async throws {
        guard let link, sessionID != nil else { throw BedError.notConnected }
        log(FrameEvent(.tx, channel: "BamKey", bytes: command.frame, text: "URGENT \(command.text)"))
        try await link.write(command.frame, to: .fuzionBamKey)
    }

    private func waitForHint(label: String) async throws {
        if hintArrived {
            hintArrived = false
            return
        }
        nextID &+= 1
        let id = nextID
        let timeout = timing.responseTimeout
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                hintWaiter = (id, continuation)
                Task { @MainActor in
                    try? await Task.sleep(for: timeout)
                    self.failHint(id: id, .timeout("\(label) response notification"))
                }
            }
        } onCancel: {
            Task { @MainActor in self.failHint(id: id, .cancelled) }
        }
    }

    private func failHint(id: UInt64? = nil, _ error: BedError) {
        guard let waiter = hintWaiter, id == nil || waiter.id == id else { return }
        hintWaiter = nil
        waiter.continuation.resume(throwing: error)
    }

    private func failHint(_ error: BedError) { failHint(id: nil, error) }

    // MARK: Typed operations

    public func systemConfiguration() async throws -> FuzionSystemConfiguration {
        try FuzionSystemConfiguration.parse(try await send(.systemConfiguration()))
    }

    public func sleepNumberControls(_ side: FuzionSide) async throws -> FuzionSleepNumberControls {
        let command = FuzionCommand.sleepNumberControls(side)
        return try FuzionSleepNumberControls.parse(try await send(command), command: command)
    }

    public func actuatorPosition(_ side: FuzionSide, actuator: FuzionActuator) async throws -> Int {
        let command = FuzionCommand.actuatorPosition(side, actuator: actuator)
        let tokens = try await send(command)
        guard tokens.count == 1 else { throw BedError.malformedResponse("ACTG returned \(tokens)") }
        return min(max(try FuzionResponse.int(tokens, at: 0, for: command), 0), 100)
    }

    public func underBedLight() async throws -> (level: FuzionLightLevel, timer: Int) {
        let command = FuzionCommand.underBedLightSettings()
        let tokens = try await send(command)
        guard tokens.count == 2, let level = FuzionLightLevel(rawValue: tokens[0].lowercased()) else {
            throw BedError.malformedResponse("UBLG returned \(tokens)")
        }
        return (level, max(0, try FuzionResponse.int(tokens, at: 1, for: command)))
    }
}

/// Runs `operation`, failing with `.timeout(label)` if it takes longer than `duration`.
@MainActor
func withTimeout<T>(_ duration: Duration, label: String, _ operation: @escaping @MainActor () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { @MainActor in try await operation() }
        group.addTask {
            try await Task.sleep(for: duration)
            throw BedError.timeout(label)
        }
        defer { group.cancelAll() }
        guard let result = try await group.next() else { throw BedError.timeout(label) }
        return result
    }
}
