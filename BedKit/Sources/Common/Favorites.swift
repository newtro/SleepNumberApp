import Foundation

// MARK: - Data model

/// A user-saved favorite for one sleeper: that side's head, the shared foot, and that side's
/// Sleep Number. Values are always valid: positions are clamped to 0...100 and the Sleep Number
/// is snapped to 5...100 in steps of 5, both on creation and when decoded from disk.
public struct BedFavorite: Codable, Identifiable, Equatable, Sendable {
    public var id: UUID
    public var name: String
    public private(set) var head: Int
    public private(set) var foot: Int
    public private(set) var sleepNumber: Int
    public var createdAt: Date

    public init(id: UUID = UUID(), name: String, head: Int, foot: Int, sleepNumber: Int, createdAt: Date = Date()) {
        self.id = id
        self.name = BedFavorite.cleanName(name)
        self.head = BedFavorite.clampPosition(head)
        self.foot = BedFavorite.clampPosition(foot)
        self.sleepNumber = SleepNumber.normalize(sleepNumber)
        self.createdAt = createdAt
    }

    public mutating func setHead(_ value: Int) { head = BedFavorite.clampPosition(value) }
    public mutating func setFoot(_ value: Int) { foot = BedFavorite.clampPosition(value) }
    public mutating func setSleepNumber(_ value: Int) { sleepNumber = SleepNumber.normalize(value) }

    public static func clampPosition(_ value: Int) -> Int { min(max(value, 0), 100) }

    /// Trims whitespace, caps at 40 characters, and falls back to "Favorite" when empty.
    public static func cleanName(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Favorite" : String(trimmed.prefix(40))
    }

    private enum CodingKeys: String, CodingKey { case id, name, head, foot, sleepNumber, createdAt }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try container.decode(UUID.self, forKey: .id),
                  name: try container.decode(String.self, forKey: .name),
                  head: try container.decode(Int.self, forKey: .head),
                  foot: try container.decode(Int.self, forKey: .foot),
                  sleepNumber: try container.decode(Int.self, forKey: .sleepNumber),
                  createdAt: try container.decode(Date.self, forKey: .createdAt))
    }
}

/// All favorites for one bed, per side, in display order. Stored as one JSON file per bed.
public struct FavoritesDocument: Codable, Equatable, Sendable {
    public static let currentVersion = 1

    public var version: Int
    /// The bed this document belongs to (the app uses the CoreBluetooth peripheral identifier).
    public var bedID: String
    public var left: [BedFavorite]
    public var right: [BedFavorite]

    public init(bedID: String, left: [BedFavorite] = [], right: [BedFavorite] = []) {
        version = FavoritesDocument.currentVersion
        self.bedID = bedID
        self.left = left
        self.right = right
    }

    public subscript(side: BedSide) -> [BedFavorite] {
        get { side == .left ? left : right }
        set { if side == .left { left = newValue } else { right = newValue } }
    }

    public mutating func add(_ favorite: BedFavorite, to side: BedSide) {
        self[side].append(favorite)
    }

    /// Replaces the favorite with the same id (rename / edit values). Returns false if absent.
    @discardableResult
    public mutating func update(_ favorite: BedFavorite, on side: BedSide) -> Bool {
        guard let index = self[side].firstIndex(where: { $0.id == favorite.id }) else { return false }
        self[side][index] = favorite
        return true
    }

    public mutating func delete(id: UUID, from side: BedSide) {
        self[side].removeAll { $0.id == id }
    }

    /// Same semantics as SwiftUI's `onMove`: moves the items at `offsets` to before `destination`.
    public mutating func move(on side: BedSide, fromOffsets offsets: IndexSet, toOffset destination: Int) {
        var items = self[side]
        let moving = offsets.sorted().map { items[$0] }
        let before = offsets.filter { $0 < destination }.count
        for index in offsets.sorted(by: >) { items.remove(at: index) }
        items.insert(contentsOf: moving, at: max(0, min(destination - before, items.count)))
        self[side] = items
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(self)
    }

    public static func decode(_ data: Data) throws -> FavoritesDocument {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let document = try decoder.decode(FavoritesDocument.self, from: data)
        guard document.version <= currentVersion else {
            throw BedError.malformedResponse("favorites file version \(document.version) is newer than this app")
        }
        return document
    }
}

// MARK: - Applying a favorite

/// Bed operations a favorite needs, bound to one side. Implemented for MCR and Fuzion below.
@MainActor
public protocol FavoriteBedOps: AnyObject {
    /// Starts an absolute move ("set and go") of one axis on this side.
    func startMove(_ axis: FoundationAxis, to position: Int) async throws
    /// Current position of the axis on this side, and whether the foundation reports motion.
    func readPosition(_ axis: FoundationAxis) async throws -> (position: Int, moving: Bool)
    /// Stops this side's movement (sent when an apply is cancelled or times out).
    func stopMove() async
    /// Starts a firmness change on this side.
    func startSleepNumber(_ value: Int) async throws
    /// Current Sleep Number of this side and whether the pump is busy.
    func readPump() async throws -> (sleepNumber: Int, busy: Bool)
    /// Interrupts a firmness change (sent when an apply is cancelled during the pump step).
    func stopPump() async
}

/// Runs a favorite: head, then foot, then firmness, one step at a time, following the official
/// app's set-and-go and firmness flows (docs/FAVORITES-SPEC.md).
@MainActor
public final class FavoriteApplier {
    public enum Step: Equatable, Sendable {
        case head(Int)
        case foot(Int)
        case firmness(Int)

        public var title: String {
            switch self {
            case .head(let value): return "Head to \(value)%"
            case .foot(let value): return "Foot to \(value)%"
            case .firmness(let value): return "Sleep Number \(value)"
            }
        }
    }

    public enum Progress: Equatable, Sendable {
        case step(index: Int, of: Int, Step)
        case moving(Step, current: Int)
        case skipped(Step)
        case waitingForPump
        case adjusting(Step, current: Int)
        case finished
    }

    public struct Timing: Sendable {
        /// Delay between status reads while a move runs (the app's 1000 ms).
        public var movePoll: Duration = .milliseconds(1000)
        /// Delay before confirming that a stationary axis has really stopped (the app's 500 ms).
        public var settleDelay: Duration = .milliseconds(500)
        /// Delay after a firmness set before polling (the app's 1000 ms).
        public var pumpStartDelay: Duration = .milliseconds(1000)
        /// Delay between pump polls (the app's 1000 ms).
        public var pumpPoll: Duration = .milliseconds(1000)
        /// Safety bound for one axis move.
        public var moveTimeout: Duration = .seconds(90)
        /// Safety bound for waiting for the pump (before and during the firmness step).
        public var pumpTimeout: Duration = .seconds(240)
        public init() {}
    }

    /// The app counts a target as reached when the difference is below 3.
    public static let tolerance = 3

    public let timing: Timing

    public init(timing: Timing = Timing()) {
        self.timing = timing
    }

    /// The fixed step order for a favorite.
    public nonisolated static func plan(_ favorite: BedFavorite) -> [Step] {
        [.head(favorite.head), .foot(favorite.foot), .firmness(favorite.sleepNumber)]
    }

    /// Applies `favorite` through `ops`. Throws `CancellationError`/`BedError.cancelled` when the
    /// task is cancelled (after sending the matching stop), or the first step error.
    public func apply(_ favorite: BedFavorite, ops: FavoriteBedOps,
                      progress: @escaping (Progress) -> Void) async throws {
        let steps = FavoriteApplier.plan(favorite)
        for (index, step) in steps.enumerated() {
            try Task.checkCancellation()
            progress(.step(index: index + 1, of: steps.count, step))
            switch step {
            case .head(let target): try await move(.head, to: target, step: step, ops: ops, progress: progress)
            case .foot(let target): try await move(.foot, to: target, step: step, ops: ops, progress: progress)
            case .firmness(let target): try await firmness(target, step: step, ops: ops, progress: progress)
            }
        }
        progress(.finished)
    }

    private func move(_ axis: FoundationAxis, to target: Int, step: Step, ops: FavoriteBedOps,
                      progress: @escaping (Progress) -> Void) async throws {
        let start = try await ops.readPosition(axis)
        if abs(start.position - target) < FavoriteApplier.tolerance && !start.moving {
            progress(.skipped(step))
            return
        }
        do {
            try await ops.startMove(axis, to: target)
            let clock = ContinuousClock()
            let deadline = clock.now + timing.moveTimeout
            while true {
                try await Task.sleep(for: timing.movePoll)
                var reading = try await ops.readPosition(axis)
                progress(.moving(step, current: reading.position))
                if abs(reading.position - target) < FavoriteApplier.tolerance { return }
                if !reading.moving {
                    try await Task.sleep(for: timing.settleDelay)
                    reading = try await ops.readPosition(axis)
                    progress(.moving(step, current: reading.position))
                    if abs(reading.position - target) < FavoriteApplier.tolerance { return }
                    if !reading.moving {
                        throw BedError.commandFailed("\(axis.rawValue) stopped at \(reading.position)%, target \(target)%")
                    }
                }
                if clock.now >= deadline {
                    await ops.stopMove()
                    throw BedError.timeout("\(axis.rawValue) did not reach \(target)%")
                }
            }
        } catch {
            if FavoriteApplier.isCancellation(error) { await ops.stopMove() }
            throw error
        }
    }

    private func firmness(_ target: Int, step: Step, ops: FavoriteBedOps,
                          progress: @escaping (Progress) -> Void) async throws {
        let clock = ContinuousClock()
        // The pump serves one side at a time: wait for any running adjustment to finish first.
        var pump = try await ops.readPump()
        if pump.busy {
            progress(.waitingForPump)
            let deadline = clock.now + timing.pumpTimeout
            while pump.busy {
                guard clock.now < deadline else { throw BedError.timeout("the pump stayed busy") }
                try await Task.sleep(for: timing.pumpPoll)
                pump = try await ops.readPump()
            }
        }
        if pump.sleepNumber == target {
            progress(.skipped(step))
            return
        }
        var started = false
        do {
            try await ops.startSleepNumber(target)
            started = true
            try await Task.sleep(for: timing.pumpStartDelay)
            let deadline = clock.now + timing.pumpTimeout
            while true {
                pump = try await ops.readPump()
                progress(.adjusting(step, current: pump.sleepNumber))
                if !pump.busy { return }
                guard clock.now < deadline else { throw BedError.timeout("Sleep Number did not finish adjusting") }
                try await Task.sleep(for: timing.pumpPoll)
            }
        } catch {
            if started && FavoriteApplier.isCancellation(error) { await ops.stopPump() }
            throw error
        }
    }

    public nonisolated static func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || (error as? BedError) == .cancelled
    }
}

// MARK: - Protocol adapters

/// MCR: absolute moves use the app's Sleep Expert keys (MFU*/MFF* = "<pos>_0"), progress comes
/// from foundation status, firmness uses pump cmd 0x11 and pump status.
@MainActor
public final class MCRFavoriteOps: FavoriteBedOps {
    private let session: MCRSession
    private let side: MCRWireSide
    /// Stop generation at creation: a STOP pressed during the apply cancels every later request.
    private let generation: UInt64

    public init(session: MCRSession, side: MCRWireSide) {
        self.session = session
        self.side = side
        generation = session.stopGeneration
    }

    public func startMove(_ axis: FoundationAxis, to position: Int) async throws {
        try await session.send(try MCRCommands.setPosition(side: side, axis: axis, position: position), generation: generation)
    }

    public func readPosition(_ axis: FoundationAxis) async throws -> (position: Int, moving: Bool) {
        let status = MCRFoundationStatus.parse
        let frame = try await session.send(try MCRCommands.readFoundationStatus(), generation: generation)
        let parsed = try status(frame)
        if parsed.needsHoming { throw BedError.commandFailed("the foundation needs homing; press Flat first") }
        if let fault = parsed.actuator(axis, side: side)?.fault {
            throw BedError.commandFailed("\(axis.rawValue) actuator: \(fault)")
        }
        return (parsed.position(axis, side: side), parsed.moving)
    }

    public func stopMove() async {
        try? await session.stopFoundation(sides: [side])
    }

    public func startSleepNumber(_ value: Int) async throws {
        try await session.send(try MCRCommands.setSleepNumber(side: side, value: SleepNumber.normalize(value)), generation: generation)
    }

    public func readPump() async throws -> (sleepNumber: Int, busy: Bool) {
        let status = try MCRPumpStatus.parse(try await session.send(try MCRCommands.readPumpStatus(), generation: generation))
        return (status.sleepNumber(side), status.busy)
    }

    public func stopPump() async {
        try? await session.stopPump()
    }
}

/// Fuzion: absolute moves use ACTS, progress ACTG + ACTM, firmness PSNS + SNCG.
@MainActor
public final class FuzionFavoriteOps: FavoriteBedOps {
    private let session: FuzionSession
    private let side: FuzionSide
    private let generation: UInt64

    public init(session: FuzionSession, side: FuzionSide) {
        self.session = session
        self.side = side
        generation = session.stopGeneration
    }

    public func startMove(_ axis: FoundationAxis, to position: Int) async throws {
        try await session.send(try .setActuatorTarget(side, actuator: FuzionActuator(axis), position: position), generation: generation)
    }

    public func readPosition(_ axis: FoundationAxis) async throws -> (position: Int, moving: Bool) {
        let actuator = FuzionActuator(axis)
        let movement = try FuzionMovementStatus.parse(try await session.send(.actuatorMovementStatus(), generation: generation))
        let command = FuzionCommand.actuatorPosition(side, actuator: actuator)
        let tokens = try await session.send(command, generation: generation)
        guard tokens.count == 1 else { throw BedError.malformedResponse("ACTG returned \(tokens)") }
        let position = BedFavorite.clampPosition(try FuzionResponse.int(tokens, at: 0, for: command))
        return (position, movement.moving(side, actuator))
    }

    public func stopMove() async {
        try? await session.sendUrgent(.haltAllActuators())
    }

    public func startSleepNumber(_ value: Int) async throws {
        try await session.send(try .startSleepNumberAdjustment(side, value: SleepNumber.normalize(value)), generation: generation)
    }

    public func readPump() async throws -> (sleepNumber: Int, busy: Bool) {
        let command = FuzionCommand.sleepNumberControls(side)
        let controls = try FuzionSleepNumberControls.parse(try await session.send(command, generation: generation), command: command)
        return (controls.user, controls.adjusting)
    }

    public func stopPump() async {
        try? await session.sendUrgent(.interruptSleepNumberAdjustment())
    }
}
