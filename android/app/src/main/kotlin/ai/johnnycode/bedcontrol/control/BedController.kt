package ai.johnnycode.bedcontrol.control

import ai.johnnycode.bedcontrol.BuildConfig
import ai.johnnycode.bedcontrol.ble.AdapterState
import ai.johnnycode.bedcontrol.ble.BleManager
import ai.johnnycode.bedcontrol.ble.ScannedDevice
import ai.johnnycode.bedkit.BedDetector
import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.BedPreset
import ai.johnnycode.bedkit.BedProtocol
import ai.johnnycode.bedkit.BedSide
import ai.johnnycode.bedkit.MoveDirection
import ai.johnnycode.bedkit.SideMapping
import ai.johnnycode.bedkit.SleepNumber
import ai.johnnycode.bedkit.driver.BedDriver
import ai.johnnycode.bedkit.driver.BedReading
import ai.johnnycode.bedkit.driver.FuzionDriver
import ai.johnnycode.bedkit.driver.MCRDriver
import ai.johnnycode.bedkit.driver.PresetOption
import ai.johnnycode.bedkit.favorites.Favorite
import ai.johnnycode.bedkit.favorites.FavoriteApplier
import ai.johnnycode.bedkit.favorites.FavoriteApplyException
import ai.johnnycode.bedkit.favorites.FavoritesDocument
import ai.johnnycode.bedkit.favorites.StopOutcome
import ai.johnnycode.bedkit.mcr.FoundationAxis
import ai.johnnycode.bedkit.toUnsignedList
import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * App state and behaviour (port of BedControl/Control/BedController.swift): connection policy,
 * protocol drivers, polling, press-and-hold movement, the one-side-at-a-time firmness queue, favorites
 * and STOP. Main thread only; Compose observes the snapshot state directly.
 */
class BedController(app: Application) : AndroidViewModel(app) {
    sealed class Phase {
        data class BluetoothUnavailable(val message: String) : Phase()
        data object NoBed : Phase()
        data class Searching(val name: String) : Phase()
        data class Connecting(val step: String) : Phase()
        data object Ready : Phase()
        data class Failed(val message: String) : Phase()
    }

    data class HoldKey(val axis: FoundationAxis, val side: BedSide, val direction: MoveDirection)

    @Serializable
    data class SavedBed(val address: String, val name: String, val lastProtocol: BedProtocol? = null)

    data class FavoriteRun(
        val side: BedSide,
        val favorite: Favorite,
        val status: String,
        val stepIndex: Int,
        val stepCount: Int,
        val stopping: Boolean = false,
    )

    private val prefs = app.getSharedPreferences("bedcontrol", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val favoritesStore = FavoritesStore(app.filesDir)

    val log = DiagnosticsLog()
    val ble = BleManager(app, log, viewModelScope)

    // Observable state
    var phase by mutableStateOf<Phase>(Phase.NoBed)
        private set
    var savedBed by mutableStateOf<SavedBed?>(null)
        private set
    var protocolKind by mutableStateOf<BedProtocol?>(null)
        private set
    val firmness = mutableStateMapOf<BedSide, Int>()
    var pumpBusy by mutableStateOf(false)
        private set

    /** Side whose firmness change this app started and is still running. */
    var adjustingSide by mutableStateOf<BedSide?>(null)
        private set
    val queuedFirmness = mutableStateMapOf<BedSide, Int>()
    val requestedFirmness = mutableStateMapOf<BedSide, Int>()
    val head = mutableStateMapOf<BedSide, Int>()
    val foot = mutableStateMapOf<BedSide, Int>()
    var lightOn by mutableStateOf<Boolean?>(null)
        private set
    var moving by mutableStateOf(false)
        private set
    val activePreset = mutableStateMapOf<BedSide, BedPreset>()
    var activeHold by mutableStateOf<HoldKey?>(null)
        private set
    var lastUpdated by mutableStateOf<Long?>(null)
        private set
    var banner by mutableStateOf<String?>(null)

    /** Saved favorites for the current bed. */
    var favorites by mutableStateOf(FavoritesDocument("none"))
        private set

    /** The favorite being applied, with live progress. */
    var applyingFavorite by mutableStateOf<FavoriteRun?>(null)
        private set

    /** Number of app commands (presets, firmness sets, light) still in flight. */
    var commandsInFlight by mutableStateOf(0)
        private set

    var swapSides by mutableStateOf(prefs.getBoolean(KEY_SWAP, false))
        private set

    /** Which sleeper's side the shared foot hold buttons command (the firmware drives the one motor). */
    var footSide by mutableStateOf(BedSide.entries.firstOrNull { it.name == prefs.getString(KEY_FOOT_SIDE, null) } ?: BedSide.LEFT)
        private set

    private var driver: BedDriver? = null
    private var pollJob: Job? = null
    private var holdJob: Job? = null
    private var holdId = 0
    private var favoriteJob: Job? = null
    private val commandJobs = mutableMapOf<Int, Job>()
    private var nextCommandId = 0
    private var reconnectJob: Job? = null
    private var pollCycle = 0
    private var firmnessAckAt: Long? = null
    private var autoReconnect = true
    private var pendingConnectName: String? = null

    val mapping: SideMapping get() = SideMapping(swapSides)
    val isReady: Boolean get() = phase == Phase.Ready
    val presetOptions: List<PresetOption>
        get() = driver?.presetOptions ?: listOf(
            PresetOption.Preset(BedPreset.FAVORITE), PresetOption.Preset(BedPreset.FLAT),
            PresetOption.Preset(BedPreset.ZERO_G), PresetOption.Preset(BedPreset.SNORE), PresetOption.PartnerSnore,
        )
    val hasUnderBedLight: Boolean get() = driver?.hasUnderBedLight ?: true
    val diagnostics: List<Pair<String, String>>
        get() = (driver?.diagnostics ?: emptyList()) +
            listOf("BLE MTU" to ble.mtu.toString(), "Bond" to ble.bondState.ifEmpty { "unknown" })

    init {
        savedBed = prefs.getString(KEY_SAVED_BED, null)?.let { runCatching { json.decodeFromString(SavedBed.serializer(), it) }.getOrNull() }
        ble.onAdapterChange = { bluetoothChanged(it) }
        ble.onReady = { linkReady(it) }
        ble.onDisconnect = { linkLost(it) }
        ble.onNotification = { c, data -> driver?.handleNotification(c, data) }
        ble.onLinkStall = {
            // The link stays open until this returns: get the stop out before it is dropped.
            val d = driver
            if (d != null && (activeHold != null || moving || pumpBusy || applyingFavorite != null)) {
                holdJob?.cancel()
                favoriteJob?.cancel()
                runCatching { d.stopAll() }
            }
        }
        phase = savedBed?.let { Phase.Searching(it.name) } ?: Phase.NoBed
        reloadFavorites()
    }

    /** Called once the Bluetooth permissions are granted. */
    fun permissionsGranted() {
        ble.refreshAdapterState()
        bluetoothChanged(ble.adapterState)
    }

    /** The official app's persisted random 64-bit MCR client ID, created once. */
    private val mcrClientId: List<Int>
        get() {
            prefs.getString(KEY_CLIENT_ID, null)?.let { stored ->
                ai.johnnycode.bedkit.hexToBytes(stored)?.takeIf { it.size == 8 }?.let { return it.toUnsignedList() }
            }
            val random = SecureRandom()
            var id: ByteArray
            do {
                id = ByteArray(8).also { random.nextBytes(it) }
            } while (id.all { it.toInt() == 0 })
            prefs.edit().putString(KEY_CLIENT_ID, id.joinToString("") { "%02x".format(it) }).apply()
            return id.toUnsignedList()
        }

    fun updateSwapSides(value: Boolean) {
        swapSides = value
        prefs.edit().putBoolean(KEY_SWAP, value).apply()
        log.info("Settings", "swap sides = $value")
    }

    fun updateFootSide(side: BedSide) {
        footSide = side
        prefs.edit().putString(KEY_FOOT_SIDE, side.name).apply()
    }

    // MARK: Connection policy

    private fun bluetoothChanged(state: AdapterState) {
        when (state) {
            AdapterState.ON -> {
                val bed = savedBed
                if (bed != null && driver == null && autoReconnect && phase !is Phase.Connecting) connect(bed.address, bed.name)
                else if (bed == null) phase = Phase.NoBed
            }
            AdapterState.OFF -> phase = Phase.BluetoothUnavailable("Bluetooth is off. Turn it on to control the bed.")
            AdapterState.UNSUPPORTED -> phase = Phase.BluetoothUnavailable("This device does not support Bluetooth LE.")
            AdapterState.UNKNOWN -> Unit
        }
    }

    /** Connects to a device picked from the scan list and remembers it. */
    fun choose(device: ScannedDevice) {
        autoReconnect = false
        viewModelScope.launch {
            stopMotionBeforeLeaving()
            tearDownDriver()
            ble.disconnect()
            save(SavedBed(device.address, device.name, device.classification.likelyProtocol))
            autoReconnect = true
            connect(device.address, device.name)
        }
    }

    fun retry() {
        val bed = savedBed ?: run { phase = Phase.NoBed; return }
        autoReconnect = true
        banner = null
        connect(bed.address, bed.name)
    }

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        viewModelScope.launch {
            stopMotionBeforeLeaving()
            tearDownDriver()
            ble.disconnect()
            phase = if (savedBed == null) Phase.NoBed else Phase.Failed("Disconnected. Tap Retry to reconnect.")
        }
    }

    fun forgetBed() {
        autoReconnect = false
        reconnectJob?.cancel()
        viewModelScope.launch {
            stopMotionBeforeLeaving()
            tearDownDriver()
            ble.disconnect()
            savedBed = null
            prefs.edit().remove(KEY_SAVED_BED).apply()
            reloadFavorites()
            phase = Phase.NoBed
        }
    }

    /** Sends (and waits up to 3 s for) the stop sequence before the link is dropped, if anything may move. */
    private suspend fun stopMotionBeforeLeaving() {
        val d = driver ?: return
        if (activeHold == null && !moving && !pumpBusy && applyingFavorite == null) return
        holdJob?.cancel()
        favoriteJob?.cancel()
        withTimeoutOrNull(3_000) { runCatching { d.stopAll() } }
    }

    private fun connect(address: String, name: String, waitForDevice: Boolean = false) {
        reconnectJob?.cancel()
        if (ble.adapterState != AdapterState.ON) return
        pendingConnectName = name
        phase = Phase.Searching(name)
        ble.connect(address, name, waitForDevice)
    }

    private fun save(bed: SavedBed) {
        val changed = savedBed?.address != bed.address
        savedBed = bed
        if (changed) reloadFavorites()
        prefs.edit().putString(KEY_SAVED_BED, json.encodeToString(SavedBed.serializer(), bed)).apply()
    }

    private fun linkReady(kind: BedProtocol) {
        protocolKind = kind
        val name = savedBed?.name ?: pendingConnectName ?: "bed"
        phase = Phase.Connecting(if (kind == BedProtocol.MCR) "Binding with $name…" else "Authenticating with $name…")
        tearDownDriver()
        val logger: (ai.johnnycode.bedkit.FrameEvent) -> Unit = { log.append(it) }
        val newDriver: BedDriver = when (kind) {
            BedProtocol.MCR -> MCRDriver(
                ble, logger, mcrClientId,
                macFallback = BedDetector.mcrAddress(savedBed?.address ?: name) ?: BedDetector.mcrAddress(name),
                mapping = { mapping },
            )
            BedProtocol.FUZION -> FuzionDriver(ble, logger, mapping = { mapping })
        }
        driver = newDriver
        savedBed?.let { if (it.lastProtocol != kind) save(it.copy(lastProtocol = kind)) }
        viewModelScope.launch {
            try {
                newDriver.start()
                if (driver !== newDriver) return@launch
                phase = Phase.Ready
                banner = null
                log.info("App", "ready (${kind.displayName})")
                startPolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (driver !== newDriver) return@launch
                val error = e as? BedError ?: BedError.Transport(e.message ?: e.toString())
                log.error("App", "start failed: ${error.message}")
                failConnection(error)
            }
        }
    }

    /** Errors that need the user (pairing, connection limit) stop auto-reconnect. */
    private fun failConnection(error: BedError) {
        val needsUser = error is BedError.BondRequired || error is BedError.PairingRemoved || error is BedError.ConnectionLimit ||
            error is BedError.AuthenticationRejected || error is BedError.UnsupportedCommand
        tearDownDriver()
        ble.disconnect()
        phase = Phase.Failed(error.message)
        if (needsUser) autoReconnect = false else scheduleReconnect(5_000)
    }

    private fun linkLost(error: BedError?) {
        val wasReady = phase == Phase.Ready
        tearDownDriver()
        log.info("App", "link lost${error?.let { ": ${it.message}" } ?: ""}")
        if (error is BedError.PairingRemoved || error is BedError.BondRequired) {
            phase = Phase.Failed(error.message)
            autoReconnect = false
            return
        }
        val bed = savedBed
        if (bed == null || !autoReconnect) {
            phase = Phase.Failed(error?.message ?: "Disconnected.")
            return
        }
        if (wasReady) {
            // Wait for the bed to come back in range (Android's background auto-connect).
            connect(bed.address, bed.name, waitForDevice = true)
        } else {
            phase = Phase.Failed(error?.message ?: "Could not connect.")
            scheduleReconnect(5_000)
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            val bed = savedBed
            if (autoReconnect && driver == null && bed != null) connect(bed.address, bed.name)
        }
    }

    private fun tearDownDriver() {
        favoriteJob?.cancel()
        favoriteJob = null
        applyingFavorite = null
        holdJob?.cancel()
        holdJob = null
        activeHold = null
        pollJob?.cancel()
        pollJob = null
        cancelCommands()
        driver?.reset()
        driver = null
        queuedFirmness.clear()
        adjustingSide = null
    }

    // MARK: Polling

    private fun startPolling() {
        pollJob?.cancel()
        pollCycle = 0
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (activeHold == null && applyingFavorite == null) {
                    refreshNow()
                    processFirmnessQueue()
                }
                // While an adjustment runs, poll every second like the app; otherwise relax.
                val interval = when {
                    adjustingSide != null || pumpBusy -> 1_000L
                    moving || queuedFirmness.isNotEmpty() -> 2_000L
                    protocolKind == BedProtocol.FUZION -> 10_000L
                    else -> 5_000L
                }
                delay(interval)
            }
        }
    }

    /** One poll. Safe to call any time (it never moves the bed). */
    suspend fun refreshNow() {
        val d = driver ?: return
        if (phase != Phase.Ready) return
        pollCycle++
        val started = System.currentTimeMillis()
        try {
            apply(d.refresh(includeLight = pollCycle % 3 == 1), started)
            lastUpdated = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BedError.Cancelled) {
        } catch (e: BedError) {
            log.error("App", "poll failed: ${e.message}")
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    private fun apply(reading: BedReading, pollStarted: Long? = null) {
        reading.firmness.forEach { (side, value) -> firmness[side] = value }
        reading.pumpBusy?.let { busy ->
            pumpBusy = busy
            // The app waits 1 s after the bed acknowledges a set, then polls until idle. Only a poll that
            // started at least 1 s after the acknowledgement may end the adjustment.
            val ack = firmnessAckAt
            val settled = when {
                adjustingSide == null -> true
                ack != null && pollStarted != null -> pollStarted - ack >= 1_000
                else -> false
            }
            if (!busy && settled) {
                adjustingSide?.let {
                    log.info("App", "${it.title} firmness adjustment finished")
                    requestedFirmness.remove(it)
                }
                adjustingSide = null
            }
        }
        reading.head.forEach { (side, value) -> head[side] = value }
        reading.foot.forEach { (side, value) -> foot[side] = value }
        reading.lightOn?.let { lightOn = it }
        reading.moving?.let { moving = it }
        reading.activePreset.forEach { (side, preset) -> activePreset[side] = preset }
    }

    // MARK: Commands

    private val gateState: CommandGates.State
        get() = CommandGates.State(
            connected = driver != null && phase == Phase.Ready,
            holdActive = activeHold != null,
            applyingFavorite = applyingFavorite != null,
            commandsInFlight = commandsInFlight,
            adjustingFirmness = adjustingSide != null,
            moving = moving,
        )

    /** Why a new command cannot run now, or null if it can. Checked before any state is changed. */
    private fun commandBlocker(): String? = CommandGates.commandBlocker(gateState)

    /** Runs a command as a tracked job so STOP can cancel it. Callers check [commandBlocker] first. */
    private fun perform(label: String, onFailure: () -> Unit = {}, action: suspend (BedDriver) -> Unit) {
        commandBlocker()?.let { banner = it; onFailure(); return }
        val d = driver ?: return
        val id = ++nextCommandId
        commandsInFlight = commandJobs.size + 1
        commandJobs[id] = viewModelScope.launch {
            try {
                action(d)
            } catch (e: CancellationException) {
                onFailure()
                throw e
            } catch (e: BedError.Cancelled) {
                onFailure()
                log.info("App", "$label cancelled")
            } catch (e: Exception) {
                onFailure()
                val message = e.message ?: e.toString()
                log.error("App", "$label failed: $message")
                banner = "$label failed: $message"
            } finally {
                commandJobs.remove(id)
                commandsInFlight = commandJobs.size
            }
        }
    }

    private fun cancelCommands() {
        commandJobs.values.forEach { it.cancel() }
        commandJobs.clear()
        commandsInFlight = 0
    }

    fun activate(option: PresetOption, side: BedSide) {
        val (preset, target) = when (option) {
            is PresetOption.Preset -> option.preset to side
            PresetOption.PartnerSnore -> BedPreset.SNORE to side.other
        }
        perform("${option.title} (${side.title})") { d ->
            d.activate(preset, target)
            moving = true
            activePreset.remove(target)
        }
    }

    fun toggleLight() {
        val target = !(lightOn ?: false)
        perform(if (target) "Light on" else "Light off") { d ->
            d.setLight(target)
            lightOn = target
        }
    }

    /**
     * Requests a firmness change. The pump serves one side at a time, so a request waits while the pump
     * is busy with the other side (or with an adjustment this app did not start). A new value for the
     * side already adjusting interrupts and replaces it, as the app does.
     */
    fun setFirmness(value: Int, side: BedSide) {
        commandBlocker()?.let { banner = it; return }
        val normalized = SleepNumber.normalize(value)
        requestedFirmness[side] = normalized
        when {
            adjustingSide == side -> sendFirmness(normalized, side, interrupt = true)
            pumpBusy || adjustingSide != null -> {
                queuedFirmness[side] = normalized
                log.info("App", "${side.title} firmness $normalized queued until the pump is idle")
            }
            else -> sendFirmness(normalized, side, interrupt = false)
        }
    }

    private fun sendFirmness(value: Int, side: BedSide, interrupt: Boolean) {
        if (commandBlocker() != null) return
        queuedFirmness.remove(side)
        adjustingSide = side
        pumpBusy = true
        firmnessAckAt = null
        perform("Firmness $value (${side.title})", onFailure = { if (adjustingSide == side) adjustingSide = null }) { d ->
            d.setFirmness(value, side, interrupt)
            firmnessAckAt = System.currentTimeMillis()
        }
    }

    private fun processFirmnessQueue() {
        if (queuedFirmness.isEmpty() || pumpBusy || adjustingSide != null || applyingFavorite != null) return
        val side = if (queuedFirmness.containsKey(BedSide.LEFT)) BedSide.LEFT else BedSide.RIGHT
        queuedFirmness[side]?.let { sendFirmness(it, side, interrupt = false) }
    }

    fun cancelQueuedFirmness(side: BedSide) {
        queuedFirmness.remove(side)
        requestedFirmness.remove(side)
    }

    // MARK: Press and hold

    fun beginHold(axis: FoundationAxis, side: BedSide, direction: MoveDirection) {
        val d = driver
        if (d == null || phase != Phase.Ready) {
            banner = "Not connected to the bed."
            return
        }
        if (activeHold != null) return
        if (applyingFavorite != null) {
            banner = "A favorite is being applied. Press STOP to cancel it first."
            return
        }
        activeHold = HoldKey(axis, side, direction)
        moving = true
        val id = ++holdId
        log.info("App", "hold ${axis.title} ${side.title} ${direction.name.lowercase()}")
        holdJob = viewModelScope.launch {
            try {
                d.hold(axis, side, direction) { apply(it) }
            } catch (e: CancellationException) {
                // released
            } catch (e: BedError.Cancelled) {
            } catch (e: Exception) {
                log.error("App", "hold failed: ${e.message}")
                banner = "Move failed: ${e.message}"
            }
            // Always release, even though this job may be cancelled: the stop must go out.
            val reading = withContext(NonCancellable) { d.releaseHold(axis, side) }
            reading?.let { apply(it) }
            if (holdId == id) {
                activeHold = null
                holdJob = null
            }
        }
    }

    fun endHold() {
        if (activeHold == null) return
        log.info("App", "hold released")
        holdJob?.cancel()
    }

    // MARK: STOP

    fun stopEverything() {
        log.info("App", "STOP pressed")
        favoriteJob?.cancel()
        holdJob?.cancel()
        cancelCommands()
        queuedFirmness.clear()
        requestedFirmness.clear()
        adjustingSide = null
        val d = driver ?: return
        viewModelScope.launch {
            try {
                d.stopAll()
            } catch (e: BedError.Cancelled) {
            } catch (e: BedError) {
                banner = "STOP could not be sent: ${e.message}"
            }
            delay(500)
            refreshNow()
        }
    }

    /** The app left the foreground: never leave a hold or a favorite running unattended. */
    fun onBackground() {
        endHold()
        if (applyingFavorite != null) {
            log.info("App", "app went to the background; cancelling the favorite")
            cancelFavorite()
        }
    }

    // MARK: Favorites

    private fun reloadFavorites() {
        val bedId = savedBed?.address ?: "none"
        val loaded = favoritesStore.load(bedId)
        favorites = loaded.document
        loaded.problem?.let {
            banner = it
            log.error("Favorites", it)
        }
    }

    private fun persistFavorites() {
        try {
            favoritesStore.save(favorites)
        } catch (e: Exception) {
            banner = "Could not save favorites: ${e.message}"
            log.error("Favorites", "save failed: ${e.message}")
        }
    }

    /** A new favorite pre-filled with live values read from the bed (last known values if the read fails). */
    suspend fun captureFavorite(side: BedSide): Favorite {
        refreshNow()
        return Favorite.create(
            name = favorites.suggestedName(side),
            head = head[side] ?: 0,
            foot = foot[side] ?: foot[side.other] ?: 0,
            sleepNumber = firmness[side] ?: 50,
        )
    }

    fun saveFavorite(favorite: Favorite, side: BedSide) {
        favorites = if (favorites[side].any { it.id == favorite.id }) favorites.updating(favorite, side) else favorites.adding(favorite, side)
        persistFavorites()
        val saved = favorites[side].first { it.id == favorite.id }
        log.info("Favorites", "saved ${side.title} \"${saved.name}\" head ${saved.head} foot ${saved.foot} SN ${saved.sleepNumber}")
    }

    fun deleteFavorite(id: String, side: BedSide) {
        favorites = favorites.deleting(id, side)
        persistFavorites()
    }

    fun moveFavoriteUp(id: String, side: BedSide) {
        favorites = favorites.movingUp(id, side)
        persistFavorites()
    }

    fun moveFavoriteDown(id: String, side: BedSide) {
        favorites = favorites.movingDown(id, side)
        persistFavorites()
    }

    /** Why Apply is unavailable right now, or null. */
    fun applyBlocker(): String? = CommandGates.applyBlocker(gateState)

    /** Applies a favorite: head, foot, then firmness, with progress. STOP or Cancel stops it (confirmed). */
    fun applyFavorite(favorite: Favorite, side: BedSide) {
        applyBlocker()?.let { banner = it; return }
        val d = driver ?: return
        // Queued firmness requests would race the favorite's own firmness step: drop them first.
        if (queuedFirmness.isNotEmpty()) log.info("Favorites", "dropping queued firmness requests")
        queuedFirmness.keys.toList().forEach { requestedFirmness.remove(it) }
        queuedFirmness.clear()
        val ops = d.favoriteOps(side)
        val applier = FavoriteApplier()
        applyingFavorite = FavoriteRun(side, favorite, "Starting…", 0, 3)
        log.info("Favorites", "apply ${side.title} \"${favorite.name}\": head ${favorite.head}, foot ${favorite.foot}, SN ${favorite.sleepNumber}")
        var stop: StopOutcome? = null
        var lastStep = ""
        favoriteJob = viewModelScope.launch {
            try {
                applier.apply(favorite, ops, { progress ->
                    if (progress is FavoriteApplier.Progress.StepStarted) lastStep = progress.step.title
                    handle(progress, side)
                }, onStopped = { stop = it })
                log.info("Favorites", "applied \"${favorite.name}\"")
            } catch (e: CancellationException) {
                val message = "\"${favorite.name}\" was cancelled${if (lastStep.isNotEmpty()) " during \"$lastStep\"" else ""}" +
                    (stop?.let { ". ${it.summary}." } ?: ". Nothing was moving yet.")
                log.info("Favorites", message)
                banner = message
            } catch (e: FavoriteApplyException) {
                log.error("Favorites", e.userMessage)
                banner = e.userMessage
            } catch (e: Exception) {
                log.error("Favorites", "apply failed: ${e.message}")
                banner = "\"${favorite.name}\" failed: ${e.message}"
            } finally {
                withContext(NonCancellable) {
                    applyingFavorite = null
                    favoriteJob = null
                    moving = false
                    refreshNow()
                }
            }
        }
    }

    /** Cancels the favorite being applied; the applier sends a confirmed stop for its step. */
    fun cancelFavorite() {
        favoriteJob?.cancel()
    }

    private fun handle(progress: FavoriteApplier.Progress, side: BedSide) {
        val run = applyingFavorite ?: return
        applyingFavorite = when (progress) {
            is FavoriteApplier.Progress.StepStarted -> run.copy(stepIndex = progress.index, stepCount = progress.of, status = progress.step.title)
            is FavoriteApplier.Progress.Moving -> {
                if (progress.step is FavoriteApplier.Step.Head) head[side] = progress.current else foot[side] = progress.current
                moving = true
                run.copy(status = "${progress.step.title}: now ${progress.current}%")
            }
            is FavoriteApplier.Progress.Skipped -> run.copy(status = "${progress.step.title}: already there")
            FavoriteApplier.Progress.WaitingForPump -> run.copy(status = "Waiting for the pump to finish the other side…")
            is FavoriteApplier.Progress.Adjusting -> {
                firmness[side] = progress.current
                pumpBusy = true
                run.copy(status = "${progress.step.title}: now ${progress.current}")
            }
            is FavoriteApplier.Progress.Stopping -> run.copy(status = "Stopping…", stopping = true)
            FavoriteApplier.Progress.Finished -> {
                moving = false
                run.copy(status = "Done")
            }
        }
    }

    // MARK: Diagnostics

    fun diagnosticsHeader(): String {
        val lines = mutableListOf(
            "Bed Control Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            "Bed: ${savedBed?.name ?: "none"} ${savedBed?.address ?: ""}",
            "Phase: $phase", "Swap sides: $swapSides", "Foot side: ${footSide.title}",
        )
        lines += diagnostics.map { "${it.first}: ${it.second}" }
        return lines.joinToString("\n")
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        // No reconnects or callbacks into a cleared controller.
        autoReconnect = false
        reconnectJob?.cancel()
        ble.onAdapterChange = null
        ble.onReady = null
        ble.onDisconnect = null
        ble.onLinkStall = null
        val d = driver
        val busy = activeHold != null || moving || pumpBusy || applyingFavorite != null
        holdJob?.cancel()
        favoriteJob?.cancel()
        pollJob?.cancel()
        if (d != null && busy) {
            // viewModelScope is already cancelled: send the stop from an app-lifetime scope, then close.
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                withTimeoutOrNull(3_000) { runCatching { d.stopAll() } }
                d.reset()
                ble.close()
            }
        } else {
            d?.reset()
            ble.close()
        }
        super.onCleared()
    }

    private companion object {
        const val KEY_SAVED_BED = "savedBed"
        const val KEY_SWAP = "swapSides"
        const val KEY_FOOT_SIDE = "footSide"
        const val KEY_CLIENT_ID = "mcrClientID"
    }
}
