package com.anas.secretary.edge

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.anas.secretary.logic.RuleEngine
import com.anas.secretary.service.LiveActions
import com.anas.secretary.ui.AppIcon
import com.anas.secretary.ui.LocalPalette
import com.anas.secretary.ui.SecretaryTheme
import com.anas.secretary.ui.oneUiFieldColors
import com.anas.secretary.util.Bg
import com.anas.secretary.util.Fmt
import com.anas.secretary.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lets Compose run inside a window that floats over other apps. */
internal class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}

internal class BackFrame(ctx: Context, private val onBack: () -> Unit) : FrameLayout(ctx) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * The glass look: a second, untouchable window right behind the panel card that blurs what is under it
 * (Window.setBackgroundBlurRadius only blurs inside a window's own bounds, and the panel window is full screen).
 * Added before the panel window so it stays underneath it.
 */
internal object PanelGlass {
    private var dialog: Dialog? = null
    private var radiusPx = 0
    /** The look in use while the blur window exists (null = solid card). */
    var current: Pair<Int, Float>? = null
        private set
    /** False while the phone has blur switched off (then the card is drawn solid). */
    val blurOn = mutableStateOf(true)
    private var listener: java.util.function.Consumer<Boolean>? = null
    private var wmRef: WindowManager? = null

    /** Android's own window blur (off on many Samsung phones, whatever the setting says). */
    private fun androidBlur(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && ctx.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled

    /** Samsung's blur (View.semSetBlurInfo, a public One UI API), used when Android's is off. */
    private val samsung: Boolean by lazy {
        try {
            val info = Class.forName("android.view.SemBlurInfo")
            View::class.java.getMethod("semSetBlurInfo", info)
            true
        } catch (_: Throwable) { false }
    }

    /** True when the panel can be blurred on this phone right now. */
    fun canBlur(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 31 && (androidBlur(ctx) || samsung)

    /** Blur radius in dp and how solid the card stays, per level; null when off or the phone can't blur. */
    fun look(ctx: Context): Pair<Int, Float>? {
        val level = Prefs.panelGlass
        if (level <= 0 || !canBlur(ctx)) return null
        return when (level) { 1 -> 16 to 0.8f; 2 -> 28 to 0.62f; else -> 44 to 0.45f }
    }

    /** Samsung blur on [v]: what is behind the window shows blurred inside the view, with round corners. */
    private fun semBlur(v: View, radius: Int, corner: Float): Boolean = try {
        val b = Class.forName("android.view.SemBlurInfo\$Builder")
        val builder = b.getConstructor(Int::class.javaPrimitiveType).newInstance(0) // BLUR_MODE_WINDOW
        b.getMethod("setRadius", Int::class.javaPrimitiveType).invoke(builder, radius)
        b.getMethod("setBackgroundColor", Int::class.javaPrimitiveType).invoke(builder, 0)
        b.getMethod("setBackgroundCornerRadius", Float::class.javaPrimitiveType).invoke(builder, corner)
        val info = b.getMethod("build").invoke(builder)
        View::class.java.getMethod("semSetBlurInfo", Class.forName("android.view.SemBlurInfo")).invoke(v, info)
        true
    } catch (_: Throwable) { false }

    /** True when Samsung's blur is drawn by a view inside the panel itself (no second window to move, so no lag). */
    var inPanel = false
        private set
    private var blurPx = 0

    /** Turns [v] (a view right behind the panel card) into Samsung's blur. */
    fun blurInside(v: View): Boolean = semBlur(v, blurPx, radiusPx.toFloat())

    fun create(ctx: Context) {
        hide()
        val lk = look(ctx) ?: return
        val blurDp = lk.first
        if (Build.VERSION.SDK_INT < 31) return
        val d = ctx.resources.displayMetrics.density
        radiusPx = (28 * d).toInt()
        blurPx = (blurDp * d).toInt()
        if (!androidBlur(ctx)) {
            // Samsung: the blur is a view under the card in the panel window, so it moves with the card from frame one
            inPanel = true
            current = lk
            return
        }
        try {
            val dlg = Dialog(ctx, com.anas.secretary.R.style.Theme_Secretary_Glass)
            val content = View(ctx)
            dlg.setContentView(content, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
            val w = dlg.window ?: return
            w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.addFlags(
                // not NOT_TOUCHABLE: Android caps untouchable overlays at 80% opacity, which thins the blur;
                // the panel window above takes every touch anyway
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            // The blur takes the shape of the window background, so this gives it the card's round corners
            w.setBackgroundDrawable(GradientDrawable().apply { cornerRadius = radiusPx.toFloat(); setColor(0x01000000) })
            w.setWindowAnimations(0)
            w.attributes = w.attributes.apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
                x = 0; y = 0; width = 1; height = 1; alpha = 0f
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            dlg.show()
            dialog = dlg
            if (androidBlur(ctx)) {
                // set once the window is attached, or the blur isn't applied
                w.setBackgroundBlurRadius((blurDp * d).toInt())
                // power saving can switch blur off while the panel is open: the card goes solid again
                val wm = ctx.getSystemService(WindowManager::class.java)
                val l = java.util.function.Consumer<Boolean> { blurOn.value = it }
                wm.addCrossWindowBlurEnabledListener(ctx.mainExecutor, l)
                listener = l
                wmRef = wm
            } else if (!semBlur(content, (blurDp * d).toInt(), radiusPx.toFloat())) {
                hide(); return
            }
            current = lk
        } catch (_: Exception) {
            hide()
        }
    }

    /** Moves the blur under the card (screen coordinates), fading with it while the panel opens. */
    fun place(left: Int, top: Int, width: Int, height: Int, fade: Float = 1f) {
        val w = dialog?.window ?: return
        if (width <= 0 || height <= 0) return
        try {
            val a = w.attributes
            if (a.x == left && a.y == top && a.width == width && a.height == height && a.alpha == fade) return
            w.attributes = a.apply { x = left; y = top; this.width = width; this.height = height; alpha = fade }
        } catch (_: Exception) {}
    }

    fun hide() {
        try { dialog?.dismiss() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 31) listener?.let { l -> try { wmRef?.removeCrossWindowBlurEnabledListener(l) } catch (_: Exception) {} }
        listener = null; wmRef = null
        dialog = null
        current = null
        inPanel = false
        blurOn.value = true
    }
}

/** The peek panel and the conversation pop-up, drawn over whatever app you're using. */
object Panels {
    private var root: View? = null
    private var owner: OverlayOwner? = null

    fun show(ctx: Context, pkg: String, edge: String) {
        close()
        // Start the Shizuku side now, so Open doesn't wait for it
        if (Prefs.edgeAppInPanel && Shizu.state(ctx) == Shizu.State.Ready) Shizu.withService {}
        val app = ctx.applicationContext
        val o = OverlayOwner().also { it.start() }
        val frame = BackFrame(app) { close() }
        frame.setViewTreeLifecycleOwner(o)
        frame.setViewTreeSavedStateRegistryOwner(o)
        frame.setViewTreeViewModelStoreOwner(o)
        val cv = ComposeView(app).apply {
            setContent { SecretaryTheme { PanelContent(pkg, edge) { close() } } }
        }
        frame.addView(cv)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            com.anas.secretary.util.Smooth.apply(app, this)
        }
        PanelGlass.create(app)
        try {
            app.getSystemService(WindowManager::class.java).addView(frame, lp)
            root = frame
            owner = o
        } catch (_: Exception) {
            o.stop()
            PanelGlass.hide()
        }
    }

    fun close() {
        PanelGlass.hide()
        root?.let { v ->
            try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
        }
        owner?.stop()
        root = null
        owner = null
    }
}

@Composable
private fun PanelContent(pkg: String, edge: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    val v by EdgeLight.version.collectAsState()
    val lv by LiveActions.version.collectAsState()
    val list = remember(v, lv) { EdgeLight.entriesFor(pkg) }
    var expanded by remember { mutableStateOf<EdgeLight.Entry?>(null) }
    // The app opened inside the panel (opening a chat usually clears its notification, so this keeps the panel open)
    var live by remember { mutableStateOf<EdgeLight.Entry?>(null) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 380f)) }
    val glass = remember { PanelGlass.current }
    val blurInPanel = remember { PanelGlass.inPanel }
    var blurFailed by remember { mutableStateOf(false) }
    var cardRect by remember { mutableStateOf<android.graphics.Rect?>(null) }
    // The blur follows the card from the first frame: same slide and fade as the card's opening animation
    LaunchedEffect(Unit) {
        snapshotFlow { cardRect to appear.value }.collect { (r, a) ->
            if (r == null) return@collect
            val off = ((1f - a) * 40f).toInt()
            val (dx, dy) = when (edge) { "left" -> -off to 0; "top" -> 0 to -off; "bottom" -> 0 to off; else -> off to 0 }
            PanelGlass.place(r.left + dx, r.top + dy, r.width(), r.height(), a.coerceIn(0f, 1f))
        }
    }
    val hostView = LocalView.current
    LaunchedEffect(list.isEmpty(), live) { if (list.isEmpty() && live == null) { delay(400); onClose() } }
    val ctx = LocalContext.current
    val openIn: (EdgeLight.Entry) -> Unit = {
        if (Shizu.state(ctx) == Shizu.State.Ready) { SideWindow.open(ctx, it, edge); onClose() } else live = it
    }

    Box(
        Modifier.fillMaxSize() // no dim: the app behind stays as it is
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
            .systemBarsPadding().imePadding().padding(14.dp),
        contentAlignment = if (expanded != null || live != null) Alignment.Center else when (edge) {
            "left" -> Alignment.CenterStart
            "top" -> Alignment.TopCenter
            "bottom" -> Alignment.BottomCenter
            else -> Alignment.CenterEnd
        }
    ) {
        val e = expanded ?: live
        Box(
            Modifier
                // measured outside the opening animation, so the blur gets the card's real place
                .onGloballyPositioned { c ->
                    if (glass == null) return@onGloballyPositioned
                    val b = c.boundsInWindow(); val loc = IntArray(2); hostView.getLocationOnScreen(loc)
                    cardRect = android.graphics.Rect(loc[0] + b.left.toInt(), loc[1] + b.top.toInt(), loc[0] + b.right.toInt(), loc[1] + b.bottom.toInt())
                }
                .graphicsLayer {
                    alpha = appear.value
                    val off = (1f - appear.value) * 40f
                    when (edge) {
                        "left" -> translationX = -off
                        "top" -> translationY = -off
                        "bottom" -> translationY = off
                        else -> translationX = off
                    }
                }
                .widthIn(max = if (e != null) 420.dp else 330.dp)
                .fillMaxWidth(if (e != null) 1f else 0.86f)
                .then(if (e != null) Modifier.fillMaxHeight(0.78f) else Modifier.heightIn(max = 520.dp))
                .clip(RoundedCornerShape(28.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
        ) {
            // Samsung: the blur itself, under the card's colour
            if (blurInPanel) AndroidView(factory = { c -> View(c).also { v -> if (!PanelGlass.blurInside(v)) blurFailed = true } }, modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(p.card.copy(alpha = if (glass != null && PanelGlass.blurOn.value && !blurFailed) glass.second else 1f)))
            CompositionLocalProvider(LocalPanelGlass provides (glass != null && PanelGlass.blurOn.value && !blurFailed)) {
            Column(Modifier.fillMaxWidth().then(if (e != null) Modifier.fillMaxHeight() else Modifier).padding(16.dp)) {
                val l = live
                when {
                    l != null -> LiveApp(l, edge, onBack = { live = null }, onClose = onClose)
                    e == null -> Peek(pkg, list, onExpand = { expanded = it }, onOpen = openIn, onClose = onClose)
                    else -> Conversation(e, onBack = { expanded = null }, onOpen = openIn, onClose = onClose)
                }
            }
            }
        }
    }
}

/** True inside the panel card while it is glass: buttons and bubbles become see-through too. */
private val LocalPanelGlass = staticCompositionLocalOf { false }

/** Button and bubble colour in the panel: frosted when the card is glass, else the usual field grey. */
@Composable
private fun panelField(): Color {
    val p = LocalPalette.current
    if (!LocalPanelGlass.current) return p.field
    val dark = p.bg.red + p.bg.green + p.bg.blue < 1.5f
    return if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f)
}

@Composable
private fun Header(pkg: String, title: String, sub: String?, onClose: () -> Unit, onHide: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(pkg, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = 12.sp, color = p.sub, maxLines = 1)
        }
        if (onHide != null) {
            Small("Hide glow", onClick = onHide)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.size(34.dp).clip(CircleShape).background(panelField()).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "Close", tint = p.sub, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun Small(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (primary) Color.White else p.text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (primary) p.accent else panelField())
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Peek(pkg: String, list: List<EdgeLight.Entry>, onExpand: (EdgeLight.Entry) -> Unit, onOpen: (EdgeLight.Entry) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    // Hide = the glow goes dark, but the notifications stay in the shade
    val hide: (() -> Unit)? = if (Prefs.edgeHideButton) ({
        EdgeLight.hideApp(pkg)
        android.widget.Toast.makeText(ctx, "Glow hidden. The notifications are still there.", android.widget.Toast.LENGTH_SHORT).show()
        onClose()
    }) else null
    Header(pkg, list.firstOrNull()?.app ?: "", if (list.size == 1) "1 notification" else "${list.size} notifications", onClose, hide)
    LazyColumn(Modifier.padding(top = 10.dp)) {
        items(list, key = { it.key }) { e ->
            var replying by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title.ifBlank { e.app }, fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (e.count > 1) Text("  ${e.count} messages", fontSize = 12.sp, color = p.sub)
                    if (e.needsReply) Text("  needs reply", fontSize = 12.sp, color = p.alert)
                    Spacer(Modifier.weight(1f))
                    Text(Fmt.short(e.time), fontSize = 12.sp, color = p.sub)
                }
                Text(e.text, fontSize = 14.sp, color = p.text, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (LiveActions.canReply(e.key)) Small("Reply", primary = true) { replying = !replying }
                    Small("Expand") { onExpand(e) }
                    Small("Pop-up window") { EdgeLight.openInPopup(ctx, e.key, e.pkg); onClose() }
                    Small("Open") { if (Prefs.edgeAppInPanel && Shizu.state(ctx) != Shizu.State.NotInstalled) onOpen(e) else { EdgeLight.openChat(ctx, e.key, e.pkg); onClose() } }
                    Small("Clear") { EdgeLight.clear(e) }
                }
                AnimatedVisibility(replying, enter = fadeIn() + slideInVertically { -it / 3 }) {
                    ReplyBar(e, compact = true) { replying = false }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
        }
    }
}

@Composable
internal fun ReplyBar(e: EdgeLight.Entry, compact: Boolean, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf<String?>(null) }
    val quick = remember(e.key) {
        (LiveActions.get(e.key)?.smartReplies.orEmpty() + RuleEngine.quickReplies(e.text, false)).distinct().take(4)
    }
    fun send(t: String) {
        val text = t.trim()
        if (text.isEmpty()) return
        if (LiveActions.reply(ctx, e.key, text)) {
            sent = text; draft = ""
            scope.launch { delay(900); onSent() }
        } else {
            Toast.makeText(ctx, "This message can't be answered from here anymore. Open the app to reply.", Toast.LENGTH_LONG).show()
        }
    }
    Column(Modifier.padding(top = if (compact) 8.dp else 0.dp)) {
        sent?.let { Text("Sent: $it", fontSize = 13.sp, color = p.success, modifier = Modifier.padding(bottom = 6.dp)) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            quick.forEach { q ->
                Text(
                    q, fontSize = 13.sp, color = p.onGood,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(p.good)
                        .clickable { send(q) }.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                draft, { draft = it }, placeholder = { Text("Reply to ${e.title.ifBlank { e.app }}") },
                shape = RoundedCornerShape(22.dp), colors = oneUiFieldColors(), maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(draft) }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(46.dp).clip(CircleShape).background(p.send).clickable { send(draft) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Conversation(e: EdgeLight.Entry, onBack: () -> Unit, onOpen: (EdgeLight.Entry) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val live by LiveActions.version.collectAsState()
    val msgs = remember(live) {
        LiveActions.get(e.key)?.messages?.takeIf { it.isNotEmpty() }
            ?: listOf(LiveActions.Msg(e.title, e.text, e.time, false))
    }
    val state = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.animateScrollToItem(msgs.size - 1) }

    Header(e.pkg, e.title.ifBlank { e.app }, e.app, onClose)
    LazyColumn(Modifier.weight(1f).padding(top = 12.dp), state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(msgs) { m ->
            Box(Modifier.fillMaxWidth(), contentAlignment = if (m.me) Alignment.CenterEnd else Alignment.CenterStart) {
                Column(
                    Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (m.me) (p.bubble) else panelField())
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (!m.me && m.sender != e.title) Text(m.sender, fontSize = 12.sp, color = p.accent)
                    Text(m.text, fontSize = 15.sp, color = if (m.me) p.onBubble else p.text)
                    Text(Fmt.short(m.time), fontSize = 10.sp, color = p.sub, modifier = Modifier.align(Alignment.End))
                }
            }
        }
    }
    if (LiveActions.canReply(e.key)) {
        Box(Modifier.padding(top = 10.dp)) { ReplyBar(e, compact = false) {} }
    }
    FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Small("Back") { onBack() }
        Small("Pop-up window") { EdgeLight.openInPopup(ctx, e.key, e.pkg); onClose() }
        Small("Open ${e.app}") { if (Prefs.edgeAppInPanel && Shizu.state(ctx) != Shizu.State.NotInstalled) onOpen(e) else { EdgeLight.openChat(ctx, e.key, e.pkg); onClose() } }
        Small("Clear") { EdgeLight.clear(e); onBack() }
    }
}

// ---------- the real app, inside the panel (through Shizuku) ----------

@Composable
private fun ColumnScope.LiveApp(e: EdgeLight.Entry, edge: String, onBack: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val sv by Shizu.version.collectAsState()
    var retry by remember { mutableIntStateOf(0) }
    val st = remember(sv, retry) { Shizu.state(ctx) }
    // Shizuku is set up in another app, so look again every couple of seconds while the setup card shows
    LaunchedEffect(st) { while (st != Shizu.State.Ready) { delay(1500); retry++ } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(panelField()).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Text("‹", fontSize = 22.sp, color = p.text, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) { Header(e.pkg, e.app, if (st == Shizu.State.Ready) "Live" else "Set up once", onClose) }
    }
    // Once Shizuku is ready, the app opens in the side window
    LaunchedEffect(st) { if (st == Shizu.State.Ready) { SideWindow.open(ctx, e, edge); onClose() } }
    if (st != Shizu.State.Ready) SetupInPanel(e, st, onClose)
}

/** Shown in the panel until Shizuku is installed, running and allowed. */
@Composable
private fun ColumnScope.SetupInPanel(e: EdgeLight.Entry, st: Shizu.State, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    Column(Modifier.padding(top = 14.dp).weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(panelField()).padding(18.dp)) {
        Text("Open apps inside the panel", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text)
        val steps = when (st) {
            Shizu.State.NotInstalled -> listOf("Install Shizuku from the Play Store (free, no root)", "Open it, pair Wireless debugging and tap Start", "Come back and tap Allow")
            Shizu.State.NotRunning -> listOf("Open Shizuku and tap Start (Wireless debugging)", "Come back here. After a restart, tap Start again")
            else -> listOf("Tap Allow so Daybrief can show ${e.app} here")
        }
        steps.forEachIndexed { i, t ->
            Row(Modifier.padding(top = 10.dp)) {
                Text("${i + 1}", fontSize = 13.sp, color = p.accent, fontWeight = FontWeight.Medium, modifier = Modifier.width(20.dp))
                Text(t, fontSize = 14.sp, color = p.text)
            }
        }
        Spacer(Modifier.weight(1f))
        val label = when (st) { Shizu.State.NotInstalled -> "Get Shizuku"; Shizu.State.NotRunning -> "Open Shizuku"; else -> "Allow" }
        Text(
            label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(p.accent)
                // Shizuku and its "Allow" dialog open as normal screens, which would sit under this panel, so close it
                .clickable { if (st == Shizu.State.NotAllowed) Shizu.ask() else Shizu.openShizuku(ctx); onClose() }.padding(vertical = 12.dp)
        )
        Text(
            "Not now · open in a pop-up window", fontSize = 13.sp, color = p.sub, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { EdgeLight.openInPopup(ctx, e.key, e.pkg); onClose() }.padding(top = 12.dp)
        )
    }
}
