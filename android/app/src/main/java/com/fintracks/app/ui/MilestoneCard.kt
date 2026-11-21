package com.fintracks.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.fintracks.app.R
import java.io.File

/** Makes a square PNG "achievement card" for a goal milestone and opens the share sheet. */
object MilestoneCard {
    private const val SIZE = 1080

    fun share(ctx: Context, c: GoalCelebration) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "milestone-${c.percent}-${System.currentTimeMillis()}.png")
        val bmp = render(ctx, c)
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
        Export.share(ctx, file, "image/png", tr("FinTrack লক্ষ্য অর্জন", "FinTrack goal reached"))
    }

    private fun render(ctx: Context, c: GoalCelebration): Bitmap {
        val w = SIZE.toFloat()
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, w, w,
                intArrayOf(0xFF0EA5E9.toInt(), 0xFF2563EB.toInt(), 0xFF0F172A.toInt()),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, w, bg)

        val bold = ResourcesCompat.getFont(ctx, R.font.hind_siliguri_bold) ?: Typeface.DEFAULT_BOLD
        val regular = ResourcesCompat.getFont(ctx, R.font.hind_siliguri_regular) ?: Typeface.DEFAULT

        fun draw(text: String, x: Float, y: Float, size: Float, face: Typeface, align: Paint.Align, alpha: Int = 255) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                this.alpha = alpha
                textSize = size
                typeface = face
                textAlign = align
            }
            canvas.drawText(text, x, y, p)
        }

        draw("FinTrack", 80f, 140f, 56f, bold, Paint.Align.LEFT)
        draw(tr("লক্ষ্য অর্জন", "Goal reached"), 80f, 210f, 40f, regular, Paint.Align.LEFT, 210)
        draw("${c.percent}%", w / 2, 610f, 230f, bold, Paint.Align.CENTER)
        val name = if (c.goalName.length > 24) c.goalName.take(23) + "…" else c.goalName
        draw(name, w / 2, 730f, 60f, bold, Paint.Align.CENTER)
        draw("${taka(c.saved, false)} / ${taka(c.target, false)}", w / 2, 810f, 44f, regular, Paint.Align.CENTER)
        draw(tr("আর এগিয়ে যান, আপনি পারবেন!", "Keep going, you can do it!"), w / 2, 960f, 36f, regular, Paint.Align.CENTER, 220)
        return bmp
    }
}
