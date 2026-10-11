package com.chalkboard.keyboard.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.ai.AiTool
import com.chalkboard.keyboard.ai.CustomAssistant
import com.chalkboard.keyboard.ai.ToolChoice
import com.chalkboard.keyboard.ai.ToolOutput
import com.chalkboard.keyboard.ai.ToolRun
import com.chalkboard.keyboard.ui.Notice

/**
 * The AI tools, in the space the keys normally occupy.
 *
 * One panel with four faces — the tool grid, a tone or language choice, a wait, and a
 * result — swapped in place under a header whose back arrow always undoes exactly one
 * step. It stands where the keys stood and at the same height, so opening it never
 * resizes the host app's window.
 *
 * Built from ordinary views rather than drawn on a canvas like the key grid: nothing
 * here redraws per keystroke, and real buttons get focus, ripple and TalkBack for free.
 *
 * A result never reaches the text field by itself. Every card ends in an explicit
 * Insert or Replace, because this is text a model wrote, not text the user typed.
 */
class ToolsPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Back from the grid: the IME puts the keys back. */
    var onClose: (() -> Unit)? = null
    var onRun: ((ToolRun) -> Unit)? = null

    /** Adds the answer at the caret, leaving what is already in the field alone. */
    var onInsert: ((String) -> Unit)? = null

    /** Swaps the field's text for the answer. */
    var onReplace: ((String) -> Unit)? = null
    var onCopy: ((String) -> Unit)? = null
    var onOpenProviders: (() -> Unit)? = null
    var onOpenAssistants: (() -> Unit)? = null

    private val touchTarget = resources.getDimensionPixelSize(R.dimen.min_touch_target)
    private val space1 = resources.getDimensionPixelSize(R.dimen.space_1)
    private val space2 = resources.getDimensionPixelSize(R.dimen.space_2)
    private val space3 = resources.getDimensionPixelSize(R.dimen.space_3)
    private val space4 = resources.getDimensionPixelSize(R.dimen.space_4)
    private val keyRadius = resources.getDimension(R.dimen.radius_key)

    private val colorSlate = ContextCompat.getColor(context, R.color.bg_slate)
    private val colorKey = ContextCompat.getColor(context, R.color.surface_key)
    private val colorKeySpecial = ContextCompat.getColor(context, R.color.surface_key_special)
    private val colorPressed = ContextCompat.getColor(context, R.color.surface_key_pressed)
    private val colorChalk = ContextCompat.getColor(context, R.color.text_chalk)
    private val colorChalkMuted = ContextCompat.getColor(context, R.color.text_chalk_muted)
    private val colorAccent = ContextCompat.getColor(context, R.color.accent_chalk_yellow)

    private val titleSize = resources.getDimension(R.dimen.suggestion_word_size)
    private val captionSize = resources.getDimension(R.dimen.banner_body_size)
    private val bodySize = resources.getDimension(R.dimen.correction_text_size)

    private val titleView = text(titleSize, colorChalk, ChalkTypefaces.SEMI_BOLD).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Marks an answer that did not come from a model. Hidden on every other face. */
    private val tagView = text(captionSize, colorChalkMuted, ChalkTypefaces.MEDIUM).apply {
        setText(R.string.tool_sample_tag)
        letterSpacing = 0.08f
    }

    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        addView(content)
    }
    private val actions = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private var backAction: () -> Unit = {}

    private var assistants: List<CustomAssistant> = emptyList()
    private var extraLanguages: List<String> = emptyList()
    private var connected = false

    init {
        orientation = VERTICAL
        setBackgroundColor(colorSlate)
        setPadding(space2, 0, space2, space2)

        val back = ImageButton(context).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = context.getString(R.string.nav_back)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(colorChalk)
            background = ripple(oval(colorSlate))
            val inset = (touchTarget - resources.getDimensionPixelSize(R.dimen.action_key_icon_size)) / 2
            setPadding(inset, inset, inset, inset)
            setOnClickListener { backAction() }
        }

        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(back, LayoutParams(touchTarget, touchTarget))
                addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = space1 })
                addView(tagView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = space2 })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, touchTarget),
        )
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(
            actions,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = space2 },
        )
    }

    // --- Faces --------------------------------------------------------------------

    /**
     * The tool grid. [connected] false adds the notice that no provider is set up yet,
     * above tools that stay tappable: the grid is how the user learns what a key buys.
     */
    fun showGrid(assistants: List<CustomAssistant>, extraLanguages: List<String>, connected: Boolean) {
        this.assistants = assistants
        this.extraLanguages = extraLanguages
        this.connected = connected
        renderGrid()
    }

    private fun renderGrid() {
        face(context.getString(R.string.tools_title), onBack = { onClose?.invoke() })

        // One line, not the two-part notice: the panel is only as tall as the keys, and
        // every line spent here pushes a tool below the fold.
        if (!connected) {
            content.addView(
                StatusBanner(context).apply {
                    show(Notice(R.string.notice_ai_none_compact))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onOpenProviders?.invoke() }
                },
                below(0),
            )
        }

        AiTool.entries.chunked(COLUMNS).forEach { tools ->
            content.addView(
                rowOf(
                    tools.map { tool ->
                        tile(context.getString(tool.labelRes), context.getString(tool.captionRes)) {
                            open(tool)
                        }
                    }
                ),
                below(),
            )
        }

        // The user's own assistants, then the way to make another.
        content.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        assistants.forEach { assistant ->
                            addView(
                                chip(assistant.name) {
                                    onRun?.invoke(
                                        ToolRun(
                                            title = assistant.name,
                                            output = ToolOutput.REPLACE,
                                            detail = assistant.instruction,
                                        )
                                    )
                                },
                                LayoutParams(LayoutParams.WRAP_CONTENT, touchTarget).apply { marginEnd = space2 },
                            )
                        }
                        addView(
                            chip(context.getString(R.string.tools_assistants_add)) { onOpenAssistants?.invoke() },
                            LayoutParams(LayoutParams.WRAP_CONTENT, touchTarget),
                        )
                    }
                )
            },
            below(),
        )
    }

    private fun open(tool: AiTool) {
        val title = context.getString(tool.labelRes)
        val options = when (tool.choice) {
            ToolChoice.NONE -> return start(ToolRun(title, tool.output, tool))
            ToolChoice.TONE -> resources.getStringArray(R.array.tool_tones).toList()
            ToolChoice.LANGUAGE ->
                resources.getStringArray(R.array.tool_languages).toList() + extraLanguages
        }

        face(title, onBack = ::renderGrid)
        options.chunked(COLUMNS).forEach { row ->
            content.addView(
                rowOf(
                    row.map { option ->
                        chip(option) {
                            start(
                                ToolRun(
                                    title = context.getString(R.string.tool_title_with_choice, title, option),
                                    output = tool.output,
                                    tool = tool,
                                    detail = option,
                                )
                            )
                        }
                    }
                ),
                below(),
            )
        }
    }

    private fun start(run: ToolRun) {
        onRun?.invoke(run)
    }

    fun showLoading(run: ToolRun) {
        face(run.title, onBack = ::renderGrid)
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(
            ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(colorChalkMuted) },
            LayoutParams(touchTarget, touchTarget).apply { topMargin = space4 },
        )
        content.addView(
            text(captionSize, colorChalkMuted).apply { setText(R.string.tool_working) },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = space2 },
        )
        announceForAccessibility(context.getString(R.string.tool_working))
    }

    fun showResult(run: ToolRun, answers: List<String>) {
        face(run.title, onBack = ::renderGrid, sample = true)

        if (run.output == ToolOutput.REPLIES) {
            answers.forEach { answer ->
                content.addView(
                    text(bodySize, colorChalk).apply {
                        text = answer
                        background = ripple(rounded(colorKey))
                        setPadding(space3, space2, space3, space2)
                        minimumHeight = touchTarget
                        gravity = Gravity.CENTER_VERTICAL
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { onInsert?.invoke(answer) }
                    },
                    below(),
                )
            }
            content.addView(note(R.string.tool_reply_hint), below())
        } else {
            content.addView(
                text(bodySize, colorChalk).apply {
                    text = answers.firstOrNull().orEmpty()
                    setLineSpacing(0f, 1.25f)
                    setPadding(space2, space2, space2, 0)
                },
                below(),
            )
        }
        content.addView(note(R.string.tool_sample_note), below())

        val answer = answers.firstOrNull().orEmpty()
        if (run.output != ToolOutput.REPLIES) {
            actions.addView(pill(R.string.tool_copy) { onCopy?.invoke(answer) })
        }
        actions.addView(
            pill(R.string.tool_retry) { start(run) },
            LayoutParams(LayoutParams.WRAP_CONTENT, touchTarget).apply { marginStart = space2 },
        )
        actions.addView(Space(context), LayoutParams(0, 0, 1f))
        when (run.output) {
            ToolOutput.SHOW ->
                actions.addView(pill(R.string.tool_insert, primary = true) { onInsert?.invoke(answer) })

            ToolOutput.REPLACE ->
                actions.addView(pill(R.string.tool_replace, primary = true) { onReplace?.invoke(answer) })

            ToolOutput.REPLIES -> Unit
        }

        announceForAccessibility(answers.joinToString(separator = ". "))
    }

    /** Clears the panel down to its header, ready for one face's content. */
    private fun face(title: String, onBack: () -> Unit, sample: Boolean = false) {
        titleView.text = title
        tagView.visibility = if (sample) View.VISIBLE else View.GONE
        backAction = onBack
        content.removeAllViews()
        content.gravity = Gravity.NO_GRAVITY
        actions.removeAllViews()
        scroll.scrollTo(0, 0)
    }

    // --- Pieces -------------------------------------------------------------------

    /** A row of equal columns. A short last row is padded so its cells keep their width. */
    private fun rowOf(cells: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        repeat(COLUMNS) { index ->
            addView(
                cells.getOrNull(index) ?: Space(context),
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index > 0) marginStart = space2
                },
            )
        }
    }

    private fun tile(title: String, caption: String, onTap: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(rounded(colorKey))
            setPadding(space3, space1, space3, space1)
            minimumHeight = touchTarget
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $caption"
            addView(
                text(titleSize, colorChalk, ChalkTypefaces.MEDIUM).apply {
                    text = title
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
            addView(
                text(captionSize, colorChalkMuted).apply {
                    text = caption
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
            setOnClickListener { onTap() }
        }

    private fun chip(label: String, onTap: () -> Unit): Button = button(label, colorKey, colorChalk, keyRadius, onTap)

    private fun pill(@StringRes label: Int, primary: Boolean = false, onTap: () -> Unit): Button =
        button(
            label = context.getString(label),
            fill = if (primary) colorAccent else colorKeySpecial,
            textColor = if (primary) colorSlate else colorChalk,
            radius = touchTarget / 2f,
            onTap = onTap,
        )

    private fun button(label: String, fill: Int, textColor: Int, radius: Float, onTap: () -> Unit) =
        Button(context).apply {
            text = label
            isAllCaps = false
            stateListAnimator = null
            minHeight = touchTarget
            minimumHeight = touchTarget
            minWidth = 0
            minimumWidth = 0
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(space4, 0, space4, 0)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize)
            typeface = ChalkTypefaces.weighted(ChalkTypefaces.MEDIUM)
            background = ripple(rounded(fill, radius))
            setOnClickListener { onTap() }
        }

    private fun note(@StringRes textRes: Int): TextView =
        text(captionSize, colorChalkMuted).apply {
            setText(textRes)
            setPadding(space2, 0, space2, 0)
        }

    private fun text(sizePx: Float, color: Int, weight: Int = ChalkTypefaces.REGULAR) =
        TextView(context).apply {
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
            typeface = ChalkTypefaces.weighted(weight)
        }

    private fun below(gap: Int = space2) =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap }

    private fun rounded(fill: Int, radius: Float = keyRadius) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
    }

    private fun oval(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    /** The shape doubles as the ripple's mask, so the press never spills past the corners. */
    private fun ripple(shape: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(colorPressed), shape, shape.constantState?.newDrawable())

    private companion object {
        const val COLUMNS = 3
    }
}
