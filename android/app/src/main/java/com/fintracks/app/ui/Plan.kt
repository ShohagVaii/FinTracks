package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import kotlinx.serialization.json.JsonObject
import java.time.YearMonth

private val red = Rose500
private val green = Emerald500

// ======================= বাজেট এনভেলপ (rollover) =======================

/** Spending in one category during one month (YYYY-MM). */
private fun spentIn(txs: List<JsonObject>, category: String, month: String): Double =
    txs.filter { it.str("type") == "Expense" && it.str("category") == category && dateOf(it, "tx_date").startsWith(month) }
        .sumOf { it.num("amount") }

/**
 * This month's limit. With rollover on, unused money from earlier months is added on top.
 * The carry is worked out month by month over the last 12 months, starting from zero.
 */
fun effectiveBudgetLimit(budget: JsonObject, txs: List<JsonObject>, month: String): Double {
    val base = budget.num("monthly_limit")
    if (!budget.bool("rollover")) return base
    val category = budget.str("category")
    val current = YearMonth.parse(month)
    // Never carry money from months before the budget existed (created_at needs the budgets migration).
    val since = budget.strOrNull("created_at")?.take(7)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
    var carry = 0.0
    for (back in 12 downTo 1) {
        val ym = current.minusMonths(back.toLong())
        if (since != null && ym < since) continue
        val m = ym.toString()
        val available = base + carry
        carry = (available - spentIn(txs, category, m)).coerceAtLeast(0.0)
    }
    return base + carry
}

@Composable
fun BudgetsScreen(state: UiState, vm: AppViewModel) {
    var editing by remember { mutableStateOf<String?>(null) }
    var showing by remember { mutableStateOf(false) }
    val budgets = state.rows("budgets")
    val txs = state.rows("transactions")
    val month = monthPrefix()
    val p = state.privacy
    Page {
        AddButton(tr("বাজেট সেট করুন", "Set budget")) { showing = true }
        Text(
            "Monthly limit per category. With \"Carry to next month\" on, unused money is added to next month\'s limit. Yellow at 80% spent, red warning when over the limit.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (budgets.isEmpty()) EmptyHint(tr("কোনো বাজেট নেই", "No budgets"))
        budgets.forEach { b ->
            val cat = b.str("category")
            val spent = spentIn(txs, cat, month)
            val base = b.num("monthly_limit")
            val limit = effectiveBudgetLimit(b, txs, month)
            val carry = limit - base
            val ratio = if (limit > 0) spent / limit else 0.0
            val color = when {
                ratio >= 1.0 -> red
                ratio >= 0.8 -> Amber500
                else -> Sky500
            }
            AppCard(onClick = { editing = b.id() }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${catIcon(cat)} ${catLabel(cat)}", fontWeight = FontWeight.Bold)
                    Text("${taka(spent, p)} / ${taka(limit, p)}", fontSize = 12.sp)
                }
                ProgressBar(ratio.toFloat(), color)
                val notes = buildList {
                    if (b.bool("rollover")) add(tr("পরের মাসে নিয়ে যাওয়া চালু", "Carry-over to next month on"))
                    if (carry > 0.005) add(tr("আগের মাস থেকে +${taka(carry, p)}", "Carried from last month +${taka(carry, p)}"))
                    if (ratio >= 1.0) add(tr("সীমা ছাড়িয়েছে", "Over limit"))
                    else if (ratio >= 0.8) add(tr("৮০%+ খরচ হয়েছে", "80%+ spent"))
                }
                if (notes.isNotEmpty()) Text(notes.joinToString(" · "), fontSize = 11.sp, color = color)
                DeleteButton { vm.remove("budgets", b.id()) }
            }
        }
    }
    if (showing || editing != null) {
        val existing = budgets.firstOrNull { it.id() == editing }
        var category by remember { mutableStateOf(existing?.str("category") ?: "খাদ্য") }
        var otherOpen by remember { mutableStateOf(category == OTHER_CAT || category !in ALL_CATS.map { it.name }) }
        var limit by remember { mutableStateOf(existing?.let { fmtNum(it.num("monthly_limit")) } ?: "") }
        var rollover by remember { mutableStateOf(existing?.bool("rollover") == true) }
        FormDialog(tr("মাসিক বাজেট", "Monthly budget"), onDismiss = { showing = false; editing = null }, onConfirm = {
            val l = parseAmount(limit)
            if (l != null && l >= 0) {
                // Upsert: one budget per category, matching the web app's unique key.
                val target = budgets.firstOrNull { it.str("category") == category }?.id()
                vm.save("budgets", row("category" to category, "monthly_limit" to l, "rollover" to rollover), target)
                showing = false; editing = null
            }
        }) {
            Text(tr("ক্যাটাগরি", "Category"), fontSize = 12.sp)
            ChoiceChips(ALL_CATS.map { it.name to "${it.icon} ${it.name}" }.distinctBy { it.first }, category) { pick ->
                otherOpen = pick == OTHER_CAT
                category = pick
            }
            if (otherOpen) OtherCategoryField(category) { category = it }
            TextIn(tr("মাসিক সীমা (৳)", "Monthly limit (৳)"), limit, { limit = it }, number = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("অব্যবহৃত টাকা পরের মাসে নিয়ে যাবে", "Unused money carries to next month"), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = rollover, onCheckedChange = { rollover = it })
            }
        }
    }
}

// ======================= লক্ষ্য (জমা/তোলা + মাইলস্টোন) =======================

val GOAL_MILESTONES = listOf(25, 50, 75, 100)

@Composable
fun GoalsScreen(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    var topUp by remember { mutableStateOf<JsonObject?>(null) }
    val goals = state.rows("goals")
    val p = state.privacy
    Page {
        AddButton(tr("নতুন লক্ষ্য", "New goal")) { adding = true }
        if (goals.isEmpty()) EmptyHint(tr("কোনো সঞ্চয় লক্ষ্য নেই", "No savings goals"))
        goals.forEach { g ->
            val target = g.num("target")
            val saved = g.num("saved")
            val pct = if (target > 0) (saved / target * 100) else 0.0
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(g.str("name"), fontWeight = FontWeight.Bold)
                    DeleteButton { vm.remove("goals", g.id()) }
                }
                KeyValue(tr("জমা / লক্ষ্য", "Saved / goal"), "${taka(saved, p)} / ${taka(target, p)}")
                ProgressBar((pct / 100).toFloat(), green)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GOAL_MILESTONES.forEach { m ->
                        val reached = pct >= m
                        Text(
                            "$m%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (reached) green else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                g.strOrNull("deadline")?.let { Text(tr("শেষ তারিখ: ${it.take(10)}", "Due: ${it.take(10)}"), fontSize = 11.sp) }
                OutlinedButton(onClick = { topUp = g }) { Text(tr("জমা / তোলা", "Deposit / withdraw")) }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        var deadline by remember { mutableStateOf("") }
        FormDialog(tr("নতুন সঞ্চয় লক্ষ্য", "New savings goal"), onDismiss = { adding = false }, onConfirm = {
            val t = parseAmount(target)
            if (name.isNotBlank() && t != null && t > 0 && (deadline.isBlank() || isValidIsoDate(deadline))) {
                vm.save("goals", row("name" to name, "target" to t, "saved" to 0.0, "deadline" to nonEmpty(deadline)), null)
                adding = false
            }
        }) {
            TextIn(tr("লক্ষ্যের নাম", "Goal name"), name, { name = it })
            TextIn(tr("লক্ষ্য (৳)", "Goal (৳)"), target, { target = it }, number = true)
            DateField(tr("শেষ তারিখ (ঐচ্ছিক)", "Due date (optional)"), deadline, { deadline = it }, optional = true)
        }
    }
    topUp?.let { g ->
        var mode by remember { mutableStateOf("deposit") }
        var amount by remember { mutableStateOf("") }
        val target = g.num("target")
        val saved = g.num("saved")
        val a = parseAmount(amount)
        val delta = if (a == null) 0.0 else if (mode == "withdraw") -a else a
        val after = saved + delta
        val error = when {
            a == null || a <= 0 -> null
            after > target + 0.005 -> tr("লক্ষ্যের বেশি জমা করা যাবে না (বাকি জমার সুযোগ ${taka(target - saved, false)})", "Cannot save more than the goal (room left: ${taka(target - saved, false)})")
            after < -0.005 -> tr("জমার বেশি তোলা যাবে না (জমা আছে ${taka(saved, false)})", "Cannot withdraw more than saved (saved: ${taka(saved, false)})")
            else -> null
        }
        val valid = a != null && a > 0 && error == null
        FormDialog("${g.str("name")}: জমা বা তোলা", confirmEnabled = valid, onDismiss = { topUp = null }, onConfirm = {
            if (a != null) {
                vm.changeGoal(g, delta)
                topUp = null
            }
        }) {
            Text(tr("বর্তমান জমা: ${taka(saved, false)} / ${taka(target, false)}", "Current savings: ${taka(saved, false)} / ${taka(target, false)}"), fontSize = 12.sp)
            ChoiceChips(listOf("deposit" to tr("জমা", "Deposit"), "withdraw" to tr("তোলা", "Withdraw")), mode) { mode = it }
            TextIn(tr("পরিমাণ (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
            if (error != null) Text(error, fontSize = 12.sp, color = red)
        }
    }
    state.celebration?.let { c ->
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { vm.dismissCelebration() },
            title = { Text(tr("অভিনন্দন! ${c.percent}% অর্জন", "Congratulations! ${c.percent}% reached"), fontWeight = FontWeight.Bold) },
            text = {
                Text("\"${c.goalName}\" goal: ${taka(c.saved, p)} saved. Keep going!")
            },
            confirmButton = {
                TextButton(onClick = {
                    MilestoneCard.share(ctx, c)
                    vm.dismissCelebration()
                }) { Text(tr("শেয়ার কার্ড", "Share card")) }
            },
            dismissButton = { TextButton(onClick = { vm.dismissCelebration() }) { Text(tr("বন্ধ", "Off")) } }
        )
    }
}
