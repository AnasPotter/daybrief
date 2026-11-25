package com.anas.secretary.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The tab highlight's motion, run frame by frame like a small physics model (the approach of
 * liquid_glass_easy's animated nav bar, MIT License, (c) 2025 Ahmed Gamil):
 *
 * - A tap lifts the highlight at once: it grows past the bar on two under-damped springs (width and height),
 *   so it overshoots a little and rebounds, and stays lifted for the whole trip.
 * - The trip is its own, almost critically damped spring: smooth, no bounce at the end.
 * - Its drawn position is sampled every frame and differentiated twice: it stretches wide and flat as it
 *   launches and narrow and tall as it brakes, the same way for the whole trip.
 * - Near the end (92 %) the lift is let go, so it settles where it lands, dipping a touch under its size.
 * - Glass is a third, fast spring: the highlight reads as glass as soon as it's touched and turns back into a
 *   flat fill on landing.
 * - Under a finger it chases the finger smoothly.
 *
 * Positions are in tabs (0 = first tab). Everything is read while drawing, so a frame of motion never
 * recomposes the bar.
 */
@Stable
class PillMotion(private val scope: CoroutineScope, initial: Int) {
    var position by mutableFloatStateOf(initial.toFloat()); private set
    var liftX by mutableFloatStateOf(0f); private set
    var liftY by mutableFloatStateOf(0f); private set
    /** How much glass is on, 0..1. */
    var material by mutableFloatStateOf(0f); private set
    /** Squash: width × (1 + this), height × (1 − this). */
    var deviation by mutableFloatStateOf(0f); private set
    /** How much still reads as glass after landing, 1 lifted → 0 flat. */
    var glass by mutableFloatStateOf(0f); private set
    var isFlat by mutableStateOf(true); private set

    var index = initial; private set
    /** One tab in dp, which the squash is measured in. */
    var stepDp = 0f
    /** Android's "Remove animations": everything lands at once. */
    var reduceMotion = false

    private var travelPos = initial.toDouble(); private var travelVel = 0.0
    private var travelTarget = travelPos; private var travelFrom = travelPos
    private var travelActive = false
    private var lifted = false
    private var lxPos = 0.0; private var lxVel = 0.0
    private var lyPos = 0.0; private var lyVel = 0.0
    private var mPos = 0.0; private var mVel = 0.0
    private var dragging = false; private var dragTarget = 0.0; private var dragFollow = 0.0
    private var sign = 0.0; private var signEased = 0.0
    private var handover = 1.0
    private var squash = 0.0
    private val squashModel = Squash()
    private var running = false

    /** A tab was chosen: travel there, lifted. */
    fun animateTo(next: Int) {
        if (next == index) return
        if (reduceMotion) { snapTo(next); return }
        index = next
        travelActive = true
        lifted = true
        travelFrom = travelPos
        travelTarget = next.toDouble()
        sign = signOf(travelTarget - travelFrom)
        isFlat = false
        start()
    }

    /** Straight onto [target], at rest. */
    fun snapTo(target: Int) {
        index = target
        travelPos = target.toDouble(); travelTarget = travelPos; travelFrom = travelPos; travelVel = 0.0
        travelActive = false; dragging = false; lifted = false
        lxPos = 0.0; lxVel = 0.0; lyPos = 0.0; lyVel = 0.0; mPos = 0.0; mVel = 0.0
        handover = 1.0; squash = 0.0; squashModel.stop(); sign = 0.0; signEased = 0.0
        publish(); isFlat = true
    }

    /** A finger picked it up where it stands. */
    fun startDrag() {
        dragging = true
        travelActive = false
        lifted = !reduceMotion
        sign = 0.0
        dragFollow = travelPos; dragTarget = travelPos; travelVel = 0.0
        isFlat = false
        start()
    }

    fun dragTo(pos: Float) { if (dragging) dragTarget = pos.toDouble() }

    /** The finger let go: settle on [next], landing like a tap does. */
    fun release(next: Int) {
        if (!dragging) return
        val from = dragFollow
        dragging = false
        index = next
        if (reduceMotion) { snapTo(next); return }
        travelActive = true
        travelPos = from; travelVel = 0.0; travelFrom = from
        travelTarget = next.toDouble()
        sign = signOf(travelTarget - travelFrom)
        start()
    }

    private fun start() {
        if (running) return
        running = true
        scope.launch {
            var last = -1L
            try {
                while (true) {
                    val now = withFrameNanos { it }
                    // Real time between frames: a slow frame skips ahead instead of slowing the motion down
                    val dt = if (last < 0) 0.0 else (now - last) / 1e9
                    last = now
                    if (!tick(dt, now / 1e9)) break
                }
            } finally { running = false }
        }
    }

    private fun tick(dt: Double, now: Double): Boolean {
        var travelSettled = true
        if (travelActive) {
            val r = spring(travelPos, travelVel, travelTarget, dt, TRAVEL_K, TRAVEL_C)
            travelPos = r.first; travelVel = r.second
            travelSettled = abs(travelPos - travelTarget) < 0.003 && abs(travelVel) < 0.05
            if (travelSettled) { travelPos = travelTarget; travelVel = 0.0 }
        }
        if (dragging) dragFollow += if (reduceMotion) dragTarget - dragFollow else (dragTarget - dragFollow) * (1 - exp(-dt / FOLLOW_TAU))

        if (!dragging && (travelSettled || progress() >= HANDOVER_START)) lifted = false
        val lt = if (lifted) 1.0 else 0.0
        settle(lxPos, lxVel, lt, dt, LIFT_K, LIFT_CX).let { lxPos = it.first; lxVel = it.second }
        settle(lyPos, lyVel, lt, dt, LIFT_K, LIFT_CY).let { lyPos = it.first; lyVel = it.second }
        settle(mPos, mVel, lt, dt, GLASS_K, GLASS_C).let { mPos = it.first; mVel = it.second }
        val liftSettled = !lifted && lxPos == 0.0 && lyPos == 0.0 && mPos == 0.0
        if (travelActive && travelSettled && liftSettled && !dragging) travelActive = false

        val drawn = if (dragging) dragFollow else travelPos
        if (stepDp > 0f) {
            if (!squashModel.tracking) squashModel.start()
            squash = squashModel.track(drawn * stepDp, now, dt)
            if (signEased == 0.0) signEased = sign
            else if (signEased != sign) {
                signEased += (sign - signEased) * (1 - exp(-dt / SIGN_TAU))
                if (abs(sign - signEased) < 0.01) signEased = sign
            }
            // One way of deforming for the whole trip, keyed to where it's going
            if (signEased != 0.0) squash = squash * (1 - abs(signEased)) - signEased * abs(squash)
        }

        val ht = if (lifted) 0.0 else 1.0
        val tau = if (ht > handover) HANDOVER_TAU else GLASS_RETURN_TAU
        handover += (ht - handover) * (1 - exp(-dt / tau))
        if (abs(ht - handover) < 0.002) handover = ht

        val settled = !travelActive && !dragging && liftSettled && abs(squash) < 0.0005 && handover >= 1.0
        if (settled) { squashModel.stop(); squash = 0.0; sign = 0.0; signEased = 0.0 }
        publish()
        isFlat = settled
        return !settled
    }

    private fun publish() {
        position = (if (dragging) dragFollow else travelPos).toFloat()
        liftX = lxPos.toFloat(); liftY = lyPos.toFloat()
        material = mPos.coerceIn(0.0, 1.0).toFloat()
        deviation = squash.toFloat()
        glass = (1 - handover).coerceIn(0.0, 1.0).toFloat()
    }

    private fun progress(): Double {
        val span = abs(travelTarget - travelFrom)
        return if (span < 1e-6) 1.0 else (1 - abs(travelTarget - travelPos) / span).coerceIn(0.0, 1.0)
    }

    private fun signOf(d: Double) = if (abs(d) < 1e-6) 0.0 else sign(d)

    /** The size to draw this frame, between [rest] and [lifted], squashed. */
    fun liveSize(rest: Size, lifted: Size): Size {
        val w = rest.width + (lifted.width - rest.width) * liftX
        val h = rest.height + (lifted.height - rest.height) * liftY
        return Size(w * (1 + deviation), h * (1 - deviation))
    }

    private companion object {
        const val TRAVEL_K = 280.0; const val TRAVEL_C = 31.4
        // Damping ratio about 0.6 across and 0.7 down, so the width overshoots a shade further
        const val LIFT_K = 250.0; const val LIFT_CX = 19.0; const val LIFT_CY = 22.1
        const val GLASS_K = 1000.0; const val GLASS_C = 63.3
        const val HANDOVER_START = 0.92; const val HANDOVER_TAU = 0.09; const val GLASS_RETURN_TAU = 0.05
        const val FOLLOW_TAU = 0.05; const val SIGN_TAU = 0.25

        /** Semi-implicit Euler in 1/240 s steps, so a slow frame can't blow it up. */
        fun spring(x: Double, v: Double, target: Double, dt: Double, k: Double, c: Double): Pair<Double, Double> {
            var t = dt; var px = x; var pv = v
            while (t > 0) {
                val s = if (t > 1 / 240.0) 1 / 240.0 else t
                pv += (-k * (px - target) - c * pv) * s
                px += pv * s
                t -= s
            }
            return px to pv
        }

        fun settle(x: Double, v: Double, target: Double, dt: Double, k: Double, c: Double): Pair<Double, Double> {
            val r = spring(x, v, target, dt, k, c)
            return if (abs(r.first - target) < 0.0008 && abs(r.second) < 0.01) target to 0.0 else r
        }
    }

    /** Acceleration → deformation: position over a short window, differentiated twice, averaged, clamped, eased. */
    private class Squash {
        private val history = ArrayList<Pair<Double, Double>>()
        var tracking = false; private set
        private var value = 0.0
        fun start() { history.clear(); value = 0.0; tracking = true }
        fun stop() { tracking = false; history.clear(); value = 0.0 }
        fun track(x: Double, now: Double, dt: Double): Double {
            if (!tracking) return value
            history.add(x to now)
            history.removeAll { it.second < now - WINDOW }
            val raw = (accel() * SENSITIVITY).coerceIn(-MAX, MAX)
            value += (raw - value) * (dt / RESPONSE).coerceIn(0.0, 1.0)
            return value
        }
        private fun accel(): Double {
            if (history.size < 3) return 0.0
            val vel = ArrayList<Pair<Double, Double>>()
            for (i in 1 until history.size) {
                val d = history[i].second - history[i - 1].second
                if (d > 0) vel += (history[i].first - history[i - 1].first) / d to (history[i].second + history[i - 1].second) / 2
            }
            if (vel.size < 2) return 0.0
            var total = 0.0; var n = 0
            for (i in 1 until vel.size) {
                val d = vel[i].second - vel[i - 1].second
                if (d > 0) { total += (vel[i].first - vel[i - 1].first) / d; n++ }
            }
            return if (n == 0) 0.0 else total / n
        }
        companion object {
            const val WINDOW = 0.3; const val SENSITIVITY = 0.00007; const val MAX = 0.12; const val RESPONSE = 0.18
        }
    }
}

/**
 * Places a pill-sized child centred on [center] at [size] inside a parent of fixed size, so it can move and
 * grow past the parent's edges. Read during layout, so a frame of motion is only a relayout.
 */
fun Modifier.pillPlacement(center: () -> Offset, size: () -> Size): Modifier = layout { measurable, constraints ->
    val s = size()
    val w = s.width.roundToInt().coerceAtLeast(1)
    val h = s.height.roundToInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.minWidth, constraints.minHeight) {
        val c = center()
        placeable.place((c.x - w / 2f).roundToInt(), (c.y - h / 2f).roundToInt())
    }
}
