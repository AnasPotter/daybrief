package com.anas.secretary.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.anas.secretary.R
import com.anas.secretary.data.Repo
import com.anas.secretary.logic.LinkPreview
import com.anas.secretary.ui.SavedGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Tile(val id: Long, val url: String?, val title: String?, val group: String, val pic: Bitmap?)

/** The newest things you saved, as picture tiles. Tap one to open it. */
class SavedWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val load = suspend {
            withContext(Dispatchers.IO) {
                Repo.saved().take(4).map { s ->
                    Tile(
                        s.id, s.url, s.title?.takeUnless { LinkPreview.isGeneric(it) } ?: s.note.ifBlank { null },
                        SavedGroups.name(s), s.image?.let { Widgets.decode(it, 320) }
                    )
                }
            }
        }
        val first = load()
        provideContent { Content(context, Widgets.live(first, load)) }
    }

    @Composable
    private fun Content(ctx: Context, tiles: List<Tile>) {
        val size = LocalSize.current
        val count = if (size.width.value < 230) 2 else if (size.width.value < 330) 3 else 4
        Column(GlanceModifier.widgetCard(12)) {
            Row(
                GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp)
                    .clickable(actionStartActivity(Widgets.open(ctx, "saved") { putExtra("tab", 3) })),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Saved", style = TextStyle(color = WColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Text("  latest", style = TextStyle(color = WColors.sub, fontSize = 12.sp))
            }
            if (tiles.isEmpty()) {
                Text("Share a link to Daybrief to keep it here", style = TextStyle(color = WColors.sub, fontSize = 13.sp), modifier = GlanceModifier.padding(4.dp))
                return@Column
            }
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                tiles.take(count).forEachIndexed { i, t ->
                    if (i > 0) Spacer(GlanceModifier.width(7.dp))
                    val open = if (t.url != null)
                        actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else actionStartActivity(Widgets.open(ctx, "saved/${t.id}") { putExtra("tab", 3) })
                    TileView(t, GlanceModifier.defaultWeight().fillMaxHeight().cornerRadius(14.dp).clickable(open))
                }
            }
        }
    }

    @Composable
    private fun TileView(t: Tile, modifier: GlanceModifier) {
        val c = SavedGroups.color(t.group)
        Box(modifier.background(ColorProvider(c.copy(alpha = 0.25f)))) {
            if (t.pic != null) {
                Image(ImageProvider(t.pic), t.title, GlanceModifier.fillMaxSize().cornerRadius(14.dp), contentScale = ContentScale.Crop)
            } else {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(SavedGroups.letters(t.group), style = TextStyle(color = ColorProvider(c), fontSize = 18.sp, fontWeight = FontWeight.Bold))
                }
            }
            // App badge, top corner
            Box(GlanceModifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.TopStart) {
                Box(GlanceModifier.size(20.dp).background(ColorProvider(c)).cornerRadius(6.dp), contentAlignment = Alignment.Center) {
                    Text(
                        SavedGroups.letters(t.group),
                        style = TextStyle(color = ColorProvider(if (c.luminance() > 0.5f) Color.Black else Color.White), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }
            // Caption on a dark fade at the bottom
            if (t.title != null) {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                    Box(GlanceModifier.fillMaxWidth().background(ImageProvider(R.drawable.w_scrim)).cornerRadius(14.dp).padding(start = 7.dp, end = 7.dp, top = 16.dp, bottom = 6.dp)) {
                        Text(t.title, maxLines = 2, style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp))
                    }
                }
            }
        }
    }
}

class SavedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SavedWidget()
}
