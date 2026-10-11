package com.chalkboard.keyboard.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.ai.CustomAssistant
import com.chalkboard.keyboard.ui.theme.ChalkColors
import com.chalkboard.keyboard.ui.theme.ChalkShapes
import com.chalkboard.keyboard.ui.theme.ChalkSpacing
import com.chalkboard.keyboard.ui.theme.ChalkType

/**
 * The parts of the tools panel that need typing to set up, and the layout switches.
 *
 * They live here rather than in the keyboard because the keyboard cannot type into
 * itself: naming an assistant or a language needs a text field, and the only text field
 * the app owns is on the paper side.
 */

@Composable
fun ToolsScreen(
    assistants: List<CustomAssistant>,
    languages: List<String>,
    onAddAssistant: (name: String, instruction: String) -> Unit,
    onRemoveAssistant: (CustomAssistant) -> Unit,
    onAddLanguage: (String) -> Unit,
    onRemoveLanguage: (String) -> Unit,
) {
    DetailColumn {
        SectionCard(title = stringResource(R.string.assistants_title)) {
            var name by rememberSaveable { mutableStateOf("") }
            var instruction by rememberSaveable { mutableStateOf("") }

            Explainer(R.string.assistants_explainer)
            assistants.forEach { assistant ->
                RemovableRow(
                    title = assistant.name,
                    body = assistant.instruction,
                    onRemove = { onRemoveAssistant(assistant) },
                )
            }
            Spacer(Modifier.height(ChalkSpacing.x3))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.assistants_name_hint), style = ChalkType.label) },
                textStyle = ChalkType.body,
                singleLine = true,
                shape = ChalkShapes.card,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(ChalkSpacing.x2))
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                label = {
                    Text(stringResource(R.string.assistants_instruction_hint), style = ChalkType.label)
                },
                textStyle = ChalkType.body,
                minLines = 2,
                shape = ChalkShapes.card,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(ChalkSpacing.x3))
            PillButton(
                text = stringResource(R.string.assistants_add),
                onClick = {
                    onAddAssistant(name, instruction)
                    name = ""
                    instruction = ""
                },
                enabled = name.isNotBlank() && instruction.isNotBlank(),
            )
        }

        SectionCard(title = stringResource(R.string.languages_title)) {
            var language by rememberSaveable { mutableStateOf("") }

            Explainer(R.string.languages_explainer)
            languages.forEach { added ->
                RemovableRow(title = added, body = null, onRemove = { onRemoveLanguage(added) })
            }
            Spacer(Modifier.height(ChalkSpacing.x3))
            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                label = { Text(stringResource(R.string.languages_hint), style = ChalkType.label) },
                textStyle = ChalkType.body,
                singleLine = true,
                shape = ChalkShapes.card,
                modifier = Modifier.fillMaxWidth(),
            )
            // A text button, not a second pill: one chalk-yellow action per screen.
            TextButton(
                onClick = {
                    onAddLanguage(language)
                    language = ""
                },
                enabled = language.isNotBlank(),
            ) {
                Text(stringResource(R.string.languages_add), style = ChalkType.label)
            }
        }
    }
}

@Composable
fun LayoutScreen(numberRow: Boolean, onNumberRow: (Boolean) -> Unit) {
    DetailColumn {
        SectionCard(title = stringResource(R.string.layout_number_row_title)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ChalkSpacing.minTouchTarget)
                    .toggleable(value = numberRow, role = Role.Switch, onValueChange = onNumberRow),
            ) {
                Text(
                    text = stringResource(R.string.layout_number_row_body),
                    style = ChalkType.body,
                    color = ChalkColors.textInkMuted,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = numberRow,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ChalkColors.surfacePaperCard,
                        checkedTrackColor = ChalkColors.accentDustyTeal,
                        uncheckedThumbColor = ChalkColors.textInkMuted,
                        uncheckedTrackColor = ChalkColors.surfacePaper,
                        uncheckedBorderColor = ChalkColors.borderHairline,
                    ),
                )
            }
        }

        SectionCard(title = stringResource(R.string.layout_hints_title)) {
            Explainer(R.string.layout_hints_body)
        }
    }
}

@Composable
private fun Explainer(@StringRes textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = ChalkType.body,
        color = ChalkColors.textInkMuted,
    )
}

/** Something the user added, with the one thing that can be done to it. */
@Composable
private fun RemovableRow(title: String, body: String?, onRemove: () -> Unit) {
    Spacer(Modifier.height(ChalkSpacing.x2))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ChalkType.body, color = ChalkColors.textInk)
            if (body != null) {
                Text(
                    text = body,
                    style = ChalkType.caption,
                    color = ChalkColors.textInkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onRemove) {
            Text(
                stringResource(R.string.providers_remove),
                style = ChalkType.label,
                color = ChalkColors.stateError,
            )
        }
    }
}
