package com.example.screencensor

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONTokener

class BrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var bar: LinearLayout
    private lateinit var bridge: FeedBridge
    private var ua: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Ui.CARD
        window.navigationBarColor = Ui.BG
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        Thread { try { ImageProxy.warm(this) } catch (_: Throwable) {} }.start()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.CARD)
            setPadding(Ui.dp(this@BrowserActivity, 8), Ui.dp(this@BrowserActivity, 6), Ui.dp(this@BrowserActivity, 8), Ui.dp(this@BrowserActivity, 6))
        }

        fun pill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.rounded(this@BrowserActivity, Ui.CARD2, 10)
            minimumWidth = Ui.dp(this@BrowserActivity, 40)
            minimumHeight = Ui.dp(this@BrowserActivity, 40)
            setPadding(Ui.dp(this@BrowserActivity, 10), 0, Ui.dp(this@BrowserActivity, 10), 0)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Ui.dp(this@BrowserActivity, 4)
            }
        }

        urlBox = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 14f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            hint = "Search or type a web address"
            background = Ui.rounded(this@BrowserActivity, Ui.CARD2, 10)
            setPadding(Ui.dp(this@BrowserActivity, 12), Ui.dp(this@BrowserActivity, 9), Ui.dp(this@BrowserActivity, 12), Ui.dp(this@BrowserActivity, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Ui.dp(this@BrowserActivity, 6)
            }
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }

        bar.addView(pill("\u2302") { finish() })                  // home: back to the main app screen
        bar.addView(pill("\u2039") { if (web.canGoBack()) web.goBack() })
        bar.addView(urlBox)
        bar.addView(pill("Feed") { web.loadUrl(FEED) })
        bar.addView(pill("\u2699") { openSettings(p) })
        bar.addView(pill("\u2B07") { collectAndSave() })

        web = WebView(this)
        web.setBackgroundColor(Ui.BG)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false      // the countdown video must be able to start by itself
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        // look like a normal Chrome browser instead of an embedded WebView
        ua = web.settings.userAgentString.replace("; wv", "").replace(" Version/4.0", "")
        web.settings.userAgentString = ua
        bridge = FeedBridge(
            web, ua,
            { urls -> runOnUiThread { confirmSave(urls) } },
            { on -> runOnUiThread { applyFullScreen(on) } }
        )
        web.addJavascriptInterface(bridge, "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest): WebResourceResponse? {
                // the countdown video: the one you picked if there is one, otherwise the one built into the app
                if (r.url.host == "old.reddit.com" && r.url.path == "/__countdown.mp4") {
                    val custom = CountdownVideo.file(this@BrowserActivity)
                    val hdr = mapOf("Cache-Control" to "no-store", "X-Source" to (if (custom.exists()) "custom" else "default"))
                    return try {
                        val st = if (custom.exists()) custom.inputStream() else this@BrowserActivity.assets.open("countdown.mp4")
                        WebResourceResponse("video/mp4", null, 200, "OK", hdr, st)
                    } catch (e: Exception) {
                        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", hdr, java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                // the pace-game settings chosen in the main menu, for the full screen page
                if (r.url.host == "old.reddit.com" && r.url.path == "/__pace.json") {
                    val j = org.json.JSONObject()
                        .put("lv", p.getString("pace_lv", "random"))
                        .put("snd", p.getBoolean("pace_snd", false))
                        .put("vib", p.getBoolean("pace_vib", true))
                        .put("csnd", p.getBoolean("pace_csnd", true))
                        .put("voice", p.getBoolean("pace_voice", false))
                        .put("vid", p.getBoolean("pace_vid", true))
                        .put("vpic", p.getBoolean("pace_vpic", true))
                        .put("lead", (p.getString("pace_lead", "0") ?: "0").toIntOrNull() ?: 0)
                    return WebResourceResponse(
                        "application/json", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                        java.io.ByteArrayInputStream(j.toString().toByteArray())
                    )
                }
                if (r.url.host == "old.reddit.com" && r.url.path == "/__countdown_reset") {
                    CountdownVideo.file(this@BrowserActivity).delete()
                    return WebResourceResponse(
                        "text/plain", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                        java.io.ByteArrayInputStream("ok".toByteArray())
                    )
                }
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
                if (url != null) {
                    urlBox.setText(url)
                    bridge.page = url
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 24) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(r: WebResourceRequest?): WebResourceResponse? =
                    if (r == null) null else ImageProxy.handle(this@BrowserActivity, r, p)
            })
        }

        root.addView(bar)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        when {
            intent.getBooleanExtra("shuffle", false) -> web.loadUrl("$FEED?mode=shuffle")
            p.getBoolean("feedmode", true) -> web.loadUrl(FEED)
            else -> load(p.getString("home", "https://old.reddit.com") ?: "https://old.reddit.com")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        CountdownVideo.handle(this, req, res, data)
    }

    companion object {
        const val FEED = "https://old.reddit.com/__feed"
    }

    /** Full screen for the random-pictures view: hides the address bar and the phone's bars. */
    @Suppress("DEPRECATION")
    private fun applyFullScreen(on: Boolean) {
        bar.visibility = if (on) View.GONE else View.VISIBLE
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else View.SYSTEM_UI_FLAG_VISIBLE
    }

    /** Finds the pictures on the current page (the ones that have loaded) and offers to save them to Photos. */
    private fun collectAndSave() {
        val js = """(function () {
  var seen = {}, out = [];
  function add(u) {
    if (!u || u.indexOf('http') !== 0) return;
    var m = /^https?:\/\/(?:preview|i)\.redd\.it\/([a-z0-9]{10,16})\.(jpe?g|png|webp|gif)/i.exec(u);
    var key = u, url = u;
    if (m) { key = m[1].toLowerCase(); url = 'https://i.redd.it/' + key + '.' + m[2].toLowerCase(); }
    else if (!/\.(jpe?g|png|webp|gif)(\?|${'$'})/i.test(u)) return;
    if (!seen[key]) { seen[key] = 1; out.push(url); }
  }
  var imgs = document.images;
  for (var i = 0; i < imgs.length; i++) {
    var im = imgs[i];
    if ((im.naturalWidth || 0) < 300 && (im.naturalHeight || 0) < 300) continue;
    add(im.currentSrc || im.src);
  }
  return JSON.stringify(out.slice(0, 60));
})()"""
        web.evaluateJavascript(js) { res ->
            val urls = ArrayList<String>()
            try {
                val inner = JSONTokener(res).nextValue()
                if (inner is String) {
                    val a = JSONArray(inner)
                    for (i in 0 until a.length()) urls.add(a.getString(i))
                }
            } catch (_: Exception) {
            }
            if (urls.isEmpty()) {
                Toast.makeText(this, "No pictures found on this page. Scroll down so they load, then try again.", Toast.LENGTH_LONG).show()
            } else confirmSave(urls)
        }
    }

    private fun confirmSave(urls: List<String>) {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        AlertDialog.Builder(this)
            .setTitle("Save ${urls.size} picture${if (urls.size == 1) "" else "s"} to Photos?")
            .setMessage("They are censored with your current settings first, and saved in Pictures/ScreenCensor.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()
                val app = applicationContext
                Thread {
                    val ok = PhotoSaver.save(app, urls, ua, p)
                    runOnUiThread {
                        Toast.makeText(this, "Saved $ok of ${urls.size} to Photos (Pictures/ScreenCensor)", Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .show()
    }

    private fun sig(p: SharedPreferences) = p.all.filterKeys { it != "home" }.toString()

    /** Censor settings on top of the browser. The page reloads when you close it if anything changed. */
    private fun openSettings(p: SharedPreferences) {
        val before = sig(p)
        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        val pad = Ui.dp(this, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.CARD)
            setPadding(pad, Ui.dp(this@BrowserActivity, 10), pad, Ui.dp(this@BrowserActivity, 10))
        }
        top.addView(Ui.label(this, "Censor settings", 18f, Ui.TEXT, true).apply { layoutParams = Ui.weight() })
        top.addView(Ui.button(this, "Done", true) { dlg.dismiss() })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        SettingsUi.addSettings(this, body, p, false)
        root.addView(top)
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dlg.setContentView(root)
        dlg.window?.statusBarColor = Ui.CARD
        dlg.window?.navigationBarColor = Ui.BG
        dlg.setOnDismissListener { if (sig(p) != before) web.reload() }
        dlg.show()
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
        val u = web.url ?: ""
        if (u.startsWith(FEED)) {
            // on the feed: first close a picture / the shuffle if one is open, otherwise leave for the main screen
            web.evaluateJavascript("(function(){return window.feedBack?window.feedBack():false})()") { r ->
                if (r != "true") finish()
            }
        } else if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
