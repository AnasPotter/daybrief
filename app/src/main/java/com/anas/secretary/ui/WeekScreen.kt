package com.anas.secretary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.data.Repo
import com.anas.secretary.logic.DayPlan
import com.anas.secretary.logic.HealthSummary
import com.anas.secretary.util.Health
import com.anas.secretary.util.Prefs
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** "Your week" opens over everything (from the Brief tile, the morning summary or Health). */
object WeekState {
    var show by mutableStateOf(false)
}

private val purple = Color(0xFFA97CFF)
private val orange = Color(0xFFFF7A2F)
private val green = Color(0xFF5BD16B)

@Composable
fun WeekScreen(v: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    BackHandler(onBack = onClose)
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val from = today.minusDays(6)
    val snap = remember(v) { Health.cached(ctx) }
    val nights = snap?.nights.orEmpty().filter { !it.wakeDate(zone).isBefore(from) }
    val wk = remember(snap) { snap?.let { DayPlan.week(it.copy(nights = nights)) } }

    // Remember this week's sleep on the review day, so next week can compare
    // Compared only on the review day, and only with the review a week before (6-8 days ago)
    val compare = remember(wk) {
        val avg = wk?.avgSleep
        if (avg == null || today.dayOfWeek.value != Prefs.weekDay) return@remember null
        if (Prefs.lastWeekDate != today.toString()) {
            Prefs.prevWeekSleep = Prefs.lastWeekSleep; Prefs.prevWeekDate = Prefs.lastWeekDate
            Prefs.lastWeekDate = today.toString()
        }
        Prefs.lastWeekSleep = avg // kept fresh while the review day lasts
        val prevDay = try { LocalDate.parse(Prefs.prevWeekDate) } catch (_: Exception) { null }
        val gap = prevDay?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) }
        if (gap != null && gap in 6..8 && Prefs.prevWeekSleep > 0) avg - Prefs.prevWeekSleep else null
    }

    val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val tasks = remember(v) { Repo.tasks().filter { it.due != null && it.due in start until end } }
    val people = remember(v) { Repo.people().filter { it.isDue() }.sortedByDescending { it.daysSince() ?: 9999 }.take(6) }

    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 40.dp)
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowBack, "Back", tint = p.text)
            }
        }
        Text("${from.format(fmt)} – ${today.format(fmt)}", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        Text("Your week", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(start = 6.dp, bottom = 14.dp))

        // Sleep
        if (nights.isNotEmpty() && wk?.avgSleep != null) {
            WCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(purple), contentAlignment = Alignment.Center) {
                        Icon(HealthIcons.Moon, null, tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                    Text("Sleep", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.padding(start = 8.dp))
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text(HealthSummary.dur(wk.avgSleep), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = p.text)
                    Text("average" + (wk.avgScore?.let { " · score $it" } ?: ""), fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
                }
                // One bar per day; short nights (1 h under the average) in orange
                val byDay = nights.associateBy { it.wakeDate(zone) }
                val maxMs = maxOf(nights.maxOf { it.asleep }, 8 * 3_600_000L).toFloat()
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    (0..6).map { from.plusDays(it.toLong()) }.forEach { d ->
                        val n = byDay[d]
                        val f = ((n?.asleep ?: 0L) / maxMs).coerceIn(0.04f, 1f)
                        val short = n != null && n.asleep < wk.avgSleep - 3_600_000L
                        Box(
                            Modifier.weight(1f).fillMaxHeight(f).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(if (n == null) p.sub.copy(alpha = 0.15f) else if (short) orange else purple.copy(alpha = 0.85f))
                        )
                    }
                }
                Row(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..6).map { from.plusDays(it.toLong()) }.forEach { d ->
                        Text(d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.US), fontSize = 11.sp, color = p.sub, modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                wk.best?.let { b ->
                    val day = b.wakeDate(zone).dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.US)
                    Text("Best night $day (${HealthSummary.dur(b.asleep)}).", fontSize = 12.5.sp, color = p.sub, modifier = Modifier.padding(top = 8.dp))
                }
                compare?.takeIf { kotlin.math.abs(it) >= 10 * 60_000 }?.let { d ->
                    Text(
                        if (d < 0) "${HealthSummary.dur(-d)} less than last week" else "${HealthSummary.dur(d)} more than last week",
                        fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (d < 0) orange else green
                    )
                }
            }
        }

        // Numbers
        val rows = buildList {
            wk?.avgSteps?.let { add(Triple("Steps a day", NumberFormat.getIntegerInstance(Locale.US).format(it), "avg")) }
            if (wk != null && wk.workouts > 0) add(Triple("Workouts", "${wk.workouts}", HealthSummary.dur(wk.workoutMin * 60_000)))
            if (tasks.isNotEmpty()) add(Triple("Tasks done", "${tasks.count { it.done }}", "of ${tasks.size}"))
        }
        if (rows.isNotEmpty()) WCard {
            rows.forEachIndexed { i, (label, big, small) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text(label, fontSize = 13.sp, color = p.sub, modifier = Modifier.weight(1f))
                    Text(big, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.text)
                    Text(small, fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                }
            }
        }

        // People
        if (people.isNotEmpty()) WCard {
            Text("Haven't talked in a while", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Column(Modifier.padding(top = 6.dp)) {
                people.forEach { pp ->
                    val d = pp.daysSince()
                    val ago = when {
                        d == null -> "not yet"
                        d >= 14 -> "${d / 7} weeks"
                        else -> "$d days"
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pp.name, fontSize = 14.sp, color = p.text, modifier = Modifier.weight(1f))
                        Text(ago, fontSize = 12.5.sp, color = p.sub)
                    }
                }
            }
        }

        if (nights.isEmpty() && rows.isEmpty() && people.isEmpty()) {
            Text("Not much to show yet. Your week fills in as Daybrief learns your days.", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(6.dp))
        }
    }
}

@Composable
private fun WCard(content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.card).padding(horizontal = 17.dp, vertical = 16.dp), content = content)
}
