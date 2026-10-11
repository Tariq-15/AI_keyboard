package com.chalkboard.keyboard.ui

import android.content.Context
import androidx.annotation.StringRes

/**
 * One thing the interface needs to say, in the two parts the frame gallery draws
 * (sections D2, E1-E4): a title naming what happened, and a body saying what still
 * works or what to do about it.
 *
 * Both halves are required by section 1.5 — a bare "Grammar correction unavailable"
 * leaves the user to guess whether typing is broken too. Splitting the copy in the
 * model rather than at each call site is what keeps the keyboard's slate banner and
 * the settings screens' paper card saying the same words.
 */
data class Notice(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int = NONE,
    val severity: Severity = Severity.NEUTRAL,
    val titleArgs: List<String> = emptyList(),
    val bodyArgs: List<String> = emptyList(),
) {

    /**
     * How the notice is marked. Deliberately two values, not five: the only distinction
     * the user can act on is "something went wrong" versus "this is just how things are
     * right now", and each maps to exactly one rule colour per surface.
     */
    enum class Severity {
        /** Nothing is broken and there is nothing to fix here. Muted rule. */
        NEUTRAL,

        /** A tier failed. Coral rule on slate, state.error on paper. */
        FAILURE,
    }

    fun title(context: Context): String =
        context.getString(titleRes, *titleArgs.toTypedArray())

    fun body(context: Context): String? =
        if (bodyRes == NONE) null else context.getString(bodyRes, *bodyArgs.toTypedArray())

    companion object {
        /** [bodyRes] value meaning "title only" — used for transient progress lines. */
        const val NONE = 0
    }
}
