import XCTest
import CryptoKit
@testable import PyroChat

/// Reverse fixture gate for the Android port: a store, its attachment sidecars and a PYROBAK1
/// backup WRITTEN BY ANDROID (`android/core-store`, `AndroidFixtureWriterTest`) are opened through
/// the real `SecureStore` / `BackupService` / `CryptoEngine` and checked against the same facts
/// `android/fixtures/ios-store/manifest.json` promises about the data. A NO-OP (XCTSkip) unless
/// the env var `PYRO_IMPORT_DIR` is set — from xcodebuild pass it as
/// `TEST_RUNNER_PYRO_IMPORT_DIR=/abs/path/android/fixtures/android-store`.
///
/// Like `FixtureExportTests`, the Keychain `kdf-salt` is pinned to the fixture's salt for the
/// duration of the test and whatever was there before is restored on exit. The fixture files
/// are copied to tmp first, so the store's debounced re-save (the session ratchet advances when
/// the probe is decrypted) never touches the checked-in files.
///
/// macOS TCC gotcha: the simulator's test host cannot read files under `~/Desktop`,
/// `~/Documents` or `~/Downloads` without a consent prompt, and under xcodebuild that prompt is
/// never answered — `open(2)` blocks for good and the run looks hung at "started". Copy
/// `android/fixtures/android-store` somewhere unprotected (e.g. `/private/tmp/...`) and point
/// `PYRO_IMPORT_DIR` at the copy.
final class FixtureImportTests: XCTestCase {

    func testImportAndroidStoreFixtures() throws {
        guard let inPath = ProcessInfo.processInfo.environment["PYRO_IMPORT_DIR"],
              !inPath.isEmpty else {
            throw XCTSkip("PYRO_IMPORT_DIR not set — Android fixture import is opt-in")
        }
        let fm = FileManager.default
        let src = URL(fileURLWithPath: inPath, isDirectory: true)
        let manifest = try loadJSON(src.appendingPathComponent("manifest.json"))
        let storeInfo = try XCTUnwrap(manifest["store"] as? [String: Any])
        let backupInfo = try XCTUnwrap(manifest["backup"] as? [String: Any])
        let password = try XCTUnwrap(storeInfo["password"] as? String)
        let saltHex = try XCTUnwrap(storeInfo["kdfSaltHex"] as? String)
        let passphrase = try XCTUnwrap(backupInfo["passphrase"] as? String)
        let files = try XCTUnwrap(manifest["files"] as? [String: [String: Any]])
        let attachmentIds = try XCTUnwrap(manifest["attachmentIds"] as? [String: String])

        // ---------------------------------------------------------------
        // Keychain: pin the KDF salt/pepper; restore the previous items on exit
        // ---------------------------------------------------------------
        let prevSalt = KeychainHelper.get("kdf-salt")
        let prevDuress = KeychainHelper.get("duress-verifier")
        defer {
            if let prevSalt { KeychainHelper.set(prevSalt, key: "kdf-salt") }
            else { KeychainHelper.delete("kdf-salt") }
            if let prevDuress { KeychainHelper.set(prevDuress, key: "duress-verifier") }
            else { KeychainHelper.delete("duress-verifier") }
        }
        let salt = hexData(saltHex)
        XCTAssertEqual(salt.count, 32)
        KeychainHelper.set(salt, key: "kdf-salt")
        XCTAssertEqual(KeychainHelper.get("kdf-salt"), salt,
                       "Keychain must be writable in the test host for a known salt")

        // scratch copy: the fixture must never be re-saved in place
        let work = fm.temporaryDirectory.appendingPathComponent("pyro-import-\(UUID().uuidString)")
        try fm.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: work) }
        let storeURL = work.appendingPathComponent("pyro.store")
        try fm.copyItem(at: src.appendingPathComponent("pyro.store"), to: storeURL)
        try fm.copyItem(at: src.appendingPathComponent("attachments"),
                        to: work.appendingPathComponent("attachments"))
        let storeBytes = try Data(contentsOf: storeURL)
        XCTAssertEqual(storeBytes.count, files["pyro.store"]?["bytes"] as? Int)
        XCTAssertEqual(sha256HexData(storeBytes), files["pyro.store"]?["sha256"] as? String)

        // ---------------------------------------------------------------
        // 1. The Android-written store unlocks through the real SecureStore
        // ---------------------------------------------------------------
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "wrong password", at: storeURL))
        let store = try SecureStore(unlockingWithPassword: password, at: storeURL)
        try assertFixtureContents(store.blob, manifest: manifest, label: "store", store: store)
        for (_, id) in attachmentIds {
            let plain = try XCTUnwrap(store.readAttachment(id: id), "sidecar \(id)")
            let info = try XCTUnwrap(files["attachments/\(id)"])
            XCTAssertEqual(plain.count, info["plaintextBytes"] as? Int, id)
            XCTAssertEqual(sha256HexData(plain), info["plaintextSha256"] as? String, id)
        }
        let seedBySlot: [String: (Int, UInt8)] = [
            "imageOutgoing": (4096, 0x11), "imageIncoming": (3000, 0x12),
            "fileOutgoing": (2500, 0x13), "fileIncoming": (700, 0x14),
        ]
        for (slot, id) in attachmentIds {
            let (n, seed) = try XCTUnwrap(seedBySlot[slot])
            XCTAssertEqual(store.readAttachment(id: id), pattern(n, seed: seed), slot)
        }

        // ---------------------------------------------------------------
        // 2. The session bytes Android re-encoded still drive libsignal:
        //    decrypt the message bob sent after the iOS store was written
        // ---------------------------------------------------------------
        let probe = try loadJSON(src.appendingPathComponent("session-probe.json"))
        let bobHash = try XCTUnwrap(probe["from"] as? String)
        let crypto = CryptoEngine(store: store)
        XCTAssertTrue(crypto.hasSession(with: bobHash))
        let payload = try crypto.decrypt(try XCTUnwrap(probe["body"] as? String), from: bobHash)
        let expected = try XCTUnwrap(probe["expectedPayload"] as? [String: Any])
        XCTAssertEqual(payload.kind, expected["kind"] as? String)
        XCTAssertEqual(payload.text, expected["text"] as? String)
        XCTAssertEqual(payload.senderName, expected["senderName"] as? String)
        XCTAssertEqual(payload.burnAfter, (expected["burnAfter"] as? NSNumber)?.doubleValue)
        store.flush()

        // ---------------------------------------------------------------
        // 3. The Android-written backup restores through the real BackupService
        // ---------------------------------------------------------------
        let bak = try Data(contentsOf: src.appendingPathComponent("backup.pyrobak"))
        XCTAssertEqual(bak.count, files["backup.pyrobak"]?["bytes"] as? Int)
        XCTAssertEqual(bak.prefix(8), Data("PYROBAK1".utf8))
        XCTAssertEqual(hexString(bak.subdata(in: 8..<40)), backupInfo["saltHex"] as? String)
        XCTAssertThrowsError(try BackupService.decode(bak, passphrase: "wrong-passphrase"))
        let (bakBlob, bakAtts) = try BackupService.decode(bak, passphrase: passphrase)
        try assertFixtureContents(bakBlob, manifest: manifest, label: "backup")
        XCTAssertEqual(bakAtts.count, attachmentIds.count)
        for (_, id) in attachmentIds {
            let bytes = try XCTUnwrap(bakAtts[id], "backup attachment \(id)")
            XCTAssertEqual(sha256HexData(bytes), files["attachments/\(id)"]?["plaintextSha256"] as? String)
        }

        // restore onto a "new device" under a brand-new password, then re-open it
        let newDir = work.appendingPathComponent("new", isDirectory: true)
        try fm.createDirectory(at: newDir, withIntermediateDirectories: true)
        let newURL = newDir.appendingPathComponent("pyro.store")
        let restored = SecureStore(restoring: bakBlob, attachments: bakAtts,
                                   password: "brand-new-pass", at: newURL)
        for (_, id) in attachmentIds {
            XCTAssertEqual(restored.readAttachment(id: id), bakAtts[id])
        }
        let reopened = try SecureStore(unlockingWithPassword: "brand-new-pass", at: newURL)
        try assertFixtureContents(reopened.blob, manifest: manifest, label: "restored", store: reopened)
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: password, at: newURL))
        XCTAssertTrue(CryptoEngine(store: reopened).hasSession(with: bobHash))
    }

    // MARK: the facts every copy of the blob must carry

    /// §1a: a voice note's audio, wherever this copy of the blob keeps it —
    /// inline (a backup decoded straight off disk) or in a sidecar (anything
    /// that has been through `SecureStore`).
    private func voiceBytes(_ row: ChatMessage, _ store: SecureStore?) -> Data? {
        if let inline = row.voiceData { return inline }
        guard let id = row.attachmentId else { return nil }
        return store?.readAttachment(id: id)
    }

    private func assertFixtureContents(_ blob: SecureStore.Blob, manifest: [String: Any],
                                       label: String, store: SecureStore? = nil) throws {
        let hashes = try XCTUnwrap(manifest["hashes"] as? [String: String])
        let counts = try XCTUnwrap(manifest["counts"] as? [String: Any])
        let identity = try XCTUnwrap(manifest["identity"] as? [String: Any])
        let peer = try XCTUnwrap(manifest["peer"] as? [String: Any])
        let attachmentIds = try XCTUnwrap(manifest["attachmentIds"] as? [String: String])
        let bob = try XCTUnwrap(hashes["bob_fixture"])
        let carol = try XCTUnwrap(hashes["carol_fixture"])
        let dave = try XCTUnwrap(hashes["dave_fixture"])
        let stranger = try XCTUnwrap(hashes["stranger_fixture"])
        let ghost = try XCTUnwrap(hashes["ghost_fixture"])

        // identity + libsignal material
        XCTAssertEqual(blob.usernameDisplay, identity["usernameDisplay"] as? String, label)
        XCTAssertEqual(blob.usernameHash, hashes["alice_fixture"], label)
        XCTAssertEqual(blob.usernameHash, sha256Hex("alice_fixture"))
        XCTAssertEqual(Int(blob.registrationId), identity["registrationId"] as? Int, label)
        XCTAssertEqual(blob.authPrivateKey.count, 32, label)
        XCTAssertEqual(blob.identityKeyPair.count, identity["identityKeyPairBytes"] as? Int, label)
        XCTAssertEqual(blob.preKeys.count, counts["preKeys"] as? Int, label)
        XCTAssertEqual(Set(blob.preKeys.keys.map(Int.init)), Set(1...32), label)
        XCTAssertEqual(Int(blob.nextPreKeyId), counts["nextPreKeyId"] as? Int, label)
        XCTAssertEqual(blob.signedPreKeys.count, 1, label)
        XCTAssertEqual(blob.kyberPreKeys.count, 1, label)
        XCTAssertEqual(blob.signedPreKeys[1]?.count, 146, label)
        XCTAssertEqual(blob.kyberPreKeys[1]?.count, 4821, label)
        XCTAssertEqual(Array(blob.sessions.keys), [bob], label)
        XCTAssertEqual(blob.sessions[bob]?.count, peer["sessionRecordBytes"] as? Int, label)
        XCTAssertEqual(blob.knownIdentities[bob]?.base64EncodedString(), peer["identityKeyB64"] as? String, label)
        XCTAssertEqual(blob.knownIdentities[bob]?.count, 33, label)
        XCTAssertEqual(blob.knownIdentities[bob]?.first, 0x05, label)

        // contacts / block list
        XCTAssertEqual(blob.contacts.map(\.name), ["bob_fixture", "carol_fixture", "dave_fixture", "anon-c11801ff"], label)
        let byName = Dictionary(uniqueKeysWithValues: blob.contacts.map { ($0.name, $0) })
        XCTAssertEqual(byName["bob_fixture"]?.hash, bob, label)
        XCTAssertEqual(byName["bob_fixture"]?.verified, true, label)
        XCTAssertNil(byName["bob_fixture"]?.burnSeconds, label)
        XCTAssertEqual(byName["bob_fixture"]?.addedAt, d(-1000), label)
        XCTAssertEqual(byName["carol_fixture"]?.hash, carol, label)
        XCTAssertEqual(byName["carol_fixture"]?.verified, false, label)
        XCTAssertEqual(byName["carol_fixture"]?.burnSeconds, 0, label)
        XCTAssertEqual(byName["dave_fixture"]?.burnSeconds, 86400, label)
        XCTAssertEqual(byName["anon-c11801ff"]?.hash, stranger, label)
        XCTAssertEqual(byName["anon-c11801ff"]?.addedAt, d(320), label)
        XCTAssertEqual(blob.blockedHashes, [carol], label)

        // messages
        XCTAssertEqual(blob.messages.count, counts["threads"] as? Int, label)
        XCTAssertEqual(blob.messages[ghost]?.isEmpty, true, label)
        let rows = blob.messages.values.flatMap { $0 }
        XCTAssertEqual(rows.count, counts["messages"] as? Int, label)
        XCTAssertEqual(Dictionary(grouping: rows, by: { $0.kind.rawValue }).mapValues(\.count),
                       counts["messagesByKind"] as? [String: Int], label)
        XCTAssertEqual(Dictionary(grouping: rows, by: { $0.direction.rawValue }).mapValues(\.count),
                       counts["messagesByDirection"] as? [String: Int], label)
        XCTAssertEqual(Dictionary(grouping: rows, by: { $0.status.rawValue }).mapValues(\.count),
                       counts["messagesByStatus"] as? [String: Int], label)
        XCTAssertEqual(rows.filter { $0.burnAfter != nil }.count, counts["messagesWithBurnAfter"] as? Int, label)
        XCTAssertEqual(rows.filter { $0.burnStartedAt != nil }.count, counts["messagesWithBurnStartedAt"] as? Int, label)
        XCTAssertEqual(rows.filter { $0.viaWeb == true }.count, counts["messagesViaWeb"] as? Int, label)
        XCTAssertEqual(rows.filter { $0.attachmentId != nil }.count, counts["messagesWithAttachment"] as? Int, label)
        // docs/DECOY-ACCOUNT.md §1a: on unlock the audio moves to a sidecar, so
        // the fact the manifest pins is "how many voice notes", not "how many
        // rows still carry the bytes inline".
        XCTAssertEqual(rows.filter { $0.kind == .voice }.count, counts["messagesWithVoiceData"] as? Int, label)

        let thread = try XCTUnwrap(blob.messages[bob], label)
        XCTAssertEqual(thread.count, 24, label)
        XCTAssertEqual(thread.map(\.id), (1...24).map(mid), label)
        XCTAssertEqual(thread.map(\.timestamp), (0..<24).map { d(Double($0) * 10) }, label)
        XCTAssertEqual(thread[0].direction, .incoming)
        XCTAssertEqual(thread[0].text, "hey alice — first contact from bob")
        XCTAssertEqual(thread[1].status, .read)
        XCTAssertEqual(thread[2].status, .sent)
        XCTAssertEqual(thread[3].status, .pending)
        XCTAssertEqual(thread[4].status, .failed)
        XCTAssertNil(thread[0].viaWeb, "viaWeb is written as true or omitted — never false")
        XCTAssertEqual(thread[6].viaWeb, true)
        XCTAssertEqual(thread[7].viaWeb, true)
        XCTAssertEqual(thread[8].burnAfter, 60)
        XCTAssertNil(thread[8].burnStartedAt)
        XCTAssertEqual(thread[9].burnAfter, 3600)
        XCTAssertEqual(thread[9].burnStartedAt, d(90.5), "sub-second date survives the Android codec")
        XCTAssertEqual(thread[9].burnDeadline, d(3690.5))
        XCTAssertEqual(thread[10].burnStartedAt, d(101))
        XCTAssertEqual(thread[11].kind, .voice)
        XCTAssertEqual(thread[11].text, "[voice 3s]")
        XCTAssertEqual(thread[11].voiceDuration, 3.4)
        XCTAssertEqual(voiceBytes(thread[11], store), pattern(1536, seed: 0x21))
        XCTAssertEqual(voiceBytes(thread[12], store), pattern(2048, seed: 0x22))
        XCTAssertEqual(thread[12].voiceDuration, 7)
        XCTAssertEqual(thread[12].burnAfter, 5)
        XCTAssertEqual(thread[13].kind, .image)
        XCTAssertEqual(thread[13].text, "[IMG]")
        XCTAssertEqual(thread[13].attachmentId, attachmentIds["imageOutgoing"])
        XCTAssertEqual(thread[13].fileMime, "image/jpeg")
        XCTAssertEqual(thread[13].fileBytes, 4096)
        XCTAssertEqual(thread[13].imageWidth, 640)
        XCTAssertEqual(thread[13].imageHeight, 480)
        XCTAssertNil(thread[13].fileName)
        XCTAssertEqual(thread[14].attachmentId, attachmentIds["imageIncoming"])
        XCTAssertEqual(thread[14].status, .read)
        XCTAssertEqual(thread[14].burnStartedAt, d(141))
        XCTAssertEqual(thread[14].imageWidth, 1024)
        XCTAssertEqual(thread[14].imageHeight, 768)
        XCTAssertEqual(thread[15].kind, .file)
        XCTAssertEqual(thread[15].text, "[FILE] report.pdf")
        XCTAssertEqual(thread[15].attachmentId, attachmentIds["fileOutgoing"])
        XCTAssertEqual(thread[15].fileName, "report.pdf")
        XCTAssertEqual(thread[15].fileMime, "application/pdf")
        XCTAssertEqual(thread[15].fileBytes, 2500)
        XCTAssertEqual(thread[15].status, .sent)
        XCTAssertEqual(thread[16].attachmentId, attachmentIds["fileIncoming"])
        XCTAssertEqual(thread[16].fileName, "notes.txt")
        XCTAssertEqual(thread[16].fileMime, "text/plain")
        XCTAssertEqual(thread[16].fileBytes, 700)
        for n in 17...22 {
            XCTAssertEqual(thread[n].kind, .system, "\(label) row \(n + 1)")
            XCTAssertEqual(thread[n].status, .delivered)
        }
        XCTAssertEqual(thread[17].text, "\u{26A0} peer took a screenshot")
        XCTAssertEqual(thread[21].text, "\u{26A0} <bob_fixture>'s identity key changed — re-verify before trusting")
        XCTAssertEqual(thread[23].text,
                       "escapes: café 🔥 — \"quoted\" back\\slash /slash/ tab\there\nnewline <b>&amp;</b> \u{01}ctrl \u{7F}del",
                       "every escape survives Android's encoder")
        XCTAssertEqual(blob.messages[dave]?.map(\.text), ["\u{1F525} thread burned by peer"], label)
        XCTAssertEqual(blob.messages[dave]?.first?.kind, .system, label)
        XCTAssertEqual(blob.messages[carol]?.map(\.text), ["message received before carol was blocked"], label)
        XCTAssertEqual(blob.messages[stranger]?.first?.timestamp, d(320), label)

        // settings: every field non-default
        let s = blob.settings
        XCTAssertEqual(s.theme, "amber", label)
        XCTAssertFalse(s.scanlines)
        XCTAssertTrue(s.pushEnabled)
        XCTAssertEqual(s.defaultBurnSeconds, 3600)
        XCTAssertEqual(s.threadAutoBurnDays, 7)
        XCTAssertTrue(s.burnOnScreenshot)
        XCTAssertEqual(s.sendReadReceipts, true)
        XCTAssertEqual(s.soundsEnabled, false)
        XCTAssertEqual(s.sendTyping, false)
        XCTAssertEqual(s.blockScreenshots, false)
        XCTAssertEqual(s.socksProxy, "127.0.0.1:9050")
        XCTAssertEqual(s.wipeAfterDays, 30)
        XCTAssertEqual(s.autoLockMinutes, 15)
        XCTAssertEqual(s.relayURL, "wss://relay.example.test/ws")
        XCTAssertEqual(s.billingURL, "https://billing.example.test")

        // notes / drafts / tokens / receipt outbox
        let notes = try XCTUnwrap(blob.notes, label)
        XCTAssertEqual(notes.count, counts["notes"] as? Int, label)
        XCTAssertEqual(notes.filter(\.isPinned).count, counts["notesPinned"] as? Int, label)
        XCTAssertEqual(notes[0].pinned, true)
        XCTAssertEqual(notes[0].text, "ops checklist\n\n- rotate keys\n- verify bob")
        XCTAssertEqual(notes[0].createdAt, d(-500))
        XCTAssertEqual(notes[0].modifiedAt, d(-400))
        XCTAssertEqual(notes[1].pinned, false)
        XCTAssertNil(notes[2].pinned)
        XCTAssertEqual(notes[2].text, "  \n\nleading blank lines then the title")
        XCTAssertEqual(notes[2].modifiedAt, d(-299.25))
        XCTAssertEqual(blob.drafts, [bob: "unsent draft for bob 🔥", dave: "draft for dave"], label)
        XCTAssertEqual(blob.accessTokens.map(\.epochDay), [20350, 20351], label)
        for token in blob.accessTokens {
            XCTAssertEqual(token.token.count, 40)
            XCTAssertEqual(token.signature.count, 256)
        }
        XCTAssertEqual(blob.accessTokens.first?.signature, pattern(256, seed: 0x32))
        let outbox = try XCTUnwrap(blob.receiptOutbox, label)
        XCTAssertEqual(outbox.delivered, [bob: [mid(1)]], label)
        XCTAssertEqual(outbox.read, [bob: [mid(6), mid(10)]], label)
        XCTAssertEqual(outbox.burnStart, [dave: [mid(99)]], label)
    }

    // MARK: helpers (same conventions as FixtureExportTests)

    private static let referenceBase: Double = 780_000_000

    private func d(_ off: Double) -> Date { Date(timeIntervalSinceReferenceDate: Self.referenceBase + off) }
    private func mid(_ n: Int) -> String { String(format: "6d000000-0000-4000-8000-%012d", n) }

    /// Deterministic LCG byte pattern (manifest.fixedValues.attachmentPlaintext).
    private func pattern(_ n: Int, seed: UInt8) -> Data {
        var x: UInt32 = UInt32(seed) | 0x9E37_0000
        var d = Data(count: n)
        for i in 0..<n {
            x = x &* 1_664_525 &+ 1_013_904_223
            d[i] = UInt8(truncatingIfNeeded: x >> 24)
        }
        return d
    }

    private func loadJSON(_ url: URL) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
                      url.lastPathComponent)
    }

    private func hexData(_ hex: String) -> Data {
        var d = Data()
        var it = hex.makeIterator()
        while let a = it.next(), let b = it.next() {
            d.append(UInt8(String([a, b]), radix: 16)!)
        }
        return d
    }

    private func hexString(_ d: Data) -> String {
        d.map { String(format: "%02x", $0) }.joined()
    }

    private func sha256HexData(_ d: Data) -> String {
        SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined()
    }
}
