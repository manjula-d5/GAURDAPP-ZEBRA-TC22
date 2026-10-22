package com.storelense.gateBt.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storelense.gateBt.data.repository.ReaderSettings
import com.storelense.gateBt.data.repository.ReaderSettingsRepository
import com.storelense.gateBt.rfid.AndroidBluetoothScanner
import com.storelense.gateBt.rfid.RfidBluetoothDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BtSettingsState(
    val currentSettings:   ReaderSettings             = ReaderSettings(null, null),
    val discoveredDevices: List<RfidBluetoothDevice>  = emptyList(),
    val isScanning:        Boolean                    = false,
    val savedMessage:      String?                    = null
)

@HiltViewModel
class BtSettingsViewModel @Inject constructor(
    private val scanner:  AndroidBluetoothScanner,
    private val settings: ReaderSettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BtSettingsState())
    val state = _state.asStateFlow()

    init {
        // Mirror reader settings
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.update { it.copy(currentSettings = s) }
            }
        }
        // Mirror discovered devices
        viewModelScope.launch {
            scanner.discoveredDevices.collect { devices ->
                _state.update { it.copy(discoveredDevices = devices) }
            }
        }
        // Mirror scan state from the scanner itself — this is the source of truth.
        // (Don't track it manually in the VM; startScan() is not a suspend fun
        //  and returns immediately, so manual tracking would flip back to false at once.)
        viewModelScope.launch {
            scanner.isScanning.collect { scanning ->
                _state.update { it.copy(isScanning = scanning) }
            }
        }
        // Pre-populate with already-bonded devices (no radio needed)
        scanner.loadBondedDevices()
    }

    /** Called from the UI after BT permissions have been granted. */
    fun startScan() {
        scanner.startScan()   // non-suspend; sets scanner.isScanning = true internally
    }

    fun stopScan() {
        scanner.stopScan()    // scanner.isScanning flows to false → state updates via collector
    }

    fun selectDevice(device: RfidBluetoothDevice) {
        viewModelScope.launch {
            settings.saveDevice(device.address, device.name)
            _state.update { it.copy(savedMessage = "Reader set to ${device.name ?: device.address}") }
        }
    }

    fun clearDevice() {
        viewModelScope.launch {
            settings.clearDevice()
            _state.update { it.copy(savedMessage = "Reader cleared") }
        }
    }

    fun saveTxPower(dbm: Int) {
        viewModelScope.launch { settings.saveTxPower(dbm) }
    }

    fun clearMessage() = _state.update { it.copy(savedMessage = null) }
}
