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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.PI

/** A newer signed build on GitHub Releases. [code] is the CI run number, the same number as versionCode. */
data class UpdateInfo(val versionName: String, val code: Int, val sizeBytes: Long, val notes: List<String>, val apkUrl: String)

object UpdateCenter {
    private const val LATEST_API = "https://api.github.com/repos/ShohagVaii/FinTracks/releases/latest"
    private const val APK_NAME = "FinTrack.apk"
    private val json = Json { ignoreUnknownKeys = true }

    /** What the popup shows now. Null when there is nothing to show. */
    var phase by mutableStateOf<Phase?>(null)

    /** Whether the popup is on screen. Hiding it does not stop a download. */
    var visible by mutableStateOf(false)

    sealed class Phase(val info: UpdateInfo) {
        class Available(info: UpdateInfo) : Phase(info)
        class Downloading(info: UpdateInfo, val progress: Float) : Phase(info)
        class Ready(info: UpdateInfo, val file: File) : Phase(info)
        class Failed(info: UpdateInfo, val reason: String) : Phase(info)
    }

    /** App-level scope: a download keeps running when the popup is hidden. */
    private val scope = MainScope()
    private var job: Job? = null
    private const val CHANNEL = "fintrack_update"
    private const val NOTIFY_ID = 4420

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

    /** Shows the popup for [info]. A download already running or finished for this build is shown as it is. */
    private fun offer(info: UpdateInfo) {
        val cur = phase
        if (cur != null && cur.info.code == info.code && cur !is Phase.Available) {
            visible = true
            return
        }
        phase = Phase.Available(info)
        visible = true
    }

    /** Check when the app opens. Shown on every open until the update is installed. */
    suspend fun checkOnStart(ctx: Context) {
        val info = fetchLatest() ?: return
        if (isNewer(info)) offer(info)
    }

    /** Settings row: shows the popup whenever a newer version exists. */
    fun manualCheck(ctx: Context) {
        MainScope().launch {
            val info = fetchLatest()
            when {
                info == null -> Toast.makeText(ctx, tr("আপডেট যাচাই করা যায়নি", "Could not check for updates"), Toast.LENGTH_SHORT)
                isNewer(info) -> offer(info)
                else -> Toast.makeText(ctx, tr("সর্বশেষ ভার্সন ব্যবহার করছেন", "You have the latest version"), Toast.LENGTH_SHORT)
            }
        }
    }

    /** "পরে" and × only hide the popup for this app open. */
    fun later() {
        visible = false
    }

    /** Starts the download in the background. Progress goes to the popup and to the notification. */
    fun start(ctx: Context, info: UpdateInfo) {
        if (job?.isActive == true) {
            visible = true
            return
        }
        if (!canInstall(ctx)) {
            openInstallPermission(ctx)
            return
        }
        phase = Phase.Downloading(info, 0f)
        visible = true
        var lastShown = -1
        job = scope.launch {
            try {
                val file = download(ctx, info) { p ->
                    phase = Phase.Downloading(info, p)
                    val pct = (p * 100).toInt()
                    if (pct / 5 != lastShown / 5) {
                        lastShown = pct
                        showNotice(ctx, tr("ডাউনলোড চলছে · ", "Downloading · ") + bnDigits("$pct%"), pct)
                    }
                }
                phase = Phase.Ready(info, file)
                if (visible) return@launch
                // The popup was closed: continue to the installer. Android shows its own confirmation once.
                // If the system refuses to open it from the background, the notification opens it on tap.
                val opened = runCatching { install(ctx, file) }.isSuccess
                if (opened) {
                    finish(ctx)
                } else {
                    showNotice(ctx, tr("আপডেট প্রস্তুত, খুলে ইনস্টল করুন", "Update ready, open to install"), null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = Phase.Failed(info, e.message ?: tr("অজানা সমস্যা", "Unknown error"))
                visible = true
                cancelNotice(ctx)
            }
        }
    }

    /** Stops a running download and removes the partial file. */
    fun cancel(ctx: Context) {
        job?.cancel()
        job = null
        File(File(ctx.cacheDir, "updates"), APK_NAME).delete()
        cancelNotice(ctx)
        phase = null
        visible = false
    }

    /** The installer was opened; the popup is done. */
    fun finish(ctx: Context) {
        cancelNotice(ctx)
        phase = null
        visible = false
    }

    private fun showNotice(ctx: Context, text: String, progress: Int?) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                    NotificationChannel(CHANNEL, tr("আপডেট", "Updates"), NotificationManager.IMPORTANCE_LOW)
                )
            }
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(ctx.applicationInfo.icon)
                .setContentTitle("FinTrack")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(progress != null)
            if (progress != null) b.setProgress(100, progress, false)
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
                b.setContentIntent(PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE))
            }
            NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, b.build())
        } catch (e: SecurityException) {
            // Notifications are switched off for the app; the popup still shows the progress.
        }
    }

    private fun cancelNotice(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIFY_ID)
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

private val UpdateGreen = Color(0xFF22C55E)
private val UpdateCyan = Color(0xFF39C7E0)

@Composable
private fun UpdatePrompt() {
    val ph = UpdateCenter.phase ?: return
    if (!UpdateCenter.visible) return
    val ctx = LocalContext.current
    val reduced = motionReduced(ctx)
    val info = ph.info
    val enter = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val gentle by rememberInfiniteTransition(label = "gentle").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart), label = "gentleT"
    )
    LaunchedEffect(Unit) {
        if (reduced) {
            enter.snapTo(1f); pop.snapTo(1f); burst.snapTo(1f)
        } else {
            enter.animateTo(1f, tween(350))
            pop.animateTo(1f, tween(700))
            burst.animateTo(1f, tween(1600))
        }
    }

    Dialog(
        onDismissRequest = { UpdateCenter.later() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxWidth(0.9f).graphicsLayer {
                alpha = enter.value
                scaleX = 0.92f + 0.08f * enter.value
                scaleY = 0.92f + 0.08f * enter.value
            },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier.padding(top = 28.dp).fillMaxWidth().heightIn(max = 600.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, faint(0.1f), RoundedCornerShape(28.dp))
            ) {
                if (!reduced) {
                    if (ph is UpdateCenter.Phase.Ready) {
                        UpdateConfetti(burst.value, true, 60, Modifier.matchParentSize())
                    } else if (ph is UpdateCenter.Phase.Available) {
                        UpdateConfetti(gentle, false, 14, Modifier.matchParentSize())
                    }
                }
                UpdateWave(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp), reduced)
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 22.dp, end = 22.dp, top = 44.dp, bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(tr("নতুন ভার্সন", "New version"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    when (ph) {
                        is UpdateCenter.Phase.Available -> {
                            val sizeMb = "%.1f MB".format(info.sizeBytes / 1_048_576.0)
                            Text(
                                tr("আপনার জন্য নতুন কিছু নিয়ে এসেছি...", "We have something new for you..."),
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                            )
                            Text(
                                bnDigits("${info.versionName} (${info.code}) · $sizeMb"),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            info.notes.take(4).forEach { line ->
                                Text("• $line", fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            GradientButton(tr("🚀 এখনই আপডেট", "🚀 Update now")) { UpdateCenter.start(ctx, info) }
                            OutlinedButton(modifier = Modifier.fillMaxWidth().pressScale(), onClick = { UpdateCenter.later() }) {
                                Text(tr("পরে", "Later"))
                            }
                        }
                        is UpdateCenter.Phase.Downloading -> {
                            val pct = (ph.progress * 100).toInt()
                            Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
                                SegmentRing(ph.progress, Modifier.fillMaxSize())
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = UpdateGreen, modifier = Modifier.size(22.dp))
                                    Text(bnDigits("$pct%"), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            ProcessPill()
                            TextButton(onClick = { UpdateCenter.cancel(ctx) }) { Text(tr("বাতিল", "Cancel")) }
                        }
                        is UpdateCenter.Phase.Ready -> {
                            Box(
                                Modifier.size(64.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                                    .clip(CircleShape).background(UpdateGreen),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
                            }
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                    .background(UpdateGreen.copy(alpha = 0.12f)).padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(tr("আপনার ডাউনলোড সম্পন্ন হয়েছে!", "Your download is complete!"), fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
                                Text(
                                    tr("এখনই আপডেট করুন এবং উপভোগ করুন নতুন অভিজ্ঞতা।", "Update now and enjoy the new experience."),
                                    fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            GradientButton(tr("এখনই আপডেট", "Update now")) {
                                if (!UpdateCenter.canInstall(ctx)) {
                                    UpdateCenter.openInstallPermission(ctx)
                                } else {
                                    UpdateCenter.install(ctx, ph.file)
                                    UpdateCenter.finish(ctx)
                                }
                            }
                            OutlinedButton(modifier = Modifier.fillMaxWidth().pressScale(), onClick = { UpdateCenter.later() }) {
                                Text(tr("পরে", "Later"))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text("FinTrack", color = UpdateCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(" / Always Better...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        is UpdateCenter.Phase.Failed -> {
                            Text(
                                tr("ডাউনলোড ব্যর্থ হয়েছে", "Download failed"),
                                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Rose500, textAlign = TextAlign.Center
                            )
                            Text(ph.reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            GradientButton(tr("আবার চেষ্টা করুন", "Retry")) { UpdateCenter.start(ctx, info) }
                            OutlinedButton(modifier = Modifier.fillMaxWidth().pressScale(), onClick = { UpdateCenter.later() }) {
                                Text(tr("পরে", "Later"))
                            }
                        }
                    }
                }
                IconButton(onClick = { UpdateCenter.later() }, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    Icon(Icons.Default.Close, contentDescription = tr("বন্ধ করুন", "Close"))
                }
            }
            UpdateBadge(Modifier.align(Alignment.TopCenter), reduced)
        }
    }
}

/** Green round badge on the card's top edge, with a white ↗ arrow. It floats gently. */
@Composable
private fun UpdateBadge(modifier: Modifier, reduced: Boolean) {
    val float by rememberInfiniteTransition(label = "badge").animateFloat(
        initialValue = 0f, targetValue = -6f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "badgeY"
    )
    Box(
        modifier.size(60.dp).offset(y = (if (reduced) 0f else float).dp)
            .clip(CircleShape).background(Brush.linearGradient(listOf(UpdateGreen, UpdateCyan))),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(26.dp)) {
            val w = size.width
            val h = size.height
            val sw = 3.dp.toPx()
            drawLine(Color.White, Offset(w * 0.12f, h * 0.88f), Offset(w * 0.88f, h * 0.12f), sw, StrokeCap.Round)
            drawLine(Color.White, Offset(w * 0.88f, h * 0.12f), Offset(w * 0.88f, h * 0.62f), sw, StrokeCap.Round)
            drawLine(Color.White, Offset(w * 0.88f, h * 0.12f), Offset(w * 0.38f, h * 0.12f), sw, StrokeCap.Round)
        }
    }
}

/** Three slow, light green waves along the bottom of the card. */
@Composable
private fun UpdateWave(modifier: Modifier, reduced: Boolean) {
    val t by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "waveT"
    )
    val phase = if (reduced) 0f else t
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (layer in 0 until 3) {
            val amp = h * 0.16f
            val base = h * (0.42f + layer * 0.14f)
            val ph = phase + layer * 1.3f
            val k = (2 * PI / w).toFloat() * (1.2f + layer * 0.4f)
            val path = Path()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w) {
                path.lineTo(x, base + amp * sin(k * x + ph))
                x += 6f
            }
            path.lineTo(w, h)
            path.close()
            drawPath(path, UpdateGreen.copy(alpha = 0.08f + layer * 0.04f))
        }
    }
}

/** Small green and yellow dots. [burst] throws them outward once; otherwise they drift down slowly. */
@Composable
private fun UpdateConfetti(progress: Float, burst: Boolean, count: Int, modifier: Modifier) {
    val colors = listOf(UpdateGreen, UpdateCyan, Tok_FFFACC15)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 0 until count) {
            val rx = dust(i, 1)
            val ry = dust(i, 2)
            val rs = dust(i, 3)
            val r = 2.5.dp.toPx() * (0.8f + 0.4f * rs)
            val color = colors[i % colors.size]
            if (burst) {
                val x = w * 0.5f + (rx - 0.5f) * w * 1.1f * progress
                val y = h * (0.2f + progress * (0.2f + ry * 0.5f))
                drawCircle(color.copy(alpha = (1f - progress * 0.8f).coerceIn(0f, 1f)), radius = r, center = Offset(x, y))
            } else {
                val x = w * (0.08f + 0.84f * rx)
                val y = h * ((ry + progress) % 1f)
                drawCircle(color.copy(alpha = 0.5f), radius = r, center = Offset(x, y))
            }
        }
    }
}

private fun dust(i: Int, salt: Int): Float {
    val v = sin(i * 12.9898 + salt * 78.233) * 43758.5453
    return abs(v - floor(v)).toFloat()
}

/** Segmented ring. Filled segments are the real download %; the head has a glow dot. */
@Composable
private fun SegmentRing(progress: Float, modifier: Modifier) {
    val track = faint(0.12f)
    Canvas(modifier) {
        val stroke = 10.dp.toPx()
        val inset = stroke / 2f
        val side = size.minDimension - stroke
        val segments = 48
        val step = 360f / segments
        val p = progress.coerceIn(0f, 1f)
        val filled = (p * segments).toInt()
        for (i in 0 until segments) {
            drawArc(
                color = if (i < filled) lerp(UpdateGreen, UpdateCyan, i / (segments - 1f)) else track,
                startAngle = -90f + i * step + 1.5f,
                sweepAngle = step - 3f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(side, side),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        if (p > 0f) {
            val r = side / 2f
            val a = Math.toRadians(-90.0 + 360.0 * p)
            val c = Offset(size.width / 2f + (cos(a) * r).toFloat(), size.height / 2f + (sin(a) * r).toFloat())
            drawCircle(Color.White.copy(alpha = 0.45f), radius = stroke * 1.4f, center = c)
            drawCircle(Color.White, radius = stroke * 0.4f, center = c)
        }
    }
}

@Composable
private fun ProcessPill() {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(UpdateGreen.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = UpdateGreen)
        Spacer(Modifier.width(8.dp))
        Text(tr("প্রসেস চলছে, একটু অপেক্ষা করুন...", "Working, please wait..."), fontSize = 13.sp)
    }
}

@Composable
private fun GradientButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(50.dp).pressScale().clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(UpdateGreen, UpdateCyan)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
