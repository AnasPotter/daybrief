package com.anas.secretary.ui

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReportLinkTest {
    @Test
    fun longArabicNoteAndTraceStillFitAndCloseTheCodeBlock() {
        val trace = (1..90).joinToString("\n") { "  at com.anas.secretary.ui.Something.method$it(Something.kt:$it)" }
        val note = "التطبيق قفل فجأة لما فتحت صفحة التقويم ".repeat(30)
        val url = issueUrl("owner/repo", "التطبيق قفل", note, true, trace)
        assertTrue(url.length <= 6500)
        val body = android.net.Uri.decode(url.substringAfter("&body="))
        assertTrue(body.trimEnd().endsWith("```"))
        assertTrue(body.contains("  …"))
    }

    @Test
    fun shortReportIsKeptWhole() {
        val url = issueUrl("owner/repo", "Problem report", "It froze", false, "java.lang.IllegalStateException\n  at a.b.C.d(C.kt:1)")
        val body = android.net.Uri.decode(url.substringAfter("&body="))
        assertTrue(body.contains("It froze") && body.contains("C.kt:1") && !body.contains("…"))
    }
}
