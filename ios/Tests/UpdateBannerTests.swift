import XCTest
@testable import PyroChat

/// PROTOCOL.md "Latest versions (update banner)": the relay's `versions`
/// frame → `ChatService.updateAvailable`. Pure-rule tests over
/// `UpdateCheck` / `LatestVersion`, ChatService ingest + persistence +
/// clear (the notices pattern: an isolated UserDefaults suite, nothing in
/// the encrypted store, every wipe path clears it), and one live test
/// against the dev relay's `/admin/versions` that skips while the relay
/// running on :7444 predates the endpoint. Live changes are restored in
/// tearDown so the Android suite sharing this relay never sees them.
@MainActor
final class UpdateBannerTests: XCTestCase {
    /// `PYRO_VERSIONS_RELAY=host:port` points the live test at another dev
    /// relay (a fresh jar on a spare port while :7444 predates the endpoint).
    private static let relayHost = ProcessInfo.processInfo.environment["PYRO_VERSIONS_RELAY"] ?? "127.0.0.1:7444"
    private static let admin = URL(string: "http://\(relayHost)/admin/versions")!
    private static let relayURL = "ws://\(relayHost)/ws"
    /// `PYRO_VERSIONS_ADMIN_PW` overrides the dev relay's admin password (a
    /// throwaway relay started by the live gate uses its own).
    private static let adminPassword = ProcessInfo.processInfo.environment["PYRO_VERSIONS_ADMIN_PW"].flatMap { $0.isEmpty ? nil : $0 } ?? "dev-admin-pw"
    private static let auth = "Basic " + Data("op:\(adminPassword)".utf8).base64EncodedString()

    private var services: [ChatService] = []
    /// The relay's `/admin/versions` body before this test touched it.
    private var restore: [String: Any]?

    override func tearDown() async throws {
        for s in services { s.goOffline() }
        services = []
        if let body = restore { try? await Self.putVersions(body) }
        restore = nil
    }

    // MARK: harness

    /// A service that believes it runs build `running` (the real install is
    /// build `UpdateCheck.runningBuild`; tests must not depend on it).
    private func makeService(defaults: UserDefaults? = nil, running: Int = 23) -> (ChatService, String, UserDefaults) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-update-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "update-test-pass",
                                at: dir.appendingPathComponent("store"))
        let d = defaults ?? UserDefaults(suiteName: "pyro-update-test-\(UUID().uuidString)")!
        let username = "ut" + UUID().uuidString.prefix(8).lowercased()
        store.update { $0.settings.relayURL = Self.relayURL }
        let svc = ChatService(store: store, defaults: d, runningBuild: running)
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

    /// A frame exactly as the relay would send it, through the real decoder.
    private func frame(_ json: String) throws -> Wire.In {
        try JSONDecoder().decode(Wire.In.self, from: Data(json.utf8))
    }

    /// A `versions` frame with only an iOS entry.
    private func ios(build: Int? = 24, name: String? = "0.1.0", url: String? = nil) -> Wire.In {
        Wire.In(t: "versions", ios: Wire.VersionJSON(build: build, name: name, url: url))
    }

    /// One admin request on a connection of its own. The relay's admin handler closes
    /// the connection after every reply; a shared session reuses it for the next request
    /// and fails with -1005 ("the network connection was lost") about one run in three.
    private static func adminData(for request: URLRequest) async throws -> (Data, URLResponse) {
        var req = request
        req.setValue("close", forHTTPHeaderField: "Connection")
        var last: Error = URLError(.unknown)
        for _ in 0..<3 {
            let session = URLSession(configuration: .ephemeral)
            defer { session.invalidateAndCancel() }
            do { return try await session.data(for: req) } catch { last = error }
        }
        throw last
    }

    /// GET /admin/versions → the current body, or nil when the running relay
    /// predates the endpoint. A bare status check is not enough: the admin
    /// handler answers any unknown `/admin/*` path with the dashboard HTML
    /// and HTTP 200, so the body must parse as a JSON object.
    private static func getVersions() async throws -> [String: Any]? {
        var req = URLRequest(url: admin)
        req.setValue(auth, forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, resp) = try await adminData(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200,
              let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return body
    }

    /// PUT /admin/versions — body = the frame without `t`.
    private static func putVersions(_ body: [String: Any]) async throws {
        var req = URLRequest(url: admin)
        req.httpMethod = "PUT"
        req.setValue(auth, forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var clean = body
        clean.removeValue(forKey: "t")
        req.httpBody = try JSONSerialization.data(withJSONObject: clean)
        let (data, resp) = try await adminData(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? -1
        guard (200..<300).contains(status) else {
            throw NSError(domain: "admin", code: status,
                          userInfo: [NSLocalizedDescriptionKey: String(decoding: data, as: UTF8.self)])
        }
    }

    // MARK: (a) the pure rule

    func testRule_GreaterShowsEqualAndLowerAndAbsentClear() throws {
        let v = LatestVersion(build: 24, name: "0.1.0", url: nil)
        XCTAssertEqual(UpdateCheck.read(ios(build: 24)), .latest(v))
        XCTAssertEqual(UpdateCheck.updateAvailable(latest: v, running: 23), v, "greater")
        XCTAssertNil(UpdateCheck.updateAvailable(latest: v, running: 24), "equal")
        XCTAssertNil(UpdateCheck.updateAvailable(latest: v, running: 25), "lower")
        XCTAssertNil(UpdateCheck.updateAvailable(latest: nil, running: 1), "absent")
        XCTAssertEqual(UpdateCheck.updateAvailable(latest: v, running: 1), v)

        // the relay always sends the frame, even empty — that CLEARS
        XCTAssertEqual(UpdateCheck.read(Wire.In(t: "versions")), .absent)
        XCTAssertEqual(UpdateCheck.read(try frame(#"{"t":"versions"}"#)), .absent)
        XCTAssertEqual(UpdateCheck.read(try frame(#"{"t":"versions","ios":null}"#)), .absent, "null platform = cleared")
        // an android-only frame is "absent" for us
        XCTAssertEqual(UpdateCheck.read(try frame(#"{"t":"versions","android":{"build":9,"name":"0.1.0"}}"#)), .absent)
        // …and the same frame read as android sees its entry
        XCTAssertEqual(UpdateCheck.read(try frame(#"{"t":"versions","android":{"build":9,"name":"0.1.0"}}"#), platform: .android),
                       .latest(LatestVersion(build: 9, name: "0.1.0", url: nil)))
        // the full shape from the spec, through the decoder
        let both = try frame(#"{"t":"versions","ios":{"build":24,"name":"0.1.0","url":"https://pyrochat.ca/ios"},"android":{"build":9,"name":"0.1.0","url":"https://pyrochat.ca/android"}}"#)
        XCTAssertEqual(UpdateCheck.read(both), .latest(LatestVersion(build: 24, name: "0.1.0", url: "https://pyrochat.ca/ios")))
    }

    func testRule_MalformedFramesAreIgnoredAsAWhole() throws {
        let bad: [(String, String)] = [
            ("build is a string", #"{"t":"versions","ios":{"build":"24","name":"0.1.0"}}"#),
            ("build 0", #"{"t":"versions","ios":{"build":0,"name":"0.1.0"}}"#),
            ("build negative", #"{"t":"versions","ios":{"build":-3,"name":"0.1.0"}}"#),
            ("build fractional", #"{"t":"versions","ios":{"build":24.5,"name":"0.1.0"}}"#),
            ("build boolean", #"{"t":"versions","ios":{"build":true,"name":"0.1.0"}}"#),
            ("build missing", #"{"t":"versions","ios":{"name":"0.1.0"}}"#),
            ("name missing", #"{"t":"versions","ios":{"build":24}}"#),
            ("name not a string", #"{"t":"versions","ios":{"build":24,"name":5}}"#),
            ("name blank", #"{"t":"versions","ios":{"build":24,"name":"  "}}"#),
            ("entry is a string", #"{"t":"versions","ios":"24"}"#),
            ("entry is a number", #"{"t":"versions","ios":24}"#),
            ("entry is an array", #"{"t":"versions","ios":[24]}"#),
            ("entry is empty", #"{"t":"versions","ios":{}}"#),
            ("other platform malformed voids the frame", #"{"t":"versions","ios":{"build":24,"name":"0.1.0"},"android":{"build":"x"}}"#),
        ]
        for (why, json) in bad {
            let f: Wire.In
            do { f = try frame(json) } catch { XCTFail("\(why): the frame decode itself must never fail — \(error)"); continue }
            XCTAssertEqual(UpdateCheck.read(f), .malformed, why)
        }
        XCTAssertEqual(UpdateCheck.read(Wire.In(t: "recv", ios: Wire.VersionJSON(build: 24, name: "x"))), .malformed, "wrong frame type")
        XCTAssertNil(LatestVersion.parse(Wire.VersionJSON()), "all-nil entry")
    }

    func testRule_URLIsHttpsOnlyOtherwiseDroppedNotFatal() throws {
        func url(_ raw: String?) -> String?? {
            guard case .latest(let v) = UpdateCheck.read(ios(url: raw)) else { return nil }
            return .some(v.url)
        }
        XCTAssertEqual(url("https://pyrochat.ca/ios"), .some("https://pyrochat.ca/ios"))
        XCTAssertEqual(url("  https://testflight.apple.com/join/abc  "), .some("https://testflight.apple.com/join/abc"), "trimmed")
        XCTAssertEqual(url(nil), .some(nil), "url optional")
        XCTAssertEqual(url(""), .some(nil), "empty == no url")
        XCTAssertEqual(url("http://pyrochat.ca/"), .some(nil), "http dropped, entry kept")
        XCTAssertEqual(url("javascript:alert(1)"), .some(nil))
        XCTAssertEqual(url("https://pyro chat.ca/"), .some(nil), "whitespace inside")
        XCTAssertEqual(url("https://"), .some(nil), "no host")
        XCTAssertEqual(url("ftp://pyrochat.ca/"), .some(nil))
        XCTAssertEqual(url("https://pyrochat.ca/" + String(repeating: "a", count: 300)), .some(nil), "over the 300-char cap")
        let atCap = "https://pyrochat.ca/" + String(repeating: "a", count: LatestVersion.maxURLLength - 20)
        XCTAssertEqual(atCap.count, LatestVersion.maxURLLength)
        XCTAssertEqual(url(atCap), .some(atCap), "exactly the cap is fine")
        // through the decoder too
        let f = try frame(#"{"t":"versions","ios":{"build":24,"name":"0.1.0","url":"http://pyrochat.ca/"}}"#)
        XCTAssertEqual(UpdateCheck.read(f), .latest(LatestVersion(build: 24, name: "0.1.0", url: nil)))
    }

    func testRule_NameIsClampedTo32() {
        func name(_ raw: String) -> String? {
            guard case .latest(let v) = UpdateCheck.read(ios(name: raw)) else { return nil }
            return v.name
        }
        XCTAssertEqual(LatestVersion.maxNameLength, 32)
        XCTAssertEqual(name(String(repeating: "9", count: 40)), String(repeating: "9", count: 32))
        XCTAssertEqual(name(String(repeating: "9", count: 32)), String(repeating: "9", count: 32))
        XCTAssertEqual(name("0.1.0"), "0.1.0")
        XCTAssertEqual(name("  0.2.0-beta \n"), "0.2.0-beta", "trimmed")
    }

    func testRule_UnknownKeysAreIgnored() throws {
        // a third platform later, even a malformed one, and stray keys:
        // the decode never fails and the verdict is ours alone
        let f = try frame(#"{"t":"versions","windows":{"build":"x"},"foo":1,"ios":{"build":24,"name":"0.1.0","beta":true}}"#)
        XCTAssertEqual(UpdateCheck.read(f), .latest(LatestVersion(build: 24, name: "0.1.0", url: nil)))
    }

    func testRunningBuildComesFromTheBundle() {
        let raw = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        let expected = raw.flatMap { Int($0) }
        XCTAssertNotNil(expected, "the test host is the real app: CFBundleVersion is an integer")
        XCTAssertEqual(UpdateCheck.runningBuild, expected)
        XCTAssertGreaterThanOrEqual(UpdateCheck.runningBuild, 1)
        // the default service uses it; tests inject their own
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-update-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "update-test-pass",
                                at: dir.appendingPathComponent("store"))
        let svc = ChatService(store: store, defaults: UserDefaults(suiteName: "pyro-update-test-\(UUID().uuidString)")!)
        services.append(svc)
        XCTAssertEqual(svc.runningBuild, UpdateCheck.runningBuild)
        XCTAssertNil(svc.updateAvailable)
        // the shipping build is never told to update to itself
        XCTAssertTrue(svc.ingestVersions(ios(build: UpdateCheck.runningBuild)))
        XCTAssertNil(svc.updateAvailable)
        XCTAssertTrue(svc.ingestVersions(ios(build: UpdateCheck.runningBuild + 1)))
        XCTAssertEqual(svc.updateAvailable?.build, UpdateCheck.runningBuild + 1)
    }

    // MARK: (b) ChatService: ingest, persistence, clear

    func testIngestPersistsOutsideTheStoreAndSurvivesRelaunch() throws {
        let (a, _, defaults) = makeService(running: 23)
        XCTAssertNil(a.updateAvailable, "fresh device: nothing advertised")
        XCTAssertNil(defaults.string(forKey: NoticeStore.latestVersionKey))

        XCTAssertTrue(a.ingestVersions(ios(build: 24, name: "0.1.0", url: "https://pyrochat.ca/")))
        let v = LatestVersion(build: 24, name: "0.1.0", url: "https://pyrochat.ca/")
        XCTAssertEqual(a.updateAvailable, v)
        XCTAssertEqual(a.latestVersion, v)

        // the agreed key, next to the notices, and nothing in the blob
        XCTAssertEqual(NoticeStore.latestVersionKey, "pyro.versions.latest")
        XCTAssertNotNil(defaults.string(forKey: NoticeStore.latestVersionKey))
        XCTAssertEqual(NoticeStore.loadLatestVersion(from: defaults), v)
        let blobJSON = String(decoding: try JSONEncoder().encode(a.store.blob), as: UTF8.self)
        XCTAssertFalse(blobJSON.contains("pyrochat.ca/"), "the store format is untouched")
        XCTAssertFalse(blobJSON.contains("\"build\""))

        // "relaunch" offline: a new service over the same defaults shows it
        let (b, _, _) = makeService(defaults: defaults, running: 23)
        XCTAssertEqual(b.updateAvailable, v, "banner shows when the app opens offline")

        // …but after the update was installed the remembered entry no longer beats us
        let (c, _, _) = makeService(defaults: defaults, running: 24)
        XCTAssertNil(c.updateAvailable, "equal build: no banner even though the entry is remembered")
        XCTAssertEqual(c.latestVersion, v)
        let (d, _, _) = makeService(defaults: defaults, running: 30)
        XCTAssertNil(d.updateAvailable)
    }

    func testLowerEqualAbsentAndOtherPlatformClearTheBanner() {
        let (svc, _, defaults) = makeService(running: 23)
        XCTAssertTrue(svc.ingestVersions(ios(build: 24)))
        XCTAssertNotNil(svc.updateAvailable)

        // equal: cleared, entry remembered
        XCTAssertTrue(svc.ingestVersions(ios(build: 23)))
        XCTAssertNil(svc.updateAvailable)
        XCTAssertEqual(svc.latestVersion?.build, 23)
        XCTAssertEqual(NoticeStore.loadLatestVersion(from: defaults)?.build, 23)

        // lower
        XCTAssertTrue(svc.ingestVersions(ios(build: 24)))
        XCTAssertNotNil(svc.updateAvailable)
        XCTAssertTrue(svc.ingestVersions(ios(build: 10)))
        XCTAssertNil(svc.updateAvailable)

        // absent (the empty frame the relay sends when nothing is advertised)
        XCTAssertTrue(svc.ingestVersions(ios(build: 24)))
        XCTAssertNotNil(svc.updateAvailable)
        XCTAssertTrue(svc.ingestVersions(Wire.In(t: "versions")))
        XCTAssertNil(svc.updateAvailable)
        XCTAssertNil(svc.latestVersion)
        XCTAssertNil(defaults.string(forKey: NoticeStore.latestVersionKey), "forgotten, not just hidden")

        // android-only frame: absent for us
        XCTAssertTrue(svc.ingestVersions(ios(build: 24)))
        XCTAssertTrue(svc.ingestVersions(Wire.In(t: "versions",
                                                 android: Wire.VersionJSON(build: 99, name: "9.9.9", url: "https://pyrochat.ca/android"))))
        XCTAssertNil(svc.updateAvailable)
        XCTAssertNil(defaults.string(forKey: NoticeStore.latestVersionKey))

        // a relaunch after the clear shows nothing
        let (again, _, _) = makeService(defaults: defaults, running: 1)
        XCTAssertNil(again.updateAvailable)
    }

    func testMalformedFrameLeavesTheBannerAlone() throws {
        let (svc, _, defaults) = makeService(running: 23)
        let v = LatestVersion(build: 24, name: "0.1.0", url: "https://pyrochat.ca/")
        XCTAssertTrue(svc.ingestVersions(ios(build: 24, url: "https://pyrochat.ca/")))
        XCTAssertEqual(svc.updateAvailable, v)

        XCTAssertFalse(svc.ingestVersions(ios(build: 0)), "build 0")
        XCTAssertFalse(svc.ingestVersions(ios(build: nil)), "no build")
        XCTAssertFalse(svc.ingestVersions(ios(name: nil)), "no name")
        XCTAssertFalse(svc.ingestVersions(try frame(#"{"t":"versions","ios":"nope"}"#)), "entry not an object")
        XCTAssertFalse(svc.ingestVersions(try frame(#"{"t":"versions","ios":{"build":30,"name":"0.2.0"},"android":{"build":0,"name":"x"}}"#)),
                       "the other platform's bad entry voids the frame")
        XCTAssertFalse(svc.ingestVersions(Wire.In(t: "notice", ios: Wire.VersionJSON(build: 30, name: "x"))), "wrong frame type")
        XCTAssertEqual(svc.updateAvailable, v, "malformed frames change nothing")
        XCTAssertEqual(NoticeStore.loadLatestVersion(from: defaults), v)

        // and a fresh device stays clean after garbage
        let (fresh, _, d2) = makeService(running: 23)
        XCTAssertFalse(fresh.ingestVersions(ios(build: -1)))
        XCTAssertNil(fresh.updateAvailable)
        XCTAssertNil(d2.string(forKey: NoticeStore.latestVersionKey))
    }

    func testUnreadablePersistedValueIsIgnored() {
        let d = UserDefaults(suiteName: "pyro-update-test-\(UUID().uuidString)")!
        d.set("not json", forKey: NoticeStore.latestVersionKey)
        XCTAssertNil(NoticeStore.loadLatestVersion(from: d))
        d.set(#"{"build":0,"name":"x"}"#, forKey: NoticeStore.latestVersionKey)
        XCTAssertNil(NoticeStore.loadLatestVersion(from: d))
        let (svc, _, _) = makeService(defaults: d, running: 1)
        XCTAssertNil(svc.updateAvailable)
        // saving nil removes the key
        NoticeStore.saveLatestVersion(LatestVersion(build: 5, name: "0.0.5", url: nil), to: d)
        XCTAssertEqual(NoticeStore.loadLatestVersion(from: d)?.build, 5)
        NoticeStore.saveLatestVersion(nil, to: d)
        XCTAssertNil(d.object(forKey: NoticeStore.latestVersionKey))
    }

    func testEveryWipePathClearsTheVersionKey() {
        let d = UserDefaults.standard
        d.set(#"{"build":24,"name":"0.1.0"}"#, forKey: NoticeStore.latestVersionKey)
        SecureStore.obliterate()          // total wipe, duress pin, dead-man all end here
        XCTAssertNil(d.object(forKey: NoticeStore.latestVersionKey))
        // NoticeStore.wipe on an arbitrary suite too
        let s = UserDefaults(suiteName: "pyro-update-test-\(UUID().uuidString)")!
        s.set("x", forKey: NoticeStore.latestVersionKey)
        NoticeStore.wipe(s)
        XCTAssertNil(s.object(forKey: NoticeStore.latestVersionKey))
    }

    // MARK: (c) live: the relay sends it at login and on change

    func testVersionsFrameArrivesAtLoginAndOnChangeLive() async throws {
        guard let original = try await Self.getVersions() else {
            throw XCTSkip("dev relay at \(Self.relayHost) has no /admin/versions yet")
        }
        restore = original                                    // tearDown puts it back

        // advertise a build far above anything real; keep android as it was
        var body = original
        body["ios"] = ["build": 1_000_001, "name": "9.9.9-test", "url": "https://pyrochat.ca/"]
        try await Self.putVersions(body)

        let (svc, name, defaults) = makeService(running: 23)
        try await svc.register(username: name)
        let arrived = await waitUntil { svc.updateAvailable?.build == 1_000_001 }
        XCTAssertTrue(arrived, "login sends `versions` right after the notice replay")
        XCTAssertEqual(svc.updateAvailable?.name, "9.9.9-test")
        XCTAssertEqual(svc.updateAvailable?.url, "https://pyrochat.ca/")
        XCTAssertNotNil(defaults.string(forKey: NoticeStore.latestVersionKey))

        // the operator clears iOS: every connected client drops the banner live
        body["ios"] = NSNull()
        try await Self.putVersions(body)
        let cleared = await waitUntil { svc.updateAvailable == nil }
        XCTAssertTrue(cleared, "a frame without our platform clears the banner")
        XCTAssertNil(defaults.string(forKey: NoticeStore.latestVersionKey))
    }

    // MARK: (d) live gate: a relay pre-set to build 99 — skipped without PYRO_VERSIONS_RELAY

    /// The coordinator's live gate: `PYRO_VERSIONS_RELAY=host:port` names a
    /// relay whose operator already PUT `{"ios":{"build":99,"name":"9.9.9",
    /// "url":"https://pyrochat.ca/"}, …}`. A real ChatService registers and
    /// must see that banner within 10 s; the operator then lowers iOS to
    /// build 1 and the banner must clear within 10 s (build 1 ≤ our running
    /// build 23). A NO-OP (XCTSkip) unless the env var is set — from xcodebuild
    /// pass it as `TEST_RUNNER_PYRO_VERSIONS_RELAY=127.0.0.1:<port>` (and
    /// `TEST_RUNNER_PYRO_VERSIONS_ADMIN_PW` for that relay's admin password).
    func testGateRelayAdvertisesBuild99ThenClearsLive() async throws {
        guard let host = ProcessInfo.processInfo.environment["PYRO_VERSIONS_RELAY"], !host.isEmpty else {
            throw XCTSkip("PYRO_VERSIONS_RELAY not set — the update-banner live gate is opt-in")
        }
        guard let original = try await Self.getVersions() else {
            XCTFail("relay at \(host) has no /admin/versions")
            return
        }
        restore = original                                    // tearDown puts build 99 back
        let expected = LatestVersion(build: 99, name: "9.9.9", url: "https://pyrochat.ca/")
        XCTAssertEqual(original["ios"] as? [String: AnyHashable],
                       ["build": 99, "name": "9.9.9", "url": "https://pyrochat.ca/"],
                       "the gate relay must be pre-set to iOS build 99 before this test runs")

        let (svc, name, defaults) = makeService(running: 23)
        try await svc.register(username: name)
        let arrived = await waitUntil(10) { svc.updateAvailable == expected }
        XCTAssertTrue(arrived, "login must deliver `versions` (build 99) within 10 s; got \(String(describing: svc.updateAvailable))")
        XCTAssertEqual(svc.updateAvailable, expected)
        XCTAssertEqual(NoticeStore.loadLatestVersion(from: defaults), expected, "remembered next to the notices for the offline open")

        // the operator lowers iOS to build 1: every connected client clears live
        var body = original
        body["ios"] = ["build": 1, "name": "9.9.9", "url": "https://pyrochat.ca/"]
        try await Self.putVersions(body)
        let cleared = await waitUntil(10) { svc.updateAvailable == nil }
        XCTAssertTrue(cleared, "a frame with build 1 (≤ running 23) must clear the banner within 10 s")
        XCTAssertEqual(NoticeStore.loadLatestVersion(from: defaults)?.build, 1, "the last frame is remembered even when it shows no banner")
    }
}
