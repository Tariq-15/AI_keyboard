package com.chalkboard.keyboard.ime

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Touch state machine for [KeyboardView].
 *
 * Owns exactly three behaviours and nothing about rendering:
 *  - press / slide-off / release, so a finger that drifts off a key cancels it;
 *  - repeat-on-hold for keys marked [Key.repeatable] (backspace);
 *  - long-press, which fires [Key.longPressText] instead of the key's action.
 *
 * Single-pointer by design. A second finger down while a key is held is ignored rather
 * than queued: on a keyboard that produces fewer wrong characters than optimistic
 * multi-touch does.
 */
class KeyPressHandler(
    private val hitTest: (x: Float, y: Float) -> Key?,
    private val onPressedKeyChanged: (Key?) -> Unit,
    private val onKeyAction: (Key) -> Unit,
    private val onLongPressText: (String) -> Unit,
) {

    private companion object {
        /** Delay before repeat-on-hold starts. */
        const val REPEAT_START_MS = 400L

        /** Interval between repeats once started. */
        const val REPEAT_INTERVAL_MS = 50L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var pressedKey: Key? = null

    /** Set once a hold has produced a repeat or a long-press, so release commits nothing. */
    private var consumedByHold = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = pressedKey ?: return
            consumedByHold = true
            onKeyAction(key)
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val longPressRunnable = Runnable {
        val text = pressedKey?.longPressText ?: return@Runnable
        consumedByHold = true
        onLongPressText(text)
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (activePointerId != MotionEvent.INVALID_POINTER_ID) return true
                activePointerId = event.getPointerId(0)
                press(hitTest(event.x, event.y))
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val key = hitTest(event.getX(index), event.getY(index))
                // Sliding onto a different key re-arms it; sliding off cancels.
                if (key !== pressedKey) press(key)
            }

            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(activePointerId)
                val key = if (index >= 0) hitTest(event.getX(index), event.getY(index)) else null
                val held = consumedByHold
                cancelTimers()
                if (!held && key != null && key === pressedKey) onKeyAction(key)
                release()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelTimers()
                release()
            }
        }
        return true
    }

    /** Drops any in-flight press. Call when the keyboard is hidden or the layout changes. */
    fun reset() {
        cancelTimers()
        release()
    }

    private fun press(key: Key?) {
        cancelTimers()
        pressedKey = key?.takeUnless { it.isSpacer }
        consumedByHold = false
        onPressedKeyChanged(pressedKey)

        val active = pressedKey ?: return
        if (active.repeatable) {
            handler.postDelayed(repeatRunnable, REPEAT_START_MS)
        } else if (active.longPressText != null) {
            handler.postDelayed(longPressRunnable, longPressTimeout)
        }
    }

    private fun release() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        pressedKey = null
        consumedByHold = false
        onPressedKeyChanged(null)
    }

    private fun cancelTimers() {
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(longPressRunnable)
    }
}
