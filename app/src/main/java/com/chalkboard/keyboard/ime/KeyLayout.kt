package com.chalkboard.keyboard.ime

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.chalkboard.keyboard.R

/**
 * Declarative description of a keyboard page. [KeyboardView] renders these; it holds no
 * layout knowledge of its own, so adding a page or a language is a data change.
 */

/** What a key does when it is released. */
sealed interface KeyAction {
    /** Commits [text] to the input connection. */
    data class Character(val text: String) : KeyAction

    data object Shift : KeyAction
    data object Backspace : KeyAction
    data object Space : KeyAction
    data object Enter : KeyAction

    /** Switches to the symbols page. */
    data object SwitchSymbols : KeyAction

    /** Switches back to the letters page. */
    data object SwitchLetters : KeyAction

    /** Switches to the second symbols page. */
    data object SwitchSymbolsAlt : KeyAction

    /** Hands off to the system input-method picker. */
    data object SwitchIme : KeyAction

    /** Swaps the key grid for the emoji panel. */
    data object Emoji : KeyAction

    // Grammar correction and the AI tools are not key actions. They live on the
    // suggestion bar (see SuggestionBar), because neither inserts a character and both
    // act on the whole field. The IME wires those buttons straight to its own handlers.
}

/**
 * One key.
 *
 * @param widthWeight share of the row's flexible width; a normal letter is 1f.
 * @param special true for shift/backspace/enter/symbols — drawn on `surface.keySpecial`.
 * @param repeatable true if holding the key should fire it repeatedly (backspace).
 * @param longPressText committed instead of [action] on a long press, e.g. the digit
 *        above a letter. Rendered as a hint in the key's top-right corner.
 * @param caption drawn instead of [label], small and muted — the space bar naming the
 *        active layout. A string resource rather than text so this table stays free of
 *        a [android.content.Context].
 */
data class Key(
    val action: KeyAction,
    val label: String? = null,
    @DrawableRes val icon: Int? = null,
    @StringRes val contentDescription: Int? = null,
    @StringRes val caption: Int? = null,
    val widthWeight: Float = 1f,
    val special: Boolean = false,
    val repeatable: Boolean = false,
    val longPressText: String? = null,
)

data class KeyRow(val keys: List<Key>)

data class KeyboardLayout(val rows: List<KeyRow>) {
    val rowCount: Int get() = rows.size
}

/** Which page the keyboard is currently showing. */
enum class KeyboardPage { LETTERS, SYMBOLS, SYMBOLS_ALT }

/** Shift is a three-state control: off, one-shot, locked. */
enum class ShiftState { OFF, SHIFTED, LOCKED }

object KeyLayouts {

    private fun letters(row: String, hints: String? = null): List<Key> =
        row.mapIndexed { i, c ->
            Key(
                action = KeyAction.Character(c.toString()),
                label = c.toString(),
                longPressText = hints?.getOrNull(i)?.toString(),
            )
        }

    private fun symbols(row: String): List<Key> =
        row.map { c -> Key(action = KeyAction.Character(c.toString()), label = c.toString()) }

    private val shiftKey = Key(
        action = KeyAction.Shift,
        icon = R.drawable.ic_shift,
        contentDescription = R.string.key_shift,
        widthWeight = 1.5f,
        special = true,
    )

    private val backspaceKey = Key(
        action = KeyAction.Backspace,
        icon = R.drawable.ic_backspace,
        contentDescription = R.string.key_backspace,
        widthWeight = 1.5f,
        special = true,
        repeatable = true,
    )

    private val enterKey = Key(
        action = KeyAction.Enter,
        icon = R.drawable.ic_enter,
        contentDescription = R.string.key_enter,
        widthWeight = 2f,
        special = true,
    )

    /**
     * The space bar carries the layout's name rather than sitting blank, which is both
     * the convention users expect and the only place the active language is visible
     * without leaving the keyboard.
     */
    private val spaceKey = Key(
        action = KeyAction.Space,
        contentDescription = R.string.key_space,
        caption = R.string.subtype_en,
        widthWeight = 4f,
    )

    private val commaKey = Key(KeyAction.Character(","), label = ",", longPressText = "!")
    private val periodKey = Key(KeyAction.Character("."), label = ".", longPressText = "?")

    private val emojiKey = Key(
        action = KeyAction.Emoji,
        icon = R.drawable.ic_emoji,
        contentDescription = R.string.key_emoji,
        special = true,
    )

    private val toSymbols = Key(
        action = KeyAction.SwitchSymbols,
        label = "123",
        contentDescription = R.string.key_symbols,
        widthWeight = 1.3f,
        special = true,
    )

    private val toLetters = Key(
        action = KeyAction.SwitchLetters,
        label = "ABC",
        contentDescription = R.string.key_letters,
        widthWeight = 1.3f,
        special = true,
    )

    /**
     * The bottom row, identical on every page.
     *
     * Grammar and the AI tools are deliberately *not* here. They act on the whole field
     * rather than inserting a character, so they live in the suggestion bar instead
     * (sections B3-B6), which leaves this row fixed: the same six keys in the same
     * places whatever else is switched on.
     */
    private fun bottomRow(modeKey: Key): List<Key> =
        listOf(modeKey, commaKey, emojiKey, spaceKey, periodKey, enterKey)

    /**
     * The letters page. Every letter carries a long-press symbol, drawn in its corner.
     *
     * With [numberRow] the digits get a row of their own, so the top letters carry the
     * brackets instead: between the hints and the first symbols page, nothing common
     * needs the second one.
     */
    fun qwerty(numberRow: Boolean = false): KeyboardLayout = KeyboardLayout(
        buildList {
            if (numberRow) add(KeyRow(symbols("1234567890")))
            add(
                KeyRow(
                    letters("qwertyuiop", hints = if (numberRow) "%\\|=[]<>{}" else "1234567890")
                )
            )
            add(
                KeyRow(
                    buildList {
                        // Half-key inset on the home row, as on a physical keyboard.
                        add(Key(KeyAction.Character(""), widthWeight = 0.5f).spacer())
                        addAll(letters("asdfghjkl", hints = "@#\$_&-+()"))
                        add(Key(KeyAction.Character(""), widthWeight = 0.5f).spacer())
                    }
                )
            )
            add(KeyRow(listOf(shiftKey) + letters("zxcvbnm", hints = "*\"':;!?") + backspaceKey))
            add(KeyRow(bottomRow(toSymbols)))
        }
    )

    fun symbolsPage(): KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(symbols("1234567890")),
            KeyRow(symbols("@#\$_&-+()/")),
            KeyRow(
                listOf(
                    Key(KeyAction.SwitchSymbolsAlt, label = "=\\<", widthWeight = 1.5f, special = true)
                ) + symbols("*\"':;!?") + backspaceKey
            ),
            KeyRow(bottomRow(toLetters)),
        )
    )

    fun symbolsAltPage(): KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(symbols("~`|•√π÷×<>")),
            KeyRow(symbols("£¢€¥^°={}")),
            KeyRow(
                listOf(
                    Key(KeyAction.SwitchSymbols, label = "123", widthWeight = 1.5f, special = true)
                ) + symbols("\\©®™%[]") + backspaceKey
            ),
            KeyRow(bottomRow(toLetters)),
        )
    )

    fun forPage(page: KeyboardPage, numberRow: Boolean = false): KeyboardLayout = when (page) {
        KeyboardPage.LETTERS -> qwerty(numberRow)
        KeyboardPage.SYMBOLS -> symbolsPage()
        KeyboardPage.SYMBOLS_ALT -> symbolsAltPage()
    }
}

/** Marks a key as non-interactive padding: it occupies width but never draws or fires. */
internal fun Key.spacer(): Key = copy(label = null, icon = null)

/** A spacer is a character key with nothing to commit. */
internal val Key.isSpacer: Boolean
    get() = action is KeyAction.Character && (action as KeyAction.Character).text.isEmpty()
