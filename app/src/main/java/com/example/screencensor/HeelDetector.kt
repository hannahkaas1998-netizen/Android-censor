package com.example.screencensor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.*
import java.nio.FloatBuffer
import kotlin.math.ceil

/** YOLOv8n trained on Open Images V7 (has "High heels", "Footwear", "Sandal", "Boot"). Built into the APK by the workflow. */
class HeelDetector(ctx: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(ctx.assets.open("heels.onnx").readBytes())
    private val heelIdx: List<Int>
    private val footIdx: List<Int>

    init {
        val names = ctx.assets.open("oiv7_names.txt").bufferedReader().readLines()
        heelIdx = names.withIndex().filter { it.value.contains("heel", ignoreCase = true) }.map { it.index }
        if (heelIdx.isEmpty()) throw IllegalStateException("no heel class in model")
        footIdx = names.withIndex().filter {
            val n = it.value.lowercase()
            it.index !in heelIdx && (n == "footwear" || n == "sandal" || n == "boot" || n.contains("shoe"))
        }.map { it.index }
    }

    /**
     * Looks at the whole picture first. If nothing is found it also looks at zoomed-in pieces,
     * which catches small heels in big pictures.
     */
    fun detect(bmp: Bitmap, minScore: Float, includeFootwear: Boolean): List<RectF> {
        val idx = if (includeFootwear) heelIdx + footIdx else heelIdx
        var found = run(bmp, minScore, idx)
        if (found.isEmpty()) {
            val all = ArrayList<Pair<Float, RectF>>()
            for (t in tiles(bmp.width, bmp.height)) {
                val crop = Bitmap.createBitmap(bmp, t.left, t.top, t.width(), t.height())
                for ((s, r) in run(crop, minScore, idx)) {
                    all.add(Pair(s, RectF(r.left + t.left, r.top + t.top, r.right + t.left, r.bottom + t.top)))
                }
            }
            found = nms(all)
        }
        return found.map { it.second }
    }

    private fun positions(len: Int, t: Int): List<Int> {
        if (len <= t) return listOf(0)
        val n = ceil((len - t) / (t * 0.7f)).toInt() + 1
        return (0 until n).map { ((len - t) * it / (n - 1).toFloat()).toInt() }
    }

    private fun tiles(w: Int, h: Int): List<Rect> {
        val m = minOf(w, h)
        val ar = maxOf(w, h).toFloat() / m
        val t = if (ar < 1.25f) (m * 0.6f).toInt() else m      // near-square: zoom in; otherwise slide along the long side
        if (t < 64) return emptyList()
        val out = ArrayList<Rect>()
        for (y in positions(h, t)) for (x in positions(w, t)) {
            out.add(Rect(x, y, minOf(w, x + t), minOf(h, y + t)))
        }
        return if (out.size > 9) out.take(9) else out
    }

    private fun run(bmp: Bitmap, minScore: Float, idx: List<Int>): List<Pair<Float, RectF>> {
        val size = 640
        val w = bmp.width
        val h = bmp.height
        val s = size.toFloat() / maxOf(w, h)
        val sq = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(sq)
        c.drawColor(Color.rgb(114, 114, 114))
        c.drawBitmap(bmp, null, RectF(0f, 0f, w * s, h * s), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(size * size)
        sq.getPixels(px, 0, size, 0, 0, size, size)
        sq.recycle()
        val plane = size * size
        val buf = FloatBuffer.allocate(3 * plane)
        for (i in px.indices) {
            val p = px[i]
            buf.put(i, ((p shr 16) and 255) / 255f)
            buf.put(i + plane, ((p shr 8) and 255) / 255f)
            buf.put(i + 2 * plane, (p and 255) / 255f)
        }
        val cands = ArrayList<Pair<Float, RectF>>()
        val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, size.toLong(), size.toLong()))
        try {
            session.run(mapOf(session.inputNames.first() to tensor)).use { r ->
                val out = r[0] as OnnxTensor
                val n = out.info.shape[2].toInt()
                val fb = out.floatBuffer
                for (j in 0 until n) {
                    var best = 0f
                    for (k in idx) {
                        val sc = fb.get((4 + k) * n + j)
                        if (sc > best) best = sc
                    }
                    if (best >= minScore) {
                        val cx = fb.get(j)
                        val cy = fb.get(n + j)
                        val bw = fb.get(2 * n + j)
                        val bh = fb.get(3 * n + j)
                        val l = ((cx - bw / 2) / s).coerceIn(0f, w.toFloat())
                        val t = ((cy - bh / 2) / s).coerceIn(0f, h.toFloat())
                        val rr = ((cx + bw / 2) / s).coerceIn(0f, w.toFloat())
                        val bb = ((cy + bh / 2) / s).coerceIn(0f, h.toFloat())
                        if (rr > l && bb > t) cands.add(Pair(best, RectF(l, t, rr, bb)))
                    }
                }
            }
        } finally {
            tensor.close()
        }
        return nms(cands)
    }

    private fun nms(c: List<Pair<Float, RectF>>): List<Pair<Float, RectF>> {
        val keep = ArrayList<Pair<Float, RectF>>()
        for (x in c.sortedByDescending { it.first }) {
            if (keep.none { iou(it.second, x.second) > 0.5f }) keep.add(x)
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val il = maxOf(a.left, b.left); val it = maxOf(a.top, b.top)
        val ir = minOf(a.right, b.right); val ib = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, ir - il) * maxOf(0f, ib - it)
        val u = a.width() * a.height() + b.width() * b.height() - inter
        return if (u <= 0f) 0f else inter / u
    }
}
