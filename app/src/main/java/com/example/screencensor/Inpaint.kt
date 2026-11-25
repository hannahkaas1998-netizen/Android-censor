package com.example.screencensor

/**
 * Fills gaps with smooth colour taken from the surroundings ("push-pull" pyramid fill).
 * Used to make nipples and genitals disappear into plain skin.
 */
internal object Inpaint {
    /** [planes]: 3 colour planes (0..255). [known]: 0..1 how much each pixel can be trusted. */
    fun fill(planes: Array<FloatArray>, known: FloatArray, w: Int, h: Int): Array<FloatArray> {
        val cs = ArrayList<Array<FloatArray>>()
        val ws = ArrayList<FloatArray>()
        val dw = ArrayList<Int>()
        val dh = ArrayList<Int>()
        cs.add(Array(3) { c -> FloatArray(w * h) { i -> planes[c][i] * known[i] } })
        ws.add(known.copyOf())
        dw.add(w); dh.add(h)
        var cw = w
        var ch = h
        while (cw > 1 || ch > 1) {
            val nw = (cw + 1) / 2
            val nh = (ch + 1) / 2
            val pc = cs[cs.size - 1]
            val pw = ws[ws.size - 1]
            val c = Array(3) { FloatArray(nw * nh) }
            val wt = FloatArray(nw * nh)
            for (y in 0 until ch) {
                val row = (y / 2) * nw
                for (x in 0 until cw) {
                    val s = y * cw + x
                    val d = row + x / 2
                    wt[d] += pw[s] * 0.25f
                    for (k in 0 until 3) c[k][d] += pc[k][s] * 0.25f
                }
            }
            cs.add(c); ws.add(wt); dw.add(nw); dh.add(nh)
            cw = nw; ch = nh
        }
        val top = cs.size - 1
        var cur = Array(3) { k ->
            FloatArray(cs[top][k].size) { i -> if (ws[top][i] > 1e-6f) cs[top][k][i] / ws[top][i] else 128f }
        }
        for (lv in top - 1 downTo 0) {
            val up = Array(3) { k -> upsample(cur[k], dw[lv + 1], dh[lv + 1], dw[lv], dh[lv]) }
            val wl = ws[lv]
            val cl = cs[lv]
            cur = Array(3) { k -> FloatArray(dw[lv] * dh[lv]) { i -> cl[k][i] + up[k][i] * (1f - wl[i]) } }
        }
        return cur
    }

    private fun upsample(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = minOf(sh - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = minOf(sw - 1, x0 + 1)
                val tx = fx - x0
                val a = src[y0 * sw + x0] * (1f - tx) + src[y0 * sw + x1] * tx
                val b = src[y1 * sw + x0] * (1f - tx) + src[y1 * sw + x1] * tx
                out[y * dw + x] = a * (1f - ty) + b * ty
            }
        }
        return out
    }
}
