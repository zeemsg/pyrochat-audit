import XCTest
@testable import PyroChat

/// Encrypted backup / device migration. The migration test runs the FULL
/// journey against the live relay: chat → export → wipe "old phone" →
/// restore on "new phone" → same identity logs in and keeps chatting.
@MainActor
final class BackupTests: XCTestCase {

    private func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-bak-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
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

    func testBackupRoundTripAndWrongPassphrase() throws {
        let store = SecureStore(creatingWithPassword: "old-device-pass",
                                at: tempDir().appendingPathComponent("store"))
        let svc = ChatService(store: store)
        svc.updateNoteText(id: svc.createNote().id, text: "secret ops note")
        let attId = UUID().uuidString.lowercased()
        try store.writeAttachment(id: attId, data: Data("attachment bytes".utf8))
        store.update {
            $0.usernameDisplay = "zee"
            $0.usernameHash = sha256Hex("zee")
            $0.identityKeyPair = Data([1, 2, 3])
        }

        let backup = try BackupService.export(from: store, passphrase: "migrate-me-123")
        XCTAssertTrue(backup.prefix(8) == Data("PYROBAK1".utf8))
        // sealed: plaintext must not be visible in the file
        let raw = String(decoding: backup, as: UTF8.self)
        XCTAssertFalse(raw.contains("secret ops note"))
        XCTAssertFalse(raw.contains("zee"))

        // wrong passphrase must fail clean
        XCTAssertThrowsError(try BackupService.decode(backup, passphrase: "wrong"))

        // right passphrase restores everything
        let (blob, atts) = try BackupService.decode(backup, passphrase: "migrate-me-123")
        XCTAssertEqual(blob.usernameDisplay, "zee")
        XCTAssertEqual(blob.notes?.first?.text, "secret ops note")
        XCTAssertEqual(atts[attId], Data("attachment bytes".utf8))

        // restore onto a "new device" under a brand-new password
        let newURL = tempDir().appendingPathComponent("store")
        let restored = SecureStore(restoring: blob, attachments: atts,
                                   password: "brand-new-pass", at: newURL)
        XCTAssertEqual(restored.blob.usernameHash, sha256Hex("zee"))
        XCTAssertEqual(restored.readAttachment(id: attId), Data("attachment bytes".utf8))
        // and the new store unlocks with the NEW password only
        XCTAssertNoThrow(try SecureStore(unlockingWithPassword: "brand-new-pass", at: newURL))
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "old-device-pass", at: newURL))
    }

    func testFullDeviceMigrationKeepsIdentityAndContacts() async throws {
        // "old phone": alice registers and chats with bob
        let aliceURL = tempDir().appendingPathComponent("store")
        let aliceStore = SecureStore(creatingWithPassword: "old-phone-pass", at: aliceURL)
        let alice = ChatService(store: aliceStore)
        let aliceName = "mg" + UUID().uuidString.prefix(8).lowercased()

        let bobDir = tempDir()
        let bob = ChatService(store: SecureStore(creatingWithPassword: "bob-pass-123",
                                                 at: bobDir.appendingPathComponent("store")))
        let bobName = "mg" + UUID().uuidString.prefix(8).lowercased()

        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        await alice.sendText("before migration", to: alice.contacts.first!)
        var ok = await waitUntil { bob.messages[alice.myHash]?.isEmpty == false }
        XCTAssertTrue(ok)
        let identityBefore = alice.myHash

        // export, then the old phone dies
        let backup = try BackupService.export(from: aliceStore, passphrase: "crossing-over-99")
        alice.goOffline()
        alice.shutdown()

        // "new phone": restore under a new password
        let (blob, atts) = try BackupService.decode(backup, passphrase: "crossing-over-99")
        let newStore = SecureStore(restoring: blob, attachments: atts,
                                   password: "new-phone-pass",
                                   at: tempDir().appendingPathComponent("store"))
        let alice2 = ChatService(store: newStore)

        // same identity: the relay's challenge is answered by the RESTORED key
        XCTAssertEqual(alice2.myHash, identityBefore, "identity must survive migration")
        XCTAssertEqual(alice2.myUsername, aliceName)
        try await alice2.goOnline()
        XCTAssertEqual(alice2.connState, .online,
                       "restored auth key must pass the relay's challenge")

        // history + contacts came along
        XCTAssertEqual(alice2.contacts.first?.name, bobName)
        XCTAssertTrue(alice2.messages[bob.myHash]?.contains {
            $0.text == "before migration"
        } == true)

        // and the channel still works both ways from the new phone
        await alice2.sendText("hello from my new phone", to: alice2.contacts.first!)
        ok = await waitUntil(10) {
            bob.messages[identityBefore]?.contains { $0.text == "hello from my new phone" } == true
        }
        XCTAssertTrue(ok, "restored sessions must keep the channel alive")
        let bobContact = bob.contacts.first { $0.hash == identityBefore }!
        await bob.sendText("loud and clear", to: bobContact)
        ok = await waitUntil(10) {
            alice2.messages[bob.myHash]?.contains { $0.text == "loud and clear" } == true
        }
        XCTAssertTrue(ok)

        alice2.goOffline(); bob.goOffline()
    }

    /// `PyroChatApp.init` seeds the noise container on every first launch, so
    /// "is there a file at the store path" is true on every device. Restore
    /// used to read that as "an identity already exists — burn it first" and
    /// could never succeed anywhere.
    @MainActor
    func testRestoreSucceedsOnAFreshDeviceHoldingOnlyTheNoiseContainer() async throws {
        // the old phone: a registered identity, exported
        let old = SecureStore(creatingWithPassword: "old-phone-pass",
                              at: tempDir().appendingPathComponent("store"))
        let svc = ChatService(store: old)
        let name = "rs" + UUID().uuidString.prefix(8).lowercased()
        try await svc.register(username: name)
        let identity = svc.myHash
        let backup = try BackupService.export(from: old, passphrase: "crossing-over-99")
        svc.goOffline(); svc.shutdown()

        // the new phone: first launch seeded three slots of noise, nothing else
        try SecureStore.writeNoiseContainer(at: SecureStore.storeURL)
        XCTAssertTrue(SecureStore.storeExists)
        let m = AppModel()
        m.finishBoot()
        let failure = await m.submitRestore(backup: backup, passphrase: "crossing-over-99",
                                            newPassword: "new-phone-pass")
        XCTAssertNil(failure, "restore refused on a device holding only the noise container: \(failure ?? "")")
        XCTAssertEqual(m.stage, .main)
        XCTAssertEqual(m.service?.myHash, identity, "the restored identity is the exported one")
        XCTAssertEqual(m.service?.myUsername, name)
        XCTAssertNotNil(try? SecureStore(unlockingWithPassword: "new-phone-pass"),
                        "the container now opens under the new password")

        m.service?.goOffline(); m.service?.shutdown()
        try SecureStore.writeNoiseContainer(at: SecureStore.storeURL)   // leave the device wiped
    }
}
