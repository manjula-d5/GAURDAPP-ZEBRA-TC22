package com.storelense.gateBt.ui.gate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storelense.gateBt.data.remote.dto.GateCheckDto

private val TealPrimary    = Color(0xFF0F766E)
private val TealAccent     = Color(0xFF14B8A6)
private val GreenFulfilled = Color(0xFF16A34A)
private val RedFlagged     = Color(0xFFDC2626)
private val SubText        = Color(0xFF64748B)
private val DarkText       = Color(0xFF1E293B)
private val SurfaceWhite   = Color.White
private val BgPage         = Color(0xFFF5F7FA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    vm: DashboardViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Shift — Dashboard", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Color.White)
                    }
                }
            )
        },
        containerColor = BgPage
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TealPrimary)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SummaryCards(state) }

            if (!state.error.isNullOrBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                        shape  = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = RedFlagged)
                            Spacer(Modifier.width(8.dp))
                            Text(state.error!!, fontSize = 13.sp, color = RedFlagged)
                        }
                    }
                }
            }

            if (state.checks.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Receipt, null, Modifier.size(48.dp), tint = SubText.copy(alpha = 0.4f))
                        Spacer(Modifier.height(12.dp))
                        Text("No checks yet this shift", color = SubText, fontSize = 14.sp)
                    }
                }
            } else {
                item {
                    Text(
                        "Recent checks (${state.checks.size})",
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = DarkText
                    )
                }
                items(state.checks, key = { it.id ?: it.billRef ?: it.hashCode().toString() }) { check ->
                    GateCheckCard(check)
                }
            }
        }
    }
}

@Composable
private fun SummaryCards(state: DashboardState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("Checked", state.totalChecked.toString(), TealPrimary, Icons.Default.QrCodeScanner, Modifier.weight(1f))
        StatCard("Released", state.totalReleased.toString(), GreenFulfilled, Icons.Default.CheckCircle, Modifier.weight(1f))
        StatCard("Flagged", state.totalFlagged.toString(), RedFlagged, Icons.Default.Flag, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape     = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.TrendingUp, null, Modifier.size(22.dp), tint = TealAccent)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Avg. match rate", fontSize = 12.sp, color = SubText)
                Text(
                    "${(state.avgMatch * 100).toInt()}%",
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TealPrimary
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier  = modifier,
        colors    = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape     = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = color)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 11.sp, color = SubText, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GateCheckCard(check: GateCheckDto) {
    val outcomeColor = when (check.outcome) {
        "RELEASED"  -> GreenFulfilled
        "FLAGGED"   -> RedFlagged
        "ABANDONED" -> SubText
        else        -> SubText
    }
    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape     = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    check.billRef ?: "—",
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = DarkText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${check.matchedCount} / ${check.expectedCount} items",
                    fontSize = 12.sp, color = SubText
                )
                if (check.extraCount > 0) {
                    Text("${check.extraCount} extra", fontSize = 11.sp, color = Color(0xFFEA580C))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    check.outcome,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, color = outcomeColor
                )
                check.checkedAt?.let { ts ->
                    val short = ts.take(16).replace("T", " ")
                    Text(short, fontSize = 11.sp, color = SubText)
                }
            }
        }
        LinearProgressIndicator(
            progress   = { if (check.expectedCount > 0) check.matchedCount.toFloat() / check.expectedCount else 0f },
            modifier   = Modifier.fillMaxWidth().height(3.dp),
            color      = outcomeColor,
            trackColor = outcomeColor.copy(alpha = 0.15f)
        )
    }
}
