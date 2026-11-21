package com.anas.secretary.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.data.Repo
import com.anas.secretary.edge.EdgeApp
import com.anas.secretary.edge.EdgeConfig
import com.anas.secretary.edge.EdgeLight
import com.anas.secretary.edge.Shizu
import com.anas.secretary.util.Fmt
import com.anas.secretary.util.Prefs

private val knownChat = listOf(
    "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business", "org.telegram.messenger" to "Telegram",
    "com.google.android.apps.messaging" to "Messages", "com.samsung.android.messaging" to "Samsung Messages",
    "com.google.android.gm" to "Gmail", "com.microsoft.teams" to "Teams", "com.instagram.android" to "Instagram",
    "com.snapchat.android" to "Snapchat"
)
private val knownQuiet = listOf(
    "com.google.android.youtube" to "YouTube", "com.netflix.mediaclient" to "Netflix", "net.mbc.shahid" to "Shahid",
    "com.zhiliaoapp.musically" to "TikTok", "com.google.android.apps.youtube.music" to "YouTube Music"
)

private fun appsToPick(): List<Pair<String, String>> =
    (Repo.seenApps() + knownChat).distinctBy { it.first }.sortedBy { it.second.lowercase() }

@Composable
private fun ERow(title: String, summary: String?, summaryColor: Color? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = p.text)
            if (summary != null) Text(summary, fontSize = 13.sp, color = summaryColor ?: p.sub, modifier = Modifier.padding(top = 2.dp))
        }
        trailing?.invoke()
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        label, fontSize = 13.sp, color = if (on) p.bg else p.text, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.text else p.field).clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
    )
}

/** A small phone outline that shows where each app glows. */
@Composable
private fun PhonePreview(apps: List<EdgeApp>, highlight: String?) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.width(130.dp).height(260.dp)) {
            val w = size.width; val h = size.height
            drawRoundRect(p.field, size = size, cornerRadius = CornerRadius(26.dp.toPx()))
            apps.forEach { a ->
                val th = (a.thick * 0.9f).dp.toPx()
                val c = Color(a.color).copy(alpha = if (highlight == null || highlight == a.pkg) 1f else 0.35f)
                val r = CornerRadius(th / 2)
                when (a.edge) {
                    "left" -> drawRoundRect(c, Offset(0f, h * a.pos), Size(th, h * a.len), r)
                    "top" -> drawRoundRect(c, Offset(w * a.pos, 0f), Size(w * a.len, th), r)
                    "bottom" -> drawRoundRect(c, Offset(w * a.pos, h - th), Size(w * a.len, th), r)
                    else -> drawRoundRect(c, Offset(w - th, h * a.pos), Size(th, h * a.len), r)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EdgePage(tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var on by remember { mutableStateOf(Prefs.edgeOn) }
    var smart by remember { mutableStateOf(Prefs.edgeSmart) }
    var anim by remember { mutableStateOf(Prefs.edgeAnim) }
    var apps by remember { mutableStateOf(EdgeConfig.all()) }
    var quiet by remember { mutableStateOf(Prefs.edgeQuiet) }
    var swipeHide by remember { mutableStateOf(Prefs.edgeSwipeHide) }
    var hideButton by remember { mutableStateOf(Prefs.edgeHideButton) }
    var autoHide by remember { mutableIntStateOf(Prefs.edgeAutoHide) }
    var open by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var pickingQuiet by remember { mutableStateOf(false) }
    val canDraw = remember(tick) { EdgeLight.canDraw(ctx) }
    val canUsage = remember(tick) { EdgeLight.canUsage(ctx) }
    val names = remember(apps) { appsToPick().toMap() }

    fun save(list: List<EdgeApp>) { apps = list; EdgeConfig.save(list); EdgeLight.refresh() }
    fun askOverlay() = ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))

    OneUiScreen("Edge lighting", "Each app glows in its own color") {
        item(key = "back") { Box(Modifier.padding(bottom = 8.dp)) { TextAction("Back to settings", onClick = onBack) } }
        item(key = "main") {
            GroupBox {
                ERow("Edge lighting", if (on) "On" else "Off", onClick = {
                    on = !on; Prefs.edgeOn = on; EdgeLight.refresh(); if (on && !canDraw) askOverlay()
                }) { Switch(checked = on, onCheckedChange = { on = it; Prefs.edgeOn = it; EdgeLight.refresh(); if (it && !canDraw) askOverlay() }) }
                GroupDivider(start = 20.dp)
                ERow(
                    "Show over other apps", if (canDraw) "Allowed" else "Needed to draw the glow, tap to allow",
                    if (canDraw) p.accent else p.danger, onClick = { askOverlay() }
                )
                GroupDivider(start = 20.dp)
                ERow(
                    "Smart glow", "Codes and promotions stay dark. Real messages still glow.",
                    onClick = { smart = !smart; Prefs.edgeSmart = smart; EdgeLight.refresh() }
                ) { Switch(checked = smart, onCheckedChange = { smart = it; Prefs.edgeSmart = it; EdgeLight.refresh() }) }
            }
        }
        item(key = "sleep") {
            Column {
                SectionLabel("While you sleep")
                var on by remember { mutableStateOf(Prefs.sleepEdge) }
                var people by remember { mutableStateOf(Prefs.sleepPeople) }
                var adding by remember { mutableStateOf(false) }
                var hours by remember { mutableStateOf(Prefs.sleepFrom to Prefs.sleepTo) }
                GroupBox {
                    ERow(
                        "Sleep mode", "In your usual sleep hours (from your watch), only calls and the people below light up the edge.",
                        onClick = { on = !on; Prefs.sleepEdge = on; EdgeLight.refresh() }
                    ) { Switch(checked = on, onCheckedChange = { on = it; Prefs.sleepEdge = it; EdgeLight.refresh() }) }
                    if (on) {
                        GroupDivider(start = 20.dp)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text("Still glow for", fontSize = 14.sp, color = p.text)
                            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                people.sorted().forEach { n -> Chip("$n  ✕", false) { people = people - n; Prefs.sleepPeople = people; EdgeLight.refresh() } }
                                Chip("+ Add", false) { adding = true }
                            }
                        }
                        GroupDivider(start = 20.dp)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text("Until your watch has a few nights", fontSize = 14.sp, color = p.text)
                            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(22 * 60 to 6 * 60, 23 * 60 to 7 * 60, 23 * 60 + 30 to 7 * 60, 0 to 8 * 60).forEach { h ->
                                    fun t(m: Int) = "%d:%02d".format(m / 60, m % 60)
                                    Chip("${t(h.first)} – ${t(h.second)}", hours == h) {
                                        hours = h; Prefs.sleepFrom = h.first; Prefs.sleepTo = h.second; EdgeLight.refresh()
                                    }
                                }
                            }
                        }
                    }
                }
                if (adding) {
                    val known = remember { Repo.people().map { it.name }.filter { it.isNotBlank() }.distinct().sorted() }
                    var typed by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { adding = false },
                        title = { Text("Still glow for") },
                        text = {
                            Column {
                                OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("Name or group, as it shows in the chat") })
                                if (known.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    known.forEach { n ->
                                        Chip(n, n in people) {
                                            people = if (n in people) people - n else people + n
                                            Prefs.sleepPeople = people; EdgeLight.refresh()
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                typed.trim().takeIf { it.length >= 2 }?.let { people = people + it; Prefs.sleepPeople = people; EdgeLight.refresh() }
                                adding = false
                            }) { Text("Done") }
                        }
                    )
                }
            }
        }
        item(key = "hide") {
            Column {
                SectionLabel("Hiding a glow")
                GroupBox {
                    ERow(
                        "Swipe out to hide", "Push the glow toward the edge, or long-press it. The notification stays.",
                        onClick = { swipeHide = !swipeHide; Prefs.edgeSwipeHide = swipeHide; EdgeLight.refresh() }
                    ) { Switch(checked = swipeHide, onCheckedChange = { swipeHide = it; Prefs.edgeSwipeHide = it; EdgeLight.refresh() }) }
                    GroupDivider(start = 20.dp)
                    ERow(
                        "Hide button in the panel", "Tap the glow, then Hide at the top",
                        onClick = { hideButton = !hideButton; Prefs.edgeHideButton = hideButton }
                    ) { Switch(checked = hideButton, onCheckedChange = { hideButton = it; Prefs.edgeHideButton = it }) }
                    GroupDivider(start = 20.dp)
                    ERow("Hide unseen glows after", if (autoHide == 0) "Never: glows stay until you open or clear them" else "The glow turns off by itself")
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "Never", 5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour").forEach { (m, l) ->
                            Chip(l, autoHide == m) { autoHide = m; Prefs.edgeAutoHide = m; EdgeLight.refresh() }
                        }
                    }
                }
            }
        }
        item(key = "inpanel") {
            Column {
                SectionLabel("Opening apps")
                var inPanel by remember { mutableStateOf(Prefs.edgeAppInPanel) }
                val sv by Shizu.version.collectAsState()
                var resumed by remember { mutableIntStateOf(0) }
                // Shizuku is started outside the app, so look again whenever you come back
                val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, ev -> if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
                    owner.lifecycle.addObserver(obs)
                    onDispose { owner.lifecycle.removeObserver(obs) }
                }
                val st = remember(sv, resumed) { Shizu.state(ctx) }
                GroupBox {
                    var popup by remember { mutableStateOf(Prefs.openPopup) }
                    ERow(
                        "Open apps in a pop-up window", "Open shows the chat in a small floating window, where the phone supports it. Off: full screen.",
                        onClick = { popup = !popup; Prefs.openPopup = popup }
                    ) { Switch(checked = popup, onCheckedChange = { popup = it; Prefs.openPopup = it }) }
                    GroupDivider(start = 20.dp)
                    ERow(
                        "Open apps in a small window", "Optional. Open shows the app in a small window on the side, so you can reply or look around, then close it with ✕. Needs the Shizuku app (no root). Without Shizuku, apps open in a pop-up window.",
                        onClick = { inPanel = !inPanel; Prefs.edgeAppInPanel = inPanel }
                    ) { Switch(checked = inPanel, onCheckedChange = { inPanel = it; Prefs.edgeAppInPanel = it }) }
                    if (inPanel) GroupDivider(start = 20.dp)
                    val (sum, ok) = when (st) {
                        Shizu.State.Ready -> "Running · allowed" to true
                        Shizu.State.NotAllowed -> "Running · tap to allow Daybrief" to false
                        Shizu.State.NotRunning -> "Not running · tap, then Start in Shizuku" to false
                        Shizu.State.NotInstalled -> "Not installed · tap to get it" to false
                    }
                    if (inPanel) ERow("Shizuku", sum, if (ok) p.accent else p.danger, onClick = {
                        if (st == Shizu.State.NotAllowed) Shizu.ask() else Shizu.openShizuku(ctx)
                    })
                    if (inPanel) {
                        var size by remember { mutableIntStateOf(Prefs.sideSize) }
                        var side by remember { mutableStateOf(Prefs.sideEdge) }
                        var keep by remember { mutableStateOf(Prefs.sideRemember) }
                        GroupDivider(start = 20.dp)
                        ERow("Window size", "Apps open in a small window on the side; the app behind keeps working")
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Small", "Medium", "Large").forEachIndexed { i, l -> Chip(l, size == i) { size = i; Prefs.sideSize = i } }
                        }
                        GroupDivider(start = 20.dp)
                        ERow("Opens on", null)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("swiped" to "Edge I swiped", "left" to "Left", "right" to "Right").forEach { (k, l) ->
                                Chip(l, side == k) { side = k; Prefs.sideEdge = k; Prefs.sideLeft = null }
                            }
                        }
                        GroupDivider(start = 20.dp)
                        ERow(
                            "Remember where I moved it", "Size and place are kept for next time",
                            onClick = { keep = !keep; Prefs.sideRemember = keep }
                        ) { Switch(checked = keep, onCheckedChange = { keep = it; Prefs.sideRemember = it }) }
                    }
                }
            }
        }
        item(key = "anim") {
            Column {
                SectionLabel("Animation")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EdgeConfig.anims.forEach { (k, l) -> Chip(l, anim == k) { anim = k; Prefs.edgeAnim = k; EdgeLight.refresh() } }
                }
            }
        }
        item(key = "apps") {
            Column {
                SectionLabel("Apps that glow")
                GroupBox(Modifier.animateContentSize()) {
                    if (apps.isNotEmpty()) PhonePreview(apps, open)
                    apps.forEachIndexed { i, a ->
                        if (i > 0 || apps.isNotEmpty()) GroupDivider(start = 20.dp)
                        Row(
                            Modifier.fillMaxWidth().clickable { open = if (open == a.pkg) null else a.pkg }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(a.pkg, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(names[a.pkg] ?: Fmt.appLabel(ctx, a.pkg), fontSize = 15.sp, color = p.text)
                                Text(EdgeConfig.edges.firstOrNull { it.first == a.edge }?.second ?: a.edge, fontSize = 12.sp, color = p.sub)
                            }
                            Box(Modifier.size(20.dp).clip(CircleShape).background(Color(a.color)))
                        }
                        if (open == a.pkg) {
                            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    EdgeConfig.palette.forEach { c ->
                                        Box(
                                            Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                                .then(if (c == a.color) Modifier.border(3.dp, p.text, CircleShape) else Modifier)
                                                .clickable { save(apps.map { if (it.pkg == a.pkg) it.copy(color = c) else it }) }
                                        )
                                    }
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    EdgeConfig.edges.forEach { (k, l) -> Chip(l, a.edge == k) { save(apps.map { if (it.pkg == a.pkg) it.copy(edge = k) else it }) } }
                                }
                                var pos by remember(a.pkg) { mutableFloatStateOf(a.pos) }
                                var len by remember(a.pkg) { mutableFloatStateOf(a.len) }
                                var th by remember(a.pkg) { mutableFloatStateOf(a.thick.toFloat()) }
                                Text("Position", fontSize = 13.sp, color = p.sub)
                                Slider(pos, { pos = it; apps = apps.map { x -> if (x.pkg == a.pkg) x.copy(pos = it) else x } }, valueRange = 0f..0.95f,
                                    onValueChangeFinished = { save(apps) })
                                Text("Length", fontSize = 13.sp, color = p.sub)
                                Slider(len, { len = it; apps = apps.map { x -> if (x.pkg == a.pkg) x.copy(len = it) else x } }, valueRange = 0.04f..0.6f,
                                    onValueChangeFinished = { save(apps) })
                                Text("Thickness", fontSize = 13.sp, color = p.sub)
                                Slider(th, { th = it; apps = apps.map { x -> if (x.pkg == a.pkg) x.copy(thick = it.toInt()) else x } }, valueRange = 3f..14f,
                                    onValueChangeFinished = { save(apps) })
                                Row {
                                    TextAction("Try it") { EdgeLight.demo() }
                                    Spacer(Modifier.weight(1f))
                                    TextAction("Remove", p.danger) { open = null; save(apps.filter { it.pkg != a.pkg }) }
                                }
                            }
                        }
                    }
                    if (apps.isNotEmpty()) GroupDivider(start = 20.dp)
                    ERow("+ Add an app", null, onClick = { picking = true })
                }
            }
        }
        item(key = "quiet") {
            Column {
                SectionLabel("Quiet while using")
                GroupBox {
                    ERow(
                        "Apps", if (quiet.isEmpty()) "None" else "Glows wait while ${quiet.size} apps are open (videos, games…)",
                        onClick = { pickingQuiet = true }
                    )
                    GroupDivider(start = 20.dp)
                    ERow(
                        "Usage access", if (canUsage) "Allowed" else "Needed to know which app is open, tap to allow",
                        if (canUsage) p.accent else p.danger,
                        onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    )
                }
            }
        }
        item(key = "test") {
            Box(Modifier.padding(top = 16.dp)) { BigButton("Show all glows for a few seconds") { EdgeLight.demo() } }
        }
        item(key = "how") {
            Text(
                "Tap a glow, or drag it inward, to peek. From there you can reply, open the chat in a pop-up window, or clear it.",
                fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
            )
        }
    }

    if (picking) {
        AppPicker("Add an app", appsToPick().filter { x -> apps.none { it.pkg == x.first } }, emptySet(), multi = false,
            onDismiss = { picking = false }) { chosen ->
            chosen.firstOrNull()?.let { pkg -> save(apps + EdgeConfig.newFor(pkg, apps)); open = pkg }
            picking = false
        }
    }
    if (pickingQuiet) {
        AppPicker("Quiet while using", (appsToPick() + knownQuiet).distinctBy { it.first }.sortedBy { it.second.lowercase() }, quiet, multi = true,
            onDismiss = { pickingQuiet = false }) { chosen ->
            quiet = chosen; Prefs.edgeQuiet = chosen; pickingQuiet = false; EdgeLight.refresh()
        }
    }
}

@Composable
private fun AppPicker(
    title: String, apps: List<Pair<String, String>>, selected: Set<String>, multi: Boolean,
    onDismiss: () -> Unit, onDone: (Set<String>) -> Unit
) {
    val p = LocalPalette.current
    var chosen by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp), containerColor = p.card,
        title = { Text(title, fontSize = 20.sp) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                apps.forEach { (pkg, name) ->
                    val sel = pkg in chosen
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable {
                                if (multi) chosen = if (sel) chosen - pkg else chosen + pkg
                                else onDone(setOf(pkg))
                            }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(pkg, 30.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(name, fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
                        if (multi) Checkbox(checked = sel, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = { if (multi) TextAction("Done") { onDone(chosen) } },
        dismissButton = { TextAction("Cancel", p.sub, onDismiss) }
    )
}

