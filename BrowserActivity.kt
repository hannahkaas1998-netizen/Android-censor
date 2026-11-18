package com.example.screencensor

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

class BrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBox: EditText

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        Thread { try { ImageProxy.warm(this) } catch (_: Throwable) {} }.start()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 8) }
        val back = Button(this).apply { text = "<"; setOnClickListener { if (web.canGoBack()) web.goBack() } }
        urlBox = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        val go = Button(this).apply { text = "Go"; setOnClickListener { load(urlBox.text.toString()) } }
        val feedBtn = Button(this).apply { text = "Feed"; setOnClickListener { web.loadUrl(FEED) } }
        bar.addView(back)
        bar.addView(urlBox)
        bar.addView(go)
        bar.addView(feedBtn)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest): WebResourceResponse? {
                // our own phone-friendly feed page, served from inside the app
                if (r.isForMainFrame && r.url.host == "old.reddit.com" && r.url.path == "/__feed") {
                    return WebResourceResponse(
                        "text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                        this@BrowserActivity.assets.open("feed.html")
                    )
                }
                return ImageProxy.handle(this@BrowserActivity, r, p)
            }

            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val s = r?.url?.scheme
                return s != "http" && s != "https"
            }

            override fun onPageStarted(v: WebView?, url: String?, f: Bitmap?) {
                if (url != null) urlBox.setText(url)
            }
        }
        if (Build.VERSION.SDK_INT >= 24) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(r: WebResourceRequest?): WebResourceResponse? =
                    if (r == null) null else ImageProxy.handle(this@BrowserActivity, r, p)
            })
        }

        root.addView(bar)
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (p.getBoolean("feedmode", true)) web.loadUrl(FEED)
        else load(p.getString("home", "https://old.reddit.com") ?: "https://old.reddit.com")
    }

    companion object {
        const val FEED = "https://old.reddit.com/__feed"
    }

    private fun load(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.contains("://") -> s
            s.contains(".") && !s.contains(" ") -> "https://$s"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(s)
        }
        web.loadUrl(url)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
