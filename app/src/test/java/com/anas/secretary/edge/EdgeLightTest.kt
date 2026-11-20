package com.anas.secretary.edge

import android.app.Application
import android.content.Context
import com.anas.secretary.logic.RuleEngine
import com.anas.secretary.logic.Suggestion
import com.anas.secretary.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EdgeLightTest {
    private val pkg = "example.chat"
    private val actionable = Suggestion(RuleEngine.REPLY, "Reply", "Reply", null)

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(app)
        Prefs.edgeQuiet = emptySet()
        Prefs.edgeSmart = false
        EdgeConfig.save(listOf(EdgeConfig.newFor(pkg, emptyList())))
        ShadowSettings.setCanDrawOverlays(false)
        EdgeLight.init(app)
        EdgeLight.reset()
        Prefs.edgeAutoHide = 0
    }

    @Test
    fun notificationReceivedWhileDisabledIsAvailableAfterEnabling() {
        Prefs.edgeOn = false
        EdgeLight.onPosted("message", pkg, "Chat", "Sender", "Hello", actionable)
        Prefs.edgeOn = true
        EdgeLight.refresh()
        assertEquals(listOf("message"), EdgeLight.entriesFor(pkg).map { it.key })
    }

    @Test
    fun missingOverlayPermissionDoesNotDiscardNotification() {
        Prefs.edgeOn = true
        EdgeLight.onPosted("message", pkg, "Chat", "Sender", "Hello", actionable)
        assertEquals(1, EdgeLight.entriesFor(pkg).size)
    }

    @Test
    fun smartGlowStillLightsOrdinaryMessages() {
        Prefs.edgeOn = true
        Prefs.edgeSmart = true
        // A plain "Hello" gets no suggestion from the rule engine, but it is a real message
        EdgeLight.onPosted("message", pkg, "Chat", "Sender", "Hello", null)
        assertEquals(1, EdgeLight.entriesFor(pkg).size)
    }

    @Test
    fun turningOffSmartGlowRestoresPromotions() {
        Prefs.edgeOn = true
        Prefs.edgeSmart = true
        val promo = Suggestion(RuleEngine.IGNORE, "Promotion", "", null)
        EdgeLight.onPosted("promo", pkg, "Chat", "Shop", "50% off today", promo)
        assertTrue(EdgeLight.entriesFor(pkg).isEmpty())
        Prefs.edgeSmart = false
        EdgeLight.refresh()
        assertEquals(1, EdgeLight.entriesFor(pkg).size)
    }

    @Test
    fun reconnectSnapshotRemovesStaleNotifications() {
        EdgeLight.onPosted("removed", pkg, "Chat", "Sender", "Old", actionable)
        EdgeLight.onPosted("active", pkg, "Chat", "Sender", "New", actionable)
        EdgeLight.retainNotifications(setOf("active"))
        assertEquals(listOf("active"), EdgeLight.entriesFor(pkg).map { it.key })
        EdgeLight.onRemoved("active")
        assertTrue(EdgeLight.entriesFor(pkg).isEmpty())
    }

    @Test
    fun selectingAnAppDoesNotRequireAnotherNotification() {
        EdgeConfig.save(emptyList())
        EdgeLight.onPosted("message", pkg, "Chat", "Sender", "Hello", actionable)
        EdgeConfig.save(listOf(EdgeConfig.newFor(pkg, emptyList())))
        EdgeLight.refresh()
        assertEquals(1, EdgeLight.entriesFor(pkg).size)
    }

    @Test
    fun hiddenGlowStaysHiddenWhenTheSameNotificationIsRestored() {
        Prefs.edgeOn = true
        EdgeLight.onPosted("message", pkg, "Chat", "Sender", "Hello", actionable)
        EdgeLight.hideApp(pkg)
        assertTrue(EdgeLight.entriesFor(pkg).isEmpty())
        // Reopening the app re-reads the notification shade (it starts from an empty list): same text, so still hidden
        EdgeLight.retainNotifications(emptySet())
        EdgeLight.onPosted("message", pkg, "Chat", "Sender", "Hello", actionable, redraw = false)
        assertTrue(EdgeLight.entriesFor(pkg).isEmpty())
    }

    @Test
    fun newMessageAfterHidingGlowsAgain() {
        Prefs.edgeOn = true
        EdgeLight.onPosted("message", pkg, "Chat", "Sender", "Hello", actionable)
        EdgeLight.hideApp(pkg)
        EdgeLight.onPosted("message", pkg, "Chat", "Sender", "Hello, are you there?", actionable)
        assertEquals(1, EdgeLight.entriesFor(pkg).size)
    }

    @Test
    fun autoHideTurnsOffOldGlows() {
        Prefs.edgeOn = true
        Prefs.edgeAutoHide = 5
        EdgeLight.onPosted("old", pkg, "Chat", "Sender", "Old", actionable, time = System.currentTimeMillis() - 6 * 60_000L)
        EdgeLight.onPosted("new", pkg, "Chat", "Sender", "New", actionable)
        assertEquals(listOf("new"), EdgeLight.entriesFor(pkg).map { it.key })
        Prefs.edgeAutoHide = 0
    }

    @Test
    fun demoExplainsMissingOverlayPermission() {
        EdgeLight.demo()
        assertEquals("Allow Show over other apps before testing edge lighting.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun messagesFromTheSameChatShareOneRow() {
        Prefs.edgeOn = true
        EdgeLight.onPosted("0|$pkg|1", pkg, "Chat", "Group", "first", null, time = 1000)
        EdgeLight.onPosted("0|$pkg|2", pkg, "Chat", "Group", "second", null, time = 2000)
        EdgeLight.onPosted("0|$pkg|3", pkg, "Chat", "Sara", "hi", null, time = 1500)
        val rows = EdgeLight.entriesFor(pkg)
        assertEquals(listOf("second", "hi"), rows.map { it.text })
        assertEquals(2, rows.first().count)
    }

    @Test
    fun dualAppChatsStayApartAndKnowTheirUser() {
        Prefs.edgeOn = true
        EdgeLight.onPosted("0|$pkg|1", pkg, "Chat", "Sara", "main", null, time = 1000)
        EdgeLight.onPosted("95|$pkg|1", pkg, "Chat", "Sara", "dual", null, time = 2000)
        val rows = EdgeLight.entriesFor(pkg)
        assertEquals(2, rows.size)
        assertEquals(95, rows.first().user)
    }

    @Test
    fun countOnlyNotificationHidesNextToRealMessages() {
        Prefs.edgeOn = true
        EdgeLight.onPosted("0|$pkg|1", pkg, "Chat", "New messages", "You have 8 new messages.", null, time = 3000)
        EdgeLight.onPosted("0|$pkg|2", pkg, "Chat", "Sara", "hi", null, time = 1000)
        assertEquals(listOf("hi"), EdgeLight.entriesFor(pkg).map { it.text })
    }
}
