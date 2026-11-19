package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

val EXPENSE_CATEGORIES = listOf(
    "Food" to "খাবার",
    "Travel" to "যাতায়াত",
    "Shopping" to "কেনাকাটা",
    "Bills" to "বিল",
    "Others" to "অন্যান্য"
)
val INCOME_CATEGORIES = listOf("Salary" to "বেতন", "Others" to "অন্যান্য")
val PAYMENT_MODES = listOf("Cash", "Bkash", "Nagad", "Rocket", "Bank", "Card").map { it to it }
val TX_TYPES = listOf("Expense" to "ব্যয়", "Income" to "আয়")

@Composable
fun TransactionsScreen(state: UiState, privacy: Boolean, onDelete: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    val list = state.transactions.filter { filter == "All" || it.type == filter }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "লেনদেন",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        ChoiceChips(
            options = listOf("All" to "সব", "Income" to "আয়", "Expense" to "ব্যয়"),
            selected = filter,
            onSelect = { filter = it }
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            if (list.isEmpty()) {
                item { EmptyHint("কোনো লেনদেন পাওয়া যায়নি") }
            }
            items(list, key = { it.id }) { tx ->
                TxItem(tx = tx, privacy = privacy, onDelete = { onDelete(tx.id) })
            }
        }
    }
}

@Composable
fun AddTransactionDialog(
    onDismiss: () -> Unit,
    onSave: (type: String, amount: Double, category: String, note: String, date: String, mode: String) -> Unit
) {
    var type by rememberSaveable { mutableStateOf("Expense") }
    var amount by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Food") }
    var note by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(todayIso()) }
    var mode by rememberSaveable { mutableStateOf("Cash") }

    val value = amount.toDoubleOrNull() ?: 0.0
    val valid = value > 0.0 && isValidIsoDate(date)
    val categories = if (type == "Income") INCOME_CATEGORIES else EXPENSE_CATEGORIES

    FormDialog(
        title = "নতুন লেনদেন",
        confirmEnabled = valid,
        onDismiss = onDismiss,
        onConfirm = {
            onSave(type, value, category, note.trim(), date.trim(), mode)
        }
    ) {
        ChoiceChips(TX_TYPES, type) {
            type = it
            category = if (it == "Income") "Salary" else "Food"
        }
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("পরিমাণ (৳)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        ChoiceChips(categories, category) { category = it }
        ChoiceChips(PAYMENT_MODES, mode) { mode = it }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("নোট") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            label = { Text("তারিখ (YYYY-MM-DD)") },
            singleLine = true,
            isError = !isValidIsoDate(date),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
