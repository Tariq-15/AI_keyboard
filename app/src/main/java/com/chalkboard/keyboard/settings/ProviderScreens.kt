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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.ai.CustomEndpointProvider
import com.chalkboard.keyboard.ui.theme.ChalkColors
import com.chalkboard.keyboard.ui.theme.ChalkShapes
import com.chalkboard.keyboard.ui.theme.ChalkSpacing
import com.chalkboard.keyboard.ui.theme.ChalkType

/**
 * The AI-rewrite screens: sections D1-D4, with the per-provider failures of E2 and E3.
 *
 * Rewriting is the one feature here the user pays a third party for, so every screen
 * says plainly what is stored, what is sent and what happens without it. The empty state
 * is instructional rather than decorative (section 1.5): one sentence naming what is
 * missing, one action to fix it, and an explicit statement that nothing else is degraded.
 */

// --- D1 / D3: the provider list -------------------------------------------------

@Composable
fun ProvidersScreen(
    state: SettingsState,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onSetDefault: (String) -> Unit,
) {
    val connected = state.providers.filter { it.hasKey }

    if (connected.isEmpty()) {
        ProvidersEmptyState(onAdd)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ChalkSpacing.x4),
        verticalArrangement = Arrangement.spacedBy(ChalkSpacing.x3),
    ) {
        connected.forEach { provider ->
            ProviderCard(
                provider = provider,
                onRemove = { onRemove(provider.id) },
                onSetDefault = { onSetDefault(provider.id) },
            )
        }
        PillButton(
            text = stringResource(R.string.providers_add_key_action),
            onClick = onAdd,
        )
        Text(
            text = stringResource(R.string.providers_explainer),
            style = ChalkType.caption,
            color = ChalkColors.textInkMuted,
        )
    }
}

@Composable
private fun ProvidersEmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ChalkSpacing.x8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.providers_empty_headline),
            style = ChalkType.display,
            color = ChalkColors.textInk,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(ChalkSpacing.x4))
        Text(
            text = stringResource(R.string.providers_empty_body),
            style = ChalkType.body,
            color = ChalkColors.textInkMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(ChalkSpacing.x6))
        PillButton(text = stringResource(R.string.providers_add_key_action), onClick = onAdd)
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderRow,
    onRemove: () -> Unit,
    onSetDefault: () -> Unit,
) {
    Card(
        shape = ChalkShapes.card,
        colors = CardDefaults.cardColors(containerColor = ChalkColors.surfacePaperCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(ChalkSpacing.x4),
            verticalArrangement = Arrangement.spacedBy(ChalkSpacing.x2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(ChalkSpacing.x2)
                        .clip(CircleShape)
                        .background(
                            if (provider.failure != null) ChalkColors.stateError
                            else if (provider.isDefault) ChalkColors.accentDustyTeal
                            else ChalkColors.borderHairline
                        )
                )
                Spacer(Modifier.width(ChalkSpacing.x2))
                Text(
                    text = provider.displayName,
                    style = ChalkType.label.copy(fontSize = ChalkType.body.fontSize),
                    color = ChalkColors.textInk,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(R.string.providers_remove),
                        style = ChalkType.caption,
                        color = ChalkColors.textInkMuted,
                    )
                }
            }

            provider.keyHint?.let { hint ->
                Text(
                    text = stringResource(R.string.providers_masked_key, hint),
                    style = ChalkType.caption,
                    color = ChalkColors.textInkMuted,
                )
            }

            provider.failure?.let { NoticeCard(it) }

            DefaultChoice(isDefault = provider.isDefault, onSetDefault = onSetDefault)
        }
    }
}

/**
 * The default-provider radio (section D3). Already-default reads "Default provider" and
 * is inert; the others read "Set as default" and are the action that changes it.
 */
@Composable
private fun DefaultChoice(isDefault: Boolean, onSetDefault: () -> Unit) {
    val label = stringResource(
        if (isDefault) R.string.providers_default else R.string.providers_set_default
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.selectable(
            selected = isDefault,
            enabled = !isDefault,
            role = Role.RadioButton,
            onClick = onSetDefault,
        ),
    ) {
        RadioButton(
            selected = isDefault,
            // Null rather than a no-op: the already-default row is a statement, and a
            // radio you can press without anything happening reads as broken.
            onClick = if (isDefault) null else onSetDefault,
            colors = RadioButtonDefaults.colors(
                selectedColor = ChalkColors.accentChalkYellow,
                unselectedColor = ChalkColors.borderHairline,
            ),
        )
        Text(label, style = ChalkType.caption, color = ChalkColors.textInkMuted)
    }
}

// --- D2: add a provider ---------------------------------------------------------

@Composable
fun AddProviderScreen(
    state: SettingsState,
    onValidate: (providerId: String, apiKey: String) -> Unit,
    onOpenCustom: () -> Unit,
    onOpenKeyUrl: (String) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    // The saved selection is re-checked against the live list rather than trusted: the
    // screen can be restored before the registry has been read, and "custom" is never a
    // valid selection here — it has its own screen.
    val selectedId = selected.takeIf { id -> state.providers.any { it.id == id } }
        ?: state.providers.firstOrNull { it.id != CustomEndpointProvider.ID }?.id.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ChalkSpacing.x4),
        verticalArrangement = Arrangement.spacedBy(ChalkSpacing.x6),
    ) {
        Column {
            FieldLabel(stringResource(R.string.providers_label_provider))
            ListCard {
                Column {
                    state.providers.forEachIndexed { index, provider ->
                        if (index > 0) RowDivider()
                        ProviderChoiceRow(
                            provider = provider,
                            selected = provider.id == selectedId,
                            // The custom endpoint needs a URL and a model before a key
                            // means anything, so it gets its own screen rather than two
                            // fields that appear out of nowhere in this one.
                            onClick = {
                                if (provider.id == CustomEndpointProvider.ID) {
                                    onOpenCustom()
                                } else {
                                    selected = provider.id
                                }
                            },
                        )
                    }
                }
            }
        }

        Column {
            FieldLabel(stringResource(R.string.providers_label_api_key))
            ApiKeyField(value = apiKey, onValueChange = { apiKey = it })

            // Bring-your-own-key only works if the user can find out where to get one,
            // and every provider's page is somewhere different.
            val chosen = state.providers.firstOrNull { it.id == selectedId }
            if (chosen != null && chosen.keyUrl.isNotEmpty()) {
                TextButton(onClick = { onOpenKeyUrl(chosen.keyUrl) }) {
                    Text(
                        text = stringResource(R.string.providers_get_key, chosen.displayName),
                        style = ChalkType.caption,
                        color = ChalkColors.textInkMuted,
                    )
                }
            }
        }

        ValidateAction(
            state = state,
            providerId = selectedId,
            enabled = selectedId.isNotEmpty() && apiKey.isNotBlank(),
            onValidate = { onValidate(selectedId, apiKey) },
        )

        Text(
            text = stringResource(R.string.providers_explainer),
            style = ChalkType.caption,
            color = ChalkColors.textInkMuted,
        )
    }
}

@Composable
private fun ProviderChoiceRow(
    provider: ProviderRow,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        // A 3dp rule down the selected row, so the choice is legible in the layout and
        // not only in the dot at the far end of it.
        Spacer(
            Modifier
                .width(3.dp)
                .height(ChalkSpacing.minTouchTarget)
                .background(
                    if (selected) ChalkColors.accentChalkYellow else Color.Transparent
                )
        )
        Text(
            text = provider.displayName,
            style = ChalkType.body,
            color = ChalkColors.textInk,
            modifier = Modifier
                .weight(1f)
                .padding(start = ChalkSpacing.x4, top = ChalkSpacing.x3, bottom = ChalkSpacing.x3),
        )
        if (provider.hasKey) {
            Text(
                text = stringResource(R.string.providers_connected),
                style = ChalkType.caption,
                color = ChalkColors.textInkMuted,
                modifier = Modifier.padding(end = ChalkSpacing.x3),
            )
        }
        if (selected) {
            Spacer(
                Modifier
                    .padding(end = ChalkSpacing.x4)
                    .size(ChalkSpacing.x2)
                    .clip(CircleShape)
                    .background(ChalkColors.accentChalkYellow)
            )
        }
    }
}

// --- D4: custom endpoint --------------------------------------------------------

@Composable
fun CustomEndpointScreen(
    state: SettingsState,
    onValidate: (baseUrl: String, model: String, apiKey: String) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(state.customBaseUrl) }
    var model by rememberSaveable { mutableStateOf(state.customModel) }
    var apiKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ChalkSpacing.x4),
        verticalArrangement = Arrangement.spacedBy(ChalkSpacing.x6),
    ) {
        Column {
            FieldLabel(stringResource(R.string.providers_label_base_url))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                placeholder = {
                    Text(
                        stringResource(R.string.providers_custom_url_placeholder),
                        style = ChalkType.body,
                    )
                },
                textStyle = ChalkType.body,
                singleLine = true,
                shape = ChalkShapes.card,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column {
            FieldLabel(stringResource(R.string.providers_label_model))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                placeholder = {
                    Text(
                        stringResource(R.string.providers_custom_model_placeholder),
                        style = ChalkType.body,
                    )
                },
                textStyle = ChalkType.body,
                singleLine = true,
                shape = ChalkShapes.card,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column {
            FieldLabel(stringResource(R.string.providers_label_api_key))
            ApiKeyField(value = apiKey, onValueChange = { apiKey = it })
        }

        ValidateAction(
            state = state,
            providerId = CustomEndpointProvider.ID,
            enabled = baseUrl.isNotBlank() && apiKey.isNotBlank(),
            onValidate = { onValidate(baseUrl, model, apiKey) },
        )

        Text(
            text = stringResource(
                if (baseUrl.isBlank()) R.string.providers_custom_required
                else R.string.providers_explainer
            ),
            style = ChalkType.caption,
            color = ChalkColors.textInkMuted,
        )
    }
}

// --- Shared field and action ----------------------------------------------------

/**
 * The key is masked by default even while being typed, with an explicit reveal — the
 * one place in the app where a full key exists on screen, and only because the user
 * asked for it.
 */
@Composable
private fun ApiKeyField(value: String, onValueChange: (String) -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.providers_key_hint), style = ChalkType.label) },
        textStyle = ChalkType.body,
        singleLine = true,
        shape = ChalkShapes.card,
        visualTransformation = if (revealed) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            TextButton(onClick = { revealed = !revealed }) {
                Text(
                    text = stringResource(
                        if (revealed) R.string.providers_hide else R.string.providers_show
                    ),
                    style = ChalkType.caption,
                    color = ChalkColors.textInkMuted,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The Validate button and, beneath it, whichever of section D2's two outcomes applies. */
@Composable
private fun ValidateAction(
    state: SettingsState,
    providerId: String,
    enabled: Boolean,
    onValidate: () -> Unit,
) {
    val validating = state.validating == providerId

    Column(verticalArrangement = Arrangement.spacedBy(ChalkSpacing.x3)) {
        if (validating) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = ChalkColors.accentChalkYellow,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(ChalkSpacing.x3))
                Text(
                    stringResource(R.string.providers_checking),
                    style = ChalkType.body,
                    color = ChalkColors.textInkMuted,
                )
            }
        } else {
            PillButton(
                text = stringResource(R.string.providers_validate),
                onClick = onValidate,
                enabled = enabled,
            )
        }

        when (val result = state.validation) {
            is Validation.Connected -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓", style = ChalkType.body, color = ChalkColors.accentDustyTeal)
                Spacer(Modifier.width(ChalkSpacing.x2))
                Text(
                    text = stringResource(R.string.providers_connected_to, result.providerName),
                    style = ChalkType.caption,
                    color = ChalkColors.textInk,
                )
            }

            is Validation.Failed -> NoticeCard(result.notice)

            null -> Unit
        }
    }
}
