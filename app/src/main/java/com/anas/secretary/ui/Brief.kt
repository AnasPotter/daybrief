package com.anas.secretary.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.anas.secretary.data.Notif
import com.anas.secretary.data.Person
import com.anas.secretary.data.TCard
import com.anas.secretary.data.Tracker
import com.anas.secretary.util.Insight
import com.anas.secretary.util.Track
import com.anas.secretary.util.Features
import com.anas.secretary.data.Repo
import com.anas.secretary.data.SavedItem
import com.anas.secretary.data.Task
import com.anas.secretary.logic.LinkPreview
import com.anas.secretary.logic.RuleEngine
import com.anas.secretary.service.LiveActions
import com.anas.secretary.edge.EdgeLight
import com.anas.secretary.util.Cal
import com.anas.secretary.util.Contacts
import com.anas.secretary.util.CalEvent
import com.anas.secretary.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.anas.secretary.util.Prefs
import com.anas.secretary.util.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ======================= helpers used across screens =======================

/** Opens a link in its own app (TikTok, browser…) as a SEPARATE task, so Back/Home behave normally. */
fun openUrl(ctx: Context, url: String?) {
    if (url == null) return
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}
}

fun openApp(ctx: Context, pkg: String) {
    try {
        ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { ctx.startActivity(it) }
    } catch (_: Exception) {}
}

/** Today/tomorrow at a given hour (moves to the next day if already passed). */
fun atHour(dayOffset: Long, hour: Int): Long {
    val zone = ZoneId.systemDefault()
    var t = ZonedDateTime.of(LocalDate.now().plusDays(dayOffset), LocalTime.of(hour, 0), zone)
    if (t.toInstant().toEpochMilli() <= System.currentTimeMillis()) t = t.plusDays(1)
    return t.toInstant().toEpochMilli()
}

fun inOneHour() = System.currentTimeMillis() + 3_600_000L

@Composable
fun CircleIcon(icon: ImageVector, size: Int = 28) {
    val p = LocalPalette.current
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(p.pill), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = p.pillText, modifier = Modifier.size((size * 0.58f).dp))
    }
}

// ======================= Brief state =======================

/** Lives while the app is open: how many things you answered, and which task was just added. */
object BriefSession {
    var answered by mutableIntStateOf(0)
    var filter by mutableIntStateOf(0) // 0 = all, 1 = personal, 2 = work
    var highlight by mutableStateOf<Long?>(null)
}

class UndoInfo(val text: String, val undo: (() -> Unit)?)

enum class ChoiceType { Instant, PickTime, Reply, Stay }

class Choice(
    val label: String,
    val primary: Boolean = false,
    val type: ChoiceType = ChoiceType.Instant,
    val dir: Int = 1,
    /** false = never the swipe-right answer (e.g. opening the app) */
    val swipe: Boolean = true,
    val perform: (Long?) -> Unit = {}
)

class DeckContent(
    val icon: @Composable () -> Unit,
    val app: String,
    val time: String,
    val sender: String?,
    val message: String,
    val image: String?,
    val question: String,
    val choices: List<Choice>,
    val swipeRight: Int,
    val replyKey: String?,
    val suggestions: List<String>,
    val onReplied: () -> Unit,
    val work: Boolean = false,
    /** Opens the conversation in its app (shown when a reply can't be sent from here) */
    val onOpen: (() -> Unit)? = null
)

private sealed class Ask(val key: String) {
    class FromNotif(val n: Notif) : Ask("n${n.id}")
    class Overdue(val t: Task) : Ask("o${t.id}")
    class SavedLink(val s: SavedItem) : Ask("s${s.id}")
    class PersonDue(val p: Person) : Ask("p${p.id}")
    class Birthday(val p: Person, val days: Int, val year: Int) : Ask("b${p.id}")
    class TrackerDue(val t: Tracker, val card: TCard?, val ins: Insight) : Ask("t${t.id}")
}

private val avatarColors = listOf(
    Color(0xFF7F77DD), Color(0xFFD4537E), Color(0xFF1D9E75), Color(0xFFD85A30), Color(0xFF378ADD), Color(0xFFBA7517)
)

// ======================= The Brief screen =======================

@Composable
fun BriefScreen(v: Int, tick: Int, onOpenHealth: () -> Unit = {}, onOpenTab: (Int) -> Unit = {}, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val live by LiveActions.version.collectAsState()
    val notifs = remember(v) { Repo.notifs(false) }
    val tasks = remember(v) { Repo.tasks() }
    val saved = remember(v) { Repo.saved() }
    val people = remember(v) { Repo.people() }
    val workApps = remember(v) { Prefs.workApps }
    val listenerOn = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    var undo by remember { mutableStateOf<UndoInfo?>(null) }
    var showFiltered by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var editingNotif by remember { mutableStateOf<Notif?>(null) }
    var clearing by remember { mutableStateOf(false) }
    // Last night's sleep from the watch (saved numbers; refreshed when the app opens)
    val hv by com.anas.secretary.util.Health.version.collectAsState()
    val health = remember(v, hv) { if (Features.health) com.anas.secretary.util.Health.cached(ctx) else null }
    var sleepAnswered by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (Features.health) com.anas.secretary.util.Health.refresh(ctx)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { com.anas.secretary.util.Daily.planTonight(ctx) } catch (_: Exception) {}
        }
    }
    var supportDue by remember { mutableStateOf(supportCardDue(ctx)) }
    var supporting by remember { mutableStateOf(false) }
    if (supporting) SupportSheet { supporting = false }
    var lightClosed by remember { mutableStateOf(Prefs.lightDayDone == LocalDate.now().toString()) }
    var lightMoved by remember { mutableStateOf<List<Task>>(emptyList()) }

    val now = System.currentTimeMillis()
    val allAsks: List<Ask> = remember(v) {
        buildList {
            // Skip reminder questions whose time has already gone by
            if (Features.messages) notifs.filter { it.sType != null && it.sType != RuleEngine.IGNORE && !(it.sType == RuleEngine.REMIND && (it.sDue ?: Long.MAX_VALUE) < now) }
                .forEach { add(Ask.FromNotif(it)) }
            if (Features.people) people.forEach { pp ->
                val b = Contacts.daysToBirthday(pp.birthday)
                if (b != null && b.first in 0..3 && pp.bdayYear != b.second) add(Ask.Birthday(pp, b.first, b.second))
            }
            if (Features.people) people.filter { it.isDue(now) && it.snooze < now }.forEach { add(Ask.PersonDue(it)) }
            val cardsById = Repo.cards().associateBy { it.id }
            if (Features.things) Track.attentionList(now).forEach { (t, ins) -> add(Ask.TrackerDue(t, cardsById[t.cardId], ins)) }
            tasks.filter { !it.done && it.due != null && it.due < now }.forEach { add(Ask.Overdue(it)) }
            if (Prefs.askAboutSaved && Features.saved) saved.filter { !it.nudged && it.url != null && it.created > now - 3 * 86_400_000L }
                .forEach { add(Ask.SavedLink(it)) }
        }
    }
    fun isWork(a: Ask) = when (a) {
        is Ask.FromNotif -> a.n.pkg in workApps
        is Ask.Overdue -> a.t.work
        is Ask.PersonDue -> a.p.cat == "work"
        else -> false
    }
    val counts = listOf(allAsks.size, allAsks.count { !isWork(it) }, allAsks.count { isWork(it) })
    val asks = when (BriefSession.filter) {
        1 -> allAsks.filter { !isWork(it) }
        2 -> allAsks.filter { isWork(it) }
        else -> allAsks
    }
    val filtered = if (Features.messages) notifs.filter { it.sType == RuleEngine.IGNORE } else emptyList()
    val upcoming = tasks.filter { !it.done && it.due != null && it.due >= now }.take(7)
    val calEvents by produceState(emptyList<CalEvent>(), v, tick) {
        value = if (!Features.calendar) emptyList()
            else withContext(Dispatchers.IO) { Cal.events(ctx, now, now + 2 * 86_400_000L) }.filter { !it.allDay }
    }

    LaunchedEffect(undo) { if (undo != null) { delay(3500); undo = null } }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun answered(msg: String, undoFn: (() -> Unit)?) {
        BriefSession.answered++
        undo = UndoInfo(msg, undoFn?.let { fn -> { fn(); BriefSession.answered = (BriefSession.answered - 1).coerceAtLeast(0) } })
    }

    fun content(ask: Ask): DeckContent = when (ask) {
        is Ask.FromNotif -> {
            val n = ask.n
            val who = n.title.ifBlank { n.app }
            val canReply = live >= 0 && LiveActions.canReply(n.skey)
            val taskTitle = n.sTitle?.ifBlank { null } ?: who
            fun remind(at: Long?) {
                val id = Repo.addTask(taskTitle, "${n.app}: ${n.body}", at, "notification", work = n.pkg in workApps)
                Repo.setNotifStatus(n.id, "done")
                BriefSession.highlight = id
                answered(if (at != null) "Reminder set for ${Fmt.whenText(at)}" else "Added to tasks") {
                    Repo.deleteTask(id); Repo.setNotifStatus(n.id, "new")
                }
            }
            val no = Choice("No", dir = -1) {
                Repo.setNotifStatus(n.id, "dismissed")
                answered("Dismissed") { Repo.setNotifStatus(n.id, "new") }
            }
            val reply = Choice("Reply", primary = true, type = ChoiceType.Reply)
            fun openChat() = EdgeLight.openChat(ctx, n.skey, n.pkg)
            // The notification is gone (read on the phone, or old), so Android can't send a reply from here anymore
            fun openAndDone() { openChat(); Repo.setNotifStatus(n.id, "dismissed"); answered("Opened ${n.app}", null) }
            val open = Choice("Open ${n.app}", primary = n.sType == RuleEngine.REPLY, swipe = false) { openAndDone() }
            val choices = when (n.sType) {
                RuleEngine.REMIND -> listOfNotNull(
                    Choice("Remind me", primary = !canReply) { remind(n.sDue) },
                    if (canReply) reply else null,
                    Choice("Other time", type = ChoiceType.PickTime) { remind(it) },
                    if (!canReply) open else null,
                    no
                )
                RuleEngine.REPLY -> listOfNotNull(
                    if (canReply) reply else open,
                    Choice("In 1 hour") { remind(inOneHour()) },
                    Choice("Tonight") { remind(atHour(0, 20)) },
                    no
                )
                RuleEngine.CALL -> listOf(
                    Choice("Call back", primary = true) {
                        if (!LiveActions.call(ctx, n.skey)) openApp(ctx, n.pkg)
                        Repo.setNotifStatus(n.id, "done"); answered("Calling $who", null)
                    },
                    Choice("In 1 hour") { remind(inOneHour()) },
                    Choice("Tonight") { remind(atHour(0, 20)) },
                    no
                )
                else -> listOf(
                    Choice("Add task", primary = true) { remind(null) },
                    Choice("With reminder", type = ChoiceType.PickTime) { remind(it) },
                    no
                )
            }
            val question = when (n.sType) {
                RuleEngine.REMIND -> {
                    val w = n.sDue?.let { Fmt.whenText(it) } ?: "it"
                    if (canReply) "Reply now, or want a reminder for $w?" else "Want a reminder for $w?"
                }
                RuleEngine.REPLY -> if (canReply) "$who is waiting for your reply. Reply now or later?"
                else "$who is waiting for your reply. Should I remind you?"
                RuleEngine.CALL -> "You missed a call. Call back now, or should I remind you?"
                else -> "${n.sLabel ?: "This might need action"}. Add it to your tasks?"
            }
            DeckContent(
                icon = { AppIcon(n.pkg, 30.dp) }, app = n.app, time = Fmt.short(n.time),
                sender = who, message = if (n.sType == RuleEngine.CALL) "Missed call" else n.body.ifBlank { n.title },
                image = null, question = question, choices = choices,
                swipeRight = choices.indexOfFirst { it.type == ChoiceType.Instant && it.dir > 0 && it.swipe }.coerceAtLeast(0),
                replyKey = if (canReply) n.skey else null,
                suggestions = (LiveActions.smartReplies(n.skey) + RuleEngine.quickReplies(n.body, n.sDue != null)).distinct().take(4),
                onReplied = {
                    Repo.setNotifStatus(n.id, "done")
                    answered("Reply sent to $who", null)
                },
                work = n.pkg in workApps,
                onOpen = { openAndDone() }
            )
        }
        is Ask.Overdue -> {
            val t = ask.t
            fun move(at: Long) {
                val old = t.due
                Repo.updateTask(t.id, t.title, t.notes, at)
                BriefSession.highlight = t.id
                answered("Moved to ${Fmt.whenText(at)}") { Repo.updateTask(t.id, t.title, t.notes, old) }
            }
            val choices = listOf(
                Choice("Done", primary = true) {
                    Repo.setDone(t.id, true); answered("Marked as done") { Repo.setDone(t.id, false) }
                },
                Choice("In 1 hour") { move(inOneHour()) },
                Choice("Edit", type = ChoiceType.Stay) { editingTask = t },
                Choice("Tomorrow", dir = -1) { move(atHour(1, 9)) }
            )
            DeckContent(
                icon = { CircleIcon(Icons.Default.DateRange, 30) }, app = "Overdue task",
                time = t.due?.let { Fmt.whenText(it) } ?: "", sender = null, message = t.title, image = t.image,
                question = "This was due ${t.due?.let { Fmt.whenText(it) } ?: "earlier"}. Is it done?",
                choices = choices, swipeRight = 0, replyKey = null, suggestions = emptyList(), onReplied = {},
                work = t.work
            )
        }
        is Ask.PersonDue -> {
            val pp = ask.p
            val days = pp.daysSince(now)
            val number = pp.phones.firstOrNull()
            val notes = Repo.notesFor(pp.id)
            fun snooze(until: Long, msg: String) {
                val old = pp.snooze
                Repo.snoozePerson(pp.id, until)
                answered(msg) { Repo.snoozePerson(pp.id, old) }
            }
            val choices = listOfNotNull(
                if (number != null) Choice("Call now", primary = true) {
                    Contacts.dial(ctx, number); snooze(System.currentTimeMillis() + 3 * 3_600_000L, "Calling ${pp.name}")
                } else null,
                if (number != null) Choice("WhatsApp") {
                    Contacts.whatsapp(ctx, number); snooze(System.currentTimeMillis() + 3 * 3_600_000L, "Opening WhatsApp")
                } else null,
                Choice("Tonight", primary = number == null) {
                    val at = atHour(0, 20)
                    val id = Repo.addTask("Call ${pp.name}", notes.joinToString("\n") { "\u2022 " + it.text }, at, "person",
                        work = pp.cat == "work", personId = pp.id)
                    Repo.snoozePerson(pp.id, at + 3 * 3_600_000L)
                    BriefSession.highlight = id
                    answered("Reminder set for ${Fmt.whenText(at)}") { Repo.deleteTask(id); Repo.snoozePerson(pp.id, 0) }
                },
                Choice("Already did") {
                    Repo.addInteraction(pp.id, "manual", 0, System.currentTimeMillis())
                    answered("Noted, you talked to ${pp.name}", null)
                },
                Choice("Later", dir = -1) { snooze(atHour(1, 9), "I'll ask again tomorrow") }
            )
            DeckContent(
                icon = { PersonAvatar(pp, 30.dp, showRing = false) },
                app = pp.relation.ifBlank { "People" }, time = days?.let { daysAgo(it) } ?: "",
                sender = pp.name,
                message = pp.lastContact?.let { "Last time: ${pp.lastHow}, ${Fmt.ago(it).lowercase()}" } ?: "No calls in the last 90 days",
                image = null,
                question = (if (days == null) "You haven't talked to ${pp.name} in a while." else "It's been $days days since you talked to ${pp.name}.") +
                    (if (number != null) " Call now?" else " Want a reminder?") +
                    (if (notes.isNotEmpty()) "\n\nRemember: " + notes.joinToString("; ") { it.text } else ""),
                choices = choices, swipeRight = choices.indexOfFirst { it.label == "Tonight" }.coerceAtLeast(0),
                replyKey = null, suggestions = emptyList(), onReplied = {}, work = pp.cat == "work"
            )
        }
        is Ask.Birthday -> {
            val pp = ask.p
            val d = ask.days
            val number = pp.phones.firstOrNull()
            val whenTxt = when (d) { 0 -> "today"; 1 -> "tomorrow"; else -> "in $d days" }
            val choices = listOfNotNull(
                if (d == 0 && number != null) Choice("Call now", primary = true) {
                    Contacts.dial(ctx, number); Repo.setBirthdayAsked(pp.id, ask.year); answered("Calling ${pp.name}", null)
                } else null,
                Choice(if (d == 0) "Remind in 1 hour" else "Remind me on the day", primary = d != 0 || number == null) {
                    val at = if (d == 0) inOneHour() else atHour(d.toLong(), 9)
                    val id = Repo.addTask("Wish ${pp.name} a happy birthday", "", at, "person", personId = pp.id)
                    Repo.setBirthdayAsked(pp.id, ask.year)
                    BriefSession.highlight = id
                    answered("Reminder set for ${Fmt.whenText(at)}") { Repo.deleteTask(id); Repo.setBirthdayAsked(pp.id, 0) }
                },
                Choice("No", dir = -1) {
                    Repo.setBirthdayAsked(pp.id, ask.year); answered("OK") { Repo.setBirthdayAsked(pp.id, 0) }
                }
            )
            DeckContent(
                icon = { CircleIcon(Icons.Default.Favorite, 30) }, app = "Birthday", time = whenTxt.replaceFirstChar { it.uppercase() },
                sender = pp.name, message = "Birthday $whenTxt", image = null,
                question = "${pp.name}'s birthday is $whenTxt. " + if (d == 0) "Call or send your wishes?" else "Should I remind you on the day?",
                choices = choices, swipeRight = choices.indexOfFirst { it.label.startsWith("Remind") }.coerceAtLeast(0),
                replyKey = null, suggestions = emptyList(), onReplied = {}
            )
        }
        is Ask.TrackerDue -> {
            val t = ask.t
            val ins = ask.ins
            val good = t.states.firstOrNull() ?: t.state
            fun did(at: Long, msg: String) {
                val u = Track.tap(t, toIdx = 0, at = at)
                answered(msg, u)
            }
            val choices = listOf(
                Choice("Yes, done", primary = true) { did(System.currentTimeMillis(), "${t.name}: ${good.name}") },
                Choice("Earlier", type = ChoiceType.PickTime) { at -> did(at ?: System.currentTimeMillis(), "${t.name}: ${good.name}") },
                Choice("Remind tonight") {
                    val at = atHour(0, 20)
                    val id = Repo.addTask("${t.name}" + (ask.card?.let { " (${it.name})" } ?: ""), "", at, "tracker")
                    Repo.snoozeTracker(t.id, at + 3_600_000L)
                    BriefSession.highlight = id
                    answered("Reminder set for ${Fmt.whenText(at)}") { Repo.deleteTask(id); Repo.snoozeTracker(t.id, 0) }
                },
                Choice("Not yet", dir = -1) {
                    Repo.snoozeTracker(t.id, atHour(1, 9)); answered("I'll ask again tomorrow") { Repo.snoozeTracker(t.id, 0) }
                }
            )
            val question = if (ins.lastDone != null && ins.usual != null)
                "${good.name} ${Track.ago(ins.lastDone).lowercase()}, you usually do it ${Track.every(ins.usual)}. Did you do it?"
            else "${t.name} has been \u201C${t.state.name}\u201D for ${Track.dur(now - t.since)}. Did you take care of it?"
            DeckContent(
                icon = { EmojiIcon(ask.card?.emoji ?: "\u2B50", ask.card?.color ?: Track.colors.last(), 30) },
                app = ask.card?.name ?: "Things", time = Track.ago(t.since),
                sender = null, message = "${t.name}: ${t.state.name}", image = null, question = question,
                choices = choices, swipeRight = 0, replyKey = null, suggestions = emptyList(), onReplied = {}
            )
        }
        is Ask.SavedLink -> {
            val s = ask.s
            val title = s.title?.takeUnless { LinkPreview.isGeneric(it) }
            fun remindSaved(at: Long) {
                val what = s.note.ifBlank { title ?: LinkPreview.domain(s.url) }
                val id = Repo.addTask("Check: $what", s.url ?: s.body, at, "saved", null)
                Repo.setSavedNudged(s.id)
                BriefSession.highlight = id
                answered("Reminder set for ${Fmt.whenText(at)}") { Repo.deleteTask(id); Repo.setSavedNudged(s.id, false) }
            }
            val choices = listOf(
                Choice("Tonight", primary = true) { remindSaved(atHour(0, 20)) },
                Choice("Tomorrow") { remindSaved(atHour(1, 20)) },
                Choice("Pick a time", type = ChoiceType.PickTime) { it?.let { at -> remindSaved(at) } },
                Choice("No need", dir = -1) {
                    Repo.setSavedNudged(s.id); answered("OK, no reminder") { Repo.setSavedNudged(s.id, false) }
                }
            )
            DeckContent(
                icon = { CircleIcon(Icons.Default.Star, 30) },
                app = s.source.ifBlank { LinkPreview.domain(s.url) }, time = Fmt.short(s.created),
                sender = null, message = s.note.ifBlank { title ?: s.url ?: "" }, image = s.image,
                question = "You saved this ${Fmt.ago(s.created).lowercase()}. When do you want to check it?",
                choices = choices, swipeRight = 0, replyKey = null, suggestions = emptyList(), onReplied = {}
            )
        }
    }

    val zone = java.time.ZoneId.systemDefault()
    val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val todayTasks = tasks.filter { it.due != null && it.due >= dayStart && it.due < dayEnd }
    val duePeople = remember(v) { if (!Features.people) emptyList() else people.filter { it.isDue(now) && it.snooze < now }.sortedByDescending { it.daysSince(now) ?: Int.MAX_VALUE } }
    val overdue = tasks.filter { !it.done && it.due != null && it.due < now }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 150.dp)
        ) {
            item(key = "header") { BriefHeader(remaining = asks.size) }
            item(key = "filter") {
                Box(Modifier.padding(bottom = 16.dp)) {
                    IconSwitcher(
                        listOf(LookIcons.Grid to "All", LookIcons.Person to "Personal", LookIcons.Work to "Work"),
                        BriefSession.filter, badges = counts, labels = true
                    ) { BriefSession.filter = it }
                }
            }
            if (!sleepAnswered) sleepCardFor(health)?.let { n ->
                item(key = "sleep") {
                    BriefSleepCard(health!!, n, onDetails = { Prefs.sleepCardDay = LocalDate.now().toEpochDay(); sleepAnswered = true; onOpenHealth() }) { msg ->
                        sleepAnswered = true
                        if (msg != "OK") undo = UndoInfo(msg, null)
                    }
                }
            }
            Updater.available?.let { r ->
                item(key = "update") {
                    GroupBox(Modifier.padding(bottom = 12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenSettings).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircleIcon(Icons.Default.Refresh, 30)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("A new version is ready", fontSize = 15.sp, color = p.text)
                                Text("${r.label}, install it from Settings", fontSize = 13.sp, color = p.sub)
                            }
                            Text("Update", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            if (!listenerOn && (Features.messages || Features.edge)) {
                item(key = "setup") {
                    GroupBox(Modifier.padding(bottom = 12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenSettings).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircleIcon(Icons.Default.Warning, 30)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Notification access is off", fontSize = 15.sp, color = p.text)
                                Text("Turn it on so I can help with your messages", fontSize = 13.sp, color = p.sub)
                            }
                            Text("Turn on", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            // After a short night, before noon: offer a lighter day
            val lastN = health?.lastNight
            val shortBy = health?.nights?.let { com.anas.secretary.logic.DayPlan.shortBy(it) }
            val movable = todayTasks.filter { !it.done && (it.due ?: 0) > now }
            // Waits until the morning sleep card (which says the same thing) has been answered
            if (Features.day && Prefs.lightDayOn && !lightClosed && (sleepAnswered || sleepCardFor(health) == null) && shortBy != null && lastN != null && lastN.wakeDate() == LocalDate.now() &&
                java.time.LocalTime.now().hour < 12 && (movable.isNotEmpty() || lightMoved.isNotEmpty())
            ) item(key = "lightday") {
                LightDayCard(shortBy, lastN.asleep, movable.ifEmpty { lightMoved },
                    onMove = {
                        lightMoved = movable
                        Prefs.lightDayDone = LocalDate.now().toString() // once a day, even if Brief is left
                        movable.forEach { t ->
                            val next = java.time.Instant.ofEpochMilli(t.due!!).atZone(java.time.ZoneId.systemDefault()).plusDays(1).toInstant().toEpochMilli()
                            Repo.updateTask(t.id, t.title, t.notes, next)
                        }
                    },
                    onUndo = {
                        lightMoved.forEach { t -> Repo.updateTask(t.id, t.title, t.notes, t.due) }
                        lightMoved = emptyList()
                        Prefs.lightDayDone = ""
                    },
                    onClose = { Prefs.lightDayDone = LocalDate.now().toString(); lightClosed = true }
                )
            }
            if (supportDue) item(key = "support") {
                SupportCard(onOpen = { supporting = true; Prefs.supportDismissed = true; supportDue = false }, onClose = { Prefs.supportDismissed = true; supportDue = false })
            }
            // Only when there's a week to look back on (sleep, or tasks from the last 7 days)
            val weekHasData = health?.nights?.isNotEmpty() == true || tasks.any { it.due != null && it.due in now - 7 * 86_400_000L..now }
            if (Features.day && Prefs.weekOn && weekHasData && LocalDate.now().dayOfWeek.value == Prefs.weekDay) item(key = "week") {
                WeekTile { WeekState.show = true }
            }
            item(key = "deck") {
                AnimatedContent(
                    modifier = Modifier.rise(0),
                    targetState = asks.isEmpty(),
                    transitionSpec = { (fadeIn(tween(300)) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(tween(200)) },
                    label = "deck"
                ) { empty ->
                    if (empty) AllClear()
                    else if (asks.isNotEmpty()) Deck(content(asks.first()), asks.first().key, behind = asks.size - 1)
                }
            }
            if (asks.size >= 2) item(key = "clear") { DeckFooter(asks.size) { clearing = true } }
            item(key = "tiles") {
                Box(Modifier.padding(top = if (asks.size >= 2) 0.dp else 12.dp).rise(2)) {
                    val sleepQuestion = !sleepAnswered && sleepCardFor(health) != null
                    BriefTiles(
                        lastNight = if (sleepQuestion) null else health?.lastNight?.takeIf { it.end > now - 30 * 3_600_000L },
                        next = nextUp(tasks, calEvents, now), today = todayTasks, due = duePeople, overdue = overdue,
                        onSleep = onOpenHealth, onNext = { onOpenTab(1) },
                        onPeople = { TrackState.seg = 1; onOpenTab(2) }, onOverdue = { CalState.mode = 2; onOpenTab(1) },
                        onToday = { CalState.mode = 0; CalState.day = LocalDate.now(); onOpenTab(1) }
                    )
                }
            }
            if (filtered.isNotEmpty()) {
                item(key = "filtered") {
                    FilteredBox(filtered, showFiltered, { showFiltered = !showFiltered }, { editingNotif = it })
                }
            }
            // "Later" lists what comes after the Next up tile, so the same item never shows twice
            val next = nextUp(tasks, calEvents, now)
            // Drop only the one item shown in Next up (an event already under way stays listed)
            val nextTask = upcoming.firstOrNull { next != null && it.title == next.first && it.due == next.second }
            val laterTasks = if (nextTask != null) upcoming - nextTask else upcoming
            val nextEvent = if (nextTask != null) null else calEvents.firstOrNull { next != null && it.title == next.first && it.begin == next.second }
            val laterEvents = if (nextEvent != null) calEvents - nextEvent else calEvents
            if (laterTasks.isNotEmpty() || laterEvents.isNotEmpty()) {
                item(key = "up-label") { SectionLabel("Later") }
                item(key = "upcoming") { UpcomingBox(laterTasks, laterEvents) { editingTask = it } }
            }
        }
        UndoPill(undo, Modifier.align(Alignment.BottomCenter)) { undo = null }
    }

    if (clearing) {
        // Clearing a card does what its No / Later button does; nothing leaves the phone's notification shade
        fun clearOne(a: Ask): () -> Unit = when (a) {
            is Ask.FromNotif -> ({ Repo.setNotifStatus(a.n.id, "new") }) // undo only; the status change is batched below
            is Ask.Overdue -> {
                val old = a.t.due
                Repo.updateTask(a.t.id, a.t.title, a.t.notes, atHour(1, 9));
                { Repo.updateTask(a.t.id, a.t.title, a.t.notes, old) }
            }
            is Ask.PersonDue -> { val old = a.p.snooze; Repo.snoozePerson(a.p.id, atHour(1, 9)); { Repo.snoozePerson(a.p.id, old) } }
            is Ask.Birthday -> { Repo.setBirthdayAsked(a.p.id, a.year); { Repo.setBirthdayAsked(a.p.id, 0) } }
            is Ask.TrackerDue -> { val old = a.t.snooze; Repo.snoozeTracker(a.t.id, atHour(1, 9)); { Repo.snoozeTracker(a.t.id, old) } }
            is Ask.SavedLink -> { Repo.setSavedNudged(a.s.id); { Repo.setSavedNudged(a.s.id, false) } }
        }
        fun clear(list: List<Ask>, what: String) {
            clearing = false
            if (list.isEmpty()) return
            val ids = list.filterIsInstance<Ask.FromNotif>().map { it.n.id }
            val undos = list.map { clearOne(it) }
            Repo.setNotifStatusMany(ids, "dismissed")
            val n = list.size
            BriefSession.answered += n
            undo = UndoInfo("Cleared $n ($what)") {
                Repo.setNotifStatusMany(ids, "new")
                undos.filterIndexed { i, _ -> list[i] !is Ask.FromNotif }.forEach { it() }
                BriefSession.answered = (BriefSession.answered - n).coerceAtLeast(0)
            }
        }
        ClearSheet(asks, onDismiss = { clearing = false }, onClear = ::clear)
    }

    editingTask?.let { t ->
        TaskDialog(
            "Edit task", t.title, t.notes, t.due, image = t.image, initWork = t.work,
            onDelete = { Repo.deleteTask(t.id); editingTask = null },
            onDismiss = { editingTask = null },
            onSave = { a, b, c, w -> Repo.updateTask(t.id, a, b, c, w); editingTask = null }
        )
    }
    editingNotif?.let { n ->
        TaskDialog(
            "Add task",
            initTitle = n.sTitle?.ifBlank { null } ?: "${n.title}: ${n.body}".take(120),
            initNotes = "${n.app}: ${n.body}", initDue = n.sDue, initWork = n.pkg in Prefs.workApps,
            onDismiss = { editingNotif = null },
            onSave = { t, notes, d, w ->
                Repo.addTask(t, notes, d, "notification", work = w); Repo.setNotifStatus(n.id, "done"); editingNotif = null
            }
        )
    }
}

// ======================= Header, ring, stats =======================

@Composable
private fun BriefHeader(remaining: Int) {
    val p = LocalPalette.current
    val hour = LocalTime.now().hour
    val greeting = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
    val name = Prefs.name.trim()
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 4.dp, top = 26.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(date, fontSize = 13.sp, color = p.text.copy(alpha = 0.75f))
            Text(
                if (name.isNotBlank()) "$greeting, $name" else greeting,
                fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.padding(top = 2.dp)
            )
        }
        SearchButton()
        Spacer(Modifier.width(6.dp))
        Avatar()
    }
}

// ======================= The card stack =======================

@Composable
private fun Deck(front: DeckContent, frontKey: String, behind: Int) {
    val dark = isSystemInDarkTheme()
    val layers = behind.coerceIn(0, 2)
    Box(Modifier.fillMaxWidth().padding(bottom = (layers * 12).dp)) {
        for (j in layers downTo 1) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        translationY = (12 * j).dp.toPx()
                        scaleX = 1f - 0.05f * j
                        scaleY = scaleX
                    }
                    .clip(RoundedCornerShape(28.dp))
                    // Soft see-through layers so the stack reads as "more cards", not as black slabs on the glow
                    .background(
                        if (dark) LocalPalette.current.card.copy(alpha = if (j == 1) 0.6f else 0.35f)
                        else Color.White.copy(alpha = if (j == 1) 0.7f else 0.45f)
                    )
            )
        }
        key(frontKey) { FrontCard(front) }
    }
}

@Composable
private fun FrontCard(c: DeckContent) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    val width = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val threshold = with(density) { 110.dp.toPx() }
    val current by rememberUpdatedState(c)

    val offsetX = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 260f)) }
    var replying by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<String?>(null) }
    var flying by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sendFailed by remember { mutableStateOf(false) }

    fun fly(dir: Int, after: () -> Unit) {
        if (flying) return
        flying = true
        if (dir < 0) Feel.reject(view) else Feel.confirm(view)
        scope.launch {
            offsetX.animateTo(dir * width * 1.4f, tween(300, easing = FastOutLinearInEasing))
            after()
        }
    }

    fun choose(ch: Choice) {
        when (ch.type) {
            ChoiceType.Reply -> { replying = true; scope.launch { offsetX.animateTo(0f, spring()) } }
            ChoiceType.Stay -> { scope.launch { offsetX.animateTo(0f, spring()) }; ch.perform(null) }
            ChoiceType.PickTime -> {
                scope.launch { offsetX.animateTo(0f, spring()) }
                pickDateTime(ctx, null) { t -> fly(ch.dir) { ch.perform(t) } }
            }
            ChoiceType.Instant -> fly(ch.dir) { ch.perform(null) }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) { error = "Write a reply first"; return }
        if (LiveActions.reply(ctx, current.replyKey, t)) {
            sent = t; replying = false
            scope.launch { delay(1100); fly(1) { current.onReplied() } }
        } else {
            error = "This message can't be answered from here anymore. Open ${current.app} to reply."
            sendFailed = true
        }
    }

    Box(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                val prog = enter.value
                translationX = offsetX.value
                rotationZ = offsetX.value / 45f
                translationY = (1f - prog) * 14.dp.toPx()
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleX = 0.95f + 0.05f * prog
                scaleY = scaleX
            }
            .clip(RoundedCornerShape(28.dp))
            .background(p.card)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val x = offsetX.value
                        val cc = current
                        if (x > threshold) choose(cc.choices[cc.swipeRight])
                        else if (x < -threshold) choose(cc.choices.last())
                        else scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 400f)) }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, spring()) } },
                    onHorizontalDrag = { change, amount ->
                        if (!flying && !replying && draft.isEmpty()) {
                            change.consume()
                            val before = offsetX.value
                            scope.launch { offsetX.snapTo(before + amount) }
                            if ((before < threshold && before + amount >= threshold) || (before > -threshold && before + amount <= -threshold)) {
                                Feel.tick(view)
                            }
                        }
                    }
                )
            }
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
            // App + time
            Row(verticalAlignment = Alignment.CenterVertically) {
                c.icon()
                Spacer(Modifier.width(10.dp))
                Text(c.app, fontSize = 13.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.work) { WorkBadge(); Spacer(Modifier.width(8.dp)) }
                Text(c.time, fontSize = 12.sp, color = p.sub)
            }
            // Their message
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
                if (c.sender != null) {
                    val col = avatarColors[(c.sender.hashCode() and 0x7fffffff) % avatarColors.size]
                    Box(Modifier.size(28.dp).clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
                        Text(c.sender.take(1).uppercase(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(
                    Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(p.field)
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                ) {
                    if (c.sender != null) Text(c.sender, fontSize = 12.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.image != null) {
                        Thumb(c.image, Modifier.padding(top = 4.dp, bottom = 6.dp).fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)))
                    }
                    Text(c.message, fontSize = 15.sp, lineHeight = 21.sp, color = p.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            // Your sent reply
            AnimatedVisibility(
                visible = sent != null,
                enter = scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(1f, 1f), animationSpec = spring(dampingRatio = 0.6f)) + fadeIn()
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    Column(
                        Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(20.dp))
                            .background(p.bubble)
                            .padding(horizontal = 13.dp, vertical = 9.dp)
                    ) {
                        Text(sent.orEmpty(), fontSize = 15.sp, color = p.onBubble)
                        Text("Sent", fontSize = 11.sp, color = p.onBubble.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.End))
                    }
                }
            }
            // The secretary's question
            AnimatedVisibility(visible = sent == null, exit = fadeOut() + shrinkVertically()) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        c.question, fontSize = 15.sp, lineHeight = 21.sp, color = p.pillText,
                        modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(p.pill)
                            .padding(horizontal = 13.dp, vertical = 9.dp)
                    )
                }
            }
            // Reply box: open right away while the notification still allows a reply (kept open if it goes
            // away mid-typing, so the text isn't lost; sending then explains and offers to open the app)
            val inline = c.replyKey != null
            AnimatedVisibility(
                visible = (inline || replying || draft.isNotEmpty() || error != null) && sent == null,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(Modifier.padding(top = 12.dp)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        c.suggestions.forEach { s ->
                            Box(
                                Modifier.clip(RoundedCornerShape(50))
                                    .background(p.good)
                                    .clickable { send(s) }.padding(horizontal = 14.dp, vertical = 8.dp)
                            ) { Text(s, fontSize = 13.sp, color = p.onGood) }
                        }
                    }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft, onValueChange = { draft = it; error = null },
                            placeholder = { Text("Message ${c.sender ?: ""}".trim()) },
                            shape = RoundedCornerShape(24.dp), colors = oneUiFieldColors(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send(draft) }),
                            maxLines = 4, modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(p.send).clickable { send(draft) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Send, "Send", tint = Color.White, modifier = Modifier.size(22.dp)) }
                    }
                    error?.let { Text(it, fontSize = 13.sp, color = p.danger, modifier = Modifier.padding(top = 6.dp, start = 6.dp)) }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sent through ${c.app}, like replying there", fontSize = 12.sp, color = p.sub, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        if (sendFailed && c.onOpen != null) TextAction("Open ${c.app}") { c.onOpen.invoke() }
                        else if (replying) TextAction("Cancel", p.sub) { replying = false; error = null }
                    }
                }
            }
            // Answers (Reply isn't needed as a button when the box is already open)
            AnimatedVisibility(visible = !replying && sent == null, exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier.padding(top = 14.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    c.choices.forEach { ch ->
                        if (!(inline && ch.type == ChoiceType.Reply)) ReplyChip(ch.label, ch.primary) { choose(ch) }
                    }
                }
            }
        }
        // Swipe hints
        val right = c.choices[c.swipeRight].label
        val left = c.choices.last().label
        SwipeLabel(right, p.accent, Modifier.align(Alignment.TopStart).padding(16.dp)) { (offsetX.value / threshold).coerceIn(0f, 1f) }
        SwipeLabel(left, p.sub, Modifier.align(Alignment.TopEnd).padding(16.dp)) { (-offsetX.value / threshold).coerceIn(0f, 1f) }
    }
}

@Composable
private fun SwipeLabel(text: String, color: Color, modifier: Modifier, alpha: () -> Float) {
    val p = LocalPalette.current
    Text(
        text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = modifier.graphicsLayer { this.alpha = alpha() }
            .clip(RoundedCornerShape(12.dp)).background(p.card)
            .border2(color)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

private fun Modifier.border2(color: Color) = this.then(
    Modifier.border(2.dp, color, RoundedCornerShape(12.dp))
)

// ======================= Done state, filtered, upcoming, undo =======================

/** Nothing waiting: a green ring with a tick, "All clear", and how many you handled. */
@Composable
private fun AllClear() {
    val p = LocalPalette.current
    val scale = remember { Animatable(0.5f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f)) }
    val done = BriefSession.answered
    Tile(null, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                    .border(6.dp, TileColors.green, CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Check, null, tint = TileColors.green, modifier = Modifier.size(32.dp)) }
            Column(Modifier.padding(start = 16.dp)) {
                Text("All clear", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = p.text)
                Text(
                    when (done) {
                        0 -> "Nothing waiting for you"
                        1 -> "Nothing waiting. You handled 1 thing."
                        else -> "Nothing waiting. You handled $done things."
                    },
                    fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun FilteredBox(filtered: List<Notif>, expanded: Boolean, onToggle: () -> Unit, onAdd: (Notif) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    GroupBox(Modifier.padding(top = 4.dp).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(Icons.Default.Lock, 30)
            Spacer(Modifier.width(12.dp))
            Text(
                if (filtered.size == 1) "1 code or promotion kept away" else "${filtered.size} codes and promotions kept away",
                fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f)
            )
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = p.sub)
        }
        if (expanded) {
            filtered.forEach { n ->
                GroupDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { openApp(ctx, n.pkg) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(n.pkg, 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.title.ifBlank { n.app }, fontSize = 15.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.body, fontSize = 13.sp, color = p.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextAction("Add") { onAdd(n) }
                }
            }
            GroupDivider(start = 18.dp)
            Box(Modifier.fillMaxWidth().padding(6.dp), contentAlignment = Alignment.CenterEnd) {
                TextAction("Clear all") { filtered.forEach { Repo.setNotifStatus(it.id, "dismissed") } }
            }
        }
    }
}

@Composable
private fun UpcomingBox(upcoming: List<Task>, events: List<CalEvent>, onEdit: (Task) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    // Your reminders and your Samsung Calendar events, in time order
    val rows: List<Pair<Long, Any>> = (upcoming.map { (it.due ?: 0L) to it } + events.map { it.begin to it })
        .sortedBy { it.first }.take(6)
    GroupBox(Modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = 300f))) {
        if (rows.isEmpty()) {
            Text("Nothing scheduled yet", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(18.dp))
        }
        rows.forEachIndexed { i, (time, x) ->
            if (i > 0) GroupDivider(start = 64.dp)
            when (x) {
                is Task -> {
                    val hl = BriefSession.highlight == x.id
                    val bg by animateColorAsState(if (hl) p.pill else Color.Transparent, tween(900), label = "hl")
                    LaunchedEffect(hl) { if (hl) { delay(1600); BriefSession.highlight = null } }
                    Row(
                        Modifier.fillMaxWidth().background(bg).clickable { onEdit(x) }.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(p.field), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Notifications, null, tint = p.pillText, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(x.title, fontSize = 15.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Fmt.whenText(time), fontSize = 12.sp, color = p.accent, modifier = Modifier.padding(top = 1.dp))
                        }
                    }
                }
                is CalEvent -> {
                    val col = Color(x.color or 0xFF000000.toInt())
                    Row(
                        Modifier.fillMaxWidth().clickable { Cal.open(ctx, x) }.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(col.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DateRange, null, tint = col, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(x.title, fontSize = 15.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Fmt.whenText(x.begin)}  ${x.calName}", fontSize = 12.sp, color = p.sub, maxLines = 1,
                                modifier = Modifier.padding(top = 1.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UndoPill(info: UndoInfo?, modifier: Modifier, onClose: () -> Unit) {
    // Keeps the last message visible while the pill slides away
    val holder = remember { arrayOfNulls<UndoInfo>(1) }
    if (info != null) holder[0] = info
    val last = holder[0]
    AnimatedVisibility(
        visible = info != null,
        modifier = modifier.navigationBarsPadding().padding(bottom = 92.dp),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        val shown = last
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF2C2C2E))
                .padding(start = 18.dp, end = if (shown?.undo != null) 6.dp else 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(shown?.text.orEmpty(), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
            shown?.undo?.let { u ->
                Spacer(Modifier.width(6.dp))
                Text(
                    "Undo", color = Color(0xFF6AB0FF), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { u(); onClose() }.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}


// ======================= Clear all =======================

@Composable
private fun DeckFooter(count: Int, onClear: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("1 of $count · swipe for the next", fontSize = 13.sp, color = p.sub, modifier = Modifier.weight(1f))
        Text(
            "Clear all", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.accent,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClear)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        )
    }
}

/** Pick what to clear: one app, old ones, all notification questions, or everything. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearSheet(asks: List<Ask>, onDismiss: () -> Unit, onClear: (List<Ask>, String) -> Unit) {
    val p = LocalPalette.current
    val now = System.currentTimeMillis()
    val notifs = asks.filterIsInstance<Ask.FromNotif>()
    val byApp = notifs.groupBy { it.n.pkg }.entries.sortedByDescending { it.value.size }.take(5)
    val old = notifs.filter { it.n.time < now - 2 * 86_400_000L }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Text("Clear what's waiting", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.padding(start = 6.dp))
            Text(
                "They leave your Brief. Nothing is removed from your phone's notifications.", fontSize = 13.sp, color = p.sub,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 14.dp)
            )
            if (byApp.size > 1 || old.isNotEmpty()) {
                ClearGroup {
                    if (byApp.size > 1) byApp.forEachIndexed { i, (pkg, list) ->
                        if (i > 0) ClearDivider()
                        val app = list.first().n.app
                        ClearOption("Only from $app", list.size, icon = { AppIcon(pkg, 26.dp) }) { onClear(list, app) }
                    }
                    if (old.isNotEmpty()) {
                        if (byApp.size > 1) ClearDivider()
                        ClearOption("Older than 2 days", old.size, icon = { CircleIcon(Icons.Default.DateRange, 26) }) { onClear(old, "older than 2 days") }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            ClearGroup {
                if (notifs.isNotEmpty() && notifs.size != asks.size) {
                    ClearOption("All notification questions", notifs.size) { onClear(notifs, "notifications") }
                    ClearDivider()
                }
                ClearOption("Clear everything", asks.size, color = p.danger) { onClear(asks, "everything") }
            }
        }
    }
}

@Composable
private fun ClearGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LocalPalette.current.field), content = content)
}

@Composable
private fun ClearDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.divider))

@Composable
private fun ClearOption(label: String, count: Int, color: Color? = null, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { icon(); Spacer(Modifier.width(12.dp)) }
        Text(label, fontSize = 15.sp, color = color ?: p.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", fontSize = 13.sp, color = p.sub)
    }
}
