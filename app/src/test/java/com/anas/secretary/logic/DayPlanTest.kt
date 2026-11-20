package com.anas.secretary.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DayPlanTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private fun at(d: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(d, java.time.LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 11, 18)

    /** A night that starts on [day] evening at bedH:bedM and lasts [hours]. */
    private fun night(day: LocalDate, bedH: Int, bedM: Int, hours: Double, score: Int? = null): SleepNight {
        val start = if (bedH < 12) at(day.plusDays(1), bedH, bedM) else at(day, bedH, bedM)
        val end = start + (hours * 3_600_000).toLong()
        return SleepNight(start, end, 0, end - start, 0, 0, emptyList(), score)
    }

    private val week = (7 downTo 1).map { night(today.minusDays(it.toLong()), 23, 30, 7.0) }

    @Test fun usualSleepAndBedtime() {
        assertEquals(7 * 3_600_000L, DayPlan.usualSleep(week))
        assertEquals(11 * 60 + 30, DayPlan.usualBedMinutes(week, zone))
        assertEquals(6 * 60 + 30, DayPlan.usualWakeMinutes(week, zone))
        assertNull(DayPlan.usualSleep(week.take(2)))
    }

    @Test fun bedtimeUsesUsualWithoutEarlyEvent() {
        assertEquals(at(today, 23, 30), DayPlan.bedtime(week, null, today, zone = zone))
        // A 10:00 meeting doesn't need an earlier bedtime
        assertEquals(at(today, 23, 30), DayPlan.bedtime(week, at(today.plusDays(1), 10), today, zone = zone))
    }

    @Test fun bedtimeMovesEarlierForEarlyEvent() {
        // 6:00 event - 1h getting ready - 7h sleep = 22:00
        assertEquals(at(today, 22), DayPlan.bedtime(week, at(today.plusDays(1), 6), today, zone = zone))
        // Never before 21:00
        assertEquals(at(today, 21), DayPlan.bedtime(week, at(today.plusDays(1), 3), today, zone = zone))
    }

    @Test fun bedtimeAfterMidnight() {
        val late = (7 downTo 1).map { night(today.minusDays(it.toLong()), 1, 0, 7.0) }
        assertEquals(at(today.plusDays(1), 1), DayPlan.bedtime(late, null, today, zone = zone))
    }

    @Test fun shortNight() {
        assertNull(DayPlan.shortBy(week))
        val short = week.dropLast(1) + night(today.minusDays(1), 1, 0, 5.0)
        assertEquals(2 * 3_600_000L, DayPlan.shortBy(short))
    }

    @Test fun sleepHours() {
        assertTrue(DayPlan.inSleepHours(week, at(today, 2), 23 * 60, 7 * 60, zone))
        assertFalse(DayPlan.inSleepHours(week, at(today, 12), 23 * 60, 7 * 60, zone))
        // Too few nights: fallback 23:00 - 7:00
        assertTrue(DayPlan.inSleepHours(emptyList(), at(today, 23, 30), 23 * 60, 7 * 60, zone))
        assertFalse(DayPlan.inSleepHours(emptyList(), at(today, 22), 23 * 60, 7 * 60, zone))
    }

    @Test fun scoreWordsAndJson() {
        assertEquals("Good", DayPlan.scoreWord(82))
        assertEquals("Poor", DayPlan.scoreWord(40))
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class SnapJsonTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private fun night(score: Int?) = SleepNight(1_000L, 25_000_000L, 0, 24_999_000L, 0, 0, emptyList(), score)

    @Test fun scoreSurvivesJson() {
        val snap = HealthSnap(1L, listOf(night(82), night(null)), 0, emptyList(), emptyList(), emptyList())
        val back = HealthSnap.fromJson(snap.toJson())
        assertNotNull(back)
        assertEquals(82, back!!.nights[0].score)
        assertNull(back.nights[1].score)
    }
}
