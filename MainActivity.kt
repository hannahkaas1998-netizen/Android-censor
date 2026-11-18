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
import android.view.View
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
        fun radios(key: String, def: String, opts: List<Pair<String, String>>) = RadioGroup(this).apply {
            val cur = p.getString(key, def)
            opts.forEach { (v, t) ->
                addView(RadioButton(context).apply { id = View.generateViewId(); text = t; tag = v; isChecked = v == cur })
            }
            setOnCheckedChangeListener { g, id -> p.edit().putString(key, g.findViewById<RadioButton>(id).tag as String).apply() }
        }
        fun strength(title: String, key: String, def: Int) {
            val lab = label("")
            fun set(v: Int) { lab.text = "$title: $v / 10" }
            set(p.getInt(key, def))
            root.addView(lab)
            root.addView(SeekBar(this).apply {
                max = 9
                progress = p.getInt(key, def) - 1
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                        p.edit().putInt(key, v + 1).apply(); set(v + 1)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }

        root.addView(TextView(this).apply { text = "Screen Censor"; textSize = 26f })

        root.addView(label("Safe browser (edits the images themselves: no flicker, nothing moves)"))
        root.addView(Button(this).apply {
            text = "Open safe browser"
            setOnClickListener { startActivity(Intent(this@MainActivity, BrowserActivity::class.java)) }
        })
        root.addView(check("Block videos (they can't be censored)", "blockvideo", true))
        root.addView(check("Photo feed on Reddit: only subreddit, title and picture", "feed", true))
        root.addView(EditText(this).apply {
            hint = "Start page"
            setText(p.getString("home", "https://old.reddit.com"))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { p.edit().putString("home", s.toString()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        root.addView(label("Censor"))
        root.addView(check("Nudity (breasts, genitals, anus)", "nudity", true))
        root.addView(check("Male genitals (untick to leave them uncovered)", "male", true))
        root.addView(check("Buttocks (also when clothed)", "butt", true))
        root.addView(check("Cleavage / covered chest", "cleavage", true))
        root.addView(check("Faces", "face", false))
        root.addView(check("Eyes only", "eyes", false))
        root.addView(check("Feet", "feet", false))

        root.addView(label("Cover"))
        root.addView(radios("cover", "parts", listOf("parts" to "Detected areas only", "group" to "Whole body area", "screen" to "Entire picture")))
        root.addView(label("Style"))
        root.addView(radios("style", "mosaic", listOf("mosaic" to "Mosaic", "blur" to "Blur", "solid" to "Solid box")))
        strength("Pixelation strength (higher = chunkier)", "pixel", 6)
        strength("Blur strength (higher = blurrier)", "blurs", 5)

        root.addView(label("Text on the cover"))
        root.addView(check("Show text labels", "labels", true))
        root.addView(EditText(this).apply {
            hint = "Words, separated by commas"
            setText(p.getString("words", "LOSER, PIG, GOONER"))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { p.edit().putString("words", s.toString()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        root.addView(label("Sensitivity (right = catches more)"))
        root.addView(SeekBar(this).apply {
            max = 70
            progress = 80 - (p.getFloat("sens", 0.35f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    p.edit().putFloat("sens", (80 - v) / 100f).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        root.addView(label("High heels focus (safe browser only)"))
        root.addView(radios("heelmode", "off", listOf(
            "off" to "Off",
            "zoom" to "Zoom in on the heels",
            "isolate_blur" to "Blur everything except the heels",
            "isolate_black" to "Black out everything except the heels"
        )))
        root.addView(label("If a picture has no heels in it"))
        root.addView(radios("heelnone", "hide", listOf("hide" to "Hide the picture", "show" to "Show it normally")))

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
