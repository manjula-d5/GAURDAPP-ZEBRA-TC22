package com.storelense.gateBt.rfid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Prefers the integrated TC22R RFID reader and falls back to the paired MUBR01. */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AutoRfidReader @Inject constructor(
    private val zebraReader: EmDkRfidReader,
    private val bluetoothReader: BluetoothRfidReader
) : RfidReader {

    private val activeReader = MutableStateFlow<RfidReader?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectMutex = Mutex()

    override val connectionState = combine(
        activeReader,
        zebraReader.connectionState,
        bluetoothReader.connectionState
    ) { active, zebraConnected, bluetoothConnected ->
        when (active) {
            zebraReader -> zebraConnected
            bluetoothReader -> bluetoothConnected
            else -> false
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    override val isConnected: Boolean get() = activeReader.value?.isConnected == true
    override val reads: Flow<EpcRead> = activeReader.flatMapLatest { it?.reads ?: emptyFlow() }
    override val triggerEvents: Flow<Boolean> = activeReader.flatMapLatest { it?.triggerEvents ?: emptyFlow() }

    override suspend fun connect() = connectMutex.withLock {
        if (isConnected) return@withLock
        activeReader.value = null

        try {
            zebraReader.connect()
            activeReader.value = zebraReader
            Timber.i("RFID: using integrated Zebra TC22R reader")
        } catch (zebraError: Exception) {
            runCatching { zebraReader.disconnect() }
            Timber.i(zebraError, "Integrated Zebra reader unavailable; trying Bluetooth RFID reader")
            try {
                bluetoothReader.connect()
                activeReader.value = bluetoothReader
                Timber.i("RFID: using Bluetooth reader")
            } catch (bluetoothError: Exception) {
                throw IllegalStateException(
                    "No RFID reader connected. Check the TC22R reader or pair a MUBR01 in Settings → BT Device.",
                    bluetoothError
                )
            }
        }
    }

    override suspend fun disconnect() {
        val reader = activeReader.value
        activeReader.value = null
        reader?.disconnect()
    }

    override fun startScan() { activeReader.value?.startScan() }
    override fun stopScan() { activeReader.value?.stopScan() }
    override fun setTxPower(dbm: Int) { activeReader.value?.setTxPower(dbm) }
}
