import XCTest
@testable import PyroChat

/// The mixed-platform group gate (docs/GROUPS.md §8): TWO real iOS `ChatService`s
/// (`ios_a_<rand>`, `ios_b_<rand>`) and ONE Android JVM `ChatService`
/// (`android/core-service` `GroupInteropWithIosTest`) form `#gate` on the local dev
/// relay (127.0.0.1:7444) under `burn_remove`:
///
///   ios_a creates `#gate` with ios_b + the Android member → `from ios_a #1` →
///   Android answers `from android #1` + a JPEG (blob pipeline into two iOS phones) →
///   ios_a's row aggregates to `READ 2/2` → colour indices agree on every phone →
///   the Android member's `screenshot` removes it on BOTH iOS phones →
///   ios_a `group_burn`s the channel and ios_b's copy is gone.
///
/// Opt-in. `TEST_RUNNER_PYRO_GROUP_INTEROP` names the Android member — either the
/// username itself or the PATH of the file the Android side publishes its username
/// to (`groups-gate-android.txt`, polled up to 3 min). `TEST_RUNNER_PYRO_GROUP_NAMES_FILE`
/// is where THIS side publishes `ios_a\nios_b\n` for the Android side
/// (`groups-gate-ios.txt`). Both files must live OUTSIDE ~/Desktop — macOS TCC blocks
/// the simulator's test host there without a prompt (see FixtureImportTests).
@MainActor
final class GroupInteropTests: XCTestCase {

    private static let iosText = "from ios_a #1"
    private static let androidText = "from android #1"

    private struct GateFailure: Error, CustomStringConvertible { let description: String }

    private func gate(_ s: String) { print("GATE: \(s)") }

    private func makeService(_ prefix: String) -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-ggate-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "ggate-test-pass",
                                at: dir.appendingPathComponent("store"))
        // the Android side asserts READ from both iOS members — reciprocity toggle on
        store.update { $0.settings.sendReadReceipts = true }
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

    /// `PYRO_GROUP_INTEROP`: a username, or a file the Android side writes its username to.
    private func resolveAndroidName(_ spec: String) async throws -> String {
        guard spec.contains("/") else { return spec }
        gate("waiting for the Android username in \(spec) (up to 3 min)")
        let deadline = Date().addingTimeInterval(180)
        while Date() < deadline {
            if let s = try? String(contentsOfFile: spec, encoding: .utf8),
               let first = s.split(whereSeparator: \.isNewline).first {
                let name = first.trimmingCharacters(in: .whitespaces)
                if !name.isEmpty { return name }
            }
            try await Task.sleep(for: .seconds(1))
        }
        throw GateFailure(description: "the Android side never published its username to \(spec)")
    }

    /// Every incoming (non-system) row from `sender` in the thread gets the read-dwell
    /// trigger: read locally + READ receipt to the SENDER (with `group`) + burn start.
    private func markRead(_ svc: ChatService, thread: String, from sender: String) {
        for row in svc.messages[thread] ?? []
        where row.direction == .incoming && row.kind != .system && row.sender == sender && row.status != .read {
            svc.noteRowVisible(messageID: row.id, thread: thread)
        }
    }

    func testMixedGroupWithTwoIOSServicesAndOneAndroidMember() async throws {
        guard let spec = ProcessInfo.processInfo.environment["PYRO_GROUP_INTEROP"], !spec.isEmpty else {
            throw XCTSkip("PYRO_GROUP_INTEROP not set — the mixed iOS+Android group gate is opt-in")
        }
        let namesFile = ProcessInfo.processInfo.environment["PYRO_GROUP_NAMES_FILE"]

        // ---- two real iOS services ----
        let (a, aName) = makeService("ios_a")
        let (b, bName) = makeService("ios_b")
        try await a.register(username: aName)
        try await b.register(username: bName)
        defer { a.goOffline(); b.goOffline() }
        gate("registered ios_a=<\(aName)> hash=\(a.myHash.prefix(12))… ios_b=<\(bName)> hash=\(b.myHash.prefix(12))…")
        if let namesFile, !namesFile.isEmpty {
            try "\(aName)\n\(bName)\n".write(toFile: namesFile, atomically: true, encoding: .utf8)
            gate("published the iOS usernames to \(namesFile)")
        }

        // ---- the Android member: registers first, we poll until the relay knows it ----
        let androidName = try await resolveAndroidName(spec)
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
        gate("Android member online: <\(androidName)> hash=\(androidHash.prefix(12))… (prekey bundle processed)")
        try await a.addContact(username: bName)

        // ---- create #gate: burn_remove, both others as members ----
        let picked = a.contacts.filter { $0.name == bName || $0.name == androidName }
        XCTAssertEqual(picked.count, 2)
        let g = try await a.createGroup(name: "gate", members: picked, policy: .burnRemove)
        XCTAssertEqual(g.members.count, 3)
        // counted now: the Android member's screenshot (the last step) removes it, and that can
        // land before the READ 2/2 check below has run
        let everyoneElse = a.recipients(of: g).count
        XCTAssertEqual(everyoneElse, 2)
        XCTAssertEqual(g.creator, a.myHash)
        gate("created #gate id=\(g.id) epoch=\(g.epoch) policy=\(g.policy.rawValue) members=\(g.members.map(\.name))")
        let bCreated = await waitUntil(30) { b.group(id: g.id)?.members.count == 3 }
        XCTAssertTrue(bCreated, "group_create must land on ios_b")
        XCTAssertEqual(b.group(id: g.id)?.policy, .burnRemove)
        XCTAssertEqual(b.group(id: g.id)?.creator, a.myHash)
        gate("ios_b received group_create: #\(b.group(id: g.id)?.name ?? "?") members=\(b.group(id: g.id)?.members.map(\.name) ?? [])")

        // ---- from ios_a #1 ----
        await a.sendText(Self.iosText, toGroup: g.id)
        let ownRow = try XCTUnwrap(a.messages[g.id]?.first { $0.text == Self.iosText })
        gate("sent '\(Self.iosText)' id=\(ownRow.id) (fan-out to ios_b + Android)")
        let bGotIt = await waitUntil(30) { b.messages[g.id]?.contains { $0.id == ownRow.id } == true }
        XCTAssertTrue(bGotIt, "ios_b must file the row under the sender's id (refId)")
        markRead(b, thread: g.id, from: a.myHash)

        // ---- from android #1 + a JPEG land on BOTH iOS phones ----
        let androidTextArrived = await waitUntil(180) { [self] in
            markRead(a, thread: g.id, from: androidHash)
            markRead(b, thread: g.id, from: androidHash)
            return [a, b].allSatisfy { s in
                s.messages[g.id]?.contains { $0.kind == .text && $0.text == Self.androidText && $0.sender == androidHash } == true
            }
        }
        XCTAssertTrue(androidTextArrived, "'\(Self.androidText)' must reach both iOS phones")
        let aRow = try XCTUnwrap(a.messages[g.id]?.first { $0.text == Self.androidText })
        let bRow = try XCTUnwrap(b.messages[g.id]?.first { $0.text == Self.androidText })
        XCTAssertEqual(aRow.id, bRow.id, "the Android row id (refId) is the same on every phone")
        XCTAssertEqual(aRow.group, g.id)
        XCTAssertNil(a.messages[androidHash]?.first { $0.text == Self.androidText }, "never in the 1:1 thread")
        gate("'\(Self.androidText)' arrived on ios_a and ios_b, sender=\(androidHash.prefix(12))… id=\(aRow.id)")

        let imageArrived = await waitUntil(180) { [self] in
            markRead(a, thread: g.id, from: androidHash)
            markRead(b, thread: g.id, from: androidHash)
            return [a, b].allSatisfy { s in
                s.messages[g.id]?.contains { $0.kind == .image && $0.sender == androidHash } == true
            }
        }
        XCTAssertTrue(imageArrived, "the Android JPEG (blob pipeline) must reach both iOS phones")
        let aImg = try XCTUnwrap(a.messages[g.id]?.first { $0.kind == .image })
        let bImg = try XCTUnwrap(b.messages[g.id]?.first { $0.kind == .image })
        let aBytes = try XCTUnwrap(a.attachmentData(for: aImg))
        let bBytes = try XCTUnwrap(b.attachmentData(for: bImg))
        XCTAssertEqual(aBytes, bBytes, "same plaintext on every phone")
        XCTAssertEqual(aImg.id, bImg.id)
        XCTAssertEqual(aImg.fileMime, "image/jpeg")
        XCTAssertEqual(Array(aBytes.prefix(2)), [0xFF, 0xD8], "a real JPEG (SOI marker)")
        XCTAssertEqual(aImg.fileBytes, aBytes.count)
        markRead(a, thread: g.id, from: androidHash)
        markRead(b, thread: g.id, from: androidHash)
        gate("Android image arrived on both: \(aBytes.count) B \(aImg.imageWidth ?? 0)x\(aImg.imageHeight ?? 0) id=\(aImg.id)")

        // ---- receipts aggregate on ios_a's row: READ 2/2 ----
        let read2 = await waitUntil(120) {
            a.messages[g.id]?.first { $0.id == ownRow.id }?.receipts?.values.filter { $0 == "read" }.count == 2
        }
        XCTAssertTrue(read2, "READ receipts from ios_b AND the Android member")
        let mine = try XCTUnwrap(a.messages[g.id]?.first { $0.id == ownRow.id })
        for m in a.group(id: g.id)!.members where m.hash != a.myHash {
            gate("ios_a counts <\(m.name)> as a recipient: gone=\(m.isGone) blocked=\(a.blockedHashes.contains(m.hash))")
        }
        gate("ios_a system rows so far: \((a.messages[g.id] ?? []).filter { $0.kind == .system }.map(\.text))")
        let word = GroupMath.statusWord(status: mine.status, receipts: mine.receipts ?? [:],
                                        recipients: everyoneElse, showRead: true)
        XCTAssertEqual(word, "READ 2/2")
        XCTAssertEqual(mine.status, .read)
        XCTAssertEqual(mine.receipts?[androidHash], "read")
        XCTAssertEqual(mine.receipts?[b.myHash], "read")
        gate("ios_a row '\(Self.iosText)' → \(word) receipts=\(mine.receipts ?? [:])")

        // ---- colour indices for all three, on both iOS phones ----
        func colours(_ s: ChatService, _ label: String) -> [String: Int] {
            let grp = s.group(id: g.id)!
            var out: [String: Int] = [:]
            for h in GroupMath.joinOrder(members: grp.members, former: grp.former) {
                let i = GroupMath.joinIndex(of: h, in: grp)!
                out[h] = i
                gate("colour on \(label): <\(s.displayName(for: h, in: grp))> hash=\(h) index=\(i) hex=\(GroupMath.colour(joinIndex: i).hex)")
            }
            return out
        }
        let coloursA = colours(a, "ios_a")
        let coloursB = colours(b, "ios_b")
        XCTAssertEqual(coloursA, coloursB, "identical colour indices on both iOS phones")
        XCTAssertEqual(coloursA.count, 3)

        // ---- the Android member screenshots: removed on BOTH iOS phones (burn_remove) ----
        gate("waiting for the Android member's screenshot control (up to 4 min)")
        let removed = await waitUntil(240) {
            a.group(id: g.id)?.isMember(androidHash) == false && b.group(id: g.id)?.isMember(androidHash) == false
        }
        XCTAssertTrue(removed, "burn_remove: the offender is dropped on every other phone, no control needed")
        for (s, label) in [(a, "ios_a"), (b, "ios_b")] {
            let texts = (s.messages[g.id] ?? []).filter { $0.kind == .system }.map(\.text)
            XCTAssertTrue(texts.contains("\u{26A0} <\(androidName)> took a screenshot"), "\(label): \(texts)")
            XCTAssertTrue(texts.contains("── <\(androidName)> was removed for taking a screenshot ──"), "\(label): \(texts)")
            let grp = try XCTUnwrap(s.group(id: g.id))
            XCTAssertEqual(grp.members.count, 2, label)
            XCTAssertEqual(grp.epoch, 1, label)
            XCTAssertEqual(grp.former?.map(\.hash), [androidHash], "\(label): the offender keeps a colour slot in `former`")
            XCTAssertEqual(GroupMath.joinIndex(of: androidHash, in: grp), coloursA[androidHash], "\(label): nobody recolours")
            gate("\(label): Android member removed — members=\(grp.members.map(\.name)) epoch=\(grp.epoch) rows=\(texts)")
        }

        // ---- ios_a burns the group: ios_b's copy is destroyed ----
        try await a.burnGroup(g.id)
        XCTAssertNil(a.group(id: g.id))
        XCTAssertNil(a.messages[g.id])
        let burned = await waitUntil(30) { b.group(id: g.id) == nil && b.messages[g.id] == nil }
        XCTAssertTrue(burned, "group_burn destroys rows + roster on ios_b")
        XCTAssertEqual(b.homeFlash, "#gate was burned by the creator")
        XCTAssertNil(b.store.readAttachment(id: bImg.attachmentId ?? ""), "sidecars go with the thread")
        gate("burned #gate from ios_a — ios_b group=nil thread=nil flash='\(b.homeFlash ?? "")'")
        gate("PASSED")
    }
}
