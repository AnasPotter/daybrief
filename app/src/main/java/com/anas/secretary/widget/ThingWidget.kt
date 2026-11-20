package com.anas.secretary.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment as GAlignment
import androidx.glance.layout.Box as GBox
import androidx.glance.layout.Column as GColumn
import androidx.glance.layout.Row as GRow
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.padding as gPadding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text as GText
import androidx.glance.text.TextStyle
import com.anas.secretary.data.Repo
import com.anas.secretary.data.Tracker
import com.anas.secretary.ui.LocalPalette
import com.anas.secretary.ui.SecretaryTheme
import com.anas.secretary.util.Prefs
import com.anas.secretary.util.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ThingData(val tracker: Tracker, val emoji: String, val name: String, val big: String, val small: String, val alert: Boolean)

/** One tracked thing, shown big. Tap the pill to move it to its next state. */
class ThingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val load = suspend {
            withContext(Dispatchers.IO) {
                Track.applyAuto()
                val chosen = Prefs.thingFor(widgetId).takeIf { it >= 0 }?.let { Repo.tracker(it) }
                // Not picked yet (or deleted): show whatever needs attention most
                val t = chosen ?: Track.attentionList().firstOrNull()?.first ?: Repo.trackers().firstOrNull()
                t?.let {
                    val card = Repo.cards().firstOrNull { c -> c.id == it.cardId }
                    val ins = Track.insight(it, Repo.logs(it.id, 60))
                    val ago = Track.ago(it.since) // "3 days ago", "5h ago", "Just now"
                    val fresh = ago.startsWith("Just")
                    val big = if (fresh) "Now" else ago.substringBefore(' ')
                    val small = if (fresh) "" else ago.substringAfter(' ', "")
                    ThingData(it, card?.emoji ?: "", it.name, big, small, Track.needsAttention(it, ins))
                }
            }
        }
        val first = load()
        provideContent { Content(context, Widgets.live(first, load)) }
    }

    @Composable
    private fun Content(ctx: Context, d: ThingData?) {
        val open = actionStartActivity(Widgets.open(ctx, "track") { putExtra("tab", 2) })
        if (d == null) {
            GBox(GlanceModifier.widgetCard().clickable(open), contentAlignment = GAlignment.Center) {
                GText("Add things to track in Daybrief", style = TextStyle(color = WColors.sub, fontSize = 13.sp))
            }
            return
        }
        GColumn(GlanceModifier.widgetCard().clickable(open)) {
            if (d.emoji.isNotBlank()) GText(d.emoji, style = TextStyle(fontSize = 24.sp))
            GText(
                d.name, maxLines = 2, modifier = GlanceModifier.gPadding(top = 2.dp),
                style = TextStyle(color = WColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(GlanceModifier.defaultWeight())
            GRow(verticalAlignment = GAlignment.Bottom) {
                GText(d.big, maxLines = 1, style = TextStyle(color = if (d.alert) WColors.red else WColors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold))
                if (d.small.isNotBlank()) GText(
                    "  ${d.small}", maxLines = 1, modifier = GlanceModifier.gPadding(bottom = 5.dp),
                    style = TextStyle(color = WColors.sub, fontSize = 13.sp)
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            val (bg, fg) = WColors.pill(d.tracker.state.kind)
            GBox(
                GlanceModifier.background(bg).cornerRadius(14.dp).gPadding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable(actionRunCallback<CycleAction>(actionParametersOf(CycleAction.key to d.tracker.id)))
            ) {
                GText(d.tracker.state.name, maxLines = 1, style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

class ThingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ThingWidget()
}

/** Shown when the "One thing" widget is added (or reconfigured): pick which thing it shows. */
class ThingWidgetConfig : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // If you back out, the widget is not added
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val cards = Repo.cards().associateBy { it.id }
        val list = Repo.trackers().map { t -> Triple(t, cards[t.cardId]?.emoji ?: "", Track.ago(t.since)) }
        setContent {
            SecretaryTheme {
                val scope = rememberCoroutineScope()
                Picker(list) { t ->
                    scope.launch {
                        Prefs.setThingFor(widgetId, t.id)
                        try {
                            ThingWidget().update(this@ThingWidgetConfig, GlanceAppWidgetManager(this@ThingWidgetConfig).getGlanceIdBy(widgetId))
                        } catch (_: Exception) {}
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    }
                }
            }
        }
    }

    @Composable
    private fun Picker(list: List<Triple<Tracker, String, String>>, onPick: (Tracker) -> Unit) {
        val p = LocalPalette.current
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().padding(16.dp)) {
            Text("Which thing?", fontSize = 30.sp, color = p.text, modifier = Modifier.padding(top = 40.dp, bottom = 6.dp, start = 8.dp))
            Text("The widget shows it big, and you can tap to update it.", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(start = 8.dp, bottom = 20.dp))
            if (list.isEmpty()) {
                Text("You don't track anything yet. Add something in Daybrief → Track first.", fontSize = 15.sp, color = p.text, modifier = Modifier.padding(8.dp))
            }
            LazyColumn(Modifier.clip(RoundedCornerShape(26.dp)).background(p.card), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(list, key = { it.first.id }) { (t, emoji, ago) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.padding(end = 12.dp)) { Text(emoji.ifBlank { "•" }, fontSize = 22.sp) }
                        Column(Modifier.weight(1f)) {
                            Text(t.name, fontSize = 16.sp, color = p.text)
                            Text("${t.state.name}  ·  $ago", fontSize = 13.sp, color = p.sub)
                        }
                    }
                }
            }
        }
    }
}
