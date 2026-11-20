package com.anas.secretary.edge

import android.content.Context
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
import androidx.compose.ui.platform.ComposeView
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
        }
        try {
            app.getSystemService(WindowManager::class.java).addView(frame, lp)
            root = frame
            owner = o
        } catch (_: Exception) {
            o.stop()
        }
    }

    fun close() {
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
    LaunchedEffect(list.isEmpty(), live) { if (list.isEmpty() && live == null) { delay(400); onClose() } }
    val ctx = LocalContext.current
    val openIn: (EdgeLight.Entry) -> Unit = {
        if (Shizu.state(ctx) == Shizu.State.Ready) { SideWindow.open(ctx, it, edge); onClose() } else live = it
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f * appear.value))
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
        Column(
            Modifier
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
                .clip(RoundedCornerShape(28.dp)).background(p.card)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(16.dp)
        ) {
            val l = live
            when {
                l != null -> LiveApp(l, edge, onBack = { live = null }, onClose = onClose)
                e == null -> Peek(pkg, list, onExpand = { expanded = it }, onOpen = openIn, onClose = onClose)
                else -> Conversation(e, onBack = { expanded = null }, onOpen = openIn, onClose = onClose)
            }
        }
    }
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
            Small("Hide", onClick = onHide)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.size(34.dp).clip(CircleShape).background(p.field).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "Close", tint = p.sub, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun Small(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (primary) Color.White else p.text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (primary) p.accent else p.field)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun Peek(pkg: String, list: List<EdgeLight.Entry>, onExpand: (EdgeLight.Entry) -> Unit, onOpen: (EdgeLight.Entry) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    // Hide = the glow goes dark, but the notifications stay in the shade
    val hide: (() -> Unit)? = if (Prefs.edgeHideButton) ({ EdgeLight.hideApp(pkg); onClose() }) else null
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
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (LiveActions.canReply(e.key)) Small("Reply", primary = true) { replying = !replying }
                    Small("Expand") { onExpand(e) }
                    Small("Pop-up window") { EdgeLight.openInPopup(ctx, e.key, e.pkg); onClose() }
                    Small("Open") { if (Prefs.edgeAppInPanel) onOpen(e) else { EdgeLight.open(ctx, e.key, e.pkg); onClose() } }
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
                        .background(if (m.me) (p.bubble) else p.field)
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
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Small("Back") { onBack() }
        Small("Pop-up window") { EdgeLight.openInPopup(ctx, e.key, e.pkg); onClose() }
        Small("Open ${e.app}") { if (Prefs.edgeAppInPanel) onOpen(e) else { EdgeLight.open(ctx, e.key, e.pkg); onClose() } }
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
        Box(Modifier.size(34.dp).clip(CircleShape).background(p.field).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
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
    Column(Modifier.padding(top = 14.dp).weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.field).padding(18.dp)) {
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
            "Not now · open full screen", fontSize = 13.sp, color = p.sub, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { EdgeLight.open(ctx, e.key, e.pkg); onClose() }.padding(top = 12.dp)
        )
    }
}
