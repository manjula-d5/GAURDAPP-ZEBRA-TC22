package com.storelense.gateBt.rfid

import android.content.Context
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.STATUS_EVENT_TYPE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

/** Zebra RFID3 adapter for the TC22R integrated UHF RFID reader. */
class EmDkRfidReader @Inject constructor(
    @ApplicationContext private val context: Context
) : RfidReader {

    private val _reads = MutableSharedFlow<EpcRead>(extraBufferCapacity = 1024)
    override val reads: Flow<EpcRead> = _reads.asSharedFlow()
    private val _triggerEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    override val triggerEvents: Flow<Boolean> = _triggerEvents.asSharedFlow()
    private val _connectionState = MutableStateFlow(false)
    override val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    @Volatile private var connectedReader: RFIDReader? = null
    private var readers: Readers? = null
    @Volatile private var scanning = false
    private val sdkLock = Any()

    override val isConnected: Boolean get() = connectedReader?.isConnected == true

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext
        synchronized(sdkLock) {
            val sdkReaders = readers ?: Readers(context.applicationContext, ENUM_TRANSPORT.SERVICE_USB)
                .also { readers = it }
            val device = sdkReaders.GetAvailableRFIDReaderList()?.firstOrNull()
                ?: throw IllegalStateException("No integrated Zebra RFID reader found. Confirm this is a TC22R and RFID is enabled.")
            val rfidReader = device.rfidReader
                ?: throw IllegalStateException("Zebra RFID SDK did not provide a reader connection.")
            try {
                rfidReader.connect()
                rfidReader.Events.addEventsListener(eventsListener)
                rfidReader.Events.setTagReadEvent(true)
                rfidReader.Events.setHandheldEvent(true)
                rfidReader.Events.setReaderDisconnectEvent(true)
                connectedReader = rfidReader
                _connectionState.value = rfidReader.isConnected
                check(_connectionState.value) { "Zebra RFID reader did not connect." }
                Timber.i("Zebra RFID3 reader connected: %s", device.name)
            } catch (e: Exception) {
                runCatching { rfidReader.disconnect() }
                connectedReader = null
                _connectionState.value = false
                Timber.e(e, "Failed to connect to the TC22R RFID reader")
                throw e
            }
        }
    }

    override fun startScan() {
        val reader = connectedReader ?: return
        if (!reader.isConnected || scanning) return
        try {
            reader.Actions.Inventory.perform()
            scanning = true
            Timber.d("TC22R RFID inventory started")
        } catch (e: Exception) {
            Timber.e(e, "TC22R RFID inventory start failed")
        }
    }

    override fun stopScan() {
        val reader = connectedReader ?: return
        if (!scanning) return
        try { reader.Actions.Inventory.stop() }
        catch (e: Exception) { Timber.e(e, "TC22R RFID inventory stop failed") }
        finally { scanning = false }
    }

    override fun setTxPower(dbm: Int) {
        // Radio power is managed by the Zebra regulatory profile on this device.
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        synchronized(sdkLock) {
            val reader = connectedReader
            try {
                stopScan()
                if (reader != null) {
                    runCatching { reader.Events.removeEventsListener(eventsListener) }
                    reader.disconnect()
                }
            } catch (e: Exception) {
                Timber.w(e, "TC22R RFID disconnect failed")
            } finally {
                connectedReader = null
                scanning = false
                _connectionState.value = false
                runCatching { readers?.Dispose() }
                readers = null
            }
        }
    }

    private val eventsListener = object : RfidEventsListener {
        override fun eventReadNotify(event: RfidReadEvents) {
            val tags = connectedReader?.Actions?.getReadTags(200) ?: return
            val timestamp = Instant.now().toString()
            tags.forEach { tag ->
                val epc = tag.tagID?.trim()?.uppercase().orEmpty()
                if (epc.isNotEmpty()) _reads.tryEmit(
                    EpcRead(epc = epc, rssi = tag.peakRSSI.toDouble(), antennaPort = tag.antennaID.toInt(), readAt = timestamp)
                )
            }
        }

        override fun eventStatusNotify(event: RfidStatusEvents) {
            val status = event.StatusEventData ?: return
            when (status.statusEventType) {
                STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                    val pressed = status.HandheldTriggerEventData?.handheldEvent ==
                        HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED
                    _triggerEvents.tryEmit(pressed)
                }
                STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
                    connectedReader = null
                    scanning = false
                    _connectionState.value = false
                    Timber.w("TC22R RFID reader disconnected")
                }
                else -> Unit
            }
        }
    }
}
