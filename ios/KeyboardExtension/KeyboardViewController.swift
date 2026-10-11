import UIKit

/// The keyboard extension.
///
/// Two platform constraints shape this class, and neither is discovered at runtime:
///
/// 1. **Memory.** The extension is killed far sooner than an app would be, so the n-gram
///    dictionary is memory-mapped (see `NgramDictionary`) and never parsed into
///    collections.
/// 2. **Full Access.** Without it the extension has no network at all. Typing and
///    prediction are local and keep working; grammar and rewrite cannot. Those two keys
///    stay visible and explain themselves rather than disappearing or failing silently —
///    a key that vanishes reads as a bug, a key that says why reads as a choice.
final class KeyboardViewController: UIInputViewController {

    private var keyboardView: KeyboardView!
    private var dictionary: NgramDictionary?
    private var words = WordTracker()

    override func viewDidLoad() {
        super.viewDidLoad()

        // Shared code branches on this without importing UIKit.
        FullAccess.isGranted = hasFullAccess

        dictionary = Bundle.main.url(forResource: "ngram_dictionary", withExtension: "bin")
            .flatMap { NgramDictionary(url: $0) }

        keyboardView = KeyboardView(
            showRewriteKey: ProviderRegistry.isRewriteAvailable,
            onKey: { [weak self] action in self?.handle(action) }
        )
        keyboardView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(keyboardView)
        NSLayoutConstraint.activate([
            keyboardView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            keyboardView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            keyboardView.topAnchor.constraint(equalTo: view.topAnchor),
            keyboardView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        FullAccess.isGranted = hasFullAccess
        words.reset(from: textDocumentProxy.documentContextBeforeInput)
        refreshSuggestions()
    }

    // MARK: - Key handling

    private func handle(_ action: KeyAction) {
        switch action {
        case .character(let text):
            textDocumentProxy.insertText(text)
            if let completed = words.commit(text) {
                PersonalLearningStore.shared.record(completed.word, after: completed.previous)
            }
            refreshSuggestions()

        case .space:
            handle(.character(" "))

        case .backspace:
            textDocumentProxy.deleteBackward()
            words.backspace()
            refreshSuggestions()

        case .enter:
            textDocumentProxy.insertText("\n")
            words.reset()
            refreshSuggestions()

        case .nextKeyboard:
            advanceToNextInputMode()

        case .suggestion(let word):
            commitSuggestion(word)

        case .fixGrammar:
            fixGrammar()

        case .rewrite:
            rewrite()

        case .shift, .switchPage:
            // Handled inside KeyboardView; it owns page and shift state.
            break
        }
    }

    private func commitSuggestion(_ word: String) {
        let partial = words.current
        for _ in 0..<partial.count { textDocumentProxy.deleteBackward() }
        textDocumentProxy.insertText(word + " ")
        if let completed = words.acceptSuggestion(word) {
            PersonalLearningStore.shared.record(completed.word, after: completed.previous)
        }
        refreshSuggestions()
    }

    private func refreshSuggestions() {
        guard let dictionary else {
            keyboardView.setSuggestions([])
            return
        }
        let context = words.context
        let prefix = words.current
        // Cheap enough to run inline: the lookup is a handful of binary searches over
        // mapped pages, measured under a frame on Android with the same format.
        let candidates = dictionary.lookup(context: context, prefix: prefix, limit: 3)
        keyboardView.setSuggestions(candidates.map(\.word))
    }

    // MARK: - Network features

    private func fixGrammar() {
        guard hasFullAccess else {
            keyboardView.showStatus("Enable Full Access in Settings to use grammar correction.")
            return
        }
        let text = fieldText()
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            keyboardView.showStatus("Type something to check.")
            return
        }
        guard let client = GrammarClient() else {
            keyboardView.showStatus("Add a LanguageTool server address in settings.")
            return
        }

        keyboardView.showStatus("Checking…")
        Task { @MainActor in
            switch await client.check(text) {
            case .success(let corrections) where corrections.isEmpty:
                keyboardView.showStatus("Nothing to correct.")
            case .success(let corrections):
                let corrected = applyCorrections(to: text, corrections)
                keyboardView.playChalkRedraw(text: text, corrections: corrections) { [weak self] in
                    self?.replaceFieldText(with: corrected)
                }
            case .failure(let error):
                keyboardView.showStatus(message(for: error))
            }
        }
    }

    private func rewrite() {
        guard hasFullAccess else {
            keyboardView.showStatus("Enable Full Access in Settings to use rewriting.")
            return
        }
        let text = fieldText()
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            keyboardView.showStatus("Type something to rewrite.")
            return
        }

        keyboardView.showStatus("Rewriting…")
        Task { @MainActor in
            switch await ProviderRegistry.rewrite(text) {
            case .success(let rewritten):
                replaceFieldText(with: rewritten)
            case .failure(let error):
                keyboardView.showStatus(ProviderRegistry.message(for: error))
            }
        }
    }

    private func message(for error: GrammarError) -> String {
        switch error {
        case .offline: return "LanguageTool server unreachable. Check your connection and try again."
        case .noFullAccess: return "Enable Full Access in Settings to use grammar correction."
        case .timeout: return "LanguageTool server did not respond. Try again."
        case .server: return "LanguageTool server returned an error. Check the address in settings."
        case .notConfigured: return "Add a LanguageTool server address in settings."
        case .malformed: return "LanguageTool server sent an unreadable response."
        }
    }

    // MARK: - Field access

    /// Bounded on purpose: the user tapped a key to correct what they are writing, not to
    /// upload a document, and this is the only text that ever leaves the device.
    private func fieldText() -> String {
        let before = String((textDocumentProxy.documentContextBeforeInput ?? "").suffix(600))
        let after = String((textDocumentProxy.documentContextAfterInput ?? "").prefix(600))
        return before + after
    }

    private func replaceFieldText(with replacement: String) {
        let before = String((textDocumentProxy.documentContextBeforeInput ?? "").suffix(600))
        let after = String((textDocumentProxy.documentContextAfterInput ?? "").prefix(600))

        for _ in 0..<before.count { textDocumentProxy.deleteBackward() }
        if !after.isEmpty {
            textDocumentProxy.adjustTextPosition(byCharacterOffset: after.count)
            for _ in 0..<after.count { textDocumentProxy.deleteBackward() }
        }
        textDocumentProxy.insertText(replacement)
        words.reset(from: replacement)
        refreshSuggestions()
    }
}
