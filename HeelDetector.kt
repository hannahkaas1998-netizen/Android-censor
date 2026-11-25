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

    class Result(val heels: List<RectF>, val footwear: List<RectF>)
    private class Cand(val score: Float, val rect: RectF, val heel: Boolean)

    /**
     * Looks at the whole picture first. If nothing is found it also looks at zoomed-in pieces,
     * which catches small heels in big pictures. Real "high heels" and other footwear are kept apart.
     */
    fun detect(bmp: Bitmap, minScore: Float): Result {
        var found = run(bmp, minScore)
        if (found.isEmpty()) {
            val all = ArrayList<Cand>()
            for (t in tiles(bmp.width, bmp.height)) {
                val crop = Bitmap.createBitmap(bmp, t.left, t.top, t.width(), t.height())
                for (x in run(crop, minScore)) {
                    all.add(Cand(x.score, RectF(x.rect.left + t.left, x.rect.top + t.top, x.rect.right + t.left, x.rect.bottom + t.top), x.heel))
                }
            }
            found = nms(all)
        }
        return Result(found.filter { it.heel }.map { it.rect }, found.filter { !it.heel }.map { it.rect })
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

    private fun run(bmp: Bitmap, minScore: Float): List<Cand> {
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
        val cands = ArrayList<Cand>()
        val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, size.toLong(), size.toLong()))
        try {
            session.run(mapOf(session.inputNames.first() to tensor)).use { r ->
                val out = r[0] as OnnxTensor
                val n = out.info.shape[2].toInt()
                val fb = out.floatBuffer
                for (j in 0 until n) {
                    var bestHeel = 0f
                    for (k in heelIdx) {
                        val sc = fb.get((4 + k) * n + j)
                        if (sc > bestHeel) bestHeel = sc
                    }
                    var bestFoot = 0f
                    for (k in footIdx) {
                        val sc = fb.get((4 + k) * n + j)
                        if (sc > bestFoot) bestFoot = sc
                    }
                    if (bestHeel >= minScore || bestFoot >= minScore) {
                        val cx = fb.get(j)
                        val cy = fb.get(n + j)
                        val bw = fb.get(2 * n + j)
                        val bh = fb.get(3 * n + j)
                        val l = ((cx - bw / 2) / s).coerceIn(0f, w.toFloat())
                        val t = ((cy - bh / 2) / s).coerceIn(0f, h.toFloat())
                        val rr = ((cx + bw / 2) / s).coerceIn(0f, w.toFloat())
                        val bb = ((cy + bh / 2) / s).coerceIn(0f, h.toFloat())
                        if (rr > l && bb > t) {
                            if (bestHeel >= minScore) cands.add(Cand(bestHeel, RectF(l, t, rr, bb), true))
                            if (bestFoot >= minScore) cands.add(Cand(bestFoot, RectF(l, t, rr, bb), false))
                        }
                    }
                }
            }
        } finally {
            tensor.close()
        }
        return nms(cands)
    }

    private fun nms(c: List<Cand>): List<Cand> {
        val keep = ArrayList<Cand>()
        for (x in c.sortedByDescending { it.score }) {
            if (keep.none { it.heel == x.heel && iou(it.rect, x.rect) > 0.5f }) keep.add(x)
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
