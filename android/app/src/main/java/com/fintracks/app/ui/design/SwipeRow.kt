package com.fintracks.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.ui.rememberReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One action revealed by a swipe. */
class SwipeAction(val label: String, val icon: ImageVector, val color: Color, val onTrigger: () -> Unit)

/**
 * Row that reveals an action when dragged. Past 30 % of the width the action runs; otherwise the row springs back.
 * Every action is also reachable without a swipe (the row's long-press menu), so TalkBack users are not blocked.
 */
@Composable
fun SwipeRow(
    onSwipeRight: SwipeAction?,
    onSwipeLeft: SwipeAction?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val reduced = rememberReduceMotion()
    val offset = remember { Animatable(0f) }
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
        val maxPx = with(LocalDensity.current) { maxWidth.toPx() }
        val threshold = maxPx * 0.3f
        val v = offset.value

        // Backdrop: the action that the current drag direction reveals.
        if (v > 0f && onSwipeRight != null) {
            ActionBackdrop(onSwipeRight, alignStart = true)
        } else if (v < 0f && onSwipeLeft != null) {
            ActionBackdrop(onSwipeLeft, alignStart = false)
        }

        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(onSwipeRight, onSwipeLeft) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val now = offset.value
                                when {
                                    now > threshold && onSwipeRight != null -> {
                                        onSwipeRight.onTrigger()
                                    }
                                    now < -threshold && onSwipeLeft != null -> {
                                        onSwipeLeft.onTrigger()
                                    }
                                }
                                offset.animateTo(0f, tween(if (reduced) 0 else 200))
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(if (reduced) 0 else 200)) } }
                    ) { change, drag ->
                        change.consume()
                        val min = if (onSwipeLeft != null) -maxPx else 0f
                        val max = if (onSwipeRight != null) maxPx else 0f
                        val next = (offset.value + drag).coerceIn(min, max)
                        scope.launch { offset.snapTo(next) }
                    }
                }
        ) {
            content()
        }
    }
}

@Composable
private fun BoxScope.ActionBackdrop(action: SwipeAction, alignStart: Boolean) {
    Box(
        Modifier.matchParentSize().background(action.color.copy(alpha = 0.18f)).padding(horizontal = 18.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(action.icon, contentDescription = null, tint = action.color, modifier = Modifier.width(22.dp))
            Text(action.label, color = action.color, fontSize = 14.sp)
        }
    }
}
