package com.anas.secretary.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
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
import androidx.glance.text.TextStyle

/** One-tap buttons: new task, reminder, note, or save the link you copied. */
class QuickAddWidget : GlanceAppWidget() {
    private class Btn(val key: String, val icon: String, val label: String, val bgDay: Long, val bgNight: Long, val fgDay: Long, val fgNight: Long)

    private val buttons = listOf(
        Btn("task", "✓", "Task", 0xFFE3EFFF, 0xFF13284A, 0xFF0A5FD1, 0xFF8FBCFF),
        Btn("remind", "⏰", "Remind", 0xFFFBEFD2, 0xFF3D3015, 0xFF8A5A00, 0xFFF5CF7A),
        Btn("note", "✎", "Note", 0xFFEAE7FB, 0xFF2A2350, 0xFF5B52C9, 0xFFB9B2F2),
        Btn("paste", "🔗", "Paste link", 0xFFDDF5E6, 0xFF1F3A2C, 0xFF126B35, 0xFFBFE8CF),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content(context) }
    }

    @Composable
    private fun Content(ctx: Context) {
        Row(GlanceModifier.widgetCard(8), verticalAlignment = Alignment.CenterVertically) {
            buttons.forEachIndexed { i, b ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                Column(
                    GlanceModifier.defaultWeight().fillMaxHeight().background(WColors.inner).cornerRadius(18.dp)
                        .clickable(actionStartActivity(Widgets.open(ctx, "quick/${b.key}") { putExtra("quick", b.key) }))
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        GlanceModifier.size(30.dp).cornerRadius(10.dp)
                            .background(ColorProvider(day = Color(b.bgDay), night = Color(b.bgNight))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(b.icon, style = TextStyle(color = ColorProvider(day = Color(b.fgDay), night = Color(b.fgNight)), fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    Text(b.label, maxLines = 1, style = TextStyle(color = WColors.text, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}
