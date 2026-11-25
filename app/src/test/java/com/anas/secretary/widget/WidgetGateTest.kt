package com.anas.secretary.widget

import android.app.Application
import android.service.notification.NotificationListenerService as NLS
import com.anas.secretary.service.NotifListener
import com.anas.secretary.util.Features.F
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WidgetGateTest {
    @Test
    fun eachWidgetFollowsItsPart() {
        assertEquals(F.Health, Widgets.featureOf("SleepWidget"))
        assertEquals(F.Health, Widgets.featureOf("HealthTodayWidget"))
        assertEquals(F.People, Widgets.featureOf("PeopleWidget"))
        assertEquals(F.Saved, Widgets.featureOf("SavedWidget"))
        assertEquals(F.Things, Widgets.featureOf("ThingWidget"))
        assertEquals(F.Things, Widgets.featureOf("TrackWidget"))
        // Tasks live in the Calendar tab, which never turns off
        assertNull(Widgets.featureOf("TasksWidget"))
        assertNull(Widgets.featureOf("TodayWidget"))
        assertNull(Widgets.featureOf("QuickAddWidget"))
    }

    @Test
    fun onlyHandledOnThePhoneLeavesBrief() {
        listOf(NLS.REASON_CLICK, NLS.REASON_CANCEL, NLS.REASON_CANCEL_ALL, NLS.REASON_APP_CANCEL, NLS.REASON_APP_CANCEL_ALL)
            .forEach { assertTrue("reason $it", NotifListener.followsShade(it)) }
        // Our own cancel (after answering in Brief), snoozes, timeouts and uninstalls don't count
        listOf(NLS.REASON_LISTENER_CANCEL, NLS.REASON_SNOOZED, NLS.REASON_TIMEOUT, NLS.REASON_PACKAGE_CHANGED, NLS.REASON_CHANNEL_BANNED)
            .forEach { assertFalse("reason $it", NotifListener.followsShade(it)) }
    }
}
