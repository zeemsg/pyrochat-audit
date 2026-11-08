import SwiftUI

/// Entry flow: a choice between unlocking whatever is on this device and
/// creating something new — then the matching form.
///
/// ## docs/DECOY-ACCOUNT.md §5 — this screen no longer claims to know
///
/// It used to open by saying either `> an identity lives on this device.` or
/// `> no identity on this device yet.`, routing a returning user straight to
/// a password field. Both statements are now false, and the reason is worth
/// stating plainly because it is the whole feature:
///
/// A container exists from first run and no wipe ever deletes it, so wiped,
/// never used, and holding two accounts are the same 12,582,925 bytes. With no
/// password typed the app genuinely cannot tell them apart — and it must not,
/// because a screen that can say "nothing here" is a screen that can also say
/// "something here", and that is exactly what a supervisor reads after making
/// someone wipe. A wrong password already had to be indistinguishable from an
/// empty app; this extends the same property from one moment to the whole
/// surface.
///
/// So: no `mode`, no branch anywhere below on what the device holds, and both
/// actions offered on equal footing. Which one happens is the user saying so.
///
/// PyroChat identities live ONLY on the device that created them (no cloud
/// login, no recovery), and the screens still say so plainly — that is a fact
/// about the product, not about this device.
struct AuthView: View {
    let settings: AppSettings
    /// Bumped by every wipe path. The device state changed under the screen,
    /// so it re-roots — what used to be `onChange(of: mode)`.
    var wipeGeneration: Int = 0
    let onUnlock: (_ password: String) async -> String?
    let onCreate: (_ username: String, _ password: String, _ relay: String) async -> String?
    let onRestore: (_ backup: Data, _ passphrase: String, _ newPassword: String) async -> String?
    /// docs/BIOMETRIC-UNLOCK.md §3. Both nil on a device that never enabled
    /// the feature — the screen is then exactly what it always was.
    /// `biometricPresentation` is consulted BEFORE anything is decrypted.
    var biometricPresentation: (() -> AppModel.BiometricPresentation?)? = nil
    var onBiometric: (() async -> AppModel.BiometricUnlockResult)? = nil
    /// docs/SCRUB-AND-WIPE.md §A2: the countdown line, from the seventh
    /// consecutive wrong password. nil = nothing to show.
    var wipeWarning: (() -> String?)? = nil

    private enum Screen { case choose, create, signIn, restore }

    /// Start on the PASSWORD FORM, not the chooser.
    ///
    /// docs/DECOY-ACCOUNT.md §5 removed the auto-routing that used to read the
    /// filesystem, because showing a different first screen depending on
    /// whether an identity existed answered the question this design exists to
    /// leave unanswered. Landing everyone on a chooser satisfied that, and
    /// charged every returning user an extra tap on every launch — which is
    /// what a real tester reported, fairly, as a bug.
    ///
    /// Defaulting to the form costs nothing in what it reveals: it is the SAME
    /// first screen on a phone that has never held an account, on one holding
    /// two, and on one wiped ten minutes ago. The leak was ever only in
    /// *branching* on what is there. Creating and restoring stay one tap away
    /// through the header, which is the right way round — signing in happens
    /// daily, creating an identity happens once.
    @State private var screen: Screen = .signIn
    @State private var routed = false

    @State private var username = ""
    @State private var password = ""
    @State private var confirm = ""
    // PRIVATE_SERVER_UI: the relay picker is hidden for the public build —
    // every identity uses the built-in relay. Define PRIVATE_SERVER_UI in
    // SWIFT_ACTIVE_COMPILATION_CONDITIONS to bring the field back for
    // customers running their own server.
    @State private var relay = AppSettings.defaultRelayURL
    @State private var error: String?
    @State private var busy = false
    // restore
    @State private var showBackupPicker = false
    @State private var backupData: Data?
    @State private var backupName = ""
    @State private var passphrase = ""
    // biometrics (docs/BIOMETRIC-UNLOCK.md §3)
    @State private var bio: AppModel.BiometricPresentation?
    @State private var bioNote: String?
    @State private var bioBusy = false
    @State private var bioAutoTried = false
    /// The screen opened on `.signIn` because there was a face to offer, not
    /// because anything on disk was consulted.
    @State private var bioAutoRouted = false
    // docs/SCRUB-AND-WIPE.md §A2
    @State private var wipeNote: String?
    // docs/DECOY-ACCOUNT.md §1, the unconditional cold-create confirmation
    @State private var confirmSlotUse = false
    @State private var slotUseTitle = AuthCopy.coldCreateTitle
    @State private var pendingSlotUse: (() async -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            switch screen {
            case .choose: chooser
            case .create: createForm
            case .signIn: signInForm
            case .restore: restoreForm
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .terminalScreen(settings)
        .onAppear {
            // §5: the SAME root for every device. The app opens on the
            // password form — unconditionally, never because something on disk
            // said an identity was here. Branching on what the device holds is
            // the assertion this screen gave up; opening on a form that looks
            // identical on an empty phone, a full one and a wiped one is not.
            routed = true
            refreshWipeWarning()
            // docs/BIOMETRIC-UNLOCK.md §3: attempt biometry once, automatically.
            // §6.3 of the decoy spec: the offer must look identical whichever
            // slot the stored key belongs to — and on a device with no stored
            // key there is simply no offer, which is the same as before.
            refreshBiometric()
            #if DEBUG
            if ScreenshotDemo.confirmCreate {
                screen = .create
                confirmSlotUse(AuthCopy.coldCreateTitle) {}
                return
            }
            #endif
            if case .offer = bio, !bioAutoTried {
                bioAutoTried = true
                bioAutoRouted = true
                screen = .signIn          // there is a face to offer: show it
                Task { await runBiometric() }
            }
        }
        // §1: shown EVERY time, unconditionally. A confirmation that appeared
        // only when something was there would answer the question this design
        // exists to leave unanswered; one that always appears carries no
        // information at all, and happens to be true.
        .alert(slotUseTitle, isPresented: $confirmSlotUse) {
            Button(AuthCopy.coldCreateConfirm) {
                let work = pendingSlotUse
                pendingSlotUse = nil
                busy = true
                Task { await work?(); busy = false }
            }
            Button("ABORT", role: .cancel) { pendingSlotUse = nil }
        } message: {
            Text(AuthCopy.coldCreateWarning)
        }
        .onChange(of: wipeGeneration) { _, _ in
            // duress wipe / burn: the device state changed under us — re-root
            // to the form a fresh install shows, which is the sign-in form.
            // Nothing else on screen may move: the duress PIN promises to
            // look exactly like a wrong password, and a wrong password keeps
            // the typed field, keeps the biometric button and shows one error
            // line (set by the submit handler, never cleared here). Jumping
            // to the chooser, blanking the field and dropping the Face ID
            // button were the tells.
            screen = .signIn
            confirm = ""; username = ""
            // §A2: the tenth failure wipes and lands here with NO trace —
            // the countdown goes with the identity it was counting for
            wipeNote = nil
        }
    }

    private var header: some View {
        HStack {
            if screen != .choose {
                Button {
                    screen = .choose
                    error = nil
                } label: {
                    Text("[<]").font(Theme.mono(14, weight: .bold))
                        .foregroundStyle(Theme.fgDim(settings))
                }
                .accessibilityLabel(screen == .signIn
                                    ? "Other options: create an identity or restore a backup"
                                    : "Back")
            }
            (Text("PYROCHAT").foregroundColor(Theme.flame)
             + Text(" // SECURE TERMINAL"))
                .font(Theme.mono(15, weight: .bold))
        }
    }

    // MARK: choose

    private var chooser: some View {
        VStack(alignment: .leading, spacing: 16) {
            // §5: the one line at the top of the app, and it asserts nothing
            // about this device. It is a true statement on a phone that has
            // never held an account, on one holding two, and on one wiped
            // ten minutes ago — which is the property the whole feature rests
            // on, said out loud in the first sentence a user reads.
            Text(AuthCopy.root)
                .font(Theme.mono(12))
                .foregroundStyle(Theme.fgDim(settings))
                .fixedSize(horizontal: false, vertical: true)

            Button {
                screen = .signIn
                error = nil
            } label: {
                Text("[ UNLOCK ]")
                    .font(Theme.mono(14, weight: .bold))
                    .padding(.vertical, 10).padding(.horizontal, 14)
                    .overlay(Rectangle().stroke(Theme.fg(settings), lineWidth: 1))
            }
            .accessibilityLabel("Unlock")

            Button {
                screen = .create
                error = nil
            } label: {
                Text("[ CREATE IDENTITY ]")
                    .font(Theme.mono(14, weight: .bold))
                    .padding(.vertical, 10).padding(.horizontal, 14)
                    .overlay(Rectangle().stroke(Theme.fg(settings), lineWidth: 1))
            }
            .accessibilityLabel("Create identity")

            Button {
                screen = .restore
                error = nil
            } label: {
                Text("[ RESTORE BACKUP ]")
                    .font(Theme.mono(14, weight: .bold))
                    .padding(.vertical, 10).padding(.horizontal, 14)
                    .overlay(Rectangle().stroke(Theme.fgDim(settings), lineWidth: 1))
                    .foregroundStyle(Theme.fgDim(settings))
            }
            .accessibilityLabel("Restore from backup")

            Text("identities exist only on the device that created them \u{2014} unless you carry an encrypted backup (made in [cfg]) to the new device. no cloud account, no email, no server-side recovery. a password is the only thing that opens what is here.")
                .font(Theme.mono(11))
                .foregroundStyle(Theme.fgDim(settings).opacity(0.8))
        }
    }

    // MARK: sign in

    /// §5: no branch. A wrong password on a device that holds nothing and a
    /// wrong password on a device holding two accounts do the same thing and
    /// say the same words, which they always had to — this screen just stops
    /// undoing that a line above the field.
    @ViewBuilder
    private var signInForm: some View {
        Text("> enter a password to open what is on this device.")
            .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
            .fixedSize(horizontal: false, vertical: true)
        // docs/BIOMETRIC-UNLOCK.md §3: above the password field — which stays
        // visible and usable at all times, so the duress PIN is always reachable
        biometricBlock
        wipeWarningLine
        secure("password:", text: $password)
        errorLine
        submitButton("[ UNLOCK ]", working: "[ UNLOCK") {
            guard !password.isEmpty else { error = "enter a password"; return }
            let failure = await onUnlock(password)
            if let failure { error = failure }
            // §A2: the count moved — re-read it (the tenth failure has already
            // wiped by now, and the wipe re-roots the screen entirely)
            refreshWipeWarning()
        }
        // The way out of the form, for the one launch in a thousand that is
        // not a sign-in. Worded as a question about the PERSON, never a claim
        // about the device: it reads the same whether this phone has held an
        // account for a year or has never held one.
        Text("> first time on this phone, or restoring one?")
            .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 4)
        Button {
            screen = .choose
            error = nil
        } label: {
            Text("[ OTHER OPTIONS ]")
                .font(Theme.mono(12, weight: .bold))
                .foregroundStyle(Theme.fgDim(settings))
        }
        .accessibilityLabel("Create an identity or restore a backup")
    }

    // MARK: create

    /// §5: no branch here either — and this form is the one place the missing
    /// branch has teeth, because creating an identity AUTHORS the container
    /// and a container may already hold accounts this screen cannot see.
    ///
    /// The old screen made that unreachable by refusing to show the form at
    /// all when a store existed. That refusal was an assertion, so it is gone,
    /// and what replaces it is a warning that is TRUE ON EVERY DEVICE: on a
    /// phone that has never held an account it replaces three slots of noise,
    /// and on one holding two accounts it replaces those. Unconditional, so it
    /// discloses nothing, and accurate either way.
    @ViewBuilder
    private var createForm: some View {
        Text("> new identity — no recovery. lose the password, lose everything.")
            .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
            .fixedSize(horizontal: false, vertical: true)
        replacesWarning
        passwordManagerWarning
            field("username:", text: $username)
            secure("password:", text: $password)
            secure("confirm :", text: $confirm)
            #if PRIVATE_SERVER_UI
            field("relay   :", text: $relay)
            #endif
            errorLine
            submitButton("[ IGNITE ]", working: "[ IGNITE") {
                guard username.count >= 3,
                      username.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" }) else {
                    error = "username: 3+ chars, a-z 0-9 _"; return
                }
                guard password.count >= 8 else { error = "password: 8+ chars"; return }
                guard password == confirm else { error = "passwords do not match"; return }
                #if PRIVATE_SERVER_UI
                let r = relay.trimmingCharacters(in: .whitespaces)
                // NOT `u`: `let u = username` follows, outside the #if, and
                // the collision made the PRIVATE_SERVER_UI variant fail to
                // compile. Found while adding docs/STATUS-AND-CFG.md §2's
                // `advanced …` row, which only exists in that variant — the
                // public build compiles this branch away, so nothing had
                // type-checked it since it was written.
                guard let parsed = URL(string: r),
                      ["ws", "wss"].contains(parsed.scheme ?? "") else {
                    error = "relay: must start with ws:// or wss://"; return
                }
                #else
                let r = ""   // empty = keep the built-in default
                #endif
                let u = username, pw = password
                confirmSlotUse(AuthCopy.coldCreateTitle) {
                    let failure = await onCreate(u, pw, r)
                    if let failure { error = failure }
                    refreshWipeWarning()
                }
            }
        Text("> igniting accepts pyrochat.ca/terms + privacy")
            .font(Theme.mono(10))
            .foregroundStyle(Theme.fgDim(settings).opacity(0.8))
    }

    // MARK: restore

    /// Restoring authors a container too (§6.4), so it carries the same
    /// unconditional warning the create form does, for the same reason.
    @ViewBuilder
    private var restoreForm: some View {
        Text("> restore an encrypted backup made on your old device ([cfg] \u{2192} EXPORT).")
            .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
            .fixedSize(horizontal: false, vertical: true)
        replacesWarning
        passwordManagerWarning
            Button {
                showBackupPicker = true
            } label: {
                Text(backupData == nil ? "[ CHOOSE BACKUP FILE ]"
                                       : "[ \(backupName) \u{2713} ]")
                    .font(Theme.mono(13, weight: .bold))
                    .padding(.vertical, 8).padding(.horizontal, 12)
                    .overlay(Rectangle().stroke(Theme.fg(settings), lineWidth: 1))
            }
            .fileImporter(isPresented: $showBackupPicker,
                          allowedContentTypes: [.data, .item],
                          allowsMultipleSelection: false) { result in
                guard case .success(let urls) = result, let url = urls.first else { return }
                guard url.startAccessingSecurityScopedResource() else {
                    error = "file access denied"; return
                }
                defer { url.stopAccessingSecurityScopedResource() }
                if let data = try? Data(contentsOf: url) {
                    backupData = data
                    backupName = url.lastPathComponent
                    error = nil
                } else {
                    error = "could not read file"
                }
            }
            secure("backup passphrase:", text: $passphrase)
            Text("> then choose a password for THIS device:")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            secure("new password:", text: $password)
            secure("confirm     :", text: $confirm)
            errorLine
            submitButton("[ RESTORE ]", working: "[ RESTORING") {
                guard let backupData else { error = "choose a backup file first"; return }
                guard !passphrase.isEmpty else { error = "enter the backup passphrase"; return }
                guard password.count >= 8 else { error = "new password: 8+ chars"; return }
                guard password == confirm else { error = "passwords do not match"; return }
                let data = backupData, pass = passphrase, pw = password
                confirmSlotUse(AuthCopy.restoreTitle) {
                    if let failure = await onRestore(data, pass, pw) { error = failure }
                }
            }
        Text("restoring MOVES the identity here. stop using the old device — one identity, one device.")
            .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings).opacity(0.8))
    }

    /// §1: the plain advice that sits beside the warning. Unconditional like
    /// everything else on this screen — it points at the safe path without
    /// implying anything about whether this device is on it.
    /// iOS offers to put this password in the phone's password manager, and
    /// the app cannot decline for the user (see `secure`). So it says what
    /// accepting costs, on every screen where a password is chosen.
    private var passwordManagerWarning: some View {
        Text(AuthCopy.passwordManagerWarning)
            .font(Theme.mono(11)).foregroundStyle(Theme.alert)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("passwordManagerWarning")
    }

    private var replacesWarning: some View {
        Text(AuthCopy.coldCreateAdvice)
            .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            .fixedSize(horizontal: false, vertical: true)
    }

    /// §1: shown EVERY time, on every device, whatever is on it. The one place
    /// this app asks "are you sure" and means something by it.
    private func confirmSlotUse(_ title: String, then work: @escaping () async -> Void) {
        pendingSlotUse = work
        slotUseTitle = title
        confirmSlotUse = true
    }

    // MARK: biometrics (docs/BIOMETRIC-UNLOCK.md §3)

    @ViewBuilder
    private var biometricBlock: some View {
        switch bio {
        case .offer(let label):
            Button {
                Task { await runBiometric() }
            } label: {
                Text(bioBusy ? String(label.dropLast(2)) + "\u{2026} ]" : label)
                    .font(Theme.mono(14, weight: .bold))
                    .padding(.vertical, 10).padding(.horizontal, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(Rectangle().stroke(Theme.fg(settings), lineWidth: 1))
            }
            .disabled(bioBusy)
            .accessibilityIdentifier("biometricUnlock")
        case .passwordRequired:
            Text("\u{2500}\u{2500} password required \u{2500}\u{2500}")
                .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
                .accessibilityIdentifier("biometricPasswordRequired")
        case .invalidated:
            Text("\u{2500}\u{2500} biometrics changed: type your password once \u{2500}\u{2500}")
                .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("biometricInvalidated")
        case nil:
            EmptyView()
        }
        if let bioNote {
            Text(bioNote).font(Theme.mono(12)).foregroundStyle(Theme.alert)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("biometricNote")
        }
    }

    private func refreshBiometric() {
        bio = biometricPresentation?()
    }

    /// One attempt. A cancel says nothing (§3) — the user just types.
    private func runBiometric() async {
        guard let onBiometric, !bioBusy else { return }
        bioBusy = true
        bioNote = nil
        defer { bioBusy = false }
        switch await onBiometric() {
        case .unlocked:
            break                       // the model flips the stage under us
        case .cancelled:
            break                       // silently fall back to the field
        case .notRecognised:
            bioNote = "! not recognised \u{2014} try again or type your password"
            refreshBiometric()          // the third failure retires the button
        case .invalidated:
            bio = .invalidated
        case .passwordRequired:
            bio = .passwordRequired
        case .failed(let message):
            bioNote = "! \(message)"
            bio = nil
        }
    }

    // MARK: docs/SCRUB-AND-WIPE.md §A2

    @ViewBuilder
    private var wipeWarningLine: some View {
        if let wipeNote {
            Text(wipeNote)
                .font(Theme.mono(12))
                .foregroundStyle(Theme.alert)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("wipeWarning")
        }
    }

    private func refreshWipeWarning() { wipeNote = wipeWarning?() }

    // MARK: shared pieces

    @ViewBuilder
    private var errorLine: some View {
        if let error {
            Text("! \(error)").font(Theme.mono(12)).foregroundStyle(Theme.alert)
        }
    }

    private func submitButton(_ label: String, working: String,
                              action: @escaping () async -> Void) -> some View {
        Button {
            Task {
                error = nil
                busy = true
                defer { busy = false }
                await action()
            }
        } label: {
            Text(busy ? working + " \u{2026}]" : label)
                .font(Theme.mono(14, weight: .bold))
                .padding(.vertical, 10).padding(.horizontal, 14)
                .overlay(Rectangle().stroke(Theme.fg(settings), lineWidth: 1))
        }
        .disabled(busy)
    }

    /// The label is drawn beside the field rather than inside it, so without
    /// this the field announces nothing at all to VoiceOver.
    private func field(_ label: String, text: Binding<String>) -> some View {
        HStack(spacing: 8) {
            Text(label).font(Theme.mono(13))
            TextField("", text: text)
                .font(Theme.mono(13))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(nil)
                .accessibilityLabel(label)
        }
        .padding(8)
        .background(Theme.panel)
    }

    /// The label is drawn beside the field, so without the accessibility label
    /// the field announces nothing to VoiceOver.
    ///
    /// `textContentType(nil)` is the strongest honest thing available and it is
    /// not enough: iOS still raises "Use Strong Password?" on these fields, and
    /// its own text says the password "will be saved in Passwords". Three ways
    /// of declining were tried and measured — this, a `UITextField` subclass
    /// whose `textContentType` is hard-nil and whose `isSecureTextEntry` is set
    /// only after it is in the hierarchy, and a secure `UITextView` — and the
    /// sheet appears for all three. What does suppress it is claiming the field
    /// is something it is not (`.oneTimeCode`), which would also hand the user
    /// a numeric keyboard for the most important secret in the app.
    ///
    /// So the app says so instead: `AuthCopy.passwordManagerWarning`, on every
    /// screen where a password is chosen. See the wave 2 report.
    private func secure(_ label: String, text: Binding<String>) -> some View {
        HStack(spacing: 8) {
            Text(label).font(Theme.mono(13))
            SecureField("", text: text)
                .font(Theme.mono(13))
                .textContentType(nil)
                .accessibilityLabel(label)
        }
        .padding(8)
        .background(Theme.panel)
    }
}
