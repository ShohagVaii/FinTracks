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
                d.strOrNull("due_date")?.let { Text(tr("শেষ তারিখ", "Due") + ": ${displayDate(it)}", fontSize = 11.sp) }
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
                        Text("${s.str("frequency")} · ${displayDate(s.str("renewal_date")).ifEmpty { "-" }}", fontSize = 11.sp)
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
