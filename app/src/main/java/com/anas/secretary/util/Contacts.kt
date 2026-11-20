package com.anas.secretary.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.anas.secretary.data.Person
import com.anas.secretary.data.Repo
import java.time.LocalDate

/** Contacts, call history and quick ways to reach someone. Everything stays on the phone. */
object Contacts {
    fun canContacts(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canCallLog(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    class Picked(val contactId: Long, val name: String, val phones: List<String>, val photo: String?, val birthday: String?)

    /** Reads a contact chosen from the phone's contact picker. */
    fun read(ctx: Context, uri: Uri): Picked? {
        return try {
            var id = -1L
            var name = ""
            var photo: String? = null
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) { id = c.getLong(0); name = c.getString(1) ?: ""; photo = c.getString(2) }
            }
            if (id < 0) return null
            val phones = mutableListOf<String>()
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(id.toString()), null
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { if (phones.none { p -> same(p, it) }) phones += it } }
            var birthday: String? = null
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Event.TYPE}=?",
                arrayOf(id.toString(), ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
                null
            )?.use { c ->
                if (c.moveToFirst()) birthday = Regex("(\\d{1,2})-(\\d{1,2})$").find(c.getString(0) ?: "")?.let {
                    "%02d-%02d".format(it.groupValues[1].toInt(), it.groupValues[2].toInt())
                }
            }
            Picked(id, name, phones, photo, birthday)
        } catch (_: Exception) { null }
    }

    private fun digits(n: String) = n.filter { it.isDigit() }

    /** Same phone number, written differently (+966 5…, 05…, 9665…). */
    fun same(a: String, b: String): Boolean {
        val x = digits(a).takeLast(9)
        val y = digits(b).takeLast(9)
        return x.length >= 7 && x == y
    }

    /** Days until the next birthday ("MM-dd"), or null. */
    fun daysToBirthday(birthday: String?): Pair<Int, Int>? {
        val m = Regex("(\\d{2})-(\\d{2})").find(birthday ?: "") ?: return null
        val today = LocalDate.now()
        return try {
            var next = LocalDate.of(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            if (next.isBefore(today)) next = next.plusYears(1)
            (next.toEpochDay() - today.toEpochDay()).toInt() to next.year
        } catch (_: Exception) { null }
    }

    /** Reads new calls from the call history and updates "last talked". */
    fun syncCallLog(ctx: Context, onlyPerson: Person? = null) {
        if (!canCallLog(ctx)) return
        val people = (onlyPerson?.let { listOf(it) } ?: Repo.people()).filter { it.phones.isNotEmpty() }
        if (people.isEmpty()) return
        val now = System.currentTimeMillis()
        val since = if (onlyPerson != null) now - 90 * 86_400_000L
        else maxOf(Prefs.callLogSince, now - 90 * 86_400_000L)
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE}>?", arrayOf(since.toString()), "${CallLog.Calls.DATE} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0) ?: continue
                    val p = people.firstOrNull { pp -> pp.phones.any { same(it, number) } } ?: continue
                    val dur = c.getInt(3)
                    val kind = when (c.getInt(1)) {
                        CallLog.Calls.OUTGOING_TYPE -> if (dur > 0) "call_out" else "call_try"
                        CallLog.Calls.INCOMING_TYPE -> "call_in"
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "missed"
                        else -> continue
                    }
                    Repo.addInteraction(p.id, kind, dur, c.getLong(2))
                }
            }
            if (onlyPerson == null) Prefs.callLogSince = now - 86_400_000L
        } catch (_: Exception) {}
    }

    fun dial(ctx: Context, number: String?) {
        if (number == null) return
        try {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /** Opens the WhatsApp chat with this number. */
    fun whatsapp(ctx: Context, number: String?) {
        if (number == null) return
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val iso = (tm?.simCountryIso?.ifBlank { null } ?: tm?.networkCountryIso ?: "sa").uppercase()
        val e164 = PhoneNumberUtils.formatNumberToE164(number, iso) ?: number
        val d = e164.filter { it.isDigit() }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$d")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /** Finds a person by the name an app shows (WhatsApp uses your contact names). */
    fun personByName(name: String): Person? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return Repo.people().firstOrNull {
            it.contactName.equals(n, true) || it.name.equals(n, true) ||
                (it.contactName.isNotBlank() && n.contains(it.contactName, true))
        }
    }
}
