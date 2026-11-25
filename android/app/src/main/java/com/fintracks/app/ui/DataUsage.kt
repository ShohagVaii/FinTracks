package com.fintracks.app.ui

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Data usage tool: live speed, totals per period, and per-app usage. Needs Usage Access for the totals. */

internal val DuCyan = Color(0xFF38C7E0)
private val DuPurple = Color(0xFF8B7CF6)
internal val DuGreen = Color(0xFF2ECC71)

/** Icon bitmaps by package, so rows do not reload icons on every refresh. */
private val duIconCache = ConcurrentHashMap<String, ImageBitmap>()
/** UID -> (package, app label), filled on the first successful lookup. */
private val duUidCache = ConcurrentHashMap<Int, Pair<String, String>>()

enum class DuSpan(val bn: String, val en: String) { DAY("আজ", "Today"), WEEK("সপ্তাহ", "Week"), MONTH("মাস", "Month") }

class DuTotals(val mobile: Long, val wifi: Long, val rx: Long, val tx: Long) {
    val total: Long get() = mobile + wifi
}

class DuApp(val label: String, val pkg: String?, val system: Boolean, val rx: Long, val tx: Long) {
    val bytes: Long get() = rx + tx
}

/** Start of the period in epoch millis (today, the last 7 days, or this month). */
internal fun duStart(span: DuSpan): Long {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val day = when (span) {
        DuSpan.DAY -> today
        DuSpan.WEEK -> today.minusDays(6)
        DuSpan.MONTH -> today.withDayOfMonth(1)
    }
    return day.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** True when Usage Access is granted: the device summary query only succeeds with it. */
fun duHasUsageAccess(ctx: Context): Boolean = try {
    ctx.getSystemService(NetworkStatsManager::class.java)
        .querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, 0L, System.currentTimeMillis())
    true
} catch (e: SecurityException) {
    false
} catch (e: Exception) {
    true
}

private fun duSummary(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): Pair<Long, Long> = try {
    val b = nsm.querySummaryForDevice(type, null, start, end)
    b.rxBytes to b.txBytes
} catch (e: Exception) {
    0L to 0L
}

internal fun duTotals(ctx: Context, start: Long, end: Long): DuTotals {
    val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
    val (mRx, mTx) = duSummary(nsm, ConnectivityManager.TYPE_MOBILE, start, end)
    val (wRx, wTx) = duSummary(nsm, ConnectivityManager.TYPE_WIFI, start, end)
    return DuTotals(mobile = mRx + mTx, wifi = wRx + wTx, rx = mRx + wRx, tx = mTx + wTx)
}

/** Usage per app (uid), mobile and Wi-Fi together, largest first. Names come from PackageManager. */
private fun duApps(ctx: Context, types: List<Int>, start: Long, end: Long): List<DuApp> {
    val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
    val pm = ctx.packageManager
    val byUid = HashMap<Int, LongArray>() // [rx, tx]
    for (type in types) {
        try {
            val stats: NetworkStats = nsm.queryDetails(type, null, start, end)
            val b = NetworkStats.Bucket()
            try {
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(b)
                    val acc = byUid.getOrPut(b.uid) { LongArray(2) }
                    acc[0] += b.rxBytes
                    acc[1] += b.txBytes
                }
            } finally {
                stats.close()
            }
        } catch (e: Exception) {
            // This network type has no data for the period; the other one still counts.
        }
    }
    return byUid.filter { it.value[0] + it.value[1] > 0L }.map { (uid, v) ->
        val cached = duUidCache[uid]
        val pkg = cached?.first ?: pm.getPackagesForUid(uid)?.firstOrNull()
        val info = pkg?.let { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
        val appLabel = info?.let { pm.getApplicationLabel(it).toString() } ?: cached?.second
        if (pkg != null && appLabel != null) duUidCache[uid] = pkg to appLabel
        val system = uid < 10000 || (info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        DuApp(
            label = uidLabel(uid, appLabel, AppPrefs.english),
            pkg = pkg, system = system, rx = v[0], tx = v[1]
        )
    }.sortedByDescending { it.bytes }
}

/** Bytes as "28.6 GB": the number and the unit. Decimals: 2 below 10, 1 below 100, none above. */
private fun duParts(bytes: Long, perSecond: Boolean = false): Pair<String, String> {
    val unit: String
    val v: Double
    when {
        bytes >= 1_073_741_824L -> { v = bytes / 1_073_741_824.0; unit = "GB" }
        bytes >= 1_048_576L -> { v = bytes / 1_048_576.0; unit = "MB" }
        else -> { v = bytes / 1024.0; unit = "KB" }
    }
    val num = when {
        perSecond -> if (unit == "KB") "%.0f".format(v) else "%.1f".format(v)
        v < 10 -> "%.2f".format(v)
        v < 100 -> "%.1f".format(v)
        else -> "%.0f".format(v)
    }
    return bnDigits(num) to (if (perSecond) "$unit/s" else unit)
}

internal fun duText(bytes: Long, perSecond: Boolean = false): String {
    val (n, u) = duParts(bytes, perSecond)
    return "$n $u"
}

/** Mobile and Wi-Fi download/upload for one window. */
class DuMedTotals(val mRx: Long, val mTx: Long, val wRx: Long, val wTx: Long) {
    fun rx(m: DuMedium): Long = when (m) {
        DuMedium.BOTH -> mRx + wRx
        DuMedium.WIFI -> wRx
        DuMedium.MOBILE -> mRx
    }

    fun tx(m: DuMedium): Long = when (m) {
        DuMedium.BOTH -> mTx + wTx
        DuMedium.WIFI -> wTx
        DuMedium.MOBILE -> mTx
    }

    fun total(m: DuMedium): Long = rx(m) + tx(m)
}

private class DuLoad(val total: DuMedTotals, val today: DuMedTotals, val usage: List<DuSlotUsage>, val apps: List<DuApp>)

private fun duMedTotals(ctx: Context, start: Long, end: Long): DuMedTotals {
    val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
    val (mRx, mTx) = duSummary(nsm, ConnectivityManager.TYPE_MOBILE, start, end)
    val (wRx, wTx) = duSummary(nsm, ConnectivityManager.TYPE_WIFI, start, end)
    return DuMedTotals(mRx, mTx, wRx, wTx)
}

private fun duTypes(m: DuMedium): List<Int> = when (m) {
    DuMedium.BOTH -> listOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)
    DuMedium.WIFI -> listOf(ConnectivityManager.TYPE_WIFI)
    DuMedium.MOBILE -> listOf(ConnectivityManager.TYPE_MOBILE)
}

/** Mobile and Wi-Fi bytes for each chart slot. */
private fun duSlotUsages(ctx: Context, slots: List<DuSlot>): List<DuSlotUsage> {
    val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
    return slots.map { s ->
        val (mRx, mTx) = duSummary(nsm, ConnectivityManager.TYPE_MOBILE, s.start, s.end)
        val (wRx, wTx) = duSummary(nsm, ConnectivityManager.TYPE_WIFI, s.start, s.end)
        DuSlotUsage(s, mRx, mTx, wRx, wTx)
    }
}

private fun duShort(bytes: Long): String = if (bytes == 0L) (if (AppPrefs.english) "0 B" else "০ B") else duText(bytes)

@Composable
fun DataUsageScreen() {
    val ctx = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    var period by remember { mutableStateOf(DuPeriod.MONTH) }
    var anchor by remember { mutableStateOf(today) }
    var customFrom by remember { mutableStateOf(today.withDayOfMonth(1)) }
    var customTo by remember { mutableStateOf(today) }
    var medium by remember { mutableStateOf(DuMedium.BOTH) }
    var showFrom by remember { mutableStateOf(false) }
    var showTo by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(-1) }
    var showSystem by remember { mutableStateOf(false) }
    var down by remember { mutableLongStateOf(0L) }
    var up by remember { mutableLongStateOf(0L) }
    var granted by remember { mutableStateOf(duHasUsageAccess(ctx)) }
    var load by remember { mutableStateOf<DuLoad?>(null) }
    var slotApps by remember { mutableStateOf<List<DuApp>>(emptyList()) }

    val range = duRangeFor(period, anchor, customFrom, customTo)
    val slots = remember(period, range.from, range.to) { duSlots(period, range, zone) }
    val canNext = duShift(period, range, 1).from <= today
    val cardBg = faint(0.05f)
    val cardBorder = faint(0.1f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    fun move(dir: Int) {
        val next = duShift(period, range, dir)
        if (period == DuPeriod.CUSTOM) {
            customFrom = next.from
            customTo = next.to
        } else {
            anchor = next.to
        }
    }

    // Live speed: TrafficStats sampled every second; stops when the screen leaves.
    LaunchedEffect(Unit) {
        var prevRx = TrafficStats.getTotalRxBytes()
        var prevTx = TrafficStats.getTotalTxBytes()
        while (isActive) {
            delay(1000)
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            down = (rx - prevRx).coerceAtLeast(0L)
            up = (tx - prevTx).coerceAtLeast(0L)
            prevRx = rx
            prevTx = tx
        }
    }

    // Without Usage Access the totals stay hidden; the check repeats so returning from Settings updates the screen.
    LaunchedEffect(granted) {
        while (!granted) {
            delay(2000)
            granted = duHasUsageAccess(ctx)
        }
    }

    // A new period, range, or network filter clears the selected bar.
    LaunchedEffect(period, range.from, range.to, medium) { selected = -1 }

    // Totals, chart bars and app list refresh every minute while the screen is open and access is granted.
    LaunchedEffect(slots, medium, granted) {
        if (!granted) return@LaunchedEffect
        while (isActive) {
            val now = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                val rs = range.from.atStartOfDay(zone).toInstant().toEpochMilli()
                val re = maxOf(rs, minOf(now, range.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()))
                val tod = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
                DuLoad(
                    total = duMedTotals(ctx, rs, re),
                    today = duMedTotals(ctx, tod, maxOf(tod, now)),
                    usage = duSlotUsages(ctx, slots),
                    apps = duApps(ctx, duTypes(medium), rs, re)
                )
            }
            load = result
            delay(60_000)
        }
    }

    // Apps for the selected bar (hour or day).
    LaunchedEffect(selected, slots, medium, granted) {
        val slot = slots.getOrNull(selected)
        slotApps = if (slot == null || !granted) emptyList()
        else withContext(Dispatchers.IO) {
            val end = minOf(slot.end, System.currentTimeMillis())
            duApps(ctx, duTypes(medium), slot.start, maxOf(slot.start, end))
        }
    }

    val ld = load
    val usage = ld?.usage ?: emptyList()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Live pill
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(DuGreen.copy(alpha = 0.14f))
                    .border(1.dp, DuGreen.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(DuGreen))
                Text(tr("লাইভ", "Live"), color = DuGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Live speed strip
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).border(1.dp, cardBorder, RoundedCornerShape(20.dp)).padding(16.dp)
        ) {
            SpeedCell(tr("ডাউনলোড", "Download"), duText(down, perSecond = true), DuCyan, Icons.Default.ArrowDownward, Modifier.weight(1f))
            SpeedCell(tr("আপলোড", "Upload"), duText(up, perSecond = true), DuGreen, Icons.Default.ArrowUpward, Modifier.weight(1f))
        }

        // Period segment
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(cardBg).padding(4.dp)) {
            listOf(
                DuPeriod.DAY to tr("আজ", "Today"),
                DuPeriod.WEEK to tr("সপ্তাহ", "Week"),
                DuPeriod.MONTH to tr("মাস", "Month"),
                DuPeriod.CUSTOM to tr("কাস্টম", "Custom")
            ).forEach { (p, label) ->
                val on = period == p
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (on) DuGreen else Color.Transparent)
                        .clickable {
                            period = p
                            if (p == DuPeriod.CUSTOM) showFrom = true
                        }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (on) Color.White else muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        if (!granted) {
            // Consent step: why the permission is needed, and a button to the system Usage Access page.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).border(1.dp, cardBorder, RoundedCornerShape(20.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(tr("ব্যবহার দেখতে অনুমতি লাগবে", "Usage access is needed"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    tr(
                        "কোন অ্যাপ কত ডাটা ব্যবহার করল, তা দেখাতে Usage Access অনুমতি লাগবে। ডাটা শুধু আপনার ফোনে থাকে।",
                        "Usage access shows how much data each app used. The data stays on your phone."
                    ),
                    color = muted, fontSize = 13.sp
                )
                Button(
                    onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(tr("সেটিংসে গিয়ে অনুমতি দিন", "Open settings to allow")) }
            }
            return@Column
        }

        // Custom range: chips open the date pickers, start first, then end.
        if (period == DuPeriod.CUSTOM) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DuChip(null, tr("শুরু ", "From ") + duRangeLabel(DuPeriod.DAY, DuRange(customFrom, customFrom), AppPrefs.english), false, Modifier.weight(1f)) { showFrom = true }
                DuChip(null, tr("শেষ ", "To ") + duRangeLabel(DuPeriod.DAY, DuRange(customTo, customTo), AppPrefs.english), false, Modifier.weight(1f)) { showTo = true }
            }
        }

        // Period navigation: previous / next, label in Bengali.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            NavArrow(Icons.Default.ChevronLeft, enabled = true) { move(-1) }
            Text(duRangeLabel(period, range, AppPrefs.english), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            NavArrow(Icons.Default.ChevronRight, enabled = canNext) { move(1) }
        }

        // Network filter
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DuChip(Icons.Default.DataUsage, tr("ডাটা ও ওয়াইফাই", "Data and Wi-Fi"), medium == DuMedium.BOTH, Modifier.weight(1f)) { medium = DuMedium.BOTH }
            DuChip(Icons.Default.Wifi, "Wi-Fi", medium == DuMedium.WIFI, Modifier.weight(1f)) { medium = DuMedium.WIFI }
            DuChip(Icons.Default.CellTower, tr("মোবাইল ডাটা", "Mobile"), medium == DuMedium.MOBILE, Modifier.weight(1f)) { medium = DuMedium.MOBILE }
        }

        // Hero: total for the period and filter, split into mobile and Wi-Fi.
        val total = ld?.total?.total(medium) ?: 0L
        val mob = ld?.total?.total(DuMedium.MOBILE) ?: 0L
        val wif = ld?.total?.total(DuMedium.WIFI) ?: 0L
        val (num, unit) = duParts(total)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).border(1.dp, cardBorder, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(num, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(unit, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DuCyan)
            }
            if (medium != DuMedium.WIFI) {
                DuBar(Icons.Default.CellTower, tr("মোবাইল ডাটা", "Mobile data"), duShort(mob),
                    if (medium == DuMedium.BOTH) fraction(mob, mob + wif) else 1f, DuCyan)
            }
            if (medium != DuMedium.MOBILE) {
                DuBar(Icons.Default.Wifi, "Wi-Fi", duShort(wif),
                    if (medium == DuMedium.BOTH) fraction(wif, mob + wif) else 1f, DuPurple)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconValue(Icons.Default.ArrowUpward, DuGreen, tr("আপলোড ", "Upload "), duShort(ld?.total?.tx(medium) ?: 0L))
                Spacer(Modifier.width(16.dp))
                IconValue(Icons.Default.ArrowDownward, DuCyan, tr("ডাউনলোড ", "Download "), duShort(ld?.total?.rx(medium) ?: 0L))
            }
        }

        // Today tiles
        val td = ld?.today
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Default.DataUsage, tr("আজকের ব্যবহার", "Today's usage"), duShort(td?.total(medium) ?: 0L), DuCyan, Modifier.weight(1f))
            StatTile(
                Icons.Default.ArrowUpward, tr("আজকের আপলোড / ডাউনলোড", "Today upload / download"),
                duShort(td?.tx(medium) ?: 0L) + " / " + duShort(td?.rx(medium) ?: 0L),
                DuGreen, Modifier.weight(1f)
            )
        }

        PackCard()
        CallCard()

        // Usage chart: stacked bars, download (cyan) at the bottom, upload (green) on top.
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

            val picked = usage.getOrNull(selected)
            if (picked != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(faint(0.1f)))
                Text(slotTitle(period, picked.slot), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val pickedApps = slotApps.filter { showSystem || !it.system }.take(5)
                val peak = pickedApps.maxOfOrNull { it.bytes } ?: 0L
                if (pickedApps.isEmpty()) {
                    Text(tr("এই সময়ে ডাটা ব্যবহারের তথ্য নেই", "No data usage in this period"), color = muted, fontSize = 13.sp)
                }
                pickedApps.forEach { app -> AppRow(app, peak) }
            }
        }

        // Daily (or hourly) history: mobile and Wi-Fi per row; tap a row to select it.
        if (usage.isNotEmpty()) {
            Text(tr("ইতিহাস", "History"), fontWeight = FontWeight.Bold)
            usage.indices.reversed().forEach { i ->
                val u = usage[i]
                val on = selected == i
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (on) DuCyan.copy(alpha = 0.12f) else cardBg)
                        .border(1.dp, if (on) DuCyan.copy(alpha = 0.5f) else cardBorder, RoundedCornerShape(16.dp))
                        .clickable { selected = if (on) -1 else i }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(slotTitle(period, u.slot), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconValue(Icons.Default.CellTower, DuCyan, "", duShort(u.mRx + u.mTx))
                        IconValue(Icons.Default.Wifi, DuPurple, "", duShort(u.wRx + u.wTx))
                    }
                }
            }
        }

        // App list
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("অ্যাপভিত্তিক ব্যবহার — ", "Usage by app — ") + duRangeLabel(period, range, AppPrefs.english), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(tr("সিস্টেম", "System"), color = muted, fontSize = 12.sp)
            Switch(checked = showSystem, onCheckedChange = { showSystem = it })
        }
        val shown = (ld?.apps ?: emptyList()).filter { showSystem || !it.system }
        val max = shown.maxOfOrNull { it.bytes } ?: 0L
        if (shown.isEmpty()) {
            Text(tr("এই সময়ে ডাটা ব্যবহারের তথ্য নেই", "No data usage in this period"), color = muted, fontSize = 13.sp)
        }
        shown.forEach { app -> AppRow(app, max) }
    }

    if (showFrom) {
        DatePickerSheet(customFrom, tr("শুরুর তারিখ", "Start date"), onDismiss = { showFrom = false }, onPick = {
            customFrom = it
            showFrom = false
            showTo = true
        })
    }
    if (showTo) {
        DatePickerSheet(customTo, tr("শেষ তারিখ", "End date"), onDismiss = { showTo = false }, onPick = {
            customTo = it
            showTo = false
        })
    }
}

/** Header label for a slot: the hour for the day view, the date otherwise. */
@Composable
private fun slotTitle(period: DuPeriod, slot: DuSlot): String =
    if (period == DuPeriod.DAY) slot.label + ":" + (if (AppPrefs.english) "00" else "০০")
    else duRangeLabel(DuPeriod.DAY, DuRange(slot.day, slot.day), AppPrefs.english)

@Composable
private fun AppRow(app: DuApp, max: Long) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(faint(0.05f)).border(1.dp, faint(0.1f), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(app.pkg, app.label)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(app.label, maxLines = 1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            StackedBar(app.rx, app.tx, max)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(duText(app.bytes), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = DuCyan, modifier = Modifier.size(11.dp))
                Text(duShort(app.rx), color = muted, fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = DuGreen, modifier = Modifier.size(11.dp))
                Text(duShort(app.tx), color = muted, fontSize = 11.sp)
            }
        }
    }
}

/** Stacked bars for the slots: download (cyan) at the bottom, upload (green) on top; tap selects a bar. */
@Composable
private fun UsageChart(usage: List<DuSlotUsage>, medium: DuMedium, selected: Int, onTap: (Int) -> Unit) {
    val max = (usage.maxOfOrNull { it.total(medium) } ?: 0L).coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().height(150.dp)) {
        usage.forEachIndexed { i, u ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { onTap(i) },
                contentAlignment = Alignment.BottomCenter
            ) {
                if (selected == i) Box(Modifier.fillMaxSize().background(faint(0.08f)))
                val rx = u.rx(medium)
                val tx = u.tx(medium)
                if (rx + tx > 0L) {
                    Column(Modifier.fillMaxWidth(0.6f).fillMaxHeight(fraction(rx + tx, max))) {
                        if (tx > 0L) Box(Modifier.fillMaxWidth().weight(tx.toFloat()).background(DuGreen))
                        if (rx > 0L) Box(Modifier.fillMaxWidth().weight(rx.toFloat()).background(DuCyan))
                    }
                }
            }
        }
    }
}

/** Labels under the chart: about seven evenly spaced ones. */
@Composable
private fun AxisLabels(labels: List<String>) {
    val step = ((labels.size + 6) / 7).coerceAtLeast(1)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            Text(if (i % step == 0) l else "", fontSize = 10.sp, color = muted, modifier = Modifier.weight(1f), maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DuChip(icon: ImageVector?, text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (selected) DuCyan else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.clip(RoundedCornerShape(50))
            .background(if (selected) DuCyan.copy(alpha = 0.16f) else faint(0.05f))
            .border(1.dp, if (selected) DuCyan.copy(alpha = 0.6f) else faint(0.1f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun NavArrow(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(faint(0.05f)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else faint(0.25f), modifier = Modifier.size(22.dp))
    }
}

private fun fraction(part: Long, whole: Long): Float = if (whole <= 0L) 0f else (part.toDouble() / whole).toFloat().coerceIn(0f, 1f)

@Composable
private fun SpeedCell(label: String, value: String, color: Color, icon: ImageVector, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DuBar(icon: ImageVector, label: String, value: String, fraction: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Text(label, fontSize = 13.sp)
            }
            Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(faint(0.08f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(faint(0.05f)).border(1.dp, faint(0.1f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
        }
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Download-then-upload stacked bar: cyan for download, green for upload, scaled to [max]. */
@Composable
private fun StackedBar(down: Long, up: Long, max: Long) {
    val total = down + up
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(faint(0.08f))) {
        if (total > 0L) {
            Row(Modifier.fillMaxWidth(fraction(total, max)).fillMaxHeight()) {
                if (down > 0L) Box(Modifier.weight(down.toFloat()).fillMaxHeight().background(DuCyan))
                if (up > 0L) Box(Modifier.weight(up.toFloat()).fillMaxHeight().background(DuGreen))
            }
        }
    }
}

@Composable
private fun IconValue(icon: ImageVector, color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Text(label + value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

/** The app's real icon; a letter avatar when the icon cannot be loaded. */
@Composable
private fun AppIcon(pkg: String?, label: String) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = pkg?.let { duIconCache[it] }, pkg) {
        value = if (pkg == null) null else duIconCache[pkg] ?: withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }
                .getOrNull()?.also { duIconCache[pkg] = it }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val icon = bmp
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(shape))
    } else {
        Box(Modifier.size(40.dp).clip(shape).background(faint(0.12f)), contentAlignment = Alignment.Center) {
            Text(label.take(1).uppercase(), fontWeight = FontWeight.Bold)
        }
    }
}
