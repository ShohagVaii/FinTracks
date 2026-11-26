package com.fintracks.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.compose.ui.Alignment

/**
 * Transaction photos. A photo is first saved on the device (works offline) and listed as waiting for upload.
 * The ViewModel uploads it to the user's own folder in Storage and then writes attachment_url through the outbox.
 * Removed photos are queued for deletion from Storage the same way.
 */
object AttachmentStore {
    private const val PREFS = "fintracks"

    /**
     * A photo's key: a transaction keeps its plain id (existing files stay valid);
     * any other table is "table.id", e.g. credit_entries.<uuid>. Table names have no dots.
     */
    fun ref(table: String, id: String): String = if (table == "transactions") id else "$table.$id"
    fun refTable(ref: String): String = if (ref.contains('.')) ref.substringBefore('.') else "transactions"
    fun refId(ref: String): String = ref.substringAfter('.', ref)
    private const val KEY_PENDING = "attach_pending"
    private const val KEY_DELETE = "attach_delete"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "attachments").apply { mkdirs() }
    private fun fileOf(ctx: Context, txId: String) = File(dir(ctx), "$txId.jpg")

    private fun read(ctx: Context, key: String): Set<String> = prefs(ctx).getStringSet(key, emptySet()).orEmpty().toSet()
    private fun write(ctx: Context, key: String, values: Set<String>) {
        prefs(ctx).edit().putStringSet(key, HashSet(values)).apply()
    }

    /** Local copy of a photo (pending upload, or cached after download). */
    fun file(ctx: Context, txId: String): File? = fileOf(ctx, txId).takeIf { it.exists() }

    /** Saves the photo on the device and queues it for upload. */
    fun stage(ctx: Context, txId: String, bytes: ByteArray) {
        fileOf(ctx, txId).writeBytes(bytes)
        write(ctx, KEY_PENDING, read(ctx, KEY_PENDING) + txId)
    }

    fun pendingIds(ctx: Context): List<String> = read(ctx, KEY_PENDING).toList()
    fun unstage(ctx: Context, txId: String) = write(ctx, KEY_PENDING, read(ctx, KEY_PENDING) - txId)

    fun queueDelete(ctx: Context, path: String) = write(ctx, KEY_DELETE, read(ctx, KEY_DELETE) + path)
    fun pendingDeletes(ctx: Context): List<String> = read(ctx, KEY_DELETE).toList()
    fun doneDelete(ctx: Context, path: String) = write(ctx, KEY_DELETE, read(ctx, KEY_DELETE) - path)

    /** Removes the local copy and any waiting upload for one transaction. */
    fun purge(ctx: Context, txId: String) {
        fileOf(ctx, txId).delete()
        unstage(ctx, txId)
    }

    /** Photo for the view: the local copy when there is one, otherwise a download (cached on the device). */
    suspend fun load(ctx: Context, repo: Repository, txId: String, path: String): Bitmap? {
        file(ctx, txId)?.let { return BitmapFactory.decodeFile(it.path) }
        val bytes = repo.downloadAttachment(path)
        fileOf(ctx, txId).writeBytes(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** Reads a gallery or camera image, scales it to at most 1600 px and returns JPEG bytes. */
    fun readImage(ctx: Context, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        bmp.recycle()
        return out.toByteArray()
    }
}

/** Shows a saved transaction photo. Needs internet only when the photo is not on the device yet. */
@Composable
fun AttachmentImage(txId: String, path: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val repo = remember { Repository(ctx) }
    var bmp by remember(txId, path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(txId, path) { mutableStateOf(false) }
    LaunchedEffect(txId, path) {
        failed = false
        try {
            bmp = withContext(Dispatchers.IO) { AttachmentStore.load(ctx, repo, txId, path) }
        } catch (e: Exception) {
            failed = true
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        val shown = bmp
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = tr("লেনদেনের ছবি", "Transaction photo"),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(16.dp))
            )
        } else {
            Text(
                if (failed) tr("ছবি লোড হয়নি (ইন্টারনেট লাগবে)", "Photo could not load (internet needed)") else tr("ছবি লোড হচ্ছে…", "Loading photo…"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Small square photo for a list row (e.g. a purchase). Loads like AttachmentImage, shows nothing on failure. */
@Composable
fun AttachmentThumb(ref: String, path: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val repo = remember { Repository(ctx) }
    var bmp by remember(ref, path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(ref, path) {
        bmp = try {
            withContext(Dispatchers.IO) { AttachmentStore.load(ctx, repo, ref, path) }
        } catch (e: Exception) {
            null
        }
    }
    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = tr("কেনার ছবি", "Purchase photo"),
            contentScale = ContentScale.Crop,
            modifier = modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
        )
    }
}
