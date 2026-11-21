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

// ======================= ধার ও পাওনা =======================

/**
 * Local calendar date (yyyy-MM-dd) of a server timestamp such as "2026-11-19T12:06:24.381962+00:00".
 * Converting to the device zone keeps a late-evening Bangladesh entry on the right day.
 */
fun localDateOf(timestamp: String): String = runCatching {
    java.time.OffsetDateTime.parse(timestamp).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate().toString()
}.getOrElse { timestamp.take(10) }

/** Amount already paid back on one debt (sum of its payment rows). */
fun debtPaid(debt: JsonObject, payments: List<JsonObject>): Double =
    payments.filter { it.str("debt_id") == debt.id() }.sumOf { it.num("amount") }

/** Still owed on one debt: amount minus payments, never below zero. */
fun debtRemaining(debt: JsonObject, payments: List<JsonObject>): Double =
    (debt.num("amount") - debtPaid(debt, payments)).coerceAtLeast(0.0)

/** Total still owed in one direction ("owe" = I owe, "owed" = owed to me), counting only unpaid parts. */
fun debtsRemaining(state: UiState, direction: String): Double {
    val payments = state.rows("debt_payments")
    return state.rows("debts").filter { it.str("direction") == direction }.sumOf { debtRemaining(it, payments) }
}

// Design colours from the web reference (views/FinancialToolsView.tsx): violet submit and filled toggle.
private val DebtViolet = Color(0xFF7C3AED)
private val DebtIndigo = Color(0xFF4F46E5)

/** Coloured summary card: green "আমি পাবো", red "আমি দেবো". */
@Composable
private fun DebtSummaryCard(label: String, amount: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Black, color = color, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text(amount, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp)) }
        }
    }
}

/** Filled-violet segment used for the বকেয়া | পরিশোধিত toggle. */
@Composable
private fun DebtSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) DebtViolet else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Violet→indigo gradient submit button, same look as the AI send button. */
@Composable
private fun DebtGradientButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fill = if (enabled) listOf(DebtViolet, DebtIndigo) else listOf(Color.Gray.copy(alpha = 0.4f), Color.Gray.copy(alpha = 0.4f))
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(fill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp) }
}

/** Dialog shell shared by the add-debt and payment forms, with the gradient submit button. */
@Composable
private fun DebtDialog(
    title: String,
    confirmText: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        },
        confirmButton = { DebtGradientButton(confirmText, confirmEnabled, onConfirm) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}

@Composable
fun DebtsScreen(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<JsonObject?>(null) }
    var showPaid by remember { mutableStateOf(false) }
    val debts = state.rows("debts")
    val payments = state.rows("debt_payments")
    val p = state.privacy
    val owe = debtsRemaining(state, "owe")
    val owed = debtsRemaining(state, "owed")
    val shown = debts.filter { (debtRemaining(it, payments) > 0.005) != showPaid }
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DebtSummaryCard(tr("আমি পাবো", "I will get"), taka(owed, p), green, Icons.Default.ArrowUpward, Modifier.weight(1f))
            DebtSummaryCard(tr("আমি দেবো", "I will pay"), taka(owe, p), red, Icons.Default.ArrowDownward, Modifier.weight(1f))
        }
        DebtPlanner(debts, payments, p)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DebtSegment(tr("বকেয়া", "Open"), !showPaid) { showPaid = false }
                DebtSegment(tr("পরিশোধিত", "Settled"), showPaid) { showPaid = true }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(DebtViolet, DebtIndigo)))
                    .clickable { adding = true },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Add, contentDescription = tr("নতুন ধার / পাওনা", "New debt / due"), tint = Color.White) }
        }
        if (shown.isEmpty()) {
            EmptyHint(
                if (showPaid) tr("কোনো পরিশোধিত ধার নেই", "No settled debts")
                else tr("কোনো বকেয়া ধার বা পাওনা নেই", "No open debts or dues")
            )
        }
        shown.forEach { d ->
            val left = debtRemaining(d, payments)
            val isOwe = d.str("direction") == "owe"
            val dirColor = if (isOwe) red else green
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(dirColor.copy(alpha = 0.12f))
                                .border(1.dp, dirColor.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (isOwe) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, contentDescription = null, tint = dirColor)
                        }
                        Column {
                            Text(d.str("person"), fontWeight = FontWeight.Bold)
                            Text(
                                if (isOwe) tr("আমি দেবো", "I will pay") else tr("আমি পাবো", "I will get"),
                                fontSize = 11.sp, color = dirColor, fontWeight = FontWeight.Bold
                            )
                            d.strOrNull("created_at")?.let { Text(tr("নেওয়া", "Taken") + ": ${displayDate(localDateOf(it))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            d.strOrNull("due_date")?.let { Text(tr("শেষ তারিখ", "Due") + ": ${displayDate(it)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            taka(left, p), fontWeight = FontWeight.Black, fontSize = 17.sp,
                            color = if (left <= 0.005) MaterialTheme.colorScheme.onSurfaceVariant else dirColor
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier.clip(CircleShape)
                                .background(if (left > 0.005) dirColor.copy(alpha = 0.1f) else Color.Transparent)
                                .border(1.dp, if (left > 0.005) dirColor.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
                                .clickable(enabled = left > 0.005) { paying = d }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (left > 0.005) tr("পরিশোধ করুন", "Pay") else tr("পরিশোধিত", "Settled"),
                                fontSize = 10.sp, fontWeight = FontWeight.Black,
                                color = if (left > 0.005) dirColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (d.str("note").isNotBlank()) Text(d.str("note"), fontSize = 11.sp)
                Text(
                    tr("মোট: ", "Total: ") + taka(d.num("amount"), p) + tr(" · পরিশোধ: ", " · Paid: ") + taka(debtPaid(d, payments), p) + tr(" · বাকি: ", " · Left: ") + taka(left, p),
                    fontSize = 11.sp
                )
                payments.filter { it.str("debt_id") == d.id() }.sortedByDescending { it.str("paid_on") }.forEach { pay ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(displayDate(pay.str("paid_on")) + (if (pay.str("note").isNotBlank()) " · ${pay.str("note")}" else ""), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(taka(pay.num("amount"), p), fontSize = 11.sp)
                        DeleteButton { vm.remove("debt_payments", pay.id()) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
        val a = parseAmount(amount)
        val valid = person.isNotBlank() && a != null && a > 0 && (due.isBlank() || isValidIsoDate(due))
        DebtDialog(
            title = tr("নতুন ধার / পাওনা", "New debt / due"),
            confirmText = tr("ডাটা সেভ করুন", "Save data"),
            confirmEnabled = valid,
            onDismiss = { adding = false },
            onConfirm = {
                if (a != null) {
                    vm.save("debts", row("person" to person, "amount" to a, "direction" to direction, "due_date" to nonEmpty(due), "note" to note), null)
                    adding = false
                }
            }
        ) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DirectionSegment(tr("আমি দেবো", "I will pay"), direction == "owe", red, Modifier.weight(1f)) { direction = "owe" }
                DirectionSegment(tr("আমি পাবো", "I will get"), direction == "owed", green, Modifier.weight(1f)) { direction = "owed" }
            }
            TextIn(tr("ব্যক্তির নাম", "Person's name"), person, { person = it })
            TextIn(tr("পরিমাণ (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
            DateField(tr("শেষ তারিখ (ঐচ্ছিক)", "Due date (optional)"), due, { due = it }, optional = true)
            TextIn(tr("নোট", "Note"), note, { note = it })
        }
    }
    paying?.let { d -> DebtPaymentDialog(d, debtRemaining(d, payments), vm) { paying = null } }
}

/** Direction choice inside the add form: filled rose for আমি দেবো, filled green for আমি পাবো. */
@Composable
private fun DirectionSegment(label: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) color else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Black, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
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

/** Debt payoff plan (snowball: smallest debt first). Interest-free estimate: months = cumulative debt / monthly payment. */
@Composable
fun DebtPlanner(debts: List<JsonObject>, payments: List<JsonObject>, privacy: Boolean) {
    var monthly by remember { mutableStateOf("") }
    val owes = debts.filter { it.str("direction") == "owe" && debtRemaining(it, payments) > 0.005 }.sortedBy { debtRemaining(it, payments) }
    val m = parseAmount(monthly) ?: 0.0
    AppCard {
        Text(tr("ধার পরিশোধের পরিকল্পনা (ছোট ধার আগে)", "Repayment plan (smallest debts first)"), fontWeight = FontWeight.Bold)
        TextIn(tr("প্রতি মাসে কত দিতে পারবেন (৳)", "How much per month (৳)"), monthly, { monthly = it }, number = true)
        when {
            owes.isEmpty() -> Text(tr("কোনো ধার নেই", "No loans"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            m <= 0 -> Text(tr("মাসিক পরিমাণ লিখুন", "Enter a monthly amount"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                var cumulative = 0.0
                owes.forEach { d ->
                    val left = debtRemaining(d, payments)
                    cumulative += left
                    Text("${d.str("person")}: ${taka(left, privacy)} → ${ceil(cumulative / m).toInt()} মাসে শেষ", fontSize = 13.sp)
                }
                Text(tr("সব ধার শেষ হবে ${ceil(cumulative / m).toInt()} মাসে (সুদ ছাড়া হিসাব)", "All debts clear in ${ceil(cumulative / m).toInt()} months (no interest)"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Records a partial (or full) repayment of one ধার/পাওনা. No transaction is created. */
@Composable
private fun DebtPaymentDialog(debt: JsonObject, balance: Double, vm: AppViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayIso()) }
    var note by remember { mutableStateOf("") }
    val a = parseAmount(amount)
    val tooMuch = a != null && a > balance + 0.005
    val valid = a != null && a > 0 && !tooMuch && isValidIsoDate(date) && balance > 0.005
    DebtDialog(
        title = tr("পরিশোধ", "Pay"),
        confirmText = tr("পরিশোধ সেভ করুন", "Save payment"),
        confirmEnabled = valid,
        onDismiss = onDismiss,
        onConfirm = {
            if (a != null) {
                vm.recordPersonalDebtPayment(debt.id(), a, date.trim(), note.trim())
                onDismiss()
            }
        }
    ) {
        Text(tr("বর্তমান বাকি: ", "Balance: ") + taka(balance, false), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        TextIn(tr("পরিমাণ (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
        if (tooMuch) Text(tr("বাকির বেশি টাকা দেওয়া যাবে না", "Payment cannot exceed the balance"), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        DateField(tr("তারিখ", "Date"), date, { date = it })
        TextIn(tr("নোট (ঐচ্ছিক)", "Note (optional)"), note, { note = it })
    }
}
