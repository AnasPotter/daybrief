package com.anas.secretary

import android.app.Application
import com.anas.secretary.data.Repo
import com.anas.secretary.util.Notifs
import com.anas.secretary.util.Prefs
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Repo.init(this)
        Notifs.createChannels(this)
        com.anas.secretary.edge.EdgeLight.init(this)
        com.anas.secretary.edge.Shizu.init()
        // "Open apps inside the panel" is off for new people. Keep it on for anyone already using it with Shizuku.
        if (!Prefs.has("edgeAppInPanel")) Prefs.edgeAppInPanel =
            try { com.anas.secretary.edge.Shizu.state(this) != com.anas.secretary.edge.Shizu.State.NotInstalled } catch (_: Exception) { false }
        com.anas.secretary.util.Track.scheduleNudges(this)
        com.anas.secretary.util.Daily.scheduleCheck(this)
        com.anas.secretary.util.Bg.scope.launch { try { com.anas.secretary.util.Daily.planTonight(this@App) } catch (_: Exception) {} }
        com.anas.secretary.util.Bg.scope.launch { com.anas.secretary.util.Track.applyAuto() }
        // Rules v2 (bank notices, past times): sort the waiting notifications again, once
        if (Prefs.rulesVersion < 2) com.anas.secretary.util.Bg.scope.launch { Repo.reclassifyWaiting(); Prefs.rulesVersion = 2 }
        // After an update Android can leave widgets on their "loading" picture: redraw them on every start
        com.anas.secretary.widget.Widgets.refreshSoon(this)
    }
}
