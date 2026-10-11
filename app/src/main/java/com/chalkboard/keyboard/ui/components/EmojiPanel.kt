package com.chalkboard.keyboard.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import androidx.core.content.ContextCompat
import androidx.emoji2.emojipicker.EmojiPickerView
import com.chalkboard.keyboard.R

/**
 * Emoji, in the space the keys normally occupy: the platform's own picker over a strip
 * with the two keys that still matter while it is open — back to letters, and backspace.
 *
 * The picker is androidx's rather than a grid built here: categories, recents, skin-tone
 * variants and new Unicode releases are exactly the part of an emoji keyboard that is
 * never finished.
 */
class EmojiPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onEmoji: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.bg_slate))

        val touchTarget = resources.getDimensionPixelSize(R.dimen.min_touch_target)
        val edge = resources.getDimensionPixelSize(R.dimen.space_2)
        val colorSpecial = ContextCompat.getColor(context, R.color.surface_key_special)
        val colorPressed = ContextCompat.getColor(context, R.color.surface_key_pressed)
        val colorChalk = ContextCompat.getColor(context, R.color.text_chalk)

        fun keyBackground() = RippleDrawable(
            ColorStateList.valueOf(colorPressed),
            GradientDrawable().apply {
                cornerRadius = resources.getDimension(R.dimen.radius_key)
                setColor(colorSpecial)
            },
            null,
        )

        // The picker styles itself from AppCompat theme attributes, which an input
        // method's own context does not carry.
        val themed = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat)
        addView(
            EmojiPickerView(themed).apply {
                setOnEmojiPickedListener { item -> onEmoji?.invoke(item.emoji) }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )

        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(edge, edge, edge, edge)
                addView(
                    Button(context).apply {
                        setText(R.string.key_letters_label)
                        contentDescription = context.getString(R.string.key_letters)
                        stateListAnimator = null
                        minWidth = 0
                        minimumWidth = 0
                        setTextColor(colorChalk)
                        setTextSize(
                            TypedValue.COMPLEX_UNIT_PX,
                            resources.getDimension(R.dimen.suggestion_word_size),
                        )
                        typeface = ChalkTypefaces.weighted(ChalkTypefaces.MEDIUM)
                        background = keyBackground()
                        setOnClickListener { onClose?.invoke() }
                    },
                    LayoutParams(touchTarget * 3 / 2, touchTarget),
                )
                addView(Space(context), LayoutParams(0, 0, 1f))
                addView(
                    ImageButton(context).apply {
                        setImageResource(R.drawable.ic_backspace)
                        contentDescription = context.getString(R.string.key_backspace)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        imageTintList = ColorStateList.valueOf(colorChalk)
                        background = keyBackground()
                        // shortcut: one delete per tap, no repeat-on-hold; reuse
                        // KeyPressHandler's repeat if deleting runs of emoji gets tedious.
                        setOnClickListener { onBackspace?.invoke() }
                    },
                    LayoutParams(touchTarget * 3 / 2, touchTarget),
                )
            }
        )
    }
}
