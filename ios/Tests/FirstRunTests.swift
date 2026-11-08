import XCTest
@testable import PyroChat

/// docs/FIRST-RUN.md §4 "Unit" — pure rules, no relay, no store.
final class FirstRunKeyParsingTests: XCTestCase {
    let code = "PYRO-ABCD-EFGH-2345-6789"

    func testPayloadForm() {
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v1:\(code)"), .key(code))
        XCTAssertEqual(AccessKeyLink.parse(AccessKeyLink.payload(for: code)), .key(code))
    }

    func testDeepLinkForm() {
        XCTAssertEqual(AccessKeyLink.parse("pyrochat://redeem/\(code)"), .key(code))
        XCTAssertEqual(AccessKeyLink.parse(AccessKeyLink.deepLink(for: code)), .key(code))
        // exactly what `onOpenURL` hands over
        let url = URL(string: "pyrochat://redeem/\(code)")!
        XCTAssertEqual(AccessKeyLink.parse(url.absoluteString), .key(code))
        // a trailing slash is not part of the code
        XCTAssertEqual(AccessKeyLink.parse("pyrochat://redeem/\(code)/"), .key(code))
    }

    func testLowercaseIsNormalised() {
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v1:pyro-abcd-efgh-2345-6789"), .key(code))
        XCTAssertEqual(AccessKeyLink.parse("pyrochat://redeem/pyro-abcd-efgh-2345-6789"), .key(code))
        XCTAssertEqual(AccessKeyLink.parse("PYRO-KEY:V1:pyro-abcd-EFGH-2345-6789"), .key(code))
        XCTAssertEqual(AccessKeyLink.parse("PYROCHAT://REDEEM/\(code)"), .key(code))
    }

    func testWhitespaceIsTrimmed() {
        XCTAssertEqual(AccessKeyLink.parse("  \n pyro-key:v1:\(code) \t\n"), .key(code))
        XCTAssertEqual(AccessKeyLink.parse(" pyrochat://redeem/\(code)  "), .key(code))
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v1: \(code) "), .key(code))
    }

    func testNormaliseMatchesTheRedeemEndpoint() {
        // trim, upper-case, keep A–Z 0–9 - (billing: trim().toUpperCase().replaceAll("[^A-Z0-9-]", ""))
        XCTAssertEqual(AccessKeyLink.normalise("  pyro-abcd.efgh/2345 6789  "), "PYRO-ABCDEFGH23456789")
        XCTAssertEqual(AccessKeyLink.normalise(code.lowercased()), code)
        XCTAssertEqual(AccessKeyLink.normalise("PYRO-ÄBCD"), "PYRO-BCD")
    }

    func testWrongPrefixIsNotAKey() {
        XCTAssertEqual(AccessKeyLink.parse("pyro-add:v1:alice:AAAA"), .none)
        XCTAssertEqual(AccessKeyLink.parse("pyro-link:v1:x:y:z"), .none)
        XCTAssertEqual(AccessKeyLink.parse("https://pyrochat.ca/redeem/\(code)"), .none)
        XCTAssertEqual(AccessKeyLink.parse(code), .none, "a bare code is not one of the two forms")
        XCTAssertEqual(AccessKeyLink.parse(""), .none)
        XCTAssertEqual(AccessKeyLink.parse("hello"), .none)
    }

    func testWrongShapeIsRefused() {
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v1:PYRO-ABCD"), .malformed)
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v1:PYRO-ABCD-EFGH-2345"), .malformed)
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v1:PYRO-ABCD-EFGH-2345-6789-0000"), .malformed)
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v1:PYRO-ABC-EFGH-2345-6789"), .malformed)
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v1:PYRA-ABCD-EFGH-2345-6789"), .malformed)
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v1:PYRO_ABCD_EFGH_2345_6789"), .malformed)
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v1:"), .malformed)
        XCTAssertEqual(AccessKeyLink.parse("pyro-key:v2:\(code)"), .malformed)
        XCTAssertEqual(AccessKeyLink.parse("pyrochat://redeem/"), .malformed)
        XCTAssertEqual(AccessKeyLink.parse("pyrochat://redeem"), .malformed)
        XCTAssertEqual(AccessKeyLink.parse("pyrochat://other/\(code)"), .malformed)
        XCTAssertEqual(AccessKeyLink.refusal, "! not an access key")
    }

    func testWellFormedShape() {
        XCTAssertTrue(AccessKeyLink.isWellFormed("PYRO-0000-0000-0000-0000"))
        XCTAssertTrue(AccessKeyLink.isWellFormed("PYRO-TEST-CODE-0000-0000"))
        XCTAssertFalse(AccessKeyLink.isWellFormed("pyro-abcd-efgh-2345-6789"), "already normalised input only")
        XCTAssertFalse(AccessKeyLink.isWellFormed("PYRO-ABCD-EFGH-2345-678"))
        XCTAssertFalse(AccessKeyLink.isWellFormed(""))
    }

    func testPyroAddStillParsesAsAContact() {
        let key = Data((0..<33).map { UInt8($0) })
        let add = "pyro-add:v1:alice:\(MirrorCrypto.b64url(key))"
        XCTAssertEqual(AccessKeyLink.parse(add), .none, "a contact code is never an access key")
        XCTAssertEqual(ContactCode.parse(add), .contact(username: "alice", identityKey: key))
        XCTAssertEqual(ContactCode.parse("  \(add)\n"), .contact(username: "alice", identityKey: key))
        // the whole [share] message pasted as one string still yields the code
        let shared = FirstRun.shareText(username: "alice", addCode: add)
        XCTAssertEqual(ContactCode.parse(shared), .contact(username: "alice", identityKey: key))
        // and a key is never a contact
        XCTAssertEqual(ContactCode.parse("pyro-key:v1:\(code)"), .none)
        XCTAssertEqual(ContactCode.parse("pyrochat://redeem/\(code)"), .none)
        // broken contact codes explain themselves instead of being ignored
        XCTAssertEqual(ContactCode.parse("pyro-add:v1:alice"), .malformed)
        XCTAssertEqual(ContactCode.parse("pyro-add:v2:alice:AAAA"), .malformed)
        XCTAssertEqual(ContactCode.parse("pyro-add:v1::AAAA"), .malformed)
        XCTAssertEqual(ContactCode.parse("pyro-add:v1:alice:"), .malformed)
        XCTAssertEqual(ContactCode.parse("pyro-add:v1:alice:!!!"), .malformed)
    }
}

final class FirstRunShareTextTests: XCTestCase {
    func testShareTextIsThreeVerbatimLines() {
        let text = FirstRun.shareText(username: "alice", addCode: "pyro-add:v1:alice:KEY")
        XCTAssertEqual(text, """
        add me on PyroChat \u{2014} my username is alice
        in the app: [qr] \u{2192} paste this code \u{2192} pyro-add:v1:alice:KEY
        get the app: https://pyrochat.ca
        """)
        XCTAssertEqual(text.split(separator: "\n").count, 3)
        XCTAssertFalse(text.hasSuffix("\n"))
    }
}

final class FirstRunHomeRulesTests: XCTestCase {
    func testEmptyStateOnlyWithoutContactsAndGroups() {
        XCTAssertTrue(FirstRun.showsEmptyState(contacts: 0, groups: 0))
        XCTAssertFalse(FirstRun.showsEmptyState(contacts: 1, groups: 0))
        XCTAssertFalse(FirstRun.showsEmptyState(contacts: 0, groups: 1))
        XCTAssertFalse(FirstRun.showsEmptyState(contacts: 2, groups: 3))
    }

    func testEmptyStateWords() {
        XCTAssertEqual(FirstRun.emptyStateLines, [
            "no channels yet.",
            "press [+] and add pyrobot for a 60-second tour,",
            "or [share] your username to bring a friend.",
        ])
    }

    func testPyrobotSubtitleRule() {
        let demo = "demo peer \u{2014} say hi for a 60-second tour"
        let generic = "channel open \u{2014} no traffic"
        XCTAssertEqual(FirstRun.subtitle(forContactNamed: "pyrobot", lastPreview: nil), demo)
        // a thread with traffic shows its preview, never the hint
        XCTAssertEqual(FirstRun.subtitle(forContactNamed: "pyrobot", lastPreview: "you: hi"), "you: hi")
        XCTAssertEqual(FirstRun.subtitle(forContactNamed: "pyrobot", lastPreview: "hello. i am pyrobot"), "hello. i am pyrobot")
        // only the contact named exactly `pyrobot`
        XCTAssertEqual(FirstRun.subtitle(forContactNamed: "alice", lastPreview: nil), generic)
        XCTAssertEqual(FirstRun.subtitle(forContactNamed: "Pyrobot", lastPreview: nil), generic)
        XCTAssertEqual(FirstRun.subtitle(forContactNamed: "pyrobot2", lastPreview: nil), generic)
        XCTAssertEqual(FirstRun.subtitle(forContactNamed: " pyrobot", lastPreview: nil), generic)
        XCTAssertEqual(FirstRun.demoPeer, "pyrobot")
    }

    func testDropMessage() {
        XCTAssertEqual(FirstRun.createIdentityFirst, "! create your identity first, then scan the key again")
    }
}

/// §3 "behaviour on receiving a key" as a state machine: applied at once
/// when unlocked, held in memory while locked and applied right after
/// unlock, dropped (with the message) without an identity.
final class PendingAccessKeyTests: XCTestCase {
    let code = "PYRO-ABCD-EFGH-2345-6789"

    func testUnlockedAppliesAtOnce() {
        var m = PendingAccessKey()
        XCTAssertEqual(m.receive(code, at: .unlocked), .apply(code))
        XCTAssertNil(m.held)
        XCTAssertEqual(m.stageChanged(to: .unlocked), .none, "nothing left to apply")
    }

    func testLockedHoldsThenAppliesAfterUnlockExactlyOnce() {
        var m = PendingAccessKey()
        XCTAssertEqual(m.receive(code, at: .locked), .held)
        XCTAssertEqual(m.held, code)
        XCTAssertEqual(m.stageChanged(to: .locked), .none, "still waiting for the password")
        XCTAssertEqual(m.held, code)
        XCTAssertEqual(m.stageChanged(to: .unlocked), .apply(code))
        XCTAssertNil(m.held)
        XCTAssertEqual(m.stageChanged(to: .unlocked), .none, "applied once, never again")
        XCTAssertEqual(m.stageChanged(to: .locked), .none)
    }

    func testBootWithAnIdentityIsLocked() {
        // the OS may deliver the link before the boot screen finished: same as locked
        var m = PendingAccessKey()
        XCTAssertEqual(m.receive(code, at: .locked), .held)
        XCTAssertEqual(m.stageChanged(to: .unlocked), .apply(code))
    }

    func testNoIdentityDropsWithMessage() {
        var m = PendingAccessKey()
        XCTAssertEqual(m.receive(code, at: .noIdentity),
                       .dropped("! create your identity first, then scan the key again"))
        XCTAssertNil(m.held, "never kept — the user must scan again after creating an identity")
        XCTAssertEqual(m.stageChanged(to: .unlocked), .none)
    }

    func testHeldKeyIsForgottenWhenTheIdentityVanishes() {
        // received while locked, then a duress / dead-man wipe: nothing to apply to
        var m = PendingAccessKey()
        XCTAssertEqual(m.receive(code, at: .locked), .held)
        XCTAssertEqual(m.stageChanged(to: .noIdentity), .discarded)
        XCTAssertNil(m.held)
        XCTAssertEqual(m.stageChanged(to: .unlocked), .none, "a later identity never inherits it")
    }

    func testLatestKeyWinsWhileLocked() {
        var m = PendingAccessKey()
        let other = "PYRO-0000-1111-2222-3333"
        XCTAssertEqual(m.receive(code, at: .locked), .held)
        XCTAssertEqual(m.receive(other, at: .locked), .held)
        XCTAssertEqual(m.stageChanged(to: .unlocked), .apply(other))
    }

    func testReceiveWhileUnlockedClearsAnyHeldKey() {
        var m = PendingAccessKey()
        _ = m.receive(code, at: .locked)
        XCTAssertEqual(m.receive("PYRO-0000-1111-2222-3333", at: .unlocked), .apply("PYRO-0000-1111-2222-3333"))
        XCTAssertNil(m.held)
    }
}

/// docs/FIRST-RUN.md §1 adds pyrobot right after CREATE IDENTITY — while
/// the user may already be pressing [+] → pyrobot. Two concurrent adds of
/// the same user must leave exactly one row (live: relay on :7444).
@MainActor
final class FirstRunConcurrentAddTests: XCTestCase {
    private func makeService() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-fr-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "fr-test-pass-123",
                                at: dir.appendingPathComponent("store"))
        let username = "fr" + UUID().uuidString.prefix(8).lowercased()
        return (ChatService(store: store), username)
    }

    func testConcurrentAddsOfTheSameUserLeaveOneRow() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        defer { alice.shutdown(); bob.shutdown() }
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)

        // both adds start before either prekey fetch has returned
        async let first: Void = alice.addContact(username: bobName)
        async let second: Void = alice.addContact(username: bobName)
        try await first
        try await second

        XCTAssertEqual(alice.contacts.filter { $0.hash == bob.myHash }.count, 1, "one row per contact")
        XCTAssertEqual(alice.store.blob.contacts.filter { $0.hash == bob.myHash }.count, 1, "and one on disk")
        XCTAssertTrue(alice.crypto.hasSession(with: bob.myHash))
    }
}

/// §3 wiring through the real `AppModel` (boot → auth → main), against the
/// local relay: a key that arrives before any unlock waits in RAM and reaches
/// the service the moment one happens, and a key held during a CREATE that
/// fails is forgotten. Uses the app's real store path (wiped before and after,
/// like the other wipe tests).
///
/// docs/DECOY-ACCOUNT.md §5 changed the first of those. There used to be a
/// "create your identity first" drop, chosen by reading the filesystem for an
/// identity — and the filesystem no longer knows, because a container exists
/// from first run and no wipe deletes it. So the key is HELD in every case
/// before an unlock, which is both the honest answer and the better one: a
/// redeem link followed by a CREATE now applies instead of being thrown away.
@MainActor
final class FirstRunAppModelKeyTests: XCTestCase {
    let code = "PYRO-ABCD-EFGH-2345-6789"
    let password = "fr-model-pass-123"
    private var model: AppModel?

    override func setUp() async throws {
        SecureStore.obliterate()
    }

    override func tearDown() async throws {
        model?.service?.shutdown()
        model = nil
        SecureStore.obliterate()
    }

    private func freshName() -> String { "fr" + UUID().uuidString.prefix(8).lowercased() }

    /// §5: the app can no longer say "no identity on this device", so it no
    /// longer drops the key claiming to know. It holds it, and a CREATE
    /// straight afterwards applies it.
    func testAKeyArrivingBeforeAnyUnlockIsHeldAndAppliedByACreate() async throws {
        let m = AppModel(); model = m
        m.finishBoot()
        XCTAssertEqual(m.stage, .auth)
        m.receive(url: URL(string: "pyrochat://redeem/\(code)")!)
        XCTAssertNil(m.keyFlash, "no message: the app is not entitled to one")
        XCTAssertNil(m.service)
        let err = await m.submitAuth(.create, username: freshName(), password: password)
        XCTAssertNil(err, "create failed: \(err ?? "")")
        XCTAssertEqual(m.service?.pendingAccessKey, code,
                       "held through the create and applied the moment there is a session")
    }

    func testMalformedLinkIsRefusedNotHeld() async throws {
        let m = AppModel(); model = m
        m.finishBoot()
        m.receive(url: URL(string: "pyrochat://redeem/PYRO-ABCD")!)
        XCTAssertEqual(m.keyFlash, "! not an access key")
        m.receive(url: URL(string: "pyro-add:v1:alice:AAAA")!)   // not ours: ignored
        XCTAssertEqual(m.keyFlash, "! not an access key", "unchanged")
    }

    func testKeyWhileLockedWaitsAndAppliesRightAfterUnlock() async throws {
        // an identity exists on the device: make one, then "relaunch"
        let name = freshName()
        do {
            let first = AppModel()
            let created = await first.submitAuth(.create, username: name, password: password)
            XCTAssertNil(created, "create failed: \(created ?? "")")
            first.lock()
            XCTAssertEqual(first.stage, .auth)
            XCTAssertNil(first.service)
        }
        let m = AppModel(); model = m
        XCTAssertEqual(m.stage, .boot)
        // the OS hands the link over before boot finished — same as locked
        m.receive(url: URL(string: "pyrochat://redeem/\(code)")!)
        XCTAssertNil(m.keyFlash, "no message: the key is simply waiting")
        XCTAssertNil(m.service, "nothing to apply to while locked")
        m.finishBoot()
        let wrong = await m.submitAuth(.unlock, username: name, password: "not-the-password")
        XCTAssertNotNil(wrong)
        XCTAssertNil(m.service, "a failed unlock applies nothing")
        let err = await m.submitAuth(.unlock, username: name, password: password)
        XCTAssertNil(err, "unlock failed: \(err ?? "")")
        XCTAssertEqual(m.stage, .main)
        XCTAssertEqual(m.service?.pendingAccessKey, code, "applied right after unlock")
        // consumed once: a re-lock + unlock does not replay it
        m.service?.pendingAccessKey = nil
        m.lock()
        let again = await m.submitAuth(.unlock, username: name, password: password)
        XCTAssertNil(again)
        XCTAssertNil(m.service?.pendingAccessKey)
    }

    /// This used to assert the key was FORGOTTEN, because the rollback left no
    /// store and the app then read "no identity" off the filesystem and
    /// dropped it. docs/DECOY-ACCOUNT.md §5 removed that reading, and the
    /// behaviour it was propping up turns out to have been wrong on its own
    /// merits: losing someone's paid access key because the first username
    /// they tried was taken is not a property worth keeping. A key held
    /// through a rolled-back CREATE now applies to the one that succeeds.
    func testKeyHeldDuringAFailedCreateSurvivesToTheNextOne() async throws {
        // take the name first so the CREATE below fails at register
        let taken = freshName()
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-fr-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let owner = ChatService(store: SecureStore(creatingWithPassword: password,
                                                   at: dir.appendingPathComponent("store")))
        defer { owner.shutdown() }
        try await owner.register(username: taken)

        let m = AppModel(); model = m
        m.finishBoot()
        let attempt = Task { await m.submitAuth(.create, username: taken, password: password) }
        // the service exists from the moment `register` is awaited; the
        // store's debounced write lands 250 ms later — a slow relay leaves
        // the file on disk while the reply is pending, so a key arriving
        // then is held against the half-made store. Force that state.
        var spins = 0
        while m.service == nil && spins < 100_000 { await Task.yield(); spins += 1 }
        let pending = try XCTUnwrap(m.service, "create never started")
        XCTAssertEqual(m.stage, .auth, "still on the auth screen while registering")
        pending.store.flush()
        m.receive(url: URL(string: "pyrochat://redeem/\(code)")!)
        XCTAssertNil(m.keyFlash, "held, not dropped")
        let err = await attempt.value
        XCTAssertNotNil(err, "registering a taken name must fail")
        XCTAssertNil(m.service)
        // the rollback must not leave a zombie: the debounced write of the
        // generated identity keys used to re-create the file ~250 ms after
        // the obliterate, under a salt that was already wiped
        try await Task.sleep(for: .milliseconds(600))
        // §5: the FILE is never deleted — it is three slots of noise now. What
        // must be gone is anything openable, and the half-made identity above.
        XCTAssertEqual(try Data(contentsOf: SecureStore.storeURL).count, SlotVault.fileBytes)
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: password),
                             "rolled back: nothing in it opens, and it stays that way")
        XCTAssertNil(m.service)
        // the later, successful CREATE gets it — the user typed one username,
        // it was taken, they typed another, and their key is still theirs
        let later = await m.submitAuth(.create, username: freshName(), password: password)
        XCTAssertNil(later, "create failed: \(later ?? "")")
        XCTAssertEqual(m.stage, .main)
        XCTAssertEqual(m.service?.pendingAccessKey, code)
    }
}
