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
class FeedBridge(private val web: WebView, private val ua: String) {
    @Volatile var page: String = ""

    @JavascriptInterface
    fun fetch(id: Int, path: String) {
        val allowed = page.startsWith("https://old.reddit.com/__feed")
        Thread {
            var status: Int
            var body = ""
            try {
                if (!allowed) throw IOException("not allowed here")
                val www = path.startsWith("@www")
                val rest = if (www) path.removePrefix("@www") else path
                if (!rest.startsWith("/") || rest.startsWith("//")) throw IOException("bad path")
                val url = "https://" + (if (www) "www.reddit.com" else "old.reddit.com") + rest
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 20000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
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
