package com.chalkboard.keyboard.ui.components

import android.graphics.Typeface
import android.os.Build

/**
 * Numeric font weights for the View-rendered keyboard, which cannot read the Compose
 * type scale in `ui/theme/Type.kt`.
 *
 * The design calls for 400/500/600 by number. `Typeface.create(family, weight, italic)`
 * expresses that exactly, but only from API 28; below it the closest the platform offers
 * are the named families, so 600 lands on "sans-serif-medium" (500). One step light on
 * older devices is a fair trade for the ranks staying distinguishable everywhere.
 */
internal object ChalkTypefaces {

    const val REGULAR = 400
    const val MEDIUM = 500
    const val SEMI_BOLD = 600

    fun weighted(weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.SANS_SERIF, weight, false)
        } else {
            Typeface.create(
                if (weight >= MEDIUM) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL,
            )
        }
}
