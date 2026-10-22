package com.storelense.gateBt.rfid

import kotlinx.coroutines.flow.StateFlow

/** Represents a discovered BLE/Classic BT device. */
data class RfidBluetoothDevice(
    val name:    String?,
    val address: String
)

/**
 * Scans for nearby Bluetooth devices so the guard can pick and save
 * the MUBR01 MAC address in [com.storelense.gateBt.data.repository.ReaderSettingsRepository].
 */
interface BluetoothScanner {
    val isScanning:        StateFlow<Boolean>
    val discoveredDevices: StateFlow<List<RfidBluetoothDevice>>
    fun startScan()
    fun stopScan()
}
