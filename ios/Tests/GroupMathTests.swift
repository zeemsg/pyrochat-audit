import XCTest
@testable import PyroChat

/// Pure group rules (docs/GROUPS.md §2–§6): roster state machine, authority,
/// epochs, colours, receipt/fan-out aggregation, screenshot policies, store
/// round trip. No relay, no service.
final class GroupMathTests: XCTestCase {

    // fixed identities: hashes sort A < B < C < D
    let creator = String(repeating: "a", count: 64)
    let bob = String(repeating: "b", count: 64)
    let carol = String(repeating: "c", count: 64)
    let dave = String(repeating: "d", count: 64)
    let t0 = Date(timeIntervalSinceReferenceDate: 780_000_000)

    func member(_ h: String, _ name: String, _ off: TimeInterval = 0) -> Member {
        Member(hash: h, name: name, joinedAt: t0.addingTimeInterval(off))
    }

    func base() -> Group {
        Group(id: "g1", name: "ops", creator: creator, createdAt: t0, epoch: 0,
              policy: .burnRemove, burnSeconds: nil,
              members: [member(creator, "alice"), member(bob, "bob"), member(carol, "carol")])
    }

    // MARK: names (§1)

    func testNameNormalization() {
        XCTAssertEqual(GroupMath.normalizeName("  #ops  "), "ops")
        XCTAssertEqual(GroupMath.normalizeName("##ops"), "ops")
        XCTAssertEqual(GroupMath.normalizeName("ops team"), "ops team")
        XCTAssertNil(GroupMath.normalizeName(""))
        XCTAssertNil(GroupMath.normalizeName("#"))
        XCTAssertNil(GroupMath.normalizeName("   "))
        XCTAssertEqual(GroupMath.normalizeName(String(repeating: "x", count: 32))?.count, 32)
        XCTAssertNil(GroupMath.normalizeName(String(repeating: "x", count: 33)))
        XCTAssertEqual(GroupMath.normalizeName("a\u{01}b\nc"), "abc", "control chars and newlines stripped")
        XCTAssertEqual(GroupMath.normalizeName(String(repeating: "🔥", count: 32))?.count, 32, "graphemes, not bytes")
    }

    // MARK: colours (§5)

    func testPaletteIsTheSpecTable() {
        let hex = GroupMath.palette.map { String(format: "#%06x", $0) }
        XCTAssertEqual(hex, ["#3ef573", "#4fd8ff", "#c792ff", "#ffd166", "#ff8fab",
                             "#7ee8fa", "#b5e550", "#ffa64d", "#e0b3ff", "#8be9fd"])
        XCTAssertFalse(hex.contains("#ff5940"), "never the alert colour")
        XCTAssertFalse(hex.contains("#ffaf32"), "never the flame colour")
    }

    func testJoinOrderIsByJoinedAtThenHash() {
        // same joinedAt for the initial roster -> hash order; a later joiner goes last regardless of hash
        let g = Group(id: "g", name: "n", creator: creator, createdAt: t0, epoch: 0, policy: .alert,
                      burnSeconds: nil,
                      members: [member(carol, "c"), member(creator, "a"), member(bob, "b"),
                                member(String(repeating: "0", count: 64), "late", 10)])
        XCTAssertEqual(GroupMath.joinOrder(members: g.members),
                       [creator, bob, carol, String(repeating: "0", count: 64)])
        XCTAssertEqual(GroupMath.joinIndex(of: bob, in: g), 1)
        XCTAssertEqual(GroupMath.colour(of: bob, in: g)?.hex, "#4fd8ff")
        XCTAssertEqual(GroupMath.colour(of: carol, in: g)?.hex, "#c792ff")
        XCTAssertNil(GroupMath.colour(of: dave, in: g), "never joined -> no colour")
    }

    func testDimmingByLap() {
        XCTAssertEqual(GroupMath.colour(joinIndex: 0).hex, "#3ef573")
        XCTAssertEqual(GroupMath.colour(joinIndex: 10).hex, "#2bab51", "second lap: 70 % (245 × 0.7 rounds to 171 in binary floating point)")
        XCTAssertEqual(GroupMath.colour(joinIndex: 20).hex, "#1f7b3a", "third lap and beyond: 50 %")
        XCTAssertEqual(GroupMath.colour(joinIndex: 13).r, GroupMath.colour(joinIndex: 3).r * 0.7, accuracy: 1e-9)
    }

    func testRemovedMembersKeepTheirIndexSoNobodyRecolours() {
        var g = base()                                  // a=0 b=1 c=2
        XCTAssertEqual(GroupMath.joinIndex(of: carol, in: g), 2)
        let out = GroupMath.apply(.remove(hash: bob), epoch: 1, from: creator, myHash: carol, to: g)
        g = out.group!
        XCTAssertFalse(g.isMember(bob))
        XCTAssertEqual(GroupMath.joinIndex(of: carol, in: g), 2, "carol keeps index 2 after bob left")
        XCTAssertEqual(GroupMath.joinIndex(of: bob, in: g), 1, "bob's old rows keep his colour")
        // a newcomer takes the NEXT index, not bob's freed one
        let add = GroupMath.apply(.add(member(dave, "dave", 50)), epoch: 2, from: creator, myHash: carol, to: g)
        XCTAssertEqual(GroupMath.joinIndex(of: dave, in: add.group!), 3)
        // re-adding bob later keeps his ORIGINAL colour (earliest join wins)
        let readd = GroupMath.apply(.add(member(bob, "bob", 99)), epoch: 3, from: creator, myHash: carol, to: add.group!)
        XCTAssertEqual(GroupMath.joinIndex(of: bob, in: readd.group!), 1)
    }

    // MARK: roster state machine + authority (§2)

    func testCreateRequiresSenderToBeTheCreatorAndMeOnTheRoster() {
        let g = base()
        XCTAssertTrue(GroupMath.apply(.create(g, invite: false), epoch: 0, from: creator, myHash: bob, to: nil).applied)
        XCTAssertFalse(GroupMath.apply(.create(g, invite: false), epoch: 0, from: bob, myHash: carol, to: nil).applied,
                       "creator field must equal the session it arrived on")
        XCTAssertFalse(GroupMath.apply(.create(g, invite: false), epoch: 0, from: creator, myHash: dave, to: nil).applied,
                       "a roster that doesn't include me is ignored")
        // idempotent by id
        XCTAssertFalse(GroupMath.apply(.create(g, invite: false), epoch: 0, from: creator, myHash: bob, to: g).applied)
    }

    func testInviteAcceptedFromAnyMemberOnTheCarriedRoster() {
        var g = base()
        g.members.append(member(dave, "dave", 5))
        g.epoch = 1
        XCTAssertTrue(GroupMath.apply(.create(g, invite: true), epoch: 1, from: bob, myHash: dave, to: nil).applied,
                      "bob (a member) added dave")
        XCTAssertFalse(GroupMath.apply(.create(g, invite: true), epoch: 1, from: String(repeating: "e", count: 64),
                                       myHash: dave, to: nil).applied, "a stranger cannot invite")
    }

    func testInviteRejoinsALeftGroupAndKeepsColourHistory() {
        var left = base()
        _ = GroupMath.apply(.remove(hash: carol), epoch: 1, from: creator, myHash: carol, to: left).group.map { left = $0 }
        XCTAssertTrue(left.isLeft)
        var fresh = base()
        fresh.members.append(member(dave, "dave", 3))   // dave joined while carol was out
        fresh.members.append(member(carol, "carol", 9)) // carol re-added
        fresh.epoch = 4
        let out = GroupMath.apply(.create(fresh, invite: true), epoch: 4, from: creator, myHash: carol, to: left)
        XCTAssertTrue(out.applied)
        XCTAssertFalse(out.group!.isLeft)
        XCTAssertEqual(GroupMath.joinIndex(of: carol, in: out.group!), 2, "original join kept")
        XCTAssertEqual(GroupMath.joinIndex(of: dave, in: out.group!), 3)
    }

    func testAddAppendsBumpsEpochAndIsIdempotent() {
        let g = base()
        let out = GroupMath.apply(.add(member(dave, "dave", 7)), epoch: 1, from: bob, myHash: carol, to: g)
        XCTAssertTrue(out.applied)
        XCTAssertEqual(out.group?.epoch, 1)
        XCTAssertEqual(out.group?.members.count, 4)
        XCTAssertEqual(out.rows, ["── <dave> joined ──"])
        XCTAssertEqual(out.stamps[dave], GroupMath.Stamp(epoch: 1, writer: bob))
        // same add again (redelivered): nothing
        let again = GroupMath.apply(.add(member(dave, "dave", 7)), epoch: 1, from: bob, myHash: carol,
                                    to: out.group, stamps: out.stamps)
        XCTAssertFalse(again.applied)
        // from a non-member: ignored
        XCTAssertFalse(GroupMath.apply(.add(member(dave, "dave")), epoch: 1, from: dave, myHash: carol, to: g).applied)
    }

    func testAddRefusedBeyondTwenty() {
        var g = base()
        for i in 3..<20 {
            g.members.append(member(String(format: "%064x", i), "m\(i)", Double(i)))
        }
        XCTAssertEqual(g.members.count, 20)
        let out = GroupMath.apply(.add(member(dave, "dave", 99)), epoch: 1, from: creator, myHash: bob, to: g)
        XCTAssertFalse(out.applied, "ERR: group is full (20)")
    }

    func testRemoveIsCreatorOnly() {
        let g = base()
        let ok = GroupMath.apply(.remove(hash: bob), epoch: 1, from: creator, myHash: carol, to: g)
        XCTAssertTrue(ok.applied)
        XCTAssertEqual(ok.rows, ["── <bob> was removed ──"])
        XCTAssertFalse(ok.group!.isMember(bob))
        XCTAssertFalse(ok.group!.isLeft, "carol stays")
        XCTAssertEqual(ok.group?.epoch, 1)

        let bad = GroupMath.apply(.remove(hash: carol), epoch: 1, from: bob, myHash: carol, to: g)
        XCTAssertFalse(bad.applied, "a removal from anyone but the creator is ignored")
        XCTAssertEqual(bad.debugRows, ["── ignored a removal from <bob> (not the creator) ──"])
        XCTAssertEqual(bad.group, g, "state untouched")
    }

    func testBeingRemovedMarksTheGroupLeft() {
        let out = GroupMath.apply(.remove(hash: carol), epoch: 1, from: creator, myHash: carol, to: base())
        XCTAssertTrue(out.applied)
        XCTAssertTrue(out.group!.isLeft)
        XCTAssertFalse(out.group!.isMember(carol))
    }

    func testCreatorLeavingKeepsGroupAliveWithNoMoreRemovals() {
        let out = GroupMath.apply(.leave, epoch: 1, from: creator, myHash: carol, to: base())
        XCTAssertTrue(out.applied)
        XCTAssertEqual(out.rows, ["── <alice> left ──"])
        let g = out.group!
        XCTAssertEqual(g.creator, creator, "creator id is fixed for life")
        XCTAssertFalse(g.isMember(creator))
        // a later removal signed by the (departed) creator is ignored
        let rm = GroupMath.apply(.remove(hash: bob), epoch: 2, from: creator, myHash: carol, to: g)
        XCTAssertFalse(rm.applied)
        XCTAssertFalse(GroupMath.apply(.rename("x"), epoch: 2, from: creator, myHash: carol, to: g).applied)
    }

    func testRenameAndPolicyAreCreatorOnly() {
        let g = base()
        let rn = GroupMath.apply(.rename("  #ops2 "), epoch: 1, from: creator, myHash: bob, to: g)
        XCTAssertTrue(rn.applied)
        XCTAssertEqual(rn.group?.name, "ops2")
        XCTAssertEqual(rn.rows, ["── renamed to #ops2 ──"])
        XCTAssertFalse(GroupMath.apply(.rename("nope"), epoch: 1, from: bob, myHash: carol, to: g).applied)
        XCTAssertFalse(GroupMath.apply(.rename(""), epoch: 1, from: creator, myHash: bob, to: g).applied, "invalid name")

        let pol = GroupMath.apply(.policy(.burn, burnSeconds: 60), epoch: 2, from: creator, myHash: bob, to: g)
        XCTAssertTrue(pol.applied)
        XCTAssertEqual(pol.group?.policy, .burn)
        XCTAssertEqual(pol.group?.burnSeconds, 60)
        XCTAssertEqual(pol.rows, ["── screenshot policy: burn ──"])
        XCTAssertFalse(GroupMath.apply(.policy(.alert, burnSeconds: nil), epoch: 2, from: bob, myHash: carol, to: g).applied)
    }

    func testBurnIsCreatorOnlyAndDestroysEverything() {
        let g = base()
        let out = GroupMath.apply(.burn, epoch: 1, from: creator, myHash: bob, to: g)
        XCTAssertTrue(out.applied)
        XCTAssertNil(out.group)
        XCTAssertTrue(out.wipeThread)
        XCTAssertEqual(out.flash, "#ops was burned by the creator")
        XCTAssertFalse(GroupMath.apply(.burn, epoch: 1, from: bob, myHash: carol, to: g).applied,
                       "group_burn from anyone but the creator is ignored")
        // a phone that was already removed still honours the creator's burn
        var left = g; left.left = true
        XCTAssertTrue(GroupMath.apply(.burn, epoch: 5, from: creator, myHash: carol, to: left).applied)
    }

    func testEpochsConvergeAcrossConcurrentAdds() {
        // creator adds D and bob adds E at the same time, both from epoch 0
        let g = base()
        let eve = String(repeating: "e", count: 64)
        let fromCreator = GroupMath.apply(.add(member(dave, "dave", 1)), epoch: 1, from: creator, myHash: carol, to: g)
        let then = GroupMath.apply(.add(member(eve, "eve", 1)), epoch: 1, from: bob, myHash: carol,
                                   to: fromCreator.group, stamps: fromCreator.stamps)
        let other1 = GroupMath.apply(.add(member(eve, "eve", 1)), epoch: 1, from: bob, myHash: carol, to: g)
        let other2 = GroupMath.apply(.add(member(dave, "dave", 1)), epoch: 1, from: creator, myHash: carol,
                                     to: other1.group, stamps: other1.stamps)
        XCTAssertEqual(then.group?.epoch, 2)
        XCTAssertEqual(other2.group?.epoch, 2)
        XCTAssertEqual(Set(then.group!.members.map(\.hash)), Set(other2.group!.members.map(\.hash)))
        // a late-delivered message from an older epoch is still shown — that is a
        // rendering rule (no drop); nothing in the reducer rejects on epoch alone
    }

    func testLastWriterByEpochThenHashWins() {
        // bob (hash b) adds dave at epoch 1; the creator (hash a) removes dave at epoch 1:
        // tie on epoch -> higher hash wins -> the ADD wins, on every phone, whatever the order
        let g = base()
        let add = GroupMath.apply(.add(member(dave, "dave", 1)), epoch: 1, from: bob, myHash: carol, to: g)
        let rmAfter = GroupMath.apply(.remove(hash: dave), epoch: 1, from: creator, myHash: carol,
                                      to: add.group, stamps: add.stamps)
        XCTAssertFalse(rmAfter.applied, "(1, a) loses to (1, b)")
        XCTAssertTrue(rmAfter.group!.isMember(dave))

        var gWithDave = g; gWithDave.members.append(member(dave, "dave", 1))
        let rmFirst = GroupMath.apply(.remove(hash: dave), epoch: 1, from: creator, myHash: carol, to: gWithDave)
        XCTAssertTrue(rmFirst.applied)
        let addAfter = GroupMath.apply(.add(member(dave, "dave", 1)), epoch: 1, from: bob, myHash: carol,
                                       to: rmFirst.group, stamps: rmFirst.stamps)
        XCTAssertTrue(addAfter.applied, "(1, b) beats the earlier (1, a)")
        XCTAssertTrue(addAfter.group!.isMember(dave))
        XCTAssertTrue(GroupMath.wins(.init(epoch: 2, writer: creator), over: .init(epoch: 1, writer: dave)))
        XCTAssertFalse(GroupMath.wins(.init(epoch: 1, writer: creator), over: .init(epoch: 1, writer: creator)),
                       "an identical stamp is a redelivery")
    }

    // MARK: screenshot policies (§6, receiver side)

    func testScreenshotAlertAndBurnOnlyWarn() {
        for policy in [GroupPolicy.alert, .burn] {
            var g = base(); g.policy = policy
            let out = GroupMath.apply(.screenshot, epoch: 0, from: bob, myHash: carol, to: g)
            XCTAssertTrue(out.applied)
            XCTAssertEqual(out.rows, ["\u{26A0} <bob> took a screenshot"])
            XCTAssertTrue(out.group!.isMember(bob), "\(policy): bob stays")
            XCTAssertEqual(out.group?.epoch, 0)
            XCTAssertFalse(out.wipeThread)
        }
    }

    func testScreenshotBurnRemoveDropsTheOffenderEverywhere() {
        let out = GroupMath.apply(.screenshot, epoch: 0, from: bob, myHash: carol, to: base())
        XCTAssertTrue(out.applied)
        XCTAssertEqual(out.rows, ["\u{26A0} <bob> took a screenshot",
                                  "── <bob> was removed for taking a screenshot ──"])
        XCTAssertFalse(out.group!.isMember(bob))
        XCTAssertEqual(out.group?.epoch, 1)
        XCTAssertEqual(out.stamps[bob], GroupMath.Stamp(epoch: 1, writer: bob))
        XCTAssertEqual(GroupMath.joinIndex(of: bob, in: out.group!), 1, "his old lines keep their colour")
        // a stranger's screenshot control means nothing
        XCTAssertFalse(GroupMath.apply(.screenshot, epoch: 0, from: dave, myHash: carol, to: base()).applied)
    }

    // MARK: receipts + fan-out aggregation (§1, §4)

    func testStatusWordsAggregate() {
        XCTAssertEqual(GroupMath.statusWord(status: .sent, receipts: [:], recipients: 9, showRead: true), "SENT")
        XCTAssertEqual(GroupMath.statusWord(status: .sent, receipts: [bob: "delivered", carol: "delivered", dave: "read", "x": "read"],
                                            recipients: 9, showRead: true), "READ 2/9")
        XCTAssertEqual(GroupMath.statusWord(status: .sent, receipts: [bob: "delivered", carol: "delivered", dave: "read", "x": "read"],
                                            recipients: 9, showRead: false), "DELIVERED 4/9", "reciprocity: no READ without my toggle")
        XCTAssertEqual(GroupMath.statusWord(status: .delivered, receipts: [bob: "delivered"], recipients: 2, showRead: true), "DELIVERED 1/2")
        XCTAssertEqual(GroupMath.statusWord(status: .pending, receipts: [:], recipients: 2, showRead: true), "\u{2026}")
        XCTAssertEqual(GroupMath.statusWord(status: .failed, receipts: [bob: "read"], recipients: 2, showRead: true), "FAILED")
    }

    func testRowStatusFromSendStatesAndReceipts() {
        XCTAssertEqual(GroupMath.rowStatus(sendStates: [.sent, .pending], receipts: [:], recipients: 2, previous: .pending), .pending)
        XCTAssertEqual(GroupMath.rowStatus(sendStates: [.sent, .failed], receipts: [:], recipients: 2, previous: .pending), .failed)
        XCTAssertEqual(GroupMath.rowStatus(sendStates: [.sent, .sent], receipts: [:], recipients: 2, previous: .pending), .sent)
        XCTAssertEqual(GroupMath.rowStatus(sendStates: [], receipts: [bob: "delivered"], recipients: 2, previous: .sent), .sent)
        XCTAssertEqual(GroupMath.rowStatus(sendStates: [], receipts: [bob: "delivered", carol: "read"], recipients: 2, previous: .sent), .delivered)
        XCTAssertEqual(GroupMath.rowStatus(sendStates: [], receipts: [bob: "read", carol: "read"], recipients: 2, previous: .delivered), .read)
        XCTAssertEqual(GroupMath.rowStatus(sendStates: [], receipts: [:], recipients: 2, previous: .failed), .failed, "a persisted FAILED stays after relaunch")
        XCTAssertEqual(GroupMath.rowStatus(sendStates: [], receipts: [bob: "read"], recipients: 3, previous: .read), .read, "never backwards when a member joins later")
        XCTAssertEqual(GroupMath.mergedReceipt(existing: "read", incoming: "delivered"), "read")
        XCTAssertEqual(GroupMath.mergedReceipt(existing: nil, incoming: "delivered"), "delivered")
    }

    func testFanOutPartialFailureAndRetryTargets() {
        // live bookkeeping: retry only the recipients that errored
        let states: [String: GroupMath.SendState] = [bob: .sent, carol: .failed, dave: .failed]
        XCTAssertEqual(Set(GroupMath.retryTargets(states: states, recipients: [bob, carol, dave], receipts: [:])), [carol, dave])
        XCTAssertEqual(GroupMath.rowStatus(sendStates: Array(states.values), receipts: [:], recipients: 3, previous: .pending), .failed)
        // after a relaunch (no states): everyone who never receipted
        XCTAssertEqual(GroupMath.retryTargets(states: [:], recipients: [bob, carol, dave], receipts: [bob: "delivered"]), [carol, dave])
        // a gone member is simply not a recipient any more
        XCTAssertEqual(GroupMath.retryTargets(states: [carol: .failed], recipients: [bob], receipts: [:]), [])
    }

    func testTypingLine() {
        XCTAssertNil(GroupMath.typingLine([]))
        XCTAssertEqual(GroupMath.typingLine(["alice"]), "alice is typing\u{2026}")
        XCTAssertEqual(GroupMath.typingLine(["alice", "bob"]), "alice, bob are typing\u{2026}")
        XCTAssertEqual(GroupMath.typingLine(["a", "b", "c", "d", "e"]), "a, b, c \u{2026} and 2 more are typing\u{2026}")
    }

    // MARK: store round trip (§3)

    func testGroupAndRowsRoundTripWithExactKeys() throws {
        var g = base()
        g.burnSeconds = 300
        g.left = true
        g.former = [member(dave, "dave", 2)]
        let data = try JSONEncoder().encode(g)
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(obj.keys), ["id", "name", "creator", "createdAt", "epoch", "policy", "burnSeconds", "members", "left", "former"])
        XCTAssertEqual(obj["policy"] as? String, "burn_remove")
        XCTAssertEqual(obj["createdAt"] as? Double, 780_000_000, "Swift seconds since 2001")
        let m0 = try XCTUnwrap((obj["members"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(m0.keys), ["hash", "name", "joinedAt"], "gone omitted when nil")
        let back = try JSONDecoder().decode(Group.self, from: data)
        XCTAssertEqual(back, g)

        var plain = base()
        plain.left = nil
        let plainObj = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(plain)) as? [String: Any])
        XCTAssertNil(plainObj["left"]); XCTAssertNil(plainObj["former"]); XCTAssertNil(plainObj["burnSeconds"])

        var row = ChatMessage(id: "m1", direction: .outgoing, kind: .text, text: "hi",
                              timestamp: t0, status: .sent, burnAfter: nil, burnStartedAt: nil)
        row.group = "g1"
        row.receipts = [bob: "read"]
        let rowObj = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(row)) as? [String: Any])
        XCTAssertEqual(rowObj["group"] as? String, "g1")
        XCTAssertEqual(rowObj["receipts"] as? [String: String], [bob: "read"])
        XCTAssertNil(rowObj["sender"])
        var inRow = row; inRow.sender = bob; inRow.receipts = nil
        let inObj = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(inRow)) as? [String: Any])
        XCTAssertEqual(inObj["sender"] as? String, bob)
        XCTAssertNil(inObj["receipts"])
    }

    func testBlobWithGroupsRoundTripsAndDecodesWhenAbsent() throws {
        var blob = SecureStore.Blob()
        blob.groups = [base()]
        var row = ChatMessage(id: "m1", direction: .incoming, kind: .text, text: "hi",
                              timestamp: t0, status: .delivered, burnAfter: nil, burnStartedAt: nil)
        row.group = "g1"; row.sender = bob
        blob.messages = ["g1": [row]]
        let data = try JSONEncoder().encode(blob)
        let decoded = try JSONDecoder().decode(SecureStore.Blob.self, from: data)
        XCTAssertEqual(decoded.groups, [base()])
        XCTAssertEqual(decoded.messages["g1"]?.first?.sender, bob)
        XCTAssertEqual(decoded.messages["g1"]?.first?.group, "g1")

        // an old store has no `groups` key at all
        var obj = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        obj.removeValue(forKey: "groups")
        let old = try JSONDecoder().decode(SecureStore.Blob.self, from: JSONSerialization.data(withJSONObject: obj))
        XCTAssertNil(old.groups)
        // no groups -> the key is absent again after a save
        let empty = try JSONSerialization.jsonObject(with: JSONEncoder().encode(SecureStore.Blob())) as? [String: Any]
        XCTAssertNil(empty?["groups"])
        // a legacy row (no group/sender/receipts) decodes with nils
        let legacy = #"{"id":"m","direction":"outgoing","kind":"text","text":"x","timestamp":1,"status":"sent"}"#
        let lr = try JSONDecoder().decode(ChatMessage.self, from: Data(legacy.utf8))
        XCTAssertNil(lr.group); XCTAssertNil(lr.sender); XCTAssertNil(lr.receipts)
        // an unknown policy word from a newer build reads as alert
        let future = #"{"id":"g","policy":"vaporise","members":[]}"#
        XCTAssertEqual(try JSONDecoder().decode(Group.self, from: Data(future.utf8)).policy, .alert)
    }

    func testWirePayloadCarriesGroupFieldsAndDecodesLeniently() throws {
        var p = Wire.Payload(kind: "control", control: "group_create")
        p.group = Wire.GroupRef(id: "g1", epoch: 2)
        p.name = "ops"; p.creator = creator
        p.members = [Wire.MemberJSON(hash: bob, name: "bob", joinedAt: t0)]
        p.policy = "burn_remove"; p.createdAt = t0
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(p)) as? [String: Any])
        XCTAssertEqual((obj["group"] as? [String: Any])?["epoch"] as? Int, 2)
        XCTAssertEqual(((obj["members"] as? [[String: Any]])?.first)?["joinedAt"] as? Double, 780_000_000)
        XCTAssertEqual(obj["policy"] as? String, "burn_remove")
        XCTAssertNil(obj["member"]); XCTAssertNil(obj["burnSeconds"])
        // a text message with `group` + `refId`
        var t = Wire.Payload(kind: "text", text: "hi", senderName: "alice")
        t.group = Wire.GroupRef(id: "g1", epoch: 0); t.refId = "m1"
        let back = try JSONDecoder().decode(Wire.Payload.self, from: JSONEncoder().encode(t))
        XCTAssertEqual(back.group?.id, "g1"); XCTAssertEqual(back.refId, "m1")
        // a malformed group object from another client never fails the payload
        let junk = #"{"kind":"text","text":"still here","group":"not-an-object","createdAt":"iso?","members":[{"hash":5}]}"#
        let lenient = try JSONDecoder().decode(Wire.Payload.self, from: Data(junk.utf8))
        XCTAssertEqual(lenient.text, "still here")
        XCTAssertNil(lenient.group); XCTAssertNil(lenient.createdAt)
    }
}
