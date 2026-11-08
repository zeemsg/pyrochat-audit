import XCTest
@testable import PyroChat

/// Operator notices — the relay's `<pyrochat>` channel. Live tests drive the
/// dev relay on 127.0.0.1:7444 and post through its admin API (Basic
/// op:dev-admin-pw, like the admin page). Every notice posted here is
/// deleted in tearDown so later runs — and the Android suite sharing this
/// relay — never see it. The board is shared, so assertions never assume
/// it is otherwise empty.
@MainActor
final class NoticeTests: XCTestCase {
    private static let admin = URL(string: "http://127.0.0.1:7444/admin/notices")!
    private static let auth = "Basic " + Data("op:dev-admin-pw".utf8).base64EncodedString()

    private var posted: [String] = []
    private var services: [ChatService] = []

    override func tearDown() async throws {
        for s in services { s.goOffline() }
        services = []
        for id in posted { try? await Self.deleteNotice(id) }
        posted = []
    }

    // MARK: harness

    private func makeService(defaults: UserDefaults? = nil) -> (ChatService, String, UserDefaults) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-notice-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "notice-test-pass",
                                at: dir.appendingPathComponent("store"))
        let d = defaults ?? UserDefaults(suiteName: "pyro-notice-test-\(UUID().uuidString)")!
        let username = "nt" + UUID().uuidString.prefix(8).lowercased()
        let svc = ChatService(store: store, defaults: d)
        services.append(svc)
        return (svc, username, d)
    }

    private func waitUntil(_ timeout: TimeInterval = 8,
                           _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return cond()
    }

    /// A `notice` frame as the relay would send it (epoch seconds).
    private func frame(id: String?, text: String, url: String? = nil,
                       ts: Int64 = 1_800_000_000, expires: Int64? = 1_900_000_000) -> Wire.In {
        Wire.In(t: "notice", id: id, ts: ts, text: text, url: url, expires: expires)
    }

    private struct PostedNotice: Decodable { let id: String }

    /// POST /admin/notices → the new notice's id.
    private static func postNotice(text: String, url: String? = nil, ttl: Int = 600) async throws -> String {
        var req = URLRequest(url: admin)
        req.httpMethod = "POST"
        req.setValue(auth, forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var body: [String: Any] = ["text": text, "ttl": ttl]
        if let url { body["url"] = url }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? -1
        guard status == 200 else {
            throw NSError(domain: "admin", code: status,
                          userInfo: [NSLocalizedDescriptionKey: String(decoding: data, as: UTF8.self)])
        }
        return try JSONDecoder().decode(PostedNotice.self, from: data).id
    }

    /// DELETE /admin/notices/{id}
    private static func deleteNotice(_ id: String) async throws {
        var req = URLRequest(url: admin.appendingPathComponent(id))
        req.httpMethod = "DELETE"
        req.setValue(auth, forHTTPHeaderField: "Authorization")
        _ = try await URLSession.shared.data(for: req)
    }

    // MARK: (a) live delivery

    func testPostedNoticeReachesAConnectedClientLive() async throws {
        let (svc, name, _) = makeService()
        try await svc.register(username: name)
        let text = "live notice \(UUID().uuidString.prefix(6))"
        let id = try await Self.postNotice(text: text, url: "https://pyrochat.ca/status")
        posted.append(id)

        let arrived = await waitUntil { svc.notices.contains { $0.id == id } }
        XCTAssertTrue(arrived, "a posted notice is broadcast to everyone connected")
        let n = try XCTUnwrap(svc.notices.first { $0.id == id })
        XCTAssertEqual(n.text, text)
        XCTAssertEqual(n.url, "https://pyrochat.ca/status")
        XCTAssertGreaterThan(n.expires, Date())
        XCTAssertLessThanOrEqual(abs(n.ts.timeIntervalSinceNow), 60, "ts is epoch seconds, not ms")
        // a fresh device has opened the channel for nothing: every active
        // notice is unseen (== 1 on an otherwise empty board)
        XCTAssertGreaterThanOrEqual(svc.unseenNoticeCount, 1)
        XCTAssertEqual(svc.unseenNoticeCount, svc.notices.count)
        // oldest first
        XCTAssertTrue(zip(svc.notices, svc.notices.dropFirst()).allSatisfy { $0.ts <= $1.ts })
    }

    // MARK: (b) replay at login

    func testNoticePostedBeforeLoginArrivesAtLogin() async throws {
        let id = try await Self.postNotice(text: "posted before login")
        posted.append(id)
        let (svc, name, _) = makeService()
        XCTAssertTrue(svc.notices.isEmpty, "nothing persisted on a fresh device")
        try await svc.register(username: name)
        let arrived = await waitUntil { svc.notices.contains { $0.id == id } }
        XCTAssertTrue(arrived, "login replays every active notice after ready + prekey_count")
        XCTAssertEqual(svc.notices.filter { $0.id == id }.count, 1, "deduped by id")
    }

    // MARK: (c) deletion

    func testDeletedNoticeDisappearsLiveAndStaysGone() async throws {
        let (svc, name, defaults) = makeService()
        try await svc.register(username: name)
        let id = try await Self.postNotice(text: "soon deleted")
        posted.append(id)
        let arrived = await waitUntil { svc.notices.contains { $0.id == id } }
        XCTAssertTrue(arrived)

        try await Self.deleteNotice(id)
        posted.removeAll { $0 == id }
        let gone = await waitUntil { !svc.notices.contains { $0.id == id } }
        XCTAssertTrue(gone, "notice_gone removes it from the board")
        // and the persisted board agrees — a relaunch cannot resurrect it
        let (again, _, _) = makeService(defaults: defaults)
        XCTAssertFalse(again.notices.contains { $0.id == id })
    }

    // MARK: (d) seen state persists outside the store

    func testSeenStatePersistsAcrossServicesOnTheSameDefaults() throws {
        let (a, _, defaults) = makeService()
        XCTAssertTrue(a.ingestNotice(frame(id: "n1", text: "first", ts: 1_800_000_000)))
        XCTAssertTrue(a.ingestNotice(frame(id: "n2", text: "second\nsecond line", ts: 1_800_000_001)))
        XCTAssertEqual(a.unseenNoticeCount, 2)
        a.markNoticesSeen()
        XCTAssertEqual(a.unseenNoticeCount, 0)

        // "relaunch": a new service over the same UserDefaults
        let (b, _, _) = makeService(defaults: defaults)
        XCTAssertEqual(b.notices.map(\.id), ["n1", "n2"], "board persisted, oldest first")
        XCTAssertEqual(b.notices[1].preview, "second second line")
        XCTAssertEqual(b.unseenNoticeCount, 0, "seen ids persisted")
        // a new notice is unseen; the old two stay seen
        XCTAssertTrue(b.ingestNotice(frame(id: "n3", text: "third", ts: 1_800_000_002)))
        XCTAssertEqual(b.unseenNoticeCount, 1)
        // the login replay of a known notice changes nothing
        XCTAssertFalse(b.ingestNotice(frame(id: "n1", text: "first", ts: 1_800_000_000)))
        XCTAssertEqual(b.unseenNoticeCount, 1)
        // removing a seen notice forgets its id too
        b.removeNotice(id: "n1")
        XCTAssertEqual(b.notices.map(\.id), ["n2", "n3"])

        // the agreed keys, and nothing landed in the encrypted store
        XCTAssertNotNil(defaults.string(forKey: NoticeStore.noticesKey))
        XCTAssertEqual(NoticeStore.noticesKey, "pyro.notices")
        XCTAssertEqual(NoticeStore.seenKey, "pyro.notices.seen")
        XCTAssertEqual(defaults.stringArray(forKey: NoticeStore.seenKey), ["n2"])
        let blobJSON = String(decoding: try JSONEncoder().encode(b.store.blob), as: UTF8.self)
        XCTAssertFalse(blobJSON.contains("\"notices\""), "the store format is untouched")
        XCTAssertFalse(blobJSON.contains("second second line"))
    }

    // MARK: (e) malformed frames

    func testMalformedNoticeFramesAreIgnored() {
        let (svc, _, defaults) = makeService()
        let now = Date()
        let nowS = Int64(now.timeIntervalSince1970)
        let future = nowS + 3600
        XCTAssertFalse(svc.ingestNotice(frame(id: "e1", text: "", expires: future), now: now), "empty text")
        XCTAssertFalse(svc.ingestNotice(frame(id: "e2", text: "  \n\t", expires: future), now: now), "blank text")
        XCTAssertFalse(svc.ingestNotice(frame(id: "e3", text: "x", url: "http://pyrochat.ca/", expires: future), now: now), "http url")
        XCTAssertFalse(svc.ingestNotice(frame(id: "e4", text: "x", url: "javascript:alert(1)", expires: future), now: now), "non-https url")
        XCTAssertFalse(svc.ingestNotice(frame(id: "e5", text: "x", url: "https://pyro chat.ca/", expires: future), now: now), "whitespace in url")
        XCTAssertFalse(svc.ingestNotice(frame(id: "e6", text: "x", url: "https://", expires: future), now: now), "url without host")
        XCTAssertFalse(svc.ingestNotice(frame(id: "e7", text: "x", expires: nowS), now: now), "expires == now")
        XCTAssertFalse(svc.ingestNotice(frame(id: "e8", text: "x", expires: nowS - 1), now: now), "already expired")
        XCTAssertFalse(svc.ingestNotice(frame(id: "e9", text: "x", expires: nil), now: now), "missing expires")
        XCTAssertFalse(svc.ingestNotice(frame(id: nil, text: "x", expires: future), now: now), "missing id")
        XCTAssertFalse(svc.ingestNotice(frame(id: "  ", text: "x", expires: future), now: now), "blank id")
        XCTAssertFalse(svc.ingestNotice(Wire.In(t: "recv", id: "e10", text: "x", expires: future), now: now), "wrong frame type")
        XCTAssertTrue(svc.notices.isEmpty)
        XCTAssertEqual(svc.unseenNoticeCount, 0)
        XCTAssertNil(defaults.string(forKey: NoticeStore.noticesKey))

        // well-formed still lands; url optional; empty url == no url
        XCTAssertTrue(svc.ingestNotice(frame(id: "ok1", text: "  fine  ", url: "", expires: future), now: now))
        XCTAssertEqual(svc.notices.map(\.id), ["ok1"])
        XCTAssertEqual(svc.notices.first?.text, "fine")
        XCTAssertNil(svc.notices.first?.url)
        // over-long text is clamped to the relay's cap, not dropped
        XCTAssertTrue(svc.ingestNotice(frame(id: "ok2", text: String(repeating: "y", count: 900),
                                             ts: 1_800_000_001, expires: future), now: now))
        XCTAssertEqual(svc.notices.last?.text.count, Notice.maxTextLength)
        XCTAssertEqual(svc.unseenNoticeCount, 2)
    }

    func testExpiredNoticesAreSweptByTheTimer() async {
        let (svc, _, defaults) = makeService()
        let soon = Int64(Date().timeIntervalSince1970) + 1
        XCTAssertTrue(svc.ingestNotice(frame(id: "blink", text: "brief", expires: soon)))
        XCTAssertEqual(svc.notices.count, 1)
        XCTAssertEqual(svc.unseenNoticeCount, 1)
        let swept = await waitUntil(5) { svc.notices.isEmpty }
        XCTAssertTrue(swept, "the 1 s sweeper drops it once `expires` passes")
        XCTAssertEqual(svc.unseenNoticeCount, 0)
        XCTAssertNil(defaults.string(forKey: NoticeStore.noticesKey))
        // …and a persisted-but-expired notice never loads at all
        NoticeStore.saveNotices([Notice(id: "old", text: "stale", url: nil,
                                        ts: Date(timeIntervalSinceNow: -7200),
                                        expires: Date(timeIntervalSinceNow: -3600))], to: defaults)
        let (again, _, _) = makeService(defaults: defaults)
        XCTAssertTrue(again.notices.isEmpty)
    }

    func testEveryWipePathClearsTheNoticeKeys() {
        let d = UserDefaults.standard
        d.set("[]", forKey: NoticeStore.noticesKey)
        d.set(["x"], forKey: NoticeStore.seenKey)
        SecureStore.obliterate()          // total wipe, duress pin, dead-man all end here
        XCTAssertNil(d.object(forKey: NoticeStore.noticesKey))
        XCTAssertNil(d.object(forKey: NoticeStore.seenKey))
    }

    // MARK: (f) the one store-format addition

    func testHideNoticesRoundTripsAndIsOmittedWhenNil() throws {
        var s = AppSettings()
        XCTAssertNil(s.hideNotices)
        XCTAssertTrue(s.noticesShown, "nil = shown")
        let bare = String(decoding: try JSONEncoder().encode(s), as: UTF8.self)
        XCTAssertFalse(bare.contains("hideNotices"), "nil is omitted — untouched stores stay byte-identical")

        s.hideNotices = true
        let json = String(decoding: try JSONEncoder().encode(s), as: UTF8.self)
        XCTAssertTrue(json.contains(#""hideNotices":true"#), json)
        let back = try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
        XCTAssertEqual(back.hideNotices, true)
        XCTAssertFalse(back.noticesShown)

        // decode-safety: stores written before the key existed, and Android's
        // explicit false
        let legacy = try JSONDecoder().decode(AppSettings.self,
            from: Data(#"{"theme":"amber","soundsEnabled":false}"#.utf8))
        XCTAssertNil(legacy.hideNotices)
        XCTAssertTrue(legacy.noticesShown)
        let off = try JSONDecoder().decode(AppSettings.self, from: Data(#"{"hideNotices":false}"#.utf8))
        XCTAssertEqual(off.hideNotices, false)
        XCTAssertTrue(off.noticesShown)

        // and through the whole blob
        var blob = SecureStore.Blob()
        blob.settings.hideNotices = true
        let decoded = try JSONDecoder().decode(SecureStore.Blob.self, from: JSONEncoder().encode(blob))
        XCTAssertEqual(decoded.settings.hideNotices, true)
    }
}
