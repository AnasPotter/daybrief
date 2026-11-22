package com.anas.secretary.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.anas.secretary.util.Prefs
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * The app's glass look (Settings → Look): bars, the scrolled header and overlays blur what is under them
 * (Haze: real blur on Android 12+, a tint below that), and cards let the background glow show through.
 * Level 0 = off (solid, like before), 1 light, 2 medium, 3 strong.
 */
object Glass {
    private val state = mutableIntStateOf(-1)

    var level: Int
        get() { if (state.intValue < 0) state.intValue = Prefs.appGlass; return state.intValue }
        set(v) { state.intValue = v; Prefs.appGlass = v }

    /** How solid cards stay over the background glow. */
    fun cardAlpha(level: Int) = when (level) { 0 -> 1f; 1 -> 0.85f; 2 -> 0.7f; else -> 0.55f }

    /** How much colour the frosted bars keep (the rest is the blurred content under them). */
    fun tintAlpha(level: Int) = when (level) { 0 -> 1f; 1 -> 0.78f; 2 -> 0.6f; else -> 0.42f }

    fun blur(level: Int) = when (level) { 1 -> 18.dp; 2 -> 26.dp; else -> 36.dp }
}

/** The content the main screen's bars and overlays blur (null outside the main screen). */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/** How solid cards are where they're drawn (1 = solid). Set by the main screen from [Glass.level]. */
val LocalCardAlpha = compositionLocalOf { 1f }

/** Card colour on the main pages: see-through when the glass look is on. */
@Composable
fun glassCard(): Color = LocalPalette.current.card.copy(alpha = LocalCardAlpha.current)

/**
 * Frosted background for a bar or overlay: blurs [state] under it when the glass look is on,
 * otherwise a plain [solid] background. [extra] adds colour on top, for overlays that hold text.
 */
@Composable
fun Modifier.frosted(state: HazeState?, solid: Color, tint: Color = solid, extra: Float = 0f): Modifier {
    val level = Glass.level
    if (state == null || level == 0) return this.background(solid)
    val p = LocalPalette.current
    val a = (Glass.tintAlpha(level) + extra).coerceIn(0f, 1f)
    return this.hazeEffect(
        state,
        HazeStyle(
            backgroundColor = p.bg,
            tints = listOf(HazeTint(tint.copy(alpha = a))),
            blurRadius = Glass.blur(level),
            noiseFactor = 0f,
            fallbackTint = HazeTint(tint.copy(alpha = 0.92f))
        )
    )
}
