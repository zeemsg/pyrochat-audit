import XCTest
@testable import PyroChat

/// Group channels, live against the dev relay (127.0.0.1:7444), three real
/// services (docs/GROUPS.md §8): create → text + image → receipts aggregate
/// (`READ 2/2`) → a removal by a non-creator is ignored → `burn_remove` drops
/// the offender everywhere → the creator's `group_burn` destroys it on all
/// three. Every wait has a deadline.
@MainActor
final class GroupTests: XCTestCase {

    private func makeService() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-grp-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "grp-test-pass",
                                at: dir.appendingPathComponent("store"))
        let username = "gr" + UUID().uuidString.prefix(8).lowercased()
        return (ChatService(store: store), username)
    }

    private func waitUntil(_ timeout: TimeInterval = 10,
                           _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return cond()
    }

    /// Three registered services; alice knows bob and carol as contacts.
    private func trio() async throws -> (alice: ChatService, bob: ChatService, carol: ChatService) {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        let (carol, carolName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await carol.register(username: carolName)
        for s in [alice, bob, carol] { s.store.update { $0.settings.sendReadReceipts = true } }
        try await alice.addContact(username: bobName)
        try await alice.addContact(username: carolName)
        return (alice, bob, carol)
    }

    private func groupOn(_ s: ChatService, _ id: String) -> Group? { s.group(id: id) }

    func testFullGroupLifecycleAcrossThreeServices() async throws {
        let (alice, bob, carol) = try await trio()
        defer { alice.goOffline(); bob.goOffline(); carol.goOffline() }

        // ---- create: bob and carol receive the full roster at epoch 0 ----
        let g = try await alice.createGroup(name: "ops", members: alice.contacts, policy: .burnRemove)
        XCTAssertEqual(g.members.count, 3)
        XCTAssertEqual(g.creator, alice.myHash)
        let created = await waitUntil {
            bob.group(id: g.id)?.members.count == 3 && carol.group(id: g.id)?.members.count == 3
        }
        XCTAssertTrue(created, "group_create must land on both members")
        XCTAssertEqual(bob.group(id: g.id)?.policy, .burnRemove)
        XCTAssertEqual(bob.group(id: g.id)?.name, "ops")
        XCTAssertEqual(bob.group(id: g.id)?.creator, alice.myHash)
        XCTAssertEqual(GroupMath.joinOrder(members: bob.group(id: g.id)!.members),
                       GroupMath.joinOrder(members: g.members), "same colours everywhere")

        // ---- text fan-out: one row on every phone, keyed by the group id ----
        await alice.sendText("hello ops", toGroup: g.id)
        let textArrived = await waitUntil {
            bob.messages[g.id]?.contains { $0.text == "hello ops" && $0.sender == alice.myHash } == true
            && carol.messages[g.id]?.contains { $0.text == "hello ops" } == true
        }
        XCTAssertTrue(textArrived)
        let ownRow = try XCTUnwrap(alice.messages[g.id]?.first { $0.text == "hello ops" })
        XCTAssertEqual(ownRow.group, g.id)
        XCTAssertEqual(bob.messages[g.id]?.first { $0.text == "hello ops" }?.id, ownRow.id,
                       "the receiver's row carries the sender's shared id (refId)")
        XCTAssertNil(bob.messages[alice.myHash]?.first { $0.text == "hello ops" },
                     "a group line never lands in the 1:1 thread")

        // ---- delivered receipts aggregate ----
        let delivered2 = await waitUntil {
            alice.messages[g.id]?.first { $0.id == ownRow.id }?.receipts?.count == 2
        }
        XCTAssertTrue(delivered2, "DELIVERED 2/2")
        var row = alice.messages[g.id]!.first { $0.id == ownRow.id }!
        XCTAssertEqual(GroupMath.statusWord(status: row.status, receipts: row.receipts ?? [:],
                                            recipients: 2, showRead: true), "DELIVERED 2/2")
        XCTAssertEqual(row.status, .delivered)

        // ---- read receipts: each member's read-dwell reports to the SENDER ----
        bob.noteRowVisible(messageID: ownRow.id, thread: g.id)
        carol.noteRowVisible(messageID: ownRow.id, thread: g.id)
        let read2 = await waitUntil {
            alice.messages[g.id]?.first { $0.id == ownRow.id }?.receipts?.values.filter { $0 == "read" }.count == 2
        }
        XCTAssertTrue(read2, "READ 2/2")
        row = alice.messages[g.id]!.first { $0.id == ownRow.id }!
        XCTAssertEqual(GroupMath.statusWord(status: row.status, receipts: row.receipts ?? [:],
                                            recipients: 2, showRead: true), "READ 2/2")
        XCTAssertEqual(row.status, .read)

        // ---- a member replies: everyone (incl. the creator) gets it ----
        await bob.sendText("bob here", toGroup: g.id)
        let reply = await waitUntil {
            alice.messages[g.id]?.contains { $0.text == "bob here" && $0.sender == bob.myHash } == true
            && carol.messages[g.id]?.contains { $0.text == "bob here" && $0.sender == bob.myHash } == true
        }
        XCTAssertTrue(reply, "bob had no session with carol yet — fan-out fetches the bundle")

        // ---- image fan-out: the blob pipeline once per recipient ----
        let jpeg = Data((0..<20_000).map { UInt8($0 % 251) })
        await carol.sendImage(jpeg: jpeg, width: 64, height: 48, toGroup: g.id)
        let imgArrived = await waitUntil(20) {
            bob.messages[g.id]?.contains { $0.kind == .image && $0.sender == carol.myHash } == true
            && alice.messages[g.id]?.contains { $0.kind == .image && $0.sender == carol.myHash } == true
        }
        XCTAssertTrue(imgArrived)
        let imgOnBob = try XCTUnwrap(bob.messages[g.id]?.first { $0.kind == .image })
        XCTAssertEqual(bob.attachmentData(for: imgOnBob), jpeg, "same plaintext on every phone")
        let carolImgAtt = try XCTUnwrap(carol.messages[g.id]?.first { $0.kind == .image }?.attachmentId)
        XCTAssertNotNil(carol.store.readAttachment(id: carolImgAtt))
        XCTAssertEqual(imgOnBob.id, carol.messages[g.id]?.first { $0.kind == .image }?.id)
        let imgDelivered = await waitUntil(20) {
            carol.messages[g.id]?.first { $0.kind == .image }?.receipts?.count == 2
        }
        XCTAssertTrue(imgDelivered, "image receipts aggregate too")

        // ---- a removal by a non-creator is ignored everywhere ----
        do {
            try await bob.removeMember(carol.myHash, from: g.id)
            XCTFail("must throw")
        } catch {
            XCTAssertEqual("\(error)", "ERR: only the creator can remove members")
        }
        // craft the control by hand (a modified client): alice and carol ignore it
        var forged = Wire.Payload(kind: "control", control: "group_remove")
        forged.group = Wire.GroupRef(id: g.id, epoch: 99)
        forged.member = Wire.MemberJSON(hash: carol.myHash)
        await bob.sendGroupControl(forged, to: alice.myHash, in: g.id)
        await bob.sendGroupControl(forged, to: carol.myHash, in: g.id)
        let ignoredNoted = await waitUntil {
            alice.messages[g.id]?.contains { $0.kind == .system && $0.text.contains("ignored a removal") } == true
        }
        XCTAssertTrue(ignoredNoted, "DEBUG build logs the ignored removal")
        XCTAssertEqual(alice.group(id: g.id)?.members.count, 3)
        XCTAssertEqual(carol.group(id: g.id)?.members.count, 3)
        XCTAssertFalse(carol.group(id: g.id)?.isLeft ?? true)

        // ---- rename by the creator ----
        try await alice.renameGroup(g.id, to: "ops2")
        let renamed = await waitUntil {
            bob.group(id: g.id)?.name == "ops2" && carol.group(id: g.id)?.name == "ops2"
        }
        XCTAssertTrue(renamed)
        XCTAssertTrue(bob.messages[g.id]?.contains { $0.text == "── renamed to #ops2 ──" } == true)

        // ---- burn_remove: bob screenshots -> dropped on every other phone ----
        await bob.reportGroupCapture(in: g.id)
        let dropped = await waitUntil {
            alice.group(id: g.id)?.isMember(bob.myHash) == false
            && carol.group(id: g.id)?.isMember(bob.myHash) == false
        }
        XCTAssertTrue(dropped, "burn_remove: the offender is removed on every phone, no control needed")
        XCTAssertTrue(alice.messages[g.id]?.contains { $0.text == "\u{26A0} <\(bob.myUsername)> took a screenshot" } == true)
        XCTAssertTrue(alice.messages[g.id]?.contains {
            $0.text == "── <\(bob.myUsername)> was removed for taking a screenshot ──" } == true)
        XCTAssertTrue(bob.group(id: g.id)?.isLeft ?? false, "the offender's own copy is burned + left")
        XCTAssertEqual(bob.messages[g.id]?.map(\.text), ["── this channel burned: you took a screenshot ──"])
        XCTAssertEqual(alice.group(id: g.id)?.members.count, 2)
        // bob's join index survives in history so his old lines keep their colour
        XCTAssertEqual(GroupMath.joinIndex(of: bob.myHash, in: alice.group(id: g.id)!),
                       GroupMath.joinIndex(of: bob.myHash, in: g))
        // the offender can no longer write
        await bob.sendText("still here?", toGroup: g.id)
        XCTAssertEqual(bob.lastError, "ERR: you are no longer in this group")
        // subsequent fan-out skips him
        await alice.sendText("after the drop", toGroup: g.id)
        let carolGot = await waitUntil { carol.messages[g.id]?.contains { $0.text == "after the drop" } == true }
        XCTAssertTrue(carolGot)
        let stillOne = await waitUntil(3) {
            alice.messages[g.id]?.first { $0.text == "after the drop" }?.receipts?.count == 1
        }
        XCTAssertTrue(stillOne, "DELIVERED 1/1 — bob is no longer a recipient")

        // ---- creator burns the group: gone on every member's phone ----
        try await alice.burnGroup(g.id)
        XCTAssertNil(alice.group(id: g.id))
        XCTAssertNil(alice.messages[g.id])
        let burnedEverywhere = await waitUntil {
            carol.group(id: g.id) == nil && carol.messages[g.id] == nil
        }
        XCTAssertTrue(burnedEverywhere, "rows, sidecars and roster destroyed on every member's phone")
        XCTAssertEqual(carol.homeFlash, "#ops2 was burned by the creator")
        XCTAssertNil(carol.store.blob.groups, "no groups -> key absent")
        XCTAssertNil(carol.store.readAttachment(id: carolImgAtt), "sidecars go with the thread")
        // bob was dropped before the burn: no longer a member, so the creator never
        // addresses him — his read-only, already-burned copy simply stays `left`
        XCTAssertTrue(bob.group(id: g.id)?.isLeft ?? false)
    }

    func testAddMemberAndLeaveAndPersistence() async throws {
        let (alice, bob, carol) = try await trio()
        defer { alice.goOffline(); bob.goOffline(); carol.goOffline() }
        // alice creates with bob only; bob (not the creator) adds carol
        let bobContact = try XCTUnwrap(alice.contacts.first { $0.hash == bob.myHash })
        let g = try await alice.createGroup(name: "duo", members: [bobContact])
        let bobHas = await waitUntil { bob.group(id: g.id) != nil }
        XCTAssertTrue(bobHas)
        try await bob.addContact(username: carol.myUsername)
        let carolContact = try XCTUnwrap(bob.contacts.first { $0.hash == carol.myHash })
        try await bob.addMember(carolContact, to: g.id)
        let added = await waitUntil {
            alice.group(id: g.id)?.isMember(carol.myHash) == true
            && carol.group(id: g.id)?.members.count == 3
        }
        XCTAssertTrue(added, "group_add to existing members + group_invite to the newcomer")
        XCTAssertEqual(alice.group(id: g.id)?.epoch, 1)
        XCTAssertEqual(carol.group(id: g.id)?.epoch, 1, "the invitee starts at the adder's epoch")
        XCTAssertTrue(alice.messages[g.id]?.contains { $0.text == "── <\(carol.myUsername)> joined ──" } == true)
        // the newcomer can speak to everyone
        await carol.sendText("thanks for the add", toGroup: g.id)
        let heard = await waitUntil {
            alice.messages[g.id]?.contains { $0.text == "thanks for the add" } == true
            && bob.messages[g.id]?.contains { $0.text == "thanks for the add" } == true
        }
        XCTAssertTrue(heard)
        // typing fans out per member
        bob.noteComposerActivity(in: g.id)
        let typing = await waitUntil { alice.typingNames(in: alice.group(id: g.id)!) == [bob.myUsername] }
        XCTAssertTrue(typing, "alice, bob are typing… strip source")
        // bob leaves: everyone else sees it; his copy is read-only
        try await bob.leaveGroup(g.id)
        let left = await waitUntil {
            alice.group(id: g.id)?.isMember(bob.myHash) == false
            && carol.group(id: g.id)?.isMember(bob.myHash) == false
        }
        XCTAssertTrue(left)
        XCTAssertTrue(carol.messages[g.id]?.contains { $0.text == "── <\(bob.myUsername)> left ──" } == true)
        XCTAssertTrue(bob.group(id: g.id)?.isLeft ?? false)
        // persistence: a cold unlock of carol's store has the group + rows
        carol.store.flush()
        let dir = carol.store.attachmentsDir.deletingLastPathComponent()
        let reopened = try SecureStore(unlockingWithPassword: "grp-test-pass",
                                       at: dir.appendingPathComponent("store"))
        XCTAssertEqual(reopened.blob.groups?.first?.id, g.id)
        XCTAssertEqual(reopened.blob.groups?.first?.members.count, 2)
        XCTAssertTrue(reopened.blob.messages[g.id]?.contains { $0.text == "thanks for the add" } == true)
        // full: 20 members max
        do {
            var many: [Contact] = []
            for i in 0..<20 {
                many.append(Contact(hash: String(format: "%064x", i + 1), name: "m\(i)", addedAt: Date()))
            }
            _ = try await alice.createGroup(name: "big", members: many)
            XCTFail("must refuse")
        } catch {
            XCTAssertEqual("\(error)", "ERR: group is full (20)")
        }
    }

    func testDeleteForAllAndUnknownUserMarksMemberGone() async throws {
        let (alice, bob, carol) = try await trio()
        defer { alice.goOffline(); bob.goOffline(); carol.goOffline() }
        let g = try await alice.createGroup(name: "trio", members: alice.contacts)
        let landed = await waitUntil { bob.group(id: g.id) != nil && carol.group(id: g.id) != nil }
        XCTAssertTrue(landed)
        await alice.sendText("retract me", toGroup: g.id)
        let id = try XCTUnwrap(alice.messages[g.id]?.first { $0.text == "retract me" }?.id)
        let arrived = await waitUntil {
            bob.messages[g.id]?.contains { $0.id == id } == true && carol.messages[g.id]?.contains { $0.id == id } == true
        }
        XCTAssertTrue(arrived)
        // author only: bob's forged delete naming alice's id touches nothing
        var forged = Wire.Payload(kind: "control", control: "delete_msg")
        forged.refIds = [id]
        forged.group = Wire.GroupRef(id: g.id, epoch: 0)
        await bob.sendGroupControl(forged, to: carol.myHash, in: g.id)
        try? await Task.sleep(for: .seconds(1))
        XCTAssertTrue(carol.messages[g.id]?.contains { $0.id == id } == true, "not bob's row")
        // the author retracts it everywhere
        let ok = await alice.deleteForAll(id: id, groupID: g.id)
        XCTAssertTrue(ok)
        let gone = await waitUntil {
            bob.messages[g.id]?.contains { $0.id == id } != true && carol.messages[g.id]?.contains { $0.id == id } != true
        }
        XCTAssertTrue(gone)
        XCTAssertNil(alice.messages[g.id]?.first { $0.id == id })

        // carol kills her account: the next fan-out marks her gone, the row still reaches bob
        await carol.deregister()
        carol.goOffline()
        try? await Task.sleep(for: .seconds(1))
        await alice.sendText("who is left?", toGroup: g.id)
        let goneNoted = await waitUntil {
            alice.group(id: g.id)?.member(carol.myHash)?.isGone == true
        }
        XCTAssertTrue(goneNoted, "unknown_user -> member gone")
        XCTAssertTrue(alice.messages[g.id]?.contains { $0.text == "── <\(carol.myUsername)> deleted their account ──" } == true)
        let bobGot = await waitUntil { bob.messages[g.id]?.contains { $0.text == "who is left?" } == true }
        XCTAssertTrue(bobGot)
        let sentNotFailed = await waitUntil {
            let r = alice.messages[g.id]?.first { $0.text == "who is left?" }
            return r?.status == .delivered && r?.receipts?.count == 1
        }
        XCTAssertTrue(sentNotFailed, "a gone member does not fail the row: DELIVERED 1/1")
    }
}
