package ai.johnnycode.bedkit.favorites

import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.driver.Clock
import ai.johnnycode.bedkit.driver.systemClock
import ai.johnnycode.bedkit.mcr.FoundationAxis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

// Port of BedKit/Sources/Common/Favorites.swift (apply sequence), per docs/FAVORITES-SPEC.md §2.

data class PositionReading(val position: Int, val moving: Boolean)
data class PumpReading(val sleepNumber: Int, val busy: Boolean)

/** Bed operations a favorite needs, bound to one side. Implemented for MCR and Fuzion in the drivers. */
interface FavoriteBedOps {
    /** Starts an absolute move ("set and go") of one axis on this side. */
    suspend fun startMove(axis: FoundationAxis, position: Int)

    /** Current position of the axis on this side, and whether the foundation reports motion. */
    suspend fun readPosition(axis: FoundationAxis): PositionReading

    /**
     * Confirmed stop of this side's movement: stop, re-read status, resend while still moving.
     * Returns true when the bed confirmed it stopped. Never throws.
     */
    suspend fun stopMove(): Boolean

    /** Starts a firmness change on this side. */
    suspend fun startSleepNumber(value: Int)

    /** Current Sleep Number of this side and whether the pump is busy. */
    suspend fun readPump(): PumpReading

    /** Confirmed pump idle: idle, re-read, resend while still busy. True when confirmed. Never throws. */
    suspend fun stopPump(): Boolean
}

/** Result of the stop sent when an apply was cancelled or failed after something started. */
sealed class StopOutcome {
    abstract val what: String

    data class Confirmed(override val what: String) : StopOutcome()
    data class Unconfirmed(override val what: String) : StopOutcome()

    val summary: String
        get() = when (this) {
            is Confirmed -> "$what stopped (confirmed by the bed)"
            is Unconfirmed -> "$what stop could not be confirmed — check the bed and press STOP"
        }
}

/** An apply failed at [step]; [stop] says what the stop did, when one was needed. */
class FavoriteApplyException(
    val favoriteName: String,
    val step: FavoriteApplier.Step?,
    val error: BedError,
    val stop: StopOutcome?,
) : Exception(error.message, error) {
    /** What actually happened, for the UI. */
    val userMessage: String
        get() = buildString {
            append(if (error == BedError.Cancelled) "\"$favoriteName\" was stopped" else "\"$favoriteName\" failed")
            step?.let { append(" at \"${it.title}\"") }
            if (error != BedError.Cancelled) append(": ${error.message}")
            stop?.let { append(". ${it.summary}") }
            append(".")
        }
}

/**
 * Runs a favorite: head, then foot, then firmness, one step at a time, following the official app's
 * set-and-go and firmness flows.
 *
 * Stops: once a move has been requested (or the firmness set issued — marked *before* the request is
 * awaited), cancellation or any error sends a confirmed stop for that step (move: STOP(side); firmness:
 * PUMP-IDLE), run under NonCancellable so the cancellation itself cannot cut it short. Completed steps
 * are not undone.
 */
class FavoriteApplier(
    val timing: Timing = Timing(),
    private val clock: Clock = systemClock,
) {
    sealed class Step {
        abstract val value: Int
        data class Head(override val value: Int) : Step()
        data class Foot(override val value: Int) : Step()
        data class Firmness(override val value: Int) : Step()

        val title: String
            get() = when (this) {
                is Head -> "Head to $value%"
                is Foot -> "Foot to $value%"
                is Firmness -> "Sleep Number $value"
            }
    }

    sealed class Progress {
        /** [index] is 1-based. */
        data class StepStarted(val index: Int, val of: Int, val step: Step) : Progress()
        data class Moving(val step: Step, val current: Int) : Progress()
        data class Skipped(val step: Step) : Progress()
        data object WaitingForPump : Progress()
        data class Adjusting(val step: Step, val current: Int) : Progress()
        data class Stopping(val step: Step) : Progress()
        data object Finished : Progress()
    }

    data class Timing(
        /** Delay between status reads while a move runs (the app's 1000 ms). */
        val movePollMs: Long = 1_000,
        /** Delay before confirming that a stationary axis has really stopped (the app's 500 ms). */
        val settleDelayMs: Long = 500,
        /** Delay after a firmness set before polling (the app's 1000 ms). */
        val pumpStartDelayMs: Long = 1_000,
        /** Delay between pump polls (the app's 1000 ms). */
        val pumpPollMs: Long = 1_000,
        /** Safety bound for one axis move. */
        val moveTimeoutMs: Long = 90_000,
        /** Safety bound for waiting for the pump (before and during the firmness step). */
        val pumpTimeoutMs: Long = 240_000,
    )

    companion object {
        /** The app counts a target as reached when the difference is below 3. */
        const val TOLERANCE = 3

        /** The fixed step order for a favorite. */
        fun plan(favorite: Favorite): List<Step> =
            listOf(Step.Head(favorite.head), Step.Foot(favorite.foot), Step.Firmness(favorite.sleepNumber))
    }

    /**
     * Applies [favorite] through [ops]. Throws [FavoriteApplyException] for a step failure (or a STOP
     * from elsewhere, `error == BedError.Cancelled`); rethrows CancellationException when the coroutine is
     * cancelled, after the stop. [onStopped] reports the stop outcome in both cases.
     */
    suspend fun apply(
        favorite: Favorite,
        ops: FavoriteBedOps,
        progress: (Progress) -> Unit,
        onStopped: (StopOutcome) -> Unit = {},
    ) {
        val steps = plan(favorite)
        var current: Step? = null
        var stop: StopOutcome? = null
        try {
            steps.forEachIndexed { index, step ->
                currentCoroutineContext().ensureActive()
                current = step
                progress(Progress.StepStarted(index + 1, steps.size, step))
                when (step) {
                    is Step.Head -> move(FoundationAxis.HEAD, step, ops, progress) { stop = it; onStopped(it) }
                    is Step.Foot -> move(FoundationAxis.FOOT, step, ops, progress) { stop = it; onStopped(it) }
                    is Step.Firmness -> firmness(step, ops, progress) { stop = it; onStopped(it) }
                }
            }
        } catch (e: BedError) {
            throw FavoriteApplyException(favorite.name, current, e, stop)
        }
        progress(Progress.Finished)
    }

    private suspend fun stopping(step: Step, progress: (Progress) -> Unit, what: String, action: suspend () -> Boolean): StopOutcome =
        withContext(NonCancellable) {
            progress(Progress.Stopping(step))
            if (action()) StopOutcome.Confirmed(what) else StopOutcome.Unconfirmed(what)
        }

    private suspend fun move(
        axis: FoundationAxis,
        step: Step,
        ops: FavoriteBedOps,
        progress: (Progress) -> Unit,
        stopped: (StopOutcome) -> Unit,
    ) {
        val target = step.value
        val start = ops.readPosition(axis)
        if (abs(start.position - target) < TOLERANCE && !start.moving) {
            progress(Progress.Skipped(step))
            return
        }
        var started = false
        try {
            // Marked before the request is awaited: a cancel while it is in flight still stops.
            started = true
            ops.startMove(axis, target)
            val begin = clock()
            while (true) {
                delay(timing.movePollMs)
                var reading = ops.readPosition(axis)
                progress(Progress.Moving(step, reading.position))
                if (abs(reading.position - target) < TOLERANCE) return
                if (!reading.moving) {
                    delay(timing.settleDelayMs)
                    reading = ops.readPosition(axis)
                    progress(Progress.Moving(step, reading.position))
                    if (abs(reading.position - target) < TOLERANCE) return
                    if (!reading.moving) {
                        throw BedError.CommandFailed("${axis.title} stopped at ${reading.position}%, target $target%")
                    }
                }
                if (clock() - begin >= timing.moveTimeoutMs) throw BedError.Timeout("${axis.title} did not reach $target%")
            }
        } catch (e: Throwable) {
            if (started && (e is CancellationException || e is BedError)) {
                stopped(stopping(step, progress, "Movement") { ops.stopMove() })
            }
            throw e
        }
    }

    private suspend fun firmness(
        step: Step,
        ops: FavoriteBedOps,
        progress: (Progress) -> Unit,
        stopped: (StopOutcome) -> Unit,
    ) {
        val target = step.value
        // The pump serves one side at a time: wait for any running adjustment to finish first.
        var pump = ops.readPump()
        if (pump.busy) {
            progress(Progress.WaitingForPump)
            val begin = clock()
            while (pump.busy) {
                if (clock() - begin >= timing.pumpTimeoutMs) throw BedError.Timeout("the pump stayed busy")
                delay(timing.pumpPollMs)
                pump = ops.readPump()
            }
        }
        if (pump.sleepNumber == target) {
            progress(Progress.Skipped(step))
            return
        }
        var started = false
        try {
            // Marked before the set is awaited, so a cancel while it is in flight still idles the pump.
            started = true
            ops.startSleepNumber(target)
            delay(timing.pumpStartDelayMs)
            val begin = clock()
            while (true) {
                pump = ops.readPump()
                progress(Progress.Adjusting(step, pump.sleepNumber))
                if (!pump.busy) return
                if (clock() - begin >= timing.pumpTimeoutMs) throw BedError.Timeout("Sleep Number did not finish adjusting")
                delay(timing.pumpPollMs)
            }
        } catch (e: Throwable) {
            if (started && (e is CancellationException || e is BedError)) {
                stopped(stopping(step, progress, "Firmness adjustment") { ops.stopPump() })
            }
            throw e
        }
    }
}
