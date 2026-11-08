import XCTest
import LibSignalClient
@testable import PyroChat

// docs/SEALED-SENDER.md §9, "Clients": the rules and the checks, with no relay anywhere.
// The bot's `bot/test/sealed.test.mjs` is the same list in JavaScript.

/// A stand-in for the relay's signing side, built with libsignal itself: a trust root, a
/// server certificate under it, and sender certificates and bindings under that.
struct SealedTestAuthority {
    let root = PrivateKey.generate()
    let server = PrivateKey.generate()
    let serverCert: ServerCertificate
    var roots: [PublicKey] { [root.publicKey] }

    init(keyId: UInt32 = 1) {
        serverCert = try! ServerCertificate(keyId: keyId, publicKey: server.publicKey, trustRoot: root)
    }

    /// `bindHash` signs the binding for another account than the certificate names.
    func issue(hash: Data, identityKey: PublicKey, expires: UInt64, device: UInt32 = 1,
               bindHash: Data? = nil, bindExpires: UInt64? = nil) -> Sealed.Own {
        let address = try! SealedSenderAddress(e164: nil, uuidString: Sealed.uuid(of: hash), deviceId: device)
        let cert = try! SenderCertificate(sender: address, publicKey: identityKey, expiration: expires,
                                          signerCertificate: serverCert, signerKey: server)
        let signed = Sealed.bindingMessage(hash: bindHash ?? hash, identityKey: identityKey.serialize(),
                                           expires: bindExpires ?? expires)
        return Sealed.Own(cert: cert, bind: server.generateSignature(message: signed))
    }
}

/// One identity with its own encrypted store: the crypto half of a phone.
final class SealedParty {
    let store: SecureStore
    let crypto: CryptoEngine
    let registration: CryptoEngine.RegistrationBundle
    var hash: String { store.blob.usernameHash }
    var hashBytes: Data { Sealed.hashBytes(hash)! }
    var identity: PublicKey { try! PublicKey(registration.identityKey) }
    var own: Sealed.Own!
    let expires = UInt64(Date().timeIntervalSince1970 * 1000) + 86_400_000

    init(_ name: String, authority: SealedTestAuthority? = nil) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-sealed-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        store = SecureStore(creatingWithPassword: "sealed-test-pass", at: dir.appendingPathComponent("store"))
        crypto = CryptoEngine(store: store)
        registration = try! crypto.generateIdentity(username: name + UUID().uuidString.prefix(8).lowercased())
        if let authority { own = authority.issue(hash: hashBytes, identityKey: identity, expires: expires) }
    }

    /// My prekey bundle, as the relay would hand it out.
    func bundle(as user: String? = nil) -> Wire.In {
        Wire.In(t: "prekey_bundle", user: user ?? hash, reg_id: registration.regId,
                identity_key: registration.identityKey.base64EncodedString(),
                signed_prekey: registration.signedPreKey, kyber_prekey: registration.kyberPreKey,
                one_time_prekey: registration.oneTimePreKeys.first)
    }

    /// Seal by hand, with any certificate and any `groupId`: what an attacker can build.
    func rawSeal(to peerHash: String, text: String = "x", cert: SenderCertificate, groupId: Data,
                 inner: CiphertextMessage? = nil) throws -> Data {
        let signal = SignalStore(store: store)
        let ctx = SignalStore.Context()
        let to = try ProtocolAddress(name: peerHash, deviceId: 1)
        let me = try ProtocolAddress(name: hash, deviceId: 1)
        let ciphertext = try inner ?? signalEncrypt(message: Data(text.utf8), for: to, localAddress: me,
                                                    sessionStore: signal, identityStore: signal, context: ctx)
        let content = try UnidentifiedSenderMessageContent(ciphertext, from: cert, contentHint: .default,
                                                           groupId: groupId)
        return try sealedSenderEncrypt(content, for: to, identityStore: signal, context: ctx)
    }
}

final class SealedRulesTests: XCTestCase {
    private let hex = String(repeating: "ab", count: 32)
    private func key(_ byte: UInt8, _ n: Int = 32) -> Data { Data(repeating: byte, count: n) }
    private func b64(_ d: Data) -> String { d.base64EncodedString() }

    // MARK: §5.1 — what is learned, and when

    func testAPayloadTeachesThePeersKeyAndWhatTheyHoldOfMine() {
        let theirs = key(1), mine = key(2)
        let p = Sealed.payloadReceived(nil, dk: b64(theirs), dkh: b64(Sealed.fingerprint(mine)))
        XCTAssertEqual(p, Sealed.PeerKey(dk: theirs, ack: Sealed.fingerprint(mine), sealed: true))
        XCTAssertEqual(Sealed.payloadReceived(p, dk: b64(theirs), dkh: b64(Sealed.fingerprint(mine))), p,
                       "nothing changed")
    }

    func testAPayloadWithoutDkhClearsTheEchoSoMyKeyIsSentAgain() {
        let mine = key(2)
        var p = Sealed.payloadReceived(nil, dk: nil, dkh: b64(Sealed.fingerprint(mine)))
        XCTAssertNil(Sealed.outgoingFields(p, myDeliveryKey: mine).dk, "they hold my key: nothing to send")
        p = Sealed.payloadReceived(p, dk: nil, dkh: nil)          // a restore from an old backup, say
        XCTAssertNil(p.ack)
        XCTAssertEqual(Sealed.outgoingFields(p, myDeliveryKey: mine).dk, b64(mine))
    }

    func testDkIsSentUntilEchoedAndAgainAfterMyKeyChangesAndDkhWhileIHoldTheirs() {
        let mine = key(2), next = key(3), theirs = key(1)
        var out = Sealed.outgoingFields(nil, myDeliveryKey: mine)
        XCTAssertEqual(out.dk, b64(mine)); XCTAssertNil(out.dkh)
        let p = Sealed.payloadReceived(nil, dk: b64(theirs), dkh: b64(Sealed.fingerprint(mine)))
        out = Sealed.outgoingFields(p, myDeliveryKey: mine)
        XCTAssertNil(out.dk); XCTAssertEqual(out.dkh, b64(Sealed.fingerprint(theirs)))
        out = Sealed.outgoingFields(p, myDeliveryKey: next)
        XCTAssertEqual(out.dk, b64(next)); XCTAssertEqual(out.dkh, b64(Sealed.fingerprint(theirs)))
    }

    func testMalformedKeysAreIgnored() {
        var p = Sealed.payloadReceived(nil, dk: b64(key(1, 31)), dkh: b64(key(1, 7)))
        XCTAssertTrue(p.isEmpty)
        p = Sealed.payloadReceived(nil, dk: "not base64 !!", dkh: "")
        XCTAssertTrue(p.isEmpty)
    }

    func testSealedIsThreeValued() {
        let theirs = key(1)
        XCTAssertEqual(Sealed.bundleSeen(nil, sealed: true).sealed, true)
        XCTAssertEqual(Sealed.bundleSeen(Sealed.bundleSeen(nil, sealed: true), sealed: false).sealed, false)
        var p = Sealed.payloadReceived(Sealed.bundleSeen(nil, sealed: false), dk: b64(theirs), dkh: nil)
        XCTAssertEqual(p.sealed, true, "a delivery key says they can receive sealed")
        p = Sealed.unsupported(p)
        XCTAssertEqual(p.sealed, false); XCTAssertEqual(p.dk, theirs, "`unsupported` keeps the key")
        p = Sealed.sealedReceived(p)
        XCTAssertEqual(p.sealed, true)
    }

    func testDeniedForgetsTheKeyThatWasRefusedAndOnlyThatOne() {
        let old = key(1), newer = key(9)
        var p: Sealed.PeerKey? = Sealed.payloadReceived(nil, dk: b64(old), dkh: nil)
        let dropped = Sealed.denied(p, access: old)
        XCTAssertNotNil(dropped, "the first refusal drops the key: look them up again")
        XCTAssertNil(dropped?.dk); XCTAssertEqual(dropped?.sealed, true)
        XCTAssertNil(Sealed.denied(dropped, access: old), "a second refusal of the same key has nothing left to drop")
        p = Sealed.payloadReceived(dropped, dk: b64(newer), dkh: nil)
        XCTAssertNil(Sealed.denied(p, access: old), "a late refusal of the old key does not cost the newer one")
        XCTAssertNil(Sealed.denied(p, access: nil))
        XCTAssertNil(Sealed.denied(nil, access: old))
    }

    func testARosterKeyIsTakenOnlyWhenNoneIsHeld() {
        let direct = key(1), roster = key(5)
        XCTAssertEqual(Sealed.rosterKey(nil, dk: b64(roster)), Sealed.PeerKey(dk: roster, ack: nil, sealed: true))
        let held = Sealed.payloadReceived(nil, dk: b64(direct), dkh: nil)
        XCTAssertEqual(Sealed.rosterKey(held, dk: b64(roster)), held, "a key from the person beats one from a third party")
        let old = Sealed.bundleSeen(nil, sealed: false)
        XCTAssertEqual(Sealed.rosterKey(old, dk: b64(roster)), Sealed.PeerKey(dk: roster, ack: nil, sealed: false),
                       "known not to be capable stays so")
        XCTAssertNil(Sealed.rosterKey(nil, dk: b64(key(1, 12))))
        XCTAssertNil(Sealed.rosterKey(nil, dk: nil))
    }

    // MARK: §5.2 — the path, first match wins

    func testEveryRowOfThePathTable() {
        let dk = key(1)
        func route(_ p: Sealed.PeerKey?, offered: Bool = true, session: Bool = true) -> Sealed.Lane {
            Sealed.route(p, offered: offered, hasSession: session)
        }
        XCTAssertEqual(route(.init(dk: dk, sealed: true), offered: false), .legacy, "row 1: not offered")
        XCTAssertEqual(route(.init(dk: dk, sealed: false)), .legacy, "row 2: known not capable, even holding a key")
        XCTAssertEqual(route(.init(dk: dk, sealed: true)), .key, "row 3")
        XCTAssertEqual(route(.init(dk: dk), session: false), .key, "row 3: a key from a roster, capability not yet known")
        XCTAssertEqual(route(.init(sealed: true)), .first, "row 4")
        XCTAssertEqual(route(.init(sealed: true), session: false), .first, "row 4: a new contact whose bundle said sealed")
        XCTAssertEqual(route(nil), .legacy, "row 5: an existing conversation")
        XCTAssertEqual(route(nil, session: false), .fetch, "row 6: a new contact")
        XCTAssertEqual(route(.init(sealed: false), session: false), .legacy, "row 2 after the bundle said not sealed")
    }

    // MARK: small pieces

    func testUuidFingerprintDeliveryHashAndBinding() {
        let hash = Sealed.hashBytes("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90")!
        XCTAssertEqual(Sealed.uuid(of: hash), "2bd806c9-7f0e-00af-1a1f-c3328fa763a9")
        XCTAssertEqual(Sealed.hex(hash), "2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90")
        XCTAssertNil(Sealed.hashBytes("2bd806"))
        XCTAssertNil(Sealed.hashBytes(String(repeating: "zz", count: 32)))
        let dk = key(7)
        XCTAssertEqual(Sealed.deliveryHash(dk).count, 32)
        XCTAssertEqual(Sealed.fingerprint(dk), Data(Sealed.deliveryHash(dk).prefix(8)))
        XCTAssertEqual(Sealed.newDeliveryKey().count, 32)
        XCTAssertNotEqual(Sealed.newDeliveryKey(), Sealed.newDeliveryKey())
        let signed = Sealed.bindingMessage(hash: hash, identityKey: key(5, 33), expires: 0x0102030405060708)
        XCTAssertEqual(signed.count, 99)
        XCTAssertEqual(String(decoding: signed.prefix(26), as: UTF8.self), "pyrochat-sender-binding-v1")
        XCTAssertEqual(Array(signed.suffix(8)), [1, 2, 3, 4, 5, 6, 7, 8], "the expiry is big-endian")
    }

    /// The bytes the RELAY writes (its JUnit golden vector, `sealed-test-keys/golden.json`) pass
    /// this phone's own check under the test root, and fail it under any other.
    func testACertificateTheRelayIssuedIsAccepted() throws {
        let hash = Sealed.hashBytes("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90")!
        let identity = Data(base64Encoded: "BQ8mfA3SWlXeq6SvVOeJjaQeWCH/ZXs0EVaUHEcBHCED")!
        let cert = "Cr8BEAEZAKyCHaMBAAAiIQUPJnwN0lpV3qukr1TniY2kHlgh/2V7NBFWlBxHARwhAyppCiUIARIhBc7OugViy6hWpAKt+d/OYEapfYI9yC55FVnKTCN9rSlmEkDDI71XRqp5X/I1/mbG8Vlsw9ClVRxXlZCPqd6DS31QdxrJky5NV7EHKQXonVUvaYS3kX5eToyWzkklteyteNKDMiQyYmQ4MDZjOS03ZjBlLTAwYWYtMWExZi1jMzMyOGZhNzYzYTkSQIQO8UJP1tWhW4HNnGtMD0jViVQqSUDli4ncjpafXukDOIrh8gYD5ROMGGGY8nVTarXD3TfdCvCr43fMdpE27w4="
        let bind = "jQ5YtsyAXkRPskb5yhE/W2r26OBp2QMIeh9JyPdxPaxKt8js14ii8sEaCs6XFBFxvXJ6okBnIt20+amA5R5DCA=="
        let testRoot = try PublicKey(Data(base64Encoded: SealedTrust.testRoot)!)
        let own = try Sealed.takeOwnCertificate(cert: cert, bind: bind, hash: hash, identityKey: identity,
                                                roots: [testRoot], minKeyId: 1)
        XCTAssertEqual(own.cert.expiration, 1_800_086_400_000)
        XCTAssertEqual(own.cert.senderUuid, "2bd806c9-7f0e-00af-1a1f-c3328fa763a9")
        XCTAssertThrowsError(try Sealed.takeOwnCertificate(
            cert: cert, bind: bind, hash: hash, identityKey: identity,
            roots: [PrivateKey.generate().publicKey], minKeyId: 1))
        XCTAssertThrowsError(try Sealed.takeOwnCertificate(
            cert: cert, bind: bind, hash: hash, identityKey: identity, roots: [], minKeyId: 1),
            "a build with no root accepts nothing")
    }

    /// The production root is the one made at the key ceremony and counts for any relay. The
    /// test root is trusted for a relay on this machine and nowhere else.
    func testWhichRootsAreTrustedForWhichRelay() {
        XCTAssertEqual(SealedTrust.productionRoots, ["BbCQLScxmfcklrLQwJRA7ZCFLyzoOAvxNbbu2w4DI0h+"],
                       "the root made on 2026-11-06 (deploy/sealed-keys.mjs root): changing it cuts every installed app off from sealed sender")
        let real = SealedTrust.roots(forRelay: URL(string: "wss://relay.pyrochat.ca/ws"))
        XCTAssertEqual(real.count, 1, "the throwaway root is never trusted for a real relay")
        XCTAssertEqual(real.first.map { Data($0.serialize()).base64EncodedString() }, SealedTrust.productionRoots[0],
                       "and the one root is a key libsignal reads")
        XCTAssertEqual(SealedTrust.roots(forRelay: nil).count, 1)
        // a relay on this machine: the production root and the throwaway one
        XCTAssertEqual(SealedTrust.roots(forRelay: URL(string: "ws://127.0.0.1:7444/ws")).count, 2)
        XCTAssertEqual(SealedTrust.roots(forRelay: URL(string: "ws://localhost:7444/ws")).count, 2)
    }

    // MARK: wire and store (§4.4, §5.1)

    func testThePayloadFieldsRoundTripAndDecodeLeniently() throws {
        var p = Wire.Payload(kind: "text", text: "hi")
        p.dk = b64(key(1)); p.dkh = b64(key(2, 8)); p.refId = "row-1"
        let back = try JSONDecoder().decode(Wire.Payload.self, from: JSONEncoder().encode(p))
        XCTAssertEqual(back.dk, p.dk); XCTAssertEqual(back.dkh, p.dkh); XCTAssertEqual(back.refId, "row-1")
        // a wrong shape drops the field, never the payload: a failed payload decode would be
        // taken for ratchet damage
        let odd = try JSONDecoder().decode(Wire.Payload.self, from: Data(#"{"kind":"text","text":"x","dk":5,"dkh":{"a":1}}"#.utf8))
        XCTAssertNil(odd.dk); XCTAssertNil(odd.dkh); XCTAssertEqual(odd.text, "x")
        // and a payload without them encodes exactly as before
        let plain = try JSONEncoder().encode(Wire.Payload(kind: "text", text: "hi"))
        XCTAssertFalse(String(decoding: plain, as: UTF8.self).contains("dk"))
        let member = try JSONDecoder().decode(Wire.MemberJSON.self, from: Data(#"{"hash":"aa","dk":"AAAA"}"#.utf8))
        XCTAssertEqual(member.dk, "AAAA")
        XCTAssertNil(try JSONDecoder().decode(Wire.MemberJSON.self, from: Data(#"{"hash":"aa","dk":[1]}"#.utf8)).dk)
    }

    func testTheNewFramesEncodeOnlyWhatTheyCarry() throws {
        var deliver = Wire.Out(t: "deliver", id: "i", to: "p", ttl: 60, kind: "msg", body: "AAAA")
        var json = String(decoding: try JSONEncoder().encode(deliver), as: UTF8.self)
        XCTAssertFalse(json.contains("access"), "the first-contact lane carries no delivery key")
        XCTAssertFalse(json.contains("delivery"))
        deliver.access = "KEY"
        json = String(decoding: try JSONEncoder().encode(deliver), as: UTF8.self)
        XCTAssertTrue(json.contains(#""access":"KEY""#))
        let auth = String(decoding: try JSONEncoder().encode(Wire.Out(t: "auth", sig: "s")), as: UTF8.self)
        XCTAssertFalse(auth.contains("delivery"), "a build with no root logs in exactly as before")
        let ready = try JSONDecoder().decode(Wire.In.self, from: Data(#"{"t":"ready","queued":0,"sealed":true,"cert":"C","bind":"B","cert_ttl":86400}"#.utf8))
        XCTAssertEqual(ready.sealed, true); XCTAssertEqual(ready.cert_ttl, 86_400)
        let old = try JSONDecoder().decode(Wire.In.self, from: Data(#"{"t":"ready","queued":2}"#.utf8))
        XCTAssertNil(old.sealed); XCTAssertNil(old.cert)
    }

    func testTheStoreKeepsTheTwoNewKeysAndOpensWithoutThem() throws {
        var blob = SecureStore.Blob()
        blob.deliveryKey = key(7)
        blob.peerKeys = [hex: Sealed.PeerKey(dk: key(1), ack: key(2, 8), sealed: true),
                         String(repeating: "cd", count: 32): Sealed.PeerKey(sealed: false)]
        let data = try JSONEncoder().encode(blob)
        let back = try JSONDecoder().decode(SecureStore.Blob.self, from: data)
        XCTAssertEqual(back.deliveryKey, blob.deliveryKey)
        XCTAssertEqual(back.peerKeys, blob.peerKeys)
        // the names and shapes on disk are a contract with Android
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["deliveryKey"] as? String, b64(key(7)))
        let entry = try XCTUnwrap((json["peerKeys"] as? [String: Any])?[hex] as? [String: Any])
        XCTAssertEqual(entry["dk"] as? String, b64(key(1)))
        XCTAssertEqual(entry["ack"] as? String, b64(key(2, 8)))
        XCTAssertEqual(entry["sealed"] as? Bool, true)

        // a store from before sealed sender
        let old = try JSONDecoder().decode(SecureStore.Blob.self, from: JSONEncoder().encode(SecureStore.Blob()))
        XCTAssertNil(old.deliveryKey); XCTAssertNil(old.peerKeys)
        // damage in either key never costs the store
        var broken = json
        broken["peerKeys"] = "not a map"
        broken["deliveryKey"] = "AAAA"                 // three bytes: not a delivery key
        let opened = try JSONDecoder().decode(SecureStore.Blob.self, from: JSONSerialization.data(withJSONObject: broken))
        XCTAssertNil(opened.peerKeys); XCTAssertNil(opened.deliveryKey)
    }

    func testAFrameForTheDetailsSheetNeverKeepsTheDeliveryKey() throws {
        var out = Wire.Out(t: "deliver", id: "attempt-1", to: hex, ttl: 172_800, kind: "msg", body: "AAAA")
        out.access = "c2VjcmV0LWRlbGl2ZXJ5LWtleQ=="
        let frame = try XCTUnwrap(CapturedFrame(sending: out))
        XCTAssertEqual(frame.t, "deliver"); XCTAssertTrue(frame.isOutgoing)
        XCTAssertFalse("\(frame)".contains("c2VjcmV0"), "there is nowhere in the type to put it")
        let sealedIn = CapturedFrame.received(id: "r", from: nil, kind: "msg", body: "AAAA")
        XCTAssertEqual(sealedIn.peer, "", "a sealed frame arrives naming nobody")
        XCTAssertFalse(sealedIn.isOutgoing)
    }
}

final class SealedCryptoTests: XCTestCase {
    private let authority = SealedTestAuthority()
    private var now: UInt64 { UInt64(Date().timeIntervalSince1970 * 1000) }

    private func open(_ me: SealedParty, _ body: String, ts: UInt64? = nil, roots: [PublicKey]? = nil,
                      minKeyId: UInt32 = 1) throws -> CryptoEngine.Opened {
        try me.crypto.openSealed(body: body, ts: ts ?? now, roots: roots ?? authority.roots, minKeyId: minKeyId)
    }
    private func openBytes(_ me: SealedParty, _ sealed: Data) throws -> CryptoEngine.Opened {
        try me.crypto.openSealed(sealed, ts: now, roots: authority.roots, minKeyId: 1)
    }
    private func assertRejected(_ check: String, heal: Bool = false, _ work: @autoclosure () throws -> Any,
                                _ message: String = "", line: UInt = #line) {
        XCTAssertThrowsError(try work(), message, line: line) { error in
            XCTAssertEqual(error as? Sealed.Rejected, Sealed.Rejected(check: check, heal: heal), message, line: line)
        }
    }
    private func text(_ s: String) -> Wire.Payload { Wire.Payload(kind: "text", text: s) }

    func testMyOwnCertificateIsCheckedTheWayARecipientWillCheckIt() throws {
        let p = SealedParty("self", authority: authority)
        let cert = p.own.cert.serialize().base64EncodedString(), bind = p.own.bind.base64EncodedString()
        func take(hash: Data? = nil, identity: Data? = nil, roots: [PublicKey]? = nil, minKeyId: UInt32 = 1,
                  cert c: String? = nil, bind b: String? = nil) throws -> Sealed.Own {
            try Sealed.takeOwnCertificate(cert: c ?? cert, bind: b ?? bind, hash: hash ?? p.hashBytes,
                                          identityKey: identity ?? p.registration.identityKey,
                                          roots: roots ?? authority.roots, minKeyId: minKeyId)
        }
        XCTAssertNoThrow(try take())
        assertRejected("C1", try take(roots: [PrivateKey.generate().publicKey]), "a relay with another root")
        assertRejected("C2", try take(minKeyId: 2), "a server key the apps have retired")
        assertRejected("C4", try take(hash: Data(repeating: 9, count: 32)))
        assertRejected("C4", try take(identity: PrivateKey.generate().publicKey.serialize()), "a certificate for someone else's key")
        var bad = p.own.bind; bad[3] ^= 1
        assertRejected("C4", try take(bind: bad.base64EncodedString()))
        assertRejected("C1", try take(cert: "AAAA"), "not a certificate at all")
        let device2 = authority.issue(hash: p.hashBytes, identityKey: p.identity, expires: p.expires, device: 2)
        assertRejected("C3", try take(cert: device2.cert.serialize().base64EncodedString(),
                                      bind: device2.bind.base64EncodedString()))
        // the check uses the certificate's own last millisecond, never this phone's clock:
        // one that expired long ago by the wall clock still passes its self-check
        let past = authority.issue(hash: p.hashBytes, identityKey: p.identity, expires: 1_000)
        XCTAssertNoThrow(try take(cert: past.cert.serialize().base64EncodedString(), bind: past.bind.base64EncodedString()))
    }

    func testSealAndOpenBothInnerMessageTypesAndEveryRefusal() throws {
        let alice = SealedParty("alice", authority: authority), bob = SealedParty("bob", authority: authority)
        let mallory = SealedParty("mallory", authority: authority), carol = SealedParty("carol", authority: authority)
        try alice.crypto.processBundle(bob.bundle(), for: bob.hash)

        // a first message is a PreKey message inside; the reply is a Whisper
        let first = try alice.crypto.seal(text("hello"), for: bob.hash, own: alice.own)
        XCTAssertEqual(bob.crypto.envelopeType(of: first), 6)
        let o1 = try open(bob, first)
        XCTAssertEqual(o1.sender, alice.hash)
        XCTAssertEqual(try bob.crypto.decryptSealed(o1).text, "hello")
        let reply = try bob.crypto.seal(text("hi"), for: alice.hash, own: bob.own)
        XCTAssertEqual(try alice.crypto.decryptSealed(try open(alice, reply)).text, "hi")

        let next = try alice.crypto.seal(text("again"), for: bob.hash, own: alice.own)
        assertRejected("C0", try open(mallory, next), "not sealed to her")
        assertRejected("C1", try open(bob, next, ts: alice.expires + 1), "expired at the relay's time")
        XCTAssertNoThrow(try open(bob, next, ts: alice.expires - 1), "valid until its last millisecond")
        assertRejected("C1", try open(bob, next, roots: [PrivateKey.generate().publicKey]))
        assertRejected("C1", try open(bob, next, roots: []), "a build with no root accepts nothing")
        assertRejected("C2", try open(bob, next, minKeyId: 2))
        var bytes = Data(base64Encoded: next)!
        let env = try JSONDecoder().decode(Wire.Envelope.self, from: bytes)
        bytes = env.data; bytes[bytes.count - 30] ^= 1
        assertRejected("C0", try openBytes(bob, bytes), "altered in transit")
        // today's envelope is not a sealed one
        try carol.crypto.processBundle(bob.bundle(), for: bob.hash)
        let legacy = try carol.crypto.encrypt(text("legacy"), for: bob.hash)
        XCTAssertEqual(bob.crypto.envelopeType(of: legacy), 3)
        assertRejected("C0", try open(bob, legacy))
        XCTAssertNil(bob.crypto.envelopeType(of: "not an envelope"))

        // mallory, with her own valid certificate
        try mallory.crypto.processBundle(bob.bundle(), for: bob.hash)
        let toBob = bob.hashBytes
        assertRejected("C4", try openBytes(bob, try mallory.rawSeal(
            to: bob.hash, cert: mallory.own.cert, groupId: alice.hashBytes + mallory.own.bind + toBob)), "alice's hash")
        let twin = Data(mallory.hashBytes.prefix(16)) + Data(repeating: 0x5a, count: 16)
        assertRejected("C4", try openBytes(bob, try mallory.rawSeal(
            to: bob.hash, cert: mallory.own.cert, groupId: twin + mallory.own.bind + toBob)), "a made-up hash sharing her UUID")
        assertRejected("C0", try openBytes(bob, try mallory.rawSeal(
            to: bob.hash, cert: alice.own.cert, groupId: alice.hashBytes + alice.own.bind + toBob)), "alice's certificate")
        assertRejected("C4", try openBytes(bob, try mallory.rawSeal(
            to: bob.hash, cert: mallory.own.cert, groupId: mallory.hashBytes + mallory.own.bind)), "no recipient")
        let otherExpiry = authority.issue(hash: mallory.hashBytes, identityKey: mallory.identity,
                                          expires: mallory.expires, bindExpires: mallory.expires + 1)
        assertRejected("C4", try openBytes(bob, try mallory.rawSeal(
            to: bob.hash, cert: otherExpiry.cert, groupId: mallory.hashBytes + otherExpiry.bind + toBob)),
            "a binding for another expiry")
        let device2 = authority.issue(hash: mallory.hashBytes, identityKey: mallory.identity, expires: mallory.expires, device: 2)
        assertRejected("C3", try openBytes(bob, try mallory.rawSeal(
            to: bob.hash, cert: device2.cert, groupId: mallory.hashBytes + device2.bind + toBob)))

        // the case C6 exists for: an account registered with carol's PUBLIC keys. What alice
        // writes to that account can be handed to carol, who must not read it as written to her.
        let clone = String(repeating: "c1", count: 32)
        try alice.crypto.processBundle(carol.bundle(as: clone), for: clone)
        let forClone = try alice.crypto.seal(text("for the clone only"), for: clone, own: alice.own)
        assertRejected("C6", try open(carol, forClone))

        // after all that, the real message still opens, once
        XCTAssertEqual(try bob.crypto.decryptSealed(try open(bob, next)).text, "again")
        XCTAssertThrowsError(try bob.crypto.decryptSealed(try open(bob, next))) { error in
            guard case SignalError.duplicatedMessage = error else { return XCTFail("a replay fails in the ratchet: \(error)") }
        }
    }

    func testAMessageThatClaimsToBeFromMeIsRefused() throws {
        // C5: only reachable with my own identity key, so it is my own message played back
        let alice = SealedParty("alice", authority: authority)
        let me = alice.hash
        try alice.crypto.processBundle(alice.bundle(), for: me)
        let toSelf = try alice.rawSeal(to: me, cert: alice.own.cert,
                                       groupId: alice.hashBytes + alice.own.bind + alice.hashBytes)
        assertRejected("C5", try openBytes(alice, toSelf))
    }

    func testSomeoneElsesPreKeyMessageInMyEnvelopeIsRefused() throws {
        let dave = SealedParty("dave", authority: authority), erin = SealedParty("erin", authority: authority)
        let mal = SealedParty("mal", authority: authority)
        try dave.crypto.processBundle(erin.bundle(), for: erin.hash)
        try mal.crypto.processBundle(erin.bundle(), for: erin.hash)
        // dave's own PreKey message for erin, as it would cross the relay on today's path
        let signal = SignalStore(store: dave.store)
        let stolen = try signalEncrypt(
            message: try JSONEncoder().encode(text("dave wrote this")),
            for: ProtocolAddress(name: erin.hash, deviceId: 1),
            localAddress: ProtocolAddress(name: dave.hash, deviceId: 1),
            sessionStore: signal, identityStore: signal, context: SignalStore.Context())
        XCTAssertEqual(stolen.messageType, .preKey)
        // mal wraps it in an envelope of her own, certificate and all
        let wrapped = try mal.rawSeal(to: erin.hash, cert: mal.own.cert,
                                      groupId: mal.hashBytes + mal.own.bind + erin.hashBytes, inner: stolen)
        let opened = try openBytes(erin, wrapped)
        XCTAssertEqual(opened.sender, mal.hash, "C0–C6 pass: it really is mal's envelope")
        assertRejected("C7", try erin.crypto.decryptSealed(opened), "but the message inside is not hers")
        XCTAssertFalse(erin.crypto.hasSession(with: mal.hash), "and no session was created for it")
        XCTAssertFalse(erin.crypto.hasSession(with: dave.hash))
    }

    func testAWhisperFromASenderWhoseKeyIsNotTheOneOnRecordHeals() throws {
        let alice = SealedParty("alice", authority: authority), bob = SealedParty("bob", authority: authority)
        try alice.crypto.processBundle(bob.bundle(), for: bob.hash)
        _ = try bob.crypto.decryptSealed(try open(bob, try alice.crypto.seal(text("one"), for: bob.hash, own: alice.own)))
        _ = try alice.crypto.decryptSealed(try open(alice, try bob.crypto.seal(text("two"), for: alice.hash, own: bob.own)))
        let whisper = try alice.crypto.seal(text("three"), for: bob.hash, own: alice.own)
        bob.store.update { $0.knownIdentities[alice.hash] = nil }       // bob lost what he knew about alice
        let opened = try open(bob, whisper)
        assertRejected("C7", heal: true, try bob.crypto.decryptSealed(opened),
                       "the sender is certified, so this one is answered: it heals like any failed decrypt")
    }

    func testACertificateAndABindingForTwoAccountsThatShareAKeyDoNotCombine() throws {
        // The relay refuses to register one identity key under two names; this is the check behind that rule.
        let alice = SealedParty("alice", authority: authority), bob = SealedParty("bob", authority: authority)
        try alice.crypto.processBundle(bob.bundle(), for: bob.hash)
        let second = Data(repeating: 0x77, count: 32)
        let forSecond = authority.issue(hash: second, identityKey: alice.identity, expires: alice.expires)
        // her certificate names the first account; the binding is a genuine one for the second
        assertRejected("C4", try openBytes(bob, try alice.rawSeal(
            to: bob.hash, cert: alice.own.cert, groupId: second + forSecond.bind + bob.hashBytes)))
    }

    func testASealedBlobTakesItsSenderAndItsClockFromItsManifest() throws {
        let alice = SealedParty("alice", authority: authority), bob = SealedParty("bob", authority: authority)
        let carol = SealedParty("carol", authority: authority)
        try alice.crypto.processBundle(bob.bundle(), for: bob.hash)
        let content = Data((0..<200_000).map { UInt8(truncatingIfNeeded: $0 &* 31) })
        let blob = try alice.crypto.sealBlob(content, for: bob.hash, own: alice.own)
        XCTAssertEqual(blob.first, 6)
        func decrypt(from: String, ts: UInt64, _ b: Data? = nil) throws -> Data {
            try bob.crypto.decryptSealedBlob(b ?? blob, from: from, ts: ts, roots: authority.roots, minKeyId: 1)
        }
        assertRejected("C4", try decrypt(from: carol.hash, ts: now), "not the manifest's sender")
        assertRejected("C1", try decrypt(from: alice.hash, ts: alice.expires + 1))
        assertRejected("C0", try decrypt(from: alice.hash, ts: now, Data([3]) + blob.dropFirst()), "a blob that is not sealed")
        // judged at the manifest's time, not at the moment it is opened
        XCTAssertEqual(try decrypt(from: alice.hash, ts: alice.expires - 1), content)
        // a legacy blob is not a sealed one, and the other way round
        let legacy = try alice.crypto.encryptBlob(content, for: bob.hash)
        XCTAssertNotEqual(legacy.first, 6)
        assertRejected("C0", try decrypt(from: alice.hash, ts: now, legacy))
    }
}
