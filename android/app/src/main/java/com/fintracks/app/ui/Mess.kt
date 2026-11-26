package com.fintracks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.ui.design.DivergingBar
import com.fintracks.app.ui.design.SegmentedTabs
import com.fintracks.app.ui.design.SpeedDialFab
import com.fintracks.app.ui.design.StatusChip
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

// ======================= Calculation (pure, no UI) =======================

/** Pseudo-party for the pooled cash: its balance is minus the sum of member balances, so balances always sum to 0. */
internal const val MESS_FUND = "__fund__"

private fun messIsoDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.take(10)) }.getOrNull()

/** Legacy members (no is_active value) count as active. */
internal fun messActive(m: JsonObject): Boolean = (m["is_active"] as? JsonPrimitive)?.booleanOrNull ?: true

/** Days a member was in the mess during [ym], clipped to the month and to joined / left dates. */
internal fun messActiveDays(m: JsonObject, ym: YearMonth): Int {
    val start = ym.atDay(1)
    val end = ym.atEndOfMonth()
    val from = messIsoDate(m.str("joined_on"))?.let { if (it > start) it else start } ?: start
    val to = messIsoDate(m.str("left_on"))?.let { if (it < end) it else end } ?: end
    return if (to < from) 0 else (to.toEpochDay() - from.toEpochDay()).toInt() + 1
}

private fun MutableMap<String, Double>.messAdd(key: String, v: Double) {
    this[key] = (this[key] ?: 0.0) + v
}

internal class MessCalc(
    val rate: Double,
    val totalBazaar: Double,
    val totalMeals: Double,
    val totalFixed: Double,
    val balances: Map<String, Double>,
    val fixedShare: Map<String, Double>,
    val mealCount: Map<String, Double>,
    val mealCost: Map<String, Double>,
    val bazaarBy: Map<String, Double>,
    val depositBy: Map<String, Double>
)

/**
 * Month figures. Meal rate = total bazaar ÷ total meals (guest meals included).
 * Member balance = carried-over balance + (own bazaar + deposit) − (meals × rate) − fixed share.
 * Fixed costs are shared equally among members present in the month, or by days present.
 */
internal fun messCalc(state: UiState, ym: YearMonth): MessCalc {
    val prefix = ym.toString()
    val members = state.rows("mess_members")
    val bazaars = state.rows("mess_bazaars").filter { dateOf(it, "bazaar_date").startsWith(prefix) }
    val meals = state.rows("mess_meals").filter { dateOf(it, "meal_date").startsWith(prefix) }
    val deposits = state.rows("mess_deposits").filter { dateOf(it, "deposit_date").startsWith(prefix) }
    val fixed = state.rows("mess_fixed_costs").filter { dateOf(it, "cost_date").startsWith(prefix) }
    // Carry-over: final balances of the most recent closed month before this one.
    val carry = state.rows("mess_months").filter { it.str("month") < prefix }
        .maxByOrNull { it.str("month") }?.get("final_balances") as? JsonObject

    val totalBazaar = bazaars.sumOf { it.num("amount") }
    val totalMeals = meals.sumOf { it.num("meal_count") + it.num("guest_count") }
    val rate = if (totalMeals > 0) totalBazaar / totalMeals else 0.0

    val mealCount = HashMap<String, Double>()
    val bazaarBy = HashMap<String, Double>()
    val depositBy = HashMap<String, Double>()
    meals.forEach { mealCount.messAdd(it.str("member_id"), it.num("meal_count") + it.num("guest_count")) }
    bazaars.forEach { bazaarBy.messAdd(it.str("member_id"), it.num("amount")) }
    deposits.forEach { depositBy.messAdd(it.str("member_id"), it.num("amount")) }

    val totalFixed = fixed.sumOf { it.num("amount") }
    val fixedShare = HashMap<String, Double>()
    fixed.forEach { f ->
        val byDays = f.str("split_mode") == "days"
        val weights = members.associate { m ->
            val d = messActiveDays(m, ym)
            m.id() to (if (byDays) d.toDouble() else if (d > 0) 1.0 else 0.0)
        }
        val sum = weights.values.sum()
        if (sum > 0) weights.forEach { (k, w) -> fixedShare.messAdd(k, f.num("amount") * w / sum) }
    }

    val mealCost = HashMap<String, Double>()
    val balances = LinkedHashMap<String, Double>()
    members.forEach { m ->
        val k = m.id()
        val cost = (mealCount[k] ?: 0.0) * rate
        mealCost[k] = cost
        val paid = (bazaarBy[k] ?: 0.0) + (depositBy[k] ?: 0.0)
        val opening = (carry?.get(k) as? JsonPrimitive)?.doubleOrNull ?: 0.0
        balances[k] = opening + paid - cost - (fixedShare[k] ?: 0.0)
    }
    balances[MESS_FUND] = -balances.values.sum()
    return MessCalc(rate, totalBazaar, totalMeals, totalFixed, balances, fixedShare, mealCount, mealCost, bazaarBy, depositBy)
}

/** Guest count stored on the day's meal row (first row wins). */
private fun messGuest(state: UiState, memberId: String, date: String): Double =
    state.rows("mess_meals").firstOrNull { it.str("member_id") == memberId && dateOf(it, "meal_date") == date }
        ?.num("guest_count") ?: 0.0

/** Writes one member's meal and guest count for a day. Keeps one row per member and day; zero on both removes it. */
private fun messWriteDay(vm: AppViewModel, state: UiState, memberId: String, date: String, meal: Double, guest: Double) {
    val here = state.rows("mess_meals").filter { it.str("member_id") == memberId && dateOf(it, "meal_date") == date }
    here.drop(1).forEach { vm.remove("mess_meals", it.id()) }
    val keep = here.firstOrNull()
    when {
        meal <= 0.0 && guest <= 0.0 -> if (keep != null) vm.remove("mess_meals", keep.id())
        keep == null -> vm.save(
            "mess_meals",
            row("member_id" to memberId, "meal_date" to date, "meal_count" to meal, "guest_count" to guest, "slot" to "day"),
            null
        )
        else -> vm.save("mess_meals", row("meal_count" to meal, "guest_count" to guest), keep.id())
    }
}

/** Grid tap cycle: 0 → 1 → 1.5 → 2 → 0. */
private fun messMealStep(v: Double): Double = when {
    v < 0.01 -> 1.0
    abs(v - 1.0) < 0.01 -> 1.5
    abs(v - 1.5) < 0.01 -> 2.0
    else -> 0.0
}

private fun messStatementLines(name: String, calc: MessCalc, id: String, monthLabel: String, p: Boolean): List<String> {
    val bal = calc.balances[id] ?: 0.0
    val balLine = when {
        bal > 0.005 -> "${tr("পাবেন", "Gets back")}: ${taka(bal, p)}"
        bal < -0.005 -> "${tr("দেবেন", "Owes")}: ${taka(-bal, p)}"
        else -> tr("মিটে গেছে", "Settled")
    }
    return listOf(
        "${tr("মাস", "Month")}: $monthLabel",
        "${tr("সদস্য", "Member")}: $name",
        "${tr("মিল", "Meals")}: ${fmtNum(calc.mealCount[id] ?: 0.0)}",
        "${tr("বাজার", "Bazaar")}: ${taka(calc.bazaarBy[id] ?: 0.0, p)}",
        "${tr("জমা", "Deposit")}: ${taka(calc.depositBy[id] ?: 0.0, p)}",
        "${tr("মিলের খরচ", "Meal cost")}: ${taka(calc.mealCost[id] ?: 0.0, p)}",
        "${tr("ফিক্সড খরচের ভাগ", "Fixed share")}: ${taka(calc.fixedShare[id] ?: 0.0, p)}",
        balLine
    )
}

// ======================= Screen =======================

@Composable
internal fun MessManager(state: UiState, vm: AppViewModel) {
    val p = state.privacy
    var ym by remember { mutableStateOf(YearMonth.now()) }
    var tab by remember { mutableStateOf("sum") }
    var quick by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var statementFor by remember { mutableStateOf<JsonObject?>(null) }

    val prefix = ym.toString()
    val today = todayIso()
    val monthLabel = monthYearLabel(ym.year, ym.monthValue)
    val calc = messCalc(state, ym)
    val members = state.rows("mess_members")
    val active = members.filter { messActive(it) }
    val closed = state.rows("mess_months").firstOrNull { it.str("month") == prefix }
    val locked = closed != null
    val nameOf = members.associate { it.id() to it.str("name") } + mapOf(MESS_FUND to tr("মেস ফান্ড", "Mess fund"))
    val payouts = settleBalances(calc.balances)
    val maxAbs = calc.balances.filterKeys { it != MESS_FUND }.values.maxOfOrNull { abs(it) } ?: 0.0
    val bazaars = state.rows("mess_bazaars").filter { dateOf(it, "bazaar_date").startsWith(prefix) }
    val deposits = state.rows("mess_deposits").filter { dateOf(it, "deposit_date").startsWith(prefix) }
    val fixed = state.rows("mess_fixed_costs").filter { dateOf(it, "cost_date").startsWith(prefix) }
    val prevDepBy = state.rows("mess_deposits")
        .filter { dateOf(it, "deposit_date").startsWith(ym.minusMonths(1).toString()) }
        .groupBy { it.str("member_id") }
        .mapValues { e -> e.value.sumOf { it.num("amount") } }

    MonthNav(ym) { ym = it; quick = false }
    if (locked) StatusChip(tr("মাস বন্ধ", "Month closed"), Sky400)

    SegmentedTabs(
        listOf(
            "sum" to tr("সারসংক্ষেপ", "Summary"),
            "meal" to tr("মিল", "Meals"),
            "bazar" to tr("বাজার", "Bazaar"),
            "dep" to tr("জমা", "Deposits"),
            "cost" to tr("খরচ", "Costs")
        ),
        selected = tab,
        onSelect = { tab = it; quick = false }
    )

    if (quick) {
        MessQuickMeals(state, vm, active, today, locked) { quick = false }
    } else {
        when (tab) {
            "meal" -> MessMealGrid(state, vm, ym, members, calc, today, locked) { quick = true }
            "bazar" -> MessBazaarTab(state, vm, bazaars, active, nameOf, p, locked) { dialog = "bazar" }
            "dep" -> MessDepositTab(state, vm, deposits, active, calc, prevDepBy, p, locked) { dialog = "dep" }
            "cost" -> MessCostTab(vm, fixed, p, locked) { dialog = "fixed" }
            else -> MessSummaryTab(
                vm = vm,
                members = members,
                calc = calc,
                payouts = payouts,
                nameOf = nameOf,
                maxAbs = maxAbs,
                prevDepBy = prevDepBy,
                p = p,
                locked = locked,
                hasMembers = members.isNotEmpty(),
                onEdit = { editing = it; dialog = "member" },
                onStatement = { statementFor = it },
                onClose = { dialog = "close" },
                onReopen = { closed?.let { vm.remove("mess_months", it.id()) } }
            )
        }
    }

    if (!locked) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SpeedDialFab(
                listOf(
                    Triple(tr("মিল", "Meal"), Icons.Default.Check, { tab = "meal"; quick = true }),
                    Triple(tr("বাজার", "Bazaar"), Icons.Default.Add, { dialog = "bazar" }),
                    Triple(tr("জমা", "Deposit"), Icons.Default.Add, { dialog = "dep" }),
                    Triple(tr("সদস্য", "Member"), Icons.Default.Edit, { editing = null; dialog = "member" })
                )
            )
        }
    }

    when (dialog) {
        "member" -> MessMemberDialog(editing, vm) { dialog = ""; editing = null }
        "bazar" -> MessAddBazaarDialog(active, vm) { dialog = "" }
        "dep" -> MessDepositDialog(active, vm) { dialog = "" }
        "fixed" -> MessFixedDialog(vm) { dialog = "" }
        "close" -> MessCloseDialog(prefix, calc, p, vm) { dialog = "" }
    }
    statementFor?.let { m ->
        ShopStatementDialog(
            title = "${m.str("name")} · $monthLabel",
            lines = messStatementLines(m.str("name"), calc, m.id(), monthLabel, p),
            onDismiss = { statementFor = null }
        )
    }
}

// ======================= Meals =======================

@Composable
private fun MessStepper(value: String, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(modifier = Modifier.pressScale(), enabled = enabled, onClick = onMinus) {
            Text("−", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
        IconButton(modifier = Modifier.pressScale(), enabled = enabled, onClick = onPlus) {
            Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MessMealGrid(
    state: UiState,
    vm: AppViewModel,
    ym: YearMonth,
    members: List<JsonObject>,
    calc: MessCalc,
    today: String,
    locked: Boolean,
    onQuick: () -> Unit
) {
    val rows = members.filter { messActive(it) || (calc.mealCount[it.id()] ?: 0.0) > 0.0 }
    Button(
        onClick = onQuick,
        enabled = !locked && rows.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Sky500, contentColor = Color.White)
    ) { Text(tr("আজকের মিল", "Today's meals"), fontWeight = FontWeight.Bold) }
    if (rows.isEmpty()) {
        EmptyHint(tr("কোনো সদস্য নেই। সদস্য যোগ করুন।", "No members yet. Add one."))
        return
    }
    val days = ym.lengthOfMonth()
    val monthMeals = state.rows("mess_meals").filter { dateOf(it, "meal_date").startsWith(ym.toString()) }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(96.dp))
            (1..days).forEach { d ->
                val isToday = ym.atDay(d).toString() == today
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    Text(
                        d.toString(),
                        fontSize = 11.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) Sky400 else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        rows.forEach { m ->
            val id = m.id()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.str("name"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(96.dp)
                )
                (1..days).forEach { d ->
                    val date = ym.atDay(d).toString()
                    val v = monthMeals.filter { it.str("member_id") == id && dateOf(it, "meal_date") == date }
                        .sumOf { it.num("meal_count") }
                    val isToday = date == today
                    Box(
                        Modifier
                            .padding(1.dp)
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    v > 0.0 -> Emerald500.copy(alpha = 0.22f)
                                    isToday -> Sky400.copy(alpha = 0.12f)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable(enabled = !locked) {
                                messWriteDay(vm, state, id, date, messMealStep(v), messGuest(state, id, date))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (v > 0.0) fmtNum(v) else "·",
                            fontSize = 12.sp,
                            fontWeight = if (v > 0.0) FontWeight.Bold else FontWeight.Normal,
                            color = if (v > 0.0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessQuickMeals(
    state: UiState,
    vm: AppViewModel,
    active: List<JsonObject>,
    today: String,
    locked: Boolean,
    onBack: () -> Unit
) {
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(tr("মিল গ্রিডে ফিরুন", "Back to meal grid"))
    }
    Text(displayDate(today), fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Button(
        onClick = {
            active.forEach { m -> messWriteDay(vm, state, m.id(), today, 1.0, messGuest(state, m.id(), today)) }
        },
        enabled = !locked && active.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Color.White)
    ) { Text(tr("সবাই ১", "Everyone 1"), fontWeight = FontWeight.Bold) }
    if (active.isEmpty()) EmptyHint(tr("কোনো সক্রিয় সদস্য নেই", "No active members"))
    active.forEach { m ->
        val id = m.id()
        val meal = state.rows("mess_meals")
            .filter { it.str("member_id") == id && dateOf(it, "meal_date") == today }
            .sumOf { it.num("meal_count") }
        val guest = messGuest(state, id, today)
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.str("name"),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                MessStepper(
                    value = fmtNum(meal),
                    enabled = !locked,
                    onMinus = { messWriteDay(vm, state, id, today, (meal - 0.5).coerceAtLeast(0.0), guest) },
                    onPlus = { messWriteDay(vm, state, id, today, meal + 0.5, guest) }
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("অতিথি", "Guests"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                MessStepper(
                    value = fmtNum(guest),
                    enabled = !locked,
                    onMinus = { messWriteDay(vm, state, id, today, meal, (guest - 1).coerceAtLeast(0.0)) },
                    onPlus = { messWriteDay(vm, state, id, today, meal, guest + 1) }
                )
            }
        }
    }
}

// ======================= Bazaar, deposits, costs =======================

@Composable
private fun MessBazaarTab(
    state: UiState,
    vm: AppViewModel,
    bazaars: List<JsonObject>,
    active: List<JsonObject>,
    nameOf: Map<String, String>,
    p: Boolean,
    locked: Boolean,
    onAdd: () -> Unit
) {
    val ctx = LocalContext.current
    val next = active.minByOrNull { m -> bazaars.count { it.str("member_id") == m.id() } }
    if (next != null) {
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("পরের বাজারদার", "Next to shop"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(next.str("name"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                IconButton(
                    modifier = Modifier.pressScale(),
                    onClick = { shareText(ctx, tr("আজকের বাজার: ${next.str("name")}", "Today's bazaar: ${next.str("name")}")) }
                ) { Icon(Icons.Default.Share, contentDescription = tr("রিমাইন্ডার শেয়ার", "Share reminder")) }
            }
        }
    }
    Button(
        onClick = onAdd,
        enabled = !locked && active.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Color.White)
    ) { Text(tr("+ বাজার এন্ট্রি", "+ Bazaar entry"), fontWeight = FontWeight.Bold) }

    SectionTitle(tr("এই মাসের বাজার", "This month's bazaar"))
    if (bazaars.isEmpty()) EmptyHint(tr("এই মাসে কোনো বাজার এন্ট্রি নেই", "No bazaar entries this month"))
    bazaars.sortedByDescending { dateOf(it, "bazaar_date") }.forEach { b ->
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${nameOf[b.str("member_id")].orEmpty()} · ${b.str("item").ifBlank { tr("বাজার", "Bazaar") }}",
                        fontWeight = FontWeight.Bold
                    )
                    Text(displayDate(dateOf(b, "bazaar_date")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(taka(b.num("amount"), p), fontWeight = FontWeight.Bold)
                if (!locked) DeleteButton { vm.remove("mess_bazaars", b.id()) }
            }
        }
    }
}

@Composable
private fun MessDepositTab(
    state: UiState,
    vm: AppViewModel,
    deposits: List<JsonObject>,
    active: List<JsonObject>,
    calc: MessCalc,
    prevDepBy: Map<String, Double>,
    p: Boolean,
    locked: Boolean,
    onAdd: () -> Unit
) {
    Button(
        onClick = onAdd,
        enabled = !locked && active.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.White)
    ) { Text(tr("+ জমা এন্ট্রি", "+ Deposit entry"), fontWeight = FontWeight.Bold) }

    SectionTitle(tr("সদস্যভিত্তিক জমা", "Deposits by member"))
    active.forEach { m ->
        val id = m.id()
        val now = calc.depositBy[id] ?: 0.0
        val prev = prevDepBy[id] ?: 0.0
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(m.str("name"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (prev > 0.0 && now < prev) {
                    StatusChip(tr("জমা কমেছে", "Deposit dropped"), Amber500)
                    Spacer(Modifier.width(8.dp))
                }
                Text(taka(now, p), fontWeight = FontWeight.Bold)
            }
        }
    }

    SectionTitle(tr("জমার তালিকা", "Deposit entries"))
    if (deposits.isEmpty()) EmptyHint(tr("এই মাসে কোনো জমা এন্ট্রি নেই", "No deposit entries this month"))
    val nameOf = state.rows("mess_members").associate { it.id() to it.str("name") }
    deposits.sortedByDescending { dateOf(it, "deposit_date") }.forEach { d ->
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${nameOf[d.str("member_id")].orEmpty()} · ${tr("ক্যাশ জমা", "Cash")}", fontWeight = FontWeight.Bold)
                    Text(
                        listOf(displayDate(dateOf(d, "deposit_date")), d.str("note")).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(taka(d.num("amount"), p), fontWeight = FontWeight.Bold)
                if (!locked) DeleteButton { vm.remove("mess_deposits", d.id()) }
            }
        }
    }
}

@Composable
private fun MessCostTab(vm: AppViewModel, fixed: List<JsonObject>, p: Boolean, locked: Boolean, onAdd: () -> Unit) {
    Button(
        onClick = onAdd,
        enabled = !locked,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Sky500, contentColor = Color.White)
    ) { Text(tr("+ ফিক্সড খরচ", "+ Fixed cost"), fontWeight = FontWeight.Bold) }

    SectionTitle(tr("এই মাসের ফিক্সড খরচ", "This month's fixed costs"))
    if (fixed.isEmpty()) EmptyHint(tr("এই মাসে কোনো ফিক্সড খরচ নেই", "No fixed costs this month"))
    fixed.sortedByDescending { dateOf(it, "cost_date") }.forEach { f ->
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.str("name"), fontWeight = FontWeight.Bold)
                    Text(
                        listOf(
                            displayDate(dateOf(f, "cost_date")),
                            if (f.str("split_mode") == "days") tr("দিন-অনুপাতে", "By days") else tr("সমান ভাগ", "Equal split")
                        ).joinToString(" · "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(taka(f.num("amount"), p), fontWeight = FontWeight.Bold)
                if (!locked) DeleteButton { vm.remove("mess_fixed_costs", f.id()) }
            }
        }
    }
}

// ======================= Summary =======================

@Composable
private fun MessSummaryTab(
    vm: AppViewModel,
    members: List<JsonObject>,
    calc: MessCalc,
    payouts: List<Payout>,
    nameOf: Map<String, String>,
    maxAbs: Double,
    prevDepBy: Map<String, Double>,
    p: Boolean,
    locked: Boolean,
    hasMembers: Boolean,
    onEdit: (JsonObject) -> Unit,
    onStatement: (JsonObject) -> Unit,
    onClose: () -> Unit,
    onReopen: () -> Unit
) {
    // Ring: share of the month's cost (bazaar + fixed) already covered by deposits.
    val cost = calc.totalBazaar + calc.totalFixed
    val depSum = calc.depositBy.values.sum()
    val fraction = if (cost > 0) (depSum / cost).coerceIn(0.0, 1.0) else 0.0
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AnimatedRing(fraction = fraction.toFloat(), color = Emerald500, size = 124.dp, stroke = 10.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(taka(calc.rate, p), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(tr("মিল-রেট", "Meal rate"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPill(tr("মোট বাজার", "Bazaar"), taka(calc.totalBazaar, p), Emerald500, Modifier.weight(1f))
        StatPill(tr("মোট মিল", "Meals"), fmtNum(calc.totalMeals), Sky400, Modifier.weight(1f))
        StatPill(tr("ফিক্সড", "Fixed"), taka(calc.totalFixed, p), Amber500, Modifier.weight(1f))
    }

    SectionTitle(tr("সদস্য ও হিসাব", "Members & balances"))
    if (!hasMembers) EmptyHint(tr("কোনো সদস্য নেই। নিচে '+' দিয়ে যোগ করুন।", "No members yet. Add one with '+' below."))
    members.forEach { m ->
        val id = m.id()
        val bal = calc.balances[id] ?: 0.0
        val now = calc.depositBy[id] ?: 0.0
        val prev = prevDepBy[id] ?: 0.0
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AvatarCircle(name = m.str("name"), size = 32.dp)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.str("name"), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (m.bool("is_manager")) {
                            Spacer(Modifier.width(6.dp))
                            StatusChip(tr("ম্যানেজার", "Manager"), Sky400)
                        }
                        if (!messActive(m)) {
                            Spacer(Modifier.width(6.dp))
                            StatusChip(tr("নিষ্ক্রিয়", "Inactive"), Rose500)
                        }
                        if (prev > 0.0 && now < prev) {
                            Spacer(Modifier.width(6.dp))
                            StatusChip(tr("জমা কমেছে", "Deposit dropped"), Amber500)
                        }
                    }
                    Text(
                        "${tr("মিল", "Meals")} ${fmtNum(calc.mealCount[id] ?: 0.0)} · ${tr("ফিক্সড", "Fixed")} ${taka(calc.fixedShare[id] ?: 0.0, p)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(modifier = Modifier.pressScale(), onClick = { onStatement(m) }) {
                    Icon(Icons.Default.Share, contentDescription = tr("স্টেটমেন্ট শেয়ার", "Share statement"))
                }
                IconButton(modifier = Modifier.pressScale(), onClick = { onEdit(m) }) {
                    Icon(Icons.Default.Edit, contentDescription = tr("এডিট", "Edit"))
                }
            }
            if (!p) DivergingBar(value = bal, maxAbs = if (maxAbs > 0.0) maxAbs else 1.0)
            when {
                bal > 0.005 -> KeyValue(tr("পাবেন", "Gets back"), taka(bal, p), Emerald500)
                bal < -0.005 -> KeyValue(tr("দেবেন", "Owes"), taka(-bal, p), Rose500)
                else -> KeyValue(tr("হিসাব", "Balance"), tr("মিটে গেছে", "Settled"))
            }
        }
    }

    SectionTitle(tr("হিসাব নিষ্পত্তি (সেটল-আপ)", "Settle up"))
    AppCard {
        when {
            !hasMembers -> Text(tr("সদস্য যোগ করলে এখানে কে কাকে কত দেবে তা দেখাবে।", "Add members to see who pays whom."), fontSize = 13.sp)
            payouts.isEmpty() -> Text(tr("এই মাসে কারও কাছে কিছু পাওনা বা দেনা নেই।", "Nobody owes or is owed anything this month."), fontSize = 13.sp)
            else -> payouts.forEach {
                Text(
                    if (it.from == MESS_FUND) {
                        tr("${nameOf[it.to].orEmpty()}-কে ফান্ড থেকে: ${taka(it.amount, p)}", "Fund pays ${nameOf[it.to].orEmpty()}: ${taka(it.amount, p)}")
                    } else {
                        tr("${nameOf[it.from].orEmpty()} দেবেন ${nameOf[it.to].orEmpty()}-কে: ${taka(it.amount, p)}", "${nameOf[it.from].orEmpty()} owes ${nameOf[it.to].orEmpty()}: ${taka(it.amount, p)}")
                    },
                    fontSize = 13.sp
                )
            }
        }
    }

    SectionTitle(tr("মাস বন্ধ", "Month close"))
    AppCard {
        if (locked) {
            KeyValue(tr("অবস্থা", "Status"), tr("বন্ধ", "Closed"))
            OutlinedButton(onClick = onReopen, modifier = Modifier.fillMaxWidth()) { Text(tr("মাস খুলুন", "Reopen month")) }
        } else {
            Button(
                onClick = onClose,
                enabled = hasMembers,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Sky500, contentColor = Color.White)
            ) { Text(tr("মাস বন্ধ করুন", "Close month"), fontWeight = FontWeight.Bold) }
        }
    }
}

// ======================= Dialogs =======================

@Composable
private fun MessMemberDialog(existing: JsonObject?, vm: AppViewModel, onDone: () -> Unit) {
    val key = existing?.id()
    var name by remember(key) { mutableStateOf(existing?.str("name").orEmpty()) }
    var phone by remember(key) { mutableStateOf(existing?.str("phone").orEmpty()) }
    var joined by remember(key) { mutableStateOf(existing?.str("joined_on")?.take(10).orEmpty()) }
    var left by remember(key) { mutableStateOf(existing?.str("left_on")?.take(10).orEmpty()) }
    var manager by remember(key) { mutableStateOf(existing?.bool("is_manager") == true) }
    var activeNow by remember(key) { mutableStateOf(existing?.let { messActive(it) } ?: true) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    SharedDialog(
        if (existing == null) tr("নতুন সদস্য", "New member") else tr("সদস্য এডিট", "Edit member"),
        tr("সেভ", "Save"),
        onDismiss = onDone,
        onConfirm = {
            val datesOk = (joined.isBlank() || isValidIsoDate(joined)) && (left.isBlank() || isValidIsoDate(left))
            when {
                name.isBlank() -> error = tr("নাম লিখুন", "Enter a name")
                !datesOk -> error = tr("তারিখ ঠিক নেই", "Invalid date")
                else -> {
                    val joinedValue: JsonElement = if (joined.isBlank()) JsonNull else JsonPrimitive(joined.trim())
                    val leftValue: JsonElement = when {
                        activeNow -> JsonNull
                        left.isBlank() -> JsonPrimitive(todayIso())
                        else -> JsonPrimitive(left.trim())
                    }
                    val body = JsonObject(
                        mapOf<String, JsonElement>(
                            "name" to JsonPrimitive(name.trim()),
                            "phone" to JsonPrimitive(phone.trim()),
                            "is_manager" to JsonPrimitive(manager),
                            "is_active" to JsonPrimitive(activeNow),
                            "joined_on" to joinedValue,
                            "left_on" to leftValue
                        )
                    )
                    vm.save("mess_members", body, existing?.id())
                    onDone()
                }
            }
        }
    ) {
        TextIn(tr("নাম", "Name"), name, { name = it })
        TextIn(tr("ফোন (ঐচ্ছিক)", "Phone (optional)"), phone, { phone = it })
        DateField(tr("যোগদানের তারিখ", "Joined on"), joined, { joined = it }, optional = true)
        DateField(tr("ছাড়ার তারিখ", "Left on"), left, { left = it }, optional = true)
        ChoiceChips(
            listOf("on" to tr("সক্রিয়", "Active"), "off" to tr("নিষ্ক্রিয়", "Inactive")),
            if (activeNow) "on" else "off"
        ) { activeNow = it == "on" }
        ChoiceChips(
            listOf("m" to tr("সদস্য", "Member"), "mgr" to tr("ম্যানেজার", "Manager")),
            if (manager) "mgr" else "m"
        ) { manager = it == "mgr" }
        error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
    }
}

@Composable
private fun MessAddBazaarDialog(members: List<JsonObject>, vm: AppViewModel, onDone: () -> Unit) {
    var member by remember { mutableStateOf(members.firstOrNull()?.id().orEmpty()) }
    var amount by remember { mutableStateOf("") }
    var item by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayIso()) }
    var error by remember { mutableStateOf<String?>(null) }
    SharedDialog(tr("বাজার এন্ট্রি", "Bazaar entry"), tr("সেভ", "Save"), onDismiss = onDone, onConfirm = {
        val a = parseAmount(amount)
        when {
            a == null || a <= 0 -> error = tr("সঠিক টাকার পরিমাণ লিখুন", "Enter a valid amount")
            !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
            else -> {
                vm.save("mess_bazaars", row("member_id" to member, "amount" to a, "item" to item.trim(), "bazaar_date" to date.trim()), null)
                onDone()
            }
        }
    }) {
        ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
        TextIn(tr("টাকা (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
        TextIn(tr("কী কেনা হলো", "What was bought"), item, { item = it })
        DateField(tr("তারিখ", "Date"), date, { date = it })
        error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
    }
}

@Composable
private fun MessDepositDialog(members: List<JsonObject>, vm: AppViewModel, onDone: () -> Unit) {
    var member by remember { mutableStateOf(members.firstOrNull()?.id().orEmpty()) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayIso()) }
    var error by remember { mutableStateOf<String?>(null) }
    SharedDialog(tr("জমা এন্ট্রি (ক্যাশ)", "Deposit entry (cash)"), tr("সেভ", "Save"), onDismiss = onDone, onConfirm = {
        val a = parseAmount(amount)
        when {
            a == null || a <= 0 -> error = tr("সঠিক টাকার পরিমাণ লিখুন", "Enter a valid amount")
            !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
            else -> {
                vm.save("mess_deposits", row("member_id" to member, "amount" to a, "deposit_date" to date.trim(), "note" to note.trim()), null)
                onDone()
            }
        }
    }) {
        ChoiceChips(members.map { it.id() to it.str("name") }, member) { member = it }
        TextIn(tr("টাকা (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
        DateField(tr("তারিখ", "Date"), date, { date = it })
        TextIn(tr("নোট (ঐচ্ছিক)", "Note (optional)"), note, { note = it })
        error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
    }
}

@Composable
private fun MessFixedDialog(vm: AppViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayIso()) }
    var mode by remember { mutableStateOf("equal") }
    var error by remember { mutableStateOf<String?>(null) }
    SharedDialog(tr("ফিক্সড খরচ", "Fixed cost"), tr("সেভ", "Save"), onDismiss = onDone, onConfirm = {
        val a = parseAmount(amount)
        when {
            name.isBlank() -> error = tr("নাম লিখুন", "Enter a name")
            a == null || a < 0 -> error = tr("সঠিক টাকার পরিমাণ লিখুন", "Enter a valid amount")
            !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
            else -> {
                vm.save("mess_fixed_costs", row("name" to name.trim(), "amount" to a, "cost_date" to date.trim(), "split_mode" to mode), null)
                onDone()
            }
        }
    }) {
        TextIn(tr("নাম (ভাড়া, গ্যাস…)", "Name (rent, gas…)"), name, { name = it })
        TextIn(tr("টাকা (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
        DateField(tr("তারিখ", "Date"), date, { date = it })
        ChoiceChips(
            listOf("equal" to tr("সমান ভাগ", "Equal split"), "days" to tr("দিন-অনুপাতে", "By days")),
            mode
        ) { mode = it }
        error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
    }
}

@Composable
private fun MessCloseDialog(prefix: String, calc: MessCalc, p: Boolean, vm: AppViewModel, onDone: () -> Unit) {
    SharedDialog(tr("মাস বন্ধ করবেন?", "Close this month?"), tr("বন্ধ করুন", "Close"), onDismiss = onDone, onConfirm = {
        val body = JsonObject(
            mapOf<String, JsonElement>(
                "month" to JsonPrimitive(prefix),
                "rate" to JsonPrimitive(round2(calc.rate)),
                "final_balances" to JsonObject(calc.balances.mapValues { JsonPrimitive(round2(it.value)) })
            )
        )
        vm.save("mess_months", body, null)
        onDone()
    }) {
        Text(
            "${tr("মোট বাজার", "Bazaar")}: ${taka(calc.totalBazaar, p)} · ${tr("মিল", "Meals")}: ${fmtNum(calc.totalMeals)}",
            fontSize = 13.sp
        )
    }
}
