package com.storelense.gateBt.rfid

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the Flow-based [RfidReader] interface to the simple callback contract
 * expected by [com.storelense.gateBt.ui.gate.GateScanViewModel].
 *
 * The C66 app used `rfid.startInventory { epc -> … }` with a direct callback.
 * The BT reader exposes a `Flow<EpcRead>`. This adapter hides that difference so
 * the gate ViewModel logic is identical to the C66 version.
 *
 * Additional responsibilities:
 *  - auto-reconnect: if the reader disconnects mid-session a reconnect attempt is
 *    made once; the ViewModel observes [connectionState] to surface status.
 *  - known EPCs: forwarded to [MockRfidReader] in dev builds (no-op in production).
 */
@Singleton
class GateRfidAdapter @Inject constructor(
    private val reader: RfidReader
) {
    val connectionState: StateFlow<Boolean> = reader.connectionState
    val triggerEvents: Flow<Boolean> = reader.triggerEvents
    val isConnected: Boolean get() = reader.isConnected

    private val adapterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var collectJob: Job? = null
    private var autoReconnectJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    suspend fun connect() = reader.connect()

    /** Pushes the configured antenna TX power to the reader. No-op until connected. */
    fun setTxPower(dbm: Int) = reader.setTxPower(dbm)

    suspend fun disconnect() {
        autoReconnectJob?.cancel()
        reader.disconnect()
    }

    // ── Inventory (callback-style entry point for the ViewModel) ──────────────

    /**
     * Start continuous RFID inventory. Each EPC string is delivered to [onEpc]
     * on the collection coroutine's thread. The ViewModel must be thread-safe
     * (its [kotlinx.coroutines.flow.MutableStateFlow.update] is).
     */
    fun startInventory(onEpc: (String) -> Unit) {
        if (!reader.isConnected) {
            Timber.w("GateRfidAdapter: startInventory called while disconnected — ignoring")
            return
        }
        collectJob?.cancel()
        collectJob = adapterScope.launch(start = CoroutineStart.UNDISPATCHED) {
            reader.reads.collect { epcRead ->
                onEpc(epcRead.epc)
            }
        }
        reader.startScan()
    }

    fun stopInventory() {
        collectJob?.cancel()
        collectJob = null
        reader.stopScan()
    }

    val isScanning: Boolean get() = collectJob?.isActive == true

    // ── Known EPCs (mock support) ─────────────────────────────────────────────

    /**
     * Forwards the bill's resolved EPCs to the mock reader so it can emit
     * realistic hits. On a real [BluetoothRfidReader] this is a no-op because
     * the antenna just reads whatever tags are physically present.
     */
    fun setKnownEpcs(epcs: Set<String>) {
        (reader as? MockRfidReader)?.knownEpcs?.let {
            it.clear()
            it.addAll(epcs)
        }
    }

    // ── Auto-reconnect ────────────────────────────────────────────────────────

    /**
     * Starts watching [connectionState] and attempts a single reconnect if the
     * reader drops mid-session (e.g. walked out of BLE range then back). Called
     * by the ViewModel once on init.
     */
    fun watchAndAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = adapterScope.launch {
            connectionState.collect { connected ->
                if (!connected) {
                    Timber.d("GateRfidAdapter: reader disconnected — attempting reconnect in 3 s")
                    delay(3_000)
                    try { connect() } catch (e: Exception) {
                        Timber.w(e, "GateRfidAdapter: auto-reconnect failed")
                    }
                }
            }
        }
    }
}
