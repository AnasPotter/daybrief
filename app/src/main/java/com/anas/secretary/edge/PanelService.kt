package com.anas.secretary.edge

import android.app.PendingIntent
import android.content.Context
import android.view.MotionEvent
import android.view.Surface
import kotlin.system.exitProcess

/** The Shizuku user service: a separate process running as the shell, wrapping [PanelShell]. */
class PanelService(ctx: Context) : IPanelService.Stub() {
    private val shell = PanelShell(ctx)

    override fun destroy() {
        shell.releaseAll()
        exitProcess(0)
    }

    override fun create(surface: Surface, w: Int, h: Int, dpi: Int) = shell.create(surface, w, h, dpi)
    override fun launch(display: Int, pi: PendingIntent?, component: String?, user: Int) = shell.launch(display, pi, component, user)
    override fun touch(display: Int, ev: MotionEvent) = shell.touch(display, ev)
    override fun key(display: Int, code: Int) = shell.key(display, code)
    override fun release(display: Int) = shell.release(display)
    override fun resize(display: Int, w: Int, h: Int, dpi: Int) = shell.resize(display, w, h, dpi)
}
