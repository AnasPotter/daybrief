package com.anas.secretary.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anas.secretary.data.Notif
import com.anas.secretary.data.Repo
import com.anas.secretary.data.SavedItem
import com.anas.secretary.data.Task
import com.anas.secretary.logic.LinkPreview
import com.anas.secretary.logic.RuleEngine
import com.anas.secretary.util.Fmt
import com.anas.secretary.util.Prefs
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)
    private val tab = mutableIntStateOf(0)
    /** A Quick add button from a widget: "task", "remind", "note" or "paste:<link>". */
    private val quick = mutableStateOf<String?>(null)
    private var pastePending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tab.intValue = intent.getIntExtra("tab", 0)
        fromWidget(intent)
        if ((com.anas.secretary.BuildConfig.PUBLIC || com.anas.secretary.util.Secret.isSet) &&
            System.currentTimeMillis() - com.anas.secretary.util.Prefs.lastUpdateCheck > 12 * 3_600_000L
        ) {
            com.anas.secretary.util.Bg.scope.launch {
                try { com.anas.secretary.util.Updater.check(applicationContext) } catch (_: Exception) {}
            }
        }
        setContent {
            SecretaryTheme {
                MainScreen(resumeTick.intValue, tab, quick)
                DateTimePickerHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab.intValue = intent.getIntExtra("tab", tab.intValue)
        fromWidget(intent)
    }

    /** Widgets open the app with extras saying what to show. */
    private fun fromWidget(i: Intent) {
        when (val q = i.getStringExtra("quick")) {
            null -> {}
            "paste" -> pastePending = true // the clipboard can only be read once the window has focus
            else -> quick.value = q
        }
        val personId = i.getLongExtra("personId", -1L)
        if (personId >= 0 || i.getBooleanExtra("people", false)) TrackState.seg = 1
        if (i.getBooleanExtra("health", false)) TrackState.seg = 2
        if (personId >= 0) PeopleState.openRequest = personId
        i.removeExtra("quick"); i.removeExtra("personId")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !pastePending) return
        pastePending = false
        val clip = try {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        } catch (_: Exception) { "" }
        val link = Regex("""https?://\S+""").find(clip)?.value
        if (link == null) Toast.makeText(this, "No link copied. Paste or type one.", Toast.LENGTH_SHORT).show()
        tab.intValue = 3
        quick.value = "paste:" + (link ?: "")
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        GlowClock.hour = java.time.LocalTime.now().hour
        com.anas.secretary.edge.EdgeLight.refresh()
        com.anas.secretary.service.NotifListener.refreshEdgeLighting()
    }
}

@Composable
fun MainScreen(tick: Int, tab: MutableIntState, quick: MutableState<String?>) {
    val p = LocalPalette.current
    val v by Repo.version.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    // New installs (notification access still off) start with the guided setup; anyone already set up never sees it
    val ctx = LocalContext.current
    remember {
        if (!Prefs.setupDone) {
            if (NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) Prefs.setupDone = true
            else SetupState.show = true
        }
        0
    }

    // Settings is opened from the avatar; Back goes to the page you came from
    var lastTab by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab.intValue) { if (tab.intValue != 4) lastTab = tab.intValue }
    // A tab whose part was switched off (or a widget link to it) falls back to Brief
    val shown = com.anas.secretary.util.Features.tabs()
    LaunchedEffect(shown, tab.intValue) { if (tab.intValue != 4 && tab.intValue !in shown) tab.intValue = 0 }
    BackHandler(enabled = tab.intValue == 4) { tab.intValue = lastTab }
    // Each time a tab opens (and when the app comes back), its cards rise in and numbers count up once
    remember(tab.intValue, tick) { Motion.visitAt = System.currentTimeMillis(); 0 }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        GlowBackground()
        CompositionLocalProvider(
            LocalOpenSettings provides (if (tab.intValue == 4) null else ({ tab.intValue = 4 })),
            LocalOpenSearch provides (if (tab.intValue == 4) null else ({ searching = true }))
        ) {
            Crossfade(targetState = tab.intValue, animationSpec = tween(220), label = "tabs") { t ->
                when (t) {
                    0 -> BriefScreen(v, tick, onOpenHealth = { TrackState.seg = 2; tab.intValue = 2 }, onOpenTab = { tab.intValue = it }) { tab.intValue = 4 }
                    1 -> CalendarScreen(v, tick)
                    2 -> TrackScreen(v, tick)
                    3 -> SavedScreen(v)
                    else -> SettingsScreen(v, tick)
                }
            }
        }
        // Keeps scrolled content from showing under the clock and battery icons
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(glowTop().copy(alpha = 0.92f)))
        // Cards fade out behind the bottom bar instead of showing half-covered under it
        val navH = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val fade = glowBottom()
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(84.dp + navH)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to fade.copy(alpha = 0f), 0.45f to fade.copy(alpha = 0.85f), 1f to fade
                ))
        )
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            LookBottomBar(tab.intValue, showAdd = tab.intValue != 4, visible = com.anas.secretary.util.Features.tabs(), onSelect = { tab.intValue = it }) {
                if (tab.intValue == 2) {
                    if (TrackState.seg == 1) PeopleState.addRequest++ else if (TrackState.seg == 0) TrackState.addRequest++
                } else showAdd = true
            }
        }
        if (SetupState.show) SetupScreen(tick)
        AnimatedVisibility(WeekState.show, enter = fadeIn(tween(180)), exit = fadeOut(tween(150))) {
            Box(Modifier.fillMaxSize().background(p.bg).pointerInput(Unit) { detectTapGestures { } }) {
                GlowBackground()
                WeekScreen(v) { WeekState.show = false }
            }
        }
        AnimatedVisibility(searching, enter = fadeIn(tween(180)), exit = fadeOut(tween(150))) {
            // Swallows taps in the gaps so nothing underneath (like the avatar) gets them
            Box(Modifier.fillMaxSize().background(p.bg).pointerInput(Unit) { detectTapGestures { } }) {
                GlowBackground()
                SearchScreen(v, onClose = { searching = false }) { tab.intValue = it }
            }
        }
    }

    if (showAdd && (tab.intValue == 0 || tab.intValue == 1)) {
        // In the calendar, a new reminder starts on the day you're looking at
        val preset = if (tab.intValue == 1 && CalState.day != java.time.LocalDate.now())
            CalState.day.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() else null
        TaskDialog("New task", initDue = preset, onDismiss = { showAdd = false }, onSave = { t, n, d, w ->
            Repo.addTask(t, n, d, "manual", work = w); showAdd = false
        })
    }
    if (showAdd && tab.intValue == 3) SavedDialog(onDismiss = { showAdd = false })

    // Quick add buttons on the home screen widget
    val q = quick.value
    when {
        q == "task" -> TaskDialog("New task", onDismiss = { quick.value = null }, onSave = { t, n, d, w ->
            Repo.addTask(t, n, d, "manual", work = w); quick.value = null
        })
        q == "remind" -> {
            val nextHour = java.time.LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            TaskDialog("New reminder", initDue = nextHour, onDismiss = { quick.value = null }, onSave = { t, n, d, w ->
                Repo.addTask(t, n, d, "manual", work = w); quick.value = null
            })
        }
        q == "note" -> SavedDialog(heading = "Quick note", onDismiss = { quick.value = null })
        q != null && q.startsWith("paste:") -> SavedDialog(initUrl = q.removePrefix("paste:"), onDismiss = { quick.value = null })
    }
}

// ======================= TASKS =======================

@Composable
fun TaskRow(t: Task, now: Long, onEdit: () -> Unit, onImage: (String) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundCheck(t.done) { Repo.setDone(t.id, !t.done) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, fontSize = 16.sp, color = if (t.done) p.sub else p.text,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (t.notes.isNotBlank() && !t.done) {
                Text(t.notes, fontSize = 13.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (t.due != null || t.work) {
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    t.due?.let { d ->
                        Text(
                            Fmt.whenText(d), fontSize = 13.sp,
                            color = if (!t.done && d < now) p.danger else p.accent,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    if (t.work) WorkBadge()
                }
            }
        }
        if (t.image != null) {
            Spacer(Modifier.width(12.dp))
            Thumb(
                t.image,
                Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).clickable { onImage(t.image) },
                alignTop = true
            )
        }
    }
}

// ======================= SAVED =======================
// The Saved page itself lives in SavedScreen.kt

@Composable
fun SavedDialog(initUrl: String = "", heading: String = "Save a link or note", onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(initUrl) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = p.card,
        title = { Text(heading, fontSize = 20.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(url, { url = it }, placeholder = { Text("Link") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, placeholder = { Text("Note") },
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextAction("Save") {
                if (url.isBlank() && note.isBlank()) return@TextAction
                val link = Regex("""https?://\S+""").find(url.trim())?.value
                    ?: url.trim().ifBlank { null }?.let { "https://$it" }
                Repo.addSaved(link, "", note.trim(), "")
                if (link == null) Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        },
        dismissButton = { TextAction("Cancel", p.sub, onDismiss) }
    )
}

