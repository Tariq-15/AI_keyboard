import Foundation

/// Tracks the word being typed and the two words before it.
///
/// Pure logic, no UIKit, so the edge cases — sentence boundaries, apostrophes,
/// backspacing into a finished word — are testable. Mirrors Android's `WordTracker`.
///
/// Word characters are letters and the apostrophe, matching the tokeniser that built the
/// dictionary (`tools/build_dictionary.py`).
struct WordTracker {

    /// A word the user just finished, with the word that preceded it. `previous` must be
    /// captured before the completed word enters the history, or a word ends up recorded
    /// as following itself — a bug found on Android.
    struct Completion {
        let word: String
        let previous: String
    }

    private(set) var current: String = ""
    private var history: [String] = []

    /// The last two committed words, padded with sentence-start markers.
    var context: [String] {
        let marker = NgramDictionary.sentenceStart
        switch history.count {
        case 0: return [marker, marker]
        case 1: return [marker, history[0]]
        default: return Array(history.suffix(2))
        }
    }

    mutating func commit(_ text: String) -> Completion? {
        var completed: Completion?
        for character in text {
            if isWordCharacter(character) {
                current.append(character)
            } else {
                completed = finishWord() ?? completed
                if isSentenceEnd(character) { history.removeAll() }
            }
        }
        return completed
    }

    /// Only the partial word is tracked precisely. Deleting back into committed text
    /// clears the context rather than guessing at it.
    mutating func backspace() {
        if current.isEmpty {
            history.removeAll()
        } else {
            current.removeLast()
        }
    }

    mutating func acceptSuggestion(_ word: String) -> Completion? {
        current = word
        return finishWord()
    }

    mutating func reset() {
        current = ""
        history.removeAll()
    }

    /// Rebuilds state from the text before the caret, so the first suggestion after the
    /// keyboard attaches to a non-empty field is not made blind.
    mutating func reset(from textBeforeCursor: String?) {
        reset()
        guard let text = textBeforeCursor, !text.isEmpty else { return }

        let sentence = String(text.reversed().prefix { !isSentenceEnd($0) }.reversed())
        let words = sentence.split { !isWordCharacter($0) }.map(String.init)
        guard !words.isEmpty else { return }

        let endsMidWord = sentence.last.map(isWordCharacter) ?? false
        let committed = endsMidWord ? Array(words.dropLast()) : words
        history = Array(committed.suffix(2))
        current = endsMidWord ? (words.last ?? "") : ""
    }

    private mutating func finishWord() -> Completion? {
        guard !current.isEmpty else { return nil }
        let word = current
        let previous = history.last ?? NgramDictionary.sentenceStart
        current = ""
        history.append(word)
        if history.count > 2 { history.removeFirst(history.count - 2) }
        return Completion(word: word, previous: previous)
    }

    private func isWordCharacter(_ character: Character) -> Bool {
        character.isLetter || character == "'"
    }

    private func isSentenceEnd(_ character: Character) -> Bool {
        ".!?\n".contains(character)
    }
}

/// What this user actually types, learned locally.
///
/// Never synced, never backed up, never transmitted. Stored in the App Group container so
/// the containing app can offer a "delete learned words" control over the same data.
///
/// A plain plist rather than SQLite: the volume is small, and an extension under a memory
/// ceiling is better served by something it can load and drop than by a database
/// connection it must keep open.
final class PersonalLearningStore {

    static let shared = PersonalLearningStore()

    private struct Entry: Codable {
        var count: Int
        var lastUsed: Date
    }

    /// Keyed "previous\nword".
    private var entries: [String: Entry] = [:]
    private let url: URL?

    private init() {
        url = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: SharedSettings.suiteName)?
            .appendingPathComponent("learned_words.json")
        load()
    }

    func record(_ word: String, after previous: String) {
        guard !word.isEmpty else { return }
        let key = "\(previous.lowercased())\n\(word.lowercased())"
        var entry = entries[key] ?? Entry(count: 0, lastUsed: Date())
        entry.count += 1
        entry.lastUsed = Date()
        entries[key] = entry
        save()
    }

    /// Total uses of `word`, whatever preceded it.
    func uses(of word: String) -> Int {
        let suffix = "\n\(word.lowercased())"
        return entries
            .filter { $0.key.hasSuffix(suffix) }
            .reduce(0) { $0 + $1.value.count }
    }

    /// Words this user has typed after `previous`, most-used first.
    func candidates(after previous: String, prefix: String, limit: Int = 8) -> [(String, Int)] {
        let keyPrefix = "\(previous.lowercased())\n"
        return entries
            .compactMap { key, entry -> (String, Int)? in
                guard key.hasPrefix(keyPrefix) else { return nil }
                let word = String(key.dropFirst(keyPrefix.count))
                guard prefix.isEmpty || word.hasPrefix(prefix.lowercased()) else { return nil }
                return (word, entry.count)
            }
            .sorted { $0.1 > $1.1 }
            .prefix(limit)
            .map { $0 }
    }

    /// Halves counts untouched for 45 days and drops what is left at zero, so the store
    /// tracks current habits rather than accumulating forever.
    func decay() {
        let cutoff = Date().addingTimeInterval(-45 * 24 * 60 * 60)
        for (key, entry) in entries where entry.lastUsed < cutoff {
            let halved = entry.count / 2
            if halved <= 0 { entries.removeValue(forKey: key) } else { entries[key]?.count = halved }
        }
        save()
    }

    func clear() {
        entries.removeAll()
        save()
    }

    private func load() {
        guard let url, let data = try? Data(contentsOf: url) else { return }
        entries = (try? JSONDecoder().decode([String: Entry].self, from: data)) ?? [:]
    }

    private func save() {
        guard let url, let data = try? JSONEncoder().encode(entries) else { return }
        // Not `.completeFileProtection`: the keyboard runs whenever the device is
        // unlocked, and this file must be readable for the whole of that time.
        try? data.write(to: url, options: [.atomic])
    }
}
