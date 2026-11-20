package com.anas.secretary.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * The live buttons of notifications that are still in your notification shade
 * (the same "Reply" / "Call back" buttons a smartwatch uses). Kept only in memory.
 */
object LiveActions {
    class Msg(val sender: String, val text: String, val time: Long, val me: Boolean)

    class Handle(
        val reply: Notification.Action?, val call: Notification.Action?, val smartReplies: List<String>,
        val content: android.app.PendingIntent?, val messages: List<Msg>
    )

    private val map = ConcurrentHashMap<String, Handle>()
    val version = MutableStateFlow(0)

    private val reCall = Regex("(?i)call|dial|اتصال|معاود|اتصل")

    fun capture(key: String, n: Notification, smartReplies: List<String>) {
        val all = (n.actions?.toList() ?: emptyList()) + Notification.WearableExtender(n).actions
        val reply = all.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val call = n.actions?.firstOrNull { a ->
            a.remoteInputs.isNullOrEmpty() && reCall.containsMatchIn(a.title?.toString().orEmpty())
        }
        // The recent messages of a chat (WhatsApp, Telegram… share them in the notification)
        val messages = try {
            val ms = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            ms?.messages?.map { m ->
                Msg(
                    m.person?.name?.toString() ?: ms.user.name?.toString() ?: "You",
                    m.text?.toString().orEmpty(), m.timestamp, m.person == null
                )
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        map[key] = Handle(reply, call, smartReplies, n.contentIntent, messages)
        version.update { it + 1 }
    }

    fun get(key: String?): Handle? = key?.let { map[it] }

    fun remove(key: String) {
        if (map.remove(key) != null) version.update { it + 1 }
    }

    fun canReply(key: String?) = key != null && map[key]?.reply != null
    fun canCall(key: String?) = key != null && map[key]?.call != null
    fun smartReplies(key: String?): List<String> = key?.let { map[it]?.smartReplies } ?: emptyList()

    /** Sends [text] through the original app (WhatsApp, Telegram…), exactly like replying from the notification. */
    fun reply(ctx: Context, key: String?, text: String): Boolean {
        val action = key?.let { map[it]?.reply } ?: return false
        val inputs = action.remoteInputs ?: return false
        return try {
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Presses the notification's own "Call back" button. */
    fun call(ctx: Context, key: String?): Boolean {
        val action = key?.let { map[it]?.call } ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                action.actionIntent.send(ctx, 0, null, null, null, null, opts.toBundle())
            } else {
                action.actionIntent.send()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
