package com.chalkboard.keyboard.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.ui.theme.ChalkMotion
import kotlin.math.roundToInt

/**
 * Three candidate slots in a rounded strip above the keys, fed by the prediction engine.
 *
 * Rank is carried by **weight alone** — 600 for the most likely candidate, 400 for the
 * other two, all three on `text.chalk` (section B2). An earlier version drew the top
 * candidate in chalk yellow as well; that made the ranking depend on a colour, which
 * fails the same greyscale test section 1.6 applies everywhere else, and spent the one
 * accent the keyboard reserves for a completed correction on something that changes on
 * every keystroke.
 *
 * Candidate changes cross-fade over 100ms with no slide or bounce (section 1.4), and
 * collapse to an instant swap when the system has animations turned off.
 */
class SuggestionStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Invoked with the tapped candidate. The IME commits it and advances a space. */
    var onCandidateSelected: ((String) -> Unit)? = null

    private var candidates: List<String> = emptyList()
    private var outgoing: List<String> = emptyList()
    private var fadeProgress = 1f

    private val stripHeight = resources.getDimension(R.dimen.suggestion_strip_height)
    private val minTouchTarget = resources.getDimension(R.dimen.min_touch_target)
    private val cornerRadius = resources.getDimension(R.dimen.radius_suggestion_strip)
    private val horizontalPadding = resources.getDimension(R.dimen.space_3)

    private val colorSurface = ContextCompat.getColor(context, R.color.surface_key_special)
    private val colorChalk = ContextCompat.getColor(context, R.color.text_chalk)
    private val colorChalkMuted = ContextCompat.getColor(context, R.color.text_chalk_muted)
    private val colorPressed = ContextCompat.getColor(context, R.color.surface_key_pressed)

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorSurface }

    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.suggestion_word_size)
        typeface = ChalkTypefaces.weighted(ChalkTypefaces.SEMI_BOLD)
        color = colorChalk
    }

    private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.suggestion_word_size)
        typeface = ChalkTypefaces.weighted(ChalkTypefaces.REGULAR)
        color = colorChalk
    }

    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.suggestion_placeholder_size)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
        color = colorChalkMuted
    }

    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorPressed }

    private val bounds = RectF()
    private val slotRect = RectF()

    /** The strip's own rounded outline, so a pressed slot cannot square off its corners. */
    private val clipPath = Path()

    private var pressedSlot = -1
    private var fadeAnimator: ValueAnimator? = null

    private val a11yHelper = CandidateAccessibilityHelper()

    init {
        ViewCompat.setAccessibilityDelegate(this, a11yHelper)
    }

    /** Replaces the visible candidates, cross-fading unless reduced motion is on. */
    fun setCandidates(next: List<String>) {
        if (next == candidates) return
        outgoing = candidates
        candidates = next.take(SLOTS)
        a11yHelper.invalidateRoot()

        fadeAnimator?.cancel()
        val duration = ChalkMotion.duration(context, ChalkMotion.SUGGESTION_SWAP_MS)
        if (duration == 0L) {
            outgoing = emptyList()
            fadeProgress = 1f
            invalidate()
            return
        }
        fadeProgress = 0f
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = ChalkMotion.easeOutInterpolator
            addUpdateListener {
                fadeProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun clear() = setCandidates(emptyList())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            maxOf(stripHeight, minTouchTarget).roundToInt(),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, surfacePaint)

        if (candidates.isEmpty() && outgoing.isEmpty()) {
            drawPlaceholder(canvas)
            return
        }

        if (pressedSlot in candidates.indices) {
            val slotWidth = width / SLOTS.toFloat()
            slotRect.set(slotWidth * pressedSlot, 0f, slotWidth * (pressedSlot + 1), height.toFloat())
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(slotRect, pressPaint)
            canvas.restore()
        }

        val slotWidth = width / SLOTS.toFloat()
        drawSlots(canvas, outgoing, slotWidth, alpha = 1f - fadeProgress)
        drawSlots(canvas, candidates, slotWidth, alpha = fadeProgress)
    }

    /**
     * The empty state says what the strip is for rather than leaving a blank bar
     * (section B1). It is not a candidate and is never tappable.
     */
    private fun drawPlaceholder(canvas: Canvas) {
        val baseline =
            height / 2f - (placeholderPaint.descent() + placeholderPaint.ascent()) / 2
        canvas.drawText(
            context.getString(R.string.suggestion_placeholder),
            width / 2f,
            baseline,
            placeholderPaint,
        )
    }

    private fun drawSlots(canvas: Canvas, words: List<String>, slotWidth: Float, alpha: Float) {
        if (alpha <= 0f) return
        val opacity = (255 * alpha).roundToInt().coerceIn(0, 255)
        words.forEachIndexed { i, word ->
            val paint = if (i == 0) topPaint else restPaint
            paint.alpha = opacity
            val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(
                ellipsize(word, paint, slotWidth - horizontalPadding),
                slotWidth * (i + 0.5f),
                baseline,
                paint,
            )
            paint.alpha = 255
        }
    }

    /**
     * Long candidates are clipped with an ellipsis rather than allowed to run under the
     * neighbouring slot, where they would read as one word split across two.
     */
    private fun ellipsize(word: String, paint: Paint, available: Float): String {
        if (available <= 0f || paint.measureText(word) <= available) return word
        val kept = paint.breakText(word, true, available - paint.measureText(ELLIPSIS), null)
        return word.take(kept) + ELLIPSIS
    }

    @Suppress("ClickableViewAccessibility") // Handled by CandidateAccessibilityHelper.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val slot = (event.x / (width / SLOTS.toFloat())).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedSlot = slot.takeIf { it in candidates.indices } ?: -1
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                if (pressedSlot >= 0 && slot == pressedSlot) {
                    candidates.getOrNull(pressedSlot)?.let { onCandidateSelected?.invoke(it) }
                }
                pressedSlot = -1
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedSlot = -1
                invalidate()
            }
        }
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11yHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private inner class CandidateAccessibilityHelper :
        ExploreByTouchHelper(this@SuggestionStrip) {

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val slot = (x / (width / SLOTS.toFloat())).toInt()
            return if (slot in candidates.indices) slot else INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            candidates.indices.forEach { virtualViewIds.add(it) }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val word = candidates.getOrNull(virtualViewId)
            if (word == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect())
                return
            }
            node.contentDescription = context.getString(
                R.string.suggestion_slot_description,
                word,
                virtualViewId + 1,
                candidates.size,
            )
            node.className = "android.widget.Button"
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            val slotWidth = width / SLOTS
            node.setBoundsInParent(
                Rect(slotWidth * virtualViewId, 0, slotWidth * (virtualViewId + 1), height)
            )
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: android.os.Bundle?,
        ): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val word = candidates.getOrNull(virtualViewId) ?: return false
            onCandidateSelected?.invoke(word)
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    private companion object {
        const val SLOTS = 3
        const val ELLIPSIS = "…"
    }
}
