package com.anas.secretary.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.anas.secretary.data.Repo
import com.anas.secretary.ui.MainActivity
import com.anas.secretary.util.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Row4(val id: Long, val title: String, val sub: String, val state: String, val kind: String, val alert: Boolean)

/** Home screen widget: your tracked things, tap a state to update it. */
class TrackWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val load = suspend {
            withContext(Dispatchers.IO) {
                Track.applyAuto()
                val cards = Repo.cards().associateBy { it.id }
                Repo.trackers().map { t ->
                    val ins = Track.insight(t, Repo.logs(t.id, 60))
                    val card = cards[t.cardId]
                    Row4(t.id, "${card?.emoji ?: ""} ${t.name}".trim(), Track.ago(t.since), t.state.name, t.state.kind, Track.needsAttention(t, ins))
                }.sortedByDescending { it.alert }
            }
        }
        val feat = Widgets.featureOf("TrackWidget")!!
        // A part that's off loads nothing and shows a small "turn it on" card instead
        val first = if (com.anas.secretary.util.Features.on(feat)) load() else null
        provideContent {
            Widgets.Gated(context, feat) {
                val data = Widgets.live(first) { if (com.anas.secretary.util.Features.on(feat)) load() else null }
                data?.let { Content(it) } ?: Widgets.Blank() // just turned on: data arrives with the next refresh
            }
        }
    }

    @Composable
    private fun Content(rows: List<Row4>) {
        Column(
            GlanceModifier.widgetCard(12)
        ) {
            Text(
                "Things", style = TextStyle(color = WColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.padding(start = 4.dp, bottom = 6.dp).clickable(actionStartActivity<MainActivity>())
            )
            if (rows.isEmpty()) {
                Text("Add things to track in Daybrief", style = TextStyle(color = WColors.sub, fontSize = 13.sp))
            } else {
                LazyColumn {
                    items(rows, itemId = { it.id }) { r ->
                        Column {
                            Row(GlanceModifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(GlanceModifier.defaultWeight()) {
                                    Text(r.title, maxLines = 1, style = TextStyle(color = WColors.text, fontSize = 14.sp))
                                    Text(r.sub, maxLines = 1, style = TextStyle(
                                        color = if (r.alert) WColors.red else WColors.sub, fontSize = 12.sp))
                                }
                                Spacer(GlanceModifier.width(8.dp))
                                val (bg, fg) = WColors.pill(r.kind)
                                Box(
                                    GlanceModifier.background(bg).cornerRadius(14.dp).padding(horizontal = 12.dp, vertical = 6.dp)
                                        .clickable(actionRunCallback<CycleAction>(actionParametersOf(CycleAction.key to r.id))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(r.state, maxLines = 1, style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                                }
                            }
                            Spacer(GlanceModifier.height(2.dp))
                        }
                    }
                }
            }
        }
    }
}

class CycleAction : ActionCallback {
    companion object { val key = ActionParameters.Key<Long>("trackerId") }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[key] ?: return
        withContext(Dispatchers.IO) { Repo.tracker(id)?.let { Track.tap(it) } }
        Widgets.refreshNow(context)
    }
}

class TrackWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrackWidget()
}
