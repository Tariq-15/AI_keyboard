package com.chalkboard.keyboard.ai

import android.content.Context
import androidx.annotation.StringRes
import com.chalkboard.keyboard.R
import org.json.JSONArray
import org.json.JSONObject

/** What the keyboard does with a tool's answer. */
enum class ToolOutput {
    /** Shown on a card; the user may insert it. Never touches the field by itself. */
    SHOW,

    /** A new version of the field's text, offered as a replacement. */
    REPLACE,

    /** Several short answers, each inserted with a tap. */
    REPLIES,
}

/** A second step some tools need before they can run. */
enum class ToolChoice { NONE, TONE, LANGUAGE }

/**
 * The built-in entries of the tools panel, in the order the panel lays them out.
 *
 * Every one is the same operation underneath — an instruction plus the user's text goes
 * to a model and text comes back — so a new tool is a new row here, not a new code path.
 */
enum class AiTool(
    @StringRes val labelRes: Int,
    @StringRes val captionRes: Int,
    val output: ToolOutput,
    val choice: ToolChoice = ToolChoice.NONE,
) {
    MEANING(R.string.tool_meaning, R.string.tool_meaning_caption, ToolOutput.SHOW),
    TONE(R.string.tool_tone, R.string.tool_tone_caption, ToolOutput.REPLACE, ToolChoice.TONE),
    TRANSLATE(
        R.string.tool_translate,
        R.string.tool_translate_caption,
        ToolOutput.REPLACE,
        ToolChoice.LANGUAGE,
    ),
    REPLY(R.string.tool_reply, R.string.tool_reply_caption, ToolOutput.REPLIES),
    ASK(R.string.tool_ask, R.string.tool_ask_caption, ToolOutput.SHOW),
    SHORTEN(R.string.tool_shorten, R.string.tool_shorten_caption, ToolOutput.REPLACE),
}

/** A tool the user wrote themselves: a name for the panel and the instruction behind it. */
data class CustomAssistant(val name: String, val instruction: String)

/**
 * One tap in the tools panel, ready to run.
 *
 * @param title what the result card is headed with.
 * @param tool the built-in tool, or null for a [CustomAssistant].
 * @param detail the chosen tone or language, or a custom assistant's instruction.
 */
data class ToolRun(
    val title: String,
    val output: ToolOutput,
    val tool: AiTool? = null,
    val detail: String? = null,
)

/** The user's own assistants and extra translation targets. Not secret; plain prefs. */
class ToolStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun assistants(): List<CustomAssistant> =
        decodeAssistants(prefs.getString(KEY_ASSISTANTS, null).orEmpty())

    fun setAssistants(assistants: List<CustomAssistant>) {
        prefs.edit().putString(KEY_ASSISTANTS, encodeAssistants(assistants)).apply()
    }

    /** Languages the user added, shown after the built-in ones. */
    fun languages(): List<String> =
        prefs.getString(KEY_LANGUAGES, null).orEmpty().lines().filter { it.isNotBlank() }

    fun setLanguages(languages: List<String>) {
        prefs.edit().putString(KEY_LANGUAGES, languages.joinToString("\n")).apply()
    }

    companion object {
        private const val PREFS_NAME = "ai_tools"
        private const val KEY_ASSISTANTS = "assistants"
        private const val KEY_LANGUAGES = "languages"

        fun encodeAssistants(assistants: List<CustomAssistant>): String =
            JSONArray().apply {
                assistants.forEach {
                    put(JSONObject().put("name", it.name).put("instruction", it.instruction))
                }
            }.toString()

        /** Unreadable or empty input is an empty list: a corrupt pref must not crash the IME. */
        fun decodeAssistants(json: String): List<CustomAssistant> = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name").trim()
                val instruction = item.optString("instruction").trim()
                if (name.isEmpty() || instruction.isEmpty()) null else CustomAssistant(name, instruction)
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Canned answers, so every card in the tools panel can be looked at before any model is
 * connected.
 *
 * shortcut: nothing here reads the user's text or calls a provider, delete this object
 * when the tools are wired to ProviderRegistry.
 */
object SampleAnswers {

    fun forRun(run: ToolRun): List<String> = when (run.tool) {
        AiTool.MEANING -> listOf(
            "\"Break a leg\" is an idiom. It means \"good luck\", usually said to someone " +
                "just before a performance or an exam."
        )

        AiTool.ASK -> listOf(
            "The capital of Australia is Canberra. Sydney is the largest city, which is " +
                "why it is often mistaken for the capital."
        )

        AiTool.TONE -> listOf(
            "I hope you are doing well. Could you please share the report by this evening?"
        )

        AiTool.TRANSLATE -> listOf("How are you? I will call you tomorrow.")

        AiTool.SHORTEN -> listOf("Can you send the report today?")

        AiTool.REPLY -> listOf(
            "Sure, that works for me.",
            "Thanks, let me check and get back to you.",
            "Sorry, I can't make it this time.",
        )

        null -> listOf("Your assistant's answer appears here, written from the instruction you gave it.")
    }
}
