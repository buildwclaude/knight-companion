package com.buildwclaude.companion

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Keeps the knight on screen: a tiny overlay window that is moved around every frame.
 * The window is only as big as the sprite, so touches anywhere else go to the app underneath.
 */
class CompanionService : Service(), Choreographer.FrameCallback {

    private enum class State { WALK, IDLE, CHEER, DRAG, HOP }

    private lateinit var prefs: Prefs
    private lateinit var windowContext: Context
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var view: KnightView? = null
    private var sprites: Sprites? = null

    // Screen geometry in px. The knight's position is where his feet touch the ground.
    private var screenW = 1
    private var screenH = 1
    private var barH = 0
    private var fx = 0f
    private var fy = 0f

    private var state = State.IDLE
    private var stateTime = 0f
    private var stateLen = 0f
    private var clock = 0f
    private var lastMeasure = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var walked = 0f
    private var facingLeft = false
    private var hopFrom = 0f
    private var lastFrameNanos = 0L
    private val rng = Random(System.nanoTime())

    private val density get() = resources.displayMetrics.density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (view != null) applySettings()
                return START_STICKY
            }
        }
        if (!startInForeground() || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) attach()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        sprites?.recycle()
        sprites = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (view != null) {
            measureScreen()
            settleInBounds()
        }
    }

    // ------------------------------------------------------------------ setup

    /** False when Android refuses (e.g. a sticky restart while the app is in the background). */
    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Knight on patrol", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps your knight walking around the screen"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CompanionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_knight)
            .setContentTitle("Your knight is on patrol")
            .setContentText("Tap him to cheer, drag him to move him")
            .setContentIntent(open)
            .addAction(0, "Send him home", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        return runCatching { ServiceCompat.startForeground(this, NOTE_ID, note, type) }.isSuccess
    }

    private fun attach() {
        // Android 11+ wants overlay windows added through a window context, not a bare service.
        windowContext = if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(OVERLAY_TYPE, null)
        } else {
            this
        }
        wm = windowContext.getSystemService(WindowManager::class.java)

        lp = WindowManager.LayoutParams(
            1, 1, OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Knight"
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val v = KnightView(windowContext)
        v.setOnTouchListener(TouchHandler())
        view = v
        measureScreen()
        loadSprites()
        fx = screenW * 0.3f
        fy = groundY()
        wm.addView(v, lp)
        enter(State.IDLE, 1.2f)
        render()

        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun loadSprites() {
        val px = (prefs.sizeDp * density).roundToInt()
        val old = sprites
        sprites = Sprites.load(this, px).also {
            lp.width = it.width
            lp.height = it.height
        }
        old?.recycle()
    }

    /** Called when size / speed / mode change in the app. */
    private fun applySettings() {
        loadSprites()
        settleInBounds()
        if (!prefs.roam) {
            fy = groundY()
            if (state == State.WALK) targetY = fy
        }
        render(forceLayout = true)
    }

    private fun measureScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            screenW = m.bounds.width()
            screenH = m.bounds.height()
            barH = m.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        } else {
            val dm = resources.displayMetrics
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
        if (barH <= 0) barH = statusBarFromResources()
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarFromResources(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * density).roundToInt()
    }

    // ------------------------------------------------------------------ geometry

    /** Status bar mode: feet on the bottom edge of the bar, unless he's too tall to fit. */
    private fun groundY(): Float {
        val s = sprites ?: return barH.toFloat()
        return max(barH.toFloat(), s.footY * s.height)
    }

    private fun minX(): Float = (sprites?.width ?: 0) * 0.25f
    private fun maxX(): Float = screenW - minX()
    private fun minY(): Float = groundY()
    private fun maxY(): Float {
        val s = sprites ?: return screenH.toFloat()
        return screenH - (1f - s.footY) * s.height - 48 * density
    }

    private fun settleInBounds() {
        fx = fx.coerceIn(minX(), max(minX(), maxX()))
        fy = if (prefs.roam) fy.coerceIn(minY(), max(minY(), maxY())) else groundY()
        targetX = targetX.coerceIn(minX(), max(minX(), maxX()))
    }

    // ------------------------------------------------------------------ behaviour

    private fun enter(next: State, seconds: Float = 0f) {
        state = next
        stateTime = 0f
        stateLen = seconds
    }

    private fun walkSomewhere() {
        val span = maxX() - minX()
        var x: Float
        do {
            x = minX() + rng.nextFloat() * span
        } while (abs(x - fx) < span * 0.15f && span > 0f)
        targetX = x
        targetY = if (prefs.roam) minY() + rng.nextFloat() * max(0f, maxY() - minY()) else groundY()
        enter(State.WALK)
    }

    private fun cheer() {
        val s = sprites ?: return
        enter(State.CHEER, s.durationSec(Anim.CHEER))
    }

    private fun arrived() {
        val r = rng.nextFloat()
        when {
            r < 0.55f -> enter(State.IDLE, 2f + rng.nextFloat() * 4f)
            r < 0.8f -> cheer()
            else -> walkSomewhere()
        }
    }

    private fun step(dt: Float) {
        stateTime += dt
        clock += dt
        when (state) {
            State.WALK -> {
                val dx = targetX - fx
                val dy = targetY - fy
                val dist = hypot(dx, dy)
                val stride = prefs.speedDp * density * dt
                if (dist <= stride) {
                    fx = targetX
                    fy = targetY
                    arrived()
                } else {
                    fx += dx / dist * stride
                    fy += dy / dist * stride
                    walked += stride
                    if (abs(dx) > 0.5f) facingLeft = dx < 0
                }
            }
            State.IDLE -> if (stateTime >= stateLen) walkSomewhere()
            State.CHEER -> if (stateTime >= stateLen) enter(State.IDLE, 1.5f + rng.nextFloat() * 2f)
            State.HOP -> {
                // spring back up onto the status bar after being dropped
                val t = min(stateTime / HOP_SEC, 1f)
                val c = 1.7f
                val ease = 1f + (c + 1f) * (t - 1f).let { it * it * it } + c * (t - 1f).let { it * it }
                fy = hopFrom + (groundY() - hopFrom) * ease
                if (t >= 1f) {
                    fy = groundY()
                    enter(State.IDLE, 1f)
                }
            }
            State.DRAG -> Unit
        }
    }

    private fun render(forceLayout: Boolean = false) {
        val s = sprites ?: return
        val v = view ?: return
        val frame = when (state) {
            State.WALK -> {
                // advance the walk cycle by distance so his feet don't slide
                val list = s.frames(Anim.WALK)
                val cycleLen = s.height * 0.42f
                list[((walked / cycleLen) * list.size).toInt().mod(list.size)]
            }
            State.CHEER -> s.frames(Anim.CHEER).let { it[min((stateTime * s.fps(Anim.CHEER)).toInt(), it.size - 1)] }
            else -> s.frames(Anim.IDLE).let { it[(clock * s.fps(Anim.IDLE)).toInt().mod(it.size)] }
        }
        v.show(frame, facingLeft)

        val x = (fx - s.width / 2f).roundToInt()
        val y = (fy - s.footY * s.height).roundToInt()
        if (forceLayout || x != lp.x || y != lp.y) {
            lp.x = x
            lp.y = y
            runCatching { wm.updateViewLayout(v, lp) }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = frameTimeNanos
        if (clock - lastMeasure > 1f) {
            // cheap way to notice rotation / display size changes
            lastMeasure = clock
            val w = screenW
            val h = screenH
            measureScreen()
            if (w != screenW || h != screenH) settleInBounds()
        }
        step(dt)
        render()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ------------------------------------------------------------------ touch

    private inner class TouchHandler : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startFx = 0f
        private var startFy = 0f
        private var dragging = false
        private val slop = ViewConfiguration.get(this@CompanionService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startFx = fx
                    startFy = fy
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        enter(State.DRAG)
                    }
                    if (dragging) {
                        fx = (startFx + dx).coerceIn(0f, screenW.toFloat())
                        fy = (startFy + dy).coerceIn(minY(), screenH.toFloat())
                        if (abs(dx) > slop) facingLeft = dx < 0
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) drop() else {
                    v.performClick()
                    cheer()
                }
                MotionEvent.ACTION_CANCEL -> if (dragging) drop()
            }
            return true
        }

        private fun drop() {
            dragging = false
            if (!prefs.roam && abs(fy - groundY()) > 1f) {
                hopFrom = fy
                enter(State.HOP)
            } else {
                settleInBounds()
                enter(State.IDLE, 1.5f)
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.buildwclaude.companion.STOP"
        const val ACTION_RELOAD = "com.buildwclaude.companion.RELOAD"
        private const val CHANNEL = "patrol"
        private const val NOTE_ID = 1
        private const val HOP_SEC = 0.45f
        private const val OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        /** Read by the app screen to label the start/stop button. */
        @Volatile
        var running = false
            private set
    }
}
