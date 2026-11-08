import Foundation
import CryptoKit
import LibSignalClient

/// The half of sealed sender that is not a socket (docs/SEALED-SENDER.md §3, §5.1, §5.2): what
/// a peer's delivery key is and when to send mine, which path a message takes, and the checks a
/// certificate has to pass before the sender it names is believed.
///
/// The bot's `sealed.mjs` and Android's `Sealed.kt` are these same rules; the three are tested
/// against each other with shared fixtures.
enum Sealed {
    /// `Wire.Envelope.type` of a sealed body, and the first byte of a sealed blob (§4.3).
    static let envelopeType: UInt8 = 6
    /// A first-contact `body` may be this long, in characters (§4.5).
    static let firstContactMaxBody = 16_384
    /// A sealed message makes at most this many attempts, each one step down (§5.2).
    static let maxAttempts = 3

    // MARK: small pieces

    /// What the relay stores for an account: the hash of its delivery key, never the key.
    static func deliveryHash(_ deliveryKey: Data) -> Data { Data(SHA256.hash(data: deliveryKey)) }

    /// The 8 bytes a peer echoes (`dkh`) to say which of my delivery keys it holds.
    static func fingerprint(_ deliveryKey: Data) -> Data { Data(deliveryHash(deliveryKey).prefix(8)) }

    static func newDeliveryKey() -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "no randomness")
        return Data(bytes)
    }

    /// 64 hex characters -> 32 bytes; nil for anything else.
    static func hashBytes(_ hex: String) -> Data? {
        guard hex.utf8.count == 64 else { return nil }
        var out = Data(capacity: 32)
        var high: UInt8?
        for c in hex.utf8 {
            let v: UInt8
            switch c {
            case 48...57: v = c - 48
            case 97...102: v = c - 87
            case 65...70: v = c - 55
            default: return nil
            }
            if let h = high { out.append(h << 4 | v); high = nil } else { high = v }
        }
        return out
    }

    static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    /// The certificate names an account by the first 16 bytes of its username hash, as a UUID.
    static func uuid(of hash: Data) -> String {
        let h = hex(Data(hash.prefix(16)))
        func part(_ from: Int, _ to: Int) -> Substring {
            h[h.index(h.startIndex, offsetBy: from)..<h.index(h.startIndex, offsetBy: to)]
        }
        return "\(part(0, 8))-\(part(8, 12))-\(part(12, 16))-\(part(16, 20))-\(part(20, 32))"
    }

    /// The 99 bytes the relay signs so the FULL username hash can be trusted (§3).
    static func bindingMessage(hash: Data, identityKey: Data, expires: UInt64) -> Data {
        var out = Data("pyrochat-sender-binding-v1".utf8)
        out.append(hash)
        out.append(identityKey)
        withUnsafeBytes(of: expires.bigEndian) { out.append(contentsOf: $0) }
        return out
    }

    // MARK: the checks (§3)

    /// A failed check, by its name in §3 (C0…C7). `heal` is true only for a failure after the
    /// sender is certified that today's code would answer by rebuilding the session.
    struct Rejected: Error, Equatable {
        let check: String
        var heal = false
    }

    /// C1–C4 for a certificate and binding that claim `hash`: chained to a root at time `at`, a
    /// server key that has not been retired, device 1, and a binding the same relay key signed
    /// for exactly this hash, this identity key and this expiry.
    static func checkCertificate(_ cert: SenderCertificate, bind: Data, hash: Data,
                                 roots: [PublicKey], minKeyId: UInt32, at: UInt64) throws {
        guard !roots.isEmpty, cert.validate(trustRoots: roots, time: at) else { throw Rejected(check: "C1") }
        let server = cert.serverCertificate
        guard server.keyId >= minKeyId else { throw Rejected(check: "C2") }
        guard cert.deviceId == CryptoEngine.deviceId else { throw Rejected(check: "C3") }
        guard bind.count == 64, hash.count == 32, uuid(of: hash) == cert.senderUuid else {
            throw Rejected(check: "C4")
        }
        let signed = bindingMessage(hash: hash, identityKey: cert.publicKey.serialize(),
                                    expires: cert.expiration)
        guard (try? server.publicKey.verifySignature(message: signed, signature: bind)) == true else {
            throw Rejected(check: "C4")
        }
    }

    /// My certificate and binding for this login.
    struct Own {
        let cert: SenderCertificate
        let bind: Data
    }

    /// My own certificate and binding as the relay issued them. Checked the way a recipient will
    /// check them, so a relay with another root or a retired key shows up here and not as
    /// messages that silently never arrive. The time used is one millisecond before expiry: this
    /// phone's clock plays no part.
    static func takeOwnCertificate(cert certB64: String, bind bindB64: String, hash: Data,
                                   identityKey: Data, roots: [PublicKey],
                                   minKeyId: UInt32) throws -> Own {
        guard let certBytes = Data(base64Encoded: certB64), let bind = Data(base64Encoded: bindB64),
              let cert = try? SenderCertificate(certBytes), cert.expiration > 0 else {
            throw Rejected(check: "C1")
        }
        try checkCertificate(cert, bind: bind, hash: hash, roots: roots, minKeyId: minKeyId,
                             at: cert.expiration - 1)
        guard cert.publicKey.serialize() == identityKey else { throw Rejected(check: "C4") }
        return Own(cert: cert, bind: bind)
    }

    // MARK: what I know about each peer (§5.1)

    /// One peer, as stored (`peerKeys` in the encrypted store; the same keys on Android).
    struct PeerKey: Codable, Equatable {
        /// Their delivery key, once learned.
        var dk: Data?
        /// The fingerprint of MY key that their latest payload echoed; nil if it had none.
        var ack: Data?
        /// true: can receive sealed. false: known not to. nil: not known yet.
        var sealed: Bool?

        var isEmpty: Bool { dk == nil && ack == nil && sealed == nil }
    }

    /// Every payload received from a peer, on any path. A payload without `dkh` means the peer
    /// does not hold my key (any more), so the echo is forgotten and my key goes out again.
    static func payloadReceived(_ entry: PeerKey?, dk: String?, dkh: String?) -> PeerKey {
        var p = entry ?? PeerKey()
        if let dk, let key = Data(base64Encoded: dk), key.count == 32 {
            p.dk = key
            p.sealed = true
        }
        if let dkh, let echo = Data(base64Encoded: dkh), echo.count == 8 { p.ack = echo } else { p.ack = nil }
        return p
    }

    /// A sealed message from them passed every check.
    static func sealedReceived(_ entry: PeerKey?) -> PeerKey {
        var p = entry ?? PeerKey()
        p.sealed = true
        return p
    }

    /// Every prekey bundle reply says whether the account can receive sealed messages.
    static func bundleSeen(_ entry: PeerKey?, sealed: Bool) -> PeerKey {
        var p = entry ?? PeerKey()
        p.sealed = sealed
        return p
    }

    /// The relay said `unsupported`. The key is kept.
    static func unsupported(_ entry: PeerKey?) -> PeerKey {
        var p = entry ?? PeerKey()
        p.sealed = false
        return p
    }

    /// The relay said `denied` to a message that carried `access`: that key is no longer theirs.
    /// nil when it is not the key held now (another refusal already dropped it, or a newer key
    /// arrived meanwhile); only a non-nil answer is a reason to look the peer up again.
    static func denied(_ entry: PeerKey?, access: Data?) -> PeerKey? {
        guard var p = entry, let held = p.dk, held == access else { return nil }
        p.dk = nil
        return p
    }

    /// A delivery key from a group roster: taken only when none is held for that member. A key
    /// learned from the person beats one learned from a third party.
    static func rosterKey(_ entry: PeerKey?, dk: String?) -> PeerKey? {
        guard entry?.dk == nil, let dk, let key = Data(base64Encoded: dk), key.count == 32 else { return entry }
        var p = entry ?? PeerKey()
        p.dk = key
        if p.sealed != false { p.sealed = true }
        return p
    }

    /// What every payload I send carries: `dk` until they echo my current key, `dkh` while I
    /// hold theirs.
    static func outgoingFields(_ entry: PeerKey?, myDeliveryKey: Data) -> (dk: String?, dkh: String?) {
        let echoed = entry?.ack == fingerprint(myDeliveryKey)
        return (echoed ? nil : myDeliveryKey.base64EncodedString(),
                entry?.dk.map { fingerprint($0).base64EncodedString() })
    }

    // MARK: the path (§5.2)

    enum Lane: String { case legacy, key, first, fetch }

    /// The path for a message to a peer; the first match wins.
    /// `offered`: the relay offered sealed sender at this login, my certificate passed its
    /// self-check and has not run out, and the anonymous connection is usable.
    /// `fetch` means: fetch the bundle (which sets `sealed`), then ask again.
    static func route(_ entry: PeerKey?, offered: Bool, hasSession: Bool) -> Lane {
        guard offered else { return .legacy }                 // row 1
        if entry?.sealed == false { return .legacy }          // row 2
        if entry?.dk != nil { return .key }                   // row 3
        if entry?.sealed == true { return .first }            // row 4
        return hasSession ? .legacy : .fetch                  // rows 5 and 6
    }

    // MARK: what the user is told

    /// §5.2: an attachment, or a message too long for it, on the first-contact lane.
    static func firstContactRefusal(name: String) -> String {
        "ERR: send <\(name)> a text first — attachments work once it is delivered"
    }
    static func firstContactTooLong(name: String) -> String {
        "ERR: too long for a first message — send <\(name)> a short one first"
    }
}

/// Whose word is taken that a certificate is the relay's (docs/SEALED-SENDER.md §7).
enum SealedTrust {
    /// The production trust root(s), base64, as printed by `deploy/sealed-keys.mjs root`.
    /// This one was made on 2026-11-06. Its private half is `deploy/sealed/root.key` on the
    /// owner's Mac and one offline copy, and nowhere else. A build with no root here never
    /// offers sealed sender and behaves on the wire exactly as the builds before it.
    static let productionRoots: [String] = ["BbCQLScxmfcklrLQwJRA7ZCFLyzoOAvxNbbu2w4DI0h+"]

    /// Certificates from a relay signing key older than this are refused (§3, C2).
    static let minServerKeyId: UInt32 = 1

    #if DEBUG
    /// The throwaway root in relay/src/test/resources/sealed-test-keys. Its private half is in
    /// the repository, so it is trusted only by Debug builds and only for a relay on this
    /// machine (see `roots(forRelay:)`). A Release binary does not contain it.
    static let testRoot = "Ba/550LupgYMzcYETJWXYu0sm+4OU5QhjsJxvqRehokn"
    /// Tests may hand in roots of their own.
    static var extraTestRoots: [String] = []
    #endif

    /// The roots a certificate from this relay may chain to.
    static func roots(forRelay url: URL?) -> [PublicKey] {
        var all = productionRoots
        #if DEBUG
        if let host = url?.host, ["127.0.0.1", "localhost", "::1", "10.0.2.2"].contains(host) {
            all.append(testRoot)
            all += extraTestRoots
        }
        #endif
        return all.compactMap { Data(base64Encoded: $0) }.compactMap { try? PublicKey($0) }
    }
}
