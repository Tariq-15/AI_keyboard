package com.chalkboard.keyboard.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chalkboard.keyboard.ai.AIProvider
import com.chalkboard.keyboard.ai.CustomAssistant
import com.chalkboard.keyboard.ai.ProviderRegistry
import com.chalkboard.keyboard.ai.ToolStore
import com.chalkboard.keyboard.grammar.GrammarMode
import com.chalkboard.keyboard.grammar.GrammarService
import com.chalkboard.keyboard.ime.KeyboardPrefs
import com.chalkboard.keyboard.prediction.PersonalLearningStore
import com.chalkboard.keyboard.ui.Notice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One provider card in settings (sections D3, E2, E3).
 *
 * [keyHint] is the last four characters of the stored key and the only part of it that
 * is ever rendered. [failure] is why this provider last failed, or null when it is
 * simply working — it outlives the request that produced it so the card can explain
 * itself when the user comes looking, rather than only in a toast they had to catch.
 */
data class ProviderRow(
    val id: String,
    val displayName: String,
    val keyUrl: String,
    val hasKey: Boolean,
    val isDefault: Boolean,
    val keyHint: String?,
    val failure: Notice?,
)

/** The outcome of the most recent Validate tap, shown under the button (section D2). */
sealed interface Validation {
    data class Connected(val providerName: String) : Validation
    data class Failed(val notice: Notice) : Validation
}

data class SettingsState(
    val providers: List<ProviderRow> = emptyList(),
    val serverUrl: String = "",
    val customBaseUrl: String = "",
    val customModel: String = "",
    /** Distinct words in the personal store, for the prediction row's subtitle. */
    val learnedWords: Int = 0,
    val grammarMode: GrammarMode = GrammarMode.CASUAL,
    val numberRow: Boolean = true,
    val assistants: List<CustomAssistant> = emptyList(),
    /** Translation targets the user added, beyond the built-in ones. */
    val languages: List<String> = emptyList(),
    /** Provider id whose key is currently being validated, if any. */
    val validating: String? = null,
    val validation: Validation? = null,
    /** Transient confirmation, shown once and cleared. */
    val message: String? = null,
) {
    val connectedCount: Int get() = providers.count { it.hasKey }
    val usesServer: Boolean get() = serverUrl.isNotBlank()
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val registry = ProviderRegistry.create(application)
    private val keyboardPrefs = KeyboardPrefs(application)
    private val toolStore = ToolStore(application)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val (customUrl, customModel) = registry.customEndpoint()
        _state.value = _state.value.copy(
            providers = registry.allProviders().map { it.toRow() },
            serverUrl = GrammarService.serverUrl(getApplication()).orEmpty(),
            customBaseUrl = customUrl,
            customModel = customModel,
            grammarMode = keyboardPrefs.grammarMode,
            numberRow = keyboardPrefs.numberRow,
            assistants = toolStore.assistants(),
            languages = toolStore.languages(),
        )
        refreshLearnedWordCount()
    }

    fun setGrammarMode(mode: GrammarMode) {
        keyboardPrefs.grammarMode = mode
        refresh()
    }

    fun setNumberRow(on: Boolean) {
        keyboardPrefs.numberRow = on
        refresh()
    }

    /** Both halves are required: a name with no instruction is a button that does nothing. */
    fun addAssistant(name: String, instruction: String) {
        if (name.isBlank() || instruction.isBlank()) return
        toolStore.setAssistants(
            toolStore.assistants() + CustomAssistant(name.trim(), instruction.trim())
        )
        refresh()
    }

    fun removeAssistant(assistant: CustomAssistant) {
        toolStore.setAssistants(toolStore.assistants() - assistant)
        refresh()
    }

    fun addLanguage(language: String) {
        val name = language.trim()
        if (name.isEmpty() || toolStore.languages().any { it.equals(name, ignoreCase = true) }) return
        toolStore.setLanguages(toolStore.languages() + name)
        refresh()
    }

    fun removeLanguage(language: String) {
        toolStore.setLanguages(toolStore.languages() - language)
        refresh()
    }

    /** Reads SQLite, so never on the thread that is drawing the list. */
    private fun refreshLearnedWordCount() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                PersonalLearningStore(getApplication()).use { it.distinctWordCount() }
            }
            _state.value = _state.value.copy(learnedWords = count)
        }
    }

    private fun AIProvider.toRow() = ProviderRow(
        id = id,
        displayName = displayName,
        keyUrl = keyUrl,
        hasKey = registry.hasKey(id),
        isDefault = registry.active()?.id == id,
        keyHint = registry.keyHint(id),
        failure = registry.lastFailure(id),
    )

    /**
     * Validates the key with the provider before storing it. A key that does not work is
     * never saved, so the keyboard cannot end up showing a rewrite button backed by a
     * dead credential.
     */
    fun addKey(providerId: String, apiKey: String, addedLabel: String) {
        if (apiKey.isBlank()) return
        _state.value = _state.value.copy(
            validating = providerId,
            validation = null,
            message = null,
        )
        viewModelScope.launch {
            val result = registry.addKey(providerId, apiKey)
            val name = registry.provider(providerId)?.displayName.orEmpty()
            _state.value = _state.value.copy(
                validating = null,
                validation = result.fold(
                    onSuccess = { Validation.Connected(name) },
                    onFailure = { Validation.Failed(registry.noticeFor(it)) },
                ),
                message = result.fold(onSuccess = { addedLabel }, onFailure = { null }),
            )
            refresh()
        }
    }

    fun removeKey(providerId: String, removedLabel: String) {
        registry.removeKey(providerId)
        _state.value = _state.value.copy(message = removedLabel, validation = null)
        refresh()
    }

    fun setDefault(providerId: String) {
        registry.setActive(providerId)
        refresh()
    }

    fun setCustomEndpoint(baseUrl: String, model: String) {
        registry.setCustomEndpoint(baseUrl, model)
        refresh()
    }

    fun setServerUrl(url: String, savedLabel: String) {
        GrammarService.setServerUrl(getApplication(), url)
        _state.value = _state.value.copy(message = savedLabel)
        refresh()
    }

    fun clearLearnedWords(clearedLabel: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                PersonalLearningStore(getApplication()).use { it.clear() }
            }
            _state.value = _state.value.copy(message = clearedLabel, learnedWords = 0)
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Clears the D2 result banner when the user leaves or restarts the add-key flow. */
    fun consumeValidation() {
        _state.value = _state.value.copy(validation = null)
    }
}
