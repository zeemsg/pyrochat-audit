import Foundation
import CryptoKit

/// Desktop-link QR payload + the E2E channel crypto. Constants are FROZEN —
/// docs/MIRROR-PROTOCOL.md is canonical; the web client derives identically.
struct MirrorLink: Equatable {
    let pairId: String
    let browserPub: Data          // 32 raw bytes
    let host: String

    enum ParseError: Error, Equatable {
        case notPyroLink            // wrong prefix — keep scanning silently
        case malformed
        case wrongRelay(expected: String, got: String)
    }

    static func parse(_ raw: String, expectedRelay: URL) throws -> MirrorLink {
        let parts = raw.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 5, parts[0] == "pyro-link" else { throw ParseError.notPyroLink }
        guard parts[1] == "v1" else { throw ParseError.malformed }
        let pairId = parts[2]
        guard pairId.count == 22,
              pairId.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }),
              let pub = Data(base64urlNoPad: parts[3]), pub.count == 32
        else { throw ParseError.malformed }
        let got = parts[4...].joined(separator: ":")
        // anti-phishing check compares HOST only: the web TLS listener
        // legitimately runs on a different port than the phone's ws relay.
        var gotHost = got
        if let colon = got.lastIndex(of: ":"),
           got[got.index(after: colon)...].allSatisfy(\.isNumber) {
            gotHost = String(got[..<colon])
        }
        let expected = expectedRelay.host ?? ""
        guard gotHost == expected else {
            throw ParseError.wrongRelay(expected: expected, got: gotHost)
        }
        return MirrorLink(pairId: pairId, browserPub: pub, host: got)
    }
}

/// The pair-channel record crypto (MIRROR-PROTOCOL.md §3–4).
enum MirrorCrypto {
    struct Keys {
        let p2b: SymmetricKey     // phone → browser
        let b2p: SymmetricKey     // browser → phone
        let sas: String           // 6 digits, shown on both ends
    }

    static func b64url(_ d: Data) -> String {
        d.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func deriveKeys(sharedSecret ss: SharedSecret, pairId: String,
                           phonePub: Data, browserPub: Data) -> Keys {
        let salt = Data("pyro-link-v1|\(pairId)".utf8)
        let suffix = "\(b64url(phonePub))|\(b64url(browserPub))"
        let p2b = ss.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: salt,
            sharedInfo: Data("pyro-link-v1|p2b|\(suffix)".utf8), outputByteCount: 32)
        let b2p = ss.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: salt,
            sharedInfo: Data("pyro-link-v1|b2p|\(suffix)".utf8), outputByteCount: 32)
        let sasKey = ss.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: salt,
            sharedInfo: Data("pyro-link-v1|sas|\(suffix)".utf8), outputByteCount: 4)
        let sasBytes = sasKey.withUnsafeBytes { Data($0) }
        let n = sasBytes.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } % 1_000_000
        return Keys(p2b: p2b, b2p: b2p, sas: String(format: "%06d", n))
    }

    static func nonce(dir: String, seq: UInt64) -> AES.GCM.Nonce {
        var d = Data(dir.utf8)          // "p2b" / "b2p"
        d.append(0)
        var s = seq.bigEndian
        d.append(Data(bytes: &s, count: 8))
        return try! AES.GCM.Nonce(data: d)
    }

    static func aad(dir: String, pairId: String, type: UInt8) -> Data {
        var d = Data("\(dir)|\(pairId)|".utf8)
        d.append(type)
        return d
    }

    /// Build a full record: type ‖ seq ‖ payload. For type 0x01 the caller
    /// prepends the phone pub inside `prefix`.
    static func seal(key: SymmetricKey, dir: String, pairId: String,
                     type: UInt8, seq: UInt64, prefix: Data = Data(),
                     plaintext: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: key,
                                   nonce: nonce(dir: dir, seq: seq),
                                   authenticating: aad(dir: dir, pairId: pairId, type: type))
        var record = Data([type])
        var s = seq.bigEndian
        record.append(Data(bytes: &s, count: 8))
        record.append(prefix)
        record.append(box.ciphertext)
        record.append(box.tag)
        return record
    }

    /// Open a record's encrypted portion (caller has already split type/seq
    /// and any prefix).
    static func open(key: SymmetricKey, dir: String, pairId: String,
                     type: UInt8, seq: UInt64, sealed: Data) throws -> Data {
        guard sealed.count >= 16 else { throw CryptoKitError.authenticationFailure }
        let ct = sealed.prefix(sealed.count - 16)
        let tag = sealed.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: nonce(dir: dir, seq: seq),
                                        ciphertext: ct, tag: tag)
        return try AES.GCM.open(box, using: key,
                                authenticating: aad(dir: dir, pairId: pairId, type: type))
    }
}

extension Data {
    init?(base64urlNoPad s: String) {
        var b64 = s.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        self.init(base64Encoded: b64)
    }
}
