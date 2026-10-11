package com.chalkboard.keyboard.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing and shape tokens, design spec section 1.3.
 * Spacing is a 4dp base unit — 4/8/12/16/24/32 only, no arbitrary values.
 */
object ChalkSpacing {
    val x1: Dp = 4.dp
    val x2: Dp = 8.dp
    val x3: Dp = 12.dp
    val x4: Dp = 16.dp
    val x6: Dp = 24.dp
    val x8: Dp = 32.dp

    /** Accessibility floor, section 1.6. Applies to keyboard keys too. */
    val minTouchTarget: Dp = 44.dp
}

object ChalkShapes {
    val key = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(12.dp)
    val button = RoundedCornerShape(12.dp)
    val suggestionStrip = RoundedCornerShape(20.dp)
    val iconButton = RoundedCornerShape(percent = 50)
}

val ChalkboardShapes = Shapes(
    small = ChalkShapes.key,
    medium = ChalkShapes.card,
    large = ChalkShapes.suggestionStrip,
)
