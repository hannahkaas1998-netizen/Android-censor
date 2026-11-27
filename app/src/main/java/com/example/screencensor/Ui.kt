package com.example.screencensor

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Small building blocks for the app's screens: dark cards, switches, chips, sliders. */
object Ui {
    val BG = Color.parseColor("#0E0E10")
    val CARD = Color.parseColor("#17171B")
    val CARD2 = Color.parseColor("#232329")
    val TEXT = Color.parseColor("#F2F2F4")
    val MUTED = Color.parseColor("#9A9AA6")
    val ACCENT = Color.parseColor("#7CB4FF")
    val ACCENT_TEXT = Color.parseColor("#06121F")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(ctx, radiusDp).toFloat() }

    fun label(ctx: Context, t: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun lp(ctx: Context, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, top)
            bottomMargin = dp(ctx, bottom)
        }

    fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** A rounded dark card to put things in. */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, CARD, 16)
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
    }

    fun button(ctx: Context, text: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) ACCENT_TEXT else TEXT)
            background = rounded(ctx, if (primary) ACCENT else CARD2, 12)
            setPadding(dp(ctx, 16), dp(ctx, 13), dp(ctx, 16), dp(ctx, 13))
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun note(ctx: Context, t: String): TextView = label(ctx, t, 12f, MUTED).apply {
        setPadding(0, dp(ctx, 6), 0, dp(ctx, 2))
    }

    /** A card with a title you can tap to open or close. Returns the part you add controls to. */
    fun section(ctx: Context, parent: LinearLayout, title: String, expanded: Boolean = false): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, CARD, 16)
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 15), 0, dp(ctx, 15))
        }
        val t = label(ctx, title, 16f, TEXT, true).apply { layoutParams = weight() }
        val arrow = label(ctx, if (expanded) "\u25BE" else "\u25B8", 18f, MUTED)
        head.addView(t)
        head.addView(arrow)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(ctx, 12))
        }
        head.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            arrow.text = if (open) "\u25BE" else "\u25B8"
        }
        card.addView(head)
        card.addView(body)
        parent.addView(card, lp(ctx, 0, 10))
        return body
    }

    fun toggle(ctx: Context, body: LinearLayout, title: String, sub: String?, p: SharedPreferences, key: String, def: Boolean) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = weight()
        }
        col.addView(label(ctx, title, 15f, TEXT))
        if (sub != null) col.addView(label(ctx, sub, 12f, MUTED))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(ctx).apply {
            isChecked = p.getBoolean(key, def)
            thumbTintList = ColorStateList(states, intArrayOf(ACCENT, MUTED))
            trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#3D5A80"), Color.parseColor("#3A3A44")))
            setOnCheckedChangeListener { _, v -> p.edit().putBoolean(key, v).apply() }
        }
        row.addView(col)
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        body.addView(row)
    }

    /** A grid of tap-to-select choices (instead of radio buttons). */
    fun chips(
        ctx: Context, body: LinearLayout, p: SharedPreferences, key: String, def: String,
        opts: List<Pair<String, String>>, cols: Int = 2
    ) {
        val grid = GridLayout(ctx).apply { columnCount = cols }
        val views = ArrayList<Pair<String, TextView>>()
        fun paint() {
            val cur = p.getString(key, def)
            for ((v, tv) in views) {
                val sel = v == cur
                tv.background = rounded(ctx, if (sel) ACCENT else CARD2, 10)
                tv.setTextColor(if (sel) ACCENT_TEXT else TEXT)
            }
        }
        for ((v, text) in opts) {
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 8), dp(ctx, 11), dp(ctx, 8), dp(ctx, 11))
                isClickable = true
                setOnClickListener { p.edit().putString(key, v).apply(); paint() }
            }
            val gp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3))
            }
            grid.addView(tv, gp)
            views.add(Pair(v, tv))
        }
        paint()
        body.addView(grid, lp(ctx, 2, 4))
    }

    fun slider(
        ctx: Context, body: LinearLayout, title: String, lo: Int, hi: Int, value: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ) {
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label(ctx, title, 15f, TEXT).apply { layoutParams = weight() })
        val v = label(ctx, fmt(value), 14f, ACCENT, true)
        head.addView(v)
        val sb = SeekBar(ctx).apply {
            max = hi - lo
            progress = (value - lo).coerceIn(0, hi - lo)
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, u: Boolean) {
                    v.text = fmt(pr + lo)
                    onChange(pr + lo)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        body.addView(head, lp(ctx, 10, 0))
        body.addView(sb, lp(ctx, 0, 4))
    }

    fun field(ctx: Context, body: LinearLayout, hint: String, value: String, onChange: (String) -> Unit): EditText {
        val e = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            textSize = 15f
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            background = rounded(ctx, CARD2, 10)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { onChange(s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        body.addView(e, lp(ctx, 8, 4))
        return e
    }

    fun sub(ctx: Context, body: LinearLayout, title: String) {
        body.addView(label(ctx, title, 13f, MUTED, true).apply { setPadding(0, dp(ctx, 12), 0, 0) })
    }
}
