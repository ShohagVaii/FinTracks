package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil

private val green = Color(0xFF10B981)
private val red = Color(0xFFF43F5E)

/** Column wrapper with the standard padding used by every tool screen. */
@Composable
fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { content() }
}

// ======================= বাকি (দোকানের হিসাব) =======================

/** Shop-wise running balance: total items bought minus total paid. */
fun shopBalance(shopId: String, state: UiState): Double {
    val bought = state.rows("credit_entries").filter { it.str("shop_id") == shopId }.sumOf { it.num("quantity") * it.num("unit_price") }
    val paid = state.rows("credit_payments").filter { it.str("shop_id") == shopId }.sumOf { it.num("amount") }
    return bought - paid
}

@Composable
fun CreditScreen(state: UiState, vm: AppViewModel) {
    var selected by remember { mutableStateOf<String?>(null) }
    var addingShop by remember { mutableStateOf(false) }
    val shop = selected?.let { id -> state.rows("credit_shops").firstOrNull { it.id() == id } }
    val p = state.privacy

    if (shop != null) {
        ShopDetail(shop, state, vm) { selected = null }
        return
    }
    Page {
        val totalDue = state.rows("credit_shops").sumOf { shopBalance(it.id(), state) }
        AppCard {
            Text("মোট বাকি", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(taka(totalDue, p), fontSize = 26.sp, fontWeight = FontWeight.Black, color = red)
            Text("ধার নেওয়া দোকান, আইটেম, পরিশোধ আলাদাভাবে ট্র্যাক হয়। ধার (টাকা ধার) থেকে আলাদা।", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AddButton("নতুন দোকান") { addingShop = true }
        val shops = state.rows("credit_shops")
        if (shops.isEmpty()) EmptyHint("কোনো দোকান নেই")
        shops.forEach { s ->
            AppCard(onClick = { selected = s.id() }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(s.str("name"), fontWeight = FontWeight.Bold)
                        if (s.str("phone").isNotBlank()) Text(s.str("phone"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(taka(shopBalance(s.id(), state), p), fontWeight = FontWeight.Bold, color = red)
                }
            }
        }
    }

    if (addingShop) {
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        FormDialog("নতুন দোকান", onDismiss = { addingShop = false }, onConfirm = {
            if (name.isNotBlank()) { vm.save("credit_shops", row("name" to name, "phone" to phone, "note" to note), null); addingShop = false }
        }) {
            TextIn("দোকানের নাম", name, { name = it })
            TextIn("ফোন", phone, { phone = it })
            TextIn("নোট", note, { note = it })
        }
    }
}

@Composable
private fun ShopDetail(shop: JsonObject, state: UiState, vm: AppViewModel, onBack: () -> Unit) {
    var addingItem by remember { mutableStateOf(false) }
    var addingPayment by remember { mutableStateOf(false) }
    val p = state.privacy
    val entries = state.rows("credit_entries").filter { it.str("shop_id") == shop.id() }
    val payments = state.rows("credit_payments").filter { it.str("shop_id") == shop.id() }
    val balance = shopBalance(shop.id(), state)
    val today = todayIso()

    Page {
        OutlinedButton(onClick = onBack) { Text("← সব দোকান") }
        AppCard {
            Text(shop.str("name"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            KeyValue("বাকি আছে", taka(balance, p), if (balance > 0) red else green)
            KeyValue("মোট কেনা", taka(entries.sumOf { it.num("quantity") * it.num("unit_price") }, p))
            KeyValue("মোট পরিশোধ", taka(payments.sumOf { it.num("amount") }, p))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addingItem = true }) { Text("আইটেম যোগ") }
                OutlinedButton(onClick = { addingPayment = true }) { Text("পরিশোধ") }
                DeleteButton { vm.remove("credit_shops", shop.id()); onBack() }
            }
        }
        SectionTitle("কেনা আইটেম")
        if (entries.isEmpty()) EmptyHint("এখনো কোনো আইটেম নেই")
        entries.sortedByDescending { it.str("purchase_date") }.forEach { e ->
            val total = e.num("quantity") * e.num("unit_price")
            val overdue = e.strOrNull("due_date")?.let { it < today } == true
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(e.str("item_name"), fontWeight = FontWeight.Bold)
                    DeleteButton { vm.remove("credit_entries", e.id()) }
                }
                Text("${fmtNum(e.num("quantity"))} × ${taka(e.num("unit_price"), p)} = ${taka(total, p)}", fontSize = 13.sp)
                Text(
                    "কেনা: ${dateOf(e, "purchase_date")}" + (e.strOrNull("due_date")?.let { " · শেষ তারিখ: $it" } ?: ""),
                    fontSize = 11.sp,
                    color = if (overdue) red else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (e.str("note").isNotBlank()) Text(e.str("note"), fontSize = 11.sp)
            }
        }
        SectionTitle("পরিশোধের ইতিহাস")
        if (payments.isEmpty()) EmptyHint("এখনো কোনো পরিশোধ নেই")
        payments.sortedByDescending { it.str("paid_on") }.forEach { pay ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(taka(pay.num("amount"), p), fontWeight = FontWeight.Bold, color = green)
                        Text(dateOf(pay, "paid_on") + (if (pay.str("note").isNotBlank()) " · ${pay.str("note")}" else ""), fontSize = 11.sp)
                    }
                    DeleteButton { vm.remove("credit_payments", pay.id()) }
                }
            }
        }
    }

    if (addingItem) {
        var item by remember { mutableStateOf("") }
        var qty by remember { mutableStateOf("1") }
        var price by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(todayIso()) }
        var due by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        FormDialog("আইটেম যোগ করুন", onDismiss = { addingItem = false }, onConfirm = {
            val q = qty.toDoubleOrNull(); val pr = parseAmount(price)
            if (item.isNotBlank() && q != null && q > 0 && pr != null && pr >= 0 && isValidIsoDate(date) && (due.isBlank() || isValidIsoDate(due))) {
                vm.save("credit_entries", row(
                    "shop_id" to shop.id(), "item_name" to item, "quantity" to q, "unit_price" to pr,
                    "purchase_date" to date, "due_date" to nonEmpty(due), "note" to note
                ), null)
                addingItem = false
            }
        }) {
            TextIn("আইটেমের নাম (যেমন: মালদা)", item, { item = it })
            TextIn("পরিমাণ", qty, { qty = it }, number = true)
            TextIn("একক দাম (৳)", price, { price = it }, number = true)
            TextIn("কেনার তারিখ (YYYY-MM-DD)", date, { date = it })
            TextIn("শেষ তারিখ (ঐচ্ছিক)", due, { due = it })
            TextIn("নোট", note, { note = it })
            Text("মোট: ${taka((qty.toDoubleOrNull() ?: 0.0) * (parseAmount(price) ?: 0.0), false)}", fontWeight = FontWeight.Bold)
        }
    }

    if (addingPayment) {
        var amount by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(todayIso()) }
        var note by remember { mutableStateOf("") }
        FormDialog("আংশিক/সম্পূর্ণ পরিশোধ", onDismiss = { addingPayment = false }, onConfirm = {
            val a = parseAmount(amount)
            if (a != null && a > 0 && isValidIsoDate(date)) {
                vm.save("credit_payments", row("shop_id" to shop.id(), "amount" to a, "paid_on" to date, "note" to note), null)
                addingPayment = false
            }
        }) {
            Text("বর্তমান বাকি: ${taka(balance, false)}", fontSize = 12.sp)
            TextIn("পরিমাণ (৳)", amount, { amount = it }, number = true)
            TextIn("তারিখ (YYYY-MM-DD)", date, { date = it })
            TextIn("নোট", note, { note = it })
        }
    }
}

// ======================= ধার ও পাওনা =======================

@Composable
fun DebtsScreen(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val debts = state.rows("debts")
    val p = state.privacy
    val owe = debts.filter { !it.bool("settled") && it.str("direction") == "owe" }.sumOf { it.num("amount") }
    val owed = debts.filter { !it.bool("settled") && it.str("direction") == "owed" }.sumOf { it.num("amount") }
    Page {
        AppCard {
            KeyValue("আমি ধারি", taka(owe, p), red)
            KeyValue("আমার পাওনা", taka(owed, p), green)
        }
        DebtPlanner(debts, p)
        AddButton("নতুন ধার / পাওনা") { adding = true }
        if (debts.isEmpty()) EmptyHint("কোনো ধার বা পাওনা নেই")
        debts.forEach { d ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(d.str("person"), fontWeight = FontWeight.Bold)
                        Text(if (d.str("direction") == "owe") "আমি ধারি" else "আমার পাওনা", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(taka(d.num("amount"), p), fontWeight = FontWeight.Bold, color = if (d.bool("settled")) MaterialTheme.colorScheme.onSurfaceVariant else if (d.str("direction") == "owe") red else green)
                }
                d.strOrNull("due_date")?.let { Text("শেষ তারিখ: $it", fontSize = 11.sp) }
                if (d.str("note").isNotBlank()) Text(d.str("note"), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.save("debts", row("settled" to !d.bool("settled")), d.id()) }) {
                        Text(if (d.bool("settled")) "আবার খুলুন" else "নিষ্পত্তি")
                    }
                    DeleteButton { vm.remove("debts", d.id()) }
                }
            }
        }
    }

    if (adding) {
        var person by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var direction by remember { mutableStateOf("owed") }
        var due by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        FormDialog("ধার / পাওনা", onDismiss = { adding = false }, onConfirm = {
            val a = parseAmount(amount)
            if (person.isNotBlank() && a != null && a > 0 && (due.isBlank() || isValidIsoDate(due))) {
                vm.save("debts", row("person" to person, "amount" to a, "direction" to direction, "due_date" to nonEmpty(due), "note" to note), null)
                adding = false
            }
        }) {
            ChoiceChips(listOf("owed" to "পাওনা (কেউ আমার কাছে)", "owe" to "ধার (আমি কারো কাছে)"), direction) { direction = it }
            TextIn("ব্যক্তির নাম", person, { person = it })
            TextIn("পরিমাণ (৳)", amount, { amount = it }, number = true)
            TextIn("শেষ তারিখ (YYYY-MM-DD, ঐচ্ছিক)", due, { due = it })
            TextIn("নোট", note, { note = it })
        }
    }
}

// ======================= বাজেট, লক্ষ্য, ইনভেস্টমেন্ট, সাবস্ক্রিপশন, অ্যাকাউন্ট =======================

@Composable
fun BudgetsScreen(state: UiState, vm: AppViewModel) {
    var editing by remember { mutableStateOf<String?>(null) }
    var showing by remember { mutableStateOf(false) }
    val budgets = state.rows("budgets")
    Page {
        AddButton("বাজেট সেট করুন") { showing = true }
        Text("প্রতিটি ক্যাটাগরির জন্য মাসিক সীমা। এই মাসের খরচ নিচে দেখানো হয়।", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (budgets.isEmpty()) EmptyHint("কোনো বাজেট নেই")
        val month = monthPrefix()
        budgets.forEach { b ->
            val cat = b.str("category")
            val spent = state.rows("transactions").filter { it.str("type") == "Expense" && it.str("category") == cat && dateOf(it, "tx_date").startsWith(month) }.sumOf { it.num("amount") }
            val limit = b.num("monthly_limit")
            AppCard(onClick = { editing = b.id() }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${catIcon(cat)} $cat", fontWeight = FontWeight.Bold)
                    Text("${taka(spent, state.privacy)} / ${taka(limit, state.privacy)}", fontSize = 12.sp)
                }
                ProgressBar(if (limit > 0) (spent / limit).toFloat() else 0f, if (spent >= limit) red else Sky500)
                DeleteButton { vm.remove("budgets", b.id()) }
            }
        }
    }
    if (showing || editing != null) {
        val existing = budgets.firstOrNull { it.id() == editing }
        var category by remember { mutableStateOf(existing?.str("category") ?: "খাদ্য") }
        var limit by remember { mutableStateOf(existing?.let { fmtNum(it.num("monthly_limit")) } ?: "") }
        FormDialog("মাসিক বাজেট", onDismiss = { showing = false; editing = null }, onConfirm = {
            val l = parseAmount(limit)
            if (l != null && l >= 0) {
                // Upsert: one budget per category, matching the web app's unique key.
                val target = budgets.firstOrNull { it.str("category") == category }?.id()
                vm.save("budgets", row("category" to category, "monthly_limit" to l), target)
                showing = false; editing = null
            }
        }) {
            Text("ক্যাটাগরি", fontSize = 12.sp)
            ChoiceChips(ALL_CATS.map { it.name to "${it.icon} ${it.name}" }.distinctBy { it.first }, category) { category = it }
            TextIn("মাসিক সীমা (৳)", limit, { limit = it }, number = true)
        }
    }
}

@Composable
fun GoalsScreen(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    var topUp by remember { mutableStateOf<JsonObject?>(null) }
    val goals = state.rows("goals")
    Page {
        AddButton("নতুন লক্ষ্য") { adding = true }
        if (goals.isEmpty()) EmptyHint("কোনো সঞ্চয় লক্ষ্য নেই")
        goals.forEach { g ->
            val target = g.num("target"); val saved = g.num("saved")
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(g.str("name"), fontWeight = FontWeight.Bold)
                    DeleteButton { vm.remove("goals", g.id()) }
                }
                KeyValue("জমা / লক্ষ্য", "${taka(saved, state.privacy)} / ${taka(target, state.privacy)}")
                ProgressBar(if (target > 0) (saved / target).toFloat() else 0f, green)
                g.strOrNull("deadline")?.let { Text("শেষ তারিখ: $it", fontSize = 11.sp) }
                OutlinedButton(onClick = { topUp = g }) { Text("টাকা জমা") }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        var deadline by remember { mutableStateOf("") }
        FormDialog("নতুন সঞ্চয় লক্ষ্য", onDismiss = { adding = false }, onConfirm = {
            val t = parseAmount(target)
            if (name.isNotBlank() && t != null && t > 0 && (deadline.isBlank() || isValidIsoDate(deadline))) {
                vm.save("goals", row("name" to name, "target" to t, "saved" to 0.0, "deadline" to nonEmpty(deadline)), null)
                adding = false
            }
        }) {
            TextIn("লক্ষ্যের নাম", name, { name = it })
            TextIn("লক্ষ্য (৳)", target, { target = it }, number = true)
            TextIn("শেষ তারিখ (YYYY-MM-DD, ঐচ্ছিক)", deadline, { deadline = it })
        }
    }
    topUp?.let { g ->
        var amount by remember { mutableStateOf("") }
        FormDialog("টাকা জমা: ${g.str("name")}", onDismiss = { topUp = null }, onConfirm = {
            val a = parseAmount(amount)
            if (a != null && a > 0) {
                vm.save("goals", row("saved" to g.num("saved") + a), g.id())
                topUp = null
            }
        }) {
            TextIn("পরিমাণ (৳)", amount, { amount = it }, number = true)
        }
    }
}

val INVESTMENT_KINDS = listOf("Stock" to "শেয়ার", "SavingsCertificate" to "সঞ্চয়পত্র", "Crypto" to "ক্রিপ্টো", "Gold" to "সোনা", "RealEstate" to "জমি/বাড়ি", "Other" to "অন্যান্য")

@Composable
fun InvestmentsScreen(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val inv = state.rows("investments")
    val invested = inv.sumOf { it.num("invested") }
    val value = inv.sumOf { it.num("current_value") }
    val p = state.privacy
    Page {
        AppCard {
            KeyValue("মোট বিনিয়োগ", taka(invested, p))
            KeyValue("বর্তমান মূল্য", taka(value, p))
            KeyValue("লাভ/ক্ষতি", taka(value - invested, p), if (value >= invested) green else red)
        }
        AddButton("নতুন সম্পদ") { adding = true }
        if (inv.isEmpty()) EmptyHint("কোনো সম্পদ নেই")
        inv.forEach { i ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(i.str("name"), fontWeight = FontWeight.Bold)
                        Text(INVESTMENT_KINDS.firstOrNull { it.first == i.str("kind") }?.second ?: i.str("kind"), fontSize = 11.sp)
                    }
                    DeleteButton { vm.remove("investments", i.id()) }
                }
                KeyValue("বিনিয়োগ / বর্তমান", "${taka(i.num("invested"), p)} / ${taka(i.num("current_value"), p)}")
                OutlinedButton(onClick = {
                    val now = i.num("current_value")
                    vm.save("investments", row("current_value" to now), i.id())
                }) { Text("মূল্য হালনাগাদ") }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("Other") }
        var invested by remember { mutableStateOf("") }
        var current by remember { mutableStateOf("") }
        var bought by remember { mutableStateOf(todayIso()) }
        FormDialog("নতুন সম্পদ", onDismiss = { adding = false }, onConfirm = {
            val iv = parseAmount(invested); val cv = parseAmount(current) ?: iv
            if (name.isNotBlank() && iv != null && iv >= 0 && cv != null && isValidIsoDate(bought)) {
                vm.save("investments", row("name" to name, "kind" to kind, "invested" to iv, "current_value" to cv, "bought_on" to bought), null)
                adding = false
            }
        }) {
            ChoiceChips(INVESTMENT_KINDS, kind) { kind = it }
            TextIn("নাম", name, { name = it })
            TextIn("বিনিয়োগের পরিমাণ (৳)", invested, { invested = it }, number = true)
            TextIn("বর্তমান মূল্য (৳)", current, { current = it }, number = true)
            TextIn("কেনার তারিখ", bought, { bought = it })
        }
    }
}

@Composable
fun SubscriptionsScreen(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val subs = state.rows("subscriptions")
    val monthly = subs.filter { it.bool("is_active") }.sumOf {
        when (it.str("frequency")) { "Daily" -> it.num("amount") * 30; "Weekly" -> it.num("amount") * 4.3; "Yearly" -> it.num("amount") / 12; else -> it.num("amount") }
    }
    Page {
        AppCard { KeyValue("মাসিক আনুমানিক খরচ", taka(monthly, state.privacy), red) }
        AddButton("নতুন সাবস্ক্রিপশন") { adding = true }
        if (subs.isEmpty()) EmptyHint("কোনো সাবস্ক্রিপশন নেই")
        subs.forEach { s ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(s.str("name"), fontWeight = FontWeight.Bold)
                        Text("${s.str("frequency")} · ${s.str("renewal_date").ifEmpty { "-" }}", fontSize = 11.sp)
                    }
                    Text(taka(s.num("amount"), state.privacy), fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.save("subscriptions", row("is_active" to !s.bool("is_active")), s.id()) }) {
                        Text(if (s.bool("is_active")) "বন্ধ করুন" else "চালু করুন")
                    }
                    DeleteButton { vm.remove("subscriptions", s.id()) }
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var freq by remember { mutableStateOf("Monthly") }
        var renewal by remember { mutableStateOf(todayIso()) }
        FormDialog("নতুন সাবস্ক্রিপশন", onDismiss = { adding = false }, onConfirm = {
            val a = parseAmount(amount)
            if (name.isNotBlank() && a != null && a >= 0 && isValidIsoDate(renewal)) {
                vm.save("subscriptions", row("name" to name, "amount" to a, "frequency" to freq, "renewal_date" to renewal, "currency" to state.currency), null)
                adding = false
            }
        }) {
            TextIn("সার্ভিসের নাম", name, { name = it })
            TextIn("পরিমাণ (৳)", amount, { amount = it }, number = true)
            ChoiceChips(listOf("Daily" to "দৈনিক", "Weekly" to "সাপ্তাহিক", "Monthly" to "মাসিক", "Yearly" to "বার্ষিক"), freq) { freq = it }
            TextIn("পরবর্তী নবায়ন (YYYY-MM-DD)", renewal, { renewal = it })
        }
    }
}

@Composable
fun AccountsScreen(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val txs = state.rows("transactions")
    val accounts = state.rows("accounts")
    Page {
        AppCard {
            Text("ট্রান্সফার করতে + বাটনে গিয়ে 'ট্রান্সফার' বাছাই করুন।", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AddButton("নতুন অ্যাকাউন্ট") { adding = true }
        if (accounts.isEmpty()) EmptyHint("কোনো অ্যাকাউন্ট নেই (ক্যাশ, bKash, Nagad, ব্যাংক)")
        accounts.forEach { a ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(a.str("name"), fontWeight = FontWeight.Bold)
                        Text(a.str("kind"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(taka(accountBalance(a, txs), state.privacy), fontWeight = FontWeight.Bold)
                }
                DeleteButton { vm.remove("accounts", a.id()) }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("Cash") }
        var opening by remember { mutableStateOf("0") }
        FormDialog("নতুন অ্যাকাউন্ট", onDismiss = { adding = false }, onConfirm = {
            val o = parseAmount(opening) ?: 0.0
            if (name.isNotBlank()) {
                vm.save("accounts", row("name" to name, "kind" to kind, "opening_balance" to o), null)
                adding = false
            }
        }) {
            TextIn("নাম", name, { name = it })
            ChoiceChips(listOf("Cash", "Bkash", "Nagad", "Rocket", "Bank", "Card").map { it to it }, kind) { kind = it }
            TextIn("শুরুর ব্যালেন্স (৳)", opening, { opening = it }, number = true)
        }
    }
}

/** Debt payoff plan (snowball: smallest debt first). Interest-free estimate: months = cumulative debt / monthly payment. */
@Composable
fun DebtPlanner(debts: List<JsonObject>, privacy: Boolean) {
    var monthly by remember { mutableStateOf("") }
    val owes = debts.filter { !it.bool("settled") && it.str("direction") == "owe" }.sortedBy { it.num("amount") }
    val m = parseAmount(monthly) ?: 0.0
    AppCard {
        Text("ধার পরিশোধের পরিকল্পনা (ছোট ধার আগে)", fontWeight = FontWeight.Bold)
        TextIn("প্রতি মাসে কত দিতে পারবেন (৳)", monthly, { monthly = it }, number = true)
        when {
            owes.isEmpty() -> Text("কোনো ধার নেই", color = MaterialTheme.colorScheme.onSurfaceVariant)
            m <= 0 -> Text("মাসিক পরিমাণ লিখুন", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                var cumulative = 0.0
                owes.forEach { d ->
                    cumulative += d.num("amount")
                    Text("${d.str("person")}: ${taka(d.num("amount"), privacy)} → ${ceil(cumulative / m).toInt()} মাসে শেষ", fontSize = 13.sp)
                }
                Text("সব ধার শেষ হবে ${ceil(cumulative / m).toInt()} মাসে (সুদ ছাড়া হিসাব)", fontWeight = FontWeight.Bold)
            }
        }
    }
}
