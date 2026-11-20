package com.anas.secretary.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.anas.secretary.logic.RuleEngine
import com.anas.secretary.logic.Suggestion
import com.anas.secretary.reminders.Reminders
import com.anas.secretary.service.NotifListener
import com.anas.secretary.util.Bg
import com.anas.secretary.util.Prefs
import com.anas.secretary.util.Cal
import com.anas.secretary.util.Images
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class Notif(
    val id: Long, val pkg: String, val app: String, val title: String, val body: String,
    val time: Long, val sType: String?, val sLabel: String?, val sTitle: String?,
    val sDue: Long?, val status: String, val skey: String?
)

data class Task(
    val id: Long, val title: String, val notes: String, val due: Long?,
    val done: Boolean, val created: Long, val source: String, val image: String?,
    val calEvent: Long? = null, val work: Boolean = false, val personId: Long? = null
)

data class Person(
    val id: Long, val name: String, val contactName: String, val relation: String, val cat: String,
    val contactId: Long?, val phones: List<String>, val photo: String?, val birthday: String?,
    val goal: Int, val lastContact: Long?, val lastHow: String, val snooze: Long, val bdayYear: Int, val created: Long
) {
    fun daysSince(now: Long = System.currentTimeMillis()): Int? = lastContact?.let { ((now - it) / 86_400_000L).toInt() }
    fun isDue(now: Long = System.currentTimeMillis()): Boolean = (daysSince(now) ?: 9999) >= goal
}

data class TCard(val id: Long, val name: String, val emoji: String, val color: Int, val created: Long)
data class TState(val name: String, val kind: String) // kind: good, neutral, warning, bad
data class Tracker(
    val id: Long, val cardId: Long, val name: String, val states: List<TState>, val idx: Int,
    val since: Long, val autoAfter: Long, val snooze: Long, val created: Long
) {
    val state: TState get() = states.getOrElse(idx) { states.firstOrNull() ?: TState("Done", "good") }
}
data class TLog(
    val id: Long, val trackerId: Long, val state: String, val kind: String, val time: Long,
    val note: String, val amount: String, val tags: String, val photo: String?
)

data class PersonNote(val id: Long, val personId: Long, val text: String, val created: Long)
data class Interaction(val id: Long, val personId: Long, val kind: String, val duration: Int, val time: Long)

data class SavedItem(
    val id: Long, val url: String?, val body: String, val note: String,
    val source: String, val created: Long, val title: String?, val image: String?,
    val nudged: Boolean,
    /** Group picked by hand with "Move to group" (null = automatic, by app). */
    val grp: String? = null
)

const val DB_NAME = "secretary.db"

private class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, 8) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notifs(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, app TEXT, title TEXT, body TEXT, time INTEGER, s_type TEXT, s_label TEXT, s_title TEXT, s_due INTEGER, status TEXT, skey TEXT)")
        db.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, notes TEXT, due INTEGER, done INTEGER, created INTEGER, source TEXT, image TEXT, cal_event INTEGER, cat TEXT DEFAULT 'personal', person_id INTEGER)")
        createPeople(db)
        createTrack(db)
        db.execSQL("CREATE TABLE saved(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT, body TEXT, note TEXT, source TEXT, created INTEGER, title TEXT, image TEXT, nudged INTEGER DEFAULT 0, grp TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN image TEXT")
            db.execSQL("ALTER TABLE saved ADD COLUMN title TEXT")
            db.execSQL("ALTER TABLE saved ADD COLUMN image TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE saved ADD COLUMN nudged INTEGER DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE notifs ADD COLUMN skey TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN cal_event INTEGER")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN cat TEXT DEFAULT 'personal'")
            db.execSQL("ALTER TABLE tasks ADD COLUMN person_id INTEGER")
            createPeople(db)
        }
        if (oldVersion < 7) createTrack(db)
        if (oldVersion < 8) db.execSQL("ALTER TABLE saved ADD COLUMN grp TEXT")
    }

    private fun createTrack(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tcards(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, emoji TEXT, color INTEGER, created INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS trackers(id INTEGER PRIMARY KEY AUTOINCREMENT, card_id INTEGER, name TEXT, states TEXT, idx INTEGER DEFAULT 0, since INTEGER, auto_after INTEGER DEFAULT 0, snooze INTEGER DEFAULT 0, created INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tlogs(id INTEGER PRIMARY KEY AUTOINCREMENT, tracker_id INTEGER, state TEXT, kind TEXT, time INTEGER, note TEXT, amount TEXT, tags TEXT, photo TEXT)")
    }

    private fun createPeople(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS people(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, cname TEXT, relation TEXT, cat TEXT, contact_id INTEGER, phones TEXT, photo TEXT, birthday TEXT, goal INTEGER, last_contact INTEGER, last_how TEXT, snooze INTEGER DEFAULT 0, bday_year INTEGER DEFAULT 0, created INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS person_notes(id INTEGER PRIMARY KEY AUTOINCREMENT, person_id INTEGER, text TEXT, created INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS interactions(id INTEGER PRIMARY KEY AUTOINCREMENT, person_id INTEGER, kind TEXT, duration INTEGER, time INTEGER, UNIQUE(person_id, kind, time))")
    }
}

/** All app data lives here, stored privately on the phone. */
object Repo {
    private lateinit var app: Context
    private lateinit var db: DbHelper

    /** Bumped on every change so screens refresh. */
    val version = MutableStateFlow(0)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        db = DbHelper(app)
    }

    fun changed() {
        version.update { it + 1 }
        // Home screen widgets show the same data
        if (::app.isInitialized) com.anas.secretary.widget.Widgets.refreshSoon(app)
    }

    /** Writes everything to the main database file (used before a backup). */
    fun checkpoint() {
        try { db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } } catch (_: Exception) {}
    }

    fun close() = db.close()

    /** Re-opens the database after a restore. */
    fun reopen() {
        db = DbHelper(app)
        changed()
    }
    private fun Cursor.str(i: Int): String = getString(i) ?: ""
    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
    private fun ContentValues.putLong(key: String, v: Long?) { if (v != null) put(key, v) else putNull(key) }

    // ---------- Notifications ----------

    fun insertNotif(pkg: String, appName: String, title: String, body: String, time: Long, s: Suggestion?, skey: String?) {
        val cv = ContentValues().apply {
            put("pkg", pkg); put("app", appName); put("title", title); put("body", body); put("time", time)
            put("s_type", s?.type); put("s_label", s?.label); put("s_title", s?.taskTitle)
            putLong("s_due", s?.due)
            put("status", "new"); put("skey", skey)
        }
        db.writableDatabase.insert("notifs", null, cv)
        changed()
    }

    fun isDuplicate(pkg: String, title: String, body: String, since: Long): Boolean {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM notifs WHERE pkg=? AND title=? AND body=? AND time>?",
            arrayOf(pkg, title, body, since.toString())
        ).use { c -> c.moveToFirst(); return c.getInt(0) > 0 }
    }

    /** Notifications whose app, sender or text contains [q] (all of them, not just waiting ones), newest first. */
    fun searchNotifs(q: String, limit: Int = 40): List<Notif> {
        // '!' escapes % and _ so they're searched as plain characters
        val like = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        val out = mutableListOf<Notif>()
        db.readableDatabase.rawQuery(
            "SELECT id,pkg,app,title,body,time,s_type,s_label,s_title,s_due,status,skey FROM notifs " +
                "WHERE title LIKE ? ESCAPE '!' OR body LIKE ? ESCAPE '!' OR app LIKE ? ESCAPE '!' ORDER BY time DESC LIMIT $limit",
            arrayOf(like, like, like)
        ).use { c ->
            while (c.moveToNext()) out += Notif(
                c.getLong(0), c.str(1), c.str(2), c.str(3), c.str(4), c.getLong(5),
                c.getString(6), c.getString(7), c.getString(8), c.longOrNull(9), c.str(10), c.getString(11)
            )
        }
        return out
    }


    fun notifs(showAll: Boolean): List<Notif> {
        val where = if (showAll) "" else "WHERE status='new' AND s_type IS NOT NULL"
        val out = mutableListOf<Notif>()
        db.readableDatabase.rawQuery(
            "SELECT id,pkg,app,title,body,time,s_type,s_label,s_title,s_due,status,skey FROM notifs $where ORDER BY time DESC LIMIT 300",
            null
        ).use { c ->
            while (c.moveToNext()) out += Notif(
                c.getLong(0), c.str(1), c.str(2), c.str(3), c.str(4), c.getLong(5),
                c.getString(6), c.getString(7), c.getString(8), c.longOrNull(9), c.str(10), c.getString(11)
            )
        }
        return out
    }

    /** Runs the current rules again on waiting notifications (after the rules improve). */
    fun reclassifyWaiting() {
        val w = db.writableDatabase
        notifs(false).forEach { n ->
            val s = RuleEngine.suggest(n.pkg, n.app, n.title, n.body)
            w.update("notifs", ContentValues().apply {
                put("s_type", s?.type); put("s_label", s?.label); put("s_title", s?.taskTitle); put("s_due", s?.due)
            }, "id=?", arrayOf(n.id.toString()))
        }
        changed()
    }

    /** Changes many at once (Clear all). Leaves the phone's notification shade alone. */
    fun setNotifStatusMany(ids: Collection<Long>, status: String) {
        if (ids.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            ids.forEach { w.update("notifs", ContentValues().apply { put("status", status) }, "id=?", arrayOf(it.toString())) }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        changed()
    }

    fun setNotifStatus(id: Long, status: String) {
        db.writableDatabase.update("notifs", ContentValues().apply { put("status", status) }, "id=?", arrayOf(id.toString()))
        if (status != "new" && Prefs.clearHandled) {
            db.readableDatabase.rawQuery("SELECT skey FROM notifs WHERE id=?", arrayOf(id.toString())).use { c ->
                if (c.moveToFirst()) NotifListener.clear(c.getString(0))
            }
        }
        changed()
    }

    fun updateSaved(id: Long, note: String, title: String?) {
        val cv = ContentValues().apply { put("note", note); put("title", title) }
        db.writableDatabase.update("saved", cv, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun clearHandled() {
        db.writableDatabase.delete("notifs", "status!='new' OR s_type IS NULL", null)
        changed()
    }

    fun pruneNotifs(olderThan: Long) {
        db.writableDatabase.delete("notifs", "time<?", arrayOf(olderThan.toString()))
    }

    // ---------- Tasks ----------

    fun addTask(
        title: String, notes: String, due: Long?, source: String, image: String? = null,
        work: Boolean = false, personId: Long? = null
    ): Long {
        val cv = ContentValues().apply {
            put("title", title); put("notes", notes); putLong("due", due)
            put("done", 0); put("created", System.currentTimeMillis()); put("source", source)
            put("image", image); put("cat", if (work) "work" else "personal"); putLong("person_id", personId)
        }
        val id = db.writableDatabase.insert("tasks", null, cv)
        if (due != null) { Reminders.schedule(app, id, title, due); syncCal(id) }
        changed()
        return id
    }

    fun updateTask(id: Long, title: String, notes: String, due: Long?, work: Boolean? = null) {
        val cv = ContentValues().apply {
            put("title", title); put("notes", notes); putLong("due", due)
            if (work != null) put("cat", if (work) "work" else "personal")
        }
        db.writableDatabase.update("tasks", cv, "id=?", arrayOf(id.toString()))
        Reminders.cancel(app, id)
        val t = task(id)
        if (t != null && !t.done && due != null) Reminders.schedule(app, id, title, due)
        syncCal(id)
        changed()
    }

    fun setDone(id: Long, done: Boolean) {
        db.writableDatabase.update("tasks", ContentValues().apply { put("done", if (done) 1 else 0) }, "id=?", arrayOf(id.toString()))
        Reminders.cancel(app, id)
        val t = task(id)
        if (t != null && !done && t.due != null) Reminders.schedule(app, id, t.title, t.due)
        syncCal(id)
        changed()
    }

    fun deleteTask(id: Long) {
        Reminders.cancel(app, id)
        val old = task(id)
        Images.delete(old?.image)
        old?.calEvent?.let { ev -> Bg.scope.launch { Cal.delete(app, ev) } }
        db.writableDatabase.delete("tasks", "id=?", arrayOf(id.toString()))
        changed()
    }

    private fun readTasks(where: String, args: Array<String>?): List<Task> {
        val out = mutableListOf<Task>()
        db.readableDatabase.rawQuery(
            "SELECT id,title,notes,due,done,created,source,image,cal_event,cat,person_id FROM tasks $where " +
                "ORDER BY done ASC, CASE WHEN due IS NULL THEN 1 ELSE 0 END, due ASC, created DESC",
            args
        ).use { c ->
            while (c.moveToNext()) out += Task(
                c.getLong(0), c.str(1), c.str(2), c.longOrNull(3), c.getInt(4) == 1, c.getLong(5), c.str(6),
                c.getString(7), c.longOrNull(8), c.getString(9) == "work", c.longOrNull(10)
            )
        }
        return out
    }

    fun tasks(): List<Task> = readTasks("", null)

    // ---------- Calendar link ----------

    /** Adds/updates this task's event in the phone calendar (in the background). */
    fun syncCal(id: Long) {
        if (!com.anas.secretary.util.Features.calendar) return // the phone's calendar is off in Features
        Bg.scope.launch {
            val t = task(id) ?: return@launch
            val ev = Cal.upsert(app, t)
            if (ev != t.calEvent) setCalEvent(id, ev)
        }
    }

    fun syncAllToCalendar() {
        if (!com.anas.secretary.util.Features.calendar) return
        Bg.scope.launch {
            readTasks("WHERE due IS NOT NULL", null).forEach { t ->
                val ev = Cal.upsert(app, t)
                if (ev != t.calEvent) setCalEvent(t.id, ev)
            }
        }
    }

    private fun setCalEvent(id: Long, ev: Long?) {
        db.writableDatabase.update("tasks", ContentValues().apply { putLong("cal_event", ev) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Calendar events that are really our own tasks (so they aren't shown twice). */
    fun taskEventIds(): Set<Long> {
        val out = HashSet<Long>()
        db.readableDatabase.rawQuery("SELECT cal_event FROM tasks WHERE cal_event IS NOT NULL", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }
    fun task(id: Long): Task? = readTasks("WHERE id=?", arrayOf(id.toString())).firstOrNull()
    fun upcomingReminders(): List<Task> =
        readTasks("WHERE done=0 AND due>?", arrayOf(System.currentTimeMillis().toString()))

    // ---------- People ----------

    private val talkedKinds = setOf("call_out", "call_in", "wa_call", "manual")

    fun people(): List<Person> {
        val out = mutableListOf<Person>()
        db.readableDatabase.rawQuery(
            "SELECT id,name,cname,relation,cat,contact_id,phones,photo,birthday,goal,last_contact,last_how,snooze,bday_year,created FROM people ORDER BY name COLLATE NOCASE",
            null
        ).use { c ->
            while (c.moveToNext()) out += Person(
                c.getLong(0), c.str(1), c.str(2), c.str(3), c.str(4).ifBlank { "family" }, c.longOrNull(5),
                c.str(6).split('|').filter { it.isNotBlank() }, c.getString(7), c.getString(8),
                if (c.isNull(9)) 7 else c.getInt(9), c.longOrNull(10), c.str(11),
                if (c.isNull(12)) 0L else c.getLong(12), if (c.isNull(13)) 0 else c.getInt(13), c.getLong(14)
            )
        }
        return out
    }

    fun person(id: Long): Person? = people().firstOrNull { it.id == id }

    fun addPerson(
        name: String, contactName: String, relation: String, cat: String, contactId: Long?,
        phones: List<String>, photo: String?, birthday: String?, goal: Int
    ): Long {
        val cv = ContentValues().apply {
            put("name", name); put("cname", contactName); put("relation", relation); put("cat", cat)
            putLong("contact_id", contactId); put("phones", phones.joinToString("|")); put("photo", photo)
            put("birthday", birthday); put("goal", goal); put("created", System.currentTimeMillis())
        }
        val id = db.writableDatabase.insert("people", null, cv)
        changed()
        return id
    }

    fun updatePerson(id: Long, name: String, relation: String, cat: String, goal: Int) {
        val cv = ContentValues().apply { put("name", name); put("relation", relation); put("cat", cat); put("goal", goal) }
        db.writableDatabase.update("people", cv, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun deletePerson(id: Long) {
        val a = arrayOf(id.toString())
        db.writableDatabase.delete("people", "id=?", a)
        db.writableDatabase.delete("person_notes", "person_id=?", a)
        db.writableDatabase.delete("interactions", "person_id=?", a)
        changed()
    }

    fun snoozePerson(id: Long, until: Long) {
        db.writableDatabase.update("people", ContentValues().apply { put("snooze", until) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setBirthdayAsked(id: Long, year: Int) {
        db.writableDatabase.update("people", ContentValues().apply { put("bday_year", year) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Records a call/chat. Returns false if it was already known. Keeps "last talked" up to date. */
    fun addInteraction(personId: Long, kind: String, duration: Int, time: Long): Boolean {
        val cv = ContentValues().apply { put("person_id", personId); put("kind", kind); put("duration", duration); put("time", time) }
        val row = db.writableDatabase.insertWithOnConflict("interactions", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (row == -1L) return false
        if (kind in talkedKinds) {
            val p = person(personId)
            if (p != null && (p.lastContact == null || time >= p.lastContact)) {
                val how = when (kind) {
                    "call_out" -> "Call" + if (duration > 0) ", ${minutes(duration)}" else ""
                    "call_in" -> "Call" + if (duration > 0) ", ${minutes(duration)}" else ""
                    "wa_call" -> "WhatsApp call"
                    else -> "You marked it"
                }
                val upd = ContentValues().apply { put("last_contact", time); put("last_how", how) }
                db.writableDatabase.update("people", upd, "id=?", arrayOf(personId.toString()))
            }
        }
        changed()
        return true
    }

    private fun minutes(sec: Int) = if (sec < 60) "${sec}s" else "${sec / 60} min"

    fun interactions(personId: Long, limit: Int = 8): List<Interaction> {
        val out = mutableListOf<Interaction>()
        db.readableDatabase.rawQuery(
            "SELECT id,person_id,kind,duration,time FROM interactions WHERE person_id=? ORDER BY time DESC LIMIT $limit",
            arrayOf(personId.toString())
        ).use { c -> while (c.moveToNext()) out += Interaction(c.getLong(0), c.getLong(1), c.str(2), c.getInt(3), c.getLong(4)) }
        return out
    }

    fun recentInteraction(personId: Long, kind: String, since: Long): Boolean {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM interactions WHERE person_id=? AND kind=? AND time>?",
            arrayOf(personId.toString(), kind, since.toString())
        ).use { c -> c.moveToFirst(); return c.getInt(0) > 0 }
    }

    fun notesFor(personId: Long): List<PersonNote> {
        val out = mutableListOf<PersonNote>()
        db.readableDatabase.rawQuery(
            "SELECT id,person_id,text,created FROM person_notes WHERE person_id=? ORDER BY created", arrayOf(personId.toString())
        ).use { c -> while (c.moveToNext()) out += PersonNote(c.getLong(0), c.getLong(1), c.str(2), c.getLong(3)) }
        return out
    }

    fun addPersonNote(personId: Long, text: String) {
        val cv = ContentValues().apply { put("person_id", personId); put("text", text); put("created", System.currentTimeMillis()) }
        db.writableDatabase.insert("person_notes", null, cv)
        changed()
    }

    fun deletePersonNote(id: Long) {
        db.writableDatabase.delete("person_notes", "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Apps that have sent notifications (for choosing work apps). */
    fun seenApps(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        db.readableDatabase.rawQuery("SELECT pkg, MAX(app) FROM notifs GROUP BY pkg ORDER BY MAX(app) COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out += c.str(0) to c.str(1)
        }
        return out
    }

    // ---------- Things you track ----------

    private fun statesToJson(states: List<TState>): String =
        org.json.JSONArray().apply { states.forEach { put(org.json.JSONObject().put("n", it.name).put("k", it.kind)) } }.toString()

    private fun statesFromJson(s: String): List<TState> = try {
        val a = org.json.JSONArray(s)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> TState(o.optString("n"), o.optString("k", "good")) } }
    } catch (_: Exception) { listOf(TState("Done", "good")) }

    private fun trackChanged() {
        changed()
    }

    fun cards(): List<TCard> {
        val out = mutableListOf<TCard>()
        db.readableDatabase.rawQuery("SELECT id,name,emoji,color,created FROM tcards ORDER BY created", null).use { c ->
            while (c.moveToNext()) out += TCard(c.getLong(0), c.str(1), c.str(2), c.getInt(3), c.getLong(4))
        }
        return out
    }

    fun addCard(name: String, emoji: String, color: Int): Long {
        val cv = ContentValues().apply { put("name", name); put("emoji", emoji); put("color", color); put("created", System.currentTimeMillis()) }
        val id = db.writableDatabase.insert("tcards", null, cv)
        trackChanged()
        return id
    }

    fun updateCard(id: Long, name: String, emoji: String, color: Int) {
        val cv = ContentValues().apply { put("name", name); put("emoji", emoji); put("color", color) }
        db.writableDatabase.update("tcards", cv, "id=?", arrayOf(id.toString()))
        trackChanged()
    }

    fun deleteCard(id: Long) {
        trackers(id).forEach { deleteTracker(it.id) }
        db.writableDatabase.delete("tcards", "id=?", arrayOf(id.toString()))
        trackChanged()
    }

    fun trackers(cardId: Long? = null): List<Tracker> {
        val out = mutableListOf<Tracker>()
        val where = if (cardId != null) "WHERE card_id=?" else ""
        db.readableDatabase.rawQuery(
            "SELECT id,card_id,name,states,idx,since,auto_after,snooze,created FROM trackers $where ORDER BY created",
            if (cardId != null) arrayOf(cardId.toString()) else null
        ).use { c ->
            while (c.moveToNext()) out += Tracker(
                c.getLong(0), c.getLong(1), c.str(2), statesFromJson(c.str(3)), c.getInt(4),
                c.getLong(5), c.getLong(6), c.getLong(7), c.getLong(8)
            )
        }
        return out
    }

    fun tracker(id: Long): Tracker? = trackers().firstOrNull { it.id == id }

    fun addTracker(cardId: Long, name: String, states: List<TState>, autoAfter: Long): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("card_id", cardId); put("name", name); put("states", statesToJson(states)); put("idx", 0)
            put("since", now); put("auto_after", autoAfter); put("created", now)
        }
        val id = db.writableDatabase.insert("trackers", null, cv)
        trackChanged()
        return id
    }

    fun updateTracker(id: Long, name: String, states: List<TState>, autoAfter: Long) {
        val cv = ContentValues().apply { put("name", name); put("states", statesToJson(states)); put("auto_after", autoAfter) }
        db.writableDatabase.update("trackers", cv, "id=?", arrayOf(id.toString()))
        trackChanged()
    }

    fun deleteTracker(id: Long) {
        logs(id, 10_000).forEach { Images.delete(it.photo) }
        db.writableDatabase.delete("tlogs", "tracker_id=?", arrayOf(id.toString()))
        db.writableDatabase.delete("trackers", "id=?", arrayOf(id.toString()))
        trackChanged()
    }

    fun setTrackerState(id: Long, idx: Int, since: Long) {
        val cv = ContentValues().apply { put("idx", idx); put("since", since); put("snooze", 0) }
        db.writableDatabase.update("trackers", cv, "id=?", arrayOf(id.toString()))
        trackChanged()
    }

    fun snoozeTracker(id: Long, until: Long) {
        db.writableDatabase.update("trackers", ContentValues().apply { put("snooze", until) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun addLog(trackerId: Long, state: String, kind: String, time: Long, note: String, amount: String, photo: String?): Long {
        val cv = ContentValues().apply {
            put("tracker_id", trackerId); put("state", state); put("kind", kind); put("time", time)
            put("note", note); put("amount", amount); put("tags", tagsOf(note)); put("photo", photo)
        }
        val id = db.writableDatabase.insert("tlogs", null, cv)
        trackChanged()
        return id
    }

    fun updateLog(id: Long, state: String, kind: String, time: Long, note: String, amount: String, photo: String?) {
        val cv = ContentValues().apply {
            put("state", state); put("kind", kind); put("time", time); put("note", note); put("amount", amount)
            put("tags", tagsOf(note)); put("photo", photo)
        }
        db.writableDatabase.update("tlogs", cv, "id=?", arrayOf(id.toString()))
        trackChanged()
    }

    fun deleteLog(id: Long) {
        db.readableDatabase.rawQuery("SELECT photo FROM tlogs WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) Images.delete(c.getString(0))
        }
        db.writableDatabase.delete("tlogs", "id=?", arrayOf(id.toString()))
        trackChanged()
    }

    private fun tagsOf(note: String) = Regex("#[\\p{L}\\p{N}_]+").findAll(note).joinToString(" ") { it.value }

    private fun readLogs(where: String, args: Array<String>?, limit: Int): List<TLog> {
        val out = mutableListOf<TLog>()
        db.readableDatabase.rawQuery(
            "SELECT id,tracker_id,state,kind,time,note,amount,tags,photo FROM tlogs $where ORDER BY time DESC LIMIT $limit", args
        ).use { c ->
            while (c.moveToNext()) out += TLog(c.getLong(0), c.getLong(1), c.str(2), c.str(3), c.getLong(4), c.str(5), c.str(6), c.str(7), c.getString(8))
        }
        return out
    }

    fun logs(trackerId: Long, limit: Int = 200): List<TLog> = readLogs("WHERE tracker_id=?", arrayOf(trackerId.toString()), limit)
    fun allLogs(limit: Int = 300): List<TLog> = readLogs("", null, limit)

    // ---------- Saved links & notes ----------

    fun addSaved(
        url: String?, body: String, note: String, source: String,
        title: String? = null, image: String? = null
    ): Long {
        val cv = ContentValues().apply {
            put("url", url); put("body", body); put("note", note); put("source", source)
            put("created", System.currentTimeMillis()); put("title", title); put("image", image)
        }
        val id = db.writableDatabase.insert("saved", null, cv)
        changed()
        return id
    }

    fun updateSavedPreview(id: Long, title: String?, image: String?) {
        val cv = ContentValues().apply {
            if (title != null) put("title", title)
            if (image != null) put("image", image)
        }
        if (image != null) saved().firstOrNull { it.id == id }?.image?.let { if (it != image) Images.delete(it) }
        if (cv.size() == 0) return
        db.writableDatabase.update("saved", cv, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun saved(): List<SavedItem> {
        val out = mutableListOf<SavedItem>()
        db.readableDatabase.rawQuery(
            "SELECT id,url,body,note,source,created,title,image,nudged,grp FROM saved ORDER BY created DESC", null
        ).use { c ->
            while (c.moveToNext()) out += SavedItem(
                c.getLong(0), c.getString(1), c.str(2), c.str(3), c.str(4), c.getLong(5),
                c.getString(6), c.getString(7), c.getInt(8) == 1, c.getString(9)
            )
        }
        return out
    }

    /** null puts the item back in its automatic group. */
    fun setSavedGroup(id: Long, grp: String?) {
        db.writableDatabase.update("saved", ContentValues().apply { put("grp", grp?.trim()?.ifBlank { null }) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setSavedNudged(id: Long, value: Boolean = true) {
        db.writableDatabase.update("saved", ContentValues().apply { put("nudged", if (value) 1 else 0) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun deleteSaved(id: Long) {
        saved().firstOrNull { it.id == id }?.let { Images.delete(it.image) }
        db.writableDatabase.delete("saved", "id=?", arrayOf(id.toString()))
        changed()
    }
}
