package com.example.screencensor

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

/** Downloads pictures, censors them with the current settings, and saves them to the Photos app. */
object PhotoSaver {
    /** Returns how many pictures were saved. Runs on the calling thread, so call it from a background thread. */
    fun save(ctx: Context, urls: List<String>, ua: String, p: SharedPreferences): Int {
        val headers = mapOf(
            "User-Agent" to ua,
            "Referer" to "https://www.reddit.com/",
            "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8"
        )
        val stamp = System.currentTimeMillis()
        var ok = 0
        urls.forEachIndexed { i, u ->
            try {
                val (mime, bytes) = ImageProxy.processed(ctx, u, headers, p)
                if (mime.startsWith("image/")) {
                    write(ctx, bytes, mime, "ScreenCensor_${stamp}_${i + 1}")
                    ok++
                }
            } catch (_: Throwable) {
            }
        }
        return ok
    }

    private fun write(ctx: Context, bytes: ByteArray, mime: String, base: String) {
        val ext = when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$base.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenCensor")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw IOException("can't create file")
        (r.openOutputStream(uri) ?: throw IOException("can't write")).use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= 29) {
            val d = ContentValues()
            d.put(MediaStore.Images.Media.IS_PENDING, 0)
            r.update(uri, d, null, null)
        }
    }
}
