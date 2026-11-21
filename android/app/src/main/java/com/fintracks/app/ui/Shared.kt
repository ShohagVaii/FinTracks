package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.data.strList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.YearMonth

/** Shared tab: bill splitter, mess manager, and shopping list. Money math lives in the functions below, not in the screens. */
@Composable
fun SharedScreen(state: UiState, vm: AppViewModel) {
    var section by remember { mutableStateOf("bill") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ChoiceChips(listOf("bill" to tr("বিল স্প্লিটার", "Bill splitter"), "mess" to tr("মেস ম্যানেজার", "Mess manager"), "shop" to tr("শপিং লিস্ট", "Shopping list")), section) { section = it }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (section) {
                "bill" -> BillSplitSection(state, vm)
                "shop" -> ShoppingSection(state, vm)
                else -> MessSection(state, vm)
            }
            Spacer(Modifier.height(90.dp))
        }
    }
}

// ======================= Shared math =======================

/** One payment needed to settle: [from] pays [to] [amount]. Keys are names (bills) or member ids (mess). */
private data class Payout(val from: String, val to: String, val amount: Double)

private fun round2(v: Double): Double = Math.round(v * 100) / 100.0

/**
 * Smallest practical set of payments that brings every balance to zero.
 * Balances must sum to zero (they do for bills and mess); positive = is owed, negative = owes.
 */
private fun settle(balances: Map<String, Double>): List<Payout> {
    val debtors = balances.filter { it.value < -0.005 }.map { it.key to -it.value }.sortedByDescending { it.second }.toMutableList()
    val creditors = balances.filter { it.value > 0.005 }.map { it.key to it.value }.sortedByDescending { it.second }.toMutableList()
    val out = mutableListOf<Payout>()
    var i = 0
    var j = 0
    while (i < debtors.size && j < creditors.size) {
        val pay = minOf(debtors[i].second, creditors[j].second)
        out += Payout(debtors[i].first, creditors[j].first, round2(pay))
        debtors[i] = debtors[i].first to (debtors[i].second - pay)
        creditors[j] = creditors[j].first to (creditors[j].second - pay)
        if (debtors[i].second < 0.005) i++
        if (creditors[j].second < 0.005) j++
    }
    return out
}

/** What one person owes for a bill: equal split, or their custom amount. Zero if not a participant. */
private fun billShare(b: JsonObject, name: String): Double {
    val people = b.strList("participants")
    if (!people.contains(name)) return 0.0
    if (b.str("split_mode") == "custom") {
        val shares = b["shares"] as? JsonObject
        return shares?.num(name) ?: 0.0
    }
    return b.num("total_amount") / people.size
}

/** Net position per person for one bill: the payer paid the total, every participant owes their share. */
private fun billNet(b: JsonObject): Map<String, Double> {
    val total = b.num("total_amount")
    val payer = b.str("payer").trim()
    val names = (b.strList("participants") + payer).filter { it.isNotBlank() }.distinct()
    return names.associateWith { n -> (if (n == payer) total else 0.0) - billShare(b, n) }
}

// ======================= Bill splitter =======================

@Composable
private fun BillSplitSection(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val bills = state.rows("bill_splits")
    val unsettled = bills.filter { !it.bool("is_settled") }
    val p = state.privacy

    // Totals across all bills that are not settled yet.
    val overall = HashMap<String, Double>()
    unsettled.forEach { b -> billNet(b).forEach { (k, v) -> overall[k] = (overall[k] ?: 0.0) + v } }
    val overallPayouts = settle(overall)

    AddButton(tr("নতুন বিল", "New bill")) { adding = true }
    if (unsettled.isNotEmpty()) {
        AppCard {
            Text("অমীমাংসিত বিল: সব মিলিয়ে", fontWeight = FontWeight.Bold)
            if (overallPayouts.isEmpty()) {
                Text("কারও কাছে কিছু পাওনা বা দেনা নেই।", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                overallPayouts.forEach { Text("${it.from} দেবেন ${it.to}-কে: ${taka(it.amount, p)}", fontSize = 14.sp) }
            }
        }
    }
    if (bills.isEmpty()) EmptyHint("কোনো বিল ভাগ নেই")
    bills.forEach { b -> BillCard(b, vm, p) }
    if (adding) NewBillDialog(state, vm) { adding = false }
}

@Composable
private fun BillCard(b: JsonObject, vm: AppViewModel, p: Boolean) {
    val total = b.num("total_amount")
    val custom = b.str("split_mode") == "custom"
    val net = billNet(b)
    val payouts = settle(net)
    val settled = b.bool("is_settled")
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(b.str("title"), fontWeight = FontWeight.Bold)
                Text(
                    "পরিশোধ করেছেন: ${b.str("payer")} · ${dateOf(b, "split_date")} · ${if (custom) "কাস্টম ভাগ" else "সমান ভাগ"}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(taka(total, p), fontWeight = FontWeight.Bold)
        }
        b.strList("participants").forEach { n -> KeyValue("$n-এর ভাগ", taka(billShare(b, n), p)) }
        Text("ফলাফল", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        net.forEach { (n, v) ->
            when {
                v > 0.005 -> KeyValue("$n · পাবেন", taka(v, p), Emerald500)
                v < -0.005 -> KeyValue("$n · দেবেন", taka(-v, p), Rose500)
                else -> KeyValue(n, "মিটে গেছে")
            }
        }
        payouts.forEach { Text("${it.from} → ${it.to}-কে ফেরত: ${taka(it.amount, p)}", fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.save("bill_splits", row("is_settled" to !settled), b.id()) }) {
                Text(if (settled) "অমীমাংসিত করুন" else "মিটে গেছে")
            }
            DeleteButton { vm.remove("bill_splits", b.id()) }
        }
    }
}

@Composable
private fun NewBillDialog(state: UiState, vm: AppViewModel, onDone: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("equal") }
    var people by remember { mutableStateOf(listOf<String>()) }
    var payer by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf(listOf<String>()) }
    var newName by remember { mutableStateOf("") }
    var amounts by remember { mutableStateOf(mapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    val known = (state.rows("mess_members").map { it.str("name") } + state.rows("bill_splits").flatMap { it.strList("participants") } + extra)
        .filter { it.isNotBlank() }
        .distinct()
    val t = parseAmount(total)
    val tv = t ?: 0.0
    val customSum = people.sumOf { parseAmount(amounts[it] ?: "") ?: 0.0 }

    SharedDialog("নতুন বিল ভাগ", "সেভ", onDismiss = onDone, onConfirm = {
        when {
            title.isBlank() -> error = "বিলের নাম লিখুন"
            t == null || t <= 0 -> error = "সঠিক মোট টাকা লিখুন"
            people.isEmpty() -> error = "অন্তত একজন অংশগ্রহণকারী বাছাই করুন"
            payer !in people -> error = "কে পরিশোধ করেছেন তা অংশগ্রহণকারীদের মধ্য থেকে বাছাই করুন"
            mode == "custom" && people.any { parseAmount(amounts[it] ?: "") == null } -> error = "প্রত্যেকের ভাগের পরিমাণ লিখুন"
            mode == "custom" && kotlin.math.abs(customSum - tv) > 0.01 ->
                error = "ভাগের যোগফল মোট টাকার সমান হতে হবে (এখন ${taka(customSum)})"
            else -> {
                val shares = if (mode == "custom") {
                    JsonObject(people.associateWith { JsonPrimitive(parseAmount(amounts[it] ?: "") ?: 0.0) })
                } else {
                    JsonObject(emptyMap())
                }
                vm.save(
                    "bill_splits",
                    row(
                        "title" to title.trim(),
                        "total_amount" to tv,
                        "payer" to payer,
                        "participants" to JsonArray(people.map { JsonPrimitive(it) }),
                        "split_mode" to mode,
                        "shares" to shares,
                        "split_date" to todayIso()
                    ),
                    null
                )
                onDone()
            }
        }
    }) {
        TextIn("বিলের নাম (যেমন: বাসা ভাড়া, খাবার)", title, { title = it })
        TextIn("মোট টাকা (৳)", total, { total = it }, number = true)
        Text("অংশগ্রহণকারী", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (known.isNotEmpty()) {
            ToggleChips(known, people) { n ->
                people = if (n in people) people - n else people + n
                if (payer !in people) payer = ""
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextIn("নতুন নাম", newName, { newName = it }, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                val n = newName.trim()
                if (n.isNotEmpty()) {
                    extra = (extra + n).distinct()
                    if (n !in people) people = people + n
                    newName = ""
                }
            }) { Text("যোগ") }
        }
        if (people.isNotEmpty()) {
            Text("কে পরিশোধ করেছেন", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(people.map { it to it }, payer) { payer = it }
        }
        Text("ভাগের ধরন", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceChips(listOf("equal" to "সমান ভাগ", "custom" to "কাস্টম পরিমাণ"), mode) { mode = it }
        if (mode == "custom") {
            people.forEach { n ->
                TextIn("$n-এর ভাগ (৳)", amounts[n] ?: "", { v -> amounts = amounts + (n to v) }, number = true)
            }
            if (t != null) Text("বাকি: ${taka(tv - customSum)}", fontSize = 12.sp)
        } else if (t != null && people.isNotEmpty()) {
            Text("জনপ্রতি: ${taka(tv / people.size)}", fontSize = 12.sp)
        }
        error?.let { Text(it, color = Rose500, fontSize = 12.sp) }
    }
}

// ======================= Shopping list =======================

@Composable
private fun ShoppingSection(state: UiState, vm: AppViewModel) {
    val p = state.privacy
    val items = state.rows("shopping_items")
    var name by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf("") }
    var buying by remember { mutableStateOf<JsonObject?>(null) }

    val budgetTotal = items.sumOf { it.num("estimated_price") }
    val spent = items.filter { it.bool("is_done") }.sumOf { it.num("actual_price") }
    val diff = budgetTotal - spent

    AppCard {
        Text(tr("নতুন জিনিসের তালিকা", "New shopping list"), fontWeight = FontWeight.Bold)
        TextIn("আইটেমের নাম", name, { name = it })
        TextIn("আনুমানিক বাজেট (৳)", budget, { budget = it }, number = true)
        Button(
            onClick = {
                vm.save("shopping_items", row("name" to name.trim(), "estimated_price" to (parseAmount(budget) ?: 0.0)), null)
                name = ""
                budget = ""
            },
            enabled = name.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Color.White)
        ) { Text("যোগ করুন") }
    }
    AppCard {
        Text(tr("বাজেট বনাম খরচ", "Budget vs spending"), fontWeight = FontWeight.Bold)
        KeyValue("মোট বাজেট", taka(budgetTotal, p))
        KeyValue("মোট খরচ (কেনা আইটেম)", taka(spent, p))
        KeyValue(
            if (diff >= 0) "বাজেটের বাকি" else "বাজেটের বেশি খরচ",
            taka(kotlin.math.abs(diff), p),
            if (diff >= 0) Emerald500 else Rose500
        )
        ProgressBar(if (budgetTotal > 0) (spent / budgetTotal).toFloat() else 0f, Sky500)
    }
    if (items.isEmpty()) EmptyHint("শপিং লিস্ট খালি")
    items.forEach { i ->
        val done = i.bool("is_done")
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        i.str("name"),
                        fontWeight = FontWeight.Bold,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
                    )
                    Text(
                        if (done) "বাজেট ${taka(i.num("estimated_price"), p)} · কেনা দাম ${taka(i.num("actual_price"), p)}"
                        else "আনুমানিক ${taka(i.num("estimated_price"), p)}",
                        fontSize = 12.sp
                    )
                }
                DeleteButton { vm.remove("shopping_items", i.id()) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) {
                    OutlinedButton(onClick = { buying = i }) { Text("দাম বদলান") }
                    OutlinedButton(onClick = { vm.save("shopping_items", row("is_done" to false, "actual_price" to JsonNull), i.id()) }) {
                        Text("আবার কিনতে হবে")
                    }
                } else {
                    Button(onClick = { buying = i }) { Text("কেনা হয়েছে") }
                }
            }
        }
    }
    buying?.let { item -> BoughtDialog(item, vm) { buying = null } }
}

@Composable
private fun BoughtDialog(item: JsonObject, vm: AppViewModel, onDone: () -> Unit) {
    val done = item.bool("is_done")
    var price by remember(item.id()) {
        mutableStateOf(fmtNum(if (done) item.num("actual_price") else item.num("estimated_price")))
    }
    var error by remember { mutableStateOf<String?>(null) }
    SharedDialog("আসল দাম: ${item.str("name")}", "সেভ", onDismiss = onDone, onConfirm = {
        val v = parseAmount(price)
        if (v == null || v < 0) {
            error = "সঠিক দাম লিখুন"
        } else {
            vm.save("shopping_items", row("is_done" to true, "actual_price" to v), item.id())
            onDone()
        }
    }) {
        TextIn("কত টাকায় কেনা হলো (৳)", price, { price = it }, number = true)
        error?.let { Text(it, color = Rose500, fontSize = 12.sp) }
    }
}

// ======================= Mess manager =======================

@Composable
private fun MessSection(state: UiState, vm: AppViewModel) {
    val p = state.privacy
    var ym by remember { mutableStateOf(YearMonth.now()) }
    var addingMember by remember { mutableStateOf(false) }
    var editingMember by remember { mutableStateOf<JsonObject?>(null) }
    var addingBazaar by remember { mutableStateOf(false) }
    var addingMeal by remember { mutableStateOf(false) }
    var addingDeposit by remember { mutableStateOf(false) }

    // Every figure below is for the selected month only.
    val prefix = ym.toString()
    val members = state.rows("mess_members")
    val bazaars = state.rows("mess_bazaars").filter { dateOf(it, "bazaar_date").startsWith(prefix) }
    val meals = state.rows("mess_meals").filter { dateOf(it, "meal_date").startsWith(prefix) }
    val deposits = state.rows("mess_deposits").filter { dateOf(it, "deposit_date").startsWith(prefix) }
    val totalBazaar = bazaars.sumOf { it.num("amount") }
    val totalMeals = meals.sumOf { it.num("meal_count") }
    val rate = if (totalMeals > 0) totalBazaar / totalMeals else 0.0
    // জমা মোট = bazaar spend + cash deposits this month; খরচ = meals × rate; balance = জমা মোট − খরচ.
    val balances = members.associate { m ->
        val paid = bazaars.filter { it.str("member_id") == m.id() }.sumOf { it.num("amount") }
        val cash = deposits.filter { it.str("member_id") == m.id() }.sumOf { it.num("amount") }
        val mealN = meals.filter { it.str("member_id") == m.id() }.sumOf { it.num("meal_count") }
        m.id() to (paid + cash - mealN * rate)
    }
    val nameOf = members.associate { it.id() to it.str("name") }
    val payouts = settle(balances)

    MonthNav(ym) { ym = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPill("মোট বাজার", taka(totalBazaar, p), Emerald500, Modifier.weight(1f))
        StatPill("মোট মিল", fmtNum(totalMeals), Sky400, Modifier.weight(1f))
        StatPill("মিল রেট", taka(rate, p), Amber500, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { addingMember = true },
            colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Color.White),
            modifier = Modifier.weight(1f)
        ) { Text("+ সদস্য") }
        Button(
            onClick = { addingDeposit = true },
            enabled = members.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.White),
            modifier = Modifier.weight(1f)
        ) { Text("জমা এন্ট্রি") }
    }
    Button(
        onClick = { addingBazaar = true },
        enabled = members.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text("বাজার এন্ট্রি", fontWeight = FontWeight.Bold) }
    Button(
        onClick = { addingMeal = true },
        enabled = members.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = Sky500, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text("মিল এন্ট্রি", fontWeight = FontWeight.Bold) }

    SectionTitle(tr("সদস্য ও হিসাব", "Members & accounts"))
    if (members.isEmpty()) EmptyHint("কোনো সদস্য নেই। উপরে '+ সদস্য' দিয়ে যোগ করুন।")
    members.forEach { m ->
        val paid = bazaars.filter { it.str("member_id") == m.id() }.sumOf { it.num("amount") }
        val cash = deposits.filter { it.str("member_id") == m.id() }.sumOf { it.num("amount") }
        val mealN = meals.filter { it.str("member_id") == m.id() }.sumOf { it.num("meal_count") }
        val bal = balances[m.id()] ?: 0.0
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(m.str("name"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row {
                    IconButton(onClick = { editingMember = m }) { Icon(Icons.Default.Edit, contentDescription = "এডিট") }
                    DeleteButton { vm.remove("mess_members", m.id()) }
                }
            }
            KeyValue("জমা মোট (বাজার + ক্যাশ)", taka(paid + cash, p))
            KeyValue("  · বাজার এন্ট্রি", taka(paid, p))
            KeyValue("  · ক্যাশ জমা", taka(cash, p))
            KeyValue("মিল", fmtNum(mealN))
            KeyValue("তার খরচ (মিল × রেট)", taka(mealN * rate, p))
            when {
                bal > 0.005 -> KeyValue("পাবেন", taka(bal, p), Emerald500)
                bal < -0.005 -> KeyValue("দেবেন", taka(-bal, p), Rose500)
                else -> KeyValue("হিসাব", "মিটে গেছে")
            }
        }
    }

    SectionTitle(tr("হিসাব নিষ্পত্তি (সেটল-আপ)", "Settle up"))
    AppCard {
        when {
            members.isEmpty() -> Text("সদস্য যোগ করলে এখানে কে কাকে কত দেবে তা দেখাবে।", fontSize = 13.sp)
            payouts.isEmpty() -> Text("এই মাসে কারও কাছে কিছু পাওনা বা দেনা নেই।", fontSize = 13.sp)
            else -> payouts.forEach {
                Text("${nameOf[it.from].orEmpty()} দেবেন ${nameOf[it.to].orEmpty()}-কে: ${taka(it.amount, p)}", fontSize = 14.sp)
            }
        }
    }

    SectionTitle(tr("জমার হিসাব", "Deposits"))
    if (deposits.isEmpty()) EmptyHint("এই মাসে কোনো জমা এন্ট্রি নেই")
    deposits.sortedByDescending { dateOf(it, "deposit_date") }.forEach { d ->
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${nameOf[d.str("member_id")].orEmpty()} · ক্যাশ জমা", fontWeight = FontWeight.Bold)
                    Text(
                        listOf(dateOf(d, "deposit_date"), d.str("note")).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(taka(d.num("amount"), p), fontWeight = FontWeight.Bold)
                DeleteButton { vm.remove("mess_deposits", d.id()) }
            }
        }
    }

    SectionTitle(tr("বাজারের হিসাব", "Bazaar"))
    if (bazaars.isEmpty()) EmptyHint("এই মাসে কোনো বাজার এন্ট্রি নেই")
    bazaars.sortedByDescending { dateOf(it, "bazaar_date") }.forEach { b ->
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${nameOf[b.str("member_id")].orEmpty()} · ${b.str("item").ifBlank { "বাজার" }}", fontWeight = FontWeight.Bold)
                    Text(dateOf(b, "bazaar_date"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(taka(b.num("amount"), p), fontWeight = FontWeight.Bold)
                DeleteButton { vm.remove("mess_bazaars", b.id()) }
            }
        }
    }

    SectionTitle(tr("মিলের হিসাব", "Meals"))
    if (meals.isEmpty()) EmptyHint("এই মাসে কোনো মিল নেই")
    meals.sortedByDescending { dateOf(it, "meal_date") }.forEach { m ->
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${nameOf[m.str("member_id")].orEmpty()} · ${fmtNum(m.num("meal_count"))} মিল", fontWeight = FontWeight.Bold)
                    Text(dateOf(m, "meal_date"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DeleteButton { vm.remove("mess_meals", m.id()) }
            }
        }
    }

    if (addingDeposit && members.isNotEmpty()) {
        var member by remember { mutableStateOf(members.first().id()) }
        var amount by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(todayIso()) }
        var error by remember { mutableStateOf<String?>(null) }
        SharedDialog("জমা এন্ট্রি (ক্যাশ ডিপোজিট)", "সেভ", onDismiss = { addingDeposit = false }, onConfirm = {
            val a = parseAmount(amount)
            when {
                a == null || a <= 0 -> error = "সঠিক টাকার পরিমাণ লিখুন"
                !isValidIsoDate(date) -> error = "তারিখ লিখুন (YYYY-MM-DD)"
                else -> {
                    vm.save("mess_deposits", row("member_id" to member, "amount" to a, "deposit_date" to date.trim(), "note" to note.trim()), null)
                    addingDeposit = false
                }
            }
        }) {
            Text("কে জমা দিয়েছেন", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
            TextIn("টাকা (৳)", amount, { amount = it }, number = true)
            TextIn("তারিখ (YYYY-MM-DD)", date, { date = it })
            TextIn("নোট (ঐচ্ছিক)", note, { note = it })
            error?.let { Text(it, color = Rose500, fontSize = 12.sp) }
        }
    }
    if (addingMember) {
        var name by remember { mutableStateOf("") }
        SharedDialog("নতুন সদস্য", "সেভ", onDismiss = { addingMember = false }, onConfirm = {
            if (name.isNotBlank()) {
                vm.save("mess_members", row("name" to name.trim()), null)
                addingMember = false
            }
        }) {
            TextIn("নাম", name, { name = it })
        }
    }
    editingMember?.let { m ->
        var name by remember(m.id()) { mutableStateOf(m.str("name")) }
        SharedDialog("সদস্যের নাম বদলান", "সেভ", onDismiss = { editingMember = null }, onConfirm = {
            if (name.isNotBlank()) {
                vm.save("mess_members", row("name" to name.trim()), m.id())
                editingMember = null
            }
        }) {
            TextIn("নাম", name, { name = it })
        }
    }
    if (addingBazaar && members.isNotEmpty()) {
        var member by remember { mutableStateOf(members.first().id()) }
        var amount by remember { mutableStateOf("") }
        var item by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(todayIso()) }
        var error by remember { mutableStateOf<String?>(null) }
        SharedDialog("বাজার এন্ট্রি", "সেভ", onDismiss = { addingBazaar = false }, onConfirm = {
            val a = parseAmount(amount)
            when {
                a == null || a <= 0 -> error = "সঠিক টাকার পরিমাণ লিখুন"
                !isValidIsoDate(date) -> error = "তারিখ লিখুন (YYYY-MM-DD)"
                else -> {
                    vm.save("mess_bazaars", row("member_id" to member, "amount" to a, "item" to item.trim(), "bazaar_date" to date.trim()), null)
                    addingBazaar = false
                }
            }
        }) {
            Text("কে বাজার করেছে", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
            TextIn("টাকা (৳)", amount, { amount = it }, number = true)
            TextIn("কী কেনা হলো", item, { item = it })
            TextIn("তারিখ (YYYY-MM-DD)", date, { date = it })
            error?.let { Text(it, color = Rose500, fontSize = 12.sp) }
        }
    }
    if (addingMeal && members.isNotEmpty()) {
        var member by remember { mutableStateOf(members.first().id()) }
        var count by remember { mutableStateOf(1.0) }
        var date by remember { mutableStateOf(todayIso()) }
        var error by remember { mutableStateOf<String?>(null) }
        SharedDialog("মিল এন্ট্রি", "মিল যোগ করুন", onDismiss = { addingMeal = false }, onConfirm = {
            when {
                count <= 0 -> error = "মিলের সংখ্যা ০-এর বেশি হতে হবে"
                !isValidIsoDate(date) -> error = "তারিখ লিখুন (YYYY-MM-DD)"
                else -> {
                    vm.save("mess_meals", row("member_id" to member, "meal_count" to count, "meal_date" to date.trim()), null)
                    addingMeal = false
                }
            }
        }) {
            Text("কে খেয়েছেন", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { count = (count - 0.5).coerceAtLeast(0.0) }) { Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                Text(fmtNum(count), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
                IconButton(onClick = { count += 0.5 }) { Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            }
            Text("ধাপ ০.৫", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextIn("তারিখ (YYYY-MM-DD)", date, { date = it })
            error?.let { Text(it, color = Rose500, fontSize = 12.sp) }
        }
    }
}

// ======================= Shared UI pieces =======================

@Composable
private fun MonthNav(ym: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { onChange(ym.minusMonths(1)) }) { Icon(Icons.Default.ChevronLeft, contentDescription = "আগের মাস") }
        Text("${BN_MONTHS[ym.monthValue - 1]} ${bnDigits(ym.year.toString())}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        IconButton(onClick = { onChange(ym.plusMonths(1)) }) { Icon(Icons.Default.ChevronRight, contentDescription = "পরের মাস") }
    }
}

/** Multi-select chips (participants). Wraps into rows of three. */
@Composable
private fun ToggleChips(options: List<String>, selected: List<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chunk.forEach { o ->
                    FilterChip(
                        selected = o in selected,
                        onClick = { onToggle(o) },
                        label = { Text(o, fontSize = 12.sp, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Dialog with its own confirm label (FormDialog always says সেভ). */
@Composable
private fun SharedDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } }
    )
}
