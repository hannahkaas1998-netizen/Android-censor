package com.example.screencensor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.abs

/**
 * "See-through" mode: the cover is drawn at ~91% opacity from a bitmap we know exactly.
 * Every captured frame = 0.91 * cover + 0.09 * real content, so we subtract the cover again
 * and recover the real content underneath. That lets us keep checking and adjusting the
 * cover continuously without ever hiding it. If that doesn't work on this phone (checked
 * automatically), the service falls back to the older hide-and-recheck method.
 */
class CensorService : Service() {
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: OverlayView? = null
    private var wm: WindowManager? = null
    @Volatile private var alive = false
    private var worker: Thread? = null

    private val A = 232   // cover opacity out of 255

    private class CoverRect(val rect: Rect, val o: Bitmap)
    private class Built(val items: List<OverlayView.Item>, val rects: List<RectF>)

    private val NUDE_F = setOf(2, 3, 4, 6)
    private val BUTT = setOf(2, 17)
    private val MALE = setOf(14)

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        startFg()
        val data = i?.getParcelableExtra<Intent>("data")
        if (data == null || alive) { if (data == null) stopSelf(); return START_NOT_STICKY }
        startCapture(i.getIntExtra("code", 0), data)
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("censor", "Screen Censor", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CensorService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "censor")
            .setContentTitle("Screen Censor is running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    @Suppress("DEPRECATION")
    private fun startCapture(code: Int, data: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mgr.getMediaProjection(code, data)
        proj = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val dm = DisplayMetrics()
        w.defaultDisplay.getRealMetrics(dm)
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val cw = sw / 2
        val ch = sh / 2
        val rd = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = rd
        vd = p.createVirtualDisplay(
            "censor", cw, ch, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null
        )

        val ov = OverlayView(this)
        overlay = ov
        val lp = WindowManager.LayoutParams(
            sw, sh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        w.addView(ov, lp)

        alive = true
        worker = Thread { loop(sw, sh, cw, ch) }.also { it.start() }
    }

    private fun loop(sw: Int, sh: Int, cw: Int, ch: Int) {
        val det = Detector(this)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        p.edit().putString("mode", "see-through").apply()
        val ok = loopSeeThrough(sw, sh, cw, ch, det)
        if (!ok && alive) {
            overlay?.items = emptyList()
            overlay?.postInvalidate()
            p.edit().putString("mode", "fallback (flashes while re-checking)").apply()
            loopFallback(sw, sh, cw, ch, det)
        }
    }

    // ------------------------------------------------------------------------------------
    // See-through mode
    // ------------------------------------------------------------------------------------

    /** Returns false if see-through doesn't work on this phone (caller then uses the fallback). */
    private fun loopSeeThrough(sw: Int, sh: Int, cw: Int, ch: Int, det: Detector): Boolean {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val ov = overlay ?: return true
        val sxs = sw.toFloat() / cw
        val sys = sh.toFloat() / ch
        var covers: List<CoverRect> = emptyList()
        var covered = false
        var misses = 0
        var firstMissAt = 0L
        var seed = 0
        var since = 0L
        var shortCovers = 0
        var fails = 0

        fun drain() { reader?.acquireLatestImage()?.close() }
        fun show(cs: List<CoverRect>) {
            drain()
            ov.items = cs.map {
                OverlayView.Item(
                    RectF(it.rect.left * sxs, it.rect.top * sys, it.rect.right * sxs, it.rect.bottom * sys),
                    it.o, false, null, A
                )
            }
            ov.show = true
            ov.postInvalidate()
            Thread.sleep(140)
        }
        fun clear() {
            drain()
            ov.items = emptyList()
            ov.postInvalidate()
            Thread.sleep(140)
        }

        while (alive) {
            try {
                val want = wanted(p)
                val sens = p.getFloat("sens", 0.35f)
                val frame = grab(cw, ch)
                if (frame == null) {
                    // static screen: if the last check found nothing, uncover after a short while
                    if (covered && misses > 0 && System.currentTimeMillis() - firstMissAt > 600) {
                        covered = false; covers = emptyList(); clear()
                    }
                    Thread.sleep(40); continue
                }

                if (!covered) {
                    val dets = det.detect(frame, sens).filter { it.label in want }
                    if (dets.isEmpty()) { Thread.sleep(20); continue }
                    seed++
                    covers = makeCovers(dets, frame, p, seed, sxs)
                    show(covers)
                    // Self-test: subtracting our cover from the capture must give back the real picture
                    val check = grab(cw, ch)
                    if (check != null) {
                        val err = meanError(frame, reconstruct(check, covers), covers)
                        if (err > 40f) {
                            fails++
                            covers = emptyList(); clear()
                            if (fails >= 2) return false
                            continue
                        }
                    }
                    fails = 0
                    covered = true
                    misses = 0
                    since = System.currentTimeMillis()
                } else {
                    val clean = reconstruct(frame, covers)
                    val dets = det.detect(clean, sens * 0.85f).filter { it.label in want }
                    if (dets.isEmpty()) {
                        misses++
                        if (misses == 1) firstMissAt = System.currentTimeMillis()
                        if (misses >= 2) {          // clear twice in a row before uncovering
                            covered = false; covers = emptyList(); clear()
                            if (System.currentTimeMillis() - since < 1500) {
                                shortCovers++
                                if (shortCovers >= 3) return false   // cover keeps vanishing: reconstruction isn't working
                            } else shortCovers = 0
                        }
                    } else {
                        misses = 0
                        covers = makeCovers(dets, clean, p, seed, sxs)
                        show(covers)
                    }
                }
            } catch (e: Exception) {
                try { Thread.sleep(200) } catch (_: Exception) {}
            }
        }
        return true
    }

    private fun rec(p: Int, o: Int): Int {
        val s = (o * A + 127) / 255
        return ((p - s) * 255 / (255 - A)).coerceIn(0, 255)
    }

    /** Undo our own cover: real = (captured - cover * opacity) / (1 - opacity), inside each cover rect. */
    private fun reconstruct(frame: Bitmap, covers: List<CoverRect>): Bitmap {
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        for (c in covers) {
            val r = c.rect
            val w = r.width()
            val h = r.height()
            val ps = IntArray(w * h)
            val os = IntArray(w * h)
            out.getPixels(ps, 0, w, r.left, r.top, w, h)
            c.o.getPixels(os, 0, w, 0, 0, w, h)
            for (i in ps.indices) {
                val a = ps[i]
                val o = os[i]
                ps[i] = (0xFF shl 24) or
                    (rec((a shr 16) and 255, (o shr 16) and 255) shl 16) or
                    (rec((a shr 8) and 255, (o shr 8) and 255) shl 8) or
                    rec(a and 255, o and 255)
            }
            out.setPixels(ps, 0, w, r.left, r.top, w, h)
        }
        return out
    }

    private fun meanError(a: Bitmap, b: Bitmap, cs: List<CoverRect>): Float {
        var sum = 0L
        var n = 0L
        for (c in cs) {
            val r = c.rect
            val w = r.width()
            val h = r.height()
            val pa = IntArray(w * h)
            val pb = IntArray(w * h)
            a.getPixels(pa, 0, w, r.left, r.top, w, h)
            b.getPixels(pb, 0, w, r.left, r.top, w, h)
            var i = 0
            while (i < pa.size) {
                val x = pa[i]
                val y = pb[i]
                sum += abs(((x shr 16) and 255) - ((y shr 16) and 255)) +
                    abs(((x shr 8) and 255) - ((y shr 8) and 255)) +
                    abs((x and 255) - (y and 255))
                n += 3
                i += 5
            }
        }
        return if (n == 0L) 0f else sum.toFloat() / n
    }

    private fun rectsFor(d: List<Detector.Det>, bmp: Bitmap, p: SharedPreferences): List<Rect> {
        val faceOn = p.getBoolean("face", false)
        val eyesOn = p.getBoolean("eyes", false)
        val base = d.map { det ->
            val b = det.box
            if (det.label in Detector.FACE && eyesOn && !faceOn)
                RectF(b.left, b.top + b.height() * 0.2f, b.right, b.top + b.height() * 0.55f)
            else RectF(b)
        }
        val rf: List<RectF> = when (p.getString("cover", "parts")) {
            "screen" -> listOf(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()))
            "group" -> {
                val u = RectF(base[0])
                base.forEach { u.union(it) }
                listOf(pad(u, 0.35f, bmp))
            }
            else -> base.map { pad(it, 0.2f, bmp) }
        }
        val rs = rf.map { r ->
            val l = r.left.toInt().coerceIn(0, bmp.width - 2)
            val t = r.top.toInt().coerceIn(0, bmp.height - 2)
            Rect(l, t, r.right.toInt().coerceIn(l + 2, bmp.width), r.bottom.toInt().coerceIn(t + 2, bmp.height))
        }.toMutableList()
        // overlapping covers would blend twice, so merge them
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in rs.indices) {
                for (j in i + 1 until rs.size) {
                    if (Rect.intersects(rs[i], rs[j])) {
                        rs[i].union(rs[j]); rs.removeAt(j); changed = true
                        break@loop
                    }
                }
            }
        }
        return rs
    }

    private fun makeCovers(d: List<Detector.Det>, src: Bitmap, p: SharedPreferences, seed: Int, sxs: Float): List<CoverRect> {
        val style = p.getString("style", "mosaic")
        val words = (p.getString("words", "LOSER, PIG, GOONER") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val useLabels = p.getBoolean("labels", true) && words.isNotEmpty()
        val rnd = java.util.Random(seed * 7919L)
        return rectsFor(d, src, p).mapIndexed { i, r ->
            val w = r.width()
            val h = r.height()
            val o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(o)
            when (style) {
                "solid" -> cv.drawColor(Color.rgb(40, 40, 40))
                "blur" -> {
                    val crop = Bitmap.createBitmap(src, r.left, r.top, w, h)
                    val small = Bitmap.createScaledBitmap(crop, maxOf(1, w / 14), maxOf(1, h / 14), true)
                    cv.drawBitmap(small, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                else -> {
                    val crop = Bitmap.createBitmap(src, r.left, r.top, w, h)
                    val blk = maxOf(6, minOf(w, h) / 8)
                    val small = Bitmap.createScaledBitmap(crop, maxOf(1, w / blk), maxOf(1, h / blk), true)
                    cv.drawBitmap(small, null, Rect(0, 0, w, h), Paint())
                }
            }
            // fine grain so the real picture can be recovered accurately underneath
            val px = IntArray(w * h)
            o.getPixels(px, 0, w, 0, 0, w, h)
            for (k in px.indices) {
                val n = rnd.nextInt(25) - 12
                val v = px[k]
                val rr = (((v shr 16) and 255) + n).coerceIn(0, 255)
                val gg = (((v shr 8) and 255) + n).coerceIn(0, 255)
                val bb = ((v and 255) + n).coerceIn(0, 255)
                px[k] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            o.setPixels(px, 0, w, 0, 0, w, h)
            if (useLabels && w * sxs > 140f) bakeLabel(cv, w, h, words[(seed + i) % words.size])
            CoverRect(r, o)
        }
    }

    private fun bakeLabel(c: Canvas, w: Int, h: Int, text: String) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val out = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        fill.textSize = 100f
        val tw = fill.measureText(text)
        if (tw <= 0f) return
        val size = (100f * w * 0.9f / tw).coerceAtMost(h * 0.5f).coerceAtLeast(8f)
        fill.textSize = size
        out.textSize = size
        out.strokeWidth = size * 0.14f
        val cx = w / 2f
        val cy = h / 2f
        val y = cy - (fill.ascent() + fill.descent()) / 2f
        c.save()
        c.rotate(-8f, cx, cy)
        c.drawText(text, cx, y, out)
        c.drawText(text, cx, y, fill)
        c.restore()
    }

    // ------------------------------------------------------------------------------------
    // Fallback (older method): briefly hides the cover to re-check what's underneath
    // ------------------------------------------------------------------------------------

    private fun loopFallback(sw: Int, sh: Int, cw: Int, ch: Int, det: Detector) {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val ov = overlay ?: return
        var covered = false
        var misses = 0
        var lastCheck = 0L
        var seed = 0
        var rects: List<RectF> = emptyList()

        while (alive) {
            try {
                val want = wanted(p)
                val sens = p.getFloat("sens", 0.35f)

                if (!covered) {
                    val bmp = grab(cw, ch)
                    if (bmp == null) { Thread.sleep(60); continue }
                    val dets = det.detect(bmp, sens).filter { it.label in want }
                    if (dets.isEmpty()) { Thread.sleep(30); continue }
                    seed++
                    val b = build(dets, bmp, p, sw, sh, seed)
                    rects = b.rects
                    ov.items = b.items
                    ov.show = true
                    ov.postInvalidate()
                    covered = true
                    misses = 0
                    lastCheck = System.currentTimeMillis()
                    continue
                }

                val interval = p.getInt("recheck", 2) * 1000L
                var recheck = System.currentTimeMillis() - lastCheck >= interval
                if (!recheck) {
                    val bmp = grab(cw, ch)
                    if (bmp == null) { Thread.sleep(60); continue }
                    val dets = det.detect(bmp, sens).filter { it.label in want }
                    val outside = dets.any { d -> rects.none { it.contains(d.box.centerX(), d.box.centerY()) } }
                    if (outside) recheck = true else { Thread.sleep(30); continue }
                }

                reader?.acquireLatestImage()?.close()
                ov.show = false; ov.postInvalidate()
                Thread.sleep(70)
                val clean = grab(cw, ch)
                ov.show = true; ov.postInvalidate()
                lastCheck = System.currentTimeMillis()
                if (clean == null) continue
                val dets = det.detect(clean, sens * 0.8f).filter { it.label in want }
                if (dets.isEmpty()) {
                    misses++
                    if (misses >= 2) {
                        covered = false
                        ov.items = emptyList()
                        ov.postInvalidate()
                    }
                } else {
                    misses = 0
                    val b = build(dets, clean, p, sw, sh, seed)
                    rects = b.rects
                    ov.items = b.items
                    ov.postInvalidate()
                }
            } catch (e: Exception) {
                try { Thread.sleep(200) } catch (_: Exception) {}
            }
        }
    }

    private fun build(d: List<Detector.Det>, bmp: Bitmap, p: SharedPreferences, sw: Int, sh: Int, seed: Int): Built {
        val sx = sw.toFloat() / bmp.width
        val sy = sh.toFloat() / bmp.height
        val style = p.getString("style", "mosaic")
        val faceOn = p.getBoolean("face", false)
        val eyesOn = p.getBoolean("eyes", false)
        val words = (p.getString("words", "LOSER, PIG, GOONER") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val useLabels = p.getBoolean("labels", true) && words.isNotEmpty()
        val base = d.map { det ->
            val b = det.box
            if (det.label in Detector.FACE && eyesOn && !faceOn)
                RectF(b.left, b.top + b.height() * 0.2f, b.right, b.top + b.height() * 0.55f)
            else RectF(b)
        }
        val rects: List<RectF> = when (p.getString("cover", "parts")) {
            "screen" -> listOf(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()))
            "group" -> {
                val u = RectF(base[0])
                base.forEach { u.union(it) }
                listOf(pad(u, 0.35f, bmp))
            }
            else -> base.map { pad(it, 0.2f, bmp) }
        }
        val items = rects.mapIndexed { i, r ->
            val x = r.left.toInt().coerceIn(0, bmp.width - 1)
            val y = r.top.toInt().coerceIn(0, bmp.height - 1)
            val w = r.width().toInt().coerceIn(1, bmp.width - x)
            val h = r.height().toInt().coerceIn(1, bmp.height - y)
            val dest = RectF(x * sx, y * sy, (x + w) * sx, (y + h) * sy)
            val label = if (useLabels && dest.width() > 140f) words[(seed + i) % words.size] else null
            when (style) {
                "solid" -> OverlayView.Item(dest, null, false, label)
                "blur" -> {
                    val c = Bitmap.createBitmap(bmp, x, y, w, h)
                    OverlayView.Item(dest, Bitmap.createScaledBitmap(c, maxOf(1, w / 14), maxOf(1, h / 14), true), true, label)
                }
                else -> {
                    val c = Bitmap.createBitmap(bmp, x, y, w, h)
                    val blk = maxOf(6, minOf(w, h) / 8)
                    OverlayView.Item(dest, Bitmap.createScaledBitmap(c, maxOf(1, w / blk), maxOf(1, h / blk), true), false, label)
                }
            }
        }
        return Built(items, rects)
    }

    // ------------------------------------------------------------------------------------

    private fun grab(cw: Int, ch: Int): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val pl = img.planes[0]
            val rs = pl.rowStride
            val ps = pl.pixelStride
            val b = Bitmap.createBitmap(rs / ps, ch, Bitmap.Config.ARGB_8888)
            b.copyPixelsFromBuffer(pl.buffer)
            return if (b.width == cw) b else Bitmap.createBitmap(b, 0, 0, cw, ch)
        } finally {
            img.close()
        }
    }

    private fun wanted(p: SharedPreferences): Set<Int> {
        val s = HashSet<Int>()
        if (p.getBoolean("nudity", true)) s.addAll(NUDE_F)
        if (p.getBoolean("male", true)) s.addAll(MALE)
        if (p.getBoolean("butt", true)) s.addAll(BUTT)
        if (p.getBoolean("cleavage", true)) s.addAll(Detector.CLEAVAGE)
        if (p.getBoolean("face", false) || p.getBoolean("eyes", false)) s.addAll(Detector.FACE)
        if (p.getBoolean("feet", false)) s.addAll(Detector.FEET)
        return s
    }

    private fun pad(r: RectF, f: Float, b: Bitmap): RectF {
        val dx = r.width() * f
        val dy = r.height() * f
        return RectF(
            (r.left - dx).coerceAtLeast(0f), (r.top - dy).coerceAtLeast(0f),
            (r.right + dx).coerceAtMost(b.width.toFloat()), (r.bottom + dy).coerceAtMost(b.height.toFloat())
        )
    }

    override fun onDestroy() {
        alive = false
        worker?.join(1000)
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        vd?.release()
        reader?.close()
        proj?.stop()
        super.onDestroy()
    }
}
