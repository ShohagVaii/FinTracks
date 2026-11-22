package com.fintracks.app.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val USERNAME_RE = Regex("^[a-z0-9_]{3,20}$")
private val SYNC_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a", Locale.US)

/**
 * Settings, rebuilt: profile card, appearance and preferences, categories, security, reminder,
 * data and sync, account, footer. Every switch and choice writes to the profile or the device and takes effect at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var emailLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        email = runCatching { vm.accountEmail() }.getOrDefault("")
        emailLoaded = true
    }

    var editProfile by remember { mutableStateOf(false) }
    var lockOn by remember { mutableStateOf(AppLock.isEnabled(ctx)) }
    var timeout by remember { mutableStateOf(AppLock.timeoutSeconds(ctx).toString()) }
    var bioOn by remember { mutableStateOf(AppLock.biometricOn(ctx)) }
    var hasPin by remember { mutableStateOf(AppLock.hasPin(ctx)) }
    var exporting by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }
    var showDisable by remember { mutableStateOf(false) }
    var showOldPin by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var reminderOn by remember { mutableStateOf(DailyReminder.enabled(ctx)) }
    var reminderTime by remember { mutableStateOf(DailyReminder.time(ctx)) }
    var restorePlan by remember { mutableStateOf<BackupPlan?>(null) }

    val lastSync = remember(state.tables) { Sync.lastOk(ctx) }
    val bioHardware = remember {
        BiometricManager.from(ctx).canAuthenticate(AppLock.biometricAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                }
                restorePlan = Backup.parse(text)
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: tr("ফাইল পড়া যায়নি", "Couldn't read the file"), Toast.LENGTH_LONG).show()
            }
        }
    }

    val dateStyle = if (state.profile?.str("date_format") == "bn") "bn" else "en"
    val langStyle = if (AppPrefs.english) "en" else "bn"

    // Sub-pages replace this page; their own BackHandler returns here.
    var page by rememberSaveable { mutableStateOf("") }
    if (page == "login") {
        LoginSecurityPage(vm, lockOn, hasPin, bioOn, bioHardware, onBack = { page = "" }, onOpenSecurity = { page = "" })
        return
    }
    if (page == "faq") {
        FaqPage { page = "" }
        return
    }
    val uid = remember { vm.currentUid() }

    Page {
        ProfileCard(state, email, emailLoaded) { editProfile = true }
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("ইউজার আইডি", "User ID"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (uid.isEmpty()) "—" else uid.take(8) + "…", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                if (uid.isNotEmpty()) TextButton(modifier = Modifier.pressScale(), onClick = { copyText(ctx, "FinTrack user id", uid) }) { Text(tr("কপি", "Copy")) }
            }
        }
        AppCard {
            SettingLink(tr("লগইন ও নিরাপত্তা", "Login & security"), tr("ইমেইল, Google, পাসওয়ার্ড, ফোনের লক", "Email, Google, password, phone lock")) { page = "login" }
        }

        // ---- চেহারা ও পছন্দ ----
        AppCard {
            SectionTitle(tr("চেহারা ও পছন্দ", "Appearance & preferences"))
            Text(tr("থিম", "Theme"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(
                listOf("dark" to tr("ডার্ক", "Dark"), "light" to tr("লাইট", "Light")),
                if (state.dark) "dark" else "light"
            ) { vm.saveProfile(row("theme" to it)) }

            Text(tr("অ্যাকসেন্ট কালার", "Accent colour") + ": " + state.accent.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentColor.entries.forEach { a ->
                    val selected = a == state.accent
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(a.darkPrimary)
                            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                            .clickable { vm.saveProfile(row("theme_color" to a.key)) }
                    )
                }
            }

            Text(tr("মুদ্রা", "Currency"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(
                listOf("BDT" to "${currencySymbol("BDT")} BDT", "USD" to "${currencySymbol("USD")} USD", "EUR" to "${currencySymbol("EUR")} EUR", "GBP" to "${currencySymbol("GBP")} GBP"),
                state.currency
            ) { vm.saveProfile(row("currency" to it)) }

            Text(tr("তারিখ ফরম্যাট", "Date format"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(
                listOf("bn" to tr("বাংলা", "Bangla"), "en" to tr("English", "English")),
                dateStyle
            ) { vm.saveProfile(row("date_format" to it)) }
            Text(
                tr("নমুনা: ", "Example: ") + displayDate(LocalDate.now().toString()),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(tr("ভাষা", "Language"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(listOf("bn" to "বাংলা", "en" to "English"), langStyle) { vm.saveProfile(row("language" to it)) }
            Text(
                tr("ভাষা বদলালে সেটিংস, প্রধান মেনু, হোম, লেনদেন, শেয়ার ও টুলস স্ক্রিন ইংরেজিতে দেখাবে। বাকি স্ক্রিন এখনো বাংলায়।",
                    "Language changes Settings, the main menu, Home, Transactions, Shared and Tools. Other screens are still in Bangla."),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SwitchRow(tr("ফেক মোড (DEMO): টাকার অঙ্ক বদলে দেখাবে", "Demo mode: show fake amounts"), state.demo) { vm.setDemo(it) }
            SwitchRow(tr("প্রাইভেসি মোড (টাকার পরিমাণ লুকানো)", "Privacy mode (hide amounts)"), state.privacy) {
                vm.saveProfile(row("is_privacy_mode" to it))
            }
            // Full-screen mode hides the status bar. Off returns the normal edge-to-edge look with the bar visible.
            SwitchRow(tr("পুরো স্ক্রিন মোড (স্ট্যাটাস বার লুকানো)", "Full-screen mode (status bar hidden)"), Immersive.on) {
                Immersive.set(ctx, it)
            }
        }

        // ---- ক্যাটাগরি ----
        AppCard {
            SectionTitle(tr("ক্যাটাগরি", "Categories"))
            CategoryManager(state, vm)
        }

        // ---- নিরাপত্তা ----
        AppCard {
            SectionTitle(tr("নিরাপত্তা", "Security"))
            // Link to the device-security block (PIN and fingerprint/face status) on the login & security page.
            SettingLink(tr("PIN ও ফিঙ্গারপ্রিন্ট/ফেস স্ট্যাটাস", "PIN and fingerprint/face status"), tr("লগইন ও নিরাপত্তা পেজে দেখুন", "Shown on Login & security")) { page = "login" }
            Text(
                tr("PIN, ফিঙ্গারপ্রিন্ট বা ফেস দিয়ে অ্যাপ লক করা যায়। ফোনে ফেস/ফিঙ্গারপ্রিন্ট না থাকলে PIN ব্যবহার হবে।",
                    "Lock the app with a PIN, fingerprint or face. Without a fingerprint or face on the phone, the PIN is used."),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hasPin) {
                Button(modifier = Modifier.pressScale(), onClick = { showSetPin = true }) { Text(tr("PIN সেট করুন", "Set PIN")) }
            } else {
                OutlinedButton(modifier = Modifier.pressScale(), onClick = { showOldPin = true }) { Text(tr("PIN বদলান", "Change PIN")) }
            }
            SwitchRow(tr("অ্যাপ লক চালু", "App lock on"), lockOn) {
                if (it && !hasPin) {
                    Toast.makeText(ctx, tr("আগে PIN সেট করুন", "Set a PIN first"), Toast.LENGTH_SHORT).show()
                } else if (it) {
                    lockOn = true
                    AppLock.enable(ctx)
                } else {
                    showDisable = true
                }
            }
            if (lockOn) {
                Text(tr("অটো-লক: কতক্ষণ পর লক হবে", "Auto-lock after"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceChips(
                    listOf("0" to tr("সাথে সাথে", "Immediately"), "60" to tr("১ মিনিট", "1 minute"), "300" to tr("৫ মিনিট", "5 minutes")),
                    timeout
                ) {
                    timeout = it
                    AppLock.setTimeout(ctx, it.toInt())
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    tr("ফিঙ্গারপ্রিন্ট / ফেস দিয়ে আনলক", "Unlock with fingerprint / face"),
                    color = if (lockOn && bioHardware) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = bioOn,
                    enabled = lockOn && bioHardware,
                    onCheckedChange = {
                        bioOn = it
                        AppLock.setBiometric(ctx, it)
                    }
                )
            }
            if (!bioHardware) {
                Text(tr("এই ফোনে ফিঙ্গারপ্রিন্ট বা ফেস সেটআপ নেই, তাই PIN ব্যবহার হবে।", "This phone has no fingerprint or face set up, so the PIN is used."),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- রিমাইন্ডার ----
        AppCard {
            SectionTitle(tr("দৈনিক রিমাইন্ডার", "Daily reminder"))
            SwitchRow(tr("প্রতিদিন হিসাব লিখতে মনে করিয়ে দিন", "Remind me daily to log transactions"), reminderOn) { on ->
                reminderOn = on
                DailyReminder.set(ctx, on, reminderTime)
                if (on) vm.saveProfile(row("reminder_time" to reminderTime))
            }
            if (reminderOn) {
                OutlinedButton(modifier = Modifier.pressScale(), onClick = { showTime = true }) {
                    Text(tr("সময়: ", "Time: ") + if (AppPrefs.english) reminderTime else bnDigits(reminderTime))
                }
                Text(
                    tr("নোটিফিকেশন বন্ধ থাকলে বা ব্যাটারি সেভার চালু থাকলে রিমাইন্ডার দেরিতে আসতে পারে।",
                        "If notifications are off or battery saver is on, the reminder may arrive late."),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- ডাটা ও সিঙ্ক ----
        AppCard {
            SectionTitle(tr("ডাটা ও সিঙ্ক", "Data & sync"))
            Text(
                tr("সর্বশেষ সফল সিঙ্ক: ", "Last successful sync: ") + syncText(lastSync),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { vm.syncNow() }) { Text(tr("এখনই সিঙ্ক করুন", "Sync now")) }

            Text(tr("এক্সপোর্ট", "Export"), fontWeight = FontWeight.SemiBold)
            Text(tr("সব লেনদেন (সময়সীমা অনুযায়ী) Excel, PDF বা CSV হিসেবে শেয়ার করুন। PDF-এ বাংলা লেখা থাকে।",
                "Share all transactions as Excel, PDF or CSV. The PDF keeps Bangla text."),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Export.Format.XLSX to "Excel", Export.Format.PDF to "PDF", Export.Format.CSV to "CSV").forEach { (fmt, label) ->
                    OutlinedButton(enabled = !exporting, onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val txs = state.rows("transactions")
                                val file = withContext(Dispatchers.IO) { Export.listFile(ctx, fmt, state, txs, tr("সব লেনদেন", "All transactions")) }
                                Export.share(ctx, file, fmt.mime, tr("FinTrack লেনদেন", "FinTrack transactions"))
                            } catch (e: Exception) {
                                Toast.makeText(ctx, tr("এক্সপোর্ট ব্যর্থ: ${e.message}", "Export failed: ${e.message}"), Toast.LENGTH_LONG).show()
                            } finally {
                                exporting = false
                            }
                        }
                    }) { Text(label) }
                }
            }

            Text(tr("ব্যাকআপ", "Backup"), fontWeight = FontWeight.SemiBold)
            Text(tr("সব লেনদেন, বাকি, ধার, বাজেট, লক্ষ্য, বিনিয়োগ, ক্যাটাগরি ও অন্য সব ডেটা একটি JSON ফাইলে। PIN ও লক সেটিং ব্যাকআপে থাকে না।",
                "All transactions, credit, debts, budgets, goals, investments, categories and other data in one JSON file. The PIN and lock settings are not included."),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(modifier = Modifier.pressScale(), onClick = {
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) { Backup.file(ctx, state) }
                        Export.share(ctx, file, "application/json", tr("FinTrack ব্যাকআপ", "FinTrack backup"))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, tr("ব্যাকআপ ব্যর্থ: ${e.message}", "Backup failed: ${e.message}"), Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text(tr("ব্যাকআপ ফাইল শেয়ার", "Share backup file")) }
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { pickBackup.launch(arrayOf("application/json", "text/plain")) }) {
                Text(tr("ব্যাকআপ থেকে ফিরিয়ে আনুন", "Restore from backup"))
            }

            Text(tr("অ্যাপ রিসেট", "Reset app"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text(tr("এই ফোনের সব লোকাল ডেটা (PIN, লক, অফলাইন নোট, গণনা, পছন্দ, লগইন) মুছবে। সার্ভারের ডেটা মুছবে না।",
                "Deletes all local data on this phone (PIN, lock, offline notes, counts, preferences, sign-in). Server data is not deleted."),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { showReset = true }) {
                Text(tr("লোকাল ডেটা মুছুন", "Erase local data"), color = MaterialTheme.colorScheme.error)
            }
        }

        // ---- অ্যাকাউন্ট ----
        AppCard {
            SectionTitle(tr("অ্যাকাউন্ট", "Account"))
            Text(tr("বিপজ্জনক এলাকা", "Danger zone"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Text(tr("অ্যাকাউন্ট মুছলে আপনার সব লেনদেন, বাকি, ধার, বাজেট ও অন্য সব ডেটা স্থায়ীভাবে চলে যাবে। এটি ফিরিয়ে আনা যাবে না।",
                "Deleting the account permanently removes all your transactions, credit, debts, budgets and other data. This cannot be undone."),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(modifier = Modifier.pressScale(), onClick = { showDelete = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(tr("অ্যাকাউন্ট স্থায়ীভাবে মুছুন", "Delete account permanently"))
            }
        }

        AppCard {
            SectionTitle(tr("সাহায্য ও তথ্য", "Help & info"))
            SettingLink(tr("প্রশ্ন-উত্তর", "Questions & answers"), tr("অফলাইনেও পড়া যায়", "Readable offline")) { page = "faq" }
            SettingLink(tr("সাপোর্ট ইমেইল", "Support email"), SUPPORT_EMAIL) { sendSupportMail(ctx) }
            SettingLink(tr("প্রাইভেসি পলিসি", "Privacy policy")) { openUrl(ctx, PRIVACY_URL) }
            SettingLink(tr("টার্মস ও শর্তাবলি", "Terms of use")) { openUrl(ctx, TERMS_URL) }
        }
        AppCard {
            SectionTitle(tr("শেয়ার ও রেটিং", "Share & rate"))
            SettingLink(tr("অ্যাপ শেয়ার করুন", "Share the app")) { shareApp(ctx) }
            SettingLink(
                tr("রেট দিন", "Rate the app"),
                tr("এখন ওয়েবসাইটে যাবে; Play Store-এ এখনো নেই", "Opens the website for now; not on Play Store yet")
            ) { openUrl(ctx, WEBSITE_URL) }
        }
        SocialRow(ctx)
        ToolsFooter()
        // Logout is the last row, in its own rose band.
        LogoutRow { vm.signOut() }
    }

    // ---- dialogs ----
    if (editProfile) EditProfileDialog(state, vm) { editProfile = false }
    if (showSetPin) {
        PinSetupDialog(onDismiss = { showSetPin = false }) { pin ->
            AppLock.setPin(ctx, pin)
            hasPin = true
            Toast.makeText(ctx, tr("PIN সেভ হয়েছে", "PIN saved"), Toast.LENGTH_SHORT).show()
            showSetPin = false
        }
    }
    if (showOldPin) {
        PinVerifyDialog(tr("পুরনো PIN দিন", "Enter current PIN"), onDismiss = { showOldPin = false }) {
            showOldPin = false
            showSetPin = true
        }
    }
    if (showDisable) {
        PinVerifyDialog(tr("লক বন্ধ করতে PIN দিন", "Enter PIN to turn the lock off"), onDismiss = { showDisable = false }) {
            AppLock.disable(ctx)
            lockOn = false
            showDisable = false
        }
    }
    if (showTime) {
        val parts = reminderTime.split(":")
        val timeState = rememberTimePickerState(
            initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 22,
            initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(tr("রিমাইন্ডারের সময়", "Reminder time"), fontWeight = FontWeight.Bold) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(modifier = Modifier.pressScale(), onClick = {
                    val t = "%02d:%02d".format(timeState.hour, timeState.minute)
                    reminderTime = t
                    DailyReminder.set(ctx, reminderOn, t)
                    vm.saveProfile(row("reminder_time" to t))
                    showTime = false
                }) { Text(tr("ঠিক আছে", "OK")) }
            },
            dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = { showTime = false }) { Text(tr("বাতিল", "Cancel")) } }
        )
    }
    restorePlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { restorePlan = null },
            title = { Text(tr("ব্যাকআপ ফিরিয়ে আনবেন?", "Restore backup?"), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("ব্যাকআপের তারিখ: ", "Backup date: ") + plan.exportedAt.take(10), fontSize = 13.sp)
                    plan.tables.filter { it.value.isNotEmpty() }.forEach { (t, rows) ->
                        Text("$t: ${rows.size}" + tr("টি", ""), fontSize = 12.sp)
                    }
                    Text(tr("বিদ্যমান ডেটা মুছবে না। একই ID থাকলে সেই সারি আপডেট হবে।", "Existing data is not deleted. Rows with the same ID are updated."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(modifier = Modifier.pressScale(), onClick = { vm.restoreBackup(plan); restorePlan = null }) { Text(tr("ফিরিয়ে আনুন", "Restore")) } },
            dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = { restorePlan = null }) { Text(tr("বাতিল", "Cancel")) } }
        )
    }
    if (showDelete) {
        DeleteAccountDialog(
            email = email,
            onDismiss = { showDelete = false },
            onConfirm = { password -> vm.deleteAccount(password); showDelete = false }
        )
    }
    if (showReset) {
        ResetDialog(
            pendingOffline = (vm.notes.pendingCount.value + vm.cashCounts.pendingCount.value),
            onDismiss = { showReset = false },
            onConfirm = {
                showReset = false
                vm.resetLocal()
                Toast.makeText(ctx, tr("লোকাল ডেটা মুছে ফেলা হয়েছে", "Local data erased"), Toast.LENGTH_LONG).show()
            }
        )
    }
}

private fun syncText(epochMs: Long): String {
    if (epochMs == 0L) return tr("এখনো হয়নি", "not yet")
    val s = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(SYNC_FMT)
    return if (AppPrefs.english) s else bnDigits(s)
}

/** Profile summary: photo, name, @username, login email, and the edit button. */
@Composable
private fun ProfileCard(state: UiState, email: String, emailLoaded: Boolean, onEdit: () -> Unit) {
    val name = state.name.ifBlank { tr("অতিথি ব্যবহারকারী", "Guest user") }
    val username = state.profile?.str("username").orEmpty()
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AvatarImage(url = state.profile?.str("avatar_url").orEmpty(), fallback = name, size = 64.dp)
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                Text(
                    if (username.isEmpty()) tr("ইউজারনেম নেই", "No username") else "@$username",
                    color = MaterialTheme.colorScheme.primary, fontSize = 13.sp
                )
                Text(
                    when {
                        email.isNotBlank() -> email
                        emailLoaded -> tr("ইমেইল পাওয়া যায়নি", "Email not found")
                        else -> tr("ইমেইল লোড হচ্ছে…", "Loading email…")
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
            IconButton(modifier = Modifier.pressScale(), onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = tr("প্রোফাইল এডিট", "Edit profile")) }
        }
        Spacer(Modifier.height(10.dp))
        CompletenessBar(profileChecks(state.profile), showMissing = false)
    }
}

/** Round photo from a URL. Falls back to the first letter of [fallback] when there is no photo. */
@Composable
private fun AvatarImage(url: String, fallback: String, size: Dp) {
    val bmp by produceState<ImageBitmap?>(null, url) {
        value = if (!url.startsWith("https://")) null else withContext(Dispatchers.IO) { loadAvatarBitmap(url) }
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(bitmap = b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(fallback.trim().take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = (size.value / 2.2f).sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Loads a profile photo over HTTPS. A bad URL, an HTTP error or a broken image gives null, so the letter shows. */
private fun loadAvatarBitmap(url: String): ImageBitmap? = runCatching {
    val conn = URL(url).openConnection() as java.net.HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        if (conn.responseCode !in 200..299) null
        else conn.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    } finally {
        conn.disconnect()
    }
}.getOrNull()

/** Edit name, username, birth date, phone, address and photo. Changes go to the profile on the server. */
@Composable
private fun EditProfileDialog(state: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = state.profile
    var name by remember { mutableStateOf(p?.str("name").orEmpty()) }
    var username by remember { mutableStateOf(p?.str("username").orEmpty()) }
    var dob by remember { mutableStateOf(p?.str("date_of_birth").orEmpty().take(10)) }
    var phone by remember { mutableStateOf(p?.str("phone").orEmpty()) }
    var address by remember { mutableStateOf(p?.str("address").orEmpty()) }
    var photoUrl by remember { mutableStateOf(p?.str("avatar_url").orEmpty()) }
    var district by remember { mutableStateOf(p?.str("district").orEmpty()) }
    var occupation by remember { mutableStateOf(p?.str("occupation").orEmpty()) }
    var pickDistrict by remember { mutableStateOf(false) }
    var pickOccupation by remember { mutableStateOf(false) }
    var pickingDob by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            uploading = true
            try {
                val bytes = withContext(Dispatchers.IO) { compressAvatar(ctx, uri) }
                if (bytes == null) {
                    Toast.makeText(ctx, tr("ছবিটি পড়া যায়নি", "Could not read the photo"), Toast.LENGTH_SHORT).show()
                } else {
                    // The new URL goes into this dialog at once, so Save cannot write the old value back.
                    photoUrl = vm.uploadAvatar(bytes)
                    Toast.makeText(ctx, tr("প্রোফাইল ছবি সেভ হয়েছে", "Profile photo saved"), Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The real reason, shown right here; a silent failure would look like nothing happened.
                Toast.makeText(
                    ctx,
                    tr("ছবি আপলোড ব্যর্থ: ${e.message ?: "অজানা ত্রুটি"}", "Photo upload failed: ${e.message ?: "unknown error"}"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                uploading = false
            }
        }
    }

    val u = username.trim().lowercase()
    val error = when {
        name.isBlank() -> tr("নাম লিখুন", "Enter a name")
        photoUrl.isNotBlank() && !photoUrl.trim().startsWith("https://") ->
            tr("ছবির URL অবশ্যই https:// দিয়ে শুরু হতে হবে (অথবা খালি রাখুন)", "The photo URL must start with https:// (or leave it empty)")
        u.isNotEmpty() && !USERNAME_RE.matches(u) -> tr("ইউজারনেম ৩–২০ অক্ষর: ইংরেজি ছোট হাতের অক্ষর, সংখ্যা বা _", "Username: 3–20 lowercase letters, digits or _")
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("প্রোফাইল এডিট", "Edit profile"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AvatarImage(url = photoUrl, fallback = name, size = 56.dp)
                    OutlinedButton(enabled = !uploading, onClick = {
                        picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(if (uploading) tr("আপলোড হচ্ছে…", "Uploading…") else tr("গ্যালারি থেকে ছবি", "Photo from gallery")) }
                }
                OutlinedTextField(name, { name = it.take(60) }, label = { Text(tr("নাম", "Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it.take(20) }, label = { Text(tr("ইউজারনেম", "Username")) }, singleLine = true,
                    prefix = { Text("@") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { pickingDob = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("জন্ম তারিখ: ", "Birth date: ") + (if (dob.isEmpty()) tr("বাছাই করুন", "pick") else displayDate(dob)))
                }
                OutlinedTextField(phone, { phone = it.take(20) }, label = { Text(tr("ফোন নম্বর", "Phone")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it.take(200) }, label = { Text(tr("ঠিকানা", "Address")) }, modifier = Modifier.fillMaxWidth().height(90.dp))
                OutlinedTextField(photoUrl, { photoUrl = it.take(500) }, label = { Text(tr("ছবির URL (ঐচ্ছিক)", "Photo URL (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { pickDistrict = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("জেলা: ", "District: ") + (if (district.isBlank()) tr("বাছাই করুন", "choose") else if (district == OTHER_CAT) tr("অন্যান্য", "Other") else district))
                }
                if (district == OTHER_CAT || (district.isNotBlank() && DISTRICTS.none { it.first == district })) {
                    CustomWordField(tr("জেলার নাম লিখুন", "Type the district name"), if (district == OTHER_CAT) "" else district) { district = it }
                }
                OutlinedButton(onClick = { pickOccupation = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("পেশা: ", "Occupation: ") + (if (occupation.isBlank()) tr("বাছাই করুন", "choose") else if (occupation == OTHER_CAT) tr("অন্যান্য", "Other") else occupation))
                }
                if (occupation == OTHER_CAT || (occupation.isNotBlank() && OCCUPATIONS.none { it.first == occupation })) {
                    CustomWordField(tr("পেশার নাম লিখুন", "Type the occupation"), if (occupation == OTHER_CAT) "" else occupation) { occupation = it }
                }
                CompletenessBar(
                    profileChecks(name, username, photoUrl, dob, phone, address, district, occupation),
                    showMissing = true
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = {
                val body = buildJsonObject {
                    put("name", name.trim())
                    put("username", if (u.isEmpty()) JsonNull else JsonPrimitive(u))
                    put("date_of_birth", if (dob.isBlank()) JsonNull else JsonPrimitive(dob))
                    put("phone", phone.trim())
                    put("address", address.trim())
                    if (photoUrl.trim() != p?.str("avatar_url").orEmpty()) put("avatar_url", photoUrl.trim())
                    put("district", if (district.isBlank()) JsonNull else JsonPrimitive(district.trim()))
                    put("occupation", if (occupation.isBlank()) JsonNull else JsonPrimitive(occupation.trim()))
                }
                vm.saveProfile(body)
                onDismiss()
            }) { Text(tr("সেভ", "Save")) }
        },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )

    if (pickDistrict) {
        SearchPickerSheet(tr("জেলা বাছাই", "Choose district"), DISTRICTS, district, { pickDistrict = false }) { picked ->
            district = picked
            pickDistrict = false
        }
    }
    if (pickOccupation) {
        SearchPickerSheet(tr("পেশা বাছাই", "Choose occupation"), OCCUPATIONS, occupation, { pickOccupation = false }) { picked ->
            occupation = picked
            pickOccupation = false
        }
    }
    if (pickingDob) {
        DatePickerSheet(
            initial = runCatching { LocalDate.parse(dob) }.getOrDefault(LocalDate.of(2000, 1, 1)),
            title = tr("জন্ম তারিখ", "Birth date"),
            onDismiss = { pickingDob = false },
            onPick = { dob = it.toString() }
        )
    }
}

/** Square 512 px JPEG from a gallery image. Returns null when the image cannot be read. */
private fun compressAvatar(ctx: Context, uri: Uri): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    if (largest <= 0) return null
    var sample = 1
    while (largest / sample > 1024) sample *= 2
    val decode = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) } ?: return null
    val side = minOf(bmp.width, bmp.height)
    val cropped = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
    return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}

/** Typed confirmation for wiping this phone. Warns when offline changes have not reached the server yet. */
@Composable
private fun ResetDialog(pendingOffline: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var word by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("লোকাল ডেটা মুছবেন?", "Erase local data?"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("এই ফোনের PIN, লক, পছন্দ, অফলাইন নোট ও গণনা এবং লগইন মুছে যাবে। সার্ভারের ডেটা থাকবে, আবার লগইন করলে ফিরে আসবে।",
                    "This phone's PIN, lock, preferences, offline notes and counts, and sign-in will be deleted. Server data stays, and comes back when you sign in again."),
                    fontSize = 13.sp)
                if (pendingOffline > 0) {
                    Text(tr("সতর্কতা: ${bnDigits(pendingOffline.toString())}টি অফলাইন পরিবর্তন এখনো সার্ভারে যায়নি। মুছলে সেগুলো হারিয়ে যাবে।",
                        "Warning: $pendingOffline offline change(s) have not reached the server. They will be lost."),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(word, { word = it }, label = { Text(tr("নিশ্চিত করতে RESET লিখুন", "Type RESET to confirm")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = word == "RESET", onClick = onConfirm) {
                Text(tr("মুছুন", "Erase"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}

/** Two-step confirmation: type DELETE and the account password. */
@Composable
fun DeleteAccountDialog(email: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var word by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("অ্যাকাউন্ট মুছবেন?", "Delete account?"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("এটি স্থায়ী। সব ডেটা মুছে যাবে এবং ফিরিয়ে আনা যাবে না। নিশ্চিত করতে নিচে DELETE লিখুন এবং পাসওয়ার্ড দিন।", "This is permanent. All data will be erased and cannot be restored. To confirm, type DELETE below and enter your password."), fontSize = 13.sp)
                OutlinedTextField(word, { word = it }, label = { Text(tr("DELETE লিখুন", "Type DELETE")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(tr("পাসওয়ার্ড", "Password")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = word == "DELETE" && password.isNotEmpty(), onClick = { onConfirm(password) }) {
                Text(tr("স্থায়ীভাবে মুছুন", "Delete permanently"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}
