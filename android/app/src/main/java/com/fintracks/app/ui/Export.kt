package com.fintracks.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.compose.ui.graphics.Color

/** Exports: list as CSV / Excel (.xlsx) / PDF in Bengali, and a single-transaction voucher PDF with QR. */
object Export {

    enum class Format(val ext: String, val mime: String) {
        CSV("csv", "text/csv"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        PDF("pdf", "application/pdf")
    }

    private fun header(): List<String> = listOf(tr("তারিখ", "Date"), tr("ধরন", "Type"), tr("ক্যাটাগরি", "Category"), tr("পরিমাণ (৳)", "Amount (৳)"), tr("পেমেন্ট মাধ্যম", "Payment mode"), tr("অ্যাকাউন্ট", "Account"), tr("নোট", "Note"))

    fun typeBn(t: String): String = when (t) {
        "Income" -> tr("আয়", "Income"); "Expense" -> tr("ব্যয়", "Expense"); "Transfer" -> tr("ট্রান্সফার", "Transfer"); else -> t
    }

    /** Western digits to Bengali digits, for printed PDFs. */
    fun bn(s: String): String = if (AppPrefs.english) s else buildString(s.length) {
        s.forEach { c -> append(if (c in '0'..'9') '০' + (c - '0') else c) }
    }

    fun accountName(state: UiState, t: JsonObject): String =
        state.rows("accounts").firstOrNull { it.id() == t.strOrNull("account_id") }?.str("name").orEmpty()

    private fun rows(state: UiState, txs: List<JsonObject>): List<List<Any>> =
        txs.sortedBy { dateOf(it, "tx_date") }.map { t ->
            listOf(
                displayDate(dateOf(t, "tx_date")), typeBn(t.str("type")), catLabel(t.str("category")), t.num("amount"),
                t.str("payment_mode"), accountName(state, t), t.str("note")
            )
        }

    private fun newFile(ctx: Context, format: Format, prefix: String): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        return File(dir, "$prefix-${System.currentTimeMillis()}.${format.ext}")
    }

    /** A list of transactions (already filtered by period) in the chosen format. */
    fun listFile(ctx: Context, format: Format, state: UiState, txs: List<JsonObject>, title: String): File {
        val file = newFile(ctx, format, "fintrack-list")
        val data = rows(state, txs)
        when (format) {
            Format.CSV -> writeCsv(file, listOf(header() as List<Any>) + data)
            Format.XLSX -> writeXlsx(file, listOf(header() as List<Any>) + data)
            Format.PDF -> writeListPdf(ctx, file, title, txs, state)
        }
        return file
    }

    /** One transaction as a voucher PDF (Bengali, with QR code). */
    fun receiptFile(ctx: Context, state: UiState, t: JsonObject): File = Voucher.singlePdf(ctx, state, t)

    fun share(ctx: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------- CSV ----------------

    private fun writeCsv(file: File, table: List<List<Any>>) {
        val text = buildString {
            append('\uFEFF') // BOM so Excel reads Bengali correctly
            table.forEach { r -> append(r.joinToString(",") { csvCell(it) }).append('\n') }
        }
        file.writeText(text, Charsets.UTF_8)
    }

    private fun csvCell(v: Any): String {
        val s = if (v is Double) fmtNum(v) else v.toString()
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    // ---------------- XLSX (minimal SpreadsheetML in a zip) ----------------

    private fun writeXlsx(file: File, table: List<List<Any>>) {
        val sheetRows = buildString {
            table.forEachIndexed { ri, r ->
                append("<row r=\"${ri + 1}\">")
                r.forEachIndexed { ci, v ->
                    val ref = "${'A' + ci}${ri + 1}"
                    if (v is Double) append("<c r=\"$ref\"><v>$v</v></c>")
                    else append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${xml(v.toString())}</t></is></c>")
                }
                append("</row>")
            }
        }
        val h = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        ZipOutputStream(FileOutputStream(file)).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put("[Content_Types].xml", h +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "</Types>")
            put("_rels/.rels", h +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>")
            put("xl/workbook.xml", h +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"${tr("লেনদেন", "Transactions")}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", h +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "</Relationships>")
            put("xl/worksheets/sheet1.xml", h +
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                sheetRows + "</sheetData></worksheet>")
        }
    }

    private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---------------- Fonts ----------------

    private val fontCache = HashMap<Boolean, Typeface>()

    fun font(ctx: Context, bold: Boolean): Typeface = fontCache.getOrPut(bold) {
        runCatching {
            Typeface.createFromAsset(ctx.applicationContext.assets, if (bold) "fonts/NotoSansBengali-Bold.ttf" else "fonts/NotoSansBengali-Regular.ttf")
        }.getOrDefault(Typeface.DEFAULT)
    }

    private fun paint(ctx: Context, size: Float, color: Int = Color.parseColor("#0F172A"), bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = font(ctx, bold); textAlign = align
        }

    // ---------------- List PDF (A4, Bengali) ----------------

    private fun writeListPdf(ctx: Context, file: File, title: String, txs: List<JsonObject>, state: UiState) {
        val income = txs.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
        val expense = txs.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
        val doc = PdfDocument()
        val pw = 595; val ph = 842
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
        var c: Canvas = page.canvas
        var y = 50f

        val h1 = paint(ctx, 20f, Color.parseColor("#0F172A"), bold = true)
        val small = paint(ctx, 10f, Color.parseColor("#64748B"))
        val body = paint(ctx, 10f)
        val bold = paint(ctx, 10f, bold = true)

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
            c = page.canvas
            y = 50f
        }

        c.drawText(tr("FinTrack লেনদেনের প্রতিবেদন", "FinTrack transaction report"), 40f, y, h1); y += 20f
        // The title already carries the formatted period; the creation date follows the date setting.
        c.drawText("$title · " + tr("তৈরি", "Created") + ": ${displayDate(todayIso())} · " + tr("মোট", "Total") + " ${bn(txs.size.toString())} " + tr("টি লেনদেন", "transactions"), 40f, y, small); y += 28f

        // Summary boxes
        val boxes = listOf(
            Triple(tr("মোট আয়", "Total income"), income, "#10B981"),
            Triple(tr("মোট ব্যয়", "Total expense"), expense, "#F43F5E"),
            Triple(tr("নিট", "Net"), income - expense, if (income - expense >= 0) "#047857" else "#BE123C")
        )
        boxes.forEachIndexed { i, (label, value, col) ->
            val left = 40f + i * 175f
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F1F5F9") }
            c.drawRoundRect(RectF(left, y, left + 160f, y + 58f), 12f, 12f, bg)
            c.drawText(label, left + 12f, y + 22f, small)
            c.drawText(AppPrefs.symbol + " " + bn(String.format("%,.2f", value)), left + 12f, y + 44f, paint(ctx, 14f, Color.parseColor(col), bold = true))
        }
        y += 84f

        val colX = floatArrayOf(40f, 120f, 190f, 330f, 420f)
        fun tableHeader() {
            val hb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F172A") }
            c.drawRect(RectF(40f, y - 14f, 555f, y + 6f), hb)
            val hp = paint(ctx, 10f, Color.WHITE, bold = true)
            c.drawText(tr("তারিখ", "Date"), colX[0] + 4f, y, hp)
            c.drawText(tr("ধরন", "Type"), colX[1], y, hp)
            c.drawText(tr("ক্যাটাগরি", "Category"), colX[2], y, hp)
            c.drawText(tr("মাধ্যম", "Mode"), colX[3], y, hp)
            c.drawText(tr("পরিমাণ", "Amount"), 548f, y, paint(ctx, 10f, Color.WHITE, bold = true, align = Paint.Align.RIGHT))
            y += 22f
        }
        tableHeader()

        val rowsSorted = txs.sortedBy { dateOf(it, "tx_date") }
        rowsSorted.forEachIndexed { idx, t ->
            if (y > 790f) { newPage(); tableHeader() }
            if (idx % 2 == 1) {
                val stripe = Paint().apply { color = Color.parseColor("#F8FAFC") }
                c.drawRect(RectF(40f, y - 13f, 555f, y + 5f), stripe)
            }
            val amountColor = when (t.str("type")) { "Income" -> "#059669"; "Expense" -> "#E11D48"; else -> "#0284C7" }
            c.drawText(displayDate(dateOf(t, "tx_date")), colX[0] + 4f, y, body)
            c.drawText(typeBn(t.str("type")), colX[1], y, body)
            c.drawText(catLabel(t.str("category")).take(14), colX[2], y, body)
            c.drawText(t.str("payment_mode"), colX[3], y, body)
            c.drawText(
                bn(String.format("%,.2f", t.num("amount"))),
                548f, y, paint(ctx, 10f, Color.parseColor(amountColor), bold = true, align = Paint.Align.RIGHT)
            )
            y += 18f
            val note = t.str("note").take(90)
            if (note.isNotBlank()) {
                if (y > 790f) { newPage(); tableHeader() }
                c.drawText(note, colX[2], y - 4f, small)
                y += 12f
            }
        }
        if (txs.isEmpty()) c.drawText(tr("এই সময়সীমায় কোনো লেনদেন নেই", "No transactions in this period"), 40f, y + 10f, body)
        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    // ---------------- Receipt / voucher PDF (single transaction, with QR) ----------------

    fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
