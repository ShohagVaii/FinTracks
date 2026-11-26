package com.fintracks.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.ui.CountUpText
import com.fintracks.app.ui.Emerald500
import com.fintracks.app.ui.Rose500
import com.fintracks.app.ui.Sky500
import com.fintracks.app.ui.rememberReduceMotion
import com.fintracks.app.ui.taka
import com.fintracks.app.ui.tr
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Balance scale: left pan = what you owe (rose), right pan = what is owed to you (emerald).
 * Tilt = clamp((owed − owe) / max × 12°), settles with a spring; reduce-motion sets it at once.
 */
@Composable
fun BalanceScale(owed: Double, owe: Double, privacy: Boolean, modifier: Modifier = Modifier) {
    val net = owed - owe
    val max = maxOf(owed, owe, 1.0)
    val target = ((net / max) * 12.0).coerceIn(-12.0, 12.0).toFloat()
    val reduced = rememberReduceMotion()
    val tilt = remember { Animatable(0f) }
    LaunchedEffect(target, reduced) {
        if (reduced) tilt.snapTo(target)
        else tilt.animateTo(target, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow))
    }
    val beamColor = MaterialTheme.colorScheme.onSurfaceVariant
    val emerald = Emerald500
    val rose = Rose500
    val coinsRight = if (owed <= 0) 0 else ceil(owed / max * 4).toInt().coerceIn(1, 4)
    val coinsLeft = if (owe <= 0) 0 else ceil(owe / max * 4).toInt().coerceIn(1, 4)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val cx = size.width / 2
            val pivotY = size.height * 0.2f
            val arm = size.width * 0.34f
            val a = Math.toRadians(tilt.value.toDouble())
            val rx = cx + arm * cos(a).toFloat()
            val ry = pivotY + arm * sin(a).toFloat()
            val lx = cx - arm * cos(a).toFloat()
            val ly = pivotY - arm * sin(a).toFloat()

            // Post and base stay still.
            drawLine(beamColor, Offset(cx, pivotY), Offset(cx, size.height), strokeWidth = 4f)
            drawLine(beamColor, Offset(cx - 28f, size.height), Offset(cx + 28f, size.height), strokeWidth = 5f)
            // Beam.
            drawLine(beamColor, Offset(lx, ly), Offset(rx, ry), strokeWidth = 4f)
            drawCircle(beamColor, radius = 6f, center = Offset(cx, pivotY))

            // Pans hang level from each end: a half-bowl with coins on it.
            fun pan(x: Float, y: Float, color: androidx.compose.ui.graphics.Color, coins: Int) {
                drawLine(color.copy(alpha = 0.7f), Offset(x, y), Offset(x - 24f, y + 26f), strokeWidth = 2f)
                drawLine(color.copy(alpha = 0.7f), Offset(x, y), Offset(x + 24f, y + 26f), strokeWidth = 2f)
                drawArc(
                    color = color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(x - 30f, y + 24f), size = Size(60f, 22f), style = Stroke(width = 3f)
                )
                for (k in 0 until coins) {
                    drawCircle(color, radius = 8f, center = Offset(x, y + 22f - k * 7f))
                }
            }
            pan(lx, ly, rose, coinsLeft)
            pan(rx, ry, emerald, coinsRight)
        }

        val label = when {
            net > 0.005 -> tr("নিট পাওনা", "Net owed to you")
            net < -0.005 -> tr("নিট দেনা", "Net you owe")
            else -> tr("হিসাব সমান", "Balanced")
        }
        val color = when {
            net > 0.005 -> emerald
            net < -0.005 -> rose
            else -> Sky500
        }
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CountUpText(
            target = abs(net),
            format = { taka(it, privacy) },
            color = color,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
