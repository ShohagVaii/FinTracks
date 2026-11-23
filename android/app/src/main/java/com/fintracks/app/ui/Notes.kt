package com.fintracks.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image as FoundationImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.Repository
import com.fintracks.app.data.id
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import com.fintracks.app.data.bool

/**
 * Notes live on the server (RLS: own rows only) and in a local cache for offline use.
 * Every change is first written to the cache and to a pending queue; sync pushes the queue, then pulls
 * the server list. A pending local change always wins over the server copy, so offline edits are never lost.
 */
class NotesStore(private val ctx: Context, private val repo: Repository, private val scope: CoroutineScope) {
    private val prefs = ctx.applicationContext.getSharedPreferences("fintrack_notes", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var cache: List<JsonObject> = emptyList()
    private var pending: List<JsonObject> = emptyList() // {op, id, note?}
    private var photoUploads: List<String> = emptyList() // storage paths of photos taken on the device, not uploaded yet
    private var photoDeletes: List<String> = emptyList() // storage paths to remove from Storage
    private var loadedKey: String? = null

    private val _notes = MutableStateFlow<List<JsonObject>>(emptyList())
    val notes: StateFlow<List<JsonObject>> = _notes.asStateFlow()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()
    private val _trashed = MutableStateFlow<List<JsonObject>>(emptyList())
    /** Notes in the Trash, newest deletion first. */
    val trashed: StateFlow<List<JsonObject>> = _trashed.asStateFlow()

    private fun key(): String = "notes-" + (runCatching { repo.uid() }.getOrDefault("anon"))

    /** Clears the in-memory copy (used by the app reset). */
    fun reset() = synchronized(this) {
        cache = emptyList(); pending = emptyList(); photoUploads = emptyList(); photoDeletes = emptyList(); loadedKey = null
        _notes.value = emptyList(); _pendingCount.value = 0; _trashed.value = emptyList()
    }

    /** Loads the cache for the signed-in user (once per user). */
    private fun ensureLoaded() {
        val k = key()
        if (loadedKey == k) return
        loadedKey = k
        val raw = prefs.getString(k, null)
        val root = runCatching { Json.parseToJsonElement(raw ?: "{}") as JsonObject }.getOrDefault(JsonObject(emptyMap()))
        cache = (root["notes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        pending = (root["pending"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        photoUploads = (root["photo_uploads"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        photoDeletes = (root["photo_deletes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        publish()
    }

    private fun persist() {
        val root = JsonObject(mapOf(
            "notes" to JsonArray(cache),
            "pending" to JsonArray(pending),
            "photo_uploads" to JsonArray(photoUploads.map { JsonPrimitive(it) }),
            "photo_deletes" to JsonArray(photoDeletes.map { JsonPrimitive(it) })
        ))
        prefs.edit().putString(key(), root.toString()).apply()
    }

    /** Pin, colour and archive. Only the given fields change. */
    fun setFlags(id: String, pinned: Boolean? = null, color: String? = null, archived: Boolean? = null) {
        synchronized(this) {
            ensureLoaded()
            val old = cache.firstOrNull { it.id() == id } ?: return
            writeNote(id, old.str("title"), old.str("body"), photosOf(id), pinned, color, archived)
        }
    }

    private fun publish() {
        _notes.value = cache.filter { it.str("deleted_at").isEmpty() }.sortedWith(compareByDescending<JsonObject> { it.bool("pinned") }.thenByDescending { timestamp(it) })
        _trashed.value = cache.filter { it.str("deleted_at").isNotEmpty() }.sortedByDescending { it.str("deleted_at") }
        _pendingCount.value = pending.size + photoUploads.size + photoDeletes.size
    }

    /** Writes a note to the cache and the pending queue (attachments are the storage paths of its photos). */
    private fun writeNote(
        nid: String, title: String, body: String, attachments: List<String>,
        pinned: Boolean? = null, color: String? = null, archived: Boolean? = null
    ): String {
        val now = java.time.Instant.now().toString()
        val old = cache.firstOrNull { it.id() == nid }
        val note = row(
            "id" to nid,
            "title" to title,
            "body" to body,
            "attachments" to JsonArray(attachments.map { JsonPrimitive(it) }),
            // Flags keep their old value unless this write changes them.
            "pinned" to (pinned ?: old?.bool("pinned") ?: false),
            "archived" to (archived ?: old?.bool("archived") ?: false),
            "color" to (color ?: old?.str("color").orEmpty()),
            "created_at" to (old?.str("created_at")?.ifEmpty { null } ?: now),
            "updated_at" to now
        )
        cache = cache.filterNot { it.id() == nid } + note
        pending = pending.filterNot { it.str("id") == nid } + row("op" to "upsert", "id" to nid, "note" to note)
        persist()
        publish()
        scope.launch { sync() }
        return nid
    }

    private fun photosOf(nid: String): List<String> =
        (cache.firstOrNull { it.id() == nid }?.get("attachments") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }

    /** Creates or updates a note. Returns the note id. */
    fun save(id: String?, title: String, body: String): String = synchronized(this) {
        ensureLoaded()
        writeNote(id ?: UUID.randomUUID().toString(), title, body, photosOf(id ?: ""))
    }

    /** Stores a photo on the device, adds it to the note and queues the upload. */
    fun addPhoto(noteId: String, bytes: ByteArray) {
        synchronized(this) {
            ensureLoaded()
            val note = cache.firstOrNull { it.id() == noteId } ?: return
            val uid = runCatching { repo.uid() }.getOrNull() ?: return
            val path = "$uid/notes/$noteId/${UUID.randomUUID()}.jpg"
            notePhotoFile(ctx, path).writeBytes(bytes)
            photoUploads = photoUploads + path
            writeNote(noteId, note.str("title"), note.str("body"), photosOf(noteId) + path)
        }
    }

    /** Removes a photo from the note. A photo not yet uploaded is dropped; otherwise it is deleted from Storage on sync. */
    fun removePhoto(noteId: String, path: String) {
        synchronized(this) {
            ensureLoaded()
            val note = cache.firstOrNull { it.id() == noteId } ?: return
            if (path in photoUploads) {
                photoUploads = photoUploads - path
                notePhotoFile(ctx, path).delete()
            } else {
                photoDeletes = photoDeletes + path
            }
            writeNote(noteId, note.str("title"), note.str("body"), photosOf(noteId) - path)
        }
    }

    /** Moves a note to the Trash (kept 30 days). Photos stay until the note is removed for good. */
    fun delete(id: String) {
        setTrash(id, java.time.OffsetDateTime.now().toString())
    }

    /** Brings back every note that went to the Trash at [stamp]. */
    fun restoreStamp(stamp: String) {
        cache.filter { it.str("deleted_at") == stamp }.forEach { setTrash(it.id(), null) }
    }

    private fun setTrash(id: String, stamp: String?) {
        synchronized(this) {
            ensureLoaded()
            val old = cache.firstOrNull { it.id() == id } ?: return@synchronized
            val note = JsonObject(old + ("deleted_at" to JsonPrimitive(stamp)))
            cache = cache.filterNot { it.id() == id } + note
            pending = pending.filterNot { it.str("id") == id } + row("op" to "upsert", "id" to id, "note" to note)
            persist()
            publish()
            scope.launch { sync() }
        }
    }

    /** Removes a note for good, with its photos. */
    fun purge(id: String) = synchronized(this) {
        ensureLoaded()
        for (path in photosOf(id)) {
            if (path in photoUploads) { photoUploads = photoUploads - path; notePhotoFile(ctx, path).delete() }
            else photoDeletes = photoDeletes + path
        }
        cache = cache.filterNot { it.id() == id }
        pending = pending.filterNot { it.str("id") == id } + row("op" to "delete", "id" to id)
        persist()
        publish()
        scope.launch { sync() }
    }

    /**
     * Pushes pending changes, then refreshes the cache from the server. Offline: keeps the queue, does nothing else.
     * Order: photo uploads first (so a note never points at a missing photo), then note rows, then Storage deletes.
     */
    suspend fun sync() = lock.withLock {
        synchronized(this) { ensureLoaded() }
        val uploads = synchronized(this) { photoUploads }
        for (path in uploads) {
            val bytes = runCatching { notePhotoFile(ctx, path).readBytes() }.getOrNull()
            if (bytes == null) { // local copy is gone: nothing left to upload
                synchronized(this) { photoUploads = photoUploads - path; persist(); publish() }
                continue
            }
            if (runCatching { repo.uploadNotePhoto(path, bytes) }.isFailure) {
                synchronized(this) { publish() }
                return@withLock
            }
            synchronized(this) { photoUploads = photoUploads - path; persist(); publish() }
        }
        val queue = synchronized(this) { pending }
        val done = HashSet<String>()
        for (op in queue) {
            val ok = runCatching {
                if (op.str("op") == "upsert") {
                    repo.upsert("notes", listOf(op["note"] as JsonObject), "id")
                } else {
                    repo.delete("notes", op.str("id"))
                }
            }.isSuccess
            if (!ok) break
            done += op.str("id") + "|" + op.str("op")
        }
        synchronized(this) {
            pending = pending.filterNot { (it.str("id") + "|" + it.str("op")) in done }
            persist()
        }
        // Storage deletes run after the note rows no longer reference the photos.
        for (path in synchronized(this) { photoDeletes }) {
            if (runCatching { repo.deleteAttachment(path) }.isFailure) continue
            synchronized(this) {
                photoDeletes = photoDeletes - path
                notePhotoFile(ctx, path).delete()
                persist()
                publish()
            }
        }
        val server = runCatching { repo.list("notes", "select=*&order=updated_at.desc") }.getOrNull() ?: run {
            synchronized(this) { publish() }
            return@withLock
        }
        synchronized(this) {
            val pendingIds = pending.map { it.str("id") }.toSet()
            val localPending = cache.filter { it.id() in pendingIds }
            cache = server.filter { it.id() !in pendingIds } + localPending
            persist()
            publish()
        }
    }

    companion object {
        fun timestamp(note: JsonObject): Long =
            runCatching { OffsetDateTime.parse(note.str("updated_at")).toInstant().toEpochMilli() }.getOrDefault(0L)
    }
}

/** Local copy of a note photo (cache folder per storage path, so each photo has one file). */
fun notePhotoFile(ctx: Context, path: String): File =
    File(File(ctx.applicationContext.filesDir, "note-photos").apply { mkdirs() }, path.substringAfterLast('/'))

/** Photo storage paths of a note, in the order they were added. */
fun JsonObject.photoPaths(): List<String> =
    (this["attachments"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

/** Timestamp shown on cards and in the note: date, time with seconds, AM/PM (e.g. 2026-11-21 02:47:10 AM). */
private val NOTE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss a", Locale.ENGLISH)

private fun noteStamp(note: JsonObject): String = runCatching {
    OffsetDateTime.parse(note.str("updated_at")).atZoneSameInstant(ZoneId.systemDefault()).format(NOTE_STAMP)
}.getOrDefault("")

private fun noteText(title: String, body: String): String =
    listOf(title, body).filter { it.isNotBlank() }.joinToString("\n\n")

private fun shareNote(ctx: Context, title: String, body: String) {
    val text = noteText(title, body)
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, tr("নোট শেয়ার করুন", "Share note")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Which note is open: id (null = new note) and whether it starts in edit mode. */
private class NoteRoute(val id: String?, val edit: Boolean)

/** Sub-page text on the back stack: "" list, "new" editor, "<id>" note, "<id>/e" note editor. */
private fun parseNoteRoute(sub: String): NoteRoute? = when {
    sub.isEmpty() -> null
    sub == "new" -> NoteRoute(null, true)
    sub.endsWith("/e") -> NoteRoute(sub.removeSuffix("/e"), true)
    else -> NoteRoute(sub, false)
}

private fun encodeNoteRoute(r: NoteRoute): String {
    val id = r.id ?: return "new"
    return if (r.edit) "$id/e" else id
}

/** Notepad: a list of note cards, a floating "add" pill, and a note view with copy and share. Works offline; syncs when online. */
@Composable
fun NotesScreen(vm: AppViewModel, subPage: String, go: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val notes by vm.notes.notes.collectAsState()
    val pendingCount by vm.notes.pendingCount.collectAsState()
    var query by remember { mutableStateOf("") }
    // The open note (or the editor) is an entry on the app's back stack, so it comes from the route.
    val route = parseNoteRoute(subPage)
    val setRoute: (NoteRoute?) -> Unit = { r -> if (r == null) onClose() else go("ov:NOTES/" + encodeNoteRoute(r)) }
    var archiveView by remember { mutableStateOf(false) }
    var colorFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var removingNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.notes.sync() }

    val open = route
    if (open != null) {
        key(subPage) {
            NoteView(initial = notes.firstOrNull { it.id() == open.id }, vm = vm, startEditing = open.edit, onClose = { setRoute(null) })
        }
        return
    }

    val shown = notes.filter {
        it.bool("archived") == archiveView &&
            (query.isBlank() || it.str("title").contains(query, true) || it.str("body").contains(query, true))
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Single back arrow for the list: the app header is hidden on this screen.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(modifier = Modifier.pressScale(), onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("ফিরে যান", "Back")) }
                Spacer(Modifier.weight(1f))
                IconButton(modifier = Modifier.pressScale(), onClick = { archiveView = !archiveView }) {
                    Icon(
                        if (archiveView) Icons.Default.Unarchive else Icons.Default.Archive,
                        contentDescription = tr("আর্কাইভ", "Archive")
                    )
                }
            }
            Text(
                if (archiveView) tr("আর্কাইভ করা নোট", "Archived notes")
                else tr("${bnDigits(notes.size.toString())}টি নোট", "${notes.size} notes"),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (pendingCount > 0) {
                Text(
                    tr("অফলাইন: ", "Offline: ") + bnDigits(pendingCount.toString()) + tr("টি পরিবর্তন সিঙ্ক বাকি। সংযোগ পেলে আপনাআপনি যাবে।", " changes waiting to sync. They sync automatically when online."),
                    fontSize = 12.sp,
                    color = readableOn(Tok_FFFACC15)
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(tr("নোট খুঁজুন", "Search notes")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (shown.isEmpty()) EmptyHint(if (notes.isEmpty()) tr("এখনো কোনো নোট নেই", "No notes yet") else tr("কিছু পাওয়া যায়নি", "Nothing found"))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
                // Bottom room so the last card is not hidden behind the add pill.
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(shown, key = { it.id() }) { n ->
                    AnimatedVisibility(visible = removingNote != n.id(), exit = fadeOut(tween(250)) + slideOutHorizontally(tween(250)) { -it / 3 }) {
                    NoteCard(
                        n = n,
                        onOpen = { setRoute(NoteRoute(n.id(), false)) },
                        onShare = { shareNote(ctx, n.str("title"), n.str("body")) },
                        onEdit = { setRoute(NoteRoute(n.id(), true)) },
                        onDelete = { confirmDelete = n.id() },
                        onPin = { vm.notes.setFlags(n.id(), pinned = !n.bool("pinned")) },
                        onColor = { colorFor = n.id() },
                        onArchive = { vm.notes.setFlags(n.id(), archived = !n.bool("archived")) }
                    )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { setRoute(NoteRoute(null, true)) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(tr("নোট যোগ করুন", "Add note"), fontWeight = FontWeight.Bold) },
            containerColor = Tok_FFFACC15,
            contentColor = Tok_FF1F2937,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).pressScale()
        )
    }

    colorFor?.let { id ->
        val current = notes.firstOrNull { it.id() == id }?.str("color").orEmpty()
        AlertDialog(
            onDismissRequest = { colorFor = null },
            title = { Text(tr("নোটের রং", "Note colour"), fontWeight = FontWeight.Bold) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NOTE_COLORS.forEach { (key, c) ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(c)
                                .border(if (key == current) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { vm.notes.setFlags(id, color = key); colorFor = null }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { colorFor = null }) { Text(tr("বন্ধ", "Close")) } }
        )
    }

    confirmDelete?.let { id ->
        ConfirmDialog(
            text = tr("এই নোটটি মুছবেন?", "Delete this note?"),
            confirmText = tr("মুছে ফেলুন", "Delete"),
            cancelText = tr("বাতিল", "Cancel"),
            onConfirm = {
                // Fade the card out first, then delete it.
                confirmDelete = null
                removingNote = id
                scope.launch {
                    kotlinx.coroutines.delay(if (motionReduced(ctx)) 0L else 260L)
                    vm.notes.delete(id)
                    removingNote = null
                }
            },
            onDismiss = { confirmDelete = null }
        )
    }
}

private val NOTE_COLORS: List<Pair<String, Color>> = listOf(
    "" to Tok_FFFACC15,
    "green" to Emerald500,
    "blue" to Color(0xFF38BDF8),
    "purple" to Color(0xFFA78BFA),
    "red" to Rose500
)

private fun noteTint(key: String): Color = NOTE_COLORS.firstOrNull { it.first == key }?.second ?: Tok_FFFACC15

/** One note in the list: coloured notebook icon, bold title, grey date and time, and the ⋮ menu. */
@Composable
private fun NoteCard(
    n: JsonObject, onOpen: () -> Unit, onShare: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onPin: () -> Unit, onColor: () -> Unit, onArchive: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val tint = noteTint(n.str("color"))
    AppCard(onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, contentDescription = null, tint = readableOn(tint))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (n.bool("pinned")) {
                        Icon(Icons.Default.PushPin, contentDescription = tr("পিন করা", "Pinned"), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        n.str("title").ifBlank { tr("(শিরোনামহীন)", "(Untitled)") },
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Text(noteStamp(n), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(modifier = Modifier.pressScale(), onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = tr("মেনু", "Menu"))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("শেয়ার করুন", "Share")) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { menu = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("এডিট করুন", "Edit")) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (n.bool("pinned")) tr("পিন খুলুন", "Unpin") else tr("পিন করুন", "Pin")) },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = { menu = false; onPin() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("রং বদলান", "Change colour")) },
                        leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                        onClick = { menu = false; onColor() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (n.bool("archived")) tr("আর্কাইভ থেকে ফেরত", "Restore") else tr("আর্কাইভ করুন", "Archive")) },
                        leadingIcon = { Icon(if (n.bool("archived")) Icons.Default.Unarchive else Icons.Default.Archive, contentDescription = null) },
                        onClick = { menu = false; onArchive() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("মুছে ফেলুন", "Delete"), color = readableOn(Rose500)) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Rose500) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

/**
 * One note. Opens as a read view: copy, share and edit at the top, big title, grey timestamp, photos, full text.
 * Editing starts only from the Edit button (or Edit in the list), never from tapping the text.
 * Changes are saved when leaving. Photos can be added (camera or gallery) and removed while editing.
 */
@Composable
private fun NoteView(initial: JsonObject?, vm: AppViewModel, startEditing: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val originalTitle = initial?.str("title").orEmpty()
    val originalBody = initial?.str("body").orEmpty()
    var id by remember { mutableStateOf(initial?.id()) }
    var title by remember { mutableStateOf(originalTitle) }
    var body by remember { mutableStateOf(originalBody) }
    var editing by remember { mutableStateOf(startEditing || initial == null) }
    var handled by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    // Photos come from the live list, so a photo appears as soon as it is added or removed.
    val live by vm.notes.notes.collectAsState()
    val photos = live.firstOrNull { it.id() == id }?.photoPaths().orEmpty()

    fun changed() = title != originalTitle || body != originalBody

    fun addPhotoBytes(bytes: ByteArray?) {
        if (bytes == null) return
        // A photo needs a saved note: a new note is created here with whatever has been typed so far.
        val nid = id ?: vm.notes.save(null, title, body).also { id = it }
        vm.notes.addPhoto(nid, bytes)
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addPhotoBytes(AttachmentStore.readImage(ctx, uri))
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) addPhotoBytes(AttachmentStore.readImage(ctx, uri))
    }
    fun launchCamera() {
        val file = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "note-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        cameraUri = uri
        cameraPicker.launch(uri)
    }

    // Leaving the note by any route (back, arrow, save) keeps the text, unless save or delete already did it.
    DisposableEffect(Unit) {
        onDispose {
            if (!handled && changed() && (id != null || title.isNotBlank() || body.isNotBlank())) {
                vm.notes.save(id, title, body)
            }
        }
    }
    BackHandler {
        if (editing && initial != null) editing = false else onClose()
    }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(modifier = Modifier.pressScale(), onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("ফিরে যান", "Back")) }
            Spacer(Modifier.weight(1f))
            if (!editing) {
                IconButton(modifier = Modifier.pressScale(), onClick = { editing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = tr("এডিট করুন", "Edit"))
                }
            }
            IconButton(modifier = Modifier.pressScale(), onClick = { copyText(ctx, "FinTrack note", noteText(title, body)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = tr("কপি", "Copy"))
            }
            IconButton(modifier = Modifier.pressScale(), onClick = { shareNote(ctx, title, body) }) {
                Icon(Icons.Default.Share, contentDescription = tr("শেয়ার", "Share"))
            }
        }

        if (!editing) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    title.ifBlank { tr("(শিরোনামহীন)", "(Untitled)") },
                    fontSize = 26.sp, fontWeight = FontWeight.Bold
                )
                Text(initial?.let { noteStamp(it) }.orEmpty(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                photos.forEach { NotePhoto(path = it, onRemove = null) }
                Spacer(Modifier.height(8.dp))
                Text(
                    body.ifBlank { tr("লেখা নেই", "No text") },
                    fontSize = 16.sp, lineHeight = 24.sp,
                    color = if (body.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(tr("শিরোনাম", "Title")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(tr("লেখা", "Text")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp)
                )
                photos.forEach { path ->
                    NotePhoto(path = path, onRemove = { id?.let { vm.notes.removePhoto(it, path) } })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f).pressScale(), onClick = { launchCamera() }) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("ছবি তুলুন", "Camera"))
                    }
                    OutlinedButton(modifier = Modifier.weight(1f).pressScale(), onClick = {
                        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("গ্যালারি", "Gallery"))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (id != null) {
                        TextButton(modifier = Modifier.pressScale(), onClick = {
                            handled = true
                            vm.notes.delete(id!!)
                            onClose()
                        }) { Text(tr("মুছে ফেলুন", "Delete"), color = readableOn(Rose500)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(modifier = Modifier.pressScale(), 
                        onClick = {
                            // A new note with nothing typed is not saved. Existing notes are saved only when changed.
                            if (id != null || title.isNotBlank() || body.isNotBlank()) {
                                if (changed() || id == null) id = vm.notes.save(id, title, body)
                            }
                            handled = true
                            onClose()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Tok_FFFACC15, contentColor = Tok_FF1F2937)
                    ) { Text(tr("সংরক্ষণ", "Save"), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/** One note photo. Loads from the device copy, or downloads it once and keeps it on the device. */
@Composable
private fun NotePhoto(path: String, onRemove: (() -> Unit)?) {
    val ctx = LocalContext.current
    val repo = remember { Repository(ctx) }
    var bmp by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        failed = false
        try {
            bmp = withContext(Dispatchers.IO) {
                val f = notePhotoFile(ctx, path)
                if (!f.exists()) f.writeBytes(repo.downloadAttachment(path))
                BitmapFactory.decodeFile(f.path)
            }
        } catch (e: Exception) {
            failed = true
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val shown = bmp
        if (shown != null) {
            FoundationImage(
                bitmap = shown.asImageBitmap(),
                contentDescription = tr("নোটের ছবি", "Note photo"),
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
        if (onRemove != null) {
            IconButton(modifier = Modifier.align(Alignment.TopEnd), onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = tr("ছবি সরান", "Remove photo"))
            }
        }
    }
}
