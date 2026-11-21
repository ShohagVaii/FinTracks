package com.fintracks.app.ui

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

/*
 * বাকি খাতা: দোকান → কেনা (একাধিক আইটেম + ± সমন্বয়) → পরিশোধের কিস্তি।
 * সব এন্ট্রি ম্যানুয়াল। ধার/লোন থেকে আলাদা। পরিশোধ বাকির বেশি হলে আটকে দেওয়া হয়।
 * পরিশোধ পুরোনো কেনা থেকে ক্রমানুসারে মেটে (FIFO), তাই প্রতিটি কেনার অবস্থা জানা যায়।
 */

private val BakiRed = Color(0xFFF43F5E)
private val BakiGreen = Color(0xFF10B981)
private val BakiAmber = Color(0xFFF59E0B)
private val BakiSky = Color(0xFF38BDF8)
private const val EPS = 0.005

enum class BakiStatus(val label: String, val color: Color) {
    ONGOING("চলমান", BakiSky),
    PARTIAL("আংশিক পরিশোধ", BakiAmber),
    SETTLED("শোধ", BakiGreen)
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
private fun settle(shopId: String, state: UiState): Map<String, Settlement> {
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
    return state.rows("credit_shops").sumOf { s -> settle(s.id(), state).values.count { it.overdue(today) } }
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
                Text(taka(due, p), fontSize = 22.sp, fontWeight = FontWeight.Black, color = BakiRed)
                Text(tr("দোকানের বাকি · ধার/লোন থেকে আলাদা", "Shop credit · separate from loans"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (overdue > 0) Text(tr("$overdue টি মেয়াদোত্তীর্ণ", "$overdue overdue"), color = BakiRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Shop list ----------

@Composable
fun BakiScreen(state: UiState, vm: AppViewModel) {
    var selected by remember { mutableStateOf<String?>(null) }
    var addingShop by remember { mutableStateOf(false) }
    val shop = selected?.let { id -> state.rows("credit_shops").firstOrNull { it.id() == id } }
    if (shop != null) {
        ShopDetail(shop, state, vm) { selected = null }
        return
    }
    val p = state.privacy
    val shops = state.rows("credit_shops")
    val totalDue = shops.sumOf { shopBalance(it.id(), state) }
    val overdue = bakiOverdueCount(state)

    Page {
        AppCard {
            Text(tr("মোট বাকি দেনা", "Total credit due"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(taka(totalDue, p), fontSize = 26.sp, fontWeight = FontWeight.Black, color = BakiRed)
            if (overdue > 0) Text("$overdue টি কেনার শেষ তারিখ পার হয়ে গেছে", fontSize = 12.sp, color = BakiRed)
            Text("ধার/লোন থেকে আলাদা হিসাব। দোকান ও ব্যক্তির বাকি এখানে।", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AddButton("নতুন দোকান") { addingShop = true }
        if (shops.isEmpty()) EmptyHint("কোনো দোকান নেই। দোকান যোগ করে কেনা ও বাকি তুলুন।")
        shops.forEach { s ->
            val open = settle(s.id(), state).values.count { it.status != BakiStatus.SETTLED }
            AppCard(onClick = { selected = s.id() }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(s.str("name"), fontWeight = FontWeight.Bold)
                        val sub = listOfNotNull(
                            nonEmpty(s.str("phone")),
                            if (open > 0) "$open টি কেনা খোলা আছে" else "সব কেনা শোধ"
                        ).joinToString(" · ")
                        Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(taka(shopBalance(s.id(), state), p), fontWeight = FontWeight.Bold, color = BakiRed)
                }
            }
        }
    }

    if (addingShop) {
        ShopDialog(null, vm) { addingShop = false }
    }
}

@Composable
private fun ShopDialog(existing: JsonObject?, vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.str("name").orEmpty()) }
    var phone by remember { mutableStateOf(existing?.str("phone").orEmpty()) }
    var note by remember { mutableStateOf(existing?.str("note").orEmpty()) }
    FormDialog(if (existing == null) "নতুন দোকান" else "দোকান সম্পাদনা", confirmEnabled = name.isNotBlank(), onDismiss = onDismiss, onConfirm = {
        vm.save("credit_shops", row("name" to name.trim(), "phone" to phone.trim(), "note" to note.trim()), existing?.id())
        onDismiss()
    }) {
        TextIn("দোকান / ব্যক্তির নাম", name, { name = it })
        TextIn("ফোন (ঐচ্ছিক)", phone, { phone = it })
        TextIn("নোট (ঐচ্ছিক)", note, { note = it })
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
    val settled = settle(shop.id(), state)
    val bought = purchases.sumOf { it.num("total") }
    val paid = payments.sumOf { it.num("amount") }
    val balance = bought - paid

    Page {
        OutlinedButton(onClick = onBack) { Text("← সব দোকান") }
        AppCard {
            Text(shop.str("name"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (shop.str("phone").isNotBlank()) Text(shop.str("phone"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KeyValue("বাকি আছে", taka(balance, p), if (balance > EPS) BakiRed else BakiGreen)
            KeyValue("মোট কেনা", taka(bought, p))
            KeyValue("মোট পরিশোধ", taka(paid, p))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addingPurchase = true }) { Text("নতুন কেনা") }
                OutlinedButton(onClick = { addingPayment = true }, enabled = balance > EPS) { Text("পরিশোধ") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editingShop = true }) { Text("দোকান সম্পাদনা") }
                DeleteButton { confirmDelete = true }
            }
        }

        SectionTitle("কেনার তালিকা")
        if (purchases.isEmpty()) EmptyHint("এখনো কোনো কেনা নেই")
        purchases.forEach { e ->
            val st = settled[e.id()]
            val status = st?.status ?: BakiStatus.ONGOING
            val overdue = st?.overdue(today) == true
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(e.str("item_name"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    StatusChip(if (overdue) "মেয়াদোত্তীর্ণ" else status.label, if (overdue) BakiRed else status.color)
                }
                val lines = purchaseLines(e)
                lines.forEach { l ->
                    Text(
                        "• ${l.name} · ${l.qty} × ${taka(parseAmount(l.price) ?: 0.0, p)} = ${taka(lineTotal(l), p)}",
                        fontSize = 12.sp
                    )
                }
                val adj = e.num("adjustment")
                if (adj != 0.0) Text("সমন্বয় (± ডেলিভারি/ছাড়): ${if (adj > 0) "+" else ""}${taka(adj, p)}", fontSize = 12.sp)
                KeyValue("মোট", taka(e.num("total"), p))
                KeyValue("পরিশোধ", taka(st?.paid ?: 0.0, p), BakiGreen)
                KeyValue("বাকি", taka(st?.remaining ?: e.num("total"), p), if ((st?.remaining ?: 0.0) > EPS) BakiRed else BakiGreen)
                Text(
                    tr("কেনা", "Bought") + ": ${displayDate(dateOf(e, "purchase_date"))}" + (e.strOrNull("due_date")?.take(10)?.let { " · " + tr("শেষ তারিখ", "Due") + ": ${displayDate(it)}" } ?: ""),
                    fontSize = 11.sp,
                    color = if (overdue) BakiRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (e.str("note").isNotBlank()) Text(e.str("note"), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { editingPurchase = e }) { Text("সম্পাদনা") }
                    DeleteButton { vm.remove("credit_entries", e.id()) }
                }
            }
        }

        SectionTitle("পরিশোধের ইতিহাস")
        if (payments.isEmpty()) EmptyHint("এখনো কোনো পরিশোধ নেই")
        payments.sortedByDescending { it.str("paid_on") }.forEach { pay ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(taka(pay.num("amount"), p), fontWeight = FontWeight.Bold, color = BakiGreen)
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
            title = { Text("দোকানটি মুছবেন?", fontWeight = FontWeight.Bold) },
            text = { Text("এই দোকানের সব কেনা ও পরিশোধের হিসাব মুছে যাবে। এটি ফেরানো যাবে না।") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.remove("credit_shops", shop.id()); onBack() }) { Text("মুছুন") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("বাতিল") } }
        )
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

    FormDialog(if (existing == null) "নতুন কেনা" else "কেনা সম্পাদনা", confirmEnabled = valid, onDismiss = onDismiss, onConfirm = {
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
                TextIn("আইটেমের নাম (যেমন: মালদা)", l.name, { lines[i] = l.copy(name = it) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextIn("পরিমাণ", l.qty, { lines[i] = l.copy(qty = it) }, number = true, modifier = Modifier.weight(1f))
                    TextIn("একক দাম (৳)", l.price, { lines[i] = l.copy(price = it) }, number = true, modifier = Modifier.weight(1f))
                    if (lines.size > 1) DeleteButton { lines.removeAt(i) }
                }
                if (l.name.isNotBlank()) Text("= ${taka(lineTotal(l), false)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AddButton("আইটেম যোগ") { lines.add(ItemDraft()) }
        TextIn("সমন্বয় (+ ডেলিভারি, − ছাড়; না থাকলে খালি)", adjust, { adjust = it })
        Text("মোট: ${taka(total, false)}", fontWeight = FontWeight.Bold)
        if (total < 0) Text("মোট ঋণাত্মক হতে পারে না", fontSize = 12.sp, color = BakiRed)
        TextIn("কেনার তারিখ (YYYY-MM-DD)", date, { date = it })
        TextIn("শেষ তারিখ (ঐচ্ছিক, YYYY-MM-DD)", due, { due = it })
        TextIn("নোট (ঐচ্ছিক)", note, { note = it })
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

    FormDialog("পরিশোধ / কিস্তি", confirmEnabled = valid, onDismiss = onDismiss, onConfirm = {
        if (a != null) {
            vm.recordDebtPayment(shopName, a, date.trim(), note.trim(), shopId)
            onDismiss()
        }
    }) {
        Text("বর্তমান বাকি: ${taka(balance, false)}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        TextIn("পরিমাণ (৳)", amount, { amount = it }, number = true)
        if (tooMuch) Text("বাকির বেশি টাকা দেওয়া যাবে না", fontSize = 12.sp, color = BakiRed)
        TextIn("তারিখ (YYYY-MM-DD)", date, { date = it })
        TextIn("নোট (ঐচ্ছিক)", note, { note = it })
    }
}
