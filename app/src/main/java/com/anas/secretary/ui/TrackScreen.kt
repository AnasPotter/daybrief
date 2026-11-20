package com.anas.secretary.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.anas.secretary.data.Repo
import com.anas.secretary.data.TCard
import com.anas.secretary.data.TLog
import com.anas.secretary.data.TState
import com.anas.secretary.data.Tracker
import com.anas.secretary.util.DAY
import com.anas.secretary.util.Fmt
import com.anas.secretary.util.HOUR
import com.anas.secretary.util.Images
import com.anas.secretary.util.Insight
import com.anas.secretary.util.Template
import com.anas.secretary.util.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TrackState {
    var seg by mutableIntStateOf(0) // 0 = Things, 1 = People, 2 = Health, 3 = Timeline
    var addRequest by mutableIntStateOf(0)
}

@Composable
fun TrackScreen(v: Int, tick: Int) {
    val all = listOf(LookIcons.Things to "Things", LookIcons.People to "People", LookIcons.Heart to "Health", LookIcons.Clock to "Timeline")
    val segs = com.anas.secretary.util.Features.trackSegs().ifEmpty { listOf(0) }
    if (TrackState.seg !in segs) TrackState.seg = segs.first()
    val seg: @Composable () -> Unit = {
        if (segs.size > 1) IconSwitcher(segs.map { all[it] }, segs.indexOf(TrackState.seg)) { TrackState.seg = segs[it] }
    }
    when (TrackState.seg) {
        0 -> ThingsScreen(v, seg)
        1 -> PeopleScreen(v, tick, seg)
        2 -> HealthScreen(tick, seg)
        else -> TimelineScreen(v, seg)
    }
}

// ======================= shared bits =======================

@Composable
fun kindColors(kind: String): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    val p = LocalPalette.current
    return when (kind) {
        "good" -> if (dark) Color(0xFF1F3A2C) to Color(0xFFBFE8CF) else Color(0xFFDDF3E4) to Color(0xFF145A32)
        "bad" -> if (dark) Color(0xFF4A1F22) to Color(0xFFFFB3B3) else Color(0xFFFBE1E1) to Color(0xFF9B1C1C)
        "warning" -> if (dark) Color(0xFF3D3015) to Color(0xFFF5CF7A) else Color(0xFFFBEFD5) to Color(0xFF7A5300)
        else -> p.field to p.text
    }
}

@Composable
fun EmojiIcon(emoji: String, color: Int, size: Int = 32) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(Color(color).copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) { Text(emoji, fontSize = (size * 0.5f).sp) }
}

@Composable
private fun StateButton(state: TState, onClick: () -> Unit) {
    val (bg, fg) = kindColors(state.kind)
    val bgA by animateColorAsState(bg, tween(300), label = "stBg")
    val fgA by animateColorAsState(fg, tween(300), label = "stFg")
    val scale = remember { Animatable(1f) }
    val view = LocalView.current
    LaunchedEffect(state.name) {
        scale.snapTo(0.86f)
        scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
    }
    Box(
        Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .widthIn(min = 96.dp).clip(RoundedCornerShape(50)).background(bgA)
            .clickable { Feel.confirm(view); onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(state.name, fontSize = 14.sp, color = fgA, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
private fun StatePill(name: String, kind: String) {
    val (bg, fg) = kindColors(kind)
    Text(
        name, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

private fun nextIdx(t: Tracker) = if (t.states.size <= 1) 0 else (t.idx + 1) % t.states.size

// ======================= Things =======================

@Composable
private fun ThingsScreen(v: Int, extra: @Composable () -> Unit) {
    val p = LocalPalette.current
    val cards = remember(v) { Repo.cards() }
    val trackers = remember(v) { Repo.trackers() }
    val insights = remember(v) { trackers.associate { it.id to Track.insight(it, Repo.logs(it.id)) } }
    var newCard by remember { mutableStateOf(false) }
    var editCard by remember { mutableStateOf<TCard?>(null) }
    var newTrackerFor by remember { mutableStateOf<TCard?>(null) }
    var open by remember { mutableStateOf<Long?>(null) }
    var undo by remember { mutableStateOf<UndoInfo?>(null) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Track.applyAuto() } }
    LaunchedEffect(TrackState.addRequest) { if (TrackState.addRequest > 0) { TrackState.addRequest = 0; newCard = true } }
    LaunchedEffect(undo) { if (undo != null) { delay(3500); undo = null } }

    val attention = trackers.count { t -> insights[t.id]?.let { Track.needsAttention(t, it) } == true }
    fun tap(t: Tracker) {
        val st = t.states.getOrElse(nextIdx(t)) { t.state }
        val u = Track.tap(t)
        undo = UndoInfo("${t.name}: ${st.name}", u)
    }

    Box(Modifier.fillMaxSize()) {
        OneUiScreen(
            page = "Track", title = "Things", subtitle =
            when { trackers.isEmpty() -> "Know when you last did anything"; attention == 0 -> "Everything is up to date"; attention == 1 -> "1 thing needs attention"; else -> "$attention things need attention" },
            extra = extra
        ) {
            if (cards.isEmpty()) {
                item(key = "empty") {
                    Column {
                        EmptyState(
                            Icons.Default.List, "Track anything",
                            "Watering plants, changing the car oil, your vitamins. Tap once when you do it, and always know how long ago it was."
                        )
                        SectionLabel("Start with")
                        Track.templates.filter { it.trackers.isNotEmpty() }.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { tpl ->
                                    Column(
                                        Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(p.card)
                                            .clickable {
                                                val id = Repo.addCard(tpl.name, tpl.emoji, tpl.color)
                                                tpl.trackers.forEach { (n, s, a) -> Repo.addTracker(id, n, s, a) }
                                            }.padding(vertical = 14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(tpl.emoji, fontSize = 26.sp)
                                        Text(tpl.name, fontSize = 13.sp, color = p.text, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            cards.forEachIndexed { ci, c ->
                item(key = "c${c.id}") {
                    Box(Modifier.rise(ci.coerceAtMost(4))) {
                        CardBox(
                            c, trackers.filter { it.cardId == c.id }, insights,
                            onTap = { tap(it) }, onOpen = { open = it.id }, onEdit = { editCard = c }, onAdd = { newTrackerFor = c }
                        )
                    }
                }
            }
            if (cards.isNotEmpty()) {
                item(key = "hint") {
                    Text(
                        "Tap a state to update it. Tap a name for history and insights.",
                        fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(start = 20.dp, top = 4.dp)
                    )
                }
            }
        }
        UndoPill(undo, Modifier.align(Alignment.BottomCenter)) { undo = null }
    }

    if (newCard) CardDialog(null) { newCard = false }
    editCard?.let { c -> CardDialog(c) { editCard = null } }
    newTrackerFor?.let { c -> TrackerDialog(c.id, null) { newTrackerFor = null } }
    open?.let { id -> TrackerSheet(id, v) { open = null } }
}

@Composable
private fun CardBox(
    c: TCard, list: List<Tracker>, insights: Map<Long, Insight>,
    onTap: (Tracker) -> Unit, onOpen: (Tracker) -> Unit, onEdit: () -> Unit, onAdd: () -> Unit
) {
    val p = LocalPalette.current
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxWidth().padding(bottom = 11.dp).clip(RoundedCornerShape(28.dp)).background(p.card)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Color(c.color)),
                contentAlignment = Alignment.Center
            ) { Text(c.emoji, fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.weight(1f))
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add tracker", tint = p.sub) }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit card", tint = p.sub) }
        }
        if (list.isEmpty()) {
            Text("Tap + to add something to track", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(start = 16.dp, bottom = 14.dp, top = 4.dp))
        }
        list.forEachIndexed { i, t -> key(t.id) {
            if (i > 0) GroupDivider(start = 16.dp)
            val ins = insights[t.id]
            val alert = ins != null && Track.needsAttention(t, ins)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(t) }.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Track.ago(t.since) + (ins?.usual?.let { " · ${if (alert) "usually " else ""}${Track.every(it)}" } ?: ""),
                        fontSize = 12.sp, color = if (alert) p.alert else p.sub, maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    // Fills up as the usual gap since last time runs out
                    ins?.usual?.takeIf { it > 0 }?.let { usual ->
                        val f = ((now - t.since).toFloat() / usual).coerceIn(0f, 1f)
                        val fill by animateFloatAsState(f, tween(700), label = "due")
                        val color = when { f >= 1f -> TileColors.pink; f >= 0.75f -> TileColors.yellow; else -> TileColors.green }
                        Box(Modifier.padding(top = 8.dp, end = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.text.copy(alpha = 0.1f))) {
                            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                StateButton(t.state) { onTap(t) }
            }
        } }
        Spacer(Modifier.height(6.dp))
    }
}

// ======================= Tracker details =======================

@Composable
private fun Tile(label: String, value: String, color: Color? = null, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.field).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, fontSize = 12.sp, color = p.sub)
        Text(value, fontSize = 14.sp, color = color ?: p.text, modifier = Modifier.padding(top = 2.dp), maxLines = 2)
    }
}

@Composable
private fun TrackerSheet(id: Long, v: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val t = remember(v) { Repo.tracker(id) }
    if (t == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val card = remember(v) { Repo.cards().firstOrNull { it.id == t.cardId } }
    val logs = remember(v) { Repo.logs(id) }
    val ins = remember(v) { Track.insight(t, logs) }
    val now = System.currentTimeMillis()
    var editing by remember { mutableStateOf(false) }
    var newLog by remember { mutableStateOf(false) }
    var editLog by remember { mutableStateOf<TLog?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.navigationBarsPadding().padding(10.dp).fillMaxWidth().fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(32.dp)).background(p.card)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .verticalScroll(rememberScrollState()).padding(20.dp)
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(p.divider))
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    card?.let { EmojiIcon(it.emoji, it.color, 44) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card?.name ?: "", fontSize = 13.sp, color = p.sub)
                        Text(t.name, fontSize = 22.sp, color = p.text)
                    }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit", tint = p.sub) }
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatePill(t.state.name, t.state.kind)
                    Text("  for ${Track.dur(now - t.since)}", fontSize = 14.sp, color = p.sub)
                }

                // Insights
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Usually", ins.usual?.let { Track.every(it) } ?: "Not enough history yet", modifier = Modifier.weight(1f))
                    Tile(
                        "Status",
                        when (ins.onTrack) { true -> "On track"; false -> "Overdue"; null -> "Learning" },
                        when (ins.onTrack) { true -> p.success; false -> p.alert; null -> null },
                        Modifier.weight(1f)
                    )
                    Tile("Next", ins.next?.let { if (it < now) "Now" else Fmt.whenText(it) } ?: "\u2014", modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Last 30 days", "${ins.count30} times", modifier = Modifier.weight(1f))
                    Tile("Longest gap", ins.longestGap?.let { Track.dur(it) } ?: "\u2014", modifier = Modifier.weight(1f))
                    Tile("Usually done", ins.typicalTime ?: "\u2014", modifier = Modifier.weight(1f))
                }
                ins.typicalDay?.let { Text("Most often on $it", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
                if (ins.amounts.size >= 2) {
                    val max = ins.amounts.maxOrNull() ?: 1.0
                    Text("Amounts", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    Row(
                        Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(16.dp)).background(p.field).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom
                    ) {
                        ins.amounts.forEach { a ->
                            Box(Modifier.weight(1f).fillMaxHeight((a / max).toFloat().coerceIn(0.08f, 1f)).clip(RoundedCornerShape(4.dp)).background(p.accent))
                        }
                    }
                    val first = ins.amounts.first(); val last = ins.amounts.last()
                    Text(
                        "Latest ${fmtNum(last)} ${ins.unit}".trim() + if (last != first) ", ${if (last > first) "up" else "down"} from ${fmtNum(first)}" else "",
                        fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }

                // Update
                Text("Set the state", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    t.states.forEachIndexed { i, st ->
                        val (bg, fg) = kindColors(st.kind)
                        Text(
                            st.name, fontSize = 14.sp, color = fg, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
                                .then(if (i == t.idx) Modifier.border(2.dp, fg, RoundedCornerShape(50)) else Modifier)
                                .clickable { Track.tap(t, toIdx = i); Toast.makeText(ctx, "${t.name}: ${st.name}", Toast.LENGTH_SHORT).show() }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Log now", Modifier.weight(1f)) {
                        Track.tap(t, toIdx = 0); Toast.makeText(ctx, "${t.name}: ${t.states.first().name}", Toast.LENGTH_SHORT).show()
                    }
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(p.field).clickable { newLog = true }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("With details", fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium) }
                }
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    TextAction("I did it earlier, pick the time") {
                        pickDateTime(ctx, now - HOUR) { at -> Track.tap(t, toIdx = 0, at = at) }
                    }
                }
                if (t.autoAfter > 0) {
                    Text(
                        if (t.states.size >= 2) "\u201C${t.states[0].name}\u201D turns into \u201C${t.states[1].name}\u201D after ${Track.dur(t.autoAfter)}"
                        else "Due again ${Track.every(t.autoAfter)}",
                        fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }

                // History
                Text("History", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.field)) {
                    if (logs.isEmpty()) Text("Nothing logged yet", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(14.dp))
                    logs.take(40).forEachIndexed { i, l ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(p.divider))
                        LogRow(l) { editLog = l }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                    TextAction("Delete ${t.name}", p.danger) { confirmDelete = true }
                }
            }
        }
    }

    if (editing) TrackerDialog(t.cardId, t) { editing = false }
    if (newLog) LogDialog(t, null) { newLog = false }
    editLog?.let { l -> LogDialog(t, l) { editLog = null } }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RoundedCornerShape(28.dp), containerColor = p.card,
            title = { Text("Delete ${t.name}?", fontSize = 20.sp) },
            text = { Text("Its whole history will be deleted.", color = p.sub) },
            confirmButton = { TextAction("Delete", p.danger) { Repo.deleteTracker(t.id); confirmDelete = false; onDismiss() } },
            dismissButton = { TextAction("Cancel", p.sub) { confirmDelete = false } }
        )
    }
}

private fun fmtNum(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(Locale.US, d)

@Composable
private fun LogRow(l: TLog, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatePill(l.state, l.kind)
                Text("  ${Fmt.whenText(l.time)}", fontSize = 13.sp, color = p.sub)
            }
            val extra = listOf(l.amount, l.note).filter { it.isNotBlank() }.joinToString("  ")
            if (extra.isNotBlank()) Text(extra, fontSize = 14.sp, color = p.text, modifier = Modifier.padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (l.photo != null) {
            Spacer(Modifier.width(10.dp))
            Thumb(l.photo, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
        }
    }
}

@Composable
private fun LogDialog(t: Tracker, log: TLog?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var idx by remember { mutableIntStateOf(log?.let { l -> t.states.indexOfFirst { it.name == l.state }.coerceAtLeast(0) } ?: 0) }
    var time by remember { mutableLongStateOf(log?.time ?: System.currentTimeMillis()) }
    var note by remember { mutableStateOf(log?.note ?: "") }
    var amount by remember { mutableStateOf(log?.amount ?: "") }
    var photo by remember { mutableStateOf(log?.photo) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { photo = withContext(Dispatchers.IO) { Images.saveUri(ctx, uri) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp), containerColor = p.card,
        title = { Text(if (log == null) "Log ${t.name}" else "Edit log", fontSize = 20.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    t.states.forEachIndexed { i, st ->
                        val (bg, fg) = kindColors(st.kind)
                        Text(
                            st.name, fontSize = 13.sp, color = fg, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
                                .then(if (i == idx) Modifier.border(2.dp, fg, RoundedCornerShape(50)) else Modifier)
                                .clickable { idx = i }.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.field)
                        .clickable { pickDateTime(ctx, time) { time = it } }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DateRange, null, tint = p.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(Fmt.whenText(time), fontSize = 15.sp, color = p.text)
                }
                OutlinedTextField(note, { note = it }, placeholder = { Text("Note, you can use #tags") }, minLines = 2,
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(amount, { amount = it }, placeholder = { Text("Amount, like 24 kg or 300 ml") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
                val ph = photo
                if (ph != null) {
                    Box {
                        Thumb(ph, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)))
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(Color(0x99000000))
                                .clickable { photo = null },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Close, "Remove photo", tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                } else {
                    TextAction("Add a photo") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
            }
        },
        confirmButton = {
            TextAction("Save") {
                val st = t.states.getOrElse(idx) { t.state }
                if (log == null) Track.tap(t, toIdx = idx, at = time, note = note.trim(), amount = amount.trim(), photo = photo)
                else {
                    Repo.updateLog(log.id, st.name, st.kind, time, note.trim(), amount.trim(), photo)
                    Track.recompute(t.id)
                }
                onDismiss()
            }
        },
        dismissButton = {
            Row {
                if (log != null) TextAction("Delete", p.danger) { Repo.deleteLog(log.id); Track.recompute(t.id); onDismiss() }
                TextAction("Cancel", p.sub, onDismiss)
            }
        }
    )
}

// ======================= Create / edit =======================

@Composable
private fun CardDialog(card: TCard?, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var tpl by remember { mutableStateOf<Template?>(null) }
    var name by remember { mutableStateOf(card?.name ?: "") }
    var emoji by remember { mutableStateOf(card?.emoji ?: Track.emojis.first()) }
    var color by remember { mutableIntStateOf(card?.color ?: Track.colors.first()) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp), containerColor = p.card,
        title = { Text(if (card == null) "New card" else "Edit card", fontSize = 20.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (card == null) {
                    Text("Start from", fontSize = 13.sp, color = p.sub)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Track.templates.forEach { t ->
                            val on = tpl == t
                            Text(
                                "${t.emoji} ${t.name}", fontSize = 13.sp, color = if (on) p.bg else p.text,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.text else p.field)
                                    .clickable { tpl = t; name = if (t.name == "Other") "" else t.name; emoji = t.emoji; color = t.color }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, placeholder = { Text("Name, like My car or Kitchen") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Track.emojis.forEach { e ->
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (e == emoji) Color(color).copy(alpha = 0.3f) else Color.Transparent)
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Track.colors.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                .then(if (c == color) Modifier.border(3.dp, p.text, CircleShape) else Modifier)
                                .clickable { color = c }
                        )
                    }
                }
                tpl?.let { t ->
                    if (t.trackers.isNotEmpty()) Text("Includes: " + t.trackers.joinToString(", ") { it.first }, fontSize = 13.sp, color = p.sub)
                }
            }
        },
        confirmButton = {
            TextAction("Save") {
                if (name.isBlank()) return@TextAction
                if (card == null) {
                    val id = Repo.addCard(name.trim(), emoji, color)
                    tpl?.trackers?.forEach { (n, s, a) -> Repo.addTracker(id, n, s, a) }
                } else Repo.updateCard(card.id, name.trim(), emoji, color)
                onDismiss()
            }
        },
        dismissButton = {
            Row {
                if (card != null) TextAction("Delete", p.danger) { confirmDelete = true }
                TextAction("Cancel", p.sub, onDismiss)
            }
        }
    )
    if (confirmDelete && card != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RoundedCornerShape(28.dp), containerColor = p.card,
            title = { Text("Delete ${card.name}?", fontSize = 20.sp) },
            text = { Text("Everything you track in it, and its history, will be deleted.", color = p.sub) },
            confirmButton = { TextAction("Delete", p.danger) { Repo.deleteCard(card.id); confirmDelete = false; onDismiss() } },
            dismissButton = { TextAction("Cancel", p.sub) { confirmDelete = false } }
        )
    }
}

private val kinds = listOf("good", "neutral", "warning", "bad")
private val units = listOf("Hours" to HOUR, "Days" to DAY, "Weeks" to 7 * DAY, "Months" to 30 * DAY)

@Composable
private fun TrackerDialog(cardId: Long, tracker: Tracker?, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(tracker?.name ?: "") }
    val states = remember { mutableStateListOf<TState>().apply { addAll(tracker?.states ?: listOf(TState("Done", "good"))) } }
    val start = tracker?.autoAfter ?: 0L
    var autoOn by remember { mutableStateOf(start > 0) }
    var unit by remember { mutableIntStateOf(units.indexOfLast { start > 0 && start % it.second == 0L }.let { if (it < 0) 1 else it }) }
    var number by remember { mutableStateOf(if (start > 0) (start / units[unit].second).toString() else "3") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp), containerColor = p.card,
        title = { Text(if (tracker == null) "Track something" else "Edit ${tracker.name}", fontSize = 20.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, placeholder = { Text("What, like Watering or Oil change") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
                Text("States. Tap the dot to change its color.", fontSize = 13.sp, color = p.sub)
                states.forEachIndexed { i, st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (bg, fg) = kindColors(st.kind)
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(bg).border(2.dp, fg, CircleShape)
                                .clickable { states[i] = st.copy(kind = kinds[(kinds.indexOf(st.kind) + 1) % kinds.size]) }
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            st.name, { states[i] = st.copy(name = it) }, singleLine = true,
                            placeholder = { Text("State name") },
                            shape = RoundedCornerShape(14.dp), colors = oneUiFieldColors(), modifier = Modifier.weight(1f)
                        )
                        if (states.size > 1) IconButton(onClick = { states.removeAt(i) }) { Icon(Icons.Default.Close, "Remove", tint = p.sub) }
                    }
                }
                if (states.size < 5) TextAction("+ Add a state") { states.add(TState("", "neutral")) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (states.size >= 2) "Turn \u201C${states[0].name}\u201D into \u201C${states[1].name}\u201D by itself"
                        else "Tell me when it's due again",
                        fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f)
                    )
                    Switch(checked = autoOn, onCheckedChange = { autoOn = it })
                }
                if (autoOn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("After", fontSize = 15.sp, color = p.sub)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            number, { number = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(14.dp), colors = oneUiFieldColors(), modifier = Modifier.width(76.dp)
                        )
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        units.forEachIndexed { i, (label, _) ->
                            Text(
                                label, fontSize = 13.sp, color = if (i == unit) p.bg else p.text,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (i == unit) p.text else p.field)
                                    .clickable { unit = i }.padding(horizontal = 14.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextAction("Save") {
                if (name.isBlank()) return@TextAction
                val clean = states.filter { it.name.isNotBlank() }.ifEmpty { listOf(TState("Done", "good")) }
                val ms = if (autoOn) (number.toLongOrNull() ?: 0L).coerceAtLeast(1L) * units[unit].second else 0L
                if (tracker == null) Repo.addTracker(cardId, name.trim(), clean, ms)
                else Repo.updateTracker(tracker.id, name.trim(), clean, ms)
                onDismiss()
            }
        },
        dismissButton = { TextAction("Cancel", p.sub, onDismiss) }
    )
}

// ======================= Timeline =======================

private fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
private fun TimelineScreen(v: Int, extra: @Composable () -> Unit) {
    val p = LocalPalette.current
    val logs = remember(v) { Repo.allLogs() }
    val trackers = remember(v) { Repo.trackers().associateBy { it.id } }
    val cards = remember(v) { Repo.cards().associateBy { it.id } }
    var open by remember { mutableStateOf<Long?>(null) }
    val groups = remember(logs) { logs.groupBy { dayOf(it.time) } }
    val today = LocalDate.now()

    OneUiScreen("Timeline", "Everything you logged, newest first", extra = extra, page = "Track") {
        if (logs.isEmpty()) {
            item(key = "empty") { EmptyState(Icons.Default.DateRange, "Nothing logged yet", "Every tap on a tracker shows up here.") }
        }
        groups.forEach { (d, list) ->
            item(key = "l$d") {
                SectionLabel(
                    when (d) {
                        today -> "Today"
                        today.minusDays(1) -> "Yesterday"
                        else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
                    }
                )
            }
            item(key = "g$d") {
                GroupBox {
                    list.forEachIndexed { i, l ->
                        if (i > 0) GroupDivider(start = 64.dp)
                        val t = trackers[l.trackerId]
                        val c = t?.let { cards[it.cardId] }
                        Row(
                            Modifier.fillMaxWidth().clickable { if (t != null) open = t.id }.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EmojiIcon(c?.emoji ?: "\u2B50", c?.color ?: Track.colors.last(), 36)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t?.name ?: "Deleted", fontSize = 15.sp, color = p.text, maxLines = 1)
                                Text(c?.name ?: "", fontSize = 12.sp, color = p.sub, maxLines = 1)
                                val more = listOf(l.amount, l.note).filter { it.isNotBlank() }.joinToString("  ")
                                if (more.isNotBlank()) Text(more, fontSize = 13.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                StatePill(l.state, l.kind)
                                Text(
                                    Instant.ofEpochMilli(l.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                                    fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    open?.let { id -> TrackerSheet(id, v) { open = null } }
}
