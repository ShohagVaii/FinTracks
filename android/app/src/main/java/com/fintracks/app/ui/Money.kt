package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector

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

// ======================= বাজেট, লক্ষ্য, ইনভেস্টমেন্ট, সাবস্ক্রিপশন, অ্যাকাউন্ট =======================

fun investmentKinds() = listOf("Stock" to tr("শেয়ার", "Shares"), "SavingsCertificate" to "সঞ্চয়পত্র", "Crypto" to "ক্রিপ্টো", "Gold" to "সোনা", "RealEstate" to "জমি/বাড়ি", "Other" to "অন্যান্য")

@Composable
fun InvestmentsScreen(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val inv = state.rows("investments")
    val invested = inv.sumOf { it.num("invested") }
    val value = inv.sumOf { it.num("current_value") }
    val p = state.privacy
    Page {
        AppCard {
            KeyValue(tr("মোট বিনিয়োগ", "Total invested"), taka(invested, p))
            KeyValue(tr("বর্তমান মূল্য", "Current value"), taka(value, p))
            KeyValue(tr("লাভ/ক্ষতি", "Gain/loss"), taka(value - invested, p), if (value >= invested) green else red)
        }
        AddButton(tr("নতুন সম্পদ", "New asset")) { adding = true }
        if (inv.isEmpty()) EmptyHint(tr("কোনো সম্পদ নেই", "No assets"))
        inv.forEach { i ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(i.str("name"), fontWeight = FontWeight.Bold)
                        Text(investmentKinds().firstOrNull { it.first == i.str("kind") }?.second ?: i.str("kind"), fontSize = 11.sp)
                    }
                    DeleteButton { vm.remove("investments", i.id()) }
                }
                KeyValue(tr("বিনিয়োগ / বর্তমান", "Invested / current"), "${taka(i.num("invested"), p)} / ${taka(i.num("current_value"), p)}")
                OutlinedButton(onClick = {
                    val now = i.num("current_value")
                    vm.save("investments", row("current_value" to now), i.id())
                }) { Text(tr("মূল্য হালনাগাদ", "Update value")) }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("Other") }
        var invested by remember { mutableStateOf("") }
        var current by remember { mutableStateOf("") }
        var bought by remember { mutableStateOf(todayIso()) }
        FormDialog(tr("নতুন সম্পদ", "New asset"), onDismiss = { adding = false }, onConfirm = {
            val iv = parseAmount(invested); val cv = parseAmount(current) ?: iv
            if (name.isNotBlank() && iv != null && iv >= 0 && cv != null && isValidIsoDate(bought)) {
                vm.save("investments", row("name" to name, "kind" to kind, "invested" to iv, "current_value" to cv, "bought_on" to bought), null)
                adding = false
            }
        }) {
            ChoiceChips(investmentKinds(), kind) { kind = it }
            TextIn(tr("নাম", "Name"), name, { name = it })
            TextIn(tr("বিনিয়োগের পরিমাণ (৳)", "Invested amount (৳)"), invested, { invested = it }, number = true)
            TextIn(tr("বর্তমান মূল্য (৳)", "Current value (৳)"), current, { current = it }, number = true)
            DateField(tr("কেনার তারিখ", "Purchase date"), bought, { bought = it })
        }
    }
}

/** Monthly equivalent of one subscription amount (daily = 365/12 days, weekly = 52/12 weeks). */
fun subscriptionMonthly(s: JsonObject): Double = when (s.str("frequency")) {
    "Daily" -> s.num("amount") * 365.0 / 12.0
    "Weekly" -> s.num("amount") * 52.0 / 12.0
    "Yearly" -> s.num("amount") / 12.0
    else -> s.num("amount")
}

/** The first renewal on or after [today], stepping forward from the stored renewal date. Null if no valid date. */
fun nextRenewal(s: JsonObject, today: java.time.LocalDate): java.time.LocalDate? {
    val base = runCatching { java.time.LocalDate.parse(s.str("renewal_date").take(10)) }.getOrNull() ?: return null
    val step: (Long) -> java.time.LocalDate = when (s.str("frequency")) {
        "Daily" -> { n -> base.plusDays(n) }
        "Weekly" -> { n -> base.plusWeeks(n) }
        "Yearly" -> { n -> base.plusYears(n) }
        else -> { n -> base.plusMonths(n) }
    }
    var n = 0L
    var d = base
    while (d.isBefore(today) && n < 100_000L) { n++; d = step(n) }
    return d
}

@Composable
fun SubscriptionsScreen(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val subs = state.rows("subscriptions")
    val monthly = subs.filter { it.bool("is_active") }.sumOf { subscriptionMonthly(it) }
    val today = java.time.LocalDate.now()
    Page {
        AppCard { KeyValue(tr("মাসিক আনুমানিক খরচ", "Estimated monthly cost"), taka(monthly, state.privacy), red) }
        AddButton(tr("নতুন সাবস্ক্রিপশন", "New subscription")) { adding = true }
        if (subs.isEmpty()) EmptyHint(tr("কোনো সাবস্ক্রিপশন নেই", "No subscriptions"))
        subs.forEach { s ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(s.str("name"), fontWeight = FontWeight.Bold)
                        Text("${s.str("frequency")} · ${nextRenewal(s, today)?.let { displayDate(it.toString()) } ?: "-"}", fontSize = 11.sp)
                    }
                    Text(taka(s.num("amount"), state.privacy), fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.save("subscriptions", row("is_active" to !s.bool("is_active")), s.id()) }) {
                        Text(if (s.bool("is_active")) tr("বন্ধ করুন", "Close") else tr("চালু করুন", "Activate"))
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
        FormDialog(tr("নতুন সাবস্ক্রিপশন", "New subscription"), onDismiss = { adding = false }, onConfirm = {
            val a = parseAmount(amount)
            if (name.isNotBlank() && a != null && a >= 0 && isValidIsoDate(renewal)) {
                vm.save("subscriptions", row("name" to name, "amount" to a, "frequency" to freq, "renewal_date" to renewal, "currency" to state.currency), null)
                adding = false
            }
        }) {
            TextIn(tr("সার্ভিসের নাম", "Service name"), name, { name = it })
            TextIn(tr("পরিমাণ (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
            ChoiceChips(listOf("Daily" to tr("দৈনিক", "Daily"), "Weekly" to tr("সাপ্তাহিক", "Weekly"), "Monthly" to tr("মাসিক", "Monthly"), "Yearly" to tr("বার্ষিক", "Yearly")), freq) { freq = it }
            DateField(tr("পরবর্তী নবায়ন", "Next renewal"), renewal, { renewal = it })
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
            Text(tr("ট্রান্সফার করতে + বাটনে গিয়ে 'ট্রান্সফার' বাছাই করুন।", "To transfer, tap + and choose 'Transfer'."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AddButton(tr("নতুন অ্যাকাউন্ট", "New account")) { adding = true }
        if (accounts.isEmpty()) EmptyHint(tr("কোনো অ্যাকাউন্ট নেই (ক্যাশ, bKash, Nagad, ব্যাংক)", "No accounts (cash, bKash, Nagad, bank)"))
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
        FormDialog(tr("নতুন অ্যাকাউন্ট", "New account"), onDismiss = { adding = false }, onConfirm = {
            val o = parseAmount(opening) ?: 0.0
            if (name.isNotBlank()) {
                vm.save("accounts", row("name" to name, "kind" to kind, "opening_balance" to o), null)
                adding = false
            }
        }) {
            TextIn(tr("নাম", "Name"), name, { name = it })
            ChoiceChips(listOf("Cash", "Bkash", "Nagad", "Rocket", "Bank", "Card").map { it to it }, kind) { kind = it }
            TextIn(tr("শুরুর ব্যালেন্স (৳)", "Opening balance (৳)"), opening, { opening = it }, number = true)
        }
    }
}
