package com.chalkboard.keyboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.ai.ProviderRegistry
import com.chalkboard.keyboard.ai.SampleAnswers
import com.chalkboard.keyboard.ai.ToolOutput
import com.chalkboard.keyboard.ai.ToolRun
import com.chalkboard.keyboard.ai.ToolStore
import com.chalkboard.keyboard.grammar.GrammarMode
import com.chalkboard.keyboard.grammar.GrammarService
import com.chalkboard.keyboard.prediction.PredictionEngine
import com.chalkboard.keyboard.settings.SettingsActivity
import com.chalkboard.keyboard.ui.Notice
import com.chalkboard.keyboard.ui.components.CorrectionAnimation
import com.chalkboard.keyboard.ui.components.EmojiPanel
import com.chalkboard.keyboard.ui.components.StatusBanner
import com.chalkboard.keyboard.ui.components.SuggestionBar
import com.chalkboard.keyboard.ui.components.ToolsPanel
import com.chalkboard.keyboard.ui.theme.ChalkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The keyboard. Entry point declared in the manifest under BIND_INPUT_METHOD.
 *
 * Responsibilities are deliberately narrow: own the input view, translate [KeyAction]s
 * into [InputConnection] calls, and track shift/page state. Prediction (phase 2),
 * grammar correction (phase 3) and AI rewrite (phase 5) attach here later through the
 * suggestion strip and the two dedicated keys, which already exist in the layout.
 */
class KeyboardIME : InputMethodService() {

    private lateinit var rootView: LinearLayout
    private lateinit var keyboardView: KeyboardView
    private lateinit var suggestionBar: SuggestionBar
    private lateinit var correctionView: CorrectionAnimation
    private lateinit var bannerView: StatusBanner

    private var grammar: GrammarService? = null
    private var grammarJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var page = KeyboardPage.LETTERS
    private var shiftState = ShiftState.OFF

    /** Timestamp of the last shift tap, for double-tap-to-lock. */
    private var lastShiftTapMs = 0L

    private val words = WordTracker()
    private lateinit var predictions: PredictionEngine

    /**
     * Prediction runs off the main thread because it reads SQLite, and a keystroke must
     * never wait on disk. Results are posted back to [mainHandler]; a monotonically
     * increasing [predictionGeneration] discards results that arrive after the user has
     * already typed something newer.
     */
    private lateinit var predictionThread: HandlerThread
    private lateinit var predictionHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var predictionGeneration = 0

    private var providers: ProviderRegistry? = null
    private var toolJob: Job? = null

    private lateinit var toolsPanel: ToolsPanel
    private lateinit var emojiPanel: EmojiPanel
    private lateinit var prefs: KeyboardPrefs
    private lateinit var toolStore: ToolStore

    /** What stands in the key area. Exactly one is visible. */
    private enum class Surface { KEYS, TOOLS, EMOJI }

    /** The keys' laid-out height, remembered so a panel can stand in at the same size. */
    private var keysHeight = 0

    /**
     * The next scheduled change to the slot above the keys. There is only ever one: a
     * newer one replaces it, so a short notice's timer can never cut a later one short.
     */
    private var slotTimer: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        ChalkType.load(this)
        prefs = KeyboardPrefs(this)
        toolStore = ToolStore(this)

        predictionThread = HandlerThread("prediction").apply { start() }
        predictionHandler = Handler(predictionThread.looper)
        providers = ProviderRegistry.create(this)
        predictionHandler.post {
            predictions = PredictionEngine.create(this)
            predictions.maintenance()
            // The grammar service shares the prediction dictionary rather than mapping a
            // second copy: the on-device spell tier is built on the same vocabulary.
            val service = predictions.newGrammarService(this)
            mainHandler.post { grammar = service }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        predictionThread.quitSafely()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        suggestionBar = SuggestionBar(this).apply {
            strip.onCandidateSelected = ::commitCandidate
            onGrammarTap = ::fixGrammar
            onGrammarLongPress = ::toggleGrammarMode
            onToolsTap = ::toggleTools
        }

        correctionView = CorrectionAnimation(this).apply {
            visibility = View.GONE
            onSequenceFinished = ::onCorrectionsSettled
        }

        bannerView = StatusBanner(this).apply {
            visibility = View.GONE
            // Only an undo notice does anything when tapped; see undoReplacement.
            setOnClickListener { undoReplacement() }
        }

        keyboardView = KeyboardView(this).apply {
            onKeyAction = ::handleAction
            onLongPressText = ::commitText
        }

        toolsPanel = ToolsPanel(this).apply {
            visibility = View.GONE
            onClose = { show(Surface.KEYS) }
            onRun = ::runTool
            onInsert = { text ->
                commitText(text)
                show(Surface.KEYS)
            }
            onReplace = ::replaceField
            onCopy = ::copyToClipboard
            onOpenProviders = { openSettings(SettingsActivity.SCREEN_PROVIDERS) }
            onOpenAssistants = { openSettings(SettingsActivity.SCREEN_TOOLS) }
        }

        emojiPanel = EmojiPanel(this).apply {
            visibility = View.GONE
            onEmoji = ::commitText
            onBackspace = { handleAction(KeyAction.Backspace) }
            onClose = { show(Surface.KEYS) }
        }

        val edge = resources.getDimensionPixelSize(R.dimen.space_2)

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Flat, no elevation: the keyboard is slate, not a floating card (section 1.3).
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_slate))
            addView(
                suggestionBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            )
            // Correction panel and banner share the bar's slot: only one is ever visible.
            addView(
                correctionView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            )
            addView(
                bannerView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(edge, edge, edge, edge) }
            )
            addView(
                keyboardView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            )
            // The two panels share the keys' place; show() gives them the keys' height.
            addView(
                toolsPanel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            )
            addView(
                emojiPanel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            )
        }

        // The IME window draws edge to edge, so without this the bottom key row sits
        // underneath the navigation bar. That is not merely cosmetic: the system's
        // hide-keyboard affordance in that strip swallows touches, and it lands squarely
        // on the grammar key — tapping it dismissed the keyboard instead of correcting.
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(0, 0, 0, bottom)
            insets
        }

        applyLayout()
        return rootView
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        page = KeyboardPage.LETTERS
        shiftState = if (shouldAutoCapitalize(info)) ShiftState.SHIFTED else ShiftState.OFF
        keyboardView.enterLabel = enterLabelFor(info)
        show(Surface.KEYS)
        applyLayout()

        // Seed from whatever is already in the field so the first suggestion is not blind.
        words.resetFrom(currentInputConnection?.getTextBeforeCursor(SEED_LOOKBACK, 0))
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        toolJob?.cancel()
        slotTimer?.let(mainHandler::removeCallbacks)
        undo = null
        words.reset()
        suggestionBar.clear()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        // The caret moved for a reason other than typing (a tap, a paste, an autofill).
        // Anything the tracker believes about the partial word is now wrong.
        if (newSelStart != oldSelStart + 1 || newSelEnd != newSelStart) {
            words.resetFrom(currentInputConnection?.getTextBeforeCursor(SEED_LOOKBACK, 0))
            refreshSuggestions()
        }
    }

    /**
     * The key grid depends on which page is showing and on the number-row setting, and
     * on nothing else: connecting a provider never shifts a letter key out from under a
     * thumb.
     */
    private fun applyLayout() {
        val numberRow = prefs.numberRow
        keyboardView.minRows = KeyLayouts.qwerty(numberRow).rowCount
        keyboardView.layout = KeyLayouts.forPage(page, numberRow)
        keyboardView.shiftState = shiftState
        suggestionBar.grammarMode = prefs.grammarMode
    }

    /**
     * Puts the keys, the tools or the emoji in the key area. A panel takes the height
     * the keys had, so the window never resizes under the host app.
     */
    private fun show(surface: Surface) {
        if (keyboardView.visibility == View.VISIBLE && keyboardView.height > 0) {
            keysHeight = keyboardView.height
        }
        if (keysHeight > 0) {
            toolsPanel.layoutParams.height = keysHeight
            emojiPanel.layoutParams.height = keysHeight
        }
        keyboardView.visibility = if (surface == Surface.KEYS) View.VISIBLE else View.GONE
        toolsPanel.visibility = if (surface == Surface.TOOLS) View.VISIBLE else View.GONE
        emojiPanel.visibility = if (surface == Surface.EMOJI) View.VISIBLE else View.GONE
    }

    // --- Key handling -------------------------------------------------------------

    private fun handleAction(action: KeyAction) {
        when (action) {
            is KeyAction.Character -> {
                if (action.text.isEmpty()) return
                val text = if (shiftState == ShiftState.OFF) action.text else action.text.uppercase()
                commitText(text)
                // A one-shot shift is spent by the character it capitalised; a lock is not.
                if (shiftState == ShiftState.SHIFTED) setShift(ShiftState.OFF)
            }

            KeyAction.Space -> commitText(" ")

            KeyAction.Backspace -> {
                deleteBackward()
                words.onBackspace()
                refreshSuggestions()
            }

            KeyAction.Shift -> onShiftTapped()

            KeyAction.Enter -> onEnter()

            KeyAction.SwitchSymbols -> switchPage(KeyboardPage.SYMBOLS)
            KeyAction.SwitchSymbolsAlt -> switchPage(KeyboardPage.SYMBOLS_ALT)
            KeyAction.SwitchLetters -> switchPage(KeyboardPage.LETTERS)

            KeyAction.SwitchIme -> switchInputMethod()

            KeyAction.Emoji -> show(Surface.EMOJI)
        }
    }

    private fun onShiftTapped() {
        val now = System.currentTimeMillis()
        val isDoubleTap = now - lastShiftTapMs < DOUBLE_TAP_WINDOW_MS
        lastShiftTapMs = now

        setShift(
            when {
                isDoubleTap -> ShiftState.LOCKED
                shiftState == ShiftState.OFF -> ShiftState.SHIFTED
                else -> ShiftState.OFF
            }
        )
    }

    private fun setShift(state: ShiftState) {
        shiftState = state
        keyboardView.shiftState = state
    }

    private fun switchPage(next: KeyboardPage) {
        page = next
        applyLayout()
    }

    private fun onEnter() {
        val options = currentInputEditorInfo?.imeOptions
        if (options == null || options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            sendKeyEvent(KeyEvent.KEYCODE_ENTER)
            return
        }
        val editorAction = options and EditorInfo.IME_MASK_ACTION
        if (editorAction == EditorInfo.IME_ACTION_NONE || editorAction == EditorInfo.IME_ACTION_UNSPECIFIED) {
            sendKeyEvent(KeyEvent.KEYCODE_ENTER)
        } else {
            currentInputConnection?.performEditorAction(editorAction)
        }
    }

    // --- Grammar ------------------------------------------------------------------

    /**
     * Runs a check over the text in the field and plays the chalk-redraw over whatever
     * comes back. This is the only path in the app that can send text anywhere, and it
     * runs only from this key — never from a text-changed callback.
     */
    private fun fixGrammar() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(GRAMMAR_WINDOW, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(GRAMMAR_WINDOW, 0)?.toString().orEmpty()
        val text = before + after

        if (text.isBlank()) {
            showNotice(Notice(R.string.notice_grammar_empty_title))
            return
        }

        val service = grammar
        if (service == null) {
            showNotice(Notice(R.string.notice_grammar_checking_title))
            return
        }

        grammarJob?.cancel()
        showNotice(Notice(R.string.notice_grammar_checking_title))

        grammarJob = scope.launch {
            when (val outcome = service.check(text)) {
                is GrammarService.Outcome.Corrections -> {
                    pendingReplacement = TextWindow(before.length, after.length, text)
                    showCorrection(text, outcome)
                }

                is GrammarService.Outcome.Clean ->
                    showNotice(Notice(R.string.notice_grammar_clean_title))

                is GrammarService.Outcome.Failed ->
                    showNotice(GrammarService.noticeFor(this@KeyboardIME, outcome.error))
            }
        }
    }

    private fun showCorrection(text: String, outcome: GrammarService.Outcome.Corrections) {
        // "Checking…" is still counting down to put the bar back; it must not do that
        // halfway through the redraw.
        slotTimer?.let(mainHandler::removeCallbacks)
        suggestionBar.visibility = View.GONE
        bannerView.visibility = View.GONE
        correctionView.visibility = View.VISIBLE
        correctionView.play(text, outcome.corrections)
    }

    /** Called once the animation has settled: swap the field's text for the corrected one. */
    private fun onCorrectionsSettled(corrected: String) {
        val window = pendingReplacement ?: return
        pendingReplacement = null
        val undoable = replace(window, corrected)
        // Step 3 of the chalk redraw ends on the grammar key itself, in the same yellow
        // the underline fades to, so the sequence has a visible terminus once the panel
        // has gone (section B4).
        suggestionBar.markGrammarSettled()
        // The corrected sentence holds for a beat, then the same slot offers the way back.
        afterDelay(CORRECTION_HOLD_MS) {
            showNotice(
                Notice(R.string.notice_corrected_title, R.string.notice_undo_body),
                undoable,
            )
        }
    }

    /** Swaps the text [window] covers for [replacement] and returns how to take it back. */
    private fun replace(window: TextWindow, replacement: String): Undo {
        currentInputConnection?.apply {
            beginBatchEdit()
            deleteSurroundingText(window.before, window.after)
            commitText(replacement, 1)
            endBatchEdit()
        }
        words.resetFrom(replacement)
        return Undo(window.original, replacement)
    }

    /**
     * Puts back what a correction or a tool replaced.
     *
     * Only while the replacement is still exactly what sits before the caret. Once the
     * user has typed on, moved the caret or changed field, "undo" would delete text
     * that was never part of the replacement, so the offer quietly lapses instead.
     */
    private fun undoReplacement() {
        val last = undo ?: return
        undo = null
        val ic = currentInputConnection
        val current = ic?.getTextBeforeCursor(last.replacement.length, 0)?.toString()
        if (ic != null && current == last.replacement) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(last.replacement.length, 0)
            ic.commitText(last.original, 1)
            ic.endBatchEdit()
            words.resetFrom(last.original)
        }
        restoreBar()
    }

    /**
     * Shows [notice] in the slot above the keys. With [undoable], tapping the notice
     * takes that replacement back for as long as the notice is up.
     */
    private fun showNotice(notice: Notice, undoable: Undo? = null) {
        undo = undoable
        suggestionBar.visibility = View.GONE
        correctionView.visibility = View.GONE
        bannerView.visibility = View.VISIBLE
        bannerView.show(notice)
        afterDelay(holdFor(notice), ::restoreBar)
    }

    private fun afterDelay(millis: Long, action: () -> Unit) {
        slotTimer?.let(mainHandler::removeCallbacks)
        slotTimer = Runnable(action).also { mainHandler.postDelayed(it, millis) }
    }

    /**
     * A two-line failure needs longer on screen than "Checking…" does — and a notice
     * that is still there when the user looks up from the keys has failed at its one
     * job. Time it by how much there is to read.
     */
    private fun holdFor(notice: Notice): Long =
        if (notice.bodyRes == Notice.NONE) STATUS_HOLD_MS else NOTICE_HOLD_MS

    private fun restoreBar() {
        undo = null
        correctionView.visibility = View.GONE
        bannerView.visibility = View.GONE
        suggestionBar.visibility = View.VISIBLE
        refreshSuggestions()
    }

    /** The text around the caret that a replacement covers, and what it said. */
    private data class TextWindow(val before: Int, val after: Int, val original: String)

    private var pendingReplacement: TextWindow? = null

    /** A replacement that can still be taken back: what was there, and what went in. */
    private data class Undo(val original: String, val replacement: String)

    private var undo: Undo? = null

    private fun toggleGrammarMode() {
        val formal = prefs.grammarMode == GrammarMode.CASUAL
        prefs.grammarMode = if (formal) GrammarMode.FORMAL else GrammarMode.CASUAL
        suggestionBar.grammarMode = prefs.grammarMode
        showNotice(
            if (formal) {
                Notice(R.string.notice_mode_formal_title, R.string.grammar_mode_formal_body)
            } else {
                Notice(R.string.notice_mode_casual_title, R.string.grammar_mode_casual_body)
            }
        )
    }

    // --- AI tools -----------------------------------------------------------------

    private fun toggleTools() {
        if (toolsPanel.visibility == View.VISIBLE) {
            show(Surface.KEYS)
            return
        }
        toolsPanel.showGrid(
            assistants = toolStore.assistants(),
            extraLanguages = toolStore.languages(),
            connected = providers?.isRewriteAvailable() == true,
        )
        show(Surface.TOOLS)
    }

    /**
     * Runs one tool. Like the grammar key, only ever from an explicit tap.
     *
     * shortcut: answers are canned (SampleAnswers) so the panel can be reviewed with no
     * provider connected; send the field's text to ProviderRegistry here in stage 2.
     */
    private fun runTool(run: ToolRun) {
        if (run.output == ToolOutput.REPLACE && fieldWindow()?.original.isNullOrBlank()) {
            showNotice(Notice(R.string.notice_ai_empty_title))
            return
        }
        toolJob?.cancel()
        toolsPanel.showLoading(run)
        toolJob = scope.launch {
            delay(SAMPLE_DELAY_MS)
            toolsPanel.showResult(run, SampleAnswers.forRun(run))
        }
    }

    /** Swaps the field's text for a tool's answer, offers the undo, and returns to the keys. */
    private fun replaceField(replacement: String) {
        val window = fieldWindow() ?: return
        val undoable = replace(window, replacement)
        show(Surface.KEYS)
        showNotice(Notice(R.string.notice_replaced_title, R.string.notice_undo_body), undoable)
    }

    /** The text either side of the caret, within the same bound the grammar key uses. */
    private fun fieldWindow(): TextWindow? {
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(GRAMMAR_WINDOW, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(GRAMMAR_WINDOW, 0)?.toString().orEmpty()
        return TextWindow(before.length, after.length, before + after)
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.tools_title), text))
        showNotice(Notice(R.string.notice_copied_title))
    }

    private fun openSettings(screen: String) {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SCREEN, screen)
                // CLEAR_TASK so an already-open settings app restarts on the screen asked
                // for, rather than coming forward on whichever one it was left at.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    // --- Prediction ---------------------------------------------------------------

    /** Replaces the partial word with the tapped candidate and advances a space. */
    private fun commitCandidate(word: String) {
        val partial = words.current
        val ic = currentInputConnection ?: return
        val cased = when (shiftState) {
            ShiftState.OFF -> word
            ShiftState.SHIFTED -> word.replaceFirstChar { it.uppercase() }
            ShiftState.LOCKED -> word.uppercase()
        }

        ic.beginBatchEdit()
        if (partial.isNotEmpty()) ic.deleteSurroundingText(partial.length, 0)
        ic.commitText("$cased ", 1)
        ic.endBatchEdit()

        if (shiftState == ShiftState.SHIFTED) setShift(ShiftState.OFF)
        words.onSuggestionAccepted(word)?.let(::learn)
        refreshSuggestions()
    }

    /**
     * Recomputes the suggestion strip. Stale results are dropped by generation number
     * rather than cancelled, which keeps the typing path free of locks.
     */
    private fun refreshSuggestions() {
        val generation = ++predictionGeneration
        val context = words.context
        val prefix = words.current
        predictionHandler.post {
            val candidates = predictions.predict(context, prefix)
            mainHandler.post {
                if (generation == predictionGeneration) {
                    suggestionBar.setCandidates(candidates)
                }
            }
        }
    }

    private fun learn(completion: WordTracker.Completion) {
        predictionHandler.post { predictions.learn(completion.word, completion.previous) }
    }

    // --- Input connection helpers -------------------------------------------------

    private fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
        words.onTextCommitted(text)?.let(::learn)
        refreshSuggestions()
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
    }

    /** Hands off to the system picker so the user can leave for another keyboard. */
    private fun switchInputMethod() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.showInputMethodPicker()
    }

    private fun sendKeyEvent(keyCode: Int) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // --- EditorInfo interpretation ------------------------------------------------

    /** Enter reads "Go"/"Send"/"Done" where the field asks for it (section 2.6). */
    private fun enterLabelFor(info: EditorInfo): String? {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> getString(R.string.enter_go)
            EditorInfo.IME_ACTION_SEND -> getString(R.string.enter_send)
            EditorInfo.IME_ACTION_SEARCH -> getString(R.string.enter_search)
            EditorInfo.IME_ACTION_DONE -> getString(R.string.enter_done)
            EditorInfo.IME_ACTION_NEXT -> getString(R.string.enter_next)
            else -> null
        }
    }

    private fun shouldAutoCapitalize(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_CLASS
        if (variation != InputType.TYPE_CLASS_TEXT) return false
        val capFlags = info.inputType and (
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            )
        if (capFlags == 0) return false
        // Only capitalise when the field is genuinely empty at the caret.
        return currentInputConnection?.getTextBeforeCursor(1, 0).isNullOrEmpty()
    }

    private companion object {
        const val DOUBLE_TAP_WINDOW_MS = 300L

        /** Characters read back from the field to rebuild context. Two words is plenty. */
        const val SEED_LOOKBACK = 64

        /**
         * Characters on each side of the caret sent for checking. Bounded on purpose:
         * the user tapped a key to correct what they are writing, not to upload a
         * document, and this is the only text that ever leaves the device.
         */
        const val GRAMMAR_WINDOW = 600

        /** How long the corrected sentence stays visible before the bar returns. */
        const val CORRECTION_HOLD_MS = 900L

        /** A one-line progress or result line: "Checking…", "Nothing to correct". */
        const val STATUS_HOLD_MS = 2_000L

        /** A title-and-body notice, which is roughly twice as much to read. */
        const val NOTICE_HOLD_MS = 4_000L

        /** How long a sample answer pretends to think, so the waiting face can be seen. */
        const val SAMPLE_DELAY_MS = 700L
    }
}
