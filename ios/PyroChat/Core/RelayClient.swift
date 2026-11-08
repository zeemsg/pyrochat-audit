import Foundation
import Network
import os

/// What `RelayClient.barrier` counts: the pings written to one socket and the pongs its
/// consumer has routed. The relay takes a connection's frames in the order they were written
/// and answers every ping with one pong (docs/PROTOCOL.md §6), so the pong to the nth ping is
/// its word on everything written before that ping.
final class PingLedger: @unchecked Sendable {
    /// The ping was written and nothing came back in time.
    struct NoAnswer: Error, CustomStringConvertible {
        var description: String { "no answer from the relay" }
    }

    private struct State {
        var written = 0
        var routed = 0
        var waiters: [Int: (number: Int, done: CheckedContinuation<Void, Error>)] = [:]
        var tokens = 0
        var closed = false
    }
    private let state = OSAllocatedUnfairLock(uncheckedState: State())

    /// A ping is being written: its number, counted from 1.
    func written() -> Int {
        state.withLockUnchecked { $0.written += 1; return $0.written }
    }

    /// One pong has been routed, and with it everything that arrived before it.
    func routed() {
        let ready = state.withLockUnchecked { s -> [CheckedContinuation<Void, Error>] in
            s.routed += 1
            let done = s.waiters.filter { $0.value.number <= s.routed }
            for token in done.keys { s.waiters[token] = nil }
            return done.values.map(\.done)
        }
        for waiter in ready { waiter.resume() }
    }

    /// Returns once pong number `number` has been routed. Throws `NoAnswer` when `timeout`
    /// passes first, and the loss of the socket when it ends first.
    func wait(for number: Int, timeout: Duration) async throws {
        try await withCheckedThrowingContinuation { (waiter: CheckedContinuation<Void, Error>) in
            let (now, token): (Result<Void, Error>?, Int) = state.withLockUnchecked { s in
                if s.routed >= number { return (.success(()), 0) }
                if s.closed { return (.failure(URLError(.networkConnectionLost)), 0) }
                s.tokens += 1
                s.waiters[s.tokens] = (number, waiter)
                return (nil, s.tokens)
            }
            if let now {
                waiter.resume(with: now)
                return
            }
            Task { [weak self] in
                try? await Task.sleep(for: timeout)
                let late = self?.state.withLockUnchecked { $0.waiters.removeValue(forKey: token) }
                late?.done.resume(throwing: NoAnswer())
            }
        }
    }

    /// The socket is gone: nothing more will be answered on it.
    func close() {
        let lost = state.withLockUnchecked { s -> [CheckedContinuation<Void, Error>] in
            s.closed = true
            let all = s.waiters.values.map(\.done)
            s.waiters = [:]
            return all
        }
        for waiter in lost { waiter.resume(throwing: URLError(.networkConnectionLost)) }
    }
}

/// WebSocket transport to the relay. Owns the socket lifecycle, exposes an
/// async event stream, and reconnects with backoff while logged in.
final class RelayClient: NSObject {
    enum Event {
        case connected
        case disconnected
        case frame(Wire.In)
        case binary(Data)
    }

    private var task: URLSessionWebSocketTask?
    private var session: URLSession!
    private let url: URL
    private var continuation: AsyncStream<Event>.Continuation?
    private let received = OSAllocatedUnfairLock(initialState: 0)

    /// Frames this socket has received, counted as they arrive and not as
    /// ChatService gets to them: a reply queued behind a slow decrypt still
    /// proves the link is alive.
    var framesReceived: Int { received.withLock { $0 } }

    /// Pings written to this socket and pongs routed from it (see `barrier`).
    private var pings = PingLedger()

    /// Events surface here; ChatService consumes the stream. Created eagerly
    /// (buffered) so a `.connected` yielded during `connect()` is never lost
    /// to a consumer task that hasn't started iterating yet.
    let events: AsyncStream<Event>

    /// `socksProxy` is "host:port" — all relay traffic (including the
    /// websocket) tunnels through it. Invalid strings are ignored (direct).
    init(url: URL, socksProxy: String? = nil) {
        self.url = url
        let (stream, cont) = AsyncStream.makeStream(of: Event.self)
        self.events = stream
        super.init()
        self.continuation = cont
        self.session = Self.makeSession(socksProxy: socksProxy)
    }

    /// THE ONE place a network session is built. Every HTTP/WS call the
    /// app makes (relay, mirror, billing) must go through a session from
    /// here so a configured SOCKS proxy is never bypassed.
    static func makeSession(socksProxy: String?) -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        // One fixed handshake for every install (docs/SEALED-SENDER.md §5.5). Left alone, iOS
        // sends the app build, the OS build and the phone's language list on every request;
        // on a quiet relay that is enough to tell which anonymous socket belongs to which
        // logged-in one. The second fixed header is set per request (`fixed(_:)`): two
        // headers handed over in one dictionary go out in an order that changes from one
        // connection to the next.
        config.httpAdditionalHeaders = ["User-Agent": Self.userAgent]
        if let proxy = parseProxy(socksProxy),
           let port = NWEndpoint.Port(rawValue: UInt16(proxy.port)) {
            // the modern proxy API — the legacy SOCKS connectionProxyDictionary
            // is silently ignored for websockets
            config.proxyConfigurations = [ProxyConfiguration(
                socksv5Proxy: .hostPort(host: .init(proxy.host), port: port))]
        }
        return URLSession(configuration: config, delegate: nil, delegateQueue: nil)
    }

    /// What every request says about its sender: nothing that differs between two phones.
    static let userAgent = "PyroChat"
    static let acceptLanguage = "en"

    /// A request for a session from `makeSession`, with the rest of the fixed handshake.
    static func fixed(_ request: URLRequest) -> URLRequest {
        var r = request
        r.setValue(acceptLanguage, forHTTPHeaderField: "Accept-Language")
        return r
    }

    static func parseProxy(_ raw: String?) -> (host: String, port: Int)? {
        guard let raw = raw?.trimmingCharacters(in: .whitespaces), !raw.isEmpty,
              let colon = raw.lastIndex(of: ":"),
              let port = Int(raw[raw.index(after: colon)...]), (1...65535).contains(port)
        else { return nil }
        let host = String(raw[..<colon])
        return host.isEmpty ? nil : (host, port)
    }

    func connect() {
        task?.cancel()
        pings.close()
        pings = PingLedger()
        let t = session.webSocketTask(with: Self.fixed(URLRequest(url: url)))
        task = t
        t.resume()
        continuation?.yield(.connected)
        receiveLoop(on: t)
    }

    func disconnect() {
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        pings.close()
    }

    private func receiveLoop(on t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            guard let self, self.task === t else { return }
            if case .success = result { self.received.withLock { $0 &+= 1 } }
            switch result {
            case .success(.string(let text)):
                if let frame = try? JSONDecoder().decode(Wire.In.self, from: Data(text.utf8)) {
                    self.continuation?.yield(.frame(frame))
                }
                self.receiveLoop(on: t)
            case .success(.data(let data)):
                self.continuation?.yield(.binary(data))
                self.receiveLoop(on: t)
            case .success:
                self.receiveLoop(on: t)
            case .failure:
                self.task = nil
                self.pings.close()
                self.continuation?.yield(.disconnected)
            }
        }
    }

    func send(_ frame: Wire.Out) async throws {
        if frame.t == "ping" { _ = pings.written() }       // every ping is counted: `barrier` waits by number
        try await write(frame)
    }

    private func write(_ frame: Wire.Out) async throws {
        guard let task else { throw URLError(.notConnectedToInternet) }
        let data = try JSONEncoder().encode(frame)
        try await task.send(.string(String(decoding: data, as: UTF8.self)))
    }

    /// Whoever consumes `events` calls this as it reaches a pong, after it has routed every
    /// frame that arrived before it.
    func pongRouted() { pings.routed() }

    /// Returns once the relay has handled everything written to this socket before the call:
    /// taken it, or refused it with an `error` that the consumer of `events` has routed by
    /// then. A ping is written now, and its own pong is waited for (docs/PROTOCOL.md §6).
    /// Throws when the socket ends first, or when `timeout` passes after the ping was written
    /// with no answer (`PingLedger.NoAnswer`).
    func barrier(timeout: Duration) async throws {
        let ledger = pings
        let number = ledger.written()
        try await write(Wire.Out(t: "ping"))
        try await ledger.wait(for: number, timeout: timeout)
    }

    func sendBinary(_ data: Data) async throws {
        guard let task else { throw URLError(.notConnectedToInternet) }
        try await task.send(.data(data))
    }
}

/// Tells ChatService when the phone's network came back or switched, so the
/// link recovers at once instead of waiting out the backoff. It reads the OS's
/// view of its own interfaces and contacts nobody.
final class NetworkWatch: @unchecked Sendable {
    struct Snapshot: Equatable {
        let usable: Bool
        let interfaces: [NWInterface.InterfaceType]
    }

    /// The first update is only the baseline. After it, any change that leaves
    /// a usable network counts; losing the network does not, because the
    /// socket reports that itself.
    static func worthActingOn(previous: Snapshot?, current: Snapshot) -> Bool {
        guard let previous, current.usable else { return false }
        return previous != current
    }

    private let monitor = NWPathMonitor()
    private var last: Snapshot?   // touched on the monitor's queue only

    init(onChange: @escaping @Sendable () -> Void) {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let now = Snapshot(usable: path.status == .satisfied,
                               interfaces: path.availableInterfaces.map(\.type))
            let act = Self.worthActingOn(previous: self.last, current: now)
            self.last = now
            if act { onChange() }
        }
        monitor.start(queue: DispatchQueue(label: "ca.pyrochat.network-watch"))
    }

    func stop() { monitor.cancel() }
}
