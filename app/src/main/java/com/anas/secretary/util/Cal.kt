package com.anas.secretary.util

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import com.anas.secretary.data.Repo
import com.anas.secretary.data.Task
import java.util.TimeZone

data class CalInfo(
    val id: Long, val name: String, val account: String, val accountType: String,
    val color: Int, val writable: Boolean
) {
    /** Where this calendar lives, in plain words. */
    val where: String
        get() = when {
            accountType.contains("google", true) -> "Google ($account), synced to Google"
            accountType.contains("osp", true) || accountType.contains("samsung", true) -> "Samsung account"
            accountType.equals(CalendarContract.ACCOUNT_TYPE_LOCAL, true) || accountType.contains("local", true) -> "On this phone only"
            else -> account
        }
}

data class CalEvent(
    val eventId: Long, val calId: Long, val title: String, val begin: Long, val end: Long,
    val allDay: Boolean, val color: Int, val calName: String
)

/** Link with the phone calendar (Samsung Calendar uses the same Android calendar storage). */
object Cal {
    fun canRead(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<CalInfo> {
        if (!canRead(ctx)) return emptyList()
        val out = mutableListOf<CalInfo>()
        try {
            ctx.contentResolver.query(
                Calendars.CONTENT_URI,
                arrayOf(
                    Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
                    Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(6) == 0) continue
                    out += CalInfo(
                        c.getLong(0), c.getString(1) ?: "Calendar", c.getString(2) ?: "", c.getString(3) ?: "",
                        c.getInt(4), c.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Best default for adding reminders: Samsung's own calendar, then the phone's, then anything writable. */
    fun defaultTarget(ctx: Context): CalInfo? {
        val w = calendars(ctx).filter { it.writable }
        return w.firstOrNull { it.accountType.contains("osp", true) || it.accountType.contains("samsung", true) }
            ?: w.firstOrNull { it.accountType.contains("local", true) }
            ?: w.firstOrNull()
    }

    /** Events (repeating ones included) between [from] and [to], without the ones Secretary itself added. */
    fun events(ctx: Context, from: Long, to: Long): List<CalEvent> {
        if (!Features.calendar) return emptyList() // the phone's calendar is off in Features
        if (!canRead(ctx)) return emptyList()
        val shown = Prefs.calShown
        val ours = Repo.taskEventIds()
        val out = mutableListOf<CalEvent>()
        try {
            val b = Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, from)
            ContentUris.appendId(b, to)
            ctx.contentResolver.query(
                b.build(),
                arrayOf(
                    Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.BEGIN, Instances.END,
                    Instances.ALL_DAY, Instances.DISPLAY_COLOR, Instances.CALENDAR_DISPLAY_NAME
                ),
                null, null, "${Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val eventId = c.getLong(0)
                    val calId = c.getLong(1)
                    if (eventId in ours) continue
                    if (shown.isNotEmpty() && calId !in shown) continue
                    out += CalEvent(
                        eventId, calId, c.getString(2)?.ifBlank { null } ?: "(No title)", c.getLong(3), c.getLong(4),
                        c.getInt(5) == 1, c.getInt(6), c.getString(7) ?: ""
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Creates or updates the calendar event for a task. Returns the event id (or null if none). */
    fun upsert(ctx: Context, t: Task): Long? {
        if (!Prefs.calSync || Prefs.calTarget < 0 || !canWrite(ctx)) return t.calEvent
        val due = t.due
        if (due == null) {
            t.calEvent?.let { delete(ctx, it) }
            return null
        }
        val cv = ContentValues().apply {
            put(Events.TITLE, (if (t.done) "\u2713 " else "") + t.title)
            put(Events.DESCRIPTION, (t.notes + "\n\nAdded by Daybrief").trim())
            put(Events.DTSTART, due)
            put(Events.DTEND, due + 30 * 60_000L)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        return try {
            val existing = t.calEvent
            if (existing != null &&
                ctx.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, existing), cv, null, null) > 0
            ) {
                existing
            } else {
                cv.put(Events.CALENDAR_ID, Prefs.calTarget)
                cv.put(Events.HAS_ALARM, 0) // Secretary already reminds you; no double alerts
                ctx.contentResolver.insert(Events.CONTENT_URI, cv)?.let { ContentUris.parseId(it) }
            }
        } catch (_: Exception) {
            t.calEvent
        }
    }

    fun delete(ctx: Context, eventId: Long) {
        if (!canWrite(ctx)) return
        try {
            ctx.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
        } catch (_: Exception) {}
    }

    /** Opens an event in Samsung Calendar. */
    fun open(ctx: Context, e: CalEvent) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, e.eventId))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }
}
