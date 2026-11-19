package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fintracks.app.data.SmsSuggestion
import com.fintracks.app.data.TxRow

@Composable
fun HomeScreen(
    state: UiState,
    privacy: Boolean,
    onAcceptSms: (SmsSuggestion) -> Unit,
    onDismissSms: (SmsSuggestion) -> Unit
) {
    val month = currentMonthPrefix()
    val monthTx = state.transactions.filter { it.txDate.startsWith(month) }
    val income = monthTx.filter { it.type == "Income" }.sumOf { it.amount }
    val expense = monthTx.filter { it.type == "Expense" }.sumOf { it.amount }
    val balance = state.transactions.sumOf {
        when (it.type) {
            "Income" -> it.amount
            "Expense" -> -it.amount
            else -> 0.0
        }
    }
    val name = state.profile?.name?.takeIf { it.isNotBlank() } ?: "বন্ধু"

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "স্বাগতম, $name",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            AppCard {
                Text("মোট ব্যালেন্স", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    taka(balance, privacy),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("এই মাসের আয়", taka(income, privacy), Emerald500, Modifier.weight(1f))
                StatCard("এই মাসের ব্যয়", taka(expense, privacy), Rose500, Modifier.weight(1f))
            }
        }

        if (state.pendingSms.isNotEmpty()) {
            item {
                Text("SMS থেকে সাজেশন", fontWeight = FontWeight.Bold)
            }
            items(state.pendingSms, key = { it.id }) { s ->
                SmsSuggestionCard(
                    s = s,
                    privacy = privacy,
                    onAccept = { onAcceptSms(s) },
                    onDismiss = { onDismissSms(s) }
                )
            }
        }

        item {
            Text("সাম্প্রতিক লেনদেন", fontWeight = FontWeight.Bold)
        }
        if (state.transactions.isEmpty()) {
            item { EmptyHint("এখনো কোনো লেনদেন নেই। নিচের ➕ বাটনে চাপুন।") }
        } else {
            items(state.transactions.take(5), key = { it.id }) { tx ->
                TxItem(tx = tx, privacy = privacy)
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    AppCard {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TxItem(tx: TxRow, privacy: Boolean, onDelete: (() -> Unit)? = null) {
    val isIncome = tx.type == "Income"
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tx.note.ifBlank { tx.category },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${tx.category} · ${tx.paymentMode} · ${tx.txDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                (if (isIncome) "+" else "-") + taka(tx.amount, privacy),
                color = if (isIncome) Emerald500 else Rose500,
                fontWeight = FontWeight.Bold
            )
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("মুছুন", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun SmsSuggestionCard(s: SmsSuggestion, privacy: Boolean, onAccept: () -> Unit, onDismiss: () -> Unit) {
    AppCard {
        Text(
            "${s.provider} · ${if (s.type == "Income") "আয়" else "ব্যয়"} ${taka(s.amount, privacy)}",
            fontWeight = FontWeight.Bold
        )
        Text(
            s.raw,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAccept) { Text("যোগ করুন") }
            TextButton(onClick = onDismiss) { Text("বাদ দিন") }
        }
    }
}
