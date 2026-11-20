package com.anas.secretary.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.data.Repo
import com.anas.secretary.logic.HealthSnap
import com.anas.secretary.logic.HealthSummary
import com.anas.secretary.logic.SleepNight
import com.anas.secretary.util.Health
import com.anas.secretary.util.SamsungHealth
import com.anas.secretary.util.Prefs
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

// ======================= Health (Samsung Health through Health Connect) =======================

object HealthColors {
    val deep = Color(0xFF5B5BD6)
    val light = Color(0xFF8FB3FF)
    val rem = Color(0xFFC084FC)
    val awake = Color(0xFFF2A516)
    val steps = Color(0xFF34C759)
    val heart = Color(0xFFFF5A72)
    val night = Color(0xFF23234A)

    fun stage(kind: String) = when (kind) { "deep" -> deep; "rem" -> rem; "awake" -> awake; else -> light }
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm")
private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)
private fun count(n: Long) = NumberFormat.getIntegerInstance(Locale.US).format(n)

/** The Health section in Track. */
@Composable
fun HealthScreen(tick: Int, extra: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val hv by Health.version.collectAsState()
    var snap by remember { mutableStateOf(Health.cached(ctx)) }
    var state by remember { mutableStateOf<Health.State?>(null) }
    var loading by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<Health.Source>>(emptyList()) }
    val shAvail = remember { SamsungHealth.available(ctx) }
    var shConnected by remember { mutableStateOf(false) }
    var shError by remember { mutableStateOf<String?>(null) }
    var shBusy by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        state = try { Health.state(ctx) } catch (_: Exception) { Health.State.Unavailable }
        snap = Health.refresh(ctx)
        shConnected = shAvail && SamsungHealth.connected(ctx)
        shError = SamsungHealth.lastError
        // Only worth checking when something is missing (sleep, heart or workouts)
        val s0 = snap
        val gap = s0 == null || s0.nights.isEmpty() || s0.restingHr.isEmpty() || s0.workouts.isEmpty()
        if (state == Health.State.Connected && gap && sources.isEmpty()) sources = try { Health.sources(ctx) } catch (_: Exception) { emptyList() }
        loading = false
    }
    val ask = rememberLauncherForActivityResult(Health.permissionContract()) { scope.launch { reload() } }
    LaunchedEffect(tick) { reload() }
    LaunchedEffect(hv) { snap = Health.cached(ctx) }

    val s = snap
    val subtitle = when {
        state == Health.State.Connected && s != null ->
            (if (shConnected && shError == null) "From Samsung Health and Health Connect" else "From your watch") + " · updated ${clock(s.updated)}"
        state == Health.State.Connected -> "Connected"
        else -> "Sleep, steps and heart from your watch"
    }
    OneUiScreen("Health", subtitle, extra = extra, page = "Track") {
        when (state) {
            null -> {}
            Health.State.Unavailable -> item(key = "na") {
                EmptyState(Icons.Default.Warning, "Health Connect isn't on this phone",
                    "It comes with Android 14 and newer. On older phones, install Health Connect from the Play Store.")
            }
            Health.State.NeedsUpdate -> item(key = "upd") {
                SetupCard(
                    "Update Health Connect", "It needs an update before Daybrief can read your health data.",
                    "Open Health Connect", steps = emptyList()
                ) { Health.openHealthConnect(ctx) }
            }
            Health.State.NotConnected -> item(key = "setup") {
                SetupCard(
                    "Connect your watch", "Your health data stays on this phone.", "Connect",
                    steps = listOf(
                        "In Samsung Health → Settings → Health Connect, allow it to share sleep, steps, heart rate and exercise.",
                        "Tap Connect and allow Daybrief to read them."
                    ),
                    secondary = "Open Samsung Health" to { Health.openSamsungHealth(ctx) }
                ) { ask.launch(Health.permissions) }
            }
            Health.State.Connected -> {
                val s1 = snap
                val missing = s1 == null || s1.nights.isEmpty() || s1.restingHr.isEmpty()
                if (shAvail && missing && (!shConnected || shError != null)) item(key = "samsung") {
                    SetupCard(
                        if (shConnected) "Samsung Health didn't answer" else "Read straight from Samsung Health",
                        if (shError != null) "Last try: $shError"
                        else "Sleep and heart rate aren't reaching Health Connect. Daybrief can read them from Samsung Health itself, on this phone.",
                        if (shBusy) "Connecting…" else if (shConnected) "Try again" else "Connect Samsung Health",
                        steps = listOf(
                            "In Samsung Health → ⋮ → Settings → About Samsung Health, tap the version number about 10 times.",
                            "Open Developer mode (Samsung Health Data SDK) and turn on Developer Mode for Data Read.",
                            "Come back and tap the button, then allow sleep, heart rate and exercise."
                        ),
                        secondary = "Open Samsung Health" to { Health.openSamsungHealth(ctx) }
                    ) {
                        val act = ctx.findActivity()
                        if (act != null && !shBusy) {
                            shBusy = true
                            scope.launch {
                            try {
                                if (shConnected || SamsungHealth.connect(act)) reload() else shError = SamsungHealth.lastError
                            } finally { shBusy = false }
                            }
                        }
                    }
                }
                if (s == null || (s.nights.isEmpty() && s.stepsToday == 0L && s.restingHr.isEmpty())) {
                    item(key = "nodata") {
                        SetupCard(
                            if (loading) "Reading your data…" else "Connected, but no data yet",
                            "If this stays empty, Samsung Health isn't sharing yet.", "Open Samsung Health",
                            steps = listOf("In Samsung Health → Settings → Health Connect, turn on sharing for sleep, steps, heart rate and exercise.")
                        ) { Health.openSamsungHealth(ctx) }
                    }
                    if (sources.isNotEmpty()) item(key = "sources") { SourcesCard(sources) }
                } else {
                    if (s.nights.isNotEmpty()) item(key = "sleep") { SleepWeekCard(s.nights) }
                    item(key = "row") {
                        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StepsCard(s.stepsToday, Modifier.weight(1f))
                            HeartCard(s.restingHr.map { it.second }, Modifier.weight(1f))
                        }
                    }
                    if (s.workouts.isNotEmpty()) item(key = "workouts") { WorkoutsCard(s) }
                    val notes = buildList {
                        HealthSummary.lastVsAverage(s.nights)?.let { d ->
                            if (kotlin.math.abs(d) >= 15 * 60_000) add(
                                if (d < 0) "Last night you slept ${HealthSummary.dur(-d)} less than your average." to false
                                else "Last night you slept ${HealthSummary.dur(d)} more than your average." to true
                            )
                        }
                        HealthSummary.insights(s).forEach { add(it to it.contains("longer")) }
                    }
                    if (notes.isNotEmpty()) item(key = "noticed") { NoticedCard(notes) }
                    if (sources.isNotEmpty()) item(key = "sources") { SourcesCard(sources) }
                    item(key = "manage") {
                        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                if (Prefs.weekOn) TextAction("Your week") { WeekState.show = true }
                                TextAction("Health Connect settings", p.sub) { Health.openHealthConnect(ctx) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    title: String, body: String, button: String, steps: List<String>,
    secondary: Pair<String, () -> Unit>? = null, onClick: () -> Unit
) {
    val p = LocalPalette.current
    GroupBox(Modifier.padding(bottom = 10.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(p.pill), contentAlignment = Alignment.Center) {
                    Text("⌚", fontSize = 18.sp)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                    Text(body, fontSize = 13.sp, color = p.sub)
                }
            }
            steps.forEachIndexed { i, st ->
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(p.field), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", fontSize = 12.sp, color = p.text)
                    }
                    Text(st, fontSize = 14.sp, color = p.text, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Box(
                Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(p.accent).clickable(onClick = onClick)
                    .padding(vertical = 13.dp), contentAlignment = Alignment.Center
            ) { Text(button, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            secondary?.let { (label, act) ->
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) { TextAction(label, onClick = act) }
            }
        }
    }
}

/** Deep / light / REM / awake as one rounded bar, in the order they happened. */
@Composable
fun StageBar(n: SleepNight, height: Dp = 12.dp) {
    Row(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val parts = if (n.stages.isEmpty()) listOf("light" to (n.end - n.start)) else mergeStages(n.stages)
        parts.forEach { (kind, ms) ->
            if (ms > 0) Box(Modifier.weight(ms.toFloat()).fillMaxHeight().background(HealthColors.stage(kind)))
        }
    }
}

/** Joins neighbouring pieces of the same stage and drops tiny slivers, so the bar stays readable. */
private fun mergeStages(stages: List<Pair<String, Long>>): List<Pair<String, Long>> {
    val total = stages.sumOf { it.second }.coerceAtLeast(1)
    val out = mutableListOf<Pair<String, Long>>()
    stages.forEach { (k, ms) ->
        val last = out.lastOrNull()
        if (last != null && (last.first == k || ms < total / 60)) out[out.size - 1] = last.first to last.second + ms else out += k to ms
    }
    return out
}

@Composable
fun StageLegend(n: SleepNight) {
    val p = LocalPalette.current
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val items = if (n.hasStages) listOf("Deep" to (HealthColors.deep to n.deep), "Light" to (HealthColors.light to n.light),
            "REM" to (HealthColors.rem to n.rem), "Awake" to (HealthColors.awake to n.awake))
        else listOf("Asleep" to (HealthColors.light to n.asleep))
        items.forEach { (label, cv) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(3.dp)).background(cv.first))
                Text(" $label ${HealthSummary.dur(cv.second)}", fontSize = 12.sp, color = p.sub)
            }
        }
    }
}

@Composable
private fun SleepWeekCard(nights: List<SleepNight>) {
    val p = LocalPalette.current
    var sel by remember(nights) { mutableIntStateOf(nights.lastIndex) }
    val avg = nights.map { it.asleep }.average().toLong()
    val n = nights[sel.coerceIn(0, nights.lastIndex)]
    val day = n.wakeDate().dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.US)
    GroupBox(Modifier.padding(bottom = 10.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(HealthColors.night), contentAlignment = Alignment.Center) {
                    Icon(HealthIcons.Moon, null, tint = HealthColors.light, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("Sleep", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                    Text("Avg ${HealthSummary.dur(avg)} · tap a night", fontSize = 12.sp, color = p.sub)
                }
                n.score?.let { sc -> ScoreRing(sc) }
            }
            Text(
                "$day · ${HealthSummary.dur(n.asleep)}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = p.text,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text("${clock(n.start)} → ${clock(n.end)}", fontSize = 13.sp, color = p.sub)
            Box(Modifier.padding(top = 10.dp)) { StageBar(n) }
            StageLegend(n)
            // The week: one bar per night, the dashed line is 8 hours
            val maxMs = maxOf(nights.maxOf { it.asleep }, 9 * 3_600_000L).toFloat()
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(120.dp)) {
                Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                    val y = size.height * (1f - 8 * 3_600_000L / maxMs)
                    drawLine(p.sub.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    nights.forEachIndexed { i, night ->
                        val on = i == sel
                        val h by animateDpAsState((96 * night.asleep / maxMs).dp, spring(dampingRatio = 0.7f), label = "bar")
                        val c by animateColorAsState(if (on) HealthColors.deep else HealthColors.deep.copy(alpha = 0.35f), tween(250), label = "barC")
                        Column(Modifier.weight(1f).clickable { sel = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(8.dp)).background(c))
                            Text(
                                night.wakeDate().dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.US),
                                fontSize = 11.sp, color = if (on) p.text else p.sub, modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepsCard(steps: Long, modifier: Modifier) {
    val p = LocalPalette.current
    val f by animateFloatAsState((steps / HealthSummary.STEP_GOAL.toFloat()).coerceIn(0f, 1f), tween(900), label = "steps")
    GroupBox(modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Steps today", fontSize = 13.sp, color = p.sub)
            Box(Modifier.padding(vertical = 8.dp).size(78.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 8.dp.toPx()
                    drawArc(p.field, 0f, 360f, false, style = Stroke(sw))
                    drawArc(HealthColors.steps, -90f, 360f * f, false, style = Stroke(sw, cap = StrokeCap.Round))
                }
                Text(count(steps), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            }
            Text("of ${count(HealthSummary.STEP_GOAL)}", fontSize = 12.sp, color = p.sub)
        }
    }
}

@Composable
private fun HeartCard(bpm: List<Long>, modifier: Modifier) {
    val p = LocalPalette.current
    GroupBox(modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Resting heart", fontSize = 13.sp, color = p.sub)
            if (bpm.isEmpty()) {
                Text("No readings yet", fontSize = 14.sp, color = p.text, modifier = Modifier.padding(top = 10.dp))
            } else {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                    Text("${bpm.last()}", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                    Text(" bpm", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(bottom = 4.dp))
                }
                if (bpm.size >= 2) Canvas(Modifier.padding(top = 8.dp).fillMaxWidth().height(40.dp)) {
                    val lo = bpm.min() - 3f; val hi = bpm.max() + 3f
                    val path = Path()
                    bpm.forEachIndexed { i, v ->
                        val x = size.width * i / (bpm.size - 1)
                        val y = size.height * (1f - (v - lo) / (hi - lo))
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, HealthColors.heart, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                }
                val spread = bpm.max() - bpm.min()
                Text("${bpm.size} days · ${if (spread <= 4) "steady" else "changing"}", fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun WorkoutsCard(s: HealthSnap) {
    val p = LocalPalette.current
    GroupBox(Modifier.padding(bottom = 10.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Workouts", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            s.workouts.take(5).forEachIndexed { i, w ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(p.field), contentAlignment = Alignment.Center) {
                        Icon(HealthIcons.forKind(w.kind), null, tint = HealthColors.steps, modifier = Modifier.size(17.dp))
                    }
                    Text(w.title ?: w.kind, fontSize = 14.sp, color = p.text, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    val d = Instant.ofEpochMilli(w.start).atZone(ZoneId.systemDefault()).toLocalDate()
                    val dayTxt = if (d == LocalDate.now()) "Today" else d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.US)
                    Text("$dayTxt · ${w.minutes} min", fontSize = 12.sp, color = p.sub)
                }
            }
        }
    }
}

/** What Health Connect actually has (last 30 days), so missing cards explain themselves. */
@Composable
private fun SourcesCard(list: List<Health.Source>) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    // Resting heart rate is optional (worked out from heart rate) and you may simply not have worked out
    val missing = list.filter { it.count == 0 && it.label != "Resting heart rate" && it.label != "Exercise" }
    GroupBox(Modifier.padding(bottom = 10.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("What reaches Daybrief", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Text("Last 30 days in Health Connect", fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(bottom = 6.dp))
            list.forEachIndexed { i, src ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ok = src.count > 0
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) HealthColors.steps else if (src.count < 0) HealthColors.heart else p.sub))
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(src.label, fontSize = 14.sp, color = p.text)
                        val sub = when {
                            src.count < 0 -> "Couldn't read (${src.apps.firstOrNull() ?: "error"})"
                            src.count == 0 -> "Nothing shared"
                            else -> src.apps.joinToString(", ")
                        }
                        Text(sub, fontSize = 12.sp, color = p.sub)
                    }
                    if (src.count > 0) Column(horizontalAlignment = Alignment.End) {
                        Text(if (src.count >= Health.PEEK) "${Health.PEEK}+" else count(src.count.toLong()), fontSize = 13.sp, color = p.text)
                        src.latest?.let { Text(ago(it), fontSize = 12.sp, color = p.sub) }
                    }
                }
            }
            if (missing.isNotEmpty()) {
                Text(
                    "${missing.joinToString(", ") { it.label.lowercase() }} isn't reaching Health Connect. In Samsung Health → ⋮ → Settings → Health Connect, " +
                        "allow Samsung Health to write them. New data comes in after the next sync with your watch.",
                    fontSize = 13.sp, color = p.text, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.field).padding(horizontal = 12.dp, vertical = 10.dp)
                )
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    TextAction("Open Samsung Health", p.accent) { Health.openSamsungHealth(ctx) }
                }
            }
        }
    }
}

private fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 60 -> "${m.coerceAtLeast(0)} min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} days ago"
    }
}

@Composable
private fun NoticedCard(notes: List<Pair<String, Boolean>>) {
    val p = LocalPalette.current
    GroupBox(Modifier.padding(bottom = 10.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Noticed this week", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            notes.forEach { (t, good) ->
                Text(
                    t, fontSize = 13.sp, color = if (good) HealthColors.steps else p.text, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.field).padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

// ---------- Brief: last night, in the morning ----------

/** Shown in the morning (4 AM–2 PM) until you press one of its buttons. */
fun sleepCardFor(s: HealthSnap?): SleepNight? {
    val n = s?.lastNight ?: return null
    val now = System.currentTimeMillis()
    val hour = java.time.LocalTime.now().hour
    if (hour !in 4..13 || now - n.end > 14 * 3_600_000L) return null
    if (Prefs.sleepCardDay == LocalDate.now().toEpochDay()) return null
    return n
}

@Composable
fun BriefSleepCard(s: HealthSnap, n: SleepNight, onDetails: () -> Unit, onDone: (String) -> Unit) {
    val p = LocalPalette.current
    val diff = HealthSummary.lastVsAverage(s.nights)
    GroupBox(Modifier.padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(HealthColors.night), contentAlignment = Alignment.Center) {
                    Icon(HealthIcons.Moon, null, tint = HealthColors.light, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Last night", fontSize = 13.sp, color = p.sub)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(HealthSummary.dur(n.asleep), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                        Text("  ${clock(n.start)} → ${clock(n.end)}", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            Box(Modifier.padding(top = 12.dp)) { StageBar(n) }
            StageLegend(n)
            val note = when {
                diff == null -> null
                diff <= -15 * 60_000 -> "${HealthSummary.dur(-diff)} less than your average. Head to bed a bit earlier tonight?" to false
                diff >= 15 * 60_000 -> "${HealthSummary.dur(diff)} more than your average. Nice." to true
                else -> "About your usual." to true
            }
            note?.let { (t, good) ->
                Text(
                    t, fontSize = 13.sp, color = p.text, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (good) HealthColors.steps.copy(alpha = 0.14f) else HealthColors.awake.copy(alpha = 0.16f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val remind = note != null && !note.second
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (remind) p.accent else p.field)
                        .clickable {
                            Prefs.sleepCardDay = LocalDate.now().toEpochDay()
                            if (remind) {
                                val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                Repo.addTask("Time to sleep", "Last night: ${HealthSummary.dur(n.asleep)}", midnight, "health")
                                onDone("Reminder set for 12:00 AM")
                            } else onDone("OK")
                        }.padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) { Text(if (remind) "Remind me at 12" else "Got it", color = if (remind) Color.White else p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(p.field).clickable(onClick = onDetails).padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Details", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/** Health Connect shows this when you ask why Secretary wants your health data. */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SecretaryTheme {
                val p = LocalPalette.current
                Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().padding(24.dp)) {
                    Text("Your health data", fontSize = 30.sp, color = p.text, modifier = Modifier.padding(top = 40.dp))
                    Text(
                        "Daybrief reads your sleep, steps, heart rate and workouts from Health Connect to show them in the app and widgets, " +
                            "and to notice patterns like short nights.\n\nIt only reads. Nothing is changed, and nothing leaves this phone.",
                        fontSize = 16.sp, color = p.text, lineHeight = 23.sp, modifier = Modifier.padding(top = 16.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(p.accent).clickable { finish() }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("OK", color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/** Samsung Health's sleep score in a small ring, with its word (Good, Fair...). */
@Composable
private fun ScoreRing(score: Int) {
    val p = LocalPalette.current
    val purple = Color(0xFFA97CFF)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(52.dp)) {
                val w = 5.dp.toPx()
                val st = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val inset = w / 2
                val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                drawArc(p.sub.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), sz, style = st)
                drawArc(purple, -90f, 360f * score.coerceIn(0, 100) / 100f, false, Offset(inset, inset), sz, style = st)
            }
            Text("$score", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = p.text)
        }
        Text(com.anas.secretary.logic.DayPlan.scoreWord(score), fontSize = 11.sp, color = p.sub)
    }
}
