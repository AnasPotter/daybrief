package com.anas.secretary.util

import android.content.Context
import android.os.Build
import com.anas.secretary.BuildConfig
import java.io.File

/**
 * Keeps the last crash on this phone so the person can choose to report it. Nothing is sent anywhere.
 * Only exception class names and code lines are kept: never the exception messages, which can hold
 * text from notifications, names or links.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                file(app).writeText("${System.currentTimeMillis()}\n" + trace(e))
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    /** When the last crash happened, or null when there's none waiting. */
    fun lastAt(ctx: Context): Long? = try {
        file(ctx).takeIf { it.exists() }?.useLines { it.firstOrNull() }?.toLongOrNull()
    } catch (_: Exception) { null }

    fun lastTrace(ctx: Context): String? = try {
        file(ctx).takeIf { it.exists() }?.readLines()?.drop(1)?.joinToString("\n")?.ifBlank { null }
    } catch (_: Exception) { null }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) {} }

    /** Class names and stack lines only, causes included, kept short enough for a link. */
    fun trace(e: Throwable, maxLines: Int = 40): String {
        val out = ArrayList<String>()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 4 && out.size < maxLines) {
            out += (if (depth == 0) "" else "Caused by: ") + cur.javaClass.name
            cur.stackTrace.take(if (depth == 0) 18 else 8).forEach { f ->
                if (out.size < maxLines) out += "  at ${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})"
            }
            cur = cur.cause?.takeIf { it !== cur }
            depth++
        }
        return out.joinToString("\n")
    }

    /** The text of a report: what the person wrote, versions, optional phone model, then the crash (if any). */
    fun report(note: String, withModel: Boolean, trace: String?): String = buildString {
        if (note.isNotBlank()) append(note.trim()).append("\n\n")
        append("Daybrief ").append(BuildConfig.VERSION_NAME).append(" · Android ").append(Build.VERSION.RELEASE)
        if (withModel) append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append('\n')
        if (trace != null) append("\n```\n").append(trace).append("\n```\n")
    }
}
