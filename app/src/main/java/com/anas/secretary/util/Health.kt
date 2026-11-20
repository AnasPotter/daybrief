package com.anas.secretary.util

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.anas.secretary.logic.HealthSnap
import com.anas.secretary.logic.SleepNight
import com.anas.secretary.logic.Workout
import com.anas.secretary.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Reads Samsung Health data from Health Connect, all on the phone (nothing goes online).
 * Health Connect only answers while the app is open, so the latest numbers are saved in a file
 * that Brief and the widgets read.
 */
object Health {
    val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    /** Bumped whenever new numbers are saved, so screens refresh. */
    val version = MutableStateFlow(0)

    enum class State { Unavailable, NeedsUpdate, NotConnected, Connected }

    fun sdk(ctx: Context): Int = HealthConnectClient.getSdkStatus(ctx)

    suspend fun state(ctx: Context): State = when (sdk(ctx)) {
        HealthConnectClient.SDK_AVAILABLE ->
            if (client(ctx).permissionController.getGrantedPermissions().containsAll(permissions)) State.Connected else State.NotConnected
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> State.NeedsUpdate
        else -> State.Unavailable
    }

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    /** Opens Samsung Health so you can turn on sharing with Health Connect. */
    fun openSamsungHealth(ctx: Context) {
        ctx.packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth")
            ?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ?: openHealthConnect(ctx)
    }

    fun openHealthConnect(ctx: Context) {
        try {
            ctx.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx.applicationContext)

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "health.json")

    /** The last saved numbers (works offline and in widgets). */
    fun cached(ctx: Context): HealthSnap? = try {
        HealthSnap.fromJson(file(ctx).takeIf { it.exists() }?.readText())
    } catch (_: Exception) { null }

    /** Reads fresh numbers if connected, saves them, and returns them (or the saved ones). */
    suspend fun refresh(ctx: Context): HealthSnap? = withContext(Dispatchers.IO) {
        try {
            if (state(ctx) != State.Connected) return@withContext cached(ctx)
            var snap = read(ctx)
            // Straight from Samsung Health when it's connected: fills sleep, heart and workouts
            // even when Samsung Health stops copying them into Health Connect.
            val d = SamsungHealth.read(ctx)
            if (d != null) {
                snap = snap.copy(
                    nights = d.nights.ifEmpty { snap.nights },
                    // the watch's own resting value (through Health Connect) wins over an estimate
                    restingHr = snap.restingHr.ifEmpty { d.restingHr },
                    workouts = d.workouts.ifEmpty { snap.workouts },
                )
            } else if (com.anas.secretary.util.Prefs.samsungDirect) {
                // Samsung Health didn't answer this time: keep what it gave last time instead of blanking it
                cached(ctx)?.let { old ->
                    snap = snap.copy(
                        nights = snap.nights.ifEmpty { old.nights },
                        restingHr = snap.restingHr.ifEmpty { old.restingHr },
                        workouts = snap.workouts.ifEmpty { old.workouts },
                    )
                }
            }
            file(ctx).writeText(snap.toJson())
            version.update { it + 1 }
            Widgets.refreshSoon(ctx)
            snap
        } catch (e: Exception) {
            android.util.Log.w("Secretary", "Health read failed", e)
            cached(ctx)
        }
    }

    /** All records of a kind in a time range, newest first (Health Connect hands them out in pages of up to 1000). */
    private suspend fun <T : Record> all(c: HealthConnectClient, type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val res = c.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), ascendingOrder = false, pageToken = token))
            out += res.records
            token = res.pageToken
        } while (token != null && out.size < 20_000)
        return out
    }

    /** What Health Connect has for one kind of data in the last 30 days, and which apps wrote it. [count] stops at [PEEK]. */
    data class Source(val label: String, val count: Int, val latest: Long?, val apps: List<String>)
    const val PEEK = 100

    /** A check of what reaches Secretary, so an empty card can say why. */
    suspend fun sources(ctx: Context): List<Source> = withContext(Dispatchers.IO) {
        val c = client(ctx)
        val now = Instant.now()
        val from = now.minus(java.time.Duration.ofDays(30))
        val pm = ctx.packageManager
        fun name(pkg: String) = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        suspend fun <T : Record> one(label: String, type: KClass<T>, time: (T) -> Instant) = try {
            // One small page, newest first, is enough to say "has data, newest at, from these apps"
            val r = c.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, now), ascendingOrder = false, pageSize = PEEK)).records
            Source(label, r.size, r.maxOfOrNull { time(it) }?.toEpochMilli(), r.map { it.metadata.dataOrigin.packageName }.distinct().map(::name))
        } catch (e: Exception) { Source(label, -1, null, listOf(e.javaClass.simpleName)) }
        listOf(
            one("Sleep", SleepSessionRecord::class) { it.endTime },
            one("Steps", StepsRecord::class) { it.endTime },
            one("Heart rate", HeartRateRecord::class) { it.endTime },
            one("Resting heart rate", RestingHeartRateRecord::class) { it.time },
            one("Exercise", ExerciseSessionRecord::class) { it.endTime },
        )
    }

    private suspend fun read(ctx: Context): HealthSnap {
        val c = client(ctx)
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val weekAgo = today.minusDays(7).atStartOfDay(zone).toInstant()

        // Sleep: one night per wake-up day (the longest, if there were naps)
        val sessions = all(c, SleepSessionRecord::class, today.minusDays(8).atStartOfDay(zone).toInstant(), now)
        val nights = sessions.map { night(it) }
            .groupBy { it.wakeDate(zone) }
            .map { (_, list) -> list.maxBy { it.end - it.start } }
            .sortedBy { it.end }
            .takeLast(7)

        // Steps per day for the last 8 days (Health Connect removes overlaps between phone and watch)
        val stepsByDay = c.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                setOf(StepsRecord.COUNT_TOTAL),
                TimeRangeFilter.between(today.minusDays(7).atStartOfDay(), today.plusDays(1).atStartOfDay()),
                Period.ofDays(1)
            )
        ).map { it.startTime.toLocalDate() to (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) }
        val stepsToday = c.aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(today.atStartOfDay(zone).toInstant(), now))
        )[StepsRecord.COUNT_TOTAL] ?: 0L

        // Health Connect's totals only count apps on its "data sources" priority list. If that gives nothing,
        // add up the raw step records instead: per day, the source with the most steps (so phone + watch aren't counted twice).
        var byDay = stepsByDay
        var todaySteps = stepsToday
        if (byDay.all { it.second == 0L } && todaySteps == 0L) {
            val raw = all(c, StepsRecord::class, today.minusDays(7).atStartOfDay(zone).toInstant(), now)
            val perDay = raw.groupBy { it.startTime.atZone(zone).toLocalDate() }.mapValues { (_, l) ->
                l.groupBy { it.metadata.dataOrigin.packageName }.values.maxOf { recs -> recs.sumOf { it.count } }
            }
            byDay = (0..7).map { today.minusDays(7L - it) }.map { it to (perDay[it] ?: 0L) }
            todaySteps = perDay[today] ?: 0L
        }

        // Resting heart rate: the watch's own value if it shares one, otherwise a low point of the day's readings
        val resting = all(c, RestingHeartRateRecord::class, weekAgo, now)
        val rhr = if (resting.isNotEmpty()) {
            resting.groupBy { it.time.atZone(zone).toLocalDate() }
                .map { (d, l) -> d to l.map { it.beatsPerMinute }.average().toLong() }
        } else {
            val hr = all(c, HeartRateRecord::class, weekAgo, now)
            hr.flatMap { it.samples }.groupBy { it.time.atZone(zone).toLocalDate() }
                .mapNotNull { (d, l) ->
                    val sorted = l.map { it.beatsPerMinute }.sorted()
                    if (sorted.size < 3) null else d to sorted[sorted.size / 20] // 5th percentile ignores odd readings
                }
        }.sortedBy { it.first }

        val workouts = all(c, ExerciseSessionRecord::class, weekAgo, now)
            .map { Workout(exerciseName(it.exerciseType), it.title, it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
            .sortedByDescending { it.start }

        return HealthSnap(System.currentTimeMillis(), nights, todaySteps, byDay.sortedBy { it.first }, rhr, workouts)
    }

    private fun night(r: SleepSessionRecord): SleepNight {
        var deep = 0L; var light = 0L; var rem = 0L; var awake = 0L
        val stages = r.stages.sortedBy { it.startTime }.map { s ->
            val ms = s.endTime.toEpochMilli() - s.startTime.toEpochMilli()
            val kind = when (s.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> "deep".also { deep += ms }
                SleepSessionRecord.STAGE_TYPE_REM -> "rem".also { rem += ms }
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake".also { awake += ms }
                else -> "light".also { light += ms }
            }
            kind to ms
        }
        val total = r.endTime.toEpochMilli() - r.startTime.toEpochMilli()
        if (stages.isEmpty()) light = total
        return SleepNight(r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), deep, light, rem, awake, stages)
    }

    private fun exerciseName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Run"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Strength"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swim"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hike"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN, ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Football"
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "Basketball"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
        else -> "Workout"
    }
}
