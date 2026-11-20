package com.anas.secretary.util

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.anas.secretary.R
import com.anas.secretary.data.Repo
import com.anas.secretary.logic.DayPlan
import com.anas.secretary.logic.HealthSummary
import com.anas.secretary.ui.MainActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The morning summary (first unlock after you wake) and the bedtime reminder.
 * Everything comes from what's already on the phone: the saved health numbers, tasks, calendar and waiting questions.
 */
object Daily {
    private const val CH = "day"
    private const val ID_MORNING = 7101
    private const val ID_BED = 7102
    const val ACT_CHECK = "com.anas.secretary.BED_CHECK"
    const val ACT_BED = "com.anas.secretary.BED_FIRE"
    const val ACT_BED_LATER = "com.anas.secretary.BED_LATER"
    const val ACT_BED_SKIP = "com.anas.secretary.BED_SKIP"

    private fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Morning summary and bedtime", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false)
            }
        )
    }

    private fun openApp(ctx: Context, req: Int, tab: Int = 0) = PendingIntent.getActivity(
        ctx, req, Intent(ctx, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private val hm = DateTimeFormatter.ofPattern("h:mm", Locale.US)
    private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(hm)

    // ---------- Morning summary ----------

    /** Called on every unlock. Shows the summary once a day, on the first unlock between 4 AM and noon. */
    fun onUnlock(ctx: Context) {
        if (!Prefs.morningOn || !Features.day) return
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate().toString()
        if (Prefs.morningDay == today || now.hour !in 4..11) return
        // Before 7, only when the watch says you've woken up (a 4 AM glance at the phone shouldn't use up the summary)
        val woke = Health.cached(ctx)?.lastNight?.wakeDate(zone) == now.toLocalDate()
        if (now.hour < 7 && !woke) return
        Prefs.morningDay = today
        Bg.scope.launch {
            // Try for fresh numbers (works when Samsung Health answers in the background), else use the saved ones
            if (Features.health) withTimeoutOrNull(6_000) { try { Health.refresh(ctx) } catch (_: Exception) { null } }
            try { postMorning(ctx) } catch (e: Exception) { android.util.Log.w("Secretary", "Morning summary failed", e) }
        }
    }

    fun morningLines(ctx: Context): List<String> {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val lines = ArrayList<String>()
        Health.cached(ctx)?.takeIf { Features.health }?.lastNight?.takeIf { it.wakeDate(zone) == day }?.let { n ->
            lines += "🌙 Slept ${HealthSummary.dur(n.asleep)}" + (n.score?.let { " · score $it" } ?: "")
        }
        val tasks = Repo.tasks().filter { !it.done && it.due != null }
        val first = buildList {
            if (Features.calendar && Cal.canRead(ctx)) Cal.events(ctx, now, end).filter { !it.allDay }.forEach { add(it.begin to it.title) }
            tasks.filter { it.due!! in now until end }.forEach { add(it.due!! to it.title) }
        }.minByOrNull { it.first }
        first?.let { lines += "📅 First up: ${it.second} at ${clock(it.first)}" }
        val today = tasks.count { it.due!! in start until end }
        val overdue = tasks.count { it.due!! < start }
        if (today > 0 || overdue > 0) lines += "✓ " + listOfNotNull(
            if (today > 0) "$today task${if (today == 1) "" else "s"} today" else null,
            if (overdue > 0) "$overdue overdue" else null
        ).joinToString(", ")
        if (Features.day && Prefs.weekOn && day.dayOfWeek.value == Prefs.weekDay) lines += "📊 Your week is ready"
        val waiting = if (Features.messages) Repo.notifs(false).size else 0
        if (waiting > 0) lines += "💬 $waiting thing${if (waiting == 1) "" else "s"} waiting for you"
        return lines
    }

    fun postMorning(ctx: Context) {
        val lines = morningLines(ctx)
        if (lines.isEmpty()) return
        channel(ctx)
        val name = Prefs.name.trim().split(" ").firstOrNull().orEmpty()
        val title = if (name.isNotEmpty()) "Good morning, $name" else "Good morning"
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openApp(ctx, ID_MORNING))
            .setAutoCancel(true)
            .build()
        Notifs.post(ctx, ID_MORNING, n)
    }

    // ---------- Bedtime ----------

    /** A daily check at 7 PM works out tonight's reminder time. Also run when the app opens. */
    fun scheduleCheck(ctx: Context) {
        val zone = ZoneId.systemDefault()
        var first = ZonedDateTime.of(LocalDate.now(zone), LocalTime.of(19, 0), zone)
        if (first.toInstant().toEpochMilli() <= System.currentTimeMillis()) first = first.plusDays(1)
        // One-shot and re-armed each evening: a repeating inexact alarm can arrive hours late
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pi(ctx, ACT_CHECK, 7201))
        am.setWindow(AlarmManager.RTC_WAKEUP, first.toInstant().toEpochMilli(), 15 * 60_000L, pi(ctx, ACT_CHECK, 7201))
    }

    private fun pi(ctx: Context, action: String, req: Int) = PendingIntent.getBroadcast(
        ctx, req, Intent(ctx, DailyReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Tonight's bedtime (epoch ms) and the first event tomorrow it was worked out for, or null. */
    fun tonight(ctx: Context): Pair<Long, Pair<Long, String>?>? {
        val zone = ZoneId.systemDefault()
        val nights = Health.cached(ctx)?.nights ?: return null
        val now = ZonedDateTime.now(zone)
        // After midnight, "tonight" still means the evening that started yesterday
        val evening = if (now.hour < 5) now.toLocalDate().minusDays(1) else now.toLocalDate()
        val tStart = evening.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val first = buildList {
            if (Cal.canRead(ctx)) Cal.events(ctx, tStart, tStart + 14 * 3_600_000L).filter { !it.allDay }.forEach { add(it.begin to it.title) }
            Repo.tasks().filter { !it.done && it.due != null && it.due in tStart until tStart + 14 * 3_600_000L }.forEach { add(it.due!! to it.title) }
        }.minByOrNull { it.first }
        val bed = DayPlan.bedtime(nights, first?.first, evening, zone = zone) ?: return null
        val usual = DayPlan.bedtime(nights, null, evening, zone = zone) ?: Long.MAX_VALUE
        val sleep = DayPlan.usualSleep(nights) ?: 0L
        // Name the event only when it set the time (not when the 21:00 floor did)
        val forEvent = first?.let { it.first - 60 * 60_000L - sleep }
        return bed to first?.takeIf { bed < usual && forEvent == bed }
    }

    /** Sets tonight's reminder for 30 minutes before bedtime. */
    fun planTonight(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val fire = pi(ctx, ACT_BED, 7202)
        am.cancel(fire)
        if (!Prefs.bedtimeOn || !Features.day || !Features.health) return
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val evening = if (now.hour < 5) now.toLocalDate().minusDays(1) else now.toLocalDate()
        if (Prefs.bedtimeSkip == evening.toString()) return
        val (bed, _) = tonight(ctx) ?: return
        val at = bed - 30 * 60_000L
        if (at <= System.currentTimeMillis()) return
        setAlarm(am, at, fire)
    }

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun postBedtime(ctx: Context) {
        if (!Prefs.bedtimeOn || !Features.day || !Features.health) return
        val (bed, first) = tonight(ctx) ?: return
        val nights = Health.cached(ctx)?.nights ?: return
        val sleep = DayPlan.usualSleep(nights) ?: return
        channel(ctx)
        val text = if (first != null) "Sleep by ${clock(bed)} to get your usual ${HealthSummary.dur(sleep)} before ${first.second} at ${clock(first.first)}."
        else "Sleep by ${clock(bed)} to get your usual ${HealthSummary.dur(sleep)}."
        fun action(act: String, label: String, req: Int) =
            Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notify), label, pi(ctx, act, req)).build()
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Time to wind down")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, ID_BED))
            .setAutoCancel(true)
            .addAction(action(ACT_BED_LATER, "Remind in 20 min", 7203))
            .addAction(action(ACT_BED_SKIP, "Not tonight", 7204))
            .build()
        Notifs.post(ctx, ID_BED, n)
    }

    fun later(ctx: Context) {
        Notifs.cancel(ctx, ID_BED)
        setAlarm(ctx.getSystemService(AlarmManager::class.java), System.currentTimeMillis() + 20 * 60_000L, pi(ctx, ACT_BED, 7205))
    }

    fun skipTonight(ctx: Context) {
        Notifs.cancel(ctx, ID_BED)
        val now = ZonedDateTime.now()
        Prefs.bedtimeSkip = (if (now.hour < 5) now.toLocalDate().minusDays(1) else now.toLocalDate()).toString()
        ctx.getSystemService(AlarmManager::class.java).apply { cancel(pi(ctx, ACT_BED, 7202)); cancel(pi(ctx, ACT_BED, 7205)) }
    }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Bg.scope.launch {
            try {
                when (intent.action) {
                    Daily.ACT_CHECK -> { Daily.scheduleCheck(ctx); Daily.planTonight(ctx) }
                    Daily.ACT_BED -> Daily.postBedtime(ctx)
                    Daily.ACT_BED_LATER -> Daily.later(ctx)
                    Daily.ACT_BED_SKIP -> Daily.skipTonight(ctx)
                }
            } catch (e: Exception) {
                android.util.Log.w("Secretary", "Daily ${intent.action} failed", e)
            } finally { pending.finish() }
        }
    }
}
