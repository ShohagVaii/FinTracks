package com.fintracks.app.ui

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
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
import com.fintracks.app.data.row
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Offline assistant. It answers from the user's own data with no network and no API key.
 * Answers from local data. When online, ChatScreen first asks the Supabase edge function ai-chat (Gemini key stays on the server).
 */
object Assistant {
    private fun has(q: String, vararg words: String) = words.any { q.contains(it) }

    fun answer(question: String, state: UiState): String {
        val q = question.lowercase()
        val p = state.privacy
        val month = monthPrefix()
        val txs = state.rows("transactions")
        val monthTx = txs.filter { dateOf(it, "tx_date").startsWith(month) }
        val flow = flowTotalsForMonth(txs, month)
        val income = flow.income
        val expense = flow.expense
        val topCat = monthTx.filter { it.str("type") == "Expense" }
            .groupBy { it.str("category") }.maxByOrNull { e -> e.value.sumOf { it.num("amount") } }

        return when {
            has(q, tr("বাকি", "Due"), "credit", "দোকান") -> {
                val due = state.rows("credit_shops").sumOf { shopBalance(it.id(), state) }
                tr("মোট বাকি আছে ${taka(due, p)}। দোকানভিত্তিক হিসাব বাকি মেনুতে দেখতে পারবেন।", "Total due is ${taka(due, p)}. You can see the shop-wise breakdown in the Due menu.")
            }
            has(q, "ধার", "পাওনা", "debt", "loan", "লোন") -> {
                val owe = debtsRemaining(state, "owe")
                val owed = debtsRemaining(state, "owed")
                tr("আপনি ধারি ${taka(owe, p)}, আপনার পাওনা ${taka(owed, p)}।", "You owe ${taka(owe, p)}, and you are owed ${taka(owed, p)}.")
            }
            has(q, "যাকাত", "zakat") -> {
                val cash = txs.sumOf { if (it.str("type") == "Income") it.num("amount") else if (it.str("type") == "Expense") -it.num("amount") else 0.0 }
                val assets = state.rows("investments").sumOf { it.num("current_value") }
                tr("হিসাব অনুযায়ী মোট সম্পদ ${taka(cash + assets, p)}, যাকাত ২.৫%, তবে শুধু সম্পদ নিসাবের (রূপার ভিত্তিতে ৬১২.৩৬ গ্রাম) সমান বা বেশি হলে। নিসাব ও স্বর্ণ/রূপা যোগ করে যাকাত টুলসে হিসাব করুন।", "Total assets: ${taka(cash + assets, p)}. Zakat is 2.5%, but only if assets meet or exceed the nisab (silver basis 612.36 g). Use the Zakat tool to calculate with the nisab and gold/silver prices.")
            }
            has(q, "ট্যাক্স", "tax") -> "Enter yearly income in the Tax tool and it will show tax by slab. Tax-free limit for general taxpayers: ${taka(Web.GENERAL_TAX_THRESHOLD, false)}, for women and 65+: ${taka(Web.WOMEN_TAX_THRESHOLD, false)}."
            has(q, "বাজেট", "budget") -> {
                val over = state.rows("budgets").filter { b ->
                    val spent = monthTx.filter { it.str("type") == "Expense" && it.str("category") == b.str("category") }.sumOf { it.num("amount") }
                    spent > b.num("monthly_limit") && b.num("monthly_limit") > 0
                }
                if (over.isEmpty()) tr("এই মাসে কোনো বাজেট ছাড়িয়ে যায়নি। ভালো কাজ চলছে!", "No budget was exceeded this month. Good work!")
                else "এই ক্যাটাগরিগুলো বাজেট ছাড়িয়েছে: " + over.joinToString(", ") { it.str("category") } + "।"
            }
            has(q, "সঞ্চয়", "save", "saving") -> {
                if (income <= 0) tr("এই মাসে আয় নেই, তাই সঞ্চয়ের হার হিসাব করা যাচ্ছে না। আয় যোগ করুন।", "No income this month, so the savings rate can't be calculated. Add income.")
                else tr("এই মাসে সঞ্চয়ের হার ${String.format("%.0f", (income - expense) / income * 100)}%। ২০% বা তার বেশি হলে ভালো।", "Savings rate this month: ${String.format("%.0f", (income - expense) / income * 100)}%. 20% or more is good.")
            }
            has(q, tr("আয়", "Income"), "income", "বেতন") -> "এই মাসে মোট আয় ${taka(income, p)}।"
            has(q, "খরচ", "expense", "spend") -> {
                val extra = if (topCat != null) tr(" সবচেয়ে বেশি খরচ '${topCat.key}' খাতে (${taka(topCat.value.sumOf { it.num("amount") }, p)})।", " Highest spending is in '${topCat.key}' (${taka(topCat.value.sumOf { it.num("amount") }, p)}).") else ""
                tr("এই মাসে মোট খরচ ${taka(expense, p)}।$extra", "Total spending this month: ${taka(expense, p)}.$extra")
            }
            has(q, "ব্যালেন্স", "balance", "কত টাকা", "আছে") -> {
                val total = state.rows("accounts").sumOf { accountBalance(it, txs) } +
                    (if (state.rows("accounts").isEmpty()) txs.sumOf { if (it.str("type") == "Income") it.num("amount") else if (it.str("type") == "Expense") -it.num("amount") else 0.0 } else 0.0)
                tr("আপনার মোট ব্যালেন্স ${taka(total, p)}।", "Your total balance is ${taka(total, p)}.")
            }
            has(q, "সাবস্ক্রিপশন", "subscription") -> {
                val monthly = state.rows("subscriptions").filter { it.bool("is_active") }.sumOf { subscriptionMonthly(it) }
                tr("সক্রিয় সাবস্ক্রিপশনের মাসিক খরচ আনুমানিক ${taka(monthly, p)}।", "Estimated monthly cost of active subscriptions: ${taka(monthly, p)}.")
            }
            has(q, "পরামর্শ", "tip", "advice", "সাজেশন", "উপদেশ") -> tips(state, income, expense, topCat?.key)
            else -> "I can answer questions about spending, income, budget, savings, dues, debts, zakat, tax or balance. For example: \"How much did I spend this month?\""
        }
    }

    private fun tips(state: UiState, income: Double, expense: Double, topCat: String?): String {
        val list = mutableListOf<String>()
        if (income > 0 && expense > income * 0.8) list += tr("খরচ আয়ের ৮০% ছাড়িয়েছে। অপ্রয়োজনীয় খরচ কমান।", "Spending is over 80% of income. Cut unnecessary costs.")
        if (topCat != null) list += tr("'$topCat' খাতে সবচেয়ে বেশি খরচ, সেখানে বাজেট সেট করুন।", "Highest spending is in '$topCat'. Set a budget there.")
        if (state.rows("goals").isEmpty()) list += tr("একটি সঞ্চয় লক্ষ্য তৈরি করুন, যেমন জরুরি তহবিল।", "Create a savings goal, such as an emergency fund.")
        if (state.rows("budgets").isEmpty()) list += tr("মাসিক বাজেট এনভেলপ সেট করলে খরচ নিয়ন্ত্রণে থাকে।", "Setting monthly budget envelopes keeps spending under control.")
        return if (list.isEmpty()) tr("সব ঠিক আছে। এভাবে চালিয়ে যান!", "All good. Keep it up!") else list.joinToString(" ")
    }
}

/** Short financial summary sent to the AI (no raw rows; amounts hidden in privacy mode). */
fun aiContext(state: UiState): String {
    val p = state.privacy
    fun m(v: Double) = if (p) "***" else fmtNum(shownAmount(v))
    val month = monthPrefix()
    val txs = state.rows("transactions").filter { dateOf(it, "tx_date").startsWith(month) }
    // Same calculation as the Dashboard (flowTotals). Debts are listed separately below and are never added to income or expense.
    val flow = flowTotalsForMonth(state.rows("transactions"), month)
    val income = flow.income
    val expense = flow.expense
    val cats = txs.filter { it.str("type") == "Expense" }.groupBy { it.str("category") }
        .map { (k, v) -> k to v.sumOf { it.num("amount") } }.sortedByDescending { it.second }.take(5)
    val owe = debtsRemaining(state, "owe")
    val owed = debtsRemaining(state, "owed")
    val none = tr("নেই", "none")
    return buildString {
        append(tr("মুদ্রা: ", "Currency: ")).append(state.currency)
        append(tr(". মাস: ", ". Month: ")).append(month)
        append(tr(". মোট আয়: ", ". Total income: ")).append(m(income))
        append(tr(". মোট ব্যয়: ", ". Total expense: ")).append(m(expense))
        append(tr(". নিট (আয় - ব্যয়): ", ". Net (income - expense): ")).append(m(flow.net)).append(".\n")
        append(tr("শীর্ষ ব্যয়ের ক্যাটাগরি: ", "Top expense categories: "))
        append(cats.joinToString(", ") { "${catLabel(it.first)} ${m(it.second)}" }.ifEmpty { none })
        append(tr(".\nধার ও পাওনা (আয়-ব্যয়ের হিসাবের বাইরে, আলাদা তালিকা): আপনার ধার ", ".\nDebts and dues (separate from income and expense): you owe ")).append(m(owe))
        append(tr(", আপনার পাওনা ", ", owed to you ")).append(m(owed)).append(".\n")
        append(tr("বাজেট সংখ্যা: ", "Budgets: ")).append(state.rows("budgets").size)
        append(tr(", লক্ষ্য সংখ্যা: ", ", goals: ")).append(state.rows("goals").size).append(".")
    }.take(3900)
}

/**
 * One chat line. "user", "assistant" (AI) and "local" (answer from device data, not AI) are saved on the account
 * (ai_chat_messages), with date and time. "error" lines exist only on this screen: they show the real reason,
 * with Retry and a separate button for a local answer.
 */
private data class ChatMsg(val id: String, val who: String, val text: String, val at: String, val question: String = "")

private val CHAT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.US)

private fun chatInstant(iso: String): Instant = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrDefault(Instant.EPOCH)

private fun chatTime(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(CHAT_TIME)
}.getOrDefault("")

@Composable
fun ChatScreen(state: UiState, vm: AppViewModel, onClose: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val greeting = tr("হ্যালো! আমি FinTrack AI। আমি আপনাকে বাজেট, সঞ্চয় বা বিনিয়োগের পরামর্শ দিয়ে সাহায্য করতে পারি। আপনি কী জানতে চান?", "Hello! I'm FinTrack AI. I can help with budgets, savings and investment advice. What would you like to know?")
    // The account's saved chat, oldest first. It comes from the local copy, so it shows offline too.
    val saved = state.rows("ai_chat_messages").sortedBy { chatInstant(it.str("created_at")) }
    val errors = remember { mutableStateListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val lines: List<ChatMsg> = saved.map { ChatMsg(it.id(), it.str("role"), it.str("text"), it.str("created_at")) } + errors

    fun saveLine(role: String, text: String) {
        vm.save("ai_chat_messages", row("role" to role, "text" to text, "created_at" to OffsetDateTime.now().toString()), null)
    }

    fun send(text: String, saveQuestion: Boolean = true) {
        val q = text.trim().take(1000)
        if (q.isEmpty() || thinking) return
        input = ""
        thinking = true
        if (saveQuestion) saveLine("user", q)
        scope.launch {
            // No silent fallback: a failure shows its real reason, with retry and an explicit local-answer button.
            try {
                saveLine("assistant", vm.askAi(q, aiContext(state), if (AppPrefs.english) "en" else "bn"))
            } catch (e: Exception) {
                val reason = if (e is java.io.IOException && e !is com.fintracks.app.data.SupabaseException) {
                    tr("ইন্টারনেট সংযোগ বা সময়সীমা: ${e.message ?: "অজানা"}", "Connection or timeout: ${e.message ?: "unknown"}")
                } else {
                    e.message ?: tr("অজানা ত্রুটি", "Unknown error")
                }
                errors += ChatMsg(
                    "err-${System.nanoTime()}", "error",
                    tr("AI উত্তর দিতে পারেনি।\nকারণ: ", "AI could not answer.\nReason: ") + reason,
                    OffsetDateTime.now().toString(), q
                )
            } finally {
                thinking = false
            }
        }
    }

    val purple = Violet600
    Column(Modifier.fillMaxSize()) {
        // Purple advisor header, like the reference "Ask FinTrack AI" bar.
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(Tok_FF1E1B4B, Tok_FF3730A3)))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.SmartToy, contentDescription = null, tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ask FinTrack AI", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("PREMIUM ADVISOR", color = Tok_FFC7D2FE, fontSize = 10.sp, letterSpacing = 2.sp)
            }
            IconButton(modifier = Modifier.pressScale(), onClick = onClose) { Icon(Icons.Default.Close, contentDescription = tr("বন্ধ করুন", "Close"), tint = Color.White) }
        }
        ChoiceChips(
            // Label follows the language switch; the question sent matches the language so the answer engine reads it.
            listOf(
                tr("এই মাসে কত খরচ?", "How much did I spend this month?") to tr("এই মাসে কত খরচ?", "How much did I spend this month?"),
                tr("বাজেট কেমন?", "How is my budget?") to tr("বাজেট কেমন?", "How is my budget?"),
                tr("পরামর্শ দিন", "Give me advice") to tr("পরামর্শ দিন", "Give me advice"),
                tr("বাকি কত?", "What is due?") to tr("বাকি কত?", "What is due?")
            ),
            ""
        ) { send(it) }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { ChatBubble(ChatMsg("greeting", "assistant", greeting, ""), thinking, onRetry = {}, onLocal = {}) }
            items(lines, key = { it.id }) { msg ->
                ChatBubble(
                    msg = msg,
                    thinking = thinking,
                    onRetry = {
                        errors.remove(msg)
                        send(msg.question, saveQuestion = false)
                    },
                    onLocal = {
                        errors.remove(msg)
                        saveLine("local", Assistant.answer(msg.question, state) + tr("\n\n(এটি লোকাল ডাটা থেকে তৈরি, AI উত্তর নয়)", "\n\n(From local data, not AI)"))
                    }
                )
            }
            if (thinking) item { Text(tr("ভাবছি…", "Thinking…"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextIn(tr("আপনার বিষয়টি লিখুন...", "Type your question..."), input, { input = it }, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Violet600, Indigo600)))
                    .clickable(enabled = input.isNotBlank() && !thinking) { send(input) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("পাঠান", "Send"), tint = Color.White) }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMsg, thinking: Boolean, onRetry: () -> Unit, onLocal: () -> Unit) {
    val mine = msg.who == "user"
    val isError = msg.who == "error"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxWidth(0.82f)
                .clip(RoundedCornerShape(20.dp))
                .background(if (mine) Violet600 else MaterialTheme.colorScheme.surface)
                .border(1.dp, if (mine) Color.Transparent else Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                .padding(14.dp)
        ) {
            Column {
                val label = when (msg.who) {
                    "local" -> tr("লোকাল ডাটা (AI নয়)", "Local data (not AI)")
                    "assistant" -> "FinTrack AI"
                    else -> ""
                }
                if (label.isNotEmpty()) Text(label, fontSize = 10.sp, color = Tok_FFA78BFA, letterSpacing = 1.sp)
                Text(msg.text, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp)
                if (msg.at.isNotEmpty()) {
                    Text(
                        bnDigits(chatTime(msg.at)),
                        fontSize = 10.sp,
                        color = if (mine) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isError) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            tr("আবার চেষ্টা করুন", "Retry"),
                            color = Tok_FFA78BFA, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(enabled = !thinking) { if (msg.question.isNotEmpty()) onRetry() }
                        )
                        Text(
                            tr("লোকাল ডাটা থেকে উত্তর দেখুন", "Show answer from local data"),
                            color = Tok_FFA78BFA, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onLocal() }
                        )
                    }
                }
            }
        }
    }
}
