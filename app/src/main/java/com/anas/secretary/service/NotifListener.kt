package com.anas.secretary.service

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.anas.secretary.data.Repo
import com.anas.secretary.edge.EdgeLight
import com.anas.secretary.util.Bg
import com.anas.secretary.util.Contacts
import kotlinx.coroutines.launch
import com.anas.secretary.logic.RuleEngine
import com.anas.secretary.util.Fmt

/**
 * Android hands every new notification to this service (once you grant
 * "Notification access"). It also hosts the screenshot watcher, because this
 * service stays alive in the background without needing its own notification.
 */
class NotifListener : NotificationListenerService() {
    companion object {
        @Volatile private var instance: NotifListener? = null

        /** Removes a notification from the phone's notification shade. */
        /** Removal reasons that mean the person dealt with it on the phone. Our own cancel, snoozes, timeouts and uninstalls don't count. */
        fun followsShade(reason: Int): Boolean = reason in setOf(
            REASON_CLICK, REASON_CANCEL, REASON_CANCEL_ALL, REASON_APP_CANCEL, REASON_APP_CANCEL_ALL, REASON_GROUP_SUMMARY_CANCELED
        )

        fun clear(key: String?) {
            if (key == null) return
            try { instance?.cancelNotification(key) } catch (_: Exception) {}
        }

        /** Rebuild glows after reconnecting or returning from overlay permission settings. */
        fun refreshEdgeLighting() {
            instance?.let { listener -> listener.handler.post { listener.restoreEdgeLighting() } }
        }
    }

    private var watcher: ScreenshotWatcher? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val callSync = Runnable { if (com.anas.secretary.util.Features.people) Bg.scope.launch { Contacts.syncCallLog(applicationContext) } }
    private val callObserver = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // A call just ended: update "last talked" a moment later
            handler.removeCallbacks(callSync)
            handler.postDelayed(callSync, 2000)
        }
    }
    private val skipPkgs = setOf("android", "com.android.systemui")
    /** First unlock of the morning shows the morning summary. */
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            try { com.anas.secretary.util.Daily.onUnlock(applicationContext) } catch (_: Exception) {}
        }
    }
    private var unlockRegistered = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        EdgeLight.init(applicationContext)
        try { contentResolver.registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, callObserver) } catch (_: Exception) {}
        handler.post(callSync)
        if (watcher == null) watcher = ScreenshotWatcher(applicationContext).also { it.start() }
        if (!unlockRegistered) try {
            registerReceiver(unlockReceiver, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT))
            unlockRegistered = true
        } catch (_: Exception) {}
        restoreEdgeLighting()
    }

    private fun restoreEdgeLighting() {
        try {
            val active = activeNotifications ?: return
            // Rebuild from this snapshot, including changes to eligibility while disconnected.
            EdgeLight.retainNotifications(emptySet())
            active.forEach { sbn ->
                try {
                    val pkg = sbn.packageName
                    val n = sbn.notification
                    LiveActions.capture(sbn.key, n, smartReplies(sbn))
                    if (pkg == packageName || pkg in skipPkgs || sbn.isOngoing ||
                        (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return@forEach
                    val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                    val body = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
                    if (title.isEmpty() && body.isEmpty()) return@forEach
                    val app = Fmt.appLabel(this, pkg)
                    EdgeLight.onPosted(sbn.key, pkg, app, title, body,
                        RuleEngine.suggest(pkg, app, title, body), redraw = false, time = sbn.postTime)
                } catch (e: Exception) {
                    Log.w("Secretary", "Unable to restore an edge notification", e)
                }
            }
        } catch (e: Exception) {
            Log.w("Secretary", "Unable to restore edge lighting", e)
        } finally {
            EdgeLight.refresh()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        LiveActions.remove(sbn.key)
        EdgeLight.onRemoved(sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        onNotificationRemoved(sbn)
        // Handled on the phone (swiped, Clear all, opened, or answered so the app took it away): leave Brief too
        if (com.anas.secretary.util.Prefs.followShade == true && followsShade(reason)) {
            val key = sbn.key
            // Right here, on the listener's thread, so a quick re-post (handled on this same thread) always comes after it
            try { Repo.dismissByKey(key, System.currentTimeMillis(), reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL) } catch (_: Exception) {}
        }
    }

    /** WhatsApp/Telegram calls aren't in the phone's call log, so we notice them from their notifications. */
    private fun detectAppCall(pkg: String, n: Notification, title: String, body: String) {
        if (pkg !in setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger")) return
        val t = "$title $body".lowercase()
        // Only real call notifications, not chat messages that mention "call"
        val isCall = n.category == Notification.CATEGORY_CALL ||
            Regex("(voice|video) call|incoming call|ongoing call|calling…|مكالمة (صوتية|فيديو|واردة|جارية)").containsMatchIn(t)
        if (!isCall) return
        val missed = t.contains("missed") || t.contains("فائتة") || t.contains("لم يتم الرد")
        val now = System.currentTimeMillis()
        Bg.scope.launch {
            val person = Contacts.personByName(title) ?: return@launch
            if (missed) {
                Repo.addInteraction(person.id, "missed", 0, now)
            } else if (!Repo.recentInteraction(person.id, "wa_call", now - 30 * 60_000L)) {
                Repo.addInteraction(person.id, "wa_call", 0, now)
            }
        }
    }

    private fun smartReplies(sbn: StatusBarNotification): List<String> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        return try {
            val r = NotificationListenerService.Ranking()
            if (currentRanking.getRanking(sbn.key, r)) r.smartReplies.map { it.toString() } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    override fun onListenerDisconnected() {
        instance = null
        try { contentResolver.unregisterContentObserver(callObserver) } catch (_: Exception) {}
        watcher?.stop()
        watcher = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        if (unlockRegistered) { try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}; unlockRegistered = false }
        watcher?.stop()
        watcher = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w("Secretary", "Failed to process notification", e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in skipPkgs) return
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()

        if (com.anas.secretary.util.Features.people) detectAppCall(pkg, n, title, body)

        if (sbn.isOngoing) return // music players, downloads, navigation…
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (title.isEmpty() && body.isEmpty()) return
        LiveActions.capture(sbn.key, n, smartReplies(sbn))

        val now = System.currentTimeMillis()
        val app = Fmt.appLabel(this, pkg)
        val suggestion = RuleEngine.suggest(pkg, app, title, body)
        val call = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL
        EdgeLight.onPosted(sbn.key, pkg, app, title, body, suggestion, time = sbn.postTime, call = call)

        // Apps often re-post the same notification (after a restart, a reconnect…).
        // If we've seen this exact message in the last 3 days, don't ask about it again.
        if (!com.anas.secretary.util.Features.messages) return // Messages is off: nothing is kept
        if (Repo.isDuplicate(pkg, title, body, now - 3 * 86_400_000L)) return
        Repo.insertNotif(pkg, app, title, body, now, suggestion, sbn.key)

        // Keep only the last 7 days of notifications
        if (now % 20 == 0L) Repo.pruneNotifs(now - 7L * 24 * 3600_000)
    }
}

