package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** Tables with soft delete (deleted_at). Same list as migration 20261123000600. */
val TRASH_TABLES: Set<String> = setOf(
    "accounts", "categories", "transactions", "budgets", "goals",
    "debt_people", "debts", "debt_payments", "loans", "subscriptions", "investments",
    "credit_shops", "credit_entries", "credit_payments", "bill_splits", "shopping_items",
    "mess_members", "mess_bazaars", "mess_meals", "mess_deposits", "notes", "cash_counts",
    "bike_fuel_entries", "bike_maintenance_entries"
)

/** Children before parents (same order as fintrack_purge_trash). */
val TRASH_PURGE_ORDER: List<String> = listOf(
    "bike_maintenance_entries", "bike_fuel_entries", "cash_counts", "notes", "mess_deposits", "mess_meals",
    "mess_bazaars", "mess_members", "shopping_items", "bill_splits", "credit_payments", "credit_entries",
    "credit_shops", "investments", "subscriptions", "loans", "debt_payments", "debts", "debt_people",
    "goals", "budgets", "transactions", "categories", "accounts"
)

/** Days a trashed item is kept before it is removed for good. */
const val TRASH_DAYS = 30

/** True when the row is in the Trash. */
fun JsonObject.isTrashed(): Boolean = str("deleted_at").isNotEmpty()

/** The rows the rest of the app sees: trashed rows removed. */
fun liveTables(raw: Map<String, List<JsonObject>>): Map<String, List<JsonObject>> =
    raw.mapValues { (t, rows) -> if (t in TRASH_TABLES) rows.filterNot { it.isTrashed() } else rows }

/** Only the trashed rows, grouped by table (tables with none are left out). */
fun trashedTables(raw: Map<String, List<JsonObject>>): Map<String, List<JsonObject>> =
    raw.filterKeys { it in TRASH_TABLES }
        .mapValues { (_, rows) -> rows.filter { it.isTrashed() } }
        .filterValues { it.isNotEmpty() }

/** One Trash item = one deletion. All rows removed by that action share the same deleted_at. */
data class TrashEntry(val stamp: String, val table: String, val row: JsonObject, val count: Int)

/** The parent row represents a deletion (not the payment or expense linked to it). */
private val PRIMARY_ORDER = listOf(
    "debt_people", "credit_shops", "mess_members", "debts", "loans", "notes", "categories", "accounts",
    "goals", "budgets", "subscriptions", "investments", "bike_fuel_entries", "bike_maintenance_entries",
    "transactions", "credit_entries", "credit_payments", "debt_payments", "bill_splits", "shopping_items",
    "mess_bazaars", "mess_meals", "mess_deposits", "cash_counts"
)

fun trashEntries(trashed: Map<String, List<JsonObject>>): List<TrashEntry> {
    val all = trashed.flatMap { (t, rows) -> rows.map { t to it } }
    return all.groupBy { it.second.str("deleted_at") }.map { (stamp, members) ->
        val primary = members.sortedBy { m -> PRIMARY_ORDER.indexOf(m.first).let { if (it < 0) 99 else it } }.first()
        TrashEntry(stamp, primary.first, primary.second, members.size)
    }.sortedByDescending { it.stamp }
}

/** Whole days left before the item is removed for good. */
fun trashDaysLeft(stamp: String): Int = runCatching {
    val passed = ChronoUnit.DAYS.between(OffsetDateTime.parse(stamp), OffsetDateTime.now()).toInt()
    (TRASH_DAYS - passed).coerceAtLeast(0)
}.getOrDefault(TRASH_DAYS)

fun trashExpired(stamp: String): Boolean = trashDaysLeft(stamp) == 0

private fun kindLabel(table: String): String = when (table) {
    "transactions" -> tr("লেনদেন", "Transaction")
    "notes" -> tr("নোট", "Note")
    "debts", "debt_people", "debt_payments" -> tr("ধার", "Debt")
    "loans" -> tr("লোন", "Loan")
    "credit_shops", "credit_entries", "credit_payments" -> tr("বাকি", "Credit")
    "categories" -> tr("ক্যাটাগরি", "Category")
    "accounts" -> tr("অ্যাকাউন্ট", "Account")
    "goals" -> tr("লক্ষ্য", "Goal")
    "budgets" -> tr("বাজেট", "Budget")
    "bike_fuel_entries", "bike_maintenance_entries" -> tr("বাইক লগ", "Bike log")
    "subscriptions" -> tr("সাবস্ক্রিপশন", "Subscription")
    "investments" -> tr("বিনিয়োগ", "Investment")
    "mess_members", "mess_bazaars", "mess_meals", "mess_deposits" -> tr("মেস", "Mess")
    "shopping_items" -> tr("শপিং", "Shopping")
    "bill_splits" -> tr("বিল ভাগ", "Bill split")
    "cash_counts" -> tr("গণনা", "Cash count")
    else -> table
}

private fun titleOf(e: TrashEntry): String =
    listOf("title", "name", "item", "item_name", "person", "note", "category")
        .map { e.row.str(it) }.firstOrNull { it.isNotBlank() } ?: kindLabel(e.table)

private fun subtitleOf(e: TrashEntry): String {
    val days = trashDaysLeft(e.stamp)
    val date = runCatching { OffsetDateTime.parse(e.stamp).toLocalDate() }.getOrNull()
    val dateText = date?.let { "%02d/%02d/%d".format(it.dayOfMonth, it.monthValue, it.year) }.orEmpty()
    val parts = listOf(
        kindLabel(e.table),
        if (e.count > 1) tr("${e.count} টি", "${e.count} items") else "",
        dateText,
        tr("$days দিন পরে মুছবে", "Removed in $days days")
    )
    return bnDigits(parts.filter { it.isNotEmpty() }.joinToString(" · "))
}

@Composable
fun TrashScreen(state: UiState, vm: AppViewModel) {
    val notesTrash by vm.notes.trashed.collectAsState()
    val countsTrash by vm.cashCounts.trashed.collectAsState()
    val trashed = remember(state.trash, notesTrash, countsTrash) {
        val m = LinkedHashMap<String, List<JsonObject>>(state.trash)
        if (notesTrash.isNotEmpty()) m["notes"] = notesTrash
        if (countsTrash.isNotEmpty()) m["cash_counts"] = countsTrash
        m
    }
    val entries = remember(trashed) { trashEntries(trashed) }
    var askStamp by remember { mutableStateOf<String?>(null) }
    var askAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("আবর্জনা", "Trash"), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                TextButton(onClick = { askAll = true }) { Text(tr("সব খালি করুন", "Empty trash"), color = Rose500) }
            }
        }
        if (entries.isEmpty()) EmptyHint(tr("আবর্জনা খালি", "Trash is empty"))
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            items(entries, key = { it.stamp + it.table }) { e ->
                AppCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(titleOf(e), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(subtitleOf(e), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val amountKey = listOf("amount", "total", "cost", "price").firstOrNull { e.row.str(it).isNotEmpty() }
                        if (amountKey != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(taka(e.row.num(amountKey)), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.restoreTrash(setOf(e.stamp)) }) { Text(tr("ফেরত", "Restore")) }
                        TextButton(onClick = { askStamp = e.stamp }) { Text(tr("চিরতরে মুছুন", "Delete forever"), color = Rose500) }
                    }
                }
            }
        }
    }

    askStamp?.let { stamp ->
        ConfirmDialog(
            text = tr("এটি চিরতরে মুছে যাবে। নিশ্চিত?", "Delete this forever? It cannot be undone."),
            confirmText = tr("মুছে ফেলুন", "Delete"),
            cancelText = tr("বাতিল", "Cancel"),
            onConfirm = { askStamp = null; vm.purgeTrash(setOf(stamp)) },
            onDismiss = { askStamp = null }
        )
    }
    if (askAll) {
        ConfirmDialog(
            text = tr("আবর্জনার সব আইটেম চিরতরে মুছে যাবে। নিশ্চিত?", "Everything in the Trash will be deleted forever. Are you sure?"),
            confirmText = tr("সব মুছুন", "Delete all"),
            cancelText = tr("বাতিল", "Cancel"),
            onConfirm = { askAll = false; vm.emptyTrash() },
            onDismiss = { askAll = false }
        )
    }
}
