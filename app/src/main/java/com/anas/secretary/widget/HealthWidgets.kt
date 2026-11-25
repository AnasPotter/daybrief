package com.anas.secretary.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.anas.secretary.logic.HealthSnap
import com.anas.secretary.logic.HealthSummary
import com.anas.secretary.logic.SleepNight
import com.anas.secretary.util.Health
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

private val deep = Color(0xFF5B5BD6)
private val lightC = Color(0xFF8FB3FF)
private val remC = Color(0xFFC084FC)
private val awakeC = Color(0xFFF2A516)

/** The sleep stages as a rounded bar (widgets can't lay out proportional pieces themselves). */
private fun stageBitmap(ctx: Context, n: SleepNight, wDp: Int, hDp: Int): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, Path.Direction.CW) }
    c.clipPath(clip)
    val parts = if (n.stages.isEmpty()) listOf("light" to (n.end - n.start)) else n.stages
    val total = parts.sumOf { it.second }.coerceAtLeast(1).toFloat()
    var x = 0f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    parts.forEach { (k, ms) ->
        val pw = w * ms / total
        p.color = when (k) { "deep" -> deep; "rem" -> remC; "awake" -> awakeC; else -> lightC }.toArgb()
        c.drawRect(x, 0f, x + pw, h.toFloat(), p)
        x += pw
    }
    return bmp
}

private fun openHealth(ctx: Context) = actionStartActivity(Widgets.open(ctx, "health") { putExtra("tab", 2); putExtra("health", true) })

@Composable
private fun NotConnected(ctx: Context) {
    Column(GlanceModifier.widgetCard().clickable(openHealth(ctx)), verticalAlignment = Alignment.CenterVertically) {
        Text("⌚", style = TextStyle(fontSize = 20.sp))
        Text("Connect your watch", style = TextStyle(color = WColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.padding(top = 4.dp))
        Text("Tap to set up in Daybrief → Track → Health", style = TextStyle(color = WColors.sub, fontSize = 12.sp))
    }
}

/** "Last night": hours slept, the stage bar and how it compares with your average. */
class SleepWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val load = suspend { withContext(Dispatchers.IO) { Health.cached(context) } }
        val feat = Widgets.featureOf("SleepWidget")!!
        // A part that's off loads nothing and shows a small "turn it on" card instead
        val first = if (com.anas.secretary.util.Features.on(feat)) load() else null
        provideContent {
            Widgets.Gated(context, feat) {
                val data = Widgets.live(first) { if (com.anas.secretary.util.Features.on(feat)) load() else null }
                Content(context, data) // null here means "not connected", which Content shows itself
            }
        }
    }

    @Composable
    private fun Content(ctx: Context, s: HealthSnap?) {
        val n = s?.lastNight
        if (n == null) { NotConnected(ctx); return }
        val diff = HealthSummary.lastVsAverage(s.nights)
        Column(GlanceModifier.widgetCard().clickable(openHealth(ctx))) {
            Text("🌙", style = TextStyle(fontSize = 20.sp))
            Text("Last night" + (n.score?.let { " · $it ${com.anas.secretary.logic.DayPlan.scoreWord(it)}" } ?: ""), maxLines = 1,
                style = TextStyle(color = WColors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.padding(top = 2.dp))
            Spacer(GlanceModifier.defaultWeight())
            Text(HealthSummary.dur(n.asleep), maxLines = 1, style = TextStyle(color = WColors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(6.dp))
            Image(ImageProvider(stageBitmap(ctx, n, 140, 8)), "Sleep stages", GlanceModifier.fillMaxWidth().height(8.dp))
            Spacer(GlanceModifier.height(6.dp))
            val (txt, color) = when {
                diff == null -> "" to WColors.sub
                diff <= -15 * 60_000 -> "${HealthSummary.dur(-diff)} under average" to ColorProvider(awakeC)
                diff >= 15 * 60_000 -> "${HealthSummary.dur(diff)} over average" to WColors.green
                else -> "About your usual" to WColors.sub
            }
            if (txt.isNotEmpty()) Text(txt, maxLines = 1, style = TextStyle(color = color, fontSize = 11.sp))
        }
    }
}

class SleepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SleepWidget()
}

/** "Today": steps, resting heart rate and last night's sleep in one small card. */
class HealthTodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val load = suspend { withContext(Dispatchers.IO) { Health.cached(context) } }
        val feat = Widgets.featureOf("HealthTodayWidget")!!
        // A part that's off loads nothing and shows a small "turn it on" card instead
        val first = if (com.anas.secretary.util.Features.on(feat)) load() else null
        provideContent {
            Widgets.Gated(context, feat) {
                val data = Widgets.live(first) { if (com.anas.secretary.util.Features.on(feat)) load() else null }
                Content(context, data) // null here means "not connected", which Content shows itself
            }
        }
    }

    @Composable
    private fun Content(ctx: Context, s: HealthSnap?) {
        if (s == null) { NotConnected(ctx); return }
        val steps = NumberFormat.getIntegerInstance(Locale.US).format(s.stepsToday)
        val ring = Widgets.ring(ctx, 44, 5f, (s.stepsToday / HealthSummary.STEP_GOAL.toFloat()).coerceIn(0f, 1f), Color(0xFF34C759))
        Column(GlanceModifier.widgetCard().clickable(openHealth(ctx))) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Today", style = TextStyle(color = WColors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.defaultWeight())
                Image(ImageProvider(ring), "Steps", GlanceModifier.size(30.dp))
            }
            Spacer(GlanceModifier.defaultWeight())
            Line(Color(0xFF34C759), "$steps steps")
            Line(Color(0xFFFF5A72), s.restingHr.lastOrNull()?.let { "${it.second} bpm resting" } ?: "No heart data")
            Line(deep, s.lastNight?.let { "${HealthSummary.dur(it.asleep)} sleep" } ?: "No sleep data")
        }
    }

    @Composable
    private fun Line(color: Color, text: String) {
        Row(GlanceModifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(8.dp).background(ColorProvider(color)).cornerRadius(4.dp)) {}
            Spacer(GlanceModifier.width(8.dp))
            Text(text, maxLines = 1, style = TextStyle(color = WColors.text, fontSize = 13.sp))
        }
    }
}

class HealthTodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthTodayWidget()
}
