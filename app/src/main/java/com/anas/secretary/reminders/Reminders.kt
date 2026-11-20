package com.anas.secretary.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.anas.secretary.data.Repo
import com.anas.secretary.util.Notifs

object Reminders {
    const val ACTION_FIRE = "com.anas.secretary.FIRE"
    const val ACTION_DONE = "com.anas.secretary.DONE"
    const val ACTION_SNOOZE = "com.anas.secretary.SNOOZE"

    private fun pending(ctx: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.toInt(),
            Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(ctx: Context, id: Long, title: String, at: Long) {
        if (at <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, id))
            else
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, id))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, id))
        }
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        when (intent.action) {
            Reminders.ACTION_FIRE -> {
                val task = Repo.task(id) ?: return
                if (!task.done) Notifs.showReminder(ctx, task)
            }
            Reminders.ACTION_DONE -> {
                Repo.setDone(id, true)
                Notifs.cancel(ctx, Notifs.reminderId(id))
            }
            Reminders.ACTION_SNOOZE -> {
                val t = Repo.task(id) ?: return
                Repo.updateTask(id, t.title, t.notes, System.currentTimeMillis() + 15 * 60_000L)
                Notifs.cancel(ctx, Notifs.reminderId(id))
            }
        }
    }
}

/** Alarms are wiped on reboot, so re-schedule them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Repo.upcomingReminders().forEach { t -> t.due?.let { Reminders.schedule(ctx, t.id, t.title, it) } }
    }
}
