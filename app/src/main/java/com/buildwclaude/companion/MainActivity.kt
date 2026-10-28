package com.buildwclaude.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/** Settings screen: permission, where he walks, size, speed, start/stop — plus a live preview. */
class MainActivity : Activity(), Choreographer.FrameCallback {

    private lateinit var prefs: Prefs
    private lateinit var preview: KnightView
    private lateinit var previewSprites: Sprites
    private lateinit var permissionCard: View
    private lateinit var startButton: Button
    private var previewClock = 0f
    private var cheerUntil = 0f
    private var lastNanos = 0L

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onPause()
    }

    override fun onDestroy() {
        previewSprites.recycle()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ preview animation

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastNanos == 0L) 0f else (frameTimeNanos - lastNanos) / 1e9f
        lastNanos = frameTimeNanos
        previewClock += dt
        val s = previewSprites
        val frame = if (previewClock < cheerUntil) {
            val t = previewClock - (cheerUntil - s.durationSec(Anim.CHEER))
            s.frames(Anim.CHEER).let { it[min((t * s.fps(Anim.CHEER)).toInt(), it.size - 1)] }
        } else {
            s.frames(Anim.IDLE).let { it[(previewClock * s.fps(Anim.IDLE)).toInt().mod(it.size)] }
        }
        preview.show(frame, false)
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ------------------------------------------------------------------ actions

    private fun refresh() {
        val allowed = Settings.canDrawOverlays(this)
        permissionCard.visibility = if (allowed) View.GONE else View.VISIBLE
        startButton.text = if (CompanionService.running) "Send him home" else "Start patrol"
        startButton.alpha = if (allowed) 1f else 0.4f
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun toggleCompanion() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        if (CompanionService.running) {
            stopService(Intent(this, CompanionService::class.java))
            startButton.postDelayed({ refresh() }, 150)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The patrol still runs if this is refused; the notification just stays hidden.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        launchService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) launchService()
    }

    private fun launchService() {
        startForegroundService(Intent(this, CompanionService::class.java))
        startButton.postDelayed({ refresh() }, 300)
    }

    private fun pushSettings() {
        if (CompanionService.running) {
            startService(Intent(this, CompanionService::class.java).setAction(CompanionService.ACTION_RELOAD))
        }
    }

    // ------------------------------------------------------------------ layout (built in code)

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(12), px(22), px(32))
        }

        // live preview — tap him to cheer
        previewSprites = Sprites.load(this, px(220))
        preview = KnightView(this).apply {
            contentDescription = "Your knight. Tap to make him cheer."
            setOnClickListener { cheerUntil = previewClock + previewSprites.durationSec(Anim.CHEER) }
            background = rounded(Color.parseColor("#1A1C26"), 28)
        }
        column.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(250)))

        column.addView(text("KNIGHT COMPANION", 12f, MUTED, mono = true).also { it.setPadding(0, px(22), 0, 0) })
        column.addView(text("A tiny knight for your screen", 26f, Color.WHITE, bold = true))
        column.addView(
            text(
                "He patrols your status bar past the clock and battery, or wanders the whole screen. " +
                    "Tap him to cheer. Drag him anywhere.",
                15f, SOFT
            ).also { it.setPadding(0, px(6), 0, 0) }
        )

        // permission card (hidden once granted)
        permissionCard = card().apply {
            addView(text("ONE-TIME SETUP", 11f, ACCENT, mono = true))
            addView(
                text(
                    "Allow “Display over other apps” so the knight can walk on top of everything.",
                    15f, Color.WHITE
                ).also { it.setPadding(0, px(6), 0, px(12)) }
            )
            addView(button("Allow permission", filled = false) { openOverlaySettings() })
        }
        column.addView(permissionCard, spaced())

        // where he walks
        column.addView(section("WHERE HE WALKS"))
        val group = RadioGroup(this)
        val bar = radio("Patrol the status bar", 1)
        val roam = radio("Roam the whole screen", 2)
        group.addView(bar)
        group.addView(roam)
        group.check(if (prefs.roam) 2 else 1)
        group.setOnCheckedChangeListener { _, id ->
            prefs.roam = id == 2
            pushSettings()
        }
        column.addView(group)

        column.addView(section("SIZE"))
        column.addView(slider(Prefs.SIZE_MIN, Prefs.SIZE_MAX, prefs.sizeDp, "dp") {
            prefs.sizeDp = it
            pushSettings()
        })
        column.addView(section("WALKING SPEED"))
        column.addView(slider(Prefs.SPEED_MIN, Prefs.SPEED_MAX, prefs.speedDp, "dp/s") {
            prefs.speedDp = it
            pushSettings()
        })

        startButton = button("Start patrol", filled = true) { toggleCompanion() }
        column.addView(startButton, spaced(28))

        column.addView(
            text(
                "He walks behind the status bar icons, so the clock and battery stay readable. " +
                    "To stop him, use the notification or come back here.",
                13f, MUTED
            ).also { it.setPadding(0, px(16), 0, 0) }
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        // targetSdk 35 is edge-to-edge: keep content clear of the status and navigation bars
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        return scroll
    }

    private fun px(v: Int) = (v * dp).roundToInt()

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * dp
        if (stroke != null) setStroke(px(1), stroke)
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (mono) {
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.18f
            } else if (bold) {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            setLineSpacing(0f, 1.15f)
        }

    private fun section(label: String) = text(label, 11f, MUTED, mono = true).apply {
        setPadding(0, px(24), 0, px(6))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18), px(16), px(18), px(18))
        background = rounded(Color.parseColor("#161925"), 20, Color.parseColor("#2C3350"))
    }

    private fun spaced(topDp: Int = 20) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(topDp) }

    private fun radio(label: String, viewId: Int) = RadioButton(this).apply {
        id = viewId
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(px(8), px(10), 0, px(10))
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        minHeight = px(54)
        if (filled) {
            setTextColor(Color.parseColor("#0E0F14"))
            background = rounded(Color.WHITE, 30)
        } else {
            setTextColor(Color.WHITE)
            background = rounded(ACCENT, 30)
        }
        setOnClickListener { onClick() }
    }

    private fun slider(min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = text("$value $unit", 14f, Color.WHITE, mono = true).apply {
            minWidth = px(84)
            gravity = Gravity.END
        }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    label.text = "${p + min} $unit"
                }

                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = onChange(sb.progress + min)
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label)
        return row
    }

    companion object {
        private const val REQ_NOTIFY = 7
        private val ACCENT = Color.parseColor("#4E6BB5")
        private val SOFT = Color.parseColor("#B9BCC8")
        private val MUTED = Color.parseColor("#7C8193")
    }
}
