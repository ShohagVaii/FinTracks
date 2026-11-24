package com.fintracks.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Datasets that the Export Center can include. */
enum class ExportSet { TX, DEBTS, CREDIT, NOTES }

class ExportSection(val key: String, val title: String, val header: List<String>, val rows: List<List<Any>>)

class ExportOutput(val file: File, val mime: String)

/** Builds the Export Center files. Fully offline; files go to the cache folder shared through FileProvider. */
object ExportBundle {

    fun sections(state: UiState, range: DateRange, sets: Set<ExportSet>, notes: List<JsonObject>): List<ExportSection> {
        val out = mutableListOf<ExportSection>()
        if (ExportSet.TX in sets) {
            val txs = filterByRange(state.rows("transactions"), range)
            out += ExportSection("transactions", tr("লেনদেন", "Transactions"), Export.header(), Export.rows(state, txs))
        }
        if (ExportSet.DEBTS in sets) {
            val payments = state.rows("debt_payments")
            val rows = state.rows("debts").map { d ->
                listOf<Any>(
                    d.str("person"),
                    if (d.str("direction") == "owed") tr("পাওনা", "Owed to me") else tr("দেনা", "I owe"),
                    d.num("amount"),
                    debtRemaining(d, payments)
                )
            }
            out += ExportSection(
                "debts", tr("ধার-দেনা", "Debts"),
                listOf(tr("ব্যক্তি", "Person"), tr("ধরন", "Type"), tr("মূল পরিমাণ", "Amount"), tr("বাকি", "Remaining")),
                rows
            )
        }
        if (ExportSet.CREDIT in sets) {
            val rows = state.rows("credit_shops").map { s -> listOf<Any>(s.str("name"), shopBalance(s.id(), state)) }
            out += ExportSection("credit", tr("বাকি খাতা", "Credit"), listOf(tr("দোকান", "Shop"), tr("বাকি", "Due")), rows)
        }
        if (ExportSet.NOTES in sets) {
            val rows = notes.map { n -> listOf<Any>(n.str("title"), n.str("body"), n.str("updated_at").take(10)) }
            out += ExportSection(
                "notes", tr("নোট", "Notes"),
                listOf(tr("শিরোনাম", "Title"), tr("লেখা", "Text"), tr("আপডেট", "Updated")),
                rows
            )
        }
        return out
    }

    /** Totals shown on the summary page of the PDF. */
    fun summary(state: UiState, range: DateRange): List<Pair<String, String>> {
        val txs = filterByRange(state.rows("transactions"), range)
        val income = txs.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
        val expense = txs.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
        val credit = state.rows("credit_shops").sumOf { shopBalance(it.id(), state) }
        return listOf(
            tr("মোট আয়", "Income") to taka(income),
            tr("মোট ব্যয়", "Expense") to taka(expense),
            tr("নিট সঞ্চয়", "Net savings") to taka(income - expense),
            tr("মোট পাওনা", "Owed to me") to taka(debtsRemaining(state, "owed")),
            tr("মোট দেনা", "I owe") to taka(debtsRemaining(state, "owe")),
            tr("মোট বাকি", "Credit due") to taka(credit)
        )
    }

    fun build(
        ctx: Context, format: Export.Format, sections: List<ExportSection>,
        title: String, summary: List<Pair<String, String>>, logo: Boolean, pdfSummary: Boolean
    ): ExportOutput {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        return when (format) {
            Export.Format.CSV -> {
                if (sections.size == 1) {
                    val f = File(dir, "fintrack-export-$stamp.csv")
                    f.writeText("\uFEFF" + csvText(sections[0].header, sections[0].rows), Charsets.UTF_8)
                    ExportOutput(f, "text/csv")
                } else {
                    val f = File(dir, "fintrack-export-$stamp.zip")
                    ZipOutputStream(FileOutputStream(f)).use { z ->
                        sections.forEach { s ->
                            z.putNextEntry(ZipEntry("${s.key}.csv"))
                            z.write(("\uFEFF" + csvText(s.header, s.rows)).toByteArray(Charsets.UTF_8))
                            z.closeEntry()
                        }
                    }
                    ExportOutput(f, "application/zip")
                }
            }
            Export.Format.XLSX -> {
                val f = File(dir, "fintrack-export-$stamp.xlsx")
                writeXlsx(f, sections)
                ExportOutput(f, format.mime)
            }
            Export.Format.JSON -> {
                val f = File(dir, "fintrack-export-$stamp.json")
                val root = JSONObject()
                val sum = JSONObject()
                summary.forEach { (k, v) -> sum.put(k, v) }
                root.put("summary", sum)
                sections.forEach { s ->
                    val arr = JSONArray()
                    s.rows.forEach { row ->
                        val o = JSONObject()
                        s.header.forEachIndexed { i, k -> o.put(k, row.getOrNull(i) ?: "") }
                        arr.put(o)
                    }
                    root.put(s.key, arr)
                }
                f.writeText(root.toString(2), Charsets.UTF_8)
                ExportOutput(f, format.mime)
            }
            Export.Format.PDF -> {
                val f = File(dir, "fintrack-export-$stamp.pdf")
                writePdf(ctx, f, title, sections, summary, logo, pdfSummary)
                ExportOutput(f, format.mime)
            }
        }
    }

    private fun cell(v: Any): String = when (v) {
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
        else -> v.toString()
    }

    private fun csvText(header: List<String>, rows: List<List<Any>>): String {
        fun line(cells: List<Any>) = cells.joinToString(",") { v ->
            val s = cell(v)
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
        return (listOf(line(header)) + rows.map { line(it) }).joinToString("\r\n")
    }

    private fun xmlEsc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun writeXlsx(file: File, sections: List<ExportSection>) {
        ZipOutputStream(FileOutputStream(file)).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            val ct = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                .append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                .append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                .append("""<Default Extension="xml" ContentType="application/xml"/>""")
                .append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            val wb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                .append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            val rels = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                .append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            sections.forEachIndexed { idx, s ->
                val n = idx + 1
                ct.append("""<Override PartName="/xl/worksheets/sheet$n.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
                val name = xmlEsc(s.title.replace(Regex("[\\[\\]:*?/\\\\]"), " ").take(31))
                wb.append("""<sheet name="$name" sheetId="$n" r:id="rId$n"/>""")
                rels.append("""<Relationship Id="rId$n" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$n.xml"/>""")
                put("xl/worksheets/sheet$n.xml", sheetXml(s.header, s.rows))
            }
            ct.append("</Types>")
            wb.append("</sheets></workbook>")
            rels.append("</Relationships>")
            put("[Content_Types].xml", ct.toString())
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", wb.toString())
            put("xl/_rels/workbook.xml.rels", rels.toString())
        }
    }

    private fun sheetXml(header: List<String>, rows: List<List<Any>>): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        fun row(r: Int, cells: List<Any>) {
            sb.append("<row r=\"$r\">")
            cells.forEachIndexed { c, v ->
                val ref = "${'A' + c}$r"
                if (v is Number) sb.append("<c r=\"$ref\"><v>${v.toDouble()}</v></c>")
                else sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${xmlEsc(cell(v))}</t></is></c>")
            }
            sb.append("</row>")
        }
        row(1, header)
        rows.forEachIndexed { i, r -> row(i + 2, r) }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun writePdf(
        ctx: Context, file: File, title: String, sections: List<ExportSection>,
        summary: List<Pair<String, String>>, logo: Boolean, pdfSummary: Boolean
    ) {
        val W = 595
        val H = 842
        val M = 36f
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var c = page.canvas
        var y = M

        fun paint(size: Float, bold: Boolean = false, color: Int = Color.parseColor("#0F172A")) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = Export.font(ctx, bold) }

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            y = M
        }

        fun header(t: String) {
            val id = ctx.resources.getIdentifier("notif_large_logo", "drawable", ctx.packageName)
            if (logo && id != 0) {
                BitmapFactory.decodeResource(ctx.resources, id)?.let { bmp ->
                    c.drawBitmap(bmp, null, android.graphics.RectF(M, y, M + 56f, y + 56f), null)
                }
                c.drawText(t, M + 68f, y + 36f, paint(18f, true))
                y += 68f
            } else {
                c.drawText(t, M, y + 20f, paint(18f, true))
                y += 40f
            }
        }

        header(title)
        if (pdfSummary) {
            y += 12f
            summary.forEach { (k, v) ->
                c.drawText(k, M, y + 16f, paint(13f))
                val vp = paint(13f, true)
                c.drawText(bnDigits(v), W - M - vp.measureText(bnDigits(v)), y + 16f, vp)
                y += 26f
            }
            newPage()
            header(title)
        }

        val usable = W - 2 * M
        sections.forEach { s ->
            if (y + 40f > H - M) newPage()
            c.drawText(s.title, M, y + 16f, paint(14f, true))
            y += 26f
            val cols = s.header.size.coerceAtLeast(1)
            val colW = usable / cols
            fun drawRow(cells: List<String>, bold: Boolean, shade: Boolean) {
                if (shade) {
                    val bg = Paint().apply { color = Color.parseColor("#E2E8F0") }
                    c.drawRect(M, y, W - M, y + 22f, bg)
                }
                cells.forEachIndexed { i, text ->
                    val p = paint(10f, bold)
                    val tp = TextPaint(p)
                    val fitted = TextUtils.ellipsize(bnDigits(text), tp, colW - 8f, TextUtils.TruncateAt.END).toString()
                    c.drawText(fitted, M + i * colW + 4f, y + 15f, p)
                }
                y += 22f
            }
            drawRow(s.header, true, true)
            if (s.rows.isEmpty()) {
                c.drawText(tr("কোনো তথ্য নেই", "No data"), M, y + 14f, paint(10f, color = Color.GRAY))
                y += 24f
            }
            s.rows.forEach { r ->
                if (y + 24f > H - M) {
                    newPage()
                    drawRow(s.header, true, true)
                }
                drawRow(r.map { cell(it) }, false, false)
            }
            y += 16f
        }
        doc.finishPage(page)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
    }
}
