package com.fintracks.app.ui

import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fintracks.app.data.row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/** Audited constants. Tax: Finance Act 2026, assessment year 2026-27. Zakat: nisab in grams (gold 87.48 g, silver 612.36 g). */
object Web {
    const val ZAKAT_RATE = 0.025
    const val GENERAL_TAX_THRESHOLD = 400000.0
    const val WOMEN_TAX_THRESHOLD = 450000.0
    const val MIN_TAX = 5000.0
    val SLABS = listOf(300000.0 to 0.10, 400000.0 to 0.15, 500000.0 to 0.20, 2000000.0 to 0.25, Double.POSITIVE_INFINITY to 0.30)
    const val NISAB_GOLD_GRAMS = 87.48
    const val NISAB_SILVER_GRAMS = 612.36
}

fun fmtMoney(v: Double): String {
    val body = String.format(Locale.US, "%,.0f", v)
    return AppPrefs.symbol + " " + (if (AppPrefs.english) body else bnDigits(body))
}

/** One tile in the Tools grid. */
private data class ToolTile(val overlay: Overlay, val label: String, val icon: ImageVector, val color: Color)

/** English tile names, keyed by the Bengali label. Unknown labels fall back to themselves. */
private fun toolEnglishName(bn: String): String = when (bn) {
    "AI সহকারী" -> "AI assistant"
    "রিপোর্ট" -> "Reports"
    "লোন" -> "Loan"
    "যাকাত" -> "Zakat"
    "ধার ও পাওনা" -> "Debts & dues"
    "সম্পদ" -> "Assets"
    "ট্যাক্স" -> "Tax"
    "কনভার্টার" -> "Converter"
    "সাবস্ক্রিপশন" -> "Subscriptions"
    "বাকি খাতা" -> "Credit ledger"
    "বাজেট" -> "Budget"
    "লক্ষ্য" -> "Goals"
    "অ্যাকাউন্ট" -> "Accounts"
    "টাকা গণক" -> "Money counter"
    "বয়স ক্যালকুলেটর" -> "Age calculator"
    "নোটপ্যাড" -> "Notepad"
    else -> bn
}

@Composable
fun ToolsScreen(onOpen: (Overlay) -> Unit) {
    // Each tool has its own colour, like the web app's tool grid.
    val tools = listOf(
        ToolTile(Overlay.CHAT, "AI সহকারী", Icons.Default.Info, Color(0xFF818CF8)),
        ToolTile(Overlay.REPORTS, "রিপোর্ট", Icons.Default.Star, Color(0xFF38BDF8)),
        ToolTile(Overlay.LOAN, "লোন", Icons.Default.CreditCard, Color(0xFFF59E0B)),
        ToolTile(Overlay.ZAKAT, "যাকাত", Icons.Default.AccountBalance, Color(0xFF10B981)),
        ToolTile(Overlay.DEBTS, "ধার ও পাওনা", Icons.Default.Work, Color(0xFFF43F5E)),
        ToolTile(Overlay.INVESTMENTS, "সম্পদ", Icons.Default.Landscape, Color(0xFF34D399)),
        ToolTile(Overlay.TAX, "ট্যাক্স", Icons.Default.Receipt, Color(0xFFFB7185)),
        ToolTile(Overlay.CURRENCY, "কনভার্টার", Icons.Default.Refresh, Color(0xFF60A5FA)),
        ToolTile(Overlay.SUBSCRIPTIONS, "সাবস্ক্রিপশন", Icons.Default.Repeat, Color(0xFFA78BFA)),
        ToolTile(Overlay.CREDIT, "বাকি খাতা", Icons.Default.Receipt, Color(0xFFFBBF24)),
        ToolTile(Overlay.BUDGETS, "বাজেট", Icons.Default.Check, Color(0xFF2DD4BF)),
        ToolTile(Overlay.GOALS, "লক্ষ্য", Icons.Default.Star, Color(0xFFFB923C)),
        ToolTile(Overlay.ACCOUNTS, "অ্যাকাউন্ট", Icons.Default.Add, Color(0xFF94A3B8)),
        ToolTile(Overlay.COUNTER, "টাকা গণক", Icons.Default.Payments, Color(0xFF84CC16)),
        ToolTile(Overlay.AGE, "বয়স ক্যালকুলেটর", Icons.Default.Cake, Color(0xFFE879F9)),
        ToolTile(Overlay.NOTES, "নোটপ্যাড", Icons.Default.Description, Color(0xFFFACC15))
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Financial Hub", fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text("FINTRACK PREMIUM TOOLS GRID", fontSize = 10.sp, letterSpacing = 2.sp, color = Slate500)
        tools.chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { tile ->
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(32.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(32.dp))
                            .clickable { onOpen(tile.overlay) }
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(tile.color.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(tile.icon, contentDescription = tile.label, tint = tile.color, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(tr(tile.label, toolEnglishName(tile.label)), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        IntelligenceCard { onOpen(Overlay.REPORTS) }
        ToolsFooter()
    }
}

@Composable
fun ZakatScreen(state: UiState) {
    val txs = state.rows("transactions")
    var gold by remember { mutableStateOf("0") }
    var silver by remember { mutableStateOf("0") }
    var goldPerGram by remember { mutableStateOf("") }
    var silverPerGram by remember { mutableStateOf("") }
    val cash = txs.sumOf { when (it.str("type")) { "Income" -> it.num("amount"); "Expense" -> -it.num("amount"); else -> 0.0 } }
    val assets = state.rows("investments").sumOf { it.num("current_value") }
    val wealth = cash + assets + (gold.toDoubleOrNull() ?: 0.0) + (silver.toDoubleOrNull() ?: 0.0)
    val nisab = zakatNisab(goldPerGram.toDoubleOrNull(), silverPerGram.toDoubleOrNull())
    val zakat = zakatDue(wealth, nisab)
    val silverBasis = (silverPerGram.toDoubleOrNull() ?: 0.0) > 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Text(tr("যাকাত ক্যালকুলেটর", "Zakat calculator"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(tr("ক্যাশ: ${fmtMoney(cash)}", "Cash: ${fmtMoney(cash)}"), color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
            Text(tr("ইনভেস্টমেন্ট/সম্পদ: ${fmtMoney(assets)}", "Investments/assets: ${fmtMoney(assets)}"), fontSize = 13.sp)
            TextIn(tr("স্বর্ণ (৳)", "Gold (৳)"), gold, { gold = it }, number = true)
            TextIn(tr("রূপা (৳)", "Silver (৳)"), silver, { silver = it }, number = true)
        }
        AppCard {
            Text(tr("নিসাবের হিসাব", "Nisab calculation"), fontWeight = FontWeight.Bold)
            TextIn(tr("রূপার দর (প্রতি গ্রাম, ৳)", "Silver price per gram (৳)"), silverPerGram, { silverPerGram = it }, number = true)
            TextIn(tr("স্বর্ণের দর (প্রতি গ্রাম, ৳)", "Gold price per gram (৳)"), goldPerGram, { goldPerGram = it }, number = true)
            if (nisab == null) {
                Text(tr("নিসাব জানতে রূপার দর (প্রতি গ্রাম) লিখুন।", "Enter the silver price per gram to find the nisab."), fontSize = 12.sp, color = Rose500)
            } else {
                val basis = if (silverBasis) tr("রূপার ভিত্তিতে ${Web.NISAB_SILVER_GRAMS} গ্রাম", "silver basis, ${Web.NISAB_SILVER_GRAMS} g") else tr("স্বর্ণের ভিত্তিতে ${Web.NISAB_GOLD_GRAMS} গ্রাম", "gold basis, ${Web.NISAB_GOLD_GRAMS} g")
                Text(tr("নিসাব: ${fmtMoney(nisab)} ($basis)", "Nisab: ${fmtMoney(nisab)} ($basis)"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AppCard {
            Text(tr("প্রদেয় যাকাত (২.৫%)", "Zakat due (2.5%)"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (zakat == null) "—" else fmtMoney(zakat), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color(0xFFFBBF24))
            val status = when {
                nisab == null -> tr("নিসাব না জানা পর্যন্ত যাকাত হিসাব করা যাবে না।", "Zakat cannot be worked out until the nisab is known.")
                wealth >= nisab -> tr("সম্পদ নিসাবের সমান বা বেশি, তাই যাকাত দেওয়ার পর্যায়ে।", "Assets meet or exceed the nisab, so zakat is due.")
                else -> tr("সম্পদ নিসাবের নিচে, তাই যাকাত নেই।", "Assets are below the nisab, so no zakat is due.")
            }
            Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Money value of the nisab. Silver basis when a silver price is given (the lower threshold), else gold basis. */
fun zakatNisab(goldPerGram: Double?, silverPerGram: Double?): Double? = when {
    silverPerGram != null && silverPerGram > 0 -> Web.NISAB_SILVER_GRAMS * silverPerGram
    goldPerGram != null && goldPerGram > 0 -> Web.NISAB_GOLD_GRAMS * goldPerGram
    else -> null
}

/** 2.5% when wealth reaches the nisab (equal counts), 0 below it, null when the nisab is unknown. */
fun zakatDue(wealth: Double, nisab: Double?): Double? = nisab?.let { if (wealth >= it) wealth * Web.ZAKAT_RATE else 0.0 }

/** Tax-free limit for the chosen category. */
fun bdThreshold(women: Boolean): Double = if (women) Web.WOMEN_TAX_THRESHOLD else Web.GENERAL_TAX_THRESHOLD

/** Progressive tax on income above the tax-free limit; the minimum tax applies once the limit is exceeded. */
fun bdTax(income: Double, women: Boolean): Double {
    var taxable = income - bdThreshold(women)
    if (taxable <= 0) return 0.0
    var tax = 0.0
    // Each slab limit is the size of that band, not a running total.
    for ((limit, rate) in Web.SLABS) {
        val amount = minOf(taxable, limit)
        tax += amount * rate
        taxable -= amount
        if (taxable <= 0) break
    }
    return maxOf(tax, Web.MIN_TAX)
}

@Composable
fun TaxScreen(state: UiState) {
    var income by remember { mutableStateOf("500000") }
    var women by remember { mutableStateOf(false) }
    val inc = income.toDoubleOrNull() ?: 0.0
    val threshold = bdThreshold(women)
    val tax = bdTax(inc, women)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Text(tr("ট্যাক্স ক্যালকুলেটর (বার্ষিক)", "Tax calculator (yearly)"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            ChoiceChips(listOf("general" to tr("সাধারণ", "General"), "women" to tr("মহিলা বা ৬৫+", "Women or 65+")), if (women) "women" else "general") { women = it == "women" }
            TextIn(tr("বার্ষিক আয় (৳)", "Annual income (৳)"), income, { income = it }, number = true)
            KeyValue(tr("করযোগ্য সীমা", "Taxable amount"), fmtMoney(if (inc > threshold) inc - threshold else 0.0))
            KeyValue(tr("করমুক্ত সীমা", "Tax-free limit"), fmtMoney(threshold))
        }
        AppCard {
            Text(tr("মোট ট্যাক্স", "Total tax"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtMoney(tax), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Rose500)
            Text(tr("কার্যকর হার", "Effective rate") + ": ${bnDigits(if (inc > 0) String.format(Locale.US, "%.2f", tax / inc * 100) else "0.00")}%", fontSize = 12.sp)
        }
        Text(
            tr("স্ল্যাব (করমুক্ত সীমার উপরে): ১০% প্রথম ৩ লাখ, ১৫% পরের ৪ লাখ, ২০% পরের ৫ লাখ, ২৫% পরের ২০ লাখ, বাকিটা ৩০%। ন্যূনতম ট্যাক্স ৫,০০০ টাকা (সীমার উপরে হলে)। অর্থ আইন ২০২৬, অ্যাসেসমেন্ট বছর ২০২৬-২৭।", "Slabs above the tax-free limit: 10% on the first 3 lakh, 15% on the next 4 lakh, 20% on the next 5 lakh, 25% on the next 20 lakh, 30% on the rest. Minimum tax ৳5,000 when above the limit. Finance Act 2026, assessment year 2026-27."),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CurrencyScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("100") }
    var from by remember { mutableStateOf("USD") }
    var to by remember { mutableStateOf("BDT") }
    var snap by remember { mutableStateOf(FxStore.load(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            val res = withContext(Dispatchers.IO) { FxStore.fetch(ctx) }
            res.onSuccess { snap = it; failed = false }
            res.onFailure { failed = true }
            loading = false
        }
    }
    // Fetch on open when there is no saved rate or the saved one is older than 12 hours.
    LaunchedEffect(Unit) {
        val first = snap
        if (first == null || System.currentTimeMillis() - first.fetchedAt > FxStore.MAX_AGE_MS) refresh()
    }

    val v = value.toDoubleOrNull() ?: 0.0
    val s = snap
    val result = s?.let { fxConvert(v, from, to, it.rates) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Text(tr("কারেন্সি কনভার্টার", "Currency converter"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            TextIn(tr("পরিমাণ", "Amount"), value, { value = it }, number = true)
            Text(tr("থেকে", "From"), fontSize = 12.sp)
            ChoiceChips(FxStore.CURRENCIES.map { it to it }, from) { from = it }
            Text(tr("তে", "To"), fontSize = 12.sp)
            ChoiceChips(FxStore.CURRENCIES.map { it to it }, to) { to = it }
        }
        AppCard {
            Text(tr("ফলাফল", "Result"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result != null) {
                Text(bnDigits(String.format(Locale.US, "%,.2f", result)) + " $to", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color(0xFF34D399))
            } else {
                Text(tr("রেট পাওয়া যায়নি। ইন্টারনেট দিয়ে একবার রেট আনুন।", "No rate yet. Fetch rates once with internet."), fontSize = 13.sp, color = Rose500)
            }
            if (s != null) {
                val at = fxUpdatedLocal(s.updatedUtc)
                val stamp = at?.let { displayDate(it.toLocalDate().toString()) + " " + bnDigits(String.format(Locale.US, "%02d:%02d", it.hour, it.minute)) } ?: s.updatedUtc
                val stale = System.currentTimeMillis() - s.fetchedAt > FxStore.MAX_AGE_MS
                Text(tr("রেটের সময়: $stamp (ডিভাইসের সময়)", "Rate time: $stamp (device time)") + if (stale) tr(" · পুরনো ক্যাশ", " · cached") else "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (failed) Text(tr("নতুন রেট আনা যায়নি, তাই ক্যাশ করা রেট দেখানো হচ্ছে।", "Couldn't fetch new rates, so the saved rate is shown."), fontSize = 11.sp, color = Rose500)
            }
            OutlinedButton(onClick = { refresh() }, enabled = !loading) {
                Text(if (loading) tr("রেট আনা হচ্ছে…", "Fetching rates…") else tr("রেট নতুন করে আনুন", "Refresh rates"))
            }
            Text(tr("রেট সরবরাহ: ExchangeRate-API (open access)", "Rates: ExchangeRate-API (open access)"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/** EMI = P·r·(1+r)^n / ((1+r)^n − 1), with r = annual rate / 12 / 100. */
fun emiFor(principal: Double, annualPercent: Double, months: Int): Double {
    if (months <= 0) return 0.0
    val r = annualPercent / 12.0 / 100.0
    if (r == 0.0) return principal / months
    val f = (1 + r).pow(months.toDouble())
    return principal * r * f / (f - 1)
}

@Composable
fun LoanScreen(state: UiState, vm: AppViewModel) {
    val loans = state.rows("loans")
    var editing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("9") }
    var months by remember { mutableStateOf("12") }
    val p = state.privacy

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AddButton(tr("নতুন লোন", "New loan")) { editing = true }
        if (loans.isEmpty()) EmptyHint(tr("কোনো লোন নেই", "No loans"))
        loans.forEach { l ->
            val term = l.num("term_months").toInt()
            val paid = l.num("paid").toInt()
            val emi = emiFor(l.num("principal"), l.num("rate"), term)
            val done = paid >= term
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(l.str("name"), fontWeight = FontWeight.Bold)
                    DeleteButton { vm.remove("loans", l.id()) }
                }
                KeyValue(tr("মূল টাকা", "Principal"), taka(l.num("principal"), p))
                KeyValue(tr("মাসিক EMI", "Monthly EMI"), taka(emi, p))
                KeyValue(tr("পরিশোধ", "Pay"), bnDigits("$paid / $term") + " " + tr("মাস", "months"))
                ProgressBar(if (term > 0) paid.toFloat() / term else 0f, Emerald500)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!done) Button(onClick = {
                        vm.save("loans", row("paid" to (paid + 1).toDouble()), l.id())
                    }) { Text(tr("১ মাস পরিশোধ", "Pay 1 month")) }
                    else Text(tr("✅ সম্পন্ন", "✅ Done"), color = Emerald500)
                }
            }
        }
    }

    if (editing) {
        FormDialog(
            title = tr("নতুন লোন", "New loan"),
            onDismiss = { editing = false },
            onConfirm = {
                val p0 = parseAmount(amount)
                val m = months.toIntOrNull()
                if (title.isNotBlank() && p0 != null && p0 > 0 && m != null && m > 0) {
                    vm.save(
                        "loans",
                        row("name" to title, "principal" to p0, "rate" to (rate.toDoubleOrNull() ?: 0.0), "term_months" to m),
                        null
                    )
                    editing = false
                }
            }
        ) {
            TextIn(tr("লোনের নাম", "Loan name"), title, { title = it })
            TextIn(tr("মূল টাকা (৳)", "Principal (৳)"), amount, { amount = it }, number = true)
            TextIn(tr("সুদের হার % (বার্ষিক)", "Interest rate % (yearly)"), rate, { rate = it }, number = true)
            TextIn(tr("মেয়াদ (মাস)", "Term (months)"), months, { months = it }, number = true)
            val m = months.toIntOrNull() ?: 0
            val p0 = parseAmount(amount) ?: 0.0
            if (p0 > 0 && m > 0) Text("EMI: ${fmtMoney(emiFor(p0, rate.toDoubleOrNull() ?: 0.0, m))}", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ReportsScreen(state: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(Period.MONTH) }
    var customFrom by remember { mutableStateOf(todayIso().substring(0, 8) + "01") }
    var customTo by remember { mutableStateOf(todayIso()) }
    var busy by remember { mutableStateOf(false) }
    val range = rangeFor(period, customFrom, customTo)
    val txs = filterByRange(state.rows("transactions"), range)
    val income = txs.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
    val expense = txs.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
    val cats = txs.filter { it.str("type") == "Expense" }
        .groupBy { it.str("category") }
        .map { (k, v) -> k to v.sumOf { it.num("amount") } }
        .sortedByDescending { it.second }
    val p = state.privacy

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PeriodPicker(period, customFrom, customTo, onPeriod = { period = it }, onFrom = { customFrom = it }, onTo = { customTo = it })
        Text(range.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        InsightsCards(state)
        AppCard {
            KeyValue(tr("আয়", "Income"), taka(income, p), Color(0xFF10B981))
            KeyValue(tr("ব্যয়", "Expense"), taka(expense, p), Color(0xFFF43F5E))
            KeyValue(tr("নিট", "Net"), taka(income - expense, p), Color(0xFF38BDF8))
            KeyValue(tr("লেনদেন সংখ্যা", "Transactions"), txs.size.toString(), Color(0xFF94A3B8))
        }
        AppCard {
            Text(tr("আয় বনাম ব্যয় (সারসংক্ষেপ)", "Income vs expense (summary)"), fontWeight = FontWeight.Bold)
            IncomeExpenseBars(income, expense, p)
        }
        AppCard {
            Text(tr("খাতওয়ারি ব্যয়", "Expense by category"), fontWeight = FontWeight.Bold)
            if (cats.isEmpty()) Text(tr("কোনো ব্যয় নেই", "No expenses"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            cats.forEach { (name, v) ->
                val pct = if (expense > 0) (v / expense).toFloat() else 0f
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${catIcon(name)} $name")
                    Text("${taka(v, p)} (${(pct * 100).roundToLong()}%)", fontSize = 12.sp)
                }
                ProgressBar(pct, Sky500)
            }
        }
        // Trend uses fixed windows (7 days / 30 days / 12 months) chosen by its own tabs, not the period picker above.
        ExpenseTrendCard(state.rows("transactions"), p)
        AppCard {
            Text(tr("রিপোর্ট এক্সপোর্ট", "Export report"), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Export.Format.PDF to "PDF", Export.Format.XLSX to "Excel", Export.Format.CSV to "CSV").forEach { (fmt, label) ->
                    OutlinedButton(enabled = !busy && txs.isNotEmpty(), onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) { Export.listFile(ctx, fmt, state, txs, range.label) }
                                Export.share(ctx, file, fmt.mime, tr("FinTrack রিপোর্ট", "FinTrack report") + " - ${range.label}")
                            } catch (e: Exception) {
                                Toast.makeText(ctx, tr("এক্সপোর্ট ব্যর্থ: ${e.message}", "Export failed: ${e.message}"), Toast.LENGTH_LONG).show()
                            } finally { busy = false }
                        }
                    }) { Text(label) }
                }
            }
        }
    }
}

/** "Financial Intelligence" promo card from the video's Tools screen. */
@Composable
private fun IntelligenceCard(onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(40.dp))
            .background(Indigo400.copy(alpha = 0.08f))
            .border(1.dp, Indigo400.copy(alpha = 0.2f), RoundedCornerShape(40.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Indigo400.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.TrendingUp, contentDescription = null, tint = Indigo400, modifier = Modifier.size(22.dp))
            }
            Text("FINANCIAL INTELLIGENCE", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        Text(
            tr("FinTrack AI আপনার খরচ বিশ্লেষণ করে বাজেট ভারসাম্য বজায় রাখতে সাহায্য করে।", "FinTrack AI analyses your spending and helps keep your budget balanced."),
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = Slate400
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF6D5BF5))
                .clickable(onClick = onOpen)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(tr("বিস্তারিত বিশ্লেষণ", "Detailed analysis"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun ToolsFooter() {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("FinTrack AI", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "The smartest solution for your personal finance and transactions.\nMake life easier through technology.",
            color = Slate400,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        val uri = LocalUriHandler.current
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SocialButton(FacebookGlyph, "Facebook") { uri.openUri(FACEBOOK_URL) }
            SocialButton(TelegramGlyph, "Telegram") { uri.openUri(TELEGRAM_URL) }
        }
        Text("v${com.fintracks.app.BuildConfig.VERSION_NAME}-${com.fintracks.app.BuildConfig.VERSION_CODE}", color = Slate500, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Text("CRAFTED WITH ❤ BY SHOHAG VAII", color = Slate500, fontSize = 10.sp, letterSpacing = 2.sp)
        Text("© FINTRACK PREMIUM FINANCE", color = Slate600, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

// Profile links for the footer buttons.
internal const val FACEBOOK_URL = "https://www.facebook.com/shohag.vai.ofc"
internal const val TELEGRAM_URL = "https://t.me/shohag_vaii"

private val FacebookGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "Facebook", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes("M9 8H6v4h3v12h5V12h3.642L18 8h-4V6.333C14 5.378 14.192 5 15.115 5H18V0h-3.808C10.596 0 9 1.583 9 4.615V8z"),
            fill = SolidColor(Color.White)
        ).build()
}

private val TelegramGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "Telegram", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes("M9.78 18.65l.28-4.23 7.68-6.92c.34-.31-.07-.46-.52-.19L7.74 13.3 3.64 12c-.88-.25-.89-.86.2-1.3l15.97-6.16c.73-.33 1.43.18 1.15 1.3l-2.72 12.81c-.19.91-.74 1.13-1.5.71L12.6 16.3l-1.99 1.93c-.23.23-.42.42-.83.42z"),
            fill = SolidColor(Color.White)
        ).build()
}

@Composable
private fun SocialButton(glyph: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(glyph, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
}
