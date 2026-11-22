package com.anas.secretary.util

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager

/**
 * Smooth motion: asks for the screen's fastest refresh rate (120 Hz on phones that have it, else 60 Hz).
 * The phone's own setting still wins: with Motion smoothness set to Standard, Android keeps 60 Hz.
 * The edge glows don't ask for it: they are on screen for a long time and would keep the screen fast.
 */
object Smooth {
    private fun display(ctx: Context): Display? =
        ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    /** The fastest mode at the current resolution (changing resolution would make the screen blink). */
    private fun best(ctx: Context): Display.Mode? = try {
        val d = display(ctx) ?: return null
        val cur = d.mode
        d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }
    } catch (_: Exception) { null }

    /** For an app screen. */
    fun apply(a: Activity) {
        val m = best(a) ?: return
        try { a.window.attributes = a.window.attributes.apply { preferredDisplayModeId = m.modeId } } catch (_: Exception) {}
    }

    /** For an overlay window (the edge panel and the side window). */
    fun apply(ctx: Context, lp: WindowManager.LayoutParams) {
        val m = best(ctx) ?: return
        lp.preferredRefreshRate = m.refreshRate
    }
}
