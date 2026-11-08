private struct BackupShareItem: Identifiable {
    let url: URL
    var id: String { url.path }
}

import SwiftUI

struct SettingsView: View {
    @ObservedObject var service: ChatService
    @State private var confirmWipe = false
    @State private var showDuress = false
    @State private var duressPin = ""
    @State private var duressSet = false
    #if ACCESS_KEY_UI
    @State private var showRedeem = false
    @State private var accessKey = ""
    @State private var redeemStatus: String?
    @State private var redeeming = false
    #endif
    @State private var showBackupPass = false
    @State private var backupPass = ""
    @State private var backupPass2 = ""
    @State private var backupStatus: String?
    @State private var backupShareURL: URL?
    /// A refusal the user must see — shown as its own dialog, never a dim line.
    @State private var backupResult: String?
    @State private var editRelayURL = false
    @State private var editBillingURL = false
    @State private var editProxy = false
    @State private var urlDraft = ""
    /// docs/STATUS-AND-CFG.md §2: the relay and billing addresses sit behind
    /// one `advanced …` row at the bottom of ACCOUNT. Collapsed on every
    /// entry — a self-hoster opens it once a year, everyone else never.
    @State private var showAdvanced = false
    /// §1: the row the status page was tapped on, so this screen can scroll
    /// to the control that changes it. Cleared once it has.
    @State private var jump: SecurityStatus.CfgAnchor?
    /// docs/BIOMETRIC-UNLOCK.md §2. `bioRow` nil = no biometric hardware or
    /// nothing enrolled: the row is hidden entirely. `bioOn` is derived from
    /// the stored key's existence, never from a field in the blob.
    // docs/DECOY-ACCOUNT.md §3
    @State private var showDecoy = false
    @State private var decoyUser = ""
    @State private var decoyPass = ""
    @State private var decoyBusy = false
    /// Only ever `DecoyCopy.failed`, and only until the row is tapped again —
    /// §3: never a success mark, because a row that shows one has disclosed
    /// that a decoy exists.
    @State private var decoyStatus: String?
    /// The outcome, shown in its own dialog so it cannot be scrolled past or
    /// covered by the system's password sheet. Cleared when dismissed.
    @State private var decoyResult: String?

    @State private var bioRow: String?
    @State private var bioOn = false
    @State private var bioBusy = false

    private var settings: AppSettings { service.store.blob.settings }

    #if ACCESS_KEY_UI
    /// docs/FIRST-RUN.md §3: take the key the service holds (RAM only), open
    /// the dialog with it filled in and redeem it at once. Consumed exactly
    /// once; a key arriving while a redeem is in flight waits for it.
    @MainActor
    private func consumePendingKey() {
        guard !redeeming, let code = service.pendingAccessKey else { return }
        service.pendingAccessKey = nil
        accessKey = code
        redeeming = true
        redeemStatus = "redeeming\u{2026}"
        Task { @MainActor in
            async let verdict = redeemVerdict(code)          // submitted immediately
            // UIKit drops an alert presented while the push into [cfg] is
            // still animating — open it once that has settled, and keep it up
            // a moment even when the verdict is instant, so the user sees
            // what was submitted before the result lands under ACCESS
            try? await Task.sleep(for: .milliseconds(700))
            showRedeem = true
            try? await Task.sleep(for: .milliseconds(2_500))
            redeemStatus = await verdict
            redeeming = false
            showRedeem = false
            accessKey = ""
        }
    }

    /// REDEEM tapped in the dialog (the alert dismisses itself).
    @MainActor
    private func submitRedeem(_ raw: String) {
        let code = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty, !redeeming else { return }
        redeeming = true
        redeemStatus = "redeeming\u{2026}"
        Task { @MainActor in
            redeemStatus = await redeemVerdict(code)
            redeeming = false
        }
    }

    /// Result text as before: `✓ access granted: N days` / the error. The
    /// code is never logged or kept beyond the redeem call.
    @MainActor
    private func redeemVerdict(_ code: String) async -> String {
        do {
            try await service.redeemAccessKey(code)
            return "\u{2713} access granted: \(service.accessDaysRemaining) days"
        } catch {
            return "\u{2717} \(error.localizedDescription)"
        }
    }
    #endif

    var body: some View {
        ScrollViewReader { proxy in
        List {
            // docs/STATUS-AND-CFG.md §2: "`[ what protects me ]` is the first
            // row of the screen, above every section" — and §1: the status
            // page is reached from here and from nowhere else.
            Section {
                NavigationLink {
                    StatusView(service: service, jump: $jump)
                } label: {
                    Text(SecurityStatus.entryRow).font(Theme.mono(13, weight: .bold))
                }
                .accessibilityLabel("What protects me")
            }
            .listRowBackground(Theme.bg)

            // docs/STATUS-AND-CFG.md §2. Five sections named after what the
            // person is trying to do, in THIS order. Every row inside keeps
            // its exact wording: this is a reorganisation, not a rewrite.
            //
            // APPEARANCE is SECOND, not third, and that is deliberate.
            // Ordering purely by consequence put `theme` and `text size` —
            // the two rows people actually touch — below the fold on arrival,
            // which is the change most likely to read as "where did the
            // themes go". `[ what protects me ]` already carries protection
            // to the top of the screen, so the detailed section underneath
            // does not also have to bury everything else.
            //
            // Each section is its own property rather than another hundred
            // lines of this one. SwiftUI type-checks a `body` as a single
            // expression, and this one was already the third most expensive
            // in the app (907 ms, behind ChatView and HomeView) before the
            // regrouping added to it; a `[cfg]` that grows one more row should
            // not be the thing that tips it into "unable to type-check in
            // reasonable time".
            protectionSection
            appearanceSection
            leavesSection
            accountSection
            dangerSection
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .terminalScreen(settings)
        .navigationTitle("[cfg]")
        .navigationBarTitleDisplayMode(.inline)
        // §1: "tapping one goes to the setting that changes it". The status
        // page pops itself and leaves the anchor here; the scroll waits for
        // the pop to finish, because scrolling a list that is still off screen
        // lands nowhere.
        .onChange(of: jump) { _, target in
            guard let target else { return }
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(450))
                withAnimation { proxy.scrollTo(target, anchor: .center) }
                try? await Task.sleep(for: .milliseconds(1_600))
                jump = nil
            }
        }
        }
        .alert("BACKUP PASSPHRASE", isPresented: $showBackupPass) {
            SecureField("passphrase (4+ chars)", text: $backupPass).textContentType(nil)
            SecureField("confirm", text: $backupPass2).textContentType(nil)
            Button("EXPORT") {
                // A REFUSAL MUST BE UNMISSABLE. These used to write a dim line
                // beneath the row: the alert closed, nothing happened, and a
                // user reported "I export and nothing happens" — they had typed
                // a short passphrase and never saw the reason. Third time this
                // shape of bug has surfaced here, after the decoy dialog and
                // the decoy's silent progress.
                // Minimum 4, by the owner's decision. It was 12; the argument
                // for 12 is that this file IS the account — identity, every
                // message, every attachment — and no wipe in the app can reach
                // a copy of it sitting in someone's cloud storage. A 4-character
                // passphrase is opened by anyone holding the file in seconds.
                //
                // So the app does not BLOCK a short one, it TELLS the truth
                // about it. Same principle as the rest of the app: say what a
                // choice costs and let the person choose, rather than refusing
                // and explaining nothing.
                guard backupPass.count >= 4 else {
                    backupPass = ""; backupPass2 = ""
                    backupResult = "\u{2717} the passphrase must be at least 4 characters"
                    return
                }
                guard backupPass == backupPass2 else {
                    backupPass = ""; backupPass2 = ""
                    backupResult = "\u{2717} the two passphrases do not match"
                    return
                }
                let pass = backupPass
                backupPass = ""; backupPass2 = ""
                do {
                    let data = try BackupService.export(from: service.store, passphrase: pass)
                    let stamp = { let f = DateFormatter(); f.dateFormat = "yyyyMMdd-HHmm"; return f.string(from: Date()) }()
                    let dir = FileManager.default.temporaryDirectory
                        .appendingPathComponent("export-\(UUID().uuidString)", isDirectory: true)
                    let url = dir.appendingPathComponent("pyrochat-\(stamp).pyrobak")
                    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                    try data.write(to: url, options: [.completeFileProtection])
                    // PRESENT THE PICKER AFTER THE ALERT HAS GONE.
                    //
                    // Setting this inside the alert's own action races the
                    // alert's dismissal, and SwiftUI silently drops the sheet:
                    // the file is encrypted, the status line says "ready", and
                    // no picker ever appears — so the user is told a backup
                    // happened and has nowhere to find it. Same failure as the
                    // decoy dialog: work starts, nothing shows, the app looks
                    // like it ignored you.
                    backupShareURL = url
                    backupStatus = pass.count < 12
                        ? "\u{26A0} \(byteLabel(data.count)) ready \u{2014} short passphrase, anyone with this file can open it"
                        : "\u{2713} \(byteLabel(data.count)) ready \u{2014} choose where to save it"
                } catch {
                    backupResult = "\u{2717} could not create the backup file"
                }
            }
            Button("ABORT", role: .cancel) { backupPass = ""; backupPass2 = "" }
        } message: {
            Text("length is what protects it — four ordinary words beats a short password with symbols in it. the backup is sealed with THIS passphrase — aes-256, same strength as the app. without it the file is unreadable, including by you. it is NOT your account password (but may be the same if you insist).")
        }
        .alert("BACKUP", isPresented: Binding(
            get: { backupResult != nil },
            set: { if !$0 { backupResult = nil } })) {
            Button("OK", role: .cancel) { backupResult = nil }
        } message: {
            Text(backupResult ?? "")
        }
        .sheet(item: Binding(
            get: { backupShareURL.map { BackupShareItem(url: $0) } },
            set: { if $0 == nil {
                if let u = backupShareURL {
                    try? FileManager.default.removeItem(at: u.deletingLastPathComponent())
                }
                backupShareURL = nil
            } })) { item in
            ExportPicker(url: item.url) { saved in
                if let u = backupShareURL {
                    try? FileManager.default.removeItem(at: u.deletingLastPathComponent())
                }
                backupShareURL = nil
                // The picker DOES appear — verified by screenshot, twice, after
                // a user reported "I export and nothing happens". What misled
                // them was this line: the old text said "backup ready", which
                // reads as finished, so a picker dismissed without choosing
                // left them believing a backup existed somewhere.
                backupStatus = saved
                    ? "\u{2713} backup saved"
                    : "export cancelled \u{2014} nothing was written"
                // docs/STATUS-AND-CFG.md §1's `backup` row: recorded when the
                // file actually left the app, never when it was encrypted.
                if saved {
                    service.updateSettings { $0.lastBackupAt = Date().timeIntervalSince1970 }
                }
            }
        }
        #if PRIVATE_SERVER_UI
        .alert("RELAY ADDRESS", isPresented: $editRelayURL) {
            TextField("wss://relay.example/ws", text: $urlDraft)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("SET + RECONNECT") {
                let v = urlDraft.trimmingCharacters(in: .whitespaces)
                if let u = URL(string: v), ["ws", "wss"].contains(u.scheme ?? "") {
                    service.stopMirror()   // the pair lives on the old relay
                    service.updateSettings { $0.relayURL = v }
                    service.goOffline()
                    Task { try? await service.goOnline() }
                }
            }
            Button("ABORT", role: .cancel) {}
        } message: {
            Text("ws:// or wss:// only. reconnects immediately. self-hosters: see docs/SELF_HOSTING.md.")
        }
        .alert("BILLING ADDRESS", isPresented: $editBillingURL) {
            TextField("https://billing.example", text: $urlDraft)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("SET") {
                let v = urlDraft.trimmingCharacters(in: .whitespaces)
                if let u = URL(string: v), ["http", "https"].contains(u.scheme ?? "") {
                    service.updateSettings { $0.billingURL = v }
                }
            }
            Button("ABORT", role: .cancel) {}
        } message: {
            Text("http:// or https:// only. used for access-key redemption.")
        }
        #endif
        .alert("SOCKS PROXY", isPresented: $editProxy) {
            TextField("host:port (empty = direct)", text: $urlDraft)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("SET + RECONNECT") {
                let v = urlDraft.trimmingCharacters(in: .whitespaces)
                let value = RelayClient.parseProxy(v) != nil ? v : nil
                service.stopMirror()
                service.updateSettings { $0.socksProxy = value }
                service.goOffline()
                Task { try? await service.goOnline() }
            }
            Button("ABORT", role: .cancel) {}
        } message: {
            Text("all relay traffic tunnels through this SOCKS5 proxy (e.g. Tor: 127.0.0.1:9050 via Orbot's local proxy). empty or invalid = direct connection.")
        }
        #if ACCESS_KEY_UI
        .alert("ACCESS KEY", isPresented: $showRedeem) {
            TextField("PYRO-XXXX-XXXX-XXXX-XXXX", text: $accessKey)
                .textInputAutocapitalization(.characters).autocorrectionDisabled()
            Button("REDEEM") {
                let code = accessKey
                accessKey = ""
                submitRedeem(code)
            }
            Button("ABORT", role: .cancel) { accessKey = "" }
        } message: {
            Text(redeeming ? "redeeming\u{2026}"
                 : "keys are single-use and anonymous. redeeming links nothing to you.")
        }
        // docs/FIRST-RUN.md §3: a key from the scanner / paste field / deep
        // link opens this dialog pre-filled and submits it at once
        .onChange(of: service.pendingAccessKey) { _, _ in consumePendingKey() }
        .onChange(of: redeeming) { _, now in if !now { consumePendingKey() } }
        .onAppear { consumePendingKey() }
        #endif
        // docs/STATUS-AND-CFG.md §1: the status page reports `duress pin SET`
        // from the Keychain, so this row has to read the same place. It used
        // to derive SET from a `@State` flag set when the dialog was used,
        // which meant a fresh entry to `[cfg]` always said `set duress pin …`
        // however many pins were armed — and two screens disagreeing about a
        // wipe trigger is worse than either being wrong alone.
        .onAppear { refreshBiometricRow(); duressSet = SecureStore.duressPinIsSet }
        // §1: a flush point. Everything DISPLAY writes is held lazily, and
        // leaving [cfg] is when it lands.
        .onDisappear { service.flushDeferredWrites() }
        .alert("DURESS PIN", isPresented: $showDuress) {
            SecureField("pin", text: $duressPin).textContentType(nil)
            Button("ARM") {
                guard duressPin.count >= 4 else { return }
                SecureStore.setDuressPin(duressPin)
                duressPin = ""
                duressSet = true
            }
            Button("ABORT", role: .cancel) { duressPin = "" }
        } message: {
            Text("entering this pin at unlock silently destroys every key and message, then shows an empty app. it looks like a wrong-password error.")
        }
        // docs/DECOY-ACCOUNT.md §3: a username and a password, and nothing
        // else. No "seed it with fake messages" checkbox — a fabricated
        // history that reads like fabrication is worse than a thin real one.
        .alert(DecoyCopy.title, isPresented: $showDecoy) {
            TextField(DecoyCopy.usernameField, text: $decoyUser)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            // docs/DECOY-ACCOUNT.md: a decoy password saved to the iCloud
            // keychain would be a second credential for this app sitting in a
            // place the user cannot see and an examiner sometimes can.
            SecureField(DecoyCopy.passwordField, text: $decoyPass).textContentType(nil)
            Button(DecoyCopy.action) {
                let user = decoyUser, pass = decoyPass
                decoyUser = ""; decoyPass = ""
                decoyBusy = true
                // Say something IMMEDIATELY. The work behind this registers the
                // decoy on the relay, which takes seconds and can fail slowly,
                // and until it answers the screen is indistinguishable from one
                // where the button did nothing.
                decoyStatus = DecoyCopy.working
                Task { @MainActor in
                    let failure = await service.createDecoy(username: user, password: pass)
                    decoyBusy = false
                    let outcome = failure ?? DecoyCopy.created
                    decoyStatus = outcome
                    // …and report it where it cannot be missed or covered. The
                    // line under the row is below a five-line footnote, and
                    // iOS's "Save Password?" sheet lands on top of it.
                    decoyResult = outcome
                }
            }
            Button("ABORT", role: .cancel) { decoyUser = ""; decoyPass = "" }
        } message: {
            // §3: only the explainer may differ, and only for the account that
            // owns the decoy — never the row, and never the outcome wording.
            Text(DecoyCopy.explainer(replacing: service.decoyDialogIsReplacement))
        }
        // The outcome, in its own dialog. §3's invariants hold: the ROW is
        // untouched, and failure is still ONE string for every reason, so this
        // says whether it worked and never why.
        .alert(DecoyCopy.resultTitle,
               isPresented: Binding(get: { decoyResult != nil },
                                    set: { if !$0 { decoyResult = nil } })) {
            Button(DecoyCopy.resultDismiss, role: .cancel) { decoyResult = nil }
        } message: {
            Text(decoyResult ?? "")
        }
        // docs/DECOY-ACCOUNT.md §5: the VOLUNTARY wipe narrows to the current
        // slot, so a person made to wipe under supervision loses only the
        // decoy. The three involuntary paths — duress PIN, ten wrong
        // passwords, dead-man — stay total and still call `obliterate()`,
        // because a partial wipe under duress leaves the interesting slot
        // behind, which is the opposite of what they are for.
        //
        // The wording below is UNCHANGED, and reports success the same way it
        // always has: §4, "a person made to wipe under supervision gets a
        // convincing wipe and loses only the decoy".
        .alert("TOTAL WIPE", isPresented: $confirmWipe) {
            Button("BURN", role: .destructive) {
                Task {
                    service.stopMirror()        // a wiped phone must not leave a live mirror
                    await service.deregister()  // relay forgets keys, queue, push token
                    PushDelegate.detach()
                    service.goOffline()
                    // An account that can PROVE the rest of its container is
                    // noise takes the whole file, exactly as every wipe did
                    // before this feature existed. One that cannot — a decoy,
                    // or an account that has handed a slot out — scrubs its own
                    // slot and leaves the others byte-identical.
                    service.store.burnCurrentSlot()
                    exit(0)
                }
            }
            Button("ABORT", role: .cancel) {}
        } message: {
            Text("destroys all keys and messages on this device and deletes the account from the relay: contacts can no longer reach it and this phone stops receiving wake-ups. there is no undo.")
        }
    }

    @ViewBuilder
    private var protectionSection: some View {
        section("PROTECTION") {
            // docs/BIOMETRIC-UNLOCK.md §2: one row, directly above
            // auto-lock. It moved section; it did not move relative to
            // auto-lock, and it must not.
            if let bioRow {
                anchored(.biometric) {
                    toggle(bioRow, Binding(
                        get: { bioOn },
                        set: { setBiometricUnlock($0) }))
                        .disabled(bioBusy)
                }
                Text(BiometricUnlock.footnote)
                    .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                    .fixedSize(horizontal: false, vertical: true)
            }
            anchored(.autoLock) {
                picker("auto-lock", selection: Binding(
                    get: {
                        switch settings.autoLockAfterMinutes {
                        case nil: return "off"
                        case 1?: return "1m"
                        case 15?: return "15m"
                        default: return "5m"
                        }
                    },
                    set: { v in
                        let minutes: Int = switch v {
                        case "off": 0
                        case "1m": 1
                        case "15m": 15
                        default: 5
                        }
                        service.updateSettings { $0.autoLockMinutes = minutes }
                    }),
                       options: ["off", "1m", "5m", "15m"])
            }
            // §2, "Consequence gets weight": the three rows that destroy
            // data — duress, dead-man, burn — carry the existing alert
            // colour, so `scanlines` and `dead-man wipe` can no longer
            // render identically. No new palette.
            anchored(.duress) {
                Button { showDuress = true } label: {
                    Text(duressSet ? "duress pin: SET" : "set duress pin \u{2026}")
                        .font(Theme.mono(13))
                        .foregroundStyle(Theme.alert)
                }
            }
            anchored(.deadman) {
                picker("dead-man wipe", selection: Binding(
                    get: { settings.wipeAfterDays.map { "\($0)d" } ?? "off" },
                    set: { v in
                        let days: Int? = switch v {
                        case "7d": 7
                        case "30d": 30
                        case "90d": 90
                        default: nil
                        }
                        service.updateSettings { $0.wipeAfterDays = days }
                        DeadmanSwitch.arm(days: days)
                    }),
                       options: ["off", "7d", "30d", "90d"], alert: true)
            }
            Text("dead-man: if this app is not unlocked for that long, everything wipes on the next launch — same shredder as the duress pin.")
                .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
            // docs/DECOY-ACCOUNT.md §3. The row reads the SAME whether or
            // not a decoy exists: no checkmark, no "configured", no colour
            // change, no disabled state. A row that renders differently
            // once used is a disclosure, so nothing here may consult
            // whether one has been set — not even to grey the row out.
            // It moves section and nothing else: §2's "it moves section;
            // it does not change", which includes not taking the alert
            // colour the two rows above it now carry — a decoy does not
            // destroy anything, and a row that changed weight when it
            // changed section would be a second way to notice it.
            // No `accessibilityLabel`: this is a plain text row, and the
            // screen's convention is that those are matched — by VoiceOver
            // and by the UI tests — on their literal visible label. A row
            // that reads one thing and announces another would also be one
            // more place for §3's "reads the same" to drift.
            Button { decoyStatus = nil; showDecoy = true } label: {
                Text(DecoyCopy.row).font(Theme.mono(13))
            }
            .disabled(decoyBusy)
            Text(DecoyCopy.footnote)
                .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                .fixedSize(horizontal: false, vertical: true)
            if let decoyStatus {
                Text(decoyStatus)
                    .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            }
            anchored(.screenshotShield) {
                toggle("block screenshots (capture = black)", Binding(
                    get: { settings.screenshotBlockOn },
                    set: { v in
                        service.updateSettings { $0.blockScreenshots = v }
                        ScreenshotShield.set(v)
                    }))
            }
            toggle("burn thread on peer screenshot", Binding(
                get: { settings.burnOnScreenshot },
                set: { v in service.updateSettings { $0.burnOnScreenshot = v } }))
            // §2's table names no home for the two self-destruct rows, and
            // the BURN section they came from is dissolved. They go LAST in
            // PROTECTION, after every row the table does name — same as
            // Android, so §3's screenshot still matches. They belong here
            // rather than in APPEARANCE or ACCOUNT: they destroy messages on
            // a timer, which is the dead-man switch's family. §2's table now
            // names them explicitly, in this position and this order.
            picker("default self-destruct", selection: Binding(
                get: {
                    switch settings.defaultBurnSeconds {
                    case nil: return "off"
                    case 5?: return "5s"
                    case 60?: return "1m"
                    case 3600?: return "1h"
                    case 86400?: return "1d"
                    case let s?: return "\(Int(s))s"
                    }
                },
                set: { v in
                    let secs: TimeInterval? = switch v {
                    case "5s": 5
                    case "1m": 60
                    case "1h": 3600
                    case "1d": 86400
                    default: nil
                    }
                    service.updateSettings { $0.defaultBurnSeconds = secs }
                }),
                   options: ["off", "5s", "1m", "1h", "1d"])
            picker("thread auto-burn", selection: Binding(
                get: { settings.threadAutoBurnDays.map { "\($0)d" } ?? "never" },
                set: { v in
                    let days: Int? = switch v {
                    case "1d": 1
                    case "7d": 7
                    case "30d": 30
                    default: nil
                    }
                    service.updateSettings { $0.threadAutoBurnDays = days }
                }),
                   options: ["never", "1d", "7d", "30d"])
        }
    }

    @ViewBuilder
    private var leavesSection: some View {
        section("WHAT LEAVES THIS PHONE") {
            anchored(.wakePush) {
                toggle("wake push (zero-content)", Binding(
                    get: { settings.pushEnabled },
                    set: { v in
                        service.updateSettings { $0.pushEnabled = v }
                        if v { PushDelegate.requestAndRegister() }
                        Task { await service.sendPushToken(nil) }   // off → unregister now
                    }))
            }
            if settings.pushEnabled {
                Text("banner + sound whenever a message waits while the app is closed. if iOS opened Settings instead of asking, allow notifications there (Banners, Sounds).")
                    .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
            }
            // §1: "Row order in `what leaves this phone` is the same on both
            // screens, and it is: wake push, proxy, read receipts, typing
            // indicators, desktop link." The status sketch and §2's own table
            // disagreed about where `proxy` sat and both orders shipped; this
            // is the one, so the row moved UP past read receipts.
            //
            // §2: the proxy is a user feature and stays in the everyday path
            // — only the relay and billing addresses collapse.
            anchored(.proxy) {
                Button {
                    urlDraft = settings.socksProxy ?? ""
                    editProxy = true
                } label: {
                    row("socks proxy", value: settings.socksProxy ?? "off")
                }
                .accessibilityLabel("Edit SOCKS proxy")
            }
            anchored(.readReceipts) {
                toggle("read receipts (send + show)", Binding(
                    get: { settings.readReceiptsOn },
                    set: { v in service.updateSettings { $0.sendReadReceipts = v } }))
            }
            anchored(.typing) {
                toggle("typing indicators", Binding(
                    get: { settings.typingOn },
                    set: { v in service.updateSettings { $0.sendTyping = v } }))
            }
            // §1's `desktop link` status row points here, in both of this
            // row's shapes: the live link with its [unlink], and the offer.
            anchored(.desktopLink) {
                if let m = service.mirror, case .linked(let since) = m.state {
                    HStack {
                        Text("linked: \(m.browserName ?? "browser") \u{00B7} code \(m.sas)")
                            .font(Theme.mono(12))
                            .foregroundStyle(Theme.flame)
                        Spacer()
                        // §1: `HH:mm` in the phone's own zone, from the same
                        // function the status page's `desktop link` row uses.
                        // `.shortened` rendered `2:32 PM` here and `14:32`
                        // there for one instant; a time format is not wording,
                        // so §2's "every row keeps its wording" does not cover
                        // it.
                        Text("since \(SecurityStatus.clock(since))")
                            .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                        Button { service.stopMirror() } label: {
                            Text("[unlink]").font(Theme.mono(12, weight: .bold))
                                .foregroundStyle(Theme.alert)
                        }
                        .accessibilityLabel("Unlink browser")
                    }
                } else {
                    NavigationLink {
                        LinkDesktopView(service: service)
                    } label: {
                        Text("LINK DESKTOP \u{2026}").font(Theme.mono(13, weight: .bold))
                    }
                    .accessibilityLabel("Link a desktop browser")
                }
            }
        }
    }

    @ViewBuilder
    private var appearanceSection: some View {
        section("APPEARANCE") {
            picker("theme", selection: Binding(
                get: { settings.theme },
                set: { v in service.updateSettings({ $0.theme = v }, durability: .lazily) }),
                   options: Theme.themeNames)
            // docs/TEXT-SIZE.md §1 — directly under `theme`
            picker("text size", selection: Binding(
                get: { settings.textSize ?? Theme.defaultTextSize },
                set: { v in service.updateSettings({ $0.textSize = v }, durability: .lazily) }),
                   options: Theme.textSizeNames)
            toggle("scanlines", Binding(
                get: { settings.scanlines },
                set: { v in service.updateSettings({ $0.scanlines = v }, durability: .lazily) }))
            toggle("tx/rx sounds", Binding(
                get: { settings.soundsOn },
                set: { v in service.updateSettings({ $0.soundsEnabled = v }, durability: .lazily) }))
            toggle("system notices", Binding(
                get: { settings.noticesShown },
                set: { v in service.updateSettings({ $0.hideNotices = !v }, durability: .lazily) }))
            Text("announcements from the relay: maintenance, new versions. shown as a <pyrochat> channel.")
                .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
        }
    }

    @ViewBuilder
    private var accountSection: some View {
        section("ACCOUNT") {
            // ACCESS_KEY_UI (ios/project.yml): Debug only until access keys
            // unlock something again, along with the dialog, the deep link and
            // the redeem call.
            #if ACCESS_KEY_UI
            row("subscription", value: service.accessDaysRemaining > 0
                ? "\(service.accessDaysRemaining) days remaining"
                : "no access key")
            anchored(.accessKey) {
                Button { showRedeem = true } label: {
                    Text("ENTER ACCESS KEY \u{2026}").font(Theme.mono(13, weight: .bold))
                }
            }
            if let redeemStatus {
                Text(redeemStatus).font(Theme.mono(11))
                    .foregroundStyle(Theme.fgDim(settings))
            }
            #endif
            anchored(.backup) {
                Button { showBackupPass = true } label: {
                    Text("[ EXPORT ENCRYPTED BACKUP \u{2026} ]")
                        .font(Theme.mono(13, weight: .bold))
                }
                .accessibilityLabel("Export encrypted backup")
            }
            if let backupStatus {
                Text(backupStatus).font(Theme.mono(11))
                    .foregroundStyle(Theme.fgDim(settings))
            }
            Text("everything — identity, messages, files, notes — sealed with a passphrase you choose. restore it on a new phone from the sign-in screen. guard the file like the phone itself.")
                .font(Theme.mono(10))
                .foregroundStyle(Theme.fgDim(settings).opacity(0.8))
            if service.blockedHashes.isEmpty {
                row("blocked users", value: "none")
            } else {
                ForEach(Array(service.blockedHashes).sorted(), id: \.self) { hash in
                    HStack {
                        Text("<\(service.contacts.first { $0.hash == hash }?.name ?? String(hash.prefix(12)) + "\u{2026}")>")
                            .font(Theme.mono(12))
                            .foregroundStyle(Theme.flame)
                        Spacer()
                        Button { service.unblockContact(hash) } label: {
                            Text("[unblock]").font(Theme.mono(12))
                        }
                    }
                }
            }
            row("report abuse", value: "in any chat: [block] \u{2192} REPORT")
            row("support", value: "pyrochat.ca")
            // Next to `support`, the same spot Android puts it
            // (`docs/ANDROID-RELEASE.md` §6). Required by the AGPL, not a
            // nicety: libsignal is AGPLv3 and the binary has to carry Signal's
            // notice and the licence itself. Android has had this since
            // versionCode 13; iOS shipped 36 builds without it.
            NavigationLink {
                LicensesView(settings: settings)
            } label: {
                row("licenses", value: "agplv3, libsignal")
            }
            .accessibilityLabel("Open source licenses")
            // §2: "Self-hoster settings collapse. The relay address and
            // the billing address move behind one `advanced …` row at the
            // bottom of ACCOUNT."
            //
            // **Not a user-visible change, and nobody should be told it is
            // one.** Both addresses already sit behind PRIVATE_SERVER_UI,
            // which is false in every shipped build, so no user has ever
            // seen the clutter §0.3 describes. The expander is built anyway
            // and kept behind the same flag, for the private-server build
            // where the rows do exist; in the public build the row itself
            // is absent too, because an `advanced …` that opens onto
            // nothing is worse than what it was meant to tidy.
            #if PRIVATE_SERVER_UI
            Button { showAdvanced.toggle() } label: {
                Text("advanced \u{2026}").font(Theme.mono(13))
                    .foregroundStyle(Theme.fgDim(settings))
            }
            .accessibilityLabel("Advanced server addresses")
            if showAdvanced {
                Button {
                    urlDraft = settings.relayURL
                    editRelayURL = true
                } label: {
                    row("relay", value: settings.relayURL)
                }
                .accessibilityLabel("Edit relay address")
                Button {
                    urlDraft = settings.billingURL
                    editBillingURL = true
                } label: {
                    row("billing", value: settings.billingURL)
                }
                .accessibilityLabel("Edit billing address")
            }
            #endif
        }
    }

    @ViewBuilder
    private var dangerSection: some View {
        section("DANGER") {
            Button {
                confirmWipe = true
            } label: {
                Text("[ BURN EVERYTHING ]")
                    .font(Theme.mono(13, weight: .bold))
                    .foregroundStyle(Theme.alert)
            }
        }
    }

    // MARK: biometric unlock (docs/BIOMETRIC-UNLOCK.md §2)

    /// The row's presence and its state are both read from the device, never
    /// from `AppSettings` — nothing about this feature is in the blob, so the
    /// unlock screen and this toggle can never disagree.
    @MainActor
    private func refreshBiometricRow() {
        BiometricUnlock.dropIfBiometryGone()          // §5
        bioRow = BiometricUnlock.biometryType().map { BiometricUnlock.rowLabel($0) }
        // docs/DECOY-ACCOUNT.md §6.1: "The `[cfg]` row then shows the truth for
        // the account you are in: on if this slot is the one the phone holds,
        // off otherwise." NOT `BiometricUnlock.isEnabled`, which is the
        // device-level question the UNLOCK screen asks and must keep asking —
        // §6.3 requires that offer to look identical whichever slot the stored
        // key belongs to. Inside a decoy whose phone unlocks the real account
        // by face this reads off, which is an ordinary state and not a tell.
        bioOn = BiometricUnlock.isEnabled(forStoreKey: service.store.rawKey)
    }

    /// ON: prompt once to confirm, then store the 32-byte key — a cancelled
    /// or failed prompt leaves the toggle off and changes nothing.
    /// OFF: delete the stored key immediately (§5).
    @MainActor
    private func setBiometricUnlock(_ on: Bool) {
        guard !bioBusy else { return }
        let keyBytes = service.store.rawKey
        guard on else {
            // The row only reads ON for the slot the phone actually holds, so
            // this can only ever be switching OFF our own — but assert it
            // anyway: a decoy must never be able to destroy the real account's
            // stored key, and a stale row is a cheaper bug than that.
            if BiometricUnlock.isEnabled(forStoreKey: keyBytes) { BiometricUnlock.destroy() }
            bioOn = BiometricUnlock.isEnabled(forStoreKey: keyBytes)
            return
        }
        bioOn = true                                   // the switch follows the finger…
        bioBusy = true
        Task { @MainActor in
            // §6.1: "Enabling it in an account replaces whatever was there
            // before." One item, one stored key, and no way to express two.
            _ = await BiometricUnlock.enable(storeKey: keyBytes)
            bioBusy = false
            // …but the truth is the stored key, and whose it is
            bioOn = BiometricUnlock.isEnabled(forStoreKey: keyBytes)
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        Section {
            content()
        } header: {
            TerminalSectionHeader(title, settings)
        }
        .listRowBackground(Theme.bg)
    }

    private func toggle(_ label: String, _ binding: Binding<Bool>) -> some View {
        Toggle(isOn: binding) { Text(label).font(Theme.mono(13)) }
            .tint(Theme.fg(settings))
    }

    /// `alert: true` is docs/STATUS-AND-CFG.md §2's "consequence gets weight":
    /// the existing alert colour on rows that destroy data, and no new palette.
    private func picker(_ label: String, selection: Binding<String>,
                        options: [String], alert: Bool = false) -> some View {
        Picker(selection: selection) {
            ForEach(options, id: \.self) { Text($0).font(Theme.mono(13)) }
        } label: {
            Text(label).font(Theme.mono(13))
                .foregroundStyle(alert ? Theme.alert : Theme.fg(settings))
        }
        .pickerStyle(.menu)
    }

    /// Marks a row as the destination of a status-page tap (§1): it gets a
    /// stable id for `scrollTo`, and lights up for a moment when the jump
    /// lands so the eye finds it in a screen this dense.
    @ViewBuilder
    private func anchored<V: View>(_ anchor: SecurityStatus.CfgAnchor,
                                   @ViewBuilder _ content: () -> V) -> some View {
        content()
            .id(anchor)
            .listRowBackground(jump == anchor ? Theme.panel : Theme.bg)
    }

    private func row(_ label: String, value: String) -> some View {
        HStack {
            Text(label).font(Theme.mono(13))
            Spacer()
            Text(value).font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
        }
    }
}
