package com.storelense.gateBt.ui.gate

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storelense.gateBt.data.remote.dto.BillSummaryDto
import com.storelense.gateBt.data.remote.dto.IdentifyEpcResponse
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

// ── Colours ───────────────────────────────────────────────────────────────────

private val TealPrimary    = Color(0xFF0F766E)
private val TealAccent     = Color(0xFF14B8A6)
private val BgPage         = Color(0xFFF5F7FA)
private val SurfaceWhite   = Color.White
private val GreenFulfilled = Color(0xFF16A34A)
private val AmberPartial   = Color(0xFFD97706)
private val GrayPending    = Color(0xFF9CA3AF)
private val OrangeExtra    = Color(0xFFEA580C)
private val DarkText       = Color(0xFF1E293B)
private val SubText        = Color(0xFF64748B)
private val AmberBt        = Color(0xFFF59E0B)

/** Set by GateScanScreen so any nested card can trigger the full-screen zoom viewer
 *  without threading a callback through every intermediate composable. */
private val LocalImageZoomHandler = compositionLocalOf<(String) -> Unit> { {} }

private fun resolveImageUrl(relativeUrl: String) =
    com.storelense.gateBt.BuildConfig.BASE_URL.trimEnd('/') + relativeUrl

@Composable
private fun ZoomableImageDialog(imageUrl: String, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(imageUrl).crossfade(true).build(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offsetX, translationY = offsetY
                    )
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GateScanScreen(
    onLogout:    () -> Unit,
    onDashboard: () -> Unit = {},
    onSettings:  () -> Unit = {},
    vm: GateScanViewModel = hiltViewModel()
) {
    val state       by vm.state.collectAsStateWithLifecycle()
    val storeConfig by vm.storeConfig.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val handleZebraScan by rememberUpdatedState<(String) -> Unit> { code ->
        if (state.hasBill) vm.onBarcodeScanned(code) else vm.onQrScanned(code)
    }

    DisposableEffect(context) {
        val scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                intent.getStringExtra(ZebraDataWedge.DATA_STRING)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(handleZebraScan)
            }
        }
        ContextCompat.registerReceiver(
            context,
            scanReceiver,
            IntentFilter(ZebraDataWedge.SCAN_ACTION).apply {
                addCategory("android.intent.category.DEFAULT")
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { context.unregisterReceiver(scanReceiver) }
    }

    LaunchedEffect(context) { ZebraDataWedge.configure(context) }

    var zoomedImageUrl by remember { mutableStateOf<String?>(null) }
    var flashEan by remember { mutableStateOf<String?>(null) }
    val toneGenerator = remember {
        android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80)
    }
    DisposableEffect(Unit) { onDispose { toneGenerator.release() } }

    LaunchedEffect(vm) {
        vm.scanEvents.collect { event ->
            when (event) {
                is ScanEvent.Matched         -> {
                    toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
                    flashEan = event.ean
                }
                is ScanEvent.BarcodeVerified -> {
                    toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
                    flashEan = event.ean
                }
                ScanEvent.BarcodeNotOnBill   -> toneGenerator.startTone(android.media.ToneGenerator.TONE_CDMA_PIP, 250)
                ScanEvent.Extra              -> toneGenerator.startTone(android.media.ToneGenerator.TONE_CDMA_PIP, 250)
                ScanEvent.Duplicate          -> {}
            }
        }
    }
    LaunchedEffect(flashEan) {
        if (flashEan != null) { kotlinx.coroutines.delay(600); flashEan = null }
    }

    CompositionLocalProvider(LocalImageZoomHandler provides { url -> zoomedImageUrl = url }) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "StoreLense Gate · TC22",
                            fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White
                        )
                        if (state.hasBill && state.billRef.isNotBlank())
                            Text(state.billRef, fontSize = 12.sp, color = TealAccent)
                    }
                },
                colors  = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A)),
                actions = {
                    // RFID reader connection indicator in top bar
                    Icon(
                        imageVector       = Icons.Default.Bluetooth,
                        contentDescription = if (state.btConnected) "RFID reader connected" else "RFID reader disconnected",
                        tint               = if (state.btConnected) TealAccent else AmberBt,
                        modifier           = Modifier.padding(end = 4.dp).size(20.dp)
                    )
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "BT device settings", tint = Color.White)
                    }
                    IconButton(onClick = onDashboard) {
                        Icon(Icons.Default.Dashboard, "My shift dashboard", tint = Color.White)
                    }
                    if (state.hasBill) {
                        IconButton(onClick = { vm.reset() }) {
                            Icon(Icons.Default.Refresh, "New customer", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { vm.logout(); onLogout() }) {
                        Icon(Icons.Default.ExitToApp, "Logout", tint = Color.White)
                    }
                }
            )
        },
        containerColor = BgPage
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // BT disconnected / connecting banner — shown whenever reader is unavailable
            BtStatusBanner(
                connected  = state.btConnected,
                connecting = state.btConnecting,
                btError    = state.btError,
                onReconnect = { vm.connectReader() },
                onOpenSettings = onSettings
            )

            GateStepper(
                currentStep = when {
                    state.released -> 3
                    !state.hasBill  -> 1
                    else            -> 2
                }
            )

            Box(modifier = Modifier.weight(1f)) {
            when {
                state.released        -> ReleasedView(
                    markedCount      = state.markedCount,
                    items            = state.items,
                    extraEpcs        = state.extraEpcs,
                    extraBarcodes    = state.extraBarcodes,
                    extraEpcInfo     = state.extraEpcInfo,
                    extraBarcodeInfo = state.extraBarcodeInfo,
                    needsResolution  = state.needsResolution,
                    onResolve        = { vm.resolveFlag(it) },
                    onNextCustomer   = { vm.startNextCustomer() }
                )
                !state.hasBill        -> NoBillView(
                    onQrScanned         = { vm.onQrScanned(it) },
                    errorMessage        = state.error,
                    pendingBills        = state.pendingBills,
                    manualEntryEnabled  = storeConfig.manualEntryEnabled
                )
                state.isResolvingBill -> ResolvingView()
                else                  -> ActiveGateView(
                    state             = state,
                    flashEan          = flashEan,
                    rfidVerifyEnabled = storeConfig.rfidVerifyEnabled,
                    strictMode        = storeConfig.strictMode,
                    onStart           = { vm.startRfidScan() },
                    onStop            = { vm.stopRfidScan() },
                    onRelease         = { vm.releaseCustomer(flagged = false) },
                    onFlagRelease     = { vm.releaseCustomer(flagged = true) },
                    onBarcodeScanned  = { vm.onBarcodeScanned(it) },
                    onCancelBill      = { vm.reset() }
                )
            }
            }
        }
    }
    }

    zoomedImageUrl?.let { url ->
        ZoomableImageDialog(imageUrl = url, onDismiss = { zoomedImageUrl = null })
    }
}

// ── 3-step progress stepper (Bill → Bag → Result) ─────────────────────────────

@Composable
private fun GateStepper(currentStep: Int) {
    val labels = listOf("Bill", "Bag", "Result")
    Row(
        modifier          = Modifier.fillMaxWidth().background(SurfaceWhite).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        labels.forEachIndexed { index, label ->
            val step = index + 1
            val done = step < currentStep
            val active = step == currentStep
            val circleColor = when {
                done   -> GreenFulfilled
                active -> TealPrimary
                else   -> Color(0xFFD1D5DB)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier         = Modifier.size(28.dp).clip(CircleShape).background(circleColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) {
                        Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White)
                    } else {
                        Text("$step", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    fontSize   = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color      = if (active || done) circleColor else SubText
                )
            }
            if (step != labels.size) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .width(40.dp)
                        .height(2.dp)
                        .background(if (step < currentStep) GreenFulfilled else Color(0xFFD1D5DB))
                )
            }
        }
    }
}

// ── BT status banner ──────────────────────────────────────────────────────────

@Composable
private fun BtStatusBanner(
    connected:  Boolean,
    connecting: Boolean,
    btError:    String?,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    if (connected) return  // nothing to show when healthy

    val bg    = if (connecting) Color(0xFFFEF9C3) else Color(0xFFFFF3CD)
    val text  = when {
        connecting -> "Connecting to RFID reader…"
        btError != null -> btError
        else           -> "RFID reader not connected"
    }
    val needsReaderSelection = btError?.let {
        it.contains("No BT reader selected", ignoreCase = true) ||
            it.contains("pair a MUBR01", ignoreCase = true)
    } == true

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (connecting) {
            CircularProgressIndicator(
                modifier    = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color       = AmberBt
            )
        } else {
            Icon(Icons.Default.Nfc, null, Modifier.size(16.dp), tint = AmberBt)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontSize = 12.sp,
            color    = Color(0xFF92400E),
            modifier = Modifier.weight(1f)
        )
        if (!connecting) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = if (needsReaderSelection) onOpenSettings else onReconnect,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    if (needsReaderSelection) "Settings" else "Reconnect",
                    fontSize = 12.sp,
                    color = AmberBt,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ── No-bill placeholder ───────────────────────────────────────────────────────

@Composable
private fun NoBillView(
    onQrScanned:        (String) -> Unit,
    errorMessage:       String?  = null,
    pendingBills:       List<BillSummaryDto> = emptyList(),
    manualEntryEnabled: Boolean  = true
) {
    val context        = LocalContext.current
    var scanInput      by remember { mutableStateOf("") }

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape).background(TealPrimary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.QrCodeScanner, null, Modifier.size(44.dp), tint = TealPrimary)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Scan Customer Bill",
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            color = DarkText, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Scan barcode or type bill reference below",
            fontSize = 14.sp, color = SubText, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value         = scanInput,
            onValueChange = { v ->
                val hasTerminator = v.contains('\n') || v.contains('\r')
                if (hasTerminator) {
                    val code = v.replace("\r", "").replace("\n", "").trim()
                    if (code.isNotBlank()) onQrScanned(code)
                    scanInput = ""
                } else {
                    // Zebra DataWedge keyboard-wedge output enters this field even when
                    // manual typing is disabled. The scan terminator submits the value above.
                    scanInput = v
                }
            },
            modifier        = Modifier.fillMaxWidth(),
            label           = { Text(if (manualEntryEnabled) "Bill barcode / reference" else "Bill barcode (scan only)") },
            placeholder     = { Text(if (manualEntryEnabled) "Scan or type here…" else "Use scanner or camera") },
            singleLine      = true,
            readOnly        = false,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = if (manualEntryEnabled) ImeAction.Done else ImeAction.None
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (manualEntryEnabled) {
                        val code = scanInput.trim()
                        if (code.isNotBlank()) { onQrScanned(code); scanInput = "" }
                    }
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TealPrimary,
                focusedLabelColor  = TealPrimary
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (manualEntryEnabled) "Scan with the Zebra trigger or type and press Done"
            else "Press the Zebra TC22 scan trigger to scan the bill barcode",
            fontSize = 11.sp, color = SubText
        )

        if (!errorMessage.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                shape  = RoundedCornerShape(8.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = Color(0xFFDC2626))
                    Spacer(Modifier.width(8.dp))
                    Text(errorMessage, fontSize = 13.sp, color = Color(0xFFDC2626))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                ZebraDataWedge.startScan(context)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
        ) {
            Icon(Icons.Default.QrCodeScanner, null)
            Spacer(Modifier.width(8.dp))
            Text("Scan Bill", fontWeight = FontWeight.SemiBold)
        }

        if (pendingBills.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            PendingBillsSection(pendingBills)
        }
    }
}

// ── Store-wide unchecked bills ────────────────────────────────────────────────

@Composable
private fun PendingBillsSection(pendingBills: List<BillSummaryDto>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ReportProblem, null, Modifier.size(16.dp), tint = Color(0xFFDC2626))
            Spacer(Modifier.width(6.dp))
            Text(
                "Unbilled bills in store (not yet checked at gate)",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDC2626)
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            pendingBills.forEach { bill ->
                Card(
                    modifier  = Modifier.fillMaxWidth(),
                    colors    = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape     = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            bill.billRef,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DarkText,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${bill.totalItems} item${if (bill.totalItems != 1) "s" else ""}",
                            fontSize = 11.sp, color = SubText
                        )
                    }
                }
            }
        }
    }
}

// ── Resolving ─────────────────────────────────────────────────────────────────

@Composable
private fun ResolvingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = TealPrimary)
        Spacer(Modifier.height(16.dp))
        Text("Looking up products…", color = SubText, fontSize = 14.sp)
    }
}

// ── Released ──────────────────────────────────────────────────────────────────

@Composable
private fun ReleasedView(
    markedCount: Int,
    items: List<BillLineItem>,
    extraEpcs: List<String>,
    extraBarcodes: List<String> = emptyList(),
    extraEpcInfo: Map<String, IdentifyEpcResponse?> = emptyMap(),
    extraBarcodeInfo: Map<String, com.storelense.gateBt.data.remote.dto.EpcsByEanResponse?> = emptyMap(),
    needsResolution: Boolean = false,
    onResolve: (String) -> Unit = {},
    onNextCustomer: () -> Unit
) {
    val okItems      = items.filter { it.status == LineStatus.FULFILLED && !it.isNonRfid }
    val missingItems = items.filter { it.status != LineStatus.FULFILLED && !it.isNonRfid }
    val checkByEye   = items.filter { it.isNonRfid }
    val hasUnbilled  = extraEpcs.isNotEmpty() || extraBarcodes.isNotEmpty()
    val isFlagged    = hasUnbilled || missingItems.isNotEmpty()
    val bannerColor  = if (isFlagged) Color(0xFFDC2626) else GreenFulfilled

    if (isFlagged && needsResolution) {
        ResolutionBottomSheet(
            checkByEyeCount = checkByEye.size,
            onResolve       = onResolve
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgPage)) {
        Column(
            modifier = Modifier.fillMaxWidth().background(bannerColor)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (isFlagged) Icons.Default.ReportProblem else Icons.Default.CheckCircle,
                null, Modifier.size(36.dp), tint = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isFlagged) "FLAGGED" else "Customer Released",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            if (isFlagged) {
                val parts = mutableListOf<String>()
                if (hasUnbilled) parts += "${extraEpcs.size + extraBarcodes.size} unbilled"
                if (missingItems.isNotEmpty()) parts += "${missingItems.size} not in bag"
                Text(
                    parts.joinToString(" · "),
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))
                Text("Check with customer", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, textAlign = TextAlign.Center)
            } else {
                Text(
                    "$markedCount EPC${if (markedCount != 1) "s" else ""} marked as sold in RFID ledger",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, textAlign = TextAlign.Center
                )
            }
        }
        ResultStatsCard(
            total   = items.size,
            ok      = okItems.size + checkByEye.count { it.status == LineStatus.FULFILLED },
            extra   = extraEpcs.size + extraBarcodes.size,
            missing = missingItems.size
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (okItems.isNotEmpty()) {
                ResultSectionHeader("Items OK", okItems.size, GreenFulfilled, Icons.Default.CheckCircle)
                okItems.forEach { line -> BillLineCard(line) }
            }
            if (hasUnbilled) {
                ResultSectionHeader(
                    "Unbilled Items", extraEpcs.size + extraBarcodes.size,
                    Color(0xFFDC2626), Icons.Default.ReportProblem
                )
                if (extraEpcs.isNotEmpty()) {
                    ExtraEpcsCard(epcs = extraEpcs, epcInfo = extraEpcInfo)
                }
                if (extraBarcodes.isNotEmpty()) {
                    ExtraBarcodesCard(eans = extraBarcodes, barcodeInfo = extraBarcodeInfo)
                }
            }
            if (missingItems.isNotEmpty()) {
                ResultSectionHeader("Not Found in Bag", missingItems.size, AmberPartial, Icons.Default.Warning)
                missingItems.forEach { line -> BillLineCard(line) }
            }
            if (checkByEye.isNotEmpty()) {
                ResultSectionHeader(
                    "Check by Eye (No RFID)", checkByEye.size,
                    Color(0xFF2563EB), Icons.Default.Visibility
                )
                checkByEye.forEach { line -> BillLineCard(line) }
            }
        }
        Surface(tonalElevation = 4.dp, color = SurfaceWhite) {
            Button(
                onClick  = onNextCustomer,
                colors   = ButtonDefaults.buttonColors(containerColor = GreenFulfilled),
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)
            ) {
                Icon(Icons.Default.PersonAdd, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Next Customer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

// ── Result stats (Total / OK / Extra / Missing) ───────────────────────────────

@Composable
private fun ResultStatsCard(total: Int, ok: Int, extra: Int, missing: Int) {
    Card(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors    = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape     = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ResultStat("Total", total, DarkText)
            ResultStat("OK", ok, GreenFulfilled)
            ResultStat("Extra", extra, Color(0xFFDC2626))
            ResultStat("Missing", missing, AmberPartial)
        }
    }
}

@Composable
private fun ResultStat(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = SubText)
    }
}

// ── Resolution bottom sheet ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolutionBottomSheet(
    checkByEyeCount: Int,
    onResolve: (String) -> Unit
) {
    // Keep the modal in a valid visible state until a resolution choice is made.
    // A no-op onDismissRequest alone lets the sheet animate off-screen while it
    // remains composed, leaving an invisible window that can intercept taps.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden }
    )
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = { /* Resolution is required before leaving this step. */ }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Warning, null, Modifier.size(40.dp), tint = Color(0xFFD97706))
            Spacer(Modifier.height(8.dp))
            Text("Action needed", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DarkText)
            Spacer(Modifier.height(16.dp))

            if (checkByEyeCount > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                    shape    = RoundedCornerShape(8.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility, null, Modifier.size(16.dp), tint = Color(0xFF92400E))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "$checkByEyeCount item${if (checkByEyeCount != 1) "s" else ""} checked by eye — confirm you looked",
                            fontSize = 13.sp, color = Color(0xFF92400E)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Text("How was this resolved?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DarkText)
            Spacer(Modifier.height(12.dp))

            ResolutionOption(
                icon = Icons.Default.CheckCircle, iconTint = GreenFulfilled,
                bg = Color(0xFFECFDF5),
                text = "All good — customer verified",
                onClick = { onResolve("CUSTOMER_VERIFIED") }
            )
            Spacer(Modifier.height(8.dp))
            ResolutionOption(
                icon = Icons.Default.Block, iconTint = Color(0xFFDC2626),
                bg = Color(0xFFFEF2F2),
                text = "Item recovered — theft prevented",
                onClick = { onResolve("THEFT_PREVENTED") }
            )
            Spacer(Modifier.height(8.dp))
            ResolutionOption(
                icon = Icons.Default.Person, iconTint = Color(0xFF2563EB),
                bg = Color(0xFFEFF6FF),
                text = "Escalated to supervisor",
                onClick = { onResolve("ESCALATED") }
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ResolutionOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    bg: Color,
    text: String,
    onClick: () -> Unit
) {
    Card(
        modifier  = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors    = CardDefaults.cardColors(containerColor = bg),
        shape     = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = iconTint)
            }
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DarkText)
        }
    }
}

// ── Result bucket header ──────────────────────────────────────────────────────

@Composable
private fun ResultSectionHeader(
    label: String,
    count: Int,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = color, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

// ── Active gate view ──────────────────────────────────────────────────────────

@Composable
private fun ActiveGateView(
    state:             GateState,
    flashEan:          String?,
    rfidVerifyEnabled: Boolean = true,
    strictMode:        Boolean = false,
    onStart:           () -> Unit,
    onStop:            () -> Unit,
    onRelease:         () -> Unit,
    onFlagRelease:     () -> Unit,
    onBarcodeScanned:  (String) -> Unit = {},
    onCancelBill:      () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val manualCheckSectionRequester = remember { BringIntoViewRequester() }
    val manualCheckFocusRequester   = remember { FocusRequester() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Everything except the action bar scrolls as ONE unit — the header/banners/entry
        // row are fixed-height siblings that, on a crowded screen, can otherwise starve a
        // separately-weighted item list down to near-zero height.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            BillRefBar(billRef = state.billRef, onCancel = onCancelBill)

            ProgressHeader(
                totalMatched  = state.totalMatched,
                totalRequired = state.totalRequired,
                allFulfilled  = state.allFulfilled,
                isScanning    = state.isScanning,
                extraCount    = state.extraEpcs.size
            )

            BillStatsCard(
                totalItems = state.totalRequired,
                autoCheck  = state.items.filter { !it.isNonRfid }.sumOf { it.qtyRequired },
                checkByEye = state.items.filter { it.isNonRfid }.sumOf { it.qtyRequired },
                onAutoCheckClick   = { if (!state.isScanning) onStart() },
                onManualCheckClick = {
                    if (state.pendingNonRfidItems.isNotEmpty()) {
                        coroutineScope.launch {
                            manualCheckSectionRequester.bringIntoView()
                            try { manualCheckFocusRequester.requestFocus() } catch (_: Exception) {}
                        }
                    }
                }
            )

            state.error?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors   = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2))
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, Modifier.size(16.dp), tint = Color(0xFFDC2626))
                        Spacer(Modifier.width(8.dp))
                        Text(err, fontSize = 13.sp, color = Color(0xFFDC2626))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Non-RFID items banner — shown above the list when any exist
            if (state.nonRfidItems.isNotEmpty()) {
                Box(Modifier.padding(top = 8.dp)) { NonRfidWarningCard(items = state.nonRfidItems) }
            }

            Column(
                modifier            = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.items.forEach { line ->
                    BillLineCard(line, justMatched = line.ean == flashEan)
                }
                if (state.extraEpcs.isNotEmpty()) {
                    ExtraEpcsCard(epcs = state.extraEpcs, epcInfo = state.extraEpcInfo)
                }
                if (state.extraBarcodes.isNotEmpty()) {
                    ExtraBarcodesCard(eans = state.extraBarcodes, barcodeInfo = state.extraBarcodeInfo)
                }
            }

            // Non-RFID barcode entry strip — shown whenever there are pending non-RFID items
            if (state.pendingNonRfidItems.isNotEmpty()) {
                NonRfidBarcodeEntry(
                    onBarcodeScanned = onBarcodeScanned,
                    sectionRequester = manualCheckSectionRequester,
                    focusRequester   = manualCheckFocusRequester
                )
            }
        }

        ActionBar(
            isScanning         = state.isScanning,
            isReleasing        = state.isReleasing,
            canRelease         = state.totalMatched > 0,
            allFulfilled       = state.allFulfilled,
            hasExtraItems      = state.hasExtraItems,
            totalMatched       = state.totalMatched,
            totalRequired      = state.totalRequired,
            rfidVerifyEnabled  = rfidVerifyEnabled,
            strictMode         = strictMode,
            onStart            = onStart,
            onStop             = onStop,
            onRelease          = onRelease,
            onFlagRelease      = onFlagRelease
        )
    }
}

// ── Bill ref bar ──────────────────────────────────────────────────────────────

@Composable
private fun BillRefBar(billRef: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(TealPrimary, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            billRef,
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
        ) {
            Icon(Icons.Default.Close, "Cancel bill", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Bill stats (Total Items / Auto Check / Manual Check) ─────────────────────

@Composable
private fun BillStatsCard(
    totalItems: Int, autoCheck: Int, checkByEye: Int,
    onAutoCheckClick:   () -> Unit = {},
    onManualCheckClick: () -> Unit = {}
) {
    Card(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors    = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape     = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BillStat("Total Items", totalItems, DarkText)
            BillStat("Auto Check", autoCheck, TealPrimary, onClick = onAutoCheckClick)
            BillStat("Check by Eye", checkByEye, OrangeExtra, onClick = onManualCheckClick)
        }
    }
}

@Composable
private fun BillStat(label: String, value: Int, color: Color, onClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) {
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 2.dp)
        } else Modifier
    ) {
        Text("$value", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 12.sp, color = SubText, textAlign = TextAlign.Center)
    }
}

// ── Progress header ───────────────────────────────────────────────────────────

@Composable
private fun ProgressHeader(
    totalMatched: Int, totalRequired: Int,
    allFulfilled: Boolean, isScanning: Boolean, extraCount: Int
) {
    val bgColor by animateColorAsState(
        targetValue   = if (allFulfilled) GreenFulfilled else TealPrimary,
        animationSpec = tween(600), label = "headerBg"
    )
    Card(
        modifier  = Modifier.fillMaxWidth().padding(16.dp),
        colors    = CardDefaults.cardColors(containerColor = bgColor),
        shape     = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val infiniteTransition = rememberInfiniteTransition(label = "scanPulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue  = 1f, targetValue = 0.3f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label         = "pulseAlpha"
                )
                Icon(
                    if (allFulfilled) Icons.Default.CheckCircle else Icons.Default.Nfc,
                    null, Modifier.size(18.dp),
                    tint = if (allFulfilled) Color.White
                           else Color.White.copy(alpha = if (isScanning) pulseAlpha else 0.9f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (allFulfilled) "All items matched" else if (isScanning) "Scanning bag…" else "Ready to scan",
                    color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("$totalMatched / $totalRequired items", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (totalRequired > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress   = { (totalMatched.toFloat() / totalRequired).coerceIn(0f, 1f) },
                    modifier   = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color      = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
            }
            if (extraCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "$extraCount extra item${if (extraCount != 1) "s" else ""} not on bill",
                    color = Color(0xFFFEF3C7), fontSize = 12.sp
                )
            }
        }
    }
}

// ── Bill line card ────────────────────────────────────────────────────────────

@Composable
private fun BillLineCard(line: BillLineItem, justMatched: Boolean = false) {
    val statusColor = when (line.status) {
        LineStatus.FULFILLED -> GreenFulfilled
        LineStatus.PARTIAL   -> AmberPartial
        LineStatus.PENDING   -> GrayPending
    }
    val cardBg by animateColorAsState(
        targetValue   = if (justMatched) GreenFulfilled.copy(alpha = 0.18f) else SurfaceWhite,
        animationSpec = tween(600), label = "lineFlash"
    )
    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = cardBg),
        shape     = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (line.imageUrl != null) {
                val fullUrl = resolveImageUrl(line.imageUrl)
                val zoomHandler = LocalImageZoomHandler.current
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(fullUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF3F4F6))
                        .clickable { zoomHandler(fullUrl) }
                )
                Spacer(Modifier.width(12.dp))
            }
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        line.productName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = DarkText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (line.isNonRfid) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2563EB).copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("CHECK BY EYE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row {
                    if (line.sku.isNotBlank()) {
                        Text(line.sku, fontSize = 12.sp, color = SubText)
                        Text("  ·  ", fontSize = 12.sp, color = SubText)
                    }
                    Text("EAN ${line.ean}", fontSize = 12.sp, color = SubText)
                }
                line.unitPrice?.let { price ->
                    Spacer(Modifier.height(2.dp))
                    Text("₹$price", fontSize = 12.sp, color = SubText)
                }
                if (line.resolveError != null) {
                    Spacer(Modifier.height(3.dp))
                    Text("Not found in store inventory", fontSize = 11.sp, color = Color(0xFFDC2626))
                }
                if (line.isNonRfid) {
                    Spacer(Modifier.height(3.dp))
                    Text("No RFID tag — scan its barcode below to verify", fontSize = 11.sp, color = Color(0xFF2563EB))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${line.verifiedCount} / ${line.qtyRequired}",
                    fontWeight = FontWeight.Bold, fontSize = 20.sp, color = statusColor
                )
                Text(
                    when (line.status) {
                        LineStatus.FULFILLED -> "Done"
                        LineStatus.PARTIAL   -> "Partial"
                        LineStatus.PENDING   -> "Pending"
                    },
                    fontSize = 11.sp, color = statusColor
                )
            }
        }
        LinearProgressIndicator(
            progress   = { (line.verifiedCount.toFloat() / line.qtyRequired.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier   = Modifier.fillMaxWidth().height(3.dp),
            color      = statusColor,
            trackColor = statusColor.copy(alpha = 0.15f)
        )
    }
}

// ── Extra EPC warning ─────────────────────────────────────────────────────────

@Composable
private fun ExtraEpcsCard(
    epcs: List<String>,
    epcInfo: Map<String, IdentifyEpcResponse?> = emptyMap()
) {
    val count = epcs.size
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = OrangeExtra)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Extra items detected", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = OrangeExtra)
                Text(
                    "$count item${if (count != 1) "s" else ""} in bag not on this bill — inspect bag",
                    fontSize = 12.sp, color = Color(0xFF92400E)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            epcs.forEach { epc ->
                // Not in the map yet = still resolving; present with null value = confirmed
                // not registered in products.epc_tags — only then do we say "Unknown EPC".
                val hasKey = epcInfo.containsKey(epc)
                val info   = epcInfo[epc]
                ExtraEpcRow(epc = epc, info = info, resolved = hasKey)
            }
        }
    }
}

/** Styled to match BillLineCard so an unexpected tag reads the same visual language
 *  as a matched bill item — just with a red/orange status dot and an EXTRA tag
 *  instead of a matched-quantity column. */
@Composable
private fun ExtraEpcRow(
    epc: String,
    info: IdentifyEpcResponse?,
    resolved: Boolean
) {
    val statusColor = if (info != null) OrangeExtra else Color(0xFFDC2626)

    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape     = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (info?.imageUrl != null) {
                val fullUrl = resolveImageUrl(info.imageUrl)
                val zoomHandler = LocalImageZoomHandler.current
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(fullUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF3F4F6))
                        .clickable { zoomHandler(fullUrl) }
                )
                Spacer(Modifier.width(12.dp))
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (!resolved) GrayPending else statusColor)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            info != null -> info.productName ?: "Unnamed product"
                            resolved     -> "Unknown EPC"
                            else         -> "Looking up…"
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 15.sp,
                        color      = DarkText,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(statusColor.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("EXTRA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = statusColor)
                    }
                }
                Spacer(Modifier.height(2.dp))
                if (info?.sku != null) {
                    ExtraEpcDetailLine("SKU: ${info.sku}")
                }
                val eans = (info?.eans.orEmpty() + listOfNotNull(info?.ean)).distinct()
                if (eans.isNotEmpty()) {
                    ExtraEpcDetailLine("EAN: ${eans.joinToString()}")
                }
                if (info?.statusInStore != null) {
                    ExtraEpcDetailLine("Inventory: ${info.statusInStore}")
                }
                if (info?.zoneName != null) {
                    ExtraEpcDetailLine("Zone: ${info.zoneName}")
                }
                ExtraEpcDetailLine("EPC: $epc", allowWrap = true)
            }
        }
    }
}

@Composable
private fun ExtraEpcDetailLine(text: String, allowWrap: Boolean = false) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = SubText,
        maxLines = if (allowWrap) Int.MAX_VALUE else 1,
        overflow = if (allowWrap) TextOverflow.Clip else TextOverflow.Ellipsis
    )
}

// ── Non-RFID warning card ─────────────────────────────────────────────────────

@Composable
private fun NonRfidWarningCard(items: List<BillLineItem>) {
    Card(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors    = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
        shape     = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Visibility, null, Modifier.size(18.dp), tint = Color(0xFF2563EB))
            Spacer(Modifier.width(8.dp))
            Text(
                "${items.size} item${if (items.size != 1) "s" else ""} need manual barcode verification",
                fontSize   = 13.sp,
                fontWeight = FontWeight.Medium,
                color      = Color(0xFF1D4ED8)
            )
        }
    }
}

// ── Extra barcodes warning ────────────────────────────────────────────────────

@Composable
private fun ExtraBarcodesCard(
    eans: List<String>,
    barcodeInfo: Map<String, com.storelense.gateBt.data.remote.dto.EpcsByEanResponse?> = emptyMap()
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = OrangeExtra)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Extra barcodes scanned", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = OrangeExtra)
                Text(
                    "${eans.size} barcode${if (eans.size != 1) "s" else ""} not on this bill — inspect bag",
                    fontSize = 12.sp, color = Color(0xFF92400E)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            eans.forEach { ean ->
                val hasKey = barcodeInfo.containsKey(ean)
                ExtraBarcodeRow(ean = ean, info = barcodeInfo[ean], resolved = hasKey)
            }
        }
    }
}

/** Styled to match ExtraEpcRow so a scanned-but-unbilled barcode reads the same
 *  visual language as an unexpected RFID tag. */
@Composable
private fun ExtraBarcodeRow(
    ean: String,
    info: com.storelense.gateBt.data.remote.dto.EpcsByEanResponse?,
    resolved: Boolean
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape     = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (info?.imageUrl != null) {
                val fullUrl = resolveImageUrl(info.imageUrl)
                val zoomHandler = LocalImageZoomHandler.current
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(fullUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF3F4F6))
                        .clickable { zoomHandler(fullUrl) }
                )
                Spacer(Modifier.width(12.dp))
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (!resolved) GrayPending else OrangeExtra)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        info != null -> info.productName
                        resolved     -> "Unknown barcode"
                        else         -> "Looking up…"
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp,
                    color      = DarkText,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row {
                    if (info?.sku != null) {
                        Text(info.sku, fontSize = 12.sp, color = SubText)
                        Text("  ·  ", fontSize = 12.sp, color = SubText)
                    }
                    Text("EAN $ean", fontSize = 12.sp, color = SubText)
                }
            }
        }
    }
}

// ── Non-RFID barcode entry strip ──────────────────────────────────────────────

@Composable
private fun NonRfidBarcodeEntry(
    onBarcodeScanned: (String) -> Unit,
    sectionRequester: BringIntoViewRequester? = null,
    focusRequester:   FocusRequester? = null
) {
    var input by remember { mutableStateOf("") }

    Surface(
        color = SurfaceWhite,
        tonalElevation = 2.dp,
        modifier = Modifier.let { if (sectionRequester != null) it.bringIntoViewRequester(sectionRequester) else it }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "NON-RFID ITEM VERIFICATION",
                fontSize      = 11.sp,
                fontWeight    = FontWeight.Bold,
                color         = Color(0xFF2563EB),
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value         = input,
                    onValueChange = { v ->
                        val hasTerminator = v.contains('\n') || v.contains('\r')
                        if (hasTerminator) {
                            val code = v.replace("\r", "").replace("\n", "").trim()
                            if (code.isNotBlank()) onBarcodeScanned(code)
                            input = ""
                        } else {
                            input = v
                        }
                    },
                    modifier      = Modifier.weight(1f).let {
                        if (focusRequester != null) it.focusRequester(focusRequester) else it
                    },
                    label         = { Text("EAN / Barcode") },
                    placeholder   = { Text("Scan or type EAN…") },
                    singleLine    = true,
                    shape         = RoundedCornerShape(10.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val code = input.trim()
                        if (code.isNotBlank()) { onBarcodeScanned(code); input = "" }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF2563EB),
                        focusedLabelColor  = Color(0xFF2563EB)
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick  = {
                        val code = input.trim()
                        if (code.isNotBlank()) { onBarcodeScanned(code); input = "" }
                    },
                    enabled  = input.isNotBlank(),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("Verify", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Action bar ────────────────────────────────────────────────────────────────

@Composable
private fun ActionBar(
    isScanning:        Boolean,
    isReleasing:       Boolean,
    canRelease:        Boolean,
    allFulfilled:      Boolean,
    hasExtraItems:     Boolean,
    totalMatched:      Int,
    totalRequired:     Int,
    rfidVerifyEnabled: Boolean = true,
    strictMode:        Boolean = false,
    onStart:           () -> Unit,
    onStop:            () -> Unit,
    onRelease:         () -> Unit,
    onFlagRelease:     () -> Unit
) {
    val releaseBlocked = strictMode && !allFulfilled && !hasExtraItems

    Surface(tonalElevation = 4.dp, color = SurfaceWhite) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (rfidVerifyEnabled) {
                Button(
                    onClick  = if (isScanning) onStop else onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) Color(0xFF6B7280) else TealPrimary
                    )
                ) {
                    Icon(if (isScanning) Icons.Default.Stop else Icons.Default.Nfc, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isScanning) "Stop RFID Scan" else "Start RFID Scan")
                }
            }

            if (hasExtraItems && canRelease) {
                Button(
                    onClick  = onFlagRelease,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled  = !isScanning && !isReleasing,
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    if (isReleasing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Updating ledger…")
                    } else {
                        Icon(Icons.Default.Flag, null); Spacer(Modifier.width(8.dp))
                        Text("Extra Items — Flag & Release", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            } else {
                Button(
                    onClick  = onRelease,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled  = canRelease && !isScanning && !isReleasing && !releaseBlocked,
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = if (allFulfilled) GreenFulfilled else AmberPartial,
                        disabledContainerColor = Color(0xFFD1D5DB)
                    )
                ) {
                    if (isReleasing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Updating ledger…")
                    } else {
                        Icon(if (allFulfilled) Icons.Default.CheckCircle else Icons.Default.Warning, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                allFulfilled   -> "Release — All Matched"
                                releaseBlocked -> "Strict Mode — Scan all items to release"
                                else           -> "Release ($totalMatched / $totalRequired matched)"
                            },
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
