package com.fintracks.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.fintracks.app.R

/**
 * One notification look for every local notification (SMS suggestions, bill reminders, daily reminder).
 * Small icon: white monochrome arrow-F silhouette (Android status-bar rule: white on transparent).
 * Large icon: full-colour FinTrack logo.
 */
object NotifyStyle {
    val SMALL: Int get() = R.drawable.ic_stat_fintrack

    fun large(ctx: Context): Bitmap? = BitmapFactory.decodeResource(ctx.resources, R.drawable.notif_large_logo)
}
