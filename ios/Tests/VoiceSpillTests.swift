import XCTest
import CryptoKit
@testable import PyroChat

/// docs/DECOY-ACCOUNT.md §1a — voice notes must leave the blob, and they must
/// leave it WHILE THE STORE IS STILL LEGACY. A legacy file has no size limit; a
/// container half does. Doing it the other way round is the bug §1a exists to
/// prevent: an install already over the cap would fail to convert and retry
/// forever, on every unlock, and never get anywhere.
final class VoiceSpillTests: XCTestCase {

    private var work: URL!
    private var prevSalt: Data?
    private let password = "voice-spill-pass"

    override func setUpWithError() throws {
        work = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-voice-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        prevSalt = KeychainHelper.get("kdf-salt")
        KeychainHelper.set(Data((0..<32).map { UInt8($0 ^ 0x5f) }), key: "kdf-salt")
    }

    override func tearDownWithError() throws {
        SecureStore.onStoreFull = nil
        if let prevSalt { KeychainHelper.set(prevSalt, key: "kdf-salt") }
        else { KeychainHelper.delete("kdf-salt") }
        try? FileManager.default.removeItem(at: work)
    }

    /// Deterministic stand-in for AAC: distinguishable per note, and
    /// incompressible enough that base64 in the JSON really is the full cost.
    private func audio(_ seed: UInt8, bytes: Int) -> Data {
        var x = UInt32(seed) | 0x9E37_0000
        return Data((0..<bytes).map { _ in
            x = x &* 1_664_525 &+ 1_013_904_223
            return UInt8(truncatingIfNeeded: x >> 24)
        })
    }

    private func voiceRow(_ id: String, audio: Data, seconds: TimeInterval) -> ChatMessage {
        ChatMessage(id: id, direction: .incoming, kind: .voice,
                    text: "[voice \(Int(seconds))s]", timestamp: Date(timeIntervalSince1970: 1_700_000_000),
                    status: .read, burnAfter: nil, burnStartedAt: nil,
                    voiceData: audio, voiceDuration: seconds)
    }

    /// Seal a blob the old way — one AES-GCM box over the whole JSON, no magic.
    private func writeLegacy(_ blob: SecureStore.Blob, to url: URL) throws -> Data {
        let key = SecureStore.deriveKey(password: password,
                                        salt: try XCTUnwrap(KeychainHelper.get("kdf-salt")))
        let plain = try JSONEncoder().encode(blob)
        try XCTUnwrap(AES.GCM.seal(plain, using: key).combined).write(to: url)
        return plain
    }

    // MARK: - the case §1a exists for

    /// A legacy blob whose inline audio alone exceeds a container half. Before
    /// §1a this store could never migrate; it must now migrate intact, with
    /// every note still playable from its sidecar.
    func testALegacyBlobTooBigForASlotMigratesOnceTheAudioIsSpilled() throws {
        let url = work.appendingPathComponent("pyro.store")
        let thread = String(repeating: "ab", count: 32)

        // 12 x 256 KiB of "AAC" = 3 MiB raw, ~4 MiB once JSON base64 inflates
        // it by a third: comfortably past the 2 MiB half, and past the 4 MiB
        // slot the format had before the halves.
        let clipBytes = 256 * 1024
        var rows: [ChatMessage] = []
        var expected: [String: Data] = [:]
        for i in 0..<12 {
            let id = "voice-\(i)"
            let bytes = audio(UInt8(0x10 + i), bytes: clipBytes)
            expected[id] = bytes
            rows.append(voiceRow(id, audio: bytes, seconds: TimeInterval(30 + i)))
        }
        rows.append(ChatMessage(id: "text-1", direction: .outgoing, kind: .text,
                                text: "still here", timestamp: Date(timeIntervalSince1970: 1_700_000_100),
                                status: .read))
        var blob = SecureStore.Blob()
        blob.usernameDisplay = "heavy"
        blob.usernameHash = thread
        blob.messages = [thread: rows]

        let legacyPlain = try writeLegacy(blob, to: url)
        XCTAssertGreaterThan(legacyPlain.count, SlotVault.maxPayloadBytes,
                             "the fixture must actually be too big, or this proves nothing")
        XCTAssertGreaterThan(legacyPlain.count, SlotVault.slotBytes,
                             "…and too big even for a whole undivided slot")
        XCTAssertFalse(SlotVault.isContainer(try Data(contentsOf: url)))

        var full: [URL] = []
        SecureStore.onStoreFull = { url, _ in full.append(url) }

        // unlock: spill first (§1a step 2), then write the container (step 4)
        let store = try SecureStore(unlockingWithPassword: password, at: url)
        XCTAssertFalse(store.isLegacyFile, "it must have converted, not given up")
        XCTAssertEqual(full, [], "and it must not have reported itself full")
        XCTAssertNotNil(store.slotIndex)

        let onDisk = try Data(contentsOf: url)
        XCTAssertTrue(SlotVault.isContainer(onDisk))
        XCTAssertEqual(onDisk.count, SlotVault.fileBytes)

        // every note moved, and every note is still exactly the bytes it was
        let migrated = try XCTUnwrap(store.blob.messages[thread])
        XCTAssertEqual(migrated.count, rows.count)
        for row in migrated where row.kind == .voice {
            XCTAssertNil(row.voiceData, "\(row.id) is still inline")
            let attId = try XCTUnwrap(row.attachmentId, "\(row.id) has no sidecar")
            XCTAssertEqual(store.readAttachment(id: attId), expected[row.id], row.id)
            XCTAssertEqual(row.fileBytes, clipBytes)
            XCTAssertNotNil(row.voiceDuration)
        }
        XCTAssertEqual(migrated.last?.text, "still here")

        // and it survives a relaunch: reopened from the container, notes intact
        store.flush()
        let reopened = try SecureStore(unlockingWithPassword: password, at: url)
        let after = try XCTUnwrap(reopened.blob.messages[thread])
        for row in after where row.kind == .voice {
            let attId = try XCTUnwrap(row.attachmentId)
            XCTAssertEqual(reopened.readAttachment(id: attId), expected[row.id], row.id)
        }
        XCTAssertLessThanOrEqual(try JSONEncoder().encode(reopened.blob).count,
                                 SlotVault.maxPayloadBytes,
                                 "the blob is small now — that is the point")
    }

    /// The ordering, stated directly: the container is never written before the
    /// spill. If it were, this store could not exist on disk at all.
    func testTheContainerIsOnlyWrittenAfterTheBlobHasShrunk() throws {
        let url = work.appendingPathComponent("order.store")
        let thread = "cafebabe"
        var blob = SecureStore.Blob()
        blob.messages = [thread: (0..<10).map {
            voiceRow("v\($0)", audio: audio(UInt8($0), bytes: 256 * 1024), seconds: 60)
        }]
        _ = try writeLegacy(blob, to: url)

        let store = try SecureStore(unlockingWithPassword: password, at: url)
        XCTAssertFalse(store.isLegacyFile)
        // the payload actually sealed into the live half carries no audio
        let key = SecureStore.deriveKey(password: password,
                                        salt: try XCTUnwrap(KeychainHelper.get("kdf-salt")))
        let live = try XCTUnwrap(SlotVault.open(
            try SlotVault.parse(try Data(contentsOf: url)), key: key))
        XCTAssertLessThanOrEqual(live.payload.count, SlotVault.maxPayloadBytes)
        XCTAssertNil(live.payload.range(of: Data("voiceData".utf8)),
                     "no inline audio was ever sealed into a half")
    }

    // MARK: - tolerance, both directions

    /// §1a keeps DECODING tolerant: the wire format and backups are not
    /// changing this wave, so a row that arrives inline must still decode, play
    /// and then be spilled.
    func testInlineVoiceStillDecodesAndIsSpilledOnUnlock() throws {
        let url = work.appendingPathComponent("tolerant.store")
        let thread = "deadbeef"
        let bytes = audio(0x77, bytes: 4096)
        var blob = SecureStore.Blob()
        blob.messages = [thread: [voiceRow("only", audio: bytes, seconds: 3)]]
        _ = try writeLegacy(blob, to: url)

        let store = try SecureStore(unlockingWithPassword: password, at: url)
        let row = try XCTUnwrap(store.blob.messages[thread]?.first)
        XCTAssertNil(row.voiceData)
        XCTAssertEqual(store.readAttachment(id: try XCTUnwrap(row.attachmentId)), bytes)
    }

    /// A backup written by an older build carries inline audio too — restore
    /// must spill it before anything is sealed into a half.
    func testRestoringABackupWithInlineAudioSpillsIt() throws {
        let url = work.appendingPathComponent("restored.store")
        let thread = "0123abcd"
        let bytes = audio(0x33, bytes: 300 * 1024)
        var blob = SecureStore.Blob()
        blob.messages = [thread: (0..<10).map { voiceRow("r\($0)", audio: bytes, seconds: 40) }]

        let store = SecureStore(restoring: blob, attachments: [:],
                                password: "restore-pass", at: url)
        store.flush()
        XCTAssertEqual(try Data(contentsOf: url).count, SlotVault.fileBytes)
        let reopened = try SecureStore(unlockingWithPassword: "restore-pass", at: url)
        for row in try XCTUnwrap(reopened.blob.messages[thread]) {
            XCTAssertNil(row.voiceData)
            XCTAssertEqual(reopened.readAttachment(id: try XCTUnwrap(row.attachmentId)), bytes)
        }
    }

    /// Spilling twice must not duplicate sidecars or move anything again.
    func testSpillIsIdempotent() throws {
        let url = work.appendingPathComponent("idem.store")
        let thread = "11223344"
        var blob = SecureStore.Blob()
        blob.messages = [thread: [voiceRow("one", audio: audio(1, bytes: 2048), seconds: 2)]]
        _ = try writeLegacy(blob, to: url)

        let store = try SecureStore(unlockingWithPassword: password, at: url)
        let first = try XCTUnwrap(store.blob.messages[thread]?.first?.attachmentId)
        XCTAssertFalse(store.spillInlineVoiceNotes(), "nothing left to move")
        XCTAssertEqual(store.blob.messages[thread]?.first?.attachmentId, first)
        let ids = try FileManager.default.contentsOfDirectory(atPath: store.attachmentsDir.path)
        XCTAssertEqual(ids.count, 1, "one note, one sidecar")
    }
}
