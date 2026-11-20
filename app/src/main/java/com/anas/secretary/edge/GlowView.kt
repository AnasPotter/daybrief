package com.anas.secretary.edge

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The light strip on the edge of the screen. Tap it (or drag it inward) to peek.
 * Push it outward or long-press it to hide it, when [onHide] is set.
 */
@SuppressLint("ViewConstructor")
class GlowView(
    ctx: Context, private val edge: String, var color: Int, private val thickPx: Float,
    private val anim: String, private val onHide: (() -> Unit)? = null, private val onPeek: () -> Unit
) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val density = ctx.resources.displayMetrics.density
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = when (anim) { "pulse" -> 1200L; "comet" -> 1600L; "aurora" -> 3200L; "wave" -> 1800L; else -> 2400L }
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // needed for the soft glow around the strip
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    private fun strip(th: Float): RectF = when (edge) {
        "left" -> RectF(0f, 0f, th, height.toFloat())
        "top" -> RectF(0f, 0f, width.toFloat(), th)
        "bottom" -> RectF(0f, height - th, width.toFloat(), height.toFloat())
        else -> RectF(width - th, 0f, width.toFloat(), height.toFloat())
    }

    private fun wave01() = (0.5 - 0.5 * cos(2 * PI * t)).toFloat()

    override fun onDraw(canvas: Canvas) {
        var c = color
        var alpha: Float
        var th = thickPx
        when (anim) {
            "pulse" -> {
                val a = when {
                    t < .15f -> t / .15f
                    t < .3f -> 1 - (t - .15f) / .15f
                    t < .45f -> (t - .3f) / .15f
                    t < .6f -> 1 - (t - .45f) / .15f
                    else -> 0f
                }
                alpha = .3f + .7f * a
            }
            "wave" -> { val w = wave01(); th = thickPx * (.6f + .5f * w); alpha = .5f + .5f * w }
            "aurora" -> {
                val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
                hsv[0] = (hsv[0] + 40f * sin(2 * PI * t).toFloat() + 360f) % 360f
                c = Color.HSVToColor(hsv); alpha = .55f + .45f * wave01()
            }
            "comet" -> alpha = .3f
            else -> alpha = .35f + .65f * wave01()
        }
        val r = strip(th)
        val radius = th / 2
        paint.color = c
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        paint.setShadowLayer(thickPx * 2.2f, 0f, 0f, c)
        canvas.drawRoundRect(r, radius, radius, paint)
        if (anim == "comet") {
            val horizontal = edge == "top" || edge == "bottom"
            val len = if (horizontal) r.width() else r.height()
            val seg = len * .35f
            val start = -seg + (len + seg) * t
            val sr = if (horizontal) RectF(r.left + start, r.top, r.left + start + seg, r.bottom)
            else RectF(r.left, r.top + start, r.right, r.top + start + seg)
            sr.intersect(r)
            paint.alpha = 255
            canvas.drawRoundRect(sr, radius, radius, paint)
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var fired = false

    private val longPress = Runnable { if (!fired) { fired = true; hide() } }

    /** Fade and slide the strip off the edge, then hide it. */
    private fun hide() {
        val h = onHide ?: return
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        animate().alpha(0f).translationX(when (edge) { "left" -> -width / 2f; "right" -> width / 2f; else -> 0f })
            .translationY(when (edge) { "top" -> -height / 2f; "bottom" -> height / 2f; else -> 0f })
            .setDuration(220).withEndAction { h() }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; fired = false
                if (onHide != null) postDelayed(longPress, 600)
            }
            MotionEvent.ACTION_MOVE -> {
                val inward = when (edge) {
                    "left" -> e.rawX - downX
                    "top" -> e.rawY - downY
                    "bottom" -> downY - e.rawY
                    else -> downX - e.rawX
                }
                if (abs(e.rawX - downX) > 12 * density || abs(e.rawY - downY) > 12 * density) removeCallbacks(longPress)
                if (!fired && inward > 24 * density) { fired = true; onPeek() }
                // The strip sits at the very edge, so there's little room outward: a short push is enough
                if (!fired && onHide != null && inward < -8 * density) { fired = true; hide() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (e.actionMasked == MotionEvent.ACTION_UP && !fired &&
                    abs(e.rawX - downX) < 12 * density && abs(e.rawY - downY) < 12 * density) { fired = true; onPeek() }
            }
        }
        return true
    }
}
