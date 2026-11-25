package com.anas.secretary.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.anas.secretary.data.Repo
import com.anas.secretary.logic.RuleEngine
import com.anas.secretary.util.Cal
import com.anas.secretary.util.Contacts
import com.anas.secretary.util.Fmt
import com.anas.secretary.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private class Upcoming(val title: String, val whenText: String, val color: Color, val at: Long)

private class TodayData(
    val done: Int, val total: Int, val greeting: String, val next: List<Upcoming>,
    val replies: Int, val calls: Int, val overdue: Int
)

/** "Today": how much of today is done, what's next, and what is waiting for you. */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = withContext(Dispatchers.IO) { load(context) }
        provideContent {
            val data = Widgets.live(first) { withContext(Dispatchers.IO) { load(context) } }
            val ring = Widgets.ring(context, 92, 9f, if (data.total == 0) 1f else data.done / data.total.toFloat(), WColors.accentRaw)
            Content(context, data, ring)
        }
    }

    private fun load(ctx: Context): TodayData {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = start + 86_400_000L
        val tasks = Repo.tasks()
        val todays = tasks.filter { it.due != null && it.due in start until end }
        val next = buildList {
            tasks.filter { !it.done && it.due != null && it.due >= now }.take(5).forEach {
                add(Upcoming(it.title, Fmt.whenText(it.due!!), if (it.work) WColors.work else WColors.accentRaw, it.due))
            }
            if (Cal.canRead(ctx)) {
                try {
                    Cal.events(ctx, now, now + 2 * 86_400_000L).filter { !it.allDay }.take(5).forEach {
                        add(Upcoming(it.title, Fmt.whenText(it.begin), Color(it.color or 0xFF000000.toInt()), it.begin))
                    }
                } catch (_: Exception) {}
            }
            if (com.anas.secretary.util.Features.people) Repo.people().forEach { p ->
                val b = Contacts.daysToBirthday(p.birthday) ?: return@forEach
                if (b.first in 0..3) add(Upcoming(
                    "${p.name}'s birthday", if (b.first == 0) "Today" else if (b.first == 1) "Tomorrow" else "In ${b.first} days",
                    WColors.warn, now + b.first * 86_400_000L
                ))
            }
        }.sortedBy { it.at }.take(3)
        val asks = if (com.anas.secretary.util.Features.messages) Repo.notifs(false) else emptyList()
        val hour = LocalTime.now().hour
        val hello = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        return TodayData(
            done = todays.count { it.done }, total = todays.size,
            greeting = Prefs.name.ifBlank { null }?.let { "$hello, $it" } ?: hello,
            next = next,
            replies = asks.count { it.sType == RuleEngine.REPLY },
            calls = asks.count { it.sType == RuleEngine.CALL } + (if (com.anas.secretary.util.Features.people) Repo.people().count { it.isDue(now) && it.snooze < now } else 0),
            overdue = tasks.count { !it.done && it.due != null && it.due < now }
        )
    }

    @Composable
    private fun Content(ctx: Context, d: TodayData, ring: Bitmap) {
        Row(
            GlanceModifier.widgetCard().clickable(actionStartActivity(Widgets.open(ctx, "today") { putExtra("tab", 0) })),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(GlanceModifier.width(112.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(92.dp), contentAlignment = Alignment.Center) {
                    Image(ImageProvider(ring), null, GlanceModifier.size(92.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (d.total == 0) "✓" else "${d.done}/${d.total}",
                            style = TextStyle(color = WColors.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        )
                        Text(if (d.total == 0) "free day" else "done today", style = TextStyle(color = WColors.sub, fontSize = 10.sp))
                    }
                }
                Spacer(GlanceModifier.height(6.dp))
                Text(d.greeting, maxLines = 2, style = TextStyle(color = WColors.sub, fontSize = 11.sp, textAlign = TextAlign.Center))
            }
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Text("Coming up", style = TextStyle(color = WColors.sub, fontSize = 12.sp), modifier = GlanceModifier.padding(bottom = 4.dp))
                if (d.next.isEmpty()) {
                    Text("Nothing planned", style = TextStyle(color = WColors.text, fontSize = 13.sp))
                }
                d.next.forEach { u ->
                    Row(
                        GlanceModifier.fillMaxWidth().background(WColors.inner).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(GlanceModifier.width(4.dp).height(28.dp).background(ColorProvider(u.color)).cornerRadius(2.dp)) {}
                        Spacer(GlanceModifier.width(9.dp))
                        Column {
                            Text(u.title, maxLines = 1, style = TextStyle(color = WColors.text, fontSize = 13.sp))
                            Text(u.whenText, maxLines = 1, style = TextStyle(color = WColors.sub, fontSize = 11.sp))
                        }
                    }
                    Spacer(GlanceModifier.height(5.dp))
                }
                Row {
                    if (d.replies > 0) Chip("${d.replies} to reply", WColors.accent)
                    if (d.calls > 0) Chip("${d.calls} to call", WColors.green)
                    if (d.overdue > 0) Chip("${d.overdue} late", WColors.red)
                }
            }
        }
    }

    @Composable
    private fun Chip(text: String, color: ColorProvider) {
        Box(GlanceModifier.background(WColors.inner).cornerRadius(12.dp).padding(horizontal = 9.dp, vertical = 4.dp)) {
            Text(text, maxLines = 1, style = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium))
        }
        Spacer(GlanceModifier.width(5.dp))
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
