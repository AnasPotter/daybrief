package com.anas.secretary.edge

import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import com.anas.secretary.logic.RuleEngine
import com.anas.secretary.logic.Suggestion
import com.anas.secretary.service.LiveActions
import com.anas.secretary.util.Prefs
import com.anas.secretary.util.Features
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Lights up the screen edge for notifications from the apps you choose. */
object EdgeLight {
    class Entry(
        val key: String, val pkg: String, val app: String, val title: String, val text: String,
        val time: Long, val needsReply: Boolean, val worthGlow: Boolean,
        /** Every notification merged into this row (same chat), newest first. */
        val keys: List<String> = listOf(key),
        /** A call or missed call: still glows in sleep mode. */
        val call: Boolean = false
    ) {
        val count get() = keys.size
        /** 0 = the normal app, another number = a second copy such as Samsung Dual Messenger (usually 95). */
        val user get() = userOf(key)
    }

    /** The Android user a notification belongs to: its key starts with it ("95|com.whatsapp|…" for a dual app). */
    fun userOf(key: String?): Int = key?.substringBefore('|')?.toIntOrNull() ?: 0

    /** "You have 8 new messages": a count some apps post next to the real messages. */
    private val countOnly = Regex("""(?i)^you have \d+ new (messages?|notifications?|chats?)\.?$""")

    private lateinit var app: Context
    private val wm get() = app.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val entries = LinkedHashMap<String, Entry>()
    private val glows = HashMap<String, GlowView>()
    /** What each glow was drawn with, so an unchanged glow isn't torn down and added again. */
    private val glowLooks = HashMap<String, String>()
    private var demo = false

    /** Bumped when the list of glowing notifications changes (the peek panel listens). */
    val version = MutableStateFlow(0)

    private var rotation = -1

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        rotation = currentRotation()
        // Turning the phone moves the edges: redraw the glows for the new screen shape
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(c: Configuration) {
                handler.postDelayed({
                    val r = currentRotation()
                    if (r != rotation) { rotation = r; refresh() }
                }, 250)
            }
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {}
        })
    }

    private fun display() = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    private fun currentRotation(): Int = try { display().rotation } catch (_: Exception) { 0 }

    fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun canUsage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Apps that have something to show right now, newest first. */
    fun activeApps(): List<String> = entries.values.sortedByDescending { it.time }.map { it.pkg }.distinct().filter { entriesFor(it).isNotEmpty() }

    fun entriesFor(pkg: String): List<Entry> {
        val list = entries.values
            .filter { it.pkg == pkg && (!Prefs.edgeSmart || it.worthGlow) && hidden[it.key] != it.text && !expired(it) }
            .sortedByDescending { it.time }
        // One row per chat: messages with the same sender/group title (and the same app copy) are merged, newest on top
        val merged = list.groupBy { if (it.title.isBlank()) it.key else "${it.user}|${it.title}" }.values.map { g ->
            val top = g.first()
            if (g.size == 1) top
            else Entry(top.key, top.pkg, top.app, top.title, top.text, top.time, g.any { it.needsReply }, top.worthGlow, g.map { it.key }, g.any { it.call })
        }.sortedByDescending { it.time }
        // Drop "You have N new messages" when the messages themselves are there
        return if (merged.size > 1) merged.filterNot { countOnly.matches(it.text) } else merged
    }

    // ---------- sleep mode ----------
    private var sleepNights: List<com.anas.secretary.logic.SleepNight> = emptyList()
    private var sleepNightsAt = 0L

    /** While you sleep (usual sleep hours from your watch, or the hours set in settings). */
    fun sleeping(now: Long = System.currentTimeMillis()): Boolean {
        if (!Prefs.sleepEdge || !::app.isInitialized) return false
        if (now - sleepNightsAt > 30 * 60_000L) {
            sleepNights = try { com.anas.secretary.util.Health.cached(app)?.nights.orEmpty() } catch (_: Exception) { emptyList() }
            sleepNightsAt = now
        }
        return com.anas.secretary.logic.DayPlan.inSleepHours(sleepNights, now, Prefs.sleepFrom, Prefs.sleepTo)
    }

    /** In sleep mode only calls and the chosen people glow. */
    private fun sleepAllows(e: Entry): Boolean {
        if (e.call || !sleeping()) return true
        val t = e.title.trim()
        // Whole words only, so "Ali" doesn't match "Alia"
        return t.isNotEmpty() && Prefs.sleepPeople.any { n ->
            Regex("(?iU)(^|[^\\p{L}\\p{N}])" + Regex.escape(n.trim()) + "($|[^\\p{L}\\p{N}])").containsMatchIn(t)
        }
    }

    /** Glows you hid: notification key → its text at that moment. A new message (new text) glows again. */
    private val hidden = HashMap<String, String>()

    /** "Hide unseen glows after" (Prefs.edgeAutoHide minutes, 0 = never). */
    private fun expired(e: Entry, now: Long = System.currentTimeMillis()): Boolean {
        val m = Prefs.edgeAutoHide
        return m > 0 && now - e.time >= m * 60_000L
    }

    /** Turns off this app's glow without clearing its notifications. */
    fun hideApp(pkg: String) {
        if (!::app.isInitialized) return
        entries.values.filter { it.pkg == pkg }.forEach { hidden[it.key] = it.text }
        refresh()
        Toast.makeText(app, "Hidden. New messages will glow again", Toast.LENGTH_SHORT).show()
    }

    fun onPosted(
        key: String, pkg: String, appName: String, title: String, text: String, s: Suggestion?,
        redraw: Boolean = true, time: Long = System.currentTimeMillis(), call: Boolean = false
    ) {
        if (!::app.isInitialized) return
        // Keep active notifications in memory even while settings/permission hide the glow.
        // Re-enabling it must not require the sender to post another message.
        entries[key] = Entry(key, pkg, appName, title, text, time,
            s?.type == RuleEngine.REPLY,
            // Smart glow hides only codes and promotions; an ordinary "hi" has no suggestion but still glows
            s?.type != RuleEngine.IGNORE, call = call)
        if (redraw) refresh()
    }

    /** Drop notifications removed while the listener was disconnected. */
    fun retainNotifications(activeKeys: Set<String>) {
        // Not `hidden`: the shade is re-read with an empty list first, and a hidden glow must stay hidden.
        // A hidden key is dropped when its notification is removed (onRemoved).
        entries.keys.retainAll(activeKeys)
    }

    /** Forget every notification and hidden glow (tests start from a clean state). */
    @androidx.annotation.VisibleForTesting
    internal fun reset() { entries.clear(); hidden.clear() }

    fun onRemoved(key: String) {
        hidden.remove(key)
        if (entries.remove(key) != null) refresh()
    }

    fun clear(key: String) {
        com.anas.secretary.service.NotifListener.clear(key)
        onRemoved(key)
    }

    /** Clears every notification merged into [e]. */
    fun clear(e: Entry) = e.keys.forEach { clear(it) }

    /**
     * Opens [pkg]'s main screen as the right user. A dual app is the same app installed for a second user,
     * so the normal launch would open the main copy instead.
     */
    fun launchApp(ctx: Context, pkg: String, user: Int, opts: android.os.Bundle? = null) {
        val me = Process.myUserHandle().hashCode()
        if (user != me) {
            try {
                val la = ctx.getSystemService(android.content.pm.LauncherApps::class.java)
                val uh = android.os.UserHandle.getUserHandleForUid(user * 100_000)
                val act = la.getActivityList(pkg, uh).firstOrNull()
                if (act != null) { la.startMainActivity(act.componentName, uh, null, opts); return }
            } catch (_: Exception) {}
        }
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts) }
    }

    /** Shows every configured glow for a few seconds, to see how they look. */
    fun demo() {
        if (!::app.isInitialized) return
        if (!canDraw(app)) {
            Toast.makeText(app, "Allow Show over other apps before testing edge lighting.", Toast.LENGTH_LONG).show()
            return
        }
        if (EdgeConfig.all().isEmpty()) {
            Toast.makeText(app, "Add an app under Apps that glow first.", Toast.LENGTH_LONG).show()
            return
        }
        demo = true
        refresh()
        handler.postDelayed({ demo = false; refresh() }, 6000)
    }

    private val sleepTick = Runnable { refresh() }

    /** True while Daybrief's own main screen is open: glows wait (it shows the same messages). */
    @Volatile var ownAppInFront = false

    /** Removes all glows (e.g. when edge lighting is turned off). */
    fun refresh() {
        if (!::app.isInitialized) return
        val resting = ownAppInFront && !demo
        val quiet = !demo && (resting || isQuiet())
        val configured = EdgeConfig.all()
        val lit: Set<String> = when {
            !canDraw(app) -> emptySet()
            (!Prefs.edgeOn || !Features.edge) && !demo -> emptySet()
            demo -> configured.map { it.pkg }.toSet()
            quiet -> emptySet()
            // Sleep mode only dims the glow; the panel still lists every message
            else -> configured.filter { c -> entriesFor(c.pkg).any { sleepAllows(it) } }.map { it.pkg }.toSet()
        }
        // remove old glows
        glows.keys.filter { it !in lit }.forEach { pkg -> glowLooks.remove(pkg); glows.remove(pkg)?.let { try { wm.removeView(it) } catch (_: Exception) {} } }
        // Redraw lit ones only when something about them changed (position, colour, animation, screen size…).
        // Each add/remove is a call into the system; doing it for every glow on every refresh could freeze the app.
        val cfgs = configured.filter { it.pkg in lit }
        val (sw, sh) = screen()
        layout(cfgs).forEach { (cfg, start, length) ->
            val look = "$cfg|$start|$length|${Prefs.edgeAnim}|${Prefs.edgeSwipeHide && !demo}|${sw}x$sh"
            if (glows.containsKey(cfg.pkg) && glowLooks[cfg.pkg] == look) return@forEach
            glows.remove(cfg.pkg)?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            addGlow(cfg, start, length)
            if (glows.containsKey(cfg.pkg)) glowLooks[cfg.pkg] = look
        }
        handler.removeCallbacks(poll)
        if (quiet && !resting && lit.isEmpty() && entries.isNotEmpty() && Prefs.edgeOn && Features.edge && canDraw(app)) {
            handler.postDelayed(poll, 3000)
        }
        // Sleep mode: look again every 5 minutes, so waiting glows come back when the sleep hours end
        handler.removeCallbacks(sleepTick)
        // (also before bedtime, so glows already lit go dark when the sleep hours start)
        if (Prefs.sleepEdge && Prefs.edgeOn && Features.edge && entries.isNotEmpty()) handler.postDelayed(sleepTick, 5 * 60_000L)
        // Auto-hide: redraw when the next glow runs out of time
        handler.removeCallbacks(expire)
        val m = Prefs.edgeAutoHide
        if (m > 0 && Prefs.edgeOn && Features.edge) {
            val now = System.currentTimeMillis()
            entries.values.map { it.time + m * 60_000L - now }.filter { it > 0 }.minOrNull()?.let { handler.postDelayed(expire, it + 500) }
        }
        version.update { it + 1 }
    }

    private val expire = Runnable { refresh() }

    private val poll = Runnable { refresh() }

    /** Apps placed on the same part of an edge share it: the spot is split between them. */
    private fun layout(cfgs: List<EdgeApp>): List<Triple<EdgeApp, Float, Float>> {
        val out = mutableListOf<Triple<EdgeApp, Float, Float>>()
        cfgs.groupBy { it.edge }.forEach { (_, list) ->
            val sorted = list.sortedBy { it.pos }
            var i = 0
            while (i < sorted.size) {
                val group = mutableListOf(sorted[i])
                var end = sorted[i].pos + sorted[i].len
                var j = i + 1
                while (j < sorted.size && sorted[j].pos < end) { group += sorted[j]; end = maxOf(end, sorted[j].pos + sorted[j].len); j++ }
                val start = group.first().pos
                val share = (end - start) / group.size
                group.forEachIndexed { k, c -> out += Triple(c, start + share * k, share) }
                i = j
            }
        }
        return out
    }

    /** The whole screen in its current rotation (the app's own window size can lag behind a rotation). */
    private fun screen(): Pair<Int, Int> = try {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        display().getRealMetrics(dm)
        dm.widthPixels to dm.heightPixels
    } catch (_: Exception) {
        val b = wm.maximumWindowMetrics.bounds
        b.width() to b.height()
    }

    private fun addGlow(cfg: EdgeApp, start: Float, length: Float) {
        val (sw, sh) = screen()
        val d = app.resources.displayMetrics.density
        val thick = cfg.thick * d
        val touch = maxOf(thick * 3.5f, 26 * d).toInt()
        val horizontal = cfg.edge == "top" || cfg.edge == "bottom"
        val total = if (horizontal) sw else sh
        val lenPx = (total * length).toInt().coerceAtLeast((24 * d).toInt())
        val startPx = (total * start).toInt().coerceIn(0, total - lenPx)
        val lp = WindowManager.LayoutParams(
            if (horizontal) lenPx else touch,
            if (horizontal) touch else lenPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Configured edges and x/y are physical screen coordinates, even in RTL.
            gravity = Gravity.TOP or Gravity.LEFT
            when (cfg.edge) {
                "left" -> { x = 0; y = startPx }
                "top" -> { x = startPx; y = 0 }
                "bottom" -> { x = startPx; y = sh - touch }
                else -> { x = sw - touch; y = startPx }
            }
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val v = GlowView(
            app, cfg.edge, cfg.color, thick, Prefs.edgeAnim,
            onHide = if (Prefs.edgeSwipeHide && !demo) ({ hideApp(cfg.pkg) }) else null
        ) { Panels.show(app, cfg.pkg, cfg.edge) }
        try { wm.addView(v, lp); glows[cfg.pkg] = v } catch (e: Exception) {
            Log.w("Secretary", "Unable to attach edge lighting overlay", e)
            if (demo) Toast.makeText(app, "Couldn't show edge lighting. Check Show over other apps permission.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- quiet while using ----------

    private fun foreground(): String? {
        if (!canUsage(app)) return null
        return try {
            val usm = app.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - 3 * 3_600_000L, now)
            val e = UsageEvents.Event()
            var last: String? = null
            val resumed = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED else 1
            while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == resumed) last = e.packageName }
            last
        } catch (_: Exception) { null }
    }

    private fun isQuiet(): Boolean {
        val q = Prefs.edgeQuiet
        if (q.isEmpty()) return false
        return foreground() in q
    }

    // ---------- open the app ----------

    /** Opens the conversation in the real app, inside a floating pop-up window (Samsung pop-up view). */
    fun openInPopup(ctx: Context, key: String?, pkg: String) {
        val (sw, sh) = screen()
        val w = (sw * 0.9f).toInt()
        val h = (sh * 0.62f).toInt()
        val left = (sw - w) / 2
        val top = (sh * 0.12f).toInt()
        val opts = ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, left + w, top + h))
        if (Build.VERSION.SDK_INT >= 34) {
            opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        val content = LiveActions.get(key)?.content
        try {
            if (content != null) {
                content.send(ctx, 0, Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null, null, null, opts.toBundle())
            } else {
                launchApp(ctx, pkg, userOf(key), opts.toBundle())
            }
        } catch (_: Exception) {}
    }

    /**
     * The one way Daybrief opens a conversation (Brief cards, edge panel when the side window isn't used):
     * a pop-up window when that's switched on, or when the Shizuku side window is on but Shizuku isn't ready;
     * otherwise full screen. Phones without pop-up support just open it normally.
     */
    fun openChat(ctx: Context, key: String?, pkg: String) {
        if (Prefs.openPopup || (Prefs.edgeAppInPanel && Shizu.state(ctx) != Shizu.State.Ready)) openInPopup(ctx, key, pkg) else open(ctx, key, pkg)
    }

    /** Opens the conversation in the app, full screen. */
    fun open(ctx: Context, key: String?, pkg: String) {
        val content = LiveActions.get(key)?.content
        try {
            if (content != null) {
                val opts = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= 34) {
                    opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                content.send(ctx, 0, null, null, null, null, opts.toBundle())
            } else {
                launchApp(ctx, pkg, userOf(key))
            }
        } catch (_: Exception) {}
    }
}

