package com.fintracks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ping targets. Change the list here only. */
internal val PING_TARGETS = listOf("8.8.8.8", "1.1.1.1", "google.com")

private const val PING_SAMPLES = 4

/** Round-trip time in ms from ping output, or null when there was no reply. */
internal fun parsePingMs(output: String): Double? =
    Regex("""time[=<]\s*([0-9]+(?:\.[0-9]+)?)\s*ms""").find(output)?.groupValues?.get(1)?.toDoubleOrNull()

internal class PingStat(val avg: Double?, val min: Double?, val max: Double?, val lossPct: Int)

/** Average, min and max over the replies; loss is the share of samples with no reply. */
internal fun pingStat(samples: List<Double?>): PingStat {
    val ok = samples.filterNotNull()
    val loss = if (samples.isEmpty()) 0 else (samples.size - ok.size) * 100 / samples.size
    return PingStat(if (ok.isEmpty()) null else ok.average(), ok.minOrNull(), ok.maxOrNull(), loss)
}

/** TCP connect time to port 443, used when the ping binary cannot run. */
private fun tcpMs(host: String): Double? = try {
    val t0 = System.nanoTime()
    Socket().use { it.connect(InetSocketAddress(host, 443), 3000) }
    (System.nanoTime() - t0) / 1_000_000.0
} catch (e: Exception) {
    null
}

/** One echo request through the system ping; falls back to TCP connect time when ping cannot run. */
private fun pingOnce(host: String): Double? = try {
    val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", host).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().use { it.readText() }
    p.waitFor(5, TimeUnit.SECONDS)
    if (out.contains("invalid", ignoreCase = true) || out.contains("usage", ignoreCase = true)) tcpMs(host)
    else parsePingMs(out)
} catch (e: Exception) {
    tcpMs(host)
}

private fun msText(v: Double?): String =
    if (v == null) "—" else duBnDigits(String.format(Locale.US, "%.1f", v)) + " ms"

/** Ping card: each target gets four samples; rows fill in as replies arrive. Starts on open; "আবার পরীক্ষা" repeats. */
@Composable
fun PingCard() {
    var results by remember { mutableStateOf<Map<String, List<Double?>>>(emptyMap()) }
    var running by remember { mutableStateOf(false) }
    var runId by remember { mutableIntStateOf(1) }

    LaunchedEffect(runId) {
        running = true
        results = PING_TARGETS.associateWith { emptyList<Double?>() }
        for (host in PING_TARGETS) {
            val samples = mutableListOf<Double?>()
            repeat(PING_SAMPLES) {
                samples.add(withContext(Dispatchers.IO) { pingOnce(host) })
                results = results + (host to samples.toList())
            }
        }
        running = false
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(faint(0.05f))
            .border(1.dp, faint(0.1f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("পিং পরীক্ষা", "Ping test"), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Button(onClick = { runId++ }, enabled = !running) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("আবার পরীক্ষা", "Run again"), fontSize = 13.sp)
            }
        }
        PING_TARGETS.forEach { host ->
            val samples = results[host] ?: emptyList()
            val st = pingStat(samples)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Language, contentDescription = null, tint = DuCyan, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(host, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                    Text(
                        tr("লস ", "Loss ") + (if (samples.isEmpty()) "—" else duBnDigits("${st.lossPct}") + "%"),
                        color = muted, fontSize = 12.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(msText(st.avg), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.End)
                    Text(msText(st.min) + " / " + msText(st.max), color = muted, fontSize = 12.sp)
                }
            }
        }
    }
}
