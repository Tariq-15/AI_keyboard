package com.chalkboard.keyboard.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.grammar.GrammarMode
import com.chalkboard.keyboard.ui.theme.ChalkColors
import com.chalkboard.keyboard.ui.theme.ChalkShapes
import com.chalkboard.keyboard.ui.theme.ChalkSpacing
import com.chalkboard.keyboard.ui.theme.ChalkType

/** Grammar, prediction, language and about — the four leaves off the settings home. */

/** Shared card shell for a settings section. One soft shadow, never stacked. */
@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = ChalkShapes.card,
        colors = CardDefaults.cardColors(containerColor = ChalkColors.surfacePaperCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(ChalkSpacing.x4)) {
            Text(title, style = ChalkType.title, color = ChalkColors.textInk)
            Spacer(Modifier.height(ChalkSpacing.x3))
            content()
        }
    }
}

@Composable
internal fun DetailColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ChalkSpacing.x4),
        verticalArrangement = Arrangement.spacedBy(ChalkSpacing.x4),
    ) {
        content()
    }
}

@Composable
fun GrammarScreen(
    mode: GrammarMode,
    onMode: (GrammarMode) -> Unit,
    serverUrl: String,
    onSave: (String) -> Unit,
) {
    var draft by remember(serverUrl) { mutableStateOf(serverUrl) }

    DetailColumn {
        SectionCard(title = stringResource(R.string.grammar_mode_title)) {
            ModeChoice(
                title = R.string.grammar_mode_casual,
                body = R.string.grammar_mode_casual_body,
                selected = mode == GrammarMode.CASUAL,
                onSelect = { onMode(GrammarMode.CASUAL) },
            )
            ModeChoice(
                title = R.string.grammar_mode_formal,
                body = R.string.grammar_mode_formal_body,
                selected = mode == GrammarMode.FORMAL,
                onSelect = { onMode(GrammarMode.FORMAL) },
            )
            Spacer(Modifier.height(ChalkSpacing.x2))
            Text(
                text = stringResource(R.string.grammar_mode_hint),
                style = ChalkType.caption,
                color = ChalkColors.textInkMuted,
            )
        }

        SectionCard(title = stringResource(R.string.grammar_server_title)) {
            // Which tier is in force, stated rather than switched silently (section 2.3).
            // Driven by the saved address, not the draft: a line that flips to "server"
            // as soon as a character is typed is reporting an intention, not a state.
            StatusDot(
                color = if (serverUrl.isNotBlank()) {
                    ChalkColors.accentDustyTeal
                } else {
                    ChalkColors.borderHairline
                },
                label = stringResource(
                    if (serverUrl.isNotBlank()) R.string.grammar_source_server
                    else R.string.grammar_source_on_device
                ),
            )
            Spacer(Modifier.height(ChalkSpacing.x3))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = {
                    Text(stringResource(R.string.grammar_server_hint), style = ChalkType.label)
                },
                textStyle = ChalkType.body,
                singleLine = true,
                shape = ChalkShapes.card,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(ChalkSpacing.x2))
            Text(
                text = stringResource(R.string.grammar_server_explainer),
                style = ChalkType.caption,
                color = ChalkColors.textInkMuted,
            )
            Spacer(Modifier.height(ChalkSpacing.x3))
            PillButton(
                text = stringResource(R.string.grammar_save),
                onClick = { onSave(draft) },
                enabled = draft != serverUrl,
            )
        }
    }
}

/** One of the two grammar modes: the whole row is the radio, and says what the mode does. */
@Composable
private fun ModeChoice(
    @StringRes title: Int,
    @StringRes body: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChalkSpacing.minTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = ChalkSpacing.x2),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = ChalkColors.accentChalkYellow,
                unselectedColor = ChalkColors.borderHairline,
            ),
        )
        Spacer(Modifier.width(ChalkSpacing.x3))
        Column {
            Text(stringResource(title), style = ChalkType.body, color = ChalkColors.textInk)
            Text(stringResource(body), style = ChalkType.caption, color = ChalkColors.textInkMuted)
        }
    }
}

@Composable
fun PredictionScreen(learnedWords: Int, onClear: () -> Unit) {
    DetailColumn {
        SectionCard(title = stringResource(R.string.learning_title)) {
            Text(
                text = pluralStringResource(
                    R.plurals.settings_value_words_saved,
                    learnedWords,
                    learnedWords,
                ),
                style = ChalkType.body,
                color = ChalkColors.textInk,
            )
            Spacer(Modifier.height(ChalkSpacing.x2))
            Text(
                text = stringResource(R.string.learning_explainer),
                style = ChalkType.body,
                color = ChalkColors.textInkMuted,
            )
            Spacer(Modifier.height(ChalkSpacing.x3))
            TextButton(onClick = onClear, enabled = learnedWords > 0) {
                Text(
                    stringResource(R.string.learning_clear),
                    style = ChalkType.label,
                    color = ChalkColors.stateError,
                )
            }
        }
    }
}

@Composable
fun LanguageScreen() {
    DetailColumn {
        SectionCard(title = stringResource(R.string.subtype_en)) {
            Text(
                text = stringResource(R.string.language_explainer),
                style = ChalkType.body,
                color = ChalkColors.textInkMuted,
            )
        }
    }
}

/**
 * Everything the user is owed in one place: what the app sends, when, the version they
 * are running, and the attribution the prediction corpus requires by licence.
 */
@Composable
fun AboutScreen() {
    DetailColumn {
        SectionCard(title = stringResource(R.string.privacy_title)) {
            listOf(
                R.string.privacy_prediction,
                R.string.privacy_grammar,
                R.string.privacy_ai,
                R.string.privacy_never,
            ).forEach { line ->
                Text(
                    text = stringResource(line),
                    style = ChalkType.body,
                    color = ChalkColors.textInkMuted,
                )
                Spacer(Modifier.height(ChalkSpacing.x2))
            }
        }

        SectionCard(title = stringResource(R.string.about_dictionary_title)) {
            Text(
                text = stringResource(R.string.about_dictionary_body),
                style = ChalkType.body,
                color = ChalkColors.textInkMuted,
            )
        }

        Text(
            text = stringResource(R.string.about_version, appVersionName),
            style = ChalkType.caption,
            color = ChalkColors.textInkMuted,
        )
    }
}
