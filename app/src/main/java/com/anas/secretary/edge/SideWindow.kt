package com.anas.secretary.edge

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.anas.secretary.data.Repo
import com.anas.secretary.service.LiveActions
import com.anas.secretary.ui.AppIcon
import com.anas.secretary.ui.LocalPalette
import com.anas.secretary.ui.SecretaryTheme
import com.anas.secretary.util.Bg
import com.anas.secretary.util.Images
import com.anas.secretary.util.Prefs
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * A small floating window on the side of the screen that shows a real app (through Shizuku). It doesn't darken
 * anything and doesn't take the keyboard or Back, so the app behind keeps working. It can be moved, resized,
 * shrunk to a bubble and hold a few apps as tabs.
 */
object SideWindow {
    private var root: View? = null
    private var owner: OverlayOwner? = null
    private var lp: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var appCtx: Context? = null

    internal val tabs = mutableStateListOf<EdgeLight.Entry>()
    internal var current by mutableIntStateOf(0)
    internal var minimized by mutableStateOf(false)
    internal var replying by mutableStateOf(false)
    internal var loaded by mutableStateOf(false)
    internal var failed by mutableStateOf(false)
    /** Picture height in px; -1 = work it out from the size setting. */
    internal var picH by mutableFloatStateOf(-1f)
    internal val session = LiveSession()
    internal var texture: TextureView? = null
    private var left = false
    val isLeft get() = left
    /** Space the window needs around the picture (handle, buttons, padding), so the whole window stays on screen. */
    internal fun chrome(ctx: Context, land: Boolean) = ctx.resources.displayMetrics.density * if (land) 44f else 118f
    /** Where the window was before the keyboard pushed it up. */
    private var beforeIme: Int? = null

    val isOpen get() = root != null

    fun entry(): EdgeLight.Entry? = tabs.getOrNull(current)

    fun open(ctx: Context, e: EdgeLight.Entry, edge: String) {
        val i = tabs.indexOfFirst { it.pkg == e.pkg && it.user == e.user }
        if (root != null) {
            if (i >= 0) tabs[i] = e else if (tabs.size < 3) tabs += e else tabs[current] = e
            select(ctx, if (i >= 0) i else tabs.indexOfFirst { it.key == e.key })
            minimized = false
            return
        }
        tabs.clear(); tabs += e; current = 0
        minimized = false; replying = false; loaded = false; failed = false
        session.reset()
        val app = ctx.applicationContext
        appCtx = app
        val w = app.getSystemService(WindowManager::class.java)
        wm = w
        val land = app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val (sw, sh) = screen(app)
        left = when {
            Prefs.sideRemember && Prefs.sideLeft != null -> Prefs.sideLeft!!
            Prefs.sideEdge == "left" -> true
            Prefs.sideEdge == "right" -> false
            else -> edge == "left"
        }
        picH = if (Prefs.sideRemember) Prefs.sideHeight(land).takeIf { it > 0 }?.let { it * sh } ?: -1f else -1f
        val o = OverlayOwner().also { it.start() }
        val frame = BackFrame(app) { if (replying) { replying = false; setFocusable(false) } }
        frame.setViewTreeLifecycleOwner(o)
        frame.setViewTreeSavedStateRegistryOwner(o)
        frame.setViewTreeViewModelStoreOwner(o)
        frame.addView(ComposeView(app).apply { setContent { SecretaryTheme { SideContent() } } })
        // The keyboard (for the app inside or the reply box) pushes the window up while it's open
        if (Build.VERSION.SDK_INT >= 30) frame.setOnApplyWindowInsetsListener { _, ins ->
            onIme(ins.isVisible(WindowInsets.Type.ime()), ins.getInsets(WindowInsets.Type.ime()).bottom)
            ins
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or if (left) Gravity.LEFT else Gravity.RIGHT
            x = (12 * app.resources.displayMetrics.density).toInt()
            y = if (Prefs.sideRemember && Prefs.sideTop >= 0) (Prefs.sideTop * sh).toInt() else (sh * if (land) 0.03f else 0.12f).toInt()
            com.anas.secretary.util.Smooth.apply(app, this)
        }
        try {
            w.addView(frame, p)
            root = frame; owner = o; lp = p
        } catch (_: Exception) {
            o.stop()
            Toast.makeText(app, "Couldn't open the window", Toast.LENGTH_SHORT).show()
        }
    }

    fun close() {
        session.close()
        root?.let { v -> try { wm?.removeView(v) } catch (_: Exception) {} }
        owner?.stop()
        root = null; owner = null; lp = null; texture = null
        tabs.clear()
    }

    internal fun restore() {
        loaded = false; failed = false; minimized = false
        clampY()
    }

    internal fun select(ctx: Context, i: Int) {
        if (i !in tabs.indices) return
        current = i
        session.show(ctx, tabs[i])
    }

    internal fun setFocusable(on: Boolean) {
        val p = lp ?: return
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        update()
    }

    internal fun moveBy(dx: Float, dy: Float) {
        val p = lp ?: return
        val (sw, sh) = screen(appCtx ?: return)
        val w = root?.width ?: 0
        val h = root?.height ?: 0
        p.x = (p.x + (if (left) dx else -dx)).toInt().coerceIn(0, (sw - w).coerceAtLeast(0))
        p.y = (p.y + dy).toInt().coerceIn(0, (sh - h).coerceAtLeast(0))
        beforeIme = null
        update()
    }

    /** After a drag: snap to the nearer side and keep the place for next time. */
    internal fun settle() {
        val p = lp ?: return
        val ctx = appCtx ?: return
        val (sw, sh) = screen(ctx)
        val w = root?.width ?: 0
        val margin = (12 * ctx.resources.displayMetrics.density).toInt()
        val centerFromLeft = if (left) p.x + w / 2 else sw - p.x - w / 2
        left = centerFromLeft < sw / 2
        p.gravity = Gravity.TOP or if (left) Gravity.LEFT else Gravity.RIGHT
        p.x = margin
        update()
        if (Prefs.sideRemember) { Prefs.sideLeft = left; Prefs.sideTop = p.y.toFloat() / sh }
    }

    internal fun resizeBy(d: Float, land: Boolean) {
        val ctx = appCtx ?: return
        val (_, sh) = screen(ctx)
        picH = (picH + d).coerceIn(sh * 0.25f, maxPic(ctx, land))
        clampY()
    }

    internal fun maxPic(ctx: Context, land: Boolean): Float {
        val (_, sh) = screen(ctx)
        return sh - chrome(ctx, land) - 24 * ctx.resources.displayMetrics.density
    }

    /** Keeps the whole window on screen after it grows or the phone turns. */
    internal fun clampY() {
        val v = root ?: return
        v.post {
            val p = lp ?: return@post
            val (sw, sh) = screen(appCtx ?: return@post)
            val y = p.y.coerceIn(0, (sh - v.height).coerceAtLeast(0))
            val x = p.x.coerceIn(0, (sw - v.width).coerceAtLeast(0))
            if (y != p.y || x != p.x) { p.y = y; p.x = x; update() }
        }
    }

    internal fun saveSize(land: Boolean) {
        val ctx = appCtx ?: return
        if (Prefs.sideRemember && picH > 0) Prefs.setSideHeight(land, picH / screen(ctx).second)
    }

    private fun onIme(visible: Boolean, bottom: Int) {
        val p = lp ?: return
        if (visible && bottom > 0 && beforeIme == null) {
            beforeIme = p.y
            p.y = (p.y - bottom).coerceAtLeast(0)
            update()
        } else if (!visible && beforeIme != null) {
            p.y = beforeIme!!
            beforeIme = null
            update()
        }
    }

    private fun update() {
        val v = root ?: return
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    /** Saves what the window shows (only the app inside, not your screen) to Saved. */
    internal fun snapshot(ctx: Context) {
        val bmp = texture?.bitmap ?: return
        val e = entry() ?: return
        Bg.scope.launch {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bmp.recycle()
            val path = Images.saveBytes(ctx, out.toByteArray(), 1080)
            if (path != null) Repo.addSaved(null, "", "", e.app, "${e.app} screenshot", path)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, if (path != null) "Saved to Saved · ${e.app}" else "Couldn't save the picture", Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun screen(ctx: Context): Pair<Int, Int> {
        val w = ctx.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) w.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else android.graphics.Point().also { @Suppress("DEPRECATION") w.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
    }
}

@Composable
private fun SideContent() {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val e = SideWindow.entry() ?: return
    val drag = rawDrag(onEnd = { SideWindow.settle() }) { dx, dy -> SideWindow.moveBy(dx, dy) }

    if (SideWindow.minimized) {
        // Bubble on the edge: tap to bring the window back (the app stayed open)
        val v by EdgeLight.version.collectAsState()
        val n = remember(v) { SideWindow.tabs.sumOf { EdgeLight.entriesFor(it.pkg).size } }
        val bubbleDrag = rawDrag(onTap = { SideWindow.restore() }, onEnd = { SideWindow.settle() }) { dx, dy -> SideWindow.moveBy(dx, dy) }
        Box(Modifier.padding(6.dp).then(bubbleDrag)) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Color.Black).border(2.dp, p.accent, CircleShape),
                contentAlignment = Alignment.Center
            ) { AppIcon(e.pkg, 34.dp) }
            if (n > 0) Text(
                "$n", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(50)).background(Color(0xFFFF3B30)).padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        return
    }

    val dens = LocalDensity.current
    val ratio = remember { LiveSession.phoneRatio(ctx) }
    val (sw, sh) = remember(land) { SideWindow.screen(ctx) }
    // Default size: a share of the screen width when upright, of the height when sideways
    val defH = remember(land, Prefs.sideSize) {
        val i = Prefs.sideSize.coerceIn(0, 2)
        if (land) sh * floatArrayOf(0.70f, 0.84f, 0.92f)[i]
        else (sw * floatArrayOf(0.40f, 0.50f, 0.62f)[i] / ratio).coerceAtMost(sh * 0.72f)
    }
    val cap = remember(land) { SideWindow.maxPic(ctx, land) }
    // Turning the phone: use the size kept for this way round (or the default), and stay on screen
    LaunchedEffect(land) {
        SideWindow.picH = (if (Prefs.sideRemember) Prefs.sideHeight(land).takeIf { it > 0 }?.let { it * sh } else null) ?: defH
        SideWindow.clampY()
    }
    val hPx = (if (SideWindow.picH > 0f) SideWindow.picH else defH).coerceAtMost(cap)
    val picHeight: Dp = with(dens) { hPx.toDp() }
    val picWidth: Dp = picHeight * ratio
    var adding by remember { mutableStateOf(false) }

    val card = Modifier.padding(6.dp).clip(RoundedCornerShape(22.dp)).background(p.card)
        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp)).padding(6.dp)
    // The grip sits on the inner corner; dragging it away from the edge or down makes the window bigger
    val grip = rawDrag(onEnd = { SideWindow.saveSize(land) }) { dx, dy ->
        val out = if (SideWindow.isLeft) dx else -dx
        SideWindow.resizeBy(if (land) dy + out / ratio else dy + out / ratio, land)
    }

    if (!land) {
        Column(card.width(IntrinsicSize.Min), horizontalAlignment = Alignment.CenterHorizontally) {
            Handle(drag)
            Tabs(ctx, adding, { adding = !adding })
            if (adding) AddApps(ctx) { adding = false }
            Box(Modifier.padding(top = 4.dp).size(picWidth, picHeight)) { Picture(e) }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (!SideWindow.isLeft) Grip(grip)
                Buttons(ctx, e)
                if (SideWindow.isLeft) Grip(grip)
            }
            if (SideWindow.replying) Box(Modifier.width(maxOf(picWidth, 260.dp))) { ReplyBar(e, compact = true) { SideWindow.replying = false; SideWindow.setFocusable(false) } }
        }
    } else {
        Column(card) {
            Handle(drag)
            Row(Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(picWidth, picHeight)) { Picture(e) }
                Column(
                    Modifier.padding(start = 6.dp).height(picHeight).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
                ) {
                    SideWindow.tabs.forEachIndexed { i, t -> Tab(t, i == SideWindow.current) { SideWindow.select(ctx, i) } }
                    Round("–") { SideWindow.minimized = true }
                    CloseRound()
                    Buttons(ctx, e)
                    Grip(grip)
                }
            }
            if (SideWindow.replying) Box(Modifier.width(picWidth + 44.dp)) { ReplyBar(e, compact = true) { SideWindow.replying = false; SideWindow.setFocusable(false) } }
        }
    }
}

/** Drag with screen coordinates: the window moves under the finger, so the finger's position inside it can't be used. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun rawDrag(onTap: (() -> Unit)? = null, onEnd: () -> Unit, onMove: (Float, Float) -> Unit): Modifier {
    // last x, last y, distance moved
    val last = remember { floatArrayOf(0f, 0f, 0f) }
    return Modifier.pointerInteropFilter { ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { last[0] = ev.rawX; last[1] = ev.rawY; last[2] = 0f }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - last[0]; val dy = ev.rawY - last[1]
                last[2] += kotlin.math.abs(dx) + kotlin.math.abs(dy)
                onMove(dx, dy); last[0] = ev.rawX; last[1] = ev.rawY
            }
            MotionEvent.ACTION_UP -> if (onTap != null && last[2] < 24f) onTap() else onEnd()
            MotionEvent.ACTION_CANCEL -> onEnd()
        }
        true
    }
}

@Composable
private fun Handle(drag: Modifier) {
    Box(Modifier.fillMaxWidth().height(14.dp).then(drag), contentAlignment = Alignment.Center) {
        Box(Modifier.width(30.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.35f)))
    }
}

@Composable
private fun Tabs(ctx: Context, adding: Boolean, onAdd: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        SideWindow.tabs.forEachIndexed { i, t -> Tab(t, i == SideWindow.current) { SideWindow.select(ctx, i) } }
        if (SideWindow.tabs.size < 3) Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(if (adding) p.accent else p.field).clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) { Text("+", fontSize = 15.sp, color = if (adding) Color.White else p.sub) }
        Spacer(Modifier.weight(1f))
        Round("–") { SideWindow.minimized = true }
        CloseRound()
    }
}

@Composable
private fun Tab(t: EdgeLight.Entry, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)
            .border(if (on) 2.dp else 1.dp, if (on) p.accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { AppIcon(t.pkg, 18.dp) }
}

/** Other apps with notifications right now: tap one to open it as another tab. */
@Composable
private fun AddApps(ctx: Context, onDone: () -> Unit) {
    val p = LocalPalette.current
    val v by EdgeLight.version.collectAsState()
    val apps = remember(v) {
        EdgeLight.activeApps().filter { a -> SideWindow.tabs.none { it.pkg == a } }.mapNotNull { EdgeLight.entriesFor(it).firstOrNull() }
    }
    Column(Modifier.fillMaxWidth().padding(top = 5.dp).clip(RoundedCornerShape(12.dp)).background(p.field).padding(6.dp)) {
        if (apps.isEmpty()) Text("No other apps with messages", fontSize = 12.sp, color = p.sub)
        apps.take(5).forEach { a ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { SideWindow.open(ctx, a, "right"); onDone() }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(a.pkg, 20.dp)
                Text(a.app, fontSize = 12.sp, color = p.text, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** Touch size of the round buttons; the narrow window has no room for the full 48dp. */
private val TOUCH = 32.dp

@Composable
private fun Round(label: String, primary: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    @OptIn(ExperimentalFoundationApi::class)
    // The circle stays small, but the touch area around it is bigger so a thumb doesn't miss it.
    Box(
        Modifier.size(TOUCH).clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLong),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (primary) p.accent else p.field), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, color = if (primary) Color.White else p.text)
        }
    }
}

@Composable
private fun CloseRound() {
    val p = LocalPalette.current
    Box(Modifier.size(TOUCH).clip(CircleShape).clickable { SideWindow.close() }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(p.field), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "Close", tint = p.sub, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun Buttons(ctx: Context, e: EdgeLight.Entry) {
    Round("‹") { SideWindow.session.back() }
    if (LiveActions.canReply(e.key)) Round("↩", primary = true) {
        SideWindow.replying = !SideWindow.replying
        SideWindow.setFocusable(SideWindow.replying)
    }
    Round("📷") { SideWindow.snapshot(ctx) }
    // Full screen; hold for a pop-up window
    Round("⤢", onLong = { SideWindow.close(); EdgeLight.openInPopup(ctx, e.key, e.pkg) }) {
        SideWindow.close(); EdgeLight.open(ctx, e.key, e.pkg)
    }
}

@Composable
private fun Grip(m: Modifier) {
    Box(Modifier.size(22.dp).then(m), contentAlignment = Alignment.Center) {
        Text("⌟", fontSize = 16.sp, color = Color.White.copy(alpha = 0.5f))
    }
}

@Composable
private fun Picture(e: EdgeLight.Entry) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    LaunchedEffect(SideWindow.loaded) {
        if (!SideWindow.loaded) { kotlinx.coroutines.delay(12_000); if (!SideWindow.loaded) SideWindow.failed = true }
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
        AndroidView(
            factory = { c ->
                TextureView(c).apply {
                    SideWindow.texture = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val now = SideWindow.entry() ?: return
                            SideWindow.session.start(c, st, w, h, now) { ok -> if (!ok) SideWindow.failed = true }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = SideWindow.session.resize(c, w, h)
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            SideWindow.session.stop()
                            if (SideWindow.texture === this@apply) SideWindow.texture = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) { if (!SideWindow.loaded) SideWindow.loaded = true }
                    }
                    setOnTouchListener { _, ev -> SideWindow.session.touch(ev); true }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        AnimatedVisibility(!SideWindow.loaded || SideWindow.failed, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(p.field).padding(10.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (SideWindow.failed) {
                        Text("${e.app} couldn't open here", fontSize = 12.sp, color = p.text)
                        Spacer(Modifier.height(8.dp))
                        Small("Open full screen", primary = true) { SideWindow.close(); EdgeLight.open(ctx, e.key, e.pkg) }
                    } else {
                        CircularProgressIndicator(Modifier.size(24.dp), color = p.accent, strokeWidth = 3.dp)
                        Text("Opening ${e.app}…", fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * One hidden screen: made when the window's picture is ready, removed when the picture goes (closing, shrinking to a
 * bubble, turning the phone). The picture is phone-shaped and the screen is the picture's size, so touches map 1:1;
 * the density is lowered when the picture is small, so the app still gets a phone-sized layout, just drawn smaller.
 */
internal class LiveSession {
    @Volatile private var display = -1
    @Volatile private var closed = false
    @Volatile private var svc: IPanelService? = null
    /** Bumped every time the picture goes, so a hidden screen made for an old picture is dropped. */
    @Volatile private var gen = 0
    private var surface: Surface? = null

    fun reset() { closed = false; display = -1; surface = null }

    fun start(ctx: Context, st: SurfaceTexture, w: Int, h: Int, e: EdgeLight.Entry, done: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val my = gen
        Shizu.withService { s ->
            if (s == null) { done(false); return@withService }
            if (closed || my != gen) return@withService
            svc = s
            val sf = Surface(st).also { surface = it }
            val dpi = dpiFor(ctx, w)
            val pi = LiveActions.get(e.key)?.content
            val comp = component(ctx, e)
            Bg.scope.launch {
                val ok = try {
                    val id = s.create(sf, w, h, dpi)
                    when {
                        id < 0 -> false
                        closed || my != gen -> { s.release(id); true }
                        else -> { display = id; s.launch(id, pi, comp, e.user) }
                    }
                } catch (_: Exception) { false }
                main.post { done(ok) }
            }
        }
    }

    /** Brings another app (a tab) onto the same hidden screen. */
    fun show(ctx: Context, e: EdgeLight.Entry) {
        val s = svc ?: return
        val id = display.takeIf { it >= 0 } ?: return
        val pi = LiveActions.get(e.key)?.content
        val comp = component(ctx, e)
        Bg.scope.launch { try { s.launch(id, pi, comp, e.user) } catch (_: Exception) {} }
    }

    private fun component(ctx: Context, e: EdgeLight.Entry) =
        ctx.packageManager.getLaunchIntentForPackage(e.pkg)?.component?.flattenToShortString()

    private val main = Handler(Looper.getMainLooper())
    private var pendingResize: Runnable? = null
    private val resizeLock = Any()

    /**
     * The picture changed size (resizing the window): resize the hidden screen once the finger stops. Sending one
     * resize per move ran them in parallel, an older size could land last, and the app stayed drawn at that size.
     */
    fun resize(ctx: Context, w: Int, h: Int) {
        pendingResize?.let { main.removeCallbacks(it) }
        val r = Runnable {
            pendingResize = null
            val s = svc ?: return@Runnable
            val id = display.takeIf { it >= 0 } ?: return@Runnable
            val dpi = dpiFor(ctx, w)
            Bg.scope.launch { synchronized(resizeLock) { try { s.resize(id, w, h, dpi) } catch (_: Exception) {} } }
        }
        pendingResize = r
        main.postDelayed(r, 250)
    }

    fun touch(ev: MotionEvent) {
        val s = svc ?: return
        val id = display.takeIf { it >= 0 } ?: return
        // Rebuild the touch in the picture's own coordinates (the hidden screen is the same size as the picture)
        val n = ev.pointerCount
        val props = Array(n) { i -> MotionEvent.PointerProperties().also { ev.getPointerProperties(i, it) } }
        val coords = Array(n) { i -> MotionEvent.PointerCoords().also { ev.getPointerCoords(i, it) } }
        val copy = MotionEvent.obtain(
            ev.downTime, ev.eventTime, ev.action, n, props, coords, ev.metaState, ev.buttonState,
            1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try { s.touch(id, copy) } catch (_: Exception) {}
        copy.recycle()
    }

    fun back() {
        val s = svc ?: return
        val id = display.takeIf { it >= 0 } ?: return
        try { s.key(id, KeyEvent.KEYCODE_BACK) } catch (_: Exception) {}
    }

    /** The picture went: drop its screen (the app moves behind your main screen, still open); a new picture starts again. */
    fun stop() {
        gen++
        val s = svc
        val id = display
        display = -1
        if (s != null && id >= 0) Bg.scope.launch { try { s.release(id) } catch (_: Exception) {} }
        surface?.release()
        surface = null
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
    }

    companion object {
        const val MIN_WIDTH_DP = 340

        /** Width / height of the phone held upright. */
        fun phoneRatio(ctx: Context): Float {
            val (w, h) = phoneSize(ctx)
            return w.toFloat() / h
        }

        private fun phoneSize(ctx: Context): Pair<Int, Int> {
            val (a, b) = SideWindow.screen(ctx)
            return minOf(a, b) to maxOf(a, b)
        }

        fun dpiFor(ctx: Context, w: Int): Int = minOf(ctx.resources.displayMetrics.densityDpi, w * 160 / MIN_WIDTH_DP).coerceAtLeast(120)
    }
}
