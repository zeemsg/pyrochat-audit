import XCTest
import Network
@testable import PyroChat

/// A connection-losing network, simulated: a TCP gate in front of the relay on
/// 127.0.0.1:7444 that can go silent. Silent means the sockets stay open and
/// bytes stop moving, so the OS reports nothing, which is what a dropped
/// network looks like to the app. Start the relay first.
final class ReconnectTests: XCTestCase {
    final class TCPGate: @unchecked Sendable {
        private let queue = DispatchQueue(label: "reconnect-tests.gate")
        private let listener: NWListener
        private var silent = false
        private var accepted = 0
        private var open: [NWConnection] = []

        init() throws {
            listener = try NWListener(using: .tcp, on: .any)
            let ready = DispatchSemaphore(value: 0)
            listener.stateUpdateHandler = { if case .ready = $0 { ready.signal() } }
            listener.newConnectionHandler = { [weak self] in self?.accept($0) }
            listener.start(queue: queue)
            guard ready.wait(timeout: .now() + 5) == .success else {
                throw XCTSkip("the test gate could not listen")
            }
        }

        var url: String { "ws://127.0.0.1:\(listener.port!.rawValue)/ws" }
        var acceptedCount: Int { queue.sync { accepted } }
        func setSilent(_ on: Bool) { queue.sync { silent = on } }

        /// A silent gate still accepts: TCP connects, then nothing ever answers.
        private func accept(_ inbound: NWConnection) {
            accepted += 1
            let outbound = NWConnection(host: "127.0.0.1", port: 7444, using: .tcp)
            open += [inbound, outbound]
            inbound.start(queue: queue)
            outbound.start(queue: queue)
            pump(from: inbound, to: outbound)
            pump(from: outbound, to: inbound)
        }

        private func pump(from: NWConnection, to: NWConnection) {
            from.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, done, error in
                guard let self else { return }
                if let data, !self.silent { to.send(content: data, completion: .contentProcessed { _ in }) }
                if done || error != nil {
                    if !self.silent { to.cancel() }
                    return
                }
                self.pump(from: from, to: to)
            }
        }

        func close() {
            listener.cancel()
            queue.sync { open.forEach { $0.cancel() } }
        }
    }

    private var gate: TCPGate!

    override func setUpWithError() throws {
        gate = try TCPGate()
    }

    override func tearDown() {
        gate.close()
    }

    @MainActor
    private func makeService(_ timing: ChatService.LinkTiming) -> ChatService {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-reconnect-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "test-password-123",
                                at: dir.appendingPathComponent("store"))
        store.update { $0.settings.relayURL = self.gate.url }
        let service = ChatService(store: store)
        service.linkTiming = timing
        return service
    }

    @MainActor
    private func waitUntil(_ seconds: TimeInterval, _ cond: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return cond()
    }

    private func username() -> String { "rc" + UUID().uuidString.prefix(8).lowercased() }

    /// The reported bug: a login with no reply used to end at LINK DOWN until
    /// the user tapped [retry].
    @MainActor
    func testALoginThatGetsNoReplyRetriesByItself() async throws {
        let service = makeService(.init(loginDeadline: .seconds(1)))
        defer { service.shutdown() }
        try await service.register(username: username())
        service.goOffline()

        gate.setSilent(true)
        let before = gate.acceptedCount
        do {
            try await service.goOnline()
            XCTFail("a login with no reply must time out")
        } catch {
            XCTAssertEqual("\(error)", "timeout")
        }
        XCTAssertEqual(service.connState, .offline)
        let retried = await waitUntil(6) { self.gate.acceptedCount > before + 1 }
        XCTAssertTrue(retried, "no further attempt was made without a tap")

        gate.setSilent(false)
        let back = await waitUntil(20) { service.connState == .online }
        XCTAssertTrue(back, "the link never came back by itself")
    }

    /// The app used to sit at LINK UP on a socket nothing was answering.
    @MainActor
    func testASilentSocketIsNoticedAndReplaced() async throws {
        let service = makeService(.init(keepalive: .seconds(1), pongTimeout: .seconds(2),
                                        loginDeadline: .seconds(2)))
        defer { service.shutdown() }
        try await service.register(username: username())
        XCTAssertEqual(service.connState, .online)

        gate.setSilent(true)
        let noticed = await waitUntil(8) { service.connState != .online }
        XCTAssertTrue(noticed, "a socket with no replies still reads as online")

        gate.setSilent(false)
        let back = await waitUntil(20) { service.connState == .online }
        XCTAssertTrue(back, "the link never came back by itself")
    }

    @MainActor
    func testAnAnsweredPingLeavesTheLinkAlone() async throws {
        let service = makeService(.init(keepalive: .seconds(1), pongTimeout: .seconds(1)))
        defer { service.shutdown() }
        try await service.register(username: username())
        let socket = service.relay
        try await Task.sleep(for: .seconds(5))
        XCTAssertEqual(service.connState, .online)
        XCTAssertTrue(service.relay === socket, "a healthy link was replaced")
    }

    @MainActor
    func testTheNetworkComingBackSkipsTheBackoff() async throws {
        let service = makeService(.init(loginDeadline: .seconds(1)))
        defer { service.shutdown() }
        try await service.register(username: username())
        service.goOffline()

        gate.setSilent(true)
        try? await service.goOnline()
        try await Task.sleep(for: .seconds(6))   // the backoff has climbed past 4 s

        gate.setSilent(false)
        service.noteNetworkChanged()
        let back = await waitUntil(3) { service.connState == .online }
        XCTAssertTrue(back, "the network coming back must reconnect at once")
    }

    @MainActor
    func testGoingOfflineStopsTheRetries() async throws {
        let service = makeService(.init(loginDeadline: .seconds(1)))
        defer { service.shutdown() }
        try await service.register(username: username())
        service.goOffline()

        gate.setSilent(true)
        try? await service.goOnline()
        service.goOffline()
        let before = gate.acceptedCount
        try await Task.sleep(for: .seconds(4))
        XCTAssertEqual(gate.acceptedCount, before, "an explicit goOffline must stop retrying")
        XCTAssertEqual(service.connState, .offline)

        service.noteNetworkChanged()
        try await Task.sleep(for: .seconds(1))
        XCTAssertEqual(gate.acceptedCount, before, "a network change must not undo an explicit goOffline")
    }

    func testOnlyAChangeThatLeavesAUsableNetworkCounts() {
        typealias S = NetworkWatch.Snapshot
        let wifi = S(usable: true, interfaces: [.wifi])
        let cell = S(usable: true, interfaces: [.cellular])
        let none = S(usable: false, interfaces: [])
        XCTAssertFalse(NetworkWatch.worthActingOn(previous: nil, current: wifi), "first update is the baseline")
        XCTAssertFalse(NetworkWatch.worthActingOn(previous: wifi, current: wifi))
        XCTAssertFalse(NetworkWatch.worthActingOn(previous: wifi, current: none), "losing the network: the socket reports it")
        XCTAssertTrue(NetworkWatch.worthActingOn(previous: none, current: wifi))
        XCTAssertTrue(NetworkWatch.worthActingOn(previous: wifi, current: cell))
    }
}
