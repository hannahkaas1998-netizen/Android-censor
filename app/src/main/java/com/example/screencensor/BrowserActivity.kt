package com.example.screencensor

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.SharedPreferences
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONTokener
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

class BrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var bridge: FeedBridge
    private lateinit var bar: LinearLayout
    private var ua: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        Thread { try { ImageProxy.warm(this) } catch (_: Throwable) {} }.start()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 8) }
        val back = Button(this).apply { text = "<"; setOnClickListener { if (web.canGoBack()) web.goBack() } }
        urlBox = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        val saveBtn = Button(this).apply { text = "\u2B07"; setOnClickListener { collectAndSave() } }
        val feedBtn = Button(this).apply { text = "Feed"; setOnClickListener { web.loadUrl(FEED) } }
        val gear = Button(this).apply { text = "\u2699"; setOnClickListener { openSettings(p) } }
        val playBtn = Button(this).apply { text = "\u25B6"; setOnClickListener { web.loadUrl(FEED + "#__play") } }
        bar.addView(back)
        bar.addView(urlBox)
        bar.addView(feedBtn)
        bar.addView(playBtn)
        bar.addView(gear)
        bar.addView(saveBtn)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        // look like a normal Chrome browser instead of an embedded WebView
        ua = web.settings.userAgentString.replace("; wv", "").replace(" Version/4.0", "")
        web.settings.userAgentString = ua
        bridge = FeedBridge(web, ua, p, getSharedPreferences("gamebak", MODE_PRIVATE),
            { on -> runOnUiThread { setFullscreen(on) } }) { urls -> runOnUiThread { confirmSave(urls) } }
        bridge.restoreLevels()      // in case a slideshow was cut short last time
        web.addJavascriptInterface(bridge, "Android")
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
                if (url != null) {
                    urlBox.setText(url)
                    bridge.page = url
                }
                bridge.restoreLevels()      // a new page means any slideshow is over
                setFullscreen(false)
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
        if (intent.getBooleanExtra("play", false)) web.loadUrl(FEED + "#__play")
        else if (p.getBoolean("feedmode", true)) web.loadUrl(FEED)
        else load(p.getString("home", "https://old.reddit.com") ?: "https://old.reddit.com")
    }

    companion object {
        const val FEED = "https://old.reddit.com/__feed"
    }

    /** Slideshow mode: hide the browser bar and the system bars, keep the screen awake. */
    private fun setFullscreen(on: Boolean) {
        bar.visibility = if (on) View.GONE else View.VISIBLE
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                if (on) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else it.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on)
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            else View.SYSTEM_UI_FLAG_VISIBLE
        }
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

    /** Censor settings, right here on top of the browser. The page reloads when you close it if anything changed. */
    private fun openSettings(p: SharedPreferences) {
        val before = sig(p)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 16) }
        SettingsUi.addSettings(this, root, p)
        AlertDialog.Builder(this)
            .setTitle("Censor settings")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Done", null)
            .setOnDismissListener { if (sig(p) != before) web.reload() }
            .show()
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
        bridge.restoreLevels()
        web.destroy()
        super.onDestroy()
    }
}
