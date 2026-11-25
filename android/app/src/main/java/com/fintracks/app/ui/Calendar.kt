package com.fintracks.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fintracks.app.data.PushNotifier
import com.fintracks.app.data.id
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Table of the calendar tool: user special days and the birthday (kind = birthday, yearly). */
const val CAL_TABLE = "calendar_marks"

val CalCyan = Color(0xFF39C7E0)
val CalRose = Color(0xFFF43F5E)
val CalPink = Color(0xFFEC4899)

/** Colour choices for special days, stored as hex. */
val CAL_COLORS = listOf("#39C7E0", "#F43F5E", "#F59E0B", "#10B981", "#8B5CF6")

fun calColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(CalCyan)

/** A row of [CAL_TABLE] as the calendar sees it. */
data class CalMark(val id: String, val kind: String, val title: String, val date: LocalDate, val color: String, val yearly: Boolean) {
    fun occursOn(d: LocalDate): Boolean {
        if (!yearly) return date == d
        if (date.monthValue != d.monthValue) return false
        if (date.dayOfMonth == d.dayOfMonth) return true
        // 29 Feb birthdays are shown on 28 Feb in non-leap years.
        return date.monthValue == 2 && date.dayOfMonth == 29 && d.dayOfMonth == 28 && !d.isLeapYear
    }
}

fun calMarks(rows: List<JsonObject>): List<CalMark> = rows.mapNotNull { o ->
    val date = runCatching { LocalDate.parse(o.str("mark_date").take(10)) }.getOrNull() ?: return@mapNotNull null
    CalMark(
        id = o.id(),
        kind = o.str("kind").ifEmpty { "special" },
        title = o.str("title"),
        date = date,
        color = o.str("color").ifEmpty { CAL_COLORS[0] },
        yearly = (o["yearly"] as? JsonPrimitive)?.booleanOrNull == true
    )
}

/** The birthday mark, if one is saved. Shared with the birthday items so both read one store. */
fun birthdayMark(marks: List<CalMark>): CalMark? = marks.firstOrNull { it.kind == "birthday" }

/* Public holidays for Bangladesh, from the published 2026 and 2027 lists. Lunar dates follow moon sighting and may move. */
private val DATED_HOLIDAYS = mapOf(
    "2026-02-04" to "শবেবরাত",
    "2026-03-17" to "শবে কদর",
    "2026-03-19" to "ঈদুল ফিতরের ছুটি",
    "2026-03-20" to "জুমাতুল বিদা",
    "2026-03-21" to "ঈদুল ফিতর",
    "2026-03-22" to "ঈদুল ফিতরের ছুটি",
    "2026-03-23" to "ঈদুল ফিতরের ছুটি",
    "2026-05-01" to "মে দিবস ও বুদ্ধ পূর্ণিমা",
    "2026-05-25" to "ঈদুল আজহার ছুটি",
    "2026-05-26" to "ঈদুল আজহার ছুটি",
    "2026-05-27" to "ঈদুল আজহা",
    "2026-05-28" to "ঈদুল আজহার ছুটি",
    "2026-05-29" to "ঈদুল আজহার ছুটি",
    "2026-05-30" to "ঈদুল আজহার ছুটি",
    "2026-06-26" to "আশুরা",
    "2026-08-26" to "ঈদে মিলাদুন্নবী",
    "2026-09-04" to "জন্মাষ্টমী",
    "2026-10-20" to "দুর্গা পূজার ছুটি",
    "2026-10-21" to "বিজয়া দশমী",
    "2027-03-10" to "ঈদুল ফিতর",
    "2027-05-17" to "ঈদুল আজহা",
    "2027-05-20" to "বুদ্ধ পূর্ণিমা"
)

/** Fixed-date holidays, keyed by MM-dd, apply every year. */
private val FIXED_HOLIDAYS = mapOf(
    "02-21" to "শহীদ দিবস ও আন্তর্জাতিক মাতৃভাষা দিবস",
    "03-26" to "স্বাধীনতা দিবস",
    "04-14" to "পহেলা বৈশাখ",
    "05-01" to "মে দিবস",
    "08-05" to "জুলাই গণঅভ্যুত্থান দিবস",
    "12-16" to "বিজয় দিবস",
    "12-25" to "বড়দিন"
)

fun holidayOn(d: LocalDate): String? =
    DATED_HOLIDAYS[d.toString()] ?: FIXED_HOLIDAYS["%02d-%02d".format(d.monthValue, d.dayOfMonth)]

/* ---------- Birthday wish (08:00 daily, works offline from a local copy of the birthday days) ---------- */

object BirthdayWish {
    private const val PREFS = "fintrack_birthday"
    private const val WORK = "fintrack-birthday-wish"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keeps a local copy of birthday month-days, so the background job does not need the network. */
    fun mirror(ctx: Context, marks: List<CalMark>) {
        val days = marks.filter { it.kind == "birthday" }
            .map { "%02d-%02d".format(it.date.monthValue, it.date.dayOfMonth) }
            .distinct()
        prefs(ctx).edit().putString("days", days.joinToString(",")).apply()
    }

    internal fun days(ctx: Context): Set<String> =
        prefs(ctx).getString("days", "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()

    /** Schedules the daily job at the next 08:00 local time. */
    fun schedule(ctx: Context) {
        val now = LocalDateTime.now()
        var next = LocalDate.now().atTime(8, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val req = PeriodicWorkRequestBuilder<BirthdayWishWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    internal fun claimToday(ctx: Context, today: LocalDate): Boolean {
        val p = prefs(ctx)
        val key = "fired-$today"
        if (p.getBoolean(key, false)) return false
        p.edit().putBoolean(key, true).apply()
        return true
    }
}

class BirthdayWishWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val today = LocalDate.now()
        val key = "%02d-%02d".format(today.monthValue, today.dayOfMonth)
        if (key in BirthdayWish.days(ctx) && BirthdayWish.claimToday(ctx, today)) {
            val title = "শুভ জন্মদিন"
            val body = "আজ আপনার জন্মদিন। শুভেচ্ছা ও ভালো কাটুক দিনটি!"
            LocalInbox.init(ctx)
            LocalInbox.add(title, body)
            PushNotifier.show(ctx, mapOf("title" to title, "body" to body, "kind" to "text", "target" to "dashboard"))
        }
        return Result.success()
    }
}

/* ---------- Screen ---------- */

private fun saveMark(vm: AppViewModel, kind: String, title: String, date: LocalDate, color: String, yearly: Boolean, id: String?) {
    vm.save(
        CAL_TABLE,
        row("kind" to kind, "title" to title, "mark_date" to date.toString(), "color" to color, "yearly" to yearly),
        id
    )
}

@Composable
fun CalendarScreen(state: UiState, vm: AppViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val marks = calMarks(state.rows(CAL_TABLE))
    LaunchedEffect(marks) { BirthdayWish.mirror(ctx, marks) }

    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var dayDialog by remember { mutableStateOf<LocalDate?>(null) }
    var birthdayDialog by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val birthday = birthdayMark(marks)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CalCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, null) }
                Text(
                    monthTitle(month),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, null) }
            }
            Row(Modifier.fillMaxWidth()) {
                weekdayLabels().forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = faint(0.6f))
                }
            }
            val lead = month.atDay(1).dayOfWeek.value % 7
            val length = month.lengthOfMonth()
            val weeks = (lead + length + 6) / 7
            for (w in 0 until weeks) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val day = w * 7 + c - lead + 1
                        Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                            if (day in 1..length) {
                                val date = month.atDay(day)
                                DayCell(
                                    date = date,
                                    isToday = date == today,
                                    isSelected = date == selected,
                                    holiday = holidayOn(date) != null,
                                    marks = marks.filter { it.occursOn(date) },
                                    onTap = { selected = date },
                                    onLong = { selected = date; dayDialog = date }
                                )
                            }
                        }
                    }
                }
            }
        }

        CalCard {
            Text(displayDate(selected.toString()).let { bnDigits(it) }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            CalLine(tr("বাংলা", "Bangla"), bnDigits(bengaliText(selected)))
            CalLine(tr("হিজরি", "Hijri"), bnDigits(hijriText(selected)))
            holidayOn(selected)?.let { Text(it, color = CalRose, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
            marks.filter { it.occursOn(selected) }.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(calColor(m.color)))
                    Spacer(Modifier.width(8.dp))
                    Text(m.title.ifEmpty { tr("বিশেষ দিন", "Special day") }, fontSize = 14.sp)
                }
            }
        }

        CalCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, null, tint = CalPink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("জন্মদিন", "Birthday"), fontWeight = FontWeight.SemiBold)
                    Text(
                        birthday?.let { bnDigits(displayDate(it.date.toString())) } ?: tr("সেট করা নেই", "Not set"),
                        color = faint(0.6f), fontSize = 13.sp
                    )
                }
                TextButton(onClick = { birthdayDialog = true }) { Text(tr("বদলান", "Change")) }
            }
        }
    }

    dayDialog?.let { day ->
        DayMarkDialog(
            day = day,
            existing = marks.filter { it.occursOn(day) },
            holiday = holidayOn(day),
            onAdd = { title, color, yearly ->
                saveMark(vm, "special", title, day, color, yearly, null)
                dayDialog = null
            },
            onRemove = { id -> vm.remove(CAL_TABLE, id) },
            onClose = { dayDialog = null }
        )
    }

    if (birthdayDialog) {
        BirthdayDialog(
            current = birthday,
            onSave = { date ->
                saveMark(vm, "birthday", "জন্মদিন", date, "#EC4899", true, birthday?.id)
                birthdayDialog = false
            },
            onRemove = {
                birthday?.let { vm.remove(CAL_TABLE, it.id) }
                birthdayDialog = false
            },
            onClose = { birthdayDialog = false }
        )
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    holiday: Boolean,
    marks: List<CalMark>,
    onTap: () -> Unit,
    onLong: () -> Unit
) {
    val bg = when {
        isToday -> CalCyan
        isSelected -> CalCyan.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val numberColor = when {
        isToday -> Color.Black
        holiday -> CalRose
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.fillMaxSize().padding(2.dp).clip(CircleShape).background(bg)
            .pointerInput(date) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() })
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            bnDigits(date.dayOfMonth.toString()),
            color = numberColor,
            fontSize = 14.sp,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
        )
        Row(horizontalArrangement = Arrangement.Center) {
            if (marks.isNotEmpty() && !isToday) {
                marks.take(3).forEach {
                    Box(Modifier.padding(horizontal = 1.dp).size(4.dp).clip(CircleShape).background(calColor(it.color)))
                }
            } else if (holiday && !isToday) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(CalRose))
            }
        }
    }
}

@Composable
private fun DayMarkDialog(
    day: LocalDate,
    existing: List<CalMark>,
    holiday: String?,
    onAdd: (String, String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(CAL_COLORS[0]) }
    var yearly by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(bnDigits(displayDate(day.toString()))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                holiday?.let { Text(it, color = CalRose, fontSize = 14.sp) }
                existing.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(calColor(m.color)))
                        Spacer(Modifier.width(8.dp))
                        Text(m.title, Modifier.weight(1f), fontSize = 14.sp)
                        if (m.kind != "birthday") {
                            IconButton(onClick = { onRemove(m.id) }) { Icon(Icons.Default.Delete, null) }
                        }
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(tr("নাম", "Name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CAL_COLORS.forEach { hex ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(calColor(hex))
                                .border(if (hex == color) 3.dp else 0.dp, faint(0.8f), CircleShape)
                                .clickable { color = hex }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("প্রতি বছর", "Every year"), Modifier.weight(1f))
                    Switch(checked = yearly, onCheckedChange = { yearly = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onAdd(title.trim(), color, yearly) }) {
                Text(tr("যোগ করুন", "Add"))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("বন্ধ", "Close")) } }
    )
}

@Composable
private fun BirthdayDialog(current: CalMark?, onSave: (LocalDate) -> Unit, onRemove: () -> Unit, onClose: () -> Unit) {
    var iso by remember { mutableStateOf(current?.date?.toString() ?: LocalDate.now().toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("জন্মদিন", "Birthday")) },
        text = { DateField(label = tr("জন্মতারিখ", "Birth date"), iso = iso, onChange = { iso = it }) },
        confirmButton = {
            TextButton(onClick = { runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()?.let(onSave) }) {
                Text(tr("সংরক্ষণ", "Save"))
            }
        },
        dismissButton = {
            if (current != null) TextButton(onClick = onRemove) { Text(tr("মুছুন", "Remove")) }
        }
    )
}

@Composable
private fun CalCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(faint(0.05f))
            .border(1.dp, faint(0.1f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun CalLine(label: String, value: String) {
    Row {
        Text(label, Modifier.width(64.dp), color = faint(0.6f), fontSize = 14.sp)
        Text(value, fontSize = 14.sp)
    }
}

private fun monthTitle(m: YearMonth): String {
    val name = if (AppPrefs.english) m.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) else BN_MONTHS[m.monthValue - 1]
    return "$name ${bnDigits(m.year.toString())}"
}

private fun weekdayLabels(): List<String> =
    if (AppPrefs.english) listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    else listOf("রবি", "সোম", "মঙ্গল", "বুধ", "বৃহ", "শুক্র", "শনি")
