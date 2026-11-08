import SwiftUI

/// Add a contact in person: show my code, scan theirs. The QR carries the
/// username AND the identity public key, so a successful scan adds the
/// contact and verifies the safety number in one step.
///
/// docs/FIRST-RUN.md: in Debug the SCAN side also takes an access key
/// (`pyro-key:v1:<CODE>` / `pyrochat://redeem/<CODE>`) — it is handed to
/// the ACCESS KEY dialog in [cfg]. The "paste a code" field exists on
/// every build, so a `pyro-add:` code received through [share] can be
/// pasted and is added + verified exactly like a scan.
struct AddByQRView: View {
    @ObservedObject var service: ChatService
    @Environment(\.dismiss) private var dismiss

    @State private var mode = 0                 // 0 = my code, 1 = scan
    @State private var status: String?
    @State private var added = false
    @State private var pasted = ""
    @State private var cameraDenied = false
    @State private var busy = false
    /// bump to rebuild the scanner (fresh one-shot latch) after a failure
    @State private var scanGeneration = 0

    private var settings: AppSettings { service.store.blob.settings }

    var body: some View {
        // docs/TEXT-SIZE.md §3: the QR itself is a fixed size and never
        // scales; the captions around it do, so the screen scrolls rather
        // than letting a grown caption collide with (or push out) the code.
        ScrollView {
            pane
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.bg.ignoresSafeArea())
    }

    private var pane: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("── ADD BY QR ──").font(Theme.mono(15, weight: .bold))
            Picker("", selection: $mode) {
                Text("MY CODE").tag(0)
                Text("SCAN").tag(1)
            }
            .pickerStyle(.segmented)

            if mode == 0 {
                myCode
            } else if added {
                addedPane
            } else {
                #if targetEnvironment(simulator)
                Text("simulator: no camera")
                    .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                #else
                if cameraDenied {
                    deniedPane
                } else {
                    QRScanner(accept: { raw in handle(raw) },
                              onDenied: { cameraDenied = true })
                        .id(scanGeneration)
                        .frame(maxWidth: .infinity, minHeight: 260)
                        .overlay(RoundedRectangle(cornerRadius: 4)
                            .stroke(Theme.fg(settings).opacity(0.5), lineWidth: 1))
                }
                #endif
                pasteField
            }
            if let status {
                Text(status).font(Theme.mono(12))
                    .foregroundStyle(status.hasPrefix("!") ? Theme.alert : Theme.fg(settings))
            }
            if mode == 1, !added, !busy, status?.hasPrefix("!") == true {
                Button {
                    status = nil
                    scanGeneration += 1        // fresh scanner, latch reset
                } label: {
                    Text("[ RESCAN ]").font(Theme.mono(13, weight: .bold))
                }
            }
        }
    }

    private var myCode: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let payload = service.myAddQRPayload(),
               let img = QRCode.image(for: Data(payload.utf8)) {
                Image(uiImage: img)
                    .interpolation(.none)
                    .resizable().scaledToFit()
                    .frame(maxWidth: 260)
                    .padding(8).background(Color.white)
            }
            Text("<\(service.myUsername)>")
                .font(Theme.mono(14, weight: .bold)).foregroundStyle(Theme.flame)
            Text("they scan this → you're added AND verified on their device in one step. then they show you theirs. not in the same room? [share] on the home screen sends them this code as text.")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
        }
    }

    private var addedPane: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("CHANNEL OPEN \u{2713}").font(Theme.mono(16, weight: .bold))
                .foregroundStyle(Theme.flame)
            if let status { Text(status).font(Theme.mono(12)) }
            Button { dismiss() } label: {
                Text("[ DONE ]").font(Theme.mono(13, weight: .bold))
            }
        }
    }

    private var deniedPane: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("camera access denied").font(Theme.mono(13, weight: .bold))
                .foregroundStyle(Theme.alert)
            Text("scanning needs the camera. allow it in Settings → PyroChat → Camera, then come back — or paste the code below.")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                Text("[ OPEN SETTINGS ]").font(Theme.mono(13, weight: .bold))
            }
        }
    }

    #if ACCESS_KEY_UI
    private static let codeForms = "pyro-add: / pyro-key: / pyrochat://redeem/"
    #else
    private static let codeForms = "pyro-add:"
    #endif

    /// Every build: a shared `pyro-add:` code can be pasted instead of scanned.
    private var pasteField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("or paste a code (\(Self.codeForms))")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            HStack(spacing: 8) {
                TextField("pyro-add:v1:…", text: $pasted)
                    .font(Theme.mono(12))
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .submitLabel(.go)
                    .onSubmit { handle(pasted) }
                    .accessibilityLabel("Paste a code")
                Button { handle(pasted) } label: {
                    Text("[ USE ]").font(Theme.mono(12, weight: .bold))
                }
                .disabled(pasted.trimmingCharacters(in: .whitespaces).isEmpty || busy)
                .accessibilityLabel("Use pasted code")
            }
            .padding(6)
            .background(Theme.panel)
        }
    }

    /// Returns true only for a payload this screen acts on (the scanner then
    /// stops while the add runs / the key is handed over). Wrong or broken
    /// codes keep the camera live and explain themselves.
    @discardableResult
    private func handle(_ raw: String) -> Bool {
        guard !busy else { return false }
        if raw.hasPrefix("pyro-link:") {
            status = "! that's a DESKTOP LINK code — scan it from [cfg] → LINK DESKTOP"
            return false
        }
        #if ACCESS_KEY_UI
        // docs/FIRST-RUN.md §3: an access key goes to the ACCESS KEY dialog
        // in [cfg] (HomeView navigates there the moment the service holds it)
        switch AccessKeyLink.parse(raw) {
        case .key(let code):
            Haptics.tap()
            status = "access key \u{2192} [cfg] \u{2026}"
            service.pendingAccessKey = code
            return true
        case .malformed:
            status = AccessKeyLink.refusal
            return false
        case .none:
            break
        }
        #endif
        let username: String
        let key: Data
        switch ContactCode.parse(raw) {
        case .contact(let u, let k):
            username = u; key = k
        case .malformed:
            status = "! unreadable contact code"
            return false
        case .none:
            if !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                status = "! not a pyrochat code (expected \(Self.codeForms))"
            }
            return false
        }
        busy = true
        status = "opening channel with <\(username)> \u{2026}"
        Task {
            defer { busy = false }
            do {
                let verified = try await service.addContactViaQR(
                    username: username, identityKey: key)
                Haptics.tap()
                added = true
                status = verified
                    ? "<\(username)> added + safety number VERIFIED \u{2713}"
                    : "<\(username)> added — verify the safety number when you can"
            } catch {
                Haptics.warn()
                let e = "\(error)"
                status = e.contains("qr_key_mismatch")
                    ? "! this code does NOT match the key you already hold for <\(username)>. nothing was changed — do not trust this code."
                    : e.contains("identity_mismatch")
                    ? "! IDENTITY MISMATCH — the relay served a different key than this QR. the contact was NOT added; someone may sit between you."
                    : e.contains("bad_username")
                    ? "! invalid username in code"
                    : "! could not add <\(username)>: \(error)"
            }
        }
        return true
    }
}
