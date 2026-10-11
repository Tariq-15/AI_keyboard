package com.chalkboard.keyboard.ui.theme

import android.content.Context
import android.provider.Settings
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Motion tokens, design spec section 1.4.
 *
 * The chalk-redraw correction sequence is composed from [CHALK_UNDERLINE_MS],
 * [CHALK_CROSSFADE_MS] and [CHALK_SETTLE_MS]; [CHALK_STAGGER_MS] offsets each
 * corrected word so multiple fixes read left-to-right instead of firing at once.
 */
object ChalkMotion {
    /** All ordinary state changes: key press, button press, suggestion swap. */
    const val STANDARD_MS = 150L

    /** Suggestion strip candidate swap. Cross-fade only — no slide, no bounce. */
    const val SUGGESTION_SWAP_MS = 100L

    /** Step 1: coral underline drawn left-to-right beneath the original word. */
    const val CHALK_UNDERLINE_MS = 150L

    /** Step 2: original text cross-fades to the correction. */
    const val CHALK_CROSSFADE_MS = 100L

    /** Step 3: underline shifts coral -> chalk yellow and fades out. */
    const val CHALK_SETTLE_MS = 200L

    /** ~450ms total for one corrected word. */
    const val CHALK_TOTAL_MS = CHALK_UNDERLINE_MS + CHALK_CROSSFADE_MS + CHALK_SETTLE_MS

    /** Offset between consecutive corrected words. */
    const val CHALK_STAGGER_MS = 80L

    val easeOut: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** Same curve for the View-based keyboard, which cannot use Compose easings. */
    val easeOutInterpolator: Interpolator = PathInterpolator(0f, 0f, 0.2f, 1f)

    /**
     * Honours the system "remove animations" setting. Hard requirement, section 1.4:
     * when this is true the chalk-redraw collapses to an instant text swap with no
     * underline animation at all — it is not merely shortened.
     */
    fun reducedMotion(context: Context): Boolean {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        return scale == 0f
    }

    /** Duration to actually use, given the current reduced-motion state. */
    fun duration(context: Context, millis: Long): Long =
        if (reducedMotion(context)) 0L else millis
}
