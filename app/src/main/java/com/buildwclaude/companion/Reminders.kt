package com.buildwclaude.companion

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(val id: Int, val text: String, val at: Long, val focus: Boolean)

/** Reminders and focus sessions, kept in prefs and fired by AlarmManager so they survive the screen being off. */
object Reminders {
    private const val PREFS = "reminders"
    private const val KEY = "list"
    private const val NEXT_ID = "next_id"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Reminder> {
        val arr = JSONArray(sp(ctx).getString(KEY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Reminder(o.getInt("id"), o.getString("text"), o.getLong("at"), o.optBoolean("focus"))
        }.sortedBy { it.at }
    }

    private fun save(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("focus", it.focus))
        }
        sp(ctx).edit { putString(KEY, arr.toString()) }
    }

    fun add(ctx: Context, text: String, at: Long, focus: Boolean = false): Reminder {
        val id = sp(ctx).getInt(NEXT_ID, 100)
        sp(ctx).edit { putInt(NEXT_ID, id + 1) }
        val r = Reminder(id, text, at, focus)
        save(ctx, all(ctx) + r)
        schedule(ctx, r)
        return r
    }

    fun cancel(ctx: Context, id: Int) {
        save(ctx, all(ctx).filterNot { it.id == id })
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx, id))
    }

    /** Removes and returns the reminder (called when its alarm fires). */
    fun take(ctx: Context, id: Int): Reminder? {
        val list = all(ctx)
        val r = list.firstOrNull { it.id == id } ?: return null
        save(ctx, list - r)
        return r
    }

    fun focusEnd(ctx: Context): Long = all(ctx).firstOrNull { it.focus }?.at ?: 0L

    fun startFocus(ctx: Context, minutes: Int) {
        stopFocus(ctx)
        add(ctx, "Focus session", System.currentTimeMillis() + minutes * 60_000L, focus = true)
    }

    fun stopFocus(ctx: Context) = all(ctx).filter { it.focus }.forEach { cancel(ctx, it.id) }

    /** After a reboot or app update the alarms are gone: set them again, firing any that were missed. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        all(ctx).forEach { r ->
            if (r.at <= now) {
                take(ctx, r.id)
                Alerts.fire(ctx, r)
            } else {
                schedule(ctx, r)
            }
        }
    }

    private fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx, r.id)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        }
    }

    private fun alarmIntent(ctx: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, id,
        Intent(ctx, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/** Turns a due reminder into a ringing notification plus (if he's on screen) a speech bubble. */
object Alerts {
    private const val CHANNEL = "reminders"

    fun fire(ctx: Context, r: Reminder) {
        val title = if (r.focus) "Focus session done" else "Reminder"
        val body = if (r.focus) "Nice work. Stand up and take five." else r.text
        notify(ctx, r.id, title, body)
        KnightBus.post(
            Announcement(
                kind = if (r.focus) MsgKind.FOCUS_DONE else MsgKind.REMINDER,
                title = "${if (r.focus) MsgKind.FOCUS_DONE.icon else MsgKind.REMINDER.icon} $title",
                body = body,
                seconds = 25f,
            )
        )
    }

    private fun notify(ctx: Context, id: Int, title: String, body: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders and focus timer", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_knight)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val r = Reminders.take(context, intent.getIntExtra("id", -1)) ?: return
        Alerts.fire(context, r)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.rescheduleAll(context)
        }
    }
}
