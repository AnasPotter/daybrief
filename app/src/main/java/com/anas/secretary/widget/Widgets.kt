package com.anas.secretary.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.unit.ColorProvider
import com.anas.secretary.ui.MainActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.anas.secretary.util.Bg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Colours shared by all widgets. Each one has a light and a dark version and follows the phone's mode. */
object WColors {
    /** The widget card; more see-through as Prefs.widgetGlass goes up. */
    val bg: ColorProvider get() {
        val a = when (com.anas.secretary.util.Prefs.widgetGlass) { 0 -> 1f; 1 -> 0.8f; 2 -> 0.6f; else -> 0.4f }
        return ColorProvider(day = Color.White.copy(alpha = a * 0.97f), night = Color(0xFF1C1C1E).copy(alpha = a * 0.93f))
    }
    val inner = ColorProvider(day = Color(0xFFF2F2F2), night = Color(0xFF2C2C2E))
    val text = ColorProvider(day = Color(0xFF111111), night = Color(0xFFF5F5F7))
    val sub = ColorProvider(day = Color(0xFF727272), night = Color(0xFF8E8E93))
    val line = ColorProvider(day = Color(0xFFE6E6E6), night = Color(0xFF3A3A3C))
    val accent = ColorProvider(day = Color(0xFF0A7CFF), night = Color(0xFF3E91FF))
    val white = ColorProvider(Color.White)
    val red = ColorProvider(day = Color(0xFFE5484D), night = Color(0xFFFF6B6B))
    val green = ColorProvider(day = Color(0xFF1E9E52), night = Color(0xFF34C759))

    val accentRaw = Color(0xFF3E91FF)
    val work = Color(0xFF7F77DD)
    val warn = Color(0xFFF2A516)
    val bad = Color(0xFFFF6B6B)
    /** Ring track: a see-through grey that looks right on both light and dark cards. */
    val track = Color(0x33888888)

    /** Background and text of a tracker state pill, by kind (good / bad / warning / neutral). */
    fun pill(kind: String): Pair<ColorProvider, ColorProvider> = when (kind) {
        "good" -> ColorProvider(day = Color(0xFFDDF5E6), night = Color(0xFF1F3A2C)) to ColorProvider(day = Color(0xFF126B35), night = Color(0xFFBFE8CF))
        "bad" -> ColorProvider(day = Color(0xFFFDE2E2), night = Color(0xFF4A1F22)) to ColorProvider(day = Color(0xFFA12A2A), night = Color(0xFFFFB3B3))
        "warning" -> ColorProvider(day = Color(0xFFFBEFD2), night = Color(0xFF3D3015)) to ColorProvider(day = Color(0xFF8A5A00), night = Color(0xFFF5CF7A))
        else -> inner to text
    }
}

/** The rounded card every widget sits on. */
fun GlanceModifier.widgetCard(pad: Int = 14) =
    fillMaxSize().background(WColors.bg).cornerRadius(26.dp).padding(pad.dp)

object Widgets {
    private var pending: Job? = null

    /** Redraw every widget. Called when app data changes; quick bursts are merged into one update. */
    fun refreshSoon(ctx: Context) {
        val app = ctx.applicationContext
        pending?.cancel()
        pending = Bg.scope.launch {
            delay(700)
            refreshNow(app)
        }
    }

    /** Bumped on every refresh. Widgets that are still running (a Glance "session") reload when it changes. */
    val tick = MutableStateFlow(0)

    /**
     * The widget's data, kept fresh. Glance keeps a widget's composition alive for a while after a tap,
     * and during that time it does not call provideGlance again, so loading only there would show old data.
     */
    @Composable
    fun <T> live(first: T, load: suspend () -> T): T {
        val v by tick.collectAsState()
        var data by remember { mutableStateOf(first) }
        var seen by remember { mutableIntStateOf(v) }
        LaunchedEffect(v) {
            if (v != seen) {
                seen = v
                data = withContext(Dispatchers.IO) { load() }
            }
        }
        return data
    }

    suspend fun refreshNow(ctx: Context) {
        tick.update { it + 1 }
        for (w in listOf(TrackWidget(), TodayWidget(), TasksWidget(), ThingWidget(), PeopleWidget(), SavedWidget(), QuickAddWidget(), SleepWidget(), HealthTodayWidget())) {
            try { w.updateAll(ctx) } catch (_: Exception) {}
        }
    }

    /**
     * Opens the app. [what] makes each intent unique, because Android merges
     * widget intents that differ only in their extras.
     */
    fun open(ctx: Context, what: String, extras: Intent.() -> Unit = {}): Intent =
        Intent(ctx, MainActivity::class.java)
            .setData(Uri.parse("secretary://widget/$what"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply(extras)

    // ---------- drawn pictures (widgets can't draw rings themselves) ----------

    private fun density(ctx: Context) = ctx.resources.displayMetrics.density

    /** A progress ring. [fraction] 0..1. */
    fun ring(ctx: Context, sizeDp: Int, strokeDp: Float, fraction: Float, color: Color): Bitmap {
        val d = density(ctx)
        val px = (sizeDp * d).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sw = strokeDp * d
        val r = RectF(sw / 2, sw / 2, px - sw / 2, px - sw / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        p.color = WColors.track.toArgb()
        c.drawArc(r, 0f, 360f, false, p)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            p.color = color.toArgb()
            c.drawArc(r, -90f, 360f * f, false, p)
        }
        return bmp
    }

    /** A round avatar (photo or initial on a colour) inside a goal ring. */
    fun avatar(ctx: Context, sizeDp: Int, name: String, photo: String?, fill: Color, fraction: Float, ringColor: Color): Bitmap {
        val d = density(ctx)
        val bmp = ring(ctx, sizeDp, 3f, fraction, ringColor)
        val c = Canvas(bmp)
        val px = bmp.width.toFloat()
        val inset = 5.5f * d
        val inner = RectF(inset, inset, px - inset, px - inset)
        val pic = photo?.let { path -> decode(path, inner.width().toInt()) }
        if (pic != null) {
            val shader = android.graphics.BitmapShader(pic, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            val scale = maxOf(inner.width() / pic.width, inner.height() / pic.height)
            val m = android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate(inner.left + (inner.width() - pic.width * scale) / 2, inner.top + (inner.height() - pic.height * scale) / 2)
            }
            shader.setLocalMatrix(m)
            c.drawOval(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        } else {
            c.drawOval(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() })
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = inner.height() * 0.42f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val letter = name.trim().take(1).uppercase().ifEmpty { "?" }
            c.drawText(letter, inner.centerX(), inner.centerY() - (t.descent() + t.ascent()) / 2, t)
        }
        return bmp
    }

    /** Loads a saved picture small enough for a widget (widgets have a tight memory limit). */
    fun decode(path: String, targetPx: Int): Bitmap? = try {
        val f = File(path)
        if (!f.exists()) null else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= targetPx && o.outHeight / (s * 2) >= targetPx) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (_: Exception) { null }
}
