package com.example.screencensor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Semaphore

/**
 * Sits between the web page and the network. Every image the page asks for is downloaded here,
 * edited, and only then handed to the page, so the page never gets the original.
 * If anything goes wrong, the page gets a "BLOCKED" picture instead (fails closed).
 */
object ImageProxy {
    private class Cached(val mime: String, val bytes: ByteArray)

    private val cache = object : LruCache<String, Cached>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Cached): Int = value.bytes.size
    }
    private val sem = Semaphore(2)
    @Volatile private var engine: ImageCensor.Engine? = null

    private fun getEngine(ctx: Context): ImageCensor.Engine =
        engine ?: synchronized(this) {
            engine ?: ImageCensor.Engine(ctx.applicationContext).also { engine = it }
        }

    fun warm(ctx: Context) { getEngine(ctx) }

    fun handle(ctx: Context, r: WebResourceRequest, p: SharedPreferences): WebResourceResponse? {
        if (r.method != "GET" || r.isForMainFrame) return null
        val url = r.url.toString()
        if (!url.startsWith("http")) return null
        val path = (r.url.path ?: "").lowercase()
        val headers: Map<String, String> = r.requestHeaders ?: emptyMap()
        val accept = (headers["Accept"] ?: headers["accept"] ?: "").lowercase()
        val host = r.url.host ?: ""

        val video = accept.startsWith("video/") || host == "v.redd.it" ||
            path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            path.endsWith(".m4s") || path.endsWith(".webm")
        if (video) return if (p.getBoolean("blockvideo", true)) blocked() else null

        val isImage = path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".avif") || path.endsWith(".bmp") ||
            (accept.contains("image/") && !path.endsWith(".svg") && !path.endsWith(".ico"))
        if (!isImage) return null

        return try {
            image(ctx, url, headers, p)
        } catch (e: Throwable) {
            placeholder()
        }
    }

    private fun image(ctx: Context, url: String, headers: Map<String, String>, p: SharedPreferences): WebResourceResponse {
        val (m, b) = processed(ctx, url, headers, p)
        return respond(m, b)
    }

    /** The picture as it should be shown or saved: downloaded, censored per the settings, cached. */
    fun processed(ctx: Context, url: String, headers: Map<String, String>, p: SharedPreferences): Pair<String, ByteArray> {
        val key = url + "|" + p.all.filterKeys { it != "home" }.toString().hashCode()
        cache.get(key)?.let { return Pair(it.mime, it.bytes) }
        sem.acquire()
        try {
            cache.get(key)?.let { return Pair(it.mime, it.bytes) }
            val (mime, raw) = fetch(url, headers)
            var outMime = mime
            var outBytes = raw
            if (mime.startsWith("image/") && !mime.contains("svg")) {
                val st = p.getString("style", "mosaic")
                val cap = if (st == "mannequin" || st == "smooth") 1600 else 2400    // the heavy effects need more memory
                val bmp = decode(raw, cap) ?: throw IOException("can't read this picture")   // never show what we can't check
                if (bmp.width >= 96 && bmp.height >= 96) {
                    val res = ImageCensor.process(getEngine(ctx), bmp, p)
                    if (res != null) {
                        outBytes = jpeg(res)
                        outMime = "image/jpeg"
                    }
                }
            }
            cache.put(key, Cached(outMime, outBytes))
            return Pair(outMime, outBytes)
        } finally {
            sem.release()
        }
    }

    private fun fetch(url: String, headers: Map<String, String>): Pair<String, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        val skip = setOf("host", "content-length", "accept-encoding", "connection", "range")
        for ((k, v) in headers) if (k.lowercase() !in skip) c.setRequestProperty(k, v)
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        val code = c.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        val mime = (c.contentType ?: "image/jpeg").substringBefore(';').trim().lowercase()
        val ins = c.inputStream
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var total = 0
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > 30 * 1024 * 1024) throw IOException("too big")
            bos.write(buf, 0, n)
        }
        ins.close()
        c.disconnect()
        return Pair(mime, bos.toByteArray())
    }

    private fun decode(raw: ByteArray, cap: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > cap) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, o2)
    }

    private fun jpeg(b: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        return bos.toByteArray()
    }

    private fun respond(mime: String, bytes: ByteArray) = WebResourceResponse(
        mime, null, 200, "OK",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
        ByteArrayInputStream(bytes)
    )

    private fun placeholder(): WebResourceResponse {
        val bos = ByteArrayOutputStream()
        ImageCensor.placeholder(600, 400, "BLOCKED").compress(Bitmap.CompressFormat.PNG, 100, bos)
        return respond("image/png", bos.toByteArray())
    }

    private fun blocked() = WebResourceResponse(
        "text/plain", "utf-8", 403, "Blocked", mapOf<String, String>(), ByteArrayInputStream(ByteArray(0))
    )
}
