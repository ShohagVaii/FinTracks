package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fintracks.app.data.EntryRow
import com.fintracks.app.data.PaymentRow
import com.fintracks.app.data.ShopRow

/** বাকি স্ট্যাটাস: পরিশোধিত / মেয়াদোত্তীর্ণ / বাকি আছে। */
enum class CreditStatus(val label: String, val color: Color) {
    PAID("পরিশোধিত", Emerald500),
    OVERDUE("মেয়াদোত্তীর্ণ", Rose500),
    DUE("বাকি আছে", Amber500)
}

data class ShopSummary(
    val shop: ShopRow,
    val total: Double,
    val paid: Double,
    val balance: Double,
    val status: CreditStatus,
    val entries: List<EntryRow>,
    val payments: List<PaymentRow>
)

/** Builds per-shop totals from the raw rows. Balance = all items bought - all payments. */
fun summarizeShops(state: UiState): List<ShopSummary> = state.shops.map { shop ->
    val entries = state.entries.filter { it.shopId == shop.id }
    val payments = state.payments.filter { it.shopId == shop.id }
    val total = entries.sumOf { it.total }
    val paid = payments.sumOf { it.amount }
    val balance = total - paid
    val today = todayIso()
    val status = when {
        balance <= 0.0 -> CreditStatus.PAID
        entries.any { it.dueDate != null && it.dueDate < today } -> CreditStatus.OVERDUE
        else -> CreditStatus.DUE
    }
    ShopSummary(shop, total, paid, balance, status, entries, payments)
}

@Composable
fun CreditScreen(state: UiState, privacy: Boolean, onAddShop: (String, String, String) -> Unit,
                 onDeleteShop: (String) -> Unit, onAddEntry: (String, String, Double, Double, String, String?, String) -> Unit,
                 onDeleteEntry: (String) -> Unit, onAddPayment: (String, Double, String, String) -> Unit,
                 onDeletePayment: (String) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val summaries = summarizeShops(state)
    val selected = summaries.firstOrNull { it.shop.id == selectedId }

    if (selected == null) {
        CreditList(
            summaries = summaries,
            privacy = privacy,
            onOpen = { selectedId = it },
            onAddShop = onAddShop
        )
    } else {
        CreditDetail(
            ss = selected,
            privacy = privacy,
            onBack = { selectedId = null },
            onDeleteShop = { onDeleteShop(selected.shop.id); selectedId = null },
            onAddEntry = onAddEntry,
            onDeleteEntry = onDeleteEntry,
            onAddPayment = onAddPayment,
            onDeletePayment = onDeletePayment
        )
    }
}

@Composable
private fun CreditList(
    summaries: List<ShopSummary>,
    privacy: Boolean,
    onOpen: (String) -> Unit,
    onAddShop: (String, String, String) -> Unit
) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val totalDue = summaries.filter { it.balance > 0 }.sumOf { it.balance }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "বাকি",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
            )
            Button(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("দোকান")
            }
        }
        AppCard {
            Text("মোট বাকি", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                taka(totalDue, privacy),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = Rose500
            )
        }
        Spacer(Modifier.height(12.dp))
        if (summaries.isEmpty()) {
            EmptyHint("কোনো দোকান যোগ করা হয়নি। দোকান যোগ করে বাকির হিসাব রাখুন।")
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            items(summaries, key = { it.shop.id }) { ss ->
                AppCard(onClick = { onOpen(ss.shop.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ss.shop.name,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(ss.status.label, color = ss.status.color, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "বাকি: ${taka(ss.balance, privacy)}",
                        fontWeight = FontWeight.Bold,
                        color = if (ss.balance > 0) Rose500 else Emerald500
                    )
                    Text(
                        "মোট কেনা ${taka(ss.total, privacy)} · পরিশোধ ${taka(ss.paid, privacy)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddShopDialog(
            onDismiss = { showAdd = false },
            onSave = { name, phone, note ->
                onAddShop(name, phone, note)
                showAdd = false
            }
        )
    }
}

@Composable
private fun CreditDetail(
    ss: ShopSummary,
    privacy: Boolean,
    onBack: () -> Unit,
    onDeleteShop: () -> Unit,
    onAddEntry: (String, String, Double, Double, String, String?, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onAddPayment: (String, Double, String, String) -> Unit,
    onDeletePayment: (String) -> Unit
) {
    var showEntry by rememberSaveable { mutableStateOf(false) }
    var showPayment by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ফিরে যান")
            }
            Column(Modifier.weight(1f)) {
                Text(ss.shop.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (ss.shop.phone.isNotBlank()) {
                    Text(ss.shop.phone, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDeleteShop) {
                Icon(Icons.Default.Delete, contentDescription = "দোকান মুছুন")
            }
        }

        AppCard {
            Row {
                Text("মোট কেনা: ${taka(ss.total, privacy)}", modifier = Modifier.weight(1f))
                Text("পরিশোধ: ${taka(ss.paid, privacy)}")
            }
            Text(
                "বাকি: ${taka(ss.balance, privacy)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = ss.status.color
            )
            Text(ss.status.label, color = ss.status.color, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showEntry = true }, modifier = Modifier.weight(1f)) { Text("আইটেম যোগ") }
            OutlinedButton(onClick = { showPayment = true }, modifier = Modifier.weight(1f)) { Text("পরিশোধ") }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { Text("কেনা আইটেম", fontWeight = FontWeight.Bold) }
            if (ss.entries.isEmpty()) item { EmptyHint("এখনো কোনো আইটেম নেই") }
            items(ss.entries.sortedByDescending { it.purchaseDate }, key = { it.id }) { e ->
                EntryItem(e, privacy, onDelete = { onDeleteEntry(e.id) })
            }
            item { Text("পরিশোধের ইতিহাস", fontWeight = FontWeight.Bold) }
            if (ss.payments.isEmpty()) item { EmptyHint("এখনো কোনো পরিশোধ নেই") }
            items(ss.payments.sortedByDescending { it.paidOn }, key = { it.id }) { p ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("পরিশোধ · ${p.paidOn}", fontWeight = FontWeight.SemiBold)
                            if (p.note.isNotBlank()) {
                                Text(p.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(taka(p.amount, privacy), color = Emerald500, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { onDeletePayment(p.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "মুছুন")
                        }
                    }
                }
            }
        }
    }

    if (showEntry) {
        AddEntryDialog(
            onDismiss = { showEntry = false },
            onSave = { item, qty, price, date, due, note ->
                onAddEntry(ss.shop.id, item, qty, price, date, due, note)
                showEntry = false
            }
        )
    }
    if (showPayment) {
        AddPaymentDialog(
            balance = ss.balance,
            privacy = privacy,
            onDismiss = { showPayment = false },
            onSave = { amount, date, note ->
                onAddPayment(ss.shop.id, amount, date, note)
                showPayment = false
            }
        )
    }
}

@Composable
private fun EntryItem(e: EntryRow, privacy: Boolean, onDelete: () -> Unit) {
    val today = todayIso()
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.itemName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${num(e.quantity)} × ${taka(e.unitPrice, privacy)} = ${taka(e.total, privacy)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "কেনা: ${e.purchaseDate}" + (e.dueDate?.let { " · শেষ তারিখ: $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e.dueDate != null && e.dueDate < today) Rose500 else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (e.note.isNotBlank()) {
                    Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "মুছুন")
            }
        }
    }
}

@Composable
private fun AddShopDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    FormDialog(
        title = "নতুন দোকান",
        confirmEnabled = name.isNotBlank(),
        onDismiss = onDismiss,
        onConfirm = { onSave(name.trim(), phone.trim(), note.trim()) }
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("দোকানের নাম") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("ফোন (ঐচ্ছিক)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("নোট (ঐচ্ছিক)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AddEntryDialog(
    onDismiss: () -> Unit,
    onSave: (item: String, qty: Double, price: Double, date: String, due: String?, note: String) -> Unit
) {
    var item by rememberSaveable { mutableStateOf("") }
    var qty by rememberSaveable { mutableStateOf("1") }
    var price by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(todayIso()) }
    var due by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }

    val qtyValue = qty.toDoubleOrNull() ?: 0.0
    val priceValue = price.toDoubleOrNull() ?: -1.0
    val dueOk = due.isBlank() || isValidIsoDate(due)
    val valid = item.isNotBlank() && qtyValue > 0 && priceValue >= 0 && isValidIsoDate(date) && dueOk

    FormDialog(
        title = "নতুন আইটেম (বাকিতে কেনা)",
        confirmEnabled = valid,
        onDismiss = onDismiss,
        onConfirm = {
            onSave(item.trim(), qtyValue, priceValue, date.trim(), due.trim().ifBlank { null }, note.trim())
        }
    ) {
        OutlinedTextField(item, { item = it }, label = { Text("আইটেম (যেমন: মালদা)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(qty, { qty = it }, label = { Text("পরিমাণ") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            OutlinedTextField(price, { price = it }, label = { Text("দর (৳)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        }
        Text(
            "মোট: ${taka(qtyValue * maxOf(priceValue, 0.0))}",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        OutlinedTextField(date, { date = it }, label = { Text("কেনার তারিখ (YYYY-MM-DD)") }, singleLine = true,
            isError = !isValidIsoDate(date), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(due, { due = it }, label = { Text("পরিশোধের শেষ তারিখ (ঐচ্ছিক)") }, singleLine = true,
            isError = !dueOk, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("নোট (ঐচ্ছিক)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AddPaymentDialog(
    balance: Double,
    privacy: Boolean,
    onDismiss: () -> Unit,
    onSave: (amount: Double, date: String, note: String) -> Unit
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(todayIso()) }
    var note by rememberSaveable { mutableStateOf("") }
    val value = amount.toDoubleOrNull() ?: 0.0

    FormDialog(
        title = "বাকি পরিশোধ",
        confirmEnabled = value > 0 && isValidIsoDate(date),
        onDismiss = onDismiss,
        onConfirm = { onSave(value, date.trim(), note.trim()) }
    ) {
        Text("বর্তমান বাকি: ${taka(balance, privacy)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(amount, { amount = it }, label = { Text("পরিশোধের পরিমাণ (৳)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(date, { date = it }, label = { Text("তারিখ (YYYY-MM-DD)") }, singleLine = true,
            isError = !isValidIsoDate(date), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("নোট (ঐচ্ছিক)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}
