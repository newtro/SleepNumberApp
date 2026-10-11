package ai.johnnycode.bedcontrol.ble

import ai.johnnycode.bedcontrol.control.DiagnosticsLog
import ai.johnnycode.bedkit.AdvertisementInfo
import ai.johnnycode.bedkit.BedCharacteristic
import ai.johnnycode.bedkit.BedDetector
import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.BedLink
import ai.johnnycode.bedkit.BedProtocol
import ai.johnnycode.bedkit.CandidateClassification
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** A device seen while scanning. Android identifies devices by Bluetooth address. */
data class ScannedDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val classification: CandidateClassification,
    val lastSeen: Long,
)

enum class AdapterState { UNKNOWN, ON, OFF, UNSUPPORTED }

/**
 * Android BLE transport: scanning, connecting, GATT discovery, bonding, MTU, chunked writes, reads and
 * notification routing. Implements bedkit's [BedLink]; all protocol logic lives in bedkit.
 *
 * Android's GATT allows one outstanding operation, so every write chunk, read, descriptor write, MTU
 * request and discovery goes through [gattOp], which holds a mutex until the operation's callback
 * arrives. An operation always waits for its own callback (bounded by a timeout) even when the caller
 * is cancelled, so a late callback can never complete the next operation; a missing callback means the
 * link is broken and it is dropped. Whole frames are serialized by [frameMutex], so chunks of two frames
 * never interleave.
 *
 * Everything runs on the main thread: GATT callbacks (binder threads) are posted to it. Callers must
 * hold the Bluetooth permissions before using it (MainActivity gates the UI on them).
 */
@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val log: DiagnosticsLog,
    private val scope: CoroutineScope,
) : BedLink {
    sealed class LinkState {
        data object Idle : LinkState()
        data class Connecting(val name: String) : LinkState()
        data object Discovering : LinkState()
        data class Ready(val kind: BedProtocol) : LinkState()
    }

    var adapterState by mutableStateOf(AdapterState.UNKNOWN)
        private set
    var isScanning by mutableStateOf(false)
        private set
    val devices = mutableStateListOf<ScannedDevice>()
    var showAllDevices by mutableStateOf(false)
    var linkState by mutableStateOf<LinkState>(LinkState.Idle)
        private set
    var connectedAddress by mutableStateOf<String?>(null)
        private set
    var mtu by mutableStateOf(23)
        private set
    var bondState by mutableStateOf("")
        private set

    // Controller hooks
    var onAdapterChange: ((AdapterState) -> Unit)? = null
    var onReady: ((BedProtocol) -> Unit)? = null
    var onDisconnect: ((BedError?) -> Unit)? = null
    var onNotification: ((BedCharacteristic, ByteArray) -> Unit)? = null

    /** Called when a write stalls, just before the link is dropped, so the controller can try a stop. */
    var onWriteStall: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var characteristics = mapOf<BedCharacteristic, BluetoothGattCharacteristic>()
    private var userDisconnect = false
    private var detectedKind: BedProtocol? = null
    private var setupJob: Job? = null

    private val opMutex = Mutex()
    private val frameMutex = Mutex()
    private var pendingOp: PendingOp? = null

    private class PendingOp(val kind: String, val uuid: UUID?, val deferred: CompletableDeferred<ByteArray>)

    private var bondWaiter: CompletableDeferred<Int>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> refreshAdapterState()
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val changed = intentDevice(intent) ?: return
                    if (changed.address != device?.address) return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    bondState = bondName(state)
                    log.info("BLE", "bond state ${bondName(state)}")
                    if (state != BluetoothDevice.BOND_BONDING) bondWaiter?.complete(state)
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refreshAdapterState()
    }

    private fun intentDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    fun refreshAdapterState() {
        val state = when {
            manager == null || adapter == null -> AdapterState.UNSUPPORTED
            adapter?.isEnabled == true -> AdapterState.ON
            else -> AdapterState.OFF
        }
        if (state == adapterState) return
        adapterState = state
        log.info("BLE", "Bluetooth ${state.name.lowercase()}")
        if (state != AdapterState.ON) {
            isScanning = false
            if (gatt != null) {
                closeGatt()
                cleanup(BedError.Transport("Bluetooth turned off"), notify = true)
            }
        }
        onAdapterChange?.invoke(state)
    }

    // MARK: Scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { handleScan(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            main.post { results.forEach(::handleScan) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                isScanning = false
                log.error("Scan", "scan failed (error $errorCode)")
            }
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapterState != AdapterState.ON || scanner == null) return
        if (isScanning) return
        devices.clear()
        // No filters: MCR beds often advertise only their MAC as a name, without the service UUID.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, scanCallback)
            isScanning = true
            log.info("BLE", "scan started")
        } catch (e: SecurityException) {
            log.error("BLE", "scan not permitted: ${e.message}")
        }
    }

    fun stopScan() {
        if (!isScanning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        isScanning = false
        log.info("BLE", "scan stopped")
    }

    val visibleDevices: List<ScannedDevice>
        get() = devices.filter { showAllDevices || it.classification.isCandidate }
            .sortedWith(compareByDescending<ScannedDevice> { it.classification.score }.thenByDescending { it.rssi })

    private fun handleScan(result: ScanResult) {
        val record = result.scanRecord
        val name = record?.deviceName ?: try { result.device.name } catch (_: SecurityException) { null }
        val services = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
        val manufacturer = record?.manufacturerSpecificData?.let { data ->
            if (data.size() > 0) AdvertisementInfo.manufacturerData(data.keyAt(0), data.valueAt(0)) else null
        }
        val address = result.device.address
        val classification = BedDetector.classify(AdvertisementInfo(name, services, manufacturer, address))
        if (!classification.isCandidate && name == null) return
        val display = name ?: address
        val now = System.currentTimeMillis()
        val index = devices.indexOfFirst { it.address == address }
        if (index >= 0) {
            val existing = devices[index]
            // Throttle UI churn from duplicate advertisements.
            if (now - existing.lastSeen < 1000 && existing.name == display) return
            devices[index] = existing.copy(
                name = display, rssi = result.rssi, lastSeen = now,
                classification = if (classification.score > existing.classification.score) classification else existing.classification,
            )
        } else {
            devices += ScannedDevice(address, display, result.rssi, classification, now)
            if (classification.isCandidate) {
                log.info("Scan", "candidate $display ($address) rssi ${result.rssi}: ${classification.reasons.joinToString(", ") { it.label }}")
            }
        }
    }

    // MARK: Connection

    /**
     * Connects to a bed by address. `waitForDevice` uses Android's background auto-connect, which stays
     * pending until the bed is in range (for reconnecting after a link loss); otherwise a direct connect.
     */
    fun connect(address: String, name: String, waitForDevice: Boolean = false) {
        val adapter = adapter ?: return
        if (adapterState != AdapterState.ON) return
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            onDisconnect?.invoke(BedError.Transport("\"$address\" is not a Bluetooth address. Choose the bed again."))
            return
        }
        closeGatt()
        cleanup(null, notify = false)
        stopScan()
        userDisconnect = false
        val target = adapter.getRemoteDevice(address)
        device = target
        bondState = bondName(target.bondState)
        connectedAddress = address
        linkState = LinkState.Connecting(name)
        log.info("BLE", "connecting to $name ($address)${if (waitForDevice) ", waiting for it to come in range" else ""}; bond ${bondState}")
        gatt = target.connectGatt(context, waitForDevice, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) fail(BedError.Transport("could not start the connection"), "connect")
    }

    fun disconnect() {
        userDisconnect = true
        closeGatt()
        cleanup(null, notify = false)
        connectedAddress = null
        linkState = LinkState.Idle
    }

    private fun closeGatt() {
        val current = gatt ?: return
        gatt = null
        try {
            current.disconnect()
            current.close()
        } catch (_: Exception) {
        }
    }

    private fun cleanup(error: BedError?, notify: Boolean) {
        setupJob?.cancel()
        setupJob = null
        pendingOp?.deferred?.completeExceptionally(error ?: BedError.NotConnected)
        pendingOp = null
        bondWaiter?.cancel()
        bondWaiter = null
        characteristics = emptyMap()
        detectedKind = null
        mtu = 23
        if (gatt == null) linkState = LinkState.Idle
        if (notify) onDisconnect?.invoke(error)
    }

    private fun fail(error: BedError, stage: String) {
        log.error("BLE", "$stage failed: ${error.message}")
        closeGatt()
        cleanup(error, notify = true)
        linkState = LinkState.Idle
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) {
                    // A stale connection object: make sure it is released.
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) try { g.close() } catch (_: Exception) {}
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    log.info("BLE", "connected; discovering services")
                    linkState = LinkState.Discovering
                    setupJob = scope.launch { setUp(g) }
                } else {
                    val bonded = device?.bondState == BluetoothDevice.BOND_BONDED
                    val mapped = disconnectError(status, bonded)
                    log.info("BLE", "disconnected (status $status)${mapped?.let { ": ${it.message}" } ?: ""}")
                    val wasUser = userDisconnect
                    closeGatt()
                    cleanup(mapped, notify = !wasUser)
                    linkState = LinkState.Idle
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(g, "discover", null, status, ByteArray(0))

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post { if (g === gatt && status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu }
            complete(g, "mtu", null, status, ByteArray(0))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            complete(g, "write", c.uuid, status, ByteArray(0))

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            complete(g, "descriptor", d.characteristic.uuid, status, ByteArray(0))

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) complete(g, "read", c.uuid, status, value)
        }

        @Deprecated("Android 12 and lower")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) complete(g, "read", c.uuid, status, c.value?.copyOf() ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (Build.VERSION.SDK_INT >= 33) notified(g, c.uuid, value)
        }

        @Deprecated("Android 12 and lower")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) notified(g, c.uuid, c.value?.copyOf() ?: ByteArray(0))
        }
    }

    private fun complete(g: BluetoothGatt, kind: String, uuid: UUID?, status: Int, value: ByteArray) {
        main.post {
            if (g !== gatt) return@post
            val op = pendingOp
            if (op == null || op.kind != kind || (op.uuid != null && uuid != null && op.uuid != uuid)) {
                log.info("BLE", "unexpected $kind callback (status $status) ignored")
                return@post
            }
            pendingOp = null
            if (status == BluetoothGatt.GATT_SUCCESS) op.deferred.complete(value)
            else op.deferred.completeExceptionally(gattError(status))
        }
    }

    private fun notified(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        main.post {
            if (g !== gatt) return@post
            val id = identify(uuid) ?: return@post
            onNotification?.invoke(id, value)
        }
    }

    /** Discovery, protocol detection, bonding (Fuzion), MTU (Fuzion) and characteristic lookup. */
    private suspend fun setUp(g: BluetoothGatt) {
        try {
            // Bonded links encrypt right after connecting; discovering during that is unreliable.
            if (device?.bondState == BluetoothDevice.BOND_BONDED) delay(600)
            gattOp("discover", null, 15_000) { it.discoverServices() }
            val services = g.services.map { it.uuid.toString() }
            log.info("BLE", "services: ${services.joinToString(", ")}")
            val kind = BedProtocol.detect(services)
                ?: throw BedError.UnsupportedCommand("Sleep Number control (no MCR or Fuzion service found)")
            detectedKind = kind
            log.info("BLE", "detected protocol ${kind.displayName}")
            val service = g.services.first { BedProtocol.forService(it.uuid.toString()) == kind }
            characteristics = service.characteristics.mapNotNull { c -> identify(c.uuid)?.let { it to c } }.toMap()
            characteristics.forEach { (id, c) -> log.info("BLE", "characteristic ${id.name} properties 0x${c.properties.toString(16)}") }
            val required = if (kind == BedProtocol.MCR) listOf(BedCharacteristic.MCR_NOTIFY, BedCharacteristic.MCR_WRITE)
            else listOf(BedCharacteristic.FUZION_AUTH, BedCharacteristic.FUZION_BAMKEY)
            val missing = required.filter { it !in characteristics }
            if (missing.isNotEmpty()) throw BedError.MalformedResponse("bed is missing ${missing.joinToString(", ") { it.name }}")
            if (kind == BedProtocol.FUZION) {
                // The official app requests MTU 517 for Fuzion (MCR never changes the MTU).
                try {
                    gattOp("mtu", null) { it.requestMtu(517) }
                    log.info("BLE", "MTU $mtu")
                } catch (e: BedError) {
                    log.info("BLE", "MTU request failed: ${e.message}")
                }
                ensureBonded()
            }
            linkState = LinkState.Ready(kind)
            onReady?.invoke(kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BedError) {
            if (g === gatt) fail(e, "setup")
        }
    }

    /** The official app bonds a Fuzion bed before reading Auth (waiting for the user's confirmation). */
    private suspend fun ensureBonded() {
        val target = device ?: throw BedError.NotConnected
        if (target.bondState == BluetoothDevice.BOND_BONDED) return
        log.info("BLE", "bed is not bonded; requesting pairing")
        val waiter = CompletableDeferred<Int>()
        bondWaiter = waiter
        if (target.bondState != BluetoothDevice.BOND_BONDING && !target.createBond()) {
            throw BedError.BondRequired
        }
        val state = withTimeoutOrNull(30_000) { waiter.await() }
        bondWaiter = null
        if (state != BluetoothDevice.BOND_BONDED) throw BedError.BondRequired
    }

    // MARK: GATT operation queue

    /**
     * Runs one GATT operation and waits for its callback. The wait itself is not cancellable: a cancelled
     * caller still waits (at most [timeoutMs]) for the callback, so the operation queue never desyncs.
     * A missing callback drops the link (a late callback could otherwise complete the wrong operation).
     */
    private suspend fun gattOp(kind: String, uuid: UUID?, timeoutMs: Long = 5_000, start: (BluetoothGatt) -> Boolean): ByteArray =
        opMutex.withLock {
            currentCoroutineContext().ensureActive()
            val g = gatt ?: throw BedError.NotConnected
            val deferred = CompletableDeferred<ByteArray>()
            pendingOp = PendingOp(kind, uuid, deferred)
            var started = false
            for (attempt in 1..5) {
                started = try { start(g) } catch (e: SecurityException) { throw BedError.Transport("Bluetooth permission missing") }
                if (started) break
                delay(25L * attempt) // the stack is momentarily busy (e.g. a system-initiated operation)
            }
            if (!started) {
                if (pendingOp?.deferred === deferred) pendingOp = null
                throw BedError.Transport("$kind could not be started")
            }
            val result = withContext(NonCancellable) {
                withTimeoutOrNull(timeoutMs) { runCatching { deferred.await() } }
            }
            if (result == null) {
                if (pendingOp?.deferred === deferred) pendingOp = null
                linkBroken(kind, g)
                throw BedError.Timeout("BLE $kind")
            }
            currentCoroutineContext().ensureActive()
            result.getOrElse { throw (it as? BedError) ?: BedError.Transport(it.message ?: it.toString()) }
        }

    private fun linkBroken(kind: String, g: BluetoothGatt) {
        log.error("BLE", "$kind confirmation timed out; sending stop, then dropping the link")
        if (kind == "write") onWriteStall?.invoke()
        main.postDelayed({
            if (g === gatt) {
                closeGatt()
                cleanup(BedError.Timeout("BLE $kind"), notify = true)
                linkState = LinkState.Idle
            }
        }, if (kind == "write") 1_500 else 0)
    }

    // MARK: BedLink

    override suspend fun enableNotifications(characteristic: BedCharacteristic) {
        val g = gatt ?: throw BedError.NotConnected
        val target = characteristics[characteristic] ?: throw BedError.NotConnected
        if (!g.setCharacteristicNotification(target, true)) throw BedError.Transport("could not enable notifications on ${characteristic.name}")
        val cccd = target.getDescriptor(CCCD) ?: throw BedError.MalformedResponse("${characteristic.name} has no notification descriptor")
        val value = if (target.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        gattOp("descriptor", target.uuid, 15_000) { gt ->
            if (Build.VERSION.SDK_INT >= 33) gt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run {
                cccd.value = value
                gt.writeDescriptor(cccd)
            }
        }
        log.info("BLE", "notifications on ${characteristic.name}")
    }

    override suspend fun write(frame: ByteArray, characteristic: BedCharacteristic) = frameMutex.withLock {
        val target = characteristics[characteristic] ?: throw BedError.NotConnected
        val props = target.properties
        // MCR: the app writes with response whenever the characteristic allows it, in 20-byte pieces.
        // Fuzion: write-without-response in MTU-4 pieces (MTU-5 on Android 14+), like the official app.
        val withResponse = if (characteristic == BedCharacteristic.MCR_WRITE) props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        else props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0
        val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val chunkSize = if (characteristic == BedCharacteristic.MCR_WRITE) MCR_CHUNK
        else maxOf(20, mtu - if (Build.VERSION.SDK_INT >= 34) 5 else 4)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + chunkSize, frame.size)
            val chunk = frame.copyOfRange(offset, end)
            gattOp("write", target.uuid) { g ->
                if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(target, chunk, writeType) == BluetoothStatusCodes.SUCCESS
                else @Suppress("DEPRECATION") run {
                    target.writeType = writeType
                    target.value = chunk
                    g.writeCharacteristic(target)
                }
            }
            offset = end
        }
    }

    override suspend fun read(characteristic: BedCharacteristic): ByteArray {
        val target = characteristics[characteristic] ?: throw BedError.NotConnected
        return gattOp("read", target.uuid, 10_000) { it.readCharacteristic(target) }
    }

    fun has(characteristic: BedCharacteristic) = characteristic in characteristics

    private fun identify(uuid: UUID): BedCharacteristic? = when (uuid.toString().uppercase()) {
        BedProtocol.MCR_NOTIFY_UUID -> BedCharacteristic.MCR_NOTIFY
        BedProtocol.MCR_WRITE_UUID -> BedCharacteristic.MCR_WRITE
        BedProtocol.FUZION_AUTH_UUID -> BedCharacteristic.FUZION_AUTH
        BedProtocol.FUZION_BAMKEY_UUID -> BedCharacteristic.FUZION_BAMKEY
        BedProtocol.FUZION_BULK_UUID -> BedCharacteristic.FUZION_BULK
        else -> null
    }

    companion object {
        const val MCR_CHUNK = 20
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun bondName(state: Int) = when (state) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            else -> "not bonded"
        }

        /** Maps GATT operation statuses to user-actionable errors (bonding problems in particular). */
        fun gattError(status: Int): BedError = when (status) {
            // INSUFFICIENT_AUTHENTICATION, INSUFFICIENT_AUTHORIZATION, ENCRYPTION KEY SIZE, INSUFFICIENT_ENCRYPTION, GATT_AUTH_FAIL
            5, 8, 12, 15, 137 -> BedError.BondRequired
            else -> BedError.Transport("GATT status $status")
        }

        /** Maps a disconnect status (HCI reason or GATT status). */
        fun disconnectError(status: Int, bonded: Boolean): BedError? = when (status) {
            BluetoothGatt.GATT_SUCCESS, 19, 22 -> null // success, remote user terminated, local host terminated
            5, 137 -> if (bonded) BedError.PairingRemoved else BedError.BondRequired // authentication failure
            8 -> BedError.Transport("the connection timed out (bed out of range?)")
            62 -> BedError.Transport("the bed did not accept the connection")
            133 -> BedError.Transport("connection failed (GATT error 133)")
            147 -> BedError.Transport("connection attempt timed out")
            else -> BedError.Transport("disconnected (status $status)")
        }
    }
}
