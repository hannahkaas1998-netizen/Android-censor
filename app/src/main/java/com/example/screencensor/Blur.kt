package com.example.screencensor

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Small image-processing helpers shared by the censor effects (all work on plain arrays). */
internal object Blur {
    /** Box blur repeated [passes] times (close to a gaussian). Edges only average the pixels that exist. */
    fun box(src: FloatArray, w: Int, h: Int, r: Int, passes: Int = 2): FloatArray {
        val a = src.copyOf()
        if (r <= 0) return a
        val pre = DoubleArray(max(w, h) + 1)
        repeat(passes) {
            for (y in 0 until h) {
                val o = y * w
                pre[0] = 0.0
                for (x in 0 until w) pre[x + 1] = pre[x] + a[o + x]
                for (x in 0 until w) {
                    val l = max(0, x - r)
                    val rr = min(w - 1, x + r)
                    a[o + x] = ((pre[rr + 1] - pre[l]) / (rr - l + 1)).toFloat()
                }
            }
            for (x in 0 until w) {
                pre[0] = 0.0
                for (y in 0 until h) pre[y + 1] = pre[y] + a[y * w + x]
                for (y in 0 until h) {
                    val t = max(0, y - r)
                    val b = min(h - 1, y + r)
                    a[y * w + x] = ((pre[b + 1] - pre[t]) / (b - t + 1)).toFloat()
                }
            }
        }
        return a
    }

    /** Grows (or shrinks) a mask by r pixels with a square window. */
    fun morph(m: BooleanArray, w: Int, h: Int, r: Int, grow: Boolean): BooleanArray {
        if (r <= 0) return m.copyOf()
        val tmp = BooleanArray(m.size)
        val pre = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) pre[x + 1] = pre[x] + (if (m[o + x]) 1 else 0)
            for (x in 0 until w) {
                val l = max(0, x - r)
                val rr = min(w - 1, x + r)
                val c = pre[rr + 1] - pre[l]
                tmp[o + x] = if (grow) c > 0 else c == rr - l + 1
            }
        }
        val out = BooleanArray(m.size)
        for (x in 0 until w) {
            for (y in 0 until h) pre[y + 1] = pre[y] + (if (tmp[y * w + x]) 1 else 0)
            for (y in 0 until h) {
                val t = max(0, y - r)
                val b = min(h - 1, y + r)
                val c = pre[b + 1] - pre[t]
                out[y * w + x] = if (grow) c > 0 else c == b - t + 1
            }
        }
        return out
    }

    fun close(m: BooleanArray, w: Int, h: Int, r: Int) = morph(morph(m, w, h, r, true), w, h, r, false)
    fun open(m: BooleanArray, w: Int, h: Int, r: Int) = morph(morph(m, w, h, r, false), w, h, r, true)

    /** Everything in [mask] that can be reached from the [seed] cells (4-neighbour flood fill). */
    fun flood(mask: BooleanArray, w: Int, h: Int, seed: BooleanArray): BooleanArray {
        val out = BooleanArray(mask.size)
        val q = IntArray(mask.size)
        var head = 0
        var tail = 0
        for (i in mask.indices) if (seed[i] && mask[i]) { out[i] = true; q[tail++] = i }
        while (head < tail) {
            val i = q[head++]
            val x = i % w
            val y = i / w
            if (x > 0 && mask[i - 1] && !out[i - 1]) { out[i - 1] = true; q[tail++] = i - 1 }
            if (x < w - 1 && mask[i + 1] && !out[i + 1]) { out[i + 1] = true; q[tail++] = i + 1 }
            if (y > 0 && mask[i - w] && !out[i - w]) { out[i - w] = true; q[tail++] = i - w }
            if (y < h - 1 && mask[i + w] && !out[i + w]) { out[i + w] = true; q[tail++] = i + w }
        }
        return out
    }

    fun clampRect(r: RectF, w: Int, h: Int): Rect {
        val l = r.left.toInt().coerceIn(0, w - 1)
        val t = r.top.toInt().coerceIn(0, h - 1)
        return Rect(l, t, r.right.toInt().coerceIn(l + 1, w), r.bottom.toInt().coerceIn(t + 1, h))
    }

    /** The rectangle scaled by f around its centre. */
    fun scaled(r: RectF, f: Float): RectF {
        val dx = r.width() * (f - 1f) / 2f
        val dy = r.height() * (f - 1f) / 2f
        return RectF(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
    }

    fun lum(p: Int): Float =
        0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
}
