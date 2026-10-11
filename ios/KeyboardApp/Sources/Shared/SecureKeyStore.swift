import Foundation
import Security

/// API keys at rest, in the Keychain, shared between the containing app and the keyboard
/// extension through a Keychain access group.
///
/// Storage is never duplicated: the app writes, the extension reads, both through here.
/// Requires `keychain-access-groups` in both targets' entitlements.
///
/// Rules that must not be relaxed: a key value is never logged, never included in
/// diagnostics, and never sent anywhere except the provider it belongs to.
struct SecureKeyStore {

    /// Must match the entitlement in both targets, prefixed with the team id at runtime.
    static let accessGroup = "$(AppIdentifierPrefix)com.chalkboard.keyboard.shared"

    private static let service = "com.chalkboard.keyboard.providerKeys"

    static func key(for providerId: String) -> String? {
        var query = baseQuery(providerId)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8),
              !value.isEmpty
        else {
            return nil
        }
        return value
    }

    @discardableResult
    static func store(_ apiKey: String, for providerId: String) -> Bool {
        let trimmed = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = trimmed.data(using: .utf8) else { return false }

        // Upsert: try update first, insert if the item is not there yet.
        let query = baseQuery(providerId)
        let update = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return true }

        var insert = query
        insert[kSecValueData as String] = data
        // The extension needs to read this while the device is unlocked only; it never
        // runs on the lock screen.
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess
    }

    @discardableResult
    static func remove(for providerId: String) -> Bool {
        SecItemDelete(baseQuery(providerId) as CFDictionary) == errSecSuccess
    }

    static func hasKey(for providerId: String) -> Bool {
        key(for: providerId) != nil
    }

    private static func baseQuery(_ providerId: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: providerId,
            kSecAttrAccessGroup as String: accessGroup,
        ]
    }
}

/// Non-secret provider configuration, shared through the App Group.
struct SharedSettings {

    static let suiteName = "group.com.chalkboard.keyboard"

    private static var defaults: UserDefaults? { UserDefaults(suiteName: suiteName) }

    /// Provider the rewrite key uses.
    static var activeProvider: String? {
        get { defaults?.string(forKey: "active_provider") }
        set { defaults?.set(newValue, forKey: "active_provider") }
    }

    /// LanguageTool address. Empty means on-device spelling only, and no network at all.
    static var grammarServerURL: String? {
        get {
            defaults?.string(forKey: "grammar_server_url")?
                .trimmingCharacters(in: .whitespaces)
                .nilIfEmpty
        }
        set { defaults?.set(newValue, forKey: "grammar_server_url") }
    }

    static var customBaseURL: String {
        get { defaults?.string(forKey: "custom_base_url") ?? "" }
        set { defaults?.set(newValue, forKey: "custom_base_url") }
    }

    static var customModel: String {
        get { defaults?.string(forKey: "custom_model") ?? "" }
        set { defaults?.set(newValue, forKey: "custom_model") }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
