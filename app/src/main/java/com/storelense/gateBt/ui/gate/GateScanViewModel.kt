package com.storelense.gateBt.ui.gate

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.storelense.gateBt.data.remote.dto.BillSummaryDto
import com.storelense.gateBt.data.remote.dto.EpcsByEanResponse
import com.storelense.gateBt.data.remote.dto.IdentifyEpcResponse
import com.storelense.gateBt.data.repository.AuthRepository
import com.storelense.gateBt.data.repository.GateRepository
import com.storelense.gateBt.data.repository.ReaderSettingsRepository
import com.storelense.gateBt.data.repository.Result
import com.storelense.gateBt.data.repository.StoreConfig
import com.storelense.gateBt.data.repository.StoreConfigRepository
import com.storelense.gateBt.rfid.GateRfidAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

// ── Scan events ───────────────────────────────────────────────────────────────

sealed class ScanEvent {
    data class Matched(val ean: String) : ScanEvent()
    data class BarcodeVerified(val ean: String) : ScanEvent()
    object BarcodeNotOnBill : ScanEvent()
    object Extra            : ScanEvent()
    object Duplicate        : ScanEvent()
}

// ── Data model ────────────────────────────────────────────────────────────────

enum class LineStatus { PENDING, PARTIAL, FULFILLED }

data class BillLineItem(
    val ean:                  String,
    val sku:                  String,
    val productName:          String,
    val qtyRequired:          Int,
    /** null = unknown (treat as RFID); false = no RFID tag, verify by barcode */
    val isRfidEnabled:        Boolean?      = null,
    val validEpcs:            Set<String>   = emptySet(),
    val matchedEpcs:          List<String>  = emptyList(),
    val barcodeVerifiedCount: Int           = 0,
    val resolveError:         String?       = null,
    val imageUrl:             String?       = null,
    val unitPrice:            java.math.BigDecimal? = null
) {
    val isNonRfid:    Boolean    get() = isRfidEnabled == false
    val verifiedCount: Int       get() = if (isNonRfid) barcodeVerifiedCount else matchedEpcs.size
    val status:       LineStatus get() = when {
        verifiedCount >= qtyRequired -> LineStatus.FULFILLED
        verifiedCount > 0            -> LineStatus.PARTIAL
        else                         -> LineStatus.PENDING
    }
    /** Non-RFID items are "resolved" immediately — there are no EPCs to look up. */
    val isResolved: Boolean get() = isNonRfid || validEpcs.isNotEmpty() || resolveError != null
}

data class GateState(
    val billRef:               String                          = "",
    val items:                 List<BillLineItem>              = emptyList(),
    val extraEpcs:             List<String>                    = emptyList(),
    val extraBarcodes:         List<String>                    = emptyList(),
    /** epc -> lookup result, populated lazily per extra EPC. Key absent = still resolving;
     *  key present with null value = confirmed not in products.epc_tags ("Unknown EPC"). */
    val extraEpcInfo:          Map<String, IdentifyEpcResponse?> = emptyMap(),
    /** ean -> lookup result, populated lazily per extra scanned barcode. Same absent/null
     *  convention as extraEpcInfo. */
    val extraBarcodeInfo:      Map<String, EpcsByEanResponse?> = emptyMap(),
    val isResolvingBill:       Boolean                         = false,
    val isScanning:            Boolean                         = false,
    val isReleasing:           Boolean                         = false,
    val released:              Boolean                         = false,
    val markedCount:           Int                             = 0,
    val gateCheckId:           String?                         = null,
    val needsResolution:       Boolean                         = false,
    val error:                 String?                         = null,
    val hasBill:               Boolean                         = false,
    val pendingBills:          List<BillSummaryDto>            = emptyList(),
    val btConnected:           Boolean                         = false,
    val btConnecting:          Boolean                         = false,
    val btError:               String?                         = null
) {
    val totalRequired:      Int     get() = items.sumOf { it.qtyRequired }
    val totalMatched:       Int     get() = items.sumOf { it.verifiedCount }
    val allFulfilled:       Boolean get() = items.isNotEmpty() && items.all { it.status == LineStatus.FULFILLED }
    val allResolved:        Boolean get() = items.isNotEmpty() && items.all { it.isResolved }
    val hasExtraItems:      Boolean get() = extraEpcs.isNotEmpty() || extraBarcodes.isNotEmpty()
    val nonRfidItems:       List<BillLineItem> get() = items.filter { it.isNonRfid }
    val pendingNonRfidItems:List<BillLineItem> get() = nonRfidItems.filter { it.status != LineStatus.FULFILLED }
}

// ── QR payload ────────────────────────────────────────────────────────────────

private data class BillQrPayload(
    val billRef: String          = "",
    val items:   List<BillQrItem> = emptyList()
)
private data class BillQrItem(
    val ean:           String   = "",
    val qty:           Int      = 1,
    val isRfidEnabled: Boolean? = null,
    val productName:   String?  = null,
    val imageUrl:      String?  = null,
    val unitPrice:     java.math.BigDecimal? = null
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class GateScanViewModel @Inject constructor(
    private val gateRepo:        GateRepository,
    private val authRepo:        AuthRepository,
    private val storeConfigRepo: StoreConfigRepository,
    private val rfid:            GateRfidAdapter,
    private val readerSettings:  ReaderSettingsRepository,
    private val gson:            Gson,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(GateState())
    private val resolvingExtraEpcs = ConcurrentHashMap.newKeySet<String>()
    val state = _state.asStateFlow()

    private val _scanEvents = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 16)
    val scanEvents = _scanEvents.asSharedFlow()

    val storeConfig: StateFlow<StoreConfig> = storeConfigRepo.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, StoreConfig.DEFAULTS)

    init {
        viewModelScope.launch {
            rfid.connectionState.collect { connected ->
                _state.update { it.copy(btConnected = connected) }
            }
        }
        viewModelScope.launch {
            rfid.triggerEvents.collect { pressed ->
                if (pressed) startRfidScan() else stopRfidScan()
            }
        }
        rfid.watchAndAutoReconnect()
        connectReader()
        loadPendingBills()
    }

    // ── BT connection ──────────────────────────────────────────────────────────

    fun connectReader() {
        viewModelScope.launch {
            _state.update { it.copy(btConnecting = true, btError = null) }
            try {
                rfid.connect()
                // The MUBR01 boots at its own hardware-default TX power, which can be
                // too low to energize tags across a full bag — push the configured
                // power (Settings → BT Device) on every successful connect.
                rfid.setTxPower(readerSettings.load().txPowerDbm)
                _state.update { it.copy(btConnecting = false, btError = null) }
            } catch (e: Exception) {
                _state.update { it.copy(btConnecting = false, btError = e.message) }
                Timber.w(e, "connectReader failed")
            }
        }
    }

    // ── Pending bills ──────────────────────────────────────────────────────────

    fun loadPendingBills() {
        viewModelScope.launch {
            when (val r = gateRepo.getPendingBills()) {
                is Result.Success -> _state.update { it.copy(pendingBills = r.data) }
                is Result.Error   -> { /* non-critical */ }
            }
        }
    }

    // ── Bill lookup ────────────────────────────────────────────────────────────

    fun onQrScanned(rawQr: String) {
        val trimmed = rawQr.trim()
        if (trimmed.isBlank()) return

        val billRef = if (trimmed.startsWith("{")) {
            val payload = try {
                gson.fromJson(trimmed, BillQrPayload::class.java)
            } catch (_: Exception) {
                _state.update { it.copy(error = "Unrecognized QR code") }
                return
            }
            if (payload.billRef.isBlank()) {
                _state.update { it.copy(error = "QR code has no bill reference") }
                return
            }
            payload.billRef
        } else trimmed

        lookupBillByRef(billRef)
    }

    /** Renders a raw ISO-8601 timestamp from the backend as a short local time for
     *  display in the "already checked" error — falls back to the raw string if it
     *  doesn't parse (defensive against format drift, never worth crashing over). */
    private fun formatCheckedAt(iso: String): String = try {
        val instant = java.time.OffsetDateTime.parse(iso)
        instant.atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a"))
    } catch (_: Exception) {
        iso
    }

    private fun lookupBillByRef(billRef: String) {
        viewModelScope.launch {
            _state.update { it.copy(isResolvingBill = true, hasBill = true, billRef = billRef, error = null) }
            when (val r = gateRepo.lookupBill(billRef)) {
                is Result.Success -> {
                    if (r.data.status != "PENDING") {
                        _state.update {
                            it.copy(
                                isResolvingBill = false,
                                hasBill = false, billRef = "",
                                error = "Bill '$billRef' was already ${r.data.status.lowercase()} at the gate" +
                                    (r.data.gateCheckedAt?.let { ts -> " (${formatCheckedAt(ts)})" } ?: "") +
                                    " — contact your manager if it needs re-checking."
                            )
                        }
                        // Our cached pending list is stale for this bill — refresh it.
                        loadPendingBills()
                        return@launch
                    }
                    val payload = BillQrPayload(
                        billRef = billRef,
                        items   = r.data.items.map {
                            BillQrItem(
                                ean = it.ean, qty = it.qty,
                                isRfidEnabled = it.isRfidEnabled, productName = it.productName,
                                imageUrl = it.imageUrl, unitPrice = it.unitPrice
                            )
                        }
                    )
                    processBillPayload(payload)
                }
                is Result.Error -> _state.update {
                    it.copy(isResolvingBill = false, hasBill = false, billRef = "", error = r.message)
                }
            }
        }
    }

    private fun processBillPayload(payload: BillQrPayload) {
        val initialItems = payload.items.map { qrItem ->
            BillLineItem(
                ean           = qrItem.ean,
                sku           = "",
                productName   = qrItem.productName ?: "Resolving…",
                qtyRequired   = qrItem.qty.coerceAtLeast(1),
                isRfidEnabled = qrItem.isRfidEnabled,
                imageUrl      = qrItem.imageUrl,
                unitPrice     = qrItem.unitPrice
            )
        }
        _state.update {
            it.copy(
                billRef         = payload.billRef.ifBlank { "Bill" },
                items           = initialItems,
                extraEpcs       = emptyList(),
                extraBarcodes   = emptyList(),
                hasBill         = true,
                released        = false,
                isResolvingBill = true,
                error           = null
            )
        }
        resolveAllEans(payload.items)
    }

    /** Confirmed non-RFID items (isRfidEnabled == false, from the bill lookup itself) skip
     *  this entirely — there are no EPCs to resolve, and resolveEan's response doesn't carry
     *  isRfidEnabled from the real backend, so calling it here would silently clobber the
     *  correct bill-lookup-sourced value back to null. */
    private fun resolveAllEans(qrItems: List<BillQrItem>) {
        viewModelScope.launch {
            val results = qrItems.filter { it.isRfidEnabled != false }.map { qrItem ->
                async { qrItem to gateRepo.resolveEan(qrItem.ean) }
            }.awaitAll()

            _state.update { s ->
                val updatedItems = s.items.map { lineItem ->
                    val match = results.find { (qi, _) -> qi.ean == lineItem.ean }
                    when (val result = match?.second) {
                        is Result.Success -> lineItem.copy(
                            sku           = result.data.sku ?: "",
                            productName   = result.data.productName,
                            isRfidEnabled = result.data.isRfidEnabled,
                            validEpcs     = if (result.data.isRfidEnabled != false) result.data.epcs.toSet() else emptySet(),
                            imageUrl      = result.data.imageUrl ?: lineItem.imageUrl
                        )
                        is Result.Error -> lineItem.copy(
                            productName  = "EAN: ${lineItem.ean}",
                            resolveError = result.message
                        )
                        null -> lineItem
                    }
                }
                val allValidEpcs = updatedItems.filter { !it.isNonRfid }.flatMap { it.validEpcs }.toSet()
                rfid.setKnownEpcs(allValidEpcs)
                s.copy(items = updatedItems, isResolvingBill = false)
            }
        }
    }

    // ── RFID scan ──────────────────────────────────────────────────────────────

    fun startRfidScan() {
        if (_state.value.isScanning || !_state.value.hasBill) return
        if (!rfid.isConnected) {
            _state.update { it.copy(error = "RFID reader not connected — tap Reconnect") }
            return
        }
        _state.update { it.copy(isScanning = true, error = null) }
        rfid.startInventory { epc -> onEpcRead(epc) }
    }

    fun stopRfidScan() {
        rfid.stopInventory()
        _state.update { it.copy(isScanning = false) }
    }

    private fun onEpcRead(epc: String) {
        val snapshot     = _state.value
        val targetIdx    = snapshot.items.indexOfFirst { line ->
            !line.isNonRfid && epc in line.validEpcs && line.matchedEpcs.size < line.qtyRequired
        }
        val allValidEpcs = snapshot.items.filter { !it.isNonRfid }.flatMap { it.validEpcs }.toSet()

        val event: ScanEvent? = when {
            targetIdx != -1                                        -> ScanEvent.Matched(snapshot.items[targetIdx].ean)
            epc !in allValidEpcs && epc !in snapshot.extraEpcs    -> ScanEvent.Extra
            else                                                   -> ScanEvent.Duplicate
        }
        // Retry a lookup after a transient failure when the reader reports this EPC
        // again. Avoid launching duplicate requests while one is already in flight.
        if (epc !in allValidEpcs) resolveExtraEpc(epc)

        _state.update { s ->
            val idx = s.items.indexOfFirst { line ->
                !line.isNonRfid && epc in line.validEpcs && line.matchedEpcs.size < line.qtyRequired
            }
            if (idx != -1) {
                val line = s.items[idx]
                if (epc !in line.matchedEpcs) {
                    val updated = line.copy(matchedEpcs = line.matchedEpcs + epc)
                    s.copy(items = s.items.toMutableList().also { it[idx] = updated })
                } else s
            } else {
                val allValid = s.items.filter { !it.isNonRfid }.flatMap { it.validEpcs }.toSet()
                if (epc !in allValid && epc !in s.extraEpcs)
                    s.copy(extraEpcs = s.extraEpcs + epc)
                else s
            }
        }

        event?.let { _scanEvents.tryEmit(it) }
        vibrate(event)
    }

    /** Looks up an unexpected/extra EPC so the UI can show the real product it belongs to,
     *  instead of a raw tag string — falls back to "Unknown EPC" only on a confirmed 404. */
    private fun resolveExtraEpc(epc: String) {
        if (_state.value.extraEpcInfo.containsKey(epc) || !resolvingExtraEpcs.add(epc)) return
        viewModelScope.launch {
            try {
                when (val result = gateRepo.identifyEpc(epc)) {
                    is Result.Success -> _state.update { it.copy(extraEpcInfo = it.extraEpcInfo + (epc to result.data)) }
                    is Result.Error   -> { /* leave unresolved so a later reader callback can retry */ }
                }
            } finally {
                resolvingExtraEpcs.remove(epc)
            }
        }
    }

    /** Looks up an unexpected/extra barcode so the UI can show the real product (and its
     *  image, if any) instead of a raw EAN — reuses the same EAN resolution the bill lines
     *  use, since a scanned extra is just an EAN with no matching pending line. */
    private fun resolveExtraBarcode(ean: String) {
        if (_state.value.extraBarcodeInfo.containsKey(ean)) return
        viewModelScope.launch {
            when (val result = gateRepo.resolveEan(ean)) {
                is Result.Success -> _state.update { it.copy(extraBarcodeInfo = it.extraBarcodeInfo + (ean to result.data)) }
                is Result.Error   -> { /* leave unresolved (key absent) so the UI can retry later */ }
            }
        }
    }

    // ── Non-RFID barcode verification ──────────────────────────────────────────

    fun onBarcodeScanned(rawEan: String) {
        val ean = rawEan.trim()
        if (ean.isBlank()) return

        val snapshot    = _state.value
        val targetIdx   = snapshot.items.indexOfFirst { line ->
            line.isNonRfid && line.ean == ean && line.barcodeVerifiedCount < line.qtyRequired
        }
        val event = if (targetIdx != -1) ScanEvent.BarcodeVerified(ean) else ScanEvent.BarcodeNotOnBill

        _state.update { s ->
            val idx = s.items.indexOfFirst { line ->
                line.isNonRfid && line.ean == ean && line.barcodeVerifiedCount < line.qtyRequired
            }
            if (idx != -1) {
                val updated = s.items[idx].copy(
                    barcodeVerifiedCount = s.items[idx].barcodeVerifiedCount + 1
                )
                s.copy(items = s.items.toMutableList().also { it[idx] = updated })
            } else if (ean !in s.extraBarcodes) {
                s.copy(extraBarcodes = s.extraBarcodes + ean)
            } else s
        }
        if (targetIdx == -1) resolveExtraBarcode(ean)

        _scanEvents.tryEmit(event)
        vibrate(event)
    }

    // ── Release ────────────────────────────────────────────────────────────────

    fun releaseCustomer(flagged: Boolean = false) {
        val s           = _state.value
        val matchedEpcs = s.items.flatMap { it.matchedEpcs }
        val nonRfidSales = s.items
            .filter { it.isNonRfid && it.barcodeVerifiedCount > 0 }
            .associate { it.ean to it.barcodeVerifiedCount }

        if (matchedEpcs.isEmpty() && nonRfidSales.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(isReleasing = true, error = null) }
            stopRfidScan()

            val outcome      = if (flagged) "FLAGGED" else "RELEASED"
            val epcResult    = if (matchedEpcs.isNotEmpty()) gateRepo.markSold(matchedEpcs) else Result.Success(0)
            val nonRfidResult = if (nonRfidSales.isNotEmpty()) gateRepo.markNonRfidSold(nonRfidSales) else Result.Success(emptyMap<String, Int>())

            val error = (epcResult as? Result.Error)?.message ?: (nonRfidResult as? Result.Error)?.message
            if (error != null) {
                _state.update { it.copy(isReleasing = false, error = error) }
                return@launch
            }

            val markedCount = (epcResult as Result.Success).data
            val checkResult = gateRepo.recordGateCheck(
                billRef       = s.billRef,
                expectedCount = s.totalRequired,
                matchedCount  = s.totalMatched,
                extraCount    = s.extraEpcs.size + s.extraBarcodes.size,
                outcome       = outcome,
                epcsMatched   = matchedEpcs,
                epcsExtra     = s.extraEpcs
            )
            val gateCheckId = (checkResult as? Result.Success)?.data
            _state.update {
                it.copy(
                    isReleasing = false, released = true, markedCount = markedCount,
                    gateCheckId = gateCheckId, needsResolution = flagged
                )
            }
            loadPendingBills()
        }
    }

    /** Records how the guard/manager closed out a FLAGGED release. */
    fun resolveFlag(resolution: String) {
        val gateCheckId = _state.value.gateCheckId ?: run {
            _state.update { it.copy(needsResolution = false) }
            return
        }
        viewModelScope.launch {
            gateRepo.resolveGateCheck(gateCheckId, resolution)
            _state.update { it.copy(needsResolution = false) }
        }
    }

    // ── Reset / logout ─────────────────────────────────────────────────────────

    fun reset() {
        stopRfidScan()
        _state.update {
            GateState(pendingBills = it.pendingBills, btConnected = it.btConnected)
        }
    }

    fun startNextCustomer() {
        reset()
        loadPendingBills()
    }

    fun logout() {
        stopRfidScan()
        viewModelScope.launch { rfid.disconnect() }
        authRepo.logout()
    }

    override fun onCleared() {
        super.onCleared()
        rfid.stopInventory()
    }

    // ── Haptic helpers ─────────────────────────────────────────────────────────

    private fun vibrate(event: ScanEvent?) {
        val v = context.getSystemService(android.os.Vibrator::class.java) ?: return
        when (event) {
            is ScanEvent.Matched,
            is ScanEvent.BarcodeVerified -> v.vibrate(
                android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            )
            ScanEvent.Extra,
            ScanEvent.BarcodeNotOnBill   -> v.vibrate(
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 100, 60, 100), -1)
            )
            ScanEvent.Duplicate, null    -> {}
        }
    }
}
