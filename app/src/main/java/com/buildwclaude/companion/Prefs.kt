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

    companion object {
        const val SIZE_MIN = 28
        const val SIZE_MAX = 160
        const val SPEED_MIN = 12
        const val SPEED_MAX = 120
    }
}
