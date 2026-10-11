import Foundation

struct OpenAIProvider: AIProvider {
    let id = "openai"
    let displayName = "OpenAI"
    let keyURL = "https://platform.openai.com/api-keys"

    private var client: OpenAICompatibleClient {
        OpenAICompatibleClient(
            providerId: id, baseURL: "https://api.openai.com/v1", model: "gpt-4o-mini"
        )
    }

    func improveText(_ text: String, apiKey: String) async -> Result<String, AIError> {
        await client.improveText(text, apiKey: apiKey)
    }

    func validateKey(_ apiKey: String) async -> Result<Void, AIError> {
        await client.validateKey(apiKey)
    }
}

struct GroqProvider: AIProvider {
    let id = "groq"
    let displayName = "Groq"
    let keyURL = "https://console.groq.com/keys"

    private var client: OpenAICompatibleClient {
        OpenAICompatibleClient(
            providerId: id,
            baseURL: "https://api.groq.com/openai/v1",
            model: "llama-3.3-70b-versatile"
        )
    }

    func improveText(_ text: String, apiKey: String) async -> Result<String, AIError> {
        await client.improveText(text, apiKey: apiKey)
    }

    func validateKey(_ apiKey: String) async -> Result<Void, AIError> {
        await client.validateKey(apiKey)
    }
}

/// Any other OpenAI-compatible endpoint. Keeps the provider list from needing to grow.
struct CustomEndpointProvider: AIProvider {
    static let identifier = "custom"

    let id = CustomEndpointProvider.identifier
    let displayName = "Custom endpoint"
    let keyURL = ""

    var baseURL: String = SharedSettings.customBaseURL
    var model: String = SharedSettings.customModel

    private var client: OpenAICompatibleClient {
        OpenAICompatibleClient(
            providerId: id,
            baseURL: baseURL.hasSuffix("/") ? String(baseURL.dropLast()) : baseURL,
            model: model
        )
    }

    func improveText(_ text: String, apiKey: String) async -> Result<String, AIError> {
        await client.improveText(text, apiKey: apiKey)
    }

    func validateKey(_ apiKey: String) async -> Result<Void, AIError> {
        await client.validateKey(apiKey)
    }
}

/// Gemini has its own request shape, and the key goes in the query string rather than an
/// Authorization header.
struct GeminiProvider: AIProvider {
    let id = "gemini"
    let displayName = "Gemini"
    let keyURL = "https://aistudio.google.com/apikey"

    private let base = "https://generativelanguage.googleapis.com/v1beta"
    private let model = "gemini-2.5-flash"

    func improveText(_ text: String, apiKey: String) async -> Result<String, AIError> {
        let body: [String: Any] = [
            "system_instruction": ["parts": [["text": GeminiProvider.systemPrompt]]],
            "contents": [["parts": [["text": text]]]],
        ]
        let url = "\(base)/models/\(model):generateContent?key=\(escape(apiKey))"

        return await request(url: url, body: body).flatMap { data in
            guard
                let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                let candidates = json["candidates"] as? [[String: Any]],
                let content = candidates.first?["content"] as? [String: Any],
                let parts = content["parts"] as? [[String: Any]],
                let text = (parts.first?["text"] as? String)?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                !text.isEmpty
            else {
                return .failure(.malformed("no candidate in response"))
            }
            return .success(text)
        }
    }

    func validateKey(_ apiKey: String) async -> Result<Void, AIError> {
        await request(url: "\(base)/models?key=\(escape(apiKey))", body: nil).map { _ in () }
    }

    private func escape(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? value
    }

    private func request(url: String, body: [String: Any]?) async -> Result<Data, AIError> {
        guard let endpoint = URL(string: url) else {
            return .failure(.malformed("bad endpoint"))
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = body == nil ? "GET" : "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch code {
            case 200...299: return .success(data)
            case 400, 401, 403: return .failure(.invalidKey(provider: id))
            case 429: return .failure(.rateLimited(provider: id))
            default: return .failure(.server(provider: id, code: code))
            }
        } catch {
            return .failure(FullAccess.isGranted ? .offline : .noFullAccess)
        }
    }
}

/// Which providers exist, which have keys, and which one the rewrite key uses.
enum ProviderRegistry {

    static func allProviders() -> [AIProvider] {
        [GeminiProvider(), GroqProvider(), OpenAIProvider(), CustomEndpointProvider()]
    }

    static func provider(id: String) -> AIProvider? {
        allProviders().first { $0.id == id }
    }

    static func configured() -> [AIProvider] {
        allProviders().filter { SecureKeyStore.hasKey(for: $0.id) }
    }

    /// True when the rewrite key should be drawn at all.
    static var isRewriteAvailable: Bool { !configured().isEmpty }

    /// The user's choice if it still has a key, otherwise the first configured provider,
    /// so removing a key cannot leave the keyboard pointing at a dead one.
    static var active: AIProvider? {
        let chosen = SharedSettings.activeProvider
        return configured().first { $0.id == chosen } ?? configured().first
    }

    static func rewrite(_ text: String) async -> Result<String, AIError> {
        guard let provider = active else { return .failure(.malformed("no provider")) }
        guard let key = SecureKeyStore.key(for: provider.id) else {
            return .failure(.invalidKey(provider: provider.id))
        }
        return await provider.improveText(text, apiKey: key)
    }

    /// Validates before storing. A key that does not work is never saved.
    static func addKey(_ apiKey: String, for providerId: String) async -> Result<Void, AIError> {
        guard let provider = provider(id: providerId) else {
            return .failure(.malformed("unknown provider"))
        }
        let result = await provider.validateKey(apiKey)
        if case .success = result {
            SecureKeyStore.store(apiKey, for: providerId)
            if SharedSettings.activeProvider == nil {
                SharedSettings.activeProvider = providerId
            }
        }
        return result
    }

    static func removeKey(for providerId: String) {
        SecureKeyStore.remove(for: providerId)
        if SharedSettings.activeProvider == providerId {
            SharedSettings.activeProvider = nil
        }
    }

    /// User-facing copy. Names the provider, says what to do, never retries in a loop.
    static func message(for error: AIError) -> String {
        switch error {
        case .invalidKey(let provider):
            return "Invalid API key for \(name(provider)). Check the key in settings."
        case .rateLimited(let provider):
            return "\(name(provider)) rate limit reached. Try again later or switch providers."
        case .server(let provider, _):
            return "\(name(provider)) returned an error. Try again later."
        case .offline:
            return "No connection. Rewriting needs network access."
        case .noFullAccess:
            return "Enable Full Access in Settings to use rewriting."
        case .malformed:
            return "The provider sent an unreadable response."
        }
    }

    private static func name(_ providerId: String) -> String {
        provider(id: providerId)?.displayName ?? providerId
    }
}
