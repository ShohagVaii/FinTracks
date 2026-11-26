package com.fintracks.app.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import com.fintracks.app.ui.design.SearchBox
import com.fintracks.app.ui.design.FilterPill
import com.fintracks.app.ui.design.StatusChip
import com.fintracks.app.ui.design.MetricTile
import com.fintracks.app.ui.design.GlassCard
import com.fintracks.app.ui.design.AvatarCircle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect

/*
 * বাকি খাতা: দোকান → কেনা (একাধিক আইটেম + ± সমন্বয়) → পরিশোধের কিস্তি।
 * সব এন্ট্রি ম্যানুয়াল। ধার/লোন থেকে আলাদা। পরিশোধ বাকির বেশি হলে আটকে দেওয়া হয়।
 * পরিশোধ পুরোনো কেনা থেকে ক্রমানুসারে মেটে (FIFO), তাই প্রতিটি কেনার অবস্থা জানা যায়।
 */

private val BakiRed = Rose500
private val BakiGreen = Emerald500
private val BakiAmber = Amber500
private val BakiSky = ChartNet
private const val EPS = 0.005

enum class BakiStatus(private val bnLabel: String, private val enLabel: String, val color: Color) {
    ONGOING("চলমান", "Ongoing", BakiSky),
    PARTIAL("আংশিক পরিশোধ", "Partly paid", BakiAmber),
    SETTLED("শোধ", "Settled", BakiGreen);

    val label: String get() = tr(bnLabel, enLabel)
}

/** One line of a purchase, as typed in the form. */
data class ItemDraft(val name: String = "", val qty: String = "1", val price: String = "")

/** How much of one purchase is paid, and what is still owed. */
class Settlement(val total: Double, val paid: Double, val dueDate: String?) {
    val remaining: Double get() = (total - paid).coerceAtLeast(0.0)
    val status: BakiStatus
        get() = when {
            remaining <= EPS -> BakiStatus.SETTLED
            paid > EPS -> BakiStatus.PARTIAL
            else -> BakiStatus.ONGOING
        }
    fun overdue(today: String): Boolean = remaining > EPS && dueDate != null && dueDate < today
}

/** Shop-wise running balance: all purchase totals minus all payments. */
fun shopBalance(shopId: String, state: UiState): Double {
    val bought = state.rows("credit_entries").filter { it.str("shop_id") == shopId }.sumOf { it.num("total") }
    val paid = state.rows("credit_payments").filter { it.str("shop_id") == shopId }.sumOf { it.num("amount") }
    return bought - paid
}

/** Pays the oldest purchases first. Returns purchase id to its settlement. */
private fun settleShop(shopId: String, state: UiState): Map<String, Settlement> {
    var pool = state.rows("credit_payments").filter { it.str("shop_id") == shopId }.sumOf { it.num("amount") }
    val purchases = state.rows("credit_entries").filter { it.str("shop_id") == shopId }
        .sortedWith(compareBy<JsonObject>({ it.str("purchase_date") }, { it.str("created_at") }))
    val out = LinkedHashMap<String, Settlement>()
    for (e in purchases) {
        val total = e.num("total")
        val applied = minOf(total, pool).coerceAtLeast(0.0)
        pool -= applied
        out[e.id()] = Settlement(total, applied, e.strOrNull("due_date")?.take(10))
    }
    return out
}

/** Number of purchases past their due date and still unpaid, across all shops. */
fun bakiOverdueCount(state: UiState): Int {
    val today = todayIso()
    return state.rows("credit_shops").sumOf { s -> settleShop(s.id(), state).values.count { it.overdue(today) } }
}

/** Purchase lines. Old single-item rows (before grouping) show as one line. */
private fun purchaseLines(p: JsonObject): List<ItemDraft> {
    val arr = p["items"] as? JsonArray
    if (arr != null && arr.isNotEmpty()) {
        return arr.mapNotNull { el ->
            (el as? JsonObject)?.let { ItemDraft(it.str("name"), fmtNum(it.num("qty")), fmtNum(it.num("price"))) }
        }
    }
    return listOf(ItemDraft(p.str("item_name"), fmtNum(p.num("quantity")), fmtNum(p.num("unit_price"))))
}

private fun lineTotal(l: ItemDraft): Double = (l.qty.toDoubleOrNull() ?: 0.0) * (parseAmount(l.price) ?: 0.0)

// ---------- Dashboard card ----------

@Composable
fun CreditDueCard(state: UiState, onOpen: () -> Unit) {
    val due = state.rows("credit_shops").sumOf { shopBalance(it.id(), state) }
    val overdue = bakiOverdueCount(state)
    val p = state.privacy
    AppCard(onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(tr("মোট বাকি দেনা", "Total credit due"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(taka(due, p), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = readableOn(BakiRed))
            }
            if (overdue > 0) Text(tr("$overdue টি মেয়াদোত্তীর্ণ", "$overdue overdue"), color = readableOn(BakiRed), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Shop list ----------

@Composable
fun BakiScreen(state: UiState, vm: AppViewModel, subPage: String, go: (String) -> Unit, onBack: () -> Unit) {
    val selectedId = subPage.ifEmpty { null }
    var addingShop by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var chipFilter by rememberSaveable { mutableStateOf("all") }
    var sortKey by rememberSaveable { mutableStateOf("due") }
    var paying by remember { mutableStateOf<JsonObject?>(null) }
    val shop = selectedId?.let { id -> state.rows("credit_shops").firstOrNull { it.id() == id } }
    if (shop != null) {
        ShopDetail(shop, state, vm) { onBack() }
        return
    }
    val p = state.privacy
    val shops = state.rows("credit_shops")
    val totalDue = shops.sumOf { shopBalance(it.id(), state) }
    val overdue = bakiOverdueCount(state)
    val today = todayIso()
    val month = today.take(7)
    val boughtThisMonth = state.rows("credit_entries").filter { it.str("purchase_date").startsWith(month) }.sumOf { it.num("total") }
    val totalPaid = state.rows("credit_payments").sumOf { it.num("amount") }
    val stats = shops.associate { s ->
        val lines = settleShop(s.id(), state).values
        s.id() to ShopStat(
            balance = shopBalance(s.id(), state),
            open = lines.count { it.status != BakiStatus.SETTLED },
            overdue = lines.count { it.overdue(today) }
        )
    }
    val q = query.trim()
    val visible = shops.filter { s ->
        val st = stats.getValue(s.id())
        (q.isEmpty() || s.str("name").contains(q, true) || s.str("phone").contains(q)) &&
            when (chipFilter) {
                "due" -> st.balance > 0.005
                "overdue" -> st.overdue > 0
                else -> true
            }
    }.sortedWith(when (sortKey) {
        "name" -> compareBy<JsonObject> { it.str("name") }
        "balance" -> compareByDescending<JsonObject> { stats.getValue(it.id()).balance }
        else -> compareByDescending<JsonObject> { stats.getValue(it.id()).overdue > 0 }
            .thenByDescending { stats.getValue(it.id()).balance }
    })

    Page {
        GlassCard(Modifier.fillMaxWidth()) {
            Text(tr("মোট বাকি", "Total credit due"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            CountUpText(
                target = totalDue,
                format = { taka(it, p) },
                color = readableOn(BakiRed),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                tr("${shops.size} টি দোকান", "${shops.size} shops") +
                    (if (overdue > 0) " · " + tr("$overdue টি মেয়াদ-পার", "$overdue overdue") else ""),
                fontSize = 12.sp,
                color = if (overdue > 0) readableOn(BakiRed) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(tr("এই মাসে কেনা", "Bought this month"), taka(boughtThisMonth, p), Modifier.weight(1f))
            MetricTile(tr("শোধ", "Paid"), taka(totalPaid, p), Modifier.weight(1f), Emerald500)
            MetricTile(tr("মেয়াদ-পার", "Overdue"), overdue.toString(), Modifier.weight(1f).clickable { chipFilter = "overdue" }, Rose500)
        }
        SearchBox(query, { query = it }, tr("দোকান বা ফোন খুঁজুন", "Search shop or phone"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterPill(tr("সব", "All"), chipFilter == "all") { chipFilter = "all" }
            FilterPill(tr("বাকি আছে", "Due"), chipFilter == "due") { chipFilter = "due" }
            FilterPill(tr("মেয়াদ-পার", "Overdue"), chipFilter == "overdue") { chipFilter = "overdue" }
            Spacer(Modifier.weight(1f))
            val sortText = when (sortKey) {
                "balance" -> tr("বেশি বাকি", "Highest due")
                "name" -> tr("নাম", "Name")
                else -> tr("মেয়াদ আগে", "Overdue first")
            }
            FilterPill(sortText, false) { sortKey = when (sortKey) { "due" -> "balance"; "balance" -> "name"; else -> "due" } }
        }
        AddButton(tr("নতুন দোকান", "New shop")) { addingShop = true }
        if (shops.isEmpty()) EmptyHint(tr("কোনো দোকান নেই। দোকান যোগ করে কেনা ও বাকি তুলুন।", "No shops yet. Add a shop to record purchases and credit."))
        visible.forEach { s ->
            val st = stats.getValue(s.id())
            AppCard(onClick = { go("ov:CREDIT/${s.id()}") }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AvatarCircle(s.str("name"), size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.str("name"), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        when {
                            st.overdue > 0 -> StatusChip(tr("${st.overdue} টি মেয়াদ-পার", "${st.overdue} overdue"), Rose500)
                            st.open > 0 -> StatusChip(tr("${st.open} টি খোলা", "${st.open} open"), BakiAmber)
                            else -> StatusChip(tr("সব শোধ", "All settled"), Emerald500)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(taka(st.balance, p), fontWeight = FontWeight.Bold, color = readableOn(BakiRed))
                        if (st.balance > 0.005) {
                            TextButton(onClick = { paying = s }) { Text(tr("শোধ", "Pay")) }
                        }
                    }
                }
            }
        }
    }

    if (addingShop) {
        ShopDialog(null, vm) { addingShop = false }
    }
    paying?.let { s ->
        PaymentDialog(s.id(), s.str("name"), stats.getValue(s.id()).balance, vm) { paying = null }
    }
}

@Composable
private fun ShopDialog(existing: JsonObject?, vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.str("name").orEmpty()) }
    var phone by remember { mutableStateOf(existing?.str("phone").orEmpty()) }
    var note by remember { mutableStateOf(existing?.str("note").orEmpty()) }
    FormDialog(if (existing == null) tr("নতুন দোকান", "New shop") else tr("দোকান সম্পাদনা", "Edit shop"), confirmEnabled = name.isNotBlank(), onDismiss = onDismiss, onConfirm = {
        vm.save("credit_shops", row("name" to name.trim(), "phone" to phone.trim(), "note" to note.trim()), existing?.id())
        onDismiss()
    }) {
        TextIn(tr("দোকান / ব্যক্তির নাম", "Shop / person name"), name, { name = it })
        TextIn(tr("ফোন (ঐচ্ছিক)", "Phone (optional)"), phone, { phone = it })
        TextIn(tr("নোট (ঐচ্ছিক)", "Note (optional)"), note, { note = it })
    }
}

// ---------- Shop detail ----------

@Composable
private fun ShopDetail(shop: JsonObject, state: UiState, vm: AppViewModel, onBack: () -> Unit) {
    var addingPurchase by remember { mutableStateOf(false) }
    var editingPurchase by remember { mutableStateOf<JsonObject?>(null) }
    var addingPayment by remember { mutableStateOf(false) }
    var editingShop by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val p = state.privacy
    val today = todayIso()
    val purchases = state.rows("credit_entries").filter { it.str("shop_id") == shop.id() }
        .sortedWith(compareByDescending<JsonObject> { it.str("purchase_date") }.thenByDescending { it.str("created_at") })
    val payments = state.rows("credit_payments").filter { it.str("shop_id") == shop.id() }
    val settled = settleShop(shop.id(), state)
    val bought = purchases.sumOf { it.num("total") }
    val paid = payments.sumOf { it.num("amount") }
    val balance = bought - paid
    // Confetti fires once, at the moment the balance reaches zero while this screen is open.
    val settledNow = balance <= EPS
    val settledPrev = remember(shop.id()) { mutableStateOf(settledNow) }
    val settleCelebrate = remember(shop.id()) { mutableStateOf(0) }
    LaunchedEffect(settledNow) { if (settledNow && !settledPrev.value) settleCelebrate.value++; settledPrev.value = settledNow }

    Page {
        OutlinedButton(modifier = Modifier.pressScale(), onClick = onBack) { Text(tr("← সব দোকান", "← All shops")) }
        AppCard {
            Text(shop.str("name"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (shop.str("phone").isNotBlank()) Text(shop.str("phone"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KeyValue(tr("বাকি আছে", "Balance due"), taka(balance, p), if (balance > EPS) BakiRed else BakiGreen)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedRing(if (bought > 0) (paid / bought).toFloat().coerceIn(0f, 1f) else 0f, BakiGreen, 56.dp, 6.dp)
                if (balance <= EPS) Box(Modifier.popIn()) { Text(tr("পরিশোধ সম্পূর্ণ", "Fully paid"), color = readableOn(BakiGreen), fontWeight = FontWeight.Bold) }
            }
            if (settleCelebrate.value > 0) key(settleCelebrate.value) { Box(Modifier.fillMaxWidth().height(120.dp)) { ConfettiBurst(Modifier.matchParentSize()) } }
            KeyValue(tr("মোট কেনা", "Total bought"), taka(bought, p))
            KeyValue(tr("মোট পরিশোধ", "Total paid"), taka(paid, p))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.pressScale(), onClick = { addingPurchase = true }) { Text(tr("নতুন কেনা", "New purchase")) }
                OutlinedButton(modifier = Modifier.pressScale(), onClick = { addingPayment = true }, enabled = balance > EPS) { Text(tr("পরিশোধ", "Pay")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(modifier = Modifier.pressScale(), onClick = { editingShop = true }) { Text(tr("দোকান সম্পাদনা", "Edit shop")) }
                DeleteButton { confirmDelete = true }
            }
        }

        SectionTitle(tr("কেনার তালিকা", "Purchases"))
        if (purchases.isEmpty()) EmptyHint(tr("এখনো কোনো কেনা নেই", "No purchases yet"))
        purchases.forEach { e ->
            val st = settled[e.id()]
            val status = st?.status ?: BakiStatus.ONGOING
            val overdue = st?.overdue(today) == true
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(e.str("item_name"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    StatusChip(if (overdue) tr("মেয়াদোত্তীর্ণ", "Overdue") else status.label, if (overdue) BakiRed else status.color)
                }
                val lines = purchaseLines(e)
                lines.forEach { l ->
                    Text(
                        "• ${l.name} · ${l.qty} × ${taka(parseAmount(l.price) ?: 0.0, p)} = ${taka(lineTotal(l), p)}",
                        fontSize = 12.sp
                    )
                }
                val adj = e.num("adjustment")
                if (adj != 0.0) Text(tr("সমন্বয়", "Adjustment") + ": ${if (adj > 0) "+" else ""}${taka(adj, p)}", fontSize = 12.sp)
                KeyValue(tr("মোট", "Total"), taka(e.num("total"), p))
                KeyValue(tr("পরিশোধ", "Pay"), taka(st?.paid ?: 0.0, p), BakiGreen)
                KeyValue(tr("বাকি", "Due"), taka(st?.remaining ?: e.num("total"), p), if ((st?.remaining ?: 0.0) > EPS) BakiRed else BakiGreen)
                Text(
                    tr("কেনা", "Bought") + ": ${displayDate(dateOf(e, "purchase_date"))}" + (e.strOrNull("due_date")?.take(10)?.let { " · " + tr("শেষ তারিখ", "Due") + ": ${displayDate(it)}" } ?: ""),
                    fontSize = 11.sp,
                    color = if (overdue) BakiRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (e.str("note").isNotBlank()) Text(e.str("note"), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(modifier = Modifier.pressScale(), onClick = { editingPurchase = e }) { Text(tr("সম্পাদনা", "Edit")) }
                    DeleteButton { vm.remove("credit_entries", e.id()) }
                }
            }
        }

        SectionTitle(tr("পরিশোধের ইতিহাস", "Payment history"))
        if (payments.isEmpty()) EmptyHint(tr("এখনো কোনো পরিশোধ নেই", "No payments yet"))
        payments.sortedByDescending { it.str("paid_on") }.forEach { pay ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(taka(pay.num("amount"), p), fontWeight = FontWeight.Bold, color = readableOn(BakiGreen))
                        Text(displayDate(dateOf(pay, "paid_on")) + (if (pay.str("note").isNotBlank()) " · ${pay.str("note")}" else ""), fontSize = 11.sp)
                    }
                    DeleteButton { vm.remove("credit_payments", pay.id()) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (addingPurchase) PurchaseDialog(shop.id(), null, vm) { addingPurchase = false }
    editingPurchase?.let { PurchaseDialog(shop.id(), it, vm) { editingPurchase = null } }
    if (addingPayment) PaymentDialog(shop.id(), shop.str("name"), balance, vm) { addingPayment = false }
    if (editingShop) ShopDialog(shop, vm) { editingShop = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("দোকানটি মুছবেন?", "Delete this shop?"), fontWeight = FontWeight.Bold) },
            text = { Text(tr("এই দোকানের সব কেনা ও পরিশোধের হিসাব মুছে যাবে। এটি ফেরানো যাবে না।", "All purchases and payments of this shop will be deleted. This cannot be undone.")) },
            confirmButton = {
                TextButton(modifier = Modifier.pressScale(), onClick = { confirmDelete = false; vm.remove("credit_shops", shop.id()); onBack() }) { Text(tr("মুছুন", "Delete")) }
            },
            dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = { confirmDelete = false }) { Text(tr("বাতিল", "Cancel")) } }
        )
    }
}

// ---------- Purchase form (একাধিক আইটেম + ± সমন্বয়) ----------

@Composable
private fun PurchaseDialog(shopId: String, existing: JsonObject?, vm: AppViewModel, onDismiss: () -> Unit) {
    val lines = remember { mutableStateListOf<ItemDraft>().apply { addAll(existing?.let { purchaseLines(it) } ?: listOf(ItemDraft())) } }
    var adjust by remember {
        mutableStateOf(if (existing != null && existing.num("adjustment") != 0.0) fmtNum(existing.num("adjustment")) else "")
    }
    var date by remember { mutableStateOf(existing?.let { dateOf(it, "purchase_date") }?.takeIf { it.isNotBlank() } ?: todayIso()) }
    var due by remember { mutableStateOf(existing?.strOrNull("due_date")?.take(10).orEmpty()) }
    var note by remember { mutableStateOf(existing?.str("note").orEmpty()) }

    val adj = parseAmount(adjust) ?: 0.0
    val adjValid = adjust.isBlank() || parseAmount(adjust) != null
    val named = lines.filter { it.name.isNotBlank() }
    val linesValid = named.isNotEmpty() && named.all {
        (it.qty.toDoubleOrNull() ?: 0.0) > 0 && (parseAmount(it.price) ?: -1.0) >= 0
    }
    val total = named.sumOf { lineTotal(it) } + adj
    val valid = linesValid && adjValid && total >= 0 && isValidIsoDate(date) && (due.isBlank() || isValidIsoDate(due))

    FormDialog(if (existing == null) tr("নতুন কেনা", "New purchase") else tr("কেনা সম্পাদনা", "Edit purchase"), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = {
        val itemsJson = buildJsonArray {
            named.forEach { l ->
                add(buildJsonObject {
                    put("name", l.name.trim())
                    put("qty", l.qty.toDoubleOrNull() ?: 0.0)
                    put("price", parseAmount(l.price) ?: 0.0)
                })
            }
        }
        vm.save(
            "credit_entries",
            row(
                "shop_id" to shopId,
                "item_name" to named.joinToString(", ") { it.name.trim() },
                "quantity" to 1,
                "unit_price" to total,
                "items" to itemsJson,
                "adjustment" to adj,
                "total" to total,
                "purchase_date" to date.trim(),
                "due_date" to (nonEmpty(due)?.let { JsonPrimitive(it) } ?: JsonNull),
                "note" to note.trim()
            ),
            existing?.id()
        )
        onDismiss()
    }) {
        lines.forEachIndexed { i, l ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextIn(tr("আইটেমের নাম (যেমন: মালদা)", "Item name (e.g. Malda mango)"), l.name, { lines[i] = l.copy(name = it) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextIn(tr("পরিমাণ", "Amount"), l.qty, { lines[i] = l.copy(qty = it) }, number = true, modifier = Modifier.weight(1f))
                    TextIn(tr("একক দাম (৳)", "Unit price (৳)"), l.price, { lines[i] = l.copy(price = it) }, number = true, modifier = Modifier.weight(1f))
                    if (lines.size > 1) DeleteButton { lines.removeAt(i) }
                }
                if (l.name.isNotBlank()) Text("= ${taka(lineTotal(l), false)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AddButton(tr("আইটেম যোগ", "Add item")) { lines.add(ItemDraft()) }
        TextIn(tr("সমন্বয় (+ ডেলিভারি, − ছাড়; না থাকলে খালি)", "Adjustment (+ delivery, − discount; empty if none)"), adjust, { adjust = it })
        Text(tr("মোট: ${taka(total, false)}", "Total: ${taka(total, false)}"), fontWeight = FontWeight.Bold)
        if (total < 0) Text(tr("মোট ঋণাত্মক হতে পারে না", "Total cannot be negative"), fontSize = 12.sp, color = readableOn(BakiRed))
        DateField(tr("কেনার তারিখ", "Purchase date"), date, { date = it })
        DateField(tr("শেষ তারিখ (ঐচ্ছিক)", "Due date (optional)"), due, { due = it }, optional = true)
        TextIn(tr("নোট (ঐচ্ছিক)", "Note (optional)"), note, { note = it })
    }
}

// ---------- Payment (কিস্তি) ----------

@Composable
private fun PaymentDialog(shopId: String, shopName: String, balance: Double, vm: AppViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayIso()) }
    var note by remember { mutableStateOf("") }
    val a = parseAmount(amount)
    val tooMuch = a != null && a > balance + EPS
    val valid = a != null && a > 0 && !tooMuch && isValidIsoDate(date) && balance > EPS

    FormDialog(tr("পরিশোধ / কিস্তি", "Payment / instalment"), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = {
        if (a != null) {
            vm.recordDebtPayment(shopName, a, date.trim(), note.trim(), shopId)
            onDismiss()
        }
    }) {
        Text(tr("বর্তমান বাকি: ${taka(balance, false)}", "Current due: ${taka(balance, false)}"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        TextIn(tr("পরিমাণ (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
        if (tooMuch) Text(tr("বাকির বেশি টাকা দেওয়া যাবে না", "Payment cannot exceed the balance due"), fontSize = 12.sp, color = readableOn(BakiRed))
        DateField(tr("তারিখ", "Date"), date, { date = it })
        TextIn(tr("নোট (ঐচ্ছিক)", "Note (optional)"), note, { note = it })
    }
}

private class ShopStat(val balance: Double, val open: Int, val overdue: Int)
