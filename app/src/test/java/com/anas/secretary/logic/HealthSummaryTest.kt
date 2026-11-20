package com.anas.secretary.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class HealthSummaryTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val h = 3_600_000L

    /** A night that starts on [day] at [bedHour] and lasts [hours]. */
    private fun night(day: LocalDate, bedHour: Int, hours: Double, awakeMin: Long = 0): SleepNight {
        val start = LocalDateTime.of(day, java.time.LocalTime.of(bedHour, 0)).atZone(zone).toInstant().toEpochMilli()
        val end = start + (hours * h).toLong()
        return SleepNight(start, end, 0, end - start - awakeMin * 60_000, 0, awakeMin * 60_000, emptyList())
    }

    @Test
    fun asleepLeavesOutTimeAwake() {
        val n = night(LocalDate.of(2026, 11, 1), 23, 8.0, awakeMin = 30)
        assertEquals((7.5 * h).toLong(), n.asleep)
    }

    @Test
    fun lastNightComparedWithTheNightsBefore() {
        val d = LocalDate.of(2026, 11, 1)
        val nights = listOf(night(d, 23, 8.0), night(d.plusDays(1), 23, 7.0), night(d.plusDays(2), 23, 6.0))
        assertEquals(-(1.5 * h).toLong(), HealthSummary.lastVsAverage(nights))
    }

    @Test
    fun noComparisonWithOnlyOneNight() {
        assertNull(HealthSummary.lastVsAverage(listOf(night(LocalDate.of(2026, 11, 1), 23, 7.0))))
    }

    @Test
    fun stepsAndSleepFindsAClearDifference() {
        val d = LocalDate.of(2026, 11, 1)
        // Nights start late on day N and end on day N+1: the steps of day N go with them
        val nights = (0..5).map { i -> night(d.plusDays(i.toLong()), 23, if (i % 2 == 0) 8.0 else 6.5) }
        val steps = (0..5).map { i -> d.plusDays(i.toLong()) to if (i % 2 == 0) 11_000L else 3_000L }
        val s = HealthSummary.stepsAndSleep(nights, steps, zone)
        assertNotNull(s)
        assertTrue(s!!, s.startsWith("You sleep 1h 30m longer"))
    }

    @Test
    fun stepsAndSleepStaysQuietWithoutEnoughData() {
        val d = LocalDate.of(2026, 11, 1)
        val nights = listOf(night(d, 23, 8.0), night(d.plusDays(1), 23, 6.0))
        val steps = listOf(d to 11_000L, d.plusDays(1) to 2_000L)
        assertNull(HealthSummary.stepsAndSleep(nights, steps, zone))
    }

    @Test
    fun lateNightsCountsBedtimesAfterOne() {
        val d = LocalDate.of(2026, 11, 1)
        val nights = listOf(night(d, 2, 6.0), night(d.plusDays(1), 1, 6.0), night(d.plusDays(2), 3, 5.0), night(d.plusDays(3), 23, 8.0))
        assertEquals("You went to bed after 1 AM on 3 of 4 nights.", HealthSummary.lateNights(nights, zone))
    }

    @Test
    fun savedSnapshotReadsBackTheSame() {
        val d = LocalDate.of(2026, 11, 1)
        val snap = HealthSnap(
            123L, listOf(night(d, 23, 7.0).copy(stages = listOf("deep" to 1000L, "rem" to 2000L))), 6840,
            listOf(d to 6840L), listOf(d to 62L), listOf(Workout("Walk", null, 1L, 1_920_001L))
        )
        assertEquals(snap, HealthSnap.fromJson(snap.toJson()))
    }
}
