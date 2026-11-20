package com.anas.secretary.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.anas.secretary.util.Prefs

/**
 * The app's few kinds of vibration, One UI style: a tiny tick for switching, a crisp confirm,
 * a soft double tap for "no", and a firm long-press. performHapticFeedback already follows
 * the phone's Touch feedback setting; [Prefs.haptics] turns them off in the app only.
 */
object Feel {
    private fun play(v: View, c: Int) { if (Prefs.haptics) v.performHapticFeedback(c) }

    fun tick(v: View) = play(v, if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun confirm(v: View) = play(v, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun reject(v: View) = play(v, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    fun longPress(v: View) = play(v, HapticFeedbackConstants.LONG_PRESS)
}
