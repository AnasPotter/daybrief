package com.anas.secretary.util

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.launch

/**
 * The parts of Secretary a person can turn on or off. A part that's off is hidden everywhere,
 * does no background work and never asks for its permission. Its data is kept, so turning it back on restores it.
 */
object Features {
    enum class F(val key: String, val label: String, val about: String, val needs: String?) {
        Messages("feat_messages", "Messages", "Questions and quick replies in Brief", "Notification access"),
        Calendar("feat_calendar", "Phone calendar", "Your Samsung Calendar events next to your tasks", "Calendar"),
        People("feat_people", "People", "Keep in touch, birthdays, last talked", "Contacts · Call log"),
        Things("feat_things", "Things", "Chores and habits you track", null),
        Health("feat_health", "Health", "Sleep, steps and heart rate from your watch", "Health Connect"),
        Saved("feat_saved", "Saved", "Links you share and screenshots", "Photos"),
        Edge("feat_edge", "Edge lighting", "A glow on the screen edge for new messages", "Draw over apps"),
        Day("feat_day", "Your day", "Morning summary, bedtime, light day, your week", null),
    }

    private val cache = mutableStateMapOf<F, Boolean>()

    /** Compose reads this, so screens update the moment a part is switched. */
    fun on(f: F): Boolean = cache[f] ?: Prefs.feature(f.key).also { cache[f] = it }

    fun set(ctx: Context, f: F, v: Boolean) {
        Prefs.setFeature(f.key, v)
        cache[f] = v
        when (f) {
            F.Edge -> com.anas.secretary.edge.EdgeLight.refresh()
            F.Day -> Bg.scope.launch { try { Daily.planTonight(ctx) } catch (_: Exception) {} }
            else -> {}
        }
        com.anas.secretary.data.Repo.changed()
    }

    // Short names used across the app
    val messages get() = on(F.Messages)
    val calendar get() = on(F.Calendar)
    val people get() = on(F.People)
    val things get() = on(F.Things)
    val health get() = on(F.Health)
    val saved get() = on(F.Saved)
    val edge get() = on(F.Edge)
    val day get() = on(F.Day)
    val track get() = things || people || health

    /** Main tabs shown in the bottom bar (0 Brief, 1 Calendar, 2 Track, 3 Saved). Calendar always stays: your tasks live there. */
    fun tabs(): List<Int> = listOfNotNull(0, 1, if (track) 2 else null, if (saved) 3 else null)

    /** Track sections shown (0 Things, 1 People, 2 Health, 3 Timeline). */
    fun trackSegs(): List<Int> = listOfNotNull(if (things) 0 else null, if (people) 1 else null, if (health) 2 else null, if (things) 3 else null)
}
