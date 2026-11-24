package com.fintracks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/* ---------- Pure logic (unit tested) ---------- */

const val KG_PER_MAUND = 40.0

data class MaundSplit(val maund: Long, val kg: Double)

/** 40 kg = 1 maund. 115 kg = 2 maund 35 kg. */
fun kgToMaund(kg: Double): MaundSplit {
    val m = floor(kg / KG_PER_MAUND).toLong()
    val rest = kg - m * KG_PER_MAUND
    return MaundSplit(m, rest)
}

fun maundToKg(maund: Long, kg: Double): Double = maund * KG_PER_MAUND + kg

/** Shows whole numbers without decimals; otherwise at most two decimals. */
fun fmtKg(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString()
    else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

/** Bangla number words 0..99. Index = value. */
private val BN_TENS_ONES = (
    "শূন্য এক দুই তিন চার পাঁচ ছয় সাত আট নয় দশ এগারো বারো তেরো চৌদ্দ পনেরো ষোল সতেরো আঠারো উনিশ বিশ " +
    "একুশ বাইশ তেইশ চব্বিশ পঁচিশ ছাব্বিশ সাতাশ আটাশ উনত্রিশ ত্রিশ একত্রিশ বত্রিশ তেত্রিশ চৌত্রিশ পঁয়ত্রিশ ছত্রিশ সাঁইত্রিশ আটত্রিশ উনচল্লিশ " +
    "চল্লিশ একচল্লিশ বিয়াল্লিশ তেতাল্লিশ চুয়াল্লিশ পঁয়তাল্লিশ ছেচল্লিশ সাতচল্লিশ আটচল্লিশ উনপঞ্চাশ " +
    "পঞ্চাশ একান্ন বাহান্ন তিপ্পান্ন চুয়ান্ন পঞ্চান্ন ছাপ্পান্ন সাতান্ন আটান্ন উনষাট " +
    "ষাট একষট্টি বাষট্টি তেষট্টি চৌষট্টি পঁয়ষট্টি ছেষট্টি সাতষট্টি আটষট্টি উনসত্তর " +
    "সত্তর একাত্তর বাহাত্তর তিয়াত্তর চুয়াত্তর পঁচাত্তর ছিয়াত্তর সাতাত্তর আটাত্তর উনআশি " +
    "আশি একাশি বিরাশি তিরাশি চুরাশি পঁচাশি ছিয়াশি সাতাশি আটাশি উননব্বই " +
    "নব্বই একানব্বই বিরানব্বই তিরানব্বই চুরানব্বই পঁচানব্বই ছিয়ানব্বই সাতানব্বই আটানব্বই নিরানব্বই"
    ).split(" ")

private fun belowThousand(n: Int): String {
    val parts = mutableListOf<String>()
    val h = n / 100
    val r = n % 100
    if (h > 0) parts += "${BN_TENS_ONES[h]}শো"
    if (r > 0) parts += BN_TENS_ONES[r]
    return parts.joinToString(" ")
}

private fun wordsAbs(n: Long): String {
    val parts = mutableListOf<String>()
    var rem = n
    val crore = rem / 10_000_000L
    rem %= 10_000_000L
    if (crore > 0) parts += "${wordsAbs(crore)} কোটি"
    val lakh = rem / 100_000L
    rem %= 100_000L
    if (lakh > 0) parts += "${BN_TENS_ONES[lakh.toInt()]} লাখ"
    val thousand = rem / 1000L
    rem %= 1000L
    if (thousand > 0) parts += "${BN_TENS_ONES[thousand.toInt()]} হাজার"
    if (rem > 0) parts += belowThousand(rem.toInt())
    return parts.joinToString(" ")
}

/** Number to Bangla words in lakh/crore grouping. Optional "টাকা" suffix. */
fun bnWords(value: Long, taka: Boolean = false): String {
    val body = when {
        value == 0L -> "শূন্য"
        value < 0 -> "ঋণাত্মক " + wordsAbs(abs(value))
        else -> wordsAbs(value)
    }
    return if (taka) "$body টাকা" else body
}

/* ---------- UI ---------- */

private fun normNum(s: String): String = buildString {
    for (ch in s) {
        when (ch) {
            in '০'..'৯' -> append('0' + (ch - '০'))
            ',' -> Unit
            else -> append(ch)
        }
    }
}

@Composable
private fun MathCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(faint(0.05f))
            .border(1.dp, faint(0.1f), RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun MathSegment(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(faint(0.05f)).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = selected == i
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .background(if (on) Color(0xFF14B8A6) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MathField(value: String, onChange: (String) -> Unit, label: String, decimal: Boolean, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(normNum(it).filter { c -> c.isDigit() || c == '.' || c == '-' }.take(15)) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number)
    )
}

@Composable
private fun MathResult(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF14B8A6))
}

@Composable
fun MathToolsScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        MaundCard()
        WordsCard()
    }
}

@Composable
private fun MaundCard() {
    var mode by remember { mutableStateOf(0) }
    var kgIn by remember { mutableStateOf("") }
    var maundIn by remember { mutableStateOf("") }
    var kgRestIn by remember { mutableStateOf("") }

    MathCard {
        Text(tr("মন ও কেজি", "Maund and kg"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        MathSegment(listOf(tr("কেজি → মন", "kg → maund"), tr("মন → কেজি", "maund → kg")), mode) { mode = it }
        if (mode == 0) {
            MathField(kgIn, { kgIn = it }, tr("কেজি", "kg"), decimal = true)
            val kg = kgIn.toDoubleOrNull()
            if (kg != null && kg >= 0) {
                val s = kgToMaund(kg)
                val parts = mutableListOf<String>()
                if (s.maund > 0) parts += bnDigits(s.maund.toString()) + tr(" মন", " maund")
                if (s.kg > 0) parts += bnDigits(fmtKg(s.kg)) + tr(" কেজি", " kg")
                if (parts.isEmpty()) parts += bnDigits("0") + tr(" কেজি", " kg")
                MathResult(parts.joinToString(" "))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MathField(maundIn, { maundIn = it }, tr("মন", "maund"), decimal = false, modifier = Modifier.weight(1f))
                MathField(kgRestIn, { kgRestIn = it }, tr("কেজি", "kg"), decimal = true, modifier = Modifier.weight(1f))
            }
            val m = maundIn.toLongOrNull()
            val k = kgRestIn.toDoubleOrNull() ?: 0.0
            if (m != null && m >= 0 && k >= 0) {
                MathResult(bnDigits(fmtKg(maundToKg(m, k))) + tr(" কেজি", " kg"))
            }
        }
    }
}

@Composable
private fun WordsCard() {
    var numIn by remember { mutableStateOf("") }
    var taka by remember { mutableStateOf(false) }

    MathCard {
        Text(tr("সংখ্যা → কথায়", "Number → words"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        MathField(numIn, { numIn = it }, tr("সংখ্যা", "Number"), decimal = false)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("টাকা", "Taka"), modifier = Modifier.weight(1f))
            Switch(checked = taka, onCheckedChange = { taka = it })
        }
        val n = numIn.toLongOrNull()
        if (n != null) MathResult(bnWords(n, taka))
    }
}
