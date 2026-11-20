package com.anas.secretary.edge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.anas.secretary.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

/** Shizuku: whether it's installed, running and allowed, and the connection to [PanelService]. */
object Shizu {
    const val PKG = "moe.shizuku.privileged.api"

    enum class State { NotInstalled, NotRunning, NotAllowed, Ready }

    /** Bumped when Shizuku starts, stops or answers a permission request, so screens refresh. */
    val version = MutableStateFlow(0)
    private var service: IPanelService? = null
    private val waiting = ArrayList<(IPanelService?) -> Unit>()
    private var binding = false

    fun init() {
        Shizuku.addBinderReceivedListenerSticky { version.value++ }
        Shizuku.addBinderDeadListener { service = null; binding = false; version.value++ }
        Shizuku.addRequestPermissionResultListener { _, _ -> version.value++ }
    }

    fun state(ctx: Context): State = when {
        !installed(ctx) -> State.NotInstalled
        !running() -> State.NotRunning
        !allowed() -> State.NotAllowed
        else -> State.Ready
    }

    fun installed(ctx: Context) = try { ctx.packageManager.getPackageInfo(PKG, 0); true } catch (_: Exception) { false }

    private fun running() = try { Shizuku.pingBinder() && !Shizuku.isPreV11() } catch (_: Exception) { false }

    private fun allowed() = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }

    /** Shows Shizuku's own "Allow Secretary?" dialog. */
    fun ask() {
        try { Shizuku.requestPermission(1) } catch (_: Exception) {}
    }

    fun openShizuku(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$PKG"))
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, PanelService::class.java.name))
            .daemon(false)
            .processNameSuffix("panel")
            .debuggable(BuildConfig.DEBUG)
            // A new app version restarts the service, so it never runs old code after an update
            .version(BuildConfig.VERSION_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { IPanelService.Stub.asInterface(it) }
            binding = false
            flush()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    /** Gives [then] the running service (starting it if needed), or null if Shizuku isn't ready. Called on the main thread. */
    fun withService(then: (IPanelService?) -> Unit) {
        service?.takeIf { it.asBinder().pingBinder() }?.let { then(it); return }
        service = null
        if (!running() || !allowed()) { then(null); return }
        waiting += then
        if (binding) return
        binding = true
        try {
            Shizuku.bindUserService(args, conn)
        } catch (_: Exception) {
            binding = false
            flush()
        }
    }

    private fun flush() {
        val list = waiting.toList()
        waiting.clear()
        list.forEach { it(service) }
    }
}
