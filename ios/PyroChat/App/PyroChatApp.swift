import SwiftUI
import UserNotifications

/// Receives the APNs device token. The token is the ONLY thing shared with
/// the relay (RAM-only there), and pushes carry zero content.
final class PushDelegate: NSObject, UIApplicationDelegate {
    static var currentToken: String?

    /// Fired when a token arrives (or rotates). iOS hands it out
    /// asynchronously — often after the user is already logged in — so the
    /// live service pushes it to the relay instead of waiting for a reconnect.
    static var onToken: ((String) -> Void)?
    /// Background wake (content-available push): set while a service is unlocked in
    /// memory; nil when locked, since nothing can be decrypted without the password.
    static var onWake: ((@escaping (UIBackgroundFetchResult) -> Void) -> Void)?

    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        // foreground: the live socket already delivers; background: drain now
        #if DEBUG
        NSLog("PYRO bg-wake: received, state=%d onWake=%@", application.applicationState.rawValue, Self.onWake == nil ? "nil" : "set")
        #endif
        guard application.applicationState != .active, let wake = Self.onWake else {
            completionHandler(.noData); return
        }
        wake { result in
            #if DEBUG
            NSLog("PYRO bg-wake: done result=%d", result.rawValue)
            #endif
            completionHandler(result)
        }
    }

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        Self.currentToken = hex
        Self.onToken?(hex)
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        #if DEBUG
        print("PYRO push registration failed: \(error.localizedDescription)")
        #endif
    }

    /// Account wiped on this device: invalidate our APNs token so the relay's
    /// next wake bounces (410) and it drops us — a wiped phone must not keep
    /// lighting up with "incoming transmission". Also clears anything already
    /// in Notification Center. Safe pre-auth: needs no keys.
    static func detach() {
        currentToken = nil
        onToken = nil
        UIApplication.shared.unregisterForRemoteNotifications()
        let center = UNUserNotificationCenter.current()
        center.removeAllDeliveredNotifications()
        center.removeAllPendingNotificationRequests()
        center.setBadgeCount(0) { _ in }
    }

    /// Ask for banner + sound + badge. iOS shows the prompt only once per
    /// install; if it was answered "don't allow" (or an older build's silent
    /// provisional grant is in place) the only fix is the Settings app, so
    /// we open it right on the notifications page.
    static func requestAndRegister() {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized:
                DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
            case .notDetermined:
                center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
                    guard granted else { return }
                    DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
                }
            default:   // .denied, .provisional, .ephemeral — iOS will not prompt again
                DispatchQueue.main.async {
                    UIApplication.shared.registerForRemoteNotifications()   // quiet delivery still works meanwhile
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            }
        }
    }
}

/// Pure auto-lock decision — trivially unit-testable, no clock/UI coupling.
enum AutoLock {
    /// Negative elapsed = the wall clock moved backwards while we were away
    /// — fail CLOSED and lock.
    static func shouldLock(backgroundedAt: Date?, timeoutMinutes: Int?,
                           now: Date = Date()) -> Bool {
        guard let backgroundedAt, let timeoutMinutes else { return false }
        let elapsed = now.timeIntervalSince(backgroundedAt)
        if elapsed < 0 { return true }
        return elapsed >= Double(timeoutMinutes) * 60
    }
}

@main
struct PyroChatApp: App {
    @UIApplicationDelegateAdaptor(PushDelegate.self) private var pushDelegate
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // decrypted tmp copies (previews/exports) from a crashed session must
        // never survive into this one
        SecureStore.sweepPlaintextTmp()
        // docs/DECOY-ACCOUNT.md §5: the container exists from first run, as
        // three slots of noise, before any account. 90 ms on the one launch
        // that finds nothing there. Everything downstream — the auth screen
        // included — then has nothing to read off the filesystem.
        SecureStore.ensureContainerExists()
        SoundPlayer.prepare()
        // NOTE: never request .provisional here. A silent provisional grant
        // makes iOS skip the real permission prompt forever, so "wake push"
        // would only ever deliver quietly (no banner, no sound). Permission is
        // asked once, properly, when the user turns wake push on.
    }

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                #if ACCESS_KEY_UI
                // docs/FIRST-RUN.md §3: `pyrochat://redeem/<CODE>` from the
                // camera app, Safari or the success page
                .onOpenURL { url in model.receive(url: url) }
                #endif
                .onChange(of: scenePhase) { _, phase in
                    model.noteScenePhase(phase)   // may lock: service → nil
                    guard let service = model.service else { return }
                    if phase == .background {
                        service.store.flush()
                        service.noteBackgrounded()
                    }
                    // reopening the app must bring the link back by itself —
                    // never leave the user staring at LINK DOWN + RETRY
                    if phase == .active { service.noteForegrounded() }
                    // keep the springboard badge honest at every transition:
                    // it counts unread as of the moment you left the app
                    // (zero-content pushes can't update it remotely — that
                    // needs an APNs Notification Service Extension later)
                    #if DEBUG
                    // marketing captures: setBadgeCount with an undetermined
                    // authorization raises the system permission prompt over
                    // the screenshot (iOS 17+ asks for .badge implicitly)
                    if ScreenshotDemo.isActive { return }
                    #endif
                    if phase == .background || phase == .active {
                        let n = service.totalUnread
                        UNUserNotificationCenter.current().setBadgeCount(n) { error in
                            #if DEBUG
                            print("PYRO badge set=\(n) error=\(error?.localizedDescription ?? "none")")
                            #endif
                        }
                    }
                }
        }
    }
}

/// Top-level state machine: boot → auth → main.
@MainActor
final class AppModel: ObservableObject {
    enum Stage { case boot, auth, main }
    @Published var stage: Stage = .boot
    @Published var service: ChatService?

    private(set) var bootSettings = AppSettings()
    /// Wall-clock moment the scene entered .background. RAM only — a killed
    /// app relaunches into .auth and needs the password regardless.
    private var backgroundedAt: Date?

    #if ACCESS_KEY_UI
    /// docs/FIRST-RUN.md §3: a key received while locked / on the auth
    /// screen waits here (RAM only) and is applied right after unlock.
    private var pendingKey = PendingAccessKey()
    #endif

    /// Bumped by every wipe. `AuthView` watches it and re-roots — the device
    /// state changed under the screen. It replaces watching `authMode`, which
    /// §5 removed because the app is no longer allowed to know.
    @Published private(set) var wipeGeneration = 0
    /// One transient line over whichever stage is showing (`! not an access
    /// key`, `! create your identity first…`); clears itself.
    @Published var keyFlash: String?
    private var keyFlashTask: Task<Void, Never>?
    /// §1a: one line per condition per launch. See `init`.
    private var storeFullReported: Set<SecureStore.FullReason> = []

    #if ACCESS_KEY_UI
    private var keyStage: PendingAccessKey.Stage {
        if stage == .main, service != nil { return .unlocked }
        // §5 again: locked, always. `.noIdentity` was read off the filesystem,
        // and the filesystem no longer knows. A key that arrives before an
        // unlock is HELD — and dropped outright by a wipe, which is the one
        // moment the app really does know there is nothing to apply it to.
        return .locked
    }

    /// OS deep link (`onOpenURL`). Anything but our redeem link is ignored.
    func receive(url: URL) {
        switch AccessKeyLink.parse(url.absoluteString) {
        case .key(let code): receiveAccessKey(code)
        case .malformed: flash(AccessKeyLink.refusal)
        case .none: break
        }
    }

    /// A well-formed key from any source: apply now, hold until unlock, or
    /// drop with the message when there is no identity to apply it to.
    func receiveAccessKey(_ code: String) {
        switch pendingKey.receive(code, at: keyStage) {
        case .apply(let c): service?.pendingAccessKey = c
        case .dropped(let message): flash(message)
        case .held, .discarded, .none: break
        }
    }
    #endif

    /// Call whenever the identity stage may have changed (unlock, restore,
    /// create, wipe): applies a held key or forgets it.
    private func noteKeyStage() {
        #if ACCESS_KEY_UI
        if case .apply(let c) = pendingKey.stageChanged(to: keyStage) {
            service?.pendingAccessKey = c
        }
        #endif
    }

    func flash(_ text: String) {
        keyFlash = text
        keyFlashTask?.cancel()
        keyFlashTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(6))
            if !Task.isCancelled { self?.keyFlash = nil }
        }
    }

    init() {
        // docs/DECOY-ACCOUNT.md §1a: the one condition the store cannot
        // resolve on its own and must not swallow. It fires on the save queue
        // and can fire during an UNLOCK, before there is a session, so it goes
        // to the flash line — the only surface this app has that renders at
        // every stage. Deduped to one line per condition per launch: a full
        // store retries on every save, and a line that reappeared every few
        // seconds would be noise rather than information.
        SecureStore.onStoreFull = { [weak self] _, reason in
            Task { @MainActor [weak self] in
                guard let self, !self.storeFullReported.contains(reason) else { return }
                self.storeFullReported.insert(reason)
                self.flash(StoreFullCopy.line(for: reason))
            }
        }
        #if DEBUG
        // docs/BIOMETRIC-UNLOCK.md §7: a simulator's LocalAuthentication never
        // reports a no-match to the app — it keeps its own sheet up and the
        // only way out arrives as a cancel, which by design costs nothing. So
        // the failure counter cannot be EARNED on a simulator;
        // `--demo-bio-failures 3` records them through the very same API the
        // real failures use, so the §4 password requirement can be driven and
        // screenshotted there. DEBUG only, never in a release build.
        if let n = DemoAutopilot.arg("--demo-bio-failures").flatMap(Int.init) {
            for _ in 0..<max(0, n) { _ = BiometricUnlock.noteFailure() }
        }
        // marketing screenshots (`--screenshot-demo`): canned tmp store, no
        // relay, no boot/auth — straight to the home screen. The `unlock`
        // screen is the exception: it IS the auth stage, so it seeds nothing
        // and lets boot → auth run as normal.
        if ScreenshotDemo.isActive, ScreenshotDemo.screen != .unlock {
            let svc = ScreenshotDemo.makeService()
            if ScreenshotDemo.screen == .boot {
                // docs/TEXT-SIZE.md §3: stay on the boot screen. The demo
                // store was still opened, so `Theme` carries its text size and
                // the ASCII candle can be captured at `--demo-text-size`.
                bootSettings = svc.store.blob.settings
            } else {
                service = svc
                stage = .main
                ScreenshotDemo.linkIfAsked(svc)  // --demo-pairlink: the desktop-link page tests
            }
        }
        #endif
    }

    /// .inactive (app-switcher peek, Face ID sheet) must NOT arm the timer;
    /// .background arms once; .active evaluates on return.
    func noteScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .background:
            if backgroundedAt == nil { backgroundedAt = Date() }
            // leaving the app while unlocked is presence: re-arm
            if stage == .main, let svc = service {
                DeadmanSwitch.notePresence(days: svc.store.blob.settings.wipeAfterDays)
            }
        case .active:
            if stage == .main, let svc = service {
                // an unlocked, in-use device is alive by definition — the
                // switch must NEVER fire here (a mid-session wipe would be
                // undone by the shutdown flush and brick the store)
                DeadmanSwitch.notePresence(days: svc.store.blob.settings.wipeAfterDays)
            } else if DeadmanSwitch.fireIfExpired() {
                PushDelegate.detach()
                noteKeyStage()
                objectWillChange.send()   // auth screen re-reads: fresh install
            }
            let bg = backgroundedAt
            backgroundedAt = nil
            let timeout = service?.store.blob.settings.autoLockAfterMinutes
            if AutoLock.shouldLock(backgroundedAt: bg, timeoutMinutes: timeout) {
                lock()
            }
        case .inactive:
            break
        @unknown default:
            break
        }
    }

    /// Restore an encrypted backup onto this device. Returns error or nil.
    func submitRestore(backup: Data, passphrase: String,
                       newPassword: String) async -> String? {
        // No "does an identity exist" guard here: `init()` seeds the noise
        // container on every first launch, so a file is there on every
        // device (`SecureStore.storeExists` doc). The ERASE AND RESTORE
        // confirmation is the consent; `init(restoring:)` authors the whole
        // container over whatever was there, exactly like create.
        do {
            let (blob, attachments) = try BackupService.decode(backup, passphrase: passphrase)
            guard !blob.usernameHash.isEmpty, !blob.identityKeyPair.isEmpty else {
                return "backup contains no identity"
            }
            let store = SecureStore(restoring: blob, attachments: attachments,
                                    password: newPassword)
            // docs/BIOMETRIC-UNLOCK.md §5: a new password ⇒ a new store key;
            // anything the secure hardware still holds is stale
            BiometricUnlock.destroy()
            BiometricUnlock.notePasswordUnlock()
            UnlockFailures.reset()                    // §A1: restoring resets it
            let svc = ChatService(store: store)
            service = svc
            stage = .main
            DeadmanSwitch.notePresence(days: store.blob.settings.wipeAfterDays)
            PushDelegate.onToken = { [weak svc] tok in Task { await svc?.sendPushToken(tok) } }
                PushDelegate.onWake = { [weak svc] done in Task { @MainActor in done(await svc?.backgroundDrain() == true ? .newData : .noData) } }
            Task { try? await svc.goOnline() }
            noteKeyStage()                            // a key held while locked applies now
            return nil
        } catch {
            return (error as? BackupService.BackupError)?.errorDescription
                ?? error.localizedDescription
        }
    }

    /// AUTO-LOCK: tear the session down and return to the unlock prompt.
    /// Re-entry is the ordinary submitAuth path, so the duress PIN works at
    /// re-lock exactly as at cold launch (feature, not accident).
    func lock() {
        guard stage == .main, let svc = service else { return }
        bootSettings = svc.store.blob.settings   // keep theme on the auth screen
        svc.shutdown()                            // socket, timers, tasks
        PushDelegate.onWake = nil
        service = nil                             // last ref → key material out of RAM
        SecureStore.sweepPlaintextTmp()
        // docs/DECOY-ACCOUNT.md §6.6: "Nothing may render for a slot that is
        // not the open one." The springboard badge is the one thing this app
        // renders OUTSIDE a session, and it is a per-slot unread count. Left
        // standing it follows the next unlock onto the home screen — a decoy
        // with an empty inbox wearing the real account's five. It goes with
        // the session that counted it.
        Self.clearBadge()
        stage = .auth
    }

    /// §6.6. Also used by the wipe paths, which must not leave a count behind
    /// for whatever opens next.
    static func clearBadge() {
        #if DEBUG
        if ScreenshotDemo.isActive { return }
        #endif
        UNUserNotificationCenter.current().setBadgeCount(0)
    }

    /// docs/DECOY-ACCOUNT.md §5: **the first screen stops claiming to know.**
    ///
    /// `> no identity on this device yet` and `> an identity lives on this
    /// device` are both false statements now. With a container present and no
    /// password typed the app genuinely cannot tell — which is not a
    /// concession, it is the design becoming honest: a wrong password already
    /// had to be indistinguishable from an empty app, and this extends the
    /// same property from one moment to the whole surface.
    ///
    /// So there is no `authMode`, and nothing replaces it. `AuthView` offers
    /// unlocking and creating and asserts neither; which one happens is the
    /// user saying so, through `AuthIntent`, not the filesystem being read.
    enum AuthIntent { case unlock, create }

    func finishBoot() {
        #if DEBUG
        // `--screenshot-demo=boot` holds the boot screen so it can be captured
        if ScreenshotDemo.screen == .boot { return }
        #endif
        stage = .auth
    }

    /// Everything that turns an opened store into a live session — shared by
    /// the password unlock and the biometric unlock, so the two can never
    /// drift (push hooks, dead-man re-arm, a key held while locked).
    private func beginSession(store: SecureStore) {
        #if DEBUG
        if let relay = DemoAutopilot.relayOverride {
            store.update { $0.settings.relayURL = relay }
        }
        if let billing = DemoAutopilot.billingOverride {
            store.update { $0.settings.billingURL = billing }
        }
        if let socks = DemoAutopilot.arg("--demo-socks") {
            store.update { $0.settings.socksProxy = socks }
        }
        #endif
        let svc = ChatService(store: store)
        service = svc
        stage = .main
        // §6.6: the badge belongs to the slot that just opened, and to no
        // other. Set it from THIS session rather than waiting for the next
        // scene transition, which would otherwise leave the previous slot's
        // count on screen for as long as the app stays foregrounded.
        #if DEBUG
        if !ScreenshotDemo.isActive { UNUserNotificationCenter.current().setBadgeCount(svc.totalUnread) }
        #else
        UNUserNotificationCenter.current().setBadgeCount(svc.totalUnread)
        #endif
        DeadmanSwitch.notePresence(days: store.blob.settings.wipeAfterDays)
        PushDelegate.onToken = { [weak svc] tok in Task { await svc?.sendPushToken(tok) } }
        PushDelegate.onWake = { [weak svc] done in
            Task { @MainActor in done(await Self.handleWake(svc)) }
        }
        Task { try? await svc.goOnline() }
        noteKeyStage()                        // a key held while locked applies now
    }

    /// docs/DECOY-ACCOUNT.md §6.6 — "Nothing may render for a slot that is not
    /// the open one", given that the payload cannot help.
    ///
    /// The relay's push is `{"kind":"wake"}` and nothing else, by design, so it
    /// never names an account — and making it name one would be the disclosure
    /// this feature exists to prevent. The only fact the device has is whether
    /// the OPEN session ever asked the relay to wake it. A session with wake
    /// push off never did, so a wake arriving into it belongs to some other
    /// slot: drop it, drain nothing, surface nothing.
    ///
    /// A session with wake push on may well be the one being woken, and
    /// behaves exactly as it always did — which is also what keeps a phone
    /// holding one account and a phone holding two indistinguishable from
    /// outside. The honest gap (§6.6): a decoy that HAS turned wake push on
    /// will still drain for a wake that was meant for the account beside it.
    /// Closing that needs the relay to stop waking a device for an identity
    /// that is not the one signed in, which is relay work and a §8 decision.
    @MainActor
    static func handleWake(_ service: ChatService?) async -> UIBackgroundFetchResult {
        guard let service, service.store.blob.settings.pushEnabled else { return .noData }
        return await service.backgroundDrain() ? .newData : .noData
    }

    // MARK: biometric unlock (docs/BIOMETRIC-UNLOCK.md)

    /// What the unlock screen offers, decided WITHOUT decrypting anything —
    /// every input lives in the Keychain (§2).
    enum BiometricPresentation: Equatable {
        /// The `[ UNLOCK WITH FACE ID ]` button; biometry is attempted once
        /// automatically when the screen appears.
        case offer(String)
        /// §4 says the typed password is due — no biometric button this time.
        case passwordRequired
        /// The OS threw the key away because the enrolled set changed.
        case invalidated
    }

    enum BiometricUnlockResult: Equatable {
        case unlocked, cancelled, notRecognised, invalidated, passwordRequired
        case failed(String)
    }

    func biometricPresentation() -> BiometricPresentation? {
        #if DEBUG
        if ScreenshotDemo.isActive { return nil }   // the demo never sees .auth
        #endif
        guard SecureStore.storeExists else { return nil }
        BiometricUnlock.dropIfBiometryGone()          // §5: biometry left the device
        if BiometricUnlock.wasInvalidated {
            BiometricUnlock.destroy()                 // §4: delete + disable
            return .invalidated
        }
        guard BiometricUnlock.isEnabled,
              let type = BiometricUnlock.biometryType() else { return nil }
        switch BiometricUnlock.decision() {
        case .allowBiometry: return .offer(BiometricUnlock.buttonLabel(type))
        case .requirePassword(.invalidated): return .invalidated
        case .requirePassword: return .passwordRequired
        }
    }

    /// One biometric attempt: prompt, unwrap the stored key, open the store.
    /// The password field stays usable throughout — this never blocks it.
    func unlockWithBiometrics() async -> BiometricUnlockResult {
        switch await BiometricUnlock.attempt() {
        case .unlocked(let keyBytes):
            do {
                let store = try SecureStore(unlockingWithKey: keyBytes)
                // docs/SCRUB-AND-WIPE.md §A1: any successful unlock, typed or
                // biometric, resets the wipe counter.
                UnlockFailures.reset()
                beginSession(store: store)
                return .unlocked
            } catch {
                // the stored key no longer opens this store (restored backup,
                // changed password): it is useless — drop it and ask for the
                // password, which is the only thing that can rebuild it
                BiometricUnlock.destroy()
                return .failed("stored key no longer fits — type your password")
            }
        case .cancelled: return .cancelled
        case .notRecognised: return .notRecognised
        case .invalidated: return .invalidated
        case .passwordRequired: return .passwordRequired
        case .unavailable: return .cancelled      // nothing to say: use the field
        }
    }

    /// The one line a failed unlock shows — for a wrong password AND for the
    /// duress PIN, which must be indistinguishable from it on screen.
    static let wrongPasswordError = "decrypt failed — wrong password"

    /// Returns an error string, or nil on success. `relay` applies only
    /// when creating a new identity (unlock keeps the stored setting).
    func submitAuth(_ intent: AuthIntent, username: String, password: String,
                    relay: String? = nil) async -> String? {
        // Duress PIN check happens BEFORE any unlock attempt.
        if SecureStore.matchesDuressPin(password) {
            // docs/SCRUB-AND-WIPE.md §A1: the duress PIN never touches the
            // failure counter — it is not a wrong password, it is the wipe.
            // It must LOOK like one, though: the same words a wrong password
            // gets, on the same screen (AuthView stays on the sign-in form
            // across `wipeGeneration`). "store corrupt" was a tell.
            wipeEverything()
            return Self.wrongPasswordError
        }
        do {
            // The branch is the USER's choice, never a fact about the disk.
            if intent == .unlock {
                let store = try SecureStore(unlockingWithPassword: password)
                // docs/BIOMETRIC-UNLOCK.md §4: the 7-day clock runs on TYPED
                // passwords, and any successful unlock clears the failures
                BiometricUnlock.notePasswordUnlock()
                UnlockFailures.reset()                // §A1: a typed success clears it
                beginSession(store: store)
                return nil
            } else {
                let store = SecureStore(creatingWithPassword: password)
                BiometricUnlock.destroy()             // a new identity inherits nothing
                UnlockFailures.reset()                // §A1: creating an identity resets it
                BiometricUnlock.notePasswordUnlock()
                if let relay, !relay.isEmpty {
                    store.update { $0.settings.relayURL = relay }
                }
                #if DEBUG
                if let relay = DemoAutopilot.relayOverride {
                    store.update { $0.settings.relayURL = relay }
                }
                if let billing = DemoAutopilot.billingOverride {
                    store.update { $0.settings.billingURL = billing }
                }
                if let socks = DemoAutopilot.arg("--demo-socks") {
                    store.update { $0.settings.socksProxy = socks }
                }
                #endif
                let svc = ChatService(store: store)
                service = svc
                do {
                    try await svc.register(username: username)
                } catch {
                    // registration failed (e.g. username taken): roll back.
                    // The identity keys `register` generated are sitting in
                    // the store's debounced write — drop that first, or the
                    // file comes back 250 ms after the obliterate (with the
                    // keychain salt gone: an undecryptable store, and the
                    // next launch asks to UNLOCK it)
                    store.discardPendingWrites()
                    svc.shutdown()
                    service = nil
                    SecureStore.obliterate()
                    // a key that arrived during the attempt was held against
                    // the half-made store — there is no identity now, forget
                    // it (a later CREATE must not inherit it)
                    noteKeyStage()
                    return "\(error)"
                }
                stage = .main
                DeadmanSwitch.notePresence(days: store.blob.settings.wipeAfterDays)
                PushDelegate.onToken = { [weak svc] tok in Task { await svc?.sendPushToken(tok) } }
                PushDelegate.onWake = { [weak svc] done in Task { @MainActor in done(await svc?.backgroundDrain() == true ? .newData : .noData) } }
                // docs/FIRST-RUN.md §1: the moment CREATE IDENTITY succeeded
                // (never after sign-in / restore / import) add the demo peer
                // exactly as [+] → pyrobot would. Best effort: a failure
                // (offline, private relay without a bot) shows nothing and is
                // never retried — the empty state still points at pyrobot.
                Task { try? await svc.addContact(username: FirstRun.demoPeer) }
                noteKeyStage()
                return nil
            }
        } catch SecureStore.StoreError.badPassword {
            // docs/SCRUB-AND-WIPE.md §A: the ONLY thing that increments the
            // counter — a wrong typed password on the unlock screen.
            UnlockFailures.note()
            if case .wipe = UnlockFailures.decision() {
                // §A3: exactly the duress path, reused rather than
                // reimplemented. §A2: no message, no explanation, no trace —
                // the create screen a fresh install shows.
                wipeEverything()
                return nil
            }
            return Self.wrongPasswordError
        } catch {
            return "\(error)"
        }
    }

    /// The panic wipe, in one place: the duress PIN (§A3) and the tenth wrong
    /// password run through this and nothing else, so the two can never drift.
    private func wipeEverything() {
        Self.clearBadge()                         // §6.6
        // docs/BIOMETRIC-UNLOCK.md §5: the biometric copy of the key dies
        // first, unconditionally — before the network, before the files
        BiometricUnlock.destroy()
        PushDelegate.detach()
        SecureStore.obliterate()
        // A wipe is the ONE moment the app really does know there is nothing
        // to apply a held key to — the filesystem cannot say it, but this
        // code just did it. Drop it outright rather than asking `keyStage`,
        // which now always answers `.locked` (§5).
        #if ACCESS_KEY_UI
        _ = pendingKey.stageChanged(to: .noIdentity)
        #endif
        // re-root the auth screen: the password field must not keep whatever
        // was typed into it, and the countdown goes with the identity it was
        // counting for
        wipeGeneration += 1
        objectWillChange.send()
    }

    /// docs/SCRUB-AND-WIPE.md §A2 — the countdown line above the password
    /// field, or nil before the seventh failure.
    func wipeWarning() -> String? {
        #if DEBUG
        if let n = ScreenshotDemo.unlockFailures { return WipePolicy.warning(failures: n) }
        #endif
        return UnlockFailures.warning
    }
}

#if DEBUG
/// Simulator demo autopilot (DEBUG builds only): `--demo-user alice
/// --demo-peer bob --demo-say "hello"` registers, opens the channel, and
/// sends — so two simulators can demonstrate a live E2E conversation
/// with no manual typing. Compiled out of release builds.
enum DemoAutopilot {
    static func arg(_ name: String) -> String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    static var relayOverride: String? { arg("--demo-relay") }
    /// `--demo-billing http://127.0.0.1:8088`: point redeems at the local
    /// harness (the FIRST-RUN gate mints a code there and deep-links it in).
    static var billingOverride: String? { arg("--demo-billing") }

    static func run(_ model: AppModel) {
        guard let user = arg("--demo-user") else { return }
        Task { @MainActor in
            // the autopilot always makes a fresh identity
            let failure = await model.submitAuth(.create, username: user,
                                                 password: "demo-pass-123")
            if let failure { print("DEMO auth failed: \(failure)") }
            guard let service = model.service, let peer = arg("--demo-peer") else { return }
            // wait for the peer to register, then open the channel
            for _ in 0..<20 {
                try? await Task.sleep(for: .seconds(1))
                if (try? await service.addContact(username: peer)) != nil { break }
            }
            if let say = arg("--demo-say"),
               let contact = service.contacts.first(where: { $0.name == peer }) {
                try? await Task.sleep(for: .seconds(2))
                await service.sendText(say, to: contact)
            }
            // --demo-say2 "<text>" --demo-say2-delay <s>: a second send later —
            // lets a test script kill the relay in between to exercise the
            // FAILED -> reconnect -> auto-retry path.
            if let say2 = arg("--demo-say2"),
               let contact = service.contacts.first(where: { $0.name == peer }) {
                let delay = Double(arg("--demo-say2-delay") ?? "30") ?? 30
                Task { @MainActor in
                    try? await Task.sleep(for: .seconds(delay))
                    await service.sendText(say2, to: contact)
                    print("DEMO say2 dispatched")
                }
            }
            // --demo-pairlink "<pyro-link:...>": drive the desktop-link engine
            // exactly as if the QR was scanned (live-loop testing).
            if let raw = arg("--demo-pairlink"),
               let relayURL = URL(string: relayOverride ?? "ws://127.0.0.1:7444/ws") {
                do {
                    let link = try MirrorLink.parse(raw, expectedRelay: relayURL)
                    try service.startMirror(link)
                    print("DEMO mirror joining pair \(link.pairId)")
                } catch { print("DEMO pairlink parse failed: \(error)") }
            }
        }
    }
}
#endif

struct RootView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        SwiftUI.Group {
            switch model.stage {
            case .boot:
                BootView(settings: model.bootSettings) {
                model.finishBoot()
                #if DEBUG
                DemoAutopilot.run(model)
                #endif
            }
            case .auth:
                AuthView(settings: model.bootSettings,
                     wipeGeneration: model.wipeGeneration,
                     onUnlock: { p in
                await model.submitAuth(.unlock, username: "", password: p)
            }, onCreate: { u, p, r in
                await model.submitAuth(.create, username: u, password: p, relay: r)
            }, onRestore: { data, pass, newPw in
                await model.submitRestore(backup: data, passphrase: pass,
                                          newPassword: newPw)
            },
                     biometricPresentation: { model.biometricPresentation() },
                     onBiometric: { await model.unlockWithBiometrics() },
                     wipeWarning: { model.wipeWarning() })
            case .main:
                if let service = model.service {
                    HomeView(service: service)
                        .onAppear {
                            ScreenshotShield.set(
                                service.store.blob.settings.screenshotBlockOn)
                        }
                }
            }
        }
        // whole-window screenshot shield: captures show black instead of
        // the app. Installed once at launch (secure default); the [cfg]
        // toggle flips it live.
        // docs/FIRST-RUN.md §3: `! not an access key` / `! create your
        // identity first…` over whichever stage is showing
        .overlay(alignment: .bottom) {
            if let flash = model.keyFlash {
                Text(flash).font(Theme.mono(12)).foregroundStyle(Theme.alert)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Theme.panel)
                    .padding(.bottom, 24)
                    .transition(.opacity)
                    .accessibilityIdentifier("keyFlash")
            }
        }
        .background(ShieldInstaller(enabled: Self.shieldWanted))
    }

    /// `--demo-noshield` (and, in DEBUG, the screenshot demo unless
    /// `--demo-shield` is also passed) leave the shield uninstalled so
    /// simulator captures show the UI.
    private static var shieldWanted: Bool {
        #if DEBUG
        if ScreenshotDemo.isActive && !ScreenshotDemo.keepShield { return false }
        return !ProcessInfo.processInfo.arguments.contains("--demo-noshield")
        #else
        return true   // a Release build has no argument that drops the shield
        #endif
    }
}
