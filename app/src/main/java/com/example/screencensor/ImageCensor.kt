package com.example.screencensor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import kotlin.math.max
import kotlin.math.min

/** Edits the actual picture: censors it, or focuses it on one thing. Used by the safe browser. */
object ImageCensor {
    class Engine(ctx: Context) {
        val nude = Detector(ctx)
        val heels: HeelDetector? = try {
            if (ctx.assets.list("")?.contains("heels.onnx") == true) HeelDetector(ctx) else null
        } catch (e: Throwable) { null }
    }

    private val NUDE_F = setOf(2, 3, 4, 6)
    private val BUTT = setOf(2, 17)
    private val MALE = setOf(14)
    private val BODY = setOf(2, 3, 4, 6, 13, 14, 16, 17)   // where a cover can follow the body shape

    fun wanted(p: SharedPreferences): Set<Int> {
        val s = HashSet<Int>()
        if (p.getBoolean("nudity", true)) s.addAll(NUDE_F)
        if (p.getBoolean("male", true)) s.addAll(MALE)
        if (p.getBoolean("butt", true)) s.addAll(BUTT)
        if (p.getBoolean("cleavage", true)) s.addAll(Detector.CLEAVAGE)
        if (p.getBoolean("face", false) || p.getBoolean("eyes", false)) s.addAll(Detector.FACE)
        if (p.getString("style", "mosaic") == "mannequin" && p.getBoolean("mface", true)) s.addAll(Detector.FACE)
        if (p.getBoolean("feet", false)) s.addAll(Detector.FEET)
        return s
    }

    /** Returns the edited picture, or null if the original can be shown untouched. */
    fun process(e: Engine, src: Bitmap, p: SharedPreferences): Bitmap? {
        var work = src
        var changed = false

        val mode = p.getString("heelmode", "off") ?: "off"
        if (mode != "off") {
            val boxes = focusBoxes(e, work, p)
            if (boxes.isNotEmpty()) {
                work = when (mode) {
                    "zoom" -> zoom(work, boxes)
                    "isolate_blur" -> isolate(work, boxes, true, p)
                    else -> isolate(work, boxes, false, p)
                }
                changed = true
            }
            // nothing to focus on: the picture just goes through the normal censoring below
        }

        val want = wanted(p)
        if (want.isNotEmpty()) {
            val dets = e.nude.detect(work, p.getFloat("sens", 0.35f)).filter { it.label in want }
            if (dets.isNotEmpty()) {
                val c = censor(work, dets, p)
                if (c != null) {
                    work = c
                    changed = true
                }
            }
        }
        return if (changed) work else null
    }

    // ---------------- focus (zoom / isolate) ----------------

    private fun focusBoxes(e: Engine, work: Bitmap, p: SharedPreferences): List<RectF> {
        if ((p.getString("focustarget", "heels") ?: "heels") == "male") {
            return e.nude.detect(work, 0.25f).filter { it.label == 14 }.map { RectF(it.box) }
        }
        val hd = e.heels ?: return emptyList()
        val more = p.getBoolean("heelmore", true)
        val thr = (0.35f - 0.033f * p.getInt("heelsens", 7)).coerceAtLeast(0.02f)
        val res = hd.detect(work, thr)
        val hs = ArrayList<RectF>(res.heels)
        if (hs.isEmpty() && more) {
            // no real heels: other footwear and feet count instead
            hs.addAll(res.footwear)
            e.nude.detect(work, 0.25f).filter { it.label in Detector.FEET }.forEach { hs.add(RectF(it.box)) }
        }
        return hs
    }

    private fun zoom(src: Bitmap, hs: List<RectF>): Bitmap {
        val u = RectF(hs[0])
        hs.forEach { u.union(it) }
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val px = u.width() * 0.15f
        val py = u.height() * 0.15f
        var l = max(0f, u.left - px)
        var t = max(0f, u.top - py)
        var cw = min(w, u.right + px) - l
        var ch = min(h, u.bottom + py) - t
        // keep a sensible shape (between 3:4 tall and 16:9 wide) by growing the short side
        val ar = cw / ch
        if (ar < 0.75f) { val nw = ch * 0.75f; l -= (nw - cw) / 2; cw = nw }
        else if (ar > 1.78f) { val nh = cw / 1.78f; t -= (nh - ch) / 2; ch = nh }
        cw = min(cw, w)
        ch = min(ch, h)
        l = l.coerceIn(0f, w - cw)
        t = t.coerceIn(0f, h - ch)
        val crop = Bitmap.createBitmap(src, l.toInt(), t.toInt(), max(2, cw.toInt()), max(2, ch.toInt()))
        // blow it up to fill the screen width (at most 3x, never above 1600px)
        val k = min(min(3f, max(1f, 1080f / crop.width)), 1600f / max(crop.width, crop.height))
        if (k <= 1.02f) return crop
        return Bitmap.createScaledBitmap(crop, (crop.width * k).toInt(), (crop.height * k).toInt(), true)
    }

    private fun isolate(src: Bitmap, hs: List<RectF>, blur: Boolean, p: SharedPreferences): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        if (blur) {
            val d = max(4, (min(w, h) * 0.02f * p.getInt("blurs", 5)).toInt())
            val small = Bitmap.createScaledBitmap(src, max(1, w / d), max(1, h / d), true)
            cv.drawBitmap(small, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        } else cv.drawColor(Color.BLACK)
        for (r in hs) {
            val px = r.width() * 0.12f
            val py = r.height() * 0.12f
            val ri = Rect(
                (r.left - px).toInt().coerceAtLeast(0), (r.top - py).toInt().coerceAtLeast(0),
                (r.right + px).toInt().coerceAtMost(w), (r.bottom + py).toInt().coerceAtMost(h)
            )
            cv.drawBitmap(src, ri, ri, null)
        }
        return out
    }

    // ---------------- censoring ----------------

    private class Region(val rect: Rect, val label: Int)

    private fun toRect(r: RectF, bmp: Bitmap): Rect {
        val l = r.left.toInt().coerceIn(0, bmp.width - 2)
        val t = r.top.toInt().coerceIn(0, bmp.height - 2)
        return Rect(l, t, r.right.toInt().coerceIn(l + 2, bmp.width), r.bottom.toInt().coerceIn(t + 2, bmp.height))
    }

    private fun grow(r: Rect, f: Float, bmp: Bitmap): Rect {
        val dx = (r.width() * f).toInt()
        val dy = (r.height() * f).toInt()
        return Rect(max(0, r.left - dx), max(0, r.top - dy), min(bmp.width, r.right + dx), min(bmp.height, r.bottom + dy))
    }

    private fun regionsFor(d: List<Detector.Det>, bmp: Bitmap, p: SharedPreferences): List<Region> {
        val faceOn = p.getBoolean("face", false)
        val eyesOn = p.getBoolean("eyes", false)
        val items = d.map { det ->
            val b = det.box
            val rf = if (det.label in Detector.FACE && eyesOn && !faceOn)
                RectF(b.left, b.top + b.height() * 0.2f, b.right, b.top + b.height() * 0.55f)
            else RectF(b)
            Pair(rf, det.label)
        }
        return when (p.getString("cover", "parts")) {
            "screen" -> listOf(Region(Rect(0, 0, bmp.width, bmp.height), -1))
            "group" -> {
                val u = RectF(items[0].first)
                items.forEach { u.union(it.first) }
                listOf(Region(grow(toRect(u, bmp), 0.15f, bmp), -1))
            }
            else -> items.map { Region(grow(toRect(it.first, bmp), 0.08f, bmp), it.second) }
        }
    }

    /** Returns null when nothing needs to change. */
    private fun censor(src: Bitmap, d: List<Detector.Det>, p: SharedPreferences): Bitmap? {
        val style = p.getString("style", "mosaic") ?: "mosaic"
        // faces alone never trigger the mannequin / smoothing looks
        if ((style == "mannequin" || style == "smooth") && d.all { it.label in Detector.FACE }) return null
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        when (style) {
            "mannequin" -> { mannequin(out, d, p); return out }
            "smooth" -> { smooth(out, d, p); return out }
        }
        val cv = Canvas(out)
        val words = (p.getString("words", "LOSER, PIG, GOONER") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val useLabels = p.getBoolean("labels", true) && words.isNotEmpty()
        regionsFor(d, out, p).forEachIndexed { i, rg ->
            classic(out, cv, rg.rect, rg.label, style, p)
            if (useLabels && rg.rect.width() > 120) drawLabel(cv, rg.rect, words[i % words.size])
        }
        return out
    }

    /** Mosaic / blur / solid, optionally cut to the shape of the body instead of a box. */
    private fun classic(out: Bitmap, cv: Canvas, r: Rect, label: Int, style: String, p: SharedPreferences) {
        val w = r.width()
        val h = r.height()
        val crop = Bitmap.createBitmap(out, r.left, r.top, w, h)
        val fx: Bitmap = when (style) {
            "solid" -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.rgb(30, 30, 30)) }
            "blur" -> {
                val d = max(2, (min(w, h) * 0.012f * p.getInt("blurs", 5)).toInt())
                val small = Bitmap.createScaledBitmap(crop, max(1, w / d), max(1, h / d), true)
                Bitmap.createScaledBitmap(small, w, h, true)
            }
            else -> Mosaic.make(crop, p.getInt("pixel", 6))
        }
        var alpha: FloatArray? = null
        if (p.getBoolean("shape", true) && label in BODY) {
            val model = Skin.model(out, listOf(r))
            if (model != null) {
                val m = Skin.mask(out, r, model, 13f, 0.1f)
                // the middle of the box is always covered, whatever the colours say
                for (y in h / 5 until h - h / 5) for (x in w / 5 until w - w / 5) m[y * w + x] = 1f
                var s = 0.0
                for (v in m) s += v
                val cov = s / m.size
                if (cov > 0.1 && cov < 0.97) alpha = m
            }
        }
        val a = alpha
        if (a == null) {
            cv.drawBitmap(fx, null, r, null)
            return
        }
        val po = IntArray(w * h)
        val pf = IntArray(w * h)
        crop.getPixels(po, 0, w, 0, 0, w, h)
        fx.getPixels(pf, 0, w, 0, 0, w, h)
        for (i in po.indices) {
            val t = a[i]
            if (t >= 0.999f) po[i] = pf[i]
            else if (t > 0.003f) {
                val x = po[i]
                val y = pf[i]
                val nr = (((x shr 16) and 255) + (((y shr 16) and 255) - ((x shr 16) and 255)) * t).toInt().coerceIn(0, 255)
                val ng = (((x shr 8) and 255) + (((y shr 8) and 255) - ((x shr 8) and 255)) * t).toInt().coerceIn(0, 255)
                val nb = ((x and 255) + ((y and 255) - (x and 255)) * t).toInt().coerceIn(0, 255)
                po[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        out.setPixels(po, 0, w, r.left, r.top, w, h)
    }

    /** Turns the skin into glossy white plastic with the same shape, and the face into a blank oval. */
    private fun mannequin(out: Bitmap, d: List<Detector.Det>, p: SharedPreferences) {
        val faceDets = d.filter { it.label in Detector.FACE }
        val bodyDets = d.filter { it.label !in Detector.FACE }
        val boxes = bodyDets.map { toRect(it.box, out) }
        val model = Skin.model(out, boxes)
        val areas: List<Rect> = when (p.getString("cover", "parts")) {
            "screen" -> listOf(Rect(0, 0, out.width, out.height))
            "group" -> {
                val u = RectF(bodyDets[0].box)
                bodyDets.forEach { u.union(it.box) }
                listOf(grow(toRect(u, out), 0.15f, out))
            }
            else -> boxes.map { grow(it, 0.15f, out) }
        }
        if (model != null) {
            for (a in areas) Fx.whiten(out, a, Skin.mask(out, a, model, 13f, 0.06f, keepClothes = true))
        } else {
            // couldn't read the skin colour: plain mosaic instead
            val cv = Canvas(out)
            for (b in boxes) cv.drawBitmap(Mosaic.make(Bitmap.createBitmap(out, b.left, b.top, b.width(), b.height()), p.getInt("pixel", 6)), null, b, null)
        }
        if (p.getBoolean("mface", true)) for (f in faceDets) Fx.blankFace(out, toRect(f.box, out))
    }

    /** Nullifies nudity: nipples disappear, genitals are smoothed away. Everything else gets a mosaic. */
    private fun smooth(out: Bitmap, d: List<Detector.Det>, p: SharedPreferences) {
        val cv = Canvas(out)
        for (det in d) {
            when (det.label) {
                3 -> Fx.smoothBreast(out, toRect(det.box, out))
                4, 14, 6 -> Fx.smoothGenital(out, grow(toRect(det.box, out), 0.12f, out))
                else -> {
                    val r = grow(toRect(det.box, out), 0.08f, out)
                    cv.drawBitmap(Mosaic.make(Bitmap.createBitmap(out, r.left, r.top, r.width(), r.height()), p.getInt("pixel", 6)), null, r, null)
                }
            }
        }
    }

    // ---------------- text and placeholder ----------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }

    @Synchronized
    private fun drawLabel(cv: Canvas, r: Rect, text: String) {
        fill.textSize = 100f
        val tw = fill.measureText(text)
        if (tw <= 0f) return
        val size = (100f * r.width() * 0.9f / tw).coerceAtMost(r.height() * 0.5f).coerceAtLeast(10f)
        fill.textSize = size
        outline.textSize = size
        outline.strokeWidth = size * 0.14f
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        val y = cy - (fill.ascent() + fill.descent()) / 2f
        cv.save()
        cv.rotate(-8f, cx, cy)
        cv.drawText(text, cx, y, outline)
        cv.drawText(text, cx, y, fill)
        cv.restore()
    }

    fun placeholder(w: Int, h: Int, text: String): Bitmap {
        val ww = w.coerceIn(160, 1600)
        val hh = h.coerceIn(120, 1600)
        val b = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawColor(Color.rgb(28, 28, 28))
        drawLabel(cv, Rect(0, 0, ww, hh), text)
        return b
    }
}
