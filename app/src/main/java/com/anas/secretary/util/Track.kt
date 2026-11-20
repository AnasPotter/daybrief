package com.anas.secretary.util

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.anas.secretary.R
import com.anas.secretary.data.Repo
import com.anas.secretary.data.TLog
import com.anas.secretary.data.TState
import com.anas.secretary.data.Tracker
import com.anas.secretary.ui.MainActivity
import com.anas.secretary.widget.TrackWidget
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

const val HOUR = 3_600_000L
const val DAY = 24 * HOUR

/** What the history says about a tracker. */
class Insight(
    val usual: Long?, val lastDone: Long?, val next: Long?, val onTrack: Boolean?,
    val count30: Int, val longestGap: Long?, val typicalTime: String?, val typicalDay: String?,
    val amounts: List<Double>, val unit: String
)

class Template(val name: String, val emoji: String, val color: Int, val trackers: List<Triple<String, List<TState>, Long>>)

object Track {
    private fun g(n: String) = TState(n, "good")
    private fun b(n: String) = TState(n, "bad")
    private fun w(n: String) = TState(n, "warning")
    private fun nn(n: String) = TState(n, "neutral")

    val templates = listOf(
        Template("Car", "\uD83D\uDE97", 0xFF378ADD.toInt(), listOf(
            Triple("Oil change", listOf(g("Done"), b("Due")), 90 * DAY),
            Triple("Car wash", listOf(g("Washed"), w("Dirty")), 14 * DAY)
        )),
        Template("Home", "\uD83C\uDFE0", 0xFFD85A30.toInt(), listOf(
            Triple("Bedsheets", listOf(g("Changed"), b("Overdue")), 10 * DAY),
            Triple("AC filter", listOf(g("Clean"), w("Dirty")), 42 * DAY),
            Triple("Trash", listOf(g("Taken out")), 2 * DAY)
        )),
        Template("Health", "\uD83D\uDC8A", 0xFF1D9E75.toInt(), listOf(
            Triple("Vitamins", listOf(g("Taken")), 1 * DAY),
            Triple("Workout", listOf(g("Done"), nn("Rest day"), w("Skipped")), 0L)
        )),
        Template("Plant", "\uD83C\uDF31", 0xFF639922.toInt(), listOf(
            Triple("Watering", listOf(g("Watered"), b("Needs water")), 5 * DAY),
            Triple("Fertilizer", listOf(g("Done")), 30 * DAY)
        )),
        Template("Pet", "\uD83D\uDC31", 0xFFBA7517.toInt(), listOf(
            Triple("Feeding", listOf(g("Fed"), b("Hungry")), 12 * HOUR),
            Triple("Vet visit", listOf(g("Done")), 180 * DAY)
        )),
        Template("Other", "\u2B50", 0xFF7F77DD.toInt(), emptyList())
    )

    val emojis = listOf(
        "\uD83D\uDE97", "\uD83C\uDFE0", "\uD83D\uDC8A", "\uD83C\uDF31", "\uD83D\uDC31", "\uD83D\uDC36", "\uD83E\uDDFA",
        "\uD83C\uDFCB\uFE0F", "\u2702\uFE0F", "\uD83E\uDDF4", "\uD83D\uDCA7", "\u2615", "\uD83D\uDCDA", "\uD83D\uDCBC",
        "\uD83C\uDF73", "\u2B50"
    )
    val colors = listOf(0xFF378ADD, 0xFFD85A30, 0xFF1D9E75, 0xFF639922, 0xFFBA7517, 0xFF7F77DD, 0xFFD4537E).map { it.toInt() }

    // ---------- time words ----------

    fun dur(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "${m}m"
            m < 60 * 24 -> "${m / 60}h"
            m < 60 * 24 * 14 -> (m / (60 * 24)).let { if (it == 1L) "1 day" else "$it days" }
            m < 60 * 24 * 60 -> "${m / (60 * 24 * 7)} weeks"
            m < 60 * 24 * 365 -> (m / (60 * 24 * 30)).let { if (it == 1L) "1 month" else "$it months" }
            else -> (m / (60 * 24 * 365)).let { if (it == 1L) "1 year" else "$it years" }
        }
    }

    fun ago(since: Long, now: Long = System.currentTimeMillis()): String {
        val d = dur(now - since)
        return if (d == "just now") "Just now" else "$d ago"
    }

    fun every(ms: Long): String {
        val h = ms / HOUR
        return when {
            h < 20 -> "every ${h.coerceAtLeast(1)} hours"
            h < 36 -> "every day"
            h < 24 * 12 -> "every ${(ms + DAY / 2) / DAY} days"
            h < 24 * 50 -> (ms / (7 * DAY)).let { if (it <= 1) "every week" else "every $it weeks" }
            else -> (ms / (30 * DAY)).let { if (it <= 1) "every month" else "every $it months" }
        }
    }

    // ---------- insight ----------

    private fun doneTimes(t: Tracker, logs: List<TLog>): List<Long> =
        (if (t.states.size == 1) logs else logs.filter { it.kind == "good" }).map { it.time }.sorted()

    fun insight(t: Tracker, logs: List<TLog>, now: Long = System.currentTimeMillis()): Insight {
        val done = doneTimes(t, logs)
        val gaps = done.zipWithNext { a, b -> b - a }.filter { it > 10 * 60_000L }
        val usual = if (gaps.size >= 2) gaps.sorted()[gaps.size / 2] else t.autoAfter.takeIf { it > 0 }
        val last = done.lastOrNull()
        val next = if (usual != null && last != null) last + usual else null
        val onTrack = if (usual != null && last != null) now - last <= usual * 1.2 else null
        val zone = ZoneId.systemDefault()
        val typicalTime = if (done.size >= 3) done.map {
            val h = Instant.ofEpochMilli(it).atZone(zone).hour
            when (h) { in 5..11 -> "in the morning"; in 12..16 -> "in the afternoon"; in 17..20 -> "in the evening"; else -> "at night" }
        }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key else null
        val typicalDay = if (done.size >= 5) done.map { Instant.ofEpochMilli(it).atZone(zone).dayOfWeek }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }
            ?.takeIf { it.value >= 2 }?.key?.getDisplayName(TextStyle.FULL, Locale.getDefault()) else null
        val amountLogs = logs.filter { it.amount.isNotBlank() }.sortedBy { it.time }
        val amounts = amountLogs.mapNotNull { Regex("[0-9]+([.,][0-9]+)?").find(it.amount)?.value?.replace(',', '.')?.toDoubleOrNull() }
        val unit = amountLogs.lastOrNull()?.amount?.replace(Regex("[0-9.,\\s]+"), "")?.trim().orEmpty()
        return Insight(
            usual, last, next, onTrack, done.count { it > now - 30 * DAY }, gaps.maxOrNull(),
            typicalTime, typicalDay, amounts.takeLast(12), unit
        )
    }

    /** Needs your attention: in a "bad" state, or later than you usually do it. */
    fun needsAttention(t: Tracker, ins: Insight, now: Long = System.currentTimeMillis()): Boolean {
        if (t.snooze > now) return false
        if (t.state.kind == "bad") return true
        val usual = ins.usual ?: return false
        val last = ins.lastDone ?: return false
        return now - last > usual * 1.25
    }

    // ---------- changes ----------

    /** One tap: move to the next state and restart the timer. Returns a way to undo it. */
    fun tap(t: Tracker, toIdx: Int? = null, at: Long = System.currentTimeMillis(), note: String = "", amount: String = "", photo: String? = null): () -> Unit {
        val idx = toIdx ?: if (t.states.size <= 1) 0 else (t.idx + 1) % t.states.size
        val st = t.states.getOrElse(idx) { TState("Done", "good") }
        val logId = Repo.addLog(t.id, st.name, st.kind, at, note, amount, photo)
        if (at >= t.since) Repo.setTrackerState(t.id, idx, at)
        return {
            Repo.deleteLog(logId)
            Repo.setTrackerState(t.id, t.idx, t.since)
        }
    }

    /** Make the tracker show whatever its newest log says (after editing or deleting logs). */
    fun recompute(trackerId: Long) {
        val t = Repo.tracker(trackerId) ?: return
        val latest = Repo.logs(trackerId, 1).firstOrNull() ?: return
        val idx = t.states.indexOfFirst { it.name == latest.state }.let { if (it < 0) t.idx else it }
        Repo.setTrackerState(t.id, idx, latest.time)
    }

    /** "Watered" turns into "Needs water" by itself after the time you chose. */
    fun applyAuto(now: Long = System.currentTimeMillis()) {
        Repo.trackers().forEach { t ->
            if (t.autoAfter > 0 && t.states.size >= 2 && t.idx == 0 && now - t.since >= t.autoAfter) {
                val at = t.since + t.autoAfter
                val st = t.states[1]
                Repo.addLog(t.id, st.name, st.kind, at, "Changed by itself", "", null)
                Repo.setTrackerState(t.id, 1, at)
            }
        }
    }

    fun attentionList(now: Long = System.currentTimeMillis()): List<Pair<Tracker, Insight>> =
        Repo.trackers().map { it to insight(it, Repo.logs(it.id)) }.filter { needsAttention(it.first, it.second, now) }

    // ---------- widget ----------

    fun widgetsChanged(ctx: Context) = com.anas.secretary.widget.Widgets.refreshSoon(ctx)

    // ---------- daily heads-up ----------

    private const val CH = "nudges"

    fun scheduleNudges(ctx: Context) {
        val zone = ZoneId.systemDefault()
        var first = ZonedDateTime.of(LocalDate.now(), LocalTime.of(9, 0), zone)
        if (first.toInstant().toEpochMilli() <= System.currentTimeMillis()) first = first.plusDays(1)
        val pi = PendingIntent.getBroadcast(
            ctx, 7001, Intent(ctx, NudgeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ctx.getSystemService(AlarmManager::class.java)
            .setInexactRepeating(AlarmManager.RTC_WAKEUP, first.toInstant().toEpochMilli(), AlarmManager.INTERVAL_DAY, pi)
    }

    fun nudgeNow(ctx: Context) {
        if (!Features.things) return
        applyAuto()
        val due = attentionList()
        if (due.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Daily heads-up", NotificationManager.IMPORTANCE_DEFAULT))
        val (t, ins) = due.first()
        val good = t.states.firstOrNull()?.name ?: "Done"
        val text = if (ins.lastDone != null && ins.usual != null)
            "$good ${ago(ins.lastDone).lowercase()}, you usually do it ${every(ins.usual)}."
        else "${t.name}: ${t.state.name} for ${dur(System.currentTimeMillis() - t.since)}."
        val more = if (due.size > 1) " And ${due.size - 1} more." else ""
        val open = PendingIntent.getActivity(
            ctx, 7002, Intent(ctx, MainActivity::class.java).putExtra("tab", 2).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(t.name)
            .setContentText(text + more)
            .setStyle(Notification.BigTextStyle().bigText(text + more))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(7003, n) } catch (_: SecurityException) {}
    }
}

class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Bg.scope.launch {
            try { Track.nudgeNow(ctx) } finally { pending.finish() }
        }
    }
}
