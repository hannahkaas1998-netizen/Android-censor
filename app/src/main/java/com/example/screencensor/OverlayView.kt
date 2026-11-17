package com.example.screencensor

import android.content.Context
import android.graphics.*
import android.view.View

class OverlayView(c: Context) : View(c) {
    data class Item(val dest: RectF, val bmp: Bitmap?, val smooth: Boolean, val label: String? = null)

    @Volatile var items: List<Item> = emptyList()
    @Volatile var show = true
    private val black = Paint().apply { color = Color.BLACK }
    private val paintSharp = Paint().apply { isFilterBitmap = false }
    private val paintSmooth = Paint().apply { isFilterBitmap = true }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(cv: Canvas) {
        if (!show) return
        for (item in items) {
            if (item.bmp == null) cv.drawRect(item.dest, black)
            else cv.drawBitmap(item.bmp, null, item.dest, if (item.smooth) paintSmooth else paintSharp)
            item.label?.let { drawLabel(cv, item.dest, it) }
        }
    }

    private fun drawLabel(cv: Canvas, r: RectF, text: String) {
        fill.textSize = 100f
        val w = fill.measureText(text)
        if (w <= 0f) return
        val size = (100f * r.width() * 0.9f / w).coerceAtMost(r.height() * 0.5f).coerceAtLeast(14f)
        fill.textSize = size
        outline.textSize = size
        outline.strokeWidth = size * 0.14f
        val cx = r.centerX()
        val cy = r.centerY()
        val y = cy - (fill.ascent() + fill.descent()) / 2f
        cv.save()
        cv.rotate(-8f, cx, cy)
        cv.drawText(text, cx, y, outline)
        cv.drawText(text, cx, y, fill)
        cv.restore()
    }
}
