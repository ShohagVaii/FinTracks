package com.fintracks.app.ui

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/*
 * Quick entry from speech and receipts. Both only FILL the form. The user checks it and saves.
 * The parsers are pure Kotlin so they can be unit-tested on the JVM.
 */

/** Result of a spoken sentence such as "পাঁচশো টাকা বাজার করলাম". */
data class VoiceEntry(val amount: Double, val type: String, val category: String?, val transcript: String)

/** Fields read from a receipt photo. Any of them may be null when OCR cannot find it. */
data class ReceiptData(val amount: Double?, val date: String?, val shop: String?, val category: String?)

object CaptureParser {
    private val UNITS: Map<String, Double> = mapOf(
        "শূন্য" to 0.0, "এক" to 1.0, "একটা" to 1.0, "একটি" to 1.0, "দুই" to 2.0, "দুটো" to 2.0, "দু" to 2.0,
        "তিন" to 3.0, "চার" to 4.0, "পাঁচ" to 5.0, "ছয়" to 6.0, "ছ" to 6.0, "সাত" to 7.0, "আট" to 8.0, "নয়" to 9.0,
        "দশ" to 10.0, "এগারো" to 11.0, "বারো" to 12.0, "তেরো" to 13.0, "চৌদ্দ" to 14.0, "পনেরো" to 15.0,
        "ষোল" to 16.0, "সতেরো" to 17.0, "আঠারো" to 18.0, "উনিশ" to 19.0, "বিশ" to 20.0, "একুশ" to 21.0,
        "বাইশ" to 22.0, "তেইশ" to 23.0, "চব্বিশ" to 24.0, "পঁচিশ" to 25.0, "ছাব্বিশ" to 26.0, "সাতাশ" to 27.0,
        "আটাশ" to 28.0, "ঊনত্রিশ" to 29.0, "ত্রিশ" to 30.0, "একত্রিশ" to 31.0, "বত্রিশ" to 32.0,
        "তেত্রিশ" to 33.0, "চৌত্রিশ" to 34.0, "পঁয়ত্রিশ" to 35.0, "ছত্রিশ" to 36.0, "চল্লিশ" to 40.0,
        "পঞ্চাশ" to 50.0, "ষাট" to 60.0, "সত্তর" to 70.0, "আশি" to 80.0, "নব্বই" to 90.0,
        "দেড়" to 1.5, "দেড়শো" to 150.0, "আড়াই" to 2.5, "আড়াইশো" to 250.0,
        "একশো" to 100.0, "দুইশো" to 200.0, "তিনশো" to 300.0, "চারশো" to 400.0, "পাঁচশো" to 500.0,
        "ছয়শো" to 600.0, "সাতশো" to 700.0, "আটশো" to 800.0, "নয়শো" to 900.0
    )
    private val SCALES: Map<String, Double> = mapOf(
        "শো" to 100.0, "শ" to 100.0, "হাজার" to 1_000.0, "লাখ" to 100_000.0, "লক্ষ" to 100_000.0, "কোটি" to 10_000_000.0
    )
    private val INCOME_WORDS = listOf("আয়", "পেলাম", "পেয়েছি", "বেতন", "বোনাস", "বেচলাম", "বিক্রি", "received", "salary", "income")
    private val CATEGORY_WORDS: List<Pair<String, List<String>>> = listOf(
        "খাদ্য" to listOf("বাজার", "খাবার", "চা", "নাস্তা", "রেস্টুরেন্ট", "হোটেল", "মুদি", "মার্ট", "সুপার", "খাওয়া", "food", "bazar", "market", "restaurant", "grocery"),
        "যাতায়াত" to listOf("ট্যাক্সি", "রিকশা", "বাস", "সিএনজি", "পেট্রোল", "জ্বালানি", "তেল", "রাইড", "পাঠাও", "যাতায়াত", "taxi", "fuel", "uber", "cng"),
        "শপিং" to listOf("কেনাকাটা", "শপিং", "জামা", "কাপড়", "জুতা", "জুতো", "shopping", "clothes"),
        "বিল" to listOf("বিল", "বিদ্যুৎ", "গ্যাস", "পানি", "ইন্টারনেট", "মোবাইল", "রিচার্জ", "wifi", "bill", "electric"),
        "বেতন" to listOf("বেতন", "salary")
    )

    /** Converts Bengali digits (০-৯) to ASCII. */
    fun toAsciiDigits(s: String): String = buildString(s.length) {
        for (ch in s) append(if (ch in '০'..'৯') ('0' + (ch - '০')) else ch)
    }

    /** Number from a run of number words, e.g. "দুই হাজার পাঁচশো". Returns null when no number word is found. */
    fun wordsToNumber(tokens: List<String>): Double? {
        var total = 0.0
        var current = 0.0
        var seen = false
        for (t in tokens) {
            val unit = UNITS[t]
            val scale = SCALES[t]
            when {
                unit != null -> { current += unit; seen = true }
                scale != null -> {
                    seen = true
                    if (scale == 100.0) {
                        current = (if (current == 0.0) 1.0 else current) * 100.0
                    } else {
                        total += (if (current == 0.0) 1.0 else current) * scale
                        current = 0.0
                    }
                }
            }
        }
        return if (seen) total + current else null
    }

    /** Amount from a sentence: digits first ("৫০০ টাকা"), otherwise number words ("পাঁচশো টাকা"). */
    fun findAmount(text: String): Double? {
        val ascii = toAsciiDigits(text)
        NUMBER.find(ascii)?.value?.replace(",", "")?.toDoubleOrNull()?.let { return it }
        val tokens = text.split(Regex("[\\s,।!?]+")).filter { it.isNotBlank() }
        var best: Double? = null
        var run = mutableListOf<String>()
        fun flush() {
            if (run.isNotEmpty()) {
                val n = wordsToNumber(run)
                if (n != null && (best == null || n > best!!)) best = n
                run = mutableListOf()
            }
        }
        for (t in tokens) {
            if (UNITS.containsKey(t) || SCALES.containsKey(t)) run.add(t) else flush()
        }
        flush()
        return best
    }

    fun guessCategory(text: String): String? {
        val lower = text.lowercase()
        return CATEGORY_WORDS.firstOrNull { (_, words) -> words.any { lower.contains(it) } }?.first
    }

    fun parseVoice(transcript: String): VoiceEntry? {
        val amount = findAmount(transcript)?.takeIf { it > 0 } ?: return null
        val lower = transcript.lowercase()
        val type = if (INCOME_WORDS.any { lower.contains(it) }) "Income" else "Expense"
        val category = guessCategory(transcript)?.takeIf { if (type == "Income") it == "বেতন" else it != "বেতন" }
        return VoiceEntry(amount, type, category, transcript.trim())
    }

    private val TOTAL_KEYS = Regex("(?i)(grand\\s*total|net\\s*amount|amount\\s*due|total|payable|paid|মোট|সর্বমোট|পরিশোধ)")
    private val NOT_TOTAL = Regex("(?i)(sub\\s*total|subtotal|সাব|tax|vat|change|ফেরত)")
    private val NUMBER = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?")
    private val DMY = Regex("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4}|\\d{2})")
    private val YMD = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")

    private fun numbersIn(line: String): List<Double> =
        NUMBER.findAll(toAsciiDigits(line)).mapNotNull { it.value.replace(",", "").toDoubleOrNull() }.toList()

    fun parseReceipt(raw: String): ReceiptData {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val totalLine = lines.lastOrNull { TOTAL_KEYS.containsMatchIn(it) && !NOT_TOTAL.containsMatchIn(it) && numbersIn(it).isNotEmpty() }
        val amount = totalLine?.let { numbersIn(it).maxOrNull() }
            ?: lines.filter { !DMY.containsMatchIn(it) && !YMD.containsMatchIn(it) }
                .flatMap { numbersIn(it) }
                .filter { it > 0 && it < 10_000_000 }
                .maxOrNull()

        val date = parseDate(raw)
        val shop = lines.firstOrNull { line ->
            line.length >= 3 && line.any { it.isLetter() } && !TOTAL_KEYS.containsMatchIn(line) &&
                !Regex("(?i)(receipt|invoice|tel|phone|date|bill|cash|tax)").containsMatchIn(line)
        }?.take(40)
        return ReceiptData(amount, date, shop, guessCategory(raw))
    }

    private fun parseDate(raw: String): String? {
        val ascii = toAsciiDigits(raw)
        YMD.find(ascii)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()).toString() }.getOrNull()
        }
        DMY.find(ascii)?.let { m ->
            var year = m.groupValues[3].toInt()
            if (year < 100) year += 2000
            return runCatching { LocalDate.of(year, m.groupValues[2].toInt(), m.groupValues[1].toInt()).toString() }.getOrNull()
        }
        return null
    }
}

/** On-device OCR (ML Kit, free, works offline). Returns all text found in the image. */
suspend fun recognizeReceiptText(ctx: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val image = try {
        InputImage.fromFilePath(ctx, uri)
    } catch (e: Exception) {
        cont.resumeWithException(e)
        return@suspendCancellableCoroutine
    }
    recognizer.process(image)
        .addOnSuccessListener { cont.resume(it.text) }
        .addOnFailureListener { cont.resumeWithException(it) }
    cont.invokeOnCancellation { recognizer.close() }
}
