package com.anas.secretary.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.data.Person
import com.anas.secretary.data.Task
import com.anas.secretary.logic.HealthSummary
import com.anas.secretary.logic.SleepNight
import com.anas.secretary.util.CalEvent
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// ======================= Brief in the new look: the small tiles under the deck =======================

/** The small tiles under the deck. Only tiles with something to show appear, two per row. */
@Composable
fun BriefTiles(
    lastNight: SleepNight?, next: Pair<String, Long>?, today: List<Task>, due: List<Person>, overdue: List<Task>,
    onSleep: () -> Unit, onNext: () -> Unit, onPeople: () -> Unit, onOverdue: () -> Unit, onToday: () -> Unit
) {
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        next?.let { (title, at) -> add { m -> NextTile(title, at, m, onNext) } }
        if (today.isNotEmpty()) add { m -> TodayTasksTile(today, m, onToday) }
        if (due.isNotEmpty()) add { m -> PeopleTile(due, m, onPeople) }
        if (overdue.isNotEmpty()) add { m -> OverdueTile(overdue, m, onOverdue) }
        lastNight?.let { n -> add { m -> SleepTile(n, m, onSleep) } }
    }
    // One Column for the rows: the caller puts this in a Box, where loose rows would sit on top of each other
    Column {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 11.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SleepTile(n: SleepNight, m: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val hours = n.asleep / 3_600_000.0
    val (word, color) = when {
        hours >= 7 -> "Good" to TileColors.green
        hours >= 6 -> "Fair" to TileColors.yellow
        else -> "Short" to TileColors.orange
    }
    val target = (hours / 8.0).toFloat().coerceIn(0.03f, 1f)
    val sweep by animateFloatAsState(target, tween(900, easing = FastOutSlowInEasing), label = "sleepGauge")
    Tile("Sleep", m, onClick = onClick) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(104.dp), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.size(112.dp)) {
                val w = 9.dp.toPx()
                val o = Offset(w / 2, w / 2)
                val s = Size(size.width - w, size.height - w)
                drawArc(p.text.copy(alpha = 0.12f), 135f, 270f, false, o, s, style = Stroke(w, cap = StrokeCap.Round))
                drawArc(TileColors.blue, 135f, 270f * sweep, false, o, s, style = Stroke(w, cap = StrokeCap.Round))
            }
            Column(Modifier.padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(HealthSummary.dur(n.asleep), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = p.text)
                Text(word, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
        }
    }
}

@Composable
private fun NextTile(title: String, at: Long, m: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    Tile("Next up", m, onClick = onClick) {
        BigNumber(time, size = 34, modifier = Modifier.padding(top = 14.dp))
        Text(title, fontSize = 14.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(inText(at), fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun inText(at: Long): String {
    val mins = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return when {
        day != LocalDate.now() -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        mins < 60 -> "in $mins min"
        else -> "in ${mins / 60} h"
    }
}

@Composable
private fun PeopleTile(due: List<Person>, m: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val first = due.first()
    Tile("Keep in touch", m, dot = true, onClick = onClick) {
        Row(Modifier.padding(top = 14.dp)) {
            due.take(3).forEachIndexed { i, person ->
                Box(Modifier.offset(x = (-9 * i).dp).border(2.5.dp, p.card, CircleShape)) { PersonAvatar(person, 38.dp, showRing = false) }
            }
        }
        val days = first.daysSince()
        Text(
            if (days != null) "${first.name}, $days days" else first.name,
            fontSize = 13.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)
        )
        if (due.size > 1) Text("+${due.size - 1} more", fontSize = 12.sp, color = p.sub)
    }
}

/** Tasks due today: a ring that fills as you tick them off, "2/5" in the middle. */
@Composable
private fun TodayTasksTile(today: List<Task>, m: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val done = today.count { it.done }
    val total = today.size
    val sweep by animateFloatAsState(done.toFloat() / total, tween(800, easing = FastOutSlowInEasing), label = "tasksRing")
    Tile("Tasks today", m, onClick = onClick) {
        Box(Modifier.padding(top = 12.dp).size(76.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 8.dp.toPx()
                val o = Offset(w / 2, w / 2)
                val s = Size(size.width - w, size.height - w)
                drawArc(p.text.copy(alpha = 0.12f), 0f, 360f, false, o, s, style = Stroke(w))
                if (sweep > 0f) drawArc(TileColors.green, -90f, 360f * sweep, false, o, s, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text("$done/$total", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.text)
        }
        val left = total - done
        Text(
            if (left == 0) "All done" else "$left left",
            fontSize = 13.sp, color = if (left == 0) TileColors.green else p.sub, modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun OverdueTile(overdue: List<Task>, m: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Tile("Overdue", m, dot = true, onClick = onClick) {
        BigNumber("${overdue.size}", color = TileColors.pink, modifier = Modifier.padding(top = 14.dp))
        Text(overdue.first().title, fontSize = 13.sp, color = p.sub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The next reminder or calendar event from now. */
fun nextUp(tasks: List<Task>, events: List<CalEvent>, now: Long): Pair<String, Long>? {
    val t = tasks.firstOrNull { !it.done && it.due != null && it.due >= now }?.let { it.title to it.due!! }
    val e = events.filter { it.begin >= now }.minByOrNull { it.begin }?.let { it.title to it.begin }
    return listOfNotNull(t, e).minByOrNull { it.second }
}

/** After a short night: offers to move today's flexible tasks to tomorrow (with Undo). */
@Composable
fun LightDayCard(shortBy: Long, slept: Long, movable: List<Task>, onMove: () -> Unit, onUndo: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    var moved by remember { mutableStateOf(false) }
    val purple = androidx.compose.ui.graphics.Color(0xFFA97CFF)
    Box(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(glassCard())
            .border(1.5.dp, purple.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
    ) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(purple), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(HealthIcons.Moon, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(17.dp))
                }
                Text("Short night", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text(HealthSummary.dur(slept), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = p.text)
                Text("${HealthSummary.dur(shortBy)} below usual", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
            }
            if (!moved) {
                val n = movable.size
                Text("Take it easy today? $n task${if (n == 1) "" else "s"} can wait until tomorrow:", fontSize = 13.5.sp, color = p.text, modifier = Modifier.padding(top = 8.dp))
                Text(movable.joinToString(" · ") { it.title }, fontSize = 12.5.sp, color = p.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PrimaryButton("Move them", Modifier.weight(1f)) { onMove(); moved = true }
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(p.field)
                            .clickable { onClose() }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Not now", fontSize = 14.sp, color = p.text) }
                }
            } else {
                Text("Moved to tomorrow.", fontSize = 13.5.sp, color = p.text, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    TextAction("Undo") { onUndo(); moved = false }
                    TextAction("Done", p.sub) { onClose() }
                }
            }
        }
    }
}

/** On the review day: opens "Your week". */
@Composable
fun WeekTile(onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(glassCard())
            .clickable(onClick = onClick).padding(horizontal = 17.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFFA97CFF)), contentAlignment = Alignment.Center) {
            Text("📊", fontSize = 14.sp)
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("Your week", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Text("A look back at your last 7 days", fontSize = 12.5.sp, color = p.sub)
        }
        Text("›", fontSize = 22.sp, color = p.sub)
    }
}
