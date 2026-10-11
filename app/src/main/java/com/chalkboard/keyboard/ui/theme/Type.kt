package com.chalkboard.keyboard.ui.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale, design spec section 1.2.
 *
 * Two faces, with strict roles:
 *  - [inter] carries every piece of functional text: settings, onboarding body copy,
 *    the suggestion strip, key labels. This is 95% of the app.
 *  - [chalk] (Caveat/Kalam) is the signature face. Onboarding headline, empty states,
 *    28sp and up only. Never body copy, never buttons, never inside the keyboard.
 *
 * Font binaries are resolved by resource name at startup rather than compiled against
 * `R.font.*`, so the project builds before the TTFs are vendored and picks them up with
 * no code change once they are. Drop these files into `app/src/main/res/font/`:
 *
 *   inter_regular.ttf, inter_medium.ttf, inter_semibold.ttf, caveat_semibold.ttf
 *
 * Until then both families fall back to the platform sans/cursive. See README.
 */
object ChalkType {

    private var interFamily: FontFamily = FontFamily.SansSerif
    private var chalkFamily: FontFamily = FontFamily.Cursive

    val inter: FontFamily get() = interFamily
    val chalk: FontFamily get() = chalkFamily

    /**
     * Binds the bundled font files if they are present. Safe to call more than once;
     * call it from [android.app.Application.onCreate] and from the IME's `onCreate`,
     * since the keyboard process may start without the settings UI ever running.
     */
    fun load(context: Context) {
        val regular = fontId(context, "inter_regular")
        val medium = fontId(context, "inter_medium")
        val semibold = fontId(context, "inter_semibold")
        if (regular != 0 && medium != 0 && semibold != 0) {
            interFamily = FontFamily(
                Font(regular, FontWeight.Normal),
                Font(medium, FontWeight.Medium),
                Font(semibold, FontWeight.SemiBold),
            )
        }

        val caveat = fontId(context, "caveat_semibold")
        if (caveat != 0) {
            chalkFamily = FontFamily(Font(caveat, FontWeight.SemiBold))
        }
    }

    @Suppress("DiscouragedApi") // Name lookup is deliberate: see the class docs.
    private fun fontId(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "font", context.packageName)

    /** 32/40, signature face. Onboarding headline and empty states only. */
    val display: TextStyle
        get() = TextStyle(
            fontFamily = chalk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
        )

    /** 22/28. */
    val title: TextStyle
        get() = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        )

    /** 16/24. */
    val body: TextStyle
        get() = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )

    /** 14/20. */
    val label: TextStyle
        get() = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )

    /** 12/16. */
    val caption: TextStyle
        get() = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )

    /** 18/18. In-keyboard key labels. */
    val keyLabel: TextStyle
        get() = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            lineHeight = 18.sp,
        )

    /** 15/20. Suggestion strip candidates. */
    val suggestion: TextStyle
        get() = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        )

    /** 15/20 at weight 600. The most-likely candidate in the suggestion strip. */
    val suggestionTop: TextStyle get() = suggestion.copy(fontWeight = FontWeight.SemiBold)
}

/** Material3 mapping, so stray default-styled components still land on the scale. */
val chalkboardTypography: Typography
    get() = Typography(
        displayMedium = ChalkType.display,
        titleLarge = ChalkType.title,
        bodyLarge = ChalkType.body,
        labelLarge = ChalkType.label,
        bodySmall = ChalkType.caption,
    )
