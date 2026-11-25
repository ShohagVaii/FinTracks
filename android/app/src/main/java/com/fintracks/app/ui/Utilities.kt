package com.fintracks.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
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

// ======================= বয়স ক্যালকুলেটর =======================

private fun bnDate(d: LocalDate): String = displayDate(d.toString())

private fun bnWeekday(d: java.time.DayOfWeek): String = when (d) {
    java.time.DayOfWeek.SATURDAY -> tr("শনিবার", "Saturday")
    java.time.DayOfWeek.SUNDAY -> tr("রবিবার", "Sunday")
    java.time.DayOfWeek.MONDAY -> tr("সোমবার", "Monday")
    java.time.DayOfWeek.TUESDAY -> tr("মঙ্গলবার", "Tuesday")
    java.time.DayOfWeek.WEDNESDAY -> tr("বুধবার", "Wednesday")
    java.time.DayOfWeek.THURSDAY -> tr("বৃহস্পতিবার", "Thursday")
    java.time.DayOfWeek.FRIDAY -> tr("শুক্রবার", "Friday")
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
internal fun DatePickerSheet(initial: LocalDate, title: String, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = epochMillisUtc(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(modifier = Modifier.pressScale(), onClick = {
                state.selectedDateMillis?.let { onPick(localFromUtcMillis(it)) }
                onDismiss()
            }) { Text(tr("ঠিক আছে", "OK")) }
        },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            DatePicker(state = state)
        }
    }
}

/** Date field: shows the date in the chosen format and opens the calendar. Nothing is typed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, iso: String, onChange: (String) -> Unit, optional: Boolean = false, icon: ImageVector? = null, modifier: Modifier = Modifier.fillMaxWidth()) {
    var open by remember { mutableStateOf(false) }
    val parsed = runCatching { LocalDate.parse(iso.trim().take(10)) }.getOrNull()
    Column(modifier) {
        FieldLabel(icon, label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(FIELD_RADIUS), modifier = Modifier.weight(1f)) {
                Text(parsed?.let { displayDate(it.toString()) } ?: tr("তারিখ বাছাই করুন", "Pick a date"))
            }
            if (optional && parsed != null) TextButton(modifier = Modifier.pressScale(), onClick = { onChange("") }) { Text(tr("মুছুন", "Clear")) }
        }
    }
    if (open) DatePickerSheet(parsed ?: LocalDate.now(), label, onDismiss = { open = false }) { onChange(it.toString()) }
}
