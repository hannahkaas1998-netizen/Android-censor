package com.example.screencensor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
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

class CensorService : Service() {
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: OverlayView? = null
    private var wm: WindowManager? = null
    @Volatile private var alive = false
    private var worker: Thread? = null

    private class Built(val items: List<OverlayView.Item>, val rects: List<RectF>)

    private val NUDE_F = setOf(2, 3, 4, 6)   // buttocks, breast, female genitalia, anus (exposed)
    private val BUTT = setOf(2, 17)          // buttocks exposed + clothed
    private val MALE = setOf(14)             // male genitalia exposed

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

                // Something is covered. Keep watching the frame (cover included) for new
                // detections outside the covered areas, and do a timed re-check of what's underneath.
                val interval = p.getInt("recheck", 2) * 1000L
                var recheck = System.currentTimeMillis() - lastCheck >= interval
                if (!recheck) {
                    val bmp = grab(cw, ch)
                    if (bmp == null) { Thread.sleep(60); continue }
                    val dets = det.detect(bmp, sens).filter { it.label in want }
                    val outside = dets.any { d -> rects.none { it.contains(d.box.centerX(), d.box.centerY()) } }
                    if (outside) recheck = true else { Thread.sleep(30); continue }
                }

                // Re-check: hide the cover just long enough to capture one clean frame, re-show it
                // immediately, and only then run the (slow) detection.
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
                    if (misses >= 2) {        // must be clear twice in a row before uncovering
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

    private fun build(d: List<Detector.Det>, bmp: Bitmap, p: SharedPreferences, sw: Int, sh: Int, seed: Int): Built {
        val sx = sw.toFloat() / bmp.width
        val sy = sh.toFloat() / bmp.height
        val style = p.getString("style", "mosaic")
        val faceOn = p.getBoolean("face", false)
        val eyesOn = p.getBoolean("eyes", false)
        val words = (p.getString("words", "LOSER, PIG, GOONER") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val useLabels = p.getBoolean("labels", true) && words.isNotEmpty()

        // Eyes only: narrow a face box down to the eye band
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
