package com.fintracks.app.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

// Shared motion helpers. Every animation here is short (200–400 ms), uses no assets, and collapses to its
// final state when the system "Remove animations" setting is on. None of them consume pointer input.

/** True when the system "Remove animations" setting is on (animator duration scale 0). */
fun motionReduced(ctx: Context): Boolean = try {
    android.provider.Settings.Global.getFloat(
        ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f
} catch (e: Exception) {
    false
}

@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { motionReduced(ctx) }
}

/** Press feedback: the element shrinks slightly while a finger is down. Taps still reach the clickable. */
@Composable
fun Modifier.pressScale(enabled: Boolean = true): Modifier {
    val reduce = rememberReduceMotion()
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed && enabled && !reduce) 0.96f else 1f,
        tween(150, easing = FastOutSlowInEasing),
        label = "pressScale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled) {
            if (enabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
        }
}

/** Fades and slides a block in, delayed by its position in a list (about 70 ms per item). */
@Composable
fun Modifier.staggerIn(index: Int): Modifier {
    val reduce = rememberReduceMotion()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduce) {
            anim.snapTo(1f)
        } else {
            delay(index * 70L)
            anim.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
        }
    }
    return this.graphicsLayer {
        alpha = anim.value
        translationY = (1f - anim.value) * 16.dp.toPx()
    }
}

/** Springy pop-in (scale from small to full with a small overshoot). */
@Composable
fun Modifier.popIn(delayMs: Long = 0L): Modifier {
    val reduce = rememberReduceMotion()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduce) {
            anim.snapTo(1f)
        } else {
            delay(delayMs)
            anim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    return this.graphicsLayer {
        val v = anim.value
        scaleX = v
        scaleY = v
        alpha = v.coerceIn(0f, 1f)
    }
}

/** Gentle up-and-down float for empty-state icons. */
@Composable
fun Modifier.floatingBob(): Modifier {
    val reduce = rememberReduceMotion()
    val inf = rememberInfiniteTransition(label = "floatBob")
    val y by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "floatY"
    )
    return this.graphicsLayer {
        translationY = if (reduce) 0f else (y - 0.5f) * -10.dp.toPx()
    }
}

/** Shimmer band that sweeps across the element while [active] (used while syncing). */
@Composable
fun Modifier.shimmer(active: Boolean): Modifier {
    val reduce = rememberReduceMotion()
    val inf = rememberInfiniteTransition(label = "shimmer")
    val x by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX"
    )
    if (!active || reduce) return this
    return this.drawWithContent {
        drawContent()
        val w = size.width
        val start = -w + x * 2f * w
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.18f), Color.Transparent),
                start = Offset(start, 0f),
                end = Offset(start + w * 0.6f, size.height)
            )
        )
    }
}

/** Calm pulsing outline for a warning card (budget at 80%+). */
@Composable
fun Modifier.warnPulse(active: Boolean, color: Color): Modifier {
    val reduce = rememberReduceMotion()
    val inf = rememberInfiniteTransition(label = "warnPulse")
    val a by inf.animateFloat(
        0.15f, 0.9f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "warnAlpha"
    )
    if (!active || reduce) return this
    return this.drawWithContent {
        drawContent()
        drawRoundRect(
            color = color.copy(alpha = a),
            cornerRadius = CornerRadius(24.dp.toPx()),
            style = Stroke(2.dp.toPx())
        )
    }
}

/**
 * Number that counts from its previous value to [target] (starts at 0 the first time it shows).
 * [format] turns the running value into text, so currency symbols and privacy masking still apply.
 */
@Composable
fun CountUpText(
    target: Double,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle = TextStyle.Default
) {
    val reduce = rememberReduceMotion()
    val anim = remember { Animatable(0f) }
    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        finished = false
        if (reduce) {
            anim.snapTo(target.toFloat())
        } else {
            anim.animateTo(target.toFloat(), tween(400, easing = FastOutSlowInEasing))
        }
        finished = true
    }
    val shown = if (finished || reduce) target else anim.value.toDouble()
    Text(
        format(shown),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        style = style
    )
}

/** Progress ring (0..1) whose arc sweeps to the new value. */
@Composable
fun AnimatedRing(fraction: Float, color: Color, size: Dp, stroke: Dp, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400, easing = FastOutSlowInEasing), label = "ring")
    Canvas(modifier.size(size)) {
        val s = stroke.toPx()
        val inset = s / 2f
        drawArc(
            color = Color.White.copy(alpha = 0.08f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - s, this.size.height - s),
            style = Stroke(s, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - s, this.size.height - s),
            style = Stroke(s, cap = StrokeCap.Round)
        )
    }
}

private class ConfettiPiece(
    val x0: Float,
    val y0: Float,
    val vx: Float,
    val vy: Float,
    val rot: Float,
    val spin: Float,
    val color: Color
)

private val confettiColors = listOf(
    Color(0xFF38BDF8), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF472B6), Color(0xFF818CF8)
)

/**
 * One shared, lightweight confetti burst (plain shapes, no assets). It plays once when it appears, fades
 * out by itself, and draws nothing with Reduce Motion on. Used for sign-up, goal 100% and full debt payoff.
 */
@Composable
fun ConfettiBurst(modifier: Modifier = Modifier, pieces: Int = 40) {
    val reduce = rememberReduceMotion()
    val progress = remember { Animatable(0f) }
    val particles = remember(pieces) {
        val rnd = Random(System.nanoTime())
        List(pieces) {
            ConfettiPiece(
                x0 = 0.5f + (rnd.nextFloat() - 0.5f) * 0.16f,
                y0 = 0.42f,
                vx = (rnd.nextFloat() - 0.5f) * 0.9f,
                vy = -(0.25f + rnd.nextFloat() * 0.35f),
                rot = rnd.nextFloat() * 360f,
                spin = (rnd.nextFloat() - 0.5f) * 720f,
                color = confettiColors[rnd.nextInt(confettiColors.size)]
            )
        }
    }
    LaunchedEffect(Unit) {
        if (!reduce) progress.animateTo(1f, tween(1300, easing = LinearOutSlowInEasing))
    }
    if (reduce) return
    Canvas(modifier) {
        val p = progress.value
        if (p <= 0f || p >= 1f) return@Canvas
        val alpha = (1f - p * p * p).coerceIn(0f, 1f)
        val w = size.width
        val h = size.height
        val pw = 8.dp.toPx()
        val ph = 12.dp.toPx()
        particles.forEach { pc ->
            val x = w * (pc.x0 + pc.vx * p)
            val y = h * (pc.y0 + pc.vy * p) + h * 1.1f * p * p
            rotate(pc.rot + pc.spin * p, pivot = Offset(x, y)) {
                drawRect(pc.color.copy(alpha = alpha), Offset(x - pw / 2f, y - ph / 2f), Size(pw, ph))
            }
        }
    }
}

/** Success tick: a circle grows with a small overshoot, then the tick is drawn in. */
@Composable
fun SuccessTick(modifier: Modifier = Modifier, tint: Color = Color(0xFF34D399)) {
    val reduce = rememberReduceMotion()
    val scale = remember { Animatable(if (reduce) 1f else 0.4f) }
    val draw = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
            draw.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        }
    }
    Canvas(modifier.size(96.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
        val s = min(size.width, size.height)
        drawCircle(tint.copy(alpha = 0.18f), radius = s / 2f)
        drawCircle(tint, radius = s / 2f - 2.dp.toPx(), style = Stroke(4.dp.toPx()))
        val p1 = Offset(s * 0.28f, s * 0.52f)
        val p2 = Offset(s * 0.45f, s * 0.68f)
        val p3 = Offset(s * 0.73f, s * 0.36f)
        val d = draw.value
        val stroke = 6.dp.toPx()
        val f1 = (d / 0.4f).coerceIn(0f, 1f)
        val end1 = Offset(p1.x + (p2.x - p1.x) * f1, p1.y + (p2.y - p1.y) * f1)
        drawLine(tint, p1, end1, strokeWidth = stroke, cap = StrokeCap.Round)
        if (d > 0.4f) {
            val f2 = ((d - 0.4f) / 0.6f).coerceIn(0f, 1f)
            val end2 = Offset(p2.x + (p3.x - p2.x) * f2, p2.y + (p3.y - p2.y) * f2)
            drawLine(tint, p2, end2, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** Stamp text that pops in and tilts slightly, e.g. "নতুন অ্যাকাউন্ট" or "VERIFIED". */
@Composable
fun StampText(text: String, color: Color, modifier: Modifier = Modifier, tilt: Float = -8f) {
    Text(
        text,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = modifier
            .popIn()
            .graphicsLayer { rotationZ = tilt }
    )
}
