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
 * Opaque, tracked covers.
 *
 *  - A detector thread finds things to cover (about 8-10 times a second) and creates/grows covers.
 *  - A motion thread looks at every frame, measures how far the picture has scrolled and moves the
 *    covers with it, so they stay attached to the image while you scroll. Covers are never hidden,
 *    so nothing flashes.
 *  - A cover is dropped when it scrolls off screen, when the picture around it changes (new image,
 *    carousel swipe, cut) or when the picture can no longer be tracked.
 */
class CensorService : Service() {
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: OverlayView? = null
    private var wm: WindowManager? = null
    @Volatile private var alive = false
    private var motionThread: Thread? = null
    private var detectThread: Thread? = null

    private class Track(var box: RectF, val word: String?, val base: Bitmap?) {
        var vx = 0f
        var vy = 0f
        var strikes = 0
    }

    private val lock = Any()
    private val tracks = ArrayList<Track>()        // guarded by lock
    private var latestFrame: Bitmap? = null        // guarded by lock
    private var latestSeq = 0L
    private var latestCumX = 0f
    private var latestCumY = 0f
    private var cumX = 0f
    private var cumY = 0f
    private var trackSeq = 0L
    private var sxs = 2f
    private var sys = 2f

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
            .setContentText("Tracking covers")
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
        sxs = sw.toFloat() / cw
        sys = sh.toFloat() / ch
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
        motionThread = Thread { loopMotion(cw, ch) }.also { it.start() }
        detectThread = Thread { loopDetect() }.also { it.start() }
    }

    // ------------------------------------------------------------------------------------
    // Motion thread: every frame, move the covers with the picture
    // ------------------------------------------------------------------------------------

    private fun loopMotion(cw: Int, ch: Int) {
        val gw = cw / 2
        val gh = ch / 2
        var prev: IntArray? = null
        var prevT = 0L
        while (alive) {
            try {
                val frame = grab(cw, ch)
                if (frame == null) { Thread.sleep(8); continue }
                val now = System.currentTimeMillis()
                val dt = (now - prevT).coerceIn(8L, 500L) / 1000f
                val lum = lumHalf(frame, gw, gh)
                synchronized(lock) {
                    var changed = false
                    val pv = prev
                    if (pv != null && tracks.isNotEmpty()) {
                        val mask = maskFor(gw, gh)
                        val m = estimateMotion(lum, pv, mask, gw, gh)
                        if (m[2] > 0.5f) {
                            tracks.clear()                       // picture changed in a way we can't follow
                            changed = true
                        } else {
                            val dxc = m[0] * 2f
                            val dyc = m[1] * 2f
                            if (dxc != 0f || dyc != 0f) {
                                for (t in tracks) {
                                    t.box.offset(dxc, dyc)
                                    t.vx = 0.5f * t.vx + 0.5f * (dxc / dt)
                                    t.vy = 0.5f * t.vy + 0.5f * (dyc / dt)
                                    t.strikes = 0
                                }
                                cumX += dxc
                                cumY += dyc
                                changed = true
                            } else {
                                for (t in tracks) { t.vx *= 0.5f; t.vy *= 0.5f }
                                if (ringRetire(lum, pv, mask, gw, gh)) changed = true
                            }
                        }
                        if (tracks.removeAll { it.box.right < 0 || it.box.bottom < 0 || it.box.left > cw || it.box.top > ch })
                            changed = true
                    }
                    latestFrame = frame
                    latestSeq++
                    latestCumX = cumX
                    latestCumY = cumY
                    if (changed) pushLocked()
                }
                prev = lum
                prevT = now
                Thread.sleep(10)
            } catch (e: Exception) {
                try { Thread.sleep(100) } catch (_: Exception) {}
            }
        }
    }

    private fun lumHalf(frame: Bitmap, gw: Int, gh: Int): IntArray {
        val small = Bitmap.createScaledBitmap(frame, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        for (i in px.indices) {
            val v = px[i]
            px[i] = (((v shr 16) and 255) * 3 + ((v shr 8) and 255) * 6 + (v and 255)) / 10
        }
        return px
    }

    private fun maskFor(gw: Int, gh: Int): BooleanArray {
        val m = BooleanArray(gw * gh)
        for (t in tracks) {
            val ex = t.box.width() * 0.1f
            val ey = t.box.height() * 0.1f
            val l = ((t.box.left - ex) / 2f).toInt().coerceIn(0, gw - 1)
            val r = ((t.box.right + ex) / 2f).toInt().coerceIn(0, gw - 1)
            val tp = ((t.box.top - ey) / 2f).toInt().coerceIn(0, gh - 1)
            val b = ((t.box.bottom + ey) / 2f).toInt().coerceIn(0, gh - 1)
            for (y in tp..b) for (x in l..r) m[y * gw + x] = true
        }
        return m
    }

    private fun rowProfile(l: IntArray, m: BooleanArray, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(gh)
        for (y in 0 until gh) {
            var s = 0L
            var c = 0
            val o = y * gw
            for (x in 0 until gw) {
                if (m[o + x]) continue
                s += l[o + x]
                c++
            }
            out[y] = if (c < gw / 4) -1f else s.toFloat() / c
        }
        return out
    }

    private fun colProfile(l: IntArray, m: BooleanArray, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(gw)
        for (x in 0 until gw) {
            var s = 0L
            var c = 0
            for (y in 0 until gh) {
                val i = y * gw + x
                if (m[i]) continue
                s += l[i]
                c++
            }
            out[x] = if (c < gh / 4) -1f else s.toFloat() / c
        }
        return out
    }

    /** returns [subpixel shift, best error, error at zero shift]; shift s means cur[y] ~ prev[y - s] */
    private fun shift1d(cur: FloatArray, prev: FloatArray, range: Int): FloatArray {
        val n = cur.size
        val errs = FloatArray(2 * range + 1)
        var best = Float.MAX_VALUE
        var bs = 0
        for (s in -range..range) {
            var sum = 0f
            var c = 0
            for (y in 0 until n) {
                val yy = y - s
                if (yy < 0 || yy >= n) continue
                val a = cur[y]
                val b = prev[yy]
                if (a < 0f || b < 0f) continue
                sum += abs(a - b)
                c++
            }
            val e = if (c < n / 3) Float.MAX_VALUE else sum / c
            errs[s + range] = e
            if (e < best) { best = e; bs = s }
        }
        var sub = bs.toFloat()
        if (bs > -range && bs < range) {
            val l = errs[bs - 1 + range]
            val m = errs[bs + range]
            val r = errs[bs + 1 + range]
            if (l < Float.MAX_VALUE && r < Float.MAX_VALUE) {
                val d = l - 2f * m + r
                if (d > 1e-6f) sub = bs + 0.5f * (l - r) / d
            }
        }
        return floatArrayOf(sub, best, errs[range])
    }

    /** returns [dx, dy, lost] in half-resolution pixels */
    private fun estimateMotion(cur: IntArray, prev: IntArray, mask: BooleanArray, gw: Int, gh: Int): FloatArray {
        var dx = 0f
        var dy = 0f
        var lost = false
        val ry = shift1d(rowProfile(cur, mask, gw, gh), rowProfile(prev, mask, gw, gh), 60)
        if (ry[2] < 2f) dy = 0f
        else if (ry[1] < 0.6f * ry[2] && ry[1] < 14f) dy = ry[0]
        else if (ry[2] > 14f) lost = true
        if (abs(dy) < 0.4f) dy = 0f
        if (!lost && dy == 0f) {
            val rx = shift1d(colProfile(cur, mask, gw, gh), colProfile(prev, mask, gw, gh), 40)
            if (rx[2] < 2f) dx = 0f
            else if (rx[1] < 0.6f * rx[2] && rx[1] < 14f) dx = rx[0]
            else if (rx[2] > 14f) lost = true
            if (abs(dx) < 0.4f) dx = 0f
        }
        return floatArrayOf(dx, dy, if (lost) 1f else 0f)
    }

    /** Drop a cover when the picture right around it has changed (new image, swipe, cut). Needs 2 hits in a row. */
    private fun ringRetire(lum: IntArray, pv: IntArray, mask: BooleanArray, gw: Int, gh: Int): Boolean {
        val dead = ArrayList<Track>()
        for (t in tracks) {
            val ex = t.box.width() * 0.25f
            val ey = t.box.height() * 0.25f
            val l = ((t.box.left - ex) / 2f).toInt().coerceIn(0, gw - 1)
            val r = ((t.box.right + ex) / 2f).toInt().coerceIn(0, gw - 1)
            val tp = ((t.box.top - ey) / 2f).toInt().coerceIn(0, gh - 1)
            val b = ((t.box.bottom + ey) / 2f).toInt().coerceIn(0, gh - 1)
            var n = 0
            var c = 0
            for (y in tp..b) {
                for (x in l..r) {
                    val i = y * gw + x
                    if (mask[i]) continue
                    n++
                    if (abs(lum[i] - pv[i]) > 40) c++
                }
            }
            if (n > 300 && c.toFloat() / n > 0.35f) {
                t.strikes++
                if (t.strikes >= 2) dead.add(t)
            } else t.strikes = 0
        }
        if (dead.isEmpty()) return false
        tracks.removeAll(dead)
        return true
    }

    // ------------------------------------------------------------------------------------
    // Detector thread: find things to cover
    // ------------------------------------------------------------------------------------

    private fun loopDetect() {
        val det = Detector(this)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        var lastSeq = -1L
        while (alive) {
            try {
                var f: Bitmap? = null
                var cx = 0f
                var cy = 0f
                var seq = 0L
                synchronized(lock) { f = latestFrame; cx = latestCumX; cy = latestCumY; seq = latestSeq }
                val frame = f
                if (frame == null || seq == lastSeq) { Thread.sleep(15); continue }
                lastSeq = seq
                val want = wanted(p)
                val dets = det.detect(frame, p.getFloat("sens", 0.35f)).filter { it.label in want }
                if (dets.isNotEmpty()) {
                    synchronized(lock) {
                        // the picture may have scrolled while we were detecting: carry the boxes along
                        if (reconcile(dets, frame, cumX - cx, cumY - cy, p)) pushLocked()
                    }
                }
                Thread.sleep(30)
            } catch (e: Exception) {
                try { Thread.sleep(100) } catch (_: Exception) {}
            }
        }
    }

    private fun inter(a: RectF, b: RectF): Float {
        val il = maxOf(a.left, b.left)
        val it = maxOf(a.top, b.top)
        val ir = minOf(a.right, b.right)
        val ib = minOf(a.bottom, b.bottom)
        return maxOf(0f, ir - il) * maxOf(0f, ib - it)
    }

    private fun iou(a: RectF, b: RectF): Float {
        val i = inter(a, b)
        val u = a.width() * a.height() + b.width() * b.height() - i
        return if (u <= 0f) 0f else i / u
    }

    private fun reconcile(dets: List<Detector.Det>, f: Bitmap, dx: Float, dy: Float, p: SharedPreferences): Boolean {
        val style = p.getString("style", "mosaic") ?: "mosaic"
        val words = (p.getString("words", "LOSER, PIG, GOONER") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val useLabels = p.getBoolean("labels", true) && words.isNotEmpty()
        var changed = false
        for (tf in rectsFor(dets, f, p)) {
            val cur = RectF(tf)
            cur.offset(dx, dy)
            var best: Track? = null
            val area = cur.width() * cur.height()
            for (tr in tracks) {
                if (area > 0f && (inter(tr.box, cur) / area > 0.5f || iou(tr.box, cur) > 0.3f)) { best = tr; break }
            }
            if (best == null) {
                trackSeq++
                val word = if (useLabels) words[(trackSeq % words.size).toInt()] else null
                tracks.add(Track(cur, word, makeBase(f, toRect(tf, f), style, p)))
                changed = true
            } else {
                val u = RectF(best.box)
                u.union(cur)
                if (u.width() - best.box.width() > 4f || u.height() - best.box.height() > 4f) {
                    best.box = u
                    changed = true
                }
            }
        }
        if (mergeOverlapping()) changed = true
        return changed
    }

    private fun mergeOverlapping(): Boolean {
        var any = false
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in tracks.indices) {
                for (j in tracks.indices) {
                    if (i == j) continue
                    val a = tracks[i]
                    val b = tracks[j]
                    val ab = b.box.width() * b.box.height()
                    if (ab > 0f && inter(a.box, b.box) / ab > 0.85f) {
                        tracks.removeAt(j); merged = true; any = true
                        break@loop
                    }
                    if (j > i && iou(a.box, b.box) > 0.6f) {
                        a.box.union(b.box); tracks.removeAt(j); merged = true; any = true
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
        return when (p.getString("cover", "parts")) {
            "screen" -> listOf(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()))
            "group" -> {
                val u = RectF(base[0])
                base.forEach { u.union(it) }
                listOf(pad(u, 0.15f, bmp))
            }
            else -> base.map { pad(it, 0.08f, bmp) }       // tight boxes, like the example
        }
    }

    // ------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------

    /** Chunky mosaic (about 5 blocks across the short side), or a blur source. Made once per cover. */
    private fun makeBase(frame: Bitmap, r: Rect, style: String, p: SharedPreferences): Bitmap? {
        if (style == "solid") return null
        val w = r.width()
        val h = r.height()
        val crop = Bitmap.createBitmap(frame, r.left, r.top, w, h)
        val cells = (12 - p.getInt("pixel", 6)).coerceAtLeast(2)       // fewer cells = chunkier
        val blk = if (style == "blur") 6 + 2 * p.getInt("blurs", 5) else maxOf(8, minOf(w, h) / cells)
        return Bitmap.createScaledBitmap(crop, maxOf(2, w / blk), maxOf(2, h / blk), true)
    }

    private fun pushLocked() {
        val ov = overlay ?: return
        val style = getSharedPreferences("cfg", MODE_PRIVATE).getString("style", "mosaic")
        ov.items = tracks.map { t ->
            val lx = (t.vx * 0.09f).coerceIn(-120f, 120f)     // aim slightly ahead of the motion
            val ly = (t.vy * 0.09f).coerceIn(-120f, 120f)
            OverlayView.Item(
                RectF((t.box.left + lx) * sxs, (t.box.top + ly) * sys, (t.box.right + lx) * sxs, (t.box.bottom + ly) * sys),
                t.base, style == "blur", t.word, 255
            )
        }
        ov.show = true
        ov.postInvalidate()
    }

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
        motionThread?.join(1000)
        detectThread?.join(1000)
        synchronized(lock) { tracks.clear() }
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        vd?.release()
        reader?.close()
        proj?.stop()
        super.onDestroy()
    }
}
