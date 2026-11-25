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
private fun duApps(ctx: Context, start: Long, end: Long): List<DuApp> {
    val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
    val pm = ctx.packageManager
    val byUid = HashMap<Int, LongArray>() // [rx, tx]
    for (type in listOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)) {
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

@Composable
fun DataUsageScreen() {
    val ctx = LocalContext.current
    var span by remember { mutableStateOf(DuSpan.MONTH) }
    var granted by remember { mutableStateOf(duHasUsageAccess(ctx)) }
    var showSystem by remember { mutableStateOf(false) }
    var down by remember { mutableLongStateOf(0L) }
    var up by remember { mutableLongStateOf(0L) }
    var totals by remember { mutableStateOf<DuTotals?>(null) }
    var today by remember { mutableStateOf<DuTotals?>(null) }
    var apps by remember { mutableStateOf<List<DuApp>>(emptyList()) }

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

    // Totals refresh every minute while the screen is open and access is granted.
    LaunchedEffect(span, granted) {
        if (!granted) return@LaunchedEffect
        while (isActive) {
            val now = System.currentTimeMillis()
            val (t, td, a) = withContext(Dispatchers.IO) {
                Triple(duTotals(ctx, duStart(span), now), duTotals(ctx, duStart(DuSpan.DAY), now), duApps(ctx, duStart(span), now))
            }
            totals = t
            today = td
            apps = a
            delay(60_000)
        }
    }

    val cardBg = faint(0.05f)
    val cardBorder = faint(0.1f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

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
            DuSpan.values().forEach { sp ->
                val on = span == sp
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (on) DuGreen else Color.Transparent)
                        .clickable { span = sp }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tr(sp.bn, sp.en), color = if (on) Color.White else muted, fontWeight = FontWeight.SemiBold)
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
                        "কোন অ্যাপ কত ডাটা ব্যবহার করল, তা দেখাতে Usage Access অনুমতি লাগে। তথ্য শুধু আপনার ফোনে থাকে।",
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

        // Hero: total for the period, split into mobile and Wi-Fi.
        val t = totals
        val total = t?.total ?: 0L
        val (num, unit) = duParts(total)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).border(1.dp, cardBorder, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(periodTitle(span), color = muted, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(num, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(unit, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DuCyan)
            }
            DuBar(Icons.Default.CellTower, tr("মোবাইল ডাটা", "Mobile data"), duText(t?.mobile ?: 0L), fraction(t?.mobile ?: 0L, total), DuCyan)
            DuBar(Icons.Default.Wifi, "Wi-Fi", duText(t?.wifi ?: 0L), fraction(t?.wifi ?: 0L, total), DuPurple)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconValue(Icons.Default.ArrowUpward, DuGreen, tr("আপলোড ", "Upload "), duText(t?.tx ?: 0L))
                Spacer(Modifier.width(16.dp))
                IconValue(Icons.Default.ArrowDownward, DuCyan, tr("ডাউনলোড ", "Download "), duText(t?.rx ?: 0L))
            }
        }

        // Today tiles
        val td = today
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Default.DataUsage, tr("আজকের ব্যবহার", "Today's usage"), duText(td?.total ?: 0L), DuCyan, Modifier.weight(1f))
            StatTile(
                Icons.Default.ArrowUpward, tr("আজকের আপলোড / ডাউনলোড", "Today upload / download"),
                duText(td?.tx ?: 0L) + " / " + duText(td?.rx ?: 0L),
                DuGreen, Modifier.weight(1f)
            )
        }

        PackCard()
        CallCard()

        // App list
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("অ্যাপভিত্তিক ব্যবহার — ", "Usage by app — ") + tr(span.bn, span.en), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(tr("সিস্টেম", "System"), color = muted, fontSize = 12.sp)
            Switch(checked = showSystem, onCheckedChange = { showSystem = it })
        }
        val shown = apps.filter { showSystem || !it.system }
        val max = shown.maxOfOrNull { it.bytes } ?: 0L
        if (shown.isEmpty()) {
            Text(tr("এই সময়ে ডাটা ব্যবহারের তথ্য নেই", "No data usage in this period"), color = muted, fontSize = 13.sp)
        }
        shown.forEach { app ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardBg).border(1.dp, cardBorder, RoundedCornerShape(16.dp)).padding(12.dp),
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
                        Text(duText(app.rx), color = muted, fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = DuGreen, modifier = Modifier.size(11.dp))
                        Text(duText(app.tx), color = muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun fraction(part: Long, whole: Long): Float = if (whole <= 0L) 0f else (part.toDouble() / whole).toFloat().coerceIn(0f, 1f)

@Composable
private fun periodTitle(span: DuSpan): String = when (span) {
    DuSpan.DAY -> tr("আজ — মোট ব্যবহার", "Today — total usage")
    DuSpan.WEEK -> tr("গত ৭ দিন — মোট ব্যবহার", "Last 7 days — total usage")
    DuSpan.MONTH -> {
        val now = LocalDate.now()
        if (AppPrefs.english) now.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) + " — total usage"
        else BN_MONTHS[now.monthValue - 1] + " " + bnDigits(now.year.toString()) + " — মোট ব্যবহার"
    }
}

@Composable
private fun SpeedCell(label: String, value: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DuBar(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, fraction: Float, color: Color) {
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
private fun StatTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, color: Color, modifier: Modifier) {
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
private fun IconValue(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, label: String, value: String) {
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
