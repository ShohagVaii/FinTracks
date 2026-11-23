package com.fintracks.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.Inbox
import com.fintracks.app.data.InboxItem
import com.fintracks.app.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The owner's pushes for this user, newest first. Tapping a row marks it read and opens its target screen. */
@Composable
fun InboxScreen(repo: Repository, onOpen: (InboxItem) -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Inbox.refresh(repo) }
    val list = Inbox.items
    val unread = Inbox.unread
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (unread > 0) tr("$unread টি অপঠিত", "$unread unread") else tr("সব পড়া হয়েছে", "All read"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (unread > 0) {
                    TextButton(onClick = { scope.launch { Inbox.markRead(repo, list.map { it.id }) } }) {
                        Text(tr("সব পড়া হয়েছে", "Mark all read"))
                    }
                }
            }
        }
        if (list.isEmpty()) {
            item {
                Text(
                    tr("এখনো কোনো বার্তা নেই", "No messages yet"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        items(list, key = { it.id }) { item ->
            InboxRow(item) {
                scope.launch { Inbox.markRead(repo, listOf(item.id)) }
                onOpen(item)
            }
        }
    }
}

@Composable
private fun InboxRow(item: InboxItem, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            item.imageUrl?.let { url ->
                RemoteImage(url, Modifier.size(64.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        fontWeight = if (item.read) FontWeight.Medium else FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (!item.read) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
                Text(item.body, fontSize = 13.sp, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    inboxTime(item.sentAt),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Local time for an ISO timestamp, in the app's digit style. Falls back to the raw text. */
fun inboxTime(iso: String): String = runCatching {
    val t = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
    bnDigits(t.format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)))
}.getOrDefault(iso)

/** Small in-memory image cache. https only; a failed load shows nothing. */
private object RemoteImages {
    private val cache = LruCache<String, Bitmap>(24)

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (!url.startsWith("https://")) return null
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.inputStream.use { BitmapFactory.decodeStream(it) }?.also { cache.put(url, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Composable
fun RemoteImage(url: String, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, url) { value = RemoteImages.load(url) }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(12.dp))
        )
    }
}
