package com.anas.secretary.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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

/** How one animation looks at time [t] (0..1): brightness and thickness, plus a moving spot for Comet and a hue shift for Aurora. */
private class GlowFrame(val alpha: Float, val thick: Float, val spot: Float?, val hue: Float)

private fun glowFrame(anim: String, t: Float): GlowFrame {
    val w = (0.5 - 0.5 * cos(2 * PI * t)).toFloat()
    return when (anim) {
        "pulse" -> GlowFrame(if (t < 0.12f) 1f else (1f - (t - 0.12f) / 0.88f).coerceAtLeast(0.25f), 1f, null, 0f)
        "comet" -> GlowFrame(0.3f, 1f, t, 0f)
        "wave" -> GlowFrame(0.5f + 0.5f * w, 0.6f + 0.5f * w, null, 0f)
        "aurora" -> GlowFrame(0.85f, 1f, null, 40f * sin(2 * PI * t).toFloat())
        else -> GlowFrame(0.35f + 0.65f * w, 1f, null, 0f)
    }
}

private fun shift(c: Int, hue: Float): Color {
    if (hue == 0f) return Color(c)
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv)
    hsv[0] = (hsv[0] + hue + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** One glow strip, drawn the way the real glow animates. */
private fun DrawScope.glow(edge: String, pos: Float, len: Float, thickPx: Float, color: Int, f: GlowFrame, dim: Float = 1f) {
    val w = size.width; val h = size.height
    val th = thickPx * f.thick
    val c = shift(color, f.hue)
    fun strip(from: Float, l: Float): Pair<Offset, Size> = when (edge) {
        "left" -> Offset(0f, h * from) to Size(th, h * l)
        "top" -> Offset(w * from, 0f) to Size(w * l, th)
        "bottom" -> Offset(w * from, h - th) to Size(w * l, th)
        else -> Offset(w - th, h * from) to Size(th, h * l)
    }
    val (o, s) = strip(pos, len)
    drawRoundRect(c.copy(alpha = (f.alpha * dim).coerceIn(0f, 1f)), o, s, CornerRadius(th / 2))
    // soft halo
    drawRoundRect(c.copy(alpha = 0.18f * f.alpha * dim), Offset(o.x - th, o.y - th), Size(s.width + 2 * th, s.height + 2 * th), CornerRadius(th * 1.5f))
    if (f.spot != null) {
        val l = len * 0.3f
        val (so, ss) = strip(pos + (len - l) * f.spot, l)
        drawRoundRect(c.copy(alpha = dim), so, ss, CornerRadius(th / 2))
    }
}

@Composable
private fun rememberGlowTime(): Float {
    val t by rememberInfiniteTransition(label = "glow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t"
    )
    return t
}

/** A dark screen with every app's glow, animated the way the edge will look. [highlight] dims the other apps. */
@Composable
private fun EdgePreview(apps: List<EdgeApp>, anim: String, highlight: String?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val t = rememberGlowTime()
    Canvas(modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF0D0F14))) {
        val f = glowFrame(anim, t)
        if (apps.isEmpty()) {
            glow("right", 0.3f, 0.35f, 5.dp.toPx(), p.accent.toArgb(), f, 0.5f)
        }
        apps.forEach { a ->
            glow(a.edge, a.pos, a.len, (a.thick * 0.9f).dp.toPx(), a.color, f, if (highlight == null || highlight == a.pkg) 1f else 0.25f)
        }
    }
}

/** A small card that plays one animation; tap to pick it. */
@Composable
private fun AnimCard(key: String, label: String, color: Int, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val t = rememberGlowTime()
    Column(
        Modifier.width(88.dp).clip(RoundedCornerShape(18.dp)).background(p.card)
            .border(2.dp, if (selected) p.accent else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF0D0F14))) {
            glow("right", 0.15f, 0.7f, 4.dp.toPx(), color, glowFrame(key, t))
        }
        Text(label, fontSize = 12.5.sp, color = if (selected) p.text else p.sub, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A row with a coloured icon, a title, what's set now, and a switch. */
@Composable
private fun SwitchNavRow(icon: ImageVector, tint: Color, title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = p.text)
            Text(summary, fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BackTo(text: String, onBack: () -> Unit) {
    Box(Modifier.padding(bottom = 8.dp)) { TextAction(text, onClick = onBack) }
}

internal val glassLevels = listOf("Off", "Light", "Medium", "Strong")

/** Off / Light / Medium / Strong, as one segmented row. */
@Composable
internal fun LevelPicker(value: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.field).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        glassLevels.forEachIndexed { i, l ->
            val sel = value == i
            Text(
                l, fontSize = 13.sp, color = if (sel) p.text else p.sub, textAlign = TextAlign.Center, maxLines = 1,
                fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (sel) p.card else Color.Transparent)
                    .clickable { onPick(i) }.padding(vertical = 8.dp)
            )
        }
    }
}

private val autoHideChoices = listOf(0 to "Never", 5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EdgePage(tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var sub by remember { mutableStateOf("main") }
    BackHandler(enabled = sub != "main") { sub = "main" }
    val mainList = rememberLazyListState()

    var on by remember { mutableStateOf(Prefs.edgeOn) }
    var smart by remember { mutableStateOf(Prefs.edgeSmart) }
    var anim by remember { mutableStateOf(Prefs.edgeAnim) }
    var apps by remember { mutableStateOf(EdgeConfig.all()) }
    var quiet by remember { mutableStateOf(Prefs.edgeQuiet) }
    var swipeHide by remember { mutableStateOf(Prefs.edgeSwipeHide) }
    var hideButton by remember { mutableStateOf(Prefs.edgeHideButton) }
    var autoHide by remember { mutableIntStateOf(Prefs.edgeAutoHide) }
    var sleepOn by remember { mutableStateOf(Prefs.sleepEdge) }
    var people by remember { mutableStateOf(Prefs.sleepPeople) }
    var hours by remember { mutableStateOf(Prefs.sleepFrom to Prefs.sleepTo) }
    var popup by remember { mutableStateOf(Prefs.openPopup) }
    var inPanel by remember { mutableStateOf(Prefs.edgeAppInPanel) }
    var editing by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var pickingQuiet by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val canDraw = remember(tick) { EdgeLight.canDraw(ctx) }
    val canUsage = remember(tick) { EdgeLight.canUsage(ctx) }
    val names = remember(apps.map { it.pkg }) { appsToPick().toMap() }
    fun nameOf(pkg: String) = names[pkg] ?: knownQuiet.firstOrNull { it.first == pkg }?.second ?: Fmt.appLabel(ctx, pkg)

    fun save(list: List<EdgeApp>) { apps = list; EdgeConfig.save(list); EdgeLight.refresh() }
    fun askOverlay() = ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
    fun setOn(v: Boolean) { on = v; Prefs.edgeOn = v; EdgeLight.refresh(); if (v && !canDraw) askOverlay() }

    when (sub) {
        "sleep" -> OneUiScreen("While you sleep", "Only what matters lights up at night") {
            item(key = "back") { BackTo("Back to Edge lighting") { sub = "main" } }
            item(key = "sleep") {
                GroupBox {
                    ERow(
                        "Sleep mode", "In your usual sleep hours (from your watch), only calls and the people below light up the edge.",
                        onClick = { sleepOn = !sleepOn; Prefs.sleepEdge = sleepOn; EdgeLight.refresh() }
                    ) { Switch(checked = sleepOn, onCheckedChange = { sleepOn = it; Prefs.sleepEdge = it; EdgeLight.refresh() }) }
                }
            }
            if (sleepOn) item(key = "who") {
                Column {
                    SectionLabel("Still glow for")
                    GroupBox {
                        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            people.sorted().forEach { n -> Chip("$n  ✕", false) { people = people - n; Prefs.sleepPeople = people; EdgeLight.refresh() } }
                            Chip("+ Add", false) { adding = true }
                        }
                    }
                    SectionLabel("Until your watch has a few nights")
                    GroupBox {
                        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        }
        "hide" -> OneUiScreen("Hiding glows", "The notification always stays in the shade") {
            item(key = "back") { BackTo("Back to Edge lighting") { sub = "main" } }
            item(key = "hide") {
                GroupBox {
                    ERow(
                        "Swipe out to hide", "Push the glow toward the edge, or long-press it",
                        onClick = { swipeHide = !swipeHide; Prefs.edgeSwipeHide = swipeHide; EdgeLight.refresh() }
                    ) { Switch(checked = swipeHide, onCheckedChange = { swipeHide = it; Prefs.edgeSwipeHide = it; EdgeLight.refresh() }) }
                    GroupDivider(start = 20.dp)
                    ERow(
                        "Hide glow button", "Tap the glow, then Hide glow at the top of the panel",
                        onClick = { hideButton = !hideButton; Prefs.edgeHideButton = hideButton }
                    ) { Switch(checked = hideButton, onCheckedChange = { hideButton = it; Prefs.edgeHideButton = it }) }
                }
            }
            item(key = "auto") {
                Column {
                    SectionLabel("Hide unseen glows after")
                    GroupBox {
                        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            autoHideChoices.forEach { (m, l) -> Chip(l, autoHide == m) { autoHide = m; Prefs.edgeAutoHide = m; EdgeLight.refresh() } }
                        }
                        Text(
                            if (autoHide == 0) "Glows stay until you open or clear them." else "The glow turns off by itself.",
                            fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
                        )
                    }
                }
            }
        }
        "open" -> OneUiScreen("Opening apps", "What Open does in the panel") {
            item(key = "back") { BackTo("Back to Edge lighting") { sub = "main" } }
            item(key = "how") {
                GroupBox {
                    ERow(
                        "Pop-up window", "The chat opens in a small floating window, where the phone supports it. Off: full screen.",
                        onClick = { popup = !popup; Prefs.openPopup = popup }
                    ) { Switch(checked = popup, onCheckedChange = { popup = it; Prefs.openPopup = it }) }
                    GroupDivider(start = 20.dp)
                    ERow(
                        "Small side window", "Optional. The app opens in a small window on the side, and the app behind keeps working. Needs the Shizuku app (no root). Without Shizuku, apps open in a pop-up window.",
                        onClick = { inPanel = !inPanel; Prefs.edgeAppInPanel = inPanel }
                    ) { Switch(checked = inPanel, onCheckedChange = { inPanel = it; Prefs.edgeAppInPanel = it }) }
                }
            }
            if (inPanel) item(key = "side") {
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
                var size by remember { mutableIntStateOf(Prefs.sideSize) }
                var side by remember { mutableStateOf(Prefs.sideEdge) }
                var keep by remember { mutableStateOf(Prefs.sideRemember) }
                val (sum, ok) = when (st) {
                    Shizu.State.Ready -> "Running · allowed" to true
                    Shizu.State.NotAllowed -> "Running · tap to allow Daybrief" to false
                    Shizu.State.NotRunning -> "Not running · tap, then Start in Shizuku" to false
                    Shizu.State.NotInstalled -> "Not installed · tap to get it" to false
                }
                Column {
                    SectionLabel("Side window")
                    GroupBox {
                        ERow("Shizuku", sum, if (ok) p.accent else p.danger, onClick = {
                            if (st == Shizu.State.NotAllowed) Shizu.ask() else Shizu.openShizuku(ctx)
                        })
                        GroupDivider(start = 20.dp)
                        ERow("Size", null)
                        FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Small", "Medium", "Large").forEachIndexed { i, l -> Chip(l, size == i) { size = i; Prefs.sideSize = i } }
                        }
                        GroupDivider(start = 20.dp)
                        ERow("Opens on", null)
                        FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        "quiet" -> OneUiScreen("Quiet while using", "Glows wait while these apps are open") {
            item(key = "back") { BackTo("Back to Edge lighting") { sub = "main" } }
            item(key = "quiet") {
                GroupBox {
                    ERow(
                        "Apps", if (quiet.isEmpty()) "None" else quiet.map { nameOf(it) }.sortedBy { it.lowercase() }.joinToString(", "),
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
        else -> OneUiScreen("Edge lighting", "Each app glows in its own color", state = mainList) {
            item(key = "back") { BackTo("Back to settings", onBack) }
            if (!canDraw) item(key = "warn") {
                Row(
                    Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.danger.copy(alpha = 0.16f))
                        .clickable { askOverlay() }.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, null, tint = p.danger, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Show over other apps is off", fontSize = 15.sp, color = p.text)
                        Text("Nothing can glow until you allow it. Tap to allow.", fontSize = 12.sp, color = p.danger)
                    }
                }
            }
            item(key = "hero") {
                GroupBox {
                    EdgePreview(apps, anim, null, Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp))
                    ERow("Edge lighting", if (on) "On" else "Off", onClick = { setOn(!on) }) {
                        Switch(checked = on, onCheckedChange = { setOn(it) })
                    }
                }
            }
            item(key = "apps") {
                Column {
                    SectionLabel("Apps that glow")
                    GroupBox {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(Modifier.width(66.dp).clip(RoundedCornerShape(14.dp)).clickable { picking = true }.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(50.dp).clip(RoundedCornerShape(15.dp)).background(p.field), contentAlignment = Alignment.Center) {
                                    Icon(LookIcons.Plus, "Add an app", tint = p.accent, modifier = Modifier.size(24.dp))
                                }
                                Text("Add", fontSize = 12.sp, color = p.text, modifier = Modifier.padding(top = 6.dp))
                            }
                            apps.forEach { a ->
                                Column(Modifier.width(66.dp).clip(RoundedCornerShape(14.dp)).clickable { editing = a.pkg }.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(54.dp)) {
                                        Box(Modifier.align(Alignment.Center)) { AppIcon(a.pkg, 48.dp) }
                                        Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(p.card).padding(3.dp).clip(CircleShape).background(Color(a.color)))
                                    }
                                    Text(nameOf(a.pkg), fontSize = 12.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        if (apps.isEmpty()) Text(
                            "Add the apps you want to see on the edge.", fontSize = 13.sp, color = p.sub,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
                        )
                    }
                }
            }
            item(key = "anim") {
                Column {
                    SectionLabel("Animation")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val c = apps.firstOrNull()?.color ?: p.accent.toArgb()
                        EdgeConfig.anims.forEach { (k, l) -> AnimCard(k, l, c, anim == k) { anim = k; Prefs.edgeAnim = k; EdgeLight.refresh() } }
                    }
                }
            }
            item(key = "glass") {
                Column {
                    SectionLabel("Panel")
                    GroupBox {
                        var glass by remember { mutableIntStateOf(Prefs.panelGlass) }
                        val canBlur = remember(tick) { com.anas.secretary.edge.PanelGlass.canBlur(ctx) }
                        ERow(
                            "Glass look",
                            if (glass > 0 && !canBlur) "Blur is off on this phone right now (power saving, or Android 11 and older), so the panel shows solid"
                            else "The panel lets the screen show through, blurred"
                        )
                        LevelPicker(glass, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { glass = it; Prefs.panelGlass = it }
                    }
                }
            }
            item(key = "more") {
                Box(Modifier.padding(top = 18.dp)) {
                    GroupBox {
                        SwitchNavRow(LookIcons.Spark, Color(0xFF34C759), "Smart glow", "Codes and promotions stay dark", smart) {
                            smart = it; Prefs.edgeSmart = it; EdgeLight.refresh()
                        }
                        GroupDivider()
                        NavRow(LookIcons.Moon, Color(0xFFA970FF), "While you sleep",
                            if (!sleepOn) "Off" else "On · calls" + if (people.isEmpty()) "" else " + ${people.size} ${if (people.size == 1) "person" else "people"}") { sub = "sleep" }
                        GroupDivider()
                        NavRow(LookIcons.EyeOff, Color(0xFFFFB020), "Hiding glows",
                            listOfNotNull(
                                "Swipe out".takeIf { swipeHide }, "Hide button".takeIf { hideButton },
                                autoHideChoices.firstOrNull { it.first == autoHide && autoHide > 0 }?.let { "after ${it.second}" }
                            ).joinToString(" · ").ifEmpty { "Only by opening or clearing" }) { sub = "hide" }
                        GroupDivider()
                        NavRow(LookIcons.Window, Color(0xFF3E91FF), "Opening apps",
                            when { inPanel -> "Small side window"; popup -> "Pop-up window"; else -> "Full screen" }) { sub = "open" }
                        GroupDivider()
                        NavRow(LookIcons.Pause, Color(0xFFFF5A5F), "Quiet while using",
                            when {
                                quiet.isEmpty() -> "None"
                                !canUsage -> "${quiet.size} apps · needs usage access"
                                else -> "${quiet.size} ${if (quiet.size == 1) "app" else "apps"} · " + quiet.map { nameOf(it) }.sortedBy { it.lowercase() }.take(2).joinToString(", ") + if (quiet.size > 2) "…" else ""
                            }) { sub = "quiet" }
                    }
                }
            }
            item(key = "test") {
                Box(Modifier.padding(top = 16.dp)) { BigButton("Try it") { EdgeLight.demo() } }
            }
            item(key = "how") {
                Text(
                    "Tap a glow, or drag it inward, to peek. From there you can reply, open the chat, or clear it.",
                    fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
                )
            }
        }
    }

    editing?.let { pkg ->
        apps.firstOrNull { it.pkg == pkg }?.let { a ->
            AppSheet(a, nameOf(a.pkg), apps, anim,
                onLive = { x -> apps = apps.map { if (it.pkg == x.pkg) x else it } },
                onSave = { x -> save(apps.map { if (it.pkg == x.pkg) x else it }) },
                onCommit = { save(apps) },
                onRemove = { editing = null; save(apps.filter { it.pkg != pkg }) },
                onDismiss = { editing = null })
        }
    }
    if (picking) {
        AppPicker("Add an app", appsToPick().filter { x -> apps.none { it.pkg == x.first } }, emptySet(), multi = false,
            onDismiss = { picking = false }) { chosen ->
            chosen.firstOrNull()?.let { pkg -> save(apps + EdgeConfig.newFor(pkg, apps)); editing = pkg }
            picking = false
        }
    }
    if (pickingQuiet) {
        AppPicker("Quiet while using", (appsToPick() + knownQuiet).distinctBy { it.first }.sortedBy { it.second.lowercase() }, quiet, multi = true,
            onDismiss = { pickingQuiet = false }) { chosen ->
            quiet = chosen; Prefs.edgeQuiet = chosen; pickingQuiet = false; EdgeLight.refresh()
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

/** Bottom sheet for one app: color, edge, place and size, with the glow playing live at the top. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AppSheet(
    a: EdgeApp, name: String, all: List<EdgeApp>, anim: String,
    onLive: (EdgeApp) -> Unit, onSave: (EdgeApp) -> Unit, onCommit: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit
) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a.pkg, 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = p.text)
                    Text("${EdgeConfig.edges.firstOrNull { it.first == a.edge }?.second ?: a.edge} edge", fontSize = 13.sp, color = p.sub)
                }
                TextAction("Remove", p.danger, onRemove)
            }
            EdgePreview(all, anim, a.pkg, Modifier.padding(top = 14.dp))
            Text("Color", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EdgeConfig.palette.forEach { c ->
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                            .then(if (c == a.color) Modifier.border(3.dp, p.text, CircleShape) else Modifier)
                            .clickable { onSave(a.copy(color = c)) }
                    )
                }
            }
            Text("Edge", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.field).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                EdgeConfig.edges.forEach { (k, l) ->
                    val sel = a.edge == k
                    Text(
                        l, fontSize = 13.sp, color = if (sel) p.text else p.sub, textAlign = TextAlign.Center,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (sel) p.card else Color.Transparent)
                            .clickable { onSave(a.copy(edge = k)) }.padding(vertical = 8.dp)
                    )
                }
            }
            Text("Position", fontSize = 13.sp, color = p.sub, modifier = Modifier.padding(top = 14.dp))
            Slider(a.pos, { onLive(a.copy(pos = it)) }, valueRange = 0f..0.95f, onValueChangeFinished = onCommit)
            Text("Length", fontSize = 13.sp, color = p.sub)
            Slider(a.len, { onLive(a.copy(len = it)) }, valueRange = 0.04f..0.6f, onValueChangeFinished = onCommit)
            Text("Thickness", fontSize = 13.sp, color = p.sub)
            Slider(a.thick.toFloat(), { onLive(a.copy(thick = it.toInt())) }, valueRange = 3f..14f, onValueChangeFinished = onCommit)
            Box(Modifier.padding(top = 8.dp)) { BigButton("Try it on the edge") { EdgeLight.demo() } }
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

