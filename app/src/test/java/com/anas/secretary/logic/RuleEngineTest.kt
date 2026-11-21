package com.anas.secretary.logic

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RuleEngineTest {
    private val sms = "com.google.android.apps.messaging"

    @Test
    fun arabicCardPurchaseIsABankNotice() {
        val s = RuleEngine.suggest(sms, "Messages", "Bank", "شراء إنترنت\nبطاقة: ***0000;مدى\nمن: ***000\nمبلغ: 20.99\nفي: 01/01 10:00")
        assertEquals(RuleEngine.IGNORE, s?.type)
    }

    @Test
    fun shiftHoursAreNotADate() {
        val ascii = "Night Shift: 7-10 Yasser\n10-12 Subaie\n12-02 Sudi\n02-04 Fifi"
        val arabic = "Night Shift: ٧-١٠:ياسر\n١٠-١٢:السبيعي\n١٢-٠٢:السودي"
        assertEquals(null, DateExtractor.extract(ascii))
        assertEquals(null, DateExtractor.extract(arabic))
        assertNotEquals(null, DateExtractor.extract("Meeting on 7/10"))
        assertNotEquals(null, DateExtractor.extract("Meeting on 7-10-2030"))
    }

    @Test
    fun englishCardPurchaseIsABankNotice() {
        val s = RuleEngine.suggest(sms, "Messages", "Bank", "Purchase of SAR 45.00 with card ending 1234 at Store. Balance SAR 900")
        assertEquals(RuleEngine.IGNORE, s?.type)
    }

    @Test
    fun aFutureTimeStillSuggestsAReminder() {
        val s = RuleEngine.suggest("com.whatsapp", "WhatsApp", "Ahmed", "Dinner tomorrow at 8pm?")
        assertEquals(RuleEngine.REMIND, s?.type)
    }

    @Test
    fun aMessageMentioningOneCardIsNotABankNotice() {
        val s = RuleEngine.suggest("com.whatsapp", "WhatsApp", "Sara", "Can you bring my card please?")
        assertNotEquals(RuleEngine.IGNORE, s?.type)
    }
}
