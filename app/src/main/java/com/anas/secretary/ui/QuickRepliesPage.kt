package com.anas.secretary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.anas.secretary.logic.RuleEngine
import com.anas.secretary.util.Prefs
import kotlin.math.roundToInt

private const val MAX_REPLIES = 6

/** Settings → Smart prompts → Quick replies: the user writes the replies shown under messages. */
@Composable
fun QuickRepliesPage(onBack: () -> Unit) {
    var appFirst by remember { mutableStateOf(Prefs.appRepliesFirst) }
    var any by remember { mutableStateOf((Prefs.myReplies ?: RuleEngine.defaultReplies).distinct()) }
    var time by remember { mutableStateOf((Prefs.myTimeReplies ?: RuleEngine.defaultTimeReplies).distinct()) }
    // Which reply the sheet edits: list ("any"/"time") and index (-1 = new)
    var editing by remember { mutableStateOf<Pair<String, Int>?>(null) }

    // No repeats: each reply is one row (and one key in the list)
    var custom by remember { mutableStateOf(Prefs.myReplies != null || Prefs.myTimeReplies != null) }
    fun saveAny(v: List<String>) { any = v.distinct(); Prefs.myReplies = any; custom = true }
    fun saveTime(v: List<String>) { time = v.distinct(); Prefs.myTimeReplies = time; custom = true }

    editing?.let { (which, i) ->
        val list = if (which == "any") any else time
        ReplySheet(
            start = list.getOrNull(i).orEmpty(), isNew = i < 0,
            onSave = { t ->
                val next = if (i < 0) list + t else list.toMutableList().also { it[i] = t }
                if (which == "any") saveAny(next) else saveTime(next)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    OneUiScreen("Quick replies", "Shown under messages you can answer") {
        item(key = "back") { Box(Modifier.padding(bottom = 8.dp)) { TextAction("Back to Smart prompts", onClick = onBack) } }
        item(key = "first") {
            GroupBox {
                Row(
                    Modifier.fillMaxWidth().clickable { appFirst = !appFirst; Prefs.appRepliesFirst = appFirst }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("The app's own suggestions first", fontSize = 16.sp, color = LocalPalette.current.text)
                        Text("When WhatsApp or Messages suggest replies", fontSize = 13.sp, color = LocalPalette.current.sub,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = appFirst, onCheckedChange = { appFirst = it; Prefs.appRepliesFirst = it })
                }
            }
        }
        item(key = "any") {
            Column {
                SectionLabel("Any message")
                ReplyList(any, onEdit = { editing = "any" to it }, onAdd = { editing = "any" to -1 }, onChange = ::saveAny)
            }
        }
        item(key = "time") {
            Column {
                SectionLabel("When the message mentions a time")
                ReplyList(time, onEdit = { editing = "time" to it }, onAdd = { editing = "time" to -1 }, onChange = ::saveTime)
            }
        }
        item(key = "reset") {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                TextAction("Reset to the default replies", LocalPalette.current.danger) {
                    Prefs.myReplies = null; Prefs.myTimeReplies = null
                    any = RuleEngine.defaultReplies; time = RuleEngine.defaultTimeReplies; custom = false
                }
            }
            if (!custom) Text(
                "Until you change them, messages in Arabic get the same replies in Arabic.",
                fontSize = 12.sp, color = LocalPalette.current.sub,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
    }
}

/** One list: tap to edit, ✕ to remove, hold ⋮⋮ and drag to reorder. */
@Composable
private fun ReplyList(list: List<String>, onEdit: (Int) -> Unit, onAdd: () -> Unit, onChange: (List<String>) -> Unit) {
    val p = LocalPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    var order by remember(list) { mutableStateOf(list) }
    var dragging by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowH by remember { mutableIntStateOf(1) }
    // The drag gesture lives across recompositions, so it reads the newest list and callback through these
    val curList by rememberUpdatedState(list)
    val curChange by rememberUpdatedState(onChange)
    GroupBox {
        order.forEachIndexed { i, r ->
          // Keyed by the text, so a row (and its running drag) moves with the reply when the order changes
          key(r) {
            if (i > 0) GroupDivider(start = 20.dp)
            val lifted = i == dragging
            Row(
                Modifier.fillMaxWidth()
                    .onSizeChanged { if (it.height > 0) rowH = it.height }
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer { translationY = if (lifted) offset else 0f; if (lifted) { scaleX = 1.02f; scaleY = 1.02f } }
                    .background(if (lifted) p.field else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onEdit(i) }
                    .padding(start = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Drag handle
                Box(
                    Modifier.size(44.dp)
                        // A short tap on the handle does nothing (it shouldn't open the edit sheet)
                        .pointerInput(Unit) { detectTapGestures { } }
                        // Keyed on the saved list: a save makes a new `order` state, and the gesture must use it
                        .pointerInput(list) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { Feel.longPress(view); dragging = order.indexOf(r); offset = 0f },
                            onDragEnd = { val o = order; dragging = -1; offset = 0f; if (o != curList) curChange(o) },
                            onDragCancel = { order = curList; dragging = -1; offset = 0f },
                            onDrag = { ch, d ->
                                ch.consume()
                                offset += d.y
                                val from = dragging
                                if (from < 0) return@detectDragGesturesAfterLongPress
                                val steps = (offset / rowH).roundToInt()
                                val to = (from + steps).coerceIn(0, order.size - 1)
                                if (to != from) {
                                    order = order.toMutableList().also { it.add(to, it.removeAt(from)) }
                                    offset -= (to - from) * rowH
                                    dragging = to
                                    Feel.tick(view)
                                }
                            }
                        )
                    },
                    contentAlignment = Alignment.Center
                ) { Text("⋮⋮", fontSize = 16.sp, color = p.sub, letterSpacing = (-2).sp) }
                Text(
                    r, fontSize = 15.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(textDirection = TextDirection.Content),
                    modifier = Modifier.weight(1f).padding(vertical = 13.dp)
                )
                Box(
                    Modifier.size(44.dp).clickable { onChange(order.filterIndexed { j, _ -> j != i }) },
                    contentAlignment = Alignment.Center
                ) { Text("✕", fontSize = 16.sp, color = p.sub) }
            }
          }
        }
        if (order.size < MAX_REPLIES) {
            if (order.isNotEmpty()) GroupDivider(start = 20.dp)
            Text(
                "+ Add a reply", fontSize = 15.sp, color = p.accent,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(horizontal = 20.dp, vertical = 15.dp)
            )
        } else {
            GroupDivider(start = 20.dp)
            Text("6 replies is the most for one list", fontSize = 13.sp, color = p.sub,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplySheet(start: String, isNew: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(start) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
    fun save() { val t = text.replace('\n', ' ').trim(); if (t.isNotEmpty()) onSave(t) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp).navigationBarsPadding().imePadding()) {
            Text(if (isNew) "New reply" else "Edit reply", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = p.text)
            BasicTextField(
                value = text, onValueChange = { text = it.replace('\n', ' ').take(120) },
                textStyle = TextStyle(fontSize = 16.sp, color = p.text, textDirection = TextDirection.Content),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(p.field).padding(16.dp)) {
                        if (text.isEmpty()) Text("For example: On my way 🚗", fontSize = 16.sp, color = p.sub)
                        inner()
                    }
                }
            )
            Text("Shown as a button you tap to send it right away", fontSize = 12.sp, color = p.sub,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            Box(Modifier.padding(top = 16.dp)) {
                BigButton("Save") { save() }
            }
        }
    }
}
