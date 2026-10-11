package com.chalkboard.keyboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.ui.Notice
import com.chalkboard.keyboard.ui.theme.ChalkColors
import com.chalkboard.keyboard.ui.theme.ChalkShapes
import com.chalkboard.keyboard.ui.theme.ChalkSpacing
import com.chalkboard.keyboard.ui.theme.ChalkType

/**
 * The paper surface's shared parts: app bar, grouped list, pill button, notice card.
 *
 * They live together because the frame gallery draws them identically on every screen —
 * a 48dp white bar over a hairline, cards at radius 12, one chalk-yellow action per
 * screen — and the fastest way for that to stop being true is for each screen to build
 * its own.
 */

/** App bar plus paper background. [onBack] draws the back affordance when present. */
@Composable
fun ChalkScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = ChalkColors.surfacePaper,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        topBar = { ChalkAppBar(title, onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ChalkColors.surfacePaper)
                .padding(padding),
        ) {
            content()
        }
    }
}

@Composable
private fun ChalkAppBar(title: String, onBack: (() -> Unit)?) {
    // Android 15 draws every app edge to edge, so without the inset the title sits
    // underneath the clock. The bar's white runs up behind the status bar instead.
    Column(Modifier.background(ChalkColors.surfacePaperCard).statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(ChalkColors.surfacePaperCard)
                .height(48.dp)
                .padding(horizontal = ChalkSpacing.x4),
        ) {
            if (onBack != null) {
                // The chevron is 24dp of ink inside a 44dp target: the glyph the gallery
                // draws, at the size section 1.6 requires it to be touchable at.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(ChalkSpacing.minTouchTarget)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.nav_back),
                            onClick = onBack,
                        ),
                ) {
                    Text("‹", style = ChalkType.title, color = ChalkColors.textInk)
                }
                Spacer(Modifier.width(ChalkSpacing.x2))
            }
            Text(
                text = title,
                style = ChalkType.label.copy(fontSize = ChalkType.body.fontSize),
                color = ChalkColors.textInk,
            )
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ChalkColors.borderHairline)
        )
    }
}

/** A white card holding rows, with a hairline between them but not after the last. */
@Composable
fun ListCard(content: @Composable () -> Unit) {
    Card(
        shape = ChalkShapes.card,
        colors = CardDefaults.cardColors(containerColor = ChalkColors.surfacePaperCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
fun RowDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ChalkColors.borderHairline)
    )
}

/**
 * One navigable row (section C1). [value] is the row's current setting, stated in the
 * list so the list answers "how is this configured" without being opened.
 */
@Composable
fun SettingsRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            // 12dp above and below a 24sp line already clears the 44dp floor, and lets a
            // long title wrap instead of being clipped by a fixed height.
            .padding(horizontal = ChalkSpacing.x4, vertical = ChalkSpacing.x3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ChalkType.body, color = ChalkColors.textInk)
            if (value != null) {
                Text(value, style = ChalkType.caption, color = ChalkColors.textInkMuted)
            }
        }
        Text("›", style = ChalkType.body, color = ChalkColors.borderHairline)
    }
}

/** The one chalk-yellow action a screen is allowed. Never two on the same screen. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ChalkShapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = ChalkColors.accentChalkYellow,
            contentColor = ChalkColors.textInk,
            disabledContainerColor = ChalkColors.borderHairline,
            disabledContentColor = ChalkColors.textInkMuted,
        ),
        modifier = modifier.height(ChalkSpacing.minTouchTarget),
    ) {
        Text(text, style = ChalkType.label)
    }
}

/** The small uppercase label above a field, sections D2 and D4. */
@Composable
fun FieldLabel(text: String) {
    Text(
        text = text,
        style = ChalkType.caption.copy(letterSpacing = 0.08.em),
        color = ChalkColors.textInkMuted,
    )
    Spacer(Modifier.height(ChalkSpacing.x2))
}

/**
 * The paper equivalent of the keyboard's [com.chalkboard.keyboard.ui.components.StatusBanner]
 * — same two-part copy, same severity rule, in the settings palette (sections E2, E3).
 */
@Composable
fun NoticeCard(notice: Notice, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accent = when (notice.severity) {
        Notice.Severity.FAILURE -> ChalkColors.stateError
        Notice.Severity.NEUTRAL -> ChalkColors.textInkMuted
    }
    Column(modifier) {
        Text(notice.title(context), style = ChalkType.label, color = accent)
        notice.body(context)?.let { body ->
            Text(body, style = ChalkType.caption, color = ChalkColors.textInkMuted)
        }
    }
}

/** Status dot plus the same status as a word — colour never carries it alone. */
@Composable
fun StatusDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChalkSpacing.x2),
    ) {
        Spacer(Modifier.size(ChalkSpacing.x2).clip(CircleShape).background(color))
        Text(label, style = ChalkType.caption, color = ChalkColors.textInkMuted)
    }
}
