import XCTest
import CryptoKit
@testable import PyroChat

/// Reverse group-fixture gate (docs/GROUPS.md §3): the store WRITTEN BY ANDROID
/// (`android/core-store` `AndroidGroupFixtureWriterTest` → `android/fixtures/android-store-groups/`)
/// is opened through the real `SecureStore` and its `#ops` group — 4 members (one `gone`),
/// 1 `former` member, `burn_remove`, epoch 2, a thread of 3 rows with `group` / `sender` /
/// `receipts` — is checked against the manifest and the §5 colour rule.
///
/// A NO-OP (XCTSkip) unless `PYRO_IMPORT_GROUPS_DIR` is set — from xcodebuild pass it as
/// `TEST_RUNNER_PYRO_IMPORT_GROUPS_DIR=<dir>`. TCC: the simulator's test host cannot read
/// files under ~/Desktop (open(2) blocks forever) — copy the fixture to the session
/// scratchpad first and point the variable at the copy.
final class GroupFixtureImportTests: XCTestCase {

    private static let referenceBase: Double = 780_000_000
    private func d(_ off: Double) -> Date { Date(timeIntervalSinceReferenceDate: Self.referenceBase + off) }

    func testImportAndroidGroupStoreFixture() throws {
        guard let inPath = ProcessInfo.processInfo.environment["PYRO_IMPORT_GROUPS_DIR"], !inPath.isEmpty else {
            throw XCTSkip("PYRO_IMPORT_GROUPS_DIR not set — Android group fixture import is opt-in")
        }
        let fm = FileManager.default
        let src = URL(fileURLWithPath: inPath, isDirectory: true)
        let manifest = try loadJSON(src.appendingPathComponent("manifest.json"))
        XCTAssertTrue((manifest["generator"] as? String)?.contains("android") == true, "an Android-written fixture")
        let storeInfo = try XCTUnwrap(manifest["store"] as? [String: Any])
        let gm = try XCTUnwrap(manifest["group"] as? [String: Any])
        let password = try XCTUnwrap(storeInfo["password"] as? String)
        let saltHex = try XCTUnwrap(storeInfo["kdfSaltHex"] as? String)

        // Keychain: pin the KDF salt to the fixture's; restore whatever was there on exit
        let prevSalt = KeychainHelper.get("kdf-salt")
        defer {
            if let prevSalt { KeychainHelper.set(prevSalt, key: "kdf-salt") }
            else { KeychainHelper.delete("kdf-salt") }
        }
        let salt = hexData(saltHex)
        XCTAssertEqual(salt.count, 32)
        KeychainHelper.set(salt, key: "kdf-salt")
        XCTAssertEqual(KeychainHelper.get("kdf-salt"), salt)

        // scratch copy: the checked-in fixture must never be re-saved in place
        let work = fm.temporaryDirectory.appendingPathComponent("pyro-group-import-\(UUID().uuidString)")
        try fm.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: work) }
        let storeURL = work.appendingPathComponent("pyro.store")
        try fm.copyItem(at: src.appendingPathComponent("pyro.store"), to: storeURL)
        let storeBytes = try Data(contentsOf: storeURL)
        XCTAssertEqual(sha256HexData(storeBytes), storeInfo["sha256"] as? String, "the fixture on disk is the one Android hashed")
        let plainBytes = try Data(contentsOf: src.appendingPathComponent("pyro.store.plaintext.json"))
        XCTAssertEqual(sha256HexData(plainBytes), storeInfo["plaintextSha256"] as? String)
        let plainJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: plainBytes) as? [String: Any])
        XCTAssertEqual((plainJSON["groups"] as? [Any])?.count, 1, "the published plaintext carries Blob.groups")

        // ---- the Android-written store unlocks through the real SecureStore ----
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "wrong password", at: storeURL))
        let store = try SecureStore(unlockingWithPassword: password, at: storeURL)
        let blob = store.blob
        XCTAssertEqual(blob.usernameDisplay, "alice_fixture")
        XCTAssertEqual(blob.usernameHash, sha256Hex("alice_fixture"))
        XCTAssertEqual(blob.contacts.count, 4, "the rest of the fixture (contacts, 1:1 threads) still decodes")
        XCTAssertEqual(blob.messages.count, 6, "5 peer threads + the group thread")
        XCTAssertEqual(blob.drafts?.count, 2)
        XCTAssertNotNil(blob.receiptOutbox)

        // ---- the group ----
        let groups = try XCTUnwrap(blob.groups, "Blob.groups must be present")
        XCTAssertEqual(groups.count, 1)
        let g = groups[0]
        let me = blob.usernameHash
        let bob = try XCTUnwrap(gm["creator"] as? String)
        let carol = try XCTUnwrap(gm["incomingSender"] as? String)
        XCTAssertEqual(g.id, gm["id"] as? String)
        XCTAssertEqual(g.name, "ops")
        XCTAssertEqual(g.name, gm["name"] as? String)
        XCTAssertEqual(g.creator, bob)
        XCTAssertEqual(g.creator, sha256Hex("bob_fixture"))
        XCTAssertEqual(carol, sha256Hex("carol_fixture"))
        XCTAssertFalse(g.isCreator(me), "the fixture owner is a plain member here")
        XCTAssertEqual(g.epoch, 2)
        XCTAssertEqual(g.epoch, gm["epoch"] as? Int)
        XCTAssertEqual(g.policy, .burnRemove)
        XCTAssertEqual(g.policy.rawValue, gm["policy"] as? String)
        XCTAssertEqual(g.burnSeconds, 3600)
        XCTAssertEqual(g.burnSeconds, (gm["burnSeconds"] as? NSNumber)?.doubleValue)
        XCTAssertEqual(g.createdAt, d(1000))
        XCTAssertNil(g.left, "absent unless removed / left")
        XCTAssertFalse(g.isLeft)

        // members: 4 incl. the owner and one `gone` account (relay said unknown_user)
        XCTAssertEqual(g.members.count, 4)
        XCTAssertEqual(g.members.count, gm["members"] as? Int)
        let dave = sha256Hex("dave_fixture")
        XCTAssertEqual(g.members.map(\.hash), [bob, carol, me, dave])
        XCTAssertEqual(g.members.map(\.name), ["bob", "carol", "alice_fixture", "dave"])
        XCTAssertEqual(g.members.map(\.joinedAt), [d(1000), d(1000), d(1000), d(1100)])
        XCTAssertEqual(g.members.map(\.gone), [nil, nil, nil, true], "`gone` survives only where Android wrote it")
        XCTAssertEqual(g.members.filter(\.isGone).count, gm["membersGone"] as? Int)
        XCTAssertTrue(g.isMember(dave), "a gone member keeps the roster slot (and colour)")
        XCTAssertEqual(g.member(dave)?.isGone, true)
        XCTAssertTrue(g.isMember(me))

        // former: one member who left, with the original joinedAt (colour history)
        let former = try XCTUnwrap(g.former, "the optional `former` list")
        XCTAssertEqual(former.count, 1)
        XCTAssertEqual(former.count, gm["former"] as? Int)
        let ghost = sha256Hex("ghost_fixture")
        XCTAssertEqual(former[0].hash, ghost)
        XCTAssertEqual(former[0].name, "ghost")
        XCTAssertEqual(former[0].joinedAt, d(1050))
        XCTAssertNil(former[0].gone)
        XCTAssertFalse(g.isMember(ghost))
        XCTAssertEqual(g.knownName(ghost), "ghost", "old rows from a former member keep a name")

        // §5 colours over members ∪ former, ordered by (joinedAt, hash), earliest join per hash:
        // the three who joined at createdAt sort by hash (1055… alice < 5506… bob < f6bd… carol),
        // then ghost (1050) then dave (1100) — the same rule Android's GroupColours applies.
        XCTAssertEqual(GroupMath.joinOrder(members: g.members, former: g.former), [me, bob, carol, ghost, dave])
        XCTAssertEqual(GroupMath.joinIndex(of: me, in: g), 0)
        XCTAssertEqual(GroupMath.joinIndex(of: bob, in: g), 1)
        XCTAssertEqual(GroupMath.joinIndex(of: carol, in: g), 2)
        XCTAssertEqual(GroupMath.joinIndex(of: ghost, in: g), 3, "a former member keeps its slot")
        XCTAssertEqual(GroupMath.joinIndex(of: dave, in: g), 4, "a gone member keeps its slot")
        XCTAssertEqual(GroupMath.colour(of: bob, in: g)?.hex, "#4fd8ff")
        XCTAssertEqual(GroupMath.colour(of: carol, in: g)?.hex, "#c792ff")
        XCTAssertEqual(GroupMath.colour(of: ghost, in: g)?.hex, "#ffd166")
        XCTAssertEqual(GroupMath.colour(of: dave, in: g)?.hex, "#ff8fab")

        // ---- the thread: keyed by the group id, 3 rows ----
        let rows = try XCTUnwrap(blob.messages[g.id], "messages are keyed by the group id")
        XCTAssertEqual(rows.count, 3)
        XCTAssertEqual(rows.count, gm["messages"] as? Int)
        XCTAssertTrue(rows.allSatisfy { $0.group == g.id }, "every row names its group")
        XCTAssertEqual(rows.map(\.id), ["6d000000-0000-4000-8000-000000000101",
                                        "6d000000-0000-4000-8000-000000000102",
                                        "6d000000-0000-4000-8000-000000000103"])

        XCTAssertEqual(rows[0].kind, .system)
        XCTAssertEqual(rows[0].direction, .incoming)
        XCTAssertEqual(rows[0].text, "── carol joined ──")
        XCTAssertEqual(rows[0].status, .delivered)
        XCTAssertEqual(rows[0].timestamp, d(1001))
        XCTAssertNil(rows[0].sender)
        XCTAssertNil(rows[0].receipts)

        XCTAssertEqual(rows[1].kind, .text)
        XCTAssertEqual(rows[1].direction, .incoming)
        XCTAssertEqual(rows[1].text, "ops channel up")
        XCTAssertEqual(rows[1].status, .read)
        XCTAssertEqual(rows[1].timestamp, d(1002))
        XCTAssertEqual(rows[1].sender, carol, "incoming group rows carry the sender's hash")
        XCTAssertEqual(rows[1].sender, gm["incomingSender"] as? String)
        XCTAssertNil(rows[1].receipts, "receipts live on outgoing rows only")
        XCTAssertNil(rows[1].burnAfter)

        XCTAssertEqual(rows[2].id, gm["outgoingWithReceipts"] as? String)
        XCTAssertEqual(rows[2].kind, .text)
        XCTAssertEqual(rows[2].direction, .outgoing)
        XCTAssertEqual(rows[2].text, "ack")
        XCTAssertEqual(rows[2].status, .sent)
        XCTAssertEqual(rows[2].timestamp, d(1003.5), "sub-second date survives the Android codec")
        XCTAssertEqual(rows[2].burnAfter, 3600)
        XCTAssertNil(rows[2].burnStartedAt)
        XCTAssertNil(rows[2].sender)
        let receipts = try XCTUnwrap(rows[2].receipts)
        XCTAssertEqual(receipts, [bob: "delivered", carol: "read"])

        // the aggregation the UI shows for that row: recipients = members except me, minus gone = bob + carol
        let recipients = g.members.filter { $0.hash != me && !$0.isGone }.count
        XCTAssertEqual(recipients, 2)
        XCTAssertEqual(GroupMath.statusWord(status: .sent, receipts: receipts, recipients: recipients, showRead: true), "READ 1/2")
        XCTAssertEqual(GroupMath.statusWord(status: .sent, receipts: receipts, recipients: recipients, showRead: false), "DELIVERED 2/2")
        XCTAssertEqual(GroupMath.rowStatus(sendStates: [], receipts: receipts, recipients: recipients, previous: .sent), .delivered,
                       "every recipient receipted, not all read → delivered")

        // ---- re-saved by the iOS codec, the Android-only-looking keys survive: former + gone ----
        store.update { $0.drafts = $0.drafts }
        store.flush()
        let reopened = try SecureStore(unlockingWithPassword: password, at: storeURL)
        XCTAssertEqual(reopened.blob.groups, groups, "former + gone + every §3 key round-trip through iOS")
        XCTAssertEqual(reopened.blob.messages[g.id], rows)
        XCTAssertEqual(reopened.blob.groups?.first?.former?.first?.hash, ghost)
        XCTAssertEqual(reopened.blob.groups?.first?.member(dave)?.gone, true)
    }

    // MARK: helpers (as in FixtureImportTests)

    private func loadJSON(_ url: URL) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
                      url.lastPathComponent)
    }

    private func hexData(_ hex: String) -> Data {
        var d = Data()
        var it = hex.makeIterator()
        while let a = it.next(), let b = it.next() {
            d.append(UInt8(String([a, b]), radix: 16)!)
        }
        return d
    }

    private func sha256HexData(_ d: Data) -> String {
        SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined()
    }
}
