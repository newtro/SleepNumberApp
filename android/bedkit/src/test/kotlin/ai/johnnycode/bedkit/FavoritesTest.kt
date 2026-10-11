package ai.johnnycode.bedkit

import ai.johnnycode.bedkit.driver.MCRDriver
import ai.johnnycode.bedkit.favorites.Favorite
import ai.johnnycode.bedkit.favorites.FavoriteApplier
import ai.johnnycode.bedkit.favorites.FavoriteApplier.Progress
import ai.johnnycode.bedkit.favorites.FavoriteApplier.Step
import ai.johnnycode.bedkit.favorites.FavoriteApplyException
import ai.johnnycode.bedkit.favorites.FavoriteBedOps
import ai.johnnycode.bedkit.favorites.FavoritesDocument
import ai.johnnycode.bedkit.favorites.PositionReading
import ai.johnnycode.bedkit.favorites.PumpReading
import ai.johnnycode.bedkit.favorites.StopOutcome
import ai.johnnycode.bedkit.mcr.FoundationAxis
import ai.johnnycode.bedkit.mcr.MCRSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** docs/FAVORITES-SPEC.md §1 and the §4 data-model vectors. */
class FavoritesDocumentTest {
    private fun fav(name: String) = Favorite.create(name, 30, 20, 45, id = name)

    @Test
    fun normalizationOnCreate() {
        val f = Favorite.create("", head = 140, foot = -12, sleepNumber = 47)
        assertEquals(listOf(100, 0, 45), listOf(f.head, f.foot, f.sleepNumber))
        assertEquals("Favorite", f.name)
        assertEquals("x".repeat(40), Favorite.create("  ${"x".repeat(50)} ", 0, 0, 5).name)
        assertEquals(listOf(5, 100, 50), listOf(0, 103, 48).map { Favorite.create("a", 0, 0, it).sleepNumber })
        assertEquals(36, Favorite.create("a", 0, 0, 5).id.length)
        assertEquals("2026-10-10T21:15:03Z", Favorite.create("a", 0, 0, 5, createdAt = Instant.parse("2026-10-10T21:15:03.456Z")).createdAt)
        // Edits normalize too.
        assertEquals(listOf(0, 100, 100), f.edited(head = -5, foot = 200, sleepNumber = 103).let { listOf(it.head, it.foot, it.sleepNumber) })
    }

    @Test
    fun fileValuesAreNormalizedWhenRead() {
        val text = """
            {"bedID":"64:DB:A0:9A:32:D3","left":[{"createdAt":"2026-10-10T21:15:03Z","foot":-3,"head":250,
             "id":"6F9619FF-8B86-D011-B42D-00CF4FC964FF","name":"  Reading  ","sleepNumber":42}],"right":[],"version":1}
        """.trimIndent()
        val doc = FavoritesDocument.decode(text)
        val f = doc.left.single()
        assertEquals(listOf(100, 0, 40), listOf(f.head, f.foot, f.sleepNumber))
        assertEquals("Reading", f.name)
        assertTrue(doc.right.isEmpty())
    }

    @Test
    fun newerVersionAndGarbageAreRejected() {
        assertFailsWith<BedError> { FavoritesDocument.decode("""{"bedID":"x","left":[],"right":[],"version":2}""") }
        assertFailsWith<BedError> { FavoritesDocument.decode("{not json") }
        assertFailsWith<BedError> { FavoritesDocument.decode("""{"bedID":"x","left":[{"name":"a"}],"right":[],"version":1}""") }
    }

    @Test
    fun reorderMatchesSwiftUiOnMove() {
        var doc = FavoritesDocument("bed").adding(fav("A"), BedSide.LEFT).adding(fav("B"), BedSide.LEFT).adding(fav("C"), BedSide.LEFT)
        doc = doc.moving(BedSide.LEFT, setOf(2), 0)
        assertEquals(listOf("C", "A", "B"), doc.left.map { it.name })
        doc = doc.moving(BedSide.LEFT, setOf(0), 3)
        assertEquals(listOf("A", "B", "C"), doc.left.map { it.name })
        assertEquals(listOf("B", "A", "C"), doc.movingUp("B", BedSide.LEFT).left.map { it.name })
        assertEquals(listOf("A", "C", "B"), doc.movingDown("B", BedSide.LEFT).left.map { it.name })
        assertEquals(doc, doc.movingUp("A", BedSide.LEFT))
        assertEquals(doc, doc.movingDown("C", BedSide.LEFT))
    }

    @Test
    fun addEditDeletePerSideAndRoundTrip() {
        var doc = FavoritesDocument("64:DB:A0:9A:32:D3")
        assertEquals("Left 1", doc.suggestedName(BedSide.LEFT))
        doc = doc.adding(fav("Read"), BedSide.LEFT).adding(fav("TV"), BedSide.RIGHT).adding(fav("Sleep"), BedSide.LEFT)
        assertEquals("Left 3", doc.suggestedName(BedSide.LEFT))
        assertEquals(listOf("Read", "Sleep"), doc.left.map { it.name })
        doc = doc.updating(doc.left[0].edited(name = "Reading", head = 55), BedSide.LEFT)
        assertEquals(listOf("Reading", "Sleep"), doc.left.map { it.name }, "replaced in place")
        assertEquals(55, doc.left[0].head)
        doc = doc.deleting("TV", BedSide.RIGHT)
        assertTrue(doc.right.isEmpty())

        val text = doc.encode()
        val keys = Regex("\"(\\w+)\"\\s*:").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("bedID", "left", "createdAt", "foot", "head", "id", "name", "sleepNumber"), keys.take(8), "sorted keys like iOS")
        assertEquals(doc, FavoritesDocument.decode(text))
        assertEquals("64DBA09A32D3.json", FavoritesDocument.fileName("64:DB:A0:9A:32:D3"))
    }
}

/** Ops double recording the spec's abstract commands. Positions step 10 per read toward the target. */
private class FakeOps(var head: Int = 0, var foot: Int = 50, var sleepNumber: Int = 40) : FavoriteBedOps {
    val commands = mutableListOf<String>()
    private var target: Pair<FoundationAxis, Int>? = null
    private var pendingSleepNumber: Int? = null
    private var busyReads = 0
    var externalBusyReads = 0
    var stallAt: Int? = null
    var startGate: CompletableDeferred<Unit>? = null
    var stopConfirms = true
    var faultAfterStart = false

    override suspend fun startMove(axis: FoundationAxis, position: Int) {
        commands += "move ${axis.title} $position"
        startGate?.await()
        target = axis to position
    }

    override suspend fun readPosition(axis: FoundationAxis): PositionReading {
        if (faultAfterStart && commands.isNotEmpty()) throw BedError.CommandFailed("${axis.title} actuator: motor current high")
        target?.let { (a, t) ->
            val current = if (a == FoundationAxis.HEAD) head else foot
            var next = if (t > current) minOf(t, current + 10) else maxOf(t, current - 10)
            stallAt?.let { s -> if ((current < s && next >= s) || (current > s && next <= s)) next = s }
            if (a == FoundationAxis.HEAD) head = next else foot = next
            if (next == t || next == stallAt) target = null
        }
        return PositionReading(if (axis == FoundationAxis.HEAD) head else foot, target != null)
    }

    override suspend fun stopMove(): Boolean {
        commands += "stop"
        target = null
        return stopConfirms
    }

    override suspend fun startSleepNumber(value: Int) {
        commands += "sleepNumber $value"
        startGate?.await()
        pendingSleepNumber = value
        busyReads = 2
    }

    override suspend fun readPump(): PumpReading {
        if (externalBusyReads > 0) return PumpReading(sleepNumber, true).also { externalBusyReads-- }
        pendingSleepNumber?.let {
            if (busyReads > 0) {
                busyReads--
                return PumpReading(sleepNumber, true)
            }
            sleepNumber = it
            pendingSleepNumber = null
        }
        return PumpReading(sleepNumber, false)
    }

    override suspend fun stopPump(): Boolean {
        commands += "pump-idle"
        pendingSleepNumber = null
        return stopConfirms
    }
}

/** docs/FAVORITES-SPEC.md §2 / §4 apply vectors, plus confirmed-stop semantics. */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteApplierTest {
    private val favorite = Favorite.create("Reading", head = 40, foot = 20, sleepNumber = 65)
    private fun TestScope.applier() = FavoriteApplier(clock = { testScheduler.currentTime })

    @Test
    fun planIsHeadFootFirmness() {
        assertEquals(listOf(Step.Head(40), Step.Foot(20), Step.Firmness(65)), FavoriteApplier.plan(favorite))
    }

    @Test
    fun commandsInOrder() = runTest {
        val ops = FakeOps(head = 0, foot = 50)
        val progress = mutableListOf<Progress>()
        applier().apply(favorite, ops, { progress += it })
        assertEquals(listOf("move head 40", "move foot 20", "sleepNumber 65"), ops.commands)
        assertEquals(listOf(40, 20, 65), listOf(ops.head, ops.foot, ops.sleepNumber))
        assertEquals(listOf(1, 2, 3), progress.filterIsInstance<Progress.StepStarted>().map { it.index })
        assertEquals(Progress.Finished, progress.last())
    }

    @Test
    fun valuesWithinToleranceSendNothing() = runTest {
        val ops = FakeOps(head = 41, foot = 20, sleepNumber = 65)
        val progress = mutableListOf<Progress>()
        applier().apply(favorite, ops, { progress += it })
        assertEquals(emptyList(), ops.commands)
        assertEquals(3, progress.filterIsInstance<Progress.Skipped>().size)
    }

    @Test
    fun cancelDuringHeadMoveSendsStopOnly() = runTest {
        val ops = FakeOps(head = 0)
        var outcome: StopOutcome? = null
        val job = launch { applier().apply(favorite, ops, {}, onStopped = { outcome = it }) }
        advanceTimeBy(1_500)
        job.cancelAndJoin()
        assertEquals(listOf("move head 40", "stop"), ops.commands)
        assertTrue(outcome is StopOutcome.Confirmed)
    }

    @Test
    fun cancelWhileTheMoveRequestIsInFlightStillStops() = runTest {
        val ops = FakeOps(head = 0)
        ops.startGate = CompletableDeferred()
        val job = launch { applier().apply(favorite, ops, {}) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("move head 40", "stop"), ops.commands)
    }

    @Test
    fun cancelAfterTheFirmnessSetEndsWithPumpIdle() = runTest {
        val ops = FakeOps(head = 40, foot = 20)
        val job = launch { applier().apply(favorite, ops, {}) }
        advanceTimeBy(1_500)
        job.cancelAndJoin()
        assertEquals(listOf("sleepNumber 65", "pump-idle"), ops.commands)
        assertEquals("pump-idle", ops.commands.last())
    }

    @Test
    fun cancelWhileTheFirmnessSetIsInFlightStillIdlesThePump() = runTest {
        val ops = FakeOps(head = 40, foot = 20)
        ops.startGate = CompletableDeferred()
        val job = launch { applier().apply(favorite, ops, {}) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("sleepNumber 65", "pump-idle"), ops.commands)
    }

    @Test
    fun stoppedShortFailsAndSkipsTheRest() = runTest {
        val ops = FakeOps(head = 0)
        ops.stallAt = 25
        val error = assertFailsWith<FavoriteApplyException> { applier().apply(favorite, ops, {}) }
        assertEquals(Step.Head(40), error.step)
        assertEquals(BedError.CommandFailed("head stopped at 25%, target 40%"), error.error)
        assertEquals(listOf("move head 40", "stop"), ops.commands, "confirmed stop after a failure; foot and firmness never sent")
        assertEquals(
            "Reading did not finish: The bed refused the command: head stopped at 25%, target 40% (at \"Head to 40%\"). The bed was told to stop.",
            error.userMessage,
        )
    }

    @Test
    fun actuatorFaultAfterTheHeadSetSendsStopAndReportsIt() = runTest {
        val ops = FakeOps(head = 0)
        ops.faultAfterStart = true
        val error = assertFailsWith<FavoriteApplyException> { applier().apply(favorite, ops, {}) }
        assertEquals(listOf("move head 40", "stop"), ops.commands)
        assertTrue(error.userMessage.endsWith("The bed was told to stop."))
    }

    @Test
    fun moveTimeoutSendsStop() = runTest {
        val ops = FakeOps(head = 0)
        val slow = FavoriteApplier(FavoriteApplier.Timing(moveTimeoutMs = 2_500), clock = { testScheduler.currentTime })
        val error = assertFailsWith<FavoriteApplyException> { slow.apply(Favorite.create("x", 100, 50, 40), ops, {}) }
        assertTrue(error.error is BedError.Timeout)
        assertEquals(listOf("move head 100", "stop"), ops.commands)
    }

    @Test
    fun unconfirmedStopIsReported() = runTest {
        val ops = FakeOps(head = 0)
        ops.stopConfirms = false
        var outcome: StopOutcome? = null
        val job = launch { applier().apply(favorite, ops, {}, onStopped = { outcome = it }) }
        advanceTimeBy(1_500)
        job.cancelAndJoin()
        assertTrue(outcome is StopOutcome.Unconfirmed)
        assertTrue(outcome!!.summary.contains("press STOP"))
    }

    @Test
    fun waitsForABusyPumpBeforeTheSet() = runTest {
        val ops = FakeOps(head = 40, foot = 20)
        ops.externalBusyReads = 3
        val progress = mutableListOf<Progress>()
        applier().apply(favorite, ops, { progress += it })
        assertTrue(Progress.WaitingForPump in progress)
        assertEquals(listOf("sleepNumber 65"), ops.commands)
    }
}

/** Frame-level favorites against the simulated MCR bed (spec §3.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteApplyMcrTest {
    private suspend fun TestScope.setup(): Triple<FakeLink, SimulatedMcrBed, MCRDriver> {
        val link = FakeLink(backgroundScope) { testScheduler.currentTime }
        val bed = SimulatedMcrBed(link)
        val driver = MCRDriver(
            link, {}, List(8) { it + 1 }, null, { SideMapping() },
            MCRSession.Timing(responseTimeoutMs = 120, retries = 3), clock = { testScheduler.currentTime },
        )
        link.onNotify = { driver.handleNotification(BedCharacteristic.MCR_NOTIFY, it) }
        driver.start()
        bed.keyWrites.clear()
        return Triple(link, bed, driver)
    }

    private val favorite = Favorite.create("Reading", head = 40, foot = 20, sleepNumber = 55)
    private fun TestScope.applier() = FavoriteApplier(clock = { testScheduler.currentTime })

    @Test
    fun leftFavoriteUsesLeftKeysAndPumpSide() = runTest {
        val (link, bed, driver) = setup()
        val before = link.writes.size
        applier().apply(favorite, driver.favoriteOps(BedSide.LEFT), {})
        // Left = wire side 1 -> MFUL / MFFL (foot sent with the favorite's own side), pump set side 1 [0, 55].
        assertEquals(listOf("MFUL40_0", "MFFL20_0"), bed.keyWrites)
        val frames = bed.frames().drop(before)
        val set = frames.single { it.command == 0x02 && it.functionCode == 0x11 }
        assertEquals(1, set.side)
        assertEquals(listOf(0, 55), set.payload)
        assertTrue(frames.indexOf(set) > frames.indexOfLast { it.command == 0x52 }, "firmness after both moves")
        assertTrue(frames.none { it.command == 0x42 && it.functionCode == 0x11 }, "no binary micro-adjust in a favorite")
        assertEquals(listOf(40, 20, 55), listOf(bed.head[1], bed.foot, bed.sleepNumber[1]))
        assertEquals(10, bed.head[0], "the other side's head is untouched")
        // Spec example frame shape: the head key write for this favorite.
        val headKey = frames.first { it.command == 0x52 }
        assertEquals("16 16 52 a1 b2 32 d3 52 a1 b2 1b 08 4d 46 55 4c 34 30 5f 30", headKey.encoded().copyOf(20).hex())
    }

    @Test
    fun cancelMidMoveSendsConfirmedStopForThatSideOnly() = runTest {
        val (_, bed, driver) = setup()
        var outcome: StopOutcome? = null
        val job = launch { applier().apply(favorite, driver.favoriteOps(BedSide.LEFT), {}, onStopped = { outcome = it }) }
        advanceTimeBy(1_500)
        assertTrue(bed.moving)
        job.cancelAndJoin()
        assertEquals(listOf("MFUL40_0", "MFHL110", "MFHL110"), bed.keyWrites)
        assertTrue(!bed.moving)
        assertTrue(outcome is StopOutcome.Confirmed)
        assertTrue(bed.frames().none { it.command == 0x02 && it.functionCode == 0x11 }, "no firmness after cancel")
        val last = bed.frames().last()
        assertEquals(listOf(0x42, 0x12), listOf(last.command, last.functionCode), "the confirming status read follows the stop")
    }

    @Test
    fun stopResendsWhileTheBedStillReportsMotion() = runTest {
        val (_, bed, driver) = setup()
        bed.ignoreStops = 2 // the first attempt (urgent + queued stop) is lost
        val job = launch { applier().apply(favorite, driver.favoriteOps(BedSide.LEFT), {}) }
        advanceTimeBy(1_500)
        job.cancelAndJoin()
        assertEquals(listOf("MFUL40_0", "MFHL110", "MFHL110", "MFHL110", "MFHL110"), bed.keyWrites)
        assertTrue(!bed.moving)
    }

    @Test
    fun cancelDuringFirmnessSendsPumpIdle() = runTest {
        val (_, bed, driver) = setup()
        bed.head[1] = 40; bed.foot = 20
        bed.pumpBusyReadsPerSet = 50
        val job = launch { applier().apply(favorite, driver.favoriteOps(BedSide.LEFT), {}) }
        advanceTimeBy(3_000)
        assertTrue(bed.frames().any { it.command == 0x02 && it.functionCode == 0x11 })
        job.cancelAndJoin()
        val last = bed.frames().takeLast(2)
        assertEquals(listOf(0x02, 0x02), listOf(last[0].command, last[0].functionCode), "ForceIdle, then the confirming pump read")
        assertEquals(40, bed.sleepNumber[1], "adjustment interrupted")
    }

    @Test
    fun stopBeforeAQueuedSetPositionKeepsItOffTheWire() = runTest {
        val (_, bed, driver) = setup()
        val ops = driver.favoriteOps(BedSide.LEFT) // captures the stop generation
        driver.stopAll() // STOP pressed
        bed.keyWrites.clear()
        assertEquals(BedError.Cancelled, assertFailsWith<BedError> { ops.startMove(FoundationAxis.HEAD, 40) })
        assertTrue(bed.keyWrites.none { it.startsWith("MFU") })
    }

    @Test
    fun nackedFootKeyFailsAtTheFootStepWithConfirmedStop() = runTest {
        val (_, bed, driver) = setup()
        bed.nackKeys += "MFFL"
        val error = assertFailsWith<FavoriteApplyException> { applier().apply(favorite, driver.favoriteOps(BedSide.LEFT), {}) }
        assertEquals(Step.Foot(20), error.step)
        assertTrue(error.error is BedError.CommandFailed)
        assertTrue(error.stop is StopOutcome.Confirmed)
        assertTrue(bed.keyWrites.contains("MFHL110"))
        assertTrue(bed.frames().none { it.command == 0x02 && it.functionCode == 0x11 })
    }
}
