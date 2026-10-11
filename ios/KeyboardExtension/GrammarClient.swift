import Foundation

/// One proposed change to a span of text. Mirrors Android's `Correction`.
struct Correction {
    let range: Range<Int>
    let original: String
    let replacement: String
    let ruleId: String

    var isNoOp: Bool { original == replacement }
}

enum GrammarSource {
    /// Bundled dictionary, spelling only, no network.
    case onDevice
    /// Self-hosted LanguageTool, full grammar, needs Full Access.
    case server
}

enum GrammarError: Error {
    case offline
    case noFullAccess
    case server(Int)
    case timeout
    case malformed(String)
    /// No server address configured, so no request was attempted.
    case notConfigured
}

/// Applies corrections back-to-front so earlier offsets stay valid.
func applyCorrections(to text: String, _ corrections: [Correction]) -> String {
    guard !corrections.isEmpty else { return text }
    var characters = Array(text)
    for correction in corrections.filter({ !$0.isNoOp })
        .sorted(by: { $0.range.lowerBound > $1.range.lowerBound }) {
        let lower = max(0, min(correction.range.lowerBound, characters.count))
        let upper = max(lower, min(correction.range.upperBound, characters.count))
        characters.replaceSubrange(lower..<upper, with: Array(correction.replacement))
    }
    return String(characters)
}

/// Full grammar checking against the self-hosted LanguageTool instance.
///
/// Requires Full Access — every request from an extension without it fails immediately.
/// That is reported as `.noFullAccess` rather than `.offline`, because the two need
/// different words in front of the user: one is a setting they can change, the other is
/// a network they cannot.
///
/// Only the text being checked is sent. No identifiers, no history, and only on an
/// explicit key tap.
struct GrammarClient {

    let baseURL: String
    let language: String

    init?(language: String = "en-US") {
        guard let url = SharedSettings.grammarServerURL else { return nil }
        self.baseURL = url.hasSuffix("/") ? String(url.dropLast()) : url
        self.language = language
    }

    func check(_ text: String) async -> Result<[Correction], GrammarError> {
        guard FullAccess.isGranted else { return .failure(.noFullAccess) }
        guard let endpoint = URL(string: "\(baseURL)/v2/check") else {
            return .failure(.malformed("bad server address"))
        }

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 8
        request.setValue(
            "application/x-www-form-urlencoded; charset=UTF-8",
            forHTTPHeaderField: "Content-Type"
        )
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = "text=\(escape(text))&language=\(escape(language))"
            .data(using: .utf8)

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200...299).contains(code) else { return .failure(.server(code)) }
            return .success(parse(data, text: text))
        } catch let error as URLError where error.code == .timedOut {
            return .failure(.timeout)
        } catch {
            return .failure(FullAccess.isGranted ? .offline : .noFullAccess)
        }
    }

    /// Matches with no suggested replacement are dropped: this keyboard applies fixes
    /// rather than annotating problems, so a match it cannot act on is noise.
    private func parse(_ data: Data, text: String) -> [Correction] {
        guard
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let matches = json["matches"] as? [[String: Any]]
        else {
            return []
        }

        let characters = Array(text)
        return matches.compactMap { match in
            guard
                let replacements = match["replacements"] as? [[String: Any]],
                let replacement = replacements.first?["value"] as? String,
                !replacement.isEmpty,
                let offset = match["offset"] as? Int,
                let length = match["length"] as? Int,
                length > 0,
                offset >= 0,
                offset + length <= characters.count
            else {
                return nil
            }
            let rule = (match["rule"] as? [String: Any])?["id"] as? String ?? ""
            return Correction(
                range: offset..<(offset + length),
                original: String(characters[offset..<(offset + length)]),
                replacement: replacement,
                ruleId: rule
            )
        }
    }

    private func escape(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}
