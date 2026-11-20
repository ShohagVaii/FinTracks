package com.fintracks.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DatePicker
import androidx.compose.material3.Icon
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.min

// ======================= টাকা গণক =======================

/** Note and coin denominations, largest first, as the counter shows them. */
val DENOMINATIONS: List<Int> = listOf(1000, 500, 200, 100, 50, 20, 10, 5, 2, 1)

/** One saved count: when it was saved, and how many pieces of each denomination. */
data class CountRecord(val at: Long, val counts: Map<Int, Long>) {
    val total: Long get() = counts.entries.sumOf { it.key.toLong() * it.value }
    val pieces: Long get() = counts.values.sum()
}

/** Counter history lives only on this device (SharedPreferences), as agreed. */
object CounterStore {
    private const val PREFS = "fintrack_counter"
    private const val KEY = "history"
    private const val MAX = 200

    fun load(ctx: Context): List<CountRecord> {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            (Json.parseToJsonElement(raw) as JsonArray).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val c = o["counts"] as? JsonObject ?: return@mapNotNull null
                CountRecord(o.num("at").toLong(), DENOMINATIONS.associateWith { d -> c.num(d.toString()).toLong() })
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, list: List<CountRecord>) {
        val arr = JsonArray(list.take(MAX).map { r ->
            JsonObject(
                mapOf(
                    "at" to JsonPrimitive(r.at),
                    "counts" to JsonObject(r.counts.mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) })
                )
            )
        })
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

private fun grouped(v: Long): String = String.format(Locale.US, "%,d", v)

/** Money counter: enter how many of each note/coin, see the total, save the count to history. */
@Composable
fun MoneyCounterScreen() {
    val ctx = LocalContext.current
    var inputs by remember { mutableStateOf(DENOMINATIONS.associateWith { "" }) }
    var history by remember { mutableStateOf(CounterStore.load(ctx)) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val counts: Map<Int, Long> = DENOMINATIONS.associateWith { inputs[it]?.toLongOrNull() ?: 0L }
    val total = counts.entries.sumOf { it.key.toLong() * it.value }
    val pieces = counts.values.sum()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Total card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF0EA5E9))))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("সর্বমোট", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), letterSpacing = 2.sp)
            Text(bnDigits("৳ ${grouped(total)}"), fontSize = 38.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text("মোট নোট/কয়েন: ${bnDigits(pieces.toString())}টি", fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
        }

        DENOMINATIONS.forEach { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(bnDigits("৳$d"), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.width(72.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = inputs[d] ?: "",
                    onValueChange = { v -> inputs = inputs + (d to v.filter { it.isDigit() }.take(6)) },
                    label = { Text("কয়টা") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    bnDigits("= ৳${grouped(d.toLong() * (counts[d] ?: 0L))}"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(110.dp)
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { inputs = DENOMINATIONS.associateWith { "" } }, modifier = Modifier.weight(1f)) {
                Text("পরিষ্কার করুন")
            }
            Button(
                onClick = {
                    if (total > 0) {
                        val record = CountRecord(System.currentTimeMillis(), counts)
                        history = listOf(record) + history
                        CounterStore.save(ctx, history)
                    }
                },
                enabled = total > 0,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14B8A6), contentColor = Color.White),
                modifier = Modifier.weight(1f)
            ) { Text("সংরক্ষণ করুন") }
        }

        SectionTitle("গণনার ইতিহাস")
        if (history.isEmpty()) EmptyHint("এখনো কোনো সংরক্ষিত গণনা নেই")
        history.forEach { r ->
            val open = expanded == r.at
            AppCard(onClick = { expanded = if (open) null else r.at }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bnDigits(bdTime(r.at)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(bnDigits("৳ ${grouped(r.total)}"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(bnDigits("${r.pieces} টি নোট/কয়েন"), fontSize = 12.sp)
                    }
                    androidx.compose.material3.IconButton(onClick = {
                        history = history.filterNot { it.at == r.at }
                        CounterStore.save(ctx, history)
                        if (expanded == r.at) expanded = null
                    }) { Icon(Icons.Default.Delete, contentDescription = "মুছুন", tint = Rose500) }
                }
                if (open) {
                    r.counts.filter { it.value > 0 }.forEach { (d, n) ->
                        KeyValue(bnDigits("৳$d × ${n}"), bnDigits("৳ ${grouped(d.toLong() * n)}"))
                    }
                }
            }
        }
    }
}

private val BD_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a", Locale.US)

private fun bdTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(BD_TIME)

// ======================= বয়স ক্যালকুলেটর =======================

private fun bnDate(d: LocalDate): String = bnDigits("${d.dayOfMonth} ${BN_MONTHS[d.monthValue - 1]} ${d.year}")

private fun bnWeekday(d: java.time.DayOfWeek): String = when (d) {
    java.time.DayOfWeek.SATURDAY -> "শনিবার"
    java.time.DayOfWeek.SUNDAY -> "রবিবার"
    java.time.DayOfWeek.MONDAY -> "সোমবার"
    java.time.DayOfWeek.TUESDAY -> "মঙ্গলবার"
    java.time.DayOfWeek.WEDNESDAY -> "বুধবার"
    java.time.DayOfWeek.THURSDAY -> "বৃহস্পতিবার"
    java.time.DayOfWeek.FRIDAY -> "শুক্রবার"
}

/** The birthday in [year]; Feb 29 falls back to Feb 28 in non-leap years. */
private fun birthdayIn(birth: LocalDate, year: Int): LocalDate {
    val len = java.time.YearMonth.of(year, birth.month).lengthOfMonth()
    return LocalDate.of(year, birth.month, min(birth.dayOfMonth, len))
}

/** Age between [birth] and [ref], plus the extra facts shown under the result. */
data class AgeResult(
    val period: java.time.Period,
    val totalDays: Long,
    val nextBirthday: LocalDate,
    val daysToBirthday: Long,
    val weekday: java.time.DayOfWeek
)

fun computeAge(birth: LocalDate, ref: LocalDate): AgeResult? {
    if (birth.isAfter(ref)) return null
    val period = java.time.Period.between(birth, ref)
    var next = birthdayIn(birth, ref.year)
    if (next.isBefore(ref)) next = birthdayIn(birth, ref.year + 1)
    return AgeResult(period, ChronoUnit.DAYS.between(birth, ref), next, ChronoUnit.DAYS.between(ref, next), birth.dayOfWeek)
}

private fun epochMillisUtc(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun localFromUtcMillis(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(initial: LocalDate, title: String, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = epochMillisUtc(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(localFromUtcMillis(it)) }
                onDismiss()
            }) { Text("ঠিক আছে") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } }
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            DatePicker(state = state)
        }
    }
}

/** Age calculator: birth date, optional reference date (default today), years/months/days and extra facts. */
@Composable
fun AgeCalcScreen() {
    val today = LocalDate.now()
    var birth by remember { mutableStateOf<LocalDate?>(null) }
    var ref by remember { mutableStateOf(today) }
    var useCustomRef by remember { mutableStateOf(false) }
    var pickingBirth by remember { mutableStateOf(false) }
    var pickingRef by remember { mutableStateOf(false) }

    val b = birth
    val r = if (useCustomRef) ref else today
    val result = b?.let { computeAge(it, r) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Text("জন্ম তারিখ", fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { pickingBirth = true }, modifier = Modifier.fillMaxWidth()) {
                Text(b?.let { bnDate(it) } ?: "তারিখ বাছাই করুন")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("অন্য তারিখ পর্যন্ত হিসাব", modifier = Modifier.weight(1f), fontSize = 13.sp)
                androidx.compose.material3.Switch(checked = useCustomRef, onCheckedChange = { useCustomRef = it })
            }
            if (useCustomRef) {
                OutlinedButton(onClick = { pickingRef = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("রেফারেন্স: ${bnDate(ref)}")
                }
            } else {
                Text("রেফারেন্স: আজ (${bnDate(today)})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (b == null) {
            EmptyHint("জন্ম তারিখ বাছাই করলে বয়স দেখাবে")
        } else if (result == null) {
            Text("জন্ম তারিখ রেফারেন্স তারিখের পরে হতে পারে না।", color = Rose500, fontSize = 13.sp)
        } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFDB2777), Color(0xFF7C3AED))))
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("আপনার বয়স", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), letterSpacing = 2.sp)
                    Text(
                        bnDigits("${result.period.years} বছর"),
                        fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White
                    )
                    Text(
                        bnDigits("${result.period.months} মাস ${result.period.days} দিন"),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
                AppCard {
                    KeyValue("মোট দিন", bnDigits(String.format(Locale.US, "%,d", result.totalDays)))
                    KeyValue("পরবর্তী জন্মদিন", bnDate(result.nextBirthday))
                    KeyValue(
                        "জন্মদিন বাকি",
                        if (result.daysToBirthday == 0L) "আজ! জন্মদিন মুবারক" else bnDigits("${result.daysToBirthday} দিন"),
                        Color(0xFFE879F9)
                    )
                    KeyValue("জন্মের বার", bnWeekday(result.weekday))
                }
            }
    }

    if (pickingBirth) {
        DatePickerSheet(birth ?: LocalDate.of(2000, 1, 1), "জন্ম তারিখ", onDismiss = { pickingBirth = false }) { birth = it }
    }
    if (pickingRef) {
        DatePickerSheet(ref, "রেফারেন্স তারিখ", onDismiss = { pickingRef = false }) { ref = it }
    }
}
