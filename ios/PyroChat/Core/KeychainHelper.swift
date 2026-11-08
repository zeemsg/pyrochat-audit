import Foundation
import Security
import LocalAuthentication

/// Thin Keychain wrapper for the few raw secrets that must exist OUTSIDE the
/// password-encrypted store: the KDF salt/pepper, the duress-PIN verifier
/// (both needed before the store can be decrypted) and — when the user opts
/// in — the biometrically-protected copy of the store key.
enum KeychainHelper {
    private static let service = "ca.pyrochat.app"

    static func set(_ data: Data, key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func get(_ key: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    static func delete(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }

    /// Presence only. This MUST NOT raise a biometric prompt.
    ///
    /// The one item this is asked about is `biometric-store-key`, stored under
    /// a `.biometryCurrentSet` access control. Matching such an item evaluates
    /// its ACL, so unless the prompt is explicitly suppressed the OS can raise
    /// a Face ID prompt merely to answer "is it there?" — and since the answer is
    /// consulted on every settings refresh, every unlock-screen appearance and
    /// twice inside every unlock attempt, the user is asked over and over.
    /// That was the Face ID prompt storm; see docs/BIOMETRIC-UNLOCK.md §0.
    static func exists(_ key: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            // Never authenticate to answer. NOT `…UISkip`: that SKIPS items
            // needing auth, reporting the one item we care about as missing.
            // `…UIFail` reports it as errSecInteractionNotAllowed instead,
            // which is "there, but not without a face" — see below. This is
            // the same constant `wipeAll` already relies on.
            kSecUseAuthenticationUI as String: kSecUseAuthenticationUIFail,
        ]
        var out: CFTypeRef?
        return presenceMeansExists(SecItemCopyMatching(query as CFDictionary, &out))
    }

    /// How a presence query's status maps to "the item is there".
    ///
    /// `errSecInteractionNotAllowed` is the whole point: with the prompt
    /// suppressed by `…UIFail`, an item that EXISTS but needs a face reports
    /// exactly that. Reading it as "missing" would make `wasInvalidated` true,
    /// and the app would quietly destroy a perfectly good stored key. That is
    /// why `…UISkip` must never be used here: it hides the item outright.
    static func presenceMeansExists(_ status: OSStatus) -> Bool {
        status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    // MARK: biometric access control (docs/BIOMETRIC-UNLOCK.md §1)

    /// Write behind the phone's secure hardware: readable only after a
    /// successful Face ID / Touch ID match, and destroyed by the OS the moment
    /// a face or finger is added or removed (`.biometryCurrentSet`), so an
    /// attacker who enrols their own biometrics gets nothing.
    /// Returns false when the OS refuses (no passcode set, no enrolment).
    @discardableResult
    static func setBiometric(_ data: Data, key: String) -> Bool {
        guard let control = SecAccessControlCreateWithFlags(
                nil,
                kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                .biometryCurrentSet,
                nil) else { return false }
        delete(key)
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessControl as String: control,
            // an already-authenticated context: adding must not prompt
            kSecUseAuthenticationContext as String: LAContext(),
        ]
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    enum BiometricReadError: Error, Equatable {
        case cancelled
        case notRecognised
        /// The item is not there — with `.biometryCurrentSet` that means the
        /// OS destroyed it (enrolment changed), or it was never written.
        case missing
        case failed(OSStatus)
    }

    /// Read an access-controlled item, reusing an `LAContext` that has ALREADY
    /// evaluated its policy, so the user sees exactly one prompt.
    static func getBiometric(_ key: String,
                             context: LAContext) -> Result<Data, BiometricReadError> {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseAuthenticationContext as String: context,
        ]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        switch status {
        case errSecSuccess:
            guard let data = out as? Data else { return .failure(.failed(status)) }
            return .success(data)
        case errSecUserCanceled:
            return .failure(.cancelled)
        case errSecAuthFailed:
            return .failure(.notRecognised)
        case errSecItemNotFound:
            return .failure(.missing)
        default:
            return .failure(.failed(status))
        }
    }

    /// Panic wipe: remove every PyroChat keychain item — access-controlled
    /// ones included (docs/BIOMETRIC-UNLOCK.md §5 demands this be verified,
    /// not assumed: see `BiometricUnlockTests`).
    ///
    /// A plain class+service delete reaches ordinary items. Items carrying a
    /// `SecAccessControl` are matched the same way — deletion never
    /// authenticates — but `kSecUseAuthenticationUI: kSecUseAuthenticationUIFail`
    /// makes that explicit: match without ever trying to raise a prompt (a
    /// prompt during a duress wipe would be catastrophic). The second,
    /// unadorned pass is belt-and-braces for runtimes that ignore the hint,
    /// and for anything left behind by a partially-matched first pass.
    static func wipeAll() {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        var noUI = base
        noUI[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail
        _ = SecItemDelete(noUI as CFDictionary)
        // errSecItemNotFound = nothing matched. Bounded extra passes catch
        // whatever a partially-matched first pass left behind.
        for _ in 0..<4 {
            if SecItemDelete(base as CFDictionary) != errSecSuccess { break }
        }
    }
}
