package com.fintracks.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.fintracks.app.ui.topInsetPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

// ======================= ধার ও পাওনা (ব্যক্তি-কেন্দ্রিক) =======================
// দেনা (direction = owe) = আমি এই ব্যক্তির কাছে নিয়েছি। পাওনা (direction = owed) = এই ব্যক্তি আমার কাছে পাবে।
// বাকি খাতা এই মডিউলের অংশ নয়।

private val DebtViolet = Violet600
private val DebtIndigo = Indigo600

/** Local date and time of a server timestamp, e.g. "19-11-2026 14:05:09" (digits follow the language setting). */
fun debtStamp(ts: String): String = runCatching {
    val z = OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.systemDefault())
    displayDate(z.toLocalDate().toString()) + " " +
        bnDigits(String.format(Locale.ENGLISH, "%02d:%02d:%02d", z.hour, z.minute, z.second))
}.getOrElse { displayDate(ts) }

/** Amount already paid back on one entry (sum of its payment rows). */
fun debtPaid(debt: JsonObject, payments: List<JsonObject>): Double =
    payments.filter { it.str("debt_id") == debt.id() }.sumOf { it.num("amount") }

/** Still owed on one entry: amount minus payments, never below zero. */
fun debtRemaining(debt: JsonObject, payments: List<JsonObject>): Double =
    (debt.num("amount") - debtPaid(debt, payments)).coerceAtLeast(0.0)

/** Total still owed in one direction ("owe" = I owe, "owed" = owed to me), counting only unpaid parts. */
fun debtsRemaining(state: UiState, direction: String): Double {
    val payments = state.rows("debt_payments")
    return state.rows("debts").filter { it.str("direction") == direction }.sumOf { debtRemaining(it, payments) }
}

private fun personEntries(state: UiState, personId: String, direction: String): List<JsonObject> =
    state.rows("debts")
        .filter { it.str("person_id") == personId && it.str("direction") == direction }
        .sortedByDescending { it.str("created_at") }

private fun personRemaining(state: UiState, personId: String, direction: String): Double {
    val payments = state.rows("debt_payments")
    return personEntries(state, personId, direction).sumOf { debtRemaining(it, payments) }
}

private fun plainNumber(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

private data class ContactAction(val sms: Boolean, val number: String)

private fun requestContact(ctx: Context, number: String, sms: Boolean, onContact: (ContactAction) -> Unit) {
    if (number.isBlank()) Toast.makeText(ctx, tr("ফোন নম্বর নেই", "No phone number"), Toast.LENGTH_SHORT).show()
    else onContact(ContactAction(sms, number))
}

private fun openDialer(ctx: Context, number: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    } catch (e: Exception) {
        Toast.makeText(ctx, tr("ডায়ালার খোলা যায়নি", "Could not open the dialer"), Toast.LENGTH_SHORT).show()
    }
}

private fun openSms(ctx: Context, number: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")))
    } catch (e: Exception) {
        Toast.makeText(ctx, tr("মেসেজ অ্যাপ খোলা যায়নি", "Could not open Messages"), Toast.LENGTH_SHORT).show()
    }
}

// ---------- Shared pieces ----------

@Composable
private fun DebtHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().topInsetPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(modifier = Modifier.pressScale(), onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("ফিরে যান", "Back"))
        }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun DebtGradientButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val fill = if (enabled) listOf(DebtViolet, DebtIndigo) else listOf(Color.Gray.copy(alpha = 0.4f), Color.Gray.copy(alpha = 0.4f))
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(fill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

@Composable
private fun AddPill(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(DebtViolet, DebtIndigo)))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
fun ConfirmDialog(text: String, confirmText: String, cancelText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(modifier = Modifier.pressScale(), onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(cancelText) } }
    )
}

/** Form dialog with the violet gradient submit button. */
@Composable
private fun DebtFormDialog(
    title: String,
    confirmText: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = { DebtGradientButton(confirmText, confirmEnabled, onConfirm) },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}

@Composable
private fun DashedDivider() {
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = Tok_66888888,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        )
    }
}

/** Folder-and-paper motif shown when there are no people yet. Drawn in the app's violet accent. */
@Composable
private fun EmptyFolderMotif() {
    Canvas(Modifier.size(120.dp)) {
        val w = size.width
        val h = size.height
        // paper sheet behind the folder
        drawRoundRect(
            color = Color.White.copy(alpha = 0.85f),
            topLeft = Offset(w * 0.25f, h * 0.15f),
            size = Size(w * 0.5f, h * 0.5f),
            cornerRadius = CornerRadius(8f)
        )
        // folder tab
        drawRoundRect(
            color = DebtViolet,
            topLeft = Offset(w * 0.1f, h * 0.25f),
            size = Size(w * 0.36f, h * 0.14f),
            cornerRadius = CornerRadius(8f)
        )
        // folder body
        drawRoundRect(
            color = DebtViolet.copy(alpha = 0.9f),
            topLeft = Offset(w * 0.1f, h * 0.33f),
            size = Size(w * 0.8f, h * 0.55f),
            cornerRadius = CornerRadius(12f)
        )
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(3.dp).background(if (selected) DebtViolet else Color.Transparent))
    }
}

// ---------- Forms ----------

@Composable
private fun PersonDialog(existing: JsonObject?, vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.str("name").orEmpty()) }
    var phone by remember { mutableStateOf(existing?.str("phone").orEmpty()) }
    var address by remember { mutableStateOf(existing?.str("address").orEmpty()) }
    val ctx = LocalContext.current
    DebtFormDialog(
        title = if (existing == null) tr("ব্যক্তি যোগ করুন", "Add person") else tr("ব্যক্তি এডিট করুন", "Edit person"),
        confirmText = if (existing == null) tr("ব্যবহারকারী যোগ করুন", "Add user") else tr("আপডেট ইউজার", "Update user"),
        confirmEnabled = name.isNotBlank(),
        onDismiss = onDismiss,
        onConfirm = {
            vm.savePerson(existing?.id(), name.trim(), phone.trim(), address.trim())
            Toast.makeText(
                ctx,
                if (existing == null) tr("ব্যবহারকারী সফলভাবে যোগ করা হয়েছে", "User added successfully")
                else tr("ব্যবহারকারী আপডেট হয়েছে", "User updated"),
                Toast.LENGTH_SHORT
            ).show()
            onDismiss()
        }
    ) {
        TextIn(tr("নাম (আবশ্যিক)", "Name (required)"), name, { name = it })
        TextIn(tr("ফোন নম্বর", "Phone number"), phone, { phone = it })
        TextIn(tr("ঠিকানা", "Address"), address, { address = it })
    }
}

@Composable
private fun EntryDialog(
    person: JsonObject,
    direction: String,
    existing: JsonObject?,
    state: UiState,
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val owe = direction == "owe"
    var reason by remember { mutableStateOf(existing?.str("note").orEmpty()) }
    var amount by remember { mutableStateOf(existing?.let { plainNumber(it.num("amount")) }.orEmpty()) }
    val ctx = LocalContext.current
    val a = parseAmount(amount)
    val paid = existing?.let { debtPaid(it, state.rows("debt_payments")) } ?: 0.0
    val tooLow = a != null && a < paid - 0.005
    val valid = reason.isNotBlank() && a != null && a > 0 && !tooLow
    DebtFormDialog(
        title = person.str("name"),
        confirmText = when {
            existing == null && owe -> tr("দেনা যোগ করুন", "Add debt")
            existing == null -> tr("পাওনা যোগ করুন", "Add dues")
            owe -> tr("দেনা আপডেট করুন", "Update debt")
            else -> tr("পাওনা আপডেট করুন", "Update dues")
        },
        confirmEnabled = valid,
        onDismiss = onDismiss,
        onConfirm = {
            if (a != null) {
                val body = row(
                    "person" to person.str("name"),
                    "person_id" to person.id(),
                    "amount" to a,
                    "direction" to direction,
                    "note" to reason.trim()
                )
                vm.save("debts", body, existing?.id())
                val msg = when {
                    existing != null -> tr("আপডেট হয়েছে", "Updated")
                    owe -> tr("দেনা সফলভাবে যুক্ত হয়েছে।", "Debt added successfully.")
                    else -> tr("পাওনা সফলভাবে যুক্ত হয়েছে।", "Dues added successfully.")
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }
    ) {
        TextIn(
            if (owe) tr("দেনার কারণ?", "Reason for the debt?") else tr("পাওনার কারণ?", "Reason for the dues?"),
            reason,
            { reason = it }
        )
        TextIn(tr("টাকার পরিমাণ?", "Amount?"), amount, { amount = it }, number = true)
        if (tooLow) {
            Text(
                tr("পরিশোধের চেয়ে কম হতে পারবে না", "Cannot be less than the amount already paid"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PaymentDialog(entry: JsonObject, balance: Double, vm: AppViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayIso()) }
    val a = parseAmount(amount)
    val tooMuch = a != null && a > balance + 0.005
    val valid = a != null && a > 0 && !tooMuch && isValidIsoDate(date) && balance > 0.005
    DebtFormDialog(
        title = tr("আংশিক পরিশোধ", "Partial payment"),
        confirmText = tr("পরিশোধ সেভ করুন", "Save payment"),
        confirmEnabled = valid,
        onDismiss = onDismiss,
        onConfirm = {
            if (a != null) {
                vm.recordPersonalDebtPayment(entry, a, date.trim())
                onDismiss()
            }
        }
    ) {
        Text(tr("বাকি: ", "Balance: ") + taka(balance), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        TextIn(tr("পরিমাণ (৳)", "Amount (৳)"), amount, { amount = it }, number = true)
        if (tooMuch) {
            Text(
                tr("বাকির বেশি টাকা দেওয়া যাবে না", "Payment cannot exceed the balance"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        DateField(tr("তারিখ", "Date"), date, { date = it })
    }
}

// ---------- Screens ----------

/** Main ধার screen: totals, people list, and the add-person button. Tapping a person opens their detail. */
@Composable
fun DebtsScreen(state: UiState, vm: AppViewModel, onClose: () -> Unit) {
    val p = state.privacy
    val people = state.rows("debt_people").sortedBy { it.str("name") }
    var openId by remember { mutableStateOf<String?>(null) }
    var addingPerson by remember { mutableStateOf(false) }
    var editingPerson by remember { mutableStateOf<JsonObject?>(null) }
    var deletingPerson by remember { mutableStateOf<JsonObject?>(null) }
    var contact by remember { mutableStateOf<ContactAction?>(null) }
    val open = people.firstOrNull { it.id() == openId }
    val ctx = LocalContext.current
    val pendingCount by vm.outbox.pendingCount.collectAsState()

    // Back inside a person's page returns to the list; back on the list closes the screen.
    BackHandler(enabled = openId != null) { openId = null }

    Column(Modifier.fillMaxSize()) {
        DebtHeader(open?.str("name") ?: tr("দেনা ও পাওনা", "Debts & dues")) {
            if (open != null) openId = null else onClose()
        }
        if (pendingCount > 0) {
            Text(
                tr("সিঙ্ক বাকি: $pendingCount টি পরিবর্তন (ইন্টারনেট এলে যাবে)", "Waiting to sync: $pendingCount change(s) (sent when online)"),
                fontSize = 12.sp,
                color = DebtViolet,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (open == null) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SummaryBox(tr("মোট দেনা", "Total debt"), taka(debtsRemaining(state, "owe"), p), Modifier.weight(1f))
                            SummaryBox(tr("মোট পাওনা", "Total dues"), taka(debtsRemaining(state, "owed"), p), Modifier.weight(1f))
                        }
                        if (people.isEmpty()) {
                            Spacer(Modifier.height(40.dp))
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyFolderMotif()
                                Spacer(Modifier.height(8.dp))
                                Text(tr("এখনো কোনো ব্যক্তি নেই", "No people yet"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        people.forEach { person ->
                            PersonCard(
                                person = person,
                                state = state,
                                onOpen = { openId = person.id() },
                                onContact = { sms -> requestContact(ctx, person.str("phone"), sms) { contact = it } },
                                onEdit = { editingPerson = person },
                                onDelete = { deletingPerson = person }
                            )
                        }
                        Spacer(Modifier.height(80.dp))
                    }
                    AddPill(
                        tr("দেনা/পাওনা যোগ করুন", "Add debt/dues"),
                        Modifier.align(Alignment.BottomEnd).padding(16.dp)
                    ) { addingPerson = true }
                }
            } else {
                PersonDetail(
                    person = open,
                    state = state,
                    vm = vm,
                    onContact = { sms, number -> requestContact(ctx, number, sms) { contact = it } }
                )
            }
        }
    }

    if (addingPerson) PersonDialog(null, vm) { addingPerson = false }
    editingPerson?.let { PersonDialog(it, vm) { editingPerson = null } }
    deletingPerson?.let { person ->
        ConfirmDialog(
            text = tr("ব্যক্তি ও তার সব এন্ট্রি মুছে যাবে। নিশ্চিত?", "This deletes the person and all their entries. Continue?"),
            confirmText = tr("মুছে ফেলুন", "Delete"),
            cancelText = tr("বাতিল", "Cancel"),
            onConfirm = {
                vm.remove("debt_people", person.id())
                if (openId == person.id()) openId = null
                deletingPerson = null
            },
            onDismiss = { deletingPerson = null }
        )
    }
    contact?.let { action ->
        ConfirmDialog(
            text = if (action.sms) tr("আপনি কি এস এম এস দিতে চাচ্ছেন?", "Do you want to send an SMS?")
            else tr("আপনি কি কল দিতে চাচ্ছেন?", "Do you want to call?"),
            confirmText = tr("হ্যাঁ", "Yes"),
            cancelText = tr("ক্যানসেল", "Cancel"),
            onConfirm = {
                contact = null
                if (action.sms) openSms(ctx, action.number) else openDialer(ctx, action.number)
            },
            onDismiss = { contact = null }
        )
    }
}

@Composable
private fun SummaryBox(label: String, amount: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(amount, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** One person: name, দেনা/পাওনা balances, phone, address, and the ⋮ menu. */
@Composable
private fun PersonCard(
    person: JsonObject,
    state: UiState,
    onOpen: () -> Unit,
    onContact: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val p = state.privacy
    var menu by remember { mutableStateOf(false) }
    val owe = personRemaining(state, person.id(), "owe")
    val owed = personRemaining(state, person.id(), "owed")
    AppCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(person.str("name"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    tr("দেনা - ", "I owe - ") + taka(owe, p) + tr(" । পাওনা - ", " · Owed to me - ") + taka(owed, p),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (person.str("phone").isNotBlank()) Text(person.str("phone"), fontSize = 13.sp)
                if (person.str("address").isNotBlank()) Text(person.str("address"), fontSize = 13.sp)
            }
            Box {
                IconButton(modifier = Modifier.pressScale(), onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = tr("মেনু", "Menu"))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("এখনই কল করুন", "Call now")) }, onClick = { menu = false; onContact(false) })
                    DropdownMenuItem(text = { Text(tr("এসএমএস পাঠান", "Send SMS")) }, onClick = { menu = false; onContact(true) })
                    DropdownMenuItem(text = { Text(tr("এডিট করুন", "Edit")) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(tr("মুছে ফেলুন", "Delete")) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** One person's page: দেনা | পাওনা tabs (animated), a total card, one card per entry. Tap an entry for its details. */
@Composable
private fun PersonDetail(
    person: JsonObject,
    state: UiState,
    vm: AppViewModel,
    onContact: (Boolean, String) -> Unit
) {
    val p = state.privacy
    var tab by rememberSaveable { mutableStateOf("owe") }
    var addingEntry by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<JsonObject?>(null) }
    var deletingEntry by remember { mutableStateOf<JsonObject?>(null) }
    var paying by remember { mutableStateOf<JsonObject?>(null) }
    var openEntryId by remember { mutableStateOf<String?>(null) }
    val payments = state.rows("debt_payments")
    val owe = tab == "owe"
    val accent = if (owe) Rose500 else Emerald500
    val total = personRemaining(state, person.id(), tab)
    // Read from the live rows, so the detail view shows payments as soon as they change.
    val openEntry = openEntryId?.let { id -> state.rows("debts").firstOrNull { it.id() == id } }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                TabItem(tr("দেনা", "I owe"), owe, Modifier.weight(1f)) { tab = "owe" }
                TabItem(tr("পাওনা", "Owed to me"), !owe, Modifier.weight(1f)) { tab = "owed" }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), DebtViolet.copy(alpha = 0.9f))))
                    .padding(18.dp)
            ) {
                Text(
                    if (owe) tr("সর্বমোট আমার দেনা", "Total I owe") else tr("সর্বমোট আমার পাওনা", "Total owed to me"),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Text(taka(total, p), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState == "owed"
                    (slideInHorizontally(animationSpec = tween(260)) { if (forward) it / 5 else -it / 5 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(200)) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(160)))
                },
                label = "debt-tab"
            ) { t ->
                val list = personEntries(state, person.id(), t)
                val cardAccent = if (t == "owe") Rose500 else Emerald500
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (list.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(tr("কোনো এন্ট্রি নেই", "No entries"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    list.forEachIndexed { i, entry ->
                        EntryCard(entry, payments, p, cardAccent, i) { openEntryId = entry.id() }
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
        AddPill(
            if (owe) tr("দেনা যোগ করুন", "Add debt") else tr("পাওনা যোগ করুন", "Add dues"),
            Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { addingEntry = true }
    }

    openEntry?.let { entry ->
        EntryDetail(
            entry = entry,
            payments = payments,
            privacy = p,
            accent = accent,
            onDismiss = { openEntryId = null },
            onCall = { onContact(false, person.str("phone")) },
            onSms = { onContact(true, person.str("phone")) },
            onEdit = { editingEntry = entry },
            onPay = { paying = entry },
            onDelete = { deletingEntry = entry },
            onDeletePayment = { vm.remove("debt_payments", it) }
        )
    }
    if (addingEntry) EntryDialog(person, tab, null, state, vm) { addingEntry = false }
    editingEntry?.let { EntryDialog(person, tab, it, state, vm) { editingEntry = null } }
    paying?.let { entry -> PaymentDialog(entry, debtRemaining(entry, payments), vm) { paying = null } }
    deletingEntry?.let { entry ->
        ConfirmDialog(
            text = tr(
                "এই এন্ট্রি মুছে যাবে। এর সব পরিশোধ এবং সংশ্লিষ্ট লেনদেনও মুছে যাবে। নিশ্চিত?",
                "This entry will be deleted. Its payments and linked transactions will also be deleted. Continue?"
            ),
            confirmText = tr("মুছে ফেলুন", "Delete"),
            cancelText = tr("বাতিল", "Cancel"),
            onConfirm = {
                vm.remove("debts", entry.id())
                deletingEntry = null
                openEntryId = null
            },
            onDismiss = { deletingEntry = null }
        )
    }
}

/** Progress of the remaining part of an entry: a thin bar that animates when it changes. */
@Composable
private fun DebtProgress(fraction: Float, accent: Color, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = tween(500), label = "debt-progress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f))) {
        Box(
            Modifier
                .fillMaxWidth(shown)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(accent, DebtViolet)))
        )
    }
}

/** A compact entry card: reason, remaining amount, date, and the remaining-share bar. Tap opens the details. */
@Composable
private fun EntryCard(
    entry: JsonObject,
    payments: List<JsonObject>,
    privacy: Boolean,
    accent: Color,
    index: Int,
    onOpen: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.97f else 1f, label = "debt-press")
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val enter by animateFloatAsState(
        if (appeared) 1f else 0f,
        animationSpec = tween(280, delayMillis = minOf(index, 6) * 45),
        label = "debt-enter"
    )
    val amount = entry.num("amount")
    val remaining = debtRemaining(entry, payments)
    val fraction = if (amount > 0) (remaining / amount).toFloat() else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter
                translationY = (1f - enter) * 18f
                scaleX = press
                scaleY = press
            }
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .clickable(interactionSource = source, indication = null, onClick = onOpen)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.str("note"), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(debtStamp(entry.str("created_at")), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(taka(remaining, privacy), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = accent)
        }
        DebtProgress(fraction, accent)
    }
}

/** Full details of one entry: totals, progress, the whole payment history (with delete), and the entry actions. */
@Composable
private fun EntryDetail(
    entry: JsonObject,
    payments: List<JsonObject>,
    privacy: Boolean,
    accent: Color,
    onDismiss: () -> Unit,
    onCall: () -> Unit,
    onSms: () -> Unit,
    onEdit: () -> Unit,
    onPay: () -> Unit,
    onDelete: () -> Unit,
    onDeletePayment: (String) -> Unit
) {
    val amount = entry.num("amount")
    val paid = debtPaid(entry, payments)
    val remaining = debtRemaining(entry, payments)
    val settled = remaining <= 0.005
    val fraction = if (amount > 0) (remaining / amount).toFloat() else 0f
    val history = payments.filter { it.str("debt_id") == entry.id() }.sortedByDescending { it.str("paid_on") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(entry.str("note"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(tr("তারিখ - ", "Date - ") + debtStamp(entry.str("created_at")), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailStat(tr("মোট", "Total"), taka(amount, privacy), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                DetailStat(tr("পরিশোধিত", "Paid"), taka(paid, privacy), Emerald500, Modifier.weight(1f))
                DetailStat(tr("বাকি", "Left"), taka(remaining, privacy), accent, Modifier.weight(1f))
            }
            DebtProgress(fraction, accent)
            if (settled) Text(tr("পরিশোধিত", "Settled"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = readableOn(Emerald500))
            Text(tr("পরিশোধের ইতিহাস", "Payment history"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (history.isEmpty()) {
                Text(tr("এখনো কোনো পরিশোধ নেই", "No payments yet"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            history.forEach { pay ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(displayDate(pay.str("paid_on")), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(taka(pay.num("amount"), privacy), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    IconButton(onClick = { onDeletePayment(pay.id()) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = tr("পরিশোধ মুছুন", "Delete payment"), tint = Rose500, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (!settled) DebtGradientButton(tr("পরিশোধ যোগ করুন", "Add payment"), true, onPay)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(modifier = Modifier.pressScale(), onClick = onCall) { Text(tr("কল", "Call")) }
                TextButton(modifier = Modifier.pressScale(), onClick = onSms) { Text(tr("এসএমএস", "SMS")) }
                TextButton(modifier = Modifier.pressScale(), onClick = onEdit) { Text(tr("এডিট", "Edit")) }
                TextButton(modifier = Modifier.pressScale(), onClick = onDelete) { Text(tr("মুছুন", "Delete"), color = readableOn(Rose500)) }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(tr("বন্ধ করুন", "Close")) }
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(10.dp)
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
