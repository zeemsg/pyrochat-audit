import XCTest
@testable import PyroChat

/// Reality-check at 1000+ messages: store write/reload cost, unread-count
/// cost, and the render window staying bounded. No relay needed.
@MainActor
final class ScalePerfTests: XCTestCase {

    private func seededService(messages count: Int) -> (ChatService, String, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-scale-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("store")
        let store = SecureStore(creatingWithPassword: "scale-pass-123", at: url)
        let svc = ChatService(store: store)
        let peer = String(repeating: "c", count: 64)
        var log: [ChatMessage] = []
        log.reserveCapacity(count)
        for i in 0..<count {
            log.append(ChatMessage(
                id: UUID().uuidString.lowercased(),
                direction: i % 2 == 0 ? .incoming : .outgoing,
                kind: .text,
                text: "message \(i): the quick brown fox jumps over the lazy dog 🔥",
                timestamp: Date().addingTimeInterval(Double(i)),
                status: i % 2 == 0 ? (i % 5 == 0 ? .read : .delivered) : .sent,
                burnAfter: nil, burnStartedAt: nil))
        }
        svc.messages[peer] = log
        store.update { $0.messages = svc.messages }
        return (svc, peer, url)
    }

    func testThousandMessageStoreAndBadgeCosts() throws {
        let (svc, peer, url) = seededService(messages: 1000)

        // (a) full encrypted flush of a 1000-message store
        var t = Date()
        svc.store.flush()
        let flushMs = Date().timeIntervalSince(t) * 1000

        // (b) unread badge count over 1000 rows (HomeView row cost)
        t = Date()
        var unread = 0
        for _ in 0..<100 { unread = svc.unreadCount(for: peer) }
        let unreadUs = Date().timeIntervalSince(t) * 1000 / 100 * 1000

        // (c) cold unlock: decrypt + decode the whole store
        t = Date()
        let reopened = try SecureStore(unlockingWithPassword: "scale-pass-123", at: url)
        let unlockMs = Date().timeIntervalSince(t) * 1000
        XCTAssertEqual(reopened.blob.messages[peer]?.count, 1000)

        // (d) render window stays bounded regardless of history size
        XCTAssertEqual(TerminalLogMath.initialWindow, 60)
        XCTAssertLessThanOrEqual(TerminalLogMath.trimThreshold, 120)

        // (e) one incremental append on top of 1000 (the hot path)
        t = Date()
        svc.messages[peer]?.append(ChatMessage(
            id: UUID().uuidString, direction: .incoming, kind: .text,
            text: "one more", timestamp: Date(), status: .delivered,
            burnAfter: nil, burnStartedAt: nil))
        let snapshot = svc.messages
        svc.store.update { $0.messages = snapshot }
        let appendMs = Date().timeIntervalSince(t) * 1000   // snapshot cost only (write is debounced+bg)

        print("SCALE1000 flush=\(Int(flushMs))ms unlock=\(Int(unlockMs))ms unread=\(Int(unreadUs))µs append-snapshot=\(String(format: "%.2f", appendMs))ms unreadCount=\(unread)")
        XCTAssertLessThan(flushMs, 2000, "encrypted flush of 1000 msgs must stay well under 2s")
        XCTAssertLessThan(unlockMs, 2000)
        XCTAssertLessThan(appendMs, 50, "main-thread append snapshot must be near-instant")
    }

    func testFiveThousandMessagesStillBounded() throws {
        let (svc, peer, _) = seededService(messages: 5000)
        let t = Date()
        svc.store.flush()
        let flushMs = Date().timeIntervalSince(t) * 1000
        let unread = svc.unreadCount(for: peer)
        print("SCALE5000 flush=\(Int(flushMs))ms unread=\(unread)")
        XCTAssertLessThan(flushMs, 6000)
    }
}

extension ScalePerfTests {
    func testTotalUnreadSumsAcrossThreads() {
        let (svc, peer, _) = seededService(messages: 10)   // 5 incoming, 1 read → 4 unread
        let other = String(repeating: "d", count: 64)
        svc.messages[other] = [ChatMessage(
            id: "x", direction: .incoming, kind: .text, text: "hi",
            timestamp: Date(), status: .delivered, burnAfter: nil, burnStartedAt: nil)]
        XCTAssertEqual(svc.totalUnread, svc.unreadCount(for: peer) + 1)
    }
}
