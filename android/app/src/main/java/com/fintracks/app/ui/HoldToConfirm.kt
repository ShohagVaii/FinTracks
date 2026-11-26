package com.fintracks.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HOLD_MS = 2000L

/**
 * Destructive confirm: [onConfirm] runs only after the button is held for about 2 s.
 * Lifting early cancels and the fill drains back. Reduce Motion: no fill animation, the same 2 s hold still applies.
 */
@Composable
fun HoldToConfirm(
    text: String,
    color: Color,
    enabled: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reduce = rememberReduceMotion()
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .sizeIn(minWidth = 120.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (enabled) 0.18f else 0.06f))
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (enabled) {
                        val job = scope.launch {
                            if (reduce) delay(HOLD_MS) else progress.animateTo(1f, tween(HOLD_MS.toInt(), easing = LinearEasing))
                            onConfirm()
                        }
                        tryAwaitRelease()
                        job.cancel()
                        scope.launch {
                            if (reduce) progress.snapTo(0f) else progress.animateTo(0f, tween(200))
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(color.copy(alpha = 0.45f))
        )
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}
