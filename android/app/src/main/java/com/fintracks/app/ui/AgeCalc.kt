package com.fintracks.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.core.content.FileProvider
import com.fintracks.app.data.row
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/* ---------- Pure logic (unit tested) ---------- */

/** Everything the age result shows, for one birth date and one calculation date. */
data class AgeStats(
    val years: Int,
    val months: Int,
    val days: Int,
    val totalDays: Long,
    val weeks: Long,
    val totalMonths: Long,
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
    val weekday: DayOfWeek,
    val next: LocalDate,
    val daysToNext: Long,
    /** Share of the way from the last birthday to the next one, 0..1. */
    val progress: Double,
    val isBirthdayToday: Boolean,
    val zodiac: String
)

private fun ageBirthdayIn(birth: LocalDate, year: Int): LocalDate {
    val len = java.time.YearMonth.of(year, birth.month).lengthOfMonth()
    return LocalDate.of(year, birth.month, minOf(birth.dayOfMonth, len))
}

/** Null when the birth date is after [asOf]. */
fun ageStats(birth: LocalDate, asOf: LocalDate): AgeStats? {
    if (birth.isAfter(asOf)) return null
    val period = Period.between(birth, asOf)
    val days = ChronoUnit.DAYS.between(birth, asOf)
    var next = ageBirthdayIn(birth, asOf.year)
    if (next.isBefore(asOf)) next = ageBirthdayIn(birth, asOf.year + 1)
    val prev = ageBirthdayIn(birth, next.year - 1)
    val span = ChronoUnit.DAYS.between(prev, next).toDouble()
    val elapsed = ChronoUnit.DAYS.between(prev, asOf).toDouble()
    return AgeStats(
        years = period.years,
        months = period.months,
        days = period.days,
        totalDays = days,
        weeks = days / 7,
        totalMonths = period.years * 12L + period.months,
        hours = days * 24,
        minutes = days * 1440,
        seconds = days * 86400,
        weekday = birth.dayOfWeek,
        next = next,
        daysToNext = ChronoUnit.DAYS.between(asOf, next),
        progress = (elapsed / span).coerceIn(0.0, 1.0),
        isBirthdayToday = next == asOf,
        zodiac = zodiacBn(birth)
    )
}

/** Indian digit grouping (lakh/crore): 683078400 -> "68,30,78,400". */
fun groupIndian(n: Long): String {
    val s = kotlin.math.abs(n).toString()
    if (s.length <= 3) return if (n < 0) "-$s" else s
    val last3 = s.takeLast(3)
    var rest = s.dropLast(3)
    val parts = mutableListOf<String>()
    while (rest.length > 2) {
        parts.add(0, rest.takeLast(2))
        rest = rest.dropLast(2)
    }
    if (rest.isNotEmpty()) parts.add(0, rest)
    return (if (n < 0) "-" else "") + parts.joinToString(",") + "," + last3
}

/** Sun sign (tropical, by date), Bangla name. */
fun zodiacBn(birth: LocalDate): String {
    val md = birth.monthValue * 100 + birth.dayOfMonth
    return when (md) {
        in 101..119, in 1222..1231 -> "মকর"
        in 120..218 -> "কুম্ভ"
        in 219..320 -> "মীন"
        in 321..419 -> "মেষ"
        in 420..520 -> "বৃষ"
        in 521..620 -> "মিথুন"
        in 621..722 -> "কর্কট"
        in 723..822 -> "সিংহ"
        in 823..922 -> "কন্যা"
        in 923..1022 -> "তুলা"
        in 1023..1121 -> "বৃশ্চিক"
        else -> "ধনু"
    }
}

private fun ageWeekdayBn(d: DayOfWeek): String = when (d) {
    DayOfWeek.SATURDAY -> tr("শনিবার", "Saturday")
    DayOfWeek.SUNDAY -> tr("রবিবার", "Sunday")
    DayOfWeek.MONDAY -> tr("সোমবার", "Monday")
    DayOfWeek.TUESDAY -> tr("মঙ্গলবার", "Tuesday")
    DayOfWeek.WEDNESDAY -> tr("বুধবার", "Wednesday")
    DayOfWeek.THURSDAY -> tr("বৃহস্পতিবার", "Thursday")
    DayOfWeek.FRIDAY -> tr("শুক্রবার", "Friday")
}

private fun ageDate(d: LocalDate): String = displayDate(d.toString())

private fun bnInt(d: Double): String = bnDigits(d.toLong().toString())

/* ---------- Share and PNG card ---------- */

private fun shareFile(ctx: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun shareAgeText(ctx: Context, s: AgeStats) {
    val text = bnDigits(
        tr(
            "আমার বয়স ${s.years} বছর ${s.months} মাস ${s.days} দিন\nমোট ${groupIndian(s.totalDays)} দিন · ${groupIndian(s.seconds)} সেকেন্ড\nFinTrack",
            "My age ${s.years} years ${s.months} months ${s.days} days\n${groupIndian(s.totalDays)} days in total · FinTrack"
        )
    )
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(intent, tr("শেয়ার", "Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Draws the result card as a 1080x1350 PNG bitmap, with the bundled Hind Siliguri font. */
fun renderAgeCard(ctx: Context, s: AgeStats, birthText: String, asOfText: String, nextText: String): Bitmap {
    val w = 1080
    val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val bg = Paint().apply {
        shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFFDB2777.toInt(), 0xFF7C3AED.toInt(), Shader.TileMode.CLAMP)
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
    val bold = Typeface.createFromAsset(ctx.assets, "fonts/HindSiliguri-Bold.ttf")
    val regular = Typeface.createFromAsset(ctx.assets, "fonts/HindSiliguri-Regular.ttf")
    val white = 0xFFFFFFFF.toInt()

    fun text(t: String, x: Float, y: Float, size: Float, tf: Typeface, col: Int = white) {
        c.drawText(t, x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = tf
            this.color = col
            textAlign = Paint.Align.CENTER
        })
    }

    fun panel(x: Float, y: Float, pw: Float, ph: Float) {
        c.drawRoundRect(RectF(x, y, x + pw, y + ph), 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
    }

    text(bnDigits(tr("আমার বয়স", "My age")), w / 2f, 150f, 46f, regular)
    text(bnDigits("$birthText · $asOfText"), w / 2f, 225f, 34f, regular)
    text(bnDigits("${s.years} বছর"), w / 2f, 370f, 150f, bold)
    text(bnDigits("${s.months} মাস ${s.days} দিন"), w / 2f, 450f, 72f, bold)
    text(bnDigits("${ageWeekdayBn(s.weekday)} · ${s.zodiac}"), w / 2f, 520f, 40f, regular)

    val tiles = listOf(
        groupIndian(s.seconds) to tr("সেকেন্ড", "Seconds"),
        groupIndian(s.minutes) to tr("মিনিট", "Minutes"),
        groupIndian(s.hours) to tr("ঘণ্টা", "Hours"),
        groupIndian(s.totalDays) to tr("মোট দিন", "Total days"),
        groupIndian(s.weeks) to tr("মোট সপ্তাহ", "Total weeks"),
        groupIndian(s.totalMonths) to tr("মোট মাস", "Total months")
    )
    tiles.forEachIndexed { i, (value, label) ->
        val x = if (i % 2 == 0) 60f else 560f
        val y = 580f + (i / 2) * 180f
        panel(x, y, 460f, 150f)
        text(bnDigits(value), x + 230f, y + 80f, 56f, bold)
        text(label, x + 230f, y + 130f, 32f, regular)
    }

    val pct = (s.progress * 100).roundToInt()
    text(bnDigits("$pct%"), w / 2f, 1140f, 60f, bold)
    panel(60f, 1170f, 960f, 30f)
    c.drawRoundRect(RectF(60f, 1170f, 60f + 960f * s.progress.toFloat(), 1200f), 15f, 15f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white })
    text(bnDigits("${s.daysToNext} দিন বাকি · $nextText"), w / 2f, 1270f, 36f, regular)
    text("FinTrack", w / 2f, 1330f, 30f, bold)
    return bmp
}

/** Saves the PNG to Downloads (Android 10+). Older versions share it instead. */
fun saveAgeCard(ctx: Context, bmp: Bitmap) {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "fintrack-age-${System.currentTimeMillis()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/FinTrack")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        val ok = uri != null && ctx.contentResolver.openOutputStream(uri)?.use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        } == true
        flash(if (ok) tr("ছবি ডাউনলোডে সেভ হয়েছে", "Saved to Downloads") else tr("ছবি সেভ হয়নি", "Could not save the image"))
        return
    }
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "fintrack-age.png")
    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    shareFile(ctx, file, "image/png", tr("বয়সের কার্ড", "Age card"))
}

/* ---------- Screen ---------- */

private data class TimeTile(val label: String, val value: Long, val from: Color, val to: Color)

/** Age calculator: birth date and calculation date (default today), result hero, time grid, dates, progress. */
@Composable
fun AgeCalcScreen(state: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val marks = calMarks(state.rows(CAL_TABLE))
    val saved = birthdayMark(marks)

    var birth by remember { mutableStateOf(saved?.date) }
    LaunchedEffect(saved?.date) { if (birth == null) birth = saved?.date }
    var asOf by remember { mutableStateOf(today) }
    var pickingBirth by remember { mutableStateOf(false) }
    var pickingAsOf by remember { mutableStateOf(false) }

    val b = birth
    val s = b?.let { ageStats(it, asOf) }
    val asOfLabel = if (asOf == today) tr("আজ", "Today") else tr("হিসাবের দিন", "Calc date")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppCard {
            Text(tr("জন্ম তারিখ", "Birth date"), fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { pickingBirth = true }, modifier = Modifier.fillMaxWidth()) {
                Text(b?.let { ageDate(it) } ?: tr("তারিখ বাছাই করুন", "Pick a date"))
            }
            if (b != null && b != saved?.date) {
                OutlinedButton(onClick = { saveMark(vm, "birthday", "জন্মদিন", b, "#EC4899", true, saved?.id) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Favorite, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("জন্মদিন হিসেবে সেভ", "Save as birthday"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("হিসাবের তারিখ", "Calculated on"), fontSize = 13.sp, color = faint(0.6f))
                    Text(ageDate(asOf), fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { pickingAsOf = true }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = tr("তারিখ বদলান", "Change date"))
                }
            }
        }

        if (b == null || s == null) {
            if (b == null) EmptyHint(tr("জন্ম তারিখ বাছাই করলে বয়স দেখাবে", "Pick a birth date to see the age"))
            else Text(tr("জন্ম তারিখ হিসাবের তারিখের পরে হতে পারে না।", "Birth date cannot be after the calculation date."), color = MaterialTheme.colorScheme.error)
        } else {
            if (s.isBirthdayToday) {
                Box(
                    Modifier.fillMaxWidth().staggerIn(0).clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFDB2777))))
                        .padding(vertical = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tr("শুভ জন্মদিন!", "Happy birthday!"), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    ConfettiBurst(Modifier.matchParentSize())
                }
            }

            Column(
                Modifier.fillMaxWidth().staggerIn(1).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFDB2777), Color(0xFF7C3AED))))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(tr("আপনার বয়স", "Your age"), fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), letterSpacing = 2.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUpText(s.years.toDouble(), { bnInt(it) }, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("বছর", "years"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUpText(s.months.toDouble(), { bnInt(it) }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("মাস", "months"), color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.width(14.dp))
                    CountUpText(s.days.toDouble(), { bnInt(it) }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("দিন", "days"), color = Color.White, fontSize = 15.sp)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(ageWeekdayBn(s.weekday), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            val tiles = listOf(
                TimeTile(tr("সেকেন্ড", "Seconds"), s.seconds, Color(0xFF0EA5E9), Color(0xFF2563EB)),
                TimeTile(tr("মিনিট", "Minutes"), s.minutes, Color(0xFF14B8A6), Color(0xFF0D9488)),
                TimeTile(tr("ঘণ্টা", "Hours"), s.hours, Color(0xFFF59E0B), Color(0xFFEA580C)),
                TimeTile(tr("মোট দিন", "Total days"), s.totalDays, Color(0xFF8B5CF6), Color(0xFF6D28D9)),
                TimeTile(tr("মোট সপ্তাহ", "Total weeks"), s.weeks, Color(0xFFEC4899), Color(0xFFBE185D)),
                TimeTile(tr("মোট মাস", "Total months"), s.totalMonths, Color(0xFF10B981), Color(0xFF047857))
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(2).forEachIndexed { r, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEachIndexed { c, t ->
                            Box(
                                Modifier.weight(1f).staggerIn(2 + r * 2 + c).clip(RoundedCornerShape(18.dp))
                                    .background(Brush.linearGradient(listOf(t.from, t.to)))
                                    .padding(14.dp)
                            ) {
                                Column {
                                    CountUpText(t.value.toDouble(), { bnDigits(groupIndian(it.toLong())) }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(t.label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            AppCard {
                val anim by animateFloatAsState(targetValue = s.progress.toFloat(), animationSpec = tween(1200), label = "age-progress")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("পরের জন্মদিনের পথ", "Path to next birthday"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(bnDigits("${(s.progress * 100).roundToInt()}%"), fontWeight = FontWeight.Bold)
                }
                Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(faint(0.1f))) {
                    Box(
                        Modifier.fillMaxWidth(anim).fillMaxHeight().clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(Color(0xFFDB2777), Color(0xFF7C3AED))))
                    )
                }
                KeyValue(tr("পরের জন্মদিন", "Next birthday"), bnDigits(ageDate(s.next) + " · " + tr("${s.daysToNext} দিন বাকি", "${s.daysToNext} days left")))
                KeyValue(tr("রাশি", "Zodiac"), s.zodiac)
                KeyValue(tr("জন্ম · বাংলা", "Birth · Bangla"), bnDigits(bengaliText(b)))
                KeyValue(tr("জন্ম · হিজরি", "Birth · Hijri"), bnDigits(hijriText(b)))
                KeyValue("$asOfLabel · ${tr("বাংলা", "Bangla")}", bnDigits(bengaliText(asOf)))
                KeyValue("$asOfLabel · ${tr("হিজরি", "Hijri")}", bnDigits(hijriText(asOf)))
            }

            Row(Modifier.fillMaxWidth().staggerIn(8), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { shareAgeText(ctx, s) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("শেয়ার", "Share"))
                }
                Button(
                    onClick = {
                        val bmp = renderAgeCard(ctx, s, ageDate(b), ageDate(asOf), ageDate(s.next))
                        saveAgeCard(ctx, bmp)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(tr("ছবি সেভ", "Save image")) }
            }
        }
    }

    if (pickingBirth) {
        DatePickerSheet(birth ?: LocalDate.of(2000, 1, 1), tr("জন্ম তারিখ", "Birth date"), onDismiss = { pickingBirth = false }) { birth = it }
    }
    if (pickingAsOf) {
        DatePickerSheet(asOf, tr("হিসাবের তারিখ", "Calculation date"), onDismiss = { pickingAsOf = false }) { asOf = it }
    }
}
