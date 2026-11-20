package com.fintracks.app.ui

import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.saveable.rememberSaveable
import com.fintracks.app.data.SmsParser
import com.fintracks.app.data.SmsSuggestion
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
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

private data class DashStats(val income: Double, val expense: Double, val total: Double, val savingsRate: Double)

@Composable
fun DashboardScreen(state: UiState, vm: AppViewModel, openTx: (JsonObject?) -> Unit, openReceipt: (JsonObject) -> Unit, onTxLongPress: (JsonObject) -> Unit, openOverlay: (Overlay) -> Unit) {
    val txs = state.rows("transactions")
    val accounts = state.rows("accounts")
    val p = state.privacy
    val month = monthPrefix()
    var span by rememberSaveable { mutableStateOf(HomeSpan.MONTH) }
    var offset by rememberSaveable { mutableStateOf(0) }
    val period = homePeriod(span, offset)
    val jumpToMonth: (java.time.YearMonth) -> Unit = { target ->
        span = HomeSpan.MONTH
        offset = java.time.YearMonth.now().until(target, java.time.temporal.ChronoUnit.MONTHS).toInt()
    }
    // Computed once per data change, not on every recomposition (this was a main cause of lag).
    val monthTx = remember(state.tables) { txs.filter { dateOf(it, "tx_date").startsWith(month) } }
    val rangeTx = remember(state.tables, period.from, period.to) {
        txs.filter { val d = dateOf(it, "tx_date"); d >= period.from && d <= period.to }
    }
    val stats = remember(state.tables, period.from, period.to) {
        val income = rangeTx.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
        val expense = rangeTx.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
        val total = accounts.sumOf { accountBalance(it, txs) } +
            (if (accounts.isEmpty()) txs.sumOf { if (it.str("type") == "Income") it.num("amount") else if (it.str("type") == "Expense") -it.num("amount") else 0.0 } else 0.0)
        DashStats(income, expense, total, if (income > 0) ((income - expense) / income * 100) else 0.0)
    }
    val (income, expense, total, savingsRate) = stats
    val net = income - expense

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Period tabs + timeline navigator, like the video's দৈনিক / মাসিক / বাৎসরিক pill.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(VideoCardLift.copy(alpha = 0.5f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                .padding(4.dp)
        ) {
            HomeSpan.values().forEach { sp ->
                val on = sp == span
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) Sky500 else Color.Transparent)
                        .clickable { span = sp; offset = 0 }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(sp.label, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            NavSquare(Icons.Default.ChevronLeft, enabled = true) { offset -= 1 }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(period.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("টাইমলাইন ভিউ", fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NavSquare(Icons.Default.ChevronRight, enabled = offset < 0) { offset += 1 }
        }
        // Profit hero — like the video's "নিট লাভ (PROFIT)" card: large radius, green trend icon.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(40.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF0B1F1C), VideoCardLift.copy(alpha = 0.6f))))
                .border(1.dp, incomeGreen.copy(alpha = 0.22f), RoundedCornerShape(40.dp))
                .padding(24.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("নিট লাভ (PROFIT)", fontSize = 12.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(taka(net, p), fontSize = 36.sp, fontWeight = FontWeight.Black, color = if (net >= 0) incomeGreen else expenseRed)
                }
                Box(Modifier.size(64.dp).clip(CircleShape).background(incomeGreen.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.TrendingUp, contentDescription = null, tint = incomeGreen, modifier = Modifier.size(30.dp))
                }
            }
            if (income > 0) Text("সঞ্চয়ের হার: ${"%.0f".format(savingsRate)}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatPill("মোট আয়", taka(income, p), incomeGreen, Modifier.weight(1f))
            StatPill("মোট ব্যয়", taka(expense, p), expenseRed, Modifier.weight(1f))
        }
        // Wallet card — all-time balance.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(40.dp))
                .background(VideoCardLift.copy(alpha = 0.55f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(40.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("অবশিষ্ট ব্যালেন্স (ALL TIME)", fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(taka(total, p), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Sky400.copy(alpha = 0.6f), modifier = Modifier.size(32.dp))
        }
        CreditDueCard(state) { openOverlay(Overlay.CREDIT) }
        // Heatmap follows the timeline month; its ‹ › buttons move the timeline too (one shared state).
        val heatMonth = heatmapMonthOf(span, offset, period)
        val nextMonth: (() -> Unit)? = if (heatMonth < java.time.YearMonth.now()) ({ jumpToMonth(heatMonth.plusMonths(1)) }) else null
        HeatmapCard(txs, p, heatMonth, onPrev = { jumpToMonth(heatMonth.minusMonths(1)) }, onNext = nextMonth)
        SmsParserCard(state, vm)

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
        txs.take(5).forEach { t -> TxItem(t, state, onClick = { openReceipt(t) }, onDelete = null, onLongClick = { onTxLongPress(t) }) }
        Spacer(Modifier.height(90.dp))
    }
}

private fun lastIndexWithData(list: List<Double>): Int = list.indexOfLast { it > 0.0 }.coerceAtLeast(0)

/** One transaction line: icon, note, mode, date, amount. */
@Composable
fun TxItem(t: JsonObject, state: UiState, onClick: () -> Unit, onDelete: (() -> Unit)?, onShare: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    val type = t.str("type")
    val amountColor = when (type) { "Income" -> incomeGreen; "Expense" -> expenseRed; else -> Sky400 }
    val sign = when (type) { "Income" -> "+"; "Expense" -> "-"; else -> "⇄" }
    AppCard(onClick = onClick, onLongClick = onLongClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(VideoCardLift.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) { Text(t.str("mood").ifBlank { catIcon(t.str("category")) }, fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.strOrNull("note")?.takeIf { it.isNotBlank() } ?: t.str("category"), fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${dateOf(t, "tx_date")} · ${t.str("payment_mode")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$sign ${taka(t.num("amount"), state.privacy)}", color = amountColor, fontWeight = FontWeight.Bold)
            if (onShare != null) IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "শেয়ার", tint = Sky400) }
            if (onDelete != null) DeleteButton(onDelete)
        }
    }
}

@Composable
fun TransactionsScreen(state: UiState, vm: AppViewModel, openTx: (JsonObject?) -> Unit, openReceipt: (JsonObject) -> Unit, onTxLongPress: (JsonObject) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var segment by remember { mutableStateOf("history") } // history | auto (created from বাকি / SMS)
    var modeFilter by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(Period.MONTH) }
    var customFrom by remember { mutableStateOf(todayIso().substring(0, 8) + "01") }
    var customTo by remember { mutableStateOf(todayIso()) }
    var busy by remember { mutableStateOf(false) }
    val range = rangeFor(period, customFrom, customTo)
    val all = state.rows("transactions").filter { segment == "history" || it.str("source").isNotEmpty() }
    val inRange = filterByRange(all, range)
    // "#জরুরি" and "জরুরি" both find the tag জরুরি.
    val q = query.trim().removePrefix("#").trim()
    val filtered = inRange.filter { t ->
        (filter == "all" || t.str("type").lowercase() == filter.lowercase()) &&
            (modeFilter.isEmpty() || t.str("payment_mode") == modeFilter) &&
            (q.isEmpty() || t.str("note").contains(q, true) || t.str("category").contains(q, true) || t.strList("tags").any { it.contains(q, true) })
    }.sortedByDescending { dateOf(it, "tx_date") }
    // Transfers are not income or expense, so they are left out of these totals.
    val income = inRange.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
    val expense = inRange.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceChips(listOf("history" to "লেনদেনের ইতিহাস", "auto" to "অটো-লেনদেন"), segment) { segment = it }
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("খুঁজুন (নোট / ক্যাটাগরি / #ট্যাগ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PeriodPicker(period, customFrom, customTo, onPeriod = { period = it }, onFrom = { customFrom = it }, onTo = { customTo = it })
        ChoiceChips(listOf("all" to "সব", "income" to "আয়", "expense" to "ব্যয়", "transfer" to "ট্রান্সফার"), filter) { filter = it }
        ChoiceChips(listOf("" to "সব মাধ্যম") + PAYMENT_MODES.map { it to it }, modeFilter) { modeFilter = it }
        Text(range.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatPill("আয়", taka(income, state.privacy), incomeGreen, Modifier.weight(1f))
            StatPill("ব্যয়", taka(expense, state.privacy), expenseRed, Modifier.weight(1f))
            StatPill("নিট", taka(income - expense, state.privacy), Sky400, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Export.Format.XLSX to "Excel", Export.Format.PDF to "PDF", Export.Format.CSV to "CSV").forEach { (fmt, label) ->
                OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), enabled = !busy && filtered.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { Export.listFile(ctx, fmt, state, filtered, range.label) }
                            Export.share(ctx, file, fmt.mime, "FinTrack - ${range.label}")
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "এক্সপোর্ট ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally { busy = false }
                    }
                }) { Text("$label", maxLines = 1, softWrap = false, fontSize = 13.sp) }
            }
            OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), enabled = !busy && filtered.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    try {
                        val ref = vm.summaryRef(filtered, range)
                        val file = withContext(Dispatchers.IO) { Voucher.summaryPdf(ctx, state, filtered, range.label, ref) }
                        Export.share(ctx, file, "application/pdf", "FinTrack সারসংক্ষেপ ভাউচার")
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "ভাউচার ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally { busy = false }
                }
            }) { Text("ভাউচার", maxLines = 1, softWrap = false, fontSize = 13.sp) }
        }
        if (filtered.isEmpty()) EmptyHint("এই সময়সীমায় কোনো লেনদেন পাওয়া যায়নি")
        // weight() keeps the list inside the remaining height; the bottom padding keeps the last row above the nav bar and the + button.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 120.dp)) {
            items(filtered, key = { it.id() }) { t ->
                TxItem(
                    t, state,
                    onClick = { openReceipt(t) },
                    onLongClick = { onTxLongPress(t) },
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
    // A বাকি-পরিশোধ expense is changed from the বাকি খাতা (delete the payment and add it again), so it cannot drift from the debt.
    val locked = existing?.str("source") == "debt_payment"
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var captureNote by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    // Fills the form from a receipt. Nothing is saved until the user presses সেভ.
    fun applyReceipt(r: ReceiptData) {
        r.amount?.let { amount = fmtNum(it) }
        r.date?.let { date = it }
        if (note.isBlank()) r.shop?.let { note = it }
        if (type != "Income") r.category?.let { category = it }
        captureNote = if (r.amount == null) "রসিদে মোট টাকা পাওয়া যায়নি, হাতে লিখুন" else "রসিদ থেকে পড়া হয়েছে। যাচাই করে সেভ করুন।"
    }
    fun readReceipt(uri: Uri) {
        reading = true
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { recognizeReceiptText(ctx, uri) }
                applyReceipt(CaptureParser.parseReceipt(text))
            } catch (e: Exception) {
                Toast.makeText(ctx, "রসিদ পড়া যায়নি: ${e.message ?: "অজানা সমস্যা"}", Toast.LENGTH_LONG).show()
            } finally {
                reading = false
            }
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) {
            val v = CaptureParser.parseVoice(said)
            if (v == null) {
                Toast.makeText(ctx, "পরিমাণ বোঝা যায়নি। আবার বলুন বা লিখুন", Toast.LENGTH_LONG).show()
            } else {
                amount = fmtNum(v.amount)
                type = v.type
                category = v.category ?: if (v.type == "Income") "অন্যান্য" else category
                note = v.transcript
                captureNote = "ভয়েস থেকে ভরা হয়েছে। যাচাই করে সেভ করুন।"
            }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) readReceipt(uri)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) readReceipt(uri)
    }
    fun launchVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "যেমন: পাঁচশো টাকা বাজার করলাম")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "এই ফোনে ভয়েস সার্চ চালু নেই", Toast.LENGTH_LONG).show()
        }
    }
    fun launchCamera() {
        val file = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "receipt-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        cameraUri = uri
        cameraLauncher.launch(uri)
    }
    val cats = if (type == "Income") INCOME_CATS else EXPENSE_CATS
    val accounts = state.rows("accounts")

    if (showCalc) {
        CalculatorDialog(
            onUse = { amount = fmtNum(it); showCalc = false },
            onDismiss = { showCalc = false }
        )
    }

    fun trySave() {
        if (locked) return
        val amt = parseAmount(amount)
        when {
            amt == null || amt <= 0 -> error = "সঠিক পরিমাণ লিখুন"
            !isValidIsoDate(date) -> error = "তারিখ লিখুন (YYYY-MM-DD)"
            type == "Transfer" && (accountId.isEmpty() || toAccountId.isEmpty() || accountId == toAccountId) ->
                error = "দুটি ভিন্ন অ্যাকাউন্ট বাছাই করুন"
            else -> onSave(
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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var tagInput by remember { mutableStateOf("") }
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFF0B1220))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (existing == null) "লেনদেন যোগ করুন" else "লেনদেন সম্পাদনা", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "বন্ধ করুন") }
            }
            // Segmented toggle: ব্যয় red, আয় green, ট্রান্সফার sky.
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.05f)).padding(4.dp)) {
                listOf(Triple("Expense", "ব্যয় (Expense)", expenseRed), Triple("Income", "আয় (Income)", incomeGreen), Triple("Transfer", "ট্রান্সফার", Sky500)).forEach { (key, label, c) ->
                    val on = type == key
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(if (on) c else Color.Transparent)
                            .clickable { type = key; category = if (key == "Income") "বেতন" else "খাদ্য" }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            // Amount with camera / mic / image / calculator, like the reference sheet.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("৳", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(
                    value = amount,
                    onValueChange = { amount = it },
                    placeholder = { Text("0.00", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    textStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (existing == null) {
                    RoundIcon(Icons.Default.PhotoCamera, "রসিদ ক্যামেরা") { launchCamera() }
                    RoundIcon(Icons.Default.Mic, "ভয়েস") { launchVoice() }
                    RoundIcon(Icons.Default.Image, "ছবি") { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
                RoundIcon(Icons.Default.Calculate, "ক্যালকুলেটর") { showCalc = true }
            }
            if (reading) Text("রসিদ পড়া হচ্ছে…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            captureNote?.let { Text(it, fontSize = 12.sp, color = Sky400) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (type != "Transfer") {
                    Column(Modifier.weight(1f)) {
                        Text(if (type == "Income") "আয়ের উৎস" else "ক্যাটাগরি", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PickerField(category, cats.map { it.name to "${it.icon} ${it.name}" }) { category = it }
                    }
                }
                Column(Modifier.weight(1f)) {
                    TextIn("তারিখ (YYYY-MM-DD)", date, { date = it })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (accounts.isNotEmpty()) {
                    Column(Modifier.weight(1f)) {
                        Text(if (type == "Transfer") "কোথা থেকে" else "অ্যাকাউন্ট", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PickerField(accountId, listOf("" to "কোনোটি নয়") + accounts.map { it.id() to it.str("name") }) { accountId = it }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("পেমেন্ট মোড", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PickerField(mode, PAYMENT_MODES.map { it to it }) { mode = it }
                }
            }
            if (type == "Transfer" && accounts.isNotEmpty()) {
                Column {
                    Text("কোথায়", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PickerField(toAccountId, accounts.map { it.id() to it.str("name") }) { toAccountId = it }
                }
            }
            Text("মুড", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MOODS.forEach { m ->
                    val on = mood == m
                    Box(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) Sky500.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (on) Sky500 else Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                            .clickable { mood = if (on) "" else m },
                        contentAlignment = Alignment.Center
                    ) { Text(m, fontSize = 22.sp) }
                }
            }
            Text("হ্যাশ ট্যাগ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextIn("ট্যাগ লিখুন (যেমন: জরুরি)", tagInput, { tagInput = it }, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Sky500)
                        .clickable {
                            val t = tagInput.trim().trimStart('#')
                            if (t.isNotEmpty()) {
                                tagsText = listOf(tagsText, t).filter { it.isNotBlank() }.joinToString(", ")
                                tagInput = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Add, contentDescription = "ট্যাগ যোগ করুন", tint = Color.White) }
            }
            val tagList = tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (tagList.isNotEmpty()) Text(tagList.joinToString("  ") { "#$it" }, fontSize = 12.sp, color = Sky400)
            TextIn("নোট", note, { note = it })
            SwitchRow("প্রতি মাসে পুনরাবৃত্ত", recurring) { recurring = it }
            error?.let { Text(it, color = expenseRed, fontSize = 12.sp) }
            if (locked) Text("এটি বাকি পরিশোধ থেকে তৈরি। পরিবর্তন করতে বাকি খাতা থেকে এই পরিশোধ মুছে আবার যোগ করুন।", fontSize = 12.sp, color = Sky400)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Sky500, Blue600)))
                    .clickable { if (locked) onDismiss() else trySave() },
                contentAlignment = Alignment.Center
            ) { Text(if (locked) "বন্ধ করুন" else "সেভ করুন", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(40.dp))
            .background(color.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(40.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text(label, fontSize = 12.sp, color = color)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

enum class HomeSpan(val label: String) { DAY("দৈনিক"), MONTH("মাসিক"), YEAR("বাৎসরিক") }

internal val BN_MONTHS = listOf("জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর")

/** ASCII digits to Bengali digits. */
fun bnDigits(s: String): String = s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")

data class HomePeriod(val from: String, val to: String, val label: String)

/** Inclusive ISO range and Bengali label for the home period tab + offset. */
fun homePeriod(span: HomeSpan, offset: Int): HomePeriod {
    val today = java.time.LocalDate.now()
    return when (span) {
        HomeSpan.DAY -> {
            val d = today.plusDays(offset.toLong())
            HomePeriod(d.toString(), d.toString(), "${bnDigits(d.dayOfMonth.toString())} ${BN_MONTHS[d.monthValue - 1]} ${bnDigits(d.year.toString())}")
        }
        HomeSpan.MONTH -> {
            val f = today.withDayOfMonth(1).plusMonths(offset.toLong())
            val l = f.withDayOfMonth(f.lengthOfMonth())
            HomePeriod(f.toString(), l.toString(), "${BN_MONTHS[f.monthValue - 1]} ${bnDigits(f.year.toString())}")
        }
        HomeSpan.YEAR -> {
            val y = today.year + offset
            HomePeriod("$y-01-01", "$y-12-31", bnDigits(y.toString()))
        }
    }
}

@Composable
private fun NavSquare(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(VideoCardLift.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.25f))
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = desc, tint = Sky400, modifier = Modifier.size(20.dp)) }
}

/** Dropdown-style picker used in the transaction sheet. */
@Composable
private fun PickerField(value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == value }?.second ?: "বাছাই করুন"
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(shown, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onPick(key); open = false })
            }
        }
    }
}

/** Month shown by the heatmap for the current timeline selection. */
fun heatmapMonthOf(span: HomeSpan, offset: Int, period: HomePeriod): java.time.YearMonth = when (span) {
    HomeSpan.MONTH -> java.time.YearMonth.now().plusMonths(offset.toLong())
    HomeSpan.DAY -> java.time.YearMonth.from(java.time.LocalDate.parse(period.from))
    HomeSpan.YEAR -> java.time.YearMonth.of(period.from.substring(0, 4).toInt(), java.time.YearMonth.now().monthValue)
}

/** SMART SMS PARSER card on the home screen; opens the paste-and-scan dialog. */
@Composable
private fun SmsParserCard(state: UiState, vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    AppCard(onClick = { open = true }) {
        Text("SMART SMS PARSER", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("bKash, Nagad বা ব্যাংকের SMS থেকে লেনদেন বের করুন", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) SmsParserDialog(state, vm) { open = false }
}

@Composable
fun SmsParserDialog(state: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SmsSuggestion?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFF0B1220))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("SMART SMS PARSER", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "বন্ধ করুন") }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; result = null; message = null },
                label = { Text("SMS") },
                placeholder = { Text("SMS এখানে পেস্ট করুন") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val parsed = SmsParser.parse(text)
                    result = parsed
                    message = if (parsed == null) "এই SMS থেকে লেনদেন বোঝা যায়নি" else null
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("স্ক্যান করুন") }
            result?.let { s ->
                AppCard(borderColor = Sky400.copy(alpha = 0.4f)) {
                    Text("${s.provider} · ${s.type} · ${taka(s.amount, state.privacy)}", fontWeight = FontWeight.Bold)
                    Text(s.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        vm.save("transactions", row(
                            "type" to s.type,
                            "amount" to s.amount,
                            "currency" to state.currency,
                            "category" to "অন্যান্য",
                            "note" to s.note,
                            "tx_date" to todayIso(),
                            "payment_mode" to (if (s.provider in PAYMENT_MODES) s.provider else "Bank"),
                            "mood" to "",
                            "is_recurring" to false,
                            "source" to "sms"
                        ), null)
                        onDismiss()
                    }) { Text("লেনদেনে যোগ করুন") }
                }
            }
            message?.let { Text(it, color = expenseRed, fontSize = 12.sp) }
        }
    }
}
