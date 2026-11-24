package com.fintracks.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Export Center: transactions for a chosen period, saved as PDF, Excel, CSV or JSON. Fully offline. */
@Composable
fun ExportCenterScreen(state: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(Period.MONTH) }
    var customFrom by remember { mutableStateOf(todayIso().substring(0, 8) + "01") }
    var customTo by remember { mutableStateOf(todayIso()) }
    var busy by remember { mutableStateOf(false) }
    val range = rangeFor(period, customFrom, customTo)
    val txs = filterByRange(state.rows("transactions"), range)
    val formats = listOf(
        Triple(Export.Format.PDF, "PDF", Icons.Default.Description),
        Triple(Export.Format.XLSX, "Excel", Icons.Default.TableChart),
        Triple(Export.Format.CSV, "CSV", Icons.Default.ListIcon),
        Triple(Export.Format.JSON, "JSON", Icons.Default.Code)
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PeriodPicker(period, customFrom, customTo, onPeriod = { period = it }, onFrom = { customFrom = it }, onTo = { customTo = it })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(range.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(tr("লেনদেন ", "Transactions ") + txs.size, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        formats.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (fmt, label, icon) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(faint(0.05f))
                            .border(1.dp, faint(0.1f), RoundedCornerShape(18.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, contentDescription = null, tint = Color(0xFF6366F1), modifier = Modifier.size(28.dp))
                        Text(label, fontWeight = FontWeight.Bold)
                        Button(
                            enabled = !busy && txs.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        val file = withContext(Dispatchers.IO) { Export.listFile(ctx, fmt, state, txs, range.label) }
                                        Export.share(ctx, file, fmt.mime, tr("FinTrack এক্সপোর্ট", "FinTrack export") + " - ${range.label}")
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, tr("এক্সপোর্ট ব্যর্থ: ${e.message}", "Export failed: ${e.message}"), Toast.LENGTH_LONG).show()
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Text(tr("এক্সপোর্ট", "Export")) }
                    }
                }
            }
        }
    }
}
