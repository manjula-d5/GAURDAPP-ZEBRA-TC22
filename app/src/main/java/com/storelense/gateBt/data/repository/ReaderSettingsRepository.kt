package com.storelense.gateBt.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the Identium MUBR01 BLE MAC address (and optional settings) so the
 * guard only needs to pair once. Uses EncryptedSharedPreferences — the MAC is
 * not a secret but the file is shared with token storage patterns.
 */
@Singleton
class ReaderSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "gate_bt_reader_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _settings = MutableStateFlow(loadFromPrefs())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    fun load(): ReaderSettings = _settings.value

    fun saveDevice(address: String, name: String?) {
        prefs.edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name)
            .apply()
        _settings.value = loadFromPrefs()
    }

    fun saveTxPower(dbm: Int) {
        prefs.edit().putInt(KEY_TX_POWER, dbm).apply()
        _settings.value = loadFromPrefs()
    }

    fun clearDevice() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
        _settings.value = loadFromPrefs()
    }

    private fun loadFromPrefs() = ReaderSettings(
        bluetoothAddress = prefs.getString(KEY_ADDRESS,  null),
        bluetoothName    = prefs.getString(KEY_NAME,     null),
        txPowerDbm       = prefs.getInt(KEY_TX_POWER,    20)
    )

    companion object {
        private const val KEY_ADDRESS  = "bt_mac_address"
        private const val KEY_NAME     = "bt_device_name"
        private const val KEY_TX_POWER = "tx_power_dbm"
    }
}
