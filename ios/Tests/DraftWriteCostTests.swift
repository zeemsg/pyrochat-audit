import XCTest
@testable import PyroChat

/// docs/DECOY-ACCOUNT.md §1, "What a save costs, measured", and the sentence
/// that section ends with: **a keystroke must not reach the store.**
///
/// Every save writes a whole 2 MiB half whatever changed. That is not
/// avoidable inside this format — AES-GCM ciphertext is exactly as long as its
/// plaintext, so a fixed-size sealed unit is a fixed-size write — so the only
/// lever is how many saves happen at all. Android measured one typed sentence
/// at 19 saves; these tests measure the same sentence on this simulator, both
/// before and after, at the same typing cadence, so the two numbers can be
/// compared honestly rather than asserted from the spec.
@MainActor
final class DraftWriteCostTests: XCTestCase {

    /// A real sentence, typed at a real speed. 110 ms a character is a brisk
    /// but ordinary thumb-typist, and at the composer's 250 ms debounce it is
    /// exactly the "sustained typing" §1 describes.
    static let sentence = "meet me at the usual place at seven, bring the folder"
    static let keystrokeInterval: Duration = .milliseconds(110)

    private var work: URL!
    private var prevSalt: Data?

    override func setUp() async throws {
        work = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-draft-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        prevSalt = KeychainHelper.get("kdf-salt")
        KeychainHelper.set(Data((0..<32).map { UInt8(truncatingIfNeeded: $0 &* 3) }), key: "kdf-salt")
    }

    override func tearDown() async throws {
        SecureStore.onWrite = nil
        if let prevSalt { KeychainHelper.set(prevSalt, key: "kdf-salt") }
        else { KeychainHelper.delete("kdf-salt") }
        try? FileManager.default.removeItem(at: work)
    }

    /// Thread-safe, because `onWrite` fires on the store's save queue.
    private final class Meter: @unchecked Sendable {
        private let lock = NSLock()
        private var writes = 0
        private var bytes = 0
        func note(_ n: Int) { lock.lock(); writes += 1; bytes += n; lock.unlock() }
        var counts: (writes: Int, bytes: Int) {
            lock.lock(); defer { lock.unlock() }; return (writes, bytes)
        }
    }

    /// A store whose container is authored and whose save queue is quiet, so a
    /// measurement starts from nothing in flight.
    ///
    /// The settling wait matters: `init` schedules a 250 ms debounced write and
    /// `flush` satisfies it early, which leaves that timer still armed. A lazy
    /// change parked before it fires RIDES it — correct, and free, since the
    /// write was already going to happen — but it would make a measurement
    /// depend on when the test happened to start.
    private func newStore(_ name: String) async -> SecureStore {
        let store = SecureStore(creatingWithPassword: "draft-password-1",
                                at: work.appendingPathComponent(name))
        store.flush()                       // author the container before measuring
        try? await Task.sleep(for: .milliseconds(400))
        return store
    }

    private func type(_ body: (String) -> Void) async {
        var typed = ""
        for ch in Self.sentence {
            typed.append(ch)
            body(typed)
            try? await Task.sleep(for: Self.keystrokeInterval)
        }
    }

    private static func mib(_ bytes: Int) -> String {
        String(format: "%.1f MiB", Double(bytes) / 1_048_576)
    }

    /// THE measurement. One sentence, twice, on the same machine.
    ///
    /// BEFORE is not a reconstruction: it is literally what `setDraft` did —
    /// a `.prompt` `store.update` per keystroke, behind the same 250 ms
    /// debounce — so the two columns differ in exactly one thing.
    func testOneTypedSentenceBeforeAndAfter() async throws {
        // --- before: a keystroke reaches the store ---
        let before = Meter()
        let old = await newStore("before.store")
        SecureStore.onWrite = { [before] _, n in before.note(n) }
        await type { text in
            old.update { $0.drafts = text.isEmpty ? nil : ["thread": text] }
        }
        old.flush()
        SecureStore.onWrite = nil
        let b = before.counts

        // --- after: the same keystrokes, through the shipping path ---
        let after = Meter()
        let store = await newStore("after.store")
        let service = ChatService(store: store)
        SecureStore.onWrite = { [after] _, n in after.note(n) }
        await type { text in service.setDraft(text, for: "thread") }
        service.flushDeferredWrites()        // the composer losing focus
        try? await Task.sleep(for: .milliseconds(400))
        store.flush()                        // …and, later, the app backgrounding
        SecureStore.onWrite = nil
        let a = after.counts
        service.shutdown()

        print("""

            === docs/DECOY-ACCOUNT.md §1, one typed sentence \
            (\(Self.sentence.count) characters at \(Self.keystrokeInterval)) ===
            before: \(b.writes) saves, \(Self.mib(b.bytes))
            after : \(a.writes) saves, \(Self.mib(a.bytes))

            """)

        // The numbers themselves belong in the report, not in an assertion
        // that would go flaky on a loaded machine. What is asserted is the
        // property §1 makes binding.
        XCTAssertGreaterThan(b.writes, 10,
                             "the before case must actually reproduce the problem, or this proves nothing")
        XCTAssertEqual(a.writes, 1,
                       "§1: a keystroke must not reach the store — the whole sentence costs one flush")
        XCTAssertLessThan(a.bytes * 5, b.bytes,
                          "well over an order of magnitude was the whole point")

        // and the draft actually survived being held
        XCTAssertEqual(service.draft(for: "thread"), Self.sentence)
        let reopened = try SecureStore(unlockingWithPassword: "draft-password-1",
                                       at: work.appendingPathComponent("after.store"))
        XCTAssertEqual(reopened.blob.drafts?["thread"], Self.sentence,
                       "lazy means later, not never")
    }

    /// §1's flush points, one at a time, each proved to be one.
    func testEachFlushPointWritesAndNothingElseDoes() async throws {
        let meter = Meter()
        let store = await newStore("points.store")
        let service = ChatService(store: store)
        SecureStore.onWrite = { [meter] _, n in meter.note(n) }

        service.setDraft("half a thought", for: "t")
        try await Task.sleep(for: .milliseconds(600))    // well past the 250 ms debounce
        XCTAssertEqual(meter.counts.writes, 0, "nothing at all is scheduled by a lazy change")

        service.flushDeferredWrites()                     // focus loss / leaving the screen
        try await Task.sleep(for: .milliseconds(600))
        XCTAssertEqual(meter.counts.writes, 1)

        service.setDraft("half a thought and then some", for: "t")
        store.flush()                                     // backgrounding
        XCTAssertEqual(meter.counts.writes, 2)

        // a flush with nothing held costs nothing
        service.flushDeferredWrites()
        try await Task.sleep(for: .milliseconds(600))
        XCTAssertEqual(meter.counts.writes, 2, "a flush point with nothing to flush is free")
        service.shutdown()
    }

    /// §1: "a sent or received message and any key material are written
    /// promptly, because losing them to a crash is real damage". The split has
    /// to cut the right way round, and this is the half that must NOT be lazy.
    func testAMessageIsStillWrittenPromptlyAndCarriesTheDraftWithIt() async throws {
        let meter = Meter()
        let store = await newStore("prompt.store")
        let service = ChatService(store: store)
        SecureStore.onWrite = { [meter] _, n in meter.note(n) }

        service.setDraft("unsent", for: "t")
        service.appendMessage(ChatMessage(id: UUID().uuidString, direction: .incoming,
                                          kind: .text, text: "a real message",
                                          timestamp: Date(), status: .delivered,
                                          burnAfter: nil, burnStartedAt: nil),
                              thread: "t")
        try await Task.sleep(for: .milliseconds(700))

        XCTAssertEqual(meter.counts.writes, 1, "the message went promptly, on the ordinary debounce")
        let reopened = try SecureStore(unlockingWithPassword: "draft-password-1",
                                       at: work.appendingPathComponent("prompt.store"))
        XCTAssertEqual(reopened.blob.messages["t"]?.count, 1)
        XCTAssertEqual(reopened.blob.drafts?["t"], "unsent",
                       "and a lazy change riding a prompt save costs nothing extra")
        service.shutdown()
    }

    /// The other high-frequency writer §1 names: READ STATE. Opening a thread
    /// with a screenful of unread rows marks each one read as it becomes
    /// visible, and each mark used to be its own 2 MiB save.
    func testOpeningAThreadWithFortyUnreadRows() async throws {
        let thread = String(repeating: "c", count: 64)
        let rows = (0..<40).map { i in
            ChatMessage(id: "m-\(i)", direction: .incoming, kind: .text, text: "row \(i)",
                        timestamp: Date(), status: .delivered, burnAfter: nil, burnStartedAt: nil)
        }

        // --- before: a `.prompt` save per row marked read ---
        let before = Meter()
        let old = await newStore("read-before.store")
        old.update { $0.messages = [thread: rows] }
        old.flush()
        SecureStore.onWrite = { [before] _, n in before.note(n) }
        var log = rows
        for i in log.indices {
            log[i].status = .read
            let snapshot = [thread: log]
            old.update { $0.messages = snapshot }
            try? await Task.sleep(for: .milliseconds(60))   // a scroll, row by row
        }
        old.flush()
        SecureStore.onWrite = nil
        let b = before.counts

        // --- after: the shipping path ---
        let after = Meter()
        let store = await newStore("read-after.store")
        store.update { $0.messages = [thread: rows] }
        store.flush()
        let service = ChatService(store: store)
        SecureStore.onWrite = { [after] _, n in after.note(n) }
        for row in rows {
            service.noteRowVisible(messageID: row.id, thread: thread)
            try? await Task.sleep(for: .milliseconds(60))
        }
        service.flushDeferredWrites()
        try? await Task.sleep(for: .milliseconds(400))
        store.flush()
        SecureStore.onWrite = nil
        let a = after.counts

        print("\n=== §1, opening a thread with 40 unread rows ===\nbefore: \(b.writes) saves, \(Self.mib(b.bytes))\nafter : \(a.writes) saves, \(Self.mib(a.bytes))\n")

        XCTAssertGreaterThan(b.writes, 5)
        XCTAssertLessThanOrEqual(a.writes, 2)
        XCTAssertEqual(service.messages[thread]?.filter { $0.status == .read }.count, 40,
                       "and every row really is marked read")
        let reopened = try SecureStore(unlockingWithPassword: "draft-password-1",
                                       at: work.appendingPathComponent("read-after.store"))
        XCTAssertEqual(reopened.blob.messages[thread]?.filter { $0.status == .read }.count, 40,
                       "…on disk, once the flush point came")
        service.shutdown()
    }
}
