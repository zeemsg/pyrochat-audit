import XCTest
import CryptoKit
@testable import PyroChat

/// Ground-truth fixture exporter for the Android port
/// (`android/fixtures/ios-store/`). A NO-OP (XCTSkip) unless the env var
/// `PYRO_EXPORT_DIR` is set. From xcodebuild it must be an ENVIRONMENT
/// VARIABLE OF THE XCODEBUILD PROCESS, not an argument after it:
///
///     TEST_RUNNER_PYRO_EXPORT_DIR=/abs/path xcodebuild test …   # runs
///     xcodebuild test … TEST_RUNNER_PYRO_EXPORT_DIR=/abs/path   # silently skips
///
/// Written the second way the test skips without saying why, which reads
/// exactly like a pass.
///
/// Everything is produced through the real production APIs — `SecureStore`
/// (store file + attachment sidecars), `CryptoEngine`/`SignalStore` (a real
/// PQXDH session between two in-process identities), `BackupService`
/// (PYROBAK1) and `SecureStore.setDuressPin` — nothing is hand-encrypted.
/// App data uses FIXED ids/dates/strings so it is reproducible; libsignal
/// keys, the AES-GCM nonces and the backup salt are random per run (listed
/// under `randomPerRun` in manifest.json). Android tests must therefore
/// DECRYPT and compare semantically, never compare ciphertext bytes.
///
/// The KDF salt normally lives in the Keychain (`kdf-salt`); the test pins it
/// to a known value through the existing internal `KeychainHelper` API and
/// restores whatever was there before it ran.
final class FixtureExportTests: XCTestCase {

    static let password = "correct horse battery staple"
    static let backupPassphrase = "backup-pass-1234"
    static let duressPin = "2580"
    static let saltHex = "00112233445566778899aabbccddeeff102132435465768798a9bacbdcedfe0f"
    static let referenceBase: Double = 780_000_000   // seconds since 2001-01-01 (Swift Date encoding)

    func testExportIOSStoreFixtures() throws {
        guard let outPath = ProcessInfo.processInfo.environment["PYRO_EXPORT_DIR"],
              !outPath.isEmpty else {
            throw XCTSkip("PYRO_EXPORT_DIR not set — fixture export is opt-in")
        }
        let fm = FileManager.default
        let out = URL(fileURLWithPath: outPath, isDirectory: true)
        try fm.createDirectory(at: out, withIntermediateDirectories: true)

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
        let salt = hexData(Self.saltHex)
        XCTAssertEqual(salt.count, 32)
        KeychainHelper.set(salt, key: "kdf-salt")
        XCTAssertEqual(KeychainHelper.get("kdf-salt"), salt,
                       "Keychain must be writable in the test host for a known salt")

        // ---------------------------------------------------------------
        // Two real identities + a real PQXDH session (alice = the fixture)
        // ---------------------------------------------------------------
        let work = fm.temporaryDirectory.appendingPathComponent("pyro-fixture-\(UUID().uuidString)")
        let aliceDir = work.appendingPathComponent("alice", isDirectory: true)
        let bobDir = work.appendingPathComponent("bob", isDirectory: true)
        try fm.createDirectory(at: aliceDir, withIntermediateDirectories: true)
        try fm.createDirectory(at: bobDir, withIntermediateDirectories: true)
        let aliceURL = aliceDir.appendingPathComponent("pyro.store")
        let bobURL = bobDir.appendingPathComponent("pyro.store")

        let alice = SecureStore(creatingWithPassword: Self.password, at: aliceURL)
        let aliceCrypto = CryptoEngine(store: alice)
        let aliceReg = try aliceCrypto.generateIdentity(username: "alice_fixture")
        let bob = SecureStore(creatingWithPassword: "bob-device-pass", at: bobURL)
        let bobCrypto = CryptoEngine(store: bob)
        let bobReg = try bobCrypto.generateIdentity(username: "bob_fixture")
        let aliceHash = alice.blob.usernameHash
        let bobHash = bob.blob.usernameHash
        XCTAssertEqual(aliceHash, sha256Hex("alice_fixture"))

        // bob's relay prekey_bundle frame, exactly as ChatService would receive it
        let otk = bobReg.oneTimePreKeys[0]
        let spkObj: [String: Any] = ["id": bobReg.signedPreKey.id, "pub": bobReg.signedPreKey.pub,
                                     "sig": bobReg.signedPreKey.sig ?? ""]
        let kyberObj: [String: Any] = ["id": bobReg.kyberPreKey.id, "pub": bobReg.kyberPreKey.pub,
                                       "sig": bobReg.kyberPreKey.sig ?? ""]
        let otkObj: [String: Any] = ["id": otk.id, "pub": otk.pub]
        let bundleObj: [String: Any] = [
            "t": "prekey_bundle", "user": bobHash,
            "reg_id": bobReg.regId,
            "identity_key": bobReg.identityKey.base64EncodedString(),
            "signed_prekey": spkObj,
            "kyber_prekey": kyberObj,
            "one_time_prekey": otkObj,
        ]
        let bundle = try JSONDecoder().decode(
            Wire.In.self, from: JSONSerialization.data(withJSONObject: bundleObj))
        try aliceCrypto.processBundle(bundle, for: bobHash)

        // alice -> bob (PreKeySignalMessage), bob -> alice (SignalMessage): both
        // ratchets turn, so the persisted SessionRecord has sending AND
        // receiving chains.
        let b1 = try aliceCrypto.encrypt(
            Wire.Payload(kind: "text", text: "hello bob", senderName: "alice_fixture"), for: bobHash)
        XCTAssertEqual(try bobCrypto.decrypt(b1, from: aliceHash).text, "hello bob")
        let b2 = try bobCrypto.encrypt(
            Wire.Payload(kind: "text", text: "hello alice", senderName: "bob_fixture"), for: aliceHash)
        XCTAssertEqual(try aliceCrypto.decrypt(b2, from: bobHash).text, "hello alice")
        XCTAssertTrue(aliceCrypto.hasSession(with: bobHash))
        XCTAssertEqual(alice.blob.knownIdentities[bobHash], bobReg.identityKey,
                       "knownIdentities holds IdentityKey.serialize() (33 B, 0x05-prefixed)")

        // ---------------------------------------------------------------
        // Attachment sidecars (written BEFORE the rows that reference them)
        // ---------------------------------------------------------------
        let attImgOut = "a1a1a1a1-0000-4000-8000-000000000001"
        let attImgIn = "a1a1a1a1-0000-4000-8000-000000000002"
        let attFileOut = "a1a1a1a1-0000-4000-8000-000000000003"
        let attFileIn = "a1a1a1a1-0000-4000-8000-000000000004"
        let attPlain: [(id: String, bytes: Data)] = [
            (attImgOut, pattern(4096, seed: 0x11)),
            (attImgIn, pattern(3000, seed: 0x12)),
            (attFileOut, pattern(2500, seed: 0x13)),
            (attFileIn, pattern(700, seed: 0x14)),
        ]
        for a in attPlain { try alice.writeAttachment(id: a.id, data: a.bytes) }

        // ---------------------------------------------------------------
        // Fixed app data covering every persisted type / enum case
        // ---------------------------------------------------------------
        func d(_ off: Double) -> Date { Date(timeIntervalSinceReferenceDate: Self.referenceBase + off) }
        func mid(_ n: Int) -> String { String(format: "6d000000-0000-4000-8000-%012d", n) }
        func msg(_ n: Int, _ dir: ChatMessage.Direction, _ kind: ChatMessage.Kind, _ text: String,
                 _ t: Double, _ status: ChatMessage.Status,
                 burnAfter: TimeInterval? = nil, burnStartedAt: Date? = nil,
                 voiceData: Data? = nil, voiceDuration: TimeInterval? = nil,
                 attachmentId: String? = nil, fileName: String? = nil, fileMime: String? = nil,
                 fileBytes: Int? = nil, imageWidth: Int? = nil, imageHeight: Int? = nil,
                 viaWeb: Bool? = nil) -> ChatMessage {
            ChatMessage(id: mid(n), direction: dir, kind: kind, text: text, timestamp: d(t),
                        status: status, burnAfter: burnAfter, burnStartedAt: burnStartedAt,
                        voiceData: voiceData, voiceDuration: voiceDuration,
                        attachmentId: attachmentId, fileName: fileName, fileMime: fileMime,
                        fileBytes: fileBytes, imageWidth: imageWidth, imageHeight: imageHeight,
                        viaWeb: viaWeb)
        }

        let carolHash = sha256Hex("carol_fixture")      // blocked
        let daveHash = sha256Hex("dave_fixture")        // per-contact burn
        let strangerHash = sha256Hex("stranger_fixture") // auto-created "anon-…" placeholder
        let ghostHash = sha256Hex("ghost_fixture")      // stranger burn_thread ⇒ empty thread, no contact

        let contacts: [Contact] = [
            Contact(hash: bobHash, name: "bob_fixture", addedAt: d(-1000), verified: true, burnSeconds: nil),
            Contact(hash: carolHash, name: "carol_fixture", addedAt: d(-900), verified: false, burnSeconds: 0),
            Contact(hash: daveHash, name: "dave_fixture", addedAt: d(-800), verified: false, burnSeconds: 86400),
            Contact(hash: strangerHash, name: "anon-" + String(strangerHash.prefix(8)), addedAt: d(320),
                    verified: false, burnSeconds: nil),
        ]

        let voiceOut = pattern(1536, seed: 0x21)
        let voiceIn = pattern(2048, seed: 0x22)
        let bobThread: [ChatMessage] = [
            msg(1, .incoming, .text, "hey alice — first contact from bob", 0, .delivered),
            msg(2, .outgoing, .text, "hi bob, alice here", 10, .read),
            msg(3, .outgoing, .text, "did you get my last one?", 20, .sent),
            msg(4, .outgoing, .text, "still typing this… (never left the device)", 30, .pending),
            msg(5, .outgoing, .text, "this one failed to send", 40, .failed),
            msg(6, .incoming, .text, "incoming and already read by alice", 50, .read),
            msg(7, .outgoing, .text, "composed on the linked desktop browser", 60, .delivered, viaWeb: true),
            msg(8, .incoming, .text, "bob typed this on his desktop", 70, .delivered, viaWeb: true),
            msg(9, .outgoing, .text, "burn timer armed (60s) — countdown not started", 80, .delivered,
                burnAfter: 60),
            msg(10, .incoming, .text, "burn running: 1h, started when alice read it", 90, .read,
                burnAfter: 3600, burnStartedAt: d(90.5)),
            msg(11, .outgoing, .text, "burn running: 24h, started when bob's burn_start arrived", 100, .read,
                burnAfter: 86400, burnStartedAt: d(101)),
            msg(12, .outgoing, .voice, "[voice 3s]", 110, .delivered,
                voiceData: voiceOut, voiceDuration: 3.4),
            msg(13, .incoming, .voice, "[voice 7s]", 120, .delivered,
                burnAfter: 5, voiceData: voiceIn, voiceDuration: 7),
            msg(14, .outgoing, .image, "[IMG]", 130, .delivered,
                attachmentId: attImgOut, fileMime: "image/jpeg", fileBytes: 4096,
                imageWidth: 640, imageHeight: 480),
            msg(15, .incoming, .image, "[IMG]", 140, .read,
                burnAfter: 60, burnStartedAt: d(141),
                attachmentId: attImgIn, fileMime: "image/jpeg", fileBytes: 3000,
                imageWidth: 1024, imageHeight: 768),
            msg(16, .outgoing, .file, "[FILE] report.pdf", 150, .sent,
                attachmentId: attFileOut, fileName: "report.pdf", fileMime: "application/pdf",
                fileBytes: 2500),
            msg(17, .incoming, .file, "[FILE] notes.txt", 160, .delivered,
                attachmentId: attFileIn, fileName: "notes.txt", fileMime: "text/plain",
                fileBytes: 700),
            msg(18, .incoming, .system, "\u{26A0} peer took a screenshot", 170, .delivered),
            msg(19, .incoming, .system, "\u{26A0} peer may be recording the screen", 180, .delivered),
            msg(20, .incoming, .system,
                "\u{26A0} peer linked a desktop browser (Chrome) — this conversation may be viewed there",
                190, .delivered),
            msg(21, .incoming, .system, "peer unlinked the desktop browser", 200, .delivered),
            msg(22, .incoming, .system,
                "\u{26A0} <bob_fixture>'s identity key changed — re-verify before trusting",
                210, .delivered),
            msg(23, .incoming, .system,
                "\u{26A0} <bob_fixture> deleted their account — messages can no longer be delivered.",
                220, .delivered),
            msg(24, .outgoing, .text,
                "escapes: café 🔥 — \"quoted\" back\\slash /slash/ tab\there\nnewline <b>&amp;</b> \u{01}ctrl \u{7F}del",
                230, .delivered),
        ]
        let messages: [String: [ChatMessage]] = [
            bobHash: bobThread,
            daveHash: [msg(25, .incoming, .system, "\u{1F525} thread burned by peer", 300, .delivered)],
            carolHash: [msg(26, .incoming, .text, "message received before carol was blocked", 310, .delivered)],
            strangerHash: [msg(27, .incoming, .text, "first contact from a stranger — auto-created anon contact",
                               320, .delivered)],
            ghostHash: [],
        ]

        var outbox = ReceiptOutbox()
        outbox.delivered = [bobHash: [mid(1)]]
        outbox.read = [bobHash: [mid(6), mid(10)]]
        outbox.burnStart = [daveHash: [mid(99)]]

        let notes: [Note] = [
            Note(id: "6e000000-0000-4000-8000-000000000001",
                 text: "ops checklist\n\n- rotate keys\n- verify bob", createdAt: d(-500), modifiedAt: d(-400),
                 pinned: true),
            Note(id: "6e000000-0000-4000-8000-000000000002",
                 text: "second note\nwith a preview line", createdAt: d(-450), modifiedAt: d(-450),
                 pinned: false),
            Note(id: "6e000000-0000-4000-8000-000000000003",
                 text: "  \n\nleading blank lines then the title", createdAt: d(-300), modifiedAt: d(-299.25),
                 pinned: nil),
        ]

        func tokenBytes(_ day: Int64, seed: UInt8) -> Data {
            var t = Data()
            var be = day.bigEndian
            withUnsafeBytes(of: &be) { t.append(contentsOf: $0) }
            t.append(pattern(32, seed: seed))
            return t
        }
        let tokens: [AccessToken] = [
            AccessToken(epochDay: 20350, token: tokenBytes(20350, seed: 0x31), signature: pattern(256, seed: 0x32)),
            AccessToken(epochDay: 20351, token: tokenBytes(20351, seed: 0x33), signature: pattern(256, seed: 0x34)),
        ]

        var settings = AppSettings()          // every field set to a NON-default value
        settings.theme = "amber"
        settings.scanlines = false
        settings.pushEnabled = true
        settings.defaultBurnSeconds = 3600
        settings.threadAutoBurnDays = 7
        settings.burnOnScreenshot = true
        settings.sendReadReceipts = true
        settings.soundsEnabled = false
        settings.sendTyping = false
        settings.blockScreenshots = false
        settings.socksProxy = "127.0.0.1:9050"
        settings.wipeAfterDays = 30
        settings.autoLockMinutes = 15
        settings.relayURL = "wss://relay.example.test/ws"
        settings.billingURL = "https://billing.example.test"

        alice.update {
            $0.contacts = contacts
            $0.messages = messages
            $0.accessTokens = tokens
            $0.settings = settings
            $0.receiptOutbox = outbox
            $0.notes = notes
            $0.blockedHashes = [carolHash]
            $0.drafts = [bobHash: "unsent draft for bob 🔥", daveHash: "draft for dave"]
        }
        // docs/DECOY-ACCOUNT.md §1a: voice notes live in sidecar attachments
        // now, exactly where images and files already do. Run the same spill
        // the app runs so the fixture carries SPILLED notes, not inline ones.
        XCTAssertTrue(alice.spillInlineVoiceNotes(), "the fixture must exercise §1a")
        alice.flush()                          // synchronous write of pyro.store

        // the two voice rows now point at sidecars, and the bytes are unchanged
        let voiceRows = (alice.blob.messages[bobHash] ?? []).filter { $0.kind == .voice }
        XCTAssertEqual(voiceRows.count, 2)
        var voiceAttachments: [String: Any] = [:]
        for (row, expected) in zip(voiceRows, [voiceOut, voiceIn]) {
            XCTAssertNil(row.voiceData, "\(row.id) is still inline")
            let attId = try XCTUnwrap(row.attachmentId)
            XCTAssertEqual(alice.readAttachment(id: attId), expected)
            voiceAttachments[row.id] = [
                "attachmentId": attId, "durationSeconds": row.voiceDuration ?? 0,
                "plaintextBytes": expected.count, "plaintextSha256": sha256HexData(expected),
            ]
        }

        // ---------------------------------------------------------------
        // Ground truth: open the container the way the app does (§1/§7)
        // ---------------------------------------------------------------
        let storeKey = SecureStore.deriveKey(password: Self.password, salt: salt)
        let (storePlain, containerFacts) = try containerFacts(at: aliceURL, key: storeKey)
        let reencoded = try JSONEncoder().encode(alice.blob)
        let reencodeIdentical = (reencoded == storePlain)
        let decoded = try JSONDecoder().decode(SecureStore.Blob.self, from: storePlain)
        XCTAssertEqual(decoded.messages[bobHash]?.count, bobThread.count)
        XCTAssertEqual(decoded.contacts.count, contacts.count)
        XCTAssertEqual(decoded.preKeys.count, 32)
        XCTAssertEqual(decoded.nextPreKeyId, 33)
        XCTAssertEqual(decoded.sessions.count, 1)
        XCTAssertEqual(decoded.settings.theme, "amber")
        XCTAssertEqual(decoded.drafts?[bobHash], "unsent draft for bob 🔥")

        try? fm.removeItem(at: out.appendingPathComponent("attachments"))
        try fm.createDirectory(at: out.appendingPathComponent("attachments"), withIntermediateDirectories: true)
        // §7: the container is NOT written out. `pyro.store.container.json`
        // carries its facts instead, and `../container-vector.json` pins the
        // layout with a hash that both platforms must reproduce.
        try? fm.removeItem(at: out.appendingPathComponent("pyro.store"))
        try writeJSON(containerFacts, to: out.appendingPathComponent("pyro.store.container.json"))
        try storePlain.write(to: out.appendingPathComponent("pyro.store.plaintext.json"))
        var attachmentFiles: [String: Any] = [:]
        for a in attPlain {
            let sealed = try Data(contentsOf: alice.attachmentURL(id: a.id))
            try sealed.write(to: out.appendingPathComponent("attachments").appendingPathComponent(a.id))
            XCTAssertEqual(alice.readAttachment(id: a.id), a.bytes)
            let info: [String: Any] = [
                "plaintextBytes": a.bytes.count,
                "plaintextSha256": sha256HexData(a.bytes),
                "sealedBytes": sealed.count,
                "sealedSha256": sha256HexData(sealed),
            ]
            attachmentFiles[a.id] = info
        }
        // §1a: ship the spilled voice sidecars too, so the fixture is complete
        // — they are ordinary attachments now, sealed under the same key.
        for row in voiceRows {
            let id = try XCTUnwrap(row.attachmentId)
            let plain = try XCTUnwrap(alice.readAttachment(id: id))
            let sealed = try Data(contentsOf: alice.attachmentURL(id: id))
            try sealed.write(to: out.appendingPathComponent("attachments").appendingPathComponent(id))
            attachmentFiles[id] = [
                "plaintextBytes": plain.count, "plaintextSha256": sha256HexData(plain),
                "sealedBytes": sealed.count, "sealedSha256": sha256HexData(sealed),
                "kind": "voice", "messageId": row.id, "durationSeconds": row.voiceDuration ?? 0,
            ] as [String: Any]
        }

        // ---------------------------------------------------------------
        // PYROBAK1 backup of the same data
        // ---------------------------------------------------------------
        let bak = try BackupService.export(from: alice, passphrase: Self.backupPassphrase)
        XCTAssertEqual(bak.prefix(8), Data("PYROBAK1".utf8))
        let bakSalt = bak.subdata(in: 8..<40)
        let bakKey = SecureStore.deriveKey(password: Self.backupPassphrase, salt: bakSalt)
        let bakBox = try AES.GCM.SealedBox(combined: bak.subdata(in: 40..<bak.count))
        let bakPlain = try AES.GCM.open(bakBox, using: bakKey)
        let (bakBlob, bakAtts) = try BackupService.decode(bak, passphrase: Self.backupPassphrase)
        // §1a: the two voice notes are sidecars now, so the backup carries
        // them alongside the four image/file attachments
        XCTAssertEqual(bakAtts.count, attPlain.count + voiceRows.count)
        XCTAssertEqual(bakAtts[attImgOut], attPlain[0].bytes)
        XCTAssertEqual(bakBlob.messages[bobHash]?.count, bobThread.count)
        let bakObj = try XCTUnwrap(JSONSerialization.jsonObject(with: bakPlain) as? [String: Any])
        let exportedAt = try XCTUnwrap(bakObj["exportedAt"] as? Double)
        try bak.write(to: out.appendingPathComponent("backup.pyrobak"))
        try bakPlain.write(to: out.appendingPathComponent("backup.plaintext.json"))

        // ---------------------------------------------------------------
        // Duress verifier (Keychain) via the real API
        // ---------------------------------------------------------------
        SecureStore.setDuressPin(Self.duressPin)
        let verifier = try XCTUnwrap(KeychainHelper.get("duress-verifier"))
        XCTAssertTrue(SecureStore.matchesDuressPin(Self.duressPin))
        XCTAssertFalse(SecureStore.matchesDuressPin("0000"))
        let expectedVerifier = SecureStore.deriveKey(password: Self.duressPin, salt: salt)
            .withUnsafeBytes { Data($0) }
        XCTAssertEqual(verifier, expectedVerifier)
        let saltItem: [String: Any] = [
            "hex": Self.saltHex, "base64": salt.base64EncodedString(), "bytes": 32,
            "meaning": "PBKDF2 salt (the 'pepper'): random 32 B generated once per install, never stored in pyro.store. storeKey = PBKDF2-HMAC-SHA256(password UTF-8, kdf-salt, 600000, 32)",
        ]
        let duressItem: [String: Any] = [
            "hex": hexString(verifier), "base64": verifier.base64EncodedString(), "bytes": verifier.count,
            "pin": Self.duressPin,
            "meaning": "PBKDF2-HMAC-SHA256(pin UTF-8, kdf-salt, 600000, 32) — SecureStore.setDuressPin; matchesDuressPin(candidate) = (PBKDF2(candidate, kdf-salt) == stored) checked BEFORE unlock; a match calls SecureStore.obliterate()",
        ]
        let keychainItems: [String: Any] = ["kdf-salt": saltItem, "duress-verifier": duressItem]
        let keychain: [String: Any] = [
            "service": "ca.pyrochat.app",
            "class": "kSecClassGenericPassword",
            "accessible": "kSecAttrAccessibleWhenUnlockedThisDeviceOnly",
            "items": keychainItems,
            "source": "ios/PyroChat/Core/KeychainHelper.swift, ios/PyroChat/Core/SecureStore.swift (salt(), setDuressPin, matchesDuressPin)",
        ]
        try writeJSON(keychain, to: out.appendingPathComponent("keychain.json"))

        // ---------------------------------------------------------------
        // Session probe: a message bob encrypts AFTER the store was written.
        // Proven by a cold unlock of the exported bytes (scratch copy so the
        // exported file is never re-saved).
        // ---------------------------------------------------------------
        let probeText = "probe: decrypt me with the fixture session"
        let probeBody = try bobCrypto.encrypt(
            Wire.Payload(kind: "text", text: probeText, senderName: "bob_fixture", burnAfter: 60),
            for: aliceHash)
        let verifyDir = work.appendingPathComponent("verify", isDirectory: true)
        try fm.createDirectory(at: verifyDir, withIntermediateDirectories: true)
        try fm.copyItem(at: aliceURL, to: verifyDir.appendingPathComponent("pyro.store"))
        try fm.copyItem(at: alice.attachmentsDir,
                        to: SecureStore.attachmentsDir(for: verifyDir.appendingPathComponent("pyro.store")))
        let cold = try SecureStore(unlockingWithPassword: Self.password,
                                   at: verifyDir.appendingPathComponent("pyro.store"))
        let coldCrypto = CryptoEngine(store: cold)
        let probe = try coldCrypto.decrypt(probeBody, from: bobHash)
        XCTAssertEqual(probe.text, probeText)
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "wrong password",
                                             at: verifyDir.appendingPathComponent("pyro.store")))
        let envelope = try JSONDecoder().decode(Wire.Envelope.self,
                                                from: XCTUnwrap(Data(base64Encoded: probeBody)))
        let expectedPayload: [String: Any] = [
            "kind": "text", "text": probeText, "senderName": "bob_fixture", "burnAfter": 60,
        ]
        let probeDoc: [String: Any] = [
            "description": "Relay `recv.body` encrypted by bob_fixture AFTER pyro.store was written. Unlock pyro.store as alice, load sessions[from] into libsignal, decrypt: envelope = base64(JSON{v:1,type,data:b64}); type 2 = SignalMessage (Double Ratchet), 3 = PreKeySignalMessage. Inner plaintext is JSON of Wire.Payload.",
            "from": bobHash,
            "body": probeBody,
            "envelopeType": Int(envelope.type),
            "expectedPayload": expectedPayload,
        ]
        try writeJSON(probeDoc, to: out.appendingPathComponent("session-probe.json"))

        // ---------------------------------------------------------------
        // manifest.json
        // ---------------------------------------------------------------
        // Count what was actually PERSISTED, not the rows as they were built:
        // §1a's spill rewrote the voice rows on the way to disk.
        let persisted = alice.blob.messages
        var kindCounts: [String: Int] = [:], dirCounts: [String: Int] = [:], statusCounts: [String: Int] = [:]
        var total = 0
        for (_, rows) in persisted {
            for r in rows {
                total += 1
                kindCounts[r.kind.rawValue, default: 0] += 1
                dirCounts[r.direction.rawValue, default: 0] += 1
                statusCounts[r.status.rawValue, default: 0] += 1
            }
        }
        func fileInfo(_ d: Data) -> [String: Any] { ["sha256": sha256HexData(d), "bytes": d.count] }
        var files: [String: Any] = [
            "pyro.store.container.json": containerFacts,
            "pyro.store.plaintext.json": fileInfo(storePlain),
            "backup.pyrobak": fileInfo(bak),
            "backup.plaintext.json": fileInfo(bakPlain),
        ]
        for (id, info) in attachmentFiles { files["attachments/" + id] = info }
        let allRows = Array(persisted.values.joined())
        let nBurnAfter = allRows.filter { $0.burnAfter != nil }.count
        let nBurnStarted = allRows.filter { $0.burnStartedAt != nil }.count
        let nViaWeb = allRows.filter { $0.viaWeb == true }.count
        let nAttach = allRows.filter { $0.attachmentId != nil }.count
        // §1a: the audio lives in a sidecar now, so the fact worth pinning is
        // how many voice notes there are, not how many rows still hold bytes
        let nVoice = allRows.filter { $0.kind == .voice }.count
        XCTAssertEqual(allRows.filter { $0.voiceData != nil }.count, 0,
                       "no fixture row may still carry inline audio")
        let nVerified = contacts.filter { $0.verified }.count
        let nPinned = notes.filter { $0.isPinned }.count
        let nEmptyThreads = persisted.values.filter { $0.isEmpty }.count
        let sessionBytes: Int = alice.blob.sessions[bobHash]?.count ?? 0
        let identityPubB64: String = try aliceCrypto.myIdentityPublic().base64EncodedString()

        let storeInfo: [String: Any] = [
            "file": "pyro.store.container.json (the container itself is not checked in — §7)",
            "password": Self.password,
            "kdfSaltHex": Self.saltHex,
            "kdf": "PBKDF2-HMAC-SHA256", "iterations": 600_000, "keyBytes": 32,
            "aead": "AES-256-GCM", "nonceBytes": 12, "tagBytes": 16, "aad": "none",
            "layout": "PYROVLT1 container, docs/DECOY-ACCOUNT.md §1: magic(8) || slotCount(1) || "
                + "slotBytes(4, big-endian) || 3 slots; each slot is two 2 MiB halves; each half is "
                + "nonce(12) || ciphertext || tag(16) over seq(8, BE) || length(4, BE) || JSON(Blob) "
                + "|| random padding. Open tries both halves of all three slots and takes the "
                + "authenticating half with the higher seq.",
            "container": containerFacts,
            "voiceNotes": voiceAttachments,
            "voiceNotesNote": "docs/DECOY-ACCOUNT.md §1a: voice audio is a sidecar attachment, keyed "
                + "by the row's attachmentId. `voiceData` still DECODES for older data but is never "
                + "written; a legacy store's inline notes are spilled on unlock, before the "
                + "container is created.",
            "plaintext": "pyro.store.plaintext.json (exact decrypted bytes; Swift JSONEncoder, no sortedKeys/prettyPrinted)",
            "reencodeByteIdentical": reencodeIdentical,
        ]
        let backupInfo: [String: Any] = [
            "file": "backup.pyrobak",
            "passphrase": Self.backupPassphrase,
            "magic": "PYROBAK1", "saltHex": hexString(bakSalt),
            "kdf": "PBKDF2-HMAC-SHA256", "iterations": 600_000, "keyBytes": 32,
            "layout": "magic(8) || salt(32) || nonce(12) || ciphertext || tag(16)",
            "payload": "JSON {blob: <same Blob as the store>, attachments: {id: base64 plaintext}, exportedAt: Date}",
            "exportedAt": exportedAt,
            "plaintext": "backup.plaintext.json",
        ]
        let identityInfo: [String: Any] = [
            "usernameDisplay": "alice_fixture",
            "usernameHash": aliceHash,
            "registrationId": alice.blob.registrationId,
            "identityPublicKeyB64": identityPubB64,
            "identityKeyPairBytes": alice.blob.identityKeyPair.count,
            "authPublicKeyB64": aliceReg.authPub.base64EncodedString(),
            "authPrivateKeyBytes": alice.blob.authPrivateKey.count,
        ]
        let peerInfo: [String: Any] = [
            "usernameDisplay": "bob_fixture",
            "usernameHash": bobHash,
            "registrationId": bobReg.regId,
            "identityKeyB64": bobReg.identityKey.base64EncodedString(),
            "identityKeyBytes": bobReg.identityKey.count,
            "sessionRecordBytes": sessionBytes,
        ]
        let hashesInfo: [String: String] = [
            "alice_fixture": aliceHash, "bob_fixture": bobHash, "carol_fixture": carolHash,
            "dave_fixture": daveHash, "stranger_fixture": strangerHash, "ghost_fixture": ghostHash,
        ]
        let receiptCounts: [String: Int] = ["delivered": 1, "read": 2, "burnStart": 1]
        let countsInfo: [String: Any] = [
            "contacts": contacts.count,
            "contactsVerified": nVerified,
            "threads": messages.count,
            "emptyThreads": nEmptyThreads,
            "messages": total,
            "messagesByKind": kindCounts,
            "messagesByDirection": dirCounts,
            "messagesByStatus": statusCounts,
            "messagesWithBurnAfter": nBurnAfter,
            "messagesWithBurnStartedAt": nBurnStarted,
            "messagesViaWeb": nViaWeb,
            "messagesWithAttachment": nAttach,
            "messagesWithVoiceData": nVoice,
            "attachments": attachmentFiles.count,   // 4 image/file + 2 spilled voice (§1a)
            "notes": notes.count,
            "notesPinned": nPinned,
            "drafts": 2,
            "blockedHashes": 1,
            "accessTokens": tokens.count,
            "preKeys": alice.blob.preKeys.count,
            "signedPreKeys": alice.blob.signedPreKeys.count,
            "kyberPreKeys": alice.blob.kyberPreKeys.count,
            "sessions": alice.blob.sessions.count,
            "knownIdentities": alice.blob.knownIdentities.count,
            "nextPreKeyId": alice.blob.nextPreKeyId,
            "receiptOutbox": receiptCounts,
        ]
        let attachmentIds: [String: String] = [
            "imageOutgoing": attImgOut, "imageIncoming": attImgIn,
            "fileOutgoing": attFileOut, "fileIncoming": attFileIn,
        ]
        let fixedValues: [String: Any] = [
            "dateBase": Self.referenceBase,
            "dateEncoding": "seconds since 2001-01-01T00:00:00Z as a JSON number (Swift Date default)",
            "messageIdPattern": "6d000000-0000-4000-8000-%012d (n = 1…27)",
            "attachmentPlaintext": "LCG pattern: x = x*1664525 + 1013904223 (mod 2^32), byte = x >> 24, x0 = seed | 0x9E370000; seeds 0x11,0x12,0x13,0x14; voiceData seeds 0x21 (1536 B), 0x22 (2048 B)",
        ]
        let randomPerRun: [String] = [
            "identityKeyPair, registrationId, authPrivateKey (libsignal / CryptoKit generation)",
            "preKeys, signedPreKeys, kyberPreKeys, sessions, knownIdentities (libsignal records)",
            "AES-GCM nonces of pyro.store, every attachments/* file and backup.pyrobak",
            "backup salt (bytes 8..40 of backup.pyrobak) and backup exportedAt",
            "JSON object key order (Swift Dictionary / JSONEncoder — not sorted)",
            "order of the flat [UInt32: Data] arrays (preKeys, signedPreKeys, kyberPreKeys)",
        ]
        let manifest: [String: Any] = [
            "generatedBy": "ios/Tests/FixtureExportTests.swift (real SecureStore / CryptoEngine / BackupService APIs)",
            "generatedAt": ISO8601DateFormatter().string(from: Date()),
            "store": storeInfo,
            "backup": backupInfo,
            "identity": identityInfo,
            "peer": peerInfo,
            "hashes": hashesInfo,
            "counts": countsInfo,
            "attachmentIds": attachmentIds,
            "fixedValues": fixedValues,
            "randomPerRun": randomPerRun,
            "files": files,
        ]
        try writeJSON(manifest, to: out.appendingPathComponent("manifest.json"))
        try? fm.removeItem(at: work)
    }

    // MARK: group thread fixture (docs/GROUPS.md §3)

    /// SECOND export, gated by `PYRO_EXPORT_GROUPS_DIR` (checked in as
    /// `android/fixtures/ios-store-groups/`): a store with one group thread —
    /// 3 members, 6 rows incl. an image and a system row, receipts on an
    /// outgoing row, policy `burn_remove` — its plaintext JSON and a manifest,
    /// so the Android codec can prove it decodes `Blob.groups` and the
    /// `group` / `sender` / `receipts` row keys.
    func testExportIOSGroupFixtures() throws {
        guard let outPath = ProcessInfo.processInfo.environment["PYRO_EXPORT_GROUPS_DIR"],
              !outPath.isEmpty else {
            throw XCTSkip("PYRO_EXPORT_GROUPS_DIR not set — group fixture export is opt-in")
        }
        let fm = FileManager.default
        let out = URL(fileURLWithPath: outPath, isDirectory: true)
        try fm.createDirectory(at: out, withIntermediateDirectories: true)

        let prevSalt = KeychainHelper.get("kdf-salt")
        defer {
            if let prevSalt { KeychainHelper.set(prevSalt, key: "kdf-salt") }
            else { KeychainHelper.delete("kdf-salt") }
        }
        let salt = hexData(Self.saltHex)
        KeychainHelper.set(salt, key: "kdf-salt")
        XCTAssertEqual(KeychainHelper.get("kdf-salt"), salt)

        let work = fm.temporaryDirectory.appendingPathComponent("pyro-group-fixture-\(UUID().uuidString)")
        try fm.createDirectory(at: work, withIntermediateDirectories: true)
        let storeURL = work.appendingPathComponent("pyro.store")
        let alice = SecureStore(creatingWithPassword: Self.password, at: storeURL)
        let aliceCrypto = CryptoEngine(store: alice)
        _ = try aliceCrypto.generateIdentity(username: "alice_fixture")
        let aliceHash = alice.blob.usernameHash
        let bobHash = sha256Hex("bob_fixture")
        let carolHash = sha256Hex("carol_fixture")
        let daveHash = sha256Hex("dave_fixture")     // former member (left) — colour history only

        func d(_ off: Double) -> Date { Date(timeIntervalSinceReferenceDate: Self.referenceBase + off) }
        let groupID = "9d000000-0000-4000-8000-000000000001"
        let group = Group(id: groupID, name: "ops", creator: aliceHash, createdAt: d(-3600), epoch: 4,
                          policy: .burnRemove, burnSeconds: 300,
                          members: [
                              Member(hash: aliceHash, name: "alice_fixture", joinedAt: d(-3600)),
                              Member(hash: bobHash, name: "bob_fixture", joinedAt: d(-3600)),
                              Member(hash: carolHash, name: "carol_fixture", joinedAt: d(-1800)),
                          ],
                          left: nil,
                          former: [Member(hash: daveHash, name: "dave_fixture", joinedAt: d(-3000))])

        let attImg = "a2a2a2a2-0000-4000-8000-000000000001"
        let imgBytes = pattern(3200, seed: 0x41)
        try alice.writeAttachment(id: attImg, data: imgBytes)

        func gid(_ n: Int) -> String { String(format: "6e000000-0000-4000-8000-%012d", n) }
        func grow(_ n: Int, _ dir: ChatMessage.Direction, _ kind: ChatMessage.Kind, _ text: String,
                  _ t: Double, _ status: ChatMessage.Status, sender: String? = nil,
                  receipts: [String: String]? = nil, burnAfter: TimeInterval? = nil,
                  burnStartedAt: Date? = nil, attachmentId: String? = nil, fileMime: String? = nil,
                  fileBytes: Int? = nil, imageWidth: Int? = nil, imageHeight: Int? = nil) -> ChatMessage {
            var m = ChatMessage(id: gid(n), direction: dir, kind: kind, text: text, timestamp: d(t),
                                status: status, burnAfter: burnAfter, burnStartedAt: burnStartedAt,
                                attachmentId: attachmentId, fileMime: fileMime, fileBytes: fileBytes,
                                imageWidth: imageWidth, imageHeight: imageHeight)
            m.group = groupID
            m.sender = sender
            m.receipts = receipts
            return m
        }
        let rows: [ChatMessage] = [
            grow(1, .incoming, .text, "first line in #ops from bob", 0, .read, sender: bobHash),
            grow(2, .incoming, .system, "── <carol_fixture> joined ──", 10, .delivered),
            grow(3, .outgoing, .text, "welcome carol — creator speaking", 20, .read,
                 receipts: [bobHash: "read", carolHash: "read"]),
            grow(4, .incoming, .image, "[IMG]", 30, .delivered, sender: carolHash,
                 burnAfter: 300, attachmentId: attImg, fileMime: "image/jpeg", fileBytes: 3200,
                 imageWidth: 320, imageHeight: 240),
            grow(5, .outgoing, .text, "partial receipts: one delivered, one read", 40, .sent,
                 receipts: [bobHash: "delivered"], burnAfter: 300),
            grow(6, .incoming, .text, "late line from a member who then left", 50, .read, sender: daveHash),
        ]
        var outbox = ReceiptOutbox()
        outbox.read = [bobHash: [gid(1)]]

        alice.update {
            $0.contacts = [Contact(hash: bobHash, name: "bob_fixture", addedAt: d(-4000), verified: true, burnSeconds: nil)]
            $0.groups = [group]
            $0.messages = [groupID: rows, bobHash: []]
            $0.receiptOutbox = outbox
            $0.drafts = [groupID: "unsent group draft"]
        }
        alice.flush()

        let storeKey = SecureStore.deriveKey(password: Self.password, salt: salt)
        let (storePlain, containerFacts) = try containerFacts(at: storeURL, key: storeKey)
        let decoded = try JSONDecoder().decode(SecureStore.Blob.self, from: storePlain)
        XCTAssertEqual(decoded.groups, [group])
        XCTAssertEqual(decoded.messages[groupID]?.count, 6)
        XCTAssertEqual(decoded.messages[groupID]?[2].receipts, [bobHash: "read", carolHash: "read"])
        XCTAssertEqual(decoded.messages[groupID]?[0].sender, bobHash)
        let plainObj = try XCTUnwrap(JSONSerialization.jsonObject(with: storePlain) as? [String: Any])
        let groupsObj = try XCTUnwrap(plainObj["groups"] as? [[String: Any]])
        XCTAssertEqual(Set(groupsObj[0].keys), ["id", "name", "creator", "createdAt", "epoch", "policy", "burnSeconds", "members", "former"])

        try? fm.removeItem(at: out.appendingPathComponent("attachments"))
        try fm.createDirectory(at: out.appendingPathComponent("attachments"), withIntermediateDirectories: true)
        // §7: facts, not 12 MiB of padding
        try? fm.removeItem(at: out.appendingPathComponent("pyro.store"))
        try writeJSON(containerFacts, to: out.appendingPathComponent("pyro.store.container.json"))
        try storePlain.write(to: out.appendingPathComponent("pyro.store.plaintext.json"))
        let sealed = try Data(contentsOf: alice.attachmentURL(id: attImg))
        try sealed.write(to: out.appendingPathComponent("attachments").appendingPathComponent(attImg))

        let colours: [String: Any] = Dictionary(uniqueKeysWithValues:
            GroupMath.joinOrder(members: group.members, former: group.former).enumerated().map { i, h in
                (h, ["joinIndex": i, "hex": GroupMath.colour(joinIndex: i).hex] as [String: Any])
            })
        let expectedWords: [String: String] = [
            gid(3): GroupMath.statusWord(status: .read, receipts: [bobHash: "read", carolHash: "read"], recipients: 2, showRead: true),
            gid(5): GroupMath.statusWord(status: .sent, receipts: [bobHash: "delivered"], recipients: 2, showRead: true),
        ]
        let manifest: [String: Any] = [
            "generatedBy": "ios/Tests/FixtureExportTests.swift testExportIOSGroupFixtures (real SecureStore API)",
            "generatedAt": ISO8601DateFormatter().string(from: Date()),
            "spec": "docs/GROUPS.md §3 — Blob.groups + ChatMessage.group/sender/receipts; same KDF/AEAD as ../ios-store",
            "store": [
                "file": "pyro.store", "password": Self.password, "kdfSaltHex": Self.saltHex,
                "kdf": "PBKDF2-HMAC-SHA256", "iterations": 600_000, "keyBytes": 32,
                "aead": "AES-256-GCM", "nonceBytes": 12, "tagBytes": 16, "aad": "none",
                "layout": "nonce(12) || ciphertext || tag(16)",
                "plaintext": "pyro.store.plaintext.json",
            ] as [String: Any],
            "identity": ["usernameDisplay": "alice_fixture", "usernameHash": aliceHash],
            "hashes": ["alice_fixture": aliceHash, "bob_fixture": bobHash, "carol_fixture": carolHash, "dave_fixture": daveHash],
            "group": [
                "id": groupID, "name": "ops", "creator": aliceHash, "epoch": 4, "policy": "burn_remove",
                "burnSeconds": 300, "members": 3, "former": 1,
                "keys": ["id", "name", "creator", "createdAt", "epoch", "policy", "burnSeconds", "members", "left", "former"],
                "iosOnlyOptionalKeys": ["former (members who left/were removed, keeps colour indices)", "members[].gone (relay said unknown_user)"],
                "colours": colours,
                "colourRule": "joinIndex = position in (members + former) ordered by (joinedAt, hash), earliest join per hash; palette[joinIndex % 10], 70% for joinIndex/10 == 1, 50% for >= 2",
            ] as [String: Any],
            "thread": [
                "key": groupID,
                "rows": 6,
                "rowKeys": ["group (= thread key)", "sender (incoming only)", "receipts (outgoing only: {hash: delivered|read})"],
                "byKind": ["text": 4, "system": 1, "image": 1],
                "expectedStatusWords": expectedWords,
                "outgoingRowIds": [gid(3), gid(5)],
                "systemRow": "── <carol_fixture> joined ──",
            ] as [String: Any],
            "attachmentIds": ["imageIncoming": attImg],
            "counts": ["contacts": 1, "groups": 1, "threads": 2, "messages": 6, "drafts": 1,
                       "receiptOutbox": ["read": 1]] as [String: Any],
            "files": [
                "pyro.store.container.json": containerFacts,
                "pyro.store.plaintext.json": ["sha256": sha256HexData(storePlain), "bytes": storePlain.count],
                "attachments/" + attImg: ["plaintextBytes": imgBytes.count, "plaintextSha256": sha256HexData(imgBytes),
                                          "sealedBytes": sealed.count, "sealedSha256": sha256HexData(sealed)],
            ] as [String: Any],
            "fixedValues": [
                "dateBase": Self.referenceBase,
                "dateEncoding": "seconds since 2001-01-01T00:00:00Z as a JSON number",
                "messageIdPattern": "6e000000-0000-4000-8000-%012d (n = 1…6)",
                "attachmentPlaintext": "LCG pattern as in ../ios-store, seed 0x41, 3200 B",
            ] as [String: Any],
            "randomPerRun": ["identity keys, prekeys, registrationId", "AES-GCM nonces", "JSON key order"],
        ]
        try writeJSON(manifest, to: out.appendingPathComponent("manifest.json"))
        try? fm.removeItem(at: work)
    }

    // MARK: the container (docs/DECOY-ACCOUNT.md §1, §7)

    /// Open a written store the way the app does — through the container, both
    /// halves of every slot — and report what Android has to reproduce.
    ///
    /// §7: the 12 MiB container itself is NOT written to the fixture directory.
    /// It is mostly incompressible padding, and `container-vector.json` already
    /// pins the layout with a hash. What ships here is the plaintext and these
    /// facts.
    private func containerFacts(at url: URL, key: SymmetricKey) throws
        -> (plain: Data, facts: [String: Any]) {
        let bytes = try Data(contentsOf: url)
        XCTAssertEqual(bytes.prefix(8), Data("PYROVLT1".utf8), "not a container")
        XCTAssertEqual(bytes.count, SlotVault.fileBytes)
        let container = try SlotVault.parse(bytes)
        let live = try XCTUnwrap(SlotVault.open(container, key: key),
                                 "no slot authenticated under the fixture key")
        let facts: [String: Any] = [
            "format": "PYROVLT1",
            "spec": "docs/DECOY-ACCOUNT.md §1 — 3 slots x 4 MiB, each two 2 MiB halves",
            "slotCount": SlotVault.slotCount,
            "slotBytes": SlotVault.slotBytes,
            "halfBytes": SlotVault.halfBytes,
            "headerBytes": SlotVault.headerBytes,
            "headerHex": hexString(bytes.prefix(SlotVault.headerBytes)),
            "fileBytes": bytes.count,
            "fileSha256": sha256HexData(bytes),
            "occupiedSlot": live.slot,
            "liveHalf": live.half,
            "liveSeq": Int(live.seq),
            "halfPlaintext": "seq(8, big-endian) || length(4, big-endian) || JSON(Blob) || random padding",
            "note": "The container file is deliberately NOT checked in (§7): 12,582,925 bytes of "
                + "mostly incompressible padding. Reproduce the layout from "
                + "../container-vector.json; verify CONTENT against pyro.store.plaintext.json.",
        ]
        return (live.payload, facts)
    }

    // MARK: helpers

    /// Deterministic LCG byte pattern (documented in manifest.fixedValues).
    private func pattern(_ n: Int, seed: UInt8) -> Data {
        var x: UInt32 = UInt32(seed) | 0x9E37_0000
        var d = Data(count: n)
        for i in 0..<n {
            x = x &* 1_664_525 &+ 1_013_904_223
            d[i] = UInt8(truncatingIfNeeded: x >> 24)
        }
        return d
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

    private func writeJSON(_ obj: [String: Any], to url: URL) throws {
        let data = try JSONSerialization.data(
            withJSONObject: obj, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
        try (data + Data("\n".utf8)).write(to: url)
    }
}
