package com.anas.secretary.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.data.Notif
import com.anas.secretary.data.Person
import com.anas.secretary.data.Repo
import com.anas.secretary.data.SavedItem
import com.anas.secretary.data.Task
import com.anas.secretary.data.Tracker
import com.anas.secretary.logic.LinkPreview
import com.anas.secretary.util.Fmt
import com.anas.secretary.util.Prefs
import com.anas.secretary.util.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ======================= Search everything =======================

/** Opens the search page from the header of the main pages; null where there's no search button. */
val LocalOpenSearch = staticCompositionLocalOf<(() -> Unit)?> { null }

private class Results(
    val tasks: List<Task> = emptyList(), val notifs: List<Notif> = emptyList(), val saved: List<SavedItem> = emptyList(),
    val people: List<Person> = emptyList(), val things: List<Tracker> = emptyList()
) {
    val isEmpty get() = tasks.isEmpty() && notifs.isEmpty() && saved.isEmpty() && people.isEmpty() && things.isEmpty()
}

private val kinds = listOf("All", "Saved", "Messages", "Tasks", "People", "Things")

/** Everything is searched on the phone, in the app's own database. Nothing leaves the phone. */
private fun find(q: String, kind: Int): Results {
    val now = System.currentTimeMillis()
    val t = q.trim()
    fun has(vararg s: String?) = s.any { it != null && it.contains(t, ignoreCase = true) }
    val F = com.anas.secretary.util.Features
    val allowed = setOf(3) + listOfNotNull(1.takeIf { F.saved }, 2.takeIf { F.messages }, 4.takeIf { F.people }, 5.takeIf { F.things })
    fun on(k: Int) = k in allowed && (kind == 0 || kind == k)
    if (kind != 0 && kind !in allowed) return Results()
    if (t.isEmpty()) {
        // No words yet but a type picked: show the newest of that type
        return when (kind) {
            1 -> Results(saved = Repo.saved().take(30))
            2 -> {
                val midnight = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                Results(notifs = Repo.notifs(true).filter { it.time >= midnight }.take(40))
            }
            3 -> Results(tasks = Repo.tasks().filter { !it.done }.take(40))
            4 -> Results(people = Repo.people())
            5 -> Results(things = Repo.trackers())
            else -> Results()
        }
    }
    return Results(
        tasks = if (on(3)) Repo.tasks().filter { has(it.title, it.notes) }.take(30) else emptyList(),
        notifs = if (on(2)) Repo.searchNotifs(t) else emptyList(),
        saved = if (on(1)) Repo.saved().filter { has(it.title, it.note, it.url, it.body, it.source) }.take(30) else emptyList(),
        people = if (on(4)) Repo.people().filter { has(it.name, it.contactName, it.relation) } else emptyList(),
        things = if (on(5)) Repo.trackers().filter { has(it.name) } else emptyList()
    )
}

/** The text with every place it matches [q] lit up. */
@Composable
private fun lit(text: String, q: String): AnnotatedString {
    val p = LocalPalette.current
    val t = q.trim()
    if (t.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val j = text.indexOf(t, i, ignoreCase = true)
            if (j < 0) { append(text.substring(i)); break }
            append(text.substring(i, j))
            pushStyle(SpanStyle(background = p.accent.copy(alpha = 0.3f), color = p.text))
            append(text.substring(j, j + t.length))
            pop()
            i = j + t.length
        }
    }
}

/** Part of a long text around the first match, so the match is visible in one line. */
private fun around(text: String, q: String): String {
    val one = text.replace('\n', ' ')
    val j = one.indexOf(q.trim(), ignoreCase = true)
    return if (j > 40) "…" + one.substring(j - 30) else one
}

@Composable
fun SearchScreen(v: Int, onClose: () -> Unit, onOpenTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    var q by remember { mutableStateOf("") }
    var kind by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var recent by remember { mutableStateOf(Prefs.recentSearches) }
    val focus = remember { FocusRequester() }
    val results by produceState(Results(), q, kind, v) {
        delay(if (q.isBlank()) 0 else 150) // wait for a pause in typing
        value = withContext(Dispatchers.IO) { find(q, kind) }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onClose)

    fun keep() {
        val t = q.trim()
        if (t.length >= 2) { recent = listOf(t) + recent.filter { !it.equals(t, true) }; Prefs.recentSearches = recent }
    }
    fun leave(tab: Int) { keep(); onClose(); onOpenTab(tab) }
    val cards = remember(v) { Repo.cards().associateBy { it.id } }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        // Search box
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowBack, "Back", tint = p.text)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(p.card).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(LookIcons.Search, null, tint = p.sub, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search Daybrief", fontSize = 16.sp, color = p.sub)
                    BasicTextField(
                        q, { q = it }, singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, color = p.text), cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keep(); keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                }
                if (q.isNotEmpty()) Icon(Icons.Default.Close, "Clear", tint = p.sub, modifier = Modifier.size(20.dp).clip(CircleShape).clickable { q = "" })
            }
        }
        // Type chips
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            kinds.forEachIndexed { i, k ->
                val on = i == kind
                Text(
                    k, fontSize = 13.sp, color = if (on) Color.White else p.sub, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.card)
                        .clickable { kind = i }.padding(horizontal = 13.dp, vertical = 7.dp)
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 40.dp)) {
            if (q.isBlank() && kind == 0) {
                if (recent.isNotEmpty()) {
                    item(key = "recentLabel") { SectionLabel("Recent") }
                    item(key = "recent") {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            recent.forEach { r ->
                                Text(
                                    r, fontSize = 13.sp, color = p.text,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(p.card).clickable { q = r }.padding(horizontal = 13.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
                item(key = "tryLabel") { SectionLabel("Try") }
                item(key = "try") {
                    GroupBox {
                        ResultRow({ Glyph(LookIcons.Clock) }, AnnotatedString("Messages from today"), null) { kind = 2 }
                        GroupDivider(start = 58.dp)
                        ResultRow({ Glyph(LookIcons.Saved) }, AnnotatedString("Everything you saved"), null) { kind = 1 }
                        GroupDivider(start = 58.dp)
                        ResultRow({ Glyph(LookIcons.Calendar) }, AnnotatedString("Overdue tasks"), null) { CalState.mode = 2; leave(1) }
                    }
                }
                return@LazyColumn
            }
            if (results.isEmpty) {
                item(key = "none") {
                    Text(
                        if (q.isBlank()) "Nothing here yet" else "Nothing matches “${q.trim()}”",
                        fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(start = 10.dp, top = 24.dp)
                    )
                }
            }
            group("Tasks", results.tasks) { t ->
                ResultRow({ Glyph(Icons.Default.DateRange) }, lit(t.title, q), t.due?.let { Fmt.whenText(it) } ?: if (t.done) "Done" else "No date") {
                    keep(); editing = t
                }
            }
            group("Messages", results.notifs) { n ->
                ResultRow({ AppIcon(n.pkg, 32.dp) }, lit("${n.title.ifBlank { n.app }} · ${n.app}", q), null, lit(around(n.body, q), q), Fmt.short(n.time)) {
                    keep()
                    ctx.packageManager.getLaunchIntentForPackage(n.pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            group("Saved", results.saved) { s ->
                val title = s.title ?: s.note.ifBlank { s.url ?: s.body }
                ResultRow(
                    { if (s.image != null) Thumb(s.image, Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))) else Glyph(Icons.Default.Star) },
                    lit(title, q), "${s.source.ifBlank { LinkPreview.domain(s.url) }} · ${Fmt.ago(s.created)}"
                ) {
                    keep()
                    if (s.url != null) try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { leave(3) }
                    else leave(3)
                }
            }
            group("People", results.people) { person ->
                ResultRow({ PersonAvatar(person, 32.dp, showRing = false) }, lit(person.name, q), person.daysSince()?.let { "Last talked $it days ago" } ?: person.relation.ifBlank { null }) {
                    TrackState.seg = 1; PeopleState.openRequest = person.id; leave(2)
                }
            }
            group("Things", results.things) { t ->
                ResultRow({ Glyph(LookIcons.Things) }, lit(t.name, q), "${cards[t.cardId]?.name ?: ""} · ${Track.ago(t.since)}") {
                    TrackState.seg = 0; leave(2)
                }
            }
        }
    }

    editing?.let { t ->
        TaskDialog(
            "Edit task", t.title, t.notes, t.due, image = t.image, initWork = t.work,
            onDelete = { Repo.deleteTask(t.id); editing = null },
            onDismiss = { editing = null },
            onSave = { a, b, c, w -> Repo.updateTask(t.id, a, b, c, w); editing = null }
        )
    }
}

/** A labelled group of up to 5 results (all of them when its type is picked). */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.group(label: String, list: List<T>, row: @Composable (T) -> Unit) {
    if (list.isEmpty()) return
    item(key = "l$label") {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 8.dp)) {
            Text(label, fontSize = 13.sp, color = LocalPalette.current.sub, modifier = Modifier.weight(1f))
            Text("${list.size}", fontSize = 13.sp, color = LocalPalette.current.sub)
        }
    }
    item(key = "g$label") {
        GroupBox {
            list.forEachIndexed { i, x ->
                if (i > 0) GroupDivider(start = 58.dp)
                row(x)
            }
        }
    }
}

@Composable
private fun Glyph(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val p = LocalPalette.current
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(p.field), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = p.text, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ResultRow(
    icon: @Composable () -> Unit, title: AnnotatedString, sub: String?,
    body: AnnotatedString? = null, side: String? = null, onClick: () -> Unit
) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (body != null) Text(body, fontSize = 13.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (sub != null) Text(sub, fontSize = 12.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (side != null) Text(side, fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Round search button for the page headers. */
@Composable
fun SearchButton() {
    val open = LocalOpenSearch.current ?: return
    val p = LocalPalette.current
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = open), contentAlignment = Alignment.Center) {
        Icon(LookIcons.Search, "Search", tint = p.text, modifier = Modifier.size(23.dp))
    }
}
