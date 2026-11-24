package com.fintracks.app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fintracks.app.data.NotifyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Data pack (প্যাক) card and call-minutes card. Pack data stays on this device only. */

private const val GB = 1_073_741_824.0
private val PackAmber = Color(0xFFFBB024)

data class PackProfile(
    val gb: Double,
    val price: Double,
    val start: Long,        // start of the first day, epoch millis
    val days: Int,
    val settled: Boolean = false,
    val alert80: Boolean = false,
    val alertRisk: Boolean = false
)

object PackStore {
    private const val PREFS = "fintrack_pack"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): PackProfile? {
        val s = prefs(ctx).getString("profile", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            PackProfile(
                o.getDouble("gb"), o.getDouble("price"), o.getLong("start"), o.getInt("days"),
                o.optBoolean("settled"), o.optBoolean("alert80"), o.optBoolean("alertRisk")
            )
        }.getOrNull()
    }

    fun save(ctx: Context, p: PackProfile) {
        val o = JSONObject()
            .put("gb", p.gb).put("price", p.price).put("start", p.start).put("days", p.days)
            .put("settled", p.settled).put("alert80", p.alert80).put("alertRisk", p.alertRisk)
        prefs(ctx).edit().putString("profile", o.toString()).apply()
    }

    fun appendHistory(ctx: Context, p: PackProfile, usedBytes: Long) {
        val arr = runCatching { JSONArray(prefs(ctx).getString("history", "[]")) }.getOrElse { JSONArray() }
        val size = p.gb * GB
        val wasted = (size - usedBytes).coerceAtLeast(0.0)
        arr.put(
            JSONObject()
                .put("gb", p.gb).put("price", p.price).put("start", p.start).put("days", p.days)
                .put("used", usedBytes)
                .put("wastedGb", wasted / GB)
                .put("wastedTaka", if (size > 0) p.price * wasted / size else 0.0)
        )
        prefs(ctx).edit().putString("history", arr.toString()).apply()
    }
}

/** Mobile bytes used inside the pack window (start until the earlier of now and pack end). */
fun packUsed(ctx: Context, p: PackProfile): Long {
    val zone = ZoneId.systemDefault()
    val end = Instant.ofEpochMilli(p.start).atZone(zone).toLocalDate()
        .plusDays(p.days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    return duTotals(ctx, p.start, minOf(System.currentTimeMillis(), end)).mobile
}

/** Moves an expired, not yet archived pack into history. Returns the fresh stored profile. */
fun settlePack(ctx: Context, used: Long): PackProfile? {
    val cur = PackStore.load(ctx) ?: return null
    if (cur.settled || !packCalc(cur, used).expired) return cur
    PackStore.appendHistory(ctx, cur, used)
    val s = cur.copy(settled = true)
    PackStore.save(ctx, s)
    return s
}

const val PACK_STATUS_OK = 0
const val PACK_STATUS_WARN = 1
const val PACK_STATUS_RISK = 2
const val PACK_STATUS_EXPIRED = 3

class PackCalc(val sizeBytes: Double, val used: Long, val remainingDays: Int, val expired: Boolean, val projected: Double, val exhaust: LocalDate?) {
    val pct: Int get() = if (sizeBytes <= 0) 0 else (used * 100 / sizeBytes).toInt().coerceIn(0, 999)
    val status: Int
        get() = when {
            expired -> PACK_STATUS_EXPIRED
            projected <= sizeBytes -> PACK_STATUS_OK
            projected <= sizeBytes * 1.1 -> PACK_STATUS_WARN
            else -> PACK_STATUS_RISK
        }
}

/** Daily average × remaining days gives the projection; the average uses days elapsed since the pack started. */
fun packCalc(p: PackProfile, used: Long): PackCalc {
    val zone = ZoneId.systemDefault()
    val startDate = Instant.ofEpochMilli(p.start).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val day = ChronoUnit.DAYS.between(startDate, today).toInt() + 1
    val expired = day > p.days
    val remaining = (p.days - day).coerceAtLeast(0)
    val elapsed = day.coerceIn(1, p.days.coerceAtLeast(1))
    val avg = used.toDouble() / elapsed
    val size = p.gb * GB
    val projected = used + avg * remaining
    val left = size - used
    val exhaust = when {
        expired -> null
        left <= 0 -> today
        avg > 0 && projected > size -> today.plusDays(Math.ceil(left / avg).toLong())
        else -> null
    }
    return PackCalc(size, used, remaining, expired, projected, exhaust)
}

class CallSecs(val incoming: Long, val outgoing: Long) {
    val inMin: Long get() = incoming / 60
    val outMin: Long get() = outgoing / 60
    val totalMin: Long get() = (incoming + outgoing) / 60
}

fun hasCallLogPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

/** Call duration in the period, split by direction. Read on device only; nothing is sent to the server. */
fun duCalls(ctx: Context, start: Long, end: Long): CallSecs {
    var inc = 0L
    var out = 0L
    try {
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DURATION),
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} < ?",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            val tIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (c.moveToNext()) {
                val secs = c.getLong(dIdx)
                when (c.getInt(tIdx)) {
                    CallLog.Calls.INCOMING_TYPE -> inc += secs
                    CallLog.Calls.OUTGOING_TYPE -> out += secs
                }
            }
        }
    } catch (e: SecurityException) {
        // Permission was revoked while reading; show zero.
    }
    return CallSecs(inc, out)
}

private fun gbText(bytes: Double): String = bnDigits("%.1f".format(bytes / GB))

private fun dayText(d: LocalDate): String =
    if (AppPrefs.english) d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    else bnDigits(d.dayOfMonth.toString()) + " " + BN_MONTHS[d.monthValue - 1]

private fun Modifier.dataCard(): Modifier = this.fillMaxWidth().clip(RoundedCornerShape(20.dp))
    .background(faint(0.05f)).border(1.dp, faint(0.1f), RoundedCornerShape(20.dp)).padding(18.dp)

@Composable
private fun PackPill(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun PackEditButton(onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(18.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Edit, contentDescription = tr("এডিট", "Edit"), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(11.dp))
        }
    }
}

@Composable
fun PackCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(PackStore.load(ctx)) }
    var calc by remember { mutableStateOf<PackCalc?>(null) }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { PackAlertWorker.schedule(ctx) }

    LaunchedEffect(profile) {
        val p0 = profile ?: return@LaunchedEffect
        while (isActive) {
            val used = withContext(Dispatchers.IO) { packUsed(ctx, p0) }
            val p = withContext(Dispatchers.IO) { settlePack(ctx, used) } ?: break
            calc = packCalc(p, used)
            if (p.settled != p0.settled) { profile = p; break }
            delay(60_000)
        }
    }

    val p = profile
    val c = calc
    Column(Modifier.dataCard(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("আমার প্যাক", "My pack"), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (p != null) {
                PackPill(taka(p.price), DuGreen)
                Spacer(Modifier.width(4.dp))
                PackEditButton { editing = true }
            }
        }
        if (p == null) {
            Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("প্যাক সেট করুন", "Set up pack"))
            }
        } else if (c != null) {
            val barColor = when {
                c.pct < 70 -> DuGreen
                c.pct < 90 -> PackAmber
                else -> Rose500
            }
            Text(
                gbText(c.sizeBytes) + tr(" GB-এর মধ্যে ব্যবহৃত", " GB used of pack"),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(faint(0.08f))) {
                    Box(Modifier.fillMaxWidth(minOf(1f, c.pct / 100f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(barColor))
                }
                Text(bnDigits("${c.pct}%"), color = barColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val left = (c.sizeBytes - c.used).coerceAtLeast(0.0)
                Text(
                    tr("বাকি ", "Left ") + gbText(left) + tr(" GB — আর ", " GB — ") + bnDigits(c.remainingDays.toString()) + tr(" দিন", " days"),
                    fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                when (c.status) {
                    PACK_STATUS_OK -> PackPill(tr("প্যাক চলবে", "On track"), DuGreen)
                    PACK_STATUS_WARN -> PackPill(tr("সতর্ক", "Watch"), PackAmber)
                    PACK_STATUS_RISK -> PackPill(
                        tr("শেষ হবে ", "Ends ") + (c.exhaust?.let { dayText(it) } ?: ""), Rose500
                    )
                    else -> PackPill(tr("মেয়াদ শেষ", "Expired"), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (c.status == PACK_STATUS_EXPIRED) {
                val size = c.sizeBytes
                val wastedBytes = (size - c.used).coerceAtLeast(0.0)
                val wastedTaka = if (size > 0) p.price * wastedBytes / size else 0.0
                Text(
                    tr("অব্যবহৃত ", "Unused ") + gbText(wastedBytes) + " GB · " + taka(wastedTaka),
                    color = PackAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (editing) {
        PackSheet(
            current = p,
            onDismiss = { editing = false },
            onSave = { np ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val old = PackStore.load(ctx)
                        if (old != null && !old.settled) PackStore.appendHistory(ctx, old, packUsed(ctx, old))
                        PackStore.save(ctx, np)
                    }
                    profile = np
                    editing = false
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackSheet(current: PackProfile?, onDismiss: () -> Unit, onSave: (PackProfile) -> Unit) {
    val ctx = LocalContext.current
    var gb by remember { mutableStateOf(current?.let { fmtInput(it.gb) } ?: "") }
    var price by remember { mutableStateOf(current?.let { fmtInput(it.price) } ?: "") }
    var start by remember { mutableStateOf(current?.let { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().toString()) }
    var days by remember { mutableStateOf(current?.days?.toString() ?: "30") }

    val gbV = gb.toDoubleOrNull()
    val priceV = price.toDoubleOrNull()
    val startD = runCatching { LocalDate.parse(start.trim().take(10)) }.getOrNull()
    val daysV = days.toIntOrNull()
    val valid = gbV != null && gbV > 0 && priceV != null && priceV >= 0 && startD != null && daysV != null && daysV in 1..400

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("প্যাক সেটআপ", "Pack setup"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedTextField(
                value = gb, onValueChange = { gb = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("প্যাকের GB", "Pack GB")) },
                leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                value = price, onValueChange = { price = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("দাম (টাকা)", "Price")) },
                leadingIcon = { Icon(Icons.Default.Payments, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            DateField(tr("শুরুর তারিখ", "Start date"), start, { start = it }, icon = Icons.Default.CalendarMonth, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = days, onValueChange = { days = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("মেয়াদ (দিন)", "Validity (days)")) },
                leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Button(
                enabled = valid,
                onClick = {
                    val startMs = startD!!.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    onSave(PackProfile(gbV!!, priceV!!, startMs, daysV!!))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("সেভ", "Save")) }
        }
    }
}

private fun fmtInput(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

@Composable
fun CallCard() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasCallLogPermission(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var today by remember { mutableStateOf<CallSecs?>(null) }
    var month by remember { mutableStateOf<CallSecs?>(null) }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        while (isActive) {
            val now = System.currentTimeMillis()
            val (t, m) = withContext(Dispatchers.IO) {
                duCalls(ctx, duStart(DuSpan.DAY), now) to duCalls(ctx, duStart(DuSpan.MONTH), now)
            }
            today = t
            month = m
            delay(60_000)
        }
    }

    if (!granted) {
        Column(Modifier.dataCard(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("কল মিনিট দেখতে অনুমতি লাগবে", "Call minutes need permission"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                tr("কল লগ শুধু এই ফোনে পড়া হয়, সার্ভারে যায় না।", "The call log is read on this phone only and is never sent to a server."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
            Button(onClick = { launcher.launch(Manifest.permission.READ_CALL_LOG) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("অনুমতি দিন", "Allow"))
            }
        }
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CallTile(tr("আজকের কল", "Today's calls"), today, DuGreen, Modifier.weight(1f))
        CallTile(tr("এই মাসের কল", "This month's calls"), month, DuCyan, Modifier.weight(1f))
    }
}

@Composable
private fun CallTile(label: String, s: CallSecs?, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(faint(0.05f)).border(1.dp, faint(0.1f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(
            bnDigits((s?.totalMin ?: 0L).toString()) + tr(" মিনিট", " min"),
            color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1
        )
        Text(
            tr("আসা ", "In ") + bnDigits((s?.inMin ?: 0L).toString()) + "  ·  " + tr("করা ", "Out ") + bnDigits((s?.outMin ?: 0L).toString()),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
        )
    }
}

// ---- Alerts (run in background, even when the app is closed) ----

private const val PACK_CH = "pack_alerts"

private fun packNotify(ctx: Context, id: Int, title: String, text: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
        nm.createNotificationChannel(NotificationChannel(PACK_CH, "প্যাক সতর্কতা", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val n = NotificationCompat.Builder(ctx, PACK_CH)
        .setSmallIcon(NotifyStyle.SMALL)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .build()
    nm.notify(id, n)
}

class PackAlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val saved = PackStore.load(ctx) ?: return@withContext Result.success()
        val used = packUsed(ctx, saved)
        val p = settlePack(ctx, used) ?: return@withContext Result.success()
        if (!p.settled) {
            val c = packCalc(p, used)
            var cur = p
            if (!cur.alert80 && c.pct >= 80) {
                packNotify(ctx, 4401, "প্যাকের ৮০% ব্যবহৃত", "মোবাইল ডাটার প্যাক প্রায় শেষের দিকে।")
                cur = cur.copy(alert80 = true)
            }
            if (!cur.alertRisk && c.status == PACK_STATUS_RISK) {
                packNotify(ctx, 4402, "প্যাক আগে শেষ হতে পারে", "এই হারে ব্যবহার চললে প্যাক মেয়াদের আগেই শেষ হবে।")
                cur = cur.copy(alertRisk = true)
            }
            if (cur != p) PackStore.save(ctx, cur)
        }
        Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<PackAlertWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("pack_alerts", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
