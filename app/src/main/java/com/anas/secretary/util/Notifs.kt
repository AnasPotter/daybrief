package com.anas.secretary.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import com.anas.secretary.R
import com.anas.secretary.data.Task
import com.anas.secretary.reminders.ReminderReceiver
import com.anas.secretary.reminders.Reminders
import com.anas.secretary.ui.MainActivity
import com.anas.secretary.ui.ScreenshotActivity

object Notifs {
    const val CH_REMIND = "reminders"
    const val CH_SCREEN = "screenshot_popup"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.deleteNotificationChannel("screenshots") // old silent channel
        // HIGH = pops up on screen. No sound or vibration, so it isn't annoying.
        nm.createNotificationChannel(
            NotificationChannel(CH_SCREEN, "Screenshot prompts", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    fun reminderId(taskId: Long) = 100_000 + (taskId % 100_000).toInt()

    fun showReminder(ctx: Context, task: Task) {
        val open = PendingIntent.getActivity(
            ctx, reminderId(task.id),
            Intent(ctx, MainActivity::class.java).putExtra("tab", 1).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(act: String, label: String): Notification.Action {
            val pi = PendingIntent.getBroadcast(
                ctx, reminderId(task.id),
                Intent(ctx, ReminderReceiver::class.java).setAction(act).putExtra("id", task.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notify), label, pi).build()
        }
        val n = Notification.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(task.title)
            .setContentText(task.notes.ifBlank { "Reminder from Daybrief" })
            .setStyle(Notification.BigTextStyle().bigText(task.notes.ifBlank { "Reminder from Daybrief" }))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(action(Reminders.ACTION_DONE, "Done"))
            .addAction(action(Reminders.ACTION_SNOOZE, "Snooze 15 min"))
            .build()
        post(ctx, reminderId(task.id), n)
    }

    fun screenshotId(uri: Uri) = 200_000 + (uri.hashCode() and 0xFFFF)

    fun showScreenshotPrompt(ctx: Context, uri: Uri) {
        val id = screenshotId(uri)
        fun open(mode: String) = PendingIntent.getActivity(
            ctx, id + mode.hashCode(),
            Intent(ctx, ScreenshotActivity::class.java).putExtra("uri", uri.toString()).putExtra("mode", mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(ctx, R.drawable.ic_notify)
        val b = Notification.Builder(ctx, CH_SCREEN)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Save this screenshot?")
            .setContentText("Add it to your tasks or keep it in Saved")
            .setContentIntent(open("task"))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setTimeoutAfter(5 * 60_000L)
            .addAction(Notification.Action.Builder(icon, "Add to tasks", open("task")).build())
            .addAction(Notification.Action.Builder(icon, "Keep in Saved", open("save")).build())
        Images.thumbnail(ctx, uri)?.let { bmp ->
            b.setLargeIcon(bmp)
            b.setStyle(Notification.BigPictureStyle().bigPicture(bmp))
        }
        post(ctx, id, b.build())
    }

    fun cancel(ctx: Context, id: Int) = ctx.getSystemService(NotificationManager::class.java).cancel(id)

    fun post(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
            // Notifications permission not granted yet
        }
    }
}
