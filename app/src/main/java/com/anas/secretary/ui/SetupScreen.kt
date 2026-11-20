package com.anas.secretary.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.anas.secretary.edge.Shizu
import com.anas.secretary.util.Prefs

// ======================= First-time setup =======================

object SetupState {
    /** Shown over the app; set at start for new installs and from Settings → Permissions. */
    var show by mutableStateOf(false)
}

private class Step(
    val title: String, val why: String, val icon: ImageVector, val tint: Color, val optional: Boolean,
    val button: String, val isOn: () -> Boolean, val open: () -> Unit
)

@Composable
fun SetupScreen(tick: Int) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var permTick by remember { mutableIntStateOf(0) }

    fun settings(action: String, pkg: Boolean = true) = try {
        ctx.startActivity(if (pkg) Intent(action, Uri.parse("package:${ctx.packageName}")) else Intent(action))
    } catch (_: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }
    // After two refusals Android stops asking, so send the person to App info to turn it on there
    fun refused(perms: Collection<String>) {
        val act = ctx as? Activity ?: return
        if (perms.any { !granted(ctx, it) && !act.shouldShowRequestPermissionRationale(it) }) settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permTick++
        if (!ok && Build.VERSION.SDK_INT >= 33) refused(listOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permTick++
        refused(res.filterValues { !it }.keys)
    }

    // The parts picked on the first page decide which steps follow
    var picks by remember { mutableStateOf(com.anas.secretary.util.Features.F.entries.filter { com.anas.secretary.util.Features.on(it) }.toSet()) }
    var picked by remember { mutableIntStateOf(0) }
    val steps = remember(picked) {
        val F = com.anas.secretary.util.Features
        listOfNotNull(
            Step(
                "Read notifications", "So Daybrief can sort your messages, suggest replies and remind you. They stay on this phone.",
                Icons.Default.Notifications, Color(0xFFFF9F0A), false, "Open settings",
                { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) },
                { settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, pkg = false) }
            ).takeIf { F.messages || F.edge },
            Step(
                "Show over other apps", "For edge lighting and the small side window.",
                Icons.Default.Star, Color(0xFF3E91FF), false, "Open settings",
                { Settings.canDrawOverlays(ctx) },
                { settings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
            ).takeIf { F.edge },
            Step(
                "Alarms and reminders", "So reminders ring on time, and Daybrief can show its own notifications.",
                Icons.Default.DateRange, Color(0xFFFF453A), false, "Allow",
                {
                    (Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) &&
                        (Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS))
                },
                {
                    if (Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if (Build.VERSION.SDK_INT >= 31) settings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                }
            ),
            Step(
                "Calendar and contacts", "Your Samsung Calendar in the Calendar page, and the people you want to keep in touch with.",
                LookIcons.People, Color(0xFF1D9E75), true, "Allow",
                {
                    (!com.anas.secretary.util.Features.calendar || granted(ctx, Manifest.permission.READ_CALENDAR)) &&
                        (!com.anas.secretary.util.Features.people || granted(ctx, Manifest.permission.READ_CONTACTS))
                },
                {
                    multi.launch(buildList {
                        if (F.calendar) { add(Manifest.permission.READ_CALENDAR); add(Manifest.permission.WRITE_CALENDAR) }
                        if (F.people) { add(Manifest.permission.READ_CONTACTS); add(Manifest.permission.READ_CALL_LOG) }
                    }.toTypedArray())
                }
            ).takeIf { F.calendar || F.people },
            Step(
                "Shizuku", "Opens apps inside the small side window, without root. You can set it up any time later.",
                LookIcons.Grid, Color(0xFF7F77DD), true, "Set up",
                { Shizu.state(ctx) == Shizu.State.Ready },
                { if (Shizu.state(ctx) == Shizu.State.NotAllowed) Shizu.ask() else Shizu.openShizuku(ctx) }
            ).takeIf { F.edge && Prefs.edgeAppInPanel }
        )
    }
    // 0 = welcome, 1..steps.size = a step, steps.size + 1 = done
    var at by rememberSaveable { mutableIntStateOf(0) }
    val on = remember(tick, permTick, at) { steps.map { it.isOn() } }
    fun finish() { Prefs.setupDone = true; SetupState.show = false }
    // A step that's already on (or turned on in Settings just now) moves on by itself
    LaunchedEffect(on, at) { if (at in 1..steps.size && on[at - 1]) { kotlinx.coroutines.delay(400); at++ } }
    BackHandler { if (at > 0) at = 0 else finish() }

    Box(Modifier.fillMaxSize().background(p.bg).pointerInput(Unit) { detectTapGestures { } }) {
        GlowBackground()
        AnimatedContent(
            at, label = "setup",
            transitionSpec = { (slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300))) togetherWith fadeOut(tween(150)) }
        ) { i ->
            Column(
                Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    i == 0 -> {
                        Hero(LookIcons.Things, Brush.linearGradient(listOf(Color(0xFF3E91FF), WorkColor)))
                        Text(if (Prefs.name.isBlank()) "Hi there" else "Hi ${Prefs.name.trim()}", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                        Text(
                            "What should Daybrief do for you? Pick what you want. You can change it any time in Settings → Features. Everything stays on this phone.",
                            fontSize = 15.sp, color = p.sub, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp)
                        )
                        PickGrid(picks) { f -> picks = if (f in picks) picks - f else picks + f }
                        Spacer(Modifier.height(18.dp))
                        BigButton(if (picks.isEmpty()) "Pick at least one" else "Continue · ${picks.size} picked") {
                            if (picks.isNotEmpty()) {
                                com.anas.secretary.util.Features.F.entries.forEach { com.anas.secretary.util.Features.set(ctx, it, it in picks) }
                                picked++
                                at = 1
                            }
                        }
                        TextAction("Skip setup", p.sub) { finish() }
                    }
                    i <= steps.size -> {
                        val s = steps[i - 1]
                        Dots(steps.size, i - 1)
                        Hero(s.icon, Brush.linearGradient(listOf(s.tint, s.tint.copy(alpha = 0.6f))))
                        Text(s.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = p.text, textAlign = TextAlign.Center)
                        Text(s.why, fontSize = 15.sp, color = p.sub, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                        if (on[i - 1]) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                                Icon(Icons.Default.Check, null, tint = TileColors.green)
                                Text("  Done", fontSize = 15.sp, color = TileColors.green, fontWeight = FontWeight.Medium)
                            }
                            BigButton("Next") { at++ }
                        } else {
                            BigButton(s.button) { s.open() }
                            TextAction(if (s.optional) "Skip" else "Later", p.sub) { at++ }
                        }
                        if (s.title == "Read notifications" && !on[i - 1]) RestrictedTip { settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
                    }
                    else -> {
                        Hero(Icons.Default.Check, Brush.linearGradient(listOf(Color(0xFF1D9E75), Color(0xFF3E91FF))))
                        Text("You're all set", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                        Text(
                            "Anything you skipped is in Settings → Permissions.", fontSize = 15.sp, color = p.sub,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp)
                        )
                        StepList(steps, on)
                        Spacer(Modifier.height(18.dp))
                        BigButton("Go to my brief") { finish() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(icon: ImageVector, brush: Brush) {
    Box(
        Modifier.padding(top = 36.dp, bottom = 20.dp).size(88.dp).clip(RoundedCornerShape(28.dp)).background(brush),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(42.dp)) }
}

@Composable
private fun Dots(n: Int, at: Int) {
    val p = LocalPalette.current
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(n) { i ->
            Box(Modifier.height(7.dp).width(if (i == at) 20.dp else 7.dp).clip(CircleShape).background(if (i == at) p.accent else p.text.copy(alpha = 0.2f)))
        }
    }
}

@Composable
private fun StepList(steps: List<Step>, on: List<Boolean>) {
    val p = LocalPalette.current
    GroupBox {
        steps.forEachIndexed { i, s ->
            if (i > 0) GroupDivider(start = 54.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                if (on[i]) Box(Modifier.size(24.dp).clip(CircleShape).background(TileColors.green), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                } else Box(Modifier.size(24.dp).border(2.dp, p.sub, CircleShape))
                Spacer(Modifier.width(14.dp))
                Text(s.title, fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
                if (s.optional && !on[i]) Text(
                    "optional", fontSize = 11.sp, color = p.sub,
                    modifier = Modifier.border(1.dp, p.divider, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/** Android greys out notification access for apps installed from a file; this explains the way around it. */
@Composable
private fun RestrictedTip(onOpen: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.pill).padding(16.dp)) {
        Text("Greyed out?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.pillText)
        Text(
            "Android blocks this for apps installed from a file. Open App info, tap ⋮ at the top right, pick Allow restricted settings, then come back and try again.",
            fontSize = 13.sp, color = p.pillText, modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            "Open App info", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.accent,
            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(vertical = 4.dp)
        )
    }
}

/** First page of setup: a card per part, tap to pick. */
@Composable
private fun PickGrid(picks: Set<com.anas.secretary.util.Features.F>, onToggle: (com.anas.secretary.util.Features.F) -> Unit) {
    val p = LocalPalette.current
    com.anas.secretary.util.Features.F.entries.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            row.forEach { f ->
                val on = f in picks
                val (icon, tint) = featureLook(f)
                Column(
                    Modifier.weight(1f).heightIn(min = 112.dp).clip(RoundedCornerShape(22.dp)).background(p.card)
                        .border(2.dp, if (on) p.accent else Color.Transparent, RoundedCornerShape(22.dp))
                        .clickable { onToggle(f) }.padding(13.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(if (on) p.accent else Color.Transparent)
                                .border(2.dp, if (on) p.accent else p.sub, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { if (on) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    }
                    Text(f.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.padding(top = 9.dp))
                    Text(f.about, fontSize = 11.5.sp, color = p.sub, lineHeight = 15.sp)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
