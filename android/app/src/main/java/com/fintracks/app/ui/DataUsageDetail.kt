package com.fintracks.app.ui

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext


/** One window of one UID on one network: download, upload, and the foreground share of that traffic. */
private class DuUidWindow(val rx: Long, val tx: Long, val fg: Long)

private class DuUidLoad(val usage: List<DuSlotUsage>, val fg: Long)

/** Sums NetworkStats buckets of one UID. Foreground is the bucket state reported as foreground. */
private fun duUidWindow(ctx: Context, type: Int, uid: Int, start: Long, end: Long): DuUidWindow {
    if (end <= start) return DuUidWindow(0L, 0L, 0L)
    val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
    return try {
        val stats: NetworkStats = nsm.queryDetailsForUid(type, null, start, end, uid)
        var rx = 0L
        var tx = 0L
        var fg = 0L
        val b = NetworkStats.Bucket()
        try {
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b)
                rx += b.rxBytes
                tx += b.txBytes
                if (b.state == NetworkStats.Bucket.STATE_FOREGROUND) fg += b.rxBytes + b.txBytes
            }
        } finally {
            stats.close()
        }
        DuUidWindow(rx, tx, fg)
    } catch (e: Exception) {
        DuUidWindow(0L, 0L, 0L)
    }
}

private class DuAppInfo(val firstInstall: Long?, val lastUpdate: Long?, val version: String?)

/** One app's usage page: total, its own period and filter, stacked chart, split cards, app info. */
@Composable
fun DataUsageAppDetail(app: DuApp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val fs = remember { PeriodFilterState(today) }
    var selected by remember { mutableIntStateOf(-1) }
    var load by remember { mutableStateOf<DuUidLoad?>(null) }
    val range = fs.range
    val slots = remember(fs.period, range.from, range.to) { duSlots(fs.period, range, zone) }
    val cardBg = faint(0.05f)
    val cardBorder = faint(0.1f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(fs.period, range.from, range.to, fs.medium) { selected = -1 }

    // Bars and foreground share refresh every minute; the window is the slot list the screen is showing.
    LaunchedEffect(slots) {
        while (isActive) {
            val now = System.currentTimeMillis()
            load = withContext(Dispatchers.IO) {
                val usage = slots.map { s ->
                    val end = minOf(s.end, now)
                    val m = duUidWindow(ctx, ConnectivityManager.TYPE_MOBILE, app.uid, s.start, end)
                    val w = duUidWindow(ctx, ConnectivityManager.TYPE_WIFI, app.uid, s.start, end)
                    DuSlotUsage(s, m.rx, m.tx, w.rx, w.tx)
                }
                val rs = range.from.atStartOfDay(zone).toInstant().toEpochMilli()
                val re = minOf(now, range.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                val fg = duUidWindow(ctx, ConnectivityManager.TYPE_MOBILE, app.uid, rs, re).fg +
                    duUidWindow(ctx, ConnectivityManager.TYPE_WIFI, app.uid, rs, re).fg
                DuUidLoad(usage, fg)
            }
            delay(60_000)
        }
    }

    val info = remember(app.pkg) {
        val p = app.pkg
        val pi = if (p == null) null else runCatching { ctx.packageManager.getPackageInfo(p, 0) }.getOrNull()
        DuAppInfo(pi?.firstInstallTime, pi?.lastUpdateTime, pi?.versionName)
    }

    val usage = load?.usage ?: emptyList()
    val medium = fs.medium
    val total = usage.sumOf { it.total(medium) }
    val mob = usage.sumOf { it.total(DuMedium.MOBILE) }
    val wif = usage.sumOf { it.total(DuMedium.WIFI) }
    val rx = usage.sumOf { it.rx(medium) }
    val tx = usage.sumOf { it.tx(medium) }
    val all = usage.sumOf { it.total(DuMedium.BOTH) }
    val fg = (load?.fg ?: 0L).coerceAtMost(all)
    val bg = all - fg
    val (num, unit) = duParts(total)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header: back, real icon, name
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            AppIcon(app.pkg, app.label)
            Text(app.label, maxLines = 1, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        }

        // Hero: total for the period and filter
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).border(1.dp, cardBorder, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(num, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(unit, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DuCyan)
            }
        }

        PeriodFilterBar(fs, today)

        // Chart: stacked bars, download at the bottom, upload on top
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).border(1.dp, cardBorder, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconValue(Icons.Default.ArrowDownward, DuCyan, tr("ডাউনলোড", "Download"), "")
                IconValue(Icons.Default.ArrowUpward, DuGreen, tr("আপলোড", "Upload"), "")
            }
            UsageChart(usage, medium, selected) { i -> selected = if (selected == i) -1 else i }
            AxisLabels(usage.map { it.slot.label })
        }

        // Split cards: download, upload, mobile, Wi-Fi
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Default.ArrowDownward, tr("ডাউনলোড", "Download"), duShort(rx), DuCyan, Modifier.weight(1f))
            StatTile(Icons.Default.ArrowUpward, tr("আপলোড", "Upload"), duShort(tx), DuGreen, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Default.CellTower, tr("মোবাইল", "Mobile"), duShort(mob), DuCyan, Modifier.weight(1f))
            StatTile(Icons.Default.Wifi, "Wi-Fi", duShort(wif), DuPurple, Modifier.weight(1f))
        }

        // Foreground / background share, shown only when the system reports the state
        if (fg > 0L) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).border(1.dp, cardBorder, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DuBar(Icons.Default.Visibility, tr("ফোরগ্রাউন্ড", "Foreground"), duShort(fg), fraction(fg, all), DuCyan)
                DuBar(Icons.Default.VisibilityOff, tr("ব্যাকগ্রাউন্ড", "Background"), duShort(bg), fraction(bg, all), DuGreen)
            }
        }

        // App info
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).border(1.dp, cardBorder, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val zoneDate = { ms: Long -> duDateLabel(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate(), AppPrefs.english) }
            info.firstInstall?.let { InfoLine(Icons.Default.DateRange, tr("প্রথম ইনস্টল", "First installed"), zoneDate(it), muted) }
            info.lastUpdate?.let { InfoLine(Icons.Default.DateRange, tr("সর্বশেষ আপডেট", "Last updated"), zoneDate(it), muted) }
            info.version?.let { InfoLine(Icons.Default.Info, tr("সংস্করণ", "Version"), duBnDigits(it), muted) }
            Button(
                onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                enabled = app.pkg != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("সিস্টেম সেটিংসে খুলুন", "Open in system settings")) }
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, muted: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
            Text(label, color = muted, fontSize = 13.sp)
        }
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}
