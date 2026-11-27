package com.example.screencensor

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
    private val onSave: (List<String>) -> Unit,
    private val onImmersive: (Boolean) -> Unit
) {
    @Volatile var page: String = ""

    /** The feed's full-screen viewer asks to save pictures to Photos. Only the feed page may do this. */
    /** The random-pictures screen asks for the whole screen (hides the bars). Only the feed page may do this. */
    @JavascriptInterface
    fun immersive(on: Boolean) {
        if (!page.startsWith("https://old.reddit.com/__feed")) return
        onImmersive(on)
    }

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
