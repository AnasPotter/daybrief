package com.anas.secretary.util

import android.app.Activity
import android.content.Context
import com.anas.secretary.logic.SleepNight
import com.anas.secretary.logic.Workout
import java.time.LocalDate

/**
 * Public build: no direct Samsung Health reading (the Samsung Health Data SDK can't be passed on).
 * Health comes from Health Connect only. Same members as the personal build, all inactive.
 */
object SamsungHealth {
    @Volatile var lastError: String? = null

    fun available(ctx: Context): Boolean = false

    suspend fun connect(activity: Activity): Boolean = false

    suspend fun connected(ctx: Context): Boolean = false

    class Data(val nights: List<SleepNight>, val restingHr: List<Pair<LocalDate, Long>>, val workouts: List<Workout>)

    suspend fun read(ctx: Context): Data? = null
}
