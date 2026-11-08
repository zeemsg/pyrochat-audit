import SwiftUI

/// docs/STATUS-AND-CFG.md §1 — "what protects me". One read-only page,
/// reached from the first row of `[cfg]` and from nowhere else.
///
/// Every row is a fact, not a control. Tapping one pops back to `[cfg]` and
/// scrolls to the setting that changes it; the rows that no setting changes
/// are INERT — the wrong-attempt counter, which nothing sets; the
/// verified-contact count, whose control is a safety number inside a chat; and
/// the biometric row on a phone with nothing enrolled, which `[cfg]` does not
/// render at all. A row that jumped somewhere unrelated would teach the wrong
/// thing about the rest.
///
/// **The layout is two lines per row, not three columns.** §1 sketches
/// `label · value · note` across one line. That sketch is desktop-width: the
/// notes run to 45 characters and the narrowest phone fits about 45 in total
/// at `normal`, and 30 at `largest` (docs/TEXT-SIZE.md §3). So the label and
/// the state share the first line, aligned exactly as `[cfg]`'s own rows are,
/// and the right-hand column becomes the dim line beneath it. Nothing is
/// dropped and nothing is truncated; the column is a row.
///
/// Nothing here mentions a decoy in any state — `DECOY-ACCOUNT.md` §3. The
/// copy lives in `SecurityStatus`, which is a pure function, so that is
/// asserted over every input the page can have rather than over this file.
struct StatusView: View {
    @ObservedObject var service: ChatService
    /// Set to the tapped row's control, then the screen pops; `SettingsView`
    /// scrolls to it. §1: "tapping one goes to the setting that changes it".
    @Binding var jump: SecurityStatus.CfgAnchor?
    @Environment(\.dismiss) private var dismiss

    private var settings: AppSettings { service.store.blob.settings }

    /// Read afresh every time the screen appears: the biometric state and the
    /// wrong-attempt counter live outside the store and nothing republishes
    /// this view when they change.
    @State private var input = SecurityStatus.Input()

    /// The biometric answers, probed once per appearance and cached.
    ///
    /// `LAContext` and the Keychain are the expensive reads here, and the
    /// `desktop link` row needs the page to refresh on every service publish
    /// — which in an active session is often. Nothing about the hardware or
    /// the stored key can change while this screen is up, so it is read once
    /// and the cheap reads are redone.
    @State private var biometryLabel = "biometrics"
    @State private var biometryEnrolled = false
    @State private var biometricOn = false

    var body: some View {
        List {
            ForEach(SecurityStatus.groups(input), id: \.title) { group in
                Section {
                    ForEach(group.rows, id: \.label) { row in
                        if let anchor = row.anchor {
                            Button {
                                jump = anchor
                                dismiss()
                            } label: {
                                line(row)
                            }
                            .buttonStyle(.plain)
                            .accessibilityHint("Opens the setting that changes this")
                        } else {
                            line(row)
                        }
                    }
                    if let footer = group.footer {
                        // §1: iOS carries the password-manager warning in this
                        // group. It is not a row because it has no state to
                        // report — the app cannot know what the phone saved —
                        // and it is in the alert colour because it is the one
                        // exception to "a wipe here is total", which is the
                        // claim the rest of this page rests on.
                        Text(footer)
                            .font(Theme.mono(10))
                            .foregroundStyle(Theme.alert)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } header: {
                    TerminalSectionHeader(group.title, settings)
                }
                .listRowBackground(Theme.bg)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .terminalScreen(settings)
        // UNPINNED: §1 names the `[cfg]` row (`[ what protects me ]`) but
        // never the screen. The bracket form does not fit an inline nav bar
        // at `largest` on the narrowest phone, so the words go in without
        // them; `[cfg]` and `[txt]` keep theirs because they are short.
        .navigationTitle("what protects me")
        .navigationBarTitleDisplayMode(.inline)
        // Clearing the anchor on the way IN is what makes tapping the same row
        // twice work: `jump` is what `[cfg]` watches, and a second tap that
        // set it to the value it already held would change nothing and scroll
        // nowhere.
        .onAppear { jump = nil; refresh(probeBiometry: true) }
        // §1's `desktop link` row is the one fact on this page that can stop
        // being true while someone is reading it: a browser can pair or drop
        // at any moment, and `MirrorEngine` republishes the service on both
        // transitions. A page that said `LINKED` about a browser that had
        // gone — or stayed `OFF` while one attached — would be exactly the
        // kind of quiet wrongness this screen exists to remove.
        //
        // Hopped through the run loop on purpose: `objectWillChange` fires
        // BEFORE the value lands, and writing state straight back into a view
        // update is what produces "Publishing changes from within view
        // updates". One turn later the change is done and the write is safe.
        .onReceive(service.objectWillChange.receive(on: RunLoop.main)) { _ in
            refresh(probeBiometry: false)
        }
    }

    /// One row: label and state on the first line, aligned; what that state
    /// costs on the second.
    private func line(_ row: SecurityStatus.Row) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(row.label)
                    .font(Theme.mono(13))
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 4)
                Text(row.value)
                    .font(Theme.mono(13, weight: .bold))
                    .foregroundStyle(Theme.fg(settings))
                    .layoutPriority(1)
                    .fixedSize(horizontal: true, vertical: false)
            }
            // §1's table leaves exactly one cell empty — `access key` with
            // days left, whose sentence IS the value. No blank second line.
            if !row.note.isEmpty {
                Text(row.note)
                    .font(Theme.mono(10))
                    .foregroundStyle(Theme.fgDim(settings))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    // MARK: - the live state

    @MainActor
    private func refresh(probeBiometry: Bool) {
        if probeBiometry {
            biometryLabel = BiometricUnlock.hardwareName()
            biometryEnrolled = BiometricUnlock.biometryType() != nil
            biometricOn = BiometricUnlock.isEnabled(forStoreKey: service.store.rawKey)
        }
        var next = Self.reading(service, probeBiometry: probeBiometry)
        #if DEBUG
        if ScreenshotDemo.statusInput != nil { input = next; return }
        #endif
        if !probeBiometry {
            next.biometryLabel = biometryLabel
            next.biometryEnrolled = biometryEnrolled
            next.biometricOn = biometricOn
        }
        input = next
    }

    /// `live(_:)`, except in DEBUG with `--demo-status-worst`, which hands
    /// back the widest page this screen can draw so a capture measures the
    /// layout rather than whatever the demo store happens to hold.
    @MainActor
    static func reading(_ service: ChatService,
                        probeBiometry: Bool = true) -> SecurityStatus.Input {
        #if DEBUG
        if let forced = ScreenshotDemo.statusInput { return forced }
        #endif
        return live(service, probeBiometry: probeBiometry)
    }

    /// `[cfg]`'s own linked row formats the clock the same way; this reuses
    /// the format rather than inventing a second one, so the two screens
    /// never disagree about when a browser attached.
    ///
    /// §1: `linked to <name>, since <time>`, with `<time>` from
    /// `SecurityStatus.clock` — the same function `[cfg]`'s own linked row
    /// calls, so the two screens cannot render one instant two ways.
    ///
    /// §1 also pins `browser` as the name for a browser that does not
    /// identify itself, which is what `[cfg]` already said.
    @MainActor
    static func desktopLink(_ service: ChatService) -> (browser: String, since: String)? {
        guard let m = service.mirror, case .linked(let since) = m.state else { return nil }
        return (m.browserName ?? "browser", SecurityStatus.clock(since))
    }

    /// Assembles §1's inputs from the four places they actually live: the
    /// encrypted blob (settings, contacts, access tokens), the device
    /// (biometry), the Keychain (the duress verifier, the wrong-attempt
    /// counter) and the clock.
    /// `probeBiometry: false` skips the `LAContext` and Keychain reads and
    /// leaves those three fields at their defaults for the caller to fill
    /// from its own cache. This page refreshes on every service publish so
    /// the `desktop link` row stays live, and in an active session that is
    /// often; nothing about the hardware or the stored key can change while
    /// the screen is up, so it is read once per appearance and not again.
    @MainActor
    static func live(_ service: ChatService,
                     probeBiometry: Bool = true) -> SecurityStatus.Input {
        let s = service.store.blob.settings
        var i = SecurityStatus.Input()
        if probeBiometry {
            // docs/STATUS-AND-CFG.md §1: the row is shown on every phone. The
            // LABEL is the sensor the hardware has (which survives having
            // nothing enrolled); ENROLLED is the separate question `[cfg]`
            // asks to decide whether its own row exists at all
            // (BIOMETRIC-UNLOCK.md §2).
            i.biometryLabel = BiometricUnlock.hardwareName()
            i.biometryEnrolled = BiometricUnlock.biometryType() != nil
            // DECOY-ACCOUNT.md §6.1: THIS slot's key, never the device-level
            // "some slot has one" the unlock screen asks about.
            i.biometricOn = BiometricUnlock.isEnabled(forStoreKey: service.store.rawKey)
        }
        i.autoLockMinutes = s.autoLockAfterMinutes
        i.duressSet = SecureStore.duressPinIsSet
        i.deadmanDays = s.wipeAfterDays
        i.screenshotShield = s.screenshotBlockOn
        i.unlockFailures = UnlockFailures.count
        i.wakePush = s.pushEnabled
        i.proxyOn = !(s.socksProxy ?? "").isEmpty
        i.readReceipts = s.readReceiptsOn
        i.typingIndicators = s.typingOn
        if let link = desktopLink(service) {
            i.desktopLinkBrowser = link.browser
            i.desktopLinkSince = link.since
        }
        i.contactsTotal = service.contacts.count
        i.contactsVerified = service.contacts.filter(\.verified).count
        switch service.sealedStatus {
        case .on: i.senderHidden = .on
        case .notOffered: i.senderHidden = .notOffered
        case .noPrivateConnection: i.senderHidden = .noPrivateConnection
        }
        i.lastBackup = s.lastBackupAt
        i.accessDays = service.accessDaysRemaining
        i.now = Date()
        return i
    }
}
