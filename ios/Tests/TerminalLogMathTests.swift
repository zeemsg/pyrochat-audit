import XCTest
@testable import PyroChat

final class TerminalLogMathTests: XCTestCase {
    func testGrown() {
        XCTAssertEqual(TerminalLogMath.grown(60, total: 300), 100)
        XCTAssertEqual(TerminalLogMath.grown(280, total: 300), 300)
        XCTAssertEqual(TerminalLogMath.grown(260, total: 300), 300)
    }

    func testNearTop() {
        // 700 pt viewport: page once the window's top is within 1050 pt above it
        XCTAssertTrue(TerminalLogMath.nearTop(topEdge: -1000, viewportHeight: 700))
        XCTAssertFalse(TerminalLogMath.nearTop(topEdge: -1100, viewportHeight: 700))
        XCTAssertTrue(TerminalLogMath.nearTop(topEdge: 5, viewportHeight: 700))     // on screen
        // unmeasured viewport falls back to 300 pt
        XCTAssertTrue(TerminalLogMath.nearTop(topEdge: -400, viewportHeight: 0))
        XCTAssertFalse(TerminalLogMath.nearTop(topEdge: -500, viewportHeight: 0))
        // an absent trigger reports "infinitely far" and never pages
        XCTAssertFalse(TerminalLogMath.nearTop(topEdge: -.greatestFiniteMagnitude, viewportHeight: 700))
    }

    func testAppended() {
        XCTAssertEqual(TerminalLogMath.appended(60, appended: 3, atBottom: true), 60)
        XCTAssertEqual(TerminalLogMath.appended(60, appended: 3, atBottom: false), 63)
    }

    func testNearestSurvivor() {
        let ordered = ["a", "b", "c", "d", "e"]
        XCTAssertEqual(TerminalLogMath.nearestSurvivor(
            of: "c", in: ordered, surviving: ["a", "b", "d", "e"]), "d")
        XCTAssertEqual(TerminalLogMath.nearestSurvivor(
            of: "c", in: ordered, surviving: ["a", "b"]), "b")
        XCTAssertNil(TerminalLogMath.nearestSurvivor(of: "c", in: ordered, surviving: []))
        XCTAssertEqual(TerminalLogMath.nearestSurvivor(
            of: "zz", in: ordered, surviving: ["a", "d"]), "d")
    }

    @MainActor
    func testStatusWordMapping() {
        XCTAssertEqual(ChatView.statusWord(.read, showRead: false), "DELIVERED")
        XCTAssertEqual(ChatView.statusWord(.read, showRead: true), "READ")
        XCTAssertEqual(ChatView.statusWord(.failed, showRead: true), "FAILED")
        XCTAssertEqual(ChatView.statusWord(.sent, showRead: false), "SENT")
        XCTAssertEqual(ChatView.statusWord(.delivered, showRead: false), "DELIVERED")
        XCTAssertEqual(ChatView.statusWord(.pending, showRead: false), "\u{2026}")
    }

    func testReceiptRankMonotone() {
        XCTAssertLessThan(ChatMessage.Status.pending.receiptRank,
                          ChatMessage.Status.sent.receiptRank)
        XCTAssertEqual(ChatMessage.Status.sent.receiptRank,
                       ChatMessage.Status.failed.receiptRank)
        XCTAssertLessThan(ChatMessage.Status.sent.receiptRank,
                          ChatMessage.Status.delivered.receiptRank)
        XCTAssertLessThan(ChatMessage.Status.delivered.receiptRank,
                          ChatMessage.Status.read.receiptRank)
    }
}
