package com.example.screencensor

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Clean pixelation: every block is the true average colour of the area it covers. No noise, no grid. */
object Mosaic {
    /** Blocks across the short side: 40 at strength 1 (fine) down to 8 at strength 10 (chunky). */
    fun cells(strength: Int): Int =
        (40.0 * 0.836.pow((strength.coerceIn(1, 10) - 1).toDouble())).roundToInt().coerceAtLeast(6)

    @Suppress("UNUSED_PARAMETER")
    fun make(crop: Bitmap, strength: Int, seed: Long = 0L): Bitmap {
        val w = crop.width
        val h = crop.height
        val blk = max(3, min(w, h) / cells(strength))
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        var by = 0
        while (by < h) {
            val y2 = min(h, by + blk)
            var bx = 0
            while (bx < w) {
                val x2 = min(w, bx + blk)
                var sr = 0L
                var sg = 0L
                var sb = 0L
                for (y in by until y2) {
                    var o = y * w + bx
                    for (x in bx until x2) {
                        val v = px[o++]
                        sr += (v shr 16) and 255
                        sg += (v shr 8) and 255
                        sb += v and 255
                    }
                }
                val n = ((x2 - bx) * (y2 - by)).toLong()
                val col = (0xFF shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
                for (y in by until y2) {
                    var o = y * w + bx
                    for (x in bx until x2) out[o++] = col
                }
                bx = x2
            }
            by = y2
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }
}
