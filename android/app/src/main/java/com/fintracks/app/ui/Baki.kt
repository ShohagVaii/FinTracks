package com.fintracks.app.ui

import java.time.YearMonth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import com.fintracks.app.ui.design.LimitBar
import com.fintracks.app.ui.design.StampLabel
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
    val ctx = LocalContext.current
    val creditDues = shops.flatMap { sh ->
        settleShop(sh.id(), state).mapNotNull { (id, st) ->
            val due = st.dueDate
            if (due == null || st.remaining <= EPS) null else DebtDue(id, due, sh.str("name"), false)
        }
    }
    LaunchedEffect(creditDues.toString()) { DebtReminders.sync(ctx, creditDues, "credit") }
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
            val cat = shopCategory(s.str("category"))
            val limit = s.num("credit_limit")
            AppCard(onClick = { go("ov:CREDIT/${s.id()}") }) {
                Awning(cat.color)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(cat.color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Text(cat.emoji, fontSize = 20.sp)
                    }
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
                        if (st.balance > 0.005) TextButton(onClick = { paying = s }) { Text(tr("শোধ", "Pay")) }
                    }
                }
                if (limit > 0) {
                    Spacer(Modifier.height(8.dp))
                    LimitBar((st.balance / limit).toFloat())
                    Text(tr("সীমা ", "Limit ") + taka(limit, p), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ShopReports(state)
        ShopDonut(shops, stats, p)
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
    var category by remember { mutableStateOf(shopCategory(existing?.str("category").orEmpty()).key) }
    var limit by remember { mutableStateOf(existing?.num("credit_limit")?.takeIf { it > 0 }?.let { fmtNum(it) }.orEmpty()) }
    val limitValid = limit.isBlank() || (parseAmount(limit) ?: -1.0) > 0
    FormDialog(
        if (existing == null) tr("নতুন দোকান", "New shop") else tr("দোকান সম্পাদনা", "Edit shop"),
        confirmEnabled = name.isNotBlank() && limitValid, onDismiss = onDismiss,
        onConfirm = {
            vm.save(
                "credit_shops",
                row(
                    "name" to name.trim(), "phone" to phone.trim(), "note" to note.trim(), "category" to category,
                    "credit_limit" to (parseAmount(limit)?.takeIf { it > 0 }?.let { JsonPrimitive(it) } ?: JsonNull)
                ),
                existing?.id()
            )
            onDismiss()
        }
    ) {
        TextIn(tr("দোকান / ব্যক্তির নাম", "Shop / person name"), name, { name = it })
        TextIn(tr("ফোন (ঐচ্ছিক)", "Phone (optional)"), phone, { phone = it })
        TextIn(tr("নোট (ঐচ্ছিক)", "Note (optional)"), note, { note = it })
        TextIn(tr("ক্রেডিট-সীমা (৳, ঐচ্ছিক)", "Credit limit (৳, optional)"), limit, { limit = it }, number = true)
        shopCategories.chunked(4).forEach { row4 ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row4.forEach { c -> FilterPill("${c.emoji} ${tr(c.bn, c.en)}", category == c.key) { category = c.key } }
            }
        }
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
    var sharing by remember { mutableStateOf(false) }
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
                if (balance <= EPS) Box(Modifier.popIn()) { StampLabel(tr("শোধ", "Settled"), BakiGreen) }
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
                TextButton(modifier = Modifier.pressScale(), onClick = { sharing = true }) { Text(tr("খতিয়ান শেয়ার", "Share statement")) }
                DeleteButton { confirmDelete = true }
            }
        }

        SectionTitle(tr("খাতার পাতা", "Ledger"))
        val lines = ledgerLines(purchases, payments, settled, today)
        if (lines.isEmpty()) EmptyHint(tr("এখনো কোনো কেনা বা পরিশোধ নেই", "No purchases or payments yet"))
        val paperLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        Column(
            Modifier.fillMaxWidth().ledgerPaper(paperLine).padding(start = 36.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            lines.forEach { l ->
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        if (l.purchase) l.row.strOrNull("attachment_url")?.let { path ->
                            AttachmentThumb(AttachmentStore.ref("credit_entries", l.row.id()), path)
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(displayDate(l.date), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (l.purchase) {
                                Text(l.row.str("item_name"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                purchaseLines(l.row).forEach { ln ->
                                    Text(
                                        "• ${ln.name} · ${ln.qty} × ${taka(parseAmount(ln.price) ?: 0.0, p)} = ${taka(lineTotal(ln), p)}",
                                        fontSize = 11.sp
                                    )
                                }
                                val adj = l.row.num("adjustment")
                                if (adj != 0.0) Text(tr("সমন্বয়", "Adjustment") + ": ${if (adj > 0) "+" else ""}${taka(adj, p)}", fontSize = 11.sp)
                                val st = settled[l.row.id()]
                                Text(tr("বাকি", "Due") + ": " + taka(st?.remaining ?: l.row.num("total"), p), fontSize = 11.sp)
                                l.row.strOrNull("due_date")?.take(10)?.let {
                                    Text(tr("শেষ তারিখ", "Due date") + ": " + displayDate(it), fontSize = 11.sp,
                                        color = if (st?.overdue(today) == true) BakiRed else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                Text(tr("পরিশোধ", "Payment"), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = readableOn(BakiGreen))
                                l.note?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            if (l.row.str("note").isNotBlank()) Text(l.row.str("note"), fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                (if (l.purchase) "+" else "−") + taka(l.amount, p),
                                fontWeight = FontWeight.Bold,
                                color = if (l.purchase) readableOn(BakiRed) else readableOn(BakiGreen)
                            )
                            Text(
                                tr("জের", "Balance") + ": " + taka(l.balance, p),
                                fontSize = 11.sp,
                                color = if (l.balance > EPS) readableOn(BakiRed) else readableOn(BakiGreen)
                            )
                            l.stamp?.let { (text, color) -> Spacer(Modifier.height(4.dp)); StampLabel(text, color) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (l.purchase) TextButton(modifier = Modifier.pressScale(), onClick = { editingPurchase = l.row }) { Text(tr("সম্পাদনা", "Edit")) }
                        if (l.purchase) TextButton(modifier = Modifier.pressScale(), onClick = { repeatPurchase(vm, l.row) }) { Text(tr("আবার একই", "Repeat")) }
                        DeleteButton { vm.remove(if (l.purchase) "credit_entries" else "credit_payments", l.row.id()) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (addingPurchase) PurchaseDialog(shop.id(), null, vm) { addingPurchase = false }
    editingPurchase?.let { PurchaseDialog(shop.id(), it, vm) { editingPurchase = null } }
    if (addingPayment) PaymentDialog(shop.id(), shop.str("name"), balance, vm) { addingPayment = false }
    if (editingShop) ShopDialog(shop, vm) { editingShop = false }
    if (sharing) ShopStatementDialog(tr("খতিয়ান: ", "Statement: ") + shop.str("name"), shopStatementLines(shop, state, settled)) { sharing = false }
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
    val ctx = LocalContext.current
    val rowId = remember { existing?.id() ?: java.util.UUID.randomUUID().toString() }
    val photoRef = AttachmentStore.ref("credit_entries", rowId)
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photo = AttachmentStore.readImage(ctx, uri)
    }

    val adj = parseAmount(adjust) ?: 0.0
    val adjValid = adjust.isBlank() || parseAmount(adjust) != null
    val named = lines.filter { it.name.isNotBlank() }
    val linesValid = named.isNotEmpty() && named.all {
        (it.qty.toDoubleOrNull() ?: 0.0) > 0 && (parseAmount(it.price) ?: -1.0) >= 0
    }
    val total = named.sumOf { lineTotal(it) } + adj
    val valid = linesValid && adjValid && total >= 0 && isValidIsoDate(date) && (due.isBlank() || isValidIsoDate(due))

    FormDialog(if (existing == null) tr("নতুন কেনা", "New purchase") else tr("কেনা সম্পাদনা", "Edit purchase"), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = {
        photo?.let { AttachmentStore.stage(ctx, photoRef, it) }
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
                "id" to rowId,
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
        TextButton(modifier = Modifier.pressScale(), onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text(if (photo != null || existing?.strOrNull("attachment_url") != null) tr("ছবি বদলান", "Change photo") else tr("ছবি যোগ করুন", "Add photo"))
        }
        if (photo != null) Text(tr("ছবি যোগ হয়েছে", "Photo added"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else existing?.strOrNull("attachment_url")?.let { AttachmentThumb(photoRef, it) }
    }
}

// ---------- Payment (কিস্তি) ----------

@Composable
private fun PaymentDialog(shopId: String, shopName: String, balance: Double, vm: AppViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayIso()) }
    var note by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("Cash") }
    val a = parseAmount(amount)
    val tooMuch = a != null && a > balance + EPS
    val valid = a != null && a > 0 && !tooMuch && isValidIsoDate(date) && balance > EPS

    FormDialog(tr("পরিশোধ / কিস্তি", "Payment / instalment"), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = {
        if (a != null) {
            vm.recordDebtPayment(shopName, a, date.trim(), note.trim(), shopId, method)
            onDismiss()
        }
    }) {
        Text(tr("বর্তমান বাকি: ${taka(balance, false)}", "Current due: ${taka(balance, false)}"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        TextIn(tr("পরিমাণ (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
        if (tooMuch) Text(tr("বাকির বেশি টাকা দেওয়া যাবে না", "Payment cannot exceed the balance due"), fontSize = 12.sp, color = readableOn(BakiRed))
        DateField(tr("তারিখ", "Date"), date, { date = it })
        TextIn(tr("নোট (ঐচ্ছিক)", "Note (optional)"), note, { note = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Cash" to tr("ক্যাশ", "Cash"), "Bkash" to tr("বিকাশ", "Bkash"), "Nagad" to tr("নগদ", "Nagad"), "Bank" to tr("ব্যাংক", "Bank"))
                .forEach { (key, label) -> FilterPill(label, method == key) { method = key } }
        }
    }
}

private class ShopStat(val balance: Double, val open: Int, val overdue: Int)

private class LedgerLine(
    val date: String,
    val purchase: Boolean,
    val row: JsonObject,
    val amount: Double,
    val balance: Double,
    val stamp: Pair<String, Color>?,
    val note: String?
)

/** One mixed timeline: purchases and payments by date, with a running balance (জের). */
private fun ledgerLines(
    purchases: List<JsonObject>,
    payments: List<JsonObject>,
    settled: Map<String, Settlement>,
    today: String
): List<LedgerLine> {
    val fifo = purchases.sortedWith(compareBy<JsonObject>({ it.str("purchase_date") }, { it.str("created_at") }))
    val raw = purchases.map { Triple(it.str("purchase_date").take(10), it, true) } +
        payments.map { Triple(it.str("paid_on").take(10), it, false) }
    val sorted = raw.sortedWith(compareBy<Triple<String, JsonObject, Boolean>>(
        { it.first }, { if (it.third) 0 else 1 }, { it.second.str("created_at") }
    ))
    val out = ArrayList<LedgerLine>()
    var bal = 0.0
    var cumPaid = 0.0
    for ((date, row, isPurchase) in sorted) {
        if (isPurchase) {
            val total = row.num("total")
            bal += total
            val st = settled[row.id()]
            val stamp: Pair<String, Color>? = when {
                st?.overdue(today) == true -> tr("মেয়াদ-পার", "Overdue") to BakiRed
                st?.status == BakiStatus.SETTLED -> tr("শোধ", "Settled") to BakiGreen
                st?.status == BakiStatus.PARTIAL -> tr("আংশিক", "Partly") to BakiAmber
                else -> null
            }
            out += LedgerLine(date, true, row, total, bal, stamp, null)
        } else {
            val amount = row.num("amount")
            // FIFO note: the purchase where this payment's money starts landing.
            var end = 0.0
            var note: String? = null
            for (e in fifo) {
                end += e.num("total")
                if (end > cumPaid + EPS) {
                    note = tr("প্রথমে ${displayDate(e.str("purchase_date").take(10))}-এর কেনায় বসেছে",
                        "Applied first to the purchase of ${displayDate(e.str("purchase_date").take(10))}")
                    break
                }
            }
            cumPaid += amount
            bal -= amount
            out += LedgerLine(date, false, row, amount, bal, null, note)
        }
    }
    return out
}

/** Faint ruled lines every 32dp with a red margin, drawn behind the ledger column. */
private fun Modifier.ledgerPaper(lineColor: Color): Modifier = this.drawBehind {
    val step = 32.dp.toPx()
    val margin = 28.dp.toPx()
    var y = step
    while (y < size.height) {
        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
    drawLine(BakiRed.copy(alpha = 0.25f), Offset(margin, 0f), Offset(margin, size.height), strokeWidth = 2f)
}

private class ShopCategory(val key: String, val bn: String, val en: String, val emoji: String, val color: Color)

private val shopCategories = listOf(
    ShopCategory("grocery", "মুদি", "Grocery", "🛒", Color(0xFFF59E0B)),
    ShopCategory("pharmacy", "ফার্মেসি", "Pharmacy", "💊", Color(0xFF10B981)),
    ShopCategory("dairy", "দুধ-ডিম", "Dairy", "🥛", Color(0xFF0EA5E9)),
    ShopCategory("veg", "সবজি-ফল", "Veg & fruit", "🥬", Color(0xFF84CC16)),
    ShopCategory("bill", "ভাড়া/বিল", "Rent/Bill", "🧾", Color(0xFF6366F1)),
    ShopCategory("tea", "চা-নাস্তা", "Tea & snacks", "☕", Color(0xFFF97316)),
    ShopCategory("other", "অন্যান্য", "Other", "🏪", Color(0xFF64748B))
)

private fun shopCategory(key: String): ShopCategory = shopCategories.firstOrNull { it.key == key } ?: shopCategories.last()

/** Awning stripe along the top of a shop card. */
@Composable
private fun Awning(color: Color) {
    Canvas(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))) {
        val step = 14.dp.toPx()
        var x = 0f
        var i = 0
        while (x < size.width) {
            drawRect(color = if (i % 2 == 0) color else color.copy(alpha = 0.35f), topLeft = Offset(x, 0f), size = Size(step, size.height))
            x += step
            i++
        }
    }
}

/** Repeats a purchase as a new one dated today: same items, totals and adjustment. */
private fun repeatPurchase(vm: AppViewModel, e: JsonObject) {
    val drop = setOf("id", "created_at", "user_id", "purchase_date", "due_date", "attachment_url")
    val body = JsonObject(e.filterKeys { it !in drop } + mapOf("purchase_date" to JsonPrimitive(todayIso())))
    vm.save("credit_entries", body, null)
}

/** Plain lines of a shop's khata page, for the statement share. */
private fun shopStatementLines(shop: JsonObject, state: UiState, settled: Map<String, Settlement>): List<String> {
    val purchases = state.rows("credit_entries").filter { it.str("shop_id") == shop.id() }
    val payments = state.rows("credit_payments").filter { it.str("shop_id") == shop.id() }
    val out = mutableListOf(tr("খতিয়ান: ", "Statement: ") + shop.str("name"))
    ledgerLines(purchases, payments, settled, todayIso()).forEach { l ->
        val what = if (l.purchase) l.row.str("item_name") else tr("পরিশোধ", "Payment")
        val sign = if (l.purchase) "+" else "−"
        out += "${displayDate(l.date)} · $what · $sign${taka(l.amount)} · " + tr("জের", "Balance") + " ${taka(l.balance)}" +
            (l.note?.let { " · $it" } ?: "")
    }
    return out
}

/** Last six months: purchases (rose) against payments (emerald). */
@Composable
private fun ShopReports(state: UiState) {
    val months = (5 downTo 0).map { YearMonth.now().minusMonths(it.toLong()).toString() }
    val entries = state.rows("credit_entries")
    val pays = state.rows("credit_payments")
    val bought = months.map { m -> entries.filter { it.str("purchase_date").startsWith(m) }.sumOf { it.num("total") } }
    val paid = months.map { m -> pays.filter { it.str("paid_on").startsWith(m) }.sumOf { it.num("amount") } }
    val max = (bought + paid).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    GlassCard(Modifier.fillMaxWidth()) {
        Text(tr("মাসভিত্তিক কেনা ও শোধ", "Bought vs paid by month"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            months.indices.forEach { i ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.height(100.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.width(8.dp).height((100.0 * bought[i] / max).toFloat().dp).clip(RoundedCornerShape(4.dp)).background(BakiRed))
                        Box(Modifier.width(8.dp).height((100.0 * paid[i] / max).toFloat().dp).clip(RoundedCornerShape(4.dp)).background(BakiGreen))
                    }
                    Text(bnDigits(months[i].substring(5)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Donut of the balance by shop; the legend lists the first six. */
@Composable
private fun ShopDonut(shops: List<JsonObject>, stats: Map<String, ShopStat>, p: Boolean) {
    val due = shops.map { it to (stats[it.id()]?.balance ?: 0.0) }.filter { it.second > EPS }
    val total = due.sumOf { it.second }
    if (total <= EPS) return
    val palette = listOf(BakiSky, BakiGreen, BakiAmber, BakiRed, Color(0xFF8B5CF6), Color(0xFF14B8A6))
    GlassCard(Modifier.fillMaxWidth()) {
        Text(tr("দোকানভিত্তিক বাকি", "Due by shop"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Canvas(Modifier.size(120.dp)) {
                var start = -90f
                due.forEachIndexed { i, pair ->
                    val sweep = (pair.second / total * 360.0).toFloat()
                    drawArc(
                        color = palette[i % palette.size], startAngle = start, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(11f, 11f), size = Size(size.width - 22f, size.height - 22f), style = Stroke(width = 22f)
                    )
                    start += sweep
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                due.take(6).forEachIndexed { i, pair ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(palette[i % palette.size]))
                        Text(pair.first.str("name"), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
                        Text(taka(pair.second, p), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
