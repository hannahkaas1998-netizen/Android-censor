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
 * Covers are TRACKED objects: they are matched from one detection to the next, smoothed, kept for a
 * while when a detection drops out, and moved ahead of their motion. The cover is never hidden.
 *
 * To keep checking what is underneath without hiding the cover, the cover is drawn at ~91% opacity from
 * a bitmap we know exactly, and subtracted again from every captured frame ("see-through"). The app
 * tests at the first cover whether this works on the phone. If not, it uses opaque covers instead
 * ("opaque"): those are kept until the picture changes (scene cut) or they time out.
 */
class CensorService : Service() {
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: OverlayView? = null
    private var wm: WindowManager? = null
    @Volatile private var alive = false
    private var worker: Thread? = null

    private val A = 232   // cover opacity (out of 255) in see-through mode

    private class Track(var box: RectF, val seed: Long, val word: String?, val base: Bitmap?, var lastSeen: Long) {
        val born = lastSeen
        var lastUpd = lastSeen
        var vx = 0f
        var vy = 0f
        var rect = Rect()
        var o: Bitmap? = null
    }

    private val tracks = ArrayList<Track>()
    private var trackSeq = 0L

    private val NUDE_F = setOf(2, 3, 4, 6)
    private val BUTT = setOf(2, 17)
    private val MALE = setOf(14)

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        startFg("Starting...")
        val data = i?.getParcelableExtra<Intent>("data")
        if (data == null || alive) { if (data == null) stopSelf(); return START_NOT_STICKY }
        startCapture(i.getIntExtra("code", 0), data)
        return START_NOT_STICKY
    }

    private fun notif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CensorService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "censor")
            .setContentTitle("Screen Censor is running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).build()
    }

    private fun startFg(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("censor", "Screen Censor", NotificationManager.IMPORTANCE_LOW))
        val n = notif(text)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun setStatus(text: String) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("mode", text).apply()
        try { getSystemService(NotificationManager::class.java).notify(1, notif(text)) } catch (_: Exception) {}
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

    // ------------------------------------------------------------------------------------
    // Main loop
    // ------------------------------------------------------------------------------------

    private fun loop(sw: Int, sh: Int, cw: Int, ch: Int) {
        val det = Detector(this)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val ov = overlay ?: return
        val sxs = sw.toFloat() / cw
        val sys = sh.toFloat() / ch
        var seeThrough = true
        var modeKnown = false
        var shortLived = 0
        var prevGray: IntArray? = null
        setStatus("starting")

        fun push(wait: Long) {
            reader?.acquireLatestImage()?.close()
            ov.items = tracks.mapNotNull { t ->
                val o = t.o ?: return@mapNotNull null
                OverlayView.Item(
                    RectF(t.rect.left * sxs, t.rect.top * sys, t.rect.right * sxs, t.rect.bottom * sys),
                    o, false, null, if (seeThrough) A else 255
                )
            }
            ov.show = true
            ov.postInvalidate()
            if (wait > 0) Thread.sleep(wait)
        }

        while (alive) {
            try {
                val want = wanted(p)
                val sens = p.getFloat("sens", 0.35f)
                val holdMs = (p.getFloat("hold", 1.2f) * 1000).toLong()
                var now = System.currentTimeMillis()

                if (!seeThrough && tracks.isNotEmpty()) {       // opaque covers can't see underneath: time out
                    if (tracks.removeAll { now - it.lastSeen > 8000L }) push(30)
                }

                val frame = grab(cw, ch)
                if (frame == null) { Thread.sleep(30); continue }
                now = System.currentTimeMillis()

                if (tracks.isEmpty()) {
                    val dets = det.detect(frame, sens).filter { it.label in want }
                    if (dets.isEmpty()) { Thread.sleep(15); continue }
                    reconcile(dets, frame, p, now, seeThrough, sxs)
                    push(if (seeThrough) 90 else 30)
                    if (!modeKnown) {
                        modeKnown = true
                        val chk = grab(cw, ch)
                        if (chk != null) {
                            val err = meanError(frame, reconstruct(chk, tracks), tracks)
                            if (err > 45f) {
                                seeThrough = false
                                push(0)
                                setStatus("opaque mode (self-test error ${err.toInt()})")
                            } else setStatus("see-through mode (self-test error ${err.toInt()})")
                        } else setStatus("see-through mode (untested)")
                    }
                    prevGray = null
                    continue
                }

                val work: Bitmap
                if (seeThrough) {
                    work = reconstruct(frame, tracks)
                } else {
                    val g = grayMasked(frame, tracks)
                    val pg = prevGray
                    prevGray = g
                    if (pg != null && sceneCut(pg, g)) {        // picture changed: drop covers, look again
                        tracks.clear(); prevGray = null
                        push(100)
                        continue
                    }
                    work = frame
                }

                val dets = det.detect(work, if (seeThrough) sens * 0.8f else sens).filter { it.label in want }
                var changed = reconcile(dets, work, p, now, seeThrough, sxs)

                val ttl = if (seeThrough) holdMs else 8000L
                val it = tracks.iterator()
                while (it.hasNext()) {
                    val t = it.next()
                    if (now - t.lastSeen > ttl) {
                        if (seeThrough && now - t.born < 1500L) shortLived++
                        it.remove()
                        changed = true
                    }
                }
                if (seeThrough && shortLived >= 3) {            // covers keep vanishing: see-through isn't reliable here
                    seeThrough = false
                    changed = true
                    setStatus("opaque mode (see-through was unstable)")
                }
                if (mergeOverlapping(p, sxs)) changed = true
                if (changed) push(if (seeThrough) 90 else 30)
            } catch (e: Exception) {
                try { Thread.sleep(200) } catch (_: Exception) {}
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Tracking
    // ------------------------------------------------------------------------------------

    private fun iou(a: RectF, b: RectF): Float {
        val il = maxOf(a.left, b.left); val it = maxOf(a.top, b.top)
        val ir = minOf(a.right, b.right); val ib = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, ir - il) * maxOf(0f, ib - it)
        val u = a.width() * a.height() + b.width() * b.height() - inter
        return if (u <= 0f) 0f else inter / u
    }

    private fun differs(a: Rect, b: Rect, tol: Int) =
        abs(a.left - b.left) >= tol || abs(a.top - b.top) >= tol ||
            abs(a.right - b.right) >= tol || abs(a.bottom - b.bottom) >= tol

    private fun lerp(a: RectF, b: RectF, k: Float) = RectF(
        a.left + (b.left - a.left) * k, a.top + (b.top - a.top) * k,
        a.right + (b.right - a.right) * k, a.bottom + (b.bottom - a.bottom) * k
    )

    private fun reconcile(
        dets: List<Detector.Det>, work: Bitmap, p: SharedPreferences,
        now: Long, seeThrough: Boolean, sxs: Float
    ): Boolean {
        if (dets.isEmpty()) return false
        var changed = false
        val style = p.getString("style", "mosaic") ?: "mosaic"
        val words = (p.getString("words", "LOSER, PIG, GOONER") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val useLabels = p.getBoolean("labels", true) && words.isNotEmpty()
        val used = HashSet<Track>()

        for (t in rectsFor(dets, work, p)) {
            var best: Track? = null
            var bi = 0.08f
            for (tr in tracks) {
                if (tr in used) continue
                val v = iou(tr.box, t)
                if (v > bi) { bi = v; best = tr }
            }
            if (best == null) {
                trackSeq++
                val r = toRect(t, work)
                val word = if (useLabels) words[(trackSeq % words.size).toInt()] else null
                val tr = Track(RectF(t), trackSeq * 7919L, word, makeBase(work, r, style), now)
                tr.rect = r
                tr.o = renderO(tr, r, p, sxs)
                tracks.add(tr)
                used.add(tr)
                changed = true
            } else {
                used.add(best)
                val dt = (now - best.lastUpd).coerceAtLeast(1L)
                val nb: RectF
                if (seeThrough) {
                    nb = lerp(best.box, t, 0.5f)
                } else {
                    nb = RectF(best.box)
                    nb.union(t)
                }
                val vx = (nb.centerX() - best.box.centerX()) * 1000f / dt
                val vy = (nb.centerY() - best.box.centerY()) * 1000f / dt
                best.vx = 0.6f * best.vx + 0.4f * vx
                best.vy = 0.6f * best.vy + 0.4f * vy
                best.box = nb
                best.lastSeen = now
                best.lastUpd = now
                // aim a little ahead of the motion so the cover keeps up
                val lx = (best.vx * 0.15f).coerceIn(-work.width * 0.25f, work.width * 0.25f)
                val ly = (best.vy * 0.15f).coerceIn(-work.height * 0.25f, work.height * 0.25f)
                val lead = RectF(nb)
                lead.union(RectF(nb.left + lx, nb.top + ly, nb.right + lx, nb.bottom + ly))
                val wantRect = toRect(lead, work)
                if (differs(wantRect, best.rect, 3)) {      // ignore small jitter
                    best.rect = wantRect
                    best.o = renderO(best, wantRect, p, sxs)
                    changed = true
                }
            }
        }
        return changed
    }

    /** Covers that touch are merged: overlapping translucent covers could not be undone cleanly. */
    private fun mergeOverlapping(p: SharedPreferences, sxs: Float): Boolean {
        var any = false
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in tracks.indices) {
                for (j in i + 1 until tracks.size) {
                    if (Rect.intersects(tracks[i].rect, tracks[j].rect)) {
                        val a = tracks[i]
                        val b = tracks[j]
                        a.box.union(b.box)
                        a.rect.union(b.rect)
                        a.lastSeen = maxOf(a.lastSeen, b.lastSeen)
                        a.o = renderO(a, a.rect, p, sxs)
                        tracks.removeAt(j)
                        merged = true
                        any = true
                        break@loop
                    }
                }
            }
        }
        return any
    }

    private fun toRect(r: RectF, bmp: Bitmap): Rect {
        val l = r.left.toInt().coerceIn(0, bmp.width - 2)
        val t = r.top.toInt().coerceIn(0, bmp.height - 2)
        return Rect(l, t, r.right.toInt().coerceIn(l + 2, bmp.width), r.bottom.toInt().coerceIn(t + 2, bmp.height))
    }

    private fun rectsFor(d: List<Detector.Det>, bmp: Bitmap, p: SharedPreferences): List<RectF> {
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
        val rs = rf.map { RectF(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in rs.indices) {
                for (j in i + 1 until rs.size) {
                    if (RectF.intersects(rs[i], rs[j])) {
                        rs[i].union(rs[j]); rs.removeAt(j); changed = true
                        break@loop
                    }
                }
            }
        }
        return rs
    }

    // ------------------------------------------------------------------------------------
    // Cover drawing (frozen look per cover, so nothing shimmers)
    // ------------------------------------------------------------------------------------

    private fun makeBase(work: Bitmap, r: Rect, style: String): Bitmap? {
        if (style == "solid") return null
        val w = r.width()
        val h = r.height()
        val crop = Bitmap.createBitmap(work, r.left, r.top, w, h)
        val blk = if (style == "blur") 14 else maxOf(6, minOf(w, h) / 8)
        return Bitmap.createScaledBitmap(crop, maxOf(1, w / blk), maxOf(1, h / blk), true)
    }

    private fun renderO(t: Track, r: Rect, p: SharedPreferences, sxs: Float): Bitmap {
        val style = p.getString("style", "mosaic")
        val w = r.width()
        val h = r.height()
        val o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(o)
        val b = t.base
        if (style == "solid" || b == null) cv.drawColor(Color.rgb(40, 40, 40))
        else cv.drawBitmap(b, null, Rect(0, 0, w, h), if (style == "blur") Paint(Paint.FILTER_BITMAP_FLAG) else Paint())
        // fine grain: lets the real picture be recovered accurately underneath
        val px = IntArray(w * h)
        o.getPixels(px, 0, w, 0, 0, w, h)
        val rnd = java.util.Random(t.seed)
        for (k in px.indices) {
            val n = rnd.nextInt(25) - 12
            val v = px[k]
            val rr = (((v shr 16) and 255) + n).coerceIn(0, 255)
            val gg = (((v shr 8) and 255) + n).coerceIn(0, 255)
            val bb = ((v and 255) + n).coerceIn(0, 255)
            px[k] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        o.setPixels(px, 0, w, 0, 0, w, h)
        val word = t.word
        if (word != null && w * sxs > 140f) bakeLabel(cv, w, h, word)
        return o
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
    // See-through reconstruction
    // ------------------------------------------------------------------------------------

    private fun rec(p: Int, o: Int): Int {
        val s = (o * A + 127) / 255
        return ((p - s) * 255 / (255 - A)).coerceIn(0, 255)
    }

    /** real = (captured - cover * opacity) / (1 - opacity), inside each cover. */
    private fun reconstruct(frame: Bitmap, ts: List<Track>): Bitmap {
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        for (t in ts) {
            val o = t.o ?: continue
            val r = t.rect
            val w = r.width()
            val h = r.height()
            val ps = IntArray(w * h)
            val os = IntArray(w * h)
            out.getPixels(ps, 0, w, r.left, r.top, w, h)
            o.getPixels(os, 0, w, 0, 0, w, h)
            for (i in ps.indices) {
                val a = ps[i]
                val b = os[i]
                ps[i] = (0xFF shl 24) or
                    (rec((a shr 16) and 255, (b shr 16) and 255) shl 16) or
                    (rec((a shr 8) and 255, (b shr 8) and 255) shl 8) or
                    rec(a and 255, b and 255)
            }
            out.setPixels(ps, 0, w, r.left, r.top, w, h)
        }
        return out
    }

    private fun meanError(a: Bitmap, b: Bitmap, ts: List<Track>): Float {
        var sum = 0L
        var n = 0L
        for (t in ts) {
            val r = t.rect
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

    // ------------------------------------------------------------------------------------
    // Opaque mode: scene-cut detection on the uncovered part of the picture
    // ------------------------------------------------------------------------------------

    private fun grayMasked(frame: Bitmap, ts: List<Track>): IntArray {
        val gw = maxOf(1, frame.width / 8)
        val gh = maxOf(1, frame.height / 8)
        val small = Bitmap.createScaledBitmap(frame, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        val g = IntArray(gw * gh)
        for (i in px.indices) {
            val v = px[i]
            g[i] = (((v shr 16) and 255) * 3 + ((v shr 8) and 255) * 6 + (v and 255)) / 10
        }
        for (t in ts) {
            val r = t.rect
            for (y in (r.top / 8)..((r.bottom - 1) / 8)) {
                for (x in (r.left / 8)..((r.right - 1) / 8)) {
                    if (x in 0 until gw && y in 0 until gh) g[y * gw + x] = -1
                }
            }
        }
        return g
    }

    private fun sceneCut(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        var n = 0
        var s = 0L
        for (i in a.indices) {
            if (a[i] < 0 || b[i] < 0) continue
            s += abs(a[i] - b[i])
            n++
        }
        if (n < a.size / 5) return false
        return s.toFloat() / n > 28f
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
        tracks.clear()
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        vd?.release()
        reader?.close()
        proj?.stop()
        super.onDestroy()
    }
}
