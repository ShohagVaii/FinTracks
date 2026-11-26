package com.fintracks.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One transient message. [ok] = false draws the rose dot. */
internal data class FlashMsg(val text: String, val ok: Boolean, val id: Long)

/** Replaces Toast: any screen or background code calls [flash]; [GlassSnackbarHost] draws it. */
internal object Flash {
    private val state = MutableStateFlow<FlashMsg?>(null)
    val messages: StateFlow<FlashMsg?> = state.asStateFlow()

    fun show(text: String, ok: Boolean = true) {
        state.value = FlashMsg(text, ok, System.nanoTime())
    }
}

private val FLASH_BAD_WORDS = listOf("ব্যর্থ", "যায়নি", "হয়নি", "ভুল", "Could not", "Failed", "failed", "Invalid", "Wrong")

internal fun flash(text: String) {
    Flash.show(text, FLASH_BAD_WORDS.none { text.contains(it) })
}

/** Glass snackbar: slides up from the bottom, hides after about 2.6 s. Reduce Motion: no slide or fade. */
@Composable
fun GlassSnackbarHost(modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val current by Flash.messages.collectAsState()
    var last by remember { mutableStateOf<FlashMsg?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(current?.id) {
        val msg = current ?: return@LaunchedEffect
        last = msg
        visible = true
        delay(2600L)
        if (Flash.messages.value?.id == msg.id) visible = false
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduce) EnterTransition.None else slideInVertically(tween(260)) { it } + fadeIn(tween(260)),
        exit = if (reduce) ExitTransition.None else slideOutVertically(tween(200)) { it } + fadeOut(tween(200))
    ) {
        last?.let { m ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (m.ok) Emerald500 else Rose500))
                Spacer(Modifier.width(10.dp))
                Text(m.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
