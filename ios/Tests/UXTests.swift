import XCTest
@testable import PyroChat

/// v0.3 UX tier: drafts, contact management, single-message delete, typing.
@MainActor
final class UXTests: XCTestCase {

    private func makeService() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-ux-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "ux-test-pass-123",
                                at: dir.appendingPathComponent("store"))
        let username = "ux" + UUID().uuidString.prefix(8).lowercased()
        return (ChatService(store: store), username)
    }

    private func waitUntil(_ timeout: TimeInterval = 8,
                           _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return cond()
    }

    func testDraftsPersistAndClear() {
        let (svc, _) = makeService()
        let t = String(repeating: "a", count: 64)
        svc.setDraft("half-typed thought", for: t)
        XCTAssertEqual(svc.draft(for: t), "half-typed thought")
        XCTAssertEqual(svc.store.blob.drafts?[t], "half-typed thought")
        svc.setDraft("   ", for: t)   // whitespace-only clears
        XCTAssertEqual(svc.draft(for: t), "")
        XCTAssertNil(svc.store.blob.drafts?[t])
    }

    func testRenameAndDeleteContact() throws {
        let (svc, _) = makeService()
        let hash = String(repeating: "b", count: 64)
        svc.contacts.append(Contact(hash: hash, name: "oldname", addedAt: Date()))
        svc.messages[hash] = [ChatMessage(id: "m1", direction: .incoming, kind: .text,
                                          text: "hi", timestamp: Date(), status: .read,
                                          burnAfter: nil, burnStartedAt: nil)]
        svc.setDraft("wip", for: hash)

        svc.renameContact(hash, to: "  newname  ")
        XCTAssertEqual(svc.contacts.first?.name, "newname")
        svc.renameContact(hash, to: "   ")   // blank refused
        XCTAssertEqual(svc.contacts.first?.name, "newname")

        // delete: contact + messages + attachment file + draft all go
        let attId = UUID().uuidString.lowercased()
        try svc.store.writeAttachment(id: attId, data: Data("bytes".utf8))
        svc.messages[hash]?.append(ChatMessage(
            id: "m2", direction: .incoming, kind: .file, text: "[FILE] x",
            timestamp: Date(), status: .read, burnAfter: nil, burnStartedAt: nil,
            attachmentId: attId, fileName: "x", fileMime: "text/plain", fileBytes: 5))
        svc.deleteContact(hash)
        XCTAssertTrue(svc.contacts.isEmpty)
        XCTAssertNil(svc.messages[hash])
        XCTAssertNil(svc.store.readAttachment(id: attId), "attachment purged")
        XCTAssertEqual(svc.draft(for: hash), "", "draft purged")
    }

    func testDeleteSingleMessagePurgesItsAttachment() throws {
        let (svc, _) = makeService()
        let t = String(repeating: "c", count: 64)
        let attId = UUID().uuidString.lowercased()
        try svc.store.writeAttachment(id: attId, data: Data("img".utf8))
        svc.messages[t] = [
            ChatMessage(id: "keep", direction: .incoming, kind: .text, text: "stay",
                        timestamp: Date(), status: .read, burnAfter: nil, burnStartedAt: nil),
            ChatMessage(id: "kill", direction: .incoming, kind: .image, text: "[IMG]",
                        timestamp: Date(), status: .read, burnAfter: nil, burnStartedAt: nil,
                        attachmentId: attId, fileMime: "image/jpeg", fileBytes: 3),
        ]
        svc.deleteMessage(id: "kill", thread: t)
        XCTAssertEqual(svc.messages[t]?.map(\.id), ["keep"])
        XCTAssertNil(svc.store.readAttachment(id: attId))
    }

    func testTypingIndicatorReachesPeerAndExpires() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await bob.addContact(username: aliceName)
        // alice needs the contact so the typing control passes the
        // stranger guard; bob established the session
        try await alice.addContact(username: bobName)
        await bob.sendText("open the channel", to: bob.contacts.first!)
        _ = await waitUntil { alice.messages[bob.myHash]?.isEmpty == false }
        // With sealed sender a typing signal is never sent to someone who has not
        // answered yet (docs/SEALED-SENDER.md §5.2: it would spend their first-contact
        // allowance). DELIVERED means alice's phone answered, and that is when typing
        // can start; without sealed sender the wait changes nothing.
        _ = await waitUntil { bob.messages[alice.myHash]?.first?.status == .delivered }

        bob.noteComposerActivity(in: alice.myHash)
        let shown = await waitUntil { alice.typingPeers[bob.myHash] != nil }
        XCTAssertTrue(shown, "typing signal must reach the peer")

        let expired = await waitUntil(10) { alice.typingPeers[bob.myHash] == nil }
        XCTAssertTrue(expired, "typing row must expire after ~6s of silence")

        alice.goOffline(); bob.goOffline()
    }

    func testDraftsDecodeSafety() throws {
        let data = try JSONEncoder().encode(SecureStore.Blob())
        var obj = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        obj.removeValue(forKey: "drafts")
        let blob = try JSONDecoder().decode(SecureStore.Blob.self,
                                            from: JSONSerialization.data(withJSONObject: obj))
        XCTAssertNil(blob.drafts)
        // settings without sendTyping ⇒ typing defaults ON
        let s = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertTrue(s.typingOn)
    }
}

extension UXTests {
    /// First contact must show the sender's real username, not anon-…
    func testFirstContactShowsRealUsername() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)

        await alice.sendText("hi, it's me", to: alice.contacts.first!)
        let ok = await waitUntil { bob.messages[alice.myHash]?.isEmpty == false }
        XCTAssertTrue(ok)
        let contact = bob.contacts.first { $0.hash == alice.myHash }
        XCTAssertEqual(contact?.name, aliceName,
                       "receiver must see the sender's chosen username")
        XCTAssertFalse(contact?.name.hasPrefix("anon-") ?? true)
        alice.goOffline(); bob.goOffline()
    }

    func testDisplayNameSanitization() {
        XCTAssertEqual(ChatService.sanitizeDisplayName("ghost"), "ghost")
        XCTAssertEqual(ChatService.sanitizeDisplayName("<gh ost>💀"), "ghost")
        XCTAssertNil(ChatService.sanitizeDisplayName("ab"), "too short falls back to anon")
        XCTAssertNil(ChatService.sanitizeDisplayName(nil))
        XCTAssertEqual(ChatService.sanitizeDisplayName(String(repeating: "x", count: 99))?.count, 24)
    }

    func testChannelListIsNewestActivityFirst() {
        let old = Contact(hash: "a", name: "old", addedAt: Date(timeIntervalSince1970: 1_000))
        let mid = Contact(hash: "b", name: "mid", addedAt: Date(timeIntervalSince1970: 2_000))
        let new = Contact(hash: "c", name: "new", addedAt: Date(timeIntervalSince1970: 3_000))
        let msg = ChatMessage(id: "m1", direction: .incoming, kind: .text, text: "hi",
                              timestamp: Date(timeIntervalSince1970: 9_000), status: .delivered,
                              burnAfter: nil, burnStartedAt: nil)
        // "old" just received a message: it jumps to the top; the others rank by when they were added
        let ordered = HomeView.orderedByActivity([old, mid, new], messages: ["a": [msg]])
        XCTAssertEqual(ordered.map(\.name), ["old", "new", "mid"])
    }
}
