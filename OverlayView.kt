package com.example.screencensor

import android.content.Context
import android.graphics.*
import android.view.View

class OverlayView(c: Context) : View(c) {
    data class Item(val dest: RectF, val bmp: Bitmap?, val smooth: Boolean)

    @Volatile var items: List<Item> = emptyList()
    @Volatile var show = true
    private val black = Paint().apply { color = Color.BLACK }
    private val sharp = Paint().apply { isFilterBitmap = false }
    private val smooth = Paint().apply { isFilterBitmap = true }

    override fun onDraw(cv: Canvas) {
        if (!show) return
        for (it in items) {
            if (it.bmp == null) cv.drawRect(it.dest, black)
            else cv.drawBitmap(it.bmp, null, it.dest, if (it.smooth) smooth else sharp)
        }
    }
}
