package com.anas.secretary.ui

import android.Manifest
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.data.Repo
import com.anas.secretary.data.Task
import com.anas.secretary.util.Cal
import com.anas.secretary.util.CalEvent
import com.anas.secretary.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** Remembers which day and view you were on while switching tabs. */
object CalState {
    var day by mutableStateOf(LocalDate.now())
    var mode by mutableIntStateOf(0) // 0 = Day, 1 = Week, 2 = List
    var filter by mutableIntStateOf(0) // 0 = all, 1 = personal, 2 = work
}

private fun LocalDate.startMs() = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
private fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
private fun opaque(c: Int) = Color(c or 0xFF000000.toInt())

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    val full = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
    return when (d) {
        today -> "Today, $full"
        today.plusDays(1) -> "Tomorrow, $full"
        else -> full
    }
}

/** One thing on the timeline: a reminder or a calendar event. */
private class Slot(val start: Long, val end: Long, val task: Task?, val event: CalEvent?) {
    var lane = 0
    var lanes = 1
}

private fun slotsFor(day: LocalDate, tasks: List<Task>, events: List<CalEvent>): List<Slot> {
    val ds = day.startMs()
    val de = day.plusDays(1).startMs()
    val out = mutableListOf<Slot>()
    tasks.forEach { t -> val d = t.due; if (d != null && d >= ds && d < de) out += Slot(d, d + 30 * 60_000L, t, null) }
    events.forEach { e ->
        if (!e.allDay && e.end > ds && e.begin < de) {
            val s = maxOf(e.begin, ds)
            out += Slot(s, maxOf(minOf(e.end, de), s + 20 * 60_000L), null, e)
        }
    }
    // Side-by-side lanes for things that overlap
    val sorted = out.sortedBy { it.start }
    val cluster = mutableListOf<Slot>()
    val laneEnds = mutableListOf<Long>()
    var clusterEnd = Long.MIN_VALUE
    fun close() {
        val n = laneEnds.size.coerceAtLeast(1)
        cluster.forEach { it.lanes = n }
        cluster.clear(); laneEnds.clear(); clusterEnd = Long.MIN_VALUE
    }
    for (s in sorted) {
        if (cluster.isNotEmpty() && s.start >= clusterEnd) close()
        var lane = laneEnds.indexOfFirst { it <= s.start }
        if (lane < 0) { lane = laneEnds.size; laneEnds.add(s.end) } else laneEnds[lane] = s.end
        s.lane = lane
        cluster += s
        clusterEnd = maxOf(clusterEnd, s.end)
    }
    close()
    return sorted
}

@Composable
fun CalendarScreen(v: Int, tick: Int) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var permTick by remember { mutableIntStateOf(0) }
    val canRead = remember(tick, permTick) { Cal.canRead(ctx) }
    var calTick by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permTick++
        if (res[Manifest.permission.WRITE_CALENDAR] == true && Prefs.calTarget < 0) {
            Cal.defaultTarget(ctx)?.let { t ->
                Prefs.calTarget = t.id
                Prefs.calSync = true
                Repo.syncAllToCalendar()
                Toast.makeText(ctx, "Your reminders will also appear in \u201C${t.name}\u201D. Change it in Settings.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Refresh as soon as anything changes in Samsung Calendar
    DisposableEffect(canRead) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { calTick++ }
        }
        if (canRead) try { ctx.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, obs) } catch (_: Exception) {}
        onDispose { try { ctx.contentResolver.unregisterContentObserver(obs) } catch (_: Exception) {} }
    }

    val today = LocalDate.now()
    val stripStart = remember { today.minusDays(14) }
    val stripDays = 150
    val allTasks = remember(v) { Repo.tasks() }
    val tasks = when (CalState.filter) {
        1 -> allTasks.filter { !it.work }
        2 -> allTasks.filter { it.work }
        else -> allTasks
    }
    val events by produceState(emptyList<CalEvent>(), v, calTick, canRead) {
        value = withContext(Dispatchers.IO) {
            Cal.events(ctx, stripStart.minusDays(7).startMs(), stripStart.plusDays(stripDays + 7L).startMs())
        }
    }
    var editing by remember { mutableStateOf<Task?>(null) }
    var newAt by remember { mutableStateOf<Long?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 28.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(CalState.day.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), fontSize = 13.sp, color = p.sub)
                    Text("Calendar", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.padding(top = 2.dp))
                }
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(p.card)
                        .clickable(enabled = !canRead) {
                            launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (canRead) Icons.Default.Refresh else Icons.Default.DateRange, null,
                        tint = if (canRead) Color(0xFF1D9E75) else p.accent, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (canRead) "Samsung Calendar" else "Connect calendar", fontSize = 12.sp, color = if (canRead) p.sub else p.accent)
                }
                Spacer(Modifier.width(4.dp))
                SearchButton()
                Avatar()
            }
            Segmented(listOf("Day", "Week", "List"), CalState.mode) { CalState.mode = it }
            Box(Modifier.padding(top = 10.dp)) {
                CategoryFilter(listOf("All", "Personal", "Work"), CalState.filter, null) { CalState.filter = it }
            }
        }
        AnimatedVisibility(visible = CalState.mode != 2) {
            DayStrip(stripStart, stripDays, tasks, events)
        }
        Box(Modifier.weight(1f)) {
            Crossfade(targetState = CalState.mode, animationSpec = tween(220), label = "calMode") { m ->
                when (m) {
                    0 -> DayView(CalState.day, tasks, events, { editing = it }, { newAt = it })
                    1 -> WeekView(CalState.day, tasks, events) { d -> CalState.day = d; CalState.mode = 0 }
                    else -> ListView(tasks, events, { editing = it }, { viewing = it })
                }
            }
        }
    }

    newAt?.let { at ->
        TaskDialog("New reminder", initDue = at, onDismiss = { newAt = null }, onSave = { t, n, d, w ->
            Repo.addTask(t, n, d, "manual", work = w); newAt = null
        })
    }
    editing?.let { t ->
        TaskDialog(
            "Edit task", t.title, t.notes, t.due, image = t.image, initWork = t.work,
            onDelete = { Repo.deleteTask(t.id); editing = null },
            onDismiss = { editing = null },
            onSave = { a, b, c, w -> Repo.updateTask(t.id, a, b, c, w); editing = null }
        )
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

// ======================= Pieces =======================

@Composable
fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    val p = LocalPalette.current
    BoxWithConstraints(
        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(4.dp)
    ) {
        val w = maxWidth / labels.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.78f, stiffness = 420f), label = "seg")
        Box(Modifier.offset(x = x).width(w).height(36.dp).clip(RoundedCornerShape(16.dp)).background(p.divider))
        Row {
            labels.forEachIndexed { i, l ->
                val c by animateColorAsState(if (i == selected) p.text else p.sub, label = "segText")
                Box(
                    Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(16.dp)).clickable { if (i != selected) Feel.tick(view); onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(l, fontSize = 14.sp, color = c, fontWeight = if (i == selected) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun DayStrip(start: LocalDate, count: Int, tasks: List<Task>, events: List<CalEvent>) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    val sel = CalState.day
    val counts = remember(tasks, events) {
        val m = HashMap<LocalDate, Int>()
        tasks.forEach { t -> t.due?.let { if (!t.done) m.merge(dateOf(it), 1, Int::plus) } }
        events.forEach { e -> m.merge(dateOf(e.begin), 1, Int::plus) }
        m
    }
    val idx = ChronoUnit.DAYS.between(start, sel).toInt()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (idx - 2).coerceAtLeast(0))
    LaunchedEffect(sel) {
        val i = ChronoUnit.DAYS.between(start, sel).toInt()
        if (i in 0 until count) state.animateScrollToItem((i - 2).coerceAtLeast(0))
    }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(count) { i ->
            val d = start.plusDays(i.toLong())
            val isSel = d == sel
            val bg by animateColorAsState(if (isSel) p.accent else Color.Transparent, tween(220), label = "dayBg")
            val n = counts[d] ?: 0
            Column(
                Modifier.width(46.dp).clip(RoundedCornerShape(18.dp)).background(bg)
                    .clickable { CalState.day = d }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), fontSize = 11.sp,
                    color = if (isSel) Color(0xFFDBE9FF) else p.sub
                )
                Text(
                    d.dayOfMonth.toString(), fontSize = 17.sp,
                    color = when { isSel -> Color.White; d == today -> p.accent; else -> p.text },
                    fontWeight = if (isSel || d == today) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(Modifier.height(8.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(n.coerceAtMost(3)) {
                        Box(Modifier.size(4.dp).clip(CircleShape).background(if (isSel) Color.White else p.accent))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniCheck(done: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg by animateColorAsState(if (done) p.accent else Color.Transparent, label = "mini")
    Box(
        Modifier.size(18.dp).clip(CircleShape).background(bg)
            .then(if (done) Modifier else Modifier.border1(p.pillText))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { if (done) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
}

private fun Modifier.border1(c: Color) = this.border(2.dp, c, CircleShape)

// ======================= Day view =======================

/** Card at the top of the day: how many things, when they start and end, and a small 8:00–20:00 timeline. */
@Composable
private fun DaySummary(day: LocalDate, slots: List<Slot>, allDay: Int) {
    val p = LocalPalette.current
    val n = slots.size + allDay
    val ds = day.startMs()
    fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    Tile(dayLabel(day), Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).rise(0)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            BigNumber("$n", if (n == 1) "thing" else "things", size = 36)
            Text(
                if (slots.isEmpty()) (if (n == 0) "Nothing planned. Tap any hour to add a reminder." else "All day")
                else slots.maxOf { it.end }.let { end ->
                    "First at ${hhmm(slots.minOf { it.start })} · " + if (end >= day.plusDays(1).startMs()) "busy until late" else "free after ${hhmm(end)}"
                },
                fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
            )
        }
        if (slots.isNotEmpty()) {
            val from = ds + 8 * 3_600_000L
            val span = 12 * 3_600_000f
            BoxWithConstraints(Modifier.padding(top = 12.dp).fillMaxWidth().height(34.dp)) {
                val w = maxWidth
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp).background(p.text.copy(alpha = 0.12f)))
                slots.forEach { s ->
                    val a = ((s.start - from) / span).coerceIn(0f, 1f)
                    val b = ((s.end - from) / span).coerceIn(0f, 1f)
                    if (b > a) {
                        val c = s.event?.let { opaque(it.color) } ?: (if (s.task?.work == true) WorkColor else p.accent)
                        Box(
                            Modifier.offset(x = w * a).width((w * (b - a)).coerceAtLeast(6.dp)).fillMaxHeight()
                                .clip(RoundedCornerShape(9.dp)).background(c.copy(alpha = 0.45f))
                        )
                    }
                }
                val now = System.currentTimeMillis()
                if (day == LocalDate.now() && now in from..(from + span.toLong())) {
                    Box(Modifier.offset(x = w * ((now - from) / span) - 1.dp).width(2.dp).fillMaxHeight().background(TileColors.pink))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(8, 11, 14, 17, 20).forEach { Text("$it", fontSize = 11.sp, color = p.sub) }
            }
        }
    }
}

@Composable
private fun DayView(
    day: LocalDate, tasks: List<Task>, events: List<CalEvent>,
    onTask: (Task) -> Unit, onNew: (Long) -> Unit
) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val density = LocalDensity.current
    val hourH = 60.dp
    val ds = day.startMs()
    val de = day.plusDays(1).startMs()
    val slots = remember(day, tasks, events) { slotsFor(day, tasks, events) }
    val allDay = events.filter { it.allDay && it.begin < de && it.end > ds }
    val scroll = rememberScrollState()

    LaunchedEffect(day) {
        val hour = if (day == LocalDate.now()) LocalTime.now().hour - 1
        else slots.firstOrNull()?.let { ((it.start - ds) / 3_600_000L).toInt() - 1 } ?: 8
        scroll.animateScrollTo(with(density) { (hourH * hour.coerceIn(0, 20)).roundToPx() })
    }

    Column(Modifier.fillMaxSize()) {
        DaySummary(day, slots, allDay.size)
        if (allDay.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allDay.forEach { e ->
                    Text(
                        e.title, fontSize = 13.sp, color = p.text, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(opaque(e.color).copy(alpha = 0.25f))
                            .clickable { Cal.open(ctx, e) }.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(hourH * 24).padding(end = 12.dp)
                    .pointerInput(day) {
                        detectTapGestures { off ->
                            val h = (off.y / hourH.toPx()).toInt().coerceIn(0, 23)
                            onNew(ds + h * 3_600_000L)
                        }
                    }
            ) {
                val gutter = 56.dp
                val area = maxWidth - gutter
                for (h in 0..23) {
                    Row(Modifier.offset(y = hourH * h - 7.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "%02d:00".format(Locale.US, h), fontSize = 11.sp, color = p.sub.copy(alpha = 0.7f),
                            textAlign = TextAlign.End, modifier = Modifier.width(46.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f).height(1.dp).background(p.divider))
                    }
                }
                slots.forEachIndexed { i, s ->
                    val top = hourH * ((s.start - ds) / 3_600_000f)
                    val height = (hourH * ((s.end - s.start) / 3_600_000f)).coerceAtLeast(30.dp) - 3.dp
                    val w = area / s.lanes
                    val appear = remember(day, s.start, s.task?.id, s.event?.eventId) { Animatable(0f) }
                    LaunchedEffect(day, s.start) { delay(35L * i); appear.animateTo(1f, tween(280)) }
                    Box(
                        Modifier.offset(x = gutter + w * s.lane, y = top + 1.dp).width(w - 4.dp).height(height)
                            .graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 14f }
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (s.task != null) p.pill else opaque(s.event!!.color).copy(alpha = 0.22f)
                            )
                            .clickable { if (s.task != null) onTask(s.task) else s.event?.let { Cal.open(ctx, it) } }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        val t = s.task
                        val e = s.event
                        if (t != null) {
                            Row(verticalAlignment = Alignment.Top) {
                                MiniCheck(t.done) { Repo.setDone(t.id, !t.done) }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    t.title, fontSize = 13.sp, color = p.pillText, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    textDecoration = if (t.done) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f)
                                )
                                Text(hhmm(s.start), fontSize = 12.sp, color = p.pillText.copy(alpha = 0.8f), modifier = Modifier.padding(start = 4.dp))
                            }
                        } else if (e != null) {
                            Row(Modifier.fillMaxHeight()) {
                                Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(opaque(e.color)))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(e.title, fontSize = 13.sp, color = p.text, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${hhmm(e.begin)} \u2013 ${hhmm(e.end)}", fontSize = 12.sp, color = p.sub, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (day == LocalDate.now()) {
                    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
                    Row(
                        Modifier.offset(x = gutter - 4.dp, y = hourH * (nowMin / 60f) - 4.dp).width(area + 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF453A)))
                        Box(Modifier.weight(1f).height(2.dp).background(Color(0xFFFF453A)))
                    }
                }
            }
            Spacer(Modifier.height(130.dp))
        }
    }
}

// ======================= Week view =======================

@Composable
private fun WeekView(day: LocalDate, tasks: List<Task>, events: List<CalEvent>, onPickDay: (LocalDate) -> Unit) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val locale = Locale.getDefault()
    val today = LocalDate.now()
    val start = day.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))
    val week = (0..6).map { start.plusDays(it.toLong()) }
    val hourH = 30.dp
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (hourH * 7).roundToPx() }) }
    val fmt = DateTimeFormatter.ofPattern("d MMM", locale)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { CalState.day = day.minusWeeks(1) }) { Icon(Icons.Default.KeyboardArrowLeft, "Previous week", tint = p.text) }
            Text(
                "${start.format(fmt)} \u2013 ${start.plusDays(6).format(fmt)}", fontSize = 15.sp, color = p.text,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { CalState.day = day.plusWeeks(1) }) { Icon(Icons.Default.KeyboardArrowRight, "Next week", tint = p.text) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 10.dp)) {
            week.forEach { d ->
                val isSel = d == day
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onPickDay(d) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), fontSize = 11.sp, color = if (d == today) p.accent else p.sub)
                    Box(
                        Modifier.padding(top = 2.dp).size(28.dp).clip(CircleShape).background(if (isSel) p.accent else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            d.dayOfMonth.toString(), fontSize = 14.sp,
                            color = when { isSel -> Color.White; d == today -> p.accent; else -> p.text }
                        )
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(hourH * 24).padding(end = 10.dp, top = 6.dp)) {
                val gutter = 40.dp
                val colW = (maxWidth - gutter) / 7
                for (h in 0..23 step 2) {
                    Row(Modifier.offset(y = hourH * h - 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("$h", fontSize = 11.sp, color = p.sub.copy(alpha = 0.7f), textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f).height(1.dp).background(p.divider))
                    }
                }
                week.forEachIndexed { i, d ->
                    Box(
                        Modifier.offset(x = gutter + colW * i).width(colW).fillMaxHeight()
                            .background(if (d == today) p.accent.copy(alpha = 0.06f) else Color.Transparent)
                            .clickable(remember { MutableInteractionSource() }, null) { onPickDay(d) }
                    )
                    val ds = d.startMs()
                    slotsFor(d, tasks, events).forEach { s ->
                        val w = colW / s.lanes
                        Box(
                            Modifier.offset(x = gutter + colW * i + w * s.lane + 1.dp, y = hourH * ((s.start - ds) / 3_600_000f))
                                .width(w - 2.dp)
                                .height((hourH * ((s.end - s.start) / 3_600_000f)).coerceAtLeast(16.dp) - 2.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (s.task != null) p.accent else opaque(s.event!!.color))
                                .clickable { onPickDay(d) }
                        )
                    }
                }
                if (today in week) {
                    val i = week.indexOf(today)
                    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
                    Box(
                        Modifier.offset(x = gutter + colW * i, y = hourH * (nowMin / 60f) - 1.dp).width(colW).height(2.dp)
                            .background(Color(0xFFFF453A))
                    )
                }
            }
            Row(Modifier.padding(start = 20.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(p.accent))
                Text("Your reminders", fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(start = 6.dp, end = 16.dp))
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1D9E75)))
                Text("Samsung Calendar", fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(start = 6.dp))
            }
            Text("Tap a day to open it", fontSize = 12.sp, color = p.sub.copy(alpha = 0.7f), modifier = Modifier.padding(start = 20.dp, top = 6.dp))
            Spacer(Modifier.height(130.dp))
        }
    }
}

// ======================= List view =======================

@Composable
private fun ListView(tasks: List<Task>, events: List<CalEvent>, onTask: (Task) -> Unit, onImage: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    var showDone by remember { mutableStateOf(false) }
    val overdue = tasks.filter { !it.done && it.due != null && it.due < now }
    val anytime = tasks.filter { !it.done && it.due == null }
    val done = tasks.filter { it.done }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 150.dp)) {
        if (overdue.isNotEmpty()) {
            item(key = "ov-l") { SectionLabel("Overdue") }
            item(key = "ov") {
                GroupBox { overdue.forEachIndexed { i, t -> if (i > 0) GroupDivider(start = 58.dp); TaskRow(t, now, { onTask(t) }, onImage) } }
            }
        }
        for (i in 0..30) {
            val d = today.plusDays(i.toLong())
            val ds = d.startMs()
            val de = d.plusDays(1).startMs()
            val dayTasks = tasks.filter { !it.done && it.due != null && it.due >= maxOf(ds, now) && it.due < de }
            val dayEvents = events.filter { (it.allDay && it.begin < de && it.end > ds) || (!it.allDay && it.begin >= ds && it.begin < de) }
            if (dayTasks.isEmpty() && dayEvents.isEmpty()) continue
            val rows: List<Pair<Long, Any>> = (dayTasks.map { (it.due ?: 0L) to it } + dayEvents.map { (if (it.allDay) ds else it.begin) to it })
                .sortedBy { it.first }
            item(key = "d$i-l") {
                SectionLabel(
                    when (i) {
                        0 -> "Today"
                        1 -> "Tomorrow"
                        else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
                    }
                )
            }
            item(key = "d$i") {
                GroupBox {
                    rows.forEachIndexed { j, (_, x) ->
                        if (j > 0) GroupDivider(start = 58.dp)
                        when (x) {
                            is Task -> TaskRow(x, now, { onTask(x) }, onImage)
                            is CalEvent -> EventRow(x)
                        }
                    }
                }
            }
        }
        if (anytime.isNotEmpty()) {
            item(key = "any-l") { SectionLabel("Anytime") }
            item(key = "any") {
                GroupBox { anytime.forEachIndexed { i, t -> if (i > 0) GroupDivider(start = 58.dp); TaskRow(t, now, { onTask(t) }, onImage) } }
            }
        }
        if (done.isNotEmpty()) {
            item(key = "done") {
                Column {
                    SectionLabel("Completed")
                    GroupBox(Modifier.animateContentSize()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showDone = !showDone }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${done.size} completed", fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
                            Icon(if (showDone) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = p.sub)
                        }
                        if (showDone) done.forEach { t -> GroupDivider(start = 58.dp); TaskRow(t, now, { onTask(t) }, onImage) }
                    }
                }
            }
        }
        if (overdue.isEmpty() && anytime.isEmpty() && tasks.none { !it.done } && events.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.padding(top = 12.dp)) {
                    EmptyState(Icons.Default.DateRange, "Your calendar is clear", "Tap + to add a reminder, or connect Samsung Calendar.")
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: CalEvent) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable { Cal.open(ctx, e) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(opaque(e.color).copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(opaque(e.color)))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontSize = 16.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (e.allDay) "All day" else "${hhmm(e.begin)} \u2013 ${hhmm(e.end)}",
                fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}
