package com.anas.secretary.edge

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface

/**
 * The part of "apps inside the panel" that runs as the shell user (through Shizuku), because only
 * the shell or the system may put another app on a hidden screen and send it touches.
 * Never call this from the app's own process: it would be refused.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "WrongConstant")
class PanelShell(base: Context) {
    /** The shell's package name, so the system accepts our requests as coming from the shell. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL
        override fun getOpPackageName() = SHELL
        override fun getApplicationContext() = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL).build()
    }

    private val ctx: Context = ShellContext(base)
    private val displays = HashMap<Int, VirtualDisplay>()

    /** Makes a hidden screen that draws into [surface]. Returns its display id, or -1. */
    fun create(surface: Surface, w: Int, h: Int, dpi: Int, destroy: Boolean = false, ownFocus: Boolean = false): Int {
        // No OWN_FOCUS: with it the hidden screen never got focus, so the keyboard kept typing into the main-screen app.
        // Without it a tap inside makes it the focused screen and typing reaches the app there (tested on the emulator).
        // No ROTATES_WITH_CONTENT: with it, an upright-only app turned the screen and showed up sideways in the panel
        var flags = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH
        if (destroy) flags = flags or DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= 33) flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        if (Build.VERSION.SDK_INT >= 34) flags = flags or DEVICE_DISPLAY_GROUP or (if (ownFocus) OWN_FOCUS else 0)
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).newInstance(ctx)
        val vd = dm.createVirtualDisplay("Daybrief panel", w, h, dpi, surface, flags) ?: return -1
        val id = vd.display.displayId
        displays[id] = vd
        return id
    }

    /**
     * Opens [component] (pkg/activity) for [user] on the hidden screen, then the notification's own target ([pi],
     * usually the right chat). The app goes first because it's often still running behind the main screen:
     * this pulls it over, so the notification opens inside it here instead of full screen.
     * [user] is 0 for the normal app, or the second user of a dual app (usually 95).
     */
    fun launch(display: Int, pi: PendingIntent?, component: String?, user: Int = 0): Boolean {
        var ok = component != null &&
            run("am", "start", "--display", "$display", "--user", "$user", "-W", "-f", "0x10000000", "-n", component) == 0
        if (pi != null && (Build.VERSION.SDK_INT < 31 || pi.isActivity)) {
            try {
                val o = ActivityOptions.makeBasic().setLaunchDisplayId(display)
                if (Build.VERSION.SDK_INT >= 34) o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, o.toBundle())
                ok = true
            } catch (e: Exception) {
                android.util.Log.w("SecretaryShell", "send failed", e)
            }
        }
        return ok || hasTask(display)
    }

    /** Whether any app is showing on [display] (read from the system's own task list). */
    private fun hasTask(display: Int): Boolean = try {
        val out = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities")).inputStream.bufferedReader().readText()
        // The display's own block: its header, then indented lines until the first blank line
        out.lineSequence().dropWhile { !it.startsWith("Display #$display ") }.drop(1)
            .takeWhile { it.isNotBlank() }.any { it.trimStart().startsWith("* Task{") }
    } catch (_: Exception) { false }

    fun touch(display: Int, ev: MotionEvent) {
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        inject(ev, display)
    }

    fun key(display: Int, code: Int) {
        val now = SystemClock.uptimeMillis()
        inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), display)
        inject(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), display)
    }

    fun resize(display: Int, w: Int, h: Int, dpi: Int) { displays[display]?.resize(w, h, dpi) }

    /**
     * Takes the picture away but keeps the hidden screen and its app. With no picture the screen counts as off,
     * so the app is paused (like a phone with the screen off) and opens again instantly with [attach].
     */
    fun detach(display: Int) { displays[display]?.setSurface(null) }

    /** Gives a kept screen a new picture. False if it's gone. */
    fun attach(display: Int, surface: Surface, w: Int, h: Int, dpi: Int): Boolean {
        val vd = displays[display] ?: return false
        vd.resize(w, h, dpi)
        vd.setSurface(surface)
        return true
    }

    fun release(display: Int) { displays.remove(display)?.release() }

    fun releaseAll() { displays.values.forEach { it.release() }; displays.clear() }

    private fun inject(ev: InputEvent, display: Int) {
        try {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(ev, display)
            val im = if (Build.VERSION.SDK_INT >= 34) {
                Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
            } else {
                android.hardware.input.InputManager::class.java.getMethod("getInstance").invoke(null)
            }
            // 0 = don't wait for the app (a slow app must not hold up the next touches)
            im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType).invoke(im, ev, 0)
        } catch (e: Exception) {
            android.util.Log.w("SecretaryShell", "inject failed", e)
        }
    }

    /** Test only: whether [display]'s block lists a task of [pkg]. */
    internal fun hasTaskOf(display: Int, pkg: String): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities")).inputStream.bufferedReader().readText()
            .lineSequence().dropWhile { !it.startsWith("Display #$display ") }.drop(1)
            .takeWhile { it.isNotBlank() }.any { it.trimStart().startsWith("* Task{") && pkg in it }
    } catch (_: Exception) { false }

    /** Test only: the state line of [pkg]'s top activity (RESUMED, PAUSED, STOPPED). */
    internal fun state(pkg: String): String = try {
        Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities")).inputStream.bufferedReader().readText()
            .lineSequence().map { it.trim() }.firstOrNull { it.startsWith("* Hist") && pkg in it }
            ?.let { h -> Regex("state=([A-Z_]+)").find(
                Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities")).inputStream.bufferedReader().readText()
                    .substringAfter(h).take(4000))?.groupValues?.get(1) } ?: "none"
    } catch (_: Exception) { "?" }

    internal fun run(vararg cmd: String): Int = try {
        Runtime.getRuntime().exec(cmd).waitFor()
    } catch (_: Exception) { -1 }

    companion object {
        private const val SHELL = "com.android.shell"
        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden, so the values are spelled out)
        private const val PUBLIC = 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        /**
         * Closes the app when the screen goes. Not used: without it the app moves behind whatever you're
         * using on the main screen (tested: it doesn't pop up), stays warm, and opens fast next time.
         */
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15

        /**
         * Test from a computer, as the shell (the same user Shizuku gives us):
         * adb shell CLASSPATH=<apk> app_process / com.anas.secretary.edge.PanelShell <pkg/activity>
         * Opens the app on a hidden screen, saves what it shows to /data/local/tmp/panel.png, taps, closes.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            android.os.Looper.prepareMainLooper()
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            val sys = at.getMethod("getSystemContext").invoke(thread) as Context
            val shell = PanelShell(sys)
            val reader = android.media.ImageReader.newInstance(720, 1280, android.graphics.PixelFormat.RGBA_8888, 2)
            val id = shell.create(reader.surface, 720, 1280, 320, destroy = args.getOrNull(5) != "move", ownFocus = args.getOrNull(6) == "focus")
            println("display=$id")
            println("hasTask before=" + shell.hasTask(id))
            println("launch=" + shell.launch(id, null, args.getOrNull(0)))
            println("hasTask after=" + shell.hasTask(id))
            Thread.sleep(args.getOrNull(4)?.toLong() ?: 12000)
            save(reader, "/data/local/tmp/panel.png")
            val x = args.getOrNull(1)?.toFloat() ?: 360f
            val y = args.getOrNull(2)?.toFloat() ?: 640f
            if (args.getOrNull(3) == "cmd") {
                println("input=" + shell.run("input", "-d", "$id", "tap", "$x", "$y"))
            } else {
                val t = SystemClock.uptimeMillis()
                val pp = arrayOf(MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
                fun ev(a: Int) = MotionEvent.obtain(t, SystemClock.uptimeMillis(), a, 1, pp,
                    arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                shell.touch(id, ev(MotionEvent.ACTION_DOWN))
                Thread.sleep(60)
                shell.touch(id, ev(MotionEvent.ACTION_UP))
            }
            Thread.sleep(6000)
            save(reader, "/data/local/tmp/panel2.png")
            if (args.getOrNull(5) == "ime") {
                // Did tapping a text box on the hidden screen bring up the keyboard for it?
                fun grep(cmd: Array<String>, vararg keys: String) = Runtime.getRuntime().exec(cmd).inputStream.bufferedReader().readText()
                    .lineSequence().filter { l -> keys.any { it in l } }.map { it.trim() }.take(8).joinToString(" | ")
                println(grep(arrayOf("dumpsys", "input_method"), "mInputShown", "mCurFocusedWindow=", "mImeWindowVis", "displayId"))
                println(grep(arrayOf("dumpsys", "window", "displays"), "mTopFocusedDisplayId", "mCurrentFocus"))
                shell.run("input", "text", "hello")
                Thread.sleep(1500)
                save(reader, "/data/local/tmp/panel3.png")
            }
            if (args.getOrNull(5) == "sleep") {
                shell.detach(id)
                Thread.sleep(3000)
                println("asleep: " + shell.state(args[0].substringBefore('/')))
                if (args.getOrNull(6) == "home") {
                    // Like tapping the icon on the home screen while it sleeps here
                    shell.run("am", "start", "-W", "-n", args[0])
                    Thread.sleep(2000)
                    println("after home launch: hidden screen has it=" + shell.hasTask(id) + ", main screen has it=" + shell.hasTaskOf(0, args[0].substringBefore('/')))
                }
                val t0 = SystemClock.uptimeMillis()
                shell.attach(id, reader.surface, 720, 1280, 320)
                var img: android.media.Image? = null
                while (img == null && SystemClock.uptimeMillis() - t0 < 10000) { Thread.sleep(20); img = reader.acquireLatestImage() }
                println("woke, first frame after ${SystemClock.uptimeMillis() - t0} ms: " + shell.state(args[0].substringBefore('/')))
                img?.close()
            }
            shell.release(id)
            println("released")
            if (args.getOrNull(5) == "move") {
                Thread.sleep(2500)
                val out = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities")).inputStream.bufferedReader().readText()
                println("top on main: " + out.lineSequence().firstOrNull { "topResumedActivity" in it }?.trim())
                println("app on main: " + shell.hasTaskOf(0, args[0].substringBefore('/')) + ", state " + shell.state(args[0].substringBefore('/')))
            }
        }

        private fun save(reader: android.media.ImageReader, path: String) {
            val img = reader.acquireLatestImage() ?: run { println("no frame"); return }
            val pl = img.planes[0]
            val bmp = android.graphics.Bitmap.createBitmap(pl.rowStride / pl.pixelStride, img.height, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(pl.buffer)
            img.close()
            java.io.FileOutputStream(path).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            println("saved $path")
        }
    }
}
