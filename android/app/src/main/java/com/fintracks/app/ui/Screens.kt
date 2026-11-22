package com.fintracks.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.InputChip
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import kotlinx.serialization.json.JsonObject
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween

private val incomeGreen = ChartIncome
private val expenseRed = Rose500

/**
 * Balance of one account from its opening balance and its income/expense rows.
 * Transfers do not move money between accounts (transfer is a type and tag only), so they are ignored here.
 */
fun accountBalance(acc: JsonObject, txs: List<JsonObject>): Double {
    val id = acc.id()
    var b = acc.num("opening_balance")
    for (t in txs) {
        val amt = t.num("amount")
        val from = t.accountId() == id
        when (t.str("type")) {
            "Income" -> if (from) b += amt
            "Expense" -> if (from) b -= amt
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
    val stats = remember(state.tables, period.from, period.to) {
        val flow = flowTotals(txs, period.from, period.to)
        val income = flow.income
        val expense = flow.expense
        val total = accounts.sumOf { accountBalance(it, txs) } +
            (if (accounts.isEmpty()) txs.sumOf { if (it.str("type") == "Income") it.num("amount") else if (it.str("type") == "Expense") -it.num("amount") else 0.0 } else 0.0)
        DashStats(income, expense, total, if (income > 0) ((income - expense) / income * 100) else 0.0)
    }
    val (income, expense, total, savingsRate) = stats
    val net = income - expense

    // Trend for the selected period: months for the year view, otherwise one point per day.
    val trend = remember(state.tables, span, offset) {
        val incomeOf: (List<JsonObject>) -> Double = { rows -> rows.filter { it.str("type") == "Income" }.sumOf { it.num("amount") } }
        val expenseOf: (List<JsonObject>) -> Double = { rows -> rows.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") } }
        val keys: List<String> = if (span == HomeSpan.YEAR) {
            val y = period.from.substring(0, 4)
            (1..12).map { m -> y + "-" + m.toString().padStart(2, '0') }
        } else {
            val end = java.time.LocalDate.parse(period.to)
            generateSequence(java.time.LocalDate.parse(period.from)) { it.plusDays(1) }
                .takeWhile { !it.isAfter(end) }.map { it.toString() }.toList()
        }
        val inc = keys.map { k ->
            val rows = if (span == HomeSpan.YEAR) txs.filter { dateOf(it, "tx_date").startsWith(k) } else txs.filter { dateOf(it, "tx_date") == k }
            incomeOf(rows)
        }
        val exp = keys.map { k ->
            val rows = if (span == HomeSpan.YEAR) txs.filter { dateOf(it, "tx_date").startsWith(k) } else txs.filter { dateOf(it, "tx_date") == k }
            expenseOf(rows)
        }
        val last = maxOf(lastIndexWithData(inc), lastIndexWithData(exp))
        inc.take(last + 1) to exp.take(last + 1)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Period tabs + timeline navigator, like the video's দৈনিক / মাসিক / বাৎসরিক pill.
        Box(Modifier.staggerIn(0)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(cardSurface(0.5f))
                .border(1.dp, cardOutline(), RoundedCornerShape(50))
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
        }
        Box(Modifier.staggerIn(2)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            NavSquare(Icons.Default.ChevronLeft, enabled = true) { offset -= 1 }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(period.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(tr("টাইমলাইন ভিউ", "Timeline view"), fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NavSquare(Icons.Default.ChevronRight, enabled = offset < 0) { offset += 1 }
        }
        }
        // Hero: the all-time balance (it replaces the old net-profit card; the separate balance card is gone).
        Box(Modifier.staggerIn(4)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(40.dp))
                .background(heroBrush())
                .border(1.dp, if (isDarkTheme()) incomeGreen.copy(alpha = 0.22f) else cardOutline(), RoundedCornerShape(40.dp))
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(tr("অবশিষ্ট ব্যালেন্স (ALL TIME)", "Balance (all time)"), fontSize = 12.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CountUpText(total, { taka(it, p) }, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.size(64.dp).clip(CircleShape).background(Sky400.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Sky400, modifier = Modifier.size(30.dp))
            }
        }
        }
        // Total income and total expense pills sit directly under the hero.
        Box(Modifier.staggerIn(5)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatPill(tr("মোট আয়", "Total income"), taka(income, p), incomeTone(), Modifier.weight(1f), countTo = income, countFmt = { taka(it, p) })
            StatPill(tr("মোট ব্যয়", "Total expense"), taka(expense, p), expenseTone(), Modifier.weight(1f), countTo = expense, countFmt = { taka(it, p) })
        }
        }
        Box(Modifier.staggerIn(1)) { CreditDueCard(state) { openOverlay(Overlay.CREDIT) } }
        // Heatmap follows the timeline month; its ‹ › buttons move the timeline too (one shared state).
        val heatMonth = heatmapMonthOf(span, offset, period)
        val nextMonth: (() -> Unit)? = if (heatMonth < java.time.YearMonth.now()) ({ jumpToMonth(heatMonth.plusMonths(1)) }) else null
        HeatmapCard(txs, p, heatMonth, onPrev = { jumpToMonth(heatMonth.minusMonths(1)) }, onNext = nextMonth)
        Box(Modifier.staggerIn(3)) { AiAssistantCard(state) { openOverlay(Overlay.CHAT) } }

        // Each block below is a Column (not a Box): a Box stacks its children on top of each other,
        // which made the section titles print over the first row (the overlap bug).
        Column(Modifier.staggerIn(7), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.pendingSms.isNotEmpty()) {
            SectionTitle(tr("SMS থেকে নতুন লেনদেন", "New transactions from SMS"))
            state.pendingSms.take(3).forEach { s ->
                AppCard(borderColor = Sky400.copy(alpha = 0.4f)) {
                    Text("${s.provider} · ${s.type} · ${taka(s.amount, p)}", fontWeight = FontWeight.Bold)
                    Text(s.raw, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(modifier = Modifier.pressScale(), onClick = { vm.acceptSms(s) }) { Text(tr("যোগ করুন", "Add")) }
                        OutlinedButton(modifier = Modifier.pressScale(), onClick = { vm.dismissSms(s) }) { Text(tr("বাতিল", "Cancel")) }
                    }
                }
            }
        }
        }
        Column(Modifier.staggerIn(8), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (accounts.isNotEmpty()) {
            SectionTitle(tr("অ্যাকাউন্ট", "Accounts"))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                accounts.forEach { acc ->
                    AppCard(onClick = { openOverlay(Overlay.ACCOUNTS) }) {
                        Text(acc.str("name"), fontWeight = FontWeight.Bold)
                        Text(acc.str("kind"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(taka(accountBalance(acc, txs), p), fontWeight = FontWeight.Bold, color = netTone(accountBalance(acc, txs)))
                    }
                }
            }
        }
        }
        Column(Modifier.staggerIn(9), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(tr("এই মাসের বাজেট", "This month's budget"))
        val budgets = state.rows("budgets")
        if (budgets.isEmpty()) {
            AppCard(onClick = { openOverlay(Overlay.BUDGETS) }) { Text(tr("বাজেট সেট করতে ট্যাপ করুন", "Tap to set a budget"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            AppCard {
                budgets.forEach { b ->
                    val cat = b.str("category")
                    val spent = monthTx.filter { it.str("type") == "Expense" && it.str("category") == cat }.sumOf { it.num("amount") }
                    val limit = b.num("monthly_limit")
                    val frac = if (limit > 0) (spent / limit).toFloat() else 0f
                    val color = if (frac >= 1f) expenseRed else if (frac >= 0.8f) Amber500 else Sky500
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${catIcon(cat)} ${catLabel(cat)}")
                        Text("${taka(spent, p)} / ${taka(limit, p)}", fontSize = 12.sp)
                    }
                    ProgressBar(frac, color)
                }
            }
        }
        }
        Column(Modifier.staggerIn(10), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(tr("আয় ও ব্যয়ের ট্রেন্ড", "Income & expense trend"))
        AppCard {
            DualLineChart(trend.first, incomeTone(), trend.second, expenseTone())
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(tr("আয়", "Income"), incomeTone())
                LegendDot(tr("ব্যয়", "Expense"), expenseTone())
            }
        }
        }
        Column(Modifier.staggerIn(11), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(tr("সাম্প্রতিক লেনদেন", "Recent transactions"))
        if (txs.isEmpty()) EmptyHint(tr("এখনো কোনো লেনদেন নেই। নিচের + বাটন দিয়ে যোগ করুন।", "No transactions yet. Add one with the + button below."))
        txs.take(5).forEach { t -> TxItem(t, state, onClick = { openReceipt(t) }, onLongClick = { onTxLongPress(t) }) }
        }
        AppFooter()
        Spacer(Modifier.height(90.dp))
    }
}

/** Small coloured dot with a label, used under the trend chart. */
@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun lastIndexWithData(list: List<Double>): Int = list.indexOfLast { it > 0.0 }.coerceAtLeast(0)

/** One transaction line: icon, note, mode, date, amount. */
@Composable
fun TxItem(t: JsonObject, state: UiState, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val type = t.str("type")
    val amountColor = readableOn(when (type) { "Income" -> incomeGreen; "Expense" -> expenseRed; else -> Sky400 })
    val sign = when (type) { "Income" -> "+"; "Expense" -> "-"; else -> "⇄" }
    AppCard(onClick = onClick, onLongClick = onLongClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(VideoCardLift.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) { Text(t.str("mood").ifBlank { catIcon(t.str("category")) }, fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.strOrNull("note")?.takeIf { it.isNotBlank() } ?: catLabel(t.str("category")), fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${displayDate(dateOf(t, "tx_date"))} · ${t.str("payment_mode")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$sign ${taka(t.num("amount"), state.privacy)}", color = amountColor, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(state: UiState, vm: AppViewModel, openTx: (JsonObject?) -> Unit, openReceipt: (JsonObject) -> Unit, onTxLongPress: (JsonObject) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var segment by remember { mutableStateOf("history") } // history | auto (created from dues / SMS)
    var modeFilter by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(Period.MONTH) }
    var customFrom by remember { mutableStateOf(todayIso().substring(0, 8) + "01") }
    var customTo by remember { mutableStateOf(todayIso()) }
    var busy by remember { mutableStateOf(false) }
    // Bulk delete: long-press a row to start selecting; taps then toggle rows.
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var confirmBulk by remember { mutableStateOf(false) }
    fun togglePick(id: String) { picked = if (id in picked) picked - id else picked + id }
    fun endSelect() { selecting = false; picked = emptySet() }
    BackHandler(enabled = selecting) { endSelect() }
    // Scrolling the list down folds the control block away; scrolling up (or reaching the top) brings it back.
    var collapsed by remember { mutableStateOf(false) }
    val listScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -4f) collapsed = true
                else if (available.y > 4f) collapsed = false
                return Offset.Zero
            }
        }
    }
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
    val filterCount = (if (filter != "all") 1 else 0) + (if (modeFilter.isNotEmpty()) 1 else 0) + (if (segment != "history") 1 else 0)
    var filterOpen by remember { mutableStateOf(false) }
    var periodMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    // Export actions live in the ⋮ menu (they used to be four large buttons above the list).
    fun exportList(fmt: Export.Format) {
        busy = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { Export.listFile(ctx, fmt, state, filtered, range.label) }
                Export.share(ctx, file, fmt.mime, "FinTrack - ${range.label}")
            } catch (e: Exception) {
                Toast.makeText(ctx, tr("এক্সপোর্ট ব্যর্থ: ${e.message}", "Export failed: ${e.message}"), Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    fun exportVoucher() {
        busy = true
        scope.launch {
            try {
                val ref = vm.summaryRef(filtered, range)
                val file = withContext(Dispatchers.IO) { Voucher.summaryPdf(ctx, state, filtered, range.label, ref) }
                Export.share(ctx, file, "application/pdf", tr("FinTrack সারসংক্ষেপ ভাউচার", "FinTrack summary voucher"))
            } catch (e: Exception) {
                Toast.makeText(ctx, tr("ভাউচার ব্যর্থ: ${e.message}", "Voucher failed: ${e.message}"), Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }

    val typeLabel = when (filter) {
        "income" -> tr("আয়", "Income")
        "expense" -> tr("ব্যয়", "Expense")
        "transfer" -> tr("ট্রান্সফার", "Transfer")
        else -> tr("সব", "All")
    }

    Column(Modifier.fillMaxSize().padding(16.dp).nestedScroll(listScroll)) {
        AnimatedVisibility(visible = !collapsed, enter = expandVertically(), exit = shrinkVertically()) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // One thin row: search, period, filter button (with a count badge), and the ⋮ menu.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(tr("খুঁজুন (নোট / ক্যাটাগরি / #ট্যাগ)", "Search (note / category / #tag)"), fontSize = 13.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    modifier = Modifier.weight(1f)
                )
                Box {
                    OutlinedButton(
                        modifier = Modifier.pressScale(),
                        onClick = { periodMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(period.label, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                        Period.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label, fontWeight = if (p == period) FontWeight.Bold else FontWeight.Normal) },
                                onClick = {
                                    period = p
                                    periodMenu = false
                                    // A custom range needs its two dates, so the filter sheet opens for them.
                                    if (p == Period.CUSTOM) filterOpen = true
                                }
                            )
                        }
                    }
                }
                BadgedBox(badge = { if (filterCount > 0) Badge { Text("$filterCount") } }) {
                    IconButton(modifier = Modifier.pressScale(), onClick = { filterOpen = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = tr("ফিল্টার", "Filter"))
                    }
                }
                Box {
                    IconButton(modifier = Modifier.pressScale(), onClick = { moreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = tr("আরও", "More"))
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        listOf(Export.Format.XLSX to "Excel", Export.Format.PDF to "PDF", Export.Format.CSV to "CSV").forEach { (fmt, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                enabled = !busy && filtered.isNotEmpty(),
                                onClick = { moreMenu = false; exportList(fmt) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(tr("ভাউচার", "Voucher")) },
                            enabled = !busy && filtered.isNotEmpty(),
                            onClick = { moreMenu = false; exportVoucher() }
                        )
                    }
                }
            }
            // Active filters as small removable chips, in one row. Tap × to clear one.
            if (filterCount > 0 || period == Period.CUSTOM) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (filter != "all") item {
                        InputChip(selected = true, onClick = { filter = "all" }, label = { Text(typeLabel, fontSize = 12.sp) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = tr("সরান", "Remove"), modifier = Modifier.size(16.dp)) })
                    }
                    if (modeFilter.isNotEmpty()) item {
                        InputChip(selected = true, onClick = { modeFilter = "" }, label = { Text(modeFilter, fontSize = 12.sp) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = tr("সরান", "Remove"), modifier = Modifier.size(16.dp)) })
                    }
                    if (segment != "history") item {
                        InputChip(selected = true, onClick = { segment = "history" }, label = { Text(tr("অটো-লেনদেন", "Auto-created"), fontSize = 12.sp) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = tr("সরান", "Remove"), modifier = Modifier.size(16.dp)) })
                    }
                    if (period == Period.CUSTOM) item {
                        Text(range.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        }

        if (filterOpen) {
            ModalBottomSheet(onDismissRequest = { filterOpen = false }) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(tr("ফিল্টার", "Filters"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(tr("ধরন", "Type"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceChips(listOf("all" to tr("সব", "All"), "income" to tr("আয়", "Income"), "expense" to tr("ব্যয়", "Expense"), "transfer" to tr("ট্রান্সফার", "Transfer")), filter) { filter = it }
                    Text(tr("পেমেন্ট মাধ্যম", "Payment mode"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceChips(listOf("" to tr("সব মাধ্যম", "All modes")) + PAYMENT_MODES.map { it to it }, modeFilter) { modeFilter = it }
                    Text(tr("লেনদেনের উৎস", "Source"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceChips(listOf("history" to tr("লেনদেনের ইতিহাস", "History"), "auto" to tr("অটো-লেনদেন", "Auto-created")), segment) { segment = it }
                    if (period == Period.CUSTOM) {
                        Text(tr("সময়সীমা", "Date range"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DateField(tr("শুরু", "From"), customFrom, { customFrom = it }, modifier = Modifier.weight(1f))
                            DateField(tr("শেষ", "To"), customTo, { customTo = it }, modifier = Modifier.weight(1f))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f).pressScale(), onClick = { filter = "all"; modeFilter = ""; segment = "history" }) {
                            Text(tr("সব মুছুন", "Clear all"))
                        }
                        Button(modifier = Modifier.weight(1f).pressScale(), onClick = { filterOpen = false }) {
                            Text(tr("দেখুন", "Show"))
                        }
                    }
                }
            }
        }
        if (selecting) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("${picked.size}টি নির্বাচিত", "${picked.size} selected"), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                // One selected row can be edited here (or open its receipt by tapping it).
                if (picked.size == 1) TextButton(modifier = Modifier.pressScale(), onClick = {
                    val one = state.rows("transactions").firstOrNull { it.id() == picked.first() }
                    endSelect()
                    if (one != null) openTx(one)
                }) { Text(tr("এডিট", "Edit")) }
                TextButton(enabled = picked.isNotEmpty(), onClick = { confirmBulk = true }) { Text(tr("মুছুন", "Delete"), color = readableOn(Rose500)) }
                TextButton(modifier = Modifier.pressScale(), onClick = { endSelect() }) { Text(tr("বাতিল", "Cancel")) }
            }
        }
        if (confirmBulk) {
            val chosen = state.rows("transactions").filter { it.id() in picked }
            // Debt and dues rows keep their mirrored-delete rule: their payment records go too.
            val linked = chosen.count { it.str("source") == "debt_payment" || it.str("source") == "personal_debt_payment" }
            val warn = if (linked > 0) tr("\n\nএর মধ্যে $linked টি ধার/বাকি-সংযুক্ত লেনদেন আছে; সেগুলোর পরিশোধের রেকর্ডও মুছে যাবে।", "\n\n$linked of them are linked to debts or dues; their payment records will be deleted too.") else ""
            ConfirmDialog(
                text = tr("${chosen.size}টি লেনদেন মুছবেন?", "Delete ${chosen.size} transactions?") + warn,
                confirmText = tr("মুছে ফেলুন", "Delete"),
                cancelText = tr("বাতিল", "Cancel"),
                onConfirm = {
                    // Fade the rows out first, then remove them from the data.
                    val ids = chosen.map { it.id() }
                    removing = ids.toSet()
                    confirmBulk = false
                    endSelect()
                    scope.launch {
                        kotlinx.coroutines.delay(if (motionReduced(ctx)) 0L else 260L)
                        vm.removeMany("transactions", ids)
                        removing = emptySet()
                    }
                },
                onDismiss = { confirmBulk = false }
            )
        }
        if (filtered.isEmpty()) EmptyHint(tr("এই সময়সীমায় কোনো লেনদেন পাওয়া যায়নি", "No transactions found in this period"))
        // weight() keeps the list inside the remaining height; the bottom padding keeps the last row above the nav bar and the + button.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 120.dp)) {
            items(filtered, key = { it.id() }) { t ->
                AnimatedVisibility(visible = t.id() !in removing, exit = fadeOut(tween(250)) + slideOutHorizontally(tween(250)) { -it / 3 }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(checked = t.id() in picked, onCheckedChange = { togglePick(t.id()) })
                    Box(Modifier.weight(1f)) {
                        TxItem(
                            t, state,
                            onClick = { if (selecting) togglePick(t.id()) else openReceipt(t) },
                            onLongClick = { if (!selecting) { selecting = true; picked = setOf(t.id()) } }
                        )
                    }
                }
                }
            }
            item { AppFooter() }
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
    val source = existing?.str("source").orEmpty()
    val locked = source == "debt_payment" || source == "personal_debt_payment"
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var captureNote by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    // Photo for this transaction. A new one gets its id now, so the photo can be filed under it before the save.
    val txId = remember { existing?.id() ?: java.util.UUID.randomUUID().toString() }
    val attachPath = existing?.str("attachment_url").orEmpty()
    var attachBytes by remember { mutableStateOf<ByteArray?>(null) }
    var attachPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var attachRemoved by remember { mutableStateOf(false) }
    var attachCamUri by remember { mutableStateOf<Uri?>(null) }
    fun setAttach(bytes: ByteArray?) {
        attachBytes = bytes
        attachPreview = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        attachRemoved = false
    }
    val attachGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setAttach(AttachmentStore.readImage(ctx, uri))
    }
    val attachCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = attachCamUri
        if (ok && uri != null) setAttach(AttachmentStore.readImage(ctx, uri))
    }
    fun launchAttachCamera() {
        val file = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        attachCamUri = uri
        attachCamera.launch(uri)
    }

    // Fills the form from a receipt. Nothing is saved until the user presses সেভ.
    fun applyReceipt(r: ReceiptData) {
        r.amount?.let { amount = fmtNum(it) }
        r.date?.let { date = it }
        if (note.isBlank()) r.shop?.let { note = it }
        if (type != "Income") r.category?.let { category = it }
        captureNote = if (r.amount == null) tr("রসিদে মোট টাকা পাওয়া যায়নি, হাতে লিখুন", "No total found on the receipt, enter it by hand") else tr("রসিদ থেকে পড়া হয়েছে। যাচাই করে সেভ করুন।", "Read from receipt. Check and save.")
    }
    fun readReceipt(uri: Uri) {
        reading = true
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { recognizeReceiptText(ctx, uri) }
                applyReceipt(CaptureParser.parseReceipt(text))
            } catch (e: Exception) {
                Toast.makeText(ctx, tr("রসিদ পড়া যায়নি", "Could not read receipt") + ": ${e.message ?: tr("অজানা সমস্যা", "unknown error")}", Toast.LENGTH_LONG).show()
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
                Toast.makeText(ctx, tr("পরিমাণ বোঝা যায়নি। আবার বলুন বা লিখুন", "Couldn't read the amount. Say it again or type it."), Toast.LENGTH_LONG).show()
            } else {
                amount = fmtNum(v.amount)
                type = v.type
                category = v.category ?: if (v.type == "Income") "অন্যান্য" else category
                note = v.transcript
                captureNote = tr("ভয়েস থেকে ভরা হয়েছে। যাচাই করে সেভ করুন।", "Filled from voice. Check and save.")
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, tr("যেমন: পাঁচশো টাকা বাজার করলাম", "e.g. spent 500 on groceries"))
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, tr("এই ফোনে ভয়েস সার্চ চালু নেই", "Voice search is not available on this phone"), Toast.LENGTH_LONG).show()
        }
    }
    fun launchCamera() {
        val file = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "receipt-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        cameraUri = uri
        cameraLauncher.launch(uri)
    }
    val cats = categoriesFor(type)
    var otherOpen by remember { mutableStateOf(category == OTHER_CAT || category !in cats.map { it.name }) }
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
            amt == null || amt <= 0 -> error = tr("সঠিক পরিমাণ লিখুন", "Enter a valid amount")
            !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
            else -> {
                // Photo first: it is saved on the device, then the row is saved, and the ViewModel uploads it.
                attachBytes?.let { AttachmentStore.stage(ctx, txId, it) }
                if (attachRemoved) AttachmentStore.purge(ctx, txId)
                val body = row(
                    "type" to type, "amount" to amt, "currency" to state.currency,
                    "category" to if (type == "Transfer") "ট্রান্সফার" else category,
                    "note" to note, "tx_date" to date, "payment_mode" to mode, "mood" to mood,
                    // Transfers never link accounts; JsonNull clears any old link on edit (row() would skip a null).
                    "account_id" to (if (type == "Transfer") JsonNull else nonEmpty(accountId)),
                    "to_account_id" to JsonNull,
                    "is_recurring" to recurring,
                    "tags" to JsonArray(tagsText.split(',', '#').map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { JsonPrimitive(it) })
                )
                val withPhoto = if (attachRemoved) JsonObject(body + ("attachment_url" to JsonNull)) else body
                onSave(if (existing == null) JsonObject(withPhoto + ("id" to JsonPrimitive(txId))) else withPhoto, existing?.id())
                if (attachRemoved && attachPath.isNotEmpty()) AttachmentStore.queueDelete(ctx, attachPath)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var tagInput by remember { mutableStateOf("") }
        Column(
            Modifier.morphIn()
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(32.dp))
                .background(Tok_FF0B1220)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (existing == null) tr("লেনদেন যোগ করুন", "Add transaction") else tr("লেনদেন সম্পাদনা", "Edit transaction"), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = tr("বন্ধ করুন", "Close")) }
            }
            // Segmented toggle: ব্যয় red, আয় green, ট্রান্সফার sky.
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.05f)).padding(4.dp)) {
                listOf(Triple("Expense", "ব্যয় (Expense)", expenseRed), Triple("Income", tr("আয় (Income)", "Income"), incomeGreen), Triple("Transfer", "ট্রান্সফার", Sky500)).forEach { (key, label, c) ->
                    val on = type == key
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(if (on) c else Color.Transparent)
                            .clickable { type = key; category = if (key == "Income") "বেতন" else "খাদ্য"; otherOpen = false }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            // Amount with camera / mic / image / calculator, like the reference sheet.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AppPrefs.symbol, fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(
                    value = amount,
                    onValueChange = { amount = it },
                    placeholder = { Text("0.00", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    textStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
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
                    RoundIcon(Icons.Default.PhotoCamera, tr("রসিদ ক্যামেরা", "Receipt camera")) { launchCamera() }
                    RoundIcon(Icons.Default.Mic, tr("ভয়েস", "Voice")) { launchVoice() }
                    RoundIcon(Icons.Default.Image, tr("ছবি", "Photo")) { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
                RoundIcon(Icons.Default.Calculate, tr("ক্যালকুলেটর", "Calculator")) { showCalc = true }
            }
            if (reading) Text(tr("রসিদ পড়া হচ্ছে…", "Reading receipt…"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            captureNote?.let { Text(it, fontSize = 12.sp, color = readableOn(Sky400)) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (type != "Transfer") {
                    Column(Modifier.weight(1f)) {
                        Text(if (type == "Income") tr("আয়ের উৎস", "Income source") else tr("ক্যাটাগরি", "Category"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PickerField(category, cats.map { it.name to "${it.icon} ${catLabel(it.name)}" }) { pick ->
                            otherOpen = pick == OTHER_CAT
                            category = pick
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    DateField(tr("তারিখ", "Date"), date, { date = it })
                }
            }
            if (otherOpen && type != "Transfer") OtherCategoryField(category) { category = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (accounts.isNotEmpty() && type != "Transfer") {
                    Column(Modifier.weight(1f)) {
                        Text(tr("অ্যাকাউন্ট", "Account"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PickerField(accountId, listOf("" to "কোনোটি নয়") + accounts.map { it.id() to it.str("name") }) { accountId = it }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(tr("পেমেন্ট মোড", "Payment mode"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PickerField(mode, PAYMENT_MODES.map { it to it }) { mode = it }
                }
            }
            if (type == "Transfer") {
                Text(
                    tr("ট্রান্সফার শুধু ধরন ও ট্যাগ: অ্যাকাউন্ট ব্যালেন্স বদলায় না, আয় বা ব্যয়ের হিসাবেও ধরা হয় না।", "Transfer is only a type and tag: it does not change account balances or count as income or expense."),
                    fontSize = 12.sp,
                    color = Sky400
                )
            }
            Text(tr("ছবি (ঐচ্ছিক)", "Photo (optional)"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val pv = attachPreview
            if (pv != null) {
                Image(
                    bitmap = pv.asImageBitmap(),
                    contentDescription = tr("লেনদেনের ছবি", "Transaction photo"),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(16.dp))
                )
            } else if (attachPath.isNotEmpty() && !attachRemoved) {
                AttachmentImage(txId, attachPath, Modifier.heightIn(max = 180.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { attachGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(tr("গ্যালারি", "Gallery"), maxLines = 1) }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { launchAttachCamera() }) { Text(tr("ক্যামেরা", "Camera"), maxLines = 1) }
                if (pv != null || (attachPath.isNotEmpty() && !attachRemoved)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { attachBytes = null; attachPreview = null; attachRemoved = true }) {
                        Text(tr("ছবি সরান", "Remove"), color = readableOn(Rose500), maxLines = 1)
                    }
                }
            }
            Text(tr("মুড", "Mood"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(tr("হ্যাশ ট্যাগ", "Hashtag"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextIn(tr("ট্যাগ লিখুন (যেমন: জরুরি)", "Enter a tag (e.g. urgent)"), tagInput, { tagInput = it }, modifier = Modifier.weight(1f))
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
                ) { Icon(Icons.Default.Add, contentDescription = tr("ট্যাগ যোগ করুন", "Add tag"), tint = Color.White) }
            }
            val tagList = tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (tagList.isNotEmpty()) Text(tagList.joinToString("  ") { "#$it" }, fontSize = 12.sp, color = readableOn(Sky400))
            TextIn(tr("নোট", "Note"), note, { note = it })
            SwitchRow(tr("প্রতি মাসে পুনরাবৃত্ত", "Repeats monthly"), recurring) { recurring = it }
            error?.let { Text(it, color = readableOn(expenseRed), fontSize = 12.sp) }
            if (locked) Text(tr("এটি বাকি পরিশোধ থেকে তৈরি। পরিবর্তন করতে বাকি খাতা থেকে এই পরিশোধ মুছে আবার যোগ করুন।", "Created from a credit payment. To change it, delete this payment in the credit ledger and add it again."), fontSize = 12.sp, color = readableOn(Sky400))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Sky500, Blue600)))
                    .clickable { if (locked) onDismiss() else trySave() },
                contentAlignment = Alignment.Center
            ) { Text(if (locked) tr("বন্ধ করুন", "Close") else tr("সেভ করুন", "Save"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}

@Composable
internal fun StatPill(label: String, value: String, color: Color, modifier: Modifier = Modifier, countTo: Double? = null, countFmt: ((Double) -> String)? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(40.dp))
            .background(color.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(40.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text(label, fontSize = 12.sp, color = readableOn(color))
        if (countTo != null && countFmt != null) CountUpText(countTo, countFmt, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = readableOn(color))
        else Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = readableOn(color))
    }
}

enum class HomeSpan(private val bnLabel: String, private val enLabel: String) {
    DAY("দৈনিক", "Daily"), MONTH("মাসিক", "Monthly"), YEAR("বাৎসরিক", "Yearly");

    val label: String get() = tr(bnLabel, enLabel)
}

internal val BN_MONTHS = listOf("জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর")

/** ASCII digits to Bengali digits. */
fun bnDigits(s: String): String = if (AppPrefs.english) s else s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")

data class HomePeriod(val from: String, val to: String, val label: String)

/** Inclusive ISO range and Bengali label for the home period tab + offset. */
fun homePeriod(span: HomeSpan, offset: Int): HomePeriod {
    val today = java.time.LocalDate.now()
    return when (span) {
        HomeSpan.DAY -> {
            val d = today.plusDays(offset.toLong())
            HomePeriod(d.toString(), d.toString(), displayDate(d.toString()))
        }
        HomeSpan.MONTH -> {
            val f = today.withDayOfMonth(1).plusMonths(offset.toLong())
            val l = f.withDayOfMonth(f.lengthOfMonth())
            HomePeriod(f.toString(), l.toString(), monthYearLabel(f.year, f.monthValue))
        }
        HomeSpan.YEAR -> {
            val y = today.year + offset
            HomePeriod("$y-01-01", "$y-12-31", yearLabel(y))
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
        Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
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
    val shown = options.firstOrNull { it.first == value }?.second ?: tr("বাছাই করুন", "Choose")
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
                .background(Tok_FF0B1220)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("SMART SMS PARSER", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = tr("বন্ধ করুন", "Close")) }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; result = null; message = null },
                label = { Text("SMS") },
                placeholder = { Text(tr("SMS এখানে পেস্ট করুন", "Paste SMS here")) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val parsed = SmsParser.parse(text)
                    result = parsed
                    message = if (parsed == null) tr("এই SMS থেকে লেনদেন বোঝা যায়নি", "Couldn't read a transaction from this SMS") else null
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("স্ক্যান করুন", "Scan")) }
            result?.let { s ->
                AppCard(borderColor = Sky400.copy(alpha = 0.4f)) {
                    Text("${s.provider} · ${s.type} · ${taka(s.amount, state.privacy)}", fontWeight = FontWeight.Bold)
                    Text(
                        tr("তারিখ: ", "Date: ") + (if (s.date.isNotEmpty()) displayDate(s.date) else tr("এসএমএসে নেই, আজকের তারিখ", "not in SMS, today is used")),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(s.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(modifier = Modifier.pressScale(), onClick = {
                        vm.save("transactions", row(
                            "type" to s.type,
                            "amount" to s.amount,
                            "currency" to state.currency,
                            "category" to "অন্যান্য",
                            "note" to s.note,
                            "tx_date" to s.date.ifEmpty { todayIso() },
                            "payment_mode" to (if (s.provider in PAYMENT_MODES) s.provider else "Bank"),
                            "mood" to "",
                            "is_recurring" to false,
                            "source" to "sms"
                        ), null)
                        onDismiss()
                    }) { Text(tr("লেনদেনে যোগ করুন", "Add to transactions")) }
                }
            }
            message?.let { Text(it, color = readableOn(expenseRed), fontSize = 12.sp) }
        }
    }
}
