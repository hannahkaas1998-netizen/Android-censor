package com.example.screencensor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.*
import java.nio.FloatBuffer

/** NudeNet 320n (YOLOv8n) via ONNX Runtime. Output: [1, 22, 2100] = 4 box + 18 classes. */
class Detector(ctx: Context) {
    data class Det(val label: Int, val score: Float, val box: RectF)

    companion object {
        const val SIZE = 320
        val NUDE = setOf(2, 3, 4, 6, 14)      // buttocks, breast, genitalia (f/m), anus - exposed
        val CLEAVAGE = setOf(16, 13)          // covered female breast, exposed belly (approximate)
        val FACE = setOf(1, 12)
        val FEET = setOf(7, 9)
    }

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(ctx.assets.open("320n.onnx").readBytes())

    @Suppress("UNCHECKED_CAST")
    fun detect(bmp: Bitmap, minScore: Float): List<Det> {
        val w = bmp.width
        val h = bmp.height
        val m = maxOf(w, h)
        val s = SIZE.toFloat() / m
        // pad to square (bottom/right), resize to 320
        val sq = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(sq)
        c.drawColor(Color.BLACK)
        c.drawBitmap(bmp, null, RectF(0f, 0f, w * s, h * s), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(SIZE * SIZE)
        sq.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        sq.recycle()
        val plane = SIZE * SIZE
        val buf = FloatBuffer.allocate(3 * plane)
        for (i in px.indices) {
            val p = px[i]
            buf.put(i, ((p shr 16) and 255) / 255f)
            buf.put(i + plane, ((p shr 8) and 255) / 255f)
            buf.put(i + 2 * plane, (p and 255) / 255f)
        }
        val cand = ArrayList<Det>()
        val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
        try {
            session.run(mapOf(session.inputNames.first() to tensor)).use { r ->
                val out = (r[0].value as Array<Array<FloatArray>>)[0]  // [22][2100]
                val n = out[0].size
                for (j in 0 until n) {
                    var best = -1
                    var bs = 0f
                    for (k in 4 until out.size) {
                        val sc = out[k][j]
                        if (sc > bs) { bs = sc; best = k - 4 }
                    }
                    if (best >= 0 && bs >= minScore) {
                        val cx = out[0][j]; val cy = out[1][j]; val bw = out[2][j]; val bh = out[3][j]
                        val l = ((cx - bw / 2) / s).coerceIn(0f, w.toFloat())
                        val t = ((cy - bh / 2) / s).coerceIn(0f, h.toFloat())
                        val rr = ((cx + bw / 2) / s).coerceIn(0f, w.toFloat())
                        val bb = ((cy + bh / 2) / s).coerceIn(0f, h.toFloat())
                        if (rr > l && bb > t) cand.add(Det(best, bs, RectF(l, t, rr, bb)))
                    }
                }
            }
        } finally {
            tensor.close()
        }
        return nms(cand)
    }

    private fun nms(d: List<Det>): List<Det> {
        val sorted = d.sortedByDescending { it.score }
        val keep = ArrayList<Det>()
        for (x in sorted) {
            if (keep.none { it.label == x.label && iou(it.box, x.box) > 0.45f }) keep.add(x)
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
