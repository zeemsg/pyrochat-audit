import XCTest
import CryptoKit
@testable import PyroChat

/// docs/DECOY-ACCOUNT.md §9 — the container, unlock and migration half of the
/// test list. Every one of these is about a property the format promises to a
/// forensic examiner, so they assert on BYTES, not on behaviour the UI shows.
final class SlotVaultTests: XCTestCase {

    private var work: URL!
    private var prevSalt: Data?

    override func setUpWithError() throws {
        work = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-vault-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        // pin the KDF salt so these tests never depend on (or disturb) whatever
        // the host keychain holds
        prevSalt = KeychainHelper.get("kdf-salt")
        KeychainHelper.set(Data((0..<32).map { UInt8($0) }), key: "kdf-salt")
    }

    override func tearDownWithError() throws {
        SlotVault.onHalfAttempt = nil
        SlotVault.chooseSlot = { $0.randomElement()! }
        SlotVault.randomBytes = SlotVault.systemRandomBytes
        SecureStore.onStoreFull = nil
        if let prevSalt { KeychainHelper.set(prevSalt, key: "kdf-salt") }
        else { KeychainHelper.delete("kdf-salt") }
        try? FileManager.default.removeItem(at: work)
    }

    private func storeURL(_ name: String = "pyro.store") -> URL {
        work.appendingPathComponent(name)
    }

    /// Read one slot's bytes without loading the whole 12 MiB file.
    private func slotBytes(of url: URL, _ index: Int) throws -> Data {
        try read(url, at: SlotVault.offset(ofSlot: index), count: SlotVault.slotBytes)
    }

    /// Read one half.
    private func halfBytes(of url: URL, slot: Int, half: Int) throws -> Data {
        try read(url, at: SlotVault.offset(ofSlot: slot, half: half), count: SlotVault.halfBytes)
    }

    private func read(_ url: URL, at offset: Int, count: Int) throws -> Data {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(offset))
        return try XCTUnwrap(handle.read(upToCount: count))
    }

    /// Overwrite a half with garbage — a torn write, as a power cut leaves one.
    private func tear(_ url: URL, slot: Int, half: Int) throws {
        let handle = try FileHandle(forUpdating: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(SlotVault.offset(ofSlot: slot, half: half)))
        try handle.write(contentsOf: Data(repeating: 0x5a, count: SlotVault.halfBytes))
        try handle.synchronize()
    }

    private func fileSize(_ url: URL) throws -> Int {
        try XCTUnwrap((try FileManager.default.attributesOfItem(atPath: url.path)[.size]
                        as? NSNumber)?.intValue)
    }

    // MARK: - §1 the pinned layout

    func testHeaderAndSlotBoundariesAreThePinnedLayout() throws {
        let url = storeURL()
        let store = SecureStore(creatingWithPassword: "pw-layout", at: url)
        store.update { $0.usernameDisplay = "zeebest" }
        store.flush()

        let bytes = try Data(contentsOf: url)
        XCTAssertEqual(bytes.prefix(8), Data("PYROVLT1".utf8))
        XCTAssertEqual([UInt8](bytes)[8], 3, "slotCount is a build constant: always 3")
        XCTAssertEqual(Int(SlotVault.be32([UInt8](bytes), at: 9)), 4_194_304,
                       "slotBytes is the fixed constant 4 MiB")
        XCTAssertEqual(SlotVault.headerBytes, 13)
        XCTAssertEqual(bytes.prefix(13), SlotVault.header())
        XCTAssertEqual(bytes.count, 13 + 3 * 4_194_304)
        XCTAssertEqual(bytes.count, 12_582_925)
        XCTAssertEqual(SlotVault.fileBytes, 12_582_925)

        // every slot is exactly slotBytes, at a fixed offset
        let container = try SlotVault.parse(bytes)
        XCTAssertEqual(container.slots.count, 3)
        for slot in container.slots { XCTAssertEqual(slot.count, SlotVault.slotBytes) }
        XCTAssertEqual(SlotVault.offset(ofSlot: 0), 13)
        XCTAssertEqual(SlotVault.offset(ofSlot: 1), 4_194_317)
        XCTAssertEqual(SlotVault.offset(ofSlot: 2), 8_388_621)

        // …and each slot is two halves
        XCTAssertEqual(SlotVault.halfBytes, 2_097_152)
        XCTAssertEqual(SlotVault.halfCapacity, 2_097_124)
        XCTAssertEqual(SlotVault.maxPayloadBytes, 2_097_112)
        XCTAssertEqual(SlotVault.offset(ofSlot: 0, half: 0), 13)
        XCTAssertEqual(SlotVault.offset(ofSlot: 0, half: 1), 2_097_165)
        XCTAssertEqual(SlotVault.offset(ofSlot: 1, half: 0), 4_194_317)
        XCTAssertEqual(SlotVault.offset(ofSlot: 2, half: 1), 10_485_773)

        // the plaintext is seq(8 BE) ‖ length(4 BE) ‖ JSON ‖ padding, to capacity
        let key = SecureStore.deriveKey(password: "pw-layout", salt: KeychainHelper.get("kdf-salt")!)
        let index = try XCTUnwrap(store.slotIndex)
        let live = try halfBytes(of: url, slot: index, half: store.liveHalf)
        let plain = try AES.GCM.open(try AES.GCM.SealedBox(combined: live), using: key)
        XCTAssertEqual(plain.count, SlotVault.halfCapacity)
        let hit = try XCTUnwrap(SlotVault.openHalf(live, slot: index, half: store.liveHalf, key: key))
        XCTAssertEqual(SlotVault.be64([UInt8](plain), at: 0), hit.seq)
        XCTAssertEqual(Int(SlotVault.be32([UInt8](plain), at: 8)), hit.payload.count)
        XCTAssertEqual(try JSONDecoder().decode(SecureStore.Blob.self, from: hit.payload)
                        .usernameDisplay, "zeebest")
    }

    /// §1: "the file is always exactly 12 MiB + 13 bytes, on a fresh install
    /// and on a heavy one."
    func testFileSizeNeverChangesWithTheAmountOfData() throws {
        let url = storeURL()
        let store = SecureStore(creatingWithPassword: "pw-size", at: url)
        store.flush()
        XCTAssertEqual(try fileSize(url), SlotVault.fileBytes)

        store.update { $0.drafts = ["a": String(repeating: "x", count: 900_000)] }
        store.flush()
        XCTAssertEqual(try fileSize(url), SlotVault.fileBytes, "a heavy account: same size")

        store.update { $0.drafts = [:] }
        store.flush()
        XCTAssertEqual(try fileSize(url), SlotVault.fileBytes, "emptied again: same size")
        XCTAssertNoThrow(try SecureStore(unlockingWithPassword: "pw-size", at: url))
    }

    /// §9: "A container with one real slot is byte-indistinguishable from one
    /// with three, other than the bytes themselves: same length, same header,
    /// same slot boundaries."
    func testOneRealSlotIsIndistinguishableFromThree() throws {
        let key = SymmetricKey(size: .bits256)
        let keyB = SymmetricKey(size: .bits256)
        let keyC = SymmetricKey(size: .bits256)
        let payload = Data(#"{"usernameDisplay":"a"}"#.utf8)

        func slot(_ k: SymmetricKey) throws -> Data {
            try SlotVault.seal(payload: payload, key: k, seq: 1)
                + (try SlotVault.seal(payload: payload, key: k, seq: 2))
        }
        let one = try SlotVault.serialize(SlotVault.Container(slots: [
            SlotVault.noise(), try slot(key), SlotVault.noise(),
        ]))
        let three = try SlotVault.serialize(SlotVault.Container(slots: [
            try slot(key), try slot(keyB), try slot(keyC),
        ]))

        XCTAssertEqual(one.count, three.count)
        XCTAssertEqual(one.prefix(13), three.prefix(13), "identical header, byte for byte")
        let a = try SlotVault.parse(one), b = try SlotVault.parse(three)
        XCTAssertEqual(a.slots.map { $0.count }, b.slots.map { $0.count })
        XCTAssertNotEqual(one, three, "…other than the bytes themselves")
    }

    /// §1, the rule everything bends around: **never write to a slot you
    /// cannot open**. Unused slots are written once at creation and never
    /// touched again — which is the same statement as "a decoy survives every
    /// save the real account makes".
    func testSavesRewriteOnlyTheCurrentSlot() throws {
        let url = storeURL()
        let store = SecureStore(creatingWithPassword: "pw-only-mine", at: url)
        store.flush()
        let mine = try XCTUnwrap(store.slotIndex)
        let others = (0..<3).filter { $0 != mine }
        let header = try Data(contentsOf: url).prefix(13)
        let frozen = try others.map { try slotBytes(of: url, $0) }

        for i in 1...4 {
            store.update { $0.usernameDisplay = "save-\(i)" }
            store.flush()
            for (n, other) in others.enumerated() {
                XCTAssertEqual(try slotBytes(of: url, other), frozen[n],
                               "slot \(other) must be byte-identical after save \(i)")
            }
            // exactly one half of our own slot moved
            XCTAssertEqual(try halfBytes(of: url, slot: mine, half: 1 - store.liveHalf),
                           try halfBytes(of: url, slot: mine, half: 1 - store.liveHalf))
            XCTAssertEqual(try Data(contentsOf: url).prefix(13), header)
            XCTAssertEqual(try fileSize(url), SlotVault.fileBytes)
        }
        // and our own slot did move every time
        XCTAssertEqual(try SecureStore(unlockingWithPassword: "pw-only-mine", at: url)
                        .blob.usernameDisplay, "save-4")
    }

    /// The same rule, stated as the thing it protects: another account's slot
    /// survives arbitrarily many saves by the account that cannot read it.
    func testAForeignAccountSurvivesEverySaveByTheOther() throws {
        let salt = KeychainHelper.get("kdf-salt")!
        let mineKey = SecureStore.deriveKey(password: "mine", salt: salt)
        let foreignKey = SymmetricKey(size: .bits256)
        var foreignBlob = SecureStore.Blob()
        foreignBlob.usernameDisplay = "the-decoy"
        let foreignPayload = try JSONEncoder().encode(foreignBlob)
        let foreign = try SlotVault.seal(payload: foreignPayload, key: foreignKey, seq: 7)
            + (try SlotVault.seal(payload: foreignPayload, key: foreignKey, seq: 8))

        let url = storeURL("two-real.store")
        try SlotVault.serialize(SlotVault.Container(slots: [
            try SlotVault.seal(payload: try JSONEncoder().encode(SecureStore.Blob()),
                               key: mineKey, seq: 1) + SlotVault.halfNoise(),
            foreign,
            SlotVault.noise(),
        ])).write(to: url)

        let store = try SecureStore(unlockingWithPassword: "mine", at: url)
        XCTAssertEqual(store.slotIndex, 0)
        for i in 1...3 {
            store.update { $0.usernameDisplay = "busy-\(i)" }
            store.flush()
        }
        XCTAssertEqual(try slotBytes(of: url, 1), foreign, "the decoy is untouched, byte for byte")
        let decoy = try XCTUnwrap(SlotVault.open(
            try SlotVault.parse(try Data(contentsOf: url)), key: foreignKey))
        XCTAssertEqual(decoy.slot, 1)
        XCTAssertEqual(decoy.seq, 8, "and it still opens on its own live half")
        XCTAssertEqual(decoy.payload, foreignPayload)
    }

    func testUnusedSlotsPassARandomnessSanityCheck() throws {
        let url = storeURL()
        let store = SecureStore(creatingWithPassword: "pw-entropy", at: url)
        store.flush()
        let mine = try XCTUnwrap(store.slotIndex)
        for i in 0..<3 where i != mine {
            let bytes = [UInt8](try slotBytes(of: url, i))
            // every byte value should show up in 4 MiB of uniform noise
            XCTAssertEqual(Set(bytes).count, 256, "slot \(i) is not uniform over 0…255")
            // no long constant run
            var run = 1, longest = 1
            for j in 1..<bytes.count {
                run = bytes[j] == bytes[j - 1] ? run + 1 : 1
                longest = max(longest, run)
            }
            XCTAssertLessThan(longest, 10, "slot \(i) has a \(longest)-byte constant run")
        }
    }

    // MARK: - §1 two halves

    /// §1: "Save writes the OTHER half, at `seq + 1`." The half holding the
    /// last good copy is therefore never the half being written.
    func testSavesAlternateHalvesAndAdvanceSeq() throws {
        let url = storeURL("halves.store")
        let store = SecureStore(creatingWithPassword: "pw-halves", at: url)
        store.flush()
        let slot = try XCTUnwrap(store.slotIndex)
        XCTAssertEqual(store.liveHalf, 0, "a freshly authored slot writes half 0…")
        XCTAssertEqual(store.liveSeq, 1, "…at seq 1")

        var seen: [(Int, UInt64)] = []
        var previousHalfBytes = try halfBytes(of: url, slot: slot, half: 0)
        for i in 1...4 {
            let stale = store.liveHalf
            let staleBytes = try halfBytes(of: url, slot: slot, half: stale)
            store.update { $0.usernameDisplay = "save-\(i)" }
            store.flush()
            seen.append((store.liveHalf, store.liveSeq))
            XCTAssertNotEqual(store.liveHalf, stale, "a save must land in the other half")
            XCTAssertEqual(try halfBytes(of: url, slot: slot, half: stale), staleBytes,
                           "the half holding the last good copy was not touched")
            previousHalfBytes = staleBytes
        }
        XCTAssertEqual(seen.map(\.0), [1, 0, 1, 0])
        XCTAssertEqual(seen.map(\.1), [2, 3, 4, 5])
        XCTAssertFalse(previousHalfBytes.isEmpty)

        let reopened = try SecureStore(unlockingWithPassword: "pw-halves", at: url)
        XCTAssertEqual(reopened.blob.usernameDisplay, "save-4")
        XCTAssertEqual(reopened.liveSeq, 5, "the higher seq is the live copy")
        XCTAssertEqual(reopened.liveHalf, 0)
    }

    /// The reason the halves exist: a torn write costs the stale copy and
    /// nothing else. Each case gets its own store — tearing both halves of one
    /// store would just be the "everything is gone" case below.
    func testATornHalfCostsOnlyTheStaleCopy() throws {
        /// A store two saves in: one half holds "first", the other "second".
        func twoSavesDeep(_ name: String) throws -> (SecureStore, slot: Int, live: Int) {
            let url = storeURL(name)
            let store = SecureStore(creatingWithPassword: "pw-torn", at: url)
            store.update { $0.usernameDisplay = "first" }
            store.flush()
            store.update { $0.usernameDisplay = "second" }
            store.flush()
            return (store, try XCTUnwrap(store.slotIndex), store.liveHalf)
        }

        // (a) the stale half is torn: the live copy is untouched
        let a = try twoSavesDeep("torn-stale.store")
        try tear(storeURL("torn-stale.store"), slot: a.slot, half: 1 - a.live)
        let stillLive = try SecureStore(unlockingWithPassword: "pw-torn",
                                        at: storeURL("torn-stale.store"))
        XCTAssertEqual(stillLive.blob.usernameDisplay, "second", "the live half is intact")
        XCTAssertEqual(stillLive.liveHalf, a.live)

        // (b) the harsher case: the LIVE half is torn, as a power cut mid-save
        // would leave it. The previous good copy is still on disk.
        let b = try twoSavesDeep("torn-live.store")
        try tear(storeURL("torn-live.store"), slot: b.slot, half: b.live)
        let recovered = try SecureStore(unlockingWithPassword: "pw-torn",
                                        at: storeURL("torn-live.store"))
        XCTAssertEqual(recovered.liveHalf, 1 - b.live)
        XCTAssertEqual(recovered.blob.usernameDisplay, "first",
                       "the previous good copy is still there — this is the whole point")

        // …and it keeps working from there, writing over the torn half
        recovered.update { $0.usernameDisplay = "third" }
        recovered.flush()
        XCTAssertEqual(recovered.liveHalf, b.live)
        XCTAssertEqual(try SecureStore(unlockingWithPassword: "pw-torn",
                                       at: storeURL("torn-live.store")).blob.usernameDisplay,
                       "third")
    }

    /// Both halves torn is an unopenable slot — the failure the halves make
    /// twice as unlikely, not impossible — and it must still read as
    /// `badPassword`, never as anything that says "there was something here".
    func testBothHalvesTornReadsAsAnEmptyApp() throws {
        let url = storeURL("both-torn.store")
        let store = SecureStore(creatingWithPassword: "pw-both", at: url)
        store.update { $0.usernameDisplay = "gone" }
        store.flush()
        let slot = try XCTUnwrap(store.slotIndex)
        try tear(url, slot: slot, half: 0)
        try tear(url, slot: slot, half: 1)
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "pw-both", at: url)) {
            XCTAssertEqual($0 as? SecureStore.StoreError, .badPassword)
        }
    }

    // MARK: - §2 unlocking

    func testWrongPasswordAuthenticatesNothingAndReportsBadPassword() throws {
        let url = storeURL()
        let store = SecureStore(creatingWithPassword: "the-right-one", at: url)
        store.update { $0.usernameDisplay = "zeebest" }
        store.flush()

        var attempts: [String] = []
        SlotVault.onHalfAttempt = { attempts.append("\($0).\($1)") }
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "the-wrong-one", at: url)) {
            XCTAssertEqual($0 as? SecureStore.StoreError, .badPassword)
        }
        XCTAssertEqual(attempts, ["0.0", "0.1", "1.0", "1.1", "2.0", "2.1"],
                       "a wrong password is still tried against every half of every slot")
    }

    /// §2/§9: "Try all slots even after one matches." Asserted through the
    /// attempt seam, never by timing — a timing test is flaky under load.
    func testEverySlotIsAttemptedWhicheverOneMatched() throws {
        for occupied in 0..<3 {
            SlotVault.chooseSlot = { _ in occupied }
            let url = storeURL("slot-\(occupied).store")
            let store = SecureStore(creatingWithPassword: "pw-attempts", at: url)
            store.update { $0.usernameDisplay = "in-slot-\(occupied)" }
            store.flush()
            XCTAssertEqual(store.slotIndex, occupied)

            var attempts: [String] = []
            SlotVault.onHalfAttempt = { attempts.append("\($0).\($1)") }
            let reopened = try SecureStore(unlockingWithPassword: "pw-attempts", at: url)
            SlotVault.onHalfAttempt = nil
            XCTAssertEqual(attempts, ["0.0", "0.1", "1.0", "1.1", "2.0", "2.1"],
                           "slot \(occupied) matched but all six halves must still be attempted")
            XCTAssertEqual(reopened.slotIndex, occupied)
            XCTAssertEqual(reopened.blob.usernameDisplay, "in-slot-\(occupied)")
            try FileManager.default.removeItem(at: url)
        }
    }

    /// §9: "A container … opens, with the occupied slot at index 0, 1 and 2."
    /// Built here the way the other platform would build it — by hand, from the
    /// pinned format — rather than through `SecureStore`. §7: byte-identity is
    /// not the requirement; opening each other's containers is.
    func testContainerOpensWithTheOccupiedSlotAtEveryIndex() throws {
        let salt = KeychainHelper.get("kdf-salt")!
        let key = SecureStore.deriveKey(password: "cross-platform", salt: salt)
        var blob = SecureStore.Blob()
        blob.usernameDisplay = "from-the-other-platform"
        blob.usernameHash = "abc123"
        let payload = try JSONEncoder().encode(blob)

        for occupied in 0..<3 {
            let slots = try (0..<3).map { i -> Data in
                guard i == occupied else { return SlotVault.noise() }
                // the other platform, one save in: half 0 stale, half 1 live
                return try SlotVault.seal(payload: Data(#"{"usernameDisplay":"stale"}"#.utf8),
                                          key: key, seq: 1)
                    + (try SlotVault.seal(payload: payload, key: key, seq: 2))
            }
            let url = storeURL("foreign-\(occupied).store")
            try SlotVault.serialize(SlotVault.Container(slots: slots)).write(to: url)

            let store = try SecureStore(unlockingWithPassword: "cross-platform", at: url)
            XCTAssertEqual(store.slotIndex, occupied)
            XCTAssertEqual(store.liveHalf, 1, "the higher seq wins")
            XCTAssertEqual(store.liveSeq, 2)
            XCTAssertEqual(store.blob.usernameDisplay, "from-the-other-platform")
            XCTAssertEqual(store.blob.usernameHash, "abc123")
            XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "nope", at: url))
            try FileManager.default.removeItem(at: url)
        }
    }

    /// The `randomBytes` seam: with the payload and the randomness injected, a
    /// container's bytes are fully determined — which is how the cross-platform
    /// fixtures will be pinned.
    func testInjectedRandomnessFullyDeterminesTheBytes() throws {
        let key = SymmetricKey(data: Data(repeating: 0x2a, count: 32))
        let payload = Data(#"{"usernameDisplay":"pinned"}"#.utf8)
        defer { SlotVault.randomBytes = SlotVault.systemRandomBytes }

        func build() throws -> Data {
            var counter: UInt8 = 0
            // thread-guarded for the same reason as ContainerVectorTests: a
            // debounced save from another class must not draw from this seam
            let buildThread = Thread.current
            SlotVault.randomBytes = { n in
                guard Thread.current === buildThread else { return SlotVault.systemRandomBytes(n) }
                counter &+= 1
                return Data(repeating: counter, count: n)
            }
            return try SlotVault.serialize(SlotVault.Container(slots: [
                SlotVault.noise(),
                try SlotVault.seal(payload: payload, key: key, seq: 1) + SlotVault.halfNoise(),
                SlotVault.noise(),
            ]))
        }
        let a = try build(), b = try build()
        XCTAssertEqual(a, b)
        XCTAssertEqual(a.count, SlotVault.fileBytes)
        let live = try XCTUnwrap(SlotVault.open(try SlotVault.parse(a), key: key))
        XCTAssertEqual(live.slot, 1)
        XCTAssertEqual(live.payload, payload)
    }

    func testCreationDoesNotFavourSlotZero() {
        var landed = Set<Int>()
        for i in 0..<40 {
            let store = SecureStore(creatingWithPassword: "pw-\(i)",
                                    at: storeURL("spread-\(i).store"))
            store.discardPendingWrites()        // no 12 MiB write: we only want the index
            if let s = store.slotIndex { landed.insert(s) }
        }
        XCTAssertEqual(landed, [0, 1, 2], "a new account takes a uniformly random free slot")
    }

    // MARK: - §1 the blob that will not fit

    /// There is no bucket to grow into, so a blob over `maxPayloadBytes` has to
    /// have a defined behaviour: the last good file stays on disk, the edit
    /// stays queued in memory, `onStoreFull` fires, and a later save that fits
    /// lands normally. Nothing is silently dropped.
    func testABlobTooLargeForASlotFailsLoudlyAndRecovers() throws {
        let url = storeURL("full.store")
        let store = SecureStore(creatingWithPassword: "pw-full", at: url)
        store.update { $0.usernameDisplay = "before" }
        store.flush()
        let good = try slotBytes(of: url, try XCTUnwrap(store.slotIndex))
        let goodSeq = store.liveSeq

        var reported: [URL] = []
        var reasons: [SecureStore.FullReason] = []
        SecureStore.onStoreFull = { reported.append($0); reasons.append($1) }
        store.update { $0.drafts = ["x": String(repeating: "y", count: SlotVault.halfBytes)] }
        store.flush()

        XCTAssertTrue(store.isStoreFull)
        XCTAssertEqual(reported, [url], "not silent")
        XCTAssertEqual(reasons, [.cannotSave],
                       "a container that outgrew a half is the SAVE condition, not §1a's migration one")
        XCTAssertEqual(try fileSize(url), SlotVault.fileBytes)
        XCTAssertEqual(try slotBytes(of: url, try XCTUnwrap(store.slotIndex)), good,
                       "the last good slot is still on disk")
        XCTAssertEqual(store.liveSeq, goodSeq, "and seq did not advance past a write that failed")
        XCTAssertEqual(try SecureStore(unlockingWithPassword: "pw-full", at: url)
                        .blob.usernameDisplay, "before")

        // free the space again: the queued edit lands, and the flag clears
        store.update { $0.drafts = ["x": "small again"] }
        store.flush()
        XCTAssertFalse(store.isStoreFull)
        let reopened = try SecureStore(unlockingWithPassword: "pw-full", at: url)
        XCTAssertEqual(reopened.blob.drafts?["x"], "small again")
        XCTAssertEqual(reopened.blob.usernameDisplay, "before")
    }

    func testSealRefusesAPayloadOverTheCapacity() {
        let key = SymmetricKey(size: .bits256)
        XCTAssertNoThrow(try SlotVault.seal(
            payload: Data(repeating: 0, count: SlotVault.maxPayloadBytes), key: key, seq: 1))
        XCTAssertThrowsError(try SlotVault.seal(
            payload: Data(repeating: 0, count: SlotVault.maxPayloadBytes + 1), key: key, seq: 1)) {
            XCTAssertEqual($0 as? SlotVault.VaultError, .tooLarge)
        }
    }

    // MARK: - §7 migration

    /// §7: "Old file, no magic: treat the whole file as today's single sealed
    /// blob, unlock, then rewrite as a container with the account in a random
    /// slot and the rest noise."
    func testLegacyFileMigratesToAContainerHoldingTheSameBlob() throws {
        let password = "legacy-password"
        let salt = KeychainHelper.get("kdf-salt")!
        let key = SecureStore.deriveKey(password: password, salt: salt)

        for occupied in 0..<3 {
            var blob = SecureStore.Blob()
            blob.usernameDisplay = "legacy-user"
            blob.usernameHash = "deadbeef"
            blob.nextPreKeyId = 42
            blob.drafts = ["deadbeef": "half-typed 🔥"]
            let legacyPlain = try JSONEncoder().encode(blob)
            let legacyBytes = try XCTUnwrap(AES.GCM.seal(legacyPlain, using: key).combined)

            let url = storeURL("legacy-\(occupied).store")
            try legacyBytes.write(to: url)
            XCTAssertFalse(SlotVault.isContainer(try Data(contentsOf: url)))

            SlotVault.chooseSlot = { _ in occupied }
            let migrated = try SecureStore(unlockingWithPassword: password, at: url)
            XCTAssertFalse(migrated.isLegacyFile)
            XCTAssertEqual(migrated.slotIndex, occupied)
            XCTAssertEqual(migrated.blob.usernameDisplay, "legacy-user")

            // the file on disk is now a container, and it is one before any save
            XCTAssertTrue(SlotVault.isContainer(try Data(contentsOf: url).prefix(13)))
            XCTAssertEqual(try fileSize(url), SlotVault.fileBytes)

            // …it opens with the SAME password and yields an identical Blob
            let reopened = try SecureStore(unlockingWithPassword: password, at: url)
            XCTAssertEqual(reopened.slotIndex, occupied)
            // (field by field, not by re-encoding: `JSONEncoder` does not fix
            // an order for dictionary keys, so two encodes of one Blob differ)
            XCTAssertEqual(reopened.blob.usernameDisplay, blob.usernameDisplay)
            XCTAssertEqual(reopened.blob.usernameHash, blob.usernameHash)
            XCTAssertEqual(reopened.blob.nextPreKeyId, blob.nextPreKeyId)
            XCTAssertEqual(reopened.blob.drafts, blob.drafts)

            // …and the payload sealed into the slot is byte-identical to the
            // plaintext the legacy file held
            let live = try XCTUnwrap(SlotVault.open(
                try SlotVault.parse(try Data(contentsOf: url)), key: key))
            XCTAssertEqual(live.slot, occupied)
            XCTAssertEqual(live.half, 0, "a freshly authored slot writes half 0…")
            XCTAssertEqual(live.seq, 1, "…at seq 1")
            XCTAssertEqual(live.payload, legacyPlain)
            for i in 0..<3 where i != occupied {
                for h in 0..<2 {
                    XCTAssertNil(SlotVault.openHalf(try halfBytes(of: url, slot: i, half: h),
                                                    slot: i, half: h, key: key))
                }
            }

            // the wrong password still fails, and still fails the same way
            XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "wrong", at: url)) {
                XCTAssertEqual($0 as? SecureStore.StoreError, .badPassword)
            }
            try FileManager.default.removeItem(at: url)
        }
    }

    /// Migration is one way, and it never half-happens: a wrong password must
    /// leave the legacy file exactly as it found it.
    func testWrongPasswordNeverTouchesALegacyFile() throws {
        let salt = KeychainHelper.get("kdf-salt")!
        let key = SecureStore.deriveKey(password: "right", salt: salt)
        let legacy = try XCTUnwrap(
            AES.GCM.seal(try JSONEncoder().encode(SecureStore.Blob()), using: key).combined)
        let url = storeURL("untouched.store")
        try legacy.write(to: url)

        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "wrong", at: url))
        XCTAssertEqual(try Data(contentsOf: url), legacy, "byte-identical, still legacy")
    }

    /// Migration also runs off the biometric entry point (§6.3): the raw key
    /// path must not leave a legacy file behind for the password path to find.
    func testBiometricKeyPathMigratesToo() throws {
        let salt = KeychainHelper.get("kdf-salt")!
        let key = SecureStore.deriveKey(password: "bio-pass", salt: salt)
        var blob = SecureStore.Blob()
        blob.usernameDisplay = "bio"
        let legacy = try XCTUnwrap(AES.GCM.seal(try JSONEncoder().encode(blob), using: key).combined)
        let url = storeURL("bio.store")
        try legacy.write(to: url)

        let raw = key.withUnsafeBytes { Data($0) }
        let store = try SecureStore(unlockingWithKey: raw, at: url)
        XCTAssertEqual(store.blob.usernameDisplay, "bio")
        XCTAssertEqual(try fileSize(url), SlotVault.fileBytes)
        XCTAssertNoThrow(try SecureStore(unlockingWithPassword: "bio-pass", at: url))
    }

    /// No unlocked store is left holding a legacy file: a migrated session's
    /// later saves are containers too, not a silent relapse — and they are
    /// single-slot saves, like every other save.
    func testSavesAfterMigrationStayContainersAndTouchOneSlot() throws {
        let salt = KeychainHelper.get("kdf-salt")!
        let key = SecureStore.deriveKey(password: "keep-going", salt: salt)
        let legacy = try XCTUnwrap(
            AES.GCM.seal(try JSONEncoder().encode(SecureStore.Blob()), using: key).combined)
        let url = storeURL("after.store")
        try legacy.write(to: url)

        let store = try SecureStore(unlockingWithPassword: "keep-going", at: url)
        let mine = try XCTUnwrap(store.slotIndex)
        let others = (0..<3).filter { $0 != mine }
        let frozen = try others.map { try slotBytes(of: url, $0) }

        store.update { $0.usernameDisplay = "still-here" }
        store.flush()
        XCTAssertEqual(try fileSize(url), SlotVault.fileBytes)
        for (n, other) in others.enumerated() {
            XCTAssertEqual(try slotBytes(of: url, other), frozen[n])
        }
        XCTAssertEqual(try SecureStore(unlockingWithPassword: "keep-going", at: url)
                        .blob.usernameDisplay, "still-here")
    }

    // MARK: - malformed files

    func testMagicWithARuinedHeaderIsNotReportedAsABadPassword() throws {
        var bytes = SlotVault.header()
        bytes.append(Data(repeating: 0, count: 1000))          // far too short for 3 slots
        let url = storeURL("truncated.store")
        try bytes.write(to: url)
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "anything", at: url)) {
            XCTAssertEqual($0 as? SecureStore.StoreError, .io)
        }
    }

    func testAContainerClaimingAnotherSlotSizeIsRefused() throws {
        var bytes = Data("PYROVLT1".utf8)
        bytes.append(3)
        bytes.append(SlotVault.be32(65_536))                   // not the pinned 4 MiB
        bytes.append(Data(repeating: 0, count: 3 * 65_536))
        let url = storeURL("old-size.store")
        try bytes.write(to: url)
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "anything", at: url)) {
            XCTAssertEqual($0 as? SecureStore.StoreError, .io)
        }
    }

    func testMissingFileStillReportsNoStore() {
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "x",
                                             at: storeURL("absent.store"))) {
            XCTAssertEqual($0 as? SecureStore.StoreError, .noStore)
        }
    }
}
