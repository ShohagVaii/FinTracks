package com.fintracks.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream

/** Plain-text lines of one person's statement: each direction, every entry with paid and due, then the net. */
internal fun statementLines(person: JsonObject, state: UiState): List<String> {
    val payments = state.rows("debt_payments")
    val out = mutableListOf(tr("খতিয়ান: ", "Statement: ") + person.str("name"))
    for (dir in listOf("owed", "owe")) {
        val entries = personEntries(state, person.id(), dir)
        if (entries.isEmpty()) continue
        out += ""
        out += if (dir == "owed") tr("পাওনা", "Owed to me") else tr("দেনা", "I owe")
        entries.forEach { e ->
            val due = e.strOrNull("due_date")?.take(10)?.let { " · " + tr("মেয়াদ", "Due") + " " + displayDate(it) }.orEmpty()
            out += "${e.str("note").ifBlank { "-" }} · " + tr("মোট", "Total") + " ${taka(e.num("amount"))} · " +
                tr("পরিশোধ", "Paid") + " ${taka(debtPaid(e, payments))} · " +
                tr("বাকি", "Due") + " ${taka(debtRemaining(e, payments))}" + due
        }
    }
    out += ""
    out += tr("নিট", "Net") + ": " + taka(personRemaining(state, person.id(), "owed") - personRemaining(state, person.id(), "owe"))
    return out
}

private fun statementFile(ctx: Context, name: String): File =
    File(ctx.cacheDir, "exports").apply { mkdirs() }.let { File(it, name) }

internal fun shareFile(ctx: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, tr("খতিয়ান শেয়ার", "Share statement")))
}

private fun wrapStatement(lines: List<String>): List<String> = lines.flatMap { if (it.isEmpty()) listOf("") else it.chunked(72) }

/** A4 PDF with the statement lines; long lines wrap and long statements continue on the next page. */
internal fun writeStatementPdf(ctx: Context, title: String, lines: List<String>): File {
    val doc = PdfDocument()
    val pageW = 595
    val pageH = 842
    val body = Paint().apply { isAntiAlias = true; textSize = 11f; color = AndroidColor.BLACK }
    val head = Paint(body).apply { textSize = 16f; isFakeBoldText = true }
    var pageNo = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
    var y = 50f
    page.canvas.drawText(title, 40f, y, head)
    y += 28f
    for (line in wrapStatement(lines)) {
        if (y > pageH - 40f) {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = 50f
        }
        page.canvas.drawText(line, 40f, y, body)
        y += 17f
    }
    doc.finishPage(page)
    val file = statementFile(ctx, "statement-${System.currentTimeMillis()}.pdf")
    FileOutputStream(file).use { doc.writeTo(it) }
    doc.close()
    return file
}

/** PNG of the same lines on a white card, 1080 px wide. */
internal fun writeStatementImage(ctx: Context, title: String, lines: List<String>): File {
    val rows = wrapStatement(lines)
    val width = 1080
    val height = 160 + rows.size * 40
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(AndroidColor.WHITE)
    val body = Paint().apply { isAntiAlias = true; textSize = 30f; color = AndroidColor.rgb(30, 30, 40) }
    val head = Paint(body).apply { textSize = 44f; isFakeBoldText = true; color = AndroidColor.rgb(57, 199, 224) }
    canvas.drawText(title, 60f, 90f, head)
    var y = 150f
    for (line in rows) {
        canvas.drawText(line, 60f, y, body)
        y += 40f
    }
    val file = statementFile(ctx, "statement-${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return file
}

private fun shareStatementAs(ctx: Context, person: JsonObject, state: UiState, kind: String) {
    val lines = statementLines(person, state)
    val title = tr("খতিয়ান: ", "Statement: ") + person.str("name")
    try {
        when (kind) {
            "pdf" -> shareFile(ctx, writeStatementPdf(ctx, title, lines), "application/pdf")
            "image" -> shareFile(ctx, writeStatementImage(ctx, title, lines), "image/png")
            else -> {
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
                ctx.startActivity(Intent.createChooser(intent, tr("খতিয়ান শেয়ার", "Share statement")))
            }
        }
    } catch (e: Exception) {
        Toast.makeText(ctx, tr("খতিয়ান তৈরি করা যায়নি", "Could not create the statement"), Toast.LENGTH_SHORT).show()
    }
}

/** Choose the statement format: text, PDF or image. The same content in all three. */
@Composable
fun StatementShareDialog(person: JsonObject, state: UiState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("খতিয়ান শেয়ার", "Share statement"), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { shareStatementAs(ctx, person, state, "text"); onDismiss() }) { Text(tr("টেক্সট", "Text")) }
                TextButton(onClick = { shareStatementAs(ctx, person, state, "pdf"); onDismiss() }) { Text("PDF") }
                TextButton(onClick = { shareStatementAs(ctx, person, state, "image"); onDismiss() }) { Text(tr("ছবি (PNG)", "Image (PNG)")) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } },
        modifier = Modifier
    )
}

/** Share any statement (title + lines) as text, PDF or PNG. Used by the shop page. */
@Composable
fun ShopStatementDialog(title: String, lines: List<String>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    fun share(kind: String) {
        try {
            when (kind) {
                "pdf" -> shareFile(ctx, writeStatementPdf(ctx, title, lines), "application/pdf")
                "image" -> shareFile(ctx, writeStatementImage(ctx, title, lines), "image/png")
                else -> ctx.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n")),
                        tr("খতিয়ান শেয়ার", "Share statement")
                    )
                )
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, tr("খতিয়ান তৈরি করা যায়নি", "Could not create the statement"), Toast.LENGTH_SHORT).show()
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("খতিয়ান শেয়ার", "Share statement"), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            androidx.compose.foundation.layout.Column {
                TextButton(onClick = { share("text") }) { Text(tr("টেক্সট", "Text")) }
                TextButton(onClick = { share("pdf") }) { Text("PDF") }
                TextButton(onClick = { share("image") }) { Text(tr("ছবি (PNG)", "Image (PNG)")) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}
