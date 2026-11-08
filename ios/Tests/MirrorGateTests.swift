import XCTest
@testable import PyroChat

/// The desktop-link group gate (docs/MIRROR-GROUPS.md §4, last bullet): ONE real iOS
/// `ChatService` (`ios_a_<rand>`) on the local dev relay (127.0.0.1:7444) creates `#gate`
/// with ONE Android JVM member (`android/core-service` `MirrorGateAndroidTest`), then links
/// a REAL browser page (headless Chrome on `http://127.0.0.1:7444/web#test`, driven over
/// CDP by the scratchpad `mirror-gate/gate.mjs`) to this phone through `MirrorEngine`.
///
/// The three sides talk through files in `PYRO_MIRROR_GATE_DIR` (must live outside
/// ~/Desktop — macOS TCC blocks the simulator's test host there):
///   android.txt  ← the Android member's username (written by the Android side, polled here)
///   ios.txt      → this side's username (for the Android side)
///   browser-read.txt ← gate.mjs asserted READ 1/1 on the page
///   read.txt     → written after that
///   qr.txt       ← the page's QR payload (`window.__qrPayload`, written by gate.mjs; rewritten
///                  with the NEW payload once the page rescans after unlink #1)
///   linked.txt   → written once the page acked the link (the Android side then sends)
///   ios-state.json → group id, hashes, the colours this phone computed (gate.mjs compares)
///   android-1to1.txt ← the Android member saw the 1:1 "linked" row (the page opened the 1:1
///                  thread with it after the READ → `view` → announced here too)
///   browser-gate-again.txt ← the page went back to #gate (a second `view` — not re-announced);
///                  we unlink #1 only after both, so the notices land in order and the page
///                  resumes #gate after the relink
///   unlinked.txt → unlink #1 done, its `web_link {linkUp:false}` fan-out drained (the Android
///                  member, still in #gate, expects its "unlinked" row in #gate AND in the 1:1
///                  thread — the two announced threads; the page shows LINK LOST and rescans)
///   relinked.txt → the page acked the RELINK (the Android member leaves once it also saw the
///                  second "linked" row from the page's `view` of #gate)
///   done.txt     ← gate.mjs finished its last checkpoint (we keep the relink up until then)
///   unlinked2.txt → the final unlink is done: only #gate was announced in the second session
///                  and the Android member has left it, so it must hear nothing (it asserts so)
///
/// Browser-link disclosure is contextual (MIRROR-GROUPS.md §5): linking announces NOTHING;
/// the page's `view` of #gate (first open, and again after the relink) announces the browser
/// to the group by fan-out; unlink tells only the announced threads. So the order is: link →
/// view → texts → unlink #1 (Android sees "unlinked") → relink → view (Android sees "linked"
/// again) → the Android member leaves (the page sees `left`) → unlink #2.
///
/// Assertions here are the phone-side half: the page's `view` announced #gate (once per link
/// session), the Android text arrives in the group, the browser's `send_text` fans out and the
/// Android member READs it, the Android leave lands as a system row. The browser-side half
/// (the `#gate · N` row, the coloured `<andro_…>`, the echoed row with `READ 1/1`, LINK LOST
/// + rescan, the `left` system row) is asserted by gate.mjs from the page's DOM, with a
/// screenshot per checkpoint; the Android side asserts the "linked" row arrives only after the
/// page opened #gate and the "unlinked" row at unlink #1.
@MainActor
final class MirrorGateTests: XCTestCase {

    private static let androidText = "from android #1"
    private static let browserText = "hello from the browser"

    private struct GateFailure: Error, CustomStringConvertible { let description: String }

    private func gate(_ s: String) { print("GATE: \(s)") }

    private func makeService(_ prefix: String) -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-mgate-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "mgate-test-pass",
                                at: dir.appendingPathComponent("store"))
        store.update { $0.settings.sendReadReceipts = true }   // hello.showRead = true → the page shows READ
        let name = prefix + "_" + String(UUID().uuidString.prefix(6).lowercased())
        return (ChatService(store: store), name)
    }

    private func waitUntil(_ timeout: TimeInterval, _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(150))
        }
        return cond()
    }

    /// First non-empty line of `path`, polled up to `timeout`.
    private func pollFile(_ path: String, timeout: TimeInterval, what: String) async throws -> String {
        gate("waiting for \(what) in \(path) (up to \(Int(timeout)) s)")
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let s = try? String(contentsOfFile: path, encoding: .utf8),
               let first = s.split(whereSeparator: \.isNewline).first {
                let v = first.trimmingCharacters(in: .whitespaces)
                if !v.isEmpty { return v }
            }
            try await Task.sleep(for: .milliseconds(500))
        }
        throw GateFailure(description: "\(what) never appeared in \(path)")
    }

    /// Like `pollFile`, but waits for a value DIFFERENT from `previous`.
    private func pollFileChanged(_ path: String, from previous: String, timeout: TimeInterval,
                                 what: String) async throws -> String {
        gate("waiting for \(what) in \(path) (up to \(Int(timeout)) s)")
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let s = try? String(contentsOfFile: path, encoding: .utf8),
               let first = s.split(whereSeparator: \.isNewline).first {
                let v = first.trimmingCharacters(in: .whitespaces)
                if !v.isEmpty && v != previous { return v }
            }
            try await Task.sleep(for: .milliseconds(500))
        }
        throw GateFailure(description: "\(what) never appeared in \(path)")
    }

    /// Join the pair the page advertises in `payload`; returns the linked engine.
    private func link(_ a: ChatService, payload: String, relay: URL) async throws -> MirrorEngine {
        let link = try MirrorLink.parse(payload, expectedRelay: relay)
        XCTAssertEqual(link.host, "127.0.0.1:7444")
        let engine = try MirrorEngine(service: a, link: link)
        engine.start()
        gate("joining pair \(link.pairId) on \(relay) — SAS \(engine.sas)")
        let linked = await waitUntil(30) {
            if case .linked = engine.state { return engine.browserName != nil }
            return false
        }
        XCTAssertTrue(linked, "the page must ack the link; state=\(engine.state)")
        gate("linked: browser=\(engine.browserName ?? "?") state=\(engine.state)")
        return engine
    }

    func testGroupMirroredToLinkedBrowserWithAndroidMember() async throws {
        guard let dir = ProcessInfo.processInfo.environment["PYRO_MIRROR_GATE_DIR"], !dir.isEmpty else {
            throw XCTSkip("PYRO_MIRROR_GATE_DIR not set — the desktop-link group gate is opt-in")
        }
        let file = { (n: String) in dir + "/" + n }

        // ---- one real iOS service ----
        let (a, aName) = makeService("ios_a")
        try? FileManager.default.removeItem(atPath: file("done.txt"))
        try await a.register(username: aName)
        defer { a.goOffline() }
        try "\(aName)\n".write(toFile: file("ios.txt"), atomically: true, encoding: .utf8)
        gate("registered ios_a=<\(aName)> hash=\(a.myHash.prefix(12))… — name published to \(file("ios.txt"))")

        // ---- the Android member: registers first, we poll until the relay knows it ----
        let androidName = try await pollFile(file("android.txt"), timeout: 180, what: "the Android username")
        gate("waiting for the Android member <\(androidName)> on the relay (up to 3 min)")
        let addDeadline = Date().addingTimeInterval(180)
        while true {
            do {
                try await a.addContact(username: androidName)
                break
            } catch {
                let code = "\(error)"
                guard code == "unknown_user" || code == "timeout", Date() < addDeadline else {
                    throw GateFailure(description: "addContact(\(androidName)) failed: \(code)")
                }
                try await Task.sleep(for: .seconds(2))
            }
        }
        let androidHash = try XCTUnwrap(a.contacts.first { $0.name == androidName }?.hash)
        XCTAssertEqual(androidHash, sha256Hex(androidName.lowercased()))
        gate("Android member online: <\(androidName)> hash=\(androidHash.prefix(12))…")

        // ---- create #gate with the Android member (policy alert: nothing burns here) ----
        let picked = a.contacts.filter { $0.name == androidName }
        XCTAssertEqual(picked.count, 1)
        let g = try await a.createGroup(name: "gate", members: picked, policy: .alert)
        XCTAssertEqual(g.members.count, 2)
        gate("created #gate id=\(g.id) epoch=\(g.epoch) members=\(g.members.map(\.name))")

        // ---- link the REAL page: QR payload from gate.mjs → MirrorEngine on this service ----
        let payload = try await pollFile(file("qr.txt"), timeout: 240, what: "the page's QR payload")
        let relay = try XCTUnwrap(URL(string: a.store.blob.settings.relayURL))
        let engine = try await link(a, payload: payload, relay: relay)
        defer { engine.stop(reason: "unlink") }
        XCTAssertTrue(engine.announced.isEmpty, "linking announces nothing (MIRROR-GROUPS.md §5)")

        // what this phone computed — gate.mjs compares the page's colours with these
        var colours: [String: String] = [:]
        for m in g.members {
            colours[m.hash] = GroupMath.colour(of: m.hash, in: g)?.hex ?? ""
            gate("colour on ios_a: <\(m.name)> hash=\(m.hash) hex=\(colours[m.hash]!)")
        }
        let state: [String: Any] = ["group": g.id, "name": g.name, "me": a.myHash, "myName": aName,
                                    "android": androidHash, "androidName": androidName,
                                    "sas": engine.sas, "colours": colours]
        let stateData = try JSONSerialization.data(withJSONObject: state, options: [.sortedKeys])
        try stateData.write(to: URL(fileURLWithPath: file("ios-state.json")), options: .atomic)
        try "linked\n".write(toFile: file("linked.txt"), atomically: true, encoding: .utf8)
        gate("wrote ios-state.json + linked.txt (the Android side now sends)")

        // ---- §5: the page opens #gate → `view` → this phone announces the browser to the group ----
        let viewed = await waitUntil(120) { engine.announced.contains(g.id) }
        XCTAssertTrue(viewed, "the page's `view` of #gate must announce the browser to its members; announced=\(engine.announced)")
        gate("#gate announced to its members on the page's view — announced=\(engine.announced.sorted())")

        // ---- the Android member's text lands in the group (and mirrors live) ----
        let androidArrived = await waitUntil(180) {
            a.messages[g.id]?.contains { $0.kind == .text && $0.text == Self.androidText && $0.sender == androidHash } == true
        }
        XCTAssertTrue(androidArrived, "'\(Self.androidText)' must reach ios_a")
        let aRow = try XCTUnwrap(a.messages[g.id]?.first { $0.text == Self.androidText })
        XCTAssertEqual(aRow.group, g.id)
        XCTAssertEqual(aRow.direction, .incoming)
        gate("'\(Self.androidText)' arrived: id=\(aRow.id) sender=\(androidHash.prefix(12))… status=\(aRow.status.rawValue)")

        // ---- the browser's send_text: fans out from this phone, the Android member reads it ----
        let browserRowArrived = await waitUntil(240) {
            a.messages[g.id]?.contains { $0.direction == .outgoing && $0.text == Self.browserText } == true
        }
        XCTAssertTrue(browserRowArrived, "the page's send_text must become an outgoing group row here")
        var mine = try XCTUnwrap(a.messages[g.id]?.first { $0.direction == .outgoing && $0.text == Self.browserText })
        XCTAssertEqual(mine.viaWeb, true, "composed on the linked browser → via:web")
        gate("browser row filed: id=\(mine.id) via=\(mine.viaWeb == true ? "web" : "phone") status=\(mine.status.rawValue)")
        let readByAndroid = await waitUntil(120) {
            a.messages[g.id]?.first { $0.id == mine.id }?.receipts?[androidHash] == "read"
        }
        XCTAssertTrue(readByAndroid, "the Android member READs the browser's row (per-member receipt)")
        mine = try XCTUnwrap(a.messages[g.id]?.first { $0.id == mine.id })
        let word = GroupMath.statusWord(status: mine.status, receipts: mine.receipts ?? [:],
                                        recipients: a.recipients(of: a.group(id: g.id)!).count, showRead: true)
        XCTAssertEqual(word, "READ 1/1")
        gate("browser row '\(Self.browserText)' → \(word) receipts=\(mine.receipts ?? [:])")
        // the page must have shown READ 1/1 before the roster shrinks (total = members − 1 drops on leave)
        let pageSawRead = await waitUntil(90) { FileManager.default.fileExists(atPath: file("browser-read.txt")) }
        XCTAssertTrue(pageSawRead, "gate.mjs must assert READ 1/1 on the page before the Android member leaves")
        try "read\n".write(toFile: file("read.txt"), atomically: true, encoding: .utf8)
        XCTAssertEqual(engine.announced, [g.id], "one announcement per thread per link session — the send did not repeat it")

        // ---- §5: the page opens the 1:1 thread with the Android contact → `view` → announced too ----
        let oneToOne = await waitUntil(240) { engine.announced.contains(androidHash) }
        XCTAssertTrue(oneToOne, "the page's `view` of the 1:1 thread must announce the browser to the Android contact; announced=\(engine.announced)")
        XCTAssertEqual(engine.announced, [g.id, androidHash], "exactly the two threads the browser opened")
        gate("1:1 thread announced on the page's view — announced=\(engine.announced.sorted())")
        // the Android member confirms the 1:1 row landed (and that #gate was not told twice) before we unlink
        _ = try await pollFile(file("android-1to1.txt"), timeout: 180, what: "the Android member's 1:1 'linked' row")
        // …and the page has gone back to #gate (its second `view` of it) — that is the thread it must
        // resume after the relink, so the unlink waits for this hand-shake, not just the Android one
        _ = try await pollFile(file("browser-gate-again.txt"), timeout: 120, what: "the page back on #gate")
        try await Task.sleep(for: .seconds(1))   // let that second `view` reach handleCommand before we check the set
        XCTAssertEqual(engine.announced, [g.id, androidHash], "the page's return to #gate re-`view`s it but does not re-announce")

        // ---- unlink #1 while the Android member is still in: `web_link {linkUp:false}` to the announced threads only ----
        engine.stop(reason: "unlink")
        try await Task.sleep(for: .seconds(3))   // let the fan-out drain before the others act on it
        XCTAssertTrue(engine.announced.isEmpty)
        try "unlinked\n".write(toFile: file("unlinked.txt"), atomically: true, encoding: .utf8)
        gate("unlinked #1 — wrote unlinked.txt (the Android member expects its 'unlinked' rows in #gate and the 1:1 thread; the page shows LINK LOST and rescans)")

        // ---- relink: the page's NEW QR after [RESCAN], a fresh engine, a fresh `announced` ----
        let payload2 = try await pollFileChanged(file("qr.txt"), from: payload, timeout: 240,
                                                 what: "a new QR payload after the relink")
        let engine2 = try await link(a, payload: payload2, relay: relay)
        defer { engine2.stop(reason: "unlink") }
        // (no `announced.isEmpty` check here: the page re-opens #gate right after the
        // relink's snapshot, so its `view` can land before this line runs)
        try "relinked\n".write(toFile: file("relinked.txt"), atomically: true, encoding: .utf8)
        gate("relinked: browser=\(engine2.browserName ?? "?") — wrote relinked.txt")
        let reviewed = await waitUntil(120) { engine2.announced.contains(g.id) }
        XCTAssertTrue(reviewed, "the page re-opens #gate after the relink → `view` → announced again; announced=\(engine2.announced)")
        XCTAssertEqual(engine2.announced, [g.id], "the page did not reopen the 1:1 thread after the relink, so it is not announced again")
        gate("#gate announced again after the relink — announced=\(engine2.announced.sorted()) (the Android member may leave now)")

        // ---- the Android member leaves: roster shrinks, a system row lands ----
        let leftRow = "── <\(androidName)> left ──"
        let left = await waitUntil(240) {
            a.group(id: g.id)?.isMember(androidHash) == false
                && (a.messages[g.id] ?? []).contains { $0.kind == .system && $0.text == leftRow }
        }
        XCTAssertTrue(left, "group_leave from the Android member must drop it and add '\(leftRow)'; rows=\((a.messages[g.id] ?? []).map(\.text))")
        let grp = try XCTUnwrap(a.group(id: g.id))
        XCTAssertEqual(grp.members.count, 1)
        XCTAssertEqual(grp.former?.map(\.hash), [androidHash], "the departed member keeps a colour slot")
        gate("Android member left — members=\(grp.members.map(\.name)) epoch=\(grp.epoch) rows=\((a.messages[g.id] ?? []).filter { $0.kind == .system }.map(\.text))")

        // ---- keep the link up until the page asserted its last checkpoint ----
        // done.txt names THIS run's username (a stale marker from an earlier run must not unlink us early)
        let browserDone = await waitUntil(240) {
            (try? String(contentsOfFile: file("done.txt"), encoding: .utf8))?.hasPrefix(aName + " ") == true
        }
        XCTAssertTrue(browserDone, "gate.mjs must finish its DOM checks while the link is still up")
        XCTAssertEqual(engine2.announced, [g.id], "the leave upsert added no announcement")
        gate("browser side reported done; unlinking #2 — announced=\(engine2.announced.sorted()) (#gate only, and the Android member has left it: nobody is told)")

        // ---- unlink #2: only #gate was announced in this session and its one other member has left ----
        engine2.stop(reason: "unlink")
        try await Task.sleep(for: .seconds(3))
        XCTAssertTrue(engine2.announced.isEmpty)
        try "unlinked2\n".write(toFile: file("unlinked2.txt"), atomically: true, encoding: .utf8)
        gate("unlinked #2 — wrote unlinked2.txt (the Android member asserts it hears nothing)")
        gate("PASSED")
    }
}
