package com.anas.secretary.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
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
/** Overdue text: the tile pink on dark, a deeper rose on light cards so small text stays readable. */
@Composable
fun dueText(): Color = if (isSystemInDarkTheme()) TileColors.pink else Color(0xFFC8234F)

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

/** The bar folds into a small pill while you scroll down a page, and opens again when you scroll up. */
object BarFold {
    var folded by mutableStateOf(false)
    private var acc = 0f

    /** A new page starts with the bar open and nothing counted yet. */
    fun reset() { folded = false; acc = 0f }

    /** Watches every page's scrolling (nested scroll), without taking any of it. */
    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
            val dy = consumed.y // < 0 = the page moved up (reading on)
            if (dy == 0f) return Offset.Zero
            acc = if ((dy < 0) == (acc < 0)) acc + dy else dy
            if (acc < -60f) folded = true
            if (acc > 36f) folded = false
            return Offset.Zero
        }
    }
}

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
    // A new page starts with the bar open
    LaunchedEffect(selected) { BarFold.reset() }
    val fold by animateFloatAsState(if (BarFold.folded && selPos >= 0) 1f else 0f, spring(dampingRatio = 0.86f, stiffness = 380f), label = "barFold")
    BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
        val addFull = 66.dp
        val addSize = lerpDp(addFull, 52.dp, fold)
        val gap = 10.dp
        val full = if (showAdd) maxWidth - addFull - gap else maxWidth
        val barW = lerpDp(full, 66.dp, fold)
        val barH = lerpDp(66.dp, 52.dp, fold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            // Not clipped: the lifted highlight stands past the bar's edges
            Box(Modifier.width(barW).height(barH)) {
                Box(
                    Modifier.matchParentSize().clip(RoundedCornerShape(50))
                        .frosted(LocalHaze.current, bar, if (dark) Color(0xFF26262B) else Color.White).border(1.dp, line, RoundedCornerShape(50))
                )
                PillTabs(tabs.size, selPos, fold, dark, onDragTo = { pos -> onSelect(visible[pos]) }) { pos, w, tap ->
                    val (label, icon) = tabs[pos]
                    val i = visible[pos]
                    val sel = i == selected
                    val tint by animateColorAsState(if (sel) p.text else p.sub, tween(250), label = "tabTint")
                    val iconScale by animateFloatAsState(if (sel) 1.08f else 1f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "tabIcon")
                    // Folded: only the current tab stays, as an icon
                    val keep = if (sel) 1f else 1f - fold
                    Column(
                        Modifier.width(if (sel) lerpDp(w, barW - 10.dp, fold) else w * (1f - fold)).fillMaxHeight()
                            .graphicsLayer { alpha = keep }
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = fold < 0.5f) { if (i != selected) { Feel.tick(view); tap() } },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
                        if (fold < 0.95f) Text(
                            label, fontSize = 11.sp, color = tint, maxLines = 1,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(top = 2.dp).graphicsLayer { alpha = 1f - fold }
                        )
                    }
                }
                if (fold > 0.5f) Box(
                    Modifier.matchParentSize().clip(RoundedCornerShape(50))
                        .clickable(onClickLabel = "Show all tabs") { BarFold.folded = false }
                        .semantics { contentDescription = (tabs.getOrNull(selPos)?.first ?: "Tabs") + ", tabs folded" }
                )
            }
            if (showAdd) {
                Spacer(Modifier.width(gap))
                Box(
                    Modifier.size(addSize).clip(CircleShape).frosted(LocalHaze.current, bar, if (dark) Color(0xFF26262B) else Color.White).border(1.dp, line, CircleShape)
                        .clickable { Feel.tick(view); onAdd() }.semantics { contentDescription = "Add" },
                    contentAlignment = Alignment.Center
                ) { Icon(LookIcons.Plus, null, tint = p.text, modifier = Modifier.size(lerpDp(26.dp, 22.dp, fold))) }
            }
        }
    }
}

private fun lerpDp(a: Dp, b: Dp, t: Float): Dp = a + (b - a) * t

/** How much taller than the bar the highlight stands while it's lifted. */
private val PILL_GROW = 9.dp

/**
 * The tabs and their highlight, moved by [PillMotion]. A flat fill sits under the tabs at rest; while it
 * travels it becomes a clear glass lens over them (rim and soft shadow), bigger than the bar.
 * Drag along the bar to carry it; letting go opens the tab under it.
 */
@Composable
private fun PillTabs(count: Int, selPos: Int, fold: Float, dark: Boolean, onDragTo: (Int) -> Unit, tab: @Composable (pos: Int, w: Dp, tap: () -> Unit) -> Unit) {
    val view = LocalView.current
    val ctx = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = rememberCoroutineScope()
    val pill = remember { PillMotion(scope, maxOf(selPos, 0)) }
    pill.reduceMotion = remember {
        try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false }
    }
    // Travel to a tab chosen elsewhere (Search, a widget); jump straight there the first time,
    // when coming back from Settings, or when the number of tabs changed
    var lastSel by remember { mutableIntStateOf(-1) }
    var lastCount by remember { mutableIntStateOf(count) }
    LaunchedEffect(selPos, count) {
        if (selPos >= 0) {
            if (lastSel < 0 || count != lastCount) pill.snapTo(selPos) else pill.animateTo(selPos)
        }
        lastSel = selPos; lastCount = count
    }
    val curSel by rememberUpdatedState(selPos)
    val curDragTo by rememberUpdatedState(onDragTo)
    val fill = if (dark) Color.White.copy(alpha = 0.13f) else Color(0xFFE6E6EA)
    val pad = 5.dp
    BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
        val w = maxWidth / count
        val stepPx = constraints.maxWidth / count.toFloat()
        val rowH = constraints.maxHeight.toFloat()
        pill.stepDp = stepPx / density.density
        val rest = Size(stepPx, rowH)
        val liftedH = rowH + with(density) { (pad * 2 + PILL_GROW).toPx() }
        // Grows by the same amount each way, so it stays near the bar whatever the number of tabs
        val lifted = Size(stepPx + (liftedH - rowH), liftedH)
        val center = { Offset(pill.position * stepPx + stepPx / 2f, rowH / 2f) }
        val show = selPos >= 0
        // At rest: the flat fill under the tabs (fades while the glass is on)
        if (show) Box(
            Modifier.matchParentSize().pillPlacement(center) { pill.liveSize(rest, lifted) }
                .graphicsLayer { alpha = (1f - fold) * (1f - pill.material) }
                .background(fill, RoundedCornerShape(50))
        )
        Row(
            Modifier.fillMaxSize().pointerInput(count, fold > 0.5f) {
                if (fold > 0.5f) return@pointerInput
                var total = 0f
                var startIdx = 0
                var startAt = 0f
                var holding = false
                var lastTick = 0
                try { detectHorizontalDragGestures(
                    onDragStart = {
                        holding = curSel >= 0 && size.width > 0
                        if (!holding) return@detectHorizontalDragGestures
                        // Picked up where it stands, even mid-way
                        total = 0f; startIdx = pill.position.roundToInt(); lastTick = curSel
                        startAt = pill.position
                        pill.startDrag()
                    },
                    onHorizontalDrag = { ch, d ->
                        if (!holding) return@detectHorizontalDragGestures
                        ch.consume()
                        val step = size.width / count.toFloat()
                        total += d
                        val at = (startAt + total / step).coerceIn(0f, (count - 1).toFloat())
                        pill.dragTo(at)
                        val near = at.roundToInt()
                        if (near != lastTick) { lastTick = near; Feel.tick(view) }
                    },
                    onDragEnd = {
                        if (!holding) return@detectHorizontalDragGestures
                        holding = false
                        val step = size.width / count.toFloat()
                        val next = (startAt + total / step).coerceIn(0f, (count - 1).toFloat()).roundToInt()
                        pill.release(next)
                        if (next != curSel) scope.launch { withFrameNanos { }; withFrameNanos { }; curDragTo(next) }
                    },
                    onDragCancel = {
                        if (holding) pill.release(maxOf(curSel, 0))
                        holding = false
                    }
                ) } finally {
                    // Stopped from outside (the bar folded, tabs changed): never leave it held
                    if (holding) { holding = false; pill.release(maxOf(curSel, 0)) }
                }
            },
            horizontalArrangement = Arrangement.Center
        ) {
            for (pos in 0 until count) tab(pos, w) {
                // The highlight starts moving on the touch itself; the new page is built two frames later,
                // so building it never holds up the first frames of the motion
                pill.animateTo(pos)
                scope.launch { withFrameNanos { }; withFrameNanos { }; curDragTo(pos) }
            }
        }
        // While moving: a clear glass lens over the tabs, with a rim and a soft shadow that go first on landing
        if (show && !pill.isFlat) Box(
            Modifier.matchParentSize().pillPlacement(center) { pill.liveSize(rest, lifted) }
                .graphicsLayer {
                    val rim = ((pill.glass - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    alpha = 1f - fold
                    shadowElevation = 10.dp.toPx() * pill.material * rim
                    shape = RoundedCornerShape(50)
                    clip = false
                }
                .drawBehind {
                    val m = pill.material
                    val rim = ((pill.glass - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    val r = size.height / 2f
                    val cr = androidx.compose.ui.geometry.CornerRadius(r, r)
                    drawRoundRect(Color.White.copy(alpha = (if (dark) 0.10f else 0.35f) * m), cornerRadius = cr)
                    // Light catching the top edge, like a drop of glass
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f * m), Color.Transparent), endY = size.height * 0.55f),
                        cornerRadius = cr
                    )
                    drawRoundRect(
                        Color.White.copy(alpha = 0.55f * m * rim), cornerRadius = cr,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx())
                    )
                }
        )
    }
}
