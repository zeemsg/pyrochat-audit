import XCTest
@testable import PyroChat

/// docs/DECOY-ACCOUNT.md §6.6 — "A wake push for the real account, arriving
/// while the decoy is open, must not surface. Nothing may render for a slot
/// that is not the open one."
///
/// On iOS there are no local notifications at all: the push is
/// content-available and zero-content, its `userInfo` is never read, and all
/// it does is call `backgroundDrain` on whichever session is live. So there is
/// exactly one thing this app renders for a slot outside a chat screen — the
/// springboard badge — and these tests are about that, plus the wake hook that
/// feeds it.
///
/// Drives the REAL `AppModel` against the real store path, the same shape
/// `WipeAfterTenAppModelTests` uses, because the leak this closes lived in the
/// seam between `lock()` and the next `beginSession`.
@MainActor
final class DecoyNotificationTests: XCTestCase {
    private let realPassword = "real-account-pw-1"
    private let decoyPassword = "decoy-account-pw-1"
    private var model: AppModel?

    override func setUp() async throws {
        SecureStore.obliterate()
        UnlockFailures.reset()
    }

    override func tearDown() async throws {
        model?.service?.shutdown()
        model = nil
        PushDelegate.onWake = nil
        PushDelegate.onToken = nil
        SlotVault.chooseSlot = { $0.randomElement()! }
        SecureStore.obliterate()
        UnlockFailures.reset()
    }

    /// A device holding a real account with unread mail and a decoy with none.
    private func seedBothAccounts() throws {
        SlotVault.chooseSlot = { _ in 0 }
        let real = SecureStore(creatingWithPassword: realPassword)
        real.update {
            $0.usernameDisplay = "realuser"
            $0.contacts = [Contact(hash: String(repeating: "b", count: 64),
                                   name: "a-real-contact", addedAt: Date())]
            $0.messages = [String(repeating: "b", count: 64): (0..<5).map { i in
                ChatMessage(id: "real-\(i)", direction: .incoming, kind: .text,
                            text: "something the decoy must never see",
                            timestamp: Date(), status: .delivered,
                            burnAfter: nil, burnStartedAt: nil)
            }]
        }
        real.flush()
        SlotVault.chooseSlot = { $0.randomElement()! }

        let decoy = try SecureStore(creatingDecoyWithPassword: decoyPassword, inSlot: 2)
        decoy.update { $0.usernameDisplay = "decoyuser" }
        decoy.flush()
        real.noteAllocated(slot: 2)
    }

    /// §6.6, the leak. The badge is a per-slot unread count and it is the one
    /// thing the app renders outside a session. Left standing across a lock it
    /// follows the next unlock onto the home screen: a decoy with an empty
    /// inbox wearing the real account's five.
    func testTheUnreadCountTheBadgeIsDrawnFromIsPerSlot() async throws {
        try seedBothAccounts()
        let m = AppModel(); model = m
        m.finishBoot()

        var failure = await m.submitAuth(.unlock, username: "", password: realPassword)
        XCTAssertNil(failure)
        let real = try XCTUnwrap(m.service)
        XCTAssertEqual(real.totalUnread, 5, "the real account's inbox")

        m.lock()
        XCTAssertNil(m.service, "a locked device has no session to render for")
        XCTAssertNil(PushDelegate.onWake,
                     "§6.6: a wake push arriving while locked must reach no session at all")

        failure = await m.submitAuth(.unlock, username: "", password: decoyPassword)
        XCTAssertNil(failure)
        let decoy = try XCTUnwrap(m.service)
        XCTAssertEqual(decoy.totalUnread, 0,
                       "§6.6: nothing may render for a slot that is not the open one")
        XCTAssertTrue(decoy.contacts.isEmpty)
        XCTAssertEqual(decoy.messages.values.flatMap { $0 }.count, 0)
        XCTAssertNotNil(PushDelegate.onWake, "and the live session is the one a wake reaches")
    }

    /// §6.6 again, from the other side: the wake hook is rebound to the session
    /// that is open and never holds a reference to one that is not. A drain
    /// triggered by a push for the real account, while the decoy is open, can
    /// only ever touch the decoy's own relay queue.
    func testTheWakeHookFollowsTheOpenSessionAndOnlyThatOne() async throws {
        try seedBothAccounts()
        let m = AppModel(); model = m
        m.finishBoot()

        var failure = await m.submitAuth(.unlock, username: "", password: realPassword)
        XCTAssertNil(failure)
        let realService = try XCTUnwrap(m.service)
        m.lock()
        failure = await m.submitAuth(.unlock, username: "", password: decoyPassword)
        XCTAssertNil(failure)
        let decoyService = try XCTUnwrap(m.service)
        XCTAssertFalse(realService === decoyService)

        // Fire the wake the way `PushDelegate` does. The real account's five
        // messages are on disk in a slot this session cannot open, so whatever
        // the drain does, none of them can appear.
        let done = expectation(description: "wake handled")
        try XCTUnwrap(PushDelegate.onWake)({ _ in done.fulfill() })
        await fulfillment(of: [done], timeout: 20)

        XCTAssertEqual(decoyService.totalUnread, 0)
        XCTAssertEqual(decoyService.messages.values.flatMap { $0 }.count, 0)
        XCTAssertTrue(decoyService.contacts.isEmpty)
        XCTAssertEqual(decoyService.store.blob.usernameDisplay, "decoyuser")
    }

    /// §6.6, "How, given that the payload cannot help".
    ///
    /// The relay's push names no account and must not — naming one would be
    /// the disclosure this feature exists to prevent. The only fact the device
    /// has is whether the OPEN session ever asked to be woken. A session with
    /// wake push off never did, so a wake arriving into it belongs to some
    /// other slot: dropped, nothing drained, nothing surfaced.
    func testAWakeIntoASessionThatNeverAskedForOneIsDropped() async throws {
        try seedBothAccounts()
        let m = AppModel(); model = m
        m.finishBoot()
        let failure = await m.submitAuth(.unlock, username: "", password: decoyPassword)
        XCTAssertNil(failure)
        let decoy = try XCTUnwrap(m.service)
        XCTAssertFalse(decoy.store.blob.settings.pushEnabled,
                       "a decoy that never turned wake push on")

        let outcome = await AppModel.handleWake(decoy)
        XCTAssertEqual(outcome, .noData, "dropped: this wake was not for this slot")
        XCTAssertEqual(decoy.totalUnread, 0)
        XCTAssertEqual(decoy.messages.values.flatMap { $0 }.count, 0)

        // …and a session that DID ask behaves exactly as it always has, which
        // is what keeps a phone holding one account and a phone holding two
        // indistinguishable from outside.
        decoy.updateSettings { $0.pushEnabled = true }
        _ = await AppModel.handleWake(decoy)
        XCTAssertEqual(decoy.messages.values.flatMap { $0 }.count, 0,
                       "still nothing of the other slot, whatever the drain did")
    }

    /// A locked or cold device has no open slot to be wrong about.
    func testAWakeWithNoSessionIsDropped() async throws {
        let outcome = await AppModel.handleWake(nil)
        XCTAssertEqual(outcome, .noData)
    }

    /// §6.5 is the residue the app cannot fix, but it should not be made
    /// worse: a freshly created decoy has push off, so creating one does not
    /// hand the relay a second account behind the same device token.
    func testAFreshDecoyDoesNotRegisterTheDevicePushToken() throws {
        try seedBothAccounts()
        let decoy = try SecureStore(unlockingWithPassword: decoyPassword)
        XCTAssertFalse(decoy.blob.settings.pushEnabled,
                       "a decoy opts in to wake-ups the same way any account does: deliberately")
    }

    /// docs/DECOY-ACCOUNT.md §5, "The container is created at first run and is
    /// never deleted".
    ///
    /// This test used to PIN a defect: a lone account's burn deleted the file
    /// and a decoy's burn could not, so the next launch differed and a
    /// supervisor who reopened the app learned that something had survived.
    /// It now asserts the opposite — that the two are identical — because the
    /// fix came from the other end. Wiped, never used, and holding two
    /// accounts are the same 12,582,925 bytes, and the first screen no longer
    /// claims to know which it is looking at.
    func testBothBurnsLeaveTheDeviceLookingIdentical() async throws {
        func shapeOnDisk() throws -> (size: Int, header: Data, exists: Bool) {
            let url = SecureStore.storeURL
            guard FileManager.default.fileExists(atPath: url.path) else {
                return (0, Data(), false)
            }
            let raw = try Data(contentsOf: url)
            return (raw.count, raw.prefix(SlotVault.headerBytes), true)
        }

        // a lone account burns
        let lone = SecureStore(creatingWithPassword: realPassword)
        lone.flush()
        XCTAssertTrue(lone.burnCurrentSlot(), "total: device-level state goes too")
        let afterLone = try shapeOnDisk()

        // a decoy burns, on a device that also holds a real account
        try seedBothAccounts()
        let decoy = try SecureStore(unlockingWithPassword: decoyPassword)
        XCTAssertFalse(decoy.burnCurrentSlot(), "narrowed: only this slot")
        let afterDecoy = try shapeOnDisk()

        XCTAssertTrue(afterLone.exists, "§5: no path ever deletes the container")
        XCTAssertTrue(afterDecoy.exists)
        XCTAssertEqual(afterLone.size, afterDecoy.size)
        XCTAssertEqual(afterLone.size, SlotVault.fileBytes)
        XCTAssertEqual(afterLone.header, afterDecoy.header)
        XCTAssertEqual(afterLone.header, SlotVault.header())

        // and the account this feature exists to protect survived the one that
        // was performed under supervision
        XCTAssertNoThrow(try SecureStore(unlockingWithPassword: realPassword))
    }

    /// §5: the first screen stops claiming to know, so a create on a device
    /// that holds accounts is reachable. §1 then says exactly what it costs,
    /// and says it as a statement rather than a probability: it rewrites the
    /// whole container, so every account on the device is destroyed — and the
    /// file is the same size afterwards as it was before, which is what leaves
    /// nothing to compare.
    func testCreateIsReachableAndErasesEverythingOnTheDevice() async throws {
        try seedBothAccounts()          // real in 0, decoy in 2, noise in 1
        let sizeBefore = try Data(contentsOf: SecureStore.storeURL).count
        let m = AppModel(); model = m
        m.finishBoot()
        XCTAssertNoThrow(try SecureStore(unlockingWithPassword: realPassword))
        XCTAssertNoThrow(try SecureStore(unlockingWithPassword: decoyPassword))

        let failure = await m.submitAuth(.create, username: "fresh\(Int.random(in: 1000...9999))",
                                         password: "a-brand-new-password-1")
        XCTAssertNil(failure)
        try XCTUnwrap(m.service).store.flush()

        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: realPassword))
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: decoyPassword))
        XCTAssertNotNil(try? SecureStore(unlockingWithPassword: "a-brand-new-password-1"))
        XCTAssertEqual(try Data(contentsOf: SecureStore.storeURL).count, sizeBefore)
        XCTAssertEqual(sizeBefore, SlotVault.fileBytes)

        // and the device is in a fully known state: a decoy simply works
        let after = try SecureStore(unlockingWithPassword: "a-brand-new-password-1")
        XCTAssertEqual(after.blob.knownSlots, [0, 1, 2])
        XCTAssertEqual(after.freeSlots.count, 2)
    }
}
