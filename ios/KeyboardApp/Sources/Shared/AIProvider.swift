import Foundation

/// Same shape as the Android `AIProvider` interface, so the two platforms behave
/// identically and a change to the prompt or the error handling is made in two obvious
/// places rather than scattered.
protocol AIProvider {
    var id: String { get }
    var displayName: String { get }
    var keyURL: String { get }

    func improveText(_ text: String, apiKey: String) async -> Result<String, AIError>
    func validateKey(_ apiKey: String) async -> Result<Void, AIError>
}

extension AIProvider {
    /// Sent to every provider unchanged, so results are comparable between them.
    static var systemPrompt: String {
        "Rewrite the following text to improve clarity and tone. "
            + "Return only the rewritten text, no explanation."
    }
}

enum AIError: Error {
    case invalidKey(provider: String)
    case rateLimited(provider: String)
    case server(provider: String, code: Int)
    case offline
    /// The extension has no network because the user declined Full Access.
    case noFullAccess
    case malformed(String)
}

/// One implementation of the OpenAI chat-completions shape, shared by OpenAI, Groq and
/// any custom endpoint. They differ only in base URL and default model.
struct OpenAICompatibleClient {
    let providerId: String
    let baseURL: String
    let model: String

    func improveText(_ text: String, apiKey: String) async -> Result<String, AIError> {
        let body: [String: Any] = [
            "model": model,
            "temperature": 0.3,
            "messages": [
                ["role": "system", "content": GeminiProvider.systemPrompt],
                ["role": "user", "content": text],
            ],
        ]
        let result = await request(
            url: "\(baseURL)/chat/completions", apiKey: apiKey, body: body
        )
        return result.flatMap { data in
            guard
                let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                let choices = json["choices"] as? [[String: Any]],
                let message = choices.first?["message"] as? [String: Any],
                let content = (message["content"] as? String)?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                !content.isEmpty
            else {
                return .failure(.malformed("no completion in response"))
            }
            return .success(content)
        }
    }

    func validateKey(_ apiKey: String) async -> Result<Void, AIError> {
        await request(url: "\(baseURL)/models", apiKey: apiKey, body: nil).map { _ in () }
    }

    private func request(
        url: String, apiKey: String, body: [String: Any]?
    ) async -> Result<Data, AIError> {
        guard let endpoint = URL(string: url) else {
            return .failure(.malformed("bad endpoint"))
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = body == nil ? "GET" : "POST"
        request.timeoutInterval = 20
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
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
            case 401, 403: return .failure(.invalidKey(provider: providerId))
            case 429: return .failure(.rateLimited(provider: providerId))
            default: return .failure(.server(provider: providerId, code: code))
            }
        } catch {
            // Without Full Access the extension is not merely offline — every request
            // fails immediately. Tell those apart so the user gets an action they can take.
            return .failure(FullAccess.isGranted ? .offline : .noFullAccess)
        }
    }
}

/// Whether the extension may use the network at all.
enum FullAccess {
    /// `hasFullAccess` on the input view controller is the authoritative answer; the
    /// controller sets this at load so shared code can branch without importing UIKit.
    static var isGranted: Bool = false
}
