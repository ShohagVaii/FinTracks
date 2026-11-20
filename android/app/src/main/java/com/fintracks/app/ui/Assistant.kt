package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlin.math.ceil
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Offline assistant. It answers from the user's own data with no network and no API key.
 * A Gemini-backed version needs the key kept on a server (Supabase Edge Function), so it is not included here.
 */
object Assistant {
    private fun has(q: String, vararg words: String) = words.any { q.contains(it) }

    fun answer(question: String, state: UiState): String {
        val q = question.lowercase()
        val p = state.privacy
        val month = monthPrefix()
        val txs = state.rows("transactions")
        val monthTx = txs.filter { dateOf(it, "tx_date").startsWith(month) }
        val income = monthTx.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
        val expense = monthTx.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
        val topCat = monthTx.filter { it.str("type") == "Expense" }
            .groupBy { it.str("category") }.maxByOrNull { e -> e.value.sumOf { it.num("amount") } }

        return when {
            has(q, "বাকি", "credit", "দোকান") -> {
                val due = state.rows("credit_shops").sumOf { shopBalance(it.id(), state) }
                "মোট বাকি আছে ${taka(due, p)}। দোকানভিত্তিক হিসাব বাকি মেনুতে দেখতে পারবেন।"
            }
            has(q, "ধার", "পাওনা", "debt", "loan", "লোন") -> {
                val owe = state.rows("debts").filter { !it.bool("settled") && it.str("direction") == "owe" }.sumOf { it.num("amount") }
                val owed = state.rows("debts").filter { !it.bool("settled") && it.str("direction") == "owed" }.sumOf { it.num("amount") }
                "আপনি ধারি ${taka(owe, p)}, আপনার পাওনা ${taka(owed, p)}।"
            }
            has(q, "যাকাত", "zakat") -> {
                val cash = txs.sumOf { if (it.str("type") == "Income") it.num("amount") else if (it.str("type") == "Expense") -it.num("amount") else 0.0 }
                val assets = state.rows("investments").sumOf { it.num("current_value") }
                "হিসাব অনুযায়ী মোট সম্পদ ${taka(cash + assets, p)}, যাকাত (২.৫%) ${taka((cash + assets) * Web.ZAKAT_RATE, p)}। স্বর্ণ/রূপা যোগ করে যাকাত টুলসে সঠিক হিসাব করুন।"
            }
            has(q, "ট্যাক্স", "tax") -> "ট্যাক্স টুলসে বার্ষিক আয় লিখলে স্ল্যাব অনুযায়ী ট্যাক্স দেখাবে। মহিলা করদাতার সীমা ${taka(Web.FEMALE_THRESHOLD, false)}, পুরুষের ${taka(Web.MALE_THRESHOLD, false)}।"
            has(q, "বাজেট", "budget") -> {
                val over = state.rows("budgets").filter { b ->
                    val spent = monthTx.filter { it.str("type") == "Expense" && it.str("category") == b.str("category") }.sumOf { it.num("amount") }
                    spent > b.num("monthly_limit") && b.num("monthly_limit") > 0
                }
                if (over.isEmpty()) "এই মাসে কোনো বাজেট ছাড়িয়ে যায়নি। ভালো কাজ চলছে!"
                else "এই ক্যাটাগরিগুলো বাজেট ছাড়িয়েছে: " + over.joinToString(", ") { it.str("category") } + "।"
            }
            has(q, "সঞ্চয়", "save", "saving") -> {
                if (income <= 0) "এই মাসে আয় নেই, তাই সঞ্চয়ের হার হিসাব করা যাচ্ছে না। আয় যোগ করুন।"
                else "এই মাসে সঞ্চয়ের হার ${String.format("%.0f", (income - expense) / income * 100)}%। ২০% বা তার বেশি হলে ভালো।"
            }
            has(q, "আয়", "income", "বেতন") -> "এই মাসে মোট আয় ${taka(income, p)}।"
            has(q, "খরচ", "expense", "spend") -> {
                val extra = if (topCat != null) " সবচেয়ে বেশি খরচ '${topCat.key}' খাতে (${taka(topCat.value.sumOf { it.num("amount") }, p)})।" else ""
                "এই মাসে মোট খরচ ${taka(expense, p)}।$extra"
            }
            has(q, "ব্যালেন্স", "balance", "কত টাকা", "আছে") -> {
                val total = state.rows("accounts").sumOf { accountBalance(it, txs) } +
                    (if (state.rows("accounts").isEmpty()) txs.sumOf { if (it.str("type") == "Income") it.num("amount") else if (it.str("type") == "Expense") -it.num("amount") else 0.0 } else 0.0)
                "আপনার মোট ব্যালেন্স ${taka(total, p)}।"
            }
            has(q, "সাবস্ক্রিপশন", "subscription") -> {
                val monthly = state.rows("subscriptions").filter { it.bool("is_active") }.sumOf { it.num("amount") }
                "সক্রিয় সাবস্ক্রিপশনের মাসিক খরচ আনুমানিক ${taka(monthly, p)}।"
            }
            has(q, "পরামর্শ", "tip", "advice", "সাজেশন", "উপদেশ") -> tips(state, income, expense, topCat?.key)
            else -> "আমি খরচ, আয়, বাজেট, সঞ্চয়, বাকি, ধার, যাকাত, ট্যাক্স বা ব্যালেন্স নিয়ে প্রশ্নের উত্তর দিতে পারি। যেমন: \"এই মাসে কত খরচ?\""
        }
    }

    private fun tips(state: UiState, income: Double, expense: Double, topCat: String?): String {
        val list = mutableListOf<String>()
        if (income > 0 && expense > income * 0.8) list += "খরচ আয়ের ৮০% ছাড়িয়েছে। অপ্রয়োজনীয় খরচ কমান।"
        if (topCat != null) list += "'$topCat' খাতে সবচেয়ে বেশি খরচ, সেখানে বাজেট সেট করুন।"
        if (state.rows("goals").isEmpty()) list += "একটি সঞ্চয় লক্ষ্য তৈরি করুন, যেমন জরুরি তহবিল।"
        if (state.rows("budgets").isEmpty()) list += "মাসিক বাজেট এনভেলপ সেট করলে খরচ নিয়ন্ত্রণে থাকে।"
        return if (list.isEmpty()) "সব ঠিক আছে। এভাবে চালিয়ে যান!" else list.joinToString(" ")
    }
}

/** Short financial summary sent to the AI (no raw rows; amounts hidden in privacy mode). */
fun aiContext(state: UiState): String {
    val p = state.privacy
    fun m(v: Double) = if (p) "***" else fmtNum(v)
    val month = monthPrefix()
    val txs = state.rows("transactions").filter { dateOf(it, "tx_date").startsWith(month) }
    val income = txs.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
    val expense = txs.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
    val cats = txs.filter { it.str("type") == "Expense" }.groupBy { it.str("category") }
        .map { (k, v) -> k to v.sumOf { it.num("amount") } }.sortedByDescending { it.second }.take(5)
    val debts = state.rows("debts").filter { !it.bool("settled") }
    val owe = debts.filter { it.str("direction") == "owe" }.sumOf { it.num("amount") }
    val owed = debts.filter { it.str("direction") == "owed" }.sumOf { it.num("amount") }
    return buildString {
        append("মুদ্রা: ${state.currency}. মাস: $month. মোট আয়: ${m(income)}. মোট ব্যয়: ${m(expense)}.\n")
        append("শীর্ষ ব্যয়ের ক্যাটাগরি: ")
        append(cats.joinToString(", ") { "${it.first} ${m(it.second)}" }.ifEmpty { "নেই" })
        append(".\nআপনার ধার: ${m(owe)}. আপনার পাওনা: ${m(owed)}.\n")
        append("বাজেট সংখ্যা: ${state.rows("budgets").size}, লক্ষ্য সংখ্যা: ${state.rows("goals").size}.")
    }.take(3900)
}

@Composable
fun ChatScreen(state: UiState, vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf("assistant" to "আসসালামু আলাইকুম! আমি FinTrack সহকারী। আপনার খরচ, বাকি, ধার বা যাকাত নিয়ে প্রশ্ন করুন।") }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    fun send(text: String) {
        val q = text.trim().take(1000)
        if (q.isEmpty() || thinking) return
        messages += "user" to q
        input = ""
        thinking = true
        scope.launch {
            val reply = try {
                vm.askAi(q, aiContext(state))
            } catch (e: Exception) {
                // Offline or AI not available: answer from local data instead.
                Assistant.answer(q, state) + "\n\n(AI সংযোগ নেই, স্থানীয় হিসাব থেকে উত্তর দেওয়া হয়েছে)"
            }
            messages += "assistant" to reply
            thinking = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceChips(
            listOf("এই মাসে কত খরচ?" to "এই মাসে কত খরচ?", "বাজেট কেমন?" to "বাজেট কেমন?", "পরামর্শ দিন" to "পরামর্শ দিন", "বাকি কত?" to "বাকি কত?"),
            ""
        ) { send(it) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { (who, text) ->
                val mine = who == "user"
                Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                            .padding(12.dp)
                    ) {
                        Text(text, color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (thinking) item { Text("ভাবছি…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextIn("প্রশ্ন লিখুন", input, { input = it }, modifier = Modifier.weight(1f))
            Button(onClick = { send(input) }, enabled = input.isNotBlank() && !thinking) { Text("পাঠান") }
        }
    }
}
