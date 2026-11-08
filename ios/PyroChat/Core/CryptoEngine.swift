import Foundation
import CryptoKit
import LibSignalClient

/// All Signal-protocol operations: identity generation, PQXDH prekey bundles,
/// session bootstrap, and per-message encrypt/decrypt (Double Ratchet).
final class CryptoEngine {
    static let deviceId: UInt32 = 1

    private let store: SecureStore
    private let signal: SignalStore
    private let ctx = SignalStore.Context()

    init(store: SecureStore) {
        self.store = store
        self.signal = SignalStore(store: store)
    }

    private var myAddress: ProtocolAddress {
        get throws { try ProtocolAddress(name: store.blob.usernameHash, deviceId: Self.deviceId) }
    }

    // MARK: registration material

    struct RegistrationBundle {
        var regId: UInt32
        var identityKey: Data
        var signedPreKey: Wire.PreKeyJSON
        var kyberPreKey: Wire.PreKeyJSON
        var oneTimePreKeys: [Wire.PreKeyJSON]
        var authPub: Data
    }

    /// Generate the full identity + prekey set for a new account. Private
    /// halves land in the encrypted store; the returned bundle is the public
    /// material for the relay.
    func generateIdentity(username: String) throws -> RegistrationBundle {
        let identity = IdentityKeyPair.generate()
        let regId = UInt32.random(in: 1...16380)
        let authKey = Curve25519.Signing.PrivateKey()

        store.update {
            $0.usernameDisplay = username
            $0.usernameHash = sha256Hex(username.lowercased())
            $0.identityKeyPair = identity.serialize()
            $0.registrationId = regId
            $0.authPrivateKey = authKey.rawRepresentation
        }

        // signed prekey (EC)
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        let spkPriv = PrivateKey.generate()
        let spkSig = identity.privateKey.generateSignature(message: spkPriv.publicKey.serialize())
        let spk = try SignedPreKeyRecord(id: 1, timestamp: now, privateKey: spkPriv, signature: spkSig)
        try signal.storeSignedPreKey(spk, id: 1, context: ctx)

        // last-resort kyber prekey (PQXDH)
        let kemPair = KEMKeyPair.generate()
        let kyberSig = identity.privateKey.generateSignature(message: kemPair.publicKey.serialize())
        let kyber = try KyberPreKeyRecord(id: 1, timestamp: now, keyPair: kemPair, signature: kyberSig)
        try signal.storeKyberPreKey(kyber, id: 1, context: ctx)

        // one-time EC prekeys
        var otks: [Wire.PreKeyJSON] = []
        for _ in 0..<32 {
            let id = store.blob.nextPreKeyId
            store.update { $0.nextPreKeyId += 1 }
            let record = try PreKeyRecord(id: id, privateKey: PrivateKey.generate())
            try signal.storePreKey(record, id: id, context: ctx)
            otks.append(.init(id: id, pub: try record.publicKey().serialize().base64EncodedString(), sig: nil))
        }

        return RegistrationBundle(
            regId: regId,
            identityKey: identity.identityKey.serialize(),
            signedPreKey: .init(id: 1,
                                pub: spkPriv.publicKey.serialize().base64EncodedString(),
                                sig: spkSig.base64EncodedString()),
            kyberPreKey: .init(id: 1,
                               pub: kemPair.publicKey.serialize().base64EncodedString(),
                               sig: kyberSig.base64EncodedString()),
            oneTimePreKeys: otks,
            authPub: authKey.publicKey.rawRepresentation)
    }

    /// Sign a relay login challenge with the Ed25519 auth key.
    func signChallenge(_ nonce: Data) throws -> Data {
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: store.blob.authPrivateKey)
        return try key.signature(for: nonce)
    }

    // MARK: safety numbers

    /// Signal's production parameters. NEVER change without a coordinated
    /// client-wide version bump — a mismatch makes two honest, up-to-date
    /// clients display different numbers.
    static let fingerprintIterations = 5200
    static let fingerprintVersion = 2

    /// Numeric fingerprint for the channel with `peerHash`. Identifiers are
    /// the username hashes — stable, identical on both devices, so the
    /// number changes IFF a key changes. Throws until the peer's identity
    /// key is on record (first bundle fetch or first received message).
    func fingerprint(with peerHash: String) throws -> Fingerprint {
        guard let peerData = store.blob.knownIdentities[peerHash] else {
            throw SignalError.invalidArgument("no identity on record for peer")
        }
        let localKey = try IdentityKeyPair(bytes: store.blob.identityKeyPair).publicKey
        let remoteKey = try IdentityKey(bytes: peerData).publicKey
        return try NumericFingerprintGenerator(iterations: Self.fingerprintIterations)
            .create(version: Self.fingerprintVersion,
                    localIdentifier: Data(store.blob.usernameHash.utf8),
                    localKey: localKey,
                    remoteIdentifier: Data(peerHash.utf8),
                    remoteKey: remoteKey)
    }

    /// My serialized identity public key — encoded into the add-me QR.
    func myIdentityPublic() throws -> Data {
        try IdentityKeyPair(bytes: store.blob.identityKeyPair).publicKey.serialize()
    }

    /// The peer identity key currently on record (nil until first contact).
    func knownIdentity(of peerHash: String) -> Data? {
        store.blob.knownIdentities[peerHash]
    }

    /// Bridge for identity replacement events (peer hash). Set once by
    /// ChatService.init; fires synchronously inside decrypt/processBundle.
    var onIdentityReplaced: ((String) -> Void)? {
        get { signal.onIdentityReplaced }
        set { signal.onIdentityReplaced = newValue }
    }

    // MARK: sessions

    /// True only for a session usable for SENDING. An archived/reset record
    /// still exists on disk but has no current state — treating it as "no
    /// session" makes every send path (sendText/sendBlob/flushReceipts/
    /// typing) self-healing: they refetch a bundle instead of throwing.
    func hasSession(with peerHash: String) -> Bool {
        guard let data = store.blob.sessions[peerHash],
              let record = try? SessionRecord(bytes: data) else { return false }
        return record.hasCurrentState
    }

    /// Archive (not delete) the ratchet for a peer: old receiving chains are
    /// kept, so stale in-flight ciphertext from the previous session can
    /// still decrypt — strictly fewer lost messages than deletion. Sending
    /// requires a fresh PQXDH handshake afterwards.
    func archiveSession(with peerHash: String) {
        guard let data = store.blob.sessions[peerHash],
              let record = try? SessionRecord(bytes: data) else { return }
        record.archiveCurrentState()
        store.update { $0.sessions[peerHash] = record.serialize() }
    }

    /// Bootstrap a session from a fetched PQXDH prekey bundle.
    func processBundle(_ b: Wire.In, for peerHash: String) throws {
        guard let regId = b.reg_id,
              let idKeyB64 = b.identity_key, let idKey = Data(base64Encoded: idKeyB64),
              let spk = b.signed_prekey, let spkPub = Data(base64Encoded: spk.pub),
              let spkSigB64 = spk.sig, let spkSig = Data(base64Encoded: spkSigB64),
              let kyber = b.kyber_prekey, let kyberPub = Data(base64Encoded: kyber.pub),
              let kyberSigB64 = kyber.sig, let kyberSig = Data(base64Encoded: kyberSigB64)
        else { throw SignalError.invalidArgument("incomplete bundle") }

        let address = try ProtocolAddress(name: peerHash, deviceId: Self.deviceId)
        let bundle: PreKeyBundle
        if let otk = b.one_time_prekey, let otkPub = Data(base64Encoded: otk.pub) {
            bundle = try PreKeyBundle(
                registrationId: regId, deviceId: Self.deviceId,
                prekeyId: otk.id, prekey: PublicKey(otkPub),
                signedPrekeyId: spk.id, signedPrekey: PublicKey(spkPub),
                signedPrekeySignature: spkSig,
                identity: IdentityKey(bytes: idKey),
                kyberPrekeyId: kyber.id, kyberPrekey: KEMPublicKey(kyberPub),
                kyberPrekeySignature: kyberSig)
        } else {
            bundle = try PreKeyBundle(
                registrationId: regId, deviceId: Self.deviceId,
                signedPrekeyId: spk.id, signedPrekey: PublicKey(spkPub),
                signedPrekeySignature: spkSig,
                identity: IdentityKey(bytes: idKey),
                kyberPrekeyId: kyber.id, kyberPrekey: KEMPublicKey(kyberPub),
                kyberPrekeySignature: kyberSig)
        }
        try processPreKeyBundle(bundle, for: address, ourAddress: myAddress,
                                sessionStore: signal, identityStore: signal, context: ctx)
    }

    // MARK: encrypt / decrypt

    /// Encrypt an inner Payload for a peer -> opaque relay `body` string.
    func encrypt(_ payload: Wire.Payload, for peerHash: String) throws -> String {
        let address = try ProtocolAddress(name: peerHash, deviceId: Self.deviceId)
        let plaintext = try JSONEncoder().encode(payload)
        let ciphertext = try signalEncrypt(
            message: plaintext, for: address, localAddress: myAddress,
            sessionStore: signal, identityStore: signal, context: ctx)
        let envelope = Wire.Envelope(type: ciphertext.messageType.rawValue,
                                     data: ciphertext.serialize())
        return try JSONEncoder().encode(envelope).base64EncodedString()
    }

    /// Decrypt a relay `body` string from a peer -> inner Payload.
    func decrypt(_ body: String, from peerHash: String) throws -> Wire.Payload {
        guard let envData = Data(base64Encoded: body) else {
            throw SignalError.invalidArgument("bad body encoding")
        }
        let envelope = try JSONDecoder().decode(Wire.Envelope.self, from: envData)
        let address = try ProtocolAddress(name: peerHash, deviceId: Self.deviceId)

        let plaintext: Data
        if envelope.type == CiphertextMessage.MessageType.preKey.rawValue {
            plaintext = try signalDecryptPreKey(
                message: PreKeySignalMessage(bytes: envelope.data),
                from: address, localAddress: myAddress,
                sessionStore: signal, identityStore: signal,
                preKeyStore: signal, signedPreKeyStore: signal,
                kyberPreKeyStore: signal, context: ctx)
        } else {
            plaintext = try signalDecrypt(
                message: SignalMessage(bytes: envelope.data),
                from: address, to: myAddress,
                sessionStore: signal, identityStore: signal, context: ctx)
        }
        return try JSONDecoder().decode(Wire.Payload.self, from: plaintext)
    }

    /// Encrypt raw bytes for a peer -> binary blob: [1 byte messageType][ciphertext].
    /// Used for attachment/voice content so 12 MiB plaintext stays ≈12 MiB on
    /// the wire (the JSON+base64 path would expand ~2.4x).
    func encryptBlob(_ plaintext: Data, for peerHash: String) throws -> Data {
        let address = try ProtocolAddress(name: peerHash, deviceId: Self.deviceId)
        let ct = try signalEncrypt(message: plaintext, for: address, localAddress: myAddress,
                                   sessionStore: signal, identityStore: signal, context: ctx)
        var out = Data([ct.messageType.rawValue])
        out.append(ct.serialize())
        return out
    }

    /// Decrypt a binary blob ([type][ciphertext]) from a peer -> raw bytes.
    func decryptBlob(_ blob: Data, from peerHash: String) throws -> Data {
        guard blob.count > 1 else { throw SignalError.invalidArgument("empty blob") }
        let type = blob[blob.startIndex]
        let body = Data(blob.dropFirst())
        let address = try ProtocolAddress(name: peerHash, deviceId: Self.deviceId)
        if type == CiphertextMessage.MessageType.preKey.rawValue {
            return try signalDecryptPreKey(
                message: PreKeySignalMessage(bytes: body),
                from: address, localAddress: myAddress,
                sessionStore: signal, identityStore: signal,
                preKeyStore: signal, signedPreKeyStore: signal,
                kyberPreKeyStore: signal, context: ctx)
        }
        return try signalDecrypt(message: SignalMessage(bytes: body),
                                 from: address, to: myAddress,
                                 sessionStore: signal, identityStore: signal, context: ctx)
    }

    // MARK: sealed sender (docs/SEALED-SENDER.md §3, §4.3)

    /// The envelope's type byte, read without touching the ratchet: 2 or 3 on today's path,
    /// 6 for a sealed body. nil when `body` is not an envelope at all.
    func envelopeType(of body: String) -> UInt8? {
        guard let data = Data(base64Encoded: body),
              let envelope = try? JSONDecoder().decode(Wire.Envelope.self, from: data) else { return nil }
        return envelope.type
    }

    /// Seal bytes for a peer: the Signal ciphertext, wrapped so that only they learn it is
    /// from me. The content carries my certificate and, in `groupId`, my full hash, the
    /// relay's binding for it, and the account it is meant for (§3).
    private func sealBytes(_ plaintext: Data, for peerHash: String, own: Sealed.Own) throws -> Data {
        // checked before the ratchet moves: a step taken for bytes that cannot be sealed is wasted
        guard let me = Sealed.hashBytes(store.blob.usernameHash),
              let to = Sealed.hashBytes(peerHash) else {
            throw SignalError.invalidArgument("not a username hash")
        }
        let address = try ProtocolAddress(name: peerHash, deviceId: Self.deviceId)
        let ciphertext = try signalEncrypt(
            message: plaintext, for: address, localAddress: myAddress,
            sessionStore: signal, identityStore: signal, context: ctx)
        let content = try UnidentifiedSenderMessageContent(
            ciphertext, from: own.cert, contentHint: .default, groupId: me + own.bind + to)
        return try sealedSenderEncrypt(content, for: address, identityStore: signal, context: ctx)
    }

    /// Seal an inner Payload for a peer -> relay `body`: today's envelope with type 6.
    func seal(_ payload: Wire.Payload, for peerHash: String, own: Sealed.Own) throws -> String {
        let sealed = try sealBytes(JSONEncoder().encode(payload), for: peerHash, own: own)
        let envelope = Wire.Envelope(type: Sealed.envelopeType, data: sealed)
        return try JSONEncoder().encode(envelope).base64EncodedString()
    }

    /// Seal attachment bytes for a peer -> binary blob: [0x06][sealed bytes]. A blob sent
    /// before the peer has replied is a PreKey message, which carries my identity key in the
    /// clear; sealing it keeps that from the relay too (§5.6).
    func sealBlob(_ plaintext: Data, for peerHash: String, own: Sealed.Own) throws -> Data {
        var out = Data([Sealed.envelopeType])
        out.append(try sealBytes(plaintext, for: peerHash, own: own))
        return out
    }

    /// A sealed message opened as far as its sender: the outer layer and checks C1–C6 have
    /// passed. The ratchet has not been touched.
    struct Opened {
        /// The sender's username hash, as the certificate and binding vouch for it.
        let sender: String
        fileprivate let content: UnidentifiedSenderMessageContent
        fileprivate let certKey: Data
    }

    /// Checks C0–C6 on sealed bytes. `ts` is the relay's timestamp on the frame that carried
    /// them (for a blob, its manifest's): the certificate is judged at the moment the relay
    /// took the message, so one queued for two days still verifies. Throws `Sealed.Rejected`.
    func openSealed(_ sealed: Data, ts: UInt64, roots: [PublicKey], minKeyId: UInt32) throws -> Opened {
        let content: UnidentifiedSenderMessageContent
        do {
            content = try UnidentifiedSenderMessageContent(message: sealed, identityStore: signal, context: ctx)
        } catch {
            throw Sealed.Rejected(check: "C0")
        }
        let cert = content.senderCertificate
        guard let gid = content.groupId, gid.count == 128 else { throw Sealed.Rejected(check: "C4") }
        let hash = Data(gid.prefix(32))
        let bind = Data(gid.dropFirst(32).prefix(64))
        let to = Data(gid.suffix(32))
        try Sealed.checkCertificate(cert, bind: bind, hash: hash, roots: roots,
                                    minKeyId: minKeyId, at: ts)                    // C1–C4
        let sender = Sealed.hex(hash)
        guard sender != store.blob.usernameHash else { throw Sealed.Rejected(check: "C5") }
        guard Sealed.hex(to) == store.blob.usernameHash else { throw Sealed.Rejected(check: "C6") }
        return Opened(sender: sender, content: content, certKey: cert.publicKey.serialize())
    }

    /// The same, from a relay `body`. Anything that is not a type 6 envelope is C0.
    func openSealed(body: String, ts: UInt64, roots: [PublicKey], minKeyId: UInt32) throws -> Opened {
        guard let data = Data(base64Encoded: body),
              let envelope = try? JSONDecoder().decode(Wire.Envelope.self, from: data),
              envelope.type == Sealed.envelopeType else { throw Sealed.Rejected(check: "C0") }
        return try openSealed(envelope.data, ts: ts, roots: roots, minKeyId: minKeyId)
    }

    /// C7, then the ratchet. A PreKey message has to carry the certificate's identity key,
    /// or it is someone else's message in this sender's envelope: dropped. A Whisper needs
    /// that key to be the one on record for the sender; if it is not (their key changed, or
    /// this phone lost the session) the failure is one today's code heals, and says so.
    private func decryptSealedBytes(_ opened: Opened) throws -> Data {
        let address = try ProtocolAddress(name: opened.sender, deviceId: Self.deviceId)
        let type = opened.content.messageType
        if type == .preKey {
            guard let message = try? PreKeySignalMessage(bytes: opened.content.contents),
                  message.identityKey.serialize() == opened.certKey else {
                throw Sealed.Rejected(check: "C7")
            }
            return try signalDecryptPreKey(
                message: message, from: address, localAddress: myAddress,
                sessionStore: signal, identityStore: signal,
                preKeyStore: signal, signedPreKeyStore: signal,
                kyberPreKeyStore: signal, context: ctx)
        }
        guard type == .whisper else { throw Sealed.Rejected(check: "C7") }
        guard store.blob.knownIdentities[opened.sender] == opened.certKey else {
            throw Sealed.Rejected(check: "C7", heal: true)
        }
        return try signalDecrypt(
            message: SignalMessage(bytes: opened.content.contents),
            from: address, to: myAddress,
            sessionStore: signal, identityStore: signal, context: ctx)
    }

    /// An opened sealed message -> inner Payload.
    func decryptSealed(_ opened: Opened) throws -> Wire.Payload {
        try JSONDecoder().decode(Wire.Payload.self, from: decryptSealedBytes(opened))
    }

    /// A sealed blob ([0x06][sealed bytes]) -> raw bytes. Its sender must be the one its
    /// manifest came from, and its certificate is judged at the manifest's timestamp.
    func decryptSealedBlob(_ blob: Data, from peerHash: String, ts: UInt64,
                           roots: [PublicKey], minKeyId: UInt32) throws -> Data {
        guard blob.count > 1, blob[blob.startIndex] == Sealed.envelopeType else {
            throw Sealed.Rejected(check: "C0")
        }
        let opened = try openSealed(Data(blob.dropFirst()), ts: ts, roots: roots, minKeyId: minKeyId)
        guard opened.sender == peerHash else { throw Sealed.Rejected(check: "C4") }
        return try decryptSealedBytes(opened)
    }

    /// Fresh one-time prekeys for relay top-up.
    func generateOneTimePreKeys(_ count: Int) throws -> [Wire.PreKeyJSON] {
        var out: [Wire.PreKeyJSON] = []
        for _ in 0..<count {
            let id = store.blob.nextPreKeyId
            store.update { $0.nextPreKeyId += 1 }
            let record = try PreKeyRecord(id: id, privateKey: PrivateKey.generate())
            try signal.storePreKey(record, id: id, context: ctx)
            out.append(.init(id: id, pub: try record.publicKey().serialize().base64EncodedString(), sig: nil))
        }
        return out
    }
}
