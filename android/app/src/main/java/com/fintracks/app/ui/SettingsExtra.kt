package com.fintracks.app.ui

import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.AccountInfo
import com.fintracks.app.data.SupabaseException
import com.fintracks.app.data.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import androidx.compose.ui.graphics.Color

// ---------- Profile completeness ----------

/** The eight profile fields that count toward the completeness percentage, with whether each one is filled. */
fun profileChecks(
    name: String, username: String, avatar: String, dob: String, phone: String, address: String, district: String, occupation: String
): List<Pair<String, Boolean>> = listOf(
    tr("নাম", "Name") to name.isNotBlank(),
    tr("ইউজারনেম", "Username") to username.isNotBlank(),
    tr("ছবি", "Photo") to avatar.isNotBlank(),
    tr("জন্ম তারিখ", "Birth date") to dob.isNotBlank(),
    tr("ফোন", "Phone") to phone.isNotBlank(),
    tr("ঠিকানা", "Address") to address.isNotBlank(),
    tr("জেলা", "District") to district.isNotBlank(),
    tr("পেশা", "Occupation") to occupation.isNotBlank()
)

fun profileChecks(p: JsonObject?): List<Pair<String, Boolean>> = profileChecks(
    name = p?.str("name").orEmpty(),
    username = p?.str("username").orEmpty(),
    avatar = p?.str("avatar_url").orEmpty(),
    dob = p?.str("date_of_birth").orEmpty(),
    phone = p?.str("phone").orEmpty(),
    address = p?.str("address").orEmpty(),
    district = p?.str("district").orEmpty(),
    occupation = p?.str("occupation").orEmpty()
)

fun completenessPercent(checks: List<Pair<String, Boolean>>): Int =
    if (checks.isEmpty()) 0 else checks.count { it.second } * 100 / checks.size

/** Progress bar with the percentage and the names of the empty fields. */
@Composable
fun CompletenessBar(checks: List<Pair<String, Boolean>>, showMissing: Boolean) {
    val pct = completenessPercent(checks)
    val missing = checks.filter { !it.second }.map { it.first }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("প্রোফাইল সম্পূর্ণতা", "Profile completeness"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${bnDigits(pct.toString())}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        if (showMissing && missing.isNotEmpty()) {
            Text(
                tr("ফাঁকা: ", "Empty: ") + missing.joinToString(", "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Districts & occupations ----------

/** Bangladesh's 64 districts: value is the Bengali name that gets saved; the second part is the English label. */
val DISTRICTS: List<Pair<String, String>> = listOf(
    "বরগুনা" to "Barguna", "বরিশাল" to "Barishal", "ভোলা" to "Bhola", "ঝালকাঠি" to "Jhalokathi", "পটুয়াখালী" to "Patuakhali", "পিরোজপুর" to "Pirojpur",
    "বান্দরবান" to "Bandarban", "ব্রাহ্মণবাড়িয়া" to "Brahmanbaria", "চাঁদপুর" to "Chandpur", "চট্টগ্রাম" to "Chattogram", "কুমিল্লা" to "Cumilla",
    "কক্সবাজার" to "Cox's Bazar", "ফেনী" to "Feni", "খাগড়াছড়ি" to "Khagrachhari", "লক্ষ্মীপুর" to "Lakshmipur", "নোয়াখালী" to "Noakhali", "রাঙ্গামাটি" to "Rangamati",
    "ঢাকা" to "Dhaka", "ফরিদপুর" to "Faridpur", "গাজীপুর" to "Gazipur", "গোপালগঞ্জ" to "Gopalganj", "কিশোরগঞ্জ" to "Kishoreganj", "মাদারীপুর" to "Madaripur",
    "মানিকগঞ্জ" to "Manikganj", "মুন্সীগঞ্জ" to "Munshiganj", "নারায়ণগঞ্জ" to "Narayanganj", "নরসিংদী" to "Narsingdi", "রাজবাড়ী" to "Rajbari",
    "শরীয়তপুর" to "Shariatpur", "টাঙ্গাইল" to "Tangail",
    "বাগেরহাট" to "Bagerhat", "চুয়াডাঙ্গা" to "Chuadanga", "যশোর" to "Jashore", "ঝিনাইদহ" to "Jhenaidah", "খুলনা" to "Khulna", "কুষ্টিয়া" to "Kushtia",
    "মাগুরা" to "Magura", "মেহেরপুর" to "Meherpur", "নড়াইল" to "Narail", "সাতক্ষীরা" to "Satkhira",
    "জামালপুর" to "Jamalpur", "ময়মনসিংহ" to "Mymensingh", "নেত্রকোণা" to "Netrokona", "শেরপুর" to "Sherpur",
    "বগুড়া" to "Bogura", "জয়পুরহাট" to "Joypurhat", "নওগাঁ" to "Naogaon", "নাটোর" to "Natore", "চাঁপাইনবাবগঞ্জ" to "Chapai Nawabganj", "পাবনা" to "Pabna",
    "রাজশাহী" to "Rajshahi", "সিরাজগঞ্জ" to "Sirajganj",
    "দিনাজপুর" to "Dinajpur", "গাইবান্ধা" to "Gaibandha", "কুড়িগ্রাম" to "Kurigram", "লালমনিরহাট" to "Lalmonirhat", "নীলফামারী" to "Nilphamari",
    "পঞ্চগড়" to "Panchagarh", "রংপুর" to "Rangpur", "ঠাকুরগাঁও" to "Thakurgaon",
    "হবিগঞ্জ" to "Habiganj", "মৌলভীবাজার" to "Moulvibazar", "সুনামগঞ্জ" to "Sunamganj", "সিলেট" to "Sylhet", OTHER_CAT to "Other"
)

/** Common occupations. "অন্যান্য" opens a text box for the user's own word. */
val OCCUPATIONS: List<Pair<String, String>> = listOf(
    "ছাত্র/ছাত্রী" to "Student", "চাকরিজীবী" to "Employee", "ব্যবসায়ী" to "Business owner", "দোকানদার" to "Shopkeeper",
    "শিক্ষক" to "Teacher", "চিকিৎসক" to "Doctor", "নার্স" to "Nurse", "প্রকৌশলী" to "Engineer", "আইনজীবী" to "Lawyer",
    "সরকারি কর্মকর্তা" to "Government officer", "ব্যাংক কর্মকর্তা" to "Bank officer", "সাংবাদিক" to "Journalist",
    "ফ্রিল্যান্সার" to "Freelancer", "কৃষক" to "Farmer", "চালক" to "Driver", "গৃহিণী" to "Homemaker", "অবসরপ্রাপ্ত" to "Retired",
    OTHER_CAT to "Other"
)

/** Searchable bottom sheet. Picking a value hands it back; the caller decides what "অন্যান্য" opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPickerSheet(title: String, options: List<Pair<String, String>>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val shown = options.filter { (value, en) -> q.isEmpty() || value.contains(q, true) || en.contains(q, true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(tr("খুঁজুন", "Search")) },
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(Modifier.fillMaxWidth().height(420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.first }) { (value, en) ->
                    val on = value == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onPick(value) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr(value, en), modifier = Modifier.weight(1f), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                        if (on) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** A text box for the user's own word, shown when "অন্যান্য" is picked. Empty keeps "অন্যান্য". */
@Composable
fun CustomWordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(40).ifBlank { OTHER_CAT }) },
        singleLine = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

// ---------- Helpers: copy, share, links ----------

fun copyText(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, tr("কপি হয়েছে", "Copied"), Toast.LENGTH_SHORT).show()
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, tr("লিংক খোলা যায়নি", "Could not open the link"), Toast.LENGTH_SHORT).show() }
}

fun shareApp(ctx: Context) {
    val text = tr(
        "FinTrack দিয়ে আয়-ব্যয় ও বাজেট সহজে লিখুন: ",
        "Track income, spending and budgets easily with FinTrack: "
    ) + WEBSITE_URL
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(intent, tr("অ্যাপ শেয়ার করুন", "Share the app")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun sendSupportMail(ctx: Context) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL")).apply {
        putExtra(Intent.EXTRA_SUBJECT, "FinTrack support")
    }
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, tr("মেইল অ্যাপ পাওয়া যায়নি", "No mail app found"), Toast.LENGTH_SHORT).show() }
}

const val WEBSITE_URL = "https://shohagvaii.github.io/FinTracks/"
const val PRIVACY_URL = "https://shohagvaii.github.io/FinTracks/privacy.html"
const val TERMS_URL = "https://shohagvaii.github.io/FinTracks/terms.html"
const val SUPPORT_EMAIL = "shohagvaiiofc@gmail.com"

// ---------- Rows ----------

/** A settings row with a title, an optional line of text, and a chevron. */
@Composable
fun SettingLink(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Log-out row: its own soft rose band, placed at the very bottom of Settings. */
@Composable
fun LogoutRow(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Rose500.copy(alpha = 0.12f))
            .border(1.dp, Rose500.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(tr("লগআউট", "Log out"), color = readableOn(Rose500), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

// ---------- Pages ----------

/** Login and security: account box, this phone's lock box, and a collapsed danger zone at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSecurityPage(vm: AppViewModel, lockOn: Boolean, hasPin: Boolean, bioOn: Boolean, bioHardware: Boolean, onBack: () -> Unit, onOpenSecurity: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<AccountInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var changing by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf<String?>(null) }
    var appLock by remember { mutableStateOf(lockOn) }
    var pinSet by remember { mutableStateOf(hasPin) }
    var bio by remember { mutableStateOf(bioOn) }
    var timeout by remember { mutableStateOf(AppLock.timeoutSeconds(ctx).toString()) }
    var showSetPin by remember { mutableStateOf(false) }
    var showOldPin by remember { mutableStateOf(false) }
    var showDisable by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var dangerOpen by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(reload) {
        try {
            info = vm.accountInfo()
            loadError = null
        } catch (e: Exception) {
            loadError = e.message ?: tr("তথ্য আনা যায়নি", "Could not load details")
        }
    }
    val google = info?.providers?.firstOrNull { it.first == "google" }
    val hasEmailLogin = info?.providers?.any { it.first == "email" } == true
    val email = info?.email.orEmpty()
    val verified = info?.verified == true

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(modifier = Modifier.pressScale(), onClick = onBack) { Text(tr("‹ ফিরে যান", "‹ Back")) }
        Text(tr("লগইন ও নিরাপত্তা", "Login & security"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        loadError?.let {
            Text(tr("লগইন তথ্য আনা যায়নি: $it", "Could not load login details: $it"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            OutlinedButton(modifier = Modifier.pressScale(), onClick = { reload++ }) { Text(tr("আবার চেষ্টা", "Try again")) }
        }

        // ---- Account ----
        AppCardPlain {
            Text(tr("অ্যাকাউন্ট", "Account"), fontWeight = FontWeight.Bold)
            Text(tr("ইমেইল", "Email"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(email.ifBlank { "—" }, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                if (info != null) {
                    StatusChip(if (verified) tr("যাচাইকৃত", "Verified") else tr("যাচাই হয়নি", "Not verified"))
                }
            }
            HorizontalDivider(color = faint(0.08f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Google", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            info == null -> tr("লোড হচ্ছে…", "Loading…")
                            google != null -> tr("যুক্ত আছে", "Linked")
                            else -> tr("যুক্ত নেই", "Not linked")
                        },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (google != null && hasEmailLogin && (info?.providers?.size ?: 0) > 1) {
                    OutlinedButton(modifier = Modifier.pressScale(), onClick = { confirmUnlink = google.second }) {
                        Text(tr("সরান", "Remove"))
                    }
                }
            }
            HorizontalDivider(color = faint(0.08f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("পাসওয়ার্ড বদলান", "Change password"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(modifier = Modifier.pressScale(), onClick = { changing = true }, enabled = info != null) {
                    Text(tr("বদলান", "Change"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("পাসওয়ার্ড রিসেট", "Password reset"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(
                    modifier = Modifier.pressScale(),
                    onClick = {
                        vm.resetPassword(email)
                        Toast.makeText(ctx, tr("রিসেট ইমেইল পাঠানো হয়েছে", "Reset email sent"), Toast.LENGTH_SHORT).show()
                    },
                    enabled = email.isNotBlank()
                ) { Text(tr("ইমেইল পাঠান", "Send email")) }
            }
        }

        // ---- This phone's lock ----
        AppCardPlain {
            Text(tr("এই ফোনের লক", "Phone lock"), fontWeight = FontWeight.Bold)
            SwitchRow(tr("অ্যাপ লক", "App lock"), appLock) { on ->
                if (on) {
                    if (!pinSet) {
                        Toast.makeText(ctx, tr("আগে PIN সেট করুন", "Set a PIN first"), Toast.LENGTH_SHORT).show()
                    } else {
                        appLock = true
                        AppLock.enable(ctx)
                    }
                } else {
                    showDisable = true
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PIN", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(modifier = Modifier.pressScale(), onClick = { if (pinSet) showOldPin = true else showSetPin = true }) {
                    Text(if (pinSet) tr("PIN বদলান", "Change PIN") else tr("PIN সেট করুন", "Set PIN"))
                }
            }
            Text(tr("অটো-লক", "Auto-lock"), fontWeight = FontWeight.SemiBold)
            ChoiceChips(
                listOf("0" to tr("সাথে সাথে", "Immediately"), "60" to tr("১ মিনিট", "1 minute"), "300" to tr("৫ মিনিট", "5 minutes")),
                timeout
            ) {
                timeout = it
                AppLock.setTimeout(ctx, it.toInt())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("ফিঙ্গারপ্রিন্ট / ফেস", "Fingerprint / face"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(
                    checked = bio,
                    enabled = appLock && bioHardware,
                    onCheckedChange = {
                        bio = it
                        AppLock.setBiometric(ctx, it)
                    }
                )
            }
        }

        // ---- Danger zone: collapsed by default, at the bottom of the page ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Rose500.copy(alpha = 0.10f))
                .border(1.dp, Rose500.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { dangerOpen = !dangerOpen },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("বিপজ্জনক এলাকা", "Danger zone"), fontWeight = FontWeight.Bold, color = Rose500, modifier = Modifier.weight(1f))
                Text(if (dangerOpen) "˄" else "˅", color = Rose500, fontSize = 18.sp)
            }
            if (dangerOpen) {
                Text(tr("লোকাল ডেটা মুছুন", "Erase local data"), fontWeight = FontWeight.SemiBold)
                Button(
                    modifier = Modifier.pressScale(),
                    onClick = { showReset = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(tr("লোকাল ডেটা মুছুন", "Erase local data")) }
                Text(tr("অ্যাকাউন্ট মুছুন", "Delete account"), fontWeight = FontWeight.SemiBold)
                Button(
                    modifier = Modifier.pressScale(),
                    onClick = { showDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(tr("অ্যাকাউন্ট মুছুন", "Delete account")) }
            }
        }
    }

    if (changing) {
        ChangePasswordDialog(
            email = email,
            onDismiss = { changing = false },
            onDone = { changing = false; Toast.makeText(ctx, tr("পাসওয়ার্ড বদলানো হয়েছে", "Password changed"), Toast.LENGTH_SHORT).show() },
            vm = vm
        )
    }
    confirmUnlink?.let { identityId ->
        AlertDialog(
            onDismissRequest = { confirmUnlink = null },
            title = { Text(tr("Google সরাবেন?", "Remove Google?"), fontWeight = FontWeight.Bold) },
            text = { Text(tr("এই অ্যাকাউন্টে Google দিয়ে আর লগইন করা যাবে না।", "You will no longer be able to sign in with Google.")) },
            confirmButton = {
                TextButton(modifier = Modifier.pressScale(), onClick = {
                    confirmUnlink = null
                    scope.launch {
                        try {
                            vm.unlinkIdentity(identityId)
                            Toast.makeText(ctx, tr("Google সরানো হয়েছে", "Google removed"), Toast.LENGTH_SHORT).show()
                            reload++
                        } catch (e: Exception) {
                            Toast.makeText(ctx, tr("সরানো যায়নি: ${e.message}", "Could not remove: ${e.message}"), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(tr("সরান", "Remove"), color = readableOn(Rose500)) }
            },
            dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = { confirmUnlink = null }) { Text(tr("বাতিল", "Cancel")) } }
        )
    }
    if (showSetPin) {
        PinSetupDialog(onDismiss = { showSetPin = false }) { pin ->
            AppLock.setPin(ctx, pin)
            pinSet = true
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
            appLock = false
            showDisable = false
        }
    }
    if (showReset) {
        ResetDialog(
            pendingOffline = vm.notes.pendingCount.value + vm.cashCounts.pendingCount.value,
            onDismiss = { showReset = false },
            onConfirm = {
                showReset = false
                vm.resetLocal()
                Toast.makeText(ctx, tr("লোকাল ডেটা মুছে ফেলা হয়েছে", "Local data erased"), Toast.LENGTH_LONG).show()
            }
        )
    }
    if (showDelete) {
        DeleteAccountDialog(
            email = email,
            onDismiss = { showDelete = false },
            onConfirm = { password -> vm.deleteAccount(password); showDelete = false }
        )
    }
}

/** Small status label, e.g. the verified state of the email. */
@Composable
private fun StatusChip(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(faint(0.08f)).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun AppCardPlain(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

/** Current password, new password (6+ characters, as the server requires), and confirmation. */
@Composable
private fun ChangePasswordDialog(email: String, vm: AppViewModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    val localError = when {
        current.isEmpty() -> null
        new.length < 6 -> tr("নতুন পাসওয়ার্ড কমপক্ষে ৬ অক্ষর", "New password must be at least 6 characters")
        new != confirm -> tr("নতুন পাসওয়ার্ড দুবার মেলেনি", "The new passwords don't match")
        new == current -> tr("নতুন পাসওয়ার্ড আগেরটির মতো হতে পারবে না", "The new password must differ from the current one")
        else -> null
    }
    val canSave = !busy && current.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty() && localError == null && email.isNotBlank()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("পাসওয়ার্ড বদলান", "Change password"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(current, { current = it }, label = { Text(tr("বর্তমান পাসওয়ার্ড", "Current password")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(new, { new = it }, label = { Text(tr("নতুন পাসওয়ার্ড", "New password")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(confirm, { confirm = it }, label = { Text(tr("নতুন পাসওয়ার্ড আবার", "Confirm new password")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                (localError ?: serverError)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = {
                busy = true
                serverError = null
                scope.launch {
                    try {
                        vm.changePassword(email, current, new)
                        onDone()
                    } catch (e: SupabaseException) {
                        serverError = tr("বদলানো যায়নি: ", "Could not change: ") + e.message
                    } catch (e: Exception) {
                        serverError = tr("বদলানো যায়নি: ", "Could not change: ") + (e.message ?: "")
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) tr("হচ্ছে…", "Working…") else tr("বদলান", "Change")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}

private data class Faq(val q: String, val qEn: String, val a: String, val aEn: String)

private val FAQS = listOf(
    Faq(
        "টুলসের ক্রম বদলাবো কীভাবে?", "How do I change the order of the tools?",
        "১) টুলস স্ক্রিনে যান। ২) যে টাইলটি সরাতে চান সেটি কিছুক্ষণ চেপে ধরুন। ৩) না ছেড়ে পছন্দের জায়গায় টেনে নিয়ে ছাড়ুন। ৪) নতুন ক্রম নিজে থেকেই এই ফোনে সেভ থাকে, অ্যাপ বন্ধ করলেও।",
        "1) Go to the Tools screen. 2) Press and hold the tile you want to move. 3) Drag it to the place you like, then let go. 4) The new order is saved on this phone automatically, even after you close the app."
    ),
    Faq(
        "অফলাইনে কী কী করা যায়?", "What works offline?",
        "লেনদেন, বাকি, ধার, বাজেট, লক্ষ্য, সাবস্ক্রিপশন, অ্যাকাউন্ট, ক্যাটাগরি ও প্রোফাইল অফলাইনে দেখা ও যোগ/বদলানো যায়। ইন্টারনেট এলে সব নিজে থেকে সার্ভারে যায়।",
        "Transactions, dues, debts, budgets, goals, subscriptions, accounts, categories and profile can be viewed and added or changed offline. When the internet returns, everything syncs by itself."
    ),
    Faq(
        "\"সিঙ্ক বাকি\" মানে কী?", "What does \"sync pending\" mean?",
        "যে পরিবর্তনগুলো এখনো সার্ভারে যায়নি তার সংখ্যা। ইন্টারনেট এলে নিজে থেকে যাবে, অথবা সেটিংসে \"এখনই সিঙ্ক করুন\" চাপুন।",
        "The number of changes not yet sent to the server. They go once you're online, or tap \"Sync now\" in Settings."
    ),
    Faq(
        "AI সহকারী কখন কাজ করে?", "When does the AI assistant work?",
        "AI উত্তরের জন্য ইন্টারনেট লাগে। ব্যর্থ হলে আসল কারণ দেখানো হয় এবং আবার চেষ্টা করা যায়। লোকাল ডেটার উত্তর আলাদা বোতামে পাওয়া যায়।",
        "AI answers need the internet. If one fails, the real reason is shown and you can retry. Answers from your local data are behind a separate button."
    ),
    Faq(
        "ফেক মোড কী?", "What is demo mode?",
        "ফেক মোড চালু থাকলে স্ক্রিনে টাকার অঙ্ক বদলে দেখায়। আসল ডেটা বদলায় না। রসিদ ও ভাউচারে আসল অঙ্ক থাকে।",
        "With demo mode on, amounts on screen are replaced with fake numbers. Your real data doesn't change. Receipts and vouchers always show the real amounts."
    ),
    Faq(
        "লেনদেনের ছবি কোথায় থাকে?", "Where are transaction photos kept?",
        "ছবি আপনার নিজের অ্যাকাউন্টের ব্যক্তিগত স্টোরেজে থাকে। অন্য কোনো ব্যবহারকারী দেখতে পারে না। অফলাইনে যোগ করলে ইন্টারনেট এলে আপলোড হয়।",
        "Photos are stored in your own private storage space. No other user can see them. If you add one offline, it uploads once you're back online."
    ),
    Faq(
        "ব্যাকআপ কীভাবে নেব?", "How do I back up?",
        "সেটিংসের \"ব্যাকআপ\" থেকে JSON ফাইল তৈরি করে শেয়ার করা যায়। ব্যাকআপ অফলাইনেও তৈরি হয়। ফিরিয়ে আনলে বিদ্যমান ডেটা মোছে না।",
        "Make a JSON file from \"Backup\" in Settings and share it. Backups are created offline. Restoring doesn't delete existing data."
    ),
    Faq(
        "ডেটা কি সুরক্ষিত?", "Is my data safe?",
        "প্রাইভেসি পলিসিতে কোন ডেটা রাখা হয় ও কীভাবে মোছা যায় তা লেখা আছে। সেটিংসের \"প্রাইভেসি পলিসি\" লিংকে দেখুন।",
        "The privacy policy explains what data is kept and how to delete it. See \"Privacy policy\" in Settings."
    )
)

/** Offline Q&A page. The text is built into the app, so it works without internet. */
@Composable
fun FaqPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(modifier = Modifier.pressScale(), onClick = onBack) { Text(tr("‹ ফিরে যান", "‹ Back")) }
            Text(tr("প্রশ্ন-উত্তর", "Questions & answers"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(tr("এই পেজ অফলাইনেও পড়া যায়।", "This page works offline too."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(FAQS) { f ->
            AppCardPlain {
                Text(tr(f.q, f.qEn), fontWeight = FontWeight.Bold)
                Text(tr(f.a, f.aEn), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
