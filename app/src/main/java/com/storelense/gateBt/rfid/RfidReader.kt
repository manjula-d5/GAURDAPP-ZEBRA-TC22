package com.storelense.gateBt.rfid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

data class EpcRead(
    val epc:         String,
    val rssi:        Double? = null,
    val antennaPort: Int?    = null,
    val readAt:      String  = java.time.Instant.now().toString()
)

/**
 * Hardware-agnostic RFID reader contract.
 *
 * Implementations include the integrated Zebra TC22R, Identium MUBR01 over BLE,
 * their hardware selector, and [MockRfidReader] for emulator/dev builds.
 */
interface RfidReader {
    /** True while the reader hardware is connected and ready. */
    val isConnected: Boolean
    /** Hot StateFlow: true = connected & ready, false = disconnected or connecting. */
    val connectionState: StateFlow<Boolean>
    /** Hot flow of EPC reads as they arrive from the antenna. */
    val reads: Flow<EpcRead>
    /** Hardware trigger events; true means pressed, false means released. */
    val triggerEvents: Flow<Boolean> get() = emptyFlow()
    suspend fun connect()
    suspend fun disconnect()

    /** Begin continuous inventory. Tags arrive via [reads]. */
    fun startScan()
    /** Stop continuous inventory. */
    fun stopScan()
    /** Set antenna TX power in dBm (no-op on mock). */
    fun setTxPower(dbm: Int) {}
}
