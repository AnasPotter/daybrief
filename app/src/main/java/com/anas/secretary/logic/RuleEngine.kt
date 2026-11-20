package com.anas.secretary.logic

import com.anas.secretary.util.Fmt

data class Suggestion(val type: String, val label: String, val taskTitle: String, val due: Long?)

/**
 * Decides what to suggest for a notification. Pure rules for now —
 * later this is the ONE place to plug in an AI model instead.
 */
object RuleEngine {
    const val REMIND = "remind"
    const val REPLY = "reply"
    const val CALL = "call"
    const val TASK = "task"
    const val IGNORE = "ignore"

    private val messagingApps = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.facebook.orca", "com.instagram.android", "com.microsoft.teams", "com.Slack",
        "com.google.android.gm", "com.microsoft.office.outlook", "com.snapchat.android", "com.viber.voip"
    )
    private val otpWords = listOf("code", "otp", "verification", "verify", "passcode", "one-time", "رمز", "كود")
    private val promoWords = listOf(
        "sale", "discount", "special offer", "deal", "deals", "promo", "coupon", "limited time",
        "free shipping", "% off", "unsubscribe", "خصم", "عروض"
    )
    /** Bank and card alerts: purchases, transfers, balances. Two of these together = a transaction notice. */
    private val bankWords = listOf(
        "purchase", "transaction", "debited", "credited", "card ending", "balance", "pos ", "atm", "transfer",
        "sar ", "sar ", "ريال", "شراء", "مبلغ", "بطاقة", "رصيد", "حوالة", "تحويل", "مدى", "خصم من", "إيداع", "سحب"
    )
    private val missedCall = listOf("missed call", "missed voice call", "missed video call", "مكالمة فائتة")
    private val billWords = listOf(
        "bill", "invoice", "payment due", "amount due", "overdue", "pay by", "renewal",
        "subscription expires", "فاتورة"
    )
    private val deliveryWords = listOf(
        "out for delivery", "delivered", "shipped", "parcel", "courier", "tracking number", "شحنة", "توصيل"
    )
    private val requestWords = listOf(
        "can you", "could you", "please", "call me", "let me know", "asap", "urgent", "need you",
        "ممكن", "لو سمحت", "ضروري"
    )
    private val taskWords = listOf(
        "meeting", "appointment", "deadline", "due", "submit", "reminder", "don't forget",
        "interview", "موعد", "اجتماع"
    )

    fun suggest(pkg: String, app: String, title: String, body: String): Suggestion? {
        val text = "$title\n$body"
        val t = text.lowercase()
        val who = title.ifBlank { app }

        if (Regex("""\b\d{4,8}\b""").containsMatchIn(t) && hasAny(t, otpWords))
            return Suggestion(IGNORE, "Verification code — nothing to do", "", null)
        if (hasAny(t, promoWords) || t.contains("% off"))
            return Suggestion(IGNORE, "Looks like a promotion — safe to ignore", "", null)
        if (bankWords.count { t.contains(it) } >= 2)
            return Suggestion(IGNORE, "Bank notice — nothing to do", "", null)
        if (hasAny(t, missedCall))
            return Suggestion(CALL, "Missed call — call back?", "Call back $who", null)

        // A time already in the past (e.g. the time printed in a receipt) is not something to be reminded of
        DateExtractor.extract(text)?.takeIf { it.at > System.currentTimeMillis() }?.let { d ->
            return Suggestion(REMIND, "Reminder for ${Fmt.whenText(d.at)}", oneLine("$who: $body"), d.at)
        }
        if (hasAny(t, billWords))
            return Suggestion(TASK, "Looks like a bill or payment", oneLine("Pay: $who — $body"), null)
        if (hasAny(t, deliveryWords))
            return Suggestion(TASK, "Delivery update", oneLine("Package: $body"), null)

        val isMessage = pkg in messagingApps
        if (isMessage && (t.contains("?") || t.contains("؟") || hasAny(t, requestWords)))
            return Suggestion(REPLY, "$who is waiting for a reply", "Reply to $who", null)
        if (hasAny(t, taskWords))
            return Suggestion(TASK, "Might need action", oneLine("$who: $body"), null)
        return null
    }

    private val reArabic = Regex("[\u0600-\u06FF]")

    /** Ready-made replies in the language of the message. */
    fun quickReplies(body: String, hasTime: Boolean): List<String> {
        val ar = reArabic.containsMatchIn(body)
        return when {
            ar && hasTime -> listOf("تمام، موعدنا 👍", "ممكن نأجلها شوي؟", "أرد عليك بعد شوي")
            ar -> listOf("تمام 👍", "أكلمك بعد شوي", "مشغول الحين، أرد عليك بعدين")
            hasTime -> listOf("Confirmed, see you then 👍", "Can we make it a bit later?", "I'll get back to you")
            else -> listOf("Sounds good 👍", "I'll call you later", "Busy right now, will reply soon")
        }
    }

    fun actionLabel(type: String?): String = when (type) {
        REMIND -> "Set reminder"
        REPLY -> "Reply later"
        CALL -> "Remind to call"
        else -> "Add task"
    }
}
