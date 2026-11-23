package com.fintracks.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.fintracks.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer signed build on GitHub Releases. [code] is the CI run number, the same number as versionCode. */
data class UpdateInfo(val versionName: String, val code: Int, val sizeBytes: Long, val notes: List<String>, val apkUrl: String)

object UpdateCenter {
    private const val LATEST_API = "https://api.github.com/repos/ShohagVaii/FinTracks/releases/latest"
    private const val APK_NAME = "FinTrack.apk"
    private const val PREFS = "fintrack_update"
    private const val K_SKIP = "skipped_code"
    private val json = Json { ignoreUnknownKeys = true }

    /** The update the popup should show now; null when there is none. */
    var pending by mutableStateOf<UpdateInfo?>(null)

    /** Latest release from GitHub, or null when it cannot be read or has no FinTrack.apk. */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = URL(LATEST_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return@withContext null
            val root = json.parseToJsonElement(conn.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.content ?: return@withContext null // e.g. v1.1.0-117
            val code = tag.substringAfterLast('-').toIntOrNull() ?: return@withContext null
            val versionName = tag.removePrefix("v").substringBeforeLast('-')
            val asset = root["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content == APK_NAME } ?: return@withContext null
            val apkUrl = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@withContext null
            // The CI body is a fixed line; it is dropped so only real notes are shown.
            val notes = (root["body"]?.jsonPrimitive?.content ?: "").lines()
                .map { it.trim().trimStart('#', '-', '*', ' ') }
                .filter { it.isNotEmpty() && !it.startsWith("Signed release build") }
                .take(4)
            UpdateInfo(versionName, code, asset["size"]?.jsonPrimitive?.long ?: 0L, notes, apkUrl)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(info: UpdateInfo): Boolean = info.code > BuildConfig.VERSION_CODE

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Check when the app opens. A version the user chose "পরে" for does not show again. */
    suspend fun checkOnStart(ctx: Context) {
        val info = fetchLatest() ?: return
        if (isNewer(info) && prefs(ctx).getInt(K_SKIP, -1) != info.code) pending = info
    }

    /** Settings row: shows the popup whenever a newer version exists. */
    fun manualCheck(ctx: Context) {
        MainScope().launch {
            val info = fetchLatest()
            when {
                info == null -> Toast.makeText(ctx, tr("আপডেট যাচাই করা যায়নি", "Could not check for updates"), Toast.LENGTH_SHORT).show()
                isNewer(info) -> pending = info
                else -> Toast.makeText(ctx, tr("সর্বশেষ ভার্সন ব্যবহার করছেন", "You have the latest version"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun later(ctx: Context, info: UpdateInfo) {
        prefs(ctx).edit().putInt(K_SKIP, info.code).apply()
        pending = null
    }

    /** Downloads the APK into the app cache and reports progress from 0 to 1. */
    suspend fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val file = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, APK_NAME)
        file.delete()
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done.toFloat() / total).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) { onProgress(p) }
                        }
                    }
                }
            }
            file
        } finally {
            conn.disconnect()
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where "unknown apps" is allowed for FinTrack. */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the APK to the system installer; the user confirms there. */
    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Put once at the app root: checks on open and shows the popup when a newer build exists. */
@Composable
fun UpdateHost() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { UpdateCenter.checkOnStart(ctx) }
    UpdatePrompt()
}

@Composable
private fun UpdatePrompt() {
    val info = UpdateCenter.pending ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember(info.code) { mutableStateOf<Float?>(null) }
    val p = progress

    Dialog(
        onDismissRequest = { if (p == null) UpdateCenter.later(ctx, info) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, faint(0.1f), RoundedCornerShape(28.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AuLogoMark(Modifier.size(56.dp))
            Text(tr("নতুন ভার্সন", "New version"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val sizeMb = "%.1f MB".format(info.sizeBytes / 1_048_576.0)
            Text(
                bnDigits("${info.versionName} (${info.code}) · $sizeMb"),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            info.notes.forEach { line ->
                Text(line, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }

            if (p != null) {
                LinearProgressIndicator(progress = p, modifier = Modifier.fillMaxWidth())
                Text(bnDigits("${(p * 100).toInt()}%"), fontSize = 13.sp)
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Sky500, Blue600)))
                        .clickable {
                            if (!UpdateCenter.canInstall(ctx)) {
                                UpdateCenter.openInstallPermission(ctx)
                                return@clickable
                            }
                            progress = 0f
                            scope.launch {
                                try {
                                    val file = UpdateCenter.download(ctx, info) { progress = it }
                                    UpdateCenter.install(ctx, file)
                                    UpdateCenter.pending = null
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, tr("ডাউনলোড ব্যর্থ হয়েছে", "Download failed"), Toast.LENGTH_LONG).show()
                                } finally {
                                    progress = null
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(tr("এখনই আপডেট", "Update now"), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth().pressScale(), onClick = { UpdateCenter.later(ctx, info) }) {
                    Text(tr("পরে", "Later"))
                }
            }
        }
    }
}
