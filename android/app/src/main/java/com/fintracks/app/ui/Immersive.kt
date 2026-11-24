package com.fintracks.app.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen (immersive) mode: the system status bar is hidden, so every header starts at the top edge.
 * A swipe from the top edge shows the bar briefly and it hides again (transient-by-swipe).
 * The navigation bar is never touched. The setting is saved on the device and is on by default.
 */
object Immersive {
    private const val PREFS = "fintrack_display"
    private const val KEY = "immersive"

    var on by mutableStateOf(true)
        private set

    fun load(ctx: Context) {
        on = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(ctx: Context, value: Boolean) {
        on = value
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}

/** Applies [Immersive.on] to the window. Runs after each composition, so the toggle takes effect at once. */
@Composable
fun ImmersiveEffect(dark: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    val on = Immersive.on
    SideEffect {
        val activity = context as? Activity ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Light theme: dark status and navigation icons. Dark theme: light icons.
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        if (on) controller.hide(WindowInsetsCompat.Type.statusBars())
        else controller.show(WindowInsetsCompat.Type.statusBars())
    }
}

/**
 * Top padding for headers. Uses the status bar height when it is shown, and the camera cutout height when there is one,
 * so content never sits under the notch or punch hole. With the status bar hidden it is only the cutout (often zero).
 */
@Composable
fun Modifier.topInsetPadding(): Modifier =
    windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
