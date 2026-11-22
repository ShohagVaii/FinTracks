package com.fintracks.app.ui

import com.fintracks.app.data.id
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fintracks.app.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/** Receipt view for one transaction (opened by a normal tap): the voucher, then share as image or PDF. */
@Composable
fun ReceiptDialog(t: JsonObject, state: UiState, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(t, state.name) {
        preview = withContext(Dispatchers.IO) { Voucher.renderSingle(ctx, state, t, withStamp = false) }
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
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("লেনদেনের ভাউচার", "Transaction voucher"), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(modifier = Modifier.pressScale(), onClick = onEdit) { Text(tr("এডিট", "Edit")) }
                    IconButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = tr("বন্ধ করুন", "Close")) }
                }
                if (t.str("attachment_url").isNotEmpty()) AttachmentImage(t.id(), t.str("attachment_url"))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val bmp = preview
                    if (bmp != null) {
                        // The preview has no stamp baked in: the voucher fades in, then the VERIFIED stamp slams on top.
                        Box(Modifier.fillMaxSize().popIn(), contentAlignment = Alignment.Center) {
                            Box(Modifier.aspectRatio(Voucher.PAGE_W / Voucher.PAGE_H)) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = tr("ভাউচার", "Voucher"),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                                VerifiedStampLayer(Modifier.fillMaxSize())
                            }
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { share(false) },
                        enabled = preview != null && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(tr("ছবি শেয়ার", "Share image")) }
                    OutlinedButton(
                        onClick = { share(true) },
                        enabled = preview != null && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(tr("PDF শেয়ার", "Share PDF")) }
                }
            }
        }
    }
}

/** Long-press menu on a transaction row: the only place where edit and delete are offered. */
@Composable
fun TxActionDialog(t: JsonObject, onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var gone by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("লেনদেন", "Transaction"), fontWeight = FontWeight.Bold) },
        text = {
            AnimatedVisibility(visible = !gone, exit = fadeOut(tween(250)) + slideOutHorizontally(tween(250)) { -it / 3 }) {
            Column {
                Text(t.str("category").ifEmpty { tr("লেনদেন", "Transaction") }.let { catLabel(it) }, fontSize = 14.sp)
                TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(tr("এডিট করুন", "Edit")) }
                TextButton(onClick = {
                    // Slide and fade the dialog content out, then delete.
                    if (!gone) {
                        gone = true
                        scope.launch {
                            kotlinx.coroutines.delay(if (motionReduced(ctx)) 0L else 260L)
                            onDelete()
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("মুছে ফেলুন", "Delete"), color = readableOn(Rose500))
                }
            }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}

/**
 * On-screen VERIFIED stamp for the receipt preview. It slams down from 1.6x while tilting into place (about 420 ms).
 * It is drawn in voucher page units, so it sits where the stamp of the saved voucher sits. Reduce Motion: shown at once.
 * Saved and shared vouchers do not use this layer; they keep the stamp inside the bitmap.
 */
@Composable
fun VerifiedStampLayer(modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) p.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    androidx.compose.foundation.Canvas(modifier) {
        val t = p.value
        if (t <= 0f) return@Canvas
        val k = size.width / Voucher.PAGE_W
        drawIntoCanvas { canvas ->
            val n = canvas.nativeCanvas
            val red = android.graphics.Color.parseColor("#C8102E")
            val a = (255f * t).toInt().coerceIn(0, 255)
            val sc = 1.6f - 0.6f * t
            n.save()
            n.translate(482f * k, 253f * k)
            n.rotate(-30f + 18f * t)
            n.scale(sc, sc)
            val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f * k
                color = red
                alpha = a
            }
            n.drawCircle(0f, 0f, 50f * k, ring)
            ring.strokeWidth = 1f * k
            n.drawCircle(0f, 0f, 43f * k, ring)
            val tick = android.graphics.Paint(ring).apply {
                strokeWidth = 3f * k
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            n.drawPath(android.graphics.Path().apply {
                moveTo(-11f * k, -14f * k); lineTo(-3f * k, -6f * k); lineTo(11f * k, -24f * k)
            }, tick)
            val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = red
                alpha = a
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = 14f * k
            }
            n.drawText("VERIFIED", 0f, 8f * k, txt)
            txt.textSize = 9f * k
            n.drawText("FINTRACK", 0f, 22f * k, txt)
            n.restore()
        }
    }
}
