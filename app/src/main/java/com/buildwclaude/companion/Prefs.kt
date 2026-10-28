package com.buildwclaude.companion

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("companion", Context.MODE_PRIVATE)

    /** false = patrol the status bar, true = wander the whole screen. */
    var roam: Boolean
        get() = sp.getBoolean("roam", false)
        set(v) = sp.edit { putBoolean("roam", v) }

    /** Height of the sprite box in dp (the knight himself is a little shorter). */
    var sizeDp: Int
        get() = sp.getInt("size", 56)
        set(v) = sp.edit { putInt("size", v) }

    /** Walking speed in dp per second. */
    var speedDp: Int
        get() = sp.getInt("speed", 40)
        set(v) = sp.edit { putInt("speed", v) }

    /** Herald: announce new notifications with a speech bubble. Needs notification access. */
    var herald: Boolean
        get() = sp.getBoolean("herald", false)
        set(v) = sp.edit { putBoolean("herald", v) }

    /** Show the message text in the herald's scroll (off = just the app and sender). */
    var heraldPreview: Boolean
        get() = sp.getBoolean("herald_preview", true)
        set(v) = sp.edit { putBoolean("herald_preview", v) }

    /** Suggest a break after this long of continuous screen time. */
    var breaks: Boolean
        get() = sp.getBoolean("breaks", true)
        set(v) = sp.edit { putBoolean("breaks", v) }

    var breakMin: Int
        get() = sp.getInt("break_min", 30)
        set(v) = sp.edit { putInt("break_min", v) }

    companion object {
        const val BREAK_MIN = 10
        const val BREAK_MAX = 90
        const val SIZE_MIN = 28
        const val SIZE_MAX = 160
        const val SPEED_MIN = 12
        const val SPEED_MAX = 120
    }
}
