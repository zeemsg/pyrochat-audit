import XCTest
@testable import PyroChat

/// Milestone gate for the Android port (`android/core-service` InteropWithIosTest and
/// InteropMediaWithIosTest): the REAL iOS ChatService registers the username named by
/// `PYRO_INTEROP_USER` on the local relay (127.0.0.1:7444) and, for up to 120 s, echoes every
/// text it receives back to its sender (prefix `echo: `) after marking it read — so the Android
/// side observes DELIVERED → READ receipts and a Whisper-ratchet reply from a genuine iOS peer.
/// Media (M2): every image / file / voice note received is re-sent to its sender as the SAME
/// kind with the SAME bytes and metadata (manifest → chunks → reassembly → sidecar / voiceData,
/// then the reverse path), so the Android side can assert a byte-identical echo.
///
/// A NO-OP (XCTSkip) unless the env var is set — from xcodebuild pass it as
/// `TEST_RUNNER_PYRO_INTEROP_USER=<name>`.
///
/// With sealed sender on the relay this is the live matrix of docs/SEALED-SENDER.md §9 (X2):
/// `PYRO_INTEROP_LEGACY=1` makes this side a build from before sealed sender, and
/// `PYRO_INTEROP_EXPECT=sealed` or `legacy` asserts which way every echo left this phone.
@MainActor
final class InteropEchoTests: XCTestCase {

    func testEchoesEveryTextFromTheAndroidPeer() async throws {
        guard let name = ProcessInfo.processInfo.environment["PYRO_INTEROP_USER"], !name.isEmpty else {
            throw XCTSkip("PYRO_INTEROP_USER not set — the iOS↔Android interop gate is opt-in")
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-interop-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "interop-pass-123",
                                at: dir.appendingPathComponent("store"))
        // the Android gate asserts READ lands — reciprocity toggle on
        store.update { $0.settings.sendReadReceipts = true }
        let svc = ChatService(store: store)
        let env = ProcessInfo.processInfo.environment
        svc.legacyWireOnly = env["PYRO_INTEROP_LEGACY"] == "1"
        try await svc.register(username: name)
        print("INTEROP: iOS registered <\(name)> hash \(svc.myHash.prefix(12))… — echoing for up to 120 s"
              + (svc.legacyWireOnly ? " as a build from before sealed sender" : "") + ", sealed sender: \(svc.sealedStatus)")

        var echoed = Set<String>()
        let deadline = Date().addingTimeInterval(120)
        var lastActivity = Date()
        while Date() < deadline {
            for (thread, log) in svc.messages {
                guard let contact = svc.contacts.first(where: { $0.hash == thread }) else { continue }
                for row in log where row.direction == .incoming && !echoed.contains(row.id) {
                    switch row.kind {
                    case .text:
                        echoed.insert(row.id)
                        lastActivity = Date()
                        // the read trigger: marks read locally + READ receipt (toggle on) + burn start
                        svc.noteRowVisible(messageID: row.id, thread: thread)
                        await svc.sendText("echo: " + row.text, to: contact)
                        print("INTEROP: echoed \(row.id) from <\(contact.name)>: \(row.text)")
                    case .image:
                        guard let bytes = svc.attachmentData(for: row) else { continue }
                        echoed.insert(row.id)
                        lastActivity = Date()
                        svc.noteRowVisible(messageID: row.id, thread: thread)
                        await svc.sendImage(jpeg: bytes, width: row.imageWidth ?? 0,
                                            height: row.imageHeight ?? 0, to: contact)
                        print("INTEROP: echoed image \(row.id) from <\(contact.name)>: \(bytes.count) B \(row.imageWidth ?? 0)x\(row.imageHeight ?? 0)")
                    case .file:
                        guard let bytes = svc.attachmentData(for: row) else { continue }
                        echoed.insert(row.id)
                        lastActivity = Date()
                        svc.noteRowVisible(messageID: row.id, thread: thread)
                        await svc.sendFile(data: bytes, filename: row.fileName ?? "file",
                                           mime: row.fileMime ?? "application/octet-stream", to: contact)
                        print("INTEROP: echoed file \(row.id) from <\(contact.name)>: \(row.fileName ?? "file") \(bytes.count) B \(row.fileMime ?? "-")")
                    case .voice:
                        guard let bytes = svc.voiceData(for: row) else { continue }
                        echoed.insert(row.id)
                        lastActivity = Date()
                        svc.noteRowVisible(messageID: row.id, thread: thread)
                        await svc.sendVoice(bytes, duration: row.voiceDuration ?? 0, to: contact)
                        print("INTEROP: echoed voice \(row.id) from <\(contact.name)>: \(bytes.count) B \(row.voiceDuration ?? 0) s")
                    case .system:
                        continue
                    }
                }
            }
            // done once the peer has gone quiet after at least one exchange
            if !echoed.isEmpty, Date().timeIntervalSince(lastActivity) > 30 { break }
            try? await Task.sleep(for: .milliseconds(200))
        }
        print("INTEROP: finished — \(echoed.count) row(s) echoed (texts, images, files, voice notes)")
        XCTAssertGreaterThanOrEqual(echoed.count, 1, "the Android peer must have delivered at least one row")
        // which way each echo left this phone: `deliver` is sealed, `send` names its sender
        let sent = svc.messages.values.flatMap { $0 }.filter { $0.direction == .outgoing && $0.kind != .system }
        let frames = sent.compactMap { svc.frameCapture.frame(for: $0.id)?.t }
        print("INTEROP: \(frames.filter { $0 == "deliver" }.count) echo(es) left sealed, \(frames.filter { $0 == "send" }.count) the old way")
        if let expect = env["PYRO_INTEROP_EXPECT"], !expect.isEmpty {
            XCTAssertEqual(frames.count, sent.count, "every echo's frame is on record")
            XCTAssertTrue(frames.allSatisfy { $0 == (expect == "sealed" ? "deliver" : "send") },
                          "expected every echo to leave \(expect): \(frames)")
        }
        svc.goOffline()
    }
}
