import XCTest
import SwiftUI
import UIKit
@testable import PyroChat

/// A recording of an open chat has to reach the peer (`⚠ peer may be recording the
/// screen`). The app reads the capture state of the scene the chat is in, not of
/// `UIScreen.main`: a phone with two displays has two screens, and the main one is
/// not always the one the app is on. These drive that state through the trait
/// that carries it and watch what comes out the other end.
@MainActor
final class CaptureAlertTests: XCTestCase {
    private var shown: [UIWindow] = []

    override func tearDown() {
        shown.forEach { $0.isHidden = true }
        shown = []
    }

    private func show<V: View>(_ view: V, capturedAlready: Bool = false) throws -> UIHostingController<V> {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first,
                                  "the test host has no window scene")
        let host = UIHostingController(rootView: view)
        if capturedAlready { host.traitOverrides.sceneCaptureState = .active }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 700)
        window.rootViewController = host
        window.makeKeyAndVisible()
        shown.append(window)
        return host
    }

    private func settle() async { try? await Task.sleep(for: .milliseconds(300)) }

    private func waitUntil(_ timeout: TimeInterval = 10, _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return cond()
    }

    func testEachStartOfARecordingIsReportedOnce() async throws {
        var reports = 0
        let host = try show(Color.clear.onSceneCaptureStart { reports += 1 })
        await settle()
        XCTAssertEqual(reports, 0, "nothing is recording yet")

        host.traitOverrides.sceneCaptureState = .active
        await settle()
        XCTAssertEqual(reports, 1, "a recording that starts is reported")

        host.traitOverrides.sceneCaptureState = .inactive
        await settle()
        XCTAssertEqual(reports, 1, "one that stops is not")

        host.traitOverrides.sceneCaptureState = .active
        await settle()
        XCTAssertEqual(reports, 2, "and the next one is reported again")
    }

    /// A recording that is already running when the chat opens (started on the home
    /// screen, say) is reported as the chat appears; build 40 and everything before it
    /// said nothing in that case.
    func testARecordingAlreadyRunningIsReportedWhenTheViewAppears() async throws {
        var reports = 0
        _ = try show(Color.clear.onSceneCaptureStart { reports += 1 }, capturedAlready: true)
        await settle()
        XCTAssertEqual(reports, 1, "a recording already running when the view appears is reported once")
    }

    /// The second signal is the screen's own notification, read from the screen it
    /// names. In the simulator that screen is not captured, so the notification must
    /// report nothing; a real phone is where it reports (checked on a device, since the
    /// simulator cannot record its screen).
    func testTheScreenNotificationWithoutACaptureReportsNothing() async throws {
        var reports = 0
        let host = try show(Color.clear.onSceneCaptureStart { reports += 1 })
        await settle()
        NotificationCenter.default.post(name: UIScreen.capturedDidChangeNotification, object: host.view.window?.screen)
        await settle()
        XCTAssertEqual(reports, 0, "a screen that is not captured reports nothing")
    }

    // MARK: the whole way: the chat screen, the relay, the other phone

    private func service() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-capture-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "capture-alert-pass", at: dir.appendingPathComponent("store"))
        return (ChatService(store: store), "cap" + UUID().uuidString.prefix(8).lowercased())
    }

    /// Two people who have written to each other, or a skip when there is no relay to meet on.
    private func pair() async throws -> (alice: ChatService, bob: ChatService) {
        let (alice, aliceName) = service(), (bob, bobName) = service()
        do {
            try await alice.register(username: aliceName)
            try await bob.register(username: bobName)
        } catch {
            alice.goOffline(); bob.goOffline()
            throw XCTSkip("no dev relay on 127.0.0.1:7444")
        }
        try await alice.addContact(username: bobName)
        await alice.sendText("hello", to: alice.contacts.first!)
        let met = await waitUntil(20) {
            alice.messages[bob.myHash]?.contains { $0.direction == .outgoing && ($0.status == .delivered || $0.status == .read) } == true
        }
        XCTAssertTrue(met, "the first text was delivered")
        return (alice, bob)
    }

    func testARecordingOfAnOpenChatReachesThePeer() async throws {
        let (alice, bob) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        let host = try show(ChatView(service: alice, contact: alice.contacts.first!))
        await settle()
        func told() -> Bool {
            bob.messages[alice.myHash]?.contains { $0.kind == .system && $0.text.contains("recording the screen") } == true
        }
        XCTAssertFalse(told(), "opening a chat that nobody records tells the peer nothing")

        host.traitOverrides.sceneCaptureState = .active
        let reached = await waitUntil(15) { told() }
        XCTAssertTrue(reached, "bob was not told that alice is recording the chat")
    }
}
