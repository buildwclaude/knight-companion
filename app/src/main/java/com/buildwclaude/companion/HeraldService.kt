package com.buildwclaude.companion

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/** Watches incoming notifications and hands the interesting ones to the knight to announce. */
class HeraldService : NotificationListenerService() {
    private val lastSeen = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!prefs.herald || sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category in SKIP_CATEGORIES) return

        // skip silent / low-importance channels (downloads, media, weather widgets…)
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true &&
            ranking.importance < NotificationManager.IMPORTANCE_DEFAULT
        ) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        // apps re-post the same notification when anything changes; only announce new content
        val signature = "$title\u0000$text"
        if (lastSeen[sbn.key] == signature) return
        if (lastSeen.size > 64) lastSeen.clear()
        lastSeen[sbn.key] = signature

        val app = appName(sbn.packageName)
        val heading = listOf(app, title).filter { it.isNotEmpty() }.distinct().joinToString(" · ")
        val body = if (prefs.heraldPreview) text.replace('\n', ' ').take(120) else "New message"
        KnightBus.post(Announcement(MsgKind.HERALD, "${MsgKind.HERALD.icon} $heading", body, n.contentIntent, 9f))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSeen.remove(sbn.key)
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        ""
    }

    companion object {
        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_ALARM,
        )

        fun hasAccess(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
}
