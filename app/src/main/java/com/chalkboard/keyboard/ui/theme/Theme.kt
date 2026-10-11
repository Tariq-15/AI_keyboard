package com.chalkboard.keyboard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The paper surface: settings and onboarding. Section 1.1.
 *
 * There is deliberately no dark variant. The two surfaces in this product are paper
 * (containing app) and slate (keyboard); the keyboard is View-rendered and reads its
 * colours straight from res/values/colors.xml.
 */
private val paperColorScheme = lightColorScheme(
    primary = ChalkColors.accentChalkYellow,
    onPrimary = ChalkColors.textInk,
    secondary = ChalkColors.accentDustyTeal,
    onSecondary = ChalkColors.surfacePaperCard,
    background = ChalkColors.surfacePaper,
    onBackground = ChalkColors.textInk,
    surface = ChalkColors.surfacePaperCard,
    onSurface = ChalkColors.textInk,
    onSurfaceVariant = ChalkColors.textInkMuted,
    outlineVariant = ChalkColors.borderHairline,
    error = ChalkColors.stateError,
)

@Composable
fun ChalkboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = paperColorScheme,
        typography = chalkboardTypography,
        shapes = ChalkboardShapes,
        content = content,
    )
}
