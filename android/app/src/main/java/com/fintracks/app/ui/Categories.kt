package com.fintracks.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.id
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject

/** Emoji offered as quick picks for a category icon. The field also accepts any text. */
private val ICON_CHOICES = listOf("🍔", "🚗", "🛍️", "📜", "🏠", "💊", "🎓", "🎁", "✈️", "📱", "🐶", "💡", "🎮", "☕", "💼", "📦")

/** One category row: built-in or custom. [json] is set only for custom categories (they are rows in the synced table). */
private data class CatItem(val isDefault: Boolean, val key: String, val name: String, val icon: String, val json: JsonObject?, val count: Int)

private const val OTHER_NAME = "অন্যান্য"

/**
 * Categories page: expense/income categories and the payment methods. Built-in and custom categories can both be
 * edited and deleted. Deleting a category moves its transactions to "অন্যান্য"; deleting a payment method keeps its
 * name on old transactions.
 */
@Composable
fun CategoriesPage(state: UiState, vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var type by rememberSaveable { mutableStateOf("Expense") }
    var tick by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CatItem?>(null) }
    var deleting by remember { mutableStateOf<CatItem?>(null) }
    var payCreating by remember { mutableStateOf(false) }
    var payEditing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var payDeleting by remember { mutableStateOf<Pair<String, String>?>(null) }

    val txs = state.rows("transactions")
    val items = remember(state.tables, type, tick) {
        val defaults = CatStore.defaultRows(type).map { d ->
            CatItem(true, d.orig, d.name, d.icon, null, txs.count { t -> t.str("category") == d.name && t.str("type") == type })
        }
        val custom = state.rows("categories").filter { it.str("type") == type }.map { c ->
            val n = c.str("name")
            CatItem(false, n, n, c.str("icon").ifEmpty { "📦" }, c, txs.count { t -> t.str("category") == n && t.str("type") == type })
        }
        defaults + custom
    }
    val payments = remember(state.tables, tick) {
        CatStore.paymentMethods().map { (key, label) -> Triple(key, label, txs.count { it.str("payment_mode") == key }) }
    }

    Page {
        ChoiceChips(listOf("Expense" to tr("ব্যয়", "Expense"), "Income" to tr("আয়", "Income")), type) { type = it }

        AppCard {
            SectionTitle(tr("ক্যাটাগরি", "Categories"))
            items.forEach { item ->
                ManageRow(
                    icon = item.icon,
                    name = if (item.isDefault) catLabel(item.name) else item.name,
                    count = item.count,
                    tag = null,
                    onEdit = { editing = item },
                    onDelete = { deleting = item }
                )
            }
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { creating = true }) { Text(tr("+ নতুন ক্যাটাগরি", "+ New category")) }
        }

        AppCard {
            SectionTitle(tr("পেমেন্ট মেথড", "Payment methods"))
            payments.forEach { (key, label, count) ->
                ManageRow(
                    icon = null,
                    name = label,
                    count = count,
                    tag = if (key == "Cash") tr("ডিফল্ট", "Default") else null,
                    onEdit = { payEditing = key to label },
                    onDelete = { payDeleting = key to label }
                )
            }
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { payCreating = true }) { Text(tr("+ নতুন পেমেন্ট মেথড", "+ New payment method")) }
        }
    }

    // ---- category create / edit ----
    if (creating || editing != null) {
        val original = editing
        CategoryDialog(
            title = if (original == null) tr("নতুন ক্যাটাগরি", "New category") else tr("ক্যাটাগরি এডিট", "Edit category"),
            initialName = original?.name.orEmpty(),
            initialIcon = original?.icon ?: "📦",
            // A name is taken if a built-in or another category of the same type has it.
            takenNames = items.map { it.name }.toSet() - setOfNotNull(original?.name),
            onDismiss = { creating = false; editing = null },
            onSave = { name, icon ->
                if (original == null) {
                    vm.save("categories", com.fintracks.app.data.row("name" to name, "icon" to icon, "type" to type), null)
                } else if (original.isDefault) {
                    CatStore.overrideDefault(type, original.key, name, icon)
                    if (name != original.name) vm.batch(renameWrites(state, original.name, name, type))
                } else {
                    val writes = mutableListOf<Triple<String, JsonObject, String?>>(
                        Triple("categories", com.fintracks.app.data.row("name" to name, "icon" to icon), original.json?.id())
                    )
                    if (name != original.name) writes += renameWrites(state, original.name, name, type)
                    vm.batch(writes)
                }
                tick++
                creating = false
                editing = null
            }
        )
    }

    // ---- category delete (asks first, with the count) ----
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("ক্যাটাগরি মুছবেন?", "Delete category?"), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (c.count > 0) {
                        tr(
                            "\"${catLabel(c.name)}\" ক্যাটাগরিতে ${bnDigits(c.count.toString())}টি লেনদেন আছে। মুছলে সেগুলো \"অন্যান্য\"-তে যাবে।",
                            "\"${catLabel(c.name)}\" has ${c.count} transactions. They will move to \"Other\"."
                        )
                    } else {
                        tr("\"${catLabel(c.name)}\" ক্যাটাগরিটি মুছে ফেলা হবে।", "\"${catLabel(c.name)}\" will be deleted.")
                    }
                )
            },
            confirmButton = {
                TextButton(modifier = Modifier.pressScale(), onClick = {
                    val moves: List<Triple<String, JsonObject, String?>> =
                        if (c.name == OTHER_NAME) emptyList()
                        else state.rows("transactions")
                            .filter { it.str("category") == c.name && it.str("type") == type }
                            .map { Triple("transactions", com.fintracks.app.data.row("category" to OTHER_NAME), it.id()) }
                    if (c.isDefault) CatStore.hideDefault(type, c.key)
                    else c.json?.let { vm.remove("categories", it.id()) }
                    if (moves.isNotEmpty()) vm.batch(moves)
                    tick++
                    deleting = null
                }) { Text(tr("মুছুন", "Delete"), color = readableOn(Rose500)) }
            },
            dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = { deleting = null }) { Text(tr("বাতিল", "Cancel")) } }
        )
    }

    // ---- payment method create / edit / delete ----
    if (payCreating || payEditing != null) {
        val original = payEditing
        PaymentNameDialog(
            title = if (original == null) tr("নতুন পেমেন্ট মেথড", "New payment method") else tr("পেমেন্ট মেথড এডিট", "Edit payment method"),
            initial = original?.second.orEmpty(),
            taken = payments.map { it.second }.toSet() - setOfNotNull(original?.second),
            onDismiss = { payCreating = false; payEditing = null },
            onSave = { label ->
                val clean = CatStore.clean(label)
                CatStore.setPaymentMethods(
                    if (original == null) CatStore.paymentMethods() + (clean to clean)
                    else CatStore.paymentMethods().map { if (it.first == original.first) it.first to clean else it }
                )
                tick++
                payCreating = false
                payEditing = null
            }
        )
    }
    payDeleting?.let { (key, label) ->
        val n = payments.firstOrNull { it.first == key }?.third ?: 0
        AlertDialog(
            onDismissRequest = { payDeleting = null },
            title = { Text(tr("পেমেন্ট মেথড মুছবেন?", "Delete payment method?"), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (n > 0) tr("\"$label\" মেথডে ${bnDigits(n.toString())}টি লেনদেন আছে। পুরনো লেনদেনে এই নাম থেকে যাবে।", "\"$label\" has $n transactions. The name stays on them.")
                    else tr("\"$label\" মেথডটি মুছে ফেলা হবে।", "\"$label\" will be deleted.")
                )
            },
            confirmButton = {
                TextButton(modifier = Modifier.pressScale(), onClick = {
                    CatStore.setPaymentMethods(CatStore.paymentMethods().filter { it.first != key })
                    tick++
                    payDeleting = null
                }) { Text(tr("মুছুন", "Delete"), color = readableOn(Rose500)) }
            },
            dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = { payDeleting = null }) { Text(tr("বাতিল", "Cancel")) } }
        )
    }
}

/** Renames a category on existing transactions of the same type. */
private fun renameWrites(state: UiState, old: String, new: String, type: String): List<Triple<String, JsonObject, String?>> =
    state.rows("transactions")
        .filter { it.str("category") == old && it.str("type") == type }
        .map { Triple("transactions", com.fintracks.app.data.row("category" to new), it.id()) }

/** A list row: optional icon chip, name (with an optional tag), transaction count, then edit and delete. */
@Composable
private fun ManageRow(icon: String?, name: String, count: Int, tag: String?, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(faint(0.08f)),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 20.sp) }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (tag != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(tag, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                if (AppPrefs.english) "$count transactions" else bnDigits(count.toString()) + "টি লেনদেন",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        EditSquareButton(onEdit)
        IconButton(modifier = Modifier.pressScale(), onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = tr("মুছুন", "Delete"))
        }
    }
}

/** Edit button drawn as a square outline with a pencil inside, the same mark as the profile edit button. */
@Composable
private fun EditSquareButton(onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).pressScale().clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(18.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = tr("এডিট", "Edit"),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

/** Name-only dialog for a payment method. */
@Composable
private fun PaymentNameDialog(title: String, initial: String, taken: Set<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val error = if (trimmed.isNotEmpty() && trimmed in taken) tr("এই নামে মেথড আগে থেকেই আছে", "A method with this name already exists") else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it.take(20) },
                    label = { Text(tr("নাম", "Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && error == null, onClick = { onSave(trimmed) }) { Text(tr("সেভ", "Save")) }
        },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}

@Composable
private fun CategoryDialog(
    title: String,
    initialName: String,
    initialIcon: String,
    takenNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initialIcon) }
    val trimmed = name.trim()
    val error = when {
        trimmed.isNotEmpty() && trimmed in takenNames -> tr("এই নামে ক্যাটাগরি আগে থেকেই আছে", "A category with this name already exists")
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text(tr("নাম", "Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(icon, { icon = it.take(4) }, label = { Text(tr("আইকন (ইমোজি)", "Icon (emoji)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ICON_CHOICES.forEach { e ->
                        Text(e, fontSize = 22.sp, modifier = Modifier.clickable { icon = e })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && error == null, onClick = { onSave(trimmed, icon.trim().ifEmpty { "📦" }) }) {
                Text(tr("সেভ", "Save"))
            }
        },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}
