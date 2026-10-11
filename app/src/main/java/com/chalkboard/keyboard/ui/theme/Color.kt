package com.chalkboard.keyboard.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour tokens, design spec section 1.1 — "chalkboard correction".
 *
 * These are the only colours in the app. If a screen needs a colour that is not
 * here, the answer is to reuse one of these, not to add a new hex value.
 * The View-based keyboard reads the identical values from res/values/colors.xml;
 * the two files must be changed together.
 */
object ChalkColors {
    /** Keyboard background. The slate. */
    val bgSlate = Color(0xFF232B2B)

    /** Key backgrounds, default state. */
    val surfaceKey = Color(0xFF34403F)

    /** Key background while pressed. */
    val surfaceKeyPressed = Color(0xFF3E4C4B)

    /** Shift / backspace / enter / symbols keys. */
    val surfaceKeySpecial = Color(0xFF2C3736)

    /** Primary text on dark surfaces. */
    val textChalk = Color(0xFFF5F3EA)

    /** Secondary and hint text on dark surfaces. */
    val textChalkMuted = Color(0xFFA9B0AC)

    /** Correction highlight, active suggestion, primary CTA. */
    val accentChalkYellow = Color(0xFFE8C468)

    /** AI-feature indicator and success states. */
    val accentDustyTeal = Color(0xFF5B9279)

    /**
     * The mark that something is wrong, on slate: the grammar-error underline, and the
     * rule down the left edge of a failure banner in the keyboard. Never decorative,
     * and never the only carrier of the message — the banner always says it in words.
     */
    val accentSoftCoral = Color(0xFFE2725B)

    /** Settings and onboarding background. The paper. */
    val surfacePaper = Color(0xFFF4F1EA)

    /** Cards and inputs on paper surfaces. */
    val surfacePaperCard = Color(0xFFFFFFFF)

    /** Primary text on paper surfaces. */
    val textInk = Color(0xFF2B2B28)

    /** Secondary text on paper surfaces. */
    val textInkMuted = Color(0xFF6B685F)

    /** 1px dividers on paper surfaces. */
    val borderHairline = Color(0xFFDAD5C8)

    /** Form validation errors. Settings only — never appears in the keyboard. */
    val stateError = Color(0xFFC0392B)
}
