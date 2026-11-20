package com.fintracks.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fintracks.app.data.bool
import com.fintracks.app.data.strList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import kotlinx.serialization.json.JsonObject

private val incomeGreen = Color(0xFF10B981)
private val expenseRed = Color(0xFFF43F5E)

/** Balance of one account from its opening balance and all of its transactions. */
fun accountBalance(acc: JsonObject, txs: List<JsonObject>): Double {
    val id = acc.id()
    var b = acc.num("opening_balance")
    for (t in txs) {
        val amt = t.num("amount")
        val from = t.accountId() == id
        val to = t.toAccountId() == id
        when (t.str("type")) {
            "Income" -> if (from) b += amt
            "Expense" -> if (from) b -= amt
            "Transfer" -> { if (from) b -= amt; if (to) b += amt }
        }
    }
    return b
}

@Composable
fun DashboardScreen(state: UiState, vm: AppViewModel, openTx: (JsonObject?) -> Unit, openOverlay: (Overlay) -> Unit) {
    val txs = state.rows("transactions")
    val accounts = state.rows("accounts")
    val month = monthPrefix()
    val monthTx = txs.filter { dateOf(it, "tx_date").startsWith(month) }
    val income = monthTx.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
    val expense = monthTx.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
    val total = accounts.sumOf { accountBalance(it, txs) } +
        (if (accounts.isEmpty()) txs.sumOf { if (it.str("type") == "Income") it.num("amount") else if (it.str("type") == "Expense") -it.num("amount") else 0.0 } else 0.0)
    val savingsRate = if (income > 0) ((income - expense) / income * 100) else 0.0
    val p = state.privacy

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppCard {
            Text("মোট ব্যালেন্স", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(taka(total, p), fontSize = 30.sp, fontWeight = FontWeight.Black)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("আয় (এই মাস)", fontSize = 12.sp, color = incomeGreen); Text(taka(income, p), fontWeight = FontWeight.Bold) }
                Column(horizontalAlignment = Alignment.End) { Text("ব্যয় (এই মাস)", fontSize = 12.sp, color = expenseRed); Text(taka(expense, p), fontWeight = FontWeight.Bold) }
            }
            if (income > 0) Text("সঞ্চয়ের হার: ${"%.0f".format(savingsRate)}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HeatmapCard(txs, p)

        if (state.pendingSms.isNotEmpty()) {
            SectionTitle("SMS থেকে নতুন লেনদেন")
            state.pendingSms.take(3).forEach { s ->
                AppCard(borderColor = Sky400.copy(alpha = 0.4f)) {
                    Text("${s.provider} · ${s.type} · ${taka(s.amount, p)}", fontWeight = FontWeight.Bold)
                    Text(s.raw, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.acceptSms(s) }) { Text("যোগ করুন") }
                        OutlinedButton(onClick = { vm.dismissSms(s) }) { Text("বাতিল") }
                    }
                }
            }
        }

        if (accounts.isNotEmpty()) {
            SectionTitle("অ্যাকাউন্ট")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                accounts.forEach { acc ->
                    AppCard(onClick = { openOverlay(Overlay.ACCOUNTS) }) {
                        Text(acc.str("name"), fontWeight = FontWeight.Bold)
                        Text(acc.str("kind"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(taka(accountBalance(acc, txs), p), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        SectionTitle("এই মাসের বাজেট")
        val budgets = state.rows("budgets")
        if (budgets.isEmpty()) {
            AppCard(onClick = { openOverlay(Overlay.BUDGETS) }) { Text("বাজেট সেট করতে ট্যাপ করুন", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            AppCard {
                budgets.forEach { b ->
                    val cat = b.str("category")
                    val spent = monthTx.filter { it.str("type") == "Expense" && it.str("category") == cat }.sumOf { it.num("amount") }
                    val limit = b.num("monthly_limit")
                    val frac = if (limit > 0) (spent / limit).toFloat() else 0f
                    val color = if (frac >= 1f) expenseRed else if (frac >= 0.8f) Amber500 else Sky500
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${catIcon(cat)} $cat")
                        Text("${taka(spent, p)} / ${taka(limit, p)}", fontSize = 12.sp)
                    }
                    ProgressBar(frac, color)
                }
            }
        }

        SectionTitle("খরচের ট্রেন্ড (এই মাস)")
        AppCard {
            val days = (1..31).map { d ->
                val key = month + "-" + d.toString().padStart(2, '0')
                monthTx.filter { it.str("type") == "Expense" && dateOf(it, "tx_date") == key }.sumOf { it.num("amount") }
            }
            LineChart(days.take(maxOf(1, lastIndexWithData(days) + 1)), expenseRed)
        }

        SectionTitle("সাম্প্রতিক লেনদেন")
        if (txs.isEmpty()) EmptyHint("এখনো কোনো লেনদেন নেই। নিচের + বাটন দিয়ে যোগ করুন।")
        txs.take(5).forEach { t -> TxItem(t, state, onClick = { openTx(t) }, onDelete = null) }
        Spacer(Modifier.height(90.dp))
    }
}

private fun lastIndexWithData(list: List<Double>): Int = list.indexOfLast { it > 0.0 }.coerceAtLeast(0)

/** One transaction line: icon, note, mode, date, amount. */
@Composable
fun TxItem(t: JsonObject, state: UiState, onClick: () -> Unit, onDelete: (() -> Unit)?, onShare: (() -> Unit)? = null) {
    val type = t.str("type")
    val amountColor = when (type) { "Income" -> incomeGreen; "Expense" -> expenseRed; else -> Sky400 }
    val sign = when (type) { "Income" -> "+"; "Expense" -> "-"; else -> "⇄" }
    AppCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(catIcon(t.str("category")), fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.strOrNull("note")?.takeIf { it.isNotBlank() } ?: t.str("category"), fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${dateOf(t, "tx_date")} · ${t.str("payment_mode")} ${t.str("mood")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$sign ${taka(t.num("amount"), state.privacy)}", color = amountColor, fontWeight = FontWeight.Bold)
            if (onShare != null) OutlinedButton(onClick = onShare, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text("শেয়ার", fontSize = 11.sp) }
            if (onDelete != null) DeleteButton(onDelete)
        }
    }
}

@Composable
fun TransactionsScreen(state: UiState, vm: AppViewModel, openTx: (JsonObject?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var period by remember { mutableStateOf(Period.MONTH) }
    var customFrom by remember { mutableStateOf(todayIso().substring(0, 8) + "01") }
    var customTo by remember { mutableStateOf(todayIso()) }
    var busy by remember { mutableStateOf(false) }
    val range = rangeFor(period, customFrom, customTo)
    val all = state.rows("transactions")
    val inRange = filterByRange(all, range)
    val filtered = inRange.filter { t ->
        (filter == "all" || t.str("type").lowercase() == filter.lowercase()) &&
            (query.isBlank() || t.str("note").contains(query, true) || t.str("category").contains(query, true) || t.strList("tags").any { it.contains(query, true) })
    }.sortedByDescending { dateOf(it, "tx_date") }
    val income = inRange.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
    val expense = inRange.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("খুঁজুন (নোট / ক্যাটাগরি)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PeriodPicker(period, customFrom, customTo, onPeriod = { period = it }, onFrom = { customFrom = it }, onTo = { customTo = it })
        ChoiceChips(listOf("all" to "সব", "income" to "আয়", "expense" to "ব্যয়", "transfer" to "ট্রান্সফার"), filter) { filter = it }
        Text("${range.label} · আয় ${taka(income, state.privacy)} · ব্যয় ${taka(expense, state.privacy)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Export.Format.XLSX to "Excel", Export.Format.PDF to "PDF", Export.Format.CSV to "CSV").forEach { (fmt, label) ->
                OutlinedButton(enabled = !busy && filtered.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { Export.listFile(ctx, fmt, state, filtered, range.label) }
                            Export.share(ctx, file, fmt.mime, "FinTrack - ${range.label}")
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "এক্সপোর্ট ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally { busy = false }
                    }
                }) { Text("${label} এক্সপোর্ট") }
            }
        }
        if (filtered.isEmpty()) EmptyHint("এই সময়সীমায় কোনো লেনদেন পাওয়া যায়নি")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id() }) { t ->
                TxItem(
                    t, state,
                    onClick = { openTx(t) },
                    onDelete = { vm.remove("transactions", t.id()) },
                    onShare = {
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) { Export.receiptFile(ctx, state, t) }
                                Export.share(ctx, file, "application/pdf", "FinTrack লেনদেন ভাউচার")
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "শেয়ার ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
}

/** Add or edit a transaction. Transfers pick two accounts. */
@Composable
fun TransactionDialog(state: UiState, existing: JsonObject?, onDismiss: () -> Unit, onSave: (JsonObject, String?) -> Unit) {
    var type by remember { mutableStateOf(existing?.str("type")?.takeIf { it.isNotEmpty() } ?: "Expense") }
    var amount by remember { mutableStateOf(existing?.let { fmtNum(it.num("amount")) } ?: "") }
    var category by remember { mutableStateOf(existing?.str("category")?.takeIf { it.isNotEmpty() } ?: "খাদ্য") }
    var note by remember { mutableStateOf(existing?.str("note") ?: "") }
    var date by remember { mutableStateOf(existing?.let { dateOf(it, "tx_date") }?.takeIf { it.isNotEmpty() } ?: todayIso()) }
    var mode by remember { mutableStateOf(existing?.str("payment_mode")?.takeIf { it.isNotEmpty() } ?: "Cash") }
    var mood by remember { mutableStateOf(existing?.str("mood") ?: "") }
    var accountId by remember { mutableStateOf(existing?.accountId() ?: "") }
    var toAccountId by remember { mutableStateOf(existing?.toAccountId() ?: "") }
    var recurring by remember { mutableStateOf(existing?.bool("is_recurring") == true) }
    var tagsText by remember { mutableStateOf(existing?.strList("tags")?.joinToString(", ") ?: "") }
    var showCalc by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cats = if (type == "Income") INCOME_CATS else EXPENSE_CATS
    val accounts = state.rows("accounts")

    if (showCalc) {
        CalculatorDialog(
            onUse = { amount = fmtNum(it); showCalc = false },
            onDismiss = { showCalc = false }
        )
    }

    FormDialog(
        title = if (existing == null) "নতুন লেনদেন" else "লেনদেন সম্পাদনা",
        onDismiss = onDismiss,
        onConfirm = {
            val amt = parseAmount(amount)
            when {
                amt == null || amt <= 0 -> error = "সঠিক পরিমাণ লিখুন"
                !isValidIsoDate(date) -> error = "তারিখ লিখুন (YYYY-MM-DD)"
                type == "Transfer" && (accountId.isEmpty() || toAccountId.isEmpty() || accountId == toAccountId) ->
                    error = "দুটি ভিন্ন অ্যাকাউন্ট বাছাই করুন"
                else -> {
                    onSave(
                        row(
                            "type" to type, "amount" to amt, "currency" to state.currency,
                            "category" to if (type == "Transfer") "ট্রান্সফার" else category,
                            "note" to note, "tx_date" to date, "payment_mode" to mode, "mood" to mood,
                            "account_id" to nonEmpty(accountId), "to_account_id" to if (type == "Transfer") nonEmpty(toAccountId) else null,
                            "is_recurring" to recurring,
                            "tags" to JsonArray(tagsText.split(',', '#').map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { JsonPrimitive(it) })
                        ),
                        existing?.id()
                    )
                }
            }
        }
    ) {
        ChoiceChips(listOf("Expense" to "ব্যয়", "Income" to "আয়", "Transfer" to "ট্রান্সফার"), type) { type = it; category = if (it == "Income") "বেতন" else "খাদ্য" }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextIn("পরিমাণ (৳)", amount, { amount = it }, number = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { showCalc = true }) { Text("🧮") }
        }
        if (type != "Transfer") {
            Text("ক্যাটাগরি", fontSize = 12.sp)
            ChoiceChips(cats.map { it.name to "${it.icon} ${it.name}" }, category) { category = it }
        }
        if (accounts.isNotEmpty()) {
            Text(if (type == "Transfer") "কোথা থেকে" else "অ্যাকাউন্ট", fontSize = 12.sp)
            ChoiceChips(listOf("" to "কোনোটি নয়") + accounts.map { it.id() to it.str("name") }, accountId) { accountId = it }
        }
        if (type == "Transfer") {
            Text("কোথায়", fontSize = 12.sp)
            ChoiceChips(accounts.map { it.id() to it.str("name") }, toAccountId) { toAccountId = it }
        }
        TextIn("তারিখ (YYYY-MM-DD)", date, { date = it })
        Text("পেমেন্ট মোড", fontSize = 12.sp)
        ChoiceChips(PAYMENT_MODES.map { it to it }, mode) { mode = it }
        Text("মুড", fontSize = 12.sp)
        ChoiceChips(MOODS.map { it to it }, mood) { mood = if (mood == it) "" else it }
        TextIn("নোট", note, { note = it })
        TextIn("ট্যাগ (কমা দিয়ে, যেমন: জরুরি, বাজার)", tagsText, { tagsText = it })
        SwitchRow("প্রতি মাসে পুনরাবৃত্ত", recurring) { recurring = it }
        error?.let { Text(it, color = expenseRed, fontSize = 12.sp) }
    }
}
