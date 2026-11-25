package com.example.screencensor

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Mosaic like the reference pictures: one fixed grid over the whole picture, every block is the average
 * of its pixels, and only the blocks that mostly lie inside a detected shape are replaced, so the edge of
 * the mosaic follows the body part instead of being a plain rectangle.
 */
object Pixelate {
    class Shape(val rect: RectF, val ellipse: Boolean) {
        fun contains(x: Float, y: Float): Boolean {
            if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) return false
            if (!ellipse) return true
            val rx = rect.width() / 2f
            val ry = rect.height() / 2f
            if (rx <= 0f || ry <= 0f) return false
            val dx = (x - rect.centerX()) / rx
            val dy = (y - rect.centerY()) / ry
            return dx * dx + dy * dy <= 1f
        }
    }

    /** Block size in pixels for this picture. strength 1..10 (about 2% of the picture at 3, 5% at 10). */
    fun blockSize(w: Int, h: Int, strength: Int, shapes: List<Shape>): Int {
        val frac = 0.008f + 0.004f * strength.coerceIn(1, 10)
        var b = max(6, (max(w, h) * frac).toInt())
        if (shapes.isNotEmpty()) {
            val smallest = shapes.minOf { min(it.rect.width(), it.rect.height()) }
            b = min(b, max(6, (smallest / 3f).toInt()))     // a small part still gets several blocks
        }
        return b
    }

    private fun inAny(shapes: List<Shape>, x: Float, y: Float): Boolean {
        for (s in shapes) if (s.contains(x, y)) return true
        return false
    }

    /**
     * [src] is a w*h piece of a bigger picture whose top-left corner is at (offX, offY), so the grid lines
     * up the same everywhere. With [clear] the blocks outside the shapes come out transparent (for overlays).
     */
    fun render(src: IntArray, w: Int, h: Int, offX: Int, offY: Int, shapes: List<Shape>, blk: Int, clear: Boolean): IntArray {
        val out = if (clear) IntArray(w * h) else src.copyOf()
        val forced = HashSet<Long>()
        for (s in shapes) {
            val gx = (s.rect.centerX().toInt().coerceAtLeast(0) / blk).toLong()
            val gy = (s.rect.centerY().toInt().coerceAtLeast(0) / blk).toLong()
            forced.add(gx * 1000003L + gy)
        }
        var cy = (offY / blk) * blk
        while (cy < offY + h) {
            var cx = (offX / blk) * blk
            while (cx < offX + w) {
                var hit = 0
                for (j in 0..2) for (i in 0..2) {
                    if (inAny(shapes, cx + (i + 0.5f) / 3f * blk, cy + (j + 0.5f) / 3f * blk)) hit++
                }
                val key = (cx / blk).toLong() * 1000003L + (cy / blk).toLong()
                if (hit >= 3 || forced.contains(key)) {
                    val x0 = max(cx, offX) - offX
                    val x1 = min(cx + blk, offX + w) - offX
                    val y0 = max(cy, offY) - offY
                    val y1 = min(cy + blk, offY + h) - offY
                    if (x1 > x0 && y1 > y0) {
                        var sr = 0L; var sg = 0L; var sb = 0L
                        for (y in y0 until y1) for (x in x0 until x1) {
                            val p = src[y * w + x]
                            sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
                        }
                        val n = (x1 - x0) * (y1 - y0)
                        val c = (0xFF shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
                        for (y in y0 until y1) for (x in x0 until x1) out[y * w + x] = c
                    }
                }
                cx += blk
            }
            cy += blk
        }
        return out
    }
}
