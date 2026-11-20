package com.anas.secretary.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class Detection(val at: Long, val hasTime: Boolean, val phrase: String)

/**
 * Rule-based date/time finder. Understands things like:
 * "tomorrow 3pm", "next Thursday at 10", "5 Nov", "12/11", "in 2 hours",
 * "tonight", "at 14:30", plus a few Arabic words (غدا، بكرة، اليوم، الساعة).
 */
object DateExtractor {
    /** true = 12/11 means 12 November. Set false for US style (December 11). */
    var dayFirst = true

    private const val MONTH =
        "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )
    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "weds" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY
    )

    private val reRelative = Regex("""\bin\s+(\d{1,3}|an?|one|two|three)\s+(mins?|minutes?|hrs?|hours?|days?|weeks?)\b""")
    private val reIso = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val reSlash = Regex("""\b(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b""")
    private val reDayMonth = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$MONTH\b""")
    private val reMonthDay = Regex("""\b$MONTH\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b""")
    private val reWeekday = Regex("""\b(next\s+|this\s+)?(monday|tuesday|tues|wednesday|weds|thursday|thurs|friday|saturday|sunday)\b""")
    private val reAmPm = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)(?![a-z])""")
    private val re24 = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val reAt = Regex("""(?:\bat|@|الساعة)\s*(\d{1,2})(?![\d:/.])""")

    private fun MatchResult.gv(i: Int) = groupValues[i]
    private fun safe(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (e: Exception) { null }
    private fun upcoming(today: LocalDate, m: Int, d: Int): LocalDate? {
        val thisYear = safe(today.year, m, d) ?: return null
        return if (thisYear.isBefore(today)) safe(today.year + 1, m, d) else thisYear
    }

    fun extract(text: String, now: ZonedDateTime = ZonedDateTime.now()): Detection? {
        if (text.isBlank()) return null
        val t = text.lowercase()

        reRelative.find(t)?.let { m ->
            val n = when (val q = m.gv(1)) {
                "a", "an", "one" -> 1L; "two" -> 2L; "three" -> 3L; else -> q.toLong()
            }
            val unit = m.gv(2)
            val at = when {
                unit.startsWith("m") -> now.plusMinutes(n)
                unit.startsWith("h") -> now.plusHours(n)
                unit.startsWith("d") -> now.plusDays(n)
                else -> now.plusWeeks(n)
            }
            return Detection(at.toInstant().toEpochMilli(), true, m.value)
        }

        val today = now.toLocalDate()
        val date = findDate(t, today)
        val time = findTime(t)
        if (date == null && time == null) return null

        val clock = time?.first ?: partOfDay(t) ?: LocalTime.of(9, 0)
        var at = ZonedDateTime.of(date?.first ?: today, clock, now.zone)
        if (at.isBefore(now)) {
            at = when {
                date == null -> at.plusDays(1) // time already passed today -> tomorrow
                time == null && date?.first == today ->
                    now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
                else -> at
            }
        }
        val phrase = listOfNotNull(date?.second, time?.second).joinToString(" ")
        return Detection(at.toInstant().toEpochMilli(), time != null, phrase)
    }

    private fun findDate(t: String, today: LocalDate): Pair<LocalDate, String>? {
        reIso.find(t)?.let { m ->
            safe(m.gv(1).toInt(), m.gv(2).toInt(), m.gv(3).toInt())?.let { return it to m.value }
        }
        reDayMonth.find(t)?.let { m ->
            val mo = months[m.gv(2).take(3)]
            if (mo != null) upcoming(today, mo, m.gv(1).toInt())?.let { return it to m.value }
        }
        reMonthDay.find(t)?.let { m ->
            val mo = months[m.gv(1).take(3)]
            if (mo != null) upcoming(today, mo, m.gv(2).toInt())?.let { return it to m.value }
        }
        reSlash.find(t)?.let { m ->
            val a = m.gv(1).toInt()
            val b = m.gv(2).toInt()
            val d = if (dayFirst) a else b
            val mo = if (dayFirst) b else a
            val y = m.gv(3)
            val res = if (y.isEmpty()) upcoming(today, mo, d) else {
                var yr = y.toInt(); if (yr < 100) yr += 2000
                safe(yr, mo, d)
            }
            res?.let { return it to m.value }
        }
        if (t.contains("day after tomorrow")) return today.plusDays(2) to "day after tomorrow"
        listOf("tomorrow", "tmrw", "tmr", "غداً", "غدا", "بكرة", "بكره").firstOrNull { has(t, it) }
            ?.let { return today.plusDays(1) to it }
        reWeekday.find(t)?.let { m ->
            val dow = weekdays.getValue(m.gv(2))
            var ahead = (dow.value - today.dayOfWeek.value + 7) % 7
            if (ahead == 0 && m.gv(1).trim() != "this") ahead = 7
            return today.plusDays(ahead.toLong()) to m.value.trim()
        }
        listOf("today", "tonight", "اليوم", "الليلة").firstOrNull { has(t, it) }
            ?.let { return today to it }
        return null
    }

    private fun findTime(t: String): Pair<LocalTime, String>? {
        reAmPm.find(t)?.let { m ->
            var h = m.gv(1).toInt()
            val min = m.gv(2).ifEmpty { "0" }.toInt()
            val pm = m.gv(3).startsWith("p")
            if (h in 1..12 && min in 0..59) {
                if (pm && h < 12) h += 12
                if (!pm && h == 12) h = 0
                return LocalTime.of(h, min) to m.value
            }
        }
        re24.find(t)?.let { m ->
            var h = m.gv(1).toInt()
            // "3:30" with no am/pm almost always means afternoon; "03:30" stays as written
            if (m.gv(1).length == 1 && h in 1..6) h += 12
            return LocalTime.of(h, m.gv(2).toInt()) to m.value
        }
        reAt.find(t)?.let { m ->
            var h = m.gv(1).toInt()
            if (h in 1..23) {
                if (h in 1..7) h += 12
                return LocalTime.of(h, 0) to m.value
            }
        }
        return null
    }

    private fun partOfDay(t: String): LocalTime? = when {
        has(t, "tonight") || t.contains("الليلة") -> LocalTime.of(20, 0)
        has(t, "noon") || has(t, "lunch") -> LocalTime.of(12, 0)
        has(t, "morning") || t.contains("الصبح") -> LocalTime.of(9, 0)
        has(t, "afternoon") -> LocalTime.of(15, 0)
        has(t, "evening") || t.contains("المساء") -> LocalTime.of(18, 0)
        else -> null
    }
}
