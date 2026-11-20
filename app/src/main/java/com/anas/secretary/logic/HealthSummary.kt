package com.anas.secretary.logic

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One night of sleep. Times are epoch ms; stage totals are ms. [stages] = (kind, ms) in order, for the stage bar. */
data class SleepNight(
    val start: Long, val end: Long,
    val deep: Long, val light: Long, val rem: Long, val awake: Long,
    val stages: List<Pair<String, Long>>,
    /** Samsung Health's sleep score (0-100), only when read straight from Samsung Health. */
    val score: Int? = null
) {
    /** Time actually asleep (the whole night minus time awake). */
    val asleep: Long get() = (end - start - awake).coerceAtLeast(0)
    val hasStages: Boolean get() = deep + rem > 0
    fun wakeDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
}

data class Workout(val kind: String, val title: String?, val start: Long, val end: Long) {
    val minutes: Long get() = (end - start) / 60_000
}

/** Everything the app shows about health, read from Health Connect and kept so widgets and Brief can show it offline. */
data class HealthSnap(
    val updated: Long,
    val nights: List<SleepNight>,          // oldest first, at most 7
    val stepsToday: Long,
    val stepsByDay: List<Pair<LocalDate, Long>>, // oldest first, last 8 days
    val restingHr: List<Pair<LocalDate, Long>>,  // oldest first, last 7 days
    val workouts: List<Workout>            // newest first, last 7 days
) {
    val lastNight: SleepNight? get() = nights.lastOrNull()

    fun toJson(): String = JSONObject().apply {
        put("updated", updated)
        put("stepsToday", stepsToday)
        put("nights", JSONArray(nights.map { n ->
            JSONObject().put("start", n.start).put("end", n.end).put("deep", n.deep).put("light", n.light).put("rem", n.rem).put("awake", n.awake)
                .put("stages", JSONArray(n.stages.map { JSONArray().put(it.first).put(it.second) }))
                .apply { n.score?.let { put("score", it) } }
        }))
        put("steps", JSONArray(stepsByDay.map { JSONArray().put(it.first.toString()).put(it.second) }))
        put("rhr", JSONArray(restingHr.map { JSONArray().put(it.first.toString()).put(it.second) }))
        put("workouts", JSONArray(workouts.map { w ->
            JSONObject().put("kind", w.kind).put("title", w.title ?: "").put("start", w.start).put("end", w.end)
        }))
    }.toString()

    companion object {
        fun fromJson(s: String?): HealthSnap? = try {
            if (s.isNullOrBlank()) null else {
                val o = JSONObject(s)
                fun pairs(a: JSONArray) = (0 until a.length()).map { a.getJSONArray(it).let { p -> LocalDate.parse(p.getString(0)) to p.getLong(1) } }
                HealthSnap(
                    o.getLong("updated"),
                    o.getJSONArray("nights").let { a ->
                        (0 until a.length()).map { i ->
                            val n = a.getJSONObject(i)
                            val st = n.getJSONArray("stages")
                            SleepNight(
                                n.getLong("start"), n.getLong("end"), n.getLong("deep"), n.getLong("light"), n.getLong("rem"), n.getLong("awake"),
                                (0 until st.length()).map { j -> st.getJSONArray(j).let { p -> p.getString(0) to p.getLong(1) } },
                                if (n.has("score")) n.getInt("score") else null
                            )
                        }
                    },
                    o.getLong("stepsToday"),
                    pairs(o.getJSONArray("steps")),
                    pairs(o.getJSONArray("rhr")),
                    o.getJSONArray("workouts").let { a ->
                        (0 until a.length()).map { i ->
                            val w = a.getJSONObject(i)
                            Workout(w.getString("kind"), w.optString("title").ifBlank { null }, w.getLong("start"), w.getLong("end"))
                        }
                    }
                )
            }
        } catch (_: Exception) { null }
    }
}

/** Plain-language findings from the numbers. Pure functions, so they can be tested without a phone. */
object HealthSummary {
    const val STEP_GOAL = 10_000L
    private const val ACTIVE_DAY = 8_000L

    fun dur(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
    }

    /** Average sleep of the nights before last night (null if there are none). */
    fun averageBefore(nights: List<SleepNight>): Long? =
        nights.dropLast(1).takeIf { it.isNotEmpty() }?.map { it.asleep }?.average()?.toLong()

    /** Last night compared with the usual: negative = less sleep. */
    fun lastVsAverage(nights: List<SleepNight>): Long? {
        val last = nights.lastOrNull() ?: return null
        val avg = averageBefore(nights) ?: return null
        return last.asleep - avg
    }

    /** "You sleep 48 min longer on days you walk more than 8,000 steps", when the data shows a clear difference. */
    fun stepsAndSleep(nights: List<SleepNight>, stepsByDay: List<Pair<LocalDate, Long>>, zone: ZoneId = ZoneId.systemDefault()): String? {
        val steps = stepsByDay.toMap()
        // The steps of the day before you go to sleep go with that night
        val pairs = nights.mapNotNull { n -> steps[n.wakeDate(zone).minusDays(1)]?.let { it to n.asleep } }
        val active = pairs.filter { it.first >= ACTIVE_DAY }.map { it.second }
        val quiet = pairs.filter { it.first < ACTIVE_DAY }.map { it.second }
        if (active.size < 2 || quiet.size < 2) return null
        val diff = active.average() - quiet.average()
        if (kotlin.math.abs(diff) < 20 * 60_000) return null
        return if (diff > 0) "You sleep ${dur(diff.toLong())} longer on days you walk more than 8,000 steps."
        else "You sleep ${dur(-diff.toLong())} less on days you walk more than 8,000 steps."
    }

    /** "You went to bed after 1 AM on 4 of 7 nights", when it happens on 3 or more nights. */
    fun lateNights(nights: List<SleepNight>, zone: ZoneId = ZoneId.systemDefault()): String? {
        val late = nights.count { Instant.ofEpochMilli(it.start).atZone(zone).hour in 1..5 }
        return if (late >= 3) "You went to bed after 1 AM on $late of ${nights.size} nights." else null
    }

    fun insights(s: HealthSnap, zone: ZoneId = ZoneId.systemDefault()): List<String> =
        listOfNotNull(stepsAndSleep(s.nights, s.stepsByDay, zone), lateNights(s.nights, zone))
}

/** Day planning from sleep: bedtime, short nights, the week. Pure, so it is unit tested. */
object DayPlan {
    private const val H = 3_600_000L

    /** Usual time asleep: the median of the nights (needs 3). */
    fun usualSleep(nights: List<SleepNight>): Long? =
        nights.map { it.asleep }.filter { it > 2 * H }.sorted().takeIf { it.size >= 3 }?.let { it[it.size / 2] }

    /** Usual bedtime as minutes after noon (so 23:30 = 690 and 01:00 = 780); median, needs 3 nights. */
    fun usualBedMinutes(nights: List<SleepNight>, zone: ZoneId = ZoneId.systemDefault()): Int? =
        nights.map { Instant.ofEpochMilli(it.start).atZone(zone).let { t -> ((t.hour + 12) % 24) * 60 + t.minute } }
            .sorted().takeIf { it.size >= 3 }?.let { it[it.size / 2] }

    /** Usual wake time as minutes after midnight; median, needs 3 nights. */
    fun usualWakeMinutes(nights: List<SleepNight>, zone: ZoneId = ZoneId.systemDefault()): Int? =
        nights.map { Instant.ofEpochMilli(it.end).atZone(zone).let { t -> t.hour * 60 + t.minute } }
            .sorted().takeIf { it.size >= 3 }?.let { it[it.size / 2] }

    /**
     * When to be asleep tonight: early enough for the usual sleep before tomorrow's first event (minus [prepMin] to get ready),
     * but never later than the usual bedtime. Null without enough nights. [today] is the evening's date.
     */
    fun bedtime(nights: List<SleepNight>, firstEvent: Long?, today: LocalDate, prepMin: Int = 60, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val sleep = usualSleep(nights) ?: return null
        val bedMin = usualBedMinutes(nights, zone) ?: return null
        val usual = today.atStartOfDay(zone).plusMinutes((12 * 60 + bedMin).toLong()).toInstant().toEpochMilli()
        val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val floor = today.atTime(21, 0).atZone(zone).toInstant().toEpochMilli() // an early event never asks for bed before 21:00
        val forEvent = firstEvent?.takeIf { it in tomorrow until tomorrow + 14 * H }?.let { (it - prepMin * 60_000L - sleep).coerceAtLeast(floor) }
        return minOf(usual, forEvent ?: usual)
    }

    /** How much shorter last night was than the usual, when it is 1 hour or more (positive ms), else null. */
    fun shortBy(nights: List<SleepNight>): Long? {
        val d = HealthSummary.lastVsAverage(nights) ?: return null
        return if (d <= -H) -d else null
    }

    fun scoreWord(score: Int): String = when {
        score >= 85 -> "Excellent"
        score >= 70 -> "Good"
        score >= 60 -> "Fair"
        else -> "Poor"
    }

    /** True when [now] falls in the usual sleep hours (from the nights), or the fallback hours when there are too few nights. */
    fun inSleepHours(nights: List<SleepNight>, now: Long, fallbackFrom: Int, fallbackTo: Int, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val m = t.hour * 60 + t.minute
        val bed = usualBedMinutes(nights, zone)?.let { (it + 12 * 60) % (24 * 60) } ?: fallbackFrom
        val wake = usualWakeMinutes(nights, zone) ?: fallbackTo
        return if (bed <= wake) m in bed until wake else m >= bed || m < wake
    }

    data class Week(val avgSleep: Long?, val avgScore: Int?, val best: SleepNight?, val avgSteps: Long?, val workouts: Int, val workoutMin: Long)

    fun week(s: HealthSnap): Week {
        val n = s.nights
        val steps = s.stepsByDay.map { it.second }.filter { it > 0 }
        return Week(
            n.takeIf { it.isNotEmpty() }?.map { it.asleep }?.average()?.toLong(),
            n.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            n.maxByOrNull { it.asleep },
            steps.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            s.workouts.size, s.workouts.sumOf { it.minutes }
        )
    }
}
