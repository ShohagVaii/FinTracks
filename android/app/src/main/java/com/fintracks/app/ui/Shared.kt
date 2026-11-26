package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import com.fintracks.app.ui.design.SegmentedTabs
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
import androidx.compose.foundation.layout.Box

/** Shared tab: bill splitter, mess manager, and shopping list. Money math lives in the functions below, not in the screens. */
@Composable
fun SharedScreen(state: UiState, vm: AppViewModel) {
    var section by remember { mutableStateOf("bill") }
    Column(Modifier.fillMaxSize()) {
        val accent by animateColorAsState(
            when (section) { "bill" -> Sky500; "mess" -> Emerald500; else -> Amber500 }, tween(300), label = "accent"
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedTabs(listOf("bill" to tr("বিল স্প্লিটার", "Bill splitter"), "mess" to tr("মেস ম্যানেজার", "Mess manager"), "shop" to tr("শপিং লিস্ট", "Shopping list")), selected = section, onSelect = { section = it })
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).background(accent, RoundedCornerShape(50)))
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
            AppFooter()
            Spacer(Modifier.height(90.dp))
        }
    }
}

// ======================= Shared math =======================

/** One payment needed to settle: [from] pays [to] [amount]. Keys are names (bills) or member ids (mess). */
internal data class Payout(val from: String, val to: String, val amount: Double)

internal fun round2(v: Double): Double = Math.round(v * 100) / 100.0

/**
 * Smallest practical set of payments that brings every balance to zero.
 * Balances must sum to zero (they do for bills and mess); positive = is owed, negative = owes.
 */
internal fun settleBalances(balances: Map<String, Double>): List<Payout> {
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
    val payouts = settleBalances(balances)

    MonthNav(ym) { ym = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPill(tr("মোট বাজার", "Total bazaar"), taka(totalBazaar, p), Emerald500, Modifier.weight(1f), countTo = totalBazaar, countFmt = { taka(it, p) })
        StatPill(tr("মোট মিল", "Total meals"), fmtNum(totalMeals), Sky400, Modifier.weight(1f), countTo = totalMeals, countFmt = { fmtNum(it) })
        StatPill(tr("মিল রেট", "Meal rate"), taka(rate, p), Amber500, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { addingMember = true },
            colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Color.White),
            modifier = Modifier.weight(1f)
        ) { Text(tr("+ সদস্য", "+ Member")) }
        Button(
            onClick = { addingDeposit = true },
            enabled = members.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.White),
            modifier = Modifier.weight(1f)
        ) { Text(tr("জমা এন্ট্রি", "Deposit entry")) }
    }
    Button(
        onClick = { addingBazaar = true },
        enabled = members.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text(tr("বাজার এন্ট্রি", "Bazaar entry"), fontWeight = FontWeight.Bold) }
    Button(
        onClick = { addingMeal = true },
        enabled = members.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = Sky500, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text(tr("মিল এন্ট্রি", "Meal entry"), fontWeight = FontWeight.Bold) }

    SectionTitle(tr("সদস্য ও হিসাব", "Members & accounts"))
    if (members.isEmpty()) EmptyHint(tr("কোনো সদস্য নেই। উপরে '+ সদস্য' দিয়ে যোগ করুন।", "No members yet. Add one with '+ Member' above."))
    members.forEach { m ->
        val paid = bazaars.filter { it.str("member_id") == m.id() }.sumOf { it.num("amount") }
        val cash = deposits.filter { it.str("member_id") == m.id() }.sumOf { it.num("amount") }
        val mealN = meals.filter { it.str("member_id") == m.id() }.sumOf { it.num("meal_count") }
        val bal = balances[m.id()] ?: 0.0
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(m.str("name"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row {
                    IconButton(modifier = Modifier.pressScale(), onClick = { editingMember = m }) { Icon(Icons.Default.Edit, contentDescription = tr("এডিট", "Edit")) }
                    DeleteButton { vm.remove("mess_members", m.id()) }
                }
            }
            KeyValue(tr("জমা মোট (বাজার + ক্যাশ)", "Total deposits (bazaar + cash)"), taka(paid + cash, p))
            KeyValue(tr("  · বাজার এন্ট্রি", "  · bazaar entries"), taka(paid, p))
            KeyValue(tr("  · ক্যাশ জমা", "  · cash deposits"), taka(cash, p))
            KeyValue(tr("মিল", "Meals"), fmtNum(mealN))
            KeyValue(tr("তার খরচ (মিল × রেট)", "Their cost (meals × rate)"), taka(mealN * rate, p))
            when {
                bal > 0.005 -> KeyValue(tr("পাবেন", "Gets back"), taka(bal, p), Emerald500)
                bal < -0.005 -> KeyValue(tr("দেবেন", "Owes"), taka(-bal, p), Rose500)
                else -> KeyValue(tr("হিসাব", "Balance"), tr("মিটে গেছে", "Settled"))
            }
        }
    }

    SectionTitle(tr("হিসাব নিষ্পত্তি (সেটল-আপ)", "Settle up"))
    AppCard {
        when {
            members.isEmpty() -> Text(tr("সদস্য যোগ করলে এখানে কে কাকে কত দেবে তা দেখাবে।", "Add members to see who pays whom here."), fontSize = 13.sp)
            payouts.isEmpty() -> Text(tr("এই মাসে কারও কাছে কিছু পাওনা বা দেনা নেই।", "Nobody owes or is owed anything this month."), fontSize = 13.sp)
            else -> payouts.forEach {
                Text(tr("${nameOf[it.from].orEmpty()} দেবেন ${nameOf[it.to].orEmpty()}-কে: ${taka(it.amount, p)}", "${nameOf[it.from].orEmpty()} owes ${nameOf[it.to].orEmpty()}: ${taka(it.amount, p)}"), fontSize = 14.sp)
            }
        }
    }

    SectionTitle(tr("জমার হিসাব", "Deposits"))
    if (deposits.isEmpty()) EmptyHint(tr("এই মাসে কোনো জমা এন্ট্রি নেই", "No deposit entries this month"))
    deposits.sortedByDescending { dateOf(it, "deposit_date") }.forEach { d ->
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${nameOf[d.str("member_id")].orEmpty()} · ক্যাশ জমা", fontWeight = FontWeight.Bold)
                    Text(
                        listOf(displayDate(dateOf(d, "deposit_date")), d.str("note")).filter { it.isNotBlank() }.joinToString(" · "),
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
    if (bazaars.isEmpty()) EmptyHint(tr("এই মাসে কোনো বাজার এন্ট্রি নেই", "No bazaar entries this month"))
    bazaars.sortedByDescending { dateOf(it, "bazaar_date") }.forEach { b ->
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${nameOf[b.str("member_id")].orEmpty()} · ${b.str("item").ifBlank { "বাজার" }}", fontWeight = FontWeight.Bold)
                    Text(displayDate(dateOf(b, "bazaar_date")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(taka(b.num("amount"), p), fontWeight = FontWeight.Bold)
                DeleteButton { vm.remove("mess_bazaars", b.id()) }
            }
        }
    }

    SectionTitle(tr("মিলের হিসাব", "Meals"))
    if (meals.isEmpty()) EmptyHint(tr("এই মাসে কোনো মিল নেই", "No meals this month"))
    meals.sortedByDescending { dateOf(it, "meal_date") }.forEach { m ->
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${nameOf[m.str("member_id")].orEmpty()} · ${fmtNum(m.num("meal_count"))} মিল", fontWeight = FontWeight.Bold)
                    Text(displayDate(dateOf(m, "meal_date")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        SharedDialog(tr("জমা এন্ট্রি (ক্যাশ ডিপোজিট)", "Deposit entry (cash)"), tr("সেভ", "Save"), onDismiss = { addingDeposit = false }, onConfirm = {
            val a = parseAmount(amount)
            when {
                a == null || a <= 0 -> error = tr("সঠিক টাকার পরিমাণ লিখুন", "Enter a valid amount")
                !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
                else -> {
                    vm.save("mess_deposits", row("member_id" to member, "amount" to a, "deposit_date" to date.trim(), "note" to note.trim()), null)
                    addingDeposit = false
                }
            }
        }) {
            Text(tr("কে জমা দিয়েছেন", "Who deposited"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
            TextIn(tr("টাকা (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
            DateField(tr("তারিখ", "Date"), date, { date = it })
            TextIn(tr("নোট (ঐচ্ছিক)", "Note (optional)"), note, { note = it })
            error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
        }
    }
    if (addingMember) {
        var name by remember { mutableStateOf("") }
        SharedDialog(tr("নতুন সদস্য", "New member"), tr("সেভ", "Save"), onDismiss = { addingMember = false }, onConfirm = {
            if (name.isNotBlank()) {
                vm.save("mess_members", row("name" to name.trim()), null)
                addingMember = false
            }
        }) {
            TextIn(tr("নাম", "Name"), name, { name = it })
        }
    }
    editingMember?.let { m ->
        var name by remember(m.id()) { mutableStateOf(m.str("name")) }
        SharedDialog(tr("সদস্যের নাম বদলান", "Rename member"), tr("সেভ", "Save"), onDismiss = { editingMember = null }, onConfirm = {
            if (name.isNotBlank()) {
                vm.save("mess_members", row("name" to name.trim()), m.id())
                editingMember = null
            }
        }) {
            TextIn(tr("নাম", "Name"), name, { name = it })
        }
    }
    if (addingBazaar && members.isNotEmpty()) {
        var member by remember { mutableStateOf(members.first().id()) }
        var amount by remember { mutableStateOf("") }
        var item by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(todayIso()) }
        var error by remember { mutableStateOf<String?>(null) }
        SharedDialog(tr("বাজার এন্ট্রি", "Bazaar entry"), tr("সেভ", "Save"), onDismiss = { addingBazaar = false }, onConfirm = {
            val a = parseAmount(amount)
            when {
                a == null || a <= 0 -> error = tr("সঠিক টাকার পরিমাণ লিখুন", "Enter a valid amount")
                !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
                else -> {
                    vm.save("mess_bazaars", row("member_id" to member, "amount" to a, "item" to item.trim(), "bazaar_date" to date.trim()), null)
                    addingBazaar = false
                }
            }
        }) {
            Text(tr("কে বাজার করেছে", "Who shopped"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
            TextIn(tr("টাকা (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
            TextIn(tr("কী কেনা হলো", "What was bought"), item, { item = it })
            DateField(tr("তারিখ", "Date"), date, { date = it })
            error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
        }
    }
    if (addingMeal && members.isNotEmpty()) {
        var member by remember { mutableStateOf(members.first().id()) }
        var count by remember { mutableStateOf(1.0) }
        var date by remember { mutableStateOf(todayIso()) }
        var error by remember { mutableStateOf<String?>(null) }
        SharedDialog(tr("মিল এন্ট্রি", "Meal entry"), "মিল যোগ করুন", onDismiss = { addingMeal = false }, onConfirm = {
            when {
                count <= 0 -> error = tr("মিলের সংখ্যা ০-এর বেশি হতে হবে", "Meal count must be more than 0")
                !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
                else -> {
                    vm.save("mess_meals", row("member_id" to member, "meal_count" to count, "meal_date" to date.trim()), null)
                    addingMeal = false
                }
            }
        }) {
            Text(tr("কে খেয়েছেন", "Who ate"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(modifier = Modifier.pressScale(), onClick = { count = (count - 0.5).coerceAtLeast(0.0) }) { Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                Text(fmtNum(count), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
                IconButton(modifier = Modifier.pressScale(), onClick = { count += 0.5 }) { Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            }
            Text(tr("ধাপ ০.৫", "Step 0.5"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DateField(tr("তারিখ", "Date"), date, { date = it })
            error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
        }
    }
}

// ======================= Shared UI pieces =======================

@Composable
private fun MonthNav(ym: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(modifier = Modifier.pressScale(), onClick = { onChange(ym.minusMonths(1)) }) { Icon(Icons.Default.ChevronLeft, contentDescription = tr("আগের মাস", "Previous month")) }
        Text(monthYearLabel(ym.year, ym.monthValue), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        IconButton(modifier = Modifier.pressScale(), onClick = { onChange(ym.plusMonths(1)) }) { Icon(Icons.Default.ChevronRight, contentDescription = tr("পরের মাস", "Next month")) }
    }
}

/** Multi-select chips (participants). Wraps into rows of three. */
@Composable
internal fun ToggleChips(options: List<String>, selected: List<String>, onToggle: (String) -> Unit) {
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
internal fun SharedDialog(
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
        confirmButton = { TextButton(modifier = Modifier.pressScale(), onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}
