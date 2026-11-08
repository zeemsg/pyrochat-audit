import XCTest
@testable import PyroChat

/// Sealed sender end to end (docs/SEALED-SENDER.md §9): real `ChatService`s against the dev
/// relay on 127.0.0.1:7444 with `sealed.enabled=true` and the throwaway test keys. Every test
/// skips itself on a relay that does not offer sealed sender, so the suite stays green in both
/// relay modes. What the relay was told is read from its own totals (`/admin/data`): how many
/// messages it accepted sealed, on the first-contact lane, and the old way.
@MainActor
final class SealedLiveTests: XCTestCase {

    private func makeService(_ prefix: String = "sl") -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-sealed-live-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "sealed-live-pass",
                                at: dir.appendingPathComponent("store"))
        store.update { $0.settings.sendReadReceipts = true }
        return (ChatService(store: store), prefix + UUID().uuidString.prefix(8).lowercased())
    }

    private func waitUntil(_ timeout: TimeInterval = 10, _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return cond()
    }

    private struct Totals: Equatable { var sealed = 0, firstContact = 0, legacy = 0 }

    /// The relay's own count of what it accepted, since it started.
    private func totals(host: String = "127.0.0.1:7444") async throws -> Totals {
        var req = URLRequest(url: URL(string: "http://\(host)/admin/data")!)
        req.setValue("Basic " + Data("admin:dev-admin-pw".utf8).base64EncodedString(), forHTTPHeaderField: "Authorization")
        req.setValue("close", forHTTPHeaderField: "Connection")
        for _ in 0..<3 {
            // a session per request: the admin handler closes the connection after every reply
            let session = URLSession(configuration: .ephemeral)
            defer { session.invalidateAndCancel() }
            if let (data, _) = try? await session.data(for: req),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let s = json["sealedSender"] as? [String: Any] {
                return Totals(sealed: s["sealed"] as? Int ?? 0, firstContact: s["firstContact"] as? Int ?? 0,
                              legacy: s["legacy"] as? Int ?? 0)
            }
        }
        throw XCTSkip("the relay's totals are not readable (admin password, or a relay without them)")
    }

    /// Two registered services on a relay that offers sealed sender, or a skip.
    private func pair(_ a: String = "alice", _ b: String = "bob") async throws
        -> (alice: ChatService, bob: ChatService, aliceName: String, bobName: String) {
        let (alice, aliceName) = makeService(a), (bob, bobName) = makeService(b)
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        guard alice.sealedStatus == .on, bob.sealedStatus == .on else {
            alice.goOffline(); bob.goOffline()
            throw XCTSkip("this relay does not offer sealed sender")
        }
        return (alice, bob, aliceName, bobName)
    }

    private func contact(_ s: ChatService, _ peer: ChatService) -> Contact {
        s.contacts.first { $0.hash == peer.myHash }!
    }
    private func texts(_ s: ChatService, with peer: ChatService, _ direction: ChatMessage.Direction) -> [ChatMessage] {
        (s.messages[peer.myHash] ?? []).filter { $0.kind == .text && $0.direction == direction }
    }

    /// alice adds bob, writes first, and both end up holding each other's delivery key.
    private func introduce(_ alice: ChatService, _ bob: ChatService, _ bobName: String) async throws {
        try await alice.addContact(username: bobName)
        await alice.sendText("hello", to: contact(alice, bob))
        let exchanged = await waitUntil {
            alice.peerKey(bob.myHash)?.dk != nil && bob.peerKey(alice.myHash)?.dk != nil
                && self.texts(alice, with: bob, .outgoing).first?.status == .delivered
        }
        XCTAssertTrue(exchanged, "first message delivered and delivery keys exchanged")
    }

    // MARK: -

    func testFirstContactThenBothWaysSealedAndTheRelayIsNotToldWhoWrites() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        let before = try await totals()

        // adding a contact asks for their keys on the anonymous connection: the relay is not
        // told who is about to write to whom
        try await alice.addContact(username: bobName)
        XCTAssertEqual(alice.peerKey(bob.myHash)?.sealed, true, "the bundle said bob can receive sealed messages")
        XCTAssertNotNil(alice.anonLink, "the private connection is open")
        XCTAssertEqual(alice.sealedLane(for: bob.myHash), .first)

        await alice.sendText("hello bob", to: contact(alice, bob))
        let row = try XCTUnwrap(texts(alice, with: bob, .outgoing).first)
        let arrived = await waitUntil { self.texts(bob, with: alice, .incoming).first?.text == "hello bob" }
        XCTAssertTrue(arrived, "bob gets a frame that names nobody, and knows it is alice")
        XCTAssertEqual(texts(bob, with: alice, .incoming).first?.id, row.id, "filed under her row id, which rode inside")
        XCTAssertEqual(bob.contacts.first { $0.hash == alice.myHash }?.name.isEmpty, false)

        // SENT when the relay took it, DELIVERED when bob's receipt came back (sealed, on the key lane)
        let delivered = await waitUntil { self.texts(alice, with: bob, .outgoing).first?.status == .delivered }
        XCTAssertTrue(delivered)
        XCTAssertEqual(alice.peerKey(bob.myHash)?.dk, bob.store.blob.deliveryKey, "his receipt carried his key")
        XCTAssertEqual(bob.peerKey(alice.myHash)?.dk, alice.store.blob.deliveryKey, "her message carried hers")
        XCTAssertEqual(alice.sealedLane(for: bob.myHash), .key)

        // READ, and a reply
        bob.noteRowVisible(messageID: row.id, thread: alice.myHash)
        let read = await waitUntil { self.texts(alice, with: bob, .outgoing).first?.status == .read }
        XCTAssertTrue(read, "the read receipt is sealed too")
        await bob.sendText("hello alice", to: contact(bob, alice))
        let replied = await waitUntil { self.texts(alice, with: bob, .incoming).first?.text == "hello alice" }
        XCTAssertTrue(replied)
        _ = await waitUntil { self.texts(bob, with: alice, .outgoing).first?.status == .delivered }

        // the details sheet shows the frames as they were: `deliver` going out, no `from` coming in
        let sentFrame = try XCTUnwrap(alice.frameCapture.frame(for: row.id))
        XCTAssertEqual(sentFrame.t, "deliver")
        XCTAssertNotEqual(sentFrame.id, row.id, "the relay never saw the row id")
        let gotFrame = try XCTUnwrap(bob.frameCapture.frame(for: row.id))
        XCTAssertEqual(gotFrame.t, "recv"); XCTAssertEqual(gotFrame.peer, "", "the frame bob got names no sender")

        // what the relay counted: nothing the old way, and exactly one first-contact message
        let after = try await totals()
        XCTAssertEqual(after.legacy - before.legacy, 0, "not one message named its sender to the relay")
        XCTAssertEqual(after.firstContact - before.firstContact, 1, "only the very first message used the first-contact lane")
        XCTAssertEqual(after.sealed - before.sealed, 4,
                       "bob's two receipts, his reply and alice's receipt for it: all on the key lane")
    }

    func testAnAttachmentWaitsForTheFirstTextThenSendsItself() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        let before = try await totals()
        try await alice.addContact(username: bobName)

        // D1: the first message to someone new is text only
        let bytes = Data((0..<90_000).map { UInt8(truncatingIfNeeded: $0 &* 17) })
        await alice.sendFile(data: bytes, filename: "notes.bin", mime: "application/octet-stream", to: contact(alice, bob))
        let file = try XCTUnwrap(alice.messages[bob.myHash]?.first { $0.kind == .file })
        XCTAssertEqual(file.status, .failed)
        XCTAssertEqual(alice.lastError, Sealed.firstContactRefusal(name: bobName))
        XCTAssertTrue(bob.messages[alice.myHash]?.isEmpty ?? true, "nothing was sent")

        // once a text is delivered bob's key is here, and the attachment goes by itself
        await alice.sendText("first, a text", to: contact(alice, bob))
        let arrived = await waitUntil(15) {
            bob.messages[alice.myHash]?.contains { $0.kind == .file } == true
        }
        XCTAssertTrue(arrived, "the attachment was retried when bob's delivery key arrived")
        let got = try XCTUnwrap(bob.messages[alice.myHash]?.first { $0.kind == .file })
        XCTAssertEqual(got.id, file.id, "under the sender's row id")
        XCTAssertEqual(bob.attachmentData(for: got), bytes, "byte for byte")
        let done = await waitUntil { alice.messages[bob.myHash]?.first { $0.id == file.id }?.status == .delivered }
        XCTAssertTrue(done)

        // and back: a voice note on the key lane
        let audio = Data((0..<40_000).map { UInt8(truncatingIfNeeded: $0 &* 5) })
        await bob.sendVoice(audio, duration: 3, to: contact(bob, alice))
        let heard = await waitUntil { alice.messages[bob.myHash]?.contains { $0.kind == .voice } == true }
        XCTAssertTrue(heard)
        let voice = try XCTUnwrap(alice.messages[bob.myHash]?.first { $0.kind == .voice })
        XCTAssertEqual(alice.voiceData(for: voice), audio)

        let after = try await totals()
        XCTAssertEqual(after.legacy - before.legacy, 0, "manifests and receipts all went sealed")
    }

    func testAnOldBuildAndANewOneTalkTheOldWayAndOneMessageAfterTheUpdateIsEnough() async throws {
        let (nora, _) = makeService("nora"), (olga, olgaName) = makeService("olga")
        olga.legacyWireOnly = true                       // a build from before sealed sender
        try await nora.register(username: "nora" + UUID().uuidString.prefix(8).lowercased())
        try await olga.register(username: olgaName)
        defer { nora.goOffline(); olga.goOffline() }
        guard nora.sealedStatus == .on else { throw XCTSkip("this relay does not offer sealed sender") }
        XCTAssertEqual(olga.sealedStatus, .notOffered)
        XCTAssertNil(olga.store.blob.deliveryKey, "an old build has no delivery key to offer")
        let before = try await totals()

        try await nora.addContact(username: olgaName)
        XCTAssertEqual(nora.peerKey(olga.myHash)?.sealed, false, "her bundle does not say sealed")
        await nora.sendText("to an old build", to: contact(nora, olga))
        let arrived = await waitUntil { self.texts(olga, with: nora, .incoming).first?.text == "to an old build" }
        XCTAssertTrue(arrived)
        await olga.sendText("from an old build", to: contact(olga, nora))
        let replied = await waitUntil { self.texts(nora, with: olga, .incoming).first?.text == "from an old build" }
        XCTAssertTrue(replied)
        _ = await waitUntil { self.texts(nora, with: olga, .outgoing).first?.status == .delivered }
        XCTAssertNil(olga.store.blob.peerKeys, "an old build keeps nothing about delivery keys")
        let mid = try await totals()
        XCTAssertEqual(mid.sealed - before.sealed, 0, "nothing sealed reached the old build")
        XCTAssertEqual(mid.firstContact - before.firstContact, 0)
        XCTAssertGreaterThanOrEqual(mid.legacy - before.legacy, 2)
        XCTAssertEqual(nora.frameCapture.frame(for: texts(nora, with: olga, .outgoing)[0].id)?.t, "send")

        // olga updates: the same account logs in with a delivery key
        olga.goOffline()
        olga.legacyWireOnly = false
        try await olga.goOnline()
        XCTAssertEqual(olga.sealedStatus, .on)
        // nora still believes olga cannot receive sealed: one more message the old way, carrying her key…
        await nora.sendText("after your update", to: contact(nora, olga))
        // …and olga's receipt for it comes back sealed, carrying hers
        let moved = await waitUntil {
            nora.peerKey(olga.myHash)?.dk != nil && nora.peerKey(olga.myHash)?.sealed == true
        }
        XCTAssertTrue(moved, "one legacy message after the update is enough")
        XCTAssertEqual(nora.sealedLane(for: olga.myHash), .key)
        XCTAssertEqual(olga.sealedLane(for: nora.myHash), .key)
        let sealedFrom = try await totals()
        await nora.sendText("sealed now", to: contact(nora, olga))
        let last = await waitUntil { self.texts(olga, with: nora, .incoming).last?.text == "sealed now" }
        XCTAssertTrue(last)
        _ = await waitUntil { self.texts(nora, with: olga, .outgoing).last?.status == .delivered }
        let after = try await totals()
        XCTAssertEqual(after.legacy - sealedFrom.legacy, 0, "from then on nothing names its sender")
    }

    func testAPeerWhoGoesBackToAnOldBuildIsReachedTheOldWay() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        try await introduce(alice, bob, bobName)
        let link = try XCTUnwrap(alice.anonLink)

        bob.goOffline()
        bob.legacyWireOnly = true                        // an old build takes the account over
        try await bob.goOnline()
        await alice.sendText("are you still there", to: contact(alice, bob))
        let arrived = await waitUntil { self.texts(bob, with: alice, .incoming).last?.text == "are you still there" }
        XCTAssertTrue(arrived, "the relay said `unsupported`; the message went again the old way")
        let row = try XCTUnwrap(texts(alice, with: bob, .outgoing).last)
        XCTAssertEqual(alice.peerKey(bob.myHash)?.sealed, false)
        XCTAssertNotNil(alice.peerKey(bob.myHash)?.dk, "his key is kept for when he is back")
        XCTAssertEqual(alice.frameCapture.frame(for: row.id)?.t, "send", "the details sheet shows the frame that got through")
        XCTAssertEqual(alice.frameCapture.frame(for: row.id)?.id, row.id)
        let retired = await waitUntil { link.ended }
        XCTAssertTrue(retired, "the connection the refusal came in on is not used again")
        let sent = await waitUntil { self.texts(alice, with: bob, .outgoing).last?.status != .pending }
        XCTAssertTrue(sent)
        XCTAssertNotEqual(texts(alice, with: bob, .outgoing).last?.status, .failed)
    }

    func testAChangedDeliveryKeyIsFoundAgainWithoutAnyoneDoingAnything() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        try await introduce(alice, bob, bobName)
        let oldKey = try XCTUnwrap(bob.store.blob.deliveryKey)

        // bob restores a backup from before sealed sender: a new delivery key, and nothing
        // remembered about anyone else's
        bob.goOffline()
        bob.store.update { $0.deliveryKey = nil; $0.peerKeys = nil }
        try await bob.goOnline()
        let newKey = try XCTUnwrap(bob.store.blob.deliveryKey)
        XCTAssertNotEqual(newKey, oldKey)

        await alice.sendText("after your restore", to: contact(alice, bob))
        // promptly: the lookup after `denied` must not wait on the loop that delivers its answer
        let arrived = await waitUntil(5) { self.texts(bob, with: alice, .incoming).last?.text == "after your restore" }
        XCTAssertTrue(arrived, "`denied`, then looked up again, then the first-contact lane")
        XCTAssertEqual(texts(bob, with: alice, .incoming).count, 2, "and it arrived once")
        // his receipt for it carries the new key, and no longer echoes hers…
        let aliceHasIt = await waitUntil {
            alice.peerKey(bob.myHash)?.dk == newKey
                && self.texts(alice, with: bob, .outgoing).last?.status == .delivered
        }
        XCTAssertTrue(aliceHasIt)
        XCTAssertEqual(alice.sealedLane(for: bob.myHash), .key)
        XCTAssertNil(alice.peerKey(bob.myHash)?.ack, "he no longer holds her key, and his receipt said so")
        // …so her next message carries her key again, and both are back on the key lane
        await alice.sendText("and again", to: contact(alice, bob))
        let both = await waitUntil { bob.peerKey(alice.myHash)?.dk == alice.store.blob.deliveryKey }
        XCTAssertTrue(both)
        XCTAssertEqual(bob.sealedLane(for: alice.myHash), .key)
        let done = await waitUntil { self.texts(alice, with: bob, .outgoing).last?.status == .delivered }
        XCTAssertTrue(done)
    }

    func testAPeerWhoStartsOverUnderTheSameNameIsFoundAgainAndNothingIsLost() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline() }
        try await introduce(alice, bob, bobName)
        let before = try await totals()

        // bob wipes his phone and registers the same name again: a new identity key and a new delivery key
        await bob.deregister()
        bob.goOffline()
        let (again, _) = makeService("bob")
        try await again.register(username: bobName)
        defer { again.goOffline() }
        XCTAssertEqual(again.myHash, bob.myHash, "the same account name")
        guard again.sealedStatus == .on else { throw XCTSkip("this relay does not offer sealed sender") }

        await alice.sendText("after you started over", to: contact(alice, bob))
        // `denied`, a lookup that finds a new identity key, a new session, then the first-contact lane
        let arrived = await waitUntil { (again.messages[alice.myHash] ?? []).contains { $0.text == "after you started over" } }
        XCTAssertTrue(arrived, "the message that met the refusal is the one that arrives: nothing is lost on the way")
        XCTAssertEqual((again.messages[alice.myHash] ?? []).filter { $0.kind == .text }.count, 1)
        let delivered = await waitUntil { self.texts(alice, with: bob, .outgoing).last?.status == .delivered }
        XCTAssertTrue(delivered)
        let rekeyed = await waitUntil { alice.peerKey(bob.myHash)?.dk == again.store.blob.deliveryKey }
        XCTAssertTrue(rekeyed, "his receipt brought the new delivery key")
        XCTAssertEqual(alice.sealedLane(for: bob.myHash), .key)
        // the change of identity is shown as it always was
        XCTAssertTrue((alice.messages[bob.myHash] ?? []).contains { $0.kind == .system && $0.text.contains("identity key changed") })
        XCTAssertEqual(contact(alice, bob).verified, false)

        let after = try await totals()
        XCTAssertEqual(after.legacy - before.legacy, 0, "and nothing named its sender while it was sorted out")
    }

    func testTimeInTheBackgroundOrANewNetworkRetiresThePrivateConnectionAndTheNextMessageOpensAnother() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        try await introduce(alice, bob, bobName)
        let before = try await totals()

        // the app leaves the foreground: nothing in flight, so the connection closes at once
        let first = try XCTUnwrap(alice.anonLink)
        alice.noteBackgrounded()
        XCTAssertTrue(first.ended)
        await alice.sendText("after a while away", to: contact(alice, bob))
        let arrived = await waitUntil { self.texts(bob, with: alice, .incoming).last?.text == "after a while away" }
        XCTAssertTrue(arrived)
        let second = try XCTUnwrap(alice.anonLink)
        XCTAssertFalse(first === second)
        XCTAssertEqual(alice.frameCapture.frame(for: texts(alice, with: bob, .outgoing).last!.id)?.t, "deliver")
        let done = await waitUntil { self.texts(alice, with: bob, .outgoing).last?.status == .delivered }
        XCTAssertTrue(done)

        // the phone changes network: the connection may have stayed on the old one
        alice.noteNetworkChanged()
        XCTAssertTrue(second.ended)
        let up = await waitUntil { alice.connState == .online && alice.sealedStatus == .on }
        XCTAssertTrue(up)
        await alice.sendText("on another network", to: contact(alice, bob))
        let again = await waitUntil { self.texts(bob, with: alice, .incoming).last?.text == "on another network" }
        XCTAssertTrue(again)
        XCTAssertEqual(alice.frameCapture.frame(for: texts(alice, with: bob, .outgoing).last!.id)?.t, "deliver")
        let acked = await waitUntil { self.texts(alice, with: bob, .outgoing).last?.status == .delivered }
        XCTAssertTrue(acked)

        let after = try await totals()
        XCTAssertEqual(after.legacy - before.legacy, 0, "neither sent anything the old way")
    }

    func testAMessageOrAnAttachmentToAnAccountThatWasDeletedSaysSoAsItAlwaysDid() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline() }
        try await introduce(alice, bob, bobName)
        await bob.deregister()
        bob.goOffline()
        func notices() -> Int {
            (alice.messages[bob.myHash] ?? []).filter { $0.kind == .system && $0.text.contains("deleted their account") }.count
        }

        let bytes = Data((0..<40_000).map { UInt8(truncatingIfNeeded: $0) })
        await alice.sendFile(data: bytes, filename: "late.bin", mime: "application/octet-stream", to: contact(alice, bob))
        let failed = await waitUntil { alice.messages[bob.myHash]?.first { $0.kind == .file }?.status == .failed }
        XCTAssertTrue(failed)
        XCTAssertEqual(notices(), 1, "the refusal of a sealed manifest leaves the same row a refused `send` does")
        await alice.sendText("are you there", to: contact(alice, bob))
        let textFailed = await waitUntil { self.texts(alice, with: bob, .outgoing).last?.status == .failed }
        XCTAssertTrue(textFailed)
        XCTAssertEqual(notices(), 1, "and it is said once")
    }

    func testWithNoPrivateConnectionMessagesGoTheOldWayAndTheStatusSaysSo() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        alice.anonRelayURLForTest = URL(string: "ws://127.0.0.1:9/ws")       // nothing listens there
        let before = try await totals()

        try await alice.addContact(username: bobName)
        XCTAssertEqual(alice.sealedStatus, .noPrivateConnection, "two attempts failed, and the status says so")
        XCTAssertTrue(alice.anonFailed)
        await alice.sendText("the old way then", to: contact(alice, bob))
        let arrived = await waitUntil { self.texts(bob, with: alice, .incoming).first?.text == "the old way then" }
        XCTAssertTrue(arrived, "nothing is lost: the message names its sender, as before")
        XCTAssertEqual(alice.frameCapture.frame(for: texts(alice, with: bob, .outgoing)[0].id)?.t, "send")
        let after = try await totals()
        XCTAssertGreaterThanOrEqual(after.legacy - before.legacy, 1)

        // the next login tries again
        alice.goOffline()
        alice.anonRelayURLForTest = nil
        try await alice.goOnline()
        XCTAssertEqual(alice.sealedStatus, .on)
    }

    func testAGroupOfThreeSealedAndMembersWhoNeverSpokeUseTheRosterKeys() async throws {
        let (alice, bob, _, bobName) = try await pair()
        let (carol, carolName) = makeService("carol")
        try await carol.register(username: carolName)
        defer { alice.goOffline(); bob.goOffline(); carol.goOffline() }
        try await introduce(alice, bob, bobName)
        try await introduce(alice, carol, carolName)
        XCTAssertNil(bob.peerKey(carol.myHash), "bob and carol have never spoken")
        let before = try await totals()

        let g = try await alice.createGroup(name: "sealed", members: alice.contacts)
        let created = await waitUntil {
            bob.group(id: g.id)?.members.count == 3 && carol.group(id: g.id)?.members.count == 3
        }
        XCTAssertTrue(created)
        XCTAssertEqual(bob.peerKey(carol.myHash)?.dk, carol.store.blob.deliveryKey, "the roster carried carol's key to bob")
        XCTAssertEqual(carol.peerKey(bob.myHash)?.dk, bob.store.blob.deliveryKey)

        // bob writes to the group: his copy for carol goes straight to the key lane
        await bob.sendText("hello group", toGroup: g.id)
        let got = await waitUntil {
            alice.messages[g.id]?.contains { $0.text == "hello group" } == true
                && carol.messages[g.id]?.contains { $0.text == "hello group" } == true
        }
        XCTAssertTrue(got)
        XCTAssertEqual(carol.messages[g.id]?.first { $0.text == "hello group" }?.sender, bob.myHash,
                       "attributed to the certified sender")
        // an image to the whole group
        let jpeg = Data((0..<70_000).map { UInt8(truncatingIfNeeded: $0 &* 13) })
        await alice.sendImage(jpeg: jpeg, width: 4, height: 4, toGroup: g.id)
        let seen = await waitUntil(15) {
            bob.messages[g.id]?.contains { $0.kind == .image } == true
                && carol.messages[g.id]?.contains { $0.kind == .image } == true
        }
        XCTAssertTrue(seen)
        let img = try XCTUnwrap(carol.messages[g.id]?.first { $0.kind == .image })
        XCTAssertEqual(carol.attachmentData(for: img), jpeg)
        _ = await waitUntil { alice.messages[g.id]?.first { $0.kind == .image }?.status != .pending }

        let after = try await totals()
        XCTAssertEqual(after.legacy - before.legacy, 0, "no copy of anything named its sender")
        XCTAssertEqual(after.firstContact - before.firstContact, 0, "and nobody needed the first-contact lane")
    }

    func testTheBotAnswersSealed() async throws {
        let (alice, aliceName) = makeService("botfan")
        try await alice.register(username: aliceName)
        defer { alice.goOffline() }
        guard alice.sealedStatus == .on else { throw XCTSkip("this relay does not offer sealed sender") }
        let bot = sha256Hex("pyrobot")
        do { try await alice.addContact(username: "pyrobot") } catch { throw XCTSkip("no pyrobot on this relay") }
        guard alice.peerKey(bot)?.sealed == true else { throw XCTSkip("this pyrobot has no SEALED_ROOT") }
        let before = try await totals()
        await alice.sendText("hello", to: alice.contacts.first { $0.hash == bot }!)
        let toured = await waitUntil(20) {
            (alice.messages[bot] ?? []).filter { $0.direction == .incoming && $0.kind == .text }.count >= 5
        }
        XCTAssertTrue(toured, "the whole tour arrives")
        XCTAssertEqual(alice.messages[bot]?.first { $0.direction == .outgoing }?.status, .read)
        XCTAssertNotNil(alice.peerKey(bot)?.dk)
        let after = try await totals()
        XCTAssertEqual(after.legacy - before.legacy, 0, "neither side named itself to the relay")
    }

    func testAnOldBuildStillGetsTheTourFromABotThatCanSeal() async throws {
        let (olga, olgaName) = makeService("oldfan")
        olga.legacyWireOnly = true
        try await olga.register(username: olgaName)
        defer { olga.goOffline() }
        let bot = sha256Hex("pyrobot")
        do { try await olga.addContact(username: "pyrobot") } catch { throw XCTSkip("no pyrobot on this relay") }
        let before = try await totals()
        await olga.sendText("hello", to: olga.contacts.first { $0.hash == bot }!)
        let toured = await waitUntil(20) {
            (olga.messages[bot] ?? []).filter { $0.direction == .incoming && $0.kind == .text }.count >= 5
        }
        XCTAssertTrue(toured, "the whole tour arrives, the old way")
        XCTAssertNil(olga.store.blob.peerKeys, "an old build keeps nothing about delivery keys")
        let after = try await totals()
        XCTAssertEqual((after.sealed - before.sealed) + (after.firstContact - before.firstContact), 0,
                       "nothing sealed was sent to it")
    }

    /// F6, on the relay with eight-second certificates: what waited in the queue is judged at
    /// the time the relay took it.
    func testATextAndAnAttachmentThatWaitedPastTheirSendersCertificateStillOpen() async throws {
        let (alice, aliceName) = makeService("late"), (bob, bobName) = makeService("lateb")
        for s in [alice, bob] { s.store.update { $0.settings.relayURL = "ws://127.0.0.1:7449/ws" } }
        do {
            try await alice.register(username: aliceName)
            try await bob.register(username: bobName)
        } catch {
            alice.goOffline(); bob.goOffline()
            throw XCTSkip("no short-lived-certificate relay on :7449")
        }
        defer { alice.goOffline(); bob.goOffline() }
        guard alice.sealedStatus == .on, let first = alice.sealedOwn else { throw XCTSkip("relay on :7449 has sealed sender off") }
        guard Double(first.material.cert.expiration) / 1000 - Date().timeIntervalSince1970 < 30 else {
            throw XCTSkip("relay on :7449 issues long-lived certificates")
        }
        try await introduce(alice, bob, bobName)
        bob.goOffline()
        let bytes = Data((0..<150_000).map { UInt8(truncatingIfNeeded: $0 &* 23) })
        await alice.sendText("written before it ran out", to: contact(alice, bob))
        await alice.sendFile(data: bytes, filename: "late.bin", mime: "application/octet-stream", to: contact(alice, bob))
        let queued = await waitUntil {
            (alice.messages[bob.myHash] ?? []).filter { $0.direction == .outgoing }.suffix(2).allSatisfy { $0.status == .sent }
        }
        XCTAssertTrue(queued)
        let sentFile = try XCTUnwrap(alice.messages[bob.myHash]?.last { $0.kind == .file })
        XCTAssertEqual(alice.frameCapture.frame(for: sentFile.id)?.t, "deliver")
        // every certificate alice held when she sealed those two has run out by now
        let sealedUnder = Double(try XCTUnwrap(alice.sealedOwn).material.cert.expiration) / 1000
        while Date().timeIntervalSince1970 < sealedUnder + 1.5 { try await Task.sleep(for: .milliseconds(200)) }
        try await bob.goOnline()
        let arrived = await waitUntil(15) {
            let got = bob.messages[alice.myHash] ?? []
            return got.contains { $0.text == "written before it ran out" } && got.contains { $0.kind == .file }
        }
        XCTAssertTrue(arrived, "judged at the moment the relay took them, not at the moment they are opened")
        let gotFile = try XCTUnwrap(bob.messages[alice.myHash]?.first { $0.kind == .file })
        XCTAssertEqual(bob.attachmentData(for: gotFile), bytes)
        XCTAssertNil(bob.lastError)
    }

    /// F7: what the wake push runs in the background. The queue drains, the receipt for what
    /// arrived leaves sealed inside the window, and nothing is left open afterwards.
    func testABackgroundWakeDrainsTheQueueAndItsReceiptLeavesSealed() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        try await introduce(alice, bob, bobName)
        bob.goOffline()                                 // in the background: no link
        let before = try await totals()
        await alice.sendText("while you were asleep", to: contact(alice, bob))
        let queued = await waitUntil { self.texts(alice, with: bob, .outgoing).last?.status == .sent }
        XCTAssertTrue(queued)

        let fresh = await bob.backgroundDrain()
        XCTAssertTrue(fresh, "the queue was drained inside the window")
        XCTAssertEqual(texts(bob, with: alice, .incoming).last?.text, "while you were asleep")
        XCTAssertEqual(bob.connState, .offline, "and the link is closed again")
        XCTAssertTrue(bob.anonLinks.isEmpty, "with no anonymous connection left behind")
        XCTAssertNil(bob.sealedOwn)
        // the delivered receipt went out inside the window, or it is still in the saved outbox
        let delivered = await waitUntil(3) { self.texts(alice, with: bob, .outgoing).last?.status == .delivered }
        let kept = bob.store.blob.receiptOutbox?.delivered[alice.myHash]?.isEmpty == false
        XCTAssertTrue(delivered || kept, "a receipt is sent or kept, never dropped")
        XCTAssertTrue(delivered, "on this machine it makes it inside the window")
        let after = try await totals()
        XCTAssertEqual(after.legacy - before.legacy, 0, "and it did not go the old way")
    }

    /// X4: each thing the app can do, once, between people who hold each other's keys.
    func testEveryFeatureOnceAndTheRelayIsNotToldWhoSentAnyOfIt() async throws {
        let (alice, bob, _, bobName) = try await pair()
        let (carol, carolName) = makeService("carol")
        try await carol.register(username: carolName)
        defer { alice.goOffline(); bob.goOffline(); carol.goOffline() }
        try await introduce(alice, bob, bobName)
        try await introduce(alice, carol, carolName)
        let before = try await totals()
        func rows(_ s: ChatService, _ thread: String) -> [ChatMessage] { s.messages[thread] ?? [] }
        func got(_ s: ChatService, _ from: ChatService, _ text: String) -> Bool { rows(s, from.myHash).contains { $0.text == text } }
        func bytes(_ n: Int, _ seed: Int) -> Data { Data((0..<n).map { UInt8(truncatingIfNeeded: $0 &* seed) }) }

        // a text, DELIVERED, READ
        await alice.sendText("a text", to: contact(alice, bob))
        var ok = await waitUntil { got(bob, alice, "a text") }
        XCTAssertTrue(ok)
        bob.noteRowVisible(messageID: rows(bob, alice.myHash).first { $0.text == "a text" }!.id, thread: alice.myHash)
        ok = await waitUntil { rows(alice, bob.myHash).first { $0.text == "a text" }?.status == .read }
        XCTAssertTrue(ok, "read receipt")
        // typing
        alice.noteComposerActivity(in: bob.myHash)
        ok = await waitUntil { bob.typingPeers[alice.myHash] != nil }
        XCTAssertTrue(ok, "typing")
        // an image, a file, a voice note
        let image = bytes(120_000, 3)
        await alice.sendImage(jpeg: image, width: 8, height: 8, to: contact(alice, bob))
        ok = await waitUntil(15) { rows(bob, alice.myHash).contains { $0.kind == .image } }
        XCTAssertTrue(ok, "image")
        XCTAssertEqual(bob.attachmentData(for: rows(bob, alice.myHash).first { $0.kind == .image }!), image)
        let file = bytes(90_000, 5)
        await bob.sendFile(data: file, filename: "plan.bin", mime: "application/octet-stream", to: contact(bob, alice))
        ok = await waitUntil(15) { rows(alice, bob.myHash).contains { $0.kind == .file } }
        XCTAssertTrue(ok, "file")
        XCTAssertEqual(alice.attachmentData(for: rows(alice, bob.myHash).first { $0.kind == .file }!), file)
        let voice = bytes(40_000, 7)
        await alice.sendVoice(voice, duration: 2.5, to: contact(alice, bob))
        ok = await waitUntil(15) { rows(bob, alice.myHash).contains { $0.kind == .voice } }
        XCTAssertTrue(ok, "voice note")
        XCTAssertEqual(bob.voiceData(for: rows(bob, alice.myHash).first { $0.kind == .voice }!), voice)
        // a disappearing message: its timer starts when it is read, on both phones
        alice.setBurnDefault(2, for: bob.myHash)
        await alice.sendText("gone two seconds after you read it", to: contact(alice, bob))
        ok = await waitUntil { got(bob, alice, "gone two seconds after you read it") }
        XCTAssertTrue(ok)
        bob.noteRowVisible(messageID: rows(bob, alice.myHash).first { $0.text == "gone two seconds after you read it" }!.id,
                           thread: alice.myHash)
        ok = await waitUntil(12) {
            !got(bob, alice, "gone two seconds after you read it") && !got(alice, bob, "gone two seconds after you read it")
        }
        XCTAssertTrue(ok, "burned on both")
        alice.setBurnDefault(0, for: bob.myHash)
        // delete for both
        await alice.sendText("to be deleted", to: contact(alice, bob))
        ok = await waitUntil { got(bob, alice, "to be deleted") }
        XCTAssertTrue(ok)
        let deleted = await alice.deleteForBoth(id: rows(alice, bob.myHash).first { $0.text == "to be deleted" }!.id, thread: bob.myHash)
        XCTAssertTrue(deleted)
        ok = await waitUntil { !got(bob, alice, "to be deleted") }
        XCTAssertTrue(ok, "deleted on the other phone too")
        // a screenshot alert
        await alice.reportCapture("screenshot", in: contact(alice, bob))
        ok = await waitUntil { rows(bob, alice.myHash).contains { $0.kind == .system && $0.text.contains("screenshot") } }
        XCTAssertTrue(ok, "screenshot alert")
        // written in a linked browser: the phone is what sends it
        await alice.sendText("from the desktop", to: contact(alice, bob), viaWeb: true)
        ok = await waitUntil { rows(bob, alice.myHash).contains { $0.text == "from the desktop" && $0.viaWeb == true } }
        XCTAssertTrue(ok, "desktop-link send")

        // a group: text, an image, a screenshot under burn_remove, and the creator's burn
        let g = try await alice.createGroup(name: "everything", members: alice.contacts, policy: .burnRemove)
        ok = await waitUntil { bob.group(id: g.id)?.members.count == 3 && carol.group(id: g.id)?.members.count == 3 }
        XCTAssertTrue(ok, "group created")
        await bob.sendText("to the group", toGroup: g.id)
        ok = await waitUntil { rows(alice, g.id).contains { $0.text == "to the group" } && rows(carol, g.id).contains { $0.text == "to the group" } }
        XCTAssertTrue(ok, "group text")
        let groupImage = bytes(100_000, 11)
        await alice.sendImage(jpeg: groupImage, width: 8, height: 8, toGroup: g.id)
        ok = await waitUntil(15) { rows(bob, g.id).contains { $0.kind == .image } && rows(carol, g.id).contains { $0.kind == .image } }
        XCTAssertTrue(ok, "group image")
        XCTAssertEqual(carol.attachmentData(for: rows(carol, g.id).first { $0.kind == .image }!), groupImage)
        await carol.reportGroupCapture(in: g.id)
        ok = await waitUntil { alice.group(id: g.id)?.isMember(carol.myHash) == false && bob.group(id: g.id)?.isMember(carol.myHash) == false }
        XCTAssertTrue(ok, "the screenshot policy removed her on both")
        try await alice.burnGroup(g.id)
        ok = await waitUntil { bob.group(id: g.id) == nil }
        XCTAssertTrue(ok, "group burn")

        try await Task.sleep(for: .milliseconds(1500))      // the last receipts leave
        let after = try await totals()
        XCTAssertEqual(after.legacy - before.legacy, 0, "not one of those named its sender to the relay")
        XCTAssertEqual(after.firstContact - before.firstContact, 0,
                       "and nobody needed the first-contact lane: the roster carried the keys")
    }

    /// X5: his copy of the conversation is older than hers, and he has no delivery key at all.
    func testARestoreFromABackupMadeBeforeSealedSenderHealsAsItDoesTodayAndEndsOnTheKeyLane() async throws {
        let (alice, aliceName) = makeService("alice"), (bob, bobName) = makeService("bob")
        alice.legacyWireOnly = true; bob.legacyWireOnly = true       // both start on builds from before sealed sender
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        defer { alice.goOffline(); bob.goOffline() }
        try await alice.addContact(username: bobName)
        func say(_ from: ChatService, _ to: ChatService, _ text: String, line: UInt = #line) async {
            await from.sendText(text, to: from.contacts.first { $0.hash == to.myHash }!)
            let ok = await waitUntil { (to.messages[from.myHash] ?? []).contains { $0.text == text } }
            XCTAssertTrue(ok, text, line: line)
        }
        await say(alice, bob, "before the backup")
        await say(bob, alice, "yes")
        let backup = bob.store.blob                                    // the backup: his store as it is now
        // the conversation goes on, so the backup falls behind it
        for i in 0..<2 {
            await say(alice, bob, "later \(i)")
            await say(bob, alice, "reply \(i)")
        }
        // both update
        for s in [alice, bob] {
            s.goOffline()
            s.legacyWireOnly = false
            try await s.goOnline()
        }
        guard alice.sealedStatus == .on, bob.sealedStatus == .on else { throw XCTSkip("this relay does not offer sealed sender") }
        await say(alice, bob, "after the update")
        let moved = await waitUntil { alice.sealedLane(for: bob.myHash) == .key && bob.sealedLane(for: alice.myHash) == .key }
        XCTAssertTrue(moved, "one message after the update, and both are on the key lane")

        // bob's phone is gone; he restores the backup on a new one
        bob.goOffline()
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pyro-sealed-restore-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "sealed-live-pass", at: dir.appendingPathComponent("store"))
        store.update { $0 = backup }
        let restored = ChatService(store: store)
        XCTAssertEqual(restored.myHash, bob.myHash)
        try await restored.goOnline()
        defer { restored.goOffline() }
        guard restored.sealedStatus == .on else { throw XCTSkip("this relay does not offer sealed sender") }
        XCTAssertNotEqual(try XCTUnwrap(restored.store.blob.deliveryKey), bob.store.blob.deliveryKey,
                          "the backup held no delivery key: a new one")
        let before = try await totals()

        // his copy of the conversation is older than hers: her next message cannot be read,
        // and both are told, as today
        await alice.sendText("are you there", to: contact(alice, bob))
        let told = await waitUntil(20) {
            (restored.messages[alice.myHash] ?? []).contains { $0.kind == .system && $0.text.contains("re-keyed") }
                && (alice.messages[bob.myHash] ?? []).contains { $0.kind == .system && $0.text.contains("re-keyed") }
        }
        XCTAssertTrue(told, "both are told a message was lost")
        // from here it works again, and after an exchange both are back on the key lane
        await say(alice, restored, "once more")
        await say(restored, alice, "got it")
        await say(alice, restored, "and again")
        let back = await waitUntil { alice.sealedLane(for: bob.myHash) == .key && restored.sealedLane(for: alice.myHash) == .key }
        XCTAssertTrue(back, "nobody did anything but write")
        try await Task.sleep(for: .seconds(1))
        let after = try await totals()
        XCTAssertEqual(after.legacy - before.legacy, 0, "and through all of it the relay was not told who was writing")
    }

    func testWhenNoPrivateConnectionCanBeHadWhatWasLostWithTheLastOneGoesTheOldWay() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        try await introduce(alice, bob, bobName)
        alice.anonRelayURLForTest = URL(string: "ws://127.0.0.1:9/ws")       // from now on none can be opened
        // the connection dies with this message on it: its verdict has not been read yet
        let link = try XCTUnwrap(alice.anonLink)
        await alice.sendText("lost with the connection", to: contact(alice, bob))
        alice.anonEnd(link)
        // the try the app makes by itself decides nothing; the next message settles it, even one
        // written while that try is still going on
        XCTAssertEqual(alice.sealedStatus, .on)
        await alice.sendText("the next one", to: contact(alice, bob))
        let said = await waitUntil { alice.sealedStatus == .noPrivateConnection }
        XCTAssertTrue(said, "two attempts failed, and the status says so")
        let both = await waitUntil {
            let got = bob.messages[alice.myHash] ?? []
            return got.filter { $0.text == "lost with the connection" }.count == 1 && got.filter { $0.text == "the next one" }.count == 1
        }
        XCTAssertTrue(both, "both arrive, once each: the lost one was sent again the old way")
        let acked = await waitUntil { self.texts(alice, with: bob, .outgoing).suffix(2).allSatisfy { $0.status == .delivered } }
        XCTAssertTrue(acked)
        XCTAssertEqual(alice.frameCapture.frame(for: texts(alice, with: bob, .outgoing).last!.id)?.t, "send")
    }

    func testAConnectionThatDiedIsCountedOnceAndTheAppOpensTwoInARowByItselfAndNoMore() async throws {
        let (alice, bob, _, bobName) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        try await introduce(alice, bob, bobName)
        // A message waiting for its verdict and an attachment going out notice the same death,
        // one after the other.
        let first = try XCTUnwrap(alice.anonLink)
        alice.anonEnd(first)             // nothing was on it, so this alone opens nothing
        alice.lostWithConnection()
        alice.lostWithConnection()
        let another = await waitUntil { alice.anonLink != nil && alice.anonLink !== first }
        XCTAssertTrue(another, "another is opened")
        XCTAssertEqual(alice.anonReopens, 1, "one, counted once")
        // it dies with work on it again: the second one the app opens by itself
        let second = try XCTUnwrap(alice.anonLink)
        alice.anonEnd(second)
        alice.lostWithConnection()
        let again = await waitUntil { alice.anonLink != nil && alice.anonLink !== second }
        XCTAssertTrue(again)
        XCTAssertEqual(alice.anonReopens, 2)
        // and a third time: now the work waits, or a path that kills every connection would be a loop
        alice.anonEnd(try XCTUnwrap(alice.anonLink))
        alice.lostWithConnection()
        try await Task.sleep(for: .milliseconds(1500))
        XCTAssertNil(alice.anonLink, "no third one")
        XCTAssertEqual(alice.sealedStatus, .on, "which is not the same as there being none to be had")
        // the next message opens one, and its verdict starts the count again
        await alice.sendText("after all that", to: contact(alice, bob))
        let acked = await waitUntil { self.texts(alice, with: bob, .outgoing).last?.status == .delivered }
        XCTAssertTrue(acked)
        XCTAssertEqual(alice.frameCapture.frame(for: texts(alice, with: bob, .outgoing).last!.id)?.t, "deliver")
        XCTAssertEqual(alice.anonReopens, 0)
    }

    /// §5.2 `intro_full`, against a relay that holds one first-contact message per person
    /// (`sealed.firstContactQueued=1`, on 127.0.0.1:7450). Skips when there is none.
    func testWhenAFirstContactAllowanceIsUsedUpTheMessageGoesTheOldWay() async throws {
        let (alice, aliceName) = makeService("full"), (bob, bobName) = makeService("fullb")
        for s in [alice, bob] { s.store.update { $0.settings.relayURL = "ws://127.0.0.1:7450/ws" } }
        do {
            try await alice.register(username: aliceName)
            try await bob.register(username: bobName)
        } catch {
            alice.goOffline(); bob.goOffline()
            throw XCTSkip("no small-allowance relay on :7450")
        }
        defer { alice.goOffline(); bob.goOffline() }
        guard alice.sealedStatus == .on else { throw XCTSkip("relay on :7450 has sealed sender off") }
        let before = try await totals(host: "127.0.0.1:7450")

        // bob is away while alice writes to him for the first time
        bob.goOffline()
        try await alice.addContact(username: bobName)
        await alice.sendText("one", to: contact(alice, bob))
        let first = await waitUntil { self.texts(alice, with: bob, .outgoing).first?.status == .sent }
        XCTAssertTrue(first, "the first message waits for him on the first-contact lane")
        let link = try XCTUnwrap(alice.anonLink)
        await alice.sendText("two", to: contact(alice, bob))
        let second = await waitUntil { self.texts(alice, with: bob, .outgoing).last?.status == .sent }
        XCTAssertTrue(second, "the relay said `intro_full`; the message went the old way instead of failing")
        let rows = texts(alice, with: bob, .outgoing)
        XCTAssertEqual(alice.frameCapture.frame(for: rows[0].id)?.t, "deliver")
        XCTAssertEqual(alice.frameCapture.frame(for: rows[1].id)?.t, "send", "and the details sheet says which path it took")
        let retired = await waitUntil { link.ended }
        XCTAssertTrue(retired, "the connection the refusal came in on is not used again")

        try await bob.goOnline()
        let both = await waitUntil { self.texts(bob, with: alice, .incoming).map(\.text).sorted() == ["one", "two"] }
        XCTAssertTrue(both, "nothing was lost")
        let after = try await totals(host: "127.0.0.1:7450")
        XCTAssertEqual(after.firstContact - before.firstContact, 1)
        XCTAssertEqual(after.legacy - before.legacy, 1)
    }

    /// C6, against a relay whose certificates last seconds (`sealed.certTtlSeconds=6` with
    /// `sealed.testShortCertTtl=true`, on 127.0.0.1:7449). Skips when there is none.
    func testACertificateIsRenewedInTimeAndNothingIsSealedWithOneThatRanOut() async throws {
        let (alice, aliceName) = makeService("cert"), (bob, bobName) = makeService("certb")
        for s in [alice, bob] { s.store.update { $0.settings.relayURL = "ws://127.0.0.1:7449/ws" } }
        do {
            try await alice.register(username: aliceName)
            try await bob.register(username: bobName)
        } catch {
            alice.goOffline(); bob.goOffline()
            throw XCTSkip("no short-lived-certificate relay on :7449")
        }
        defer { alice.goOffline(); bob.goOffline() }
        guard alice.sealedStatus == .on, let first = alice.sealedOwn else { throw XCTSkip("relay on :7449 has sealed sender off") }
        let life = Double(first.material.cert.expiration) / 1000 - Date().timeIntervalSince1970
        guard life < 30 else { throw XCTSkip("relay on :7449 issues long-lived certificates") }

        // renewed at half its life
        let renewed = await waitUntil(life) {
            (alice.sealedOwn?.material.cert.expiration ?? 0) > first.material.cert.expiration
        }
        XCTAssertTrue(renewed, "a new certificate arrived before the first one ended")
        try await introduce(alice, bob, bobName)

        // renewals stop getting through: when the certificate runs out, nothing is sealed with it
        alice.certRenewal?.cancel()
        let expired = await waitUntil(life + 2) { !alice.sealedUsable }
        XCTAssertTrue(expired)
        XCTAssertEqual(alice.sealedLane(for: bob.myHash), .legacy)
        await alice.sendText("with no certificate", to: contact(alice, bob))
        let arrived = await waitUntil { self.texts(bob, with: alice, .incoming).last?.text == "with no certificate" }
        XCTAssertTrue(arrived, "it goes the old way and still arrives")
        XCTAssertEqual(alice.frameCapture.frame(for: texts(alice, with: bob, .outgoing).last!.id)?.t, "send")

        // a renewal gets through: sealing resumes
        try await alice.relay?.send(Wire.Out(t: "cert"))
        let back = await waitUntil { alice.sealedUsable }
        XCTAssertTrue(back)
        XCTAssertEqual(alice.sealedLane(for: bob.myHash), .key)
        await alice.sendText("sealed again", to: contact(alice, bob))
        let last = await waitUntil { self.texts(bob, with: alice, .incoming).last?.text == "sealed again" }
        XCTAssertTrue(last)
        XCTAssertEqual(alice.frameCapture.frame(for: texts(alice, with: bob, .outgoing).last!.id)?.t, "deliver")
    }
}
