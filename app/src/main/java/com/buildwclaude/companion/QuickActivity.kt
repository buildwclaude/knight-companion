package com.buildwclaude.companion

import android.app.Activity
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import kotlin.math.roundToInt

/** The menu you get by holding the knight: focus sessions and quick reminders. */
class QuickActivity : Activity() {

    private lateinit var content: LinearLayout
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(20), px(22), px(16))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        window.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        build()
    }

    private fun build() {
        content.removeAllViews()
        content.addView(text("What's the plan?", 22f, Color.WHITE, medium = true))

        // ---- focus
        content.addView(label("FOCUS"))
        val focusEnd = Reminders.focusEnd(this)
        if (focusEnd > System.currentTimeMillis()) {
            content.addView(text("Focusing until ${clock(focusEnd)}. The knight stands guard.", 15f, SOFT))
            content.addView(chip("Stop focus", outline = true) {
                Reminders.stopFocus(this)
                build()
            }, spaced(10))
        } else {
            content.addView(row(
                chip("Focus 25 min") { focus(25) },
                chip("Focus 50 min") { focus(50) },
                chip("Break 5 min") { remindIn("Break's over. Back to it", 5) },
            ))
        }

        // ---- reminder
        content.addView(label("REMIND ME TO…"))
        val input = EditText(this).apply {
            hint = "take out the laundry"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(Color.parseColor("#222536"), 14)
            setPadding(px(14), px(12), px(14), px(12))
        }
        content.addView(input, spaced(0))
        fun what() = input.text.toString().trim().ifEmpty { "Your reminder" }
        content.addView(row(
            chip("5 min") { remindIn(what(), 5) },
            chip("15 min") { remindIn(what(), 15) },
            chip("30 min") { remindIn(what(), 30) },
            chip("1 hour") { remindIn(what(), 60) },
            chip("At…", outline = true) { pickTime(what()) },
        ), spaced(10))

        // ---- upcoming
        val upcoming = Reminders.all(this).filterNot { it.focus }
        if (upcoming.isNotEmpty()) {
            content.addView(label("UPCOMING"))
            upcoming.forEach { r ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                line.addView(text(clock(r.at), 14f, ACCENT, mono = true).apply { minWidth = px(64) })
                line.addView(
                    text(r.text, 15f, Color.WHITE),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                line.addView(TextView(this).apply {
                    text = "✕"
                    setTextColor(MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding(px(12), px(6), px(4), px(6))
                    contentDescription = "Cancel reminder"
                    setOnClickListener {
                        Reminders.cancel(this@QuickActivity, r.id)
                        build()
                    }
                })
                content.addView(line, spaced(4))
            }
        }

        content.addView(chip("Done", outline = true) { finish() }, spaced(22))
    }

    private fun focus(minutes: Int) {
        Reminders.startFocus(this, minutes)
        toast("Focus on. I'll call time at ${clock(Reminders.focusEnd(this))}.")
        finish()
    }

    private fun remindIn(what: String, minutes: Int) {
        val r = Reminders.add(this, what, System.currentTimeMillis() + minutes * 60_000L)
        toast("I'll remind you at ${clock(r.at)}.")
        finish()
    }

    private fun pickTime(what: String) {
        val now = Calendar.getInstance()
        TimePickerDialog(this, { _, h, m ->
            val at = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            val r = Reminders.add(this, what, at.timeInMillis)
            toast("I'll remind you at ${clock(r.at)}.")
            finish()
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), DateFormat.is24HourFormat(this)).show()
    }

    private fun clock(at: Long): String = DateFormat.getTimeFormat(this).format(at)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ little view helpers

    private fun px(v: Int) = (v * dp).roundToInt()

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * dp
        if (stroke != null) setStroke(px(1), stroke)
    }

    private fun text(s: String, sp: Float, color: Int, medium: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = when {
                mono -> Typeface.MONOSPACE
                medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else -> Typeface.DEFAULT
            }
        }

    private fun label(s: String) = text(s, 11f, MUTED, mono = true).apply {
        letterSpacing = 0.18f
        setPadding(0, px(20), 0, px(8))
    }

    private fun chip(label: String, outline: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        minHeight = px(42)
        minimumHeight = px(42)
        setPadding(px(16), 0, px(16), 0)
        background = if (outline) rounded(Color.TRANSPARENT, 21, ACCENT) else rounded(ACCENT, 21)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): View {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach {
            line.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(42)).apply {
                marginEnd = px(8)
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(line)
        }
    }

    private fun spaced(topDp: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(topDp) }

    companion object {
        private val ACCENT = Color.parseColor("#4E6BB5")
        private val SOFT = Color.parseColor("#B9BCC8")
        private val MUTED = Color.parseColor("#7C8193")
    }
}
