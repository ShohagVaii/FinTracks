package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import com.fintracks.app.ui.design.SegmentedTabs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.data.strList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.YearMonth
import androidx.compose.foundation.layout.Box

/** Shared tab: bill splitter, mess manager, and shopping list. Money math lives in the functions below, not in the screens. */
@Composable
fun SharedScreen(state: UiState, vm: AppViewModel) {
    var section by remember { mutableStateOf("bill") }
    Column(Modifier.fillMaxSize()) {
        val accent by animateColorAsState(
            when (section) { "bill" -> Sky500; "mess" -> Emerald500; else -> Amber500 }, tween(300), label = "accent"
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedTabs(listOf("bill" to tr("বিল স্প্লিটার", "Bill splitter"), "mess" to tr("মেস ম্যানেজার", "Mess manager"), "shop" to tr("শপিং লিস্ট", "Shopping list")), selected = section, onSelect = { section = it })
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).background(accent, RoundedCornerShape(50)))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (section) {
                "bill" -> BillSplitSection(state, vm)
                "shop" -> ShoppingSection(state, vm)
                else -> MessManager(state, vm)
            }
            AppFooter()
            Spacer(Modifier.height(90.dp))
        }
    }
}

// ======================= Shared math =======================

/** One payment needed to settle: [from] pays [to] [amount]. Keys are names (bills) or member ids (mess). */
internal data class Payout(val from: String, val to: String, val amount: Double)

internal fun round2(v: Double): Double = Math.round(v * 100) / 100.0

/**
 * Smallest practical set of payments that brings every balance to zero.
 * Balances must sum to zero (they do for bills and mess); positive = is owed, negative = owes.
 */
internal fun settleBalances(balances: Map<String, Double>): List<Payout> {
    val debtors = balances.filter { it.value < -0.005 }.map { it.key to -it.value }.sortedByDescending { it.second }.toMutableList()
    val creditors = balances.filter { it.value > 0.005 }.map { it.key to it.value }.sortedByDescending { it.second }.toMutableList()
    val out = mutableListOf<Payout>()
    var i = 0
    var j = 0
    while (i < debtors.size && j < creditors.size) {
        val pay = minOf(debtors[i].second, creditors[j].second)
        out += Payout(debtors[i].first, creditors[j].first, round2(pay))
        debtors[i] = debtors[i].first to (debtors[i].second - pay)
        creditors[j] = creditors[j].first to (creditors[j].second - pay)
        if (debtors[i].second < 0.005) i++
        if (creditors[j].second < 0.005) j++
    }
    return out
}

// ======================= Shared UI pieces =======================

@Composable
internal fun MonthNav(ym: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(modifier = Modifier.pressScale(), onClick = { onChange(ym.minusMonths(1)) }) { Icon(Icons.Default.ChevronLeft, contentDescription = tr("আগের মাস", "Previous month")) }
        Text(monthYearLabel(ym.year, ym.monthValue), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        IconButton(modifier = Modifier.pressScale(), onClick = { onChange(ym.plusMonths(1)) }) { Icon(Icons.Default.ChevronRight, contentDescription = tr("পরের মাস", "Next month")) }
    }
}

/** Multi-select chips (participants). Wraps into rows of three. */
@Composable
internal fun ToggleChips(options: List<String>, selected: List<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chunk.forEach { o ->
                    FilterChip(
                        selected = o in selected,
                        onClick = { onToggle(o) },
                        label = { Text(o, fontSize = 12.sp, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Dialog with its own confirm label (FormDialog always says সেভ). */
@Composable
internal fun SharedDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        },
        confirmButton = { TextButton(modifier = Modifier.pressScale(), onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}
