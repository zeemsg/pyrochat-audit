import XCTest
@testable import PyroChat

/// Apple guideline 1.2: block + report abuse. Live relay on 127.0.0.1:7444.
@MainActor
final class SafetyTests: XCTestCase {

    private func makeService() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-safety-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "safety-pass-123",
                                at: dir.appendingPathComponent("store"))
        let username = "sf" + UUID().uuidString.prefix(8).lowercased()
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

    func testBlockedSenderIsDroppedAndUnblockRecovers() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await bob.addContact(username: aliceName)

        // establish the channel first
        await bob.sendText("hello before block", to: bob.contacts.first!)
        let ok1 = await waitUntil { alice.messages[bob.myHash]?.count == 1 }
        XCTAssertTrue(ok1)

        // alice blocks bob — everything he sends now vanishes silently
        alice.blockContact(bob.myHash)
        await bob.sendText("you should never see this", to: bob.contacts.first!)
        await bob.sendText("nor this", to: bob.contacts.first!)
        try? await Task.sleep(for: .seconds(2))
        XCTAssertEqual(alice.messages[bob.myHash]?.count, 1,
                       "blocked sender's messages must be dropped")
        XCTAssertEqual(alice.totalUnread, 0, "blocked threads never badge")

        // unblock: NEW messages flow again (ratchet skipped the dropped ones)
        alice.unblockContact(bob.myHash)
        await bob.sendText("back in the room", to: bob.contacts.first!)
        let ok2 = await waitUntil {
            alice.messages[bob.myHash]?.contains { $0.text == "back in the room" } == true
        }
        XCTAssertTrue(ok2, "post-unblock messages must decrypt (ratchet fast-forwards)")

        alice.goOffline(); bob.goOffline()
    }

    func testReportAndBlockBurnsThreadAndBlocks() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await bob.addContact(username: aliceName)

        await bob.sendText("offensive content", to: bob.contacts.first!)
        _ = await waitUntil { alice.messages[bob.myHash]?.isEmpty == false }

        let bobContact = Contact(hash: bob.myHash, name: "reported", addedAt: Date())
        await alice.reportAndBlock(bobContact)

        XCTAssertTrue(alice.isBlocked(bob.myHash))
        XCTAssertTrue(alice.messages[bob.myHash]?.isEmpty ?? true,
                      "reporting burns the local thread")

        // blocked persists across store reload
        alice.store.flush()
        XCTAssertTrue(alice.store.blob.blockedHashes?.contains(bob.myHash) == true)

        alice.goOffline(); bob.goOffline()
    }

    func testBlockedHashesDecodeSafety() throws {
        let data = try JSONEncoder().encode(SecureStore.Blob())
        var obj = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        obj.removeValue(forKey: "blockedHashes")
        let legacy = try JSONSerialization.data(withJSONObject: obj)
        let blob = try JSONDecoder().decode(SecureStore.Blob.self, from: legacy)
        XCTAssertNil(blob.blockedHashes)
    }
}
