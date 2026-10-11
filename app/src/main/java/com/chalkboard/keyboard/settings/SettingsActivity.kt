package com.chalkboard.keyboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chalkboard.keyboard.BuildConfig
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.ai.CustomEndpointProvider
import com.chalkboard.keyboard.grammar.GrammarMode
import com.chalkboard.keyboard.ime.KeyboardIME
import com.chalkboard.keyboard.ui.theme.ChalkColors
import com.chalkboard.keyboard.ui.theme.ChalkShapes
import com.chalkboard.keyboard.ui.theme.ChalkSpacing
import com.chalkboard.keyboard.ui.theme.ChalkType
import com.chalkboard.keyboard.ui.theme.ChalkboardTheme

/**
 * The paper surface. A settings home that lists what is configurable and what each thing
 * is currently set to (section C1), over a handful of detail screens.
 *
 * It is a list of destinations rather than one long scroll of cards because the AI
 * provider flow alone is four screens — picking a provider, entering a key, watching it
 * validate, choosing a default — and folding that into a page that also holds spelling
 * settings and a privacy statement buries all of it.
 */
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChalkType.load(this)
        val start = Screen.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SCREEN) }
            ?: Screen.HOME
        setContent {
            ChalkboardTheme {
                SettingsApp(viewModel, start)
            }
        }
    }

    companion object {
        /**
         * Names the screen to open on. Used by the keyboard's own shortcuts into
         * settings — "add a key", "add an assistant" — so they land where they said.
         */
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_PROVIDERS = "PROVIDERS"
        const val SCREEN_TOOLS = "TOOLS"
    }
}

/**
 * Where the user is. Held as state rather than pulled in as a navigation library: seven
 * destinations, no deep links and no arguments to pass is not what that dependency is
 * for.
 */
private enum class Screen {
    HOME, GRAMMAR, TOOLS, PREDICTION, PROVIDERS, ADD_PROVIDER, CUSTOM_ENDPOINT, LAYOUT, LANGUAGE,
    ABOUT;

    /** Where Back goes. The two provider forms return to the list, not to the top. */
    val parent: Screen?
        get() = when (this) {
            HOME -> null
            ADD_PROVIDER, CUSTOM_ENDPOINT -> PROVIDERS
            else -> HOME
        }
}

@Composable
private fun SettingsApp(viewModel: SettingsViewModel, start: Screen) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf(start) }

    // The user leaves the app to change both of these, so re-read them on resume.
    var enabled by remember { mutableStateOf(isKeyboardEnabled(context)) }
    var active by remember { mutableStateOf(isKeyboardActive(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = isKeyboardEnabled(context)
                active = isKeyboardActive(context)
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val goBack: () -> Unit = { screen.parent?.let { parent -> screen = parent } }
    BackHandler(enabled = screen.parent != null) { goBack() }

    // A stale "Connected to Gemini" under the Validate button on a screen the user has
    // come back to a day later is worse than no result at all.
    LaunchedEffect(screen) {
        if (screen != Screen.ADD_PROVIDER && screen != Screen.CUSTOM_ENDPOINT) {
            viewModel.consumeValidation()
        }
    }

    when (screen) {
        Screen.HOME -> ChalkScreen(
            title = stringResource(R.string.settings_title),
            snackbar = snackbar,
        ) {
            HomeScreen(
                state = state,
                enabled = enabled,
                active = active,
                onNavigate = { screen = it },
                onOpenImeSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                onOpenImePicker = {
                    context.getSystemService(InputMethodManager::class.java)
                        ?.showInputMethodPicker()
                },
            )
        }

        Screen.GRAMMAR -> ChalkScreen(
            title = stringResource(R.string.grammar_screen_title),
            onBack = goBack,
            snackbar = snackbar,
        ) {
            GrammarScreen(
                mode = state.grammarMode,
                onMode = viewModel::setGrammarMode,
                serverUrl = state.serverUrl,
                onSave = { url ->
                    viewModel.setServerUrl(url, context.getString(R.string.grammar_saved))
                },
            )
        }

        Screen.TOOLS -> ChalkScreen(
            title = stringResource(R.string.tools_title),
            onBack = goBack,
            snackbar = snackbar,
        ) {
            ToolsScreen(
                assistants = state.assistants,
                languages = state.languages,
                onAddAssistant = viewModel::addAssistant,
                onRemoveAssistant = viewModel::removeAssistant,
                onAddLanguage = viewModel::addLanguage,
                onRemoveLanguage = viewModel::removeLanguage,
            )
        }

        Screen.LAYOUT -> ChalkScreen(
            title = stringResource(R.string.layout_screen_title),
            onBack = goBack,
        ) {
            LayoutScreen(numberRow = state.numberRow, onNumberRow = viewModel::setNumberRow)
        }

        Screen.PREDICTION -> ChalkScreen(
            title = stringResource(R.string.prediction_screen_title),
            onBack = goBack,
            snackbar = snackbar,
        ) {
            PredictionScreen(
                learnedWords = state.learnedWords,
                onClear = {
                    viewModel.clearLearnedWords(context.getString(R.string.learning_cleared))
                },
            )
        }

        Screen.PROVIDERS -> ChalkScreen(
            title = stringResource(R.string.providers_screen_title),
            onBack = goBack,
            snackbar = snackbar,
        ) {
            ProvidersScreen(
                state = state,
                onAdd = { screen = Screen.ADD_PROVIDER },
                onRemove = { id ->
                    viewModel.removeKey(id, context.getString(R.string.providers_key_removed))
                },
                onSetDefault = viewModel::setDefault,
            )
        }

        Screen.ADD_PROVIDER -> ChalkScreen(
            title = stringResource(R.string.providers_add_title),
            onBack = goBack,
            snackbar = snackbar,
        ) {
            AddProviderScreen(
                state = state,
                onValidate = { id, key ->
                    viewModel.addKey(id, key, context.getString(R.string.providers_key_added))
                },
                onOpenCustom = { screen = Screen.CUSTOM_ENDPOINT },
                onOpenKeyUrl = { url ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
        }

        Screen.CUSTOM_ENDPOINT -> ChalkScreen(
            title = stringResource(R.string.providers_custom_title),
            onBack = goBack,
            snackbar = snackbar,
        ) {
            CustomEndpointScreen(
                state = state,
                onValidate = { baseUrl, model, key ->
                    // The endpoint has to be stored before the key is checked: validation
                    // is a real request to that URL, and there is nowhere to send it
                    // until the registry knows where "custom" points.
                    viewModel.setCustomEndpoint(baseUrl, model)
                    viewModel.addKey(
                        CustomEndpointProvider.ID,
                        key,
                        context.getString(R.string.providers_key_added),
                    )
                },
            )
        }

        Screen.LANGUAGE -> ChalkScreen(
            title = stringResource(R.string.language_screen_title),
            onBack = goBack,
        ) { LanguageScreen() }

        Screen.ABOUT -> ChalkScreen(
            title = stringResource(R.string.about_screen_title),
            onBack = goBack,
        ) { AboutScreen() }
    }
}

// --- C1: settings home ----------------------------------------------------------

@Composable
private fun HomeScreen(
    state: SettingsState,
    enabled: Boolean,
    active: Boolean,
    onNavigate: (Screen) -> Unit,
    onOpenImeSettings: () -> Unit,
    onOpenImePicker: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ChalkSpacing.x4),
        verticalArrangement = Arrangement.spacedBy(ChalkSpacing.x4),
    ) {
        // Setup comes first while it is unfinished and disappears once it is done: a
        // keyboard that is not enabled has exactly one thing worth doing to it, and a
        // keyboard that is enabled should not keep saying so at the top of every visit.
        if (!enabled) {
            StepCard(
                title = stringResource(R.string.settings_step_enable),
                body = stringResource(R.string.settings_step_enable_body),
                status = stringResource(R.string.settings_status_not_enabled),
                action = stringResource(R.string.settings_action_enable),
                onAction = onOpenImeSettings,
            )
        } else if (!active) {
            StepCard(
                title = stringResource(R.string.settings_step_select),
                body = stringResource(R.string.settings_step_select_body),
                status = stringResource(R.string.settings_status_not_active),
                action = stringResource(R.string.settings_action_select),
                onAction = onOpenImePicker,
            )
        }

        ListCard {
            Column {
                SettingsRow(
                    title = stringResource(R.string.settings_row_grammar),
                    value = stringResource(
                        R.string.settings_value_grammar,
                        stringResource(
                            if (state.grammarMode == GrammarMode.FORMAL) R.string.grammar_mode_formal
                            else R.string.grammar_mode_casual
                        ),
                        stringResource(
                            if (state.usesServer) R.string.settings_value_grammar_server
                            else R.string.settings_value_grammar_on_device
                        ),
                    ),
                    onClick = { onNavigate(Screen.GRAMMAR) },
                )
                RowDivider()
                SettingsRow(
                    title = stringResource(R.string.tools_title),
                    value = if (state.assistants.isEmpty()) {
                        stringResource(R.string.settings_value_assistants_none)
                    } else {
                        pluralStringResource(
                            R.plurals.settings_value_assistants,
                            state.assistants.size,
                            state.assistants.size,
                        )
                    },
                    onClick = { onNavigate(Screen.TOOLS) },
                )
                RowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_row_prediction),
                    value = pluralStringResource(
                        R.plurals.settings_value_words_saved,
                        state.learnedWords,
                        state.learnedWords,
                    ),
                    onClick = { onNavigate(Screen.PREDICTION) },
                )
                RowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_row_providers),
                    value = if (state.connectedCount == 0) {
                        stringResource(R.string.settings_value_providers_none)
                    } else {
                        pluralStringResource(
                            R.plurals.settings_value_providers_connected,
                            state.connectedCount,
                            state.connectedCount,
                        )
                    },
                    onClick = { onNavigate(Screen.PROVIDERS) },
                )
                RowDivider()
                SettingsRow(
                    title = stringResource(R.string.layout_screen_title),
                    value = stringResource(
                        if (state.numberRow) R.string.settings_value_number_row_on
                        else R.string.settings_value_number_row_off
                    ),
                    onClick = { onNavigate(Screen.LAYOUT) },
                )
                RowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_row_language),
                    value = stringResource(R.string.subtype_en),
                    onClick = { onNavigate(Screen.LANGUAGE) },
                )
                RowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_row_about),
                    onClick = { onNavigate(Screen.ABOUT) },
                )
            }
        }

        var draft by remember { mutableStateOf("") }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(R.string.settings_try_it), style = ChalkType.label) },
            textStyle = ChalkType.body,
            shape = ChalkShapes.card,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StepCard(
    title: String,
    body: String,
    status: String,
    action: String,
    onAction: () -> Unit,
) {
    Card(
        shape = ChalkShapes.card,
        colors = CardDefaults.cardColors(containerColor = ChalkColors.surfacePaperCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(ChalkSpacing.x4)) {
            StatusDot(color = ChalkColors.borderHairline, label = status)
            Spacer(Modifier.height(ChalkSpacing.x2))
            Text(title, style = ChalkType.title, color = ChalkColors.textInk)
            Spacer(Modifier.height(ChalkSpacing.x1))
            Text(body, style = ChalkType.body, color = ChalkColors.textInkMuted)
            Spacer(Modifier.height(ChalkSpacing.x4))
            PillButton(text = action, onClick = onAction)
        }
    }
}

// --- Identity of our own IME service --------------------------------------------

/**
 * Compared as a [ComponentName] rather than by string suffix: the platform stores the
 * *short* form ("com.chalkboard.keyboard/.ime.KeyboardIME"), while
 * `ComponentName.getClassName()` is fully qualified. Matching on text made both status
 * rows read "Not enabled" on a device where the keyboard was in fact enabled and active.
 */
private fun imeComponent(context: Context) = ComponentName(context, KeyboardIME::class.java)

private fun isKeyboardEnabled(context: Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
    val expected = imeComponent(context)
    return imm.enabledInputMethodList.any { ComponentName.unflattenFromString(it.id) == expected }
}

private fun isKeyboardActive(context: Context): Boolean {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    ) ?: return false
    return ComponentName.unflattenFromString(current) == imeComponent(context)
}

// --- Version, for the about screen ----------------------------------------------

internal val appVersionName: String get() = BuildConfig.VERSION_NAME
