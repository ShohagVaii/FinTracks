package com.fintracks.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings: profile, theme, colour, currency, privacy, security (PIN + biometric + auto-lock), export, sign-out. */
@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedName = state.profile?.str("name").orEmpty()
    var name by remember(savedName) { mutableStateOf(savedName) }
    var lockOn by remember { mutableStateOf(AppLock.isEnabled(ctx)) }
    var timeout by remember { mutableStateOf(AppLock.timeoutSeconds(ctx).toString()) }
    var exporting by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }
    var showDisable by remember { mutableStateOf(false) }
    var showOldPin by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var restorePlan by remember { mutableStateOf<BackupPlan?>(null) }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                }
                restorePlan = Backup.parse(text)
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "ফাইল পড়া যায়নি", Toast.LENGTH_LONG).show()
            }
        }
    }

    Page {
        AppCard {
            Text("প্রোফাইল", fontWeight = FontWeight.Bold)
            TextIn("নাম", name, { name = it })
            Button(onClick = { vm.saveProfile(row("name" to name)) }, enabled = name.isNotBlank() && name != savedName) { Text("নাম সেভ") }
        }
        AppCard {
            Text("থিম", fontWeight = FontWeight.Bold)
            ChoiceChips(listOf("dark" to "ডার্ক", "light" to "লাইট"), if (state.dark) "dark" else "light") {
                vm.saveProfile(row("theme" to it))
            }
            Text("অ্যাকসেন্ট কালার", fontSize = 12.sp)
            ChoiceChips(AccentColor.entries.map { it.key to it.label }, state.accent.key) {
                vm.saveProfile(row("theme_color" to it))
            }
        }
        AppCard {
            Text("কারেন্সি", fontWeight = FontWeight.Bold)
            ChoiceChips(listOf("BDT" to "৳ BDT", "USD" to "$ USD", "EUR" to "€ EUR", "GBP" to "£ GBP"), state.currency) {
                vm.saveProfile(row("currency" to it))
            }
        }
        AppCard {
            SwitchRow("প্রাইভেসি মোড (টাকার পরিমাণ লুকানো)", state.privacy) { vm.saveProfile(row("is_privacy_mode" to it)) }
        }
        AppCard {
            Text("নিরাপত্তা", fontWeight = FontWeight.Bold)
            Text(
                "PIN, ফিঙ্গারপ্রিন্ট বা ফেস দিয়ে অ্যাপ লক করা যায়। ফোনে ফেস/ফিঙ্গারপ্রিন্ট না থাকলে PIN ব্যবহার হবে।",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!AppLock.hasPin(ctx)) {
                Button(onClick = { showSetPin = true }) { Text("PIN সেট করুন") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showOldPin = true }) { Text("PIN বদলান") }
                }
            }
            SwitchRow("অ্যাপ লক চালু", lockOn) {
                if (it && !AppLock.hasPin(ctx)) {
                    Toast.makeText(ctx, "আগে PIN সেট করুন", Toast.LENGTH_SHORT).show()
                } else if (it) {
                    lockOn = true
                    AppLock.enable(ctx)
                } else {
                    showDisable = true
                }
            }
            if (lockOn) {
                Text("অটো-লক: কতক্ষণ পর লক হবে", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceChips(listOf("0" to "সাথে সাথে", "60" to "১ মিনিট", "300" to "৫ মিনিট"), timeout) {
                    timeout = it
                    AppLock.setTimeout(ctx, it.toInt())
                }
            }
        }
        AppCard {
            Text("এক্সপোর্ট", fontWeight = FontWeight.Bold)
            Text("সব লেনদেন (সময়সীমা অনুযায়ী) Excel, PDF বা CSV হিসেবে শেয়ার করুন। PDF-এ বাংলা লেখা থাকে।", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Export.Format.XLSX to "Excel", Export.Format.PDF to "PDF", Export.Format.CSV to "CSV").forEach { (fmt, label) ->
                    OutlinedButton(enabled = !exporting, onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val txs = state.rows("transactions")
                                val file = withContext(Dispatchers.IO) { Export.listFile(ctx, fmt, state, txs, "সব লেনদেন") }
                                Export.share(ctx, file, fmt.mime, "FinTrack লেনদেন")
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "এক্সপোর্ট ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                exporting = false
                            }
                        }
                    }) { Text(label) }
                }
            }
        }
        AppCard {
            Text("ব্যাকআপ", fontWeight = FontWeight.Bold)
            Text("সব লেনদেন, বাকি, ধার, বাজেট, লক্ষ্য, বিনিয়োগ ও অন্য সব ডেটা একটি JSON ফাইলে। PIN ও লক সেটিং ব্যাকআপে থাকে না।", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) { Backup.file(ctx, state) }
                        Export.share(ctx, file, "application/json", "FinTrack ব্যাকআপ")
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "ব্যাকআপ ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("ব্যাকআপ ফাইল শেয়ার") }
            OutlinedButton(onClick = { pickBackup.launch(arrayOf("application/json", "text/plain")) }) { Text("ব্যাকআপ থেকে ফিরিয়ে আনুন") }
        }
        AppCard {
            Text("বিপজ্জনক এলাকা", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Text("অ্যাকাউন্ট মুছলে আপনার সব লেনদেন, বাকি, ধার, বাজেট ও অন্য সব ডেটা স্থায়ীভাবে চলে যাবে। এটি ফিরিয়ে আনা যাবে না।", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { showDelete = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("অ্যাকাউন্ট স্থায়ীভাবে মুছুন") }
        }
        AppCard {
            Text("অ্যাকাউন্ট", fontWeight = FontWeight.Bold)
            Text(state.profile?.str("email").orEmpty().ifEmpty { "ইমেইল পাওয়া যায়নি" }, fontSize = 12.sp)
            OutlinedButton(onClick = { vm.signOut() }) { Text("লগআউট") }
        }
        ToolsFooter()
    }

    if (showSetPin) {
        PinSetupDialog(onDismiss = { showSetPin = false }) { pin ->
            AppLock.setPin(ctx, pin)
            Toast.makeText(ctx, "PIN সেভ হয়েছে", Toast.LENGTH_SHORT).show()
            showSetPin = false
        }
    }
    if (showOldPin) {
        PinVerifyDialog("পুরনো PIN দিন", onDismiss = { showOldPin = false }) {
            showOldPin = false
            showSetPin = true
        }
    }
    restorePlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { restorePlan = null },
            title = { Text("ব্যাকআপ ফিরিয়ে আনবেন?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ব্যাকআপের তারিখ: ${plan.exportedAt.take(10)}", fontSize = 13.sp)
                    plan.tables.filter { it.value.isNotEmpty() }.forEach { (t, rows) ->
                        Text("$t: ${rows.size}টি", fontSize = 12.sp)
                    }
                    Text("বিদ্যমান ডেটা মুছবে না। একই ID থাকলে সেই সারি আপডেট হবে।", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { vm.restoreBackup(plan); restorePlan = null }) { Text("ফিরিয়ে আনুন") } },
            dismissButton = { TextButton(onClick = { restorePlan = null }) { Text("বাতিল") } }
        )
    }
    if (showDelete) {
        DeleteAccountDialog(
            email = state.profile?.str("email").orEmpty(),
            onDismiss = { showDelete = false },
            onConfirm = { password -> vm.deleteAccount(password); showDelete = false }
        )
    }
    if (showDisable) {
        PinVerifyDialog("লক বন্ধ করতে PIN দিন", onDismiss = { showDisable = false }) {
            AppLock.disable(ctx)
            lockOn = false
            showDisable = false
        }
    }
}

/** Two-step confirmation: type DELETE and the account password. */
@Composable
fun DeleteAccountDialog(email: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var word by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("অ্যাকাউন্ট মুছবেন?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("এটি স্থায়ী। সব ডেটা মুছে যাবে এবং ফিরিয়ে আনা যাবে না। নিশ্চিত করতে নিচে DELETE লিখুন এবং পাসওয়ার্ড দিন।", fontSize = 13.sp)
                OutlinedTextField(word, { word = it }, label = { Text("DELETE লিখুন") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("পাসওয়ার্ড") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = word == "DELETE" && password.isNotEmpty(), onClick = { onConfirm(password) }) {
                Text("স্থায়ীভাবে মুছুন", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } }
    )
}
