package com.fintracks.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports transactions as CSV, Excel (.xlsx) or PDF, then opens the share sheet. All files stay in app cache. */
object Export {

    enum class Format(val ext: String, val mime: String) {
        CSV("csv", "text/csv"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        PDF("pdf", "application/pdf")
    }

    private val header = listOf("Date", "Type", "Category", "Amount (BDT)", "Payment mode", "Account", "Note")

    private fun rows(state: UiState): List<List<Any>> =
        state.rows("transactions").sortedBy { it.str("tx_date") }.map { t ->
            val account = state.rows("accounts").firstOrNull { it.id() == t.strOrNull("account_id") }?.str("name").orEmpty()
            listOf(
                dateOf(t, "tx_date"), t.str("type"), t.str("category"), t.num("amount"),
                t.str("payment_mode"), account, t.str("note")
            )
        }

    fun create(ctx: Context, format: Format, state: UiState): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "fintrack-${todayIso()}.${format.ext}")
        val data = rows(state)
        when (format) {
            Format.CSV -> writeCsv(file, listOf(header.map { it as Any }) + data)
            Format.XLSX -> writeXlsx(file, listOf(header.map { it as Any }) + data)
            Format.PDF -> writePdf(file, state, data)
        }
        return file
    }

    fun share(ctx: Context, file: File, format: Format) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(format.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "FinTrack এক্সপোর্ট").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

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

    /** Minimal valid .xlsx (SpreadsheetML inside a zip). Numbers are stored as numbers. */
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
        val xmlHead = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        ZipOutputStream(FileOutputStream(file)).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put("[Content_Types].xml", xmlHead +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "</Types>")
            put("_rels/.rels", xmlHead +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>")
            put("xl/workbook.xml", xmlHead +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Transactions\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", xmlHead +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "</Relationships>")
            put("xl/worksheets/sheet1.xml", xmlHead +
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                sheetRows + "</sheetData></worksheet>")
        }
    }

    private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** PDF summary + transaction table. Labels are in English so every font renders them. */
    private fun writePdf(file: File, state: UiState, data: List<List<Any>>) {
        val income = data.filter { it[1] == "Income" }.sumOf { (it[3] as Double) }
        val expense = data.filter { it[1] == "Expense" }.sumOf { (it[3] as Double) }
        val lines = mutableListOf(
            "Income: BDT ${String.format("%,.2f", income)}   Expense: BDT ${String.format("%,.2f", expense)}   Net: BDT ${String.format("%,.2f", income - expense)}",
            "Generated: ${todayIso()}   Transactions: ${data.size}",
            ""
        )
        lines += "Date         Type      Category        Amount        Mode"
        data.forEach { r ->
            lines += String.format("%-12s %-9s %-15s %12.2f  %s", r[0], r[1], r[2].toString().take(14), r[3], r[4])
        }
        val doc = PdfDocument()
        val body = Paint().apply { textSize = 10f; isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE }
        val title = Paint(body).apply { textSize = 16f; isFakeBoldText = true; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
        var canvas = page.canvas
        var y = 40f
        canvas.drawText("FinTrack report", 40f, y, title)
        y += 26f
        lines.forEach { line ->
            if (y > 800f) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
                canvas = page.canvas
                y = 40f
            }
            canvas.drawText(line, 40f, y, body)
            y += 15f
        }
        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
