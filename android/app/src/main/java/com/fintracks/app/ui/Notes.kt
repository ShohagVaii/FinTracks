package com.fintracks.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
    private var loadedKey: String? = null

    private val _notes = MutableStateFlow<List<JsonObject>>(emptyList())
    val notes: StateFlow<List<JsonObject>> = _notes.asStateFlow()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private fun key(): String = "notes-" + (runCatching { repo.uid() }.getOrDefault("anon"))

    /** Clears the in-memory copy (used by the app reset). */
    fun reset() = synchronized(this) {
        cache = emptyList(); pending = emptyList(); loadedKey = null
        _notes.value = emptyList(); _pendingCount.value = 0
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
        publish()
    }

    private fun persist() {
        val root = JsonObject(mapOf("notes" to JsonArray(cache), "pending" to JsonArray(pending)))
        prefs.edit().putString(key(), root.toString()).apply()
    }

    private fun publish() {
        _notes.value = cache.sortedByDescending { timestamp(it) }
        _pendingCount.value = pending.size
    }

    /** Creates or updates a note. Returns the note id. */
    fun save(id: String?, title: String, body: String): String = synchronized(this) {
        ensureLoaded()
        val nid = id ?: UUID.randomUUID().toString()
        val now = java.time.Instant.now().toString()
        val old = cache.firstOrNull { it.id() == nid }
        val note = row(
            "id" to nid,
            "title" to title,
            "body" to body,
            "created_at" to (old?.str("created_at")?.ifEmpty { null } ?: now),
            "updated_at" to now
        )
        cache = cache.filterNot { it.id() == nid } + note
        pending = pending.filterNot { it.str("id") == nid } + row("op" to "upsert", "id" to nid, "note" to note)
        persist()
        publish()
        scope.launch { sync() }
        nid
    }

    fun delete(id: String) = synchronized(this) {
        ensureLoaded()
        cache = cache.filterNot { it.id() == id }
        pending = pending.filterNot { it.str("id") == id } + row("op" to "delete", "id" to id)
        persist()
        publish()
        scope.launch { sync() }
    }

    /** Pushes pending changes, then refreshes the cache from the server. Offline: keeps the queue, does nothing else. */
    suspend fun sync() = lock.withLock {
        synchronized(this) { ensureLoaded() }
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

/** Notepad: a list of note cards, a floating "add" pill, and a note view with copy and share. Works offline; syncs when online. */
@Composable
fun NotesScreen(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val notes by vm.notes.notes.collectAsState()
    val pendingCount by vm.notes.pendingCount.collectAsState()
    var query by remember { mutableStateOf("") }
    var route by remember { mutableStateOf<NoteRoute?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.notes.sync() }

    val open = route
    if (open != null) {
        NoteView(initial = notes.firstOrNull { it.id() == open.id }, vm = vm, startEditing = open.edit, onClose = { route = null })
        return
    }

    val shown = notes.filter {
        query.isBlank() || it.str("title").contains(query, true) || it.str("body").contains(query, true)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Single back arrow for the list: the app header is hidden on this screen.
            IconButton(modifier = Modifier.pressScale(), onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("ফিরে যান", "Back")) }
            Text(
                tr("${bnDigits(notes.size.toString())}টি নোট", "${notes.size} notes"),
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
                    NoteCard(
                        n = n,
                        onOpen = { route = NoteRoute(n.id(), false) },
                        onShare = { shareNote(ctx, n.str("title"), n.str("body")) },
                        onEdit = { route = NoteRoute(n.id(), true) },
                        onDelete = { confirmDelete = n.id() }
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { route = NoteRoute(null, true) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(tr("নোট যোগ করুন", "Add note"), fontWeight = FontWeight.Bold) },
            containerColor = Tok_FFFACC15,
            contentColor = Tok_FF1F2937,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }

    confirmDelete?.let { id ->
        ConfirmDialog(
            text = tr("এই নোটটি মুছবেন?", "Delete this note?"),
            confirmText = tr("মুছে ফেলুন", "Delete"),
            cancelText = tr("বাতিল", "Cancel"),
            onConfirm = { vm.notes.delete(id); confirmDelete = null },
            onDismiss = { confirmDelete = null }
        )
    }
}

/** One note in the list: coloured notebook icon, bold title, grey date and time, and the ⋮ menu. */
@Composable
private fun NoteCard(n: JsonObject, onOpen: () -> Unit, onShare: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AppCard(onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Tok_FFFACC15.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, contentDescription = null, tint = readableOn(Tok_FFFACC15))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    n.str("title").ifBlank { tr("(শিরোনামহীন)", "(Untitled)") },
                    fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
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
 * One note. Opens as a read view: copy and share at the top, big title, grey timestamp, full text.
 * Tapping the text (or choosing Edit in the list) switches to editing. Changes are saved when leaving.
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

    fun changed() = title != originalTitle || body != originalBody

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
            IconButton(modifier = Modifier.pressScale(), onClick = { copyText(ctx, "FinTrack note", noteText(title, body)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = tr("কপি", "Copy"))
            }
            IconButton(modifier = Modifier.pressScale(), onClick = { shareNote(ctx, title, body) }) {
                Icon(Icons.Default.Share, contentDescription = tr("শেয়ার", "Share"))
            }
        }

        if (!editing) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clickable { editing = true }.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    title.ifBlank { tr("(শিরোনামহীন)", "(Untitled)") },
                    fontSize = 26.sp, fontWeight = FontWeight.Bold
                )
                Text(initial?.let { noteStamp(it) }.orEmpty(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
