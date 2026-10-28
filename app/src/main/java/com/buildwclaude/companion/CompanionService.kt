package com.buildwclaude.companion

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Keeps the knight on screen: a tiny overlay window that is moved around every frame, plus a
 * second small window for his speech bubble. Both are only as big as their content, so touches
 * anywhere else go to the app underneath.
 */
class CompanionService : Service(), Choreographer.FrameCallback {

    private enum class State { WALK, IDLE, CHEER, PRESENT, DRAG, HOP }

    private lateinit var prefs: Prefs
    private lateinit var windowContext: Context
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var view: KnightView? = null
    private var sprites: Sprites? = null

    // speech bubble window (added only while something is shown)
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var bubble: BubbleView? = null
    private var bubbleText = ""

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
    private var lastTick = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var rushing = false
    private var walked = 0f
    private var facingLeft = false
    private var hopFrom = 0f
    private var lastFrameNanos = 0L
    private val rng = Random(System.nanoTime())

    // announcements
    private val queue = ArrayDeque<Announcement>()
    private var current: Announcement? = null
    private var currentUntil = 0f
    private var focusEnd = 0L

    // break nudges: continuous screen-on time
    private var screenOnSince = SystemClock.elapsedRealtime()
    private var screenOffAt = 0L
    private var lastNudge = 0L
    private var nudgeIndex = 0
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAt = now
                Intent.ACTION_SCREEN_ON -> if (screenOffAt > 0 && now - screenOffAt >= BREAK_RESET_MS) {
                    // a minute away from the screen counts as a break
                    screenOnSince = now
                    lastNudge = 0
                }
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
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
        KnightBus.sink = null
        main.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(this)
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        hideBubble()
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
    // The main manifest declares foregroundServiceType="specialUse"; lint misreads the herald
    // flavor's extra manifest (which only adds the listener service) and flags this call.
    @SuppressLint("ForegroundServiceType")
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
            .setContentText("Tap: cheer · hold: timers & reminders · drag: move")
            .setContentIntent(open)
            .addAction(0, "Send him home", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        return runCatching { ServiceCompat.startForeground(this, NOTE_ID, note, type) }.isSuccess
    }

    private fun overlayParams(touchable: Boolean = true) = WindowManager.LayoutParams(
        1, 1, OVERLAY_TYPE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
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
        lp = overlayParams().apply { title = "Knight" }
        bubbleLp = overlayParams().apply { title = "Knight bubble" }

        val v = KnightView(windowContext)
        v.setOnTouchListener(KnightTouch())
        view = v
        measureScreen()
        loadSprites()
        fx = screenW * 0.3f
        fy = groundY()
        wm.addView(v, lp)
        enter(State.IDLE, 1.2f)
        render()

        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true
        KnightBus.sink = ::announce

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

    /** Called when settings change in the app. */
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

    private fun walkTo(x: Float, y: Float, rush: Boolean = false) {
        targetX = x.coerceIn(minX(), max(minX(), maxX()))
        targetY = if (prefs.roam) y.coerceIn(minY(), max(minY(), maxY())) else groundY()
        rushing = rush
        enter(State.WALK)
    }

    private fun walkSomewhere() {
        val span = maxX() - minX()
        var x: Float
        do {
            x = minX() + rng.nextFloat() * span
        } while (abs(x - fx) < span * 0.15f && span > 0f)
        walkTo(x, minY() + rng.nextFloat() * max(0f, maxY() - minY()))
    }

    private fun cheer() {
        val s = sprites ?: return
        enter(State.CHEER, s.durationSec(Anim.CHEER))
    }

    private fun arrived() {
        if (rushing) {
            // delivered: raise the sword, then stand there while the bubble is up
            rushing = false
            cheer()
            return
        }
        val r = rng.nextFloat()
        when {
            r < 0.55f -> enter(State.IDLE, 2f + rng.nextFloat() * 4f)
            r < 0.8f -> cheer()
            else -> walkSomewhere()
        }
    }

    private fun afterCheer() {
        if (current != null) enter(State.PRESENT) else enter(State.IDLE, 1.5f + rng.nextFloat() * 2f)
    }

    private fun step(dt: Float) {
        stateTime += dt
        clock += dt
        when (state) {
            State.WALK -> {
                val dx = targetX - fx
                val dy = targetY - fy
                val dist = hypot(dx, dy)
                val speed = prefs.speedDp * density * (if (rushing) RUSH_SPEED else 1f)
                val stride = speed * dt
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
            State.CHEER -> if (stateTime >= stateLen) afterCheer()
            State.PRESENT -> if (current == null) enter(State.IDLE, 1f)
            State.HOP -> {
                // spring back up onto the status bar after being dropped
                val t = min(stateTime / HOP_SEC, 1f)
                val c = 1.7f
                val ease = 1f + (c + 1f) * (t - 1f).let { it * it * it } + c * (t - 1f).let { it * it }
                fy = hopFrom + (groundY() - hopFrom) * ease
                if (t >= 1f) {
                    fy = groundY()
                    afterCheer()
                }
            }
            State.DRAG -> Unit
        }
        if (current != null && clock >= currentUntil) nextAnnouncement()
    }

    /** Once a second: focus countdown and break nudges. */
    private fun tick() {
        val w = screenW
        val h = screenH
        measureScreen() // cheap way to notice rotation / display size changes
        if (w != screenW || h != screenH) settleInBounds()

        focusEnd = Reminders.focusEnd(this)
        if (current == null) refreshPill()

        if (prefs.breaks) {
            val now = SystemClock.elapsedRealtime()
            val every = prefs.breakMin * 60_000L
            val since = if (lastNudge > screenOnSince) lastNudge else screenOnSince
            if (now - since >= every) {
                lastNudge = now
                nudge(((now - screenOnSince) / 60_000L).toInt())
            }
        }
    }

    private fun nudge(minutes: Int) {
        val (title, body) = NUDGES[nudgeIndex++ % NUDGES.size]
        announce(Announcement(MsgKind.BREAK, title, body.replace("%d", minutes.toString()), seconds = 12f))
    }

    // ------------------------------------------------------------------ announcements

    private fun announce(a: Announcement) {
        if (view == null) return
        queue.addLast(a)
        if (queue.size > 5) queue.removeFirst()
        if (current == null) nextAnnouncement()
    }

    private fun nextAnnouncement() {
        val a = queue.removeFirstOrNull()
        current = a
        if (a == null) {
            refreshPill()
            return
        }
        currentUntil = clock + a.seconds
        showBubble(a.title, a.body)
        if (state == State.DRAG || state == State.HOP) return
        if (a.kind == MsgKind.HERALD) {
            // run to the middle of the screen with the scroll
            val x = screenW / 2f + (rng.nextFloat() - 0.5f) * screenW * 0.3f
            if (abs(x - fx) > screenW * 0.08f) walkTo(x, fy, rush = true) else cheer()
        } else {
            cheer()
        }
    }

    private fun bubbleTapped() {
        val a = current
        if (a == null) {
            if (focusEnd > System.currentTimeMillis()) openQuickMenu()
            return
        }
        a.action?.let(::launch)
        currentUntil = clock // dismiss now
    }

    private fun launch(pi: PendingIntent) {
        // Android 14+ only lets a pending intent open an activity from the background if we say so
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
        runCatching { pi.send(this, 0, null, null, null, null, options) }
    }

    private fun openQuickMenu() {
        runCatching {
            startActivity(
                Intent(this, QuickActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    /** With no message showing, a running focus session shows as a small countdown pill. */
    private fun refreshPill() {
        val left = focusEnd - System.currentTimeMillis()
        if (left <= 0) {
            hideBubble()
            return
        }
        val secs = (left / 1000).toInt()
        showPill("⚔ focus %d:%02d".format(java.util.Locale.US, secs / 60, secs % 60))
    }

    private fun showBubble(title: String, body: String) {
        val b = ensureBubble()
        val maxW = min(screenW * 0.72f, 280 * density).toInt()
        val (w, h) = b.setMessage(title, body, maxW)
        resizeBubble(w, h, "$title|$body")
    }

    private fun showPill(text: String) {
        if (bubbleText == text) return
        val (w, h) = ensureBubble().setPill(text)
        resizeBubble(w, h, text)
    }

    private fun ensureBubble(): BubbleView {
        bubble?.let { return it }
        val b = BubbleView(windowContext)
        b.setOnClickListener { bubbleTapped() }
        bubble = b
        bubbleLp.x = -10_000 // placed properly on the next frame
        runCatching { wm.addView(b, bubbleLp) }
        return b
    }

    private fun resizeBubble(w: Int, h: Int, text: String) {
        bubbleText = text
        bubbleLp.width = w
        bubbleLp.height = h
        placeBubble(force = true)
    }

    private fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleText = ""
    }

    private fun placeBubble(force: Boolean = false) {
        val b = bubble ?: return
        val s = sprites ?: return
        val gap = 4 * density
        val headTop = fy - s.footY * s.height + s.height * 0.04f
        val above = headTop - bubbleLp.height - gap
        // on the status bar there's no room above him, so the bubble hangs just below his feet
        val y = if (!prefs.roam || above < barH) fy + gap else above
        val margin = 8 * density
        val x = (fx - bubbleLp.width / 2f).coerceIn(margin, max(margin, screenW - bubbleLp.width - margin))
        val nx = x.roundToInt()
        val ny = y.roundToInt()
        if (force || nx != bubbleLp.x || ny != bubbleLp.y) {
            bubbleLp.x = nx
            bubbleLp.y = ny
            runCatching { wm.updateViewLayout(b, bubbleLp) }
        }
    }

    // ------------------------------------------------------------------ drawing

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
        placeBubble()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = frameTimeNanos
        if (clock - lastTick >= 1f) {
            lastTick = clock
            tick()
        }
        step(dt)
        render()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ------------------------------------------------------------------ touch

    /** Tap: cheer (or open what the bubble announces). Hold: quick menu. Drag: move him. */
    private inner class KnightTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startFx = 0f
        private var startFy = 0f
        private var dragging = false
        private var longPressed = false
        private val slop = ViewConfiguration.get(this@CompanionService).scaledTouchSlop
        private val longPress = Runnable {
            longPressed = true
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            openQuickMenu()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startFx = fx
                    startFy = fy
                    dragging = false
                    longPressed = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !longPressed && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        enter(State.DRAG)
                    }
                    if (dragging) {
                        fx = (startFx + dx).coerceIn(0f, screenW.toFloat())
                        fy = (startFy + dy).coerceIn(minY(), screenH.toFloat())
                        if (abs(dx) > slop) facingLeft = dx < 0
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    when {
                        dragging -> drop()
                        longPressed -> Unit
                        else -> {
                            v.performClick()
                            if (current != null) bubbleTapped() else cheer()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (dragging) drop()
                }
            }
            return true
        }

        private fun drop() {
            dragging = false
            rushing = false
            if (!prefs.roam && abs(fy - groundY()) > 1f) {
                hopFrom = fy
                enter(State.HOP)
            } else {
                settleInBounds()
                afterCheer()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.buildwclaude.companion.STOP"
        const val ACTION_RELOAD = "com.buildwclaude.companion.RELOAD"
        private const val CHANNEL = "patrol"
        private const val NOTE_ID = 1
        private const val HOP_SEC = 0.45f
        private const val RUSH_SPEED = 2.6f
        private const val BREAK_RESET_MS = 60_000L
        private const val OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        private val NUDGES = listOf(
            "💧 Water break?" to "%d minutes on screen. Go grab a glass of water.",
            "🧘 Stretch time" to "Roll your shoulders back and sit up tall. The knight insists.",
            "👀 Rest your eyes" to "Look at something far away for 20 seconds.",
            "🚶 Quick walk?" to "%d minutes straight. Stand up and move for a minute.",
        )

        /** Read by the app screen to label the start/stop button. */
        @Volatile
        var running = false
            private set
    }
}
