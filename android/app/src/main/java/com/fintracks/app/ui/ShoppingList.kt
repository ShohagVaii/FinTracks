package com.fintracks.app.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult as rememberLauncher
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.ui.design.FilterPill
import com.fintracks.app.ui.design.SwipeAction
import com.fintracks.app.ui.design.SwipeRow
import com.fintracks.app.ui.design.UndoBar
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

private val shopUnits = listOf("পিস", "কেজি", "গ্রাম", "লিটার", "ডজন", "প্যাকেট")
private val shopCategories = listOf("সবজি", "মাছ-মাংস", "মুদি", "দুধ-ডিম", "মসলা", "প্রসাধনী", "অন্যান্য")

private val numberWords = mapOf(
    "এক" to 1.0, "দুই" to 2.0, "তিন" to 3.0, "চার" to 4.0, "পাঁচ" to 5.0,
    "ছয়" to 6.0, "সাত" to 7.0, "আট" to 8.0, "নয়" to 9.0, "দশ" to 10.0
)

/**
 * Splits a spoken or typed line into items: "চাল দুই কেজি ডাল এক কেজি" -> চাল 2 কেজি, ডাল 1 কেজি.
 * A name runs until a quantity; an item without a quantity is 1 piece.
 */
internal fun parseShoppingLine(text: String): List<Triple<String, Double, String>> {
    val out = mutableListOf<Triple<String, Double, String>>()
    val name = StringBuilder()
    fun flush(qty: Double, unit: String) {
        if (name.isNotBlank()) out += Triple(name.toString().trim(), qty, unit)
        name.clear()
    }
    val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        val n = t.toDoubleOrNull() ?: numberWords[t]
        when {
            n != null -> {
                var unit = "পিস"
                if (i + 1 < tokens.size && tokens[i + 1] in shopUnits) {
                    unit = tokens[i + 1]
                    i++
                }
                flush(n, unit)
            }
            t in shopUnits && name.isNotBlank() -> flush(1.0, t)
            else -> {
                if (name.isNotEmpty()) name.append(' ')
                name.append(t)
            }
        }
        i++
    }
    flush(1.0, "পিস")
    return out
}

private fun lastPrice(state: UiState, name: String): Double =
    state.rows("shopping_items").lastOrNull { it.bool("is_done") && it.str("name") == name }?.num("actual_price") ?: 0.0

private fun addShoppingItem(vm: AppViewModel, listId: String, name: String, qty: Double, unit: String, price: Double) {
    vm.save(
        "shopping_items",
        row(
            "list_id" to listId, "name" to name, "qty" to qty, "unit" to unit, "category" to "অন্যান্য",
            "priority" to "normal", "note" to "", "assignee" to "", "estimated_price" to price, "is_done" to false
        ),
        null
    )
}

private fun shoppingListText(l: JsonObject, items: List<JsonObject>, p: Boolean): String {
    val lines = mutableListOf(l.str("name"))
    items.forEach { i ->
        val qty = i.num("qty").takeIf { it > 0 } ?: 1.0
        lines += (if (i.bool("is_done")) "✓ " else "• ") + i.str("name") + " " + fmtNum(qty) + " " + i.str("unit") +
            " · " + taka(i.num("estimated_price"), p)
    }
    return lines.joinToString("\n")
}

@Composable
internal fun ShoppingSection(state: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    val p = state.privacy
    val lists = state.rows("shopping_lists").filter { !it.bool("archived") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    val l = lists.firstOrNull { it.id() == selectedId } ?: lists.firstOrNull()
    var creatingList by remember { mutableStateOf(false) }
    var editingList by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<JsonObject?>(null) }
    var pickingMember by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf("") }
    var deleted by remember { mutableStateOf<JsonObject?>(null) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    val voice = rememberLauncher(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { quick = it }
    }

    AddButton(tr("নতুন তালিকা", "New list")) { creatingList = true }
    if (lists.isNotEmpty()) {
        ChoiceChips(lists.map { it.id() to it.str("name") }, l?.id().orEmpty()) { selectedId = it }
    }
    if (l == null) {
        EmptyHint(tr("কোনো তালিকা নেই", "No list yet"))
    } else {
        val id = l.id()
        val items = state.rows("shopping_items").filter { it.str("list_id") == id }
        val bought = items.count { it.bool("is_done") }
        val spent = items.filter { it.bool("is_done") }.sumOf { it.num("actual_price") }
        val est = items.sumOf { it.num("estimated_price") }
        val budget = l.num("budget")
        val allDone = items.isNotEmpty() && bought == items.size
        val addToTx = l.bool("add_to_tx")

        // One expense per list, posted once when everything is bought and the toggle is on.
        LaunchedEffect(allDone, addToTx, l.bool("tx_posted"), id) {
            if (allDone && addToTx && !l.bool("tx_posted") && spent > 0) {
                vm.save(
                    "transactions",
                    row(
                        "type" to "Expense", "amount" to spent, "currency" to state.currency,
                        "category" to "অন্যান্য", "note" to l.str("name"), "tx_date" to todayIso(),
                        "payment_mode" to "Cash", "mood" to "", "is_recurring" to false,
                        "source" to "shopping_list", "source_ref" to id
                    ),
                    null
                )
                vm.save("shopping_lists", row("tx_posted" to true), id)
            }
        }

        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AnimatedRing(
                    fraction = if (items.isEmpty()) 0f else bought.toFloat() / items.size,
                    color = Emerald500, size = 88.dp, stroke = 8.dp
                )
                Column(Modifier.weight(1f)) {
                    Text(l.str("name"), fontWeight = FontWeight.Bold)
                    if (budget > 0) KeyValue(tr("বাজেট", "Budget"), taka(budget, p))
                    KeyValue(tr("আনুমানিক", "Estimated"), taka(est, p))
                    KeyValue(tr("কেনা", "Bought"), taka(spent, p), Emerald500)
                    if (budget > 0) {
                        val left = budget - spent
                        KeyValue(
                            if (left >= 0) tr("বাকি", "Left") else tr("বেশি খরচ", "Over"),
                            taka(abs(left), p),
                            if (left >= 0) Emerald500 else Rose500
                        )
                    }
                }
            }
            if (allDone) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuccessTick(modifier = Modifier.size(28.dp))
                    Text(tr("সব কেনা হয়েছে", "All bought"), fontWeight = FontWeight.Bold, color = readableOn(Emerald500))
                }
            }
            FilterPill(tr("লেনদেনে যোগ", "Add to transactions"), addToTx) {
                vm.save("shopping_lists", row("add_to_tx" to !addToTx), id)
            }
        }

        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextIn(tr("জিনিস যোগ করুন", "Add items"), quick, { quick = it }, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    runCatching {
                        voice.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                        )
                    }
                }) { Text(tr("ভয়েস", "Voice")) }
                Button(
                    enabled = quick.isNotBlank(),
                    onClick = {
                        parseShoppingLine(quick).forEach { (n, q, u) -> addShoppingItem(vm, id, n, q, u, lastPrice(state, n)) }
                        quick = ""
                    }
                ) { Text(tr("যোগ", "Add")) }
            }
            val recent = state.rows("shopping_items").filter { it.bool("is_done") }.asReversed().map { it.str("name") }.distinct().take(8)
            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEach { n ->
                        FilterPill(n, false) { addShoppingItem(vm, id, n, 1.0, "পিস", lastPrice(state, n)) }
                    }
                }
            }
            val previous = lists.filter { it.id() != id }.lastOrNull()
            if (previous != null) {
                OutlinedButton(modifier = Modifier.pressScale(), onClick = {
                    state.rows("shopping_items").filter { it.str("list_id") == previous.id() }.forEach { src ->
                        vm.save(
                            "shopping_items",
                            row(
                                "list_id" to id, "name" to src.str("name"), "qty" to src.num("qty"), "unit" to src.str("unit"),
                                "category" to src.str("category"), "priority" to src.str("priority"), "note" to src.str("note"),
                                "estimated_price" to src.num("estimated_price"), "is_done" to false
                            ),
                            null
                        )
                    }
                }) { Text(tr("আগের তালিকা থেকে", "From previous list")) }
            }
        }

        if (items.isEmpty()) EmptyHint(tr("তালিকা খালি", "List is empty"))
        val byCat = items.groupBy { it.str("category").ifEmpty { "অন্যান্য" } }
        byCat.keys.sortedBy { k -> shopCategories.indexOf(k).let { if (it < 0) 99 else it } }.forEach { cat ->
            val group = byCat.getValue(cat)
            val open = collapsed[cat] != true
            Row(
                Modifier.fillMaxWidth().clickable { collapsed[cat] = open },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(cat, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text((if (open) "▾ " else "▸ ") + group.size, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                group.forEachIndexed { idx, item ->
                    val done = item.bool("is_done")
                    val price = item.num("estimated_price")
                    SwipeRow(
                        onSwipeRight = if (done) null else SwipeAction(tr("কেনা", "Bought"), Icons.Default.Check, Emerald500) {
                            vm.save("shopping_items", row("is_done" to true, "actual_price" to price), item.id())
                        },
                        onSwipeLeft = SwipeAction(tr("মুছুন", "Delete"), Icons.Default.Delete, Rose500) {
                            deleted = item
                            vm.remove("shopping_items", item.id())
                        }
                    ) {
                        Box(Modifier.staggerIn(idx)) {
                            ShoppingItemRow(
                                item = item,
                                p = p,
                                last = lastPrice(state, item.str("name")),
                                onToggle = {
                                    if (done) vm.save("shopping_items", row("is_done" to false, "actual_price" to JsonNull), item.id())
                                    else vm.save("shopping_items", row("is_done" to true, "actual_price" to price), item.id())
                                },
                                onEdit = { editingItem = item },
                                onDelete = {
                                    deleted = item
                                    vm.remove("shopping_items", item.id())
                                }
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { shareText(ctx, shoppingListText(l, items, p)) }) { Text(tr("শেয়ার", "Share")) }
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { pickingMember = true }) { Text(tr("মেস বাজার", "Mess bazaar")) }
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { editingList = true }) { Text(tr("সম্পাদনা", "Edit")) }
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { vm.save("shopping_lists", row("archived" to true), id) }) { Text(tr("আর্কাইভ", "Archive")) }
            DeleteButton { vm.remove("shopping_lists", id) }
        }
        if (pickingMember) MessBazaarDialog(state, vm, l.str("name"), if (spent > 0) spent else est) { pickingMember = false }
        if (editingList) ListDialog(l, vm) { editingList = false }
        editingItem?.let { ItemDialog(it, vm) { editingItem = null } }
    }

    UndoBar(
        visible = deleted != null,
        message = tr("মুছে ফেলা হয়েছে", "Item deleted"),
        onUndo = {
            deleted?.let { vm.save("shopping_items", it, null) }
            deleted = null
        },
        onExpire = { deleted = null }
    )
    if (creatingList) ListDialog(null, vm) { creatingList = false }
}

@Composable
private fun ShoppingItemRow(
    item: JsonObject,
    p: Boolean,
    last: Double,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val done = item.bool("is_done")
    val qty = item.num("qty").takeIf { it > 0 } ?: 1.0
    val priority = when (item.str("priority")) {
        "high" -> Rose500
        "low" -> MaterialTheme.colorScheme.outline
        else -> Amber500
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Tap target for the same action as the swipe, so TalkBack users get a button.
        IconButton(onClick = onToggle) {
            Icon(
                Icons.Default.Check,
                contentDescription = if (done) tr("কেনা হয়নি", "Mark not bought") else tr("কেনা হয়েছে", "Mark bought"),
                tint = if (done) Emerald500 else muted
            )
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(priority))
        Column(Modifier.weight(1f)) {
            Text(
                item.str("name"),
                fontWeight = FontWeight.Bold,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) muted else MaterialTheme.colorScheme.onSurface
            )
            Text(
                fmtNum(qty) + " " + item.str("unit") +
                    (if (last > 0) " · " + tr("গতবার", "Last") + " " + taka(last, p) else "") +
                    (if (done) " · " + taka(item.num("actual_price"), p) else ""),
                fontSize = 12.sp,
                color = muted
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = tr("মুছুন", "Delete"))
        }
    }
}

@Composable
private fun ListDialog(existing: JsonObject?, vm: AppViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf(existing?.str("name").orEmpty()) }
    var budget by remember { mutableStateOf(existing?.num("budget")?.takeIf { it > 0 }?.let { fmtNum(it) }.orEmpty()) }
    FormDialog(
        if (existing == null) tr("নতুন তালিকা", "New list") else tr("তালিকা সম্পাদনা", "Edit list"),
        confirmEnabled = name.isNotBlank(),
        onDismiss = onDone,
        onConfirm = {
            vm.save("shopping_lists", row("name" to name.trim(), "budget" to (parseAmount(budget) ?: 0.0)), existing?.id())
            onDone()
        }
    ) {
        TextIn(tr("নাম", "Name"), name, { name = it })
        TextIn(tr("বাজেট (৳, ঐচ্ছিক)", "Budget (৳, optional)"), budget, { budget = it }, number = true)
    }
}

@Composable
private fun ItemDialog(item: JsonObject, vm: AppViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf(item.str("name")) }
    var qty by remember { mutableStateOf(fmtNum(item.num("qty").takeIf { it > 0 } ?: 1.0)) }
    var unit by remember { mutableStateOf(item.str("unit").ifEmpty { "পিস" }) }
    var category by remember { mutableStateOf(item.str("category").ifEmpty { "অন্যান্য" }) }
    var priority by remember { mutableStateOf(item.str("priority").ifEmpty { "normal" }) }
    var price by remember { mutableStateOf(item.num("estimated_price").takeIf { it > 0 }?.let { fmtNum(it) }.orEmpty()) }
    var note by remember { mutableStateOf(item.str("note")) }
    var assignee by remember { mutableStateOf(item.str("assignee")) }
    val done = item.bool("is_done")
    FormDialog(
        tr("জিনিস সম্পাদনা", "Edit item"),
        confirmEnabled = name.isNotBlank() && (parseAmount(qty) ?: 0.0) > 0,
        onDismiss = onDone,
        onConfirm = {
            vm.save(
                "shopping_items",
                row(
                    "name" to name.trim(), "qty" to (parseAmount(qty) ?: 1.0), "unit" to unit, "category" to category,
                    "priority" to priority, "estimated_price" to (parseAmount(price) ?: 0.0),
                    "actual_price" to (if (done) parseAmount(price) ?: 0.0 else null),
                    "note" to note.trim(), "assignee" to assignee.trim()
                ),
                item.id()
            )
            onDone()
        }
    ) {
        TextIn(tr("নাম", "Name"), name, { name = it })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextIn(tr("পরিমাণ", "Quantity"), qty, { qty = it }, number = true, modifier = Modifier.weight(1f))
        }
        ChoiceChips(shopUnits.map { it to it }, unit) { unit = it }
        ChoiceChips(shopCategories.map { it to it }, category) { category = it }
        ChoiceChips(listOf("high" to tr("জরুরি", "High"), "normal" to tr("সাধারণ", "Normal"), "low" to tr("কম", "Low")), priority) { priority = it }
        TextIn(tr("আনুমানিক দাম (৳)", "Estimated price (৳)"), price, { price = it }, number = true)
        TextIn(tr("নোট", "Note"), note, { note = it })
        TextIn(tr("কে আনবেন", "Assignee"), assignee, { assignee = it })
    }
}

@Composable
private fun MessBazaarDialog(state: UiState, vm: AppViewModel, listName: String, amount: Double, onDone: () -> Unit) {
    val members = state.rows("mess_members")
    var member by remember { mutableStateOf(members.firstOrNull()?.id().orEmpty()) }
    FormDialog(
        tr("মেস বাজার হিসেবে সেভ", "Save as mess bazaar"),
        confirmEnabled = member.isNotBlank() && amount > 0,
        onDismiss = onDone,
        onConfirm = {
            vm.save(
                "mess_bazaars",
                row("member_id" to member, "amount" to amount, "item" to listName, "bazaar_date" to todayIso()),
                null
            )
            onDone()
        }
    ) {
        if (members.isEmpty()) {
            EmptyHint(tr("আগে মেস সদস্য যোগ করুন", "Add mess members first"))
        } else {
            ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
        }
        KeyValue(tr("মোট", "Total"), taka(amount))
    }
}
