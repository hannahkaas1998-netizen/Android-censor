package com.example.screencensor

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * "Remove nudity": nipples/areolas are painted over with the surrounding skin, and genitals/anus are
 * smoothed out into plain skin. Nothing else is touched.
 */
object Nullify {
    private val BREAST = setOf(3, 5)           // exposed breast (female / male)
    private val GENITAL = setOf(4, 14, 6)      // exposed genitalia (female / male), anus
    val HANDLED: Set<Int> = BREAST + GENITAL

    /** [bmp] is the edited picture, [leftover] the wanted detections this effect can't deal with, [regions] what changed. */
    class Result(val bmp: Bitmap, val leftover: List<Detector.Det>, val regions: List<Rect>)

    /** [all]: every detection (used to learn the skin colour). [d]: the detections the user wants dealt with. */
    fun apply(src: Bitmap, all: List<Detector.Det>, d: List<Detector.Det>): Result {
        val targets = d.filter { it.label in HANDLED }
        val leftover = d.filter { it.label !in HANDLED }
        if (targets.isEmpty()) return Result(src, leftover, emptyList())
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val model = SkinModel.fromDets(out, all)
        val regions = ArrayList<Rect>()
        for (t in targets) {
            val boxes = if (t.label in BREAST && t.box.width() > 1.7f * t.box.height()) {
                // one box around both breasts: treat each half on its own
                val m = t.box.centerX()
                listOf(RectF(t.box.left, t.box.top, m, t.box.bottom), RectF(m, t.box.top, t.box.right, t.box.bottom))
            } else listOf(RectF(t.box))
            for (b in boxes) {
                val r = if (t.label in BREAST) breast(out, b, model) else genital(out, b, model)
                if (r != null) regions.add(r)
            }
        }
        return Result(out, leftover, regions)
    }

    // ---------------------------------------------------------------- nipples

    private fun median(a: FloatArray, n: Int): Float {
        if (n <= 0) return 0f
        val c = a.copyOf(n)
        c.sort()
        return c[n / 2]
    }

    private fun breast(out: Bitmap, box: RectF, model: SkinModel?): Rect? {
        val rc = Blur.clampRect(box, out.width, out.height)
        val w = rc.width()
        val h = rc.height()
        if (w < 16 || h < 16) return null
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, rc.left, rc.top, w, h)

        val yy = FloatArray(w * h)
        val cb = FloatArray(w * h)
        val cr = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = ((p shr 16) and 255).toFloat()
            val g = ((p shr 8) and 255).toFloat()
            val b = (p and 255).toFloat()
            yy[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cb[i] = 128f + (-0.1687f * r - 0.3313f * g + 0.5f * b)
            cr[i] = 128f + (0.5f * r - 0.4187f * g - 0.0813f * b)
        }
        // typical skin colour of this breast = median of a sample of its pixels
        val step = max(1, (w * h) / 4000)
        val sy = FloatArray(w * h / step + 1)
        val sb = FloatArray(sy.size)
        val sr = FloatArray(sy.size)
        var n = 0
        var i0 = 0
        while (i0 < w * h) { sy[n] = yy[i0]; sb[n] = cb[i0]; sr[n] = cr[i0]; n++; i0 += step }
        val my = median(sy, n)
        val mb = median(sb, n)
        val mr = median(sr, n)

        // how different from plain skin each pixel is: redder/browner and darker = nipple or areola
        val dev = FloatArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        val inside = BooleanArray(w * h)
        val inVals = FloatArray(w * h)
        var ni = 0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            dev[i] = abs(cr[i] - mr) + 0.7f * abs(cb[i] - mb) + 0.35f * max(0f, my - yy[i])
            val ex = (x - cx) / (w * 0.45f)
            val ey = (y - cy) / (h * 0.45f)
            if (ex * ex + ey * ey <= 1f) { inside[i] = true; inVals[ni++] = dev[i] }
        }
        val thr = max(8f, 3f * median(inVals, ni))
        var bin = BooleanArray(w * h) { inside[it] && dev[it] > thr }
        bin = Blur.close(Blur.open(bin, w, h, 1), w, h, max(2, (w * 0.012f).toInt()))

        // pick the blob that looks most like a nipple/areola: big enough, near the middle
        val lab = IntArray(w * h)
        val queue = IntArray(w * h)
        var bestScore = 0f
        var bestId = 0
        var bestArea = 0
        var bestX = 0f
        var bestY = 0f
        var id = 0
        val minA = 0.003f * w * h
        val maxA = 0.40f * w * h
        for (s in 0 until w * h) {
            if (!bin[s] || lab[s] != 0) continue
            id++
            var head = 0
            var tail = 0
            queue[tail++] = s
            lab[s] = id
            var sx = 0L
            var sy2 = 0L
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                sx += x; sy2 += y
                if (x > 0 && bin[i - 1] && lab[i - 1] == 0) { lab[i - 1] = id; queue[tail++] = i - 1 }
                if (x < w - 1 && bin[i + 1] && lab[i + 1] == 0) { lab[i + 1] = id; queue[tail++] = i + 1 }
                if (y > 0 && bin[i - w] && lab[i - w] == 0) { lab[i - w] = id; queue[tail++] = i - w }
                if (y < h - 1 && bin[i + w] && lab[i + w] == 0) { lab[i + w] = id; queue[tail++] = i + w }
            }
            val area = tail
            if (area < minA || area > maxA) continue
            val mx = sx.toFloat() / area
            val my2 = sy2.toFloat() / area
            val dist = sqrt((mx - cx) * (mx - cx) + (my2 - cy) * (my2 - cy)) / (0.5f * max(w, h))
            val score = area * (1f - 0.6f * min(1f, dist))
            if (score > bestScore) { bestScore = score; bestId = id; bestArea = area; bestX = mx; bestY = my2 }
        }

        val hole = BooleanArray(w * h)
        if (bestId != 0) {
            for (i in hole.indices) if (lab[i] == bestId) hole[i] = true
            val eq = sqrt(bestArea / PI.toFloat())
            var grown = Blur.morph(hole, w, h, max(3, (eq * 0.35f).toInt()), true)
            if (bestArea < 0.008f * w * h) {
                // only a small speck was found: cover a disc around it as well
                addDisc(grown, w, h, bestX, bestY, 0.09f * min(w, h))
            }
            System.arraycopy(grown, 0, hole, 0, hole.size)
        } else {
            // nothing stood out (covered, dark skin, odd lighting): smooth a disc where a nipple usually is
            addDisc(hole, w, h, cx, cy + 0.04f * h, 0.16f * min(w, h))
        }
        return smooth(out, rc, hole, model)
    }

    private fun addDisc(m: BooleanArray, w: Int, h: Int, cx: Float, cy: Float, r: Float) {
        val x0 = max(0, (cx - r).toInt())
        val x1 = min(w - 1, (cx + r).toInt())
        val y0 = max(0, (cy - r).toInt())
        val y1 = min(h - 1, (cy + r).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r * r) m[y * w + x] = true
        }
    }

    // ---------------------------------------------------------------- genitals

    private fun genital(out: Bitmap, box: RectF, model: SkinModel?): Rect? {
        // work on the box plus some surroundings, so the fill has skin to copy from
        val ctx = Blur.scaled(box, 2.2f)
        val rc = Blur.clampRect(ctx, out.width, out.height)
        val w = rc.width()
        val h = rc.height()
        if (w < 16 || h < 16) return null
        val cx = box.centerX() - rc.left
        val cy = box.centerY() - rc.top
        val rx = max(4f, box.width() * 0.5f * 1.2f)
        val ry = max(4f, box.height() * 0.5f * 1.2f)
        val hole = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = abs((x - cx) / rx)
            val dy = abs((y - cy) / ry)
            // rounded square (exponent 4) so the whole box is covered, not just an ellipse inside it
            if (dx.pow(4) + dy.pow(4) <= 1f) hole[y * w + x] = true
        }
        return smooth(out, rc, hole, model)
    }

    // ---------------------------------------------------------------- the fill

    /** Replaces the masked pixels by smooth skin made from their surroundings, with a little grain so it isn't plastic. */
    private fun smooth(out: Bitmap, rc: Rect, hole: BooleanArray, model: SkinModel?): Rect? {
        val w = rc.width()
        val h = rc.height()
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, rc.left, rc.top, w, h)
        val planes = Array(3) { FloatArray(w * h) }
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            planes[0][i] = ((p shr 16) and 255).toFloat()
            planes[1][i] = ((p shr 8) and 255).toFloat()
            planes[2][i] = (p and 255).toFloat()
            lum[i] = Blur.lum(p)
        }
        val gap = Blur.morph(hole, w, h, 2, true)       // a margin so the old edge pixels can't leak into the fill
        val known = FloatArray(w * h)
        var kc = 0
        for (i in px.indices) if (!gap[i]) {
            known[i] = if (model == null || model.isSkin(px[i], 6f)) 1f else 0.2f
            kc++
        }
        if (kc < 40) return null

        val filled = Inpaint.fill(planes, known, w, h)
        val rad = max(2, (min(w, h) * 0.03f).toInt())
        val blurred = Array(3) { Blur.box(filled[it], w, h, rad, 2) }

        // grain: as much noise as the surrounding skin has
        val lb = Blur.box(lum, w, h, 2, 1)
        var acc = 0.0
        var cnt = 0
        for (i in px.indices) if (known[i] >= 1f) { val d = lum[i] - lb[i]; acc += d * d; cnt++ }
        val sigma = (if (cnt > 0) sqrt(acc / cnt).toFloat() else 0f).coerceIn(0f, 6f) * 0.9f

        val alpha = FloatArray(w * h) { if (gap[it]) 1f else 0f }
        val soft = Blur.box(alpha, w, h, max(1, rad / 2), 2)
        val rng = java.util.Random(7)
        for (i in px.indices) {
            val a = soft[i]
            if (a <= 0.003f) continue
            val g = if (gap[i]) rng.nextGaussian().toFloat() * sigma else 0f
            val r = (planes[0][i] * (1f - a) + (blurred[0][i] + g) * a).toInt().coerceIn(0, 255)
            val gg = (planes[1][i] * (1f - a) + (blurred[1][i] + g) * a).toInt().coerceIn(0, 255)
            val b = (planes[2][i] * (1f - a) + (blurred[2][i] + g) * a).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
        }
        out.setPixels(px, 0, w, rc.left, rc.top, w, h)
        return rc
    }
}
