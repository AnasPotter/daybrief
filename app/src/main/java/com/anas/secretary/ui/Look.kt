package com.anas.secretary.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.util.Prefs
import java.time.LocalTime

// ======================= The Samsung Health–style look =======================

/** Opens Settings from the avatar at the top of the main pages; null where there's no avatar (Settings itself). */
val LocalOpenSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The three colours of the background glow: top, middle, and the soft glow in the lower middle. */
private data class Glow(val top: Color, val mid: Color, val bottom: Color, val glow: Color)

private fun glowFor(dark: Boolean, hour: Int): Glow = when {
    !dark -> when (hour) {
        in 5..10 -> Glow(Color(0xFFFFE9D6), Color(0xFFF6F1EC), Color(0xFFF2F2F2), Color(0xFFFFD9B8))
        in 11..17 -> Glow(Color(0xFFDCE7FF), Color(0xFFEEF1F8), Color(0xFFF2F2F2), Color(0xFFFFE2C7))
        else -> Glow(Color(0xFFE4DDFF), Color(0xFFEFEDF7), Color(0xFFF2F2F2), Color(0xFFE8D9FF))
    }
    hour in 5..10 -> Glow(Color(0xFF24407A), Color(0xFF16213F), Color(0xFF0C0C10), Color(0xFF8A4A12))  // morning, warm amber
    hour in 11..17 -> Glow(Color(0xFF14275A), Color(0xFF0F1830), Color(0xFF0C0C10), Color(0xFF6B3410)) // day, blue with an orange glow
    else -> Glow(Color(0xFF0F1D4A), Color(0xFF0C1430), Color(0xFF0B0C14), Color(0xFF3B1D6B))          // night, deep purple
}

/** The hour the glow is drawn for; MainActivity updates it on resume so every part of the screen agrees. */
object GlowClock {
    var hour by mutableIntStateOf(LocalTime.now().hour)
}

@Composable
private fun currentGlow(): Glow = glowFor(isSystemInDarkTheme(), GlowClock.hour)

/** The colour at the very top of the glow, for title bars that must blend into it. */
@Composable
fun glowTop(): Color = currentGlow().top

/** The colour at the bottom of the glow, for the fade behind the bottom bar. */
@Composable
fun glowBottom(): Color = currentGlow().bottom

/** Background of the main pages: a gradient with a soft glow that changes with the time of day. */
@Composable
fun GlowBackground(modifier: Modifier = Modifier) {
    val g = currentGlow()
    Box(modifier.fillMaxSize().drawBehind {
        drawRect(Brush.verticalGradient(0f to g.top, 0.32f to g.mid, 0.7f to g.bottom, 1f to g.bottom))
        drawRect(
            Brush.radialGradient(
                listOf(g.glow.copy(alpha = 0.85f), g.glow.copy(alpha = 0f)),
                center = Offset(size.width / 2, size.height * 0.56f), radius = size.width * 0.95f
            )
        )
    })
}

// ======================= Icons (stroke, like the mockup) =======================

object LookIcons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach {
            b.addPath(
                addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
        }
        return b.build()
    }

    val Grid by lazy { icon("Grid", rr(4f, 4f, 7f, 7f, 2.5f), rr(13f, 4f, 7f, 7f, 2.5f), rr(4f, 13f, 7f, 7f, 2.5f), rr(13f, 13f, 7f, 7f, 2.5f)) }
    val Person by lazy { icon("Person", circle(12f, 8f, 3.5f), "M5 20c1-4 4-6 7-6s6 2 7 6") }
    val Work by lazy { icon("Work", rr(4f, 7f, 16f, 12f, 3f), "M9 7V5.5A1.5 1.5 0 0 1 10.5 4h3A1.5 1.5 0 0 1 15 5.5V7") }
    val Things by lazy { icon("Things", "M12 4v4M12 16v4M4 12h4M16 12h4", circle(12f, 12f, 3f)) }
    val People by lazy { icon("People", circle(9f, 9f, 3f), circle(17f, 10f, 2.5f), "M3.5 19c.8-3 3-4.5 5.5-4.5s4.7 1.5 5.5 4.5M15 15c2.5 0 4.5 1.3 5.3 4") }
    val Heart by lazy { icon("Heart", "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z") }
    val Clock by lazy { icon("Clock", circle(12f, 12f, 8f), "M12 8v4l3 2") }
    val Home by lazy { icon("Home", "M4 11l8-7 8 7v8a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z") }
    val Calendar by lazy { icon("Calendar", rr(4f, 5f, 16f, 15f, 3f), "M4 10h16M9 3v4M15 3v4") }
    val Track by lazy { icon("Track", "M5 19V9M12 19V5M19 19v-7") }
    val Saved by lazy { icon("Saved", "M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z") }
    val Plus by lazy { icon("Plus", "M12 5v14M5 12h14") }
    val Search by lazy { icon("Search", circle(11f, 11f, 6.5f), "M16 16l4.5 4.5") }
    val Moon by lazy { icon("Moon", "M19.5 14.5A7.5 7.5 0 1 1 9.5 4.5a6 6 0 0 0 10 10z") }
    val EyeOff by lazy { icon("EyeOff", "M3 12s3.5-6 9-6c1.6 0 3 .5 4.2 1.2M21 12s-3.5 6-9 6c-1.6 0-3-.5-4.2-1.2", "M10 10a3 3 0 0 0 4 4", "M4 4l16 16") }
    val Window by lazy { icon("Window", rr(4f, 5f, 16f, 14f, 3f), "M4 9h16", rr(11f, 12f, 6f, 4.5f, 1.2f)) }
    val Pause by lazy { icon("Pause", rr(4f, 5f, 16f, 14f, 3f), "M10 9.5v5M14 9.5v5") }
    val Spark by lazy { icon("Spark", "M12 3.5l2 5.5 5.5 2-5.5 2-2 5.5-2-5.5-5.5-2 5.5-2z") }
}

// ======================= Pieces =======================

/** Your initial in a round gradient; tapping it opens Settings. */
@Composable
fun Avatar(size: Dp = 38.dp) {
    val open = LocalOpenSettings.current ?: return
    val initial = Prefs.name.trim().firstOrNull()?.uppercase() ?: "S"
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF3E91FF), WorkColor)))
            .clickable(onClick = open)
            .semantics { contentDescription = "Settings" },
        contentAlignment = Alignment.Center
    ) { Text(initial, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** Header of a main page: a small line above a bold title, with the avatar on the right. */
@Composable
fun MainHeader(title: String, small: String? = null, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 6.dp, top = 26.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (small != null) Text(small, fontSize = 13.sp, color = p.text.copy(alpha = 0.75f))
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.padding(top = 2.dp))
        }
        SearchButton()
        Spacer(Modifier.width(6.dp))
        Avatar()
    }
}

/** Icon-only switcher in a rounded outline, like the top of Samsung Health. Small numbers show as a badge. */
@Composable
fun IconSwitcher(
    items: List<Pair<ImageVector, String>>, selected: Int, badges: List<Int>? = null,
    labels: Boolean = false, onSelect: (Int) -> Unit
) {
    val view = LocalView.current
    val p = LocalPalette.current
    val dark = isSystemInDarkTheme()
    val line = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp).clip(RoundedCornerShape(50))
            .background(if (dark) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.6f))
            .border(1.5.dp, line, RoundedCornerShape(50)).padding(4.dp)
    ) {
        val w = maxWidth / items.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.8f, stiffness = 420f), label = "switcher")
        Box(
            Modifier.offset(x = x).width(w).height(46.dp).clip(RoundedCornerShape(50))
                .background(if (dark) Color.White.copy(alpha = 0.13f) else p.card)
        )
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, (icon, label) ->
                val tint by animateColorAsState(if (i == selected) p.text else p.text.copy(alpha = 0.7f), tween(220), label = "switcherTint")
                Box(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(50)).clickable { if (i != selected) Feel.tick(view); onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    // Icon, then the name (selected only, when labels are on) and the count as plain text beside it
                    val n = badges?.getOrNull(i) ?: 0
                    val sel = i == selected
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
                        // The name gives way first on large fonts; the count never wraps or gets cut
                        if (labels && sel) Text(
                            label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1,
                            softWrap = false, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).padding(start = 6.dp)
                        )
                        if (n > 0) Text(
                            "$n", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                            color = if (sel) p.accent else p.sub, modifier = Modifier.padding(start = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

/** A card in the new look: bigger corners, a title, and an optional orange "new" dot. */
@Composable
fun Tile(
    title: String?, modifier: Modifier = Modifier, dot: Boolean = false, onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalPalette.current
    Box(
        modifier.clip(RoundedCornerShape(28.dp)).background(glassCard())
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 16.dp)) {
            if (title != null) Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
            content()
        }
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(top = 17.dp, end = 17.dp).size(8.dp).clip(CircleShape).background(TileColors.orange))
    }
}

/** A big bold number with a small unit after it. Whole numbers count up when the page opens. */
@Composable
fun BigNumber(value: String, unit: String? = null, color: Color? = null, size: Int = 44, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val target = value.toIntOrNull()
    val shown = if (target != null && target > 0) {
        val ctx = LocalContext.current
        val anim = remember { Animatable(if (Motion.play(ctx)) 0f else target.toFloat()) }
        LaunchedEffect(target) { anim.animateTo(target.toFloat(), tween(700, easing = FastOutSlowInEasing)) }
        anim.value.roundToInt().toString()
    } else value
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(shown, fontSize = size.sp, fontWeight = FontWeight.Bold, color = color ?: p.text, lineHeight = (size + 2).sp, letterSpacing = (-0.5).sp)
        if (unit != null) Text(unit, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.sub, modifier = Modifier.padding(start = 4.dp, bottom = (size / 7).dp))
    }
}

/** Colours for the coloured words and dots on the tiles (Calm, Busy, Fair…). */
object TileColors {
    val green = Color(0xFF5BD16B)
    val yellow = Color(0xFFFFD84D)
    val orange = Color(0xFFFF7A2F)
    val pink = Color(0xFFFF4F7B)
    val blue = Color(0xFF8FB3FF)
}

// ======================= Motion =======================

/** Page-open motion plays only for things that appear right after a tab opens, so scrolling back never replays it. */
object Motion {
    var visitAt = 0L

    /** True while a page has just opened and the phone's animations are on. */
    fun play(ctx: Context): Boolean {
        if (System.currentTimeMillis() - visitAt > 900) return false
        val scale = try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Exception) { 1f }
        return scale > 0f
    }
}

/** Fades the card in and lifts it into place, a little after the one above it. */
fun Modifier.rise(index: Int): Modifier = composed {
    val ctx = LocalContext.current
    val a = remember { Animatable(if (Motion.play(ctx)) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (a.value < 1f) { delay(60L * index); a.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    }
    graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 28.dp.toPx() }
}

// ======================= Bottom bar: 4 tabs + a round add button =======================

@Composable
fun LookBottomBar(selected: Int, showAdd: Boolean, visible: List<Int> = listOf(0, 1, 2, 3), onSelect: (Int) -> Unit, onAdd: () -> Unit) {
    val view = LocalView.current
    val p = LocalPalette.current
    val dark = isSystemInDarkTheme()
    val bar = if (dark) Color(0xF026262B) else Color(0xF7FFFFFF)
    val line = if (dark) Color(0x22FFFFFF) else Color(0x14000000)
    val all = listOf("Brief" to LookIcons.Home, "Calendar" to LookIcons.Calendar, "Track" to LookIcons.Track, "Saved" to LookIcons.Saved)
    // Parts switched off in Features have no tab; positions follow what's left
    val tabs = visible.map { all[it] }
    val selPos = visible.indexOf(selected)
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BoxWithConstraints(
            Modifier.weight(1f).clip(RoundedCornerShape(50)).frosted(LocalHaze.current, bar, if (dark) Color(0xFF26262B) else Color.White).border(1.dp, line, RoundedCornerShape(50)).padding(5.dp)
        ) {
            val w = maxWidth / tabs.size
            if (selPos >= 0) {
                val x by animateDpAsState(w * selPos, spring(dampingRatio = 0.78f, stiffness = 420f), label = "tabIndicator")
                Box(
                    Modifier.offset(x = x).width(w).height(56.dp).clip(RoundedCornerShape(50))
                        .background(if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFFE9E9EB))
                )
            }
            Row(Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { pos, (label, icon) ->
                    val i = visible[pos]
                    val sel = i == selected
                    val tint by animateColorAsState(if (sel) p.text else p.sub, tween(250), label = "tabTint")
                    Column(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(50)).clickable { if (i != selected) Feel.tick(view); onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(
                            label, fontSize = 11.sp, color = tint, maxLines = 1,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
        if (showAdd) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(66.dp).clip(CircleShape).frosted(LocalHaze.current, bar, if (dark) Color(0xFF26262B) else Color.White).border(1.dp, line, CircleShape)
                    .clickable { Feel.tick(view); onAdd() }.semantics { contentDescription = "Add" },
                contentAlignment = Alignment.Center
            ) { Icon(LookIcons.Plus, null, tint = p.text, modifier = Modifier.size(26.dp)) }
        }
    }
}
