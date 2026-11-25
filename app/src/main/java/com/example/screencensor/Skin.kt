package com.example.screencensor

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Finds skin by colour, adapting to whatever skin tone is in the picture. */
object Skin {
    class Model(val cb: Float, val cr: Float, val y: Float)

    private class Hist {
        val cb = IntArray(256)
        val cr = IntArray(256)
        val y = IntArray(256)
        var n = 0

        fun add(v: Int) {
            val r = (v shr 16) and 255
            val g = (v shr 8) and 255
            val b = v and 255
            val yy = 0.299f * r + 0.587f * g + 0.114f * b
            val cbb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val crr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            if (cbb in 70f..140f && crr in 126f..182f && yy in 35f..250f) {
                cb[cbb.toInt().coerceIn(0, 255)]++
                cr[crr.toInt().coerceIn(0, 255)]++
                y[yy.toInt().coerceIn(0, 255)]++
                n++
            }
        }

        fun median(h: IntArray): Float {
            var acc = 0
            val half = n / 2
            for (i in 0..255) {
                acc += h[i]
                if (acc > half) return i.toFloat()
            }
            return 128f
        }

        fun model(): Model? = if (n < 150) null else Model(median(cb), median(cr), median(y))
    }

    fun ycc(v: Int): FloatArray {
        val r = (v shr 16) and 255
        val g = (v shr 8) and 255
        val b = v and 255
        return floatArrayOf(
            0.299f * r + 0.587f * g + 0.114f * b,
            128f - 0.168736f * r - 0.331264f * g + 0.5f * b,
            128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        )
    }

    fun chromaDist(v: Int, m: Model): Float {
        val c = ycc(v)
        return hypot(c[1] - m.cb, c[2] - m.cr)
    }

    /** Skin colour taken from the middle half of each box (that is where the skin is). */
    fun model(bmp: Bitmap, boxes: List<Rect>): Model? {
        val h = Hist()
        for (r in boxes) {
            val l = r.left + r.width() / 4
            val t = r.top + r.height() / 4
            val cw = r.width() / 2
            val ch = r.height() / 2
            if (cw < 2 || ch < 2) continue
            val px = IntArray(cw * ch)
            bmp.getPixels(px, 0, cw, l, t, cw, ch)
            val step = max(1, sqrt(cw * ch / 40000.0).toInt())
            var i = 0
            while (i < px.size) { h.add(px[i]); i += step }
        }
        return h.model()
    }

    /** Skin colour taken from the pixels around a box (everything in [outer] except [inner]). */
    fun modelRing(bmp: Bitmap, outer: Rect, inner: Rect): Model? {
        val w = outer.width()
        val h = outer.height()
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, outer.left, outer.top, w, h)
        val hist = Hist()
        val step = max(1, sqrt(w * h / 40000.0).toInt())
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val gx = outer.left + x
                val gy = outer.top + y
                if (!(gx >= inner.left && gx < inner.right && gy >= inner.top && gy < inner.bottom)) hist.add(px[y * w + x])
                x += step
            }
            y += step
        }
        return hist.model()
    }

    /**
     * 0..1 mask of the skin inside [r]. Holes smaller than [closeFrac] of the box (nipples, eyes, lips,
     * thin straps and underwear lines) are closed, so they end up inside the mask instead of leaking through.
     */
    fun mask(bmp: Bitmap, r: Rect, m: Model, tol: Float, closeFrac: Float, keepClothes: Boolean = false): FloatArray {
        val w = r.width()
        val h = r.height()
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, r.left, r.top, w, h)
        val a = FloatArray(w * h)
        val loose = BooleanArray(w * h)      // skin-like in hue and not black: nipples, lips, but not dark clothing
        for (i in px.indices) {
            val c = ycc(px[i])
            val d = hypot(c[1] - m.cb, c[2] - m.cr)
            a[i] = if (c[0] < 20f) 0f else ((tol + 3f - d) / 3f).coerceIn(0f, 1f)
            loose[i] = c[0] >= 40f && c[1] in 70f..140f && c[2] in 126f..188f
        }
        val rr = max(2, (min(w, h) * closeFrac).toInt())
        // closing = grow the skin, then shrink it back
        val bin = FloatArray(a.size)
        for (i in a.indices) bin[i] = if (a[i] > 0.5f) 1f else 0f
        val grown = Fx.boxBlurZero(bin, w, h, rr)
        for (i in bin.indices) bin[i] = if (grown[i] > 0.02f) 1f else 0f
        val shrunk = Fx.boxBlurZero(bin, w, h, rr)
        for (i in a.indices) a[i] = max(a[i], if (shrunk[i] > 0.98f && (!keepClothes || loose[i])) 1f else 0f)
        return Fx.boxBlur(a, w, h, 1)
    }
}
