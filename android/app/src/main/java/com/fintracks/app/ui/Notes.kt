package com.fintracks.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

private val NOTE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a")

private fun noteTime(note: JsonObject): String = runCatching {
    OffsetDateTime.parse(note.str("updated_at")).atZoneSameInstant(ZoneId.systemDefault()).format(NOTE_TIME)
}.map { bnDigits(it) }.getOrDefault("")

/** Notepad: list with search, full-screen editor, share. Works offline; syncs when online. */
@Composable
fun NotesScreen(vm: AppViewModel) {
    val notes by vm.notes.notes.collectAsState()
    val pendingCount by vm.notes.pendingCount.collectAsState()
    var query by remember { mutableStateOf("") }
    var editorOpen by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.notes.sync() }

    if (editorOpen) {
        NoteEditor(
            initial = notes.firstOrNull { it.id() == openId },
            vm = vm,
            onClose = { editorOpen = false }
        )
        return
    }

    val shown = notes.filter {
        query.isBlank() || it.str("title").contains(query, true) || it.str("body").contains(query, true)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(bnDigits(notes.size.toString()) + tr("টি নোট", " notes"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Button(
                onClick = { openId = null; editorOpen = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFACC15), contentColor = Color(0xFF1F2937))
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(tr("নতুন নোট", "New note"), fontWeight = FontWeight.Bold)
            }
        }
        if (pendingCount > 0) {
            Text(
                tr("অফলাইন: ", "Offline: ") + bnDigits(pendingCount.toString()) + tr("টি পরিবর্তন সিঙ্ক বাকি। সংযোগ পেলে আপনাআপনি যাবে।", " changes waiting to sync. They sync automatically when online."),
                fontSize = 12.sp,
                color = Color(0xFFFACC15)
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
        shown.forEach { n ->
            AppCard(onClick = { openId = n.id(); editorOpen = true }) {
                Text(n.str("title").ifBlank { "(শিরোনামহীন)" }, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(
                    n.str("body").replace("\n", " ").take(90).ifBlank { "লেখা নেই" },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Text(noteTime(n), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NoteEditor(initial: JsonObject?, vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val originalTitle = initial?.str("title").orEmpty()
    val originalBody = initial?.str("body").orEmpty()
    var id by remember { mutableStateOf(initial?.id()) }
    var title by remember { mutableStateOf(originalTitle) }
    var body by remember { mutableStateOf(originalBody) }
    var handled by remember { mutableStateOf(false) }

    fun changed() = title != originalTitle || body != originalBody

    // Leaving the editor by any route (back, header arrow, save) keeps the text, unless save already did it.
    DisposableEffect(Unit) {
        onDispose {
            if (!handled && changed() && (id != null || title.isNotBlank() || body.isNotBlank())) {
                vm.notes.save(id, title, body)
            }
        }
    }
    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("ফিরে যান", "Back")) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                val text = listOf(title, body).filter { it.isNotBlank() }.joinToString("\n\n")
                if (text.isNotBlank()) {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                    ctx.startActivity(Intent.createChooser(send, tr("নোট শেয়ার করুন", "Share note")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Icon(Icons.Default.Share, contentDescription = tr("শেয়ার", "Share")) }
            if (id != null) {
                DeleteButton {
                    handled = true
                    vm.notes.delete(id!!)
                    onClose()
                }
            }
            Button(onClick = {
                // A new note with nothing typed is not saved. Existing notes are saved only when changed.
                if (id != null || title.isNotBlank() || body.isNotBlank()) {
                    if (changed() || id == null) vm.notes.save(id, title, body)
                }
                handled = true
                onClose()
            }) { Text(tr("সংরক্ষণ", "Save")) }
        }
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
            modifier = Modifier.fillMaxWidth().height(420.dp)
        )
    }
}
