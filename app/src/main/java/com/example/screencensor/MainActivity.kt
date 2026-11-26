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
import android.text.Editable
import android.text.TextWatcher
import android.widget.*

class MainActivity : Activity() {
    private lateinit var p: SharedPreferences
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("cfg", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 48) }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, 32, 0, 8) }
        fun check(t: String, key: String, def: Boolean) = CheckBox(this).apply {
            text = t; isChecked = p.getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> p.edit().putBoolean(key, v).apply() }
        }

        root.addView(TextView(this).apply { text = "Screen Censor"; textSize = 26f })

        root.addView(label("Safe browser (edits the images themselves: no flicker, nothing moves)"))
        root.addView(Button(this).apply {
            text = "Open safe browser"
            setOnClickListener { startActivity(Intent(this@MainActivity, BrowserActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Slideshow game (full screen)"
            setOnClickListener { startActivity(Intent(this@MainActivity, BrowserActivity::class.java).putExtra("play", true)) }
        })
        root.addView(check("Open in the feed view (only titles and pictures, made for phones)", "feedmode", true))
        root.addView(EditText(this).apply {
            hint = "Start page for the normal site view"
            setText(p.getString("home", "https://old.reddit.com"))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { p.edit().putString("home", s.toString()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        SettingsUi.addSettings(this, root, p)

        root.addView(label("Other apps (screen overlay)"))
        root.addView(Button(this).apply { text = "Start overlay"; setOnClickListener { start() } })
        root.addView(Button(this).apply {
            text = "Stop overlay"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CensorService::class.java))
                status.text = "Stopped"
            }
        })
        status = TextView(this).apply { text = "Overlay stopped"; setPadding(0, 24, 0, 0) }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            status.text = "Allow \"Display over other apps\", come back and tap Start overlay again"
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
        if (req == 100 && res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CensorService::class.java).putExtra("code", res).putExtra("data", data)
            )
            status.text = "Overlay running. Open any app."
        } else if (req == 100) status.text = "Screen capture was not allowed"
    }
}
