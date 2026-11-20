package com.fintracks.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/** Settings: profile, theme, colour, currency, privacy, security (app lock), export, sign-out. */
@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(state.name) { mutableStateOf(state.name) }
    var lockOn by remember { mutableStateOf(AppLock.isEnabled(ctx)) }
    var timeout by remember { mutableStateOf(AppLock.timeoutSeconds(ctx).toString()) }
    var exporting by remember { mutableStateOf(false) }

    Page {
        AppCard {
            Text("প্রোফাইল", fontWeight = FontWeight.Bold)
            TextIn("নাম", name, { name = it })
            Button(onClick = { vm.saveProfile(row("name" to name)) }, enabled = name.isNotBlank() && name != state.name) { Text("নাম সেভ") }
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
            SwitchRow("অ্যাপ লক (ফিঙ্গারপ্রিন্ট / ফেস)", lockOn) {
                lockOn = it
                AppLock.setEnabled(ctx, it)
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
            Text("লেনদেনের তালিকা Excel, PDF বা CSV হিসেবে শেয়ার করুন।", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Export.Format.XLSX to "Excel", Export.Format.PDF to "PDF", Export.Format.CSV to "CSV").forEach { (fmt, label) ->
                    OutlinedButton(enabled = !exporting, onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) { Export.create(ctx, fmt, state) }
                                Export.share(ctx, file, fmt)
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
            Text("অ্যাকাউন্ট", fontWeight = FontWeight.Bold)
            Text(state.profile?.str("email").orEmpty().ifEmpty { "ইমেইল পাওয়া যায়নি" }, fontSize = 12.sp)
            OutlinedButton(onClick = { vm.signOut() }) { Text("লগআউট") }
        }
    }
}
