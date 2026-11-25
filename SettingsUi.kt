package com.example.screencensor

import android.content.Context
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*

/** The censoring settings, used both on the start screen and in the browser's menu. */
object SettingsUi {
    fun addSettings(ctx: Context, root: LinearLayout, p: SharedPreferences) {
        fun label(t: String) = TextView(ctx).apply { text = t; textSize = 16f; setPadding(0, 32, 0, 8) }
        fun check(t: String, key: String, def: Boolean) = CheckBox(ctx).apply {
            text = t; isChecked = p.getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> p.edit().putBoolean(key, v).apply() }
        }
        fun radios(key: String, def: String, opts: List<Pair<String, String>>) = RadioGroup(ctx).apply {
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
            root.addView(SeekBar(ctx).apply {
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

        root.addView(label("Videos"))
        root.addView(check("Block videos (they can't be censored; untick to watch them uncensored)", "blockvideo", true))

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
        root.addView(radios("style", "mosaic", listOf(
            "mosaic" to "Mosaic (pixels)",
            "blur" to "Blur",
            "solid" to "Solid box",
            "mannequin" to "Mannequin (skin becomes white gloss, same proportions)",
            "nullify" to "Remove nudity (nipples gone, genitals smoothed out)"
        )))
        strength("Pixel size (higher = bigger blocks)", "pixel", 4)
        strength("Blur strength (higher = blurrier)", "blurs", 5)
        root.addView(check("Mannequin: also blank the face", "mqface", true))
        root.addView(TextView(ctx).apply {
            text = "Mannequin and Remove nudity work on faces, breasts, genitals and anus. Anything else you tick under Censor " +
                "(buttocks, cleavage, feet ...) is still covered with the mosaic."
            textSize = 13f
        })

        root.addView(label("Text on the cover"))
        root.addView(check("Show text labels", "labels", true))
        root.addView(EditText(ctx).apply {
            hint = "Words, separated by commas"
            setText(p.getString("words", "LOSER, PIG, GOONER"))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { p.edit().putString("words", s.toString()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        root.addView(label("Sensitivity (right = catches more)"))
        root.addView(SeekBar(ctx).apply {
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

        root.addView(label("Focus (safe browser only)"))
        root.addView(radios("heelmode", "off", listOf(
            "off" to "Off",
            "zoom" to "Zoom in on the target",
            "isolate_blur" to "Blur everything except the target",
            "isolate_black" to "Black out everything except the target"
        )))
        root.addView(label("Focus target"))
        root.addView(radios("focus", "heels", listOf(
            "heels" to "High heels",
            "cock" to "Male genitals",
            "both" to "Both"
        )))
        strength("Heel detection sensitivity (higher = finds more)", "heelsens", 7)
        root.addView(check("Also count feet and other footwear as heels", "heelmore", true))
        root.addView(TextView(ctx).apply {
            text = "Pictures where the target isn't found are simply censored with your normal settings. " +
                "The result is censored afterwards too: to see the focused male genitals, untick \"Male genitals\" under Censor."
            textSize = 13f
        })
    }
}
