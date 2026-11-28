package com.example.screencensor

import android.content.Context
import android.content.SharedPreferences
import android.app.Activity
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.LinearLayout
import android.widget.Toast

/** The settings, as tidy collapsible cards. Used on the start screen and in the browser's menu. */
object SettingsUi {
    fun addSettings(ctx: Context, root: LinearLayout, p: SharedPreferences, includeBrowser: Boolean) {
        // ---- what to censor
        val what = Ui.section(ctx, root, "What to censor", true)
        Ui.toggle(ctx, what, "Nudity", "Breasts, genitals, anus", p, "nudity", true)
        Ui.toggle(ctx, what, "Male genitals", "Untick to leave them uncovered", p, "male", true)
        Ui.toggle(ctx, what, "Buttocks", "Also when clothed", p, "butt", true)
        Ui.toggle(ctx, what, "Cleavage", "Covered chest", p, "cleavage", true)
        Ui.toggle(ctx, what, "Faces", null, p, "face", false)
        Ui.toggle(ctx, what, "Eyes only", null, p, "eyes", false)
        Ui.toggle(ctx, what, "Feet", null, p, "feet", false)

        // ---- how it looks
        val look = Ui.section(ctx, root, "How it looks", true)
        Ui.sub(ctx, look, "STYLE")
        Ui.chips(ctx, look, p, "style", "mosaic", listOf(
            "mosaic" to "Mosaic",
            "blur" to "Blur",
            "solid" to "Solid box",
            "mannequin" to "Mannequin",
            "smooth" to "Smooth"
        ), 3)
        look.addView(Ui.note(ctx, "Mannequin: white gloss skin, same shape. Smooth: nipples and genitals disappear."))
        Ui.sub(ctx, look, "COVER")
        Ui.chips(ctx, look, p, "cover", "parts", listOf(
            "parts" to "Detected areas",
            "group" to "Whole body area",
            "screen" to "Entire picture"
        ), 3)
        Ui.slider(ctx, look, "Pixel size", 1, 10, p.getInt("pixel", 6), { "$it / 10" }) { p.edit().putInt("pixel", it).apply() }
        Ui.slider(ctx, look, "Blur strength", 1, 10, p.getInt("blurs", 5), { "$it / 10" }) { p.edit().putInt("blurs", it).apply() }
        Ui.toggle(ctx, look, "Follow the body shape", "Instead of a box (mosaic, blur, solid)", p, "shape", true)
        Ui.toggle(ctx, look, "Mannequin: blank white face", null, p, "mface", true)

        // ---- text on the cover
        val text = Ui.section(ctx, root, "Text on the cover", false)
        Ui.toggle(ctx, text, "Show text labels", null, p, "labels", true)
        Ui.field(ctx, text, "Words, separated by commas", p.getString("words", "LOSER, PIG, GOONER") ?: "") {
            p.edit().putString("words", it).apply()
        }

        // ---- detection
        val det = Ui.section(ctx, root, "Detection", false)
        val level = Math.round((0.80f - p.getFloat("sens", 0.35f)) / (0.70f / 9f)) + 1
        Ui.slider(ctx, det, "Sensitivity", 1, 10, level.coerceIn(1, 10), { "$it / 10" }) { lv ->
            p.edit().putFloat("sens", 0.80f - (lv - 1) * 0.70f / 9f).apply()
        }
        det.addView(Ui.note(ctx, "Higher catches more, but also covers more things by mistake."))

        // ---- focus mode
        val focus = Ui.section(ctx, root, "Focus mode (safe browser)", false)
        Ui.sub(ctx, focus, "WHAT TO DO")
        Ui.chips(ctx, focus, p, "heelmode", "off", listOf(
            "off" to "Off",
            "zoom" to "Zoom in",
            "isolate_blur" to "Blur the rest",
            "isolate_black" to "Black out the rest"
        ), 2)
        Ui.sub(ctx, focus, "FOCUS ON")
        Ui.chips(ctx, focus, p, "focustarget", "heels", listOf("heels" to "High heels", "male" to "Male genitals"), 2)
        Ui.slider(ctx, focus, "Heel detection", 1, 10, p.getInt("heelsens", 7), { "$it / 10" }) { p.edit().putInt("heelsens", it).apply() }
        Ui.toggle(ctx, focus, "Heels: count feet and other footwear", null, p, "heelmore", true)
        focus.addView(Ui.note(ctx, "Pictures with nothing to focus on are censored normally. For male genitals, untick \u201cMale genitals\u201d under What to censor, or they get covered."))

        // ---- pace game (full screen mode)
        val game = Ui.section(ctx, root, "Pace game (full screen)", false)
        Ui.sub(ctx, game, "PACE")
        Ui.chips(ctx, game, p, "pace_lv", "random", listOf(
            "random" to "Random", "slow" to "Slow", "medium" to "Medium",
            "fast" to "Fast", "super" to "Super fast", "build" to "Build up"
        ), 3)
        game.addView(Ui.note(ctx, "Random jumps between stop, slow, medium, fast and super fast and gets more intense towards the end. Build up rises steadily. The time is picked in full screen mode."))
        Ui.sub(ctx, game, "FEEDBACK")
        Ui.toggle(ctx, game, "Vibrate on every stroke", null, p, "pace_vib", true)
        Ui.toggle(ctx, game, "Tick sound on every stroke", null, p, "pace_snd", false)
        Ui.toggle(ctx, game, "Countdown beeps (last 10 seconds)", "Not used when the video is on", p, "pace_csnd", true)
        Ui.toggle(ctx, game, "Spoken countdown", "Needs text-to-speech on the phone", p, "pace_voice", false)
        Ui.sub(ctx, game, "VIDEO WHEN THE TIMER ENDS")
        Ui.toggle(ctx, game, "Play the video when the timer ends", "Replaces the on-screen countdown", p, "pace_vid", true)
        Ui.toggle(ctx, game, "Show the picture", "Off = sound only", p, "pace_vpic", true)
        Ui.chips(ctx, game, p, "pace_lead", "0", listOf(
            "0" to "At the end", "10" to "10 s before", "20" to "20 s before", "30" to "30 s before"
        ), 2)
        val vstat = Ui.note(ctx, CountdownVideo.status(ctx))
        game.addView(vstat)
        val act = ctx as? Activity
        if (act != null) {
            game.addView(Ui.button(ctx, "Choose video...", false) { CountdownVideo.pick(act) })
            game.addView(Ui.button(ctx, "Use the default video", false) {
                CountdownVideo.reset(ctx)
                vstat.text = CountdownVideo.status(ctx)
            })
        }

        // ---- videos
        val vid = Ui.section(ctx, root, "Videos", false)
        Ui.toggle(ctx, vid, "Block videos", "They can't be censored. Untick to watch them uncensored.", p, "blockvideo", true)

        // ---- reddit login
        val acc = Ui.section(ctx, root, "Reddit login", false)
        acc.addView(Ui.note(ctx, "If logging in keeps giving errors, clear the saved cookies and log in again from scratch."))
        acc.addView(Ui.button(ctx, "Reset login (clear cookies)", false) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            Toast.makeText(ctx, "Cleared. Open the browser and log in again at old.reddit.com/login", Toast.LENGTH_LONG).show()
        })

        // ---- browser (start screen only)
        if (includeBrowser) {
            val br = Ui.section(ctx, root, "Browser", false)
            Ui.toggle(ctx, br, "Open in the feed view", "Only titles and pictures, made for phones", p, "feedmode", true)
            Ui.field(ctx, br, "Start page for the normal site view", p.getString("home", "https://old.reddit.com") ?: "") {
                p.edit().putString("home", it).apply()
            }
        }
    }
}
