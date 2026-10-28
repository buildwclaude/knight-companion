package com.buildwclaude.companion

import android.app.PendingIntent
import android.os.Handler
import android.os.Looper

enum class MsgKind(val icon: String) {
    HERALD("📜"),      // scroll
    REMINDER("⏰"),          // alarm clock
    FOCUS_DONE("⚔️"),  // crossed swords
    BREAK("💧"),       // droplet (break nudges pass their own icon in the title)
}

/** Something the knight should say in his speech bubble. */
data class Announcement(
    val kind: MsgKind,
    val title: String,
    val body: String = "",
    /** Run when the bubble is tapped (e.g. open the message the herald brought). */
    val action: PendingIntent? = null,
    val seconds: Float = 8f,
)

/** In-process line from the notification listener and alarm receiver to the knight on screen. */
object KnightBus {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var sink: ((Announcement) -> Unit)? = null

    /** Returns false when no knight is on screen to deliver it. */
    fun post(a: Announcement): Boolean {
        val s = sink ?: return false
        main.post { s(a) }
        return true
    }
}
