package com.fintracks.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import kotlinx.coroutines.delay
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Verify page on the Pages site. It pre-fills TRX and checksum from ?trx=&chk=. */
const val VERIFY_URL = "https://shohagvaii.github.io/FinTracks/verify.html"

/**
 * Receipt for one transaction (opened by a normal tap). It is a native text layout: the amount, the details,
 * the TRX ID and checksum (each with a copy button), then the actions. The image and PDF are rendered only when
 * the user shares, so opening the receipt is fast.
 */
@Composable
fun ReceiptDialog(t: JsonObject, state: UiState, onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    // TRX and checksum come from the saved record (created on first sight, then kept).
    val rec = remember(t, state.profile) { Voucher.storedFor(ctx, state.profile, t) }

    val type = t.str("type")
    val amt = t.num("amount")
    val income = type == "Income"
    val expense = type == "Expense"
    val tone = when { income -> ChartIncome; expense -> ChartExpense; else -> Sky400 }
    val valueTone = readableOn(tone)
    val title = when {
        income -> tr("মোট আয়", "Total income")
        expense -> tr("মোট ব্যয়", "Total expense")
        else -> tr("ট্রান্সফার", "Transfer")
    }
    val sign = when { income -> "+ "; expense -> "- "; else -> "" }
    val time = txTimeLabel(t)
    val dateLine = displayDate(dateOf(t, "tx_date")) + if (time.isEmpty()) "" else " · $time"

    fun copy(label: String, value: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(ctx, tr("কপি হয়েছে", "Copied"), Toast.LENGTH_SHORT).show()
    }

    fun openVerify() {
        val url = VERIFY_URL + "?trx=" + Uri.encode(rec.trx) + "&chk=" + Uri.encode(rec.checksum)
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, tr("ব্রাউজার খুলতে পারেনি", "Could not open a browser"), Toast.LENGTH_LONG).show()
        }
    }

    // Shares are rendered again so the issue time is the moment of sharing.
    fun share(asPdf: Boolean) {
        busy = true
        scope.launch {
            try {
                if (asPdf) {
                    val file = withContext(Dispatchers.IO) { Voucher.singlePdf(ctx, state, t) }
                    Export.share(ctx, file, "application/pdf", tr("FinTrack লেনদেন ভাউচার", "FinTrack transaction voucher"))
                } else {
                    val file = withContext(Dispatchers.IO) {
                        Voucher.pngFile(ctx, Voucher.renderSingle(ctx, state, t))
                    }
                    Export.share(ctx, file, "image/png", tr("FinTrack লেনদেন ভাউচার", "FinTrack transaction voucher"))
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, tr("শেয়ার ব্যর্থ: ${e.message}", "Share failed: ${e.message}"), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          // Deleting fades and slides the receipt out first, then removes the row.
          AnimatedVisibility(visible = !gone, modifier = Modifier.fillMaxSize(), exit = fadeOut(tween(250)) + slideOutHorizontally(tween(250)) { -it / 3 }) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("লেনদেনের রসিদ", "Transaction receipt"), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    IconButton(modifier = Modifier.pressScale(), onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = tr("বন্ধ করুন", "Close"))
                    }
                }

                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Hero: the amount, green for income and red for expense. The VERIFIED stamp sits beside it.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(tone.copy(alpha = 0.14f))
                            .border(1.dp, tone.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, color = valueTone, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "$sign${taka(amt)}",
                                color = valueTone,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(tr("যাচাইকৃত ও FinTrack-এ সংরক্ষিত", "Verified and saved in FinTrack"), color = valueTone, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        VerifiedStamp()
                    }

                    AppCard {
                        Text(tr("লেনদেনের বিবরণ", "Details"), fontWeight = FontWeight.Bold)
                        ReceiptRow(tr("তারিখ ও সময়", "Date & time"), dateLine)
                        ReceiptRow(tr("ক্যাটাগরি", "Category"), catLabel(t.str("category")).ifEmpty { "-" })
                        ReceiptRow(tr("পেমেন্ট মাধ্যম", "Payment method"), t.str("payment_mode").ifEmpty { "-" })
                        ReceiptRow(tr("অ্যাকাউন্ট", "Account"), Export.accountName(state, t).ifEmpty { "-" })
                        ReceiptRow(tr("মুড", "Mood"), t.str("mood").ifEmpty { "-" })
                        ReceiptRow(tr("নোট", "Note"), t.str("note").ifEmpty { "-" })
                        ReceiptRow(tr("প্রোফাইল", "Profile"), rec.profile.ifEmpty { "-" })
                    }

                    AppCard {
                        Text(tr("যাচাই তথ্য", "Verification"), fontWeight = FontWeight.Bold)
                        ReceiptRow(tr("TRX ID", "TRX ID"), rec.trx, onCopy = { copy("TRX ID", rec.trx) })
                        ReceiptRow(tr("চেকসাম", "Checksum"), rec.checksum, onCopy = { copy("Checksum", rec.checksum) })
                        Text(
                            tr("ওয়েবসাইটে যাচাই করতে এই দুটো কপি করে পেস্ট করুন, অথবা নিচের বোতাম ব্যবহার করুন।", "To verify on the website, copy these two and paste them, or use the button below."),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (t.str("attachment_url").isNotEmpty()) AttachmentImage(t.id(), t.str("attachment_url"))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f).pressScale(),
                        onClick = { share(false) },
                        enabled = !busy
                    ) { Text(tr("ছবি শেয়ার", "Share image")) }
                    OutlinedButton(
                        modifier = Modifier.weight(1f).pressScale(),
                        onClick = { share(true) },
                        enabled = !busy
                    ) { Text(tr("PDF শেয়ার", "Share PDF")) }
                }
                Button(modifier = Modifier.fillMaxWidth().pressScale(), onClick = { openVerify() }) {
                    Text(tr("ওয়েবে যাচাই করুন", "Verify on the web"))
                }
            }
          }
        }
    }

}

/** One label/value line of the receipt. The optional copy button sits at the end of the line. */
@Composable
private fun ReceiptRow(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(0.58f))
        if (onCopy != null) {
            IconButton(modifier = Modifier.pressScale(), onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = tr("কপি করুন", "Copy"), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Time of the transaction (from its created_at), like "১০:৩০ AM". Empty when the time is not known. */
private fun txTimeLabel(t: JsonObject): String = runCatching {
    val local = java.time.OffsetDateTime.parse(t.str("created_at"))
        .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime()
    val h = if (local.hour % 12 == 0) 12 else local.hour % 12
    Export.bn("$h:${local.minute.toString().padStart(2, '0')}") + " " + if (local.hour < 12) "AM" else "PM"
}.getOrDefault("")

/** Long-press menu on a transaction row: the only place where edit and delete are offered. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxActionDialog(t: JsonObject, onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var gone by remember { mutableStateOf(false) }
    val tone = if (t.str("type") == "Income") incomeTone() else expenseTone()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Summary: icon, name and note, amount in the transaction's colour.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(tone.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Text(catIcon(t.str("category")), fontSize = 20.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        catLabel(t.str("category")).ifEmpty { tr("লেনদেন", "Transaction") },
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (t.str("note").isNotEmpty()) {
                        Text(t.str("note"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(taka(t.num("amount")), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = tone)
            }
            Button(
                modifier = Modifier.fillMaxWidth().pressScale(),
                onClick = onEdit,
                colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Color.White)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("এডিট করুন", "Edit"))
            }
            Button(
                modifier = Modifier.fillMaxWidth().pressScale(),
                enabled = !gone,
                onClick = {
                    // The sheet fades out, then the row is deleted (Reduce Motion: at once).
                    if (!gone) {
                        gone = true
                        scope.launch {
                            kotlinx.coroutines.delay(if (motionReduced(ctx)) 0L else 200L)
                            onDelete()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Rose500, contentColor = Color.White)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("মুছে ফেলুন", "Delete"))
            }
            TextButton(modifier = Modifier.fillMaxWidth().pressScale(), onClick = onDismiss) {
                Text(tr("বাতিল", "Cancel"))
            }
        }
    }
}

/**
 * VERIFIED stamp drawn by Compose on the text receipt. It slams down from 1.6x while turning into place (380 ms).
 * Reduce Motion: shown at once. It takes no touches, so it never blocks the receipt.
 * Shared and saved vouchers keep the stamp baked into their bitmap (see Voucher.stamp).
 */
@Composable
fun VerifiedStamp(modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) p.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    val red = Color(0xFFC8102E)
    Box(
        modifier
            .size(88.dp)
            .graphicsLayer {
                val t = p.value
                val sc = 1.6f - 0.6f * t
                scaleX = sc
                scaleY = sc
                rotationZ = -30f + 18f * t
                alpha = t
            }
            .drawBehind {
                val w = size.width
                val k = w / 100f // stamp units: the same proportions as the baked voucher stamp
                val c = Offset(w / 2f, w / 2f)
                val outer = w / 2f - 2.dp.toPx()
                drawCircle(red, radius = outer, center = c, style = Stroke(3.dp.toPx()))
                drawCircle(red, radius = outer - 6.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
                // Tick drawn as a path (the Bengali font may not include the glyph).
                val tick = Path().apply {
                    moveTo(c.x - 11f * k, c.y - 14f * k)
                    lineTo(c.x - 3f * k, c.y - 6f * k)
                    lineTo(c.x + 11f * k, c.y - 24f * k)
                }
                drawPath(tick, red, style = Stroke(3f * k, cap = StrokeCap.Round))
                val n = drawContext.canvas.nativeCanvas
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = red.toArgb()
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 14f * k
                }
                n.drawText("VERIFIED", c.x, c.y + 8f * k, paint)
                paint.textSize = 9f * k
                n.drawText("FINTRACK", c.x, c.y + 22f * k, paint)
            }
    )
}
