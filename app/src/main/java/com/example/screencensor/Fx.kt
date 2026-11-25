package com.example.screencensor

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Picture effects that change what is shown instead of hiding it behind blocks. */
object Fx {
    /** Moving-average blur (box blur) on a float image. */
    fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val pre = FloatArray(max(w, h) + 1)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) pre[x + 1] = pre[x] + src[o + x]
            for (x in 0 until w) {
                val a = max(0, x - r)
                val b = min(w - 1, x + r)
                tmp[o + x] = (pre[b + 1] - pre[a]) / (b - a + 1)
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) pre[y + 1] = pre[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val a = max(0, y - r)
                val b = min(h - 1, y + r)
                out[y * w + x] = (pre[b + 1] - pre[a]) / (b - a + 1)
            }
        }
        return out
    }

    /** Like [boxBlur], but everything outside the picture counts as 0 (needed for growing and shrinking masks). */
    fun boxBlurZero(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val pw = w + 2 * r
        val ph = h + 2 * r
        val pad = FloatArray(pw * ph)
        for (y in 0 until h) System.arraycopy(src, y * w, pad, (y + r) * pw + r, w)
        val b = boxBlur(pad, pw, ph, r)
        val out = FloatArray(w * h)
        for (y in 0 until h) System.arraycopy(b, (y + r) * pw + r, out, y * w, w)
        return out
    }

    // ------------------------------------------------------------------ mannequin

    /**
     * Turns the masked area into glossy white plastic that keeps the shape: the brightness of the
     * original is smoothed into soft shading, with a bit of shine where it was brightest.
     */
    fun whiten(bmp: Bitmap, r: Rect, a: FloatArray) {
        val w = r.width()
        val h = r.height()
        if (w < 4 || h < 4) return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, r.left, r.top, w, h)

        // shading is worked out on a smaller copy (faster, and uses less memory)
        val s = max(1, max(w, h) / 600)
        val sw = max(2, w / s)
        val sh = max(2, h / s)
        val sl = FloatArray(sw * sh)
        val sa = FloatArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val xx = min(w - 1, x * s + s / 2)
                val yy = min(h - 1, y * s + s / 2)
                val i = yy * w + xx
                val v = px[i]
                val l = 0.299f * ((v shr 16) and 255) + 0.587f * ((v shr 8) and 255) + 0.114f * (v and 255)
                sl[y * sw + x] = l * a[i]
                sa[y * sw + x] = a[i]
            }
        }
        val rs = max(2, min(sw, sh) / 14)
        var num = boxBlur(sl, sw, sh, rs)
        var den = boxBlur(sa, sw, sh, rs)
        run {
            // second pass without the outliers, so small dark or red spots don't leave marks in the shading
            val sl2 = FloatArray(sw * sh)
            val sa2 = FloatArray(sw * sh)
            for (i in sl2.indices) {
                if (sa[i] <= 0f) continue
                val l = sl[i] / sa[i]
                val base0 = if (den[i] > 0.02f) num[i] / den[i] else l
                val wgt = if (kotlin.math.abs(l - base0) < 18f) 1f else 0.05f
                sl2[i] = sl[i] * wgt
                sa2[i] = sa[i] * wgt
            }
            num = boxBlur(sl2, sw, sh, rs)
            den = boxBlur(sa2, sw, sh, rs)
        }
        val shade = FloatArray(sw * sh)
        var sum = 0.0
        var sum2 = 0.0
        var cnt = 0.0
        for (i in shade.indices) {
            shade[i] = if (den[i] > 0.02f) num[i] / den[i] else 128f
            if (sa[i] > 0.5f) {
                sum += shade[i]
                sum2 += shade[i] * shade[i]
                cnt += 1.0
            }
        }
        val mean = if (cnt > 0) (sum / cnt).toFloat() else 128f
        val sd = max(8f, if (cnt > 0) sqrt(max(0.0, sum2 / cnt - mean.toDouble() * mean)).toFloat() else 20f)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val al = a[i]
                if (al <= 0.003f) continue
                val v = px[i]
                val r0 = (v shr 16) and 255
                val g0 = (v shr 8) and 255
                val b0 = v and 255
                val lum = 0.299f * r0 + 0.587f * g0 + 0.114f * b0
                val base = shade[min(sh - 1, y / s) * sw + min(sw - 1, x / s)]
                val n = ((base - mean) / sd).coerceIn(-2f, 2f)
                var white = 238f + 12f * n
                val detail = lum - base
                if (detail > 0f) white += min(28f, detail * 0.7f)   // shine where it was brightest
                if (n > 1f) white += (n - 1f) * 10f
                white = white.coerceIn(170f, 255f)
                val tr = white * 0.992f
                val tg = white * 0.996f
                val nr = (r0 + (tr - r0) * al).toInt().coerceIn(0, 255)
                val ng = (g0 + (tg - g0) * al).toInt().coerceIn(0, 255)
                val nb = (b0 + (white - b0) * al).toInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        bmp.setPixels(px, 0, w, r.left, r.top, w, h)
    }

    /** Replaces a face with a blank, smooth white oval. */
    fun blankFace(bmp: Bitmap, f: Rect) {
        val ex = (f.width() * 0.18f).toInt()
        val ey = (f.height() * 0.25f).toInt()
        val r = Rect(max(0, f.left - ex), max(0, f.top - ey), min(bmp.width, f.right + ex), min(bmp.height, f.bottom + ey))
        val w = r.width()
        val h = r.height()
        if (w < 4 || h < 4) return
        val a = FloatArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x + 0.5f - cx) / cx
                val dy = (y + 0.5f - cy) / cy
                a[y * w + x] = ((1.08f - (dx * dx + dy * dy)) / 0.3f).coerceIn(0f, 1f)
            }
        }
        whiten(bmp, r, a)
    }

    // ------------------------------------------------------------------ smoothing

    /** Fills the pixels where [valid] is false from the ones around them (smooth, multi-scale fill). */
    fun inpaint(px: IntArray, valid: BooleanArray, w: Int, h: Int): IntArray {
        val c = Array(3) { FloatArray(w * h) }
        val wt = FloatArray(w * h)
        for (i in px.indices) {
            if (valid[i]) {
                val v = px[i]
                c[0][i] = ((v shr 16) and 255).toFloat()
                c[1][i] = ((v shr 8) and 255).toFloat()
                c[2][i] = (v and 255).toFloat()
                wt[i] = 1f
            }
        }
        fill(c, wt, w, h)
        repeat(12) {                                   // relax the filled area so it is smooth
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (valid[i]) continue
                    var n = 0
                    var s0 = 0f
                    var s1 = 0f
                    var s2 = 0f
                    if (x > 0) { s0 += c[0][i - 1]; s1 += c[1][i - 1]; s2 += c[2][i - 1]; n++ }
                    if (x < w - 1) { s0 += c[0][i + 1]; s1 += c[1][i + 1]; s2 += c[2][i + 1]; n++ }
                    if (y > 0) { s0 += c[0][i - w]; s1 += c[1][i - w]; s2 += c[2][i - w]; n++ }
                    if (y < h - 1) { s0 += c[0][i + w]; s1 += c[1][i + w]; s2 += c[2][i + w]; n++ }
                    if (n > 0) { c[0][i] = s0 / n; c[1][i] = s1 / n; c[2][i] = s2 / n }
                }
            }
        }
        val out = IntArray(w * h)
        for (i in out.indices) {
            out[i] = (0xFF shl 24) or (c[0][i].toInt().coerceIn(0, 255) shl 16) or
                (c[1][i].toInt().coerceIn(0, 255) shl 8) or c[2][i].toInt().coerceIn(0, 255)
        }
        return out
    }

    private fun fill(c: Array<FloatArray>, wt: FloatArray, w: Int, h: Int) {
        if (w <= 2 || h <= 2) {
            var n = 0
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            for (i in wt.indices) if (wt[i] > 0f) { n++; s0 += c[0][i]; s1 += c[1][i]; s2 += c[2][i] }
            if (n > 0) for (i in wt.indices) if (wt[i] <= 0f) { c[0][i] = s0 / n; c[1][i] = s1 / n; c[2][i] = s2 / n }
            return
        }
        val w2 = (w + 1) / 2
        val h2 = (h + 1) / 2
        val c2 = Array(3) { FloatArray(w2 * h2) }
        val wt2 = FloatArray(w2 * h2)
        for (y2 in 0 until h2) {
            for (x2 in 0 until w2) {
                var n = 0
                var a = 0f
                var b = 0f
                var d = 0f
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val x = 2 * x2 + dx
                        val y = 2 * y2 + dy
                        if (x < w && y < h) {
                            val i = y * w + x
                            if (wt[i] > 0f) { n++; a += c[0][i]; b += c[1][i]; d += c[2][i] }
                        }
                    }
                }
                if (n > 0) {
                    val j = y2 * w2 + x2
                    c2[0][j] = a / n; c2[1][j] = b / n; c2[2][j] = d / n
                    wt2[j] = 1f
                }
            }
        }
        fill(c2, wt2, w2, h2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (wt[i] > 0f) continue
                val fx = (x + 0.5f) / 2f - 0.5f
                val fy = (y + 0.5f) / 2f - 0.5f
                val x0 = fx.toInt().coerceIn(0, w2 - 1)
                val y0 = fy.toInt().coerceIn(0, h2 - 1)
                val x1 = min(w2 - 1, x0 + 1)
                val y1 = min(h2 - 1, y0 + 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val ty = (fy - y0).coerceIn(0f, 1f)
                for (k in 0..2) {
                    val top = c2[k][y0 * w2 + x0] * (1 - tx) + c2[k][y0 * w2 + x1] * tx
                    val bot = c2[k][y1 * w2 + x0] * (1 - tx) + c2[k][y1 * w2 + x1] * tx
                    c[k][i] = top * (1 - ty) + bot * ty
                }
            }
        }
    }

    private fun blend(bmp: Bitmap, r: Rect, px: IntArray, filled: IntArray, alpha: FloatArray) {
        val w = r.width()
        val h = r.height()
        for (i in px.indices) {
            val t = alpha[i].coerceIn(0f, 1f)
            if (t <= 0.003f) continue
            val a = px[i]
            val b = filled[i]
            val nr = (((a shr 16) and 255) + (((b shr 16) and 255) - ((a shr 16) and 255)) * t).toInt().coerceIn(0, 255)
            val ng = (((a shr 8) and 255) + (((b shr 8) and 255) - ((a shr 8) and 255)) * t).toInt().coerceIn(0, 255)
            val nb = ((a and 255) + ((b and 255) - (a and 255)) * t).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        bmp.setPixels(px, 0, w, r.left, r.top, w, h)
    }

    /** Takes away the nipples and areolae: the redder, darker spots are filled in with the skin around them. */
    fun smoothBreast(bmp: Bitmap, box: Rect) {
        val w = box.width()
        val h = box.height()
        if (w < 16 || h < 16) return
        val model = Skin.model(bmp, listOf(box)) ?: return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, box.left, box.top, w, h)
        val score = FloatArray(w * h)
        val skinish = BooleanArray(w * h)
        var n = 0
        for (i in px.indices) {
            val c = Skin.ycc(px[i])
            if (c[0] > 25f && hypot(c[1] - model.cb, c[2] - model.cr) < 30f) {
                skinish[i] = true
                n++
                score[i] = max(0f, (c[2] - model.cr) * 0.9f - (c[1] - model.cb) * 0.3f) + max(0f, model.y - c[0]) * 0.45f
            }
        }
        if (n < 100) return
        val vals = FloatArray(n)
        var k = 0
        for (i in px.indices) if (skinish[i]) vals[k++] = score[i]
        vals.sort()
        val thr = max(7f, vals[(n * 0.84f).toInt().coerceIn(0, n - 1)])
        val m = FloatArray(w * h)
        for (i in px.indices) if (skinish[i] && score[i] > thr) m[i] = 1f
        val b1 = boxBlur(m, w, h, max(2, min(w, h) / 40))          // drop specks
        for (i in m.indices) m[i] = if (b1[i] > 0.4f) 1f else 0f
        val b2 = boxBlur(m, w, h, max(2, min(w, h) / 30))          // grow a little
        for (i in m.indices) m[i] = if (b2[i] > 0.04f) 1f else 0f
        var area = 0
        for (v in m) if (v > 0.5f) area++
        if (area == 0 || area > w * h * 0.4f) return                // not a small spot: leave it alone
        val valid = BooleanArray(w * h) { skinish[it] && m[it] < 0.5f }
        var vc = 0
        for (v in valid) if (v) vc++
        if (vc < 100) return
        val filled = inpaint(px, valid, w, h)
        val alpha = boxBlur(m, w, h, max(1, min(w, h) / 60))
        blend(bmp, box, px, filled, alpha)
    }

    /** Smooths a genital area away: the box is filled in from the skin around it. */
    fun smoothGenital(bmp: Bitmap, box: Rect) {
        val ex = (box.width() * 0.6f).toInt()
        val ey = (box.height() * 0.6f).toInt()
        val ext = Rect(max(0, box.left - ex), max(0, box.top - ey), min(bmp.width, box.right + ex), min(bmp.height, box.bottom + ey))
        val w = ext.width()
        val h = ext.height()
        if (w < 16 || h < 16) return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, ext.left, ext.top, w, h)
        val il = box.left - ext.left
        val it = box.top - ext.top
        val ir = box.right - ext.left
        val ib = box.bottom - ext.top
        val model = Skin.modelRing(bmp, ext, box)
        val valid = BooleanArray(w * h)
        var cnt = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x >= il && x < ir && y >= it && y < ib) continue
                val i = y * w + x
                if (model == null || Skin.chromaDist(px[i], model) < 22f) { valid[i] = true; cnt++ }
            }
        }
        if (cnt < 100) return
        val filled = inpaint(px, valid, w, h)
        val a = FloatArray(w * h)
        for (y in it until ib) for (x in il until ir) a[y * w + x] = 1f
        val soft = boxBlur(a, w, h, max(2, min(ir - il, ib - it) / 12))
        for (i in soft.indices) soft[i] = min(1f, soft[i] * 1.6f)
        blend(bmp, ext, px, filled, soft)
    }
}
