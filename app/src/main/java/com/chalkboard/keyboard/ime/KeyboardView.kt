package com.chalkboard.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.chalkboard.keyboard.R
import kotlin.math.roundToInt

/**
 * Renders a [KeyboardLayout] and turns touches into key actions.
 *
 * Drawn on a canvas rather than composed from child views: a keyboard redraws on every
 * press and the view hierarchy would be ~40 nodes deep in a surface that must never
 * drop a frame. Layout knowledge lives entirely in [KeyLayout]; this class only knows
 * how to measure rows, paint them with the section 1.1 tokens, and hit-test.
 *
 * Accessibility is provided through [ExploreByTouchHelper], since canvas-drawn keys are
 * otherwise invisible to TalkBack (section 1.6).
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called when a key is committed. */
    var onKeyAction: ((KeyAction) -> Unit)? = null

    /** Called when a long press should commit literal text instead of the key's action. */
    var onLongPressText: ((String) -> Unit)? = null

    var layout: KeyboardLayout = KeyLayouts.qwerty()
        set(value) {
            field = value
            pressHandler.reset()
            // A page switch usually leaves the view the same size, so onSizeChanged will
            // not run: without this the new page is drawn into the old page's key rects.
            if (width > 0) placeKeys(width, height)
            requestLayout()
            invalidate()
        }

    var shiftState: ShiftState = ShiftState.OFF
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * The row count the view is sized for, when that is more than [layout] has. The IME
     * sets it to the letters page's, so a symbols page with one row fewer stretches its
     * keys instead of making the whole keyboard jump shorter on every page switch.
     */
    var minRows: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Label for the enter key, derived from `EditorInfo.imeOptions` by the IME. */
    var enterLabel: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private val rowHeight = resources.getDimension(R.dimen.key_row_height)
    private val rowHeightCompact = resources.getDimension(R.dimen.key_row_height_compact)
    private val keyGap = resources.getDimension(R.dimen.key_gap)
    private val keyRadius = resources.getDimension(R.dimen.radius_key)
    private val minTouchTarget = resources.getDimension(R.dimen.min_touch_target)
    private val edgePadding = resources.getDimension(R.dimen.space_1)

    private val colorSlate = ContextCompat.getColor(context, R.color.bg_slate)
    private val colorKey = ContextCompat.getColor(context, R.color.surface_key)
    private val colorKeyPressed = ContextCompat.getColor(context, R.color.surface_key_pressed)
    private val colorKeySpecial = ContextCompat.getColor(context, R.color.surface_key_special)
    private val colorChalk = ContextCompat.getColor(context, R.color.text_chalk)
    private val colorChalkMuted = ContextCompat.getColor(context, R.color.text_chalk_muted)
    private val colorAccent = ContextCompat.getColor(context, R.color.accent_chalk_yellow)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.key_label_size)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.key_label_size) * 0.55f
        color = colorChalkMuted
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** The space bar's language name. Muted and letter-spaced, never the key label size. */
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.key_caption_size)
        color = colorChalkMuted
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.08f
    }

    /** Parallel to [layout]: one rect per key, in the same row/key order. */
    private val keyRects = mutableListOf<MutableList<RectF>>()

    /** Flat view of every key with its rect, rebuilt on layout. Used for hit-testing. */
    private val placedKeys = mutableListOf<PlacedKey>()

    private var pressedKey: Key? = null

    private val iconCache = mutableMapOf<Int, Drawable>()
    private val iconBounds = Rect()
    private val roundRect = RectF()

    private val pressHandler = KeyPressHandler(
        hitTest = ::keyAt,
        onPressedKeyChanged = { key ->
            if (key != null && key !== pressedKey) {
                performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                )
            }
            pressedKey = key
            invalidate()
        },
        onKeyAction = { key -> onKeyAction?.invoke(key.action) },
        onLongPressText = { text -> onLongPressText?.invoke(text) },
    )

    private val a11yHelper = KeyAccessibilityHelper()

    init {
        setBackgroundColor(colorSlate)
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, a11yHelper)
    }

    private data class PlacedKey(val key: Key, val rect: RectF, val id: Int)

    private companion object {
        /** Rows at the full row height; more than this switches to the compact one. */
        const val STANDARD_ROWS = 4
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = maxOf(layout.rowCount, minRows)
        // A fifth row would push the keyboard past a third of the screen at full height.
        val token = if (rows > STANDARD_ROWS) rowHeightCompact else rowHeight
        // Rows never render below the 44dp touch-target floor, whatever the row token says.
        val effectiveRowHeight = maxOf(token, minTouchTarget)
        val height = (effectiveRowHeight * rows + keyGap * (rows + 1) +
            edgePadding * 2).roundToInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placeKeys(w, h)
    }

    private fun placeKeys(viewWidth: Int, viewHeight: Int) {
        keyRects.clear()
        placedKeys.clear()
        if (layout.rowCount == 0) return

        val usableHeight = viewHeight - edgePadding * 2 - keyGap * (layout.rowCount + 1)
        val rowH = usableHeight / layout.rowCount
        var top = edgePadding + keyGap
        var id = 0

        for (row in layout.rows) {
            val totalWeight = row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
            val usableWidth = viewWidth - edgePadding * 2 - keyGap * (row.keys.size + 1)
            var left = edgePadding + keyGap
            val rects = mutableListOf<RectF>()

            for (key in row.keys) {
                val width = usableWidth * (key.widthWeight / totalWeight)
                val rect = RectF(left, top, left + width, top + rowH)
                rects.add(rect)
                if (!key.isSpacer) placedKeys.add(PlacedKey(key, rect, id++))
                left += width + keyGap
            }
            keyRects.add(rects)
            top += rowH + keyGap
        }
        a11yHelper.invalidateRoot()
    }

    private fun keyAt(x: Float, y: Float): Key? = placedAt(x, y)?.key

    private fun placedAt(x: Float, y: Float): PlacedKey? =
        placedKeys.firstOrNull { it.rect.contains(x, y) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((rowIndex, row) in layout.rows.withIndex()) {
            val rects = keyRects.getOrNull(rowIndex) ?: continue
            for ((keyIndex, key) in row.keys.withIndex()) {
                val rect = rects.getOrNull(keyIndex) ?: continue
                if (key.isSpacer) continue
                drawKey(canvas, key, rect)
            }
        }
    }

    private fun drawKey(canvas: Canvas, key: Key, rect: RectF) {
        keyPaint.color = backgroundFor(key)
        roundRect.set(rect)
        canvas.drawRoundRect(roundRect, keyRadius, keyRadius, keyPaint)

        val iconRes = iconFor(key)
        if (iconRes != null) {
            val icon = iconCache.getOrPut(iconRes) {
                ContextCompat.getDrawable(context, iconRes)!!.mutate()
            }
            val size = (minOf(rect.width(), rect.height()) * 0.45f).roundToInt()
            val cx = rect.centerX().roundToInt()
            val cy = rect.centerY().roundToInt()
            iconBounds.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            icon.bounds = iconBounds
            icon.setTint(tintFor(key))
            icon.draw(canvas)
            return
        }

        key.caption?.let { captionRes ->
            val caption = context.getString(captionRes).uppercase()
            val baseline = rect.centerY() - (captionPaint.descent() + captionPaint.ascent()) / 2
            canvas.drawText(caption, rect.centerX(), baseline, captionPaint)
            return
        }

        val text = labelFor(key) ?: return
        labelPaint.color = colorChalk
        val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(text, rect.centerX(), baseline, labelPaint)

        key.longPressText?.let { hint ->
            canvas.drawText(
                hint,
                rect.right - rect.width() * 0.18f,
                rect.top + hintPaint.textSize * 1.2f,
                hintPaint,
            )
        }
    }

    /**
     * Caps lock fills the shift key solid rather than only recolouring its glyph
     * (section B8): a lock that persists across keystrokes is worth a state the eye
     * catches without reading, and it is the second signal alongside the distinct glyph.
     */
    private fun backgroundFor(key: Key): Int = when {
        key === pressedKey -> colorKeyPressed
        key.action is KeyAction.Shift && shiftState == ShiftState.LOCKED -> colorAccent
        key.special -> colorKeySpecial
        else -> colorKey
    }

    private fun iconFor(key: Key): Int? = when (key.action) {
        // Caps lock gets a distinct glyph, not just a colour change (section 1.6).
        KeyAction.Shift -> if (shiftState == ShiftState.LOCKED) R.drawable.ic_shift_locked else key.icon
        // A field that asks for "Send" says so; the arrow is only the fallback.
        KeyAction.Enter -> if (enterLabel != null) null else key.icon
        else -> key.icon
    }

    private fun tintFor(key: Key): Int = when {
        key.action !is KeyAction.Shift -> colorChalk
        // Against the filled caps-lock key the glyph has to invert to stay readable.
        shiftState == ShiftState.LOCKED -> colorSlate
        shiftState == ShiftState.SHIFTED -> colorAccent
        else -> colorChalk
    }

    private fun labelFor(key: Key): String? = when (key.action) {
        is KeyAction.Enter -> enterLabel ?: key.label
        is KeyAction.Character -> key.label?.let { if (shiftState == ShiftState.OFF) it else it.uppercase() }
        else -> key.label
    }

    @Suppress("ClickableViewAccessibility") // Handled by KeyAccessibilityHelper.
    override fun onTouchEvent(event: MotionEvent): Boolean = pressHandler.onTouchEvent(event)

    override fun onDetachedFromWindow() {
        pressHandler.reset()
        super.onDetachedFromWindow()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11yHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** Exposes each canvas-drawn key to TalkBack and switch access as a virtual node. */
    private inner class KeyAccessibilityHelper : ExploreByTouchHelper(this@KeyboardView) {

        override fun getVirtualViewAt(x: Float, y: Float): Int =
            placedAt(x, y)?.id ?: INVALID_ID

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            placedKeys.forEach { virtualViewIds.add(it.id) }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val placed = placedKeys.firstOrNull { it.id == virtualViewId }
            if (placed == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect())
                return
            }
            node.contentDescription = describe(placed.key)
            node.className = "android.widget.Button"
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.setBoundsInParent(
                Rect(
                    placed.rect.left.roundToInt(),
                    placed.rect.top.roundToInt(),
                    placed.rect.right.roundToInt(),
                    placed.rect.bottom.roundToInt(),
                )
            )
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: android.os.Bundle?,
        ): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val placed = placedKeys.firstOrNull { it.id == virtualViewId } ?: return false
            onKeyAction?.invoke(placed.key.action)
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }

        private fun describe(key: Key): String {
            key.contentDescription?.let { res ->
                val base = context.getString(res)
                return if (key.action is KeyAction.Shift && shiftState == ShiftState.LOCKED) {
                    context.getString(R.string.key_caps_lock)
                } else {
                    base
                }
            }
            return labelFor(key).orEmpty()
        }
    }
}
