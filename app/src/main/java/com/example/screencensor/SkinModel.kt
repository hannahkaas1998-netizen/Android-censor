package com.example.screencensor

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max

/** A simple skin-colour model (Gaussian on the Cb/Cr colour channels), learned from the detected body parts. */
internal class SkinModel private constructor(
    private val mb: Float, private val mr: Float,
    private val ia: Float, private val ib: Float, private val ic: Float,
    private val meanY: Float
) {
    /** Squared distance from the skin colour (huge for very dark pixels). */
    fun dist(p: Int): Float {
        val r = ((p shr 16) and 255).toFloat()
        val g = ((p shr 8) and 255).toFloat()
        val b = (p and 255).toFloat()
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        if (y < 0.55f * meanY) return 1e9f
        val cb = 128f + (-0.1687f * r - 0.3313f * g + 0.5f * b) - mb
        val cr = 128f + (0.5f * r - 0.4187f * g - 0.0813f * b) - mr
        return cb * cb * ia + 2f * cb * cr * ib + cr * cr * ic
    }

    fun isSkin(p: Int, t: Float = 4.5f) = dist(p) <= t

    companion object {
        fun fit(s: IntArray, n: Int): SkinModel? {
            if (n < 24) return null
            val cb = FloatArray(n)
            val cr = FloatArray(n)
            val yy = FloatArray(n)
            for (i in 0 until n) {
                val p = s[i]
                val r = ((p shr 16) and 255).toFloat()
                val g = ((p shr 8) and 255).toFloat()
                val b = (p and 255).toFloat()
                yy[i] = 0.299f * r + 0.587f * g + 0.114f * b
                cb[i] = 128f + (-0.1687f * r - 0.3313f * g + 0.5f * b)
                cr[i] = 128f + (0.5f * r - 0.4187f * g - 0.0813f * b)
            }
            val keep = BooleanArray(n) { true }
            var ok = false
            var mb = 0f; var mr = 0f; var my = 0f
            var ia = 1f; var ib = 0f; var ic = 1f
            for (iter in 0 until 3) {
                var cnt = 0
                var sb = 0f; var sr = 0f; var sy = 0f
                for (i in 0 until n) if (keep[i]) { cnt++; sb += cb[i]; sr += cr[i]; sy += yy[i] }
                if (cnt < 12) break
                val nb = sb / cnt
                val nr = sr / cnt
                var vbb = 0f; var vbr = 0f; var vrr = 0f
                for (i in 0 until n) if (keep[i]) {
                    val db = cb[i] - nb
                    val dr = cr[i] - nr
                    vbb += db * db; vbr += db * dr; vrr += dr * dr
                }
                vbb = vbb / cnt + 4f
                vbr /= cnt
                vrr = vrr / cnt + 4f
                val det = vbb * vrr - vbr * vbr
                if (det <= 1e-3f) break
                mb = nb; mr = nr; my = sy / cnt
                ia = vrr / det; ib = -vbr / det; ic = vbb / det
                ok = true
                for (i in 0 until n) {      // drop outliers (hair, eyes, areola ...) before the next round
                    val db = cb[i] - mb
                    val dr = cr[i] - mr
                    keep[i] = db * db * ia + 2f * db * dr * ib + dr * dr * ic < 6f
                }
            }
            return if (ok) SkinModel(mb, mr, ia, ib, ic, my) else null
        }

        private val BODY = setOf(3, 5, 2, 4, 14, 13, 11, 7, 6)
        private val FACE = setOf(1, 12)

        /** Learns the skin colour from the boxes of detected exposed body parts (faces only if nothing else was found). */
        fun fromDets(bmp: Bitmap, all: List<Detector.Det>): SkinModel? {
            val buf = IntArray(8000)
            var n = 0
            fun take(r: RectF) {
                val rc = Blur.clampRect(r, bmp.width, bmp.height)
                val w = rc.width()
                val h = rc.height()
                if (w < 4 || h < 4) return
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, rc.left, rc.top, w, h)
                val step = max(1, (w * h) / 1500)
                var i = 0
                while (i < px.size && n < buf.size) { buf[n++] = px[i]; i += step }
            }
            for (d in all) if (d.label in BODY) take(Blur.scaled(d.box, 0.6f))
            if (n < 24) for (d in all) if (d.label in FACE) take(Blur.scaled(d.box, 0.4f))
            return fit(buf, n)
        }
    }
}
