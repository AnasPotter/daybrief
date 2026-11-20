package com.anas.secretary.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.anas.secretary.R
import com.anas.secretary.data.Repo
import com.anas.secretary.data.Task
import com.anas.secretary.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Open tasks; tick them off right from the home screen. */
class TasksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val load = suspend {
            withContext(Dispatchers.IO) {
                val all = Repo.tasks()
                val now = System.currentTimeMillis()
                // Overdue and dated tasks first (soonest first), then the ones without a date.
                // Tasks ticked in the last hour stay visible (crossed out), so a mis-tap can be undone.
                val open = all.filter { !it.done }.sortedWith(compareBy<Task> { it.due == null }.thenBy { it.due ?: it.created })
                val justDone = all.filter { it.done && it.id in TaskTick.recent(now) }
                open + justDone
            }
        }
        val first = load()
        provideContent { Content(context, Widgets.live(first, load)) }
    }

    @Composable
    private fun Content(ctx: Context, tasks: List<Task>) {
        val now = System.currentTimeMillis()
        val left = tasks.count { !it.done }
        Column(GlanceModifier.widgetCard(12)) {
            Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    GlanceModifier.defaultWeight().clickable(actionStartActivity(Widgets.open(ctx, "tasks") { putExtra("tab", 1) })),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tasks", style = TextStyle(color = WColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    Text(
                        if (left == 0) "  all done" else "  $left left",
                        style = TextStyle(color = WColors.sub, fontSize = 13.sp)
                    )
                }
                Box(
                    GlanceModifier.size(30.dp).background(WColors.accent).cornerRadius(15.dp)
                        .clickable(actionStartActivity(Widgets.open(ctx, "quick/task") { putExtra("quick", "task") })),
                    contentAlignment = Alignment.Center
                ) { Image(ImageProvider(R.drawable.w_plus), "Add task", GlanceModifier.size(18.dp)) }
            }
            if (tasks.isEmpty()) {
                Text(
                    "Nothing to do. Tap + to add a task.",
                    style = TextStyle(color = WColors.sub, fontSize = 13.sp), modifier = GlanceModifier.padding(4.dp)
                )
            } else {
                LazyColumn {
                    items(tasks, itemId = { it.id }) { t ->
                        val late = !t.done && t.due != null && t.due < now
                        Column {
                            Row(
                                GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp)
                                    .clickable(actionRunCallback<TaskTick>(actionParametersOf(TaskTick.key to t.id))),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    ImageProvider(if (t.done) R.drawable.w_check_on else R.drawable.w_check_off),
                                    if (t.done) "Done" else "Mark done", GlanceModifier.size(22.dp)
                                )
                                Spacer(GlanceModifier.width(10.dp))
                                Text(
                                    t.title, maxLines = 1, modifier = GlanceModifier.defaultWeight(),
                                    style = TextStyle(
                                        color = if (t.done) WColors.sub else WColors.text, fontSize = 14.sp,
                                        textDecoration = if (t.done) TextDecoration.LineThrough else TextDecoration.None
                                    )
                                )
                                if (t.work) {
                                    Spacer(GlanceModifier.width(6.dp))
                                    Box(GlanceModifier.size(7.dp).background(ColorProvider(WColors.work)).cornerRadius(4.dp)) {}
                                }
                                if (t.due != null) {
                                    Spacer(GlanceModifier.width(8.dp))
                                    Text(
                                        Fmt.whenText(t.due), maxLines = 1,
                                        style = TextStyle(color = if (late) WColors.red else WColors.sub, fontSize = 11.sp)
                                    )
                                }
                            }
                            Box(GlanceModifier.fillMaxWidth().height(1.dp).padding(start = 36.dp).background(WColors.line)) {}
                        }
                    }
                }
            }
        }
    }
}

/** Ticks a task on or off from the widget. */
class TaskTick : ActionCallback {
    companion object {
        val key = ActionParameters.Key<Long>("taskId")
        private val ticked = HashMap<Long, Long>()

        @Synchronized fun recent(now: Long): Set<Long> {
            ticked.entries.removeAll { now - it.value > 3_600_000L }
            return ticked.keys.toSet()
        }
        @Synchronized fun mark(id: Long, done: Boolean) { if (done) ticked[id] = System.currentTimeMillis() else ticked.remove(id) }
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[key] ?: return
        withContext(Dispatchers.IO) {
            val t = Repo.task(id) ?: return@withContext
            mark(id, !t.done)
            Repo.setDone(id, !t.done)
        }
        Widgets.refreshNow(context)
    }
}

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}
