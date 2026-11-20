package com.anas.secretary.ui

import com.anas.secretary.edge.Shizu
import com.anas.secretary.util.Health
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anas.secretary.data.Notif
import com.anas.secretary.data.Repo
import com.anas.secretary.util.Backup
import com.anas.secretary.util.Release
import com.anas.secretary.util.Secret
import com.anas.secretary.util.Updater
import com.anas.secretary.util.Cal
import com.anas.secretary.util.CalInfo
import com.anas.secretary.util.Fmt
import com.anas.secretary.util.Prefs
import com.anas.secretary.util.Features
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

fun granted(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

fun photoPerm() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun SettingsScreen(v: Int, tick: Int) {
    var page by remember { mutableStateOf("main") }
    BackHandler(enabled = page != "main") { page = "main" }
    when (page) {
        "history" -> HistoryPage(v) { page = "main" }
        "edge" -> EdgePage(tick) { page = "main" }
        else -> SettingsMain(tick, page) { page = it }
    }
}

@Composable
private fun SettingRow(
    title: String,
    summary: String? = null,
    summaryColor: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = p.text)
            if (summary != null) Text(summary, fontSize = 13.sp, color = summaryColor ?: p.sub, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A row on the main page: coloured icon, title, what's set now, and an arrow to its page. */
@Composable
private fun NavRow(icon: ImageVector, tint: Color, title: String, summary: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = p.text)
            Text(summary, fontSize = 13.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", fontSize = 20.sp, color = p.sub)
    }
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, summary, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsMain(tick: Int, page: String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var permTick by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val multiPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    var pickWork by remember { mutableStateOf(false) }
    var workApps by remember { mutableStateOf(Prefs.workApps) }

    val listenerOn = remember(tick, permTick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val canPost = remember(tick, permTick) { Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS) }
    val photos = remember(tick, permTick) { granted(ctx, photoPerm()) }
    val exact = remember(tick, permTick) {
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    var name by remember { mutableStateOf(Prefs.name) }
    var shots by remember { mutableStateOf(Prefs.screenshotPrompts) }
    var askSaved by remember { mutableStateOf(Prefs.askAboutSaved) }
    var dayFirst by remember { mutableStateOf(Prefs.dayFirst) }
    var lastBackup by remember { mutableLongStateOf(Prefs.lastBackup) }
    var editName by remember { mutableStateOf(false) }

    // Updates
    var updRelease by remember { mutableStateOf<Release?>(Updater.available) }
    var updStatus by remember { mutableStateOf<String?>(null) }
    var updBusy by remember { mutableStateOf(false) }
    var updProgress by remember { mutableStateOf<Float?>(null) }
    var editSource by remember { mutableStateOf(false) }
    var clearHandled by remember { mutableStateOf(Prefs.clearHandled) }
    val currentBuild = remember { Updater.currentBuild(ctx) }

    fun checkNow() {
        if (updBusy) return
        updBusy = true; updStatus = "Checking…"
        scope.launch {
            try {
                val r = Updater.check(ctx)
                updRelease = r
                updStatus = if (r == null) "You have the latest version" else "Build ${r.build} is ready to install"
            } catch (e: Exception) {
                updStatus = e.message ?: "Couldn't check"
            }
            updBusy = false
        }
    }

    fun installNow(r: Release) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, "Allow Daybrief to install updates, then tap Install again", Toast.LENGTH_LONG).show()
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            return
        }
        updBusy = true; updProgress = 0f; updStatus = "Downloading…"
        scope.launch {
            try {
                Updater.install(ctx, r) { updProgress = it }
                updStatus = "Confirm the update on the screen that opens"
            } catch (e: Exception) {
                updStatus = e.message ?: "Download failed"
            }
            updBusy = false; updProgress = null
        }
    }

    // Calendar link
    var calTick by remember { mutableIntStateOf(0) }
    val calRead = remember(tick, permTick, calTick) { Cal.canRead(ctx) }
    val calendars = remember(tick, permTick, calTick) { Cal.calendars(ctx) }
    var calSync by remember { mutableStateOf(Prefs.calSync) }
    var calTarget by remember { mutableLongStateOf(Prefs.calTarget) }
    var calShown by remember { mutableStateOf(Prefs.calShown) }
    var pickTarget by remember { mutableStateOf(false) }
    var pickShown by remember { mutableStateOf(false) }
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        calTick++
        if (res[Manifest.permission.WRITE_CALENDAR] == true && Prefs.calTarget < 0) {
            Cal.defaultTarget(ctx)?.let { t ->
                Prefs.calTarget = t.id; calTarget = t.id
                Prefs.calSync = true; calSync = true
                Repo.syncAllToCalendar()
            }
        }
    }

    // Backup / restore flow
    var pwdMode by remember { mutableStateOf<String?>(null) } // "backup" or "restore"
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = pendingPassword
        pendingPassword = null
        if (uri != null && pw != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { Backup.export(ctx, pw, uri) } }
                pw.fill(' ')
                busy = false
                lastBackup = Prefs.lastBackup
                toast(if (result.isSuccess) "Backup saved" else "Backup failed. Try a different folder.")
            }
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; pwdMode = "restore" }
    }

    val people = remember(tick, permTick) { granted(ctx, Manifest.permission.READ_CALL_LOG) && granted(ctx, Manifest.permission.READ_CONTACTS) }
    val healthAsk = rememberLauncherForActivityResult(Health.permissionContract()) { permTick++ }
    val health by produceState<Health.State?>(null, tick, permTick) {
        value = try { Health.state(ctx) } catch (_: Exception) { null }
    }
    val shizu = remember(tick, permTick) { Shizu.state(ctx) }
    // Only the permissions the parts that are on need
    val perms = listOfNotNull(
        listenerOn.takeIf { Features.messages || Features.edge }, canPost, photos.takeIf { Features.saved }, exact,
        people.takeIf { Features.people }
    )
    val ver = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "" } }
    fun status(on: Boolean) = if (on) "On" else "Off, tap to turn on"

    val title = when (page) {
        "notifs" -> "Notifications"
        "prompts" -> "Smart prompts"
        "calendar" -> "Calendar & reminders"
        "health" -> "Health & people"
        "backup" -> "Backup & updates"
        "perms" -> "Permissions"
        "about" -> "About"
        "features" -> "Features"
        else -> "Settings"
    }
    var supporting by remember { mutableStateOf(false) }
    if (supporting) SupportSheet { supporting = false }
    OneUiScreen(title, null) {
        if (page != "main") item(key = "back") {
            Box(Modifier.padding(bottom = 8.dp)) { TextAction("Back to settings", onClick = { onOpen("main") }) }
        }
        when (page) {
            "main" -> {
                item(key = "me") {
                    GroupBox {
                        Row(
                            Modifier.fillMaxWidth().clickable { editName = true }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(p.accent, Color(0xFF7F77DD)))),
                                contentAlignment = Alignment.Center
                            ) { Text(name.take(1).uppercase().ifBlank { "?" }, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color.White) }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name.ifBlank { "Add your name" }, fontSize = 17.sp, color = p.text)
                                Text(
                                    "Daybrief $ver" + if (updRelease != null) " · update ready" else if (com.anas.secretary.BuildConfig.PUBLIC) "" else " · build $currentBuild",
                                    fontSize = 13.sp, color = if (updRelease != null) p.accent else p.sub
                                )
                            }
                            Text("›", fontSize = 20.sp, color = p.sub)
                        }
                    }
                }
                val off = perms.count { !it }
                if (off > 0) item(key = "warn") {
                    Row(
                        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.danger.copy(alpha = 0.16f))
                            .clickable { onOpen("perms") }.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = p.danger, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (off == 1) "1 permission is off" else "$off permissions are off", fontSize = 15.sp, color = p.text)
                            Text("Tap to fix", fontSize = 12.sp, color = p.danger)
                        }
                    }
                }
                item(key = "g0") {
                    Column(Modifier.padding(top = 12.dp)) {
                        GroupBox {
                            NavRow(Icons.Default.Menu, Color(0xFF3E91FF), "Features",
                                "${Features.F.entries.count { Features.on(it) }} of ${Features.F.entries.size} on") { onOpen("features") }
                        }
                    }
                }
                item(key = "g1") {
                    Column(Modifier.padding(top = 12.dp)) {
                        GroupBox {
                            if (Features.edge) {
                                NavRow(Icons.Default.Star, Color(0xFFFFB800), "Edge lighting",
                                    if (Prefs.edgeOn) "On · ${com.anas.secretary.edge.EdgeConfig.all().size} apps glow" else "Off") { onOpen("edge") }
                                GroupDivider()
                            }
                            NavRow(Icons.Default.Notifications, Color(0xFFFF9F0A), "Notifications", "Work apps, handled ones, history") { onOpen("notifs") }
                            GroupDivider()
                            NavRow(Icons.Default.Create, Color(0xFF30D158), "Smart prompts",
                                listOfNotNull("Screenshots".takeIf { shots }, "Saved links".takeIf { askSaved }).joinToString(", ").ifBlank { "Off" }) { onOpen("prompts") }
                        }
                    }
                }
                item(key = "g2") {
                    Column(Modifier.padding(top = 12.dp)) {
                        GroupBox {
                            NavRow(Icons.Default.DateRange, Color(0xFFFF453A), "Calendar & reminders",
                                if (calRead) "Samsung Calendar connected" else "Calendar not connected") { onOpen("calendar") }
                            GroupDivider()
                            NavRow(Icons.Default.Favorite, Color(0xFFFF375F), "Health & people",
                                if (health == Health.State.Connected) "Samsung Health connected" else "Samsung Health, contacts, calls") { onOpen("health") }
                        }
                    }
                }
                item(key = "g3") {
                    Column(Modifier.padding(top = 12.dp)) {
                        GroupBox {
                            NavRow(Icons.Default.Refresh, Color(0xFF64D2FF), "Backup & updates",
                                if (lastBackup == 0L) "Never backed up" else "Last backup ${Fmt.whenText(lastBackup)}") { onOpen("backup") }
                            GroupDivider()
                            NavRow(Icons.Default.Lock, Color(0xFF8E8E93), "Permissions", "${perms.count { it }} of ${perms.size} on") { onOpen("perms") }
                        }
                    }
                }
                item(key = "g4") {
                    Column(Modifier.padding(top = 12.dp)) {
                        GroupBox { NavRow(Icons.Default.Info, Color(0xFF8E8E93), "About", "Your data stays on this phone") { onOpen("about") } }
                    }
                }
                item(key = "g5") {
                    Column(Modifier.padding(top = 12.dp)) {
                        GroupBox { NavRow(Icons.Default.Favorite, Color(0xFFFF4F7B), "Support Daybrief", "Ko-fi · PayPal, card") { supporting = true } }
                    }
                }
            }

            "features" -> item(key = "features") { Column { FeaturesList() } }

            "notifs" -> item(key = "notifs") {
                Column {
                    SectionLabel("Sorting")
                    GroupBox { SettingRow("Work apps", "${workApps.size} apps count as work (Teams, Outlook…)", onClick = { pickWork = true }) }
                    SectionLabel("When you answer here")
                    GroupBox {
                        SwitchRow("Remove handled notifications", "When you answer here, the notification also leaves your phone", clearHandled) {
                            clearHandled = it; Prefs.clearHandled = it
                        }
                    }
                    SectionLabel("History")
                    GroupBox { SettingRow("Notification history", "Everything from the last 7 days", onClick = { onOpen("history") }) }
                }
            }

            "prompts" -> item(key = "prompts") {
                Column {
                    SectionLabel("Ask me about")
                    GroupBox {
                        SwitchRow("Screenshots", "Offer to save each new screenshot", shots) { shots = it; Prefs.screenshotPrompts = it }
                        GroupDivider(start = 20.dp)
                        SwitchRow("Saved links", "Ask when you want to check new links", askSaved) {
                            askSaved = it; Prefs.askAboutSaved = it; Repo.changed()
                        }
                    }
                    SectionLabel("Feel")
                    GroupBox {
                        var feel by remember { mutableStateOf(Prefs.haptics) }
                        SwitchRow("Vibration", "A light tick when you tap, switch and swipe. Follows the phone's Touch feedback too.", feel) {
                            feel = it; Prefs.haptics = it
                        }
                    }
                    SectionLabel("Your day")
                    GroupBox {
                        var morning by remember { mutableStateOf(Prefs.morningOn) }
                        SwitchRow("Morning summary", "One quiet notification on your first unlock of the morning: sleep, first event, tasks.", morning) {
                            morning = it; Prefs.morningOn = it
                        }
                        GroupDivider()
                        var bed by remember { mutableStateOf(Prefs.bedtimeOn) }
                        SwitchRow("Bedtime reminder", "30 minutes before you should sleep to get your usual hours before tomorrow's first event.", bed) {
                            bed = it; Prefs.bedtimeOn = it
                            com.anas.secretary.util.Bg.scope.launch { try { com.anas.secretary.util.Daily.planTonight(ctx) } catch (_: Exception) {} }
                        }
                        GroupDivider()
                        var light by remember { mutableStateOf(Prefs.lightDayOn) }
                        SwitchRow("Light day", "After a short night, offer to move today's flexible tasks to tomorrow.", light) {
                            light = it; Prefs.lightDayOn = it
                        }
                        GroupDivider()
                        var week by remember { mutableStateOf(Prefs.weekOn) }
                        var wday by remember { mutableIntStateOf(Prefs.weekDay) }
                        SwitchRow("Your week", "A look back at your sleep, steps, tasks and people, every " +
                            java.time.DayOfWeek.of(wday).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US) + ".", week) {
                            week = it; Prefs.weekOn = it
                        }
                        if (week) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            (1..7).forEach { d ->
                                val sel = d == wday
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(if (sel) p.accent else p.field)
                                        .clickable { wday = d; Prefs.weekDay = d },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(java.time.DayOfWeek.of(d).getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.US),
                                        fontSize = 13.sp, color = if (sel) Color.White else p.text)
                                }
                            }
                        }
                    }
                }
            }

            "calendar" -> item(key = "calendar") {
                Column {
                    SectionLabel("Samsung Calendar")
                    GroupBox {
                        SettingRow(
                            if (calRead) "Connected" else "Not connected",
                            if (calRead) "Reading your calendars" else "Tap to connect",
                            if (calRead) p.accent else p.danger,
                            onClick = { if (!calRead) calLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }
                        )
                        if (calRead) {
                            GroupDivider(start = 20.dp)
                            SettingRow(
                                "Calendars to show",
                                if (calShown.isEmpty()) "All calendars" else "${calShown.size} of ${calendars.size} calendars",
                                onClick = { pickShown = true }
                            )
                            GroupDivider(start = 20.dp)
                            SwitchRow("Add my reminders to the calendar", "Every reminder also appears in your calendar", calSync) { on ->
                                if (on && calTarget < 0) pickTarget = true
                                else {
                                    calSync = on; Prefs.calSync = on
                                    if (on) Repo.syncAllToCalendar()
                                }
                            }
                            GroupDivider(start = 20.dp)
                            val target = calendars.firstOrNull { it.id == calTarget }
                            SettingRow("Add them to", target?.let { "${it.name}, ${it.where}" } ?: "Choose a calendar", onClick = { pickTarget = true })
                        }
                    }
                    SectionLabel("Reminders")
                    GroupBox {
                        SettingRow("Alarms and reminders", if (exact) "On" else "Off, reminders may be late. Tap to fix", if (exact) p.accent else p.danger, onClick = {
                            if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                            )
                        })
                        GroupDivider(start = 20.dp)
                        SwitchRow("Day before month", if (dayFirst) "12/11 means 12 November" else "12/11 means December 11", dayFirst) {
                            dayFirst = it; Prefs.dayFirst = it
                        }
                    }
                }
            }

            "health" -> item(key = "health") {
                Column {
                    SectionLabel("Samsung Health")
                    GroupBox {
                        val h = health
                        SettingRow(
                            "Health Connect",
                            when (h) {
                                Health.State.Connected -> "Connected · sleep, steps, heart rate, workouts"
                                Health.State.NotConnected -> "Not connected, tap to allow"
                                Health.State.NeedsUpdate -> "Health Connect needs an update"
                                Health.State.Unavailable -> "Not available on this phone"
                                null -> "Checking…"
                            },
                            if (h == Health.State.Connected) p.accent else if (h == null) null else p.danger,
                            onClick = {
                                if (h == Health.State.NotConnected) healthAsk.launch(Health.permissions) else Health.openHealthConnect(ctx)
                            }
                        )
                        GroupDivider(start = 20.dp)
                        SettingRow("Open Samsung Health", "Turn on sharing with Health Connect there", onClick = { Health.openSamsungHealth(ctx) })
                    }
                    SectionLabel("People")
                    GroupBox {
                        SettingRow("Contacts and call history", if (people) "On" else "Off, needed for People", if (people) p.accent else p.danger,
                            onClick = { multiPerm.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)) })
                    }
                }
            }

            "backup" -> item(key = "backup") {
                Column {
                    SectionLabel("Updates")
                    GroupBox {
                        val r = updRelease
                        SettingRow(
                            if (r != null) "Install build ${r.build}" else "Check for updates",
                            updProgress?.let { "Downloading ${(it * 100).toInt()}%" } ?: updStatus ?: if (com.anas.secretary.BuildConfig.PUBLIC) "You're on version $ver" else "You're on build $currentBuild",
                            if (r != null) p.accent else null,
                            onClick = { if (r != null) installNow(r) else checkNow() },
                            trailing = { if (updBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = p.accent) }
                        )
                        // The public app always updates from the public page; no key to set
                        if (!com.anas.secretary.BuildConfig.PUBLIC) {
                            GroupDivider(start = 20.dp)
                            SettingRow(
                                "Update source",
                                "${Prefs.updateRepo}, " + if (Secret.isSet) "access key saved" else "no access key yet",
                                if (Secret.isSet) null else p.danger,
                                onClick = { editSource = true }
                            )
                        }
                    }
                    SectionLabel("Backup")
                    GroupBox {
                        SettingRow(
                            "Back up now",
                            if (lastBackup == 0L) "Never backed up" else "Last backup ${Fmt.whenText(lastBackup)}",
                            onClick = { if (!busy) pwdMode = "backup" },
                            trailing = { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = p.accent) }
                        )
                        GroupDivider(start = 20.dp)
                        SettingRow("Restore from backup", "Replaces what's on this phone", onClick = { if (!busy) openDoc.launch(arrayOf("*/*")) })
                        GroupDivider(start = 20.dp)
                        Text(
                            "Your backup is locked with your password on this phone before it's saved. " +
                                "Choose Google Drive or any folder when asked. The cloud only gets an unreadable file.",
                            fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                        )
                    }
                }
            }

            "perms" -> item(key = "perms") {
                Column {
                    SectionLabel("Permissions")
                    GroupBox {
                        if (Features.messages || Features.edge) {
                            SettingRow("Notification access", status(listenerOn), if (listenerOn) p.accent else p.danger,
                                onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
                            GroupDivider(start = 20.dp)
                        }
                        SettingRow("Notifications", status(canPost), if (canPost) p.accent else p.danger,
                            onClick = { if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) })
                        GroupDivider(start = 20.dp)
                        if (Features.saved) {
                            SettingRow("Photos", if (photos) "On" else "Off, needed to spot screenshots", if (photos) p.accent else p.danger,
                                onClick = { permLauncher.launch(photoPerm()) })
                            GroupDivider(start = 20.dp)
                        }
                        SettingRow("Alarms and reminders", status(exact), if (exact) p.accent else p.danger, onClick = {
                            if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                            )
                        })
                        if (Features.people) GroupDivider(start = 20.dp)
                        if (Features.people) SettingRow("Contacts and call history", if (people) "On" else "Off, needed for People", if (people) p.accent else p.danger,
                            onClick = { multiPerm.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)) })
                    }
                    if (Features.edge && Prefs.edgeAppInPanel) SectionLabel("Shizuku")
                    if (Features.edge && Prefs.edgeAppInPanel) GroupBox {
                        val (sum, ok) = when (shizu) {
                            Shizu.State.Ready -> "Running · allowed" to true
                            Shizu.State.NotAllowed -> "Running · tap to allow Daybrief" to false
                            Shizu.State.NotRunning -> "Not running · tap, then Start in Shizuku" to false
                            Shizu.State.NotInstalled -> "Not installed · tap to get it" to false
                        }
                        SettingRow("Shizuku", sum, if (ok) p.accent else p.danger, onClick = {
                            if (shizu == Shizu.State.NotAllowed) Shizu.ask() else Shizu.openShizuku(ctx)
                            permTick++
                        })
                    }
                    SectionLabel("More")
                    GroupBox {
                        SettingRow("Run setup again", "The step-by-step guide for every permission", onClick = { SetupState.show = true })
                        GroupDivider(start = 20.dp)
                        SettingRow("App info", "Battery, restricted settings and more", onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        })
                    }
                }
            }

            else -> item(key = "about") {
                GroupBox {
                    SettingRow("Daybrief $ver", "Your data stays on this phone. Nothing is sent anywhere except link previews and backups you make.")
                }
            }
        }
    }

    if (editName) {
        var temp by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { editName = false },
            shape = RoundedCornerShape(28.dp), containerColor = p.card,
            title = { Text("Your name", fontSize = 20.sp) },
            text = {
                OutlinedTextField(temp, { temp = it }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextAction("Save") { name = temp.trim(); Prefs.name = name; Repo.changed(); editName = false } },
            dismissButton = { TextAction("Cancel", p.sub) { editName = false } }
        )
    }

    if (pickWork) {
        val seen = remember { Repo.seenApps() }
        val known = listOf(
            "com.microsoft.teams" to "Microsoft Teams", "com.microsoft.office.outlook" to "Outlook", "com.Slack" to "Slack",
            "us.zoom.videomeetings" to "Zoom", "com.google.android.apps.dynamite" to "Google Chat", "com.cisco.webex.meetings" to "Webex"
        )
        val apps = (seen + known).distinctBy { it.first }.sortedBy { it.second.lowercase() }
        var chosen by remember { mutableStateOf(workApps) }
        AlertDialog(
            onDismissRequest = { pickWork = false },
            shape = RoundedCornerShape(28.dp), containerColor = p.card,
            title = { Text("Work apps", fontSize = 20.sp) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text("Messages from these apps are marked as Work.", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(bottom = 8.dp))
                    apps.forEach { (pkg, name) ->
                        val on = pkg in chosen
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .clickable { chosen = if (on) chosen - pkg else chosen + pkg }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(pkg, 30.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(name, fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
                            Checkbox(checked = on, onCheckedChange = null)
                        }
                    }
                }
            },
            confirmButton = { TextAction("Done") { workApps = chosen; Prefs.workApps = chosen; Repo.changed(); pickWork = false } },
            dismissButton = { TextAction("Cancel", p.sub) { pickWork = false } }
        )
    }

    if (editSource) {
        var repo by remember { mutableStateOf(Prefs.updateRepo) }
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editSource = false },
            shape = RoundedCornerShape(28.dp), containerColor = p.card,
            title = { Text("Update source", fontSize = 20.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(repo, { repo = it }, label = { Text("GitHub repository") }, singleLine = true,
                        shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        key, { key = it }, label = { Text(if (Secret.isSet) "New access key (optional)" else "Access key") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "The key lets Daybrief read builds from your private repository, nothing else. " +
                            "It's stored locked on this phone.",
                        fontSize = 13.sp, color = p.sub
                    )
                }
            },
            confirmButton = {
                TextAction("Save") {
                    Prefs.updateRepo = repo.trim().removePrefix("https://github.com/").trim('/')
                    if (key.isNotBlank()) Secret.put(key)
                    editSource = false
                    checkNow()
                }
            },
            dismissButton = { TextAction("Cancel", p.sub) { editSource = false } }
        )
    }

    if (pickTarget) {
        CalendarPicker(
            title = "Add reminders to",
            calendars = calendars.filter { it.writable },
            selected = setOf(calTarget),
            multi = false,
            onDismiss = { pickTarget = false },
            onDone = { ids ->
                ids.firstOrNull()?.let { id ->
                    calTarget = id; Prefs.calTarget = id
                    calSync = true; Prefs.calSync = true
                    Repo.syncAllToCalendar()
                }
                pickTarget = false
            }
        )
    }
    if (pickShown) {
        CalendarPicker(
            title = "Calendars to show",
            calendars = calendars,
            selected = if (calShown.isEmpty()) calendars.map { it.id }.toSet() else calShown,
            multi = true,
            onDismiss = { pickShown = false },
            onDone = { ids ->
                val v = if (ids.size == calendars.size) emptySet() else ids
                calShown = v; Prefs.calShown = v; Repo.changed()
                pickShown = false
            }
        )
    }

    pwdMode?.let { mode ->
        PasswordDialog(
            creating = mode == "backup",
            onDismiss = { pwdMode = null; restoreUri = null },
            onDone = { pw ->
                pwdMode = null
                if (mode == "backup") {
                    pendingPassword = pw
                    createDoc.launch("Daybrief-backup-${LocalDate.now()}.secbak")
                } else restoreUri?.let { uri ->
                    restoreUri = null
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { Backup.restore(ctx, pw, uri) } }
                        pw.fill(' ')
                        busy = false
                        val err = result.exceptionOrNull()
                        toast(
                            when (err) {
                                null -> "Backup restored"
                                is Backup.WrongPassword -> "Wrong password. Try again."
                                else -> err.message ?: "Couldn't restore this file"
                            }
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun PasswordDialog(creating: Boolean, onDismiss: () -> Unit, onDone: (CharArray) -> Unit) {
    val p = LocalPalette.current
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp), containerColor = p.card,
        title = { Text(if (creating) "Choose a backup password" else "Backup password", fontSize = 20.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (creating) {
                    Text(
                        "You'll need it to restore. If you forget it, the backup can't be opened by anyone.",
                        fontSize = 14.sp, color = p.sub
                    )
                }
                OutlinedTextField(
                    a, { a = it; err = null }, placeholder = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                if (creating) {
                    OutlinedTextField(
                        b, { b = it; err = null }, placeholder = { Text("Repeat password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                }
                err?.let { Text(it, color = p.danger, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextAction(if (creating) "Continue" else "Restore") {
                when {
                    a.length < 6 -> err = "Use at least 6 characters"
                    creating && a != b -> err = "The passwords don't match"
                    else -> onDone(a.toCharArray())
                }
            }
        },
        dismissButton = { TextAction("Cancel", p.sub, onDismiss) }
    )
}

@Composable
private fun HistoryPage(v: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val list = remember(v) { Repo.notifs(true) }
    var editing by remember { mutableStateOf<Notif?>(null) }

    OneUiScreen("History", "Notifications from the last 7 days") {
        item(key = "back") {
            Box(Modifier.padding(bottom = 8.dp)) { TextAction("Back to settings", onClick = onBack) }
        }
        if (list.isEmpty()) {
            item(key = "empty") { EmptyState(Icons.Default.Notifications, "No notifications yet", "Once notification access is on, they'll be listed here.") }
        } else {
            item(key = "list") {
                GroupBox {
                    list.forEachIndexed { i, n ->
                        if (i > 0) GroupDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { openApp(ctx, n.pkg) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(n.pkg, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(n.title.ifBlank { n.app }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(Fmt.short(n.time), fontSize = 12.sp, color = p.sub)
                                }
                                if (n.body.isNotBlank()) Text(n.body, fontSize = 13.sp, color = p.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (n.status == "done") {
                                Icon(Icons.Default.Check, "Added", tint = p.accent, modifier = Modifier.padding(start = 8.dp).size(20.dp))
                            } else {
                                TextAction("Add") { editing = n }
                            }
                        }
                    }
                }
            }
            item(key = "clear") {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    TextAction("Clear history", p.sub) { Repo.clearHandled() }
                }
            }
        }
    }

    editing?.let { n ->
        TaskDialog(
            "Add task",
            initTitle = n.sTitle?.ifBlank { null } ?: "${n.title}: ${n.body}".take(120),
            initNotes = "${n.app}: ${n.body}",
            initDue = n.sDue, initWork = n.pkg in Prefs.workApps,
            onDismiss = { editing = null },
            onSave = { t, notes, d, w ->
                Repo.addTask(t, notes, d, "notification", work = w)
                Repo.setNotifStatus(n.id, "done")
                editing = null
            }
        )
    }
}

@Composable
private fun CalendarPicker(
    title: String,
    calendars: List<CalInfo>,
    selected: Set<Long>,
    multi: Boolean,
    onDismiss: () -> Unit,
    onDone: (Set<Long>) -> Unit
) {
    val p = LocalPalette.current
    var chosen by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp), containerColor = p.card,
        title = { Text(title, fontSize = 20.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (calendars.isEmpty()) Text("No calendars found on this phone.", color = p.sub)
                calendars.forEach { c ->
                    val on = c.id in chosen
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                            chosen = if (multi) (if (on) chosen - c.id else chosen + c.id) else setOf(c.id)
                        }.padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.color or 0xFF000000.toInt())))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 15.sp, color = p.text)
                            Text(c.where, fontSize = 12.sp, color = p.sub)
                        }
                        if (multi) Checkbox(checked = on, onCheckedChange = null)
                        else RadioButton(selected = on, onClick = null)
                    }
                }
            }
        },
        confirmButton = { TextAction("Done") { if (chosen.isNotEmpty()) onDone(chosen) } },
        dismissButton = { TextAction("Cancel", p.sub, onDismiss) }
    )
}
