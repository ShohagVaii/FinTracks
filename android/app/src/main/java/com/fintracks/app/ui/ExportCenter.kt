package com.fintracks.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
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
import com.fintracks.app.data.id
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId

private val ExpCyan = Color(0xFF38C7E0)
private val ExpGreen = Color(0xFF2ECC71)

private fun fmtLabel(f: Export.Format): String = when (f) {
    Export.Format.PDF -> "PDF"
    Export.Format.XLSX -> "Excel"
    Export.Format.CSV -> "CSV"
    Export.Format.JSON -> "JSON"
}

private fun lastExport(ctx: Context): Pair<Long, Export.Format>? {
    val p = ctx.getSharedPreferences("fintrack_export", Context.MODE_PRIVATE)
    val at = p.getLong("last_at", 0L)
    val fmt = p.getString("last_fmt", null)?.let { runCatching { Export.Format.valueOf(it) }.getOrNull() }
    return if (at > 0L && fmt != null) at to fmt else null
}

private fun dateText(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return bnDigits(d.dayOfMonth.toString()) + " " + BN_MONTHS[d.monthValue - 1] + " " + bnDigits(d.year.toString())
}

/** Export Center: pick the range and datasets, then save as PDF, Excel, CSV or JSON. Fully offline. */
@Composable
fun ExportCenterScreen(state: UiState, notesFlow: StateFlow<List<JsonObject>>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes by notesFlow.collectAsState()
    var period by remember { mutableStateOf(Period.MONTH) }
    var customFrom by remember { mutableStateOf(todayIso().substring(0, 8) + "01") }
    var customTo by remember { mutableStateOf(todayIso()) }
    var showPicker by remember { mutableStateOf(false) }
    var sets by remember { mutableStateOf(setOf(ExportSet.TX, ExportSet.DEBTS, ExportSet.CREDIT)) }
    var format by remember { mutableStateOf(Export.Format.PDF) }
    var logo by remember { mutableStateOf(true) }
    var pdfSummary by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(lastExport(ctx)) }
    val range = rangeFor(period, customFrom, customTo)
    val txCount = filterByRange(state.rows("transactions"), range).size
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Range card with the change pill.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(faint(0.05f))
                .border(1.dp, faint(0.1f), RoundedCornerShape(18.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.DateRange, contentDescription = null, tint = ExpCyan, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("রেঞ্জ", "Range"), color = muted, fontSize = 12.sp)
                Text(rangeText(range), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Text(
                tr("বদলান", "Change"), color = ExpCyan, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, ExpCyan, RoundedCornerShape(50))
                    .clickable { showPicker = !showPicker }.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
        if (showPicker) {
            PeriodPicker(period, customFrom, customTo, onPeriod = { period = it }, onFrom = { customFrom = it }, onTo = { customTo = it })
        }

        Text(tr("যা যা থাকবে", "Included"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        val toggles = listOf(
            Triple(ExportSet.TX, tr("লেনদেন", "Transactions"), txCount),
            Triple(ExportSet.DEBTS, tr("ধার-দেনা", "Debts"), state.rows("debts").size),
            Triple(ExportSet.CREDIT, tr("বাকি খাতা", "Credit"), state.rows("credit_shops").size),
            Triple(ExportSet.NOTES, tr("নোট", "Notes"), notes.size)
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(faint(0.05f))
                .border(1.dp, faint(0.1f), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp)
        ) {
            toggles.forEachIndexed { i, (set, label, count) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(faint(0.1f)))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(bnDigits(count.toString()) + " " + tr("টি", ""), color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = set in sets,
                        onCheckedChange = { on -> sets = if (on) sets + set else sets - set }
                    )
                }
            }
        }

        Text(tr("ফরম্যাট", "Format"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        val formats = listOf(
            Export.Format.PDF to Triple(tr("রিপোর্ট", "Report"), "PDF", Color(0xFFFB7185)),
            Export.Format.XLSX to Triple(tr("স্প্রেডশিট", "Spreadsheet"), "Excel", Color(0xFF2ECC71)),
            Export.Format.CSV to Triple(tr("ডাটা ফাইল", "Data file"), "CSV", Color(0xFFFBBF24)),
            Export.Format.JSON to Triple(tr("ব্যাকআপ ডাটা", "Backup data"), "JSON", Color(0xFF8B7CF6))
        )
        formats.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (fmt, meta) ->
                    val selected = format == fmt
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(faint(0.05f))
                            .border(if (selected) 2.dp else 1.dp, if (selected) ExpCyan else faint(0.1f), RoundedCornerShape(18.dp))
                            .clickable { format = fmt }.padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.clip(RoundedCornerShape(10.dp)).background(meta.third).padding(horizontal = 8.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(meta.second, color = Color(0xFF0B1220), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Column {
                                Text(meta.second, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(meta.first, color = muted, fontSize = 12.sp)
                            }
                        }
                        if (selected) {
                            Box(
                                Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(ExpGreen),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        if (format == Export.Format.PDF) {
            Text(tr("PDF অপশন", "PDF options"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(faint(0.05f))
                    .border(1.dp, faint(0.1f), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("লোগোসহ হেডার", "Header with logo"), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = logo, onCheckedChange = { logo = it })
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(faint(0.1f)))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("সারাংশ পেজ", "Summary page"), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = pdfSummary, onCheckedChange = { pdfSummary = it })
                }
            }
        }

        Button(
            enabled = !busy && sets.isNotEmpty(),
            onClick = {
                busy = true
                val chosen = format
                scope.launch {
                    try {
                        val title = tr("FinTrack এক্সপোর্ট", "FinTrack export") + " - " + range.label
                        val out = withContext(Dispatchers.IO) {
                            val secs = ExportBundle.sections(state, range, sets, notes)
                            val sum = ExportBundle.summary(state, range)
                            ExportBundle.build(ctx, chosen, secs, title, sum, logo, pdfSummary)
                        }
                        Export.share(ctx, out.file, out.mime, title)
                        val now = System.currentTimeMillis()
                        ctx.getSharedPreferences("fintrack_export", Context.MODE_PRIVATE).edit()
                            .putLong("last_at", now).putString("last_fmt", chosen.name).apply()
                        last = now to chosen
                    } catch (e: Exception) {
                        flash(tr("এক্সপোর্ট ব্যর্থ: ${e.message}", "Export failed: ${e.message}"))
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ExpCyan, contentColor = Color(0xFF0B1220))
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("এক্সপোর্ট করুন", "Export"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        last?.let { (at, fmt) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(faint(0.05f))
                    .border(1.dp, faint(0.1f), RoundedCornerShape(18.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("সর্বশেষ এক্সপোর্ট", "Last export"), color = muted, fontSize = 12.sp)
                    Text(dateText(at) + " — " + fmtLabel(fmt), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Box(Modifier.size(24.dp).clip(CircleShape).background(ExpGreen), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
