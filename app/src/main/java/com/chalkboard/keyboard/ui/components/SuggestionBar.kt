package com.chalkboard.keyboard.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.grammar.GrammarMode

/**
 * The row above the keys: candidates in a rounded strip, with the grammar and AI tools
 * keys as circles beside it (sections B1-B6).
 *
 * They sit here rather than in the key grid because neither one types a character and
 * both act on the whole field. The tools key is always present: with no provider
 * connected it still opens the panel, which is where the user finds out how to connect
 * one — a key that only appeared after setup could never lead anyone to the setup.
 *
 * Both buttons are [R.dimen.min_touch_target] across, so the row is exactly one touch
 * target tall and clears the section 1.6 floor without extra padding.
 */
class SuggestionBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    val strip = SuggestionStrip(context)

    var onGrammarTap: (() -> Unit)? = null

    /** A long press on the grammar key: the IME switches Casual and Formal. */
    var onGrammarLongPress: (() -> Unit)? = null
    var onToolsTap: (() -> Unit)? = null

    /**
     * Which mode the grammar key will correct in. Shown as a letter on the key's corner
     * and spoken as part of its description, so the mode is never a hidden state.
     */
    var grammarMode: GrammarMode = GrammarMode.CASUAL
        set(value) {
            field = value
            val formal = value == GrammarMode.FORMAL
            modeBadge.setText(
                if (formal) R.string.grammar_mode_formal_badge else R.string.grammar_mode_casual_badge
            )
            grammarButton.contentDescription = context.getString(
                R.string.key_fix_grammar_mode,
                context.getString(
                    if (formal) R.string.grammar_mode_formal else R.string.grammar_mode_casual
                ),
            )
        }

    private val touchTarget = resources.getDimensionPixelSize(R.dimen.min_touch_target)
    private val gap = resources.getDimensionPixelSize(R.dimen.suggestion_bar_gap)
    private val iconSize = resources.getDimensionPixelSize(R.dimen.action_key_icon_size)

    private val colorSurface = ContextCompat.getColor(context, R.color.surface_key_special)
    private val colorPressed = ContextCompat.getColor(context, R.color.surface_key_pressed)
    private val colorChalk = ContextCompat.getColor(context, R.color.text_chalk)
    private val colorChalkMuted = ContextCompat.getColor(context, R.color.text_chalk_muted)
    private val colorAccent = ContextCompat.getColor(context, R.color.accent_chalk_yellow)
    private val colorTeal = ContextCompat.getColor(context, R.color.accent_dusty_teal)

    private val grammarButton = actionButton(
        icon = R.drawable.ic_fix_grammar,
        description = R.string.key_fix_grammar,
        fill = colorSurface,
        tint = colorChalkMuted,
    ) { onGrammarTap?.invoke() }.apply {
        setOnLongClickListener {
            onGrammarLongPress?.invoke()
            true
        }
    }

    /** The mode letter. Decoration on the grammar key, so it takes no touches itself. */
    private val modeBadge = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(colorAccent)
        setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.mode_badge_text_size),
        )
        typeface = ChalkTypefaces.weighted(ChalkTypefaces.SEMI_BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, R.color.bg_slate))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val toolsButton = actionButton(
        icon = R.drawable.ic_ai_rewrite,
        description = R.string.key_ai_tools,
        fill = colorTeal,
        tint = colorChalk,
    ) { onToolsTap?.invoke() }

    private val handler = Handler(Looper.getMainLooper())
    private val clearSettled = Runnable { grammarButton.imageTintList = muted }
    private val muted = ColorStateList.valueOf(colorChalkMuted)
    private val settled = ColorStateList.valueOf(colorAccent)

    init {
        orientation = HORIZONTAL
        val edge = resources.getDimensionPixelSize(R.dimen.space_2)
        setPadding(edge, edge, edge, edge)

        addView(
            strip,
            LayoutParams(0, touchTarget, 1f),
        )
        val badgeSize = resources.getDimensionPixelSize(R.dimen.space_4)
        addView(
            FrameLayout(context).apply {
                addView(grammarButton, FrameLayout.LayoutParams(touchTarget, touchTarget))
                addView(
                    modeBadge,
                    FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.BOTTOM or Gravity.END),
                )
            },
            spacedParams(),
        )
        addView(toolsButton, spacedParams())
        grammarMode = GrammarMode.CASUAL
    }

    private fun spacedParams() = LayoutParams(touchTarget, touchTarget).apply {
        marginStart = gap
    }

    private fun actionButton(
        @DrawableRes icon: Int,
        @StringRes description: Int,
        fill: Int,
        tint: Int,
        onTap: () -> Unit,
    ): ImageButton {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorPressed)
        }
        return ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = context.getString(description)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(tint)
            background = RippleDrawable(ColorStateList.valueOf(colorPressed), circle, mask)
            val inset = (touchTarget - iconSize) / 2
            setPadding(inset, inset, inset, inset)
            setOnClickListener { onTap() }
        }
    }

    fun setCandidates(next: List<String>) = strip.setCandidates(next)

    fun clear() = strip.clear()

    /**
     * Tints the grammar key chalk yellow for a moment once a correction has settled —
     * step 3 of the chalk redraw, the same colour the underline fades to. It is a
     * receipt, not a press state: nothing paints the key on the way in, because at that
     * point nothing has been corrected yet.
     */
    fun markGrammarSettled() {
        grammarButton.imageTintList = settled
        handler.removeCallbacks(clearSettled)
        handler.postDelayed(clearSettled, SETTLED_HOLD_MS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(clearSettled)
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Long enough to be seen after the redraw finishes, short enough not to linger. */
        const val SETTLED_HOLD_MS = 1_200L
    }
}
