package com.storelense.gateBt.rfid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers nearby Bluetooth devices (both Classic and BLE) so the guard can
 * identify and save the MUBR01's MAC address in the settings screen.
 *
 * Already-bonded devices appear instantly at the top of the list without
 * needing a full radio scan.
 */
@Singleton
class AndroidBluetoothScanner @Inject constructor(
    @ApplicationContext private val context: Context
) : BluetoothScanner {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<RfidBluetoothDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<RfidBluetoothDevice>> = _discoveredDevices.asStateFlow()

    private var classicReceiverRegistered = false

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    dev?.let { addDevice(it.safeName(), it.address) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    stopBle()
                    unregisterClassicReceiver()
                    _isScanning.value = false
                    Timber.d("BT discovery finished — %d devices found", _discoveredDevices.value.size)
                }
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addDevice(result.device.safeName(), result.device.address)
        }
        override fun onScanFailed(errorCode: Int) {
            Timber.w("BLE scan failed errorCode=$errorCode")
        }
    }

    /** Populate the list immediately with already-paired devices (no radio scan needed). */
    @SuppressLint("MissingPermission")
    fun loadBondedDevices() {
        try {
            bluetoothAdapter?.bondedDevices?.forEach { addDevice(it.safeName(), it.address) }
        } catch (e: SecurityException) {
            Timber.w("loadBondedDevices: BLUETOOTH_CONNECT not granted — ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        if (bluetoothAdapter == null || _isScanning.value) return
        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        // Bonded devices appear instantly
        try {
            bluetoothAdapter.bondedDevices?.forEach { addDevice(it.safeName(), it.address) }
        } catch (e: SecurityException) {
            Timber.w("bondedDevices blocked — BLUETOOTH_CONNECT not granted: ${e.message}")
        }

        // Classic BT discovery
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(classicReceiver, filter)
            classicReceiverRegistered = true
            if (!bluetoothAdapter.startDiscovery()) {
                Timber.w("startDiscovery() false — check location permission")
                unregisterClassicReceiver()
            }
        } catch (e: Exception) {
            Timber.w("Classic BT error: ${e.message}")
            unregisterClassicReceiver()
        }

        // BLE scan (parallel)
        try {
            bluetoothAdapter.bluetoothLeScanner?.startScan(bleScanCallback)
                ?: Timber.w("bluetoothLeScanner null — BLE unavailable")
        } catch (e: Exception) {
            Timber.w("BLE scan start error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) bluetoothAdapter.cancelDiscovery()
        } catch (e: Exception) { Timber.w("cancelDiscovery: ${e.message}") }
        stopBle()
        unregisterClassicReceiver()
        _isScanning.value = false
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback) }
        catch (e: Exception) { Timber.w("BLE stopScan: ${e.message}") }
    }

    private fun unregisterClassicReceiver() {
        if (classicReceiverRegistered) {
            try { context.unregisterReceiver(classicReceiver) } catch (_: Exception) {}
            classicReceiverRegistered = false
        }
    }

    private fun addDevice(name: String?, address: String) {
        if (address.isBlank()) return
        _discoveredDevices.update { list ->
            (list + RfidBluetoothDevice(name, address)).distinctBy { it.address }
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeName(): String? = try { name } catch (_: Exception) { null }
}
