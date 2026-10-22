package com.storelense.gateBt.rfid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.bth.api.cls.Comm_Bluetooth
import com.silionmodule.AntPower
import com.silionmodule.ParamNames
import com.silionmodule.Reader
import com.silionmodule.ReaderException
import com.silionmodule.ReaderType.AntTypeE
import com.silionmodule.SimpleReadPlan
import com.storelense.gateBt.GateBtApplication
import com.storelense.gateBt.data.repository.ReaderSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

// BLE UART profile UUIDs for the Identium MUBR01
private const val SERVICE_UUID       = "0000ffe0-0000-1000-8000-00805f9b34fb"
private const val READ_UUID          = "0000ffe1-0000-1000-8000-00805f9b34fb"
private const val WRITE_UUID         = "0000ffe1-0000-1000-8000-00805f9b34fb"
private const val BLE_MODE           = 4       // 4 = BLE 4.0; returns 0 on success
private const val INVENTORY_ROUND_MS = 300     // ms per blocking Read() round

/**
 * Production RFID reader implementation for the Identium MUBR01 BLE module.
 *
 * All SDK calls are serialised on a single worker thread ([sdkExecutor]) because the
 * Silion/Identium SDK (com.silionmodule.*, com.bth.api.*) is not thread-safe.
 *
 * MAC address is read from [ReaderSettingsRepository]. If none is saved, [connect] throws
 * an [IllegalStateException] that surfaces as an error banner in the gate screen, directing
 * the guard to Settings → BT Device.
 */
@Singleton
class BluetoothRfidReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: ReaderSettingsRepository
) : RfidReader {

    private val _reads = MutableSharedFlow<EpcRead>(extraBufferCapacity = 1024)
    override val reads: Flow<EpcRead> = _reads.asSharedFlow()

    private val _connectionState = MutableStateFlow(false)
    override val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()
    override val isConnected: Boolean get() = _connectionState.value

    // Single worker thread — all SDK calls serialised here
    private val sdkExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "mubr01-sdk") }
    private val sdk: CoroutineDispatcher = sdkExecutor.asCoroutineDispatcher()
    private val sdkScope = CoroutineScope(sdk + SupervisorJob())

    private val connectMutex = Mutex()
    private var comm:    Comm_Bluetooth? = null
    private var reader:  Reader?         = null
    private var scanJob: Job?            = null

    @SuppressLint("MissingPermission")
    override suspend fun connect() = connectMutex.withLock {
        if (isConnected) return@withLock
        withContext(sdk) {
            try {
                val address = settingsRepo.load().bluetoothAddress
                    ?: throw IllegalStateException(
                        "No BT reader selected — open Settings → BT Device and pair your MUBR01."
                    )

                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter == null || !adapter.isEnabled)
                    throw IllegalStateException("Bluetooth is disabled. Enable it to connect the reader.")

                Timber.d("RFID: connecting to %s via BLE (mode %d)", address, BLE_MODE)

                val c = comm ?: run {
                    val activity = (context.applicationContext as GateBtApplication).currentActivity
                        ?: throw IllegalStateException("Open the app to connect the Bluetooth reader.")
                    Comm_Bluetooth(activity).also { comm = it }
                }

                val rc = c.Connect(address, BLE_MODE)
                if (rc != 0)
                    throw IllegalStateException("BLE connection rejected (code $rc). Check power and range.")

                Timber.d("RFID: Connect() rc=%d, discovering GATT services…", rc)

                val services = c.FindServices(6000)
                    ?: throw IllegalStateException("GATT service discovery failed — device may not be MUBR01.")

                Timber.d("RFID: found %d GATT services", services.size)

                if (!c.SetServiceUUIDs(SERVICE_UUID, READ_UUID, WRITE_UUID))
                    throw IllegalStateException("Could not bind MUBR01 BLE service UUIDs.")

                // Passive (command) mode — host polls with Reader.Read()
                c.Comm_SetParam(ParamNames.Communication_mode, 0)
                c.SetFrameParams(20, 100)

                val r = Reader.Create(AntTypeE.ONE_ANT, c).apply {
                    paramSet(ParamNames.Reader_Read_Plan, SimpleReadPlan(intArrayOf(1)))
                }
                reader = r
                _connectionState.value = true
                Timber.d("RFID: reader ready")

            } catch (e: Exception) {
                runCatching { reader?.DisConnect() }; reader = null
                runCatching { comm?.DisConnect() };   comm   = null
                _connectionState.value = false
                Timber.e(e, "RFID connect failed")
                throw e
            }
        }
    }

    override suspend fun disconnect() {
        stopScan()
        withContext(sdk) {
            runCatching { reader?.DisConnect() }; reader = null
            runCatching { comm?.DisConnect() };   comm   = null
            _connectionState.value = false
        }
    }

    override fun startScan() {
        if (!isConnected) return
        scanJob?.cancel()
        scanJob = sdkScope.launch {
            val r = reader ?: return@launch
            try {
                while (isActive) {
                    val tags = try {
                        r.Read(INVENTORY_ROUND_MS)
                    } catch (e: ReaderException) {
                        Timber.w("RFID: Read() error: %s", e.GetMessage())
                        if (comm?.ConnectState() != Comm_Bluetooth.CONNECTED)
                            _connectionState.value = false
                        null
                    }
                    tags?.forEach { t ->
                        if (t == null) return@forEach
                        val epc = t.EPCHexstr()?.trim()?.uppercase() ?: return@forEach
                        if (epc.isBlank()) return@forEach
                        _reads.tryEmit(
                            EpcRead(
                                epc         = epc,
                                rssi        = t.RSSI().toDouble(),
                                antennaPort = t.Antenna(),
                                readAt      = Instant.now().toString()
                            )
                        )
                    }
                    yield()
                }
            } catch (_: CancellationException) {
                // normal stop
            } catch (e: Exception) {
                Timber.e(e, "RFID scan loop terminated unexpectedly")
                _connectionState.value = false
            }
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    override fun setTxPower(dbm: Int) {
        sdkScope.launch {
            val r = reader ?: return@launch
            try {
                @Suppress("UNCHECKED_CAST")
                val ports = r.paramGet(ParamNames.Reader_Radio_PortPowerList) as? Array<AntPower>
                val centi = dbm * 100
                val updated: Array<AntPower> = if (!ports.isNullOrEmpty()) {
                    Array(ports.size) { i -> AntPower(ports[i].Antid(), centi, centi) }
                } else {
                    arrayOf(AntPower(1, centi, centi))
                }
                r.paramSet(ParamNames.Reader_Radio_PortPowerList, updated)
            } catch (e: ReaderException) {
                Timber.e(e, "setTxPower failed: %s", e.GetMessage())
            } catch (e: Exception) {
                Timber.e(e, "setTxPower failed")
            }
        }
    }
}
