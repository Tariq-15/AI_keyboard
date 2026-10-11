package com.chalkboard.keyboard.ui.components

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.ui.Notice

/**
 * What the keyboard says when something is not working: a title, a body, and a rule down
 * the left edge marking which kind of not-working it is (sections E1 and E4).
 *
 * It is drawn here, inside the keyboard's own panel, because an IME has no access to the
 * host app's views — the frame gallery draws these notices over the message thread, but
 * that is a mockup convenience no input method can reproduce. It takes the same slot as
 * the correction panel and the suggestion bar; exactly one of the three is ever visible.
 *
 * The rule is a second signal, never the only one: the title always says in words what
 * happened, and the body always says what still works.
 */
class StatusBanner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val colorSurface = ContextCompat.getColor(context, R.color.surface_key_special)
    private val colorChalk = ContextCompat.getColor(context, R.color.text_chalk)
    private val colorChalkMuted = ContextCompat.getColor(context, R.color.text_chalk_muted)
    private val colorCoral = ContextCompat.getColor(context, R.color.accent_soft_coral)

    private val rule = View(context)

    private val titleView = TextView(context).apply {
        setTextColor(colorChalk)
        setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.banner_title_size),
        )
        typeface = ChalkTypefaces.weighted(ChalkTypefaces.SEMI_BOLD)
    }

    private val bodyView = TextView(context).apply {
        setTextColor(colorChalkMuted)
        setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.banner_body_size),
        )
    }

    init {
        orientation = HORIZONTAL
        // The rule is a plain rectangle; clipping to the card's outline is what rounds
        // its two outer corners to match.
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimension(R.dimen.radius_card)
            setColor(colorSurface)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true

        val padH = resources.getDimensionPixelSize(R.dimen.space_3)
        val padV = resources.getDimensionPixelSize(R.dimen.space_3)
        minimumHeight = resources.getDimensionPixelSize(R.dimen.min_touch_target)

        addView(
            rule,
            LayoutParams(
                resources.getDimensionPixelSize(R.dimen.banner_rule_width),
                LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, padV, padH, padV)
                addView(titleView)
                addView(bodyView)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    fun show(notice: Notice) {
        val title = notice.title(context)
        val body = notice.body(context)

        titleView.text = title
        bodyView.text = body
        bodyView.visibility = if (body.isNullOrEmpty()) View.GONE else View.VISIBLE

        rule.setBackgroundColor(
            when (notice.severity) {
                Notice.Severity.FAILURE -> colorCoral
                Notice.Severity.NEUTRAL -> colorChalkMuted
            }
        )

        // TalkBack does not see a View appearing inside an IME window as an event worth
        // reporting, so the notice is spoken explicitly or it is silent to a screen
        // reader — which for the one surface that explains a failure is not acceptable.
        contentDescription = listOfNotNull(title, body).joinToString(separator = ". ")
        announceForAccessibility(contentDescription)
    }
}
