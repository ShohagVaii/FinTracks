package com.fintracks.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.data.strList
import com.fintracks.app.ui.design.DivergingBar
import com.fintracks.app.ui.design.FilterPill
import com.fintracks.app.ui.design.StatusChip
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

// ======================= Bill math =======================

/** Subtotal plus tax and tip. */
private fun billGrand(b: JsonObject): Double = b.num("total_amount") + b.num("tax") + b.num("tip")

/** Share before tax and tip, by split mode. Bills saved before the modes existed (equal / custom) still work. */
private fun billBaseShare(b: JsonObject, name: String): Double {
    val people = b.strList("participants")
    if (!people.contains(name)) return 0.0
    val total = b.num("total_amount")
    val shares = b["shares"] as? JsonObject
    return when (b.str("split_mode")) {
        "custom" -> shares?.num(name) ?: 0.0
        "percent" -> total * (shares?.num(name) ?: 0.0) / 100.0
        "shares" -> {
            val weights = people.sumOf { shares?.num(it) ?: 0.0 }
            if (weights <= 0.0) 0.0 else total * (shares?.num(name) ?: 0.0) / weights
        }
        else -> total / maxOf(people.size, 1)
    }
}

/** What one person owes: their base share plus tax and tip in the same proportion. */
private fun billShare(b: JsonObject, name: String): Double {
    val base = billBaseShare(b, name)
    val total = b.num("total_amount")
    return if (total <= 0.0) base else base + (b.num("tax") + b.num("tip")) * base / total
}

/** Who paid how much. Bills saved before payers existed fall back to the single payer. */
private fun billPaid(b: JsonObject): Map<String, Double> {
    val payers = b["payers"] as? JsonObject
    if (payers != null && payers.isNotEmpty()) return payers.keys.associateWith { payers.num(it) }
    val payer = b.str("payer").trim()
    return if (payer.isBlank()) emptyMap() else mapOf(payer to billGrand(b))
}

/** Net per person (positive = gets back). Each recorded payment moves the two people toward zero. */
private fun billNet(b: JsonObject, payments: List<JsonObject>): Map<String, Double> {
    val paid = billPaid(b)
    val names = (b.strList("participants") + paid.keys).filter { it.isNotBlank() }.distinct()
    val net = names.associateWith { n -> (paid[n] ?: 0.0) - billShare(b, n) }.toMutableMap()
    payments.forEach { s ->
        val amt = s.num("amount")
        val from = s.str("from_name")
        val to = s.str("to_name")
        net[from] = (net[from] ?: 0.0) + amt
        net[to] = (net[to] ?: 0.0) - amt
    }
    return net
}

private fun billSettled(net: Map<String, Double>): Boolean = net.values.all { abs(it) < 0.01 }

private fun billPayments(state: UiState, billId: String): List<JsonObject> =
    state.rows("bill_settlements").filter { it.str("bill_id") == billId }

private fun splitModeLabel(mode: String): String = when (mode) {
    "percent" -> tr("শতাংশ", "Percent")
    "shares" -> tr("ভাগ-অংশ", "Shares")
    "custom" -> tr("কাস্টম", "Custom")
    else -> tr("সমান ভাগ", "Equal")
}

private fun billSummaryText(b: JsonObject, net: Map<String, Double>, p: Boolean): String {
    val lines = mutableListOf(b.str("title") + " · " + taka(billGrand(b), p))
    b.strList("participants").forEach { lines += "$it: " + taka(billShare(b, it), p) }
    settleBalances(net).forEach { lines += "${it.from} → ${it.to}: " + taka(it.amount, p) }
    return lines.joinToString("\n")
}

internal fun shareText(ctx: Context, text: String) {
    val intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
        tr("শেয়ার", "Share")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}

// ======================= Bill splitter screen =======================

@Composable
internal fun BillSplitSection(state: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val bills = state.rows("bill_splits")
    val p = state.privacy

    val open = bills.filter { !billSettled(billNet(it, billPayments(state, it.id()))) }
    // Totals across all bills that are not settled yet.
    val overall = HashMap<String, Double>()
    open.forEach { b -> billNet(b, billPayments(state, b.id())).forEach { (k, v) -> overall[k] = (overall[k] ?: 0.0) + v } }
    val overallPayouts = settleBalances(overall)
    val maxAbs = overall.values.maxOfOrNull { abs(it) } ?: 0.0

    AddButton(tr("নতুন বিল", "New bill")) { adding = true }
    if (open.isNotEmpty()) {
        AppCard {
            Text(tr("অমীমাংসিত বিল: সব মিলিয়ে", "Open bills, total"), fontWeight = FontWeight.Bold)
            overall.forEach { (name, v) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(72.dp))
                    DivergingBar(v, maxAbs, Modifier.weight(1f))
                    Text(taka(v, p), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (overallPayouts.isNotEmpty()) PayoutDiagram(overallPayouts, p)
        }
    }
    if (bills.isEmpty()) EmptyHint(tr("কোনো বিল ভাগ নেই", "No bill splits yet"))
    bills.forEach { b -> BillCard(b, state, vm, p) }
    if (adding) NewBillDialog(state, vm) { adding = false }
}

/** Payments as a row per transfer, with a pulsing arrow (still when reduce-motion is on). */
@Composable
private fun PayoutDiagram(payouts: List<Payout>, p: Boolean) {
    val alpha = if (rememberReduceMotion()) 1f else pulseAlpha()
    payouts.forEachIndexed { i, pay ->
        Row(
            Modifier.fillMaxWidth().staggerIn(i),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(pay.from, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("→", fontSize = 16.sp, color = Sky500, modifier = Modifier.alpha(alpha))
            Text(pay.to, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(taka(pay.amount, p), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun pulseAlpha(): Float {
    val t = rememberInfiniteTransition(label = "arrow")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "arrowAlpha")
    return a
}

@Composable
private fun BillCard(b: JsonObject, state: UiState, vm: AppViewModel, p: Boolean) {
    val ctx = LocalContext.current
    val payments = billPayments(state, b.id())
    val net = billNet(b, payments)
    val settled = billSettled(net)
    var settling by remember { mutableStateOf(false) }
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.str("title"), fontWeight = FontWeight.Bold)
                Text(
                    displayDate(dateOf(b, "split_date")) + " · " + splitModeLabel(b.str("split_mode")),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(taka(billGrand(b), p), fontWeight = FontWeight.Bold)
                if (settled) StatusChip(tr("মিটেছে", "Settled"), Emerald500)
            }
        }
        b.strList("participants").forEach { n -> KeyValue(tr("$n-এর ভাগ", "$n's share"), taka(billShare(b, n), p)) }
        if (!settled) PayoutDiagram(settleBalances(net), p)
        payments.forEach { s ->
            Text(
                s.str("from_name") + " → " + s.str("to_name") + " · " + taka(s.num("amount"), p) + " · " + displayDate(dateOf(s, "paid_on")),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!settled) OutlinedButton(modifier = Modifier.pressScale(), onClick = { settling = true }) { Text(tr("পরিশোধ", "Payment")) }
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { shareText(ctx, billSummaryText(b, net, p)) }) { Text(tr("শেয়ার", "Share")) }
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { shareBillImage(ctx, b, net, p) }) { Text(tr("ছবি", "Image")) }
            DeleteButton { vm.remove("bill_splits", b.id()) }
        }
    }
    if (settling) SettleDialog(b, net, vm) { settling = false }
}

/** Records one partial payment between two people. Amount is capped at what the pair still owes. */
@Composable
private fun SettleDialog(b: JsonObject, net: Map<String, Double>, vm: AppViewModel, onDone: () -> Unit) {
    val owing = net.filter { it.value < -0.005 }.keys.toList()
    val owed = net.filter { it.value > 0.005 }.keys.toList()
    var from by remember { mutableStateOf(owing.firstOrNull() ?: "") }
    var to by remember { mutableStateOf(owed.firstOrNull() ?: "") }
    var amount by remember { mutableStateOf("") }
    val limit = minOf(-(net[from] ?: 0.0), net[to] ?: 0.0).coerceAtLeast(0.0)
    val amt = parseAmount(amount)
    val valid = from != to && amt != null && amt > 0 && amt <= limit + 0.01

    SharedDialog(tr("পরিশোধ নথিভুক্ত", "Record payment"), tr("সেভ", "Save"), onDismiss = onDone, onConfirm = {
        if (valid) {
            vm.save(
                "bill_settlements",
                row("bill_id" to b.id(), "from_name" to from, "to_name" to to, "amount" to (amt ?: 0.0), "paid_on" to todayIso()),
                null
            )
            onDone()
        }
    }) {
        Text(tr("কে দিচ্ছেন", "From"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceChips(owing.map { it to it }, from) { from = it }
        Text(tr("কাকে", "To"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceChips(owed.map { it to it }, to) { to = it }
        TextIn(tr("টাকা (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
        Text(tr("সর্বোচ্চ", "Up to") + ": " + taka(limit), fontSize = 12.sp)
    }
}

// ======================= New bill =======================

/** Share the bill summary as an image, reusing the statement image writer. */
private fun shareBillImage(ctx: Context, b: JsonObject, net: Map<String, Double>, p: Boolean) {
    val lines = billSummaryText(b, net, p).lines()
    shareFile(ctx, writeStatementImage(ctx, b.str("title"), lines), "image/png")
}

// ======================= New bill (3 steps) =======================

/** Blank = 0; otherwise the number, or null when it is not a number. */
private fun amountOrNull(s: String): Double? = if (s.isBlank()) 0.0 else parseAmount(s)

@Composable
private fun NewBillDialog(state: UiState, vm: AppViewModel, onDone: () -> Unit) {
    val billId = remember { java.util.UUID.randomUUID().toString() }
    var step by remember { mutableStateOf(0) }
    var title by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var tax by remember { mutableStateOf("") }
    var tip by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("equal") }
    var people by remember { mutableStateOf(listOf<String>()) }
    var extra by remember { mutableStateOf(listOf<String>()) }
    var newName by remember { mutableStateOf("") }
    var amounts by remember { mutableStateOf(mapOf<String, String>()) }
    var multiPayer by remember { mutableStateOf(false) }
    var payer by remember { mutableStateOf("") }
    var paidAmounts by remember { mutableStateOf(mapOf<String, String>()) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var groupName by remember { mutableStateOf("") }
    var addToTx by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val groups = state.rows("bill_groups")
    val known = (state.rows("mess_members").map { it.str("name") } + state.rows("bill_splits").flatMap { it.strList("participants") } + extra)
        .filter { it.isNotBlank() }
        .distinct()

    val subtotal = parseAmount(total) ?: 0.0
    val taxN = amountOrNull(tax)
    val tipN = amountOrNull(tip)
    val grand = subtotal + (taxN ?: 0.0) + (tipN ?: 0.0)
    val entered = people.associateWith { parseAmount(amounts[it] ?: "") }
    val enteredSum = people.sumOf { entered[it] ?: 0.0 }
    val payersMap: Map<String, Double> = if (multiPayer) {
        people.associateWith { amountOrNull(paidAmounts[it] ?: "") ?: 0.0 }.filterValues { it > 0 }
    } else if (payer.isBlank()) {
        emptyMap()
    } else {
        mapOf(payer to grand)
    }
    val sharesMap = JsonObject(if (mode == "equal") emptyMap() else people.associateWith { JsonPrimitive(entered[it] ?: 0.0) })

    // Draft bill: the same JSON that gets saved, so the preview uses exactly the saved math.
    val draft = JsonObject(
        mapOf(
            "total_amount" to JsonPrimitive(subtotal),
            "tax" to JsonPrimitive(taxN ?: 0.0),
            "tip" to JsonPrimitive(tipN ?: 0.0),
            "participants" to JsonArray(people.map { JsonPrimitive(it) }),
            "split_mode" to JsonPrimitive(mode),
            "shares" to sharesMap,
            "payers" to JsonObject(payersMap.mapValues { JsonPrimitive(it.value) })
        )
    )
    val sharesOk = when (mode) {
        "percent" -> people.isNotEmpty() && people.all { (entered[it] ?: -1.0) >= 0 } && abs(enteredSum - 100) < 0.01
        "shares" -> people.isNotEmpty() && people.all { (entered[it] ?: 0.0) > 0 }
        "custom" -> people.isNotEmpty() && people.all { entered[it] != null } && abs(enteredSum - subtotal) < 0.01
        else -> people.isNotEmpty()
    }
    val payersOk = payersMap.isNotEmpty() && abs(payersMap.values.sum() - grand) < 0.01 && (multiPayer || payer in people)
    val leftForSplit = when (mode) {
        "percent" -> 100 - enteredSum
        "custom" -> subtotal - enteredSum
        else -> 0.0
    }

    SharedDialog(
        tr("নতুন বিল ভাগ", "New bill split"),
        if (step < 2) tr("পরবর্তী", "Next") else tr("সেভ", "Save"),
        onDismiss = onDone,
        onConfirm = {
            error = null
            when (step) {
                0 -> when {
                    title.isBlank() -> error = tr("বিলের নাম লিখুন", "Enter the bill name")
                    subtotal <= 0 -> error = tr("সঠিক মোট টাকা লিখুন", "Enter a valid total")
                    taxN == null || tipN == null -> error = tr("ট্যাক্স/টিপ সঠিক লিখুন", "Enter a valid tax or tip")
                    else -> step = 1
                }
                1 -> when {
                    people.isEmpty() -> error = tr("অন্তত একজন অংশগ্রহণকারী বাছাই করুন", "Pick at least one participant")
                    !payersOk -> error = tr("পরিশোধের যোগফল মোট টাকার সমান হতে হবে", "Payments must add up to the total")
                    else -> step = 2
                }
                else -> when {
                    !sharesOk -> error = when (mode) {
                        "percent" -> tr("শতাংশের যোগফল ১০০ হতে হবে", "Percentages must add up to 100")
                        "shares" -> tr("প্রত্যেকের ভাগ-অংশ লিখুন", "Enter each person's shares")
                        else -> tr("ভাগের যোগফল মোট টাকার সমান হতে হবে", "Shares must add up to the total")
                    }
                    else -> {
                        vm.save(
                            "bill_splits",
                            row(
                                "id" to billId,
                                "title" to title.trim(),
                                "total_amount" to subtotal,
                                "tax" to (taxN ?: 0.0),
                                "tip" to (tipN ?: 0.0),
                                "payer" to (payersMap.maxByOrNull { it.value }?.key ?: ""),
                                "payers" to JsonObject(payersMap.mapValues { JsonPrimitive(it.value) }),
                                "participants" to JsonArray(people.map { JsonPrimitive(it) }),
                                "split_mode" to mode,
                                "shares" to sharesMap,
                                "group_id" to groupId,
                                "split_date" to todayIso()
                            ),
                            null
                        )
                        if (addToTx && people.contains("আমি")) {
                            vm.save(
                                "transactions",
                                row(
                                    "type" to "Expense", "amount" to billShare(draft, "আমি"), "currency" to state.currency,
                                    "category" to "অন্যান্য", "note" to title.trim(), "tx_date" to todayIso(),
                                    "payment_mode" to "Cash", "mood" to "", "is_recurring" to false,
                                    "source" to "bill_split", "source_ref" to billId
                                ),
                                null
                            )
                        }
                        onDone()
                    }
                }
            }
        }
    ) {
        if (step > 0) {
            TextButton(modifier = Modifier.pressScale(), onClick = { step -= 1; error = null }) { Text(tr("আগে", "Back")) }
        }
        when (step) {
            0 -> {
                TextIn(tr("বিলের নাম (যেমন: বাসা ভাড়া, খাবার)", "Bill name (e.g. rent, food)"), title, { title = it })
                TextIn(tr("মোট (৳)", "Total (৳)"), total, { total = it }, number = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextIn(tr("ট্যাক্স (৳)", "Tax (৳)"), tax, { tax = it }, number = true, modifier = Modifier.weight(1f))
                    TextIn(tr("টিপ (৳)", "Tip (৳)"), tip, { tip = it }, number = true, modifier = Modifier.weight(1f))
                }
            }
            1 -> {
                if (groups.isNotEmpty()) {
                    Text(tr("গ্রুপ", "Group"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceChips(groups.map { it.id() to it.str("name") }, groupId ?: "") { id ->
                        groupId = id
                        people = groups.first { it.id() == id }.strList("members")
                        if (payer !in people) payer = ""
                    }
                }
                Text(tr("অংশগ্রহণকারী", "Participants"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (known.isNotEmpty()) {
                    ToggleChips(known, people) { n ->
                        people = if (n in people) people - n else people + n
                        if (payer !in people) payer = ""
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextIn(tr("নতুন নাম", "New name"), newName, { newName = it }, modifier = Modifier.weight(1f))
                    OutlinedButton(modifier = Modifier.pressScale(), onClick = {
                        val n = newName.trim()
                        if (n.isNotEmpty()) {
                            extra = (extra + n).distinct()
                            if (n !in people) people = people + n
                            newName = ""
                        }
                    }) { Text(tr("যোগ", "Add")) }
                }
                if (people.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextIn(tr("গ্রুপের নাম", "Group name"), groupName, { groupName = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(modifier = Modifier.pressScale(), onClick = {
                            val n = groupName.trim()
                            if (n.isNotEmpty()) {
                                vm.save("bill_groups", row("name" to n, "members" to JsonArray(people.map { JsonPrimitive(it) })), null)
                                groupName = ""
                            }
                        }) { Text(tr("গ্রুপ সেভ", "Save group")) }
                    }
                    Text(tr("কে পরিশোধ করেছেন", "Paid by"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceChips(
                        listOf("one" to tr("একজন", "One person"), "many" to tr("একাধিক", "Several")),
                        if (multiPayer) "many" else "one"
                    ) { multiPayer = it == "many" }
                    if (multiPayer) {
                        people.forEach { n ->
                            TextIn(tr("$n পরিশোধ (৳)", "$n paid (৳)"), paidAmounts[n] ?: "", { v -> paidAmounts = paidAmounts + (n to v) }, number = true)
                        }
                    } else {
                        ChoiceChips(people.map { it to it }, payer) { payer = it }
                    }
                }
            }
            else -> {
                Text(tr("ভাগের ধরন", "Split type"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceChips(
                    listOf("equal" to tr("সমান", "Equal"), "percent" to tr("শতাংশ", "Percent"), "shares" to tr("ভাগ-অংশ", "Shares"), "custom" to tr("কাস্টম", "Custom")),
                    mode
                ) { mode = it }
                if (mode != "equal") {
                    people.forEach { n ->
                        TextIn(
                            when (mode) {
                                "percent" -> "$n (%)"
                                "shares" -> tr("$n-এর ভাগ-অংশ", "$n's shares")
                                else -> tr("$n-এর ভাগ (৳)", "$n's share (৳)")
                            },
                            amounts[n] ?: "",
                            { v -> amounts = amounts + (n to v) },
                            number = true
                        )
                    }
                }
                if (people.isNotEmpty() && subtotal > 0) {
                    people.forEach { n -> KeyValue(n, taka(billShare(draft, n))) }
                    KeyValue(tr("মোট সহ", "Grand total"), taka(grand))
                }
                if (people.contains("আমি")) {
                    FilterPill(tr("আমার ভাগ আমার লেনদেনে যোগ", "Add my share to my transactions"), addToTx) { addToTx = !addToTx }
                }
                // Sticky-style footer: what is still left to assign, with a tick when it balances.
                val balanced = mode == "equal" || abs(leftForSplit) < 0.01
                Text(
                    if (balanced) "✓" else tr("বাকি", "Left") + ": " + (if (mode == "percent") "${fmtNum(leftForSplit)}%" else taka(leftForSplit)),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (balanced) readableOn(Emerald500) else readableOn(Rose500)
                )
            }
        }
        error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
    }
}
