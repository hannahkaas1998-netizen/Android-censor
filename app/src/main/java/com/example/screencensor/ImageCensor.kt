package com.example.screencensor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import kotlin.math.max
import kotlin.math.min

/** Edits the actual picture: censors it, or focuses it on high heels / male genitals. Used by the safe browser. */
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
    private val ROUND = setOf(2, 3, 5, 8, 13, 16, 17)      // shapes that get a rounded mosaic instead of a square one

    fun wanted(p: SharedPreferences): Set<Int> {
        val s = HashSet<Int>()
        if (p.getBoolean("nudity", true)) s.addAll(NUDE_F)
        if (p.getBoolean("male", true)) s.addAll(MALE)
        if (p.getBoolean("butt", true)) s.addAll(BUTT)
        if (p.getBoolean("cleavage", true)) s.addAll(Detector.CLEAVAGE)
        if (p.getBoolean("face", false) || p.getBoolean("eyes", false)) s.addAll(Detector.FACE)
        if (p.getBoolean("feet", false)) s.addAll(Detector.FEET)
        return s
    }

    /** Returns the edited picture, or null if the original can be shown untouched. */
    fun process(e: Engine, src: Bitmap, p: SharedPreferences): Bitmap? {
        var work = src
        var changed = false

        val mode = p.getString("heelmode", "off") ?: "off"
        if (mode != "off") {
            val target = p.getString("focus", "heels") ?: "heels"       // heels, cock or both
            val hs = ArrayList<RectF>()
            if (target != "cock") {
                val hd = e.heels
                if (hd != null) {
                    val more = p.getBoolean("heelmore", true)
                    val thr = (0.35f - 0.033f * p.getInt("heelsens", 7)).coerceAtLeast(0.02f)
                    val res = hd.detect(work, thr)
                    hs.addAll(res.heels)
                    if (hs.isEmpty() && more) {
                        // no real heels: other footwear and feet count instead
                        hs.addAll(res.footwear)
                        e.nude.detect(work, 0.25f).filter { it.label in Detector.FEET }.forEach { hs.add(RectF(it.box)) }
                    }
                }
            }
            if (target != "heels") {
                e.nude.detect(work, p.getFloat("sens", 0.35f)).filter { it.label in MALE }.forEach { hs.add(RectF(it.box)) }
            }
            if (hs.isNotEmpty()) {
                work = when (mode) {
                    "zoom" -> zoom(work, hs)
                    "isolate_blur" -> isolate(work, hs, true, p)
                    else -> isolate(work, hs, false, p)
                }
                changed = true
            }
            // nothing found: the picture just goes through the normal censoring below
        }

        val want = wanted(p)
        if (want.isNotEmpty()) {
            val all = e.nude.detect(work, p.getFloat("sens", 0.35f))
            val dets = all.filter { it.label in want }
            if (dets.isNotEmpty()) {
                work = censor(work, all, dets, want, p)
                changed = true
            }
        }
        return if (changed) work else null
    }

    // ---------------- focus (zoom / isolate) ----------------

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

    // ---------------- nudity censoring ----------------

    private fun baseBoxes(d: List<Detector.Det>, p: SharedPreferences): List<RectF> {
        val faceOn = p.getBoolean("face", false)
        val eyesOn = p.getBoolean("eyes", false)
        return d.map { det ->
            val b = det.box
            if (det.label in Detector.FACE && eyesOn && !faceOn)
                RectF(b.left, b.top + b.height() * 0.2f, b.right, b.top + b.height() * 0.55f)
            else RectF(b)
        }
    }

    private fun padded(r: RectF, f: Float, bmp: Bitmap): RectF {
        val dx = r.width() * f
        val dy = r.height() * f
        return RectF(
            max(0f, r.left - dx), max(0f, r.top - dy),
            min(bmp.width.toFloat(), r.right + dx), min(bmp.height.toFloat(), r.bottom + dy)
        )
    }

    /** The areas to cover. Breasts, buttocks etc. get a rounded shape so the mosaic follows the body. */
    private fun shapesFor(d: List<Detector.Det>, bmp: Bitmap, p: SharedPreferences): List<Pixelate.Shape> {
        val base = baseBoxes(d, p)
        return when (p.getString("cover", "parts")) {
            "screen" -> listOf(Pixelate.Shape(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), false))
            "group" -> {
                val u = RectF(base[0])
                base.forEach { u.union(it) }
                listOf(Pixelate.Shape(padded(u, 0.15f, bmp), false))
            }
            else -> d.indices.map { i ->
                val round = d[i].label in ROUND
                Pixelate.Shape(padded(base[i], if (round) 0.22f else 0.10f, bmp), round)
            }
        }
    }

    private fun censor(src: Bitmap, all: List<Detector.Det>, dets: List<Detector.Det>, want: Set<Int>, p: SharedPreferences): Bitmap {
        val style = p.getString("style", "mosaic") ?: "mosaic"
        var base = src
        var cover = dets
        var plain = false          // true when the picture was edited naturally, so no labels on the leftovers
        when (style) {
            "mannequin" -> Mannequin.apply(src, all, want, p.getBoolean("mqface", true))?.let {
                base = it.bmp; cover = it.leftover; plain = true
            }
            "nullify" -> Nullify.apply(src, all, dets).let {
                base = it.bmp; cover = it.leftover; plain = true
            }
        }
        if (cover.isEmpty()) return base
        val coverStyle = if (style == "blur" || style == "solid") style else "mosaic"
        return covers(base, cover, coverStyle, p, !plain)
    }

    private fun covers(src: Bitmap, d: List<Detector.Det>, style: String, p: SharedPreferences, labels: Boolean): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(out)
        val words = (p.getString("words", "LOSER, PIG, GOONER") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val useLabels = labels && p.getBoolean("labels", true) && words.isNotEmpty()
        val shapes = shapesFor(d, out, p)

        if (style == "mosaic") {
            val blk = Pixelate.blockSize(out.width, out.height, p.getInt("pixel", 4), shapes)
            val u = RectF(shapes[0].rect)
            shapes.forEach { u.union(it.rect) }
            val l = (u.left.toInt() - blk).coerceAtLeast(0)
            val t = (u.top.toInt() - blk).coerceAtLeast(0)
            val r = (u.right.toInt() + blk).coerceAtMost(out.width)
            val b = (u.bottom.toInt() + blk).coerceAtMost(out.height)
            val w = r - l
            val h = b - t
            if (w > 1 && h > 1) {
                val px = IntArray(w * h)
                out.getPixels(px, 0, w, l, t, w, h)
                out.setPixels(Pixelate.render(px, w, h, l, t, shapes, blk, false), 0, w, l, t, w, h)
            }
        }
        shapes.forEachIndexed { i, s ->
            val rf = s.rect
            val l = rf.left.toInt().coerceIn(0, out.width - 2)
            val t = rf.top.toInt().coerceIn(0, out.height - 2)
            val r = Rect(l, t, rf.right.toInt().coerceIn(l + 2, out.width), rf.bottom.toInt().coerceIn(t + 2, out.height))
            if (style != "mosaic") effect(out, cv, r, style, p)
            if (useLabels && r.width() > 120) drawLabel(cv, r, words[i % words.size])
        }
        return out
    }

    private fun effect(out: Bitmap, cv: Canvas, r: Rect, style: String, p: SharedPreferences) {
        val w = r.width()
        val h = r.height()
        when (style) {
            "solid" -> cv.drawRect(r, Paint().apply { color = Color.rgb(30, 30, 30) })
            else -> {
                val crop = Bitmap.createBitmap(out, r.left, r.top, w, h)
                val d = max(2, (min(w, h) * 0.012f * p.getInt("blurs", 5)).toInt())
                val small = Bitmap.createScaledBitmap(crop, max(1, w / d), max(1, h / d), true)
                cv.drawBitmap(small, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
    }

    // ---------------- live overlay (screen capture) ----------------

    /** Patches to lay over the screen for the mannequin / remove-nudity styles, plus what is left to cover normally. */
    class Live(val patches: List<Pair<Rect, Bitmap>>, val leftover: List<Detector.Det>)

    fun live(frame: Bitmap, all: List<Detector.Det>, dets: List<Detector.Det>, p: SharedPreferences): Live {
        val style = p.getString("style", "mosaic") ?: "mosaic"
        var bmp = frame
        var left = dets
        var regions: List<Rect> = emptyList()
        when (style) {
            "mannequin" -> Mannequin.apply(frame, all, wanted(p), p.getBoolean("mqface", true))?.let {
                bmp = it.bmp; left = it.leftover; regions = it.regions
            }
            "nullify" -> Nullify.apply(frame, all, dets).let {
                bmp = it.bmp; left = it.leftover; regions = it.regions
            }
        }
        val patches = ArrayList<Pair<Rect, Bitmap>>()
        for (r in regions) {
            val l = r.left.coerceIn(0, bmp.width - 2)
            val t = r.top.coerceIn(0, bmp.height - 2)
            val rr = r.right.coerceIn(l + 2, bmp.width)
            val bb = r.bottom.coerceIn(t + 2, bmp.height)
            patches.add(Pair(Rect(l, t, rr, bb), Bitmap.createBitmap(bmp, l, t, rr - l, bb - t)))
        }
        return Live(patches, left)
    }

    /** Mosaic patch for the live overlay: opaque blocks inside the shape, transparent elsewhere. */
    fun mosaicPatch(frame: Bitmap, r: Rect, round: Boolean, strength: Int): Bitmap {
        val w = r.width()
        val h = r.height()
        val shape = Pixelate.Shape(RectF(r), round)
        val blk = Pixelate.blockSize(frame.width, frame.height, strength, listOf(shape))
        val px = IntArray(w * h)
        frame.getPixels(px, 0, w, r.left, r.top, w, h)
        val res = Pixelate.render(px, w, h, r.left, r.top, listOf(shape), blk, true)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(res, 0, w, 0, 0, w, h)
        return b
    }

    // ---------------- labels ----------------

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
