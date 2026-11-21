package com.fintracks.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.id
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject

/** Emoji offered as quick picks for a category icon. The field also accepts any text. */
private val ICON_CHOICES = listOf("🍔", "🚗", "🛍️", "📜", "🏠", "💊", "🎓", "🎁", "✈️", "📱", "🐶", "💡", "🎮", "☕", "💼", "📦")

/**
 * Category management inside Settings. Default categories are shown but locked (they are used by the app's
 * own features). Custom categories can be added, renamed (existing transactions follow the new name) and deleted.
 */
@Composable
fun CategoryManager(state: UiState, vm: AppViewModel) {
    var type by remember { mutableStateOf("Expense") }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JsonObject?>(null) }

    val defaults = if (type == "Income") INCOME_CATS else EXPENSE_CATS
    val custom = state.rows("categories").filter { it.str("type") == type }
    val existingNames = (defaults.map { it.name } + custom.map { it.str("name") }).toSet()
    fun usedBy(name: String): Int = state.rows("transactions").count { it.str("category") == name && it.str("type") == type }

    ChoiceChips(listOf("Expense" to tr("ব্যয়", "Expense"), "Income" to tr("আয়", "Income")), type) { type = it }
    Text(
        tr("ডিফল্ট ক্যাটাগরি মোছা বা বদলানো যায় না। নিজের ক্যাটাগরি যোগ, বদল ও মুছতে পারবেন।",
            "Default categories cannot be changed or deleted. You can add, rename and delete your own."),
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    defaults.forEach { c ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.icon, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(catLabel(c.name), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(tr("ডিফল্ট", "Default"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    custom.forEach { c ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(c.str("icon").ifEmpty { "📦" }, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(c.str("name"), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { editing = c }) { Icon(Icons.Default.Edit, contentDescription = tr("এডিট", "Edit")) }
            DeleteButton { deleting = c }
        }
    }
    if (custom.isEmpty()) {
        Text(tr("এখনো নিজের কোনো ক্যাটাগরি নেই", "No custom categories yet"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedButton(onClick = { creating = true }) { Text(tr("+ নতুন ক্যাটাগরি", "+ New category")) }

    if (creating || editing != null) {
        val original = editing
        CategoryDialog(
            title = if (original == null) tr("নতুন ক্যাটাগরি", "New category") else tr("ক্যাটাগরি এডিট", "Edit category"),
            initialName = original?.str("name").orEmpty(),
            initialIcon = original?.str("icon")?.ifEmpty { "📦" } ?: "📦",
            // A name is taken if a default or another custom category of the same type has it.
            takenNames = existingNames - setOfNotNull(original?.str("name")),
            onDismiss = { creating = false; editing = null },
            onSave = { name, icon ->
                if (original == null) {
                    vm.save("categories", com.fintracks.app.data.row("name" to name, "icon" to icon, "type" to type), null)
                } else {
                    val oldName = original.str("name")
                    val writes = mutableListOf<Triple<String, JsonObject, String?>>(
                        Triple("categories", com.fintracks.app.data.row("name" to name, "icon" to icon), original.id())
                    )
                    // Rename: existing transactions with the old name follow the new name.
                    if (oldName != name) {
                        state.rows("transactions")
                            .filter { it.str("category") == oldName && it.str("type") == type }
                            .forEach { writes += Triple("transactions", com.fintracks.app.data.row("category" to name), it.id()) }
                    }
                    vm.batch(writes)
                }
                creating = false; editing = null
            }
        )
    }

    deleting?.let { c ->
        val n = usedBy(c.str("name"))
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("ক্যাটাগরি মুছবেন?", "Delete category?"), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (n > 0) tr("\"${c.str("name")}\" ক্যাটাগরিতে ${bnDigits(n.toString())}টি লেনদেন আছে। মুছলে লেনদেনগুলো থাকবে, শুধু ক্যাটাগরির নাম আর এই তালিকায় থাকবে না।", "\"${c.str("name")}\" has $n transactions. They stay, but the category will no longer be listed.")
                    else tr("\"${c.str("name")}\" ক্যাটাগরিটি মুছে ফেলা হবে।", "\"${c.str("name")}\" will be deleted.")
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.remove("categories", c.id()); deleting = null }) {
                    Text(tr("মুছুন", "Delete"), color = Rose500)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("বাতিল", "Cancel")) } }
        )
    }
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}
