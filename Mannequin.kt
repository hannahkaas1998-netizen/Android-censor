package com.example.screencensor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * "Mannequin" censor: the person's skin is turned into white glossy plastic. The shading of the original
 * photo is kept (just smoothed), so the body keeps its exact proportions and volume; hair, clothes and
 * background stay as they are. Nipples and genitals are smoothed away first so no detail shows through,
 * and the face can be blanked like a mannequin's.
 *
 * Skin is found by colour, grown out from the detected body parts, so it isn't perfect: it can miss
 * very shadowed or very bright skin and sometimes grabs hair or background of a similar colour.
 */
object Mannequin {
    private val SEEDS = setOf(1, 12, 2, 3, 4, 5, 6, 7, 11, 13, 14)
    private val FACES = setOf(1, 12)
    private val DETAIL = setOf(3, 5, 4, 14, 6)

    /** [leftover]: wanted detections that couldn't be turned into a mannequin (caller covers them another way). */
    class Out(val bmp: Bitmap, val leftover: List<Detector.Det>, val regions: List<Rect>)

    private fun grow(b: RectF, f: Float): RectF {
        val d = f * max(b.width(), b.height())
        return RectF(b.left - d, b.top - d, b.right + d, b.bottom + d)
    }

    /** Detections whose (grown) boxes touch belong to the same person. */
    private fun group(seeds: List<Detector.Det>): List<List<Detector.Det>> {
        val n = seeds.size
        val parent = IntArray(n) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (RectF.intersects(grow(seeds[i].box, 0.5f), grow(seeds[j].box, 0.5f))) parent[find(i)] = find(j)
        }
        val map = LinkedHashMap<Int, MutableList<Detector.Det>>()
        for (i in 0 until n) map.getOrPut(find(i)) { ArrayList() }.add(seeds[i])
        return map.values.toList()
    }

    fun apply(src: Bitmap, all: List<Detector.Det>, want: Set<Int>, blankFace: Boolean): Out? {
        val seeds = all.filter { it.label in SEEDS }
        if (seeds.isEmpty()) return null
        val groups = group(seeds).filter { g -> g.any { it.label in want } }
        if (groups.isEmpty()) return null

        // 1. smooth nipples and genitals away so they can't show through the shading
        val detail = groups.flatten().filter { it.label in DETAIL }
        val cleaned = if (detail.isEmpty()) src else Nullify.apply(src, all, detail).bmp

        // 2. find the skin at a smaller working size
        val W = src.width
        val H = src.height
        val s = min(1f, 720f / max(W, H))
        val ww = max(8, (W * s).toInt())
        val wh = max(8, (H * s).toInt())
        val work = if (s < 1f) Bitmap.createScaledBitmap(cleaned, ww, wh, true) else cleaned
        val wp = IntArray(ww * wh)
        work.getPixels(wp, 0, ww, 0, 0, ww, wh)
        val sx = ww.toFloat() / W
        val sy = wh.toFloat() / H
        fun toWork(b: RectF) = RectF(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy)

        val mk = BooleanArray(ww * wh)
        val faces = ArrayList<RectF>()
        val regions = ArrayList<Rect>()
        val failed = ArrayList<Detector.Det>()
        val rc = max(2, (0.006f * max(ww, wh)).toInt())

        for (g in groups) {
            val boxes = g.map { toWork(it.box) }
            val u = RectF(boxes[0])
            boxes.forEach { u.union(it) }
            val region = Blur.clampRect(grow(u, 0.5f), ww, wh)

            // skin colour of this person
            val buf = IntArray(8000)
            var n = 0
            fun sample(b: RectF) {
                val r = Blur.clampRect(b, ww, wh)
                val step = max(1, (r.width() * r.height()) / 1200)
                var k = 0
                val total = r.width() * r.height()
                while (k < total && n < buf.size) {
                    buf[n++] = wp[(r.top + k / r.width()) * ww + r.left + k % r.width()]
                    k += step
                }
            }
            for ((i, d) in g.withIndex()) if (d.label !in FACES) sample(Blur.scaled(boxes[i], 0.7f))
            if (n < 24) for ((i, d) in g.withIndex()) if (d.label in FACES) sample(Blur.scaled(boxes[i], 0.4f))
            val model = SkinModel.fit(buf, n)
            if (model == null) { failed.addAll(g.filter { it.label in want }); continue }

            // candidate skin inside the person's area
            val cand = BooleanArray(ww * wh)
            for (y in region.top until region.bottom) for (x in region.left until region.right) {
                if (model.isSkin(wp[y * ww + x])) cand[y * ww + x] = true
            }
            // the detected parts themselves always count, even where the colour is odd (shadows, areolas)
            val core = BooleanArray(ww * wh)
            val gFaces = ArrayList<RectF>()
            for ((i, d) in g.withIndex()) {
                val b = Blur.scaled(boxes[i], 0.95f)
                val isFace = d.label in FACES
                if (isFace) gFaces.add(RectF(boxes[i]))
                if (isFace && !blankFace) continue
                paintEllipse(core, ww, wh, b)
            }
            for (i in cand.indices) if (core[i]) cand[i] = true
            if (!blankFace) {
                // keep the real face: take it out of the candidates
                for (fb in gFaces) eraseEllipse(cand, ww, wh, Blur.scaled(fb, 1.1f))
            }
            val closed = Blur.close(cand, ww, wh, rc)
            val reach = Blur.flood(closed, ww, wh, core)
            val tidy = Blur.open(reach, ww, wh, max(1, rc / 2))
            var any = false
            var minX = ww; var minY = wh; var maxX = 0; var maxY = 0
            for (y in 0 until wh) for (x in 0 until ww) {
                if (tidy[y * ww + x]) {
                    mk[y * ww + x] = true
                    any = true
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
            if (!any) { failed.addAll(g.filter { it.label in want }); continue }
            if (blankFace) faces.addAll(gFaces)
            regions.add(Rect(
                (minX / sx).toInt().coerceAtLeast(0), (minY / sy).toInt().coerceAtLeast(0),
                (maxX / sx).toInt().coerceAtMost(W), (maxY / sy).toInt().coerceAtMost(H)
            ))
        }
        if (regions.isEmpty()) return null

        // 3. shading: keep the light and shadow of the photo, throw away colour and texture
        val soft = FloatArray(ww * wh) { if (mk[it]) 1f else 0f }
        val lumv = FloatArray(ww * wh) { Blur.lum(wp[it]) / 255f }
        val r = max(2, (0.006f * max(ww, wh)).toInt())
        val num = Blur.box(FloatArray(ww * wh) { lumv[it] * soft[it] }, ww, wh, r, 2)
        val den = Blur.box(soft, ww, wh, r, 2)
        val ls = FloatArray(ww * wh) { if (den[it] > 1e-3f) num[it] / den[it] else lumv[it] }
        flattenFaces(lumv, soft, ww, wh, ls, faces)

        var sum = 0.0
        var cnt = 0
        for (i in ls.indices) if (mk[i]) { sum += ls[i]; cnt++ }
        val ref = if (cnt > 0) (sum / cnt).toFloat().coerceAtLeast(0.05f) else 0.5f

        val edge = Blur.box(soft, ww, wh, max(1, (0.003f * max(ww, wh)).toInt()), 2)
        val mq = IntArray(ww * wh)
        for (i in mq.indices) {
            val a = (edge[i] * 255f).toInt().coerceIn(0, 255)
            mq[i] = if (a == 0) 0 else (a shl 24) or gloss(ls[i] / ref)
        }
        val mqBmp = Bitmap.createBitmap(ww, wh, Bitmap.Config.ARGB_8888)
        mqBmp.setPixels(mq, 0, ww, 0, 0, ww, wh)

        // 4. paint it over the (cleaned) picture at full size
        val out = cleaned.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(mqBmp, null, Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
        mqBmp.recycle()
        val leftover = failed.distinct() +
            all.filter { it.label in want && (it.label !in SEEDS || (!blankFace && it.label in FACES)) }
        return Out(out, leftover, regions)
    }

    private fun paintEllipse(m: BooleanArray, w: Int, h: Int, b: RectF) {
        val cx = b.centerX()
        val cy = b.centerY()
        val rx = max(1f, b.width() / 2f)
        val ry = max(1f, b.height() / 2f)
        for (y in max(0, b.top.toInt())..min(h - 1, b.bottom.toInt())) {
            for (x in max(0, b.left.toInt())..min(w - 1, b.right.toInt())) {
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                if (dx * dx + dy * dy <= 1f) m[y * w + x] = true
            }
        }
    }

    private fun eraseEllipse(m: BooleanArray, w: Int, h: Int, b: RectF) {
        val cx = b.centerX()
        val cy = b.centerY()
        val rx = max(1f, b.width() / 2f)
        val ry = max(1f, b.height() / 2f)
        for (y in max(0, b.top.toInt())..min(h - 1, b.bottom.toInt())) {
            for (x in max(0, b.left.toInt())..min(w - 1, b.right.toInt())) {
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                if (dx * dx + dy * dy <= 1f) m[y * w + x] = false
            }
        }
    }

    /** Inside each face, blur the lighting heavily so eyes, nose and mouth disappear but the head keeps its shape. */
    private fun flattenFaces(lum: FloatArray, mask: FloatArray, w: Int, h: Int, ls: FloatArray, faces: List<RectF>) {
        for (f in faces) {
            val pad = 0.25f * max(f.width(), f.height())
            val l = max(0, (f.left - pad).toInt())
            val t = max(0, (f.top - pad).toInt())
            val rr = min(w, (f.right + pad).toInt())
            val b = min(h, (f.bottom + pad).toInt())
            val cw = rr - l
            val ch = b - t
            if (cw < 4 || ch < 4) continue
            val a = FloatArray(cw * ch)
            val m = FloatArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                val i = (t + y) * w + l + x
                a[y * cw + x] = lum[i] * mask[i]
                m[y * cw + x] = mask[i]
            }
            val rad = max(2, (0.16f * f.width()).toInt())
            val na = Blur.box(a, cw, ch, rad, 3)
            val nm = Blur.box(m, cw, ch, rad, 3)
            for (y in 0 until ch) for (x in 0 until cw) {
                val ex = ((l + x) - f.centerX()) / (f.width() * 0.5f)
                val ey = ((t + y) - f.centerY()) / (f.height() * 0.5f)
                val wgt = ((1.15f - (ex * ex + ey * ey)) / 0.35f).coerceIn(0f, 1f)
                val j = y * cw + x
                if (wgt > 0f && nm[j] > 1e-3f) {
                    val i = (t + y) * w + l + x
                    ls[i] = ls[i] * (1f - wgt) + (na[j] / nm[j]) * wgt
                }
            }
        }
    }

    /** White glossy plastic: [rel] is the lighting relative to the average skin lighting (1 = average). */
    private fun gloss(rel: Float): Int {
        var v = (0.80f + 0.85f * (rel - 1f)).coerceIn(0.18f, 1f)
        val t = ((rel - 1.10f) / 0.35f).coerceIn(0f, 1f)
        val spec = (t * t * (3f - 2f * t)).pow(1.5f)      // bright spots turn into shiny highlights
        v += (1f - v) * spec
        val r = v * (0.95f + 0.05f * v)
        val g = v * (0.965f + 0.035f * v)
        val b = v * (0.99f + 0.01f * v)
        return ((r * 255f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255f).toInt().coerceIn(0, 255) shl 8) or
            (b * 255f).toInt().coerceIn(0, 255)
    }
}
