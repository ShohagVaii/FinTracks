package com.fintracks.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.pdf.PdfDocument
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * FinTrack voucher: TRX ID, checksum, QR payload, the server registry record, and the A4 renderer
 * (single entry and summary). Drawing uses PDF points on a 595 x 842 page; PNG output is 2x.
 */
object Voucher {
    const val PAGE_W = 595f
    const val PAGE_H = 842f
    const val APP_URL = "https://shohagvaii.github.io/FinTracks/"

    private const val L = 28f
    private const val T = 28f
    private const val R = 567f
    private const val B = 814f
    private const val SEP = "\u001F"

    private val MONTHS = listOf(
        "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
        "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"
    )

    /** One saved voucher record. The checksum is fixed at first save and never recomputed. */
    data class Stored(
        val trx: String,
        val checksum: String,
        val type: String,
        val amount: Double,
        val date: String,
        val category: String,
        val method: String,
        val profile: String,
        val status: String
    )

    /** Summary voucher reference, issued by the server registry (see AppViewModel.summaryRef). */
    data class SummaryRef(val batch: String, val checksum: String, val period: String, val date: String)

    // ---------------- Data: TRX ID, checksum, QR payload ----------------

    /** Permanent TRX ID: first 8 hex characters of the transaction UUID, upper case. */
    fun trxOf(t: JsonObject): String = t.id().replace("-", "").take(8).uppercase()

    fun typeCode(t: JsonObject): String = when (t.str("type")) {
        "Income" -> "INCOME"
        "Expense" -> "EXPENSE"
        else -> "TRANSFER"
    }

    fun amount2(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    /** Transaction checksum = first 10 characters of SHA-256("TRX|TYPE|AMOUNT|DATE|PROFILE"). */
    fun checksum(trx: String, type: String, amount: Double, date: String, profile: String): String =
        sha256Hex("$trx|$type|${amount2(amount)}|$date|$profile").take(10)

    /** Summary checksum = first 10 characters of SHA-256("BATCH|SUMMARY|INCOME|EXPENSE|PERIOD"). */
    fun summaryChecksum(income: Double, expense: Double, period: String): String =
        sha256Hex("BATCH|SUMMARY|${amount2(income)}|${amount2(expense)}|$period").take(10)

    fun periodOf(from: String, to: String): String = "$from..$to"

    fun qrPayload(trx: String, type: String, amount: Double, date: String, checksum: String): String = listOf(
        "FINTRACK-VERIFY v1",
        "TRX: $trx",
        "TYPE: $type",
        "AMOUNT: ${amount2(amount)} BDT",
        "DATE: $date",
        "CHECKSUM: $checksum",
        "APP: $APP_URL"
    ).joinToString("\n")

    fun profileName(profile: JsonObject?): String = profile?.str("name").orEmpty()

    // ---------------- Local store (per account on this device) ----------------

    private fun prefsOf(ctx: Context, profile: JsonObject?): SharedPreferences {
        val owner = profile?.str("id").orEmpty().ifEmpty { "anon" }
        return ctx.getSharedPreferences("voucher_store_$owner", Context.MODE_PRIVATE)
    }

    private fun encode(r: Stored): String = listOf(
        r.trx, r.checksum, r.type, amount2(r.amount), r.date, r.category, r.method, r.profile, r.status
    ).joinToString(SEP)

    private fun decode(s: String?): Stored? {
        val p = s?.split(SEP) ?: return null
        if (p.size != 9) return null
        return Stored(p[0], p[1], p[2], p[3].toDoubleOrNull() ?: 0.0, p[4], p[5], p[6], p[7], p[8])
    }

    /** Keeps the saved checksum; refreshes the display details from the live row. New rows get a fresh checksum. */
    private fun refresh(old: Stored?, profile: JsonObject?, t: JsonObject): Stored {
        val type = typeCode(t)
        val amt = t.num("amount")
        val date = t.str("tx_date").take(10)
        val cat = t.str("category")
        val method = t.str("payment_mode")
        if (old != null) {
            return old.copy(type = type, amount = amt, date = date, category = cat, method = method, status = "active")
        }
        val trx = trxOf(t)
        val name = profileName(profile)
        return Stored(trx, checksum(trx, type, amt, date, name), type, amt, date, cat, method, name, "active")
    }

    /** The saved record for one transaction (created on first sight, then kept). */
    fun storedFor(ctx: Context, profile: JsonObject?, t: JsonObject): Stored {
        val prefs = prefsOf(ctx, profile)
        val key = "tx_${t.id()}"
        val rec = refresh(decode(prefs.getString(key, null)), profile, t)
        prefs.edit().putString(key, encode(rec)).apply()
        return rec
    }

    /**
     * Brings the local store up to date with the live transactions and returns every record
     * (active and void) for the server registry. A deleted transaction becomes "void"; it is never removed.
     */
    fun syncLocal(ctx: Context, profile: JsonObject?, txs: List<JsonObject>): List<Stored> {
        val prefs = prefsOf(ctx, profile)
        val ed = prefs.edit()
        val live = txs.map { "tx_${it.id()}" }.toSet()
        val out = mutableListOf<Stored>()
        txs.forEach { t ->
            val key = "tx_${t.id()}"
            val rec = refresh(decode(prefs.getString(key, null)), profile, t)
            ed.putString(key, encode(rec))
            out.add(rec)
        }
        prefs.all.keys.filter { it.startsWith("tx_") && it !in live }.forEach { key ->
            val old = decode(prefs.getString(key, null)) ?: return@forEach
            val voided = old.copy(status = "void")
            ed.putString(key, encode(voided))
            out.add(voided)
        }
        ed.apply()
        return out
    }

    /** Row for the voucher_registry table (the user id is added by the repository). */
    fun registryRow(r: Stored): JsonObject = buildJsonObject {
        put("trx_id", r.trx)
        put("checksum", r.checksum)
        put("type", r.type)
        put("amount", r.amount)
        put("date", r.date)
        put("category", r.category)
        put("method", r.method)
        put("profile_name", r.profile)
        put("status", r.status)
    }

    // ---------------- Dates and money ----------------

    fun bnDate(iso: String): String = runCatching { LocalDate.parse(iso) }.map { d ->
        "${Export.bn(d.dayOfMonth.toString())} ${MONTHS[d.monthValue - 1]} ${Export.bn(d.year.toString())}"
    }.getOrDefault(iso)

    private fun shortDate(iso: String): String = runCatching { LocalDate.parse(iso) }.map { d ->
        "${Export.bn(d.dayOfMonth.toString())} ${MONTHS[d.monthValue - 1].take(3)}"
    }.getOrDefault(iso)

    /** Issue time, e.g. "২১ নভেম্বর ২০২৬ · ৩:১০ AM" (Bengali digits, 12-hour clock, device time zone). */
    fun issueText(now: ZonedDateTime = ZonedDateTime.now()): String {
        val h = if (now.hour % 12 == 0) 12 else now.hour % 12
        val time = Export.bn("$h:${now.minute.toString().padStart(2, '0')}")
        return "${bnDate(now.toLocalDate().toString())} · $time ${if (now.hour < 12) "AM" else "PM"}"
    }

    private fun money(v: Double, signPrefix: String = "", twoDecimals: Boolean = false): String {
        val body = String.format(Locale.US, if (twoDecimals || v % 1.0 != 0.0) "%,.2f" else "%,.0f", abs(v))
        return signPrefix + "৳ " + Export.bn(body)
    }

    // ---------------- Drawing helpers ----------------

    private fun text(ctx: Context, size: Float, color: String, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = Color.parseColor(color)
            typeface = Export.font(ctx, bold)
            textAlign = align
        }

    private fun solid(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun line(color: String, width: Float = 1f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor(color)
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    /** Shortens text with an ellipsis so it never runs past maxW. */
    private fun fitText(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var cut = s
        while (cut.isNotEmpty() && p.measureText("$cut…") > maxW) cut = cut.dropLast(1)
        return "$cut…"
    }

    private fun cardPath(): Path = Path().apply { addRoundRect(RectF(L, T, R, B), 26f, 26f, Path.Direction.CW) }

    /** Page background, white card and the light repeated watermark (drawn under everything else). */
    private fun base(c: Canvas, ctx: Context) {
        c.drawColor(Color.parseColor("#E9EEF5"))
        c.drawPath(cardPath(), solid(Color.WHITE))
        c.save()
        c.clipPath(cardPath())
        c.rotate(-28f, PAGE_W / 2, PAGE_H / 2)
        val wm = text(ctx, 26f, "#000000", true, Paint.Align.CENTER).apply { color = Color.argb(22, 100, 116, 139) }
        for (r in -9..9) for (col in -2..2) {
            val x = PAGE_W / 2 + col * 330f + (if (r % 2 == 0) 0f else 165f)
            c.drawText("FINTRACK PREMIUM", x, PAGE_H / 2 + r * 96f, wm)
        }
        c.restore()
    }

    private fun header(c: Canvas, ctx: Context, chip: String, subtitle: String, issue: String) {
        val headPath = Path().apply {
            addRoundRect(
                RectF(L, T, R, 140f),
                floatArrayOf(26f, 26f, 26f, 26f, 0f, 0f, 0f, 0f),
                Path.Direction.CW
            )
        }
        val grad = solid(Color.WHITE).apply {
            shader = LinearGradient(L, 0f, R, 0f, Color.parseColor("#0A1A33"), Color.parseColor("#0066B3"), Shader.TileMode.CLAMP)
        }
        c.drawPath(headPath, grad)
        c.drawText("FINTRACK PREMIUM", 60f, 76f, text(ctx, 24f, "#FFFFFF", true))
        c.drawText(subtitle, 60f, 102f, text(ctx, 13f, "#A5F3FC", true))

        val chipP = text(ctx, 12f, "#FFFFFF", true, Paint.Align.CENTER)
        val w = chipP.measureText(chip) + 32f
        val rect = RectF(R - 32f - w, 52f, R - 32f, 86f)
        c.drawRoundRect(rect, 17f, 17f, solid(Color.argb(70, 255, 255, 255)))
        c.drawRoundRect(rect, 17f, 17f, line("#80FFFFFF", 1f))
        c.drawText(chip, rect.centerX(), 73f, chipP)

        c.drawText(issue, PAGE_W / 2, 166f, text(ctx, 11f, "#64748B", true, Paint.Align.CENTER))
    }

    /** Red VERIFIED stamp (always red), slightly rotated. */
    private fun stamp(c: Canvas, ctx: Context, cx: Float, cy: Float) {
        val red = "#C8102E"
        c.save()
        c.rotate(-12f, cx, cy)
        c.drawCircle(cx, cy, 50f, line(red, 3f))
        c.drawCircle(cx, cy, 43f, line(red, 1f))
        val tick = line(red, 3f).apply { strokeCap = Paint.Cap.ROUND }
        val p = Path().apply {
            moveTo(cx - 11f, cy - 14f)
            lineTo(cx - 3f, cy - 6f)
            lineTo(cx + 11f, cy - 24f)
        }
        c.drawPath(p, tick)
        c.drawText("VERIFIED", cx, cy + 8f, text(ctx, 14f, red, true, Paint.Align.CENTER))
        c.drawText("FINTRACK", cx, cy + 22f, text(ctx, 9f, red, true, Paint.Align.CENTER))
        c.restore()
    }

    private fun heart(c: Canvas, cx: Float, cy: Float, s: Float) {
        val p = Path()
        p.moveTo(cx, cy + s * 0.9f)
        p.cubicTo(cx - s * 1.3f, cy + s * 0.1f, cx - s * 0.9f, cy - s * 1.0f, cx, cy - s * 0.4f)
        p.cubicTo(cx + s * 0.9f, cy - s * 1.0f, cx + s * 1.3f, cy + s * 0.1f, cx, cy + s * 0.9f)
        p.close()
        c.drawPath(p, solid(Color.parseColor("#E11D48")))
    }

    /** Footer inside the card: slogan line, then "CRAFTED WITH ♥ BY SHOHAG VAII". */
    private fun footer(c: Canvas, ctx: Context, sepY: Float) {
        c.drawLine(84f, sepY, 511f, sepY, line("#E2E8F0"))
        c.drawText(
            "FinTrack সুরক্ষিত লেজার — শুধুমাত্র আপনার জন্য",
            PAGE_W / 2, sepY + 22f, text(ctx, 11f, "#0F172A", true, Paint.Align.CENTER)
        )
        val small = text(ctx, 9.5f, "#94A3B8")
        val left = "Generated by FinTrack Premium · CRAFTED WITH "
        val right = " BY SHOHAG VAII"
        val heartW = 2.6f * 7f
        val lw = small.measureText(left)
        val total = lw + heartW + 3f + small.measureText(right)
        val x0 = PAGE_W / 2 - total / 2
        val y = sepY + 38f
        c.drawText(left, x0, y, small)
        heart(c, x0 + lw + heartW / 2, y - 3f, 7f)
        c.drawText(right, x0 + lw + heartW + 3f, y, small)
    }

    private fun qrAndSecure(c: Canvas, ctx: Context, qrRect: RectF, payload: String, secureLabel: String, checksum: String, textX: Float, textY: Float) {
        c.drawRoundRect(qrRect, 12f, 12f, solid(Color.WHITE))
        c.drawRoundRect(qrRect, 12f, 12f, line("#CBD5E1", 1f))
        val pad = 8f
        val qr = Export.qrBitmap(payload, 256)
        c.drawBitmap(qr, null, RectF(qrRect.left + pad, qrRect.top + pad, qrRect.right - pad, qrRect.bottom - pad), null)
        qr.recycle()
        c.drawText(secureLabel, textX, textY, text(ctx, 15f, "#0F172A", true))
        c.drawText("চেকসাম:", textX, textY + 30f, text(ctx, 11f, "#64748B"))
        c.drawText(checksum, textX + 68f, textY + 30f, text(ctx, 13f, "#0F172A", true))
        c.drawText("অ্যাপ ডাউনলোড:", textX, textY + 60f, text(ctx, 11f, "#64748B"))
        c.drawText("shohagvaii.github.io/FinTracks", textX, textY + 78f, text(ctx, 12f, "#0369A1", true))
    }

    // ---------------- Single-entry voucher ----------------

    fun drawSingle(c: Canvas, ctx: Context, t: JsonObject, state: UiState, now: ZonedDateTime = ZonedDateTime.now()) {
        val rec = storedFor(ctx, state.profile, t)
        val type = t.str("type")
        val amt = t.num("amount")
        val date = rec.date
        val income = type == "Income"
        val expense = type == "Expense"
        val accent = when {
            income -> "#047857"
            expense -> "#BE123C"
            else -> "#0369A1"
        }
        val valueColor = when {
            income -> "#065F46"
            expense -> "#BE123C"
            else -> "#0C4A6E"
        }
        val g0 = when {
            income -> "#ECFDF5"
            expense -> "#FFF1F2"
            else -> "#F0F9FF"
        }
        val g1 = when {
            income -> "#D1FAE5"
            expense -> "#FFE4E6"
            else -> "#E0F2FE"
        }
        val edge = when {
            income -> "#6EE7B7"
            expense -> "#FDA4AF"
            else -> "#7DD3FC"
        }

        base(c, ctx)
        header(c, ctx, "TRX ID: #${rec.trx}", "অফিসিয়াল লেনদেন ভাউচার", "ইস্যু সময়: ${issueText(now)}")

        // Amount card (green for income, red for expense)
        val box = RectF(60f, 180f, 535f, 326f)
        c.drawRoundRect(box, 18f, 18f, solid(Color.WHITE).apply {
            shader = LinearGradient(box.left, 0f, box.right, 0f, Color.parseColor(g0), Color.parseColor(g1), Shader.TileMode.CLAMP)
        })
        c.drawRoundRect(box, 18f, 18f, line(edge, 1.2f))
        val label = when {
            income -> "মোট আয় (INCOME)"
            expense -> "মোট ব্যয় (EXPENSE)"
            else -> "ট্রান্সফার (TRANSFER)"
        }
        c.drawText(label, 297f, 206f, text(ctx, 13f, accent, true, Paint.Align.CENTER))
        val sign = when {
            income -> "+ "
            expense -> "- "
            else -> ""
        }
        c.drawText(money(amt, sign, twoDecimals = true), 297f, 266f, text(ctx, 42f, valueColor, true, Paint.Align.CENTER))
        val subText = "যাচাইকৃত ও FinTrack-এ সংরক্ষিত"
        val subP = text(ctx, 11f, accent, true, Paint.Align.CENTER)
        c.drawText(subText, 297f, 298f, subP)
        // Check mark drawn as a path (the Bengali font may not include the glyph)
        val tx = 297f - subP.measureText(subText) / 2 - 12f
        val tick = line(accent, 1.6f).apply { strokeCap = Paint.Cap.ROUND }
        c.drawPath(Path().apply { moveTo(tx, 293f); lineTo(tx + 3f, 296.5f); lineTo(tx + 8f, 289f) }, tick)
        stamp(c, ctx, 482f, 253f)

        // Detail table
        val rows = listOf(
            "লেনদেনের তারিখ" to bnDate(date),
            "ক্যাটাগরি" to t.str("category").ifEmpty { "-" },
            "পেমেন্ট মেথড" to t.str("payment_mode").ifEmpty { "-" },
            "অ্যাকাউন্ট" to Export.accountName(state, t).ifEmpty { "-" },
            "মুড" to t.str("mood").ifEmpty { "-" },
            "নোট" to t.str("note").ifEmpty { "-" },
            "প্রোফাইল" to rec.profile.ifEmpty { "-" }
        )
        val labelP = text(ctx, 12.5f, "#64748B")
        val valueP = text(ctx, 13f, "#0F172A", true, Paint.Align.RIGHT)
        var y = 356f
        rows.forEach { (k, v) ->
            c.drawText(k, 84f, y, labelP)
            c.drawText(fitText(v, valueP, 280f), 511f, y, valueP)
            c.drawLine(84f, y + 10f, 511f, y + 10f, line("#E2E8F0"))
            y += 30f
        }
        val dash = line("#CBD5E1", 1.5f).apply { pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f) }
        c.drawLine(84f, 566f, 511f, 566f, dash)

        qrAndSecure(
            c, ctx,
            qrRect = RectF(84f, 584f, 224f, 724f),
            payload = qrPayload(rec.trx, typeCode(t), amt, date, rec.checksum),
            secureLabel = "FT-${rec.trx}-SECURE",
            checksum = rec.checksum,
            textX = 250f,
            textY = 616f
        )
        c.drawText("যাচাই করতে স্ক্যান করুন", 154f, 742f, text(ctx, 9.5f, "#64748B", false, Paint.Align.CENTER))
        footer(c, ctx, 762f)
    }

    // ---------------- Summary voucher (many entries) ----------------

    fun drawSummary(c: Canvas, ctx: Context, txs: List<JsonObject>, label: String, state: UiState, ref: SummaryRef, now: ZonedDateTime = ZonedDateTime.now()) {
        val income = txs.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
        val expense = txs.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
        val net = income - expense
        val netColor = if (net >= 0) "#0F172A" else "#BE123C"
        val dash = line("#CBD5E1", 1.5f).apply { pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f) }

        base(c, ctx)
        val issue = fitText(
            "ইস্যু সময়: ${issueText(now)} · সময়কাল: $label",
            text(ctx, 11f, "#64748B", true, Paint.Align.CENTER), 500f
        )
        header(c, ctx, "BATCH: #${ref.batch}", "লেনদেনের সারসংক্ষেপ ভাউচার", issue)

        // Totals strip
        val strip = RectF(60f, 180f, 535f, 246f)
        c.drawRoundRect(strip, 16f, 16f, solid(Color.parseColor("#F8FAFC")))
        c.drawRoundRect(strip, 16f, 16f, line("#E2E8F0"))
        val colW = strip.width() / 3f
        val stats = listOf(
            Triple("মোট আয়", money(income, "+ "), "#047857"),
            Triple("মোট ব্যয়", money(expense, "- "), "#BE123C"),
            Triple("নিট", money(abs(net), if (net < 0) "- " else "+ "), netColor)
        )
        stats.forEachIndexed { i, (k, v, col) ->
            val cx = strip.left + colW * i + colW / 2
            c.drawText(k, cx, 200f, text(ctx, 11f, "#64748B", false, Paint.Align.CENTER))
            c.drawText(v, cx, 228f, text(ctx, 16f, col, true, Paint.Align.CENTER))
            if (i > 0) c.drawLine(strip.left + colW * i, 190f, strip.left + colW * i, 236f, line("#E2E8F0"))
        }

        // Entries table: rows shrink to fit; anything that does not fit is said in one line, never clipped
        val headP = text(ctx, 10.5f, "#64748B", true)
        c.drawText("তারিখ", 84f, 270f, headP)
        c.drawText("বিবরণ", 168f, 270f, headP)
        c.drawText("মেথড", 368f, 270f, headP)
        c.drawText("পরিমাণ", 511f, 270f, text(ctx, 10.5f, "#64748B", true, Paint.Align.RIGHT))
        c.drawLine(84f, 278f, 511f, 278f, line("#94A3B8", 1f))

        val n = txs.size
        val avail = 270f
        val rowH = when {
            n * 28f <= avail -> 28f
            n * 16f <= avail -> avail / n
            else -> 16f
        }
        val showAll = n * rowH <= avail
        val shown = if (showAll) n else (avail / rowH).toInt() - 1
        val size = (rowH * 0.5f).coerceIn(8f, 11f)
        var top = 282f
        if (n == 0) {
            c.drawText("এই সময়কালে কোনো লেনদেন নেই", 297f, 312f, text(ctx, 12f, "#64748B", false, Paint.Align.CENTER))
        }
        txs.take(shown).forEach { t ->
            val baseY = top + rowH * 0.68f
            val type = t.str("type")
            val col = when (type) {
                "Income" -> "#047857"
                "Expense" -> "#BE123C"
                else -> "#0369A1"
            }
            val sgn = when (type) {
                "Income" -> "+ "
                "Expense" -> "- "
                else -> ""
            }
            val desc = listOf(t.str("category"), t.str("note")).filter { it.isNotBlank() }.joinToString(" — ").ifEmpty { "-" }
            val dark = text(ctx, size, "#0F172A")
            val muted = text(ctx, size, "#64748B")
            c.drawText(shortDate(t.str("tx_date")), 84f, baseY, muted)
            c.drawText(fitText(desc, dark, 190f), 168f, baseY, dark)
            c.drawText(fitText(t.str("payment_mode").ifEmpty { "-" }, muted, 90f), 368f, baseY, muted)
            c.drawText(money(t.num("amount"), sgn), 511f, baseY, text(ctx, size, col, true, Paint.Align.RIGHT))
            c.drawLine(84f, top + rowH, 511f, top + rowH, line("#E2E8F0"))
            top += rowH
        }
        if (!showAll) {
            val more = "… আরও ${Export.bn((n - shown).toString())} টি এন্ট্রি (সম্পূর্ণ তালিকা CSV/Excel-এ পাওয়া যাবে)"
            c.drawText(fitText(more, text(ctx, size, "#64748B"), 427f), 84f, top + rowH * 0.68f, text(ctx, size, "#64748B"))
        }

        // Totals line and QR block
        c.drawText("মোট ${Export.bn(n.toString())} টি লেনদেন", 84f, 578f, text(ctx, 12f, "#0F172A", true))
        c.drawText(
            "নিট: ${money(abs(net), if (net < 0) "- " else "+ ")}",
            511f, 578f, text(ctx, 12f, netColor, true, Paint.Align.RIGHT)
        )
        c.drawLine(84f, 592f, 511f, 592f, dash)
        qrAndSecure(
            c, ctx,
            qrRect = RectF(84f, 604f, 200f, 720f),
            payload = qrPayload(ref.batch, "SUMMARY", net, ref.date, ref.checksum),
            secureLabel = "FT-${ref.batch}-SECURE",
            checksum = ref.checksum,
            textX = 224f,
            textY = 630f
        )
        c.drawText("যাচাই করতে স্ক্যান করুন", 142f, 738f, text(ctx, 9.5f, "#64748B", false, Paint.Align.CENTER))
        footer(c, ctx, 752f)
    }

    // ---------------- Output: bitmaps, PDF files ----------------

    private fun bitmap2x(draw: (Canvas) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(PAGE_W.toInt() * 2, PAGE_H.toInt() * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(2f, 2f)
        draw(c)
        return bmp
    }

    fun renderSingle(ctx: Context, state: UiState, t: JsonObject): Bitmap =
        bitmap2x { drawSingle(it, ctx, t, state) }

    private fun newFile(ctx: Context, ext: String, prefix: String): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        return File(dir, "$prefix-${System.currentTimeMillis()}.$ext")
    }

    private fun pdfTo(file: File, draw: (Canvas) -> Unit) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), 1).create())
        draw(page.canvas)
        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    /** Single-transaction voucher as a one-page A4 PDF, timed at the moment of creation. */
    fun singlePdf(ctx: Context, state: UiState, t: JsonObject): File {
        val file = newFile(ctx, "pdf", "fintrack-voucher")
        pdfTo(file) { drawSingle(it, ctx, t, state) }
        return file
    }

    fun summaryPdf(ctx: Context, state: UiState, txs: List<JsonObject>, label: String, ref: SummaryRef): File {
        val file = newFile(ctx, "pdf", "fintrack-summary")
        pdfTo(file) { drawSummary(it, ctx, txs, label, state, ref) }
        return file
    }

    fun pngFile(ctx: Context, bmp: Bitmap): File {
        val file = newFile(ctx, "png", "fintrack-voucher")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }
}
