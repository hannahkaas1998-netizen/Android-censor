package com.example.screencensor

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var p: SharedPreferences
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("cfg", MODE_PRIVATE)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val pad = Ui.dp(this, 16)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@MainActivity, 28), pad, Ui.dp(this@MainActivity, 32))
        }

        root.addView(Ui.label(this, "Screen Censor", 30f, Ui.TEXT, true))
        root.addView(Ui.label(this, "Pictures are censored before you see them.", 14f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 18))
        })

        // ---- safe browser
        val browser = Ui.card(this)
        browser.addView(Ui.label(this, "Safe browser", 18f, Ui.TEXT, true))
        browser.addView(Ui.label(this, "Edits the pictures themselves, so nothing flickers or moves.", 13f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 3), 0, Ui.dp(this@MainActivity, 12))
        })
        browser.addView(Ui.button(this, "Open safe browser", true) {
            startActivity(Intent(this, BrowserActivity::class.java))
        }, Ui.lp(this, 0, 8))
        browser.addView(Ui.button(this, "Random pictures, full screen", false) {
            startActivity(Intent(this, BrowserActivity::class.java).putExtra("shuffle", true))
        }, Ui.lp(this))
        root.addView(browser, Ui.lp(this, 0, 12))

        // ---- overlay
        val overlay = Ui.card(this)
        overlay.addView(Ui.label(this, "Overlay for other apps", 18f, Ui.TEXT, true))
        overlay.addView(Ui.label(this, "Covers things on screen over any app. It can lag behind scrolling.", 13f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 3), 0, Ui.dp(this@MainActivity, 12))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val gap = Ui.dp(this, 4)
        row.addView(Ui.button(this, "Start", true) { start() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = gap })
        row.addView(Ui.button(this, "Stop", false) {
            stopService(Intent(this, CensorService::class.java))
            status.text = "Overlay stopped"
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = gap })
        overlay.addView(row, Ui.lp(this))
        status = Ui.label(this, "Overlay stopped", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
        }
        overlay.addView(status)
        root.addView(overlay, Ui.lp(this, 0, 20))

        // ---- settings
        root.addView(Ui.label(this, "SETTINGS", 12f, Ui.MUTED, true).apply {
            setPadding(Ui.dp(this@MainActivity, 4), 0, 0, Ui.dp(this@MainActivity, 8))
        })
        SettingsUi.addSettings(this, root, p, true)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            status.text = "Allow \"Display over other apps\", come back and tap Start again"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), 100)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (CountdownVideo.handle(this, req, res, data)) return
        if (req == 100 && res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CensorService::class.java).putExtra("code", res).putExtra("data", data)
            )
            status.text = "Overlay running. Open any app."
        } else if (req == 100) status.text = "Screen capture was not allowed"
    }
}
