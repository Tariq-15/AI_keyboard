package com.chalkboard.keyboard.grammar

import android.content.Context
import com.chalkboard.keyboard.R
import com.chalkboard.keyboard.net.Connectivity
import com.chalkboard.keyboard.prediction.NgramDictionary
import com.chalkboard.keyboard.prediction.PersonalLearningStore
import com.chalkboard.keyboard.ui.Notice

/**
 * Decides which checker runs and reports which one did.
 *
 * Order is on-device first, then the server — the reverse of what raw capability would
 * suggest, and deliberate. The offline tier costs nothing, sends nothing and answers
 * instantly; the server tier is only worth a round trip when the offline tier found
 * nothing to fix. When the server is unreachable the result is still whatever the
 * offline tier found, with the source reported as [GrammarSource.ON_DEVICE] rather than
 * a silent downgrade — section 2.3 asks for the switch to be visible, not invisible.
 */
class GrammarService(
    private val onDevice: GrammarChecker,
    private val remote: GrammarChecker?,
) {

    /** Outcome of one grammar-key tap. */
    sealed interface Outcome {
        /** Corrections to apply. Never empty. */
        data class Corrections(
            val corrections: List<Correction>,
            val source: GrammarSource,
        ) : Outcome

        /** Text was already correct according to [source]. */
        data class Clean(val source: GrammarSource) : Outcome

        /**
         * The server tier could not run. Carries the raw error rather than finished
         * copy: turning it into words needs a [Context] — the "no network at all"
         * wording in section E4 depends on asking the platform — and this class is
         * deliberately free of one so it stays unit-testable on the JVM.
         */
        data class Failed(val error: Throwable) : Outcome
    }

    suspend fun check(text: String): Outcome {
        if (text.isBlank()) return Outcome.Clean(GrammarSource.ON_DEVICE)

        val local = onDevice.check(text).getOrNull().orEmpty().filterNot { it.isNoOp }
        if (local.isNotEmpty()) {
            return Outcome.Corrections(local, GrammarSource.ON_DEVICE)
        }

        val remote = this.remote
            ?: return Outcome.Clean(GrammarSource.ON_DEVICE)

        return remote.check(text).fold(
            onSuccess = { corrections ->
                val usable = corrections.filterNot { it.isNoOp }
                if (usable.isEmpty()) {
                    Outcome.Clean(GrammarSource.SERVER)
                } else {
                    Outcome.Corrections(usable, GrammarSource.SERVER)
                }
            },
            onFailure = { error -> Outcome.Failed(error) },
        )
    }

    companion object {

        /**
         * Error copy per section 1.5: state what happened and what still works, in the
         * interface's own voice. No apologies, no exclamation marks.
         *
         * Every one of these says on-device spelling is unaffected, because it is: the
         * server tier is only ever consulted after the offline tier has already run and
         * found nothing. Reporting "grammar correction unavailable" flat would overstate
         * the outage by a whole tier.
         */
        fun noticeFor(context: Context, error: Throwable): Notice = when (error) {
            // A socket cannot tell "your server is down" from "this phone has no
            // network", and section E4 wants the opposite message from section E1.
            is GrammarError.Offline ->
                if (Connectivity.hasNetwork(context)) {
                    Notice(
                        titleRes = R.string.notice_grammar_server_title,
                        bodyRes = R.string.notice_grammar_server_body,
                        severity = Notice.Severity.FAILURE,
                    )
                } else {
                    Notice(
                        titleRes = R.string.notice_offline_title,
                        bodyRes = R.string.notice_offline_grammar_body,
                        severity = Notice.Severity.NEUTRAL,
                    )
                }

            is GrammarError.Timeout -> Notice(
                titleRes = R.string.notice_grammar_timeout_title,
                bodyRes = R.string.notice_grammar_timeout_body,
                severity = Notice.Severity.FAILURE,
            )

            is GrammarError.Server -> Notice(
                titleRes = R.string.notice_grammar_server_title,
                bodyRes = R.string.notice_grammar_error_body,
                severity = Notice.Severity.FAILURE,
            )

            else -> Notice(
                titleRes = R.string.notice_grammar_server_title,
                bodyRes = R.string.notice_grammar_unreadable_body,
                severity = Notice.Severity.FAILURE,
            )
        }

        private const val PREFS_NAME = "grammar"
        private const val KEY_SERVER_URL = "server_url"

        /**
         * Builds the service for this device. The server tier exists only if the user
         * has configured a URL: this app ships with no default endpoint, so an
         * unconfigured install performs no network requests at all.
         */
        fun create(
            context: Context,
            dictionary: NgramDictionary?,
            personal: PersonalLearningStore?,
        ): GrammarService = GrammarService(
            onDevice = OnDeviceSpellChecker(
                dictionary = dictionary,
                personal = personal?.let { store ->
                    OnDeviceSpellChecker.PersonalWordCounts { word ->
                        store.lookupByPrefix(word, limit = 1)
                            .firstOrNull { it.word == word }?.count ?: 0
                    }
                },
            ),
            remote = serverUrl(context)?.let { RemoteLanguageTool(it) },
        )

        fun serverUrl(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_URL, null)
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }

        fun setServerUrl(context: Context, url: String?) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER_URL, url?.trim().orEmpty())
                .apply()
        }
    }
}
