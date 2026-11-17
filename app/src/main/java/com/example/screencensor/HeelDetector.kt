package com.example.screencensor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.*
import java.nio.FloatBuffer

/** YOLOv8n trained on Open Images V7 (has a "High heels" class). Built into the APK by the workflow. */
class HeelDetector(ctx: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(ctx.assets.open("heels.onnx").readBytes())
    private val heelIdx: List<Int>

    init {
        val names = ctx.assets.open("oiv7_names.txt").bufferedReader().readLines()
        heelIdx = names.withIndex().filter { it.value.contains("heel", ignoreCase = true) }.map { it.index }
        if (heelIdx.isEmpty()) throw IllegalStateException("no heel class in model")
    }

    fun detect(bmp: Bitmap, minScore: Float): List<RectF> {
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
                    for (k in heelIdx) {
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
        val keep = ArrayList<RectF>()
        for ((_, r) in cands.sortedByDescending { it.first }) {
            if (keep.none { iou(it, r) > 0.5f }) keep.add(r)
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
