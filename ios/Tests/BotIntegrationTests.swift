import XCTest
@testable import PyroChat

/// pyrobot (bot/index.mjs) must be running against the dev relay:
///   RELAY_URL=ws://127.0.0.1:7444/ws BOT_USERNAME=pyrobot node bot/index.mjs
/// Proves a phone can add the bot, get real receipts (DELIVERED → READ),
/// receive the tour including a self-destructing message, and echo.
@MainActor
final class BotIntegrationTests: XCTestCase {

    private func makeService() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-bot-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "bot-test-pass",
                                at: dir.appendingPathComponent("store"))
        // PYRO_TEST_RELAY lets the same test exercise the PRODUCTION bot
        if let relay = ProcessInfo.processInfo.environment["PYRO_TEST_RELAY"], !relay.isEmpty {
            store.update { $0.settings.relayURL = relay }
        }
        return (ChatService(store: store), "bt" + UUID().uuidString.prefix(8).lowercased())
    }

    private func waitUntil(_ timeout: TimeInterval, _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(200))
        }
        return cond()
    }

    func testPyrobotToursANewUser() async throws {
        let (me, name) = makeService()
        try await me.register(username: name)
        try await me.addContact(username: "pyrobot")
        guard let bot = me.contacts.first(where: { $0.name == "pyrobot" }) else { return XCTFail("add failed") }

        await me.sendText("hi there", to: bot)
        // the bot marks our message delivered then READ (it sends real receipts)
        let read = await waitUntil(15) {
            me.messages[bot.hash]?.first { $0.direction == .outgoing }?.status == .read
        }
        XCTAssertTrue(read, "bot receipts must upgrade our message to READ")

        // the welcome tour: 5 messages, one of them self-destructing
        let toured = await waitUntil(30) {
            (me.messages[bot.hash] ?? []).filter { $0.direction == .incoming && $0.kind == .text }.count >= 5
        }
        XCTAssertTrue(toured, "tour must arrive")
        let incoming = (me.messages[bot.hash] ?? []).filter { $0.direction == .incoming }
        XCTAssertTrue(incoming.contains { $0.burnAfter == 20 }, "tour includes a 20 s burn message")
        XCTAssertTrue(incoming.contains { $0.text.contains("pyrobot") }, "bot introduces itself")
        XCTAssertEqual(bot.name, "pyrobot")

        // echo mode after the tour
        await me.sendText("echo me", to: bot)
        let echoed = await waitUntil(15) {
            (me.messages[bot.hash] ?? []).contains { $0.direction == .incoming && $0.text == "you said: echo me" }
        }
        XCTAssertTrue(echoed, "bot echoes after the tour")
        me.goOffline()
    }
}
