import XCTest
@testable import PyroChat

@MainActor
final class AutoLockTests: XCTestCase {

    func testShouldLockDecision() {
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        // never backgrounded / feature off -> never lock
        XCTAssertFalse(AutoLock.shouldLock(backgroundedAt: nil, timeoutMinutes: 5, now: t0))
        XCTAssertFalse(AutoLock.shouldLock(backgroundedAt: t0, timeoutMinutes: nil,
                                           now: t0.addingTimeInterval(9999)))
        // under / at / over the threshold
        XCTAssertFalse(AutoLock.shouldLock(backgroundedAt: t0, timeoutMinutes: 5,
                                           now: t0.addingTimeInterval(299)))
        XCTAssertTrue(AutoLock.shouldLock(backgroundedAt: t0, timeoutMinutes: 5,
                                          now: t0.addingTimeInterval(300)))
        XCTAssertTrue(AutoLock.shouldLock(backgroundedAt: t0, timeoutMinutes: 1,
                                          now: t0.addingTimeInterval(61)))
        // clock moved backwards -> fail CLOSED
        XCTAssertTrue(AutoLock.shouldLock(backgroundedAt: t0, timeoutMinutes: 5,
                                          now: t0.addingTimeInterval(-10)))
    }

    func testAutoLockSettingDecodeAndSemantics() throws {
        // absent -> secure default 5m
        let s = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertEqual(s.autoLockAfterMinutes, 5)
        // explicit off
        var off = AppSettings(); off.autoLockMinutes = 0
        XCTAssertNil(off.autoLockAfterMinutes)
        // explicit value
        var m15 = AppSettings(); m15.autoLockMinutes = 15
        XCTAssertEqual(m15.autoLockAfterMinutes, 15)
    }

    func testShutdownReleasesServiceAndInvalidatesTimer() async throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-lock-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "lock-pass-123",
                                at: dir.appendingPathComponent("store"))
        var service: ChatService? = ChatService(store: store)
        weak var weakService = service
        weak var weakTimer = service?.burnTimer
        XCTAssertNotNil(weakTimer, "sweeper timer must be running pre-shutdown")

        service?.shutdown()
        XCTAssertNil(service?.burnTimer, "shutdown must drop the timer")
        service = nil
        // let the runloop settle any pending deallocations
        try? await Task.sleep(for: .milliseconds(200))
        XCTAssertNil(weakService,
                     "no retain cycle: dropping the last ref must deinit the service")
        XCTAssertNil(weakTimer, "invalidated timer must be released by the RunLoop")
    }

    func testShutdownFailsPendingOutgoing() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-lock2-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "lock-pass-123",
                                at: dir.appendingPathComponent("store"))
        let service = ChatService(store: store)
        let t = String(repeating: "e", count: 64)
        service.messages[t] = [ChatMessage(id: "p1", direction: .outgoing, kind: .text,
                                           text: "limbo", timestamp: Date(), status: .pending,
                                           burnAfter: nil, burnStartedAt: nil)]
        service.shutdown()
        XCTAssertEqual(service.messages[t]?.first?.status, .failed,
                       "a pending send at lock time is an unconfirmed guess — surface it")
    }
}
