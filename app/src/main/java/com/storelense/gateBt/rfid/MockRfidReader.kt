package com.storelense.gateBt.rfid

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mock RFID reader for emulator / debug builds.
 *
 * Simulates the MUBR01 tag-read stream without real hardware:
 *  - Picks a random EPC from [knownEpcs] with 80% probability each tick (matched item).
 *  - Occasionally emits a simulated extra EPC for mock/debug testing.
 *  - Otherwise emits nothing (mock miss).
 *
 * connectionState is set to `true` immediately on [connect] since there is no
 * real transport to establish.
 */
@Singleton
class MockRfidReader @Inject constructor() : RfidReader {

    private val _connectionState = MutableStateFlow(false)
    override val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()
    override val isConnected: Boolean get() = _connectionState.value

    private val _reads = MutableSharedFlow<EpcRead>(extraBufferCapacity = 256)
    override val reads: Flow<EpcRead> = _reads.asSharedFlow()

    // Updated by GateRfidAdapter so the mock emits realistic hits
    val knownEpcs = mutableSetOf<String>()


    private val extraPool = listOf(
        "3034257BF4000000000000F1",
        "3034257BF4000000000000F2",
        "3034257BF4000000000000F3",
        "3034257BF4000000000000F4"
    )

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null

    override suspend fun connect() {
        _connectionState.value = true
    }

    override suspend fun disconnect() {
        stopScan()
        _connectionState.value = false
    }

    override fun startScan() {
        if (!isConnected || scanJob?.isActive == true) return
        scanJob = scope.launch {
            while (isActive) {
                delay(600L)
                val roll = (0..99).random()
                val epc: String? = when {
                    roll < 80 && knownEpcs.isNotEmpty() -> knownEpcs.random()
                    roll < 95 && extraPool.isNotEmpty() -> extraPool.random()
                    else                                -> null   // 5% miss
                }
                epc?.let { _reads.tryEmit(EpcRead(epc = it)) }
            }
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }
}
