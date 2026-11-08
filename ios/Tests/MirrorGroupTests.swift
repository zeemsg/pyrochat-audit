import XCTest
import CryptoKit
@testable import PyroChat

/// MIRROR-GROUPS.md §4, iOS: the group additions to the mirror schema on a
/// tapped engine — `emitTap` collects every event the engine would seal onto
/// the pair channel, so no relay and no browser are needed. The crypto and
/// framing (MIRROR-PROTOCOL.md §1–4, frozen) stay covered by MirrorTests'
/// golden vectors; nothing here touches them.
@MainActor
final class MirrorGroupTests: XCTestCase {
    private var events: [[String: Any]] = []
    /// every `sendWebLink` the service was asked for (MIRROR-GROUPS.md §5),
    /// as (thread, frames) — the frames it would have encrypted and sent
    private var links: [(thread: String, frames: [ChatService.WebLinkFrame])] = []

    // MARK: fixture

    private struct Fixture {
        let service: ChatService
        let engine: MirrorEngine
        let group: Group
        let alice: String
        let bob: String
        let aliceRow: String      // incoming text from alice
        let ownRow: String        // outgoing text, receipts alice read + bob delivered
        let systemRow: String     // `── <bob> joined ──`
        let burnRow: String       // incoming burn-after row from bob, unread
    }

    private func makeService(name: String = "demo_me") -> ChatService {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-mirgrp-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "mirgrp-test-pass",
                                at: dir.appendingPathComponent("store"))
        store.update {
            $0.usernameDisplay = name
            $0.usernameHash = sha256Hex(name)
            $0.settings.sendReadReceipts = true
        }
        return ChatService(store: store)
    }

    private func tap(_ service: ChatService) throws -> MirrorEngine {
        let browser = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let link = MirrorLink(pairId: "TESTPAIRID0000000000AA", browserPub: browser,
                              host: "127.0.0.1:7444")
        let engine = try MirrorEngine(service: service, link: link)
        engine.emitTap = { [weak self] in self?.events.append($0) }
        return engine
    }

    private func msg(_ dir: ChatMessage.Direction, _ kind: ChatMessage.Kind, _ text: String,
                     ago: TimeInterval, status: ChatMessage.Status, burnAfter: TimeInterval? = nil,
                     group: String? = nil, sender: String? = nil,
                     receipts: [String: String]? = nil) -> ChatMessage {
        var m = ChatMessage(id: UUID().uuidString.lowercased(), direction: dir, kind: kind,
                            text: text, timestamp: Date().addingTimeInterval(-ago),
                            status: status, burnAfter: burnAfter, burnStartedAt: nil)
        m.group = group
        m.sender = sender
        m.receipts = receipts
        return m
    }

    /// `#ops`: me (creator, colour 0), alice (1), bob (2, joined last); alice
    /// is also a verified contact. Four rows: alice's text, the join row,
    /// an own text with partial receipts, and bob's unread burn row.
    private func fixture(left: Bool = false) throws -> Fixture {
        let s = makeService()
        let me = s.myHash, alice = sha256Hex("alice"), bob = sha256Hex("bob")
        let t0 = Date().addingTimeInterval(-3600)
        var g = Group(id: "g-ops", name: "ops", creator: me, createdAt: t0, epoch: 2,
                      policy: .burnRemove, burnSeconds: nil,
                      members: [Member(hash: me, name: "demo_me", joinedAt: t0),
                                Member(hash: alice, name: "alice", joinedAt: t0.addingTimeInterval(30)),
                                Member(hash: bob, name: "bob", joinedAt: t0.addingTimeInterval(60))])
        if left { g.left = true }
        let rows = [
            msg(.incoming, .text, "relay restart tonight", ago: 300, status: .delivered,
                group: g.id, sender: alice),
            msg(.incoming, .system, "── <bob> joined ──", ago: 240, status: .delivered),
            msg(.outgoing, .text, "welcome", ago: 180, status: .delivered,
                group: g.id, receipts: [alice: "read", bob: "delivered"]),
            msg(.incoming, .text, "burns in a minute", ago: 60, status: .delivered,
                burnAfter: 60, group: g.id, sender: bob),
        ]
        s.contacts = [Contact(hash: alice, name: "alice", addedAt: t0, verified: true, burnSeconds: nil)]
        s.groups = [g]
        s.messages = [g.id: rows]
        s.webLinkTap = { [weak self] thread, frames in self?.links.append((thread, frames)) }
        let engine = try tap(s)
        return Fixture(service: s, engine: engine, group: g, alice: alice, bob: bob,
                       aliceRow: rows[0].id, ownRow: rows[2].id, systemRow: rows[1].id,
                       burnRow: rows[3].id)
    }

    private func events(_ e: String) -> [[String: Any]] {
        events.filter { $0["e"] as? String == e }
    }

    private func rows(inThread thread: String) -> [[String: Any]] {
        events("thread").filter { $0["thread"] as? String == thread }
            .flatMap { $0["rows"] as? [[String: Any]] ?? [] }
    }

    private func waitUntil(_ timeout: TimeInterval = 5,
                           _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return cond()
    }

    // MARK: schema builders — Group, sender, receipts, who

    func testSnapshotBeginListsGroupWithColouredRoster() throws {
        let f = try fixture()
        f.engine.sendSnapshot()
        let begin = try XCTUnwrap(events("snapshot_begin").first)
        let groups = try XCTUnwrap(begin["groups"] as? [[String: Any]])
        XCTAssertEqual(groups.count, 1)
        let g = groups[0]
        XCTAssertEqual(g["id"] as? String, "g-ops")
        XCTAssertEqual(g["name"] as? String, "ops", "stored without the #")
        XCTAssertEqual(g["policy"] as? String, "burn_remove")
        XCTAssertNil(g["left"], "a mirrored group is never left")
        XCTAssertEqual(g["lastTs"] as? Int,
                       Int(f.service.messages["g-ops"]!.last!.timestamp.timeIntervalSince1970 * 1000))
        let members = try XCTUnwrap(g["members"] as? [[String: Any]])
        XCTAssertEqual(members.map { $0["hash"] as? String }, [f.service.myHash, f.alice, f.bob])
        XCTAssertEqual(members.map { $0["name"] as? String }, ["demo_me", "alice", "bob"])
        for m in members {
            // the phone's own colour (GROUPS.md §5) as #rrggbb — the page never recomputes join order
            XCTAssertEqual(m["color"] as? String, GroupMath.colour(of: m["hash"] as! String, in: f.group)?.hex)
            XCTAssertNil(m["gone"])
        }
        XCTAssertEqual(members[0]["color"] as? String, "#3ef573")   // palette[0]: the creator
        XCTAssertEqual(members[1]["color"] as? String, "#4fd8ff")   // palette[1]: alice
        XCTAssertEqual(members[2]["color"] as? String, "#c792ff")   // palette[2]: bob
        // contacts stay exactly what v1 sent; the group id is not a contact
        let contacts = try XCTUnwrap(begin["contacts"] as? [[String: Any]])
        XCTAssertEqual(contacts.map { $0["hash"] as? String }, [f.alice])
        XCTAssertEqual(Set(contacts[0].keys), ["hash", "name", "verified", "lastTs"])
        // the group's window follows as a thread batch keyed by the group id
        XCTAssertEqual(rows(inThread: "g-ops").count, 4)
        XCTAssertEqual(events("snapshot_end").count, 1)
        XCTAssertEqual(events.last?["e"] as? String, "snapshot_end")
    }

    func testGroupRowsCarrySenderReceiptsSystemTextAndContentFreeBurn() throws {
        let f = try fixture()
        f.engine.sendSnapshot()
        let byId = Dictionary(uniqueKeysWithValues: rows(inThread: "g-ops").map { ($0["id"] as! String, $0) })
        // incoming: who wrote it (the thread key is the group, not the peer), in her colour
        let a = try XCTUnwrap(byId[f.aliceRow])
        XCTAssertEqual(a["dir"] as? String, "in")
        XCTAssertEqual(a["kind"] as? String, "text")
        XCTAssertEqual(a["text"] as? String, "relay restart tonight")
        let sender = try XCTUnwrap(a["sender"] as? [String: Any])
        XCTAssertEqual(sender["hash"] as? String, f.alice)
        XCTAssertEqual(sender["name"] as? String, "alice")
        XCTAssertEqual(sender["color"] as? String, "#4fd8ff")
        XCTAssertNil(a["receipts"])
        // own: aggregated receipts, `status` stays the aggregate word
        let o = try XCTUnwrap(byId[f.ownRow])
        XCTAssertEqual(o["dir"] as? String, "out")
        XCTAssertEqual(o["status"] as? String, "delivered")
        XCTAssertEqual(o["receipts"] as? [String: Int], ["delivered": 2, "read": 1, "total": 2],
                       "delivered counts every receipt, read the read ones, total = members − 1")
        XCTAssertNil(o["sender"])
        // system: the phone's own text, nothing else
        let sys = try XCTUnwrap(byId[f.systemRow])
        XCTAssertEqual(sys["kind"] as? String, "system")
        XCTAssertEqual(sys["text"] as? String, "── <bob> joined ──")
        XCTAssertNil(sys["sender"])
        XCTAssertNil(sys["receipts"])
        // burn: the flagship rule — no content, not even who wrote it
        let b = try XCTUnwrap(byId[f.burnRow])
        XCTAssertEqual(b["kind"] as? String, "burn")
        XCTAssertEqual(b["dir"] as? String, "in")
        XCTAssertEqual(Set(b.keys), ["id", "dir", "kind", "ts", "status"])
    }

    func testContactRowsKeepTheV1Shape() throws {
        let f = try fixture()
        f.service.messages[f.alice] = [
            msg(.incoming, .text, "one to one", ago: 10, status: .delivered),
            msg(.outgoing, .text, "reply", ago: 5, status: .read),
        ]
        f.engine.sendSnapshot()
        for r in rows(inThread: f.alice) {
            XCTAssertNil(r["sender"])
            XCTAssertNil(r["receipts"])
        }
        XCTAssertEqual(rows(inThread: f.alice).count, 2)
    }

    func testUnknownSenderHasNoColour() throws {
        let f = try fixture()
        let stranger = sha256Hex("stranger")
        f.service.messages["g-ops"]?.append(
            msg(.incoming, .text, "late delivery", ago: 1, status: .delivered, group: "g-ops", sender: stranger))
        f.engine.sendSnapshot()
        let r = try XCTUnwrap(rows(inThread: "g-ops").last)
        let sender = try XCTUnwrap(r["sender"] as? [String: Any])
        XCTAssertEqual(sender["hash"] as? String, stranger)
        XCTAssertEqual(sender["name"] as? String, "anon-" + stranger.prefix(8))
        XCTAssertNil(sender["color"], "never on the roster: the phone shows <name>? dim, the page gets no colour")
    }

    func testGroupTypingCarriesWhoInRosterOrderCappedAtThree() throws {
        let f = try fixture()
        f.engine.sendSnapshot(); events = []
        f.service.groupTyping["g-ops"] = [f.bob: Date(), f.alice: Date(), f.service.myHash: Date()]
        f.engine.tick()
        var t = try XCTUnwrap(events("typing").first)
        XCTAssertEqual(t["thread"] as? String, "g-ops")
        XCTAssertEqual(t["on"] as? Bool, true)
        XCTAssertEqual(t["who"] as? [String], ["alice", "bob"], "roster order, never me")
        events = []
        f.engine.tick()
        XCTAssertTrue(events("typing").isEmpty, "unchanged: nothing re-sent")
        f.service.groupTyping["g-ops"] = nil
        f.engine.tick()
        t = try XCTUnwrap(events("typing").first)
        XCTAssertEqual(t["on"] as? Bool, false)
        XCTAssertEqual(t["who"] as? [String], [])
        // a big roster: at most three names cross (the page adds `… and N more` itself)
        events = []
        let names = ["carol", "dave", "erin"]
        let extra = names.map { sha256Hex($0) }
        for (i, h) in extra.enumerated() {
            f.service.groups[0].members.append(
                Member(hash: h, name: names[i], joinedAt: Date().addingTimeInterval(Double(i))))
        }
        f.service.groupTyping["g-ops"] = Dictionary(uniqueKeysWithValues: ([f.alice, f.bob] + extra).map { ($0, Date()) })
        f.engine.tick()
        XCTAssertEqual(events("typing").last?["who"] as? [String], ["alice", "bob", "carol"])
    }

    // MARK: diffing — group upserts and group_rm

    func testLeftGroupIsNeverSnapshotted() throws {
        let f = try fixture(left: true)
        f.engine.sendSnapshot()
        XCTAssertEqual((events("snapshot_begin").first?["groups"] as? [[String: Any]])?.count, 0)
        XCTAssertTrue(events("thread").allSatisfy { $0["thread"] as? String != "g-ops" },
                      "no rows of a left group reach the browser")
        XCTAssertTrue(events("group").isEmpty)
        XCTAssertTrue(events("group_rm").isEmpty)
        f.engine.tick()
        XCTAssertTrue(events("group").isEmpty && events("group_rm").isEmpty)
    }

    func testLeavingOrBurningEmitsGroupRmOnce() throws {
        let f = try fixture()
        f.engine.sendSnapshot(); events = []
        f.service.groups[0].left = true                  // removed / left: read-only on the phone
        f.engine.tick()
        XCTAssertEqual(events("group_rm").map { $0["id"] as? String }, ["g-ops"])
        XCTAssertTrue(events("group").isEmpty)
        XCTAssertTrue(events("msg").isEmpty && events("thread_rm").isEmpty,
                      "the thread goes with the group — nothing separate about its rows")
        events = []
        f.engine.tick()
        XCTAssertTrue(events("group_rm").isEmpty, "sent once")
        // burned: the group is gone from the service altogether
        let f2 = try fixture()
        f2.engine.sendSnapshot(); events = []
        f2.service.destroyGroup("g-ops")
        f2.engine.tick()
        XCTAssertEqual(events("group_rm").map { $0["id"] as? String }, ["g-ops"])
        XCTAssertTrue(events("group").isEmpty)
    }

    func testRosterNameOrPolicyChangeUpsertsGroupButActivityDoesNot() throws {
        let f = try fixture()
        f.engine.sendSnapshot(); events = []
        f.engine.tick()
        XCTAssertTrue(events.isEmpty, "nothing changed: nothing sent")
        f.service.groups[0].name = "ops-2"
        f.engine.tick()
        var up = try XCTUnwrap(events("group").last)
        XCTAssertEqual(up["id"] as? String, "g-ops")
        XCTAssertEqual(up["name"] as? String, "ops-2")
        XCTAssertEqual(Set(up.keys), ["e", "id", "name", "members", "lastTs", "policy"])
        events = []
        let carol = sha256Hex("carol")
        f.service.groups[0].members.append(Member(hash: carol, name: "carol", joinedAt: Date()))
        f.engine.tick()
        up = try XCTUnwrap(events("group").last)
        let members = try XCTUnwrap(up["members"] as? [[String: Any]])
        XCTAssertEqual(members.count, 4)
        XCTAssertEqual(members.last?["color"] as? String, "#ffd166")   // palette[3]
        events = []
        f.service.groups[0].members[2].gone = true
        f.engine.tick()
        XCTAssertEqual(((events("group").last?["members"] as? [[String: Any]])?[2])?["gone"] as? Bool, true)
        events = []
        f.service.groups[0].policy = .alert
        f.engine.tick()
        XCTAssertEqual(events("group").last?["policy"] as? String, "alert")
        events = []
        // activity alone is a row event, never a group upsert
        f.service.appendMessage(msg(.incoming, .text, "later", ago: 0, status: .delivered,
                                    group: "g-ops", sender: f.alice), thread: "g-ops")
        f.engine.tick()
        XCTAssertTrue(events("group").isEmpty)
        XCTAssertEqual(events("msg").count, 1)
        XCTAssertEqual(((events("msg").first?["row"] as? [String: Any])?["sender"] as? [String: Any])?["name"] as? String, "alice")
    }

    func testMemberDepartureUpsertsRosterAndKeepsHerColourOnHistoryRows() throws {
        let f = try fixture()
        f.engine.sendSnapshot(); events = []
        // alice leaves (or is removed): off the roster, into `former` with her original joinedAt
        let alice = try XCTUnwrap(f.service.groups[0].member(f.alice))
        GroupMath.retire(alice, from: &f.service.groups[0])
        f.engine.tick()
        let up = try XCTUnwrap(events("group").last, "a roster removal is a group upsert")
        let members = try XCTUnwrap(up["members"] as? [[String: Any]])
        XCTAssertEqual(members.map { $0["hash"] as? String }, [f.service.myHash, f.bob])
        XCTAssertEqual(members[1]["color"] as? String, "#c792ff", "bob keeps palette[2]: nobody recolours")
        XCTAssertTrue(events("group_rm").isEmpty, "I am still in the group")
        // her old line still crosses with her name and colour (GROUPS.md §5)
        events = []
        f.service.messages["g-ops"]?.append(
            msg(.incoming, .text, "late from alice", ago: 0, status: .delivered, group: "g-ops", sender: f.alice))
        f.engine.tick()
        let sender = try XCTUnwrap((events("msg").last?["row"] as? [String: Any])?["sender"] as? [String: Any])
        XCTAssertEqual(sender["name"] as? String, "alice")
        XCTAssertEqual(sender["color"] as? String, "#4fd8ff")
    }

    func testReceiptCountChangeWithoutStatusChangeReachesTheBrowser() throws {
        let f = try fixture()
        f.engine.sendSnapshot(); events = []
        var log = f.service.messages["g-ops"]!
        let i = log.firstIndex { $0.id == f.ownRow }!
        log[i].receipts?[f.bob] = "read"                 // 1/2 read → 2/2 read, status word unchanged
        f.service.messages["g-ops"] = log
        f.engine.tick()
        let r = try XCTUnwrap(events("msg").first?["row"] as? [String: Any])
        XCTAssertEqual(r["id"] as? String, f.ownRow)
        XCTAssertEqual(r["status"] as? String, "delivered")
        XCTAssertEqual(r["receipts"] as? [String: Int], ["delivered": 2, "read": 2, "total": 2])
    }

    // MARK: commands with a group id as `thread`

    func testSendTextToLeftGroupAnswersCmdErrLeft() throws {
        let f = try fixture(left: true)
        f.service.connState = .online
        f.engine.handleCommand(["c": "send_text", "thread": "g-ops", "text": "hi", "tempId": "t1"])
        let err = try XCTUnwrap(events("cmd_err").first)
        XCTAssertEqual(err["code"] as? String, "left")
        XCTAssertEqual(err["tempId"] as? String, "t1")
        XCTAssertEqual(f.service.messages["g-ops"]?.count, 4, "no row was added")
        events = []
        f.engine.handleCommand(["c": "send_text", "thread": "no-such-thread", "text": "hi", "tempId": "t2"])
        XCTAssertEqual(events("cmd_err").first?["code"] as? String, "unknown_thread")
        events = []
        f.engine.handleCommand(["c": "typing", "thread": "g-ops"])
        XCTAssertTrue(events.isEmpty, "typing into a left group is silently dropped")
    }

    func testSendTextToGroupUsesTheFanOutViaWebAndEchoesTempId() async throws {
        let f = try fixture()
        f.engine.sendSnapshot(); events = []
        f.service.connState = .online                    // LINK UP as far as the engine is concerned
        f.engine.handleCommand(["c": "send_text", "thread": "g-ops", "text": "from the browser", "tempId": "t9"])
        let appended = await waitUntil {
            f.service.messages["g-ops"]?.contains { $0.text == "from the browser" } == true
        }
        XCTAssertTrue(appended)
        let own = try XCTUnwrap(f.service.messages["g-ops"]?.last)
        XCTAssertEqual(own.direction, .outgoing)
        XCTAssertEqual(own.group, "g-ops", "the existing group fan-out, keyed by the group id")
        XCTAssertEqual(own.viaWeb, true)
        XCTAssertEqual(own.receipts, [:])
        XCTAssertNil(f.service.messages[f.alice]?.first { $0.text == "from the browser" },
                     "never the 1:1 thread of a member")
        f.engine.tick()
        let echo = try XCTUnwrap(events("msg").first {
            ($0["row"] as? [String: Any])?["id"] as? String == own.id
        })
        XCTAssertEqual(echo["thread"] as? String, "g-ops")
        let r = try XCTUnwrap(echo["row"] as? [String: Any])
        XCTAssertEqual(r["tempId"] as? String, "t9")
        XCTAssertEqual(r["via"] as? String, "web")
        XCTAssertEqual(r["dir"] as? String, "out")
        XCTAssertEqual(r["receipts"] as? [String: Int], ["delivered": 0, "read": 0, "total": 2])
        XCTAssertTrue(events("cmd_err").isEmpty)
    }

    func testMarkReadOnGroupTakesTheGroupReadPathForNonBurnIdsOnly() throws {
        let f = try fixture()
        f.engine.sendSnapshot(); events = []
        f.engine.handleCommand(["c": "mark_read", "thread": "g-ops",
                                "ids": [f.aliceRow, f.burnRow, f.systemRow, "not-mirrored"]])
        let log = f.service.messages["g-ops"]!
        let a = try XCTUnwrap(log.first { $0.id == f.aliceRow })
        XCTAssertEqual(a.status, .read)
        let box = f.service.store.blob.receiptOutbox
        XCTAssertEqual(box?.read[f.alice] ?? [], [f.aliceRow],
                       "the read receipt goes to the row's sender — exactly like an on-phone read")
        // a system row has no author: the phone never reports it, nor does the mirror
        XCTAssertEqual(log.first { $0.id == f.systemRow }?.status, .delivered)
        XCTAssertNil(box?.read["g-ops"], "never a receipt addressed to the group id")
        XCTAssertEqual(box?.read.count, 1)
        // the burn row: untouched — no read, no timer, no receipt of any kind
        let b = try XCTUnwrap(log.first { $0.id == f.burnRow })
        XCTAssertEqual(b.status, .delivered)
        XCTAssertNil(b.burnStartedAt)
        XCTAssertNil(box?.read[f.bob])
        XCTAssertNil(box?.burnStart[f.bob])
        XCTAssertFalse((box?.read.values.flatMap { $0 } ?? []).contains(f.burnRow))
        XCTAssertFalse((box?.burnStart.values.flatMap { $0 } ?? []).contains(f.burnRow))
        // the row change reaches the browser as an upsert of that one row
        f.engine.tick()
        XCTAssertEqual(events("msg").count, 1)
        XCTAssertEqual((events("msg").first?["row"] as? [String: Any])?["id"] as? String, f.aliceRow)
        XCTAssertEqual((events("msg").first?["row"] as? [String: Any])?["status"] as? String, "read")
    }

    // MARK: browser-link disclosure, contextual (MIRROR-GROUPS.md §5)

    /// One first touch of `#ops` — `send_text`, `typing` or `mark_read` —
    /// announces the browser to every member by fan-out, with `group`, once.
    private func assertFirstTouchAnnouncesTheGroup(_ make: (Fixture) -> [String: Any]) async throws {
        links = []; events = []
        let f = try fixture()
        f.service.connState = .online
        f.engine.browserName = "Safari on Mac"
        f.engine.sendSnapshot()
        let cmd = make(f)
        f.engine.handleCommand(cmd)
        let what = cmd["c"] as! String
        XCTAssertEqual(links.count, 1, what)
        XCTAssertEqual(links.first?.thread, "g-ops", what)
        let frames = links.first?.frames ?? []
        XCTAssertEqual(frames.map(\.to), [f.alice, f.bob], "\(what): fan-out to every member but me")
        for x in frames {
            XCTAssertEqual(x.payload.kind, "control")
            XCTAssertEqual(x.payload.control, "web_link")
            XCTAssertEqual(x.payload.linkUp, true)
            XCTAssertEqual(x.payload.browser, "Safari on Mac")
            XCTAssertEqual(x.payload.group?.id, "g-ops", "\(what): exactly like a group control")
            XCTAssertEqual(x.payload.group?.epoch, 2)
        }
        // the same thread again, by any command: silent
        f.engine.handleCommand(cmd)
        f.engine.handleCommand(["c": "view", "thread": "g-ops"])
        f.engine.handleCommand(["c": "typing", "thread": "g-ops"])
        f.engine.handleCommand(["c": "mark_read", "thread": "g-ops", "ids": [f.aliceRow]])
        XCTAssertEqual(links.count, 1, "\(what): exactly one announcement per thread")
        XCTAssertEqual(f.engine.announced, ["g-ops"])
        XCTAssertTrue(events("cmd_err").isEmpty)
        if what == "send_text" {
            // let the fan-out (no relay: FAILED) settle before the fixture goes
            let appended = await waitUntil { f.service.messages["g-ops"]?.contains { $0.text == "hi" } == true }
            XCTAssertTrue(appended)
            try? await Task.sleep(for: .milliseconds(100))
        }
    }

    func testNothingIsAnnouncedAtLinkTime() throws {
        let f = try fixture()
        f.service.connState = .online
        f.engine.browserName = "Chrome on Mac"
        f.engine.sendSnapshot()
        f.engine.tick()
        XCTAssertFalse(events("snapshot_begin").isEmpty)
        XCTAssertTrue(links.isEmpty, "linking tells nobody — only the threads the browser touches hear about it")
        XCTAssertTrue(f.engine.announced.isEmpty)
    }

    func testViewAnnouncesTheOneToOnePeerOnceWithoutGroup() async throws {
        let f = try fixture()
        f.service.connState = .online
        f.engine.browserName = "Chrome on Mac"
        f.engine.sendSnapshot(); events = []
        f.engine.handleCommand(["c": "view", "thread": f.alice])
        XCTAssertTrue(events.isEmpty, "view answers nothing")
        XCTAssertEqual(links.count, 1)
        XCTAssertEqual(links.first?.thread, f.alice)
        XCTAssertEqual(links.first?.frames.count, 1)
        let fr = try XCTUnwrap(links.first?.frames.first)
        XCTAssertEqual(fr.to, f.alice)
        XCTAssertEqual(fr.payload.kind, "control")
        XCTAssertEqual(fr.payload.control, "web_link")
        XCTAssertEqual(fr.payload.linkUp, true)
        XCTAssertEqual(fr.payload.browser, "Chrome on Mac")
        XCTAssertNil(fr.payload.group, "a 1:1 thread carries no group")
        XCTAssertEqual(f.engine.announced, [f.alice])
        // every later touch of that thread is silent — view, typing, read, send
        f.engine.handleCommand(["c": "view", "thread": f.alice])
        f.engine.handleCommand(["c": "typing", "thread": f.alice])
        f.engine.handleCommand(["c": "mark_read", "thread": f.alice, "ids": []])
        f.engine.handleCommand(["c": "send_text", "thread": f.alice, "text": "hi", "tempId": "t1"])
        XCTAssertEqual(links.count, 1, "exactly one announcement per thread")
        XCTAssertTrue(events("cmd_err").isEmpty)
        let appended = await waitUntil { f.service.messages[f.alice]?.contains { $0.text == "hi" } == true }
        XCTAssertTrue(appended)
        try? await Task.sleep(for: .milliseconds(100))   // the (relay-less) send settles
    }

    func testFirstSendToTheGroupAnnouncesEveryMemberWithGroupRef() async throws {
        try await assertFirstTouchAnnouncesTheGroup { _ in ["c": "send_text", "thread": "g-ops", "text": "hi", "tempId": "t1"] }
    }

    func testFirstTypingInTheGroupAnnouncesEveryMemberWithGroupRef() async throws {
        try await assertFirstTouchAnnouncesTheGroup { _ in ["c": "typing", "thread": "g-ops"] }
    }

    func testFirstReadInTheGroupAnnouncesEveryMemberWithGroupRef() async throws {
        try await assertFirstTouchAnnouncesTheGroup { ["c": "mark_read", "thread": "g-ops", "ids": [$0.aliceRow]] }
    }

    func testUnlinkNotifiesOnlyAnnouncedThreads() throws {
        let f = try fixture()
        f.service.connState = .online
        f.engine.browserName = "Chrome on Mac"
        f.engine.sendSnapshot()
        f.engine.handleCommand(["c": "view", "thread": f.alice])
        links = []
        f.engine.stop(reason: "unlink")
        XCTAssertEqual(links.map(\.thread), [f.alice], "the group was never viewed: its members hear nothing")
        let fr = try XCTUnwrap(links.first?.frames.first)
        XCTAssertEqual(links.first?.frames.count, 1)
        XCTAssertEqual(fr.to, f.alice)
        XCTAssertEqual(fr.payload.control, "web_link")
        XCTAssertEqual(fr.payload.linkUp, false)
        XCTAssertNil(fr.payload.browser, "no label on link-down")
        XCTAssertNil(fr.payload.group)
        XCTAssertTrue(f.engine.announced.isEmpty)
        // both threads touched → both told, the group by fan-out with `group`; a second stop is nothing
        links = []; events = []
        let f2 = try fixture()
        f2.service.connState = .online
        f2.engine.sendSnapshot()
        f2.engine.handleCommand(["c": "view", "thread": "g-ops"])
        f2.engine.handleCommand(["c": "view", "thread": f2.alice])
        XCTAssertEqual(f2.engine.announced, ["g-ops", f2.alice])
        links = []
        f2.engine.stop(reason: "background")
        XCTAssertEqual(Set(links.map(\.thread)), [f2.alice, "g-ops"])
        XCTAssertEqual(links.count, 2)
        let gf = try XCTUnwrap(links.first { $0.thread == "g-ops" }?.frames)
        XCTAssertEqual(gf.map(\.to), [f2.alice, f2.bob])
        XCTAssertTrue(gf.allSatisfy { $0.payload.linkUp == false && $0.payload.browser == nil
            && $0.payload.group?.id == "g-ops" && $0.payload.group?.epoch == 2 })
        links = []
        f2.engine.stop(reason: "unlink")
        XCTAssertTrue(links.isEmpty)
        // nothing at all when nothing was touched
        links = []
        let f3 = try fixture()
        f3.service.connState = .online
        f3.engine.sendSnapshot()
        f3.engine.stop(reason: "unlink")
        XCTAssertTrue(links.isEmpty)
    }

    func testLeftGroupUnknownOrBlockedThreadAndOfflineAreNeverAnnounced() throws {
        let f = try fixture(left: true)
        f.service.connState = .online
        f.engine.sendSnapshot()
        f.engine.handleCommand(["c": "view", "thread": "g-ops"])
        f.engine.handleCommand(["c": "typing", "thread": "g-ops"])
        f.engine.handleCommand(["c": "mark_read", "thread": "g-ops", "ids": [f.aliceRow]])
        f.engine.handleCommand(["c": "view", "thread": "no-such-thread"])
        f.engine.handleCommand(["c": "view", "thread": f.bob])          // a member, not a contact
        f.service.blockContact(f.alice)
        f.engine.handleCommand(["c": "view", "thread": f.alice])
        f.engine.handleCommand(["c": "typing", "thread": f.alice])
        XCTAssertTrue(links.isEmpty)
        XCTAssertTrue(f.engine.announced.isEmpty)
        XCTAssertTrue(events("cmd_err").isEmpty, "view never answers")
        f.engine.stop(reason: "unlink")
        XCTAssertTrue(links.isEmpty)
        // offline: not announced and NOT remembered — the first touch once online does it, once
        let f2 = try fixture()
        f2.engine.sendSnapshot()
        f2.engine.handleCommand(["c": "view", "thread": f2.alice])
        f2.engine.handleCommand(["c": "mark_read", "thread": "g-ops", "ids": [f2.aliceRow]])
        XCTAssertTrue(links.isEmpty)
        XCTAssertTrue(f2.engine.announced.isEmpty)
        f2.service.connState = .online
        f2.engine.handleCommand(["c": "typing", "thread": f2.alice])
        f2.engine.handleCommand(["c": "view", "thread": f2.alice])
        XCTAssertEqual(links.map(\.thread), [f2.alice])
        XCTAssertEqual(f2.engine.announced, [f2.alice])
    }

    func testGoneGroupOrContactDropsOutOfAnnounced() throws {
        let f = try fixture()
        f.service.connState = .online
        f.engine.sendSnapshot()
        f.engine.handleCommand(["c": "view", "thread": "g-ops"])
        f.engine.handleCommand(["c": "view", "thread": f.alice])
        XCTAssertEqual(f.engine.announced, ["g-ops", f.alice])
        f.service.groups[0].left = true                  // left / removed
        f.service.contacts = []                          // deleted
        f.engine.tick()
        XCTAssertEqual(events("group_rm").count, 1)
        XCTAssertEqual(events("contact_rm").count, 1)
        XCTAssertTrue(f.engine.announced.isEmpty, "gone threads are forgotten")
        links = []
        f.engine.stop(reason: "unlink")
        XCTAssertTrue(links.isEmpty, "nobody to tell")
    }

    func testWebLinkFramesAudience() throws {
        let f = try fixture()
        let carol = sha256Hex("carol"), dave = sha256Hex("dave")
        f.service.groups[0].members.append(Member(hash: carol, name: "carol", joinedAt: Date()))
        f.service.groups[0].members.append(Member(hash: dave, name: "dave", joinedAt: Date(), gone: true))
        var fr = f.service.webLinkFrames(thread: "g-ops", up: true, browser: "Chrome on Mac")
        XCTAssertEqual(fr.map(\.to), [f.alice, f.bob, carol],
                       "every member but me and a gone account — a member need not be a contact")
        XCTAssertTrue(fr.allSatisfy { $0.payload.kind == "control" && $0.payload.control == "web_link"
            && $0.payload.linkUp == true && $0.payload.browser == "Chrome on Mac"
            && $0.payload.group?.id == "g-ops" && $0.payload.group?.epoch == 2 })
        f.service.blockContact(f.bob)
        fr = f.service.webLinkFrames(thread: "g-ops", up: false, browser: "Chrome on Mac")
        XCTAssertEqual(fr.map(\.to), [f.alice, carol], "a blocked member hears nothing")
        XCTAssertTrue(fr.allSatisfy { $0.payload.linkUp == false && $0.payload.browser == nil
            && $0.payload.group?.id == "g-ops" })
        // 1:1: the peer alone, no group
        fr = f.service.webLinkFrames(thread: f.alice, up: true, browser: "Chrome on Mac")
        XCTAssertEqual(fr.count, 1)
        XCTAssertEqual(fr[0].to, f.alice)
        XCTAssertEqual(fr[0].payload.linkUp, true)
        XCTAssertEqual(fr[0].payload.browser, "Chrome on Mac")
        XCTAssertNil(fr[0].payload.group)
        // the wire shapes the other platform decodes
        let one = try JSONSerialization.jsonObject(with: JSONEncoder().encode(fr[0].payload)) as? [String: Any]
        XCTAssertEqual(Set((one ?? [:]).keys), ["kind", "control", "linkUp", "browser"])
        let grp = try JSONSerialization.jsonObject(with: JSONEncoder().encode(
            f.service.webLinkFrames(thread: "g-ops", up: true, browser: "Chrome on Mac")[0].payload)) as? [String: Any]
        XCTAssertEqual(Set((grp ?? [:]).keys), ["kind", "control", "linkUp", "browser", "group"])
        XCTAssertEqual((grp?["group"] as? [String: Any])?["id"] as? String, "g-ops")
        XCTAssertEqual((grp?["group"] as? [String: Any])?["epoch"] as? Int, 2)
        // nobody: a left group, a stranger, a member who is not a contact, a blocked contact
        f.service.groups[0].left = true
        XCTAssertTrue(f.service.webLinkFrames(thread: "g-ops", up: true, browser: nil).isEmpty)
        XCTAssertTrue(f.service.webLinkFrames(thread: "no-such-thread", up: true, browser: nil).isEmpty)
        XCTAssertTrue(f.service.webLinkFrames(thread: carol, up: true, browser: nil).isEmpty)
        f.service.blockContact(f.alice)
        XCTAssertTrue(f.service.webLinkFrames(thread: f.alice, up: false, browser: nil).isEmpty)
    }

    // MARK: receiving web_link

    func testReceivedGroupWebLinkLandsInTheGroupThreadNamingTheSender() throws {
        let f = try fixture()
        var p = Wire.Payload(kind: "control", control: "web_link")
        p.linkUp = true
        p.browser = "Safari on Mac"
        p.group = Wire.GroupRef(id: "g-ops", epoch: 2)
        f.service.handleGroupControl(p, groupID: "g-ops", epoch: 2, from: f.alice, relayTs: nil)
        var row = try XCTUnwrap(f.service.messages["g-ops"]?.last)
        XCTAssertEqual(row.kind, .system)
        XCTAssertEqual(row.direction, .incoming)
        XCTAssertEqual(row.text, "── <alice> linked a desktop browser (Safari on Mac) — this channel may be viewed there ──")
        XCTAssertNil(f.service.messages[f.alice], "never the 1:1 thread of the member")
        p.linkUp = false; p.browser = nil
        f.service.handleGroupControl(p, groupID: "g-ops", epoch: 2, from: f.alice, relayTs: nil)
        row = try XCTUnwrap(f.service.messages["g-ops"]?.last)
        XCTAssertEqual(row.kind, .system)
        XCTAssertEqual(row.text, "── <alice> unlinked the desktop browser ──")
        // a member who is no contact is still named from the roster; the label is sanitized
        p.linkUp = true; p.browser = "Chrome <script> on Mac"
        f.service.handleGroupControl(p, groupID: "g-ops", epoch: 2, from: f.bob, relayTs: nil)
        XCTAssertEqual(f.service.messages["g-ops"]?.last?.text,
                       "── <bob> linked a desktop browser (Chrome script on Mac) — this channel may be viewed there ──")
        XCTAssertEqual(f.service.messages["g-ops"]?.count, 7)
        // a stranger, an unknown group: nothing anywhere
        f.service.handleGroupControl(p, groupID: "g-ops", epoch: 2, from: sha256Hex("stranger"), relayTs: nil)
        f.service.handleGroupControl(p, groupID: "no-such-group", epoch: 2, from: f.alice, relayTs: nil)
        XCTAssertEqual(f.service.messages["g-ops"]?.count, 7)
        XCTAssertNil(f.service.messages["no-such-group"])
        XCTAssertNil(f.service.messages[f.alice])
        // the rows cross to the browser as plain system rows of the group thread
        f.engine.sendSnapshot()
        let mirrored = rows(inThread: "g-ops").filter { $0["kind"] as? String == "system" }.compactMap { $0["text"] as? String }
        XCTAssertTrue(mirrored.contains("── <alice> linked a desktop browser (Safari on Mac) — this channel may be viewed there ──"))
        XCTAssertTrue(mirrored.contains("── <alice> unlinked the desktop browser ──"))
        // a group this phone has left: dropped
        let f2 = try fixture(left: true)
        f2.service.handleGroupControl(p, groupID: "g-ops", epoch: 2, from: f2.alice, relayTs: nil)
        XCTAssertEqual(f2.service.messages["g-ops"]?.count, 4)
    }

    func testReceivedWebLinkWithoutGroupStaysInTheOneToOneThread() throws {
        let f = try fixture()
        var p = Wire.Payload(kind: "control", control: "web_link")
        p.linkUp = true
        p.browser = "Safari on Mac"
        f.service.handleControl(p, from: f.alice)
        let row = try XCTUnwrap(f.service.messages[f.alice]?.last)
        XCTAssertEqual(row.kind, .system)
        XCTAssertEqual(row.text, "\u{26A0} peer linked a desktop browser (Safari on Mac) — this conversation may be viewed there")
        XCTAssertEqual(f.service.messages["g-ops"]?.count, 4, "nothing in the group")
        p.linkUp = false
        f.service.handleControl(p, from: f.alice)
        XCTAssertEqual(f.service.messages[f.alice]?.last?.text, "peer unlinked the desktop browser")
        XCTAssertEqual(f.service.messages[f.alice]?.count, 2)
        // bob is a group member but not a contact: a 1:1 web_link from him is nothing
        f.service.handleControl(p, from: f.bob)
        XCTAssertNil(f.service.messages[f.bob])
        XCTAssertEqual(f.service.messages["g-ops"]?.count, 4)
    }

    // MARK: live (dev relay 127.0.0.1:7444, real libsignal — as GroupTests)

    private func liveService() async throws -> ChatService {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-mirlive-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "mirlive-test-pass",
                                at: dir.appendingPathComponent("store"))
        let s = ChatService(store: store)
        try await s.register(username: "ml" + UUID().uuidString.prefix(8).lowercased())
        return s
    }

    /// The first browser touch of a thread whose peer has NO current session
    /// (a healed / re-keyed peer, a group member nobody has written to yet)
    /// bootstraps that session TWICE without care: `announce` fetches a
    /// bundle for the `web_link`, the text send piggybacks on the same fetch,
    /// and both would process the same bundle — two sessions from one
    /// one-time prekey, so the peer decrypts only the first envelope and the
    /// TEXT is lost (plus a heal storm). The link-up notice and the row must
    /// both arrive, in order, over one session — 1:1 and by group fan-out —
    /// and the unlink reaches exactly those two threads.
    func testFirstBrowserSendToASessionlessPeerDeliversNoticeAndTextOverOneSession() async throws {
        let alice = try await liveService()
        let bob = try await liveService()
        defer { alice.goOffline(); bob.goOffline() }
        try await alice.addContact(username: bob.myUsername)
        try await bob.addContact(username: alice.myUsername)
        let engine = try tap(alice)
        engine.browserName = "Chrome on Mac"
        engine.sendSnapshot()
        let linkedText = "\u{26A0} peer linked a desktop browser (Chrome on Mac) — this conversation may be viewed there"

        // ---- 1:1: alice's session with bob was archived (heal / re-key) ----
        alice.crypto.archiveSession(with: bob.myHash)
        XCTAssertFalse(alice.crypto.hasSession(with: bob.myHash))
        engine.handleCommand(["c": "send_text", "thread": bob.myHash, "text": "first from the browser", "tempId": "t1"])
        XCTAssertEqual(engine.announced, [bob.myHash])
        let both = await waitUntil(15) {
            let log = bob.messages[alice.myHash] ?? []
            return log.contains { $0.kind == .system && $0.text == linkedText }
                && log.contains { $0.kind == .text && $0.text == "first from the browser" }
        }
        XCTAssertTrue(both, "bob must get the link-up notice AND the text; rows=\((bob.messages[alice.myHash] ?? []).map(\.text)) lastError=\(bob.lastError ?? "-")")
        XCTAssertNotEqual(bob.lastError, "decrypt_failed", "one session, one one-time prekey: nothing to heal")
        XCTAssertNotEqual(alice.lastError, "decrypt_failed")
        let log = bob.messages[alice.myHash] ?? []
        XCTAssertEqual(log.map(\.text), [linkedText, "first from the browser"], "the notice precedes the row it announced")
        XCTAssertNotEqual(alice.messages[bob.myHash]?.last?.status, .failed)
        XCTAssertNil(bob.messages[alice.myHash]?.first { $0.text.contains("re-keyed") }, "no session_reset was needed")

        // ---- group: same race by fan-out, the notice lands in the GROUP thread naming alice ----
        let g = try await alice.createGroup(name: "ops", members: alice.contacts, policy: .alert)
        let landed = await waitUntil(15) { bob.group(id: g.id) != nil }
        XCTAssertTrue(landed)
        alice.crypto.archiveSession(with: bob.myHash)
        engine.handleCommand(["c": "send_text", "thread": g.id, "text": "group from the browser", "tempId": "t2"])
        XCTAssertEqual(engine.announced, [bob.myHash, g.id])
        let groupLinked = "── <\(alice.myUsername)> linked a desktop browser (Chrome on Mac) — this channel may be viewed there ──"
        let bothInGroup = await waitUntil(15) {
            let glog = bob.messages[g.id] ?? []
            return glog.contains { $0.kind == .system && $0.text == groupLinked }
                && glog.contains { $0.kind == .text && $0.text == "group from the browser" }
        }
        XCTAssertTrue(bothInGroup, "rows=\((bob.messages[g.id] ?? []).map(\.text)) lastError=\(bob.lastError ?? "-")")
        XCTAssertNotEqual(bob.lastError, "decrypt_failed")
        XCTAssertEqual((bob.messages[alice.myHash] ?? []).count, 2, "nothing about the group in the 1:1 thread")
        XCTAssertTrue((bob.messages[g.id] ?? []).allSatisfy { !$0.text.contains("re-keyed") })

        // ---- unlink: both announced threads hear it, each in its own thread ----
        engine.stop(reason: "unlink")
        let unlinked = await waitUntil(15) {
            (bob.messages[alice.myHash] ?? []).last?.text == "peer unlinked the desktop browser"
                && (bob.messages[g.id] ?? []).last?.text == "── <\(alice.myUsername)> unlinked the desktop browser ──"
        }
        XCTAssertTrue(unlinked, "1:1=\((bob.messages[alice.myHash] ?? []).map(\.text)) group=\((bob.messages[g.id] ?? []).map(\.text))")
        XCTAssertTrue(engine.announced.isEmpty)
    }
}
