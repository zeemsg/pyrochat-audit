import XCTest
import LibSignalClient
@testable import PyroChat

/// docs/SEALED-SENDER.md §9, C2–C9, on the real engine with no relay: two `ChatService`s hand
/// each other the frames their `prepare` produced. What a socket would add is in
/// `SealedLiveTests`.
@MainActor
final class SealedServiceTests: XCTestCase {
    private let authority = SealedTestAuthority()
    private var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    @MainActor private struct Phone {
        let svc: ChatService
        let reg: CryptoEngine.RegistrationBundle
        var hash: String { svc.myHash }
        var deliveryKey: Data { svc.store.blob.deliveryKey! }
        func bundle() -> Wire.In {
            Wire.In(t: "prekey_bundle", user: hash, reg_id: reg.regId,
                    identity_key: reg.identityKey.base64EncodedString(),
                    signed_prekey: reg.signedPreKey, kyber_prekey: reg.kyberPreKey,
                    one_time_prekey: reg.oneTimePreKeys.first)
        }
    }

    /// A service with an identity and, unless `sealed` is false, everything a login on a
    /// sealed-sender relay would have given it. It never connects anywhere.
    private func phone(_ prefix: String, sealed: Bool = true) -> Phone {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-sealed-svc-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "sealed-svc-pass", at: dir.appendingPathComponent("store"))
        store.update { $0.settings.relayURL = "ws://127.0.0.1:9/ws" }      // nothing listens there
        let svc = ChatService(store: store)
        let reg = try! svc.crypto.generateIdentity(username: prefix + UUID().uuidString.prefix(8).lowercased())
        let p = Phone(svc: svc, reg: reg)
        if sealed { arm(p) }
        return p
    }

    private func arm(_ p: Phone, usableFor seconds: Double = 3600) {
        p.svc.sealedRoots = authority.roots
        let own = authority.issue(hash: Sealed.hashBytes(p.hash)!, identityKey: try! PublicKey(p.reg.identityKey),
                                  expires: UInt64(nowMs) + 86_400_000)
        p.svc.sealedOwn = SealedCertificate(material: own, until: .now + .seconds(seconds))
        if p.svc.store.blob.deliveryKey == nil { p.svc.store.update { $0.deliveryKey = Sealed.newDeliveryKey() } }
    }

    /// `prepare` as it runs while the link is up. The link is "up" only for the call, so
    /// nothing queued in the background (a receipt flush) ever tries to reach a relay.
    private func prepare(_ p: Phone, _ payload: Wire.Payload, to peer: Phone, kind: String = "msg",
                         id: String = UUID().uuidString.lowercased()) throws -> ChatService.Outbound {
        p.svc.connState = .online
        defer { p.svc.connState = .offline }
        return try p.svc.prepare(payload, to: peer.hash, kind: kind, ttl: 172_800, id: id)
    }

    /// Hand a prepared frame to its recipient the way the relay would: a sealed one arrives
    /// without `from`, a legacy one names its sender.
    private func deliver(_ out: ChatService.Outbound, from sender: Phone, to p: Phone, ts: Int64? = nil) async {
        let f = out.frame
        await p.svc.routeForTest(Wire.In(t: "recv", id: f.id, from: f.t == "send" ? sender.hash : nil,
                                         kind: f.kind, body: f.body, ts: ts ?? nowMs))
    }

    private func text(_ s: String) -> Wire.Payload { Wire.Payload(kind: "text", text: s, senderName: "t") }
    private func rows(_ p: Phone, with peer: Phone) -> [ChatMessage] {
        (p.svc.messages[peer.hash] ?? []).filter { $0.kind == .text }
    }
    /// What a frame carried, read by decrypting a copy on a throwaway twin of the recipient.
    private func payload(of out: ChatService.Outbound, as recipient: Phone, from sender: Phone) throws -> Wire.Payload {
        let json = try JSONEncoder().encode(recipient.svc.store.blob)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pyro-twin-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let twin = SecureStore(creatingWithPassword: "twin", at: dir.appendingPathComponent("store"))
        let blob = try JSONDecoder().decode(SecureStore.Blob.self, from: json)
        twin.update { $0 = blob }
        let crypto = CryptoEngine(store: twin)
        guard out.frame.t == "deliver" else { return try crypto.decrypt(out.frame.body!, from: sender.hash) }
        let opened = try crypto.openSealed(body: out.frame.body!, ts: UInt64(nowMs), roots: authority.roots, minKeyId: 1)
        XCTAssertEqual(opened.sender, sender.hash)
        return try crypto.decryptSealed(opened)
    }

    // MARK: the whole exchange (C2, C3, C8)

    func testAnExistingConversationMovesToTheKeyLaneInOneLegacyMessage() async throws {
        let alice = phone("alice"), bob = phone("bob")
        try alice.svc.crypto.processBundle(bob.bundle(), for: bob.hash)

        // row 5: a session, nothing known about bob. One more message the old way, carrying my key.
        let rowA = UUID().uuidString.lowercased()
        let m1 = try prepare(alice, text("one"), to: bob, id: rowA)
        XCTAssertEqual(m1.lane, .legacy)
        XCTAssertEqual(m1.frame.t, "send"); XCTAssertEqual(m1.frame.id, rowA, "today's frame: the row id is the relay id")
        XCTAssertNil(m1.frame.access)
        let p1 = try payload(of: m1, as: bob, from: alice)
        XCTAssertEqual(p1.dk, alice.deliveryKey.base64EncodedString()); XCTAssertNil(p1.dkh)
        XCTAssertNil(p1.refId, "the legacy path carries no row id: it never needed one")
        await deliver(m1, from: alice, to: bob)
        XCTAssertEqual(rows(bob, with: alice).map(\.id), [rowA])
        XCTAssertEqual(bob.svc.peerKey(alice.hash)?.dk, alice.deliveryKey)
        XCTAssertEqual(bob.svc.peerKey(alice.hash)?.sealed, true)

        // bob answers on the key lane at once
        let rowB = UUID().uuidString.lowercased()
        let m2 = try prepare(bob, text("two"), to: alice, id: rowB)
        XCTAssertEqual(m2.lane, .key)
        XCTAssertEqual(m2.frame.t, "deliver")
        XCTAssertNotEqual(m2.frame.id, rowB, "the relay sees a fresh id, never the row's")
        XCTAssertEqual(m2.frame.access, alice.deliveryKey.base64EncodedString())
        XCTAssertEqual(m2.callerId, rowB)
        let p2 = try payload(of: m2, as: alice, from: bob)
        XCTAssertEqual(p2.refId, rowB, "the row id rides inside")
        XCTAssertEqual(p2.dk, bob.deliveryKey.base64EncodedString())
        XCTAssertEqual(p2.dkh, Sealed.fingerprint(alice.deliveryKey).base64EncodedString())
        await deliver(m2, from: bob, to: alice)
        XCTAssertEqual(rows(alice, with: bob).filter { $0.direction == .incoming }.map(\.id), [rowB],
                       "filed under the sender's row id, not the relay's")
        XCTAssertEqual(alice.svc.peerKey(bob.hash),
                       Sealed.PeerKey(dk: bob.deliveryKey, ack: Sealed.fingerprint(alice.deliveryKey), sealed: true))

        // from here both directions are sealed, and alice stops sending her key
        let m3 = try prepare(alice, text("three"), to: bob)
        XCTAssertEqual(m3.lane, .key)
        let p3 = try payload(of: m3, as: bob, from: alice)
        XCTAssertNil(p3.dk, "bob echoed it"); XCTAssertEqual(p3.dkh, Sealed.fingerprint(bob.deliveryKey).base64EncodedString())

        // a retry of bob's message (its verdict was lost): a new frame, a new id, the same row
        let again = try prepare(bob, text("two"), to: alice, id: rowB)
        XCTAssertNotEqual(again.frame.id, m2.frame.id, "no id is ever used twice")
        XCTAssertNotEqual(again.frame.body, m2.frame.body, "and nothing sealed is ever sent twice")
        await deliver(again, from: bob, to: alice)
        XCTAssertEqual(rows(alice, with: bob).filter { $0.direction == .incoming }.count, 1, "it arrives once")
        // the same frame delivered twice (the relay redelivers until acked) is not a second row either
        await deliver(again, from: bob, to: alice)
        XCTAssertEqual(rows(alice, with: bob).filter { $0.direction == .incoming }.count, 1)
        XCTAssertTrue(alice.svc.crypto.hasSession(with: bob.hash), "a duplicate is not ratchet damage")
    }

    func testAContactAddedAfterTheUpgradeStartsOnTheFirstContactLane() async throws {
        let alice = phone("alice"), bob = phone("bob")
        try alice.svc.crypto.processBundle(bob.bundle(), for: bob.hash)
        alice.svc.setPeerKey(Sealed.bundleSeen(nil, sealed: true), for: bob.hash)   // what the anonymous fetch recorded

        let row = UUID().uuidString.lowercased()
        let first = try prepare(alice, text("hello"), to: bob, id: row)
        XCTAssertEqual(first.lane, .first)
        XCTAssertEqual(first.frame.t, "deliver"); XCTAssertNil(first.frame.access, "no delivery key yet: that is the lane")
        XCTAssertLessThanOrEqual(first.frame.body!.count, Sealed.firstContactMaxBody)
        XCTAssertEqual(first.payload.refId, nil, "the caller's payload is kept as it was passed, for a retry")

        // C9: typing would spend bob's first-contact allowance
        XCTAssertThrowsError(try prepare(alice, Wire.Payload(kind: "control", control: "typing"), to: bob, kind: "control"))
        // D1: no attachment until the first text is delivered, and it says so
        XCTAssertThrowsError(try prepare(alice, Wire.Payload(kind: "blob_manifest", subtype: "image", chunks: 1, bytes: 9),
                                         to: bob, kind: "blob")) { error in
            XCTAssertEqual("\(error)", Sealed.firstContactRefusal(name: alice.svc.peerName(bob.hash)))
            XCTAssertTrue("\(error)".hasPrefix("ERR: send <"))
        }
        // a first message too long for the lane fails here, not at the relay
        XCTAssertThrowsError(try prepare(alice, text(String(repeating: "long ", count: 3000)), to: bob)) { error in
            XCTAssertTrue("\(error)".hasPrefix("ERR: too long for a first message"), "\(error)")
        }
        // receipts and other controls are fine
        XCTAssertEqual(try prepare(alice, Wire.Payload(kind: "control", control: "receipt", receipt: "delivered", refIds: ["x"]),
                                   to: bob, kind: "control").lane, .first)

        // bob receives it knowing nothing about alice: the frame names nobody, the certificate does
        await deliver(first, from: alice, to: bob)
        XCTAssertEqual(rows(bob, with: alice).map(\.id), [row])
        XCTAssertEqual(bob.svc.contacts.first?.hash, alice.hash)
        XCTAssertEqual(bob.svc.peerKey(alice.hash)?.dk, alice.deliveryKey, "her key came inside")
        XCTAssertEqual(try prepare(bob, text("hi"), to: alice).lane, .key)
    }

    func testThePathTableOnTheRealEngine() throws {
        let alice = phone("alice"), bob = phone("bob")
        try alice.svc.crypto.processBundle(bob.bundle(), for: bob.hash)
        let dk = Sealed.newDeliveryKey()
        func lane(_ entry: Sealed.PeerKey?) throws -> Sealed.Lane {
            alice.svc.setPeerKey(nil, for: bob.hash)
            alice.svc.store.update { $0.peerKeys = entry.map { [bob.hash: $0] } }
            return try prepare(alice, text("x"), to: bob).lane
        }
        XCTAssertEqual(try lane(nil), .legacy, "row 5")
        XCTAssertEqual(try lane(.init(sealed: false)), .legacy, "row 2")
        XCTAssertEqual(try lane(.init(dk: dk, sealed: false)), .legacy, "row 2, even holding a key")
        XCTAssertEqual(try lane(.init(dk: dk, sealed: true)), .key, "row 3")
        XCTAssertEqual(try lane(.init(dk: dk)), .key, "row 3, a roster key")
        XCTAssertEqual(try lane(.init(sealed: true)), .first, "row 4")

        // row 1, three ways: no private connection, a certificate that ran out, nothing offered
        _ = try lane(.init(dk: dk, sealed: true))
        alice.svc.anonFailed = true
        XCTAssertEqual(try prepare(alice, text("x"), to: bob).lane, .legacy)
        XCTAssertNotNil(try payload(of: prepare(alice, text("x"), to: bob), as: bob, from: alice).dk,
                        "the legacy path still carries my key while this login has sealed sender")
        alice.svc.anonFailed = false
        arm(alice, usableFor: -1)
        XCTAssertEqual(try prepare(alice, text("x"), to: bob).lane, .legacy, "nothing is sealed with a certificate that ran out")
        arm(alice)
        XCTAssertEqual(try prepare(alice, text("x"), to: bob).lane, .key)
        alice.svc.sealedOwn = nil
        let off = try prepare(alice, text("x"), to: bob)
        XCTAssertEqual(off.lane, .legacy)
        let p = try payload(of: off, as: bob, from: alice)
        XCTAssertNil(p.dk); XCTAssertNil(p.dkh); XCTAssertNil(p.refId)
        // a forced path after a refusal
        arm(alice)
        alice.svc.connState = .online
        XCTAssertEqual(try alice.svc.prepare(text("x"), to: bob.hash, kind: "msg", ttl: 60, id: "r",
                                             lane: .legacy, attempt: 2).frame.t, "send")
        alice.svc.connState = .offline
        XCTAssertEqual(try alice.svc.prepare(text("x"), to: bob.hash, kind: "msg", ttl: 60, id: "r").lane, .legacy,
                       "with the link down nothing is sealed")
    }

    func testABuildWithNoRootOffersNothingAndSendsTodaysFrames() throws {
        let alice = phone("alice", sealed: false), bob = phone("bob", sealed: false)
        try alice.svc.crypto.processBundle(bob.bundle(), for: bob.hash)
        XCTAssertNil(alice.svc.sealedDeliveryOffer(), "no root: `register` and `auth` carry nothing new")
        XCTAssertNil(alice.svc.store.blob.deliveryKey, "and no key is even created")
        let out = try prepare(alice, text("x"), to: bob, id: "row")
        XCTAssertEqual(out.frame.t, "send"); XCTAssertEqual(out.frame.id, "row")
        let p = try payload(of: out, as: bob, from: alice)
        XCTAssertNil(p.dk); XCTAssertNil(p.dkh); XCTAssertNil(p.refId)
        XCTAssertEqual(alice.svc.sealedStatus, .notOffered)

        // with a root the offer is the hash of a key made once and kept
        alice.svc.sealedRoots = authority.roots
        let offer = try XCTUnwrap(alice.svc.sealedDeliveryOffer())
        let key = try XCTUnwrap(alice.svc.store.blob.deliveryKey)
        XCTAssertEqual(offer, Sealed.deliveryHash(key).base64EncodedString())
        XCTAssertEqual(alice.svc.sealedDeliveryOffer(), offer)
        XCTAssertFalse(offer.contains(key.base64EncodedString()), "the relay is given the hash, never the key")
    }

    // MARK: receiving (C1's frame rules, C5)

    func testFramesThatMixTheTwoPathsAreDroppedAndHealNothing() async throws {
        let alice = phone("alice"), bob = phone("bob")
        try alice.svc.crypto.processBundle(bob.bundle(), for: bob.hash)
        await deliver(try prepare(alice, text("one"), to: bob), from: alice, to: bob)       // legacy, carries her key
        await deliver(try prepare(bob, text("two"), to: alice), from: bob, to: alice)       // sealed; both now hold keys
        XCTAssertEqual(rows(bob, with: alice).count, 1)

        let sealed = try prepare(alice, text("sealed"), to: bob)
        XCTAssertEqual(sealed.frame.t, "deliver")
        // a sealed body on a frame that names its sender
        await bob.svc.routeForTest(Wire.In(t: "recv", id: UUID().uuidString, from: alice.hash, kind: "msg",
                                            body: sealed.frame.body, ts: nowMs))
        // today's envelope on a frame that names nobody
        alice.svc.store.update { $0.peerKeys = nil }
        let legacy = try prepare(alice, text("legacy"), to: bob)
        XCTAssertEqual(legacy.frame.t, "send")
        await bob.svc.routeForTest(Wire.In(t: "recv", id: UUID().uuidString, kind: "msg", body: legacy.frame.body, ts: nowMs))
        // a sealed frame with no timestamp to judge its certificate at
        await bob.svc.routeForTest(Wire.In(t: "recv", id: UUID().uuidString, kind: "msg", body: sealed.frame.body))
        // a sealed frame after its sender's certificate ran out
        await bob.svc.routeForTest(Wire.In(t: "recv", id: UUID().uuidString, kind: "msg", body: sealed.frame.body,
                                            ts: nowMs + 2 * 86_400_000))
        XCTAssertEqual(rows(bob, with: alice).count, 1, "none of them became a row")
        XCTAssertTrue(bob.svc.crypto.hasSession(with: alice.hash), "and none of them cost the session: nothing to heal")
        XCTAssertNil(bob.svc.lastError)
        // the sealed one, delivered properly, still opens
        await deliver(sealed, from: alice, to: bob)
        XCTAssertEqual(rows(bob, with: alice).map(\.text), ["one", "sealed"])
    }

    func testABlockedSenderIsDroppedBeforeTheRatchetAndAnUnblockStillDecrypts() async throws {
        let alice = phone("alice"), bob = phone("bob")
        try alice.svc.crypto.processBundle(bob.bundle(), for: bob.hash)
        await deliver(try prepare(alice, text("one"), to: bob), from: alice, to: bob)
        await deliver(try prepare(bob, text("two"), to: alice), from: bob, to: alice)
        bob.svc.blockContact(alice.hash)
        let before = bob.svc.store.blob.sessions[alice.hash]
        let blocked = try prepare(alice, text("while blocked"), to: bob)
        XCTAssertEqual(blocked.lane, .key)
        await deliver(blocked, from: alice, to: bob)
        XCTAssertEqual(rows(bob, with: alice).count, 1)
        XCTAssertEqual(bob.svc.store.blob.sessions[alice.hash], before, "the ratchet did not move")
        bob.svc.unblockContact(alice.hash)
        await deliver(try prepare(alice, text("after"), to: bob), from: alice, to: bob)
        XCTAssertEqual(rows(bob, with: alice).map(\.text), ["one", "after"])
    }

    func testASealedWhisperThatCannotBeMatchedHealsLikeAnyFailedDecrypt() async throws {
        let alice = phone("alice"), bob = phone("bob")
        try alice.svc.crypto.processBundle(bob.bundle(), for: bob.hash)
        await deliver(try prepare(alice, text("one"), to: bob), from: alice, to: bob)
        await deliver(try prepare(bob, text("two"), to: alice), from: bob, to: alice)
        let whisper = try prepare(alice, text("three"), to: bob)
        XCTAssertEqual(whisper.lane, .key)
        bob.svc.store.update { $0.knownIdentities[alice.hash] = nil }     // bob lost what he knew about alice
        await deliver(whisper, from: alice, to: bob)
        XCTAssertEqual(rows(bob, with: alice).count, 1)
        XCTAssertEqual(bob.svc.lastError, "decrypt_failed")
        XCTAssertFalse(bob.svc.crypto.hasSession(with: alice.hash), "archived: the next send starts a new session")
    }

    // MARK: login and status (C6)

    func testTheCertificateFromReadyIsCheckedBeforeItIsUsed() {
        let alice = phone("alice", sealed: false)
        alice.svc.sealedRoots = authority.roots
        alice.svc.connState = .online
        let own = authority.issue(hash: Sealed.hashBytes(alice.hash)!, identityKey: try! PublicKey(alice.reg.identityKey),
                                  expires: UInt64(nowMs) + 86_400_000)
        let cert = own.cert.serialize().base64EncodedString(), bind = own.bind.base64EncodedString()

        alice.svc.sealedLoggedIn(Wire.In(t: "ready", queued: 0))
        XCTAssertNil(alice.svc.sealedOwn); XCTAssertEqual(alice.svc.sealedStatus, .notOffered)
        XCTAssertFalse(alice.svc.sealedUsable)

        alice.svc.sealedLoggedIn(Wire.In(t: "ready", queued: 0, sealed: true, cert: cert, bind: bind, cert_ttl: 86_400))
        XCTAssertNotNil(alice.svc.sealedOwn); XCTAssertEqual(alice.svc.sealedStatus, .on)
        XCTAssertTrue(alice.svc.sealedUsable)

        // the private connection could not be opened: said so, and nothing is sealed
        alice.svc.anonFailed = true
        alice.svc.refreshSealedStatus()
        XCTAssertEqual(alice.svc.sealedStatus, .noPrivateConnection)
        XCTAssertFalse(alice.svc.sealedUsable)
        // the next login starts clean
        alice.svc.sealedLoggedIn(Wire.In(t: "ready", queued: 0, sealed: true, cert: cert, bind: bind, cert_ttl: 86_400))
        XCTAssertEqual(alice.svc.sealedStatus, .on)

        // a relay with another root: its certificate fails my own check, so sealed sender stays off
        let other = SealedTestAuthority().issue(hash: Sealed.hashBytes(alice.hash)!, identityKey: try! PublicKey(alice.reg.identityKey),
                                                expires: UInt64(nowMs) + 86_400_000)
        alice.svc.takeCertificate(Wire.In(t: "cert", cert: other.cert.serialize().base64EncodedString(),
                                          bind: other.bind.base64EncodedString(), cert_ttl: 86_400))
        XCTAssertNil(alice.svc.sealedOwn); XCTAssertEqual(alice.svc.sealedStatus, .notOffered)

        // a renewal replaces the one held
        alice.svc.takeCertificate(Wire.In(t: "cert", cert: cert, bind: bind, cert_ttl: 86_400))
        XCTAssertNotNil(alice.svc.sealedOwn)
        // a ten-second certificate is usable for nine: sealing stops a tenth of its life before the end
        alice.svc.takeCertificate(Wire.In(t: "cert", cert: cert, bind: bind, cert_ttl: 10))
        let left = ContinuousClock.now.duration(to: alice.svc.sealedOwn!.until)
        XCTAssertTrue(left > .seconds(8) && left <= .seconds(9), "\(left)")
        // the link going down takes it away
        alice.svc.sealedLinkDown()
        XCTAssertNil(alice.svc.sealedOwn); XCTAssertEqual(alice.svc.sealedStatus, .notOffered)
    }

    func testTheStatusRowSaysWhichOfTheThreeItIs() {
        let on = SecurityStatus.senderHiddenRow(.on)
        XCTAssertEqual(on.label, "sender hidden"); XCTAssertEqual(on.value, "ON")
        XCTAssertTrue(on.note.contains("when the other person's app supports it"), "ON carries its limit")
        XCTAssertNil(on.anchor, "a fact about this login, not a switch")
        let none = SecurityStatus.senderHiddenRow(.notOffered), failed = SecurityStatus.senderHiddenRow(.noPrivateConnection)
        XCTAssertEqual(none.value, "OFF"); XCTAssertEqual(failed.value, "OFF")
        XCTAssertNotEqual(none.note, failed.note)
        XCTAssertTrue(none.note.contains("told who sends") && failed.note.contains("told who sends"),
                      "OFF says what the relay learns")
        for state in SecurityStatus.SenderHidden.allCases {
            var input = SecurityStatus.Input()
            input.senderHidden = state
            let leaves = SecurityStatus.groups(input)[1].rows.map(\.label)
            XCTAssertEqual(Array(leaves.suffix(2)), ["sender hidden", "verified contacts"], "after the switches, before the last row")
        }
    }

    // MARK: verdicts from the anonymous connection

    func testAVerdictIsMappedBackToTheCallersId() async throws {
        let alice = phone("alice"), bob = phone("bob")
        try alice.svc.crypto.processBundle(bob.bundle(), for: bob.hash)
        alice.svc.setPeerKey(.init(dk: Sealed.newDeliveryKey(), sealed: true), for: bob.hash)
        let link = AnonLink(client: RelayClient(url: URL(string: "ws://127.0.0.1:9/ws")!))
        func pendingRow(_ id: String) {
            alice.svc.appendMessage(ChatMessage(id: id, direction: .outgoing, kind: .text, text: "x", timestamp: Date(),
                                                status: .pending, burnAfter: nil, burnStartedAt: nil), thread: bob.hash)
        }
        func status(_ id: String) -> ChatMessage.Status? { alice.svc.messages[bob.hash]?.first { $0.id == id }?.status }

        // `sent` for the attempt's id marks the ROW sent
        pendingRow("row-1")
        let one = try prepare(alice, text("x"), to: bob, id: "row-1")
        link.attempts[one.frame.id!] = one
        await alice.svc.routeAnonForTest(Wire.In(t: "sent", id: one.frame.id), on: link)
        XCTAssertEqual(status("row-1"), .sent)
        XCTAssertTrue(link.attempts.isEmpty)
        // a `sent` for an id this connection never used changes nothing
        pendingRow("row-2")
        await alice.svc.routeAnonForTest(Wire.In(t: "sent", id: "row-2"), on: link)
        XCTAssertEqual(status("row-2"), .pending, "a row id is never a relay id on this connection")

        // an ordinary refusal fails the row, like the same refusal on the logged-in socket
        let two = try prepare(alice, text("x"), to: bob, id: "row-2")
        link.attempts[two.frame.id!] = two
        await alice.svc.routeAnonForTest(Wire.In(t: "error", code: "queue_full", ref: two.frame.id), on: link)
        XCTAssertEqual(status("row-2"), .failed)
        XCTAssertEqual(alice.svc.lastError, "queue_full")

        // `unsupported`: an old build has the account. The key is kept, the peer is marked, the
        // message goes again the old way, and this connection takes nothing more.
        pendingRow("row-3")
        let three = try prepare(alice, text("x"), to: bob, id: "row-3")
        alice.svc.captureFrame(three, for: "row-3")
        XCTAssertEqual(alice.svc.frameCapture.frame(for: "row-3")?.t, "deliver")
        link.attempts[three.frame.id!] = three
        alice.svc.connState = .online
        await alice.svc.routeAnonForTest(Wire.In(t: "error", code: "unsupported", ref: three.frame.id), on: link)
        alice.svc.connState = .offline
        XCTAssertEqual(alice.svc.peerKey(bob.hash)?.sealed, false)
        XCTAssertNotNil(alice.svc.peerKey(bob.hash)?.dk, "`unsupported` keeps the key")
        XCTAssertTrue(link.ended, "retired, and with nothing left in flight, closed")
        let after = try XCTUnwrap(alice.svc.frameCapture.frame(for: "row-3"))
        XCTAssertEqual(after.t, "send", "the details sheet shows the frame that replaced it")
        XCTAssertEqual(after.id, "row-3", "and on the legacy path the relay id is the row id again")
        XCTAssertNotEqual(after.id, three.frame.id, "no id is used on both sockets")

        // a connection that ends with a message in flight fails it, to be retried when one is back
        let fresh = AnonLink(client: RelayClient(url: URL(string: "ws://127.0.0.1:9/ws")!))
        alice.svc.setPeerKey(.init(dk: Sealed.newDeliveryKey(), sealed: true), for: bob.hash)
        pendingRow("row-4")
        let four = try prepare(alice, text("x"), to: bob, id: "row-4")
        fresh.attempts[four.frame.id!] = four
        alice.svc.anonEnd(fresh)
        XCTAssertEqual(status("row-4"), .failed)
        XCTAssertTrue(alice.svc.anonLostWork)

        // `intro_limit`: the relay found a first message too large after all. The row fails, in words.
        let third = AnonLink(client: RelayClient(url: URL(string: "ws://127.0.0.1:9/ws")!))
        alice.svc.setPeerKey(.init(sealed: true), for: bob.hash)        // no key: the first-contact lane
        pendingRow("row-5")
        let five = try prepare(alice, text("x"), to: bob, id: "row-5")
        XCTAssertEqual(five.lane, .first)
        third.attempts[five.frame.id!] = five
        await alice.svc.routeAnonForTest(Wire.In(t: "error", code: "intro_limit", ref: five.frame.id), on: third)
        XCTAssertEqual(status("row-5"), .failed)
        XCTAssertEqual(alice.svc.lastError, Sealed.firstContactTooLong(name: alice.svc.peerName(bob.hash)))
        XCTAssertFalse(third.ended, "a refusal that changes no path leaves the connection alone")
    }

    func testALookupsDeadlineNeverCutsShortALaterLookupForTheSamePerson() async throws {
        ChatService.bundleFetchTimeout = .milliseconds(600)
        defer { ChatService.bundleFetchTimeout = .seconds(15) }
        let alice = phone("alice"), bob = phone("bob"), carol = phone("carol")
        let link = AnonLink(client: RelayClient(url: URL(string: "ws://127.0.0.1:9/ws")!))
        alice.svc.relay = RelayClient(url: URL(string: "ws://127.0.0.1:9/ws")!)
        alice.svc.connState = .online
        defer { alice.svc.connState = .offline }
        alice.svc.anonLink = link
        alice.svc.anonLinks = [link]
        var bundle = bob.bundle()
        bundle.sealed = true

        // the first lookup is answered at once…
        async let one = alice.svc.fetchBundle(for: bob.hash)
        for _ in 0..<200 where link.fetches.isEmpty { await Task.yield() }
        await alice.svc.routeAnonForTest(bundle, on: link)
        _ = try await one
        // …and a second one for the same person is still waiting when the first one's deadline passes
        try await Task.sleep(for: .milliseconds(400))
        async let two = alice.svc.fetchBundle(for: bob.hash)
        for _ in 0..<200 where link.fetches.isEmpty { await Task.yield() }
        try await Task.sleep(for: .milliseconds(350))
        XCTAssertEqual(link.fetches, [bob.hash], "the first deadline left the second lookup alone")
        await alice.svc.routeAnonForTest(bundle, on: link)
        let got = try await two
        XCTAssertEqual(got.user, bob.hash)

        // its own deadline still holds: a lookup nobody answers fails when its time is up
        let started = Date()
        do {
            _ = try await alice.svc.fetchBundle(for: carol.hash)
            XCTFail("an unanswered lookup must time out")
        } catch {
            XCTAssertEqual("\(error)", "timeout")
        }
        XCTAssertGreaterThan(Date().timeIntervalSince(started), 0.5)
        XCTAssertTrue(link.fetches.isEmpty, "and it no longer holds the connection open")
    }

    func testAKeyLookupInFlightKeepsARetiringConnectionOpenAndOneThatEndsFailsItAtOnce() async throws {
        let alice = phone("alice"), bob = phone("bob"), carol = phone("carol")
        func link() -> AnonLink { AnonLink(client: RelayClient(url: URL(string: "ws://127.0.0.1:9/ws")!)) }
        alice.svc.relay = RelayClient(url: URL(string: "ws://127.0.0.1:9/ws")!)     // never connected: only asked whether it exists
        alice.svc.connState = .online
        defer { alice.svc.connState = .offline }

        // the lookup for bob is written and the connection is told to retire (what a fallback
        // or leaving the app does): it stays until the answer is back
        let first = link()
        alice.svc.anonLink = first
        alice.svc.anonLinks = [first]
        async let answer = alice.svc.fetchBundle(for: bob.hash)
        for _ in 0..<200 where first.fetches.isEmpty { await Task.yield() }
        XCTAssertEqual(first.fetches, [bob.hash])
        alice.svc.anonRetire(first)
        XCTAssertFalse(first.ended, "the answer is still owed on it")
        var bundle = bob.bundle()
        bundle.sealed = true
        await alice.svc.routeAnonForTest(bundle, on: first)
        let got = try await answer
        XCTAssertEqual(got.user, bob.hash)
        XCTAssertTrue(first.ended, "and then it closed")
        XCTAssertEqual(alice.svc.peerKey(bob.hash)?.sealed, true)

        // a connection that ends with a lookup on it says so at once, not after the fifteen-second wait
        let second = link()
        alice.svc.anonLink = second
        alice.svc.anonLinks = [second]
        async let lost = alice.svc.fetchBundle(for: carol.hash)
        for _ in 0..<200 where second.fetches.isEmpty { await Task.yield() }
        XCTAssertEqual(second.fetches, [carol.hash])
        let started = Date()
        alice.svc.anonEnd(second)
        do {
            _ = try await lost
            XCTFail("a lookup on a connection that ended must fail")
        } catch {}
        XCTAssertLessThan(Date().timeIntervalSince(started), 3, "failed promptly")
        XCTAssertTrue(alice.svc.anonLostWork, "what waited on it goes again when a connection is back")
    }

    func testARosterCarriesDeliveryKeysAndNeverOverridesADirectOne() throws {
        let alice = phone("alice"), bob = phone("bob"), carol = phone("carol")
        XCTAssertEqual(alice.svc.rosterDeliveryKey(for: alice.hash), alice.deliveryKey.base64EncodedString(), "my own, for me")
        XCTAssertNil(alice.svc.rosterDeliveryKey(for: bob.hash), "none held, none given")
        alice.svc.setPeerKey(.init(dk: bob.deliveryKey, sealed: true), for: bob.hash)
        XCTAssertEqual(alice.svc.rosterDeliveryKey(for: bob.hash), bob.deliveryKey.base64EncodedString())
        alice.svc.sealedOwn = nil
        XCTAssertNil(alice.svc.rosterDeliveryKey(for: bob.hash), "a login without sealed sender sends today's roster")

        // carol learns bob's key from a roster, and only because she held none
        carol.svc.learnRosterKey(bob.deliveryKey.base64EncodedString(), for: bob.hash)
        XCTAssertEqual(carol.svc.peerKey(bob.hash), Sealed.PeerKey(dk: bob.deliveryKey, sealed: true))
        let other = Sealed.newDeliveryKey()
        carol.svc.learnRosterKey(other.base64EncodedString(), for: bob.hash)
        XCTAssertEqual(carol.svc.peerKey(bob.hash)?.dk, bob.deliveryKey)
        carol.svc.learnRosterKey(other.base64EncodedString(), for: carol.hash)
        XCTAssertNil(carol.svc.peerKey(carol.hash), "never an entry for myself")
        // with a session she can write to bob on the key lane without ever having spoken to him
        try carol.svc.crypto.processBundle(bob.bundle(), for: bob.hash)
        XCTAssertEqual(try prepare(carol, text("hi"), to: bob).lane, .key)
    }
}
