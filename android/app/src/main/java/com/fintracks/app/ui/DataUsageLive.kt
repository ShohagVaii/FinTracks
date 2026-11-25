package com.fintracks.app.ui

import android.content.pm.ApplicationInfo
import android.net.TrafficStats
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Per-UID counters now minus counters a second earlier. A UID the device does not report (-1) is skipped,
 * and a UID with no traffic is left out, so only active UIDs remain.
 */
internal fun duUidDeltas(prev: Map<Int, LongArray>, now: Map<Int, LongArray>): Map<Int, LongArray> {
    val out = HashMap<Int, LongArray>()
    for ((uid, c) in now) {
        val p = prev[uid] ?: continue
        if (c[0] < 0L || c[1] < 0L || p[0] < 0L || p[1] < 0L) continue
        val rx = (c[0] - p[0]).coerceAtLeast(0L)
        val tx = (c[1] - p[1]).coerceAtLeast(0L)
        if (rx > 0L || tx > 0L) out[uid] = longArrayOf(rx, tx)
    }
    return out
}

private class LiveMeta(val pkg: String, val label: String, val system: Boolean)

private class LiveApp(val uid: Int, val meta: LiveMeta, val rxSpeed: Long, val txSpeed: Long, val rxTotal: Long, val txTotal: Long)

private fun sampleUids(uids: Collection<Int>): Map<Int, LongArray> =
    uids.associateWith { longArrayOf(TrafficStats.getUidRxBytes(it), TrafficStats.getUidTxBytes(it)) }

/**
 * Apps moving data right now: speed from a one-second TrafficStats per-UID delta, amounts summed since this list
 * started. Shows nothing when no app is active. Does not need Usage Access.
 */
@Composable
internal fun LiveAppsCard(showSystem: Boolean, onOpen: (DuApp) -> Unit) {
    val ctx = LocalContext.current
    var active by remember { mutableStateOf<List<LiveApp>>(emptyList()) }

    LaunchedEffect(Unit) {
        val meta: Map<Int, LiveMeta> = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0).groupBy { it.uid }.mapValues { (_, infos) ->
                val info = infos.first()
                LiveMeta(
                    pkg = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    system = info.uid < 10000 || (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
        }
        val totals = HashMap<Int, LongArray>()
        var prev = withContext(Dispatchers.IO) { sampleUids(meta.keys) }
        while (isActive) {
            delay(1000)
            val now = withContext(Dispatchers.IO) { sampleUids(meta.keys) }
            val deltas = duUidDeltas(prev, now)
            prev = now
            deltas.forEach { (uid, d) ->
                val t = totals.getOrPut(uid) { LongArray(2) }
                t[0] += d[0]
                t[1] += d[1]
            }
            active = deltas.mapNotNull { (uid, d) ->
                val m = meta[uid] ?: return@mapNotNull null
                val t = totals[uid] ?: return@mapNotNull null
                LiveApp(uid, m, d[0], d[1], t[0], t[1])
            }.sortedByDescending { it.rxSpeed + it.txSpeed }.take(8)
        }
    }

    val shown = active.filter { showSystem || !it.meta.system }
    if (shown.isEmpty()) return

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(faint(0.05f))
            .border(1.dp, faint(0.1f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(tr("এখন সক্রিয়", "Active now"), fontWeight = FontWeight.Bold)
        shown.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    onOpen(DuApp(a.uid, a.meta.label, a.meta.pkg, a.meta.system, a.rxTotal, a.txTotal))
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppIcon(a.meta.pkg, a.meta.label)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.meta.label, maxLines = 1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconValue(Icons.Default.ArrowDownward, DuCyan, "", duText(a.rxSpeed, perSecond = true))
                        IconValue(Icons.Default.ArrowUpward, DuGreen, "", duText(a.txSpeed, perSecond = true))
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconValue(Icons.Default.ArrowDownward, DuCyan, "", duShort(a.rxTotal))
                    IconValue(Icons.Default.ArrowUpward, DuGreen, "", duShort(a.txTotal))
                }
            }
        }
    }
}
