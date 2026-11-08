import Foundation
import LibSignalClient

/// libsignal protocol stores backed by the encrypted SecureStore. Every
/// record round-trips through its own serialize()/init(bytes:) so state
/// survives relaunches — encrypted at rest, always.
final class SignalStore: IdentityKeyStore, PreKeyStore, SignedPreKeyStore,
                         KyberPreKeyStore, SessionStore {
    struct Context: StoreContext {}

    private let store: SecureStore
    init(store: SecureStore) { self.store = store }

    /// Fired synchronously on the CALLER's thread when saveIdentity replaces
    /// an existing identity (param = peer hash). In the app this is always
    /// the main actor: ChatService (@MainActor) makes every CryptoEngine
    /// call synchronously, and libsignal invokes store callbacks inline.
    var onIdentityReplaced: ((String) -> Void)?

    private var identity: IdentityKeyPair {
        get throws { try IdentityKeyPair(bytes: store.blob.identityKeyPair) }
    }

    // MARK: IdentityKeyStore

    func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair {
        try identity
    }

    func localRegistrationId(context: StoreContext) throws -> UInt32 {
        store.blob.registrationId
    }

    func saveIdentity(_ identity: IdentityKey, for address: ProtocolAddress,
                      context: StoreContext) throws -> IdentityChange {
        let key = address.name
        let serialized = identity.serialize()
        let previous = store.blob.knownIdentities[key]
        store.update { $0.knownIdentities[key] = serialized }
        if previous != nil && previous != serialized {
            onIdentityReplaced?(key)
            return .replacedExisting
        }
        return .newOrUnchanged
    }

    func isTrustedIdentity(_ identity: IdentityKey, for address: ProtocolAddress,
                           direction: Direction, context: StoreContext) throws -> Bool {
        // ACCEPT-AND-WARN (Signal's modern behavior). Returning false on a
        // changed key would (a) make every message after a peer reinstall
        // throw untrustedIdentity -> permanent decrypt_failed, and (b) make
        // processPreKeyBundle throw during auto-heal, wedging recovery.
        // saveIdentity still records the change and fires onIdentityReplaced,
        // which surfaces the loud in-thread warning and clears
        // Contact.verified — trust is displayed, never enforced by dropping
        // mail on the floor. See THREAT_MODEL.md "Key changes".
        return true
    }

    func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        guard let data = store.blob.knownIdentities[address.name] else { return nil }
        return try IdentityKey(bytes: data)
    }

    // MARK: PreKeyStore

    func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        guard let data = store.blob.preKeys[id] else {
            throw SignalError.invalidKeyIdentifier("no prekey \(id)")
        }
        return try PreKeyRecord(bytes: data)
    }

    func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        store.update { $0.preKeys[id] = record.serialize() }
    }

    func removePreKey(id: UInt32, context: StoreContext) throws {
        store.update { $0.preKeys[id] = nil }
    }

    // MARK: SignedPreKeyStore

    func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        guard let data = store.blob.signedPreKeys[id] else {
            throw SignalError.invalidKeyIdentifier("no signed prekey \(id)")
        }
        return try SignedPreKeyRecord(bytes: data)
    }

    func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        store.update { $0.signedPreKeys[id] = record.serialize() }
    }

    // MARK: KyberPreKeyStore

    func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord {
        guard let data = store.blob.kyberPreKeys[id] else {
            throw SignalError.invalidKeyIdentifier("no kyber prekey \(id)")
        }
        return try KyberPreKeyRecord(bytes: data)
    }

    func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws {
        store.update { $0.kyberPreKeys[id] = record.serialize() }
    }

    func markKyberPreKeyUsed(id: UInt32, signedPreKeyId: UInt32, baseKey: PublicKey,
                             context: StoreContext) throws {
        // last-resort kyber prekey is reusable; one-time kyber prekeys (not
        // used in v1) would be deleted here
    }

    // MARK: SessionStore

    func loadSession(for address: ProtocolAddress, context: StoreContext) throws -> SessionRecord? {
        guard let data = store.blob.sessions[address.name] else { return nil }
        return try SessionRecord(bytes: data)
    }

    func loadExistingSessions(for addresses: [ProtocolAddress],
                              context: StoreContext) throws -> [SessionRecord] {
        try addresses.compactMap { try loadSession(for: $0, context: context) }
    }

    func storeSession(_ record: SessionRecord, for address: ProtocolAddress,
                      context: StoreContext) throws {
        store.update { $0.sessions[address.name] = record.serialize() }
    }
}
