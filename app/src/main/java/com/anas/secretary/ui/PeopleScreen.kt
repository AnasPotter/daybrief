package com.anas.secretary.ui

import android.Manifest
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.anas.secretary.data.Person
import com.anas.secretary.data.Repo
import com.anas.secretary.util.Bg
import com.anas.secretary.util.Contacts
import com.anas.secretary.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object PeopleState {
    var addRequest by mutableIntStateOf(0)
    /** Set by the People widget: open this person's sheet. */
    var openRequest by mutableStateOf<Long?>(null)
    var filter by mutableIntStateOf(0)
}

private val personColors = listOf(
    Color(0xFFD4537E), Color(0xFF378ADD), Color(0xFF1D9E75), Color(0xFF7F77DD), Color(0xFFD85A30), Color(0xFFBA7517)
)

fun personColor(name: String) = personColors[(name.hashCode() and 0x7fffffff) % personColors.size]

fun daysAgo(d: Int) = when (d) { 0 -> "Today"; 1 -> "Yesterday"; else -> "$d days ago" }

private val goals = listOf(1 to "Every day", 3 to "Every 3 days", 7 to "Every week", 14 to "Every 2 weeks", 30 to "Every month")
private fun goalLabel(g: Int) = goals.firstOrNull { it.first == g }?.second ?: "Every $g days"
private val cats = listOf("family" to "Family", "friends" to "Friends", "work" to "Work", "other" to "Other")

/** Round photo (or first letter) with a ring that fills up as days pass. */
@Composable
fun PersonAvatar(p: Person, size: Dp, showRing: Boolean = true) {
    val pal = LocalPalette.current
    val days = p.daysSince()
    val frac = if (days == null) 1f else (days.toFloat() / p.goal).coerceIn(0f, 1f)
    val anim by animateFloatAsState(frac, tween(700), label = "ring")
    val ringColor = if (p.isDue()) pal.danger else personColor(p.name)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (showRing) {
            Canvas(Modifier.fillMaxSize()) {
                val st = (size.toPx() / 16f).coerceAtLeast(2.dp.toPx())
                val arc = Size(this.size.width - st, this.size.height - st)
                drawArc(pal.divider, -90f, 360f, false, Offset(st / 2, st / 2), arc, style = Stroke(st))
                drawArc(ringColor, -90f, 360f * anim, false, Offset(st / 2, st / 2), arc, style = Stroke(st, cap = StrokeCap.Round))
            }
        }
        val inner = if (showRing) size * 0.78f else size
        Box(Modifier.size(inner).clip(CircleShape).background(personColor(p.name)), contentAlignment = Alignment.Center) {
            Text(p.name.take(1).uppercase(), color = Color.White, fontSize = (inner.value * 0.42f).sp, fontWeight = FontWeight.Medium)
            if (p.photo != null) {
                AsyncImage(model = Uri.parse(p.photo), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun CategoryFilter(labels: List<String>, selected: Int, counts: List<Int>?, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) p.text else p.card).clickable { if (i != selected) Feel.tick(view); onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(l, fontSize = 13.sp, color = if (on) p.bg else p.sub, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
                counts?.getOrNull(i)?.let { Text("  $it", fontSize = 13.sp, color = (if (on) p.bg else p.sub).copy(alpha = 0.6f)) }
            }
        }
    }
}

@Composable
fun PeopleScreen(v: Int, tick: Int, extra: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val people = remember(v) { Repo.people() }
    var picked by remember { mutableStateOf<Contacts.Picked?>(null) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var permTick by remember { mutableIntStateOf(0) }
    val canLog = remember(tick, permTick) { Contacts.canCallLog(ctx) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            picked = Contacts.read(ctx, uri)
            if (picked == null) Toast.makeText(ctx, "Couldn't read that contact", Toast.LENGTH_SHORT).show()
        }
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
        picker.launch(null)
    }
    fun startAdd() {
        if (!Contacts.canContacts(ctx) || !Contacts.canCallLog(ctx)) {
            perms.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG))
        } else picker.launch(null)
    }
    LaunchedEffect(PeopleState.addRequest) {
        if (PeopleState.addRequest > 0) { PeopleState.addRequest = 0; startAdd() }
    }
    LaunchedEffect(tick, canLog) { withContext(Dispatchers.IO) { Contacts.syncCallLog(ctx) } }

    val filters = listOf("All", "Family", "Friends", "Work")
    val shown = people.filter {
        when (PeopleState.filter) { 1 -> it.cat == "family"; 2 -> it.cat == "friends"; 3 -> it.cat == "work"; else -> true }
    }
    val now = System.currentTimeMillis()
    val due = shown.filter { it.isDue(now) }.sortedByDescending { (it.daysSince(now) ?: 9999).toFloat() / it.goal }
    val ok = shown.filter { !it.isDue(now) }.sortedByDescending { it.lastContact ?: 0L }
    val dueAll = people.count { it.isDue(now) }

    OneUiScreen(
        "People",
        when (dueAll) { 0 -> if (people.isEmpty()) "The people who matter to you" else "You're in touch with everyone"; 1 -> "1 person misses you"; else -> "$dueAll people miss you" },
        extra = extra, page = "Track"
    ) {
        if (!canLog && people.isNotEmpty()) {
            item(key = "perm") {
                GroupBox(Modifier.padding(bottom = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            perms.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG))
                        }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircleIcon(Icons.Default.Call, 30)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Allow call history", fontSize = 15.sp, color = p.text)
                            Text("So I can see when you last talked", fontSize = 13.sp, color = p.sub)
                        }
                        Text("Allow", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        if (people.isEmpty()) {
            item(key = "empty") {
                Column {
                    EmptyState(
                        Icons.Default.Person, "Add the people who matter",
                        "Your family and friends. I'll keep track of when you last talked and remind you when it's been a while."
                    )
                    Spacer(Modifier.height(12.dp))
                    BigButton("Add a person") { startAdd() }
                }
            }
        } else {
            item(key = "filter") { Box(Modifier.padding(bottom = 4.dp)) { CategoryFilter(filters, PeopleState.filter, null) { PeopleState.filter = it } } }
            if (due.isNotEmpty()) {
                item(key = "due-l") { SectionLabel("Time to reach out") }
                item(key = "due") {
                    GroupBox(Modifier.animateContentSize()) {
                        due.forEachIndexed { i, x -> if (i > 0) GroupDivider(start = 78.dp); PersonRow(x, { openId = x.id }) }
                    }
                }
            }
            if (ok.isNotEmpty()) {
                item(key = "ok-l") { SectionLabel("In touch") }
                item(key = "ok") {
                    GroupBox(Modifier.animateContentSize()) {
                        ok.forEachIndexed { i, x -> if (i > 0) GroupDivider(start = 78.dp); PersonRow(x, { openId = x.id }) }
                    }
                }
            }
            item(key = "foot") {
                Text(
                    "Updated from your call history and WhatsApp calls automatically.",
                    fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(start = 20.dp, top = 14.dp)
                )
            }
        }
    }

    picked?.let { c ->
        AddPersonDialog(c, onDismiss = { picked = null }) { name, relation, cat, goal ->
            val id = Repo.addPerson(name, c.name, relation, cat, c.contactId, c.phones, c.photo, c.birthday, goal)
            picked = null
            Bg.scope.launch { Repo.person(id)?.let { Contacts.syncCallLog(ctx.applicationContext, it) } }
        }
    }
    LaunchedEffect(PeopleState.openRequest) {
        PeopleState.openRequest?.let { openId = it; PeopleState.openRequest = null }
    }
    openId?.let { id -> PersonSheet(id, v) { openId = null } }
}

@Composable
private fun PersonRow(x: Person, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val days = x.daysSince()
    val bday = Contacts.daysToBirthday(x.birthday)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PersonAvatar(x, 50.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(x.name, fontSize = 16.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (x.relation.isNotBlank() && !x.relation.equals(x.name, true)) {
                    Text("  ${x.relation}", fontSize = 12.sp, color = p.sub, maxLines = 1)
                }
            }
            Text(
                if (days == null) "No calls in the last 90 days" else "${daysAgo(days)}  ${x.lastHow}",
                fontSize = 13.sp, color = if (x.isDue()) p.alert else p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (bday != null && bday.first <= 7) {
                Text(
                    when (bday.first) { 0 -> "Birthday today"; 1 -> "Birthday tomorrow"; else -> "Birthday in ${bday.first} days" },
                    fontSize = 12.sp, color = p.warn, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (x.isDue() && x.phones.isNotEmpty()) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(p.accent).clickable { Contacts.dial(ctx, x.phones.first()) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Call, "Call ${x.name}", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun ChipChoice(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) p.text else p.field).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(label, fontSize = 13.sp, color = if (on) p.bg else p.text, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal) }
}

@Composable
private fun AddPersonDialog(c: Contacts.Picked, onDismiss: () -> Unit, onSave: (String, String, String, Int) -> Unit) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(c.name) }
    var relation by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("family") }
    var goal by remember { mutableIntStateOf(7) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp), containerColor = p.card,
        title = { Text("Add ${c.name}", fontSize = 20.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (c.phones.isEmpty()) Text("This contact has no phone number, so calls can't be tracked.", fontSize = 13.sp, color = p.danger)
                OutlinedTextField(name, { name = it }, label = { Text("Show as") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(relation, { relation = it }, label = { Text("Who are they? (Mom, brother, friend…)") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cats.forEach { (k, l) -> ChipChoice(l, cat == k) { cat = k } }
                }
                Text("Remind me to reach out", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    goals.forEach { (g, l) -> ChipChoice(l, goal == g) { goal = g } }
                }
            }
        },
        confirmButton = { TextAction("Add") { if (name.isNotBlank()) onSave(name.trim(), relation.trim(), cat, goal) } },
        dismissButton = { TextAction("Cancel", p.sub, onDismiss) }
    )
}

private fun kindLabel(k: String) = when (k) {
    "call_out" -> "Outgoing call"
    "call_in" -> "Incoming call"
    "missed" -> "Missed call"
    "call_try" -> "Call not answered"
    "wa_call" -> "WhatsApp call"
    else -> "You marked that you talked"
}

@Composable
private fun PersonSheet(id: Long, v: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val x = remember(v) { Repo.person(id) }
    val notes = remember(v) { Repo.notesFor(id) }
    val log = remember(v) { Repo.interactions(id) }
    var newNote by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (x == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val number = x.phones.firstOrNull()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.navigationBarsPadding().imePadding().padding(10.dp).fillMaxWidth().fillMaxHeight(0.86f)
                    .clip(RoundedCornerShape(32.dp)).background(p.card)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .verticalScroll(rememberScrollState()).padding(20.dp)
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(p.divider))
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(x, 60.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(x.name, fontSize = 22.sp, color = p.text)
                        Text(
                            listOf(x.relation, cats.firstOrNull { it.first == x.cat }?.second ?: "").filter { it.isNotBlank() }.distinct().joinToString("  "),
                            fontSize = 13.sp, color = p.sub
                        )
                    }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit", tint = p.sub) }
                }
                val days = x.daysSince()
                Text(
                    if (days == null) "No calls in the last 90 days" else "Last talked ${daysAgo(days).lowercase()}: ${x.lastHow}",
                    fontSize = 14.sp, color = if (x.isDue()) p.alert else p.sub, modifier = Modifier.padding(top = 12.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Call", Modifier.weight(1f)) { Contacts.dial(ctx, number) }
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(p.good)
                            .clickable { Contacts.whatsapp(ctx, number) }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("WhatsApp", color = p.onGood, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(p.field)
                            .clickable {
                                pickDateTime(ctx, null) { at ->
                                    Repo.addTask(
                                        "Call ${x.name}", notes.joinToString("\n") { "• " + it.text }, at, "person",
                                        work = x.cat == "work", personId = x.id
                                    )
                                    Repo.snoozePerson(x.id, at + 3 * 3_600_000L)
                                    Toast.makeText(ctx, "Reminder set for ${Fmt.whenText(at)}", Toast.LENGTH_SHORT).show()
                                }
                            }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Remind", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                    TextAction("I talked to ${x.name} today") {
                        Repo.addInteraction(x.id, "manual", 0, System.currentTimeMillis())
                        Toast.makeText(ctx, "Noted", Toast.LENGTH_SHORT).show()
                    }
                }

                Text("Reach out", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    goals.forEach { (g, l) -> ChipChoice(l, x.goal == g) { Repo.updatePerson(x.id, x.name, x.relation, x.cat, g) } }
                }

                Text("Remember for next time", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.field)) {
                    notes.forEach { n ->
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(p.accent))
                            Text(n.text, fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp))
                            IconButton(onClick = { Repo.deletePersonNote(n.id) }) { Icon(Icons.Default.Close, "Remove", tint = p.sub, modifier = Modifier.size(18.dp)) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            newNote, { newNote = it }, placeholder = { Text("Something to ask or tell ${x.name}") },
                            shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.weight(1f), maxLines = 3
                        )
                        TextAction("Add") { if (newNote.isNotBlank()) { Repo.addPersonNote(x.id, newNote.trim()); newNote = "" } }
                    }
                }

                x.birthday?.let { b ->
                    val d = Contacts.daysToBirthday(b)
                    Text(
                        "Birthday: " + (runCatching {
                            LocalDate.of(2000, b.take(2).toInt(), b.takeLast(2).toInt()).format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
                        }.getOrNull() ?: b) + (d?.let { if (it.first == 0) " (today!)" else " (in ${it.first} days)" } ?: ""),
                        fontSize = 14.sp, color = p.warn, modifier = Modifier.padding(top = 16.dp)
                    )
                }

                Text("Recent", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.field)) {
                    if (log.isEmpty()) Text("No calls found yet", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(14.dp))
                    log.forEachIndexed { i, l ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(p.divider))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (l.kind == "missed" || l.kind == "call_try") Icons.Default.Clear else Icons.Default.Call, null,
                                tint = if (l.kind == "missed") p.danger else p.sub, modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                kindLabel(l.kind) + if (l.duration > 0) ", ${if (l.duration < 60) "${l.duration}s" else "${l.duration / 60} min"}" else "",
                                fontSize = 14.sp, color = p.text, modifier = Modifier.weight(1f)
                            )
                            Text(Fmt.short(l.time), fontSize = 12.sp, color = p.sub)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                    TextAction("Remove ${x.name} from People", p.danger) { confirmDelete = true }
                }
            }
        }
    }

    if (editing) {
        var name by remember { mutableStateOf(x.name) }
        var relation by remember { mutableStateOf(x.relation) }
        var cat by remember { mutableStateOf(x.cat) }
        AlertDialog(
            onDismissRequest = { editing = false },
            shape = RoundedCornerShape(28.dp), containerColor = p.card,
            title = { Text("Edit", fontSize = 20.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Show as") }, singleLine = true,
                        shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(relation, { relation = it }, label = { Text("Who are they?") }, singleLine = true,
                        shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cats.forEach { (k, l) -> ChipChoice(l, cat == k) { cat = k } }
                    }
                }
            },
            confirmButton = { TextAction("Save") { Repo.updatePerson(x.id, name.trim().ifBlank { x.name }, relation.trim(), cat, x.goal); editing = false } },
            dismissButton = { TextAction("Cancel", p.sub) { editing = false } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RoundedCornerShape(28.dp), containerColor = p.card,
            title = { Text("Remove ${x.name}?", fontSize = 20.sp) },
            text = { Text("Their notes and call history in Daybrief will be deleted. Your phone contact stays.", color = p.sub) },
            confirmButton = { TextAction("Remove", p.danger) { Repo.deletePerson(x.id); confirmDelete = false; onDismiss() } },
            dismissButton = { TextAction("Cancel", p.sub) { confirmDelete = false } }
        )
    }
}
