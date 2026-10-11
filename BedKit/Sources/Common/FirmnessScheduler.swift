import Foundation

/// The one-side-at-a-time firmness rule, as pure state so it can be tested.
///
/// The pump serves one chamber at a time and a new set interrupts a running one, so:
/// - a request is refused outright (no state change) when the controller says it is blocked;
/// - a new value for the side already adjusting interrupts and replaces it;
/// - a request while the pump is busy otherwise waits in a per-side queue;
/// - an adjustment ends only on an idle pump reading from a poll that started at least 1 s
///   after the bed acknowledged the set (the official app waits 1 s, then polls until idle).
public struct FirmnessScheduler: Equatable, Sendable {
    public enum Decision: Equatable, Sendable {
        case send(side: BedSide, value: Int, interrupt: Bool)
        case queued
        case refused(String)
    }

    public private(set) var adjustingSide: BedSide?
    public private(set) var queued: [BedSide: Int] = [:]
    public private(set) var requested: [BedSide: Int] = [:]
    public private(set) var pumpBusy = false
    public private(set) var acknowledgedAt: Date?

    public init() {}

    /// Handles a user request. `blockedReason` non-nil means the controller cannot send now
    /// (not connected, a hold or a favorite is running); nothing changes in that case.
    public mutating func request(_ value: Int, side: BedSide, blockedReason: String?) -> Decision {
        if let blockedReason { return .refused(blockedReason) }
        let normalized = SleepNumber.normalize(value)
        requested[side] = normalized
        if adjustingSide == side {
            return startSending(side: side, value: normalized, interrupt: true)
        }
        if pumpBusy || adjustingSide != nil {
            queued[side] = normalized
            return .queued
        }
        return startSending(side: side, value: normalized, interrupt: false)
    }

    private mutating func startSending(side: BedSide, value: Int, interrupt: Bool) -> Decision {
        queued[side] = nil
        adjustingSide = side
        pumpBusy = true
        acknowledgedAt = nil
        return .send(side: side, value: value, interrupt: interrupt)
    }

    /// The bed acknowledged the set for `side`.
    public mutating func acknowledged(side: BedSide, at date: Date = Date()) {
        if adjustingSide == side { acknowledgedAt = date }
    }

    /// The set for `side` failed or was cancelled before the bed took it.
    public mutating func sendFailed(side: BedSide) {
        if adjustingSide == side {
            adjustingSide = nil
            acknowledgedAt = nil
        }
        requested[side] = nil
    }

    /// Applies a pump reading. Returns the side whose adjustment just finished, if any.
    @discardableResult
    public mutating func pumpReading(busy: Bool, pollStarted: Date) -> BedSide? {
        pumpBusy = busy
        guard let side = adjustingSide else { return nil }
        guard !busy, let ack = acknowledgedAt, pollStarted.timeIntervalSince(ack) >= 1 else { return nil }
        adjustingSide = nil
        acknowledgedAt = nil
        requested[side] = nil
        return side
    }

    /// The next queued request to send once the pump is idle (Left first), marking it as sending.
    public mutating func nextQueued() -> Decision? {
        guard !queued.isEmpty, !pumpBusy, adjustingSide == nil else { return nil }
        let side: BedSide = queued[.left] != nil ? .left : .right
        guard let value = queued[side] else { return nil }
        return startSending(side: side, value: value, interrupt: false)
    }

    public mutating func cancelQueued(side: BedSide) {
        queued[side] = nil
        requested[side] = nil
    }

    /// Drops queued and requested values (STOP, disconnect, a favorite taking over the pump).
    /// Keeps `adjustingSide` unless `includingRunning`, so a running change is still tracked.
    public mutating func clear(includingRunning: Bool) {
        queued.removeAll()
        requested = requested.filter { includingRunning ? false : $0.key == adjustingSide }
        if includingRunning {
            adjustingSide = nil
            acknowledgedAt = nil
        }
    }

    /// Marks the pump busy from another flow (for example a favorite's firmness step).
    public mutating func setPumpBusy(_ busy: Bool) { pumpBusy = busy }
}
