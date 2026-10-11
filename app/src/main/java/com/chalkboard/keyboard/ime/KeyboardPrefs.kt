package com.chalkboard.keyboard.ime

import android.content.Context
import com.chalkboard.keyboard.grammar.GrammarMode

/**
 * The keyboard's own non-secret settings. Written by the settings app and read by the
 * IME, which share a process, so a change is visible the next time the keyboard opens.
 */
class KeyboardPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var numberRow: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, true)
        set(value) = prefs.edit().putBoolean(KEY_NUMBER_ROW, value).apply()

    var grammarMode: GrammarMode
        get() = if (prefs.getString(KEY_GRAMMAR_MODE, null) == GrammarMode.FORMAL.name) {
            GrammarMode.FORMAL
        } else {
            GrammarMode.CASUAL
        }
        set(value) = prefs.edit().putString(KEY_GRAMMAR_MODE, value.name).apply()

    private companion object {
        const val PREFS_NAME = "keyboard"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_GRAMMAR_MODE = "grammar_mode"
    }
}
