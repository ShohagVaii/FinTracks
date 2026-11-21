package com.fintracks.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.Repository
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Note and coin denominations, largest first, as the counter shows them. */
val DENOMINATIONS: List<Int> = listOf(1000, 500, 200, 100, 50, 20, 10, 5, 2, 1)

/** One saved count. [counts] maps a denomination to how many pieces were counted. */
data class CountRecord(val id: String, val at: Long, val counts: Map<Int, Long>) {
    val total: Long get() = counts.entries.sumOf { it.key.toLong() * it.value }
    val pieces: Long get() = counts.values.sum()
}

private fun countRow(r: CountRecord): JsonObject = row(
    "id" to r.id,
    "counted_at" to Instant.ofEpochMilli(r.at).toString(),
    "total" to r.total,
    "pieces" to r.pieces,
    "counts" to JsonObject(r.counts.entries.associate { it.key.toString() to JsonPrimitive(it.value) })
)

private fun countFromRow(o: JsonObject): CountRecord? {
    val id = o.id()
    if (id.isEmpty()) return null
    val at = runCatching { OffsetDateTime.parse(o.str("counted_at")).toInstant().toEpochMilli() }.getOrDefault(0L)
    val c = o["counts"] as? JsonObject ?: JsonObject(emptyMap())
    return CountRecord(id, at, DENOMINATIONS.associateWith { d -> c.num(d.toString()).toLong() })
}

/**
 * Cash counter history: saved on the device first, then pushed to the `cash_counts` table (RLS: own rows).
 * Offline saves wait in a pending queue and go out on the next sync. Same pattern as the notepad.
 */
class CashCountStore(ctx: Context, private val repo: Repository, private val scope: CoroutineScope) {
    private val prefs = ctx.applicationContext.getSharedPreferences("fintrack_counter", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var rows: List<JsonObject> = emptyList()      // local copy of cash_counts rows
    private var pending: List<JsonObject> = emptyList()   // {op, id, row?} not yet on the server
    private var loadedKey: String? = null

    private val _records = MutableStateFlow<List<CountRecord>>(emptyList())
    val records: StateFlow<List<CountRecord>> = _records.asStateFlow()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private fun key(): String = "counter-" + (runCatching { repo.uid() }.getOrDefault("anon"))

    private fun ensureLoaded() {
        val k = key()
        if (loadedKey == k) return
        loadedKey = k
        val raw = prefs.getString(k, null)
        if (raw != null && raw.trimStart().startsWith("[")) {
            // Old format (before sync): a plain list of local counts. Keep them as rows with generated ids.
            rows = runCatching { (Json.parseToJsonElement(raw) as JsonArray).mapNotNull { it as? JsonObject } }
                .getOrDefault(emptyList())
                .mapNotNull { o ->
                    val at = o.num("at").toLong()
                    val c = o["counts"] as? JsonObject ?: return@mapNotNull null
                    countFromRow(row("id" to "local-$at", "counted_at" to Instant.ofEpochMilli(at).toString(), "counts" to c))
                }
                .map { countRow(it) }
            pending = emptyList()
        } else {
            val root = runCatching { Json.parseToJsonElement(raw ?: "{}") as JsonObject }.getOrDefault(JsonObject(emptyMap()))
            rows = (root["rows"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            pending = (root["pending"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }
        publish()
    }

    private fun persist() {
        val root = JsonObject(mapOf("rows" to JsonArray(rows), "pending" to JsonArray(pending)))
        prefs.edit().putString(key(), root.toString()).apply()
    }

    private fun publish() {
        _records.value = rows.mapNotNull { countFromRow(it) }.sortedByDescending { it.at }
        _pendingCount.value = pending.size
    }

    /** Saves a count on the device, then tries the server. Returns true when the server has it too. */
    suspend fun save(counts: Map<Int, Long>): Boolean {
        val rec = CountRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), counts)
        val r = countRow(rec)
        synchronized(this) {
            ensureLoaded()
            rows = listOf(r) + rows
            pending = pending + row("op" to "upsert", "id" to rec.id, "row" to r)
            persist(); publish()
        }
        return sync()
    }

    suspend fun delete(id: String): Boolean {
        synchronized(this) {
            ensureLoaded()
            rows = rows.filterNot { it.id() == id }
            pending = pending.filterNot { it.str("id") == id } + row("op" to "delete", "id" to id)
            persist(); publish()
        }
        return sync()
    }

    /** Pushes pending changes, then refreshes from the server. True when nothing is left unsynced. */
    suspend fun sync(): Boolean = lock.withLock {
        synchronized(this) { ensureLoaded() }
        val queue = synchronized(this) { pending }
        val done = HashSet<String>()
        var pushOk = true
        for (op in queue) {
            val ok = runCatching {
                if (op.str("op") == "upsert") {
                    repo.upsert("cash_counts", listOf(op["row"] as JsonObject), "id")
                } else {
                    repo.delete("cash_counts", op.str("id"))
                }
            }.isSuccess
            if (!ok) { pushOk = false; break }
            done += op.str("id") + "|" + op.str("op")
        }
        synchronized(this) {
            pending = pending.filterNot { (it.str("id") + "|" + it.str("op")) in done }
            persist(); publish()
        }
        val server = runCatching { repo.list("cash_counts", "select=*&order=counted_at.desc") }.getOrNull()
        if (server != null) {
            synchronized(this) {
                val pendingIds = pending.map { it.str("id") }.toSet()
                val localPending = rows.filter { it.id() in pendingIds }
                rows = server.filter { it.id() !in pendingIds } + localPending
                persist(); publish()
            }
        }
        pushOk && server != null && pending.isEmpty()
    }

    /** Clears the in-memory copy (used by the app reset). */
    fun reset() {
        synchronized(this) {
            rows = emptyList(); pending = emptyList(); loadedKey = null
            _records.value = emptyList(); _pendingCount.value = 0
        }
    }
}

private fun grouped(v: Long): String = String.format(Locale.US, "%,d", v)

private val BD_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a", Locale.US)

private fun bdTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(BD_TIME)

/** Money counter: enter how many of each note/coin, see the total, save it to history (device and server). */
@Composable
fun MoneyCounterScreen(vm: AppViewModel) {
    val store = vm.cashCounts
    val history by store.records.collectAsState()
    val pending by store.pendingCount.collectAsState()
    val scope = rememberCoroutineScope()
    var inputs by remember { mutableStateOf(DENOMINATIONS.associateWith { "" }) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val counts: Map<Int, Long> = DENOMINATIONS.associateWith { inputs[it]?.toLongOrNull() ?: 0L }
    val total = counts.entries.sumOf { it.key.toLong() * it.value }
    val pieces = counts.values.sum()

    LaunchedEffect(Unit) { store.sync() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF0EA5E9))))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(tr("সর্বমোট", "Grand total"), fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), letterSpacing = 2.sp)
            Text(bnDigits("৳ ${grouped(total)}"), fontSize = 38.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text(tr("মোট নোট/কয়েন: ", "Notes/coins: ") + bnDigits(pieces.toString()) + tr("টি", ""), fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
        }

        DENOMINATIONS.forEach { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(bnDigits("৳$d"), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.width(72.dp))
                OutlinedTextField(
                    value = inputs[d] ?: "",
                    onValueChange = { v -> inputs = inputs + (d to v.filter { it.isDigit() }.take(6)) },
                    label = { Text(tr("কয়টা", "Count")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    bnDigits("= ৳${grouped(d.toLong() * (counts[d] ?: 0L))}"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(110.dp)
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { inputs = DENOMINATIONS.associateWith { ""}; status = null }, modifier = Modifier.weight(1f)) {
                Text(tr("পরিষ্কার করুন", "Clear"))
            }
            Button(
                onClick = {
                    val toSave = counts
                    scope.launch {
                        val onServer = store.save(toSave)
                        status = if (onServer) tr("✅ ডিভাইসে ও সার্ভারে সংরক্ষিত", "✅ Saved on device and server") else "💾 শুধু এই ফোনে সংরক্ষিত — সার্ভারে সিঙ্ক বাকি (ইন্টারনেট এলে নিজে যাবে)"
                    }
                },
                enabled = total > 0,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14B8A6), contentColor = Color.White),
                modifier = Modifier.weight(1f)
            ) { Text(tr("সংরক্ষণ করুন", "Save")) }
        }
        status?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (pending > 0) {
            Text(tr("অপেক্ষমাণ সিঙ্ক: ", "Pending sync: ") + bnDigits(pending.toString()) + tr("টি", ""), fontSize = 12.sp, color = Amber500)
        }

        SectionTitle(tr("গণনার ইতিহাস", "Count history"))
        if (history.isEmpty()) EmptyHint(tr("এখনো কোনো সংরক্ষিত গণনা নেই", "No saved counts yet"))
        history.forEach { r ->
            val open = expanded == r.id
            AppCard(onClick = { expanded = if (open) null else r.id }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bnDigits(bdTime(r.at)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(bnDigits("৳ ${grouped(r.total)}"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(bnDigits("${r.pieces}") + tr(" টি নোট/কয়েন", " notes/coins"), fontSize = 12.sp)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val onServer = store.delete(r.id)
                            status = if (onServer) tr("✅ গণনাটি মুছে ফেলা হয়েছে (ডিভাইস ও সার্ভার)", "✅ Count deleted (device and server)") else tr("💾 এই ফোনে মুছেছে — সার্ভারে সিঙ্ক বাকি", "💾 Deleted on this phone — server sync pending")
                        }
                        if (expanded == r.id) expanded = null
                    }) { Icon(Icons.Default.Delete, contentDescription = tr("মুছুন", "Delete"), tint = Rose500) }
                }
                if (open) {
                    r.counts.filter { it.value > 0 }.forEach { (d, n) ->
                        KeyValue(bnDigits("৳$d × $n"), bnDigits("৳ ${grouped(d.toLong() * n)}"))
                    }
                }
            }
        }
    }
}
