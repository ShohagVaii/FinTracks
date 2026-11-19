package com.fintracks.app.ui

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
import com.fintracks.app.data.row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
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

/** Web constants (constants.ts). */
object Web {
    const val ZAKAT_RATE = 0.025
    const val MALE_THRESHOLD = 350000.0
    const val FEMALE_THRESHOLD = 400000.0
    val SLABS = listOf(100000.0 to 0.05, 300000.0 to 0.10, 400000.0 to 0.15, 500000.0 to 0.20, Double.POSITIVE_INFINITY to 0.25)
    val RATES = mapOf("BDT" to 1.0, "USD" to 121.50, "EUR" to 132.20, "GBP" to 154.80)
    const val NISAB_GOLD_GRAMS = 87.48
    const val NISAB_SILVER_GRAMS = 612.36
}

fun fmtMoney(v: Double): String = "৳ " + String.format(Locale.US, "%,.0f", v)

@Composable
fun ToolsScreen(onOpen: (Overlay) -> Unit) {
    val tools = listOf(
        Triple(Overlay.REPORTS, "রিপোর্ট", Icons.Default.Star),
        Triple(Overlay.LOAN, "লোন", Icons.Default.CreditCard),
        Triple(Overlay.ZAKAT, "যাকাত", Icons.Default.AccountBalance),
        Triple(Overlay.DEBTS, "ধার ও পাওনা", Icons.Default.Work),
        Triple(Overlay.INVESTMENTS, "সম্পদ", Icons.Default.Landscape),
        Triple(Overlay.TAX, "ট্যাক্স", Icons.Default.Receipt),
        Triple(Overlay.CURRENCY, "কনভার্টার", Icons.Default.Refresh),
        Triple(Overlay.SUBSCRIPTIONS, "সাবস্ক্রিপশন", Icons.Default.Repeat),
        Triple(Overlay.CREDIT, "বাকি", Icons.Default.Receipt),
        Triple(Overlay.BUDGETS, "বাজেট", Icons.Default.Check),
        Triple(Overlay.GOALS, "লক্ষ্য", Icons.Default.Star),
        Triple(Overlay.ACCOUNTS, "অ্যাকাউন্ট", Icons.Default.Add)
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Financial Hub", fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text("FinTrack Premium Tools", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        tools.chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { (ov, label, icon) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onOpen(ov) }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(icon, contentDescription = label, tint = Sky400, modifier = Modifier.height(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun ZakatScreen(state: UiState) {
    val txs = state.rows("transactions")
    var gold by remember { mutableStateOf("0") }
    var silver by remember { mutableStateOf("0") }
    val cash = txs.sumOf { when (it.str("type")) { "Income" -> it.num("amount"); "Expense" -> -it.num("amount"); else -> 0.0 } }
    val assets = state.rows("investments").sumOf { it.num("current_value") }
    val wealth = cash + assets + (gold.toDoubleOrNull() ?: 0.0) + (silver.toDoubleOrNull() ?: 0.0)
    val zakat = wealth * Web.ZAKAT_RATE
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Text("যাকাত ক্যালকুলেটর", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("ক্যাশ: ${fmtMoney(cash)}", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
            Text("ইনভেস্টমেন্ট/সম্পদ: ${fmtMoney(assets)}", fontSize = 13.sp)
            TextIn("স্বর্ণ (৳)", gold, { gold = it }, number = true)
            TextIn("রূপা (৳)", silver, { silver = it }, number = true)
        }
        AppCard {
            Text("প্রদেয় যাকাত (২.৫%)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtMoney(zakat), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color(0xFFFBBF24))
            Text("নিসাব: স্বর্ণ ${Web.NISAB_GOLD_GRAMS} গ্রাম, রূপা ${Web.NISAB_SILVER_GRAMS} গ্রাম (তথ্যের জন্য)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Progressive BD tax on income above the threshold. Female threshold applies when the profile name contains "Ms". */
fun bdTax(income: Double, name: String): Double {
    var taxable = income - bdThreshold(name)
    if (taxable <= 0) return 0.0
    var tax = 0.0
    // Same as the web: each slab limit is the size of that band, not a running total.
    for ((limit, rate) in Web.SLABS) {
        val amount = minOf(taxable, limit)
        tax += amount * rate
        taxable -= amount
        if (taxable <= 0) break
    }
    return tax
}

@Composable
fun TaxScreen(state: UiState) {
    var income by remember { mutableStateOf("500000") }
    val inc = income.toDoubleOrNull() ?: 0.0
    val tax = bdTax(inc, state.name)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Text("ট্যাক্স ক্যালকুলেটর (বার্ষিক)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            TextIn("বার্ষিক আয় (৳)", income, { income = it }, number = true)
            KeyValue("করযোগ্য সীমা", fmtMoney(if (inc > bdThreshold(state.name)) inc - bdThreshold(state.name) else 0.0))
            KeyValue("সীমা", fmtMoney(bdThreshold(state.name)))
        }
        AppCard {
            Text("মোট ট্যাক্স", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtMoney(tax), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Rose500)
            Text("কার্যকর হার: ${if (inc > 0) String.format(Locale.US, "%.2f", tax / inc * 100) else "0.00"}%", fontSize = 12.sp)
        }
        Text("স্ল্যাব: ১ লাখ @৫%, পরের ৩ লাখ @১০%, পরের ৪ লাখ @১৫%, পরের ৫ লাখ @২০%, বাকিটা @২৫%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun bdThreshold(name: String): Double = if (name.contains("Ms")) Web.FEMALE_THRESHOLD else Web.MALE_THRESHOLD

@Composable
fun CurrencyScreen() {
    var value by remember { mutableStateOf("100") }
    var from by remember { mutableStateOf("USD") }
    var to by remember { mutableStateOf("BDT") }
    val v = value.toDoubleOrNull() ?: 0.0
    val result = v * (Web.RATES[from] ?: 1.0) / (Web.RATES[to] ?: 1.0)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Text("কারেন্সি কনভার্টার", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            TextIn("পরিমাণ", value, { value = it }, number = true)
            Text("থেকে", fontSize = 12.sp)
            ChoiceChips(Web.RATES.keys.map { it to it }, from) { from = it }
            Text("তে", fontSize = 12.sp)
            ChoiceChips(Web.RATES.keys.map { it to it }, to) { to = it }
        }
        AppCard {
            Text("ফলাফল", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${String.format(Locale.US, "%,.2f", result)} $to", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color(0xFF34D399))
            Text("রেট স্থির (ডেমো রেট, লাইভ নয়)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        AddButton("নতুন লোন") { editing = true }
        if (loans.isEmpty()) EmptyHint("কোনো লোন নেই")
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
                KeyValue("মূল টাকা", taka(l.num("principal"), p))
                KeyValue("মাসিক EMI", taka(emi, p))
                KeyValue("পরিশোধ", "$paid / $term মাস")
                ProgressBar(if (term > 0) paid.toFloat() / term else 0f, Emerald500)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!done) Button(onClick = {
                        vm.save("loans", row("paid" to (paid + 1).toDouble()), l.id())
                    }) { Text("১ মাস পরিশোধ") }
                    else Text("✅ সম্পন্ন", color = Emerald500)
                }
            }
        }
    }

    if (editing) {
        FormDialog(
            title = "নতুন লোন",
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
            TextIn("লোনের নাম", title, { title = it })
            TextIn("মূল টাকা (৳)", amount, { amount = it }, number = true)
            TextIn("সুদের হার % (বার্ষিক)", rate, { rate = it }, number = true)
            TextIn("মেয়াদ (মাস)", months, { months = it }, number = true)
            val m = months.toIntOrNull() ?: 0
            val p0 = parseAmount(amount) ?: 0.0
            if (p0 > 0 && m > 0) Text("EMI: ${fmtMoney(emiFor(p0, rate.toDoubleOrNull() ?: 0.0, m))}", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ReportsScreen(state: UiState) {
    var period by remember { mutableStateOf("monthly") }
    val now = todayIso()
    val txs = state.rows("transactions").filter { t ->
        val d = dateOf(t, "tx_date")
        when (period) {
            "weekly" -> runCatching { java.time.LocalDate.parse(d) >= java.time.LocalDate.parse(now).minusDays(7) }.getOrDefault(false)
            "monthly" -> d.startsWith(now.substring(0, 7))
            else -> d.startsWith(now.substring(0, 4))
        }
    }
    val income = txs.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
    val expense = txs.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
    val cats = txs.filter { it.str("type") == "Expense" }
        .groupBy { it.str("category") }
        .map { (k, v) -> k to v.sumOf { it.num("amount") } }
        .sortedByDescending { it.second }
    val p = state.privacy

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceChips(listOf("weekly" to "সাপ্তাহিক", "monthly" to "মাসিক", "yearly" to "বাৎসরিক"), period) { period = it }
        AppCard {
            KeyValue("আয়", taka(income, p), Color(0xFF10B981))
            KeyValue("ব্যয়", taka(expense, p), Color(0xFFF43F5E))
            KeyValue("নিট", taka(income - expense, p), Color(0xFF38BDF8))
        }
        AppCard {
            Text("খাতওয়ারি ব্যয়", fontWeight = FontWeight.Bold)
            if (cats.isEmpty()) Text("কোনো ব্যয় নেই", color = MaterialTheme.colorScheme.onSurfaceVariant)
            cats.forEach { (name, v) ->
                val pct = if (expense > 0) (v / expense).toFloat() else 0f
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${catIcon(name)} $name")
                    Text("${taka(v, p)} (${(pct * 100).roundToLong()}%)", fontSize = 12.sp)
                }
                ProgressBar(pct, Sky500)
            }
        }
        AppCard {
            Text("ব্যয়ের ট্রেন্ড", fontWeight = FontWeight.Bold)
            val byDay = txs.filter { it.str("type") == "Expense" }.groupBy { dateOf(it, "tx_date") }
                .toSortedMap().values.map { list -> list.sumOf { it.num("amount") } }
            LineChart(byDay.ifEmpty { listOf(0.0) }, Color(0xFF0EA5E9))
        }
    }
}
