package com.anas.secretary.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.anas.secretary.data.Repo
import com.anas.secretary.ui.personColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Face(val id: Long, val name: String, val ago: String, val late: Boolean, val pic: Bitmap)

/** The people you want to keep in touch with, most overdue first. Tap one to call or message. */
class PeopleWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val load = suspend {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                Repo.people().filter { it.goal > 0 && it.snooze < now }
                    .sortedByDescending { (it.daysSince(now) ?: 9999) / it.goal.toFloat() }
                    .take(6)
                    .map { p ->
                        val days = p.daysSince(now)
                        val late = p.isDue(now)
                        val left = if (days == null) 0f else 1f - days / p.goal.toFloat()
                        val color = personColor(p.name)
                        Face(
                            p.id, p.name.substringBefore(' '), agoShort(days), late,
                            Widgets.avatar(context, 54, p.name, p.photo, color, if (late) 1f else left, if (late) WColors.bad else color)
                        )
                    }
            }
        }
        val feat = Widgets.featureOf("PeopleWidget")!!
        // A part that's off loads nothing and shows a small "turn it on" card instead
        val first = if (com.anas.secretary.util.Features.on(feat)) load() else null
        provideContent {
            Widgets.Gated(context, feat) {
                val data = Widgets.live(first) { if (com.anas.secretary.util.Features.on(feat)) load() else null }
                data?.let { Content(context, it) } ?: Widgets.Blank() // just turned on: data arrives with the next refresh
            }
        }
    }

    private fun agoShort(days: Int?): String = when {
        days == null -> "never"
        days == 0 -> "today"
        days < 14 -> "${days}d"
        days < 60 -> "${days / 7}w"
        else -> "${days / 30}mo"
    }

    @Composable
    private fun Content(ctx: Context, faces: List<Face>) {
        // Fit as many faces as the widget is wide (about 72dp each)
        val fit = ((LocalSize.current.width.value - 24) / 72).toInt().coerceIn(1, 6)
        // One row high: drop the title and shrink the faces so nothing is cut off
        val h = LocalSize.current.height.value
        val compact = h < 135
        val face = if (compact) (h - 46).coerceIn(30f, 50f).dp else 54.dp
        val showAgo = h >= 100
        Column(GlanceModifier.widgetCard(if (compact) 8 else 12).clickable(actionStartActivity(Widgets.open(ctx, "people") { putExtra("tab", 2); putExtra("people", true) }))) {
            if (!compact) Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Keep in touch", style = TextStyle(color = WColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Text("  tap a person", style = TextStyle(color = WColors.sub, fontSize = 12.sp))
            }
            if (faces.isEmpty()) {
                Text("Add people in Daybrief → Track → People", style = TextStyle(color = WColors.sub, fontSize = 13.sp), modifier = GlanceModifier.padding(4.dp))
                return@Column
            }
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                faces.take(fit).forEach { f ->
                    Column(
                        GlanceModifier.defaultWeight().clickable(actionStartActivity(Widgets.open(ctx, "person/${f.id}") {
                            putExtra("tab", 2); putExtra("personId", f.id)
                        })),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(ImageProvider(f.pic), f.name, GlanceModifier.size(face))
                        Text(f.name, maxLines = 1, style = TextStyle(color = WColors.text, fontSize = 12.sp), modifier = GlanceModifier.padding(top = 3.dp))
                        if (showAgo) Text(f.ago, maxLines = 1, style = TextStyle(color = if (f.late) WColors.red else WColors.sub, fontSize = 10.sp))
                    }
                }
            }
        }
    }
}

class PeopleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PeopleWidget()
}
