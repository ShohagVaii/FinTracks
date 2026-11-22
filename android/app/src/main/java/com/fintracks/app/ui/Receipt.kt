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

/** Receipt view for one transaction (opened by a normal tap): the voucher, then share as image or PDF. */
@Composable
fun ReceiptDialog(t: JsonObject, state: UiState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(t, state.name) {
        preview = withContext(Dispatchers.IO) { Voucher.renderSingle(ctx, state, t) }
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
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = tr("বন্ধ করুন", "Close")) }
                }
                if (t.str("attachment_url").isNotEmpty()) AttachmentImage(t.id(), t.str("attachment_url"))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val bmp = preview
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = tr("ভাউচার", "Voucher"),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("লেনদেন", "Transaction"), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(t.str("category").ifEmpty { tr("লেনদেন", "Transaction") }.let { catLabel(it) }, fontSize = 14.sp)
                TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(tr("এডিট করুন", "Edit")) }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("মুছে ফেলুন", "Delete"), color = Rose500)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}
