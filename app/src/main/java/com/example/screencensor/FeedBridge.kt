package com.example.screencensor

import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lets the feed page ask the app to download Reddit pages for it, using the browser's login cookies.
 * Only the feed page may use it, and only for old.reddit.com.
 */
class FeedBridge(
    private val web: WebView,
    private val ua: String,
    private val prefs: SharedPreferences,
    private val backup: SharedPreferences,
    private val onFullscreen: (Boolean) -> Unit,
    private val onSave: (List<String>) -> Unit
) {
    @Volatile var page: String = ""

    private fun onFeed() = page.startsWith("https://old.reddit.com/__feed")

    /** The slideshow asks for the whole screen (browser bar and system bars hidden, screen kept on). */
    @JavascriptInterface
    fun fullscreen(on: Boolean) {
        if (!onFeed()) return
        onFullscreen(on)
    }

    /**
     * The slideshow game changes the censor style between rounds. Only style / pixel size / blur strength can be
     * changed, only to known values, and the person's own settings are remembered and put back afterwards.
     */
    @JavascriptInterface
    fun gameLevel(json: String) {
        if (!onFeed()) return
        try {
            val o = JSONObject(json)
            val style = o.optString("style", "")
            if (style !in setOf("mosaic", "blur", "solid", "mannequin", "smooth")) return
            if (!backup.getBoolean("active", false)) {
                backup.edit().putBoolean("active", true)
                    .putString("style", prefs.getString("style", "mosaic"))
                    .putInt("pixel", prefs.getInt("pixel", 6))
                    .putInt("blurs", prefs.getInt("blurs", 5)).apply()
            }
            val e = prefs.edit().putString("style", style)
            if (o.has("pixel")) e.putInt("pixel", o.getInt("pixel").coerceIn(1, 10))
            if (o.has("blurs")) e.putInt("blurs", o.getInt("blurs").coerceIn(1, 10))
            e.apply()
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun gameEnd() {
        if (!onFeed()) return
        restoreLevels()
    }

    /** Puts the person's own censor style back. Also called when a page loads and when the browser closes. */
    fun restoreLevels() {
        if (!backup.getBoolean("active", false)) return
        prefs.edit()
            .putString("style", backup.getString("style", "mosaic"))
            .putInt("pixel", backup.getInt("pixel", 6))
            .putInt("blurs", backup.getInt("blurs", 5)).apply()
        backup.edit().clear().apply()
    }

    /** The feed's full-screen viewer asks to save pictures to Photos. Only the feed page may do this. */
    @JavascriptInterface
    fun save(json: String) {
        if (!page.startsWith("https://old.reddit.com/__feed")) return
        val urls = ArrayList<String>()
        try {
            val a = org.json.JSONArray(json)
            for (i in 0 until minOf(a.length(), 60)) {
                val u = a.getString(i)
                if (u.startsWith("https://")) urls.add(u)
            }
        } catch (_: Exception) {
        }
        if (urls.isNotEmpty()) onSave(urls)
    }

    @JavascriptInterface
    fun fetch(id: Int, path: String) {
        val allowed = page.startsWith("https://old.reddit.com/__feed")
        Thread {
            var status: Int
            var body = ""
            try {
                if (!allowed) throw IOException("not allowed here")
                // optional prefixes: @www = www.reddit.com instead of old.reddit.com, @api = plain API-style identity
                var rest = path
                var www = false
                var api = false
                while (rest.startsWith("@")) {
                    when {
                        rest.startsWith("@www") -> { www = true; rest = rest.removePrefix("@www") }
                        rest.startsWith("@api") -> { api = true; rest = rest.removePrefix("@api") }
                        else -> throw IOException("bad path")
                    }
                }
                if (!rest.startsWith("/") || rest.startsWith("//")) throw IOException("bad path")
                val url = "https://" + (if (www) "www.reddit.com" else "old.reddit.com") + rest
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 20000
                c.instanceFollowRedirects = true
                c.setRequestProperty(
                    "User-Agent",
                    if (api) "android:com.example.screencensor:v1.0 (personal feed reader)" else ua
                )
                c.setRequestProperty(
                    "Accept",
                    if (api) "application/json" else "application/json,text/html;q=0.9,*/*;q=0.8"
                )
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                status = c.responseCode
                c.headerFields?.get("Set-Cookie")?.forEach { CookieManager.getInstance().setCookie(url, it) }
                val s = if (status in 200..399) c.inputStream else c.errorStream
                body = s?.bufferedReader()?.use { it.readText() }?.take(3_000_000) ?: ""
                c.disconnect()
            } catch (e: Exception) {
                status = -1
                body = e.message ?: "error"
            }
            val js = "window.__nativeCb($id,$status,${JSONObject.quote(body)})"
            web.post { web.evaluateJavascript(js, null) }
        }.start()
    }
}
