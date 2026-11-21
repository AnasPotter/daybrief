package com.anas.secretary.util

import android.content.Context
import android.content.SharedPreferences
import com.anas.secretary.logic.DateExtractor

/** Small app settings stored on the phone. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        DateExtractor.dayFirst = dayFirst
    }

    var name: String
        get() = sp.getString("name", com.anas.secretary.BuildConfig.DEFAULT_NAME) ?: ""
        set(v) = sp.edit().putString("name", v).apply()

    var dayFirst: Boolean
        get() = sp.getBoolean("dayFirst", true)
        set(v) { sp.edit().putBoolean("dayFirst", v).apply(); DateExtractor.dayFirst = v }

    var screenshotPrompts: Boolean
        get() = sp.getBoolean("screenshotPrompts", true)
        set(v) = sp.edit().putBoolean("screenshotPrompts", v).apply()

    /** Which tracker a "One thing" widget shows (by its widget id; -1 = not picked). */
    fun thingFor(widgetId: Int): Long = sp.getLong("thing_$widgetId", -1L)
    fun setThingFor(widgetId: Int, trackerId: Long) = sp.edit().putLong("thing_$widgetId", trackerId).apply()

    /** How the Saved page shows items: "small" list, "grid" or "cards". */
    var savedSize: String
        get() = sp.getString("savedSize", "small") ?: "small"
        set(v) = sp.edit().putString("savedSize", v).apply()

    var askAboutSaved: Boolean
        get() = sp.getBoolean("askAboutSaved", true)
        set(v) = sp.edit().putBoolean("askAboutSaved", v).apply()

    /** Add Secretary reminders to the phone calendar. */
    var calSync: Boolean
        get() = sp.getBoolean("calSync", false)
        set(v) = sp.edit().putBoolean("calSync", v).apply()

    /** Calendar that reminders are added to (-1 = not chosen). */
    var calTarget: Long
        get() = sp.getLong("calTarget", -1L)
        set(v) = sp.edit().putLong("calTarget", v).apply()

    /** Calendars to show. Empty = all of them. */
    var calShown: Set<Long>
        get() = sp.getStringSet("calShown", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("calShown", v.map { it.toString() }.toSet()).apply()

    /** Remove a notification from the phone once you've handled it in Secretary. */
    var clearHandled: Boolean
        get() = sp.getBoolean("clearHandled", true)
        set(v) = sp.edit().putBoolean("clearHandled", v).apply()

    /** GitHub repository that builds the app (owner/name). */
    var updateRepo: String
        get() = if (com.anas.secretary.BuildConfig.PUBLIC) com.anas.secretary.BuildConfig.UPDATE_REPO
            else sp.getString("updateRepo", com.anas.secretary.BuildConfig.UPDATE_REPO) ?: ""
        set(v) = sp.edit().putString("updateRepo", v).apply()

    /** Encrypted GitHub access key (see Secret). */
    var tokenBlob: String
        get() = sp.getString("tokenBlob", "") ?: ""
        set(v) = sp.edit().putString("tokenBlob", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    /** Notifications from these apps count as work. */
    var workApps: Set<String>
        get() = sp.getStringSet("workApps", null) ?: setOf(
            "com.microsoft.teams", "com.microsoft.office.outlook", "com.Slack", "us.zoom.videomeetings",
            "com.google.android.apps.dynamite", "com.cisco.webex.meetings"
        )
        set(v) = sp.edit().putStringSet("workApps", v).apply()

    /** Call history already read up to this time. */
    var callLogSince: Long
        get() = sp.getLong("callLogSince", 0L)
        set(v) = sp.edit().putLong("callLogSince", v).apply()

    // ----- Feel, setup, search -----
    /** Light vibration on taps and swipes (also follows the phone's own Touch feedback setting). */
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    /** Samsung Health was connected directly (Samsung Health Data SDK) at least once. */
    var samsungDirect: Boolean
        get() = sp.getBoolean("samsungDirect", false)
        set(v) = sp.edit().putBoolean("samsungDirect", v).apply()

    // ----- Day features (morning summary, bedtime, light day, your week, sleep mode) -----
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun str(k: String) = sp.getString(k, "") ?: ""
    private fun setStr(k: String, v: String) = sp.edit().putString(k, v).apply()

    var morningOn: Boolean get() = b("morningOn", true); set(v) = setB("morningOn", v)
    /** Date (yyyy-mm-dd) the morning summary was last shown, so it comes once a day. */
    var morningDay: String get() = str("morningDay"); set(v) = setStr("morningDay", v)
    var bedtimeOn: Boolean get() = b("bedtimeOn", true); set(v) = setB("bedtimeOn", v)
    /** Date of the evening "Not tonight" was tapped. */
    var bedtimeSkip: String get() = str("bedtimeSkip"); set(v) = setStr("bedtimeSkip", v)
    var lightDayOn: Boolean get() = b("lightDayOn", true); set(v) = setB("lightDayOn", v)
    /** Date the light-day tile was answered (moved or "Not now"). */
    var lightDayDone: String get() = str("lightDayDone"); set(v) = setStr("lightDayDone", v)
    var weekOn: Boolean get() = b("weekOn", true); set(v) = setB("weekOn", v)
    /** Day of the week for "Your week": 1 = Monday ... 7 = Sunday. Default Friday. */
    var weekDay: Int get() = sp.getInt("weekDay", 5); set(v) = sp.edit().putInt("weekDay", v).apply()
    /** Average sleep (ms) of the week before the last review, for "less than last week". */
    var lastWeekSleep: Long get() = sp.getLong("lastWeekSleep", 0L); set(v) = sp.edit().putLong("lastWeekSleep", v).apply()
    var lastWeekDate: String get() = str("lastWeekDate"); set(v) = setStr("lastWeekDate", v)
    var prevWeekSleep: Long get() = sp.getLong("prevWeekSleep", 0L); set(v) = sp.edit().putLong("prevWeekSleep", v).apply()
    var prevWeekDate: String get() = str("prevWeekDate"); set(v) = setStr("prevWeekDate", v)
    var sleepEdge: Boolean get() = b("sleepEdge", false); set(v) = setB("sleepEdge", v)
    /** Names (chat titles) that still light up the edge while you sleep. */
    var sleepPeople: Set<String>
        get() = sp.getStringSet("sleepPeople", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("sleepPeople", v).apply()
    /** Sleep hours used when there aren't enough nights yet, minutes after midnight. */
    var sleepFrom: Int get() = sp.getInt("sleepFrom", 23 * 60 + 30); set(v) = sp.edit().putInt("sleepFrom", v).apply()
    var sleepTo: Int get() = sp.getInt("sleepTo", 7 * 60); set(v) = sp.edit().putInt("sleepTo", v).apply()

    /** A part of the app switched on or off (Features). Everything is on unless switched off. */
    fun feature(key: String): Boolean = sp.getBoolean(key, true)
    fun setFeature(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    fun has(key: String): Boolean = sp.contains(key)
    /** Support card in Brief: dismissed for good. */
    var supportDismissed: Boolean get() = sp.getBoolean("supportDismissed", false); set(v) = sp.edit().putBoolean("supportDismissed", v).apply()

    /** The first-run setup was finished or skipped. */
    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) = sp.edit().putBoolean("setupDone", v).apply()

    /** Last searches, newest first. */
    var recentSearches: List<String>
        get() = (sp.getString("recentSearches", "") ?: "").split('|').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("recentSearches", v.take(6).joinToString("|") { it.replace('|', ' ') }).apply()

    // ----- Edge lighting -----
    var edgeOn: Boolean
        get() = sp.getBoolean("edgeOn", false)
        set(v) = sp.edit().putBoolean("edgeOn", v).apply()

    /** JSON list of apps that glow (see EdgeConfig). */
    var edgeApps: String
        get() = sp.getString("edgeApps", "[]") ?: "[]"
        set(v) = sp.edit().putString("edgeApps", v).apply()

    var edgeAnim: String
        get() = sp.getString("edgeAnim", "breathe") ?: "breathe"
        set(v) = sp.edit().putString("edgeAnim", v).apply()

    /** Only glow for things that need you (no codes or promotions). */
    var edgeSmart: Boolean
        get() = sp.getBoolean("edgeSmart", true)
        set(v) = sp.edit().putBoolean("edgeSmart", v).apply()

    /** The day (epoch day) the morning sleep card was answered, so it stays away until tomorrow. */
    var sleepCardDay: Long
        get() = sp.getLong("sleepCardDay", 0L)
        set(v) = sp.edit().putLong("sleepCardDay", v).apply()

    /** Version of the suggestion rules last applied to stored notifications. */
    var rulesVersion: Int
        get() = sp.getInt("rulesVersion", 1)
        set(v) = sp.edit().putInt("rulesVersion", v).apply()

    /** Swipe a glow outward (or long-press it) to hide it. */
    var edgeSwipeHide: Boolean
        get() = sp.getBoolean("edgeSwipeHide", true)
        set(v) = sp.edit().putBoolean("edgeSwipeHide", v).apply()

    /** A Hide button at the top of the peek panel. */
    /** Open opens the app inside the edge panel (needs Shizuku). */
    var edgeAppInPanel: Boolean
        get() = sp.getBoolean("edgeAppInPanel", false) // optional: needs Shizuku
        set(v) = sp.edit().putBoolean("edgeAppInPanel", v).apply()

    /** Apps opened from Daybrief (Brief cards, edge panel) open in a floating pop-up window where the phone supports it. */
    var openPopup: Boolean
        get() = sp.getBoolean("openPopup", false)
        set(v) = sp.edit().putBoolean("openPopup", v).apply()

    /** Side window for apps opened from the edge panel: 0 Small, 1 Medium, 2 Large. */
    var sideSize: Int
        get() = sp.getInt("sideSize", 1)
        set(v) = sp.edit().putInt("sideSize", v).remove("sideHP").remove("sideHL").apply()

    /** "swiped" (the edge you swiped from), "left" or "right". */
    var sideEdge: String
        get() = sp.getString("sideEdge", "swiped") ?: "swiped"
        set(v) = sp.edit().putString("sideEdge", v).apply()

    var sideRemember: Boolean
        get() = sp.getBoolean("sideRemember", true)
        set(v) = sp.edit().putBoolean("sideRemember", v).apply()

    /** Where you last left the side window: picture height (share of screen height, upright and sideways), top and side. -1 = not moved. */
    fun sideHeight(land: Boolean) = sp.getFloat(if (land) "sideHL" else "sideHP", -1f)
    fun setSideHeight(land: Boolean, v: Float) = sp.edit().putFloat(if (land) "sideHL" else "sideHP", v).apply()
    var sideTop: Float
        get() = sp.getFloat("sideTop", -1f)
        set(v) = sp.edit().putFloat("sideTop", v).apply()
    var sideLeft: Boolean?
        get() = if (sp.contains("sideLeft")) sp.getBoolean("sideLeft", false) else null
        set(v) = sp.edit().apply { if (v == null) remove("sideLeft") else putBoolean("sideLeft", v) }.apply()

    var edgeHideButton: Boolean
        get() = sp.getBoolean("edgeHideButton", true)
        set(v) = sp.edit().putBoolean("edgeHideButton", v).apply()

    /** Unseen glows turn off by themselves after this many minutes (0 = never). */
    var edgeAutoHide: Int
        get() = sp.getInt("edgeAutoHide", 0)
        set(v) = sp.edit().putInt("edgeAutoHide", v).apply()

    /** While these apps are open, glows wait. */
    var edgeQuiet: Set<String>
        get() = sp.getStringSet("edgeQuiet", null) ?: setOf("com.google.android.youtube", "com.netflix.mediaclient")
        set(v) = sp.edit().putStringSet("edgeQuiet", v).apply()

    var lastBackup: Long
        get() = sp.getLong("lastBackup", 0L)
        set(v) = sp.edit().putLong("lastBackup", v).apply()
}
