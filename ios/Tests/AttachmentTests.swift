import XCTest
import UniformTypeIdentifiers
import ImageIO
@testable import PyroChat

/// Image & file attachments: gating, normalization, store, and the full
/// E2E round-trip through the live relay (127.0.0.1:7444).
final class AttachmentTests: XCTestCase {

    // MARK: pure units

    func testVideoUTTypeRefusal() {
        XCTAssertNotNil(AttachmentGate.refusal(for: .mpeg4Movie))
        XCTAssertNotNil(AttachmentGate.refusal(for: .quickTimeMovie))
        XCTAssertNotNil(AttachmentGate.refusal(for: .avi))
        XCTAssertNotNil(AttachmentGate.refusal(for: .movie))
        XCTAssertNotNil(AttachmentGate.refusal(for: .video))
        XCTAssertNil(AttachmentGate.refusal(for: .pdf))
        XCTAssertNil(AttachmentGate.refusal(for: .png))
        XCTAssertNil(AttachmentGate.refusal(for: .zip))
        XCTAssertNil(AttachmentGate.refusal(for: nil))
        XCTAssertNotNil(AttachmentGate.refusal(forFileSize: 13 * 1024 * 1024))
        XCTAssertNil(AttachmentGate.refusal(forFileSize: 11 * 1024 * 1024))
    }

    func testFilenameSanitization() {
        XCTAssertFalse(sanitizeFilename("../../etc/passwd").contains("/"))
        XCTAssertEqual(sanitizeFilename(""), "file")
        XCTAssertEqual(sanitizeFilename("..."), "file")
        let long = String(repeating: "a", count: 200) + ".pdf"
        let cleaned = sanitizeFilename(long)
        XCTAssertLessThanOrEqual(cleaned.count, 64)
        XCTAssertTrue(cleaned.hasSuffix(".pdf"))
        XCTAssertEqual(sanitizeFilename("a\u{0}b\u{1}c.txt"), "abc.txt")
    }

    @MainActor
    func testImageNormalizerStripsMetadataAndCapsDimension() throws {
        // build a 3000px JPEG carrying a GPS EXIF dict
        let big = UIGraphicsImageRenderer(size: CGSize(width: 3000, height: 1500))
            .image { ctx in
                UIColor.systemGreen.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 3000, height: 1500))
            }
        let src = try XCTUnwrap(big.jpegData(compressionQuality: 0.9))
        let withGps = NSMutableData()
        let dest = try XCTUnwrap(CGImageDestinationCreateWithData(
            withGps, UTType.jpeg.identifier as CFString, 1, nil))
        let source = try XCTUnwrap(CGImageSourceCreateWithData(src as CFData, nil))
        let gps: [CFString: Any] = [kCGImagePropertyGPSLatitude: 45.5,
                                    kCGImagePropertyGPSLongitude: -73.6]
        CGImageDestinationAddImageFromSource(dest, source, 0,
            [kCGImagePropertyGPSDictionary: gps] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(dest))

        let norm = try XCTUnwrap(ImageNormalizer.normalize(withGps as Data))
        XCTAssertLessThanOrEqual(max(norm.width, norm.height), 2048)

        let out = try XCTUnwrap(CGImageSourceCreateWithData(norm.jpeg as CFData, nil))
        let props = CGImageSourceCopyPropertiesAtIndex(out, 0, nil) as? [CFString: Any]
        XCTAssertNil(props?[kCGImagePropertyGPSDictionary],
                     "re-encode must strip GPS EXIF")
    }

    func testAttachmentStoreSealsAndDeletes() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-att-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "att-pass-123",
                                at: dir.appendingPathComponent("store"))
        let id = UUID().uuidString.lowercased()
        let plaintext = Data("attachment secret bytes".utf8)
        try store.writeAttachment(id: id, data: plaintext)

        // sealed at rest
        let onDisk = try Data(contentsOf: store.attachmentURL(id: id))
        XCTAssertNotEqual(onDisk, plaintext)
        XCTAssertFalse(String(decoding: onDisk, as: UTF8.self).contains("secret"))

        XCTAssertEqual(store.readAttachment(id: id), plaintext)
        store.deleteAttachment(id: id)
        XCTAssertNil(store.readAttachment(id: id))

        // path traversal ids are refused outright
        XCTAssertThrowsError(try store.writeAttachment(id: "../evil", data: plaintext))
        XCTAssertNil(store.readAttachment(id: "../evil"))
    }

    func testObliterateSweepsPlaintextTmp() throws {
        let tmp = FileManager.default.temporaryDirectory
        let dir = tmp.appendingPathComponent("preview-test-\(UUID().uuidString.prefix(6))")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("leak".utf8).write(to: dir.appendingPathComponent("f"))
        SecureStore.sweepPlaintextTmp()
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.path),
                       "preview-* tmp dirs must be swept")
    }

    // MARK: live-relay round trips

    @MainActor
    private func makeService() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-svc-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "svc-test-pass-123",
                                at: dir.appendingPathComponent("store"))
        let username = "att" + UUID().uuidString.prefix(8).lowercased()
        return (ChatService(store: store), username)
    }

    @MainActor
    private func waitUntil(_ timeout: TimeInterval = 10,
                           _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return cond()
    }

    @MainActor
    func testImageRoundTripByteIdentical() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        // With sealed sender the first message to someone new is text only, and an
        // attachment goes once that text is DELIVERED (docs/SEALED-SENDER.md §5.2).
        // Without sealed sender the extra message changes nothing.
        await alice.sendText("hello", to: alice.contacts.first!)
        _ = await waitUntil { alice.messages[bob.myHash]?.first?.status == .delivered }

        var jpeg = Data(count: 300_000)
        jpeg.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 300_000, $0.baseAddress!) }
        await alice.sendImage(jpeg: jpeg, width: 800, height: 600, to: alice.contacts.first!)

        let arrived = await waitUntil {
            bob.messages[alice.myHash]?.contains { $0.kind == .image } == true
        }
        XCTAssertTrue(arrived)
        let received = bob.messages[alice.myHash]!.first { $0.kind == .image }!
        XCTAssertEqual(bob.attachmentData(for: received), jpeg,
                       "image bytes must survive encrypt/chunk/reassemble/decrypt")
        XCTAssertEqual(received.imageWidth, 800)
        XCTAssertEqual(received.imageHeight, 600)
        XCTAssertEqual(received.fileMime, "image/jpeg")

        // sender reaches DELIVERED via the E2E receipt
        let delivered = await waitUntil {
            alice.messages[bob.myHash]?.contains {
                $0.kind == .image && $0.status.receiptRank >= ChatMessage.Status.delivered.receiptRank
            } == true
        }
        XCTAssertTrue(delivered, "blob delivered-receipt must advance sender status")

        alice.goOffline(); bob.goOffline()
    }

    @MainActor
    func testFileRoundTripByteIdentical() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        // With sealed sender the first message to someone new is text only, and an
        // attachment goes once that text is DELIVERED (docs/SEALED-SENDER.md §5.2).
        // Without sealed sender the extra message changes nothing.
        await alice.sendText("hello", to: alice.contacts.first!)
        _ = await waitUntil { alice.messages[bob.myHash]?.first?.status == .delivered }

        var payload = Data(count: 5 * 1024 * 1024)
        payload.withUnsafeMutableBytes {
            _ = SecRandomCopyBytes(kSecRandomDefault, 5 * 1024 * 1024, $0.baseAddress!)
        }
        await alice.sendFile(data: payload, filename: "trip.bin",
                             mime: "application/octet-stream", to: alice.contacts.first!)

        let arrived = await waitUntil(20) {
            bob.messages[alice.myHash]?.contains { $0.kind == .file } == true
        }
        XCTAssertTrue(arrived)
        let received = bob.messages[alice.myHash]!.first { $0.kind == .file }!
        XCTAssertEqual(received.fileName, "trip.bin")
        XCTAssertEqual(received.fileBytes, 5 * 1024 * 1024)
        XCTAssertEqual(bob.attachmentData(for: received), payload)

        alice.goOffline(); bob.goOffline()
    }

    @MainActor
    func testOversizeFileRejectedLocally() async throws {
        let (alice, aliceName) = makeService()
        try await alice.register(username: aliceName)
        let peer = Contact(hash: String(repeating: "b", count: 64), name: "x", addedAt: Date())
        await alice.sendFile(data: Data(count: 13 * 1024 * 1024), filename: "big.bin",
                             mime: "application/octet-stream", to: peer)
        XCTAssertEqual(alice.lastError, "file_too_large")
        XCTAssertNil(alice.messages[peer.hash]?.first, "nothing must be appended or sent")
        alice.goOffline()
    }

    @MainActor
    func testBurnDeletesAttachmentFileBothSides() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        // With sealed sender the first message to someone new is text only, and an
        // attachment goes once that text is DELIVERED (docs/SEALED-SENDER.md §5.2).
        // Without sealed sender the extra message changes nothing.
        await alice.sendText("hello", to: alice.contacts.first!)
        _ = await waitUntil { alice.messages[bob.myHash]?.first?.status == .delivered }
        alice.store.update { $0.settings.defaultBurnSeconds = 2 }

        await alice.sendFile(data: Data("burn me".utf8), filename: "doomed.txt",
                             mime: "text/plain", to: alice.contacts.first!)
        let arrived = await waitUntil {
            bob.messages[alice.myHash]?.contains { $0.kind == .file } == true
        }
        XCTAssertTrue(arrived)

        let aliceMsg = alice.messages[bob.myHash]!.first { $0.kind == .file }!
        let bobMsg = bob.messages[alice.myHash]!.first { $0.kind == .file }!
        let aliceFile = alice.store.attachmentURL(id: aliceMsg.attachmentId!)
        let bobFile = bob.store.attachmentURL(id: bobMsg.attachmentId!)
        XCTAssertTrue(FileManager.default.fileExists(atPath: aliceFile.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: bobFile.path))

        // SYNCED BURN: countdown starts only when bob actually views it
        bob.noteRowVisible(messageID: bobMsg.id, thread: alice.myHash)

        let gone = await waitUntil(8) {
            let msgsGone = bob.messages[alice.myHash]?.contains { $0.kind == .file } != true
                && alice.messages[bob.myHash]?.contains { $0.kind == .file } != true
            let filesGone = !FileManager.default.fileExists(atPath: aliceFile.path)
                && !FileManager.default.fileExists(atPath: bobFile.path)
            return msgsGone && filesGone
        }
        XCTAssertTrue(gone, "burn must remove messages AND sidecar files on both devices")

        alice.goOffline(); bob.goOffline()
    }

    /// A socket drop in the middle of an incoming attachment. The relay
    /// replays the blob manifest and every chunk on reconnect; the manifest
    /// re-decrypts as a libsignal DUPLICATE (its message key is spent). Acking
    /// that duplicate told the relay to drop the whole blob route — chunks
    /// included — and the attachment was lost without a row or an error.
    @MainActor
    func testReplayedManifestKeepsThePartialTransferAlive() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        let bobContact = try XCTUnwrap(alice.contacts.first)
        // a live session, so the manifest below decrypts on bob's side
        await alice.sendText("hello", to: bobContact)
        var ok = await waitUntil { bob.messages[alice.myHash]?.contains { $0.text == "hello" } == true }
        XCTAssertTrue(ok)
        // With sealed sender an attachment waits until the first text is DELIVERED
        // (docs/SEALED-SENDER.md §5.2); without it the wait changes nothing.
        ok = await waitUntil { alice.messages[bob.myHash]?.first?.status == .delivered }
        XCTAssertTrue(ok)

        // bob loses the socket; alice sends an image into bob's relay queue.
        // Big enough that the relay's pump is still paused on backpressure
        // when bob's ack for the replayed manifest lands — the window in
        // which the chunks behind it were dropped.
        bob.goOffline()
        let size = 6 * 1024 * 1024
        var jpeg = Data(count: size)
        jpeg.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, size, $0.baseAddress!) }
        await alice.sendImage(jpeg: jpeg, width: 640, height: 480, to: bobContact)
        let row = try XCTUnwrap(alice.messages[bob.myHash]?.first { $0.kind == .image })
        ok = await waitUntil { alice.frameCapture.frame(for: row.id) != nil }
        XCTAssertTrue(ok, "the manifest envelope is captured as it is sent")
        let manifest = try XCTUnwrap(alice.frameCapture.frame(for: row.id))

        // bob's app had already decrypted the manifest before the drop: hand it the frame
        // once, as the relay delivers it. A sealed manifest (`deliver`) arrives without `from`.
        await bob.routeForTest(Wire.In(t: "recv", id: manifest.id,
                                       from: manifest.t == "deliver" ? nil : alice.myHash,
                                       kind: manifest.kind, body: manifest.body,
                                       ts: Int64(Date().timeIntervalSince1970 * 1000)))
        XCTAssertNotNil(bob.rxTransfers[manifest.id], "the manifest opened a partial transfer")

        // reconnect: the relay replays the manifest (now a DUPLICATE) + every chunk
        try await bob.goOnline()
        ok = await waitUntil(30) {
            bob.messages[alice.myHash]?.contains { $0.direction == .incoming && $0.kind == .image } == true
        }
        XCTAssertTrue(ok, "the replayed manifest must not ack away the chunks behind it")
        let image = try XCTUnwrap(bob.messages[alice.myHash]?.first { $0.kind == .image })
        XCTAssertEqual(bob.attachmentData(for: image), jpeg)
        alice.goOffline(); bob.goOffline()
    }
}

import UIKit
