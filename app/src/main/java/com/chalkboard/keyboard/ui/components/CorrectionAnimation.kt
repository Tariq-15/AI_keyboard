package com.chalkboard.keyboard.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.grammar.Correction
import com.chalkboard.keyboard.ui.theme.ChalkMotion
import kotlin.math.roundToInt

/**
 * The chalk-redraw correction, design spec section 1.4.
 *
 * Per corrected word, in sequence:
 *  1. a coral underline is drawn left-to-right beneath the original word (150ms) — a
 *     stroke reveal, not a fade, so it reads as being *drawn*;
 *  2. the word cross-fades from original to correction (100ms);
 *  3. the underline shifts coral to chalk yellow and fades out (200ms).
 *
 * Words are staggered by 80ms so a multi-word fix reads left-to-right instead of firing
 * at once. With system animations off the whole sequence collapses to an instant swap
 * with no underline at all — that is a hard requirement, not a shortened animation.
 *
 * This exists because an IME cannot draw into the host app's text field. The correction
 * is shown and animated here, in a panel the keyboard owns, and only then committed.
 */
class CorrectionAnimation @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called once every word has settled and the corrected text can be committed. */
    var onSequenceFinished: ((String) -> Unit)? = null

    private val colorChalk = ContextCompat.getColor(context, R.color.text_chalk)
    private val colorCoral = ContextCompat.getColor(context, R.color.accent_soft_coral)
    private val colorYellow = ContextCompat.getColor(context, R.color.accent_chalk_yellow)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = resources.getDimension(R.dimen.correction_text_size)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = colorChalk
    }

    private val correctedPaint = Paint(textPaint).apply {
        // Corrected words stay distinguishable by weight after the colour has faded, so
        // the change does not depend on colour alone (section 1.6).
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private val horizontalPadding = resources.getDimension(R.dimen.space_4)
    private val verticalPadding = resources.getDimension(R.dimen.space_3)

    /**
     * One rendered run of text. Unchanged spans have [corrected] == null and simply draw.
     */
    private data class Span(
        val original: String,
        val corrected: String?,
        var x: Float = 0f,
        var width: Float = 0f,
        /** 0..1 across this span's own three-stage sequence. */
        var progress: Float = 0f,
    )

    private var spans: List<Span> = emptyList()
    private var animator: ValueAnimator? = null
    private var pendingResult: String = ""

    /** Renders [text] with nothing animating. Used to preview before a check runs. */
    fun showPlain(text: String) {
        animator?.cancel()
        spans = listOf(Span(text, null, progress = 1f))
        pendingResult = text
        requestLayout()
        invalidate()
    }

    /**
     * Runs the sequence over [text] with [corrections] applied, then reports the
     * corrected string through [onSequenceFinished].
     */
    fun play(text: String, corrections: List<Correction>) {
        animator?.cancel()

        val ordered = corrections.filterNot { it.isNoOp }.sortedBy { it.range.first }
        spans = buildSpans(text, ordered)
        pendingResult = com.chalkboard.keyboard.grammar.applyCorrections(text, ordered)

        announceForAccessibility(
            resources.getQuantityString(
                R.plurals.correction_announcement,
                ordered.size,
                ordered.size,
                pendingResult,
            )
        )
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)

        val animatedCount = spans.count { it.corrected != null }
        if (animatedCount == 0) {
            spans.forEach { it.progress = 1f }
            requestLayout()
            invalidate()
            onSequenceFinished?.invoke(pendingResult)
            return
        }

        if (ChalkMotion.reducedMotion(context)) {
            // Instant swap, no underline, no stagger.
            spans.forEach { it.progress = 1f }
            requestLayout()
            invalidate()
            onSequenceFinished?.invoke(pendingResult)
            return
        }

        val stagger = ChalkMotion.CHALK_STAGGER_MS
        val total = ChalkMotion.CHALK_TOTAL_MS + stagger * (animatedCount - 1)

        animator = ValueAnimator.ofFloat(0f, total.toFloat()).apply {
            duration = total
            interpolator = null // Stage curves are applied per stage, not to the timeline.
            addUpdateListener { animation ->
                val elapsed = animation.animatedValue as Float
                var index = 0
                spans.forEach { span ->
                    if (span.corrected == null) {
                        span.progress = 1f
                    } else {
                        val start = stagger * index
                        span.progress = ((elapsed - start) / ChalkMotion.CHALK_TOTAL_MS)
                            .coerceIn(0f, 1f)
                        index++
                    }
                }
                invalidate()
            }
            addListener(
                onEnd = {
                    spans.forEach { it.progress = 1f }
                    invalidate()
                    onSequenceFinished?.invoke(pendingResult)
                }
            )
            start()
        }
    }

    /** Splits [text] at the correction boundaries so each becomes its own span. */
    private fun buildSpans(text: String, corrections: List<Correction>): List<Span> {
        val result = mutableListOf<Span>()
        var cursor = 0
        for (correction in corrections) {
            val start = correction.range.first.coerceIn(0, text.length)
            val end = (correction.range.last + 1).coerceIn(start, text.length)
            if (start > cursor) result += Span(text.substring(cursor, start), null)
            result += Span(text.substring(start, end), correction.replacement)
            cursor = end
        }
        if (cursor < text.length) result += Span(text.substring(cursor), null)
        return result
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val lineHeight = textPaint.fontSpacing
        // Single line: this panel shows the sentence being corrected, not a document.
        setMeasuredDimension(width, (lineHeight + verticalPadding * 2).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (spans.isEmpty()) return

        // Lay the spans out at their widest, so nothing reflows mid-animation.
        var x = horizontalPadding
        spans.forEach { span ->
            val widest = maxOf(
                textPaint.measureText(span.original),
                span.corrected?.let { correctedPaint.measureText(it) } ?: 0f,
            )
            span.x = x
            span.width = widest
            x += widest
        }

        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        val underlineY = baseline + textPaint.descent() + underlinePaint.strokeWidth

        spans.forEach { span -> drawSpan(canvas, span, baseline, underlineY) }
    }

    private fun drawSpan(canvas: Canvas, span: Span, baseline: Float, underlineY: Float) {
        val corrected = span.corrected
        if (corrected == null) {
            canvas.drawText(span.original, span.x, baseline, textPaint)
            return
        }

        val stages = stageProgress(span.progress)

        // Stage 2: cross-fade original -> correction.
        if (stages.crossfade < 1f) {
            textPaint.alpha = (255 * (1f - stages.crossfade)).roundToInt().coerceIn(0, 255)
            canvas.drawText(span.original, span.x, baseline, textPaint)
            textPaint.alpha = 255
        }
        if (stages.crossfade > 0f) {
            correctedPaint.alpha = (255 * stages.crossfade).roundToInt().coerceIn(0, 255)
            canvas.drawText(corrected, span.x, baseline, correctedPaint)
            correctedPaint.alpha = 255
        }

        // Stages 1 and 3: the underline is drawn, then recoloured and faded out.
        if (stages.underlineExtent > 0f && stages.underlineAlpha > 0f) {
            underlinePaint.color = blend(colorCoral, colorYellow, stages.underlineTint)
            underlinePaint.alpha = (255 * stages.underlineAlpha).roundToInt().coerceIn(0, 255)
            canvas.drawLine(
                span.x,
                underlineY,
                span.x + span.width * stages.underlineExtent,
                underlineY,
                underlinePaint,
            )
        }
    }

    private data class Stages(
        val underlineExtent: Float,
        val underlineAlpha: Float,
        val underlineTint: Float,
        val crossfade: Float,
    )

    /** Maps 0..1 over the whole sequence onto the three stages and their easing. */
    private fun stageProgress(progress: Float): Stages {
        val total = ChalkMotion.CHALK_TOTAL_MS.toFloat()
        val drawEnd = ChalkMotion.CHALK_UNDERLINE_MS / total
        val fadeEnd = drawEnd + ChalkMotion.CHALK_CROSSFADE_MS / total

        val extent = ease((progress / drawEnd).coerceIn(0f, 1f))
        val crossfade = ease(((progress - drawEnd) / (fadeEnd - drawEnd)).coerceIn(0f, 1f))
        val settle = ease(((progress - fadeEnd) / (1f - fadeEnd)).coerceIn(0f, 1f))

        return Stages(
            underlineExtent = extent,
            underlineAlpha = 1f - settle,
            underlineTint = settle,
            crossfade = crossfade,
        )
    }

    /** The 150ms ease-out curve from the motion tokens, evaluated directly. */
    private fun ease(t: Float): Float = ChalkMotion.easeOutInterpolator.getInterpolation(t)

    private fun blend(from: Int, to: Int, amount: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).roundToInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).roundToInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).roundToInt(),
    )

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

/** Minimal listener helper: only the end callback is ever needed here. */
private fun ValueAnimator.addListener(onEnd: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
    })
}
