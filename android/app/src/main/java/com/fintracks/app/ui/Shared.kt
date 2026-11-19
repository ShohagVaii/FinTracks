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
import com.fintracks.app.data.strList
import kotlinx.serialization.json.JsonObject

/** Shared tab: bill split, shopping list, and mess (meal) manager. Same sections as the web app. */
@Composable
fun SharedScreen(state: UiState, vm: AppViewModel) {
    var section by remember { mutableStateOf("bill") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ChoiceChips(listOf("bill" to "বিল ভাগ", "shop" to "শপিং লিস্ট", "mess" to "মেস"), section) { section = it }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (section) {
                "bill" -> BillSplitSection(state, vm)
                "shop" -> ShoppingSection(state, vm)
                else -> MessSection(state, vm)
            }
            Spacer(Modifier.height(90.dp))
        }
    }
}

@Composable
private fun BillSplitSection(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val splits = state.rows("bill_splits")
    val p = state.privacy
    AddButton("নতুন বিল ভাগ") { adding = true }
    if (splits.isEmpty()) EmptyHint("কোনো বিল ভাগ নেই")
    splits.forEach { s ->
        val people = s.strList("participants")
        val share = if (people.isEmpty()) s.num("total_amount") else s.num("total_amount") / people.size
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(s.str("title"), fontWeight = FontWeight.Bold)
                    Text("পরিশোধ করেছেন: ${s.str("payer")} · ${s.str("split_date").take(10)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(taka(s.num("total_amount"), p), fontWeight = FontWeight.Bold)
            }
            KeyValue("জনপ্রতি ভাগ", taka(share, p))
            Text(people.joinToString(", "), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.save("bill_splits", row("is_settled" to !s.bool("is_settled")), s.id()) }) {
                    Text(if (s.bool("is_settled")) "অমীমাংসিত করুন" else "মিটে গেছে")
                }
                DeleteButton { vm.remove("bill_splits", s.id()) }
            }
        }
    }
    if (adding) {
        var title by remember { mutableStateOf("") }
        var total by remember { mutableStateOf("") }
        var people by remember { mutableStateOf("") }
        var payer by remember { mutableStateOf("") }
        FormDialog("নতুন বিল ভাগ", onDismiss = { adding = false }, onConfirm = {
            val t = parseAmount(total)
            val list = people.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (title.isNotBlank() && t != null && t > 0 && list.isNotEmpty()) {
                vm.save("bill_splits", row(
                    "title" to title, "total_amount" to t,
                    "participants" to kotlinx.serialization.json.JsonArray(list.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                    "payer" to payer
                ), null)
                adding = false
            }
        }) {
            TextIn("বিলের নাম (যেমন: বাসা ভাড়া, খাবার)", title, { title = it })
            TextIn("মোট টাকা (৳)", total, { total = it }, number = true)
            TextIn("সবার নাম (কমা দিয়ে)", people, { people = it })
            TextIn("কে পরিশোধ করেছেন", payer, { payer = it })
        }
    }
}

@Composable
private fun ShoppingSection(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val items = state.rows("shopping_items")
    val estimate = items.filter { !it.bool("is_done") }.sumOf { it.num("estimated_price") }
    AppCard { KeyValue("বাকি আইটেমের আনুমানিক দাম", taka(estimate, state.privacy)) }
    AddButton("নতুন আইটেম") { adding = true }
    if (items.isEmpty()) EmptyHint("শপিং লিস্ট খালি")
    items.forEach { i ->
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(i.str("name"), fontWeight = FontWeight.Bold, color = if (i.bool("is_done")) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                    Text(taka(i.num("estimated_price"), state.privacy), fontSize = 12.sp)
                }
                Row {
                    OutlinedButton(onClick = { vm.save("shopping_items", row("is_done" to !i.bool("is_done")), i.id()) }) {
                        Text(if (i.bool("is_done")) "আবার" else "কেনা হয়েছে")
                    }
                    DeleteButton { vm.remove("shopping_items", i.id()) }
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var price by remember { mutableStateOf("") }
        FormDialog("নতুন আইটেম", onDismiss = { adding = false }, onConfirm = {
            if (name.isNotBlank()) {
                vm.save("shopping_items", row("name" to name, "estimated_price" to (parseAmount(price) ?: 0.0)), null)
                adding = false
            }
        }) {
            TextIn("আইটেম", name, { name = it })
            TextIn("আনুমানিক দাম (৳)", price, { price = it }, number = true)
        }
    }
}

@Composable
private fun MessSection(state: UiState, vm: AppViewModel) {
    var addingMember by remember { mutableStateOf(false) }
    var addingBazaar by remember { mutableStateOf(false) }
    var addingMeal by remember { mutableStateOf(false) }
    val members = state.rows("mess_members")
    val bazaars = state.rows("mess_bazaars")
    val meals = state.rows("mess_meals")
    val p = state.privacy
    val totalBazaar = bazaars.sumOf { it.num("amount") }
    val totalMeals = meals.sumOf { it.num("meal_count") }
    val rate = if (totalMeals > 0) totalBazaar / totalMeals else 0.0

    AppCard {
        KeyValue("মোট বাজার", taka(totalBazaar, p))
        KeyValue("মোট মিল", fmtNum(totalMeals))
        KeyValue("মিল রেট", taka(rate, p))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AddButton("সদস্য") { addingMember = true }
        AddButton("বাজার") { addingBazaar = true }
        AddButton("মিল") { addingMeal = true }
    }
    SectionTitle("সদস্যদের হিসাব")
    if (members.isEmpty()) EmptyHint("কোনো সদস্য নেই")
    members.forEach { m ->
        val mealCost = meals.filter { it.str("member_id") == m.id() }.sumOf { it.num("meal_count") } * rate
        val paid = bazaars.filter { it.str("member_id") == m.id() }.sumOf { it.num("amount") }
        val balance = m.num("deposit") + paid - mealCost
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(m.str("name"), fontWeight = FontWeight.Bold)
                DeleteButton { vm.remove("mess_members", m.id()) }
            }
            KeyValue("জমা", taka(m.num("deposit"), p))
            KeyValue("বাজার খরচ", taka(paid, p))
            KeyValue("মিল খরচ", taka(mealCost, p))
            KeyValue("ব্যালেন্স", taka(balance, p), if (balance >= 0) Color(0xFF10B981) else Color(0xFFF43F5E))
        }
    }
    SectionTitle("বাজারের হিসাব")
    bazaars.forEach { b ->
        val who = members.firstOrNull { it.id() == b.str("member_id") }?.str("name").orEmpty()
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$who · ${b.str("item")}", fontWeight = FontWeight.Bold)
                Text(taka(b.num("amount"), p))
            }
            Text(b.str("bazaar_date").take(10), fontSize = 11.sp)
            DeleteButton { vm.remove("mess_bazaars", b.id()) }
        }
    }

    if (addingMember) {
        var name by remember { mutableStateOf("") }
        var deposit by remember { mutableStateOf("0") }
        FormDialog("নতুন সদস্য", onDismiss = { addingMember = false }, onConfirm = {
            if (name.isNotBlank()) { vm.save("mess_members", row("name" to name, "deposit" to (parseAmount(deposit) ?: 0.0)), null); addingMember = false }
        }) {
            TextIn("নাম", name, { name = it })
            TextIn("জমা (৳)", deposit, { deposit = it }, number = true)
        }
    }
    if (addingBazaar && members.isNotEmpty()) {
        var member by remember { mutableStateOf(members.first().id()) }
        var amount by remember { mutableStateOf("") }
        var item by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(todayIso()) }
        FormDialog("বাজার খরচ", onDismiss = { addingBazaar = false }, onConfirm = {
            val a = parseAmount(amount)
            if (a != null && a >= 0 && isValidIsoDate(date)) {
                vm.save("mess_bazaars", row("member_id" to member, "amount" to a, "item" to item, "bazaar_date" to date), null)
                addingBazaar = false
            }
        }) {
            ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
            TextIn("টাকা (৳)", amount, { amount = it }, number = true)
            TextIn("কী কেনা হলো", item, { item = it })
            TextIn("তারিখ (YYYY-MM-DD)", date, { date = it })
        }
    }
    if (addingMeal && members.isNotEmpty()) {
        var member by remember { mutableStateOf(members.first().id()) }
        var count by remember { mutableStateOf("1") }
        var date by remember { mutableStateOf(todayIso()) }
        FormDialog("মিল যোগ করুন", onDismiss = { addingMeal = false }, onConfirm = {
            val c = count.toDoubleOrNull()
            if (c != null && c >= 0 && isValidIsoDate(date)) {
                vm.save("mess_meals", row("member_id" to member, "meal_count" to c, "meal_date" to date), null)
                addingMeal = false
            }
        }) {
            ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
            TextIn("মিল সংখ্যা", count, { count = it }, number = true)
            TextIn("তারিখ (YYYY-MM-DD)", date, { date = it })
        }
    }
}
