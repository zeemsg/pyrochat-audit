#if DEBUG
import Foundation
import UIKit
import AVFoundation
import LibSignalClient

/// Marketing-screenshot demo mode — DEBUG builds only, compiled out of
/// release. Launch argument
/// `--screenshot-demo[=home|chat|settings|notices|group|group-members|first-run|first-run-bot]`
/// (`first-run` = a brand-new identity's three-line empty state,
/// `first-run-bot` = the freshly added pyrobot row with the demo subtitle;
/// add `--demo-share` to open the [share] sheet by itself):
///
///   * seeds a throwaway store under tmp (fixed password, never the real
///     `pyro.store`) with the canned dataset the Android `FakeBackend` uses,
///     so both platforms' website shots show the same conversation
///   * skips boot + auth straight to the home screen and lands on the
///     requested screen (chat = the pyrobot thread, settings = [cfg],
///     notices = the `<pyrochat>` channel)
///   * never touches a relay: `connState` is set to `.online` so the header
///     reads LINK UP, and nothing ever calls `goOnline()`
///   * leaves the screenshot shield uninstalled (see `RootView`) so
///     `xcrun simctl io <udid> screenshot` captures the UI instead of black
///
/// The dataset: contacts pyrobot (verified), alice (2 unread), bob (empty);
/// the pyrobot thread exercises text, `[web]`-tagged text, voice (real AAC
/// so the waveform renders), image, file and system rows, a running burn
/// countdown, an armed-but-unstarted burn, and every outgoing status
/// (READ / DELIVERED / SENT / FAILED — read receipts are switched on so READ
/// is visible). One unseen operator notice pins the `<pyrochat>` channel
/// above the sessions; `--demo-update` (below) adds the update row. The
/// `#ops` group (3 members: demo_user, alice, bob) carries coloured sender
/// lines, own rows with aggregated receipts, an image, the system rows and
/// a burn row — the desktop-link page tests mirror it (MIRROR-GROUPS.md §4):
/// `--demo-pairlink <payload>` joins the browser's pair on the local relay,
/// `--demo-typing` keeps alice and bob typing in `#ops`.
enum ScreenshotDemo {
    enum Screen: String {
        case home, chat, settings, notices, group, groupMembers = "group-members"
        /// docs/FIRST-RUN.md: a brand-new identity — the three-line empty
        /// state (`first-run`) or the freshly added demo peer with no
        /// traffic yet (`first-run-bot`)
        case firstRun = "first-run", firstRunBot = "first-run-bot"
        /// docs/SCRUB-AND-WIPE.md §A2: the unlock screen carrying the
        /// countdown line. No store, no service — the auth stage as it is.
        case unlock
        /// docs/TEXT-SIZE.md §3: the boot screen's fixed-width art, held on
        /// screen (it never hands off) so `--demo-text-size` can be seen on it.
        case boot
    }

    static let flag = "--screenshot-demo"
    static let username = "demo_user"
    static let password = "screenshot-demo-pass"

    /// nil unless the flag is present; bare `--screenshot-demo` = home.
    static let screen: Screen? = {
        for arg in ProcessInfo.processInfo.arguments {
            if arg == flag { return .home }
            if arg.hasPrefix(flag + "=") {
                return Screen(rawValue: String(arg.dropFirst(flag.count + 1))) ?? .home
            }
        }
        return nil
    }()

    static var isActive: Bool { screen != nil }
    /// `--screenshot-demo=unlock` seeds the wipe counter (default 7 = the
    /// first warning, `3 attempts left before this device wipes`). Nothing
    /// is written anywhere: `AppModel.wipeWarning()` reads this directly.
    static var unlockFailures: Int? {
        guard screen == .unlock else { return nil }
        return DemoAutopilot.arg("--demo-unlock-failures").flatMap(Int.init)
            ?? WipePolicy.warnFrom
    }
    static var isFirstRun: Bool { screen == .firstRun || screen == .firstRunBot }

    /// `--demo-text-size <small|normal|large|larger|largest>` (docs/TEXT-SIZE.md):
    /// seed the demo store's `text size` setting, so every demo screen is
    /// captured at that size. An unknown name is left as-is and renders as
    /// `normal`, exactly as a real store would.
    static var textSize: String? {
        DemoAutopilot.arg("--demo-text-size")
    }

    /// `--demo-share`: open the [share] sheet 1.5 s after the home screen
    /// appears, so the system share sheet can be captured unattended.
    static var shareOnLaunch: Bool {
        ProcessInfo.processInfo.arguments.contains("--demo-share")
    }

    /// `--demo-rows N`: replace the canned pyrobot thread with N generated
    /// rows (mostly text of varying length, an image every 25 rows, a voice
    /// note every 40, timestamps increasing) so
    /// `--screenshot-demo=chat --demo-rows 400` opens a long chat — the
    /// scroll-history UI test drives this. nil when absent or not a number.
    static let rows: Int? = {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "--demo-rows"), i + 1 < args.count else { return nil }
        return Int(args[i + 1])
    }()

    /// `--demo-update <name> <build> [<https url>]` (e.g. `--demo-update
    /// 0.1.0 24 https://pyrochat.ca/`): seed the home-screen update row
    /// (`▲ update available: 0.1.0 (24) · tap to get it`; no url = the
    /// non-tappable variant) so it can be screenshotted. nil when absent or
    /// the build is not a number.
    static let update: (name: String, build: Int, url: String?)? = {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "--demo-update"), i + 2 < args.count,
              let build = Int(args[i + 2]) else { return nil }
        let url = i + 3 < args.count && !args[i + 3].hasPrefix("--") ? args[i + 3] : nil
        return (args[i + 1], build, url)
    }()

    /// `--demo-shield`: keep the screenshot shield installed in demo mode
    /// (the default demo leaves it out so captures show the UI).
    static var keepShield: Bool {
        ProcessInfo.processInfo.arguments.contains("--demo-shield")
    }

    /// `--demo-typing`: keep alice and bob "typing" in `#ops` (re-stamped
    /// every 3 s; the sweeper expires a signal after 6 s) so the typing strip
    /// — `alice, bob are typing…` on the phone, `who` on a linked browser —
    /// can be captured without a relay.
    static var typing: Bool {
        ProcessInfo.processInfo.arguments.contains("--demo-typing")
    }

    /// `--demo-confirm-create`: open the create form with docs/DECOY-ACCOUNT.md
    /// §1's cold-create confirmation already up, so it can be captured.
    ///
    /// A capture seam, not a behaviour change: the alert it shows is the real
    /// one, raised by the real state. It exists because iOS AutoFill claims
    /// the password fields on this screen and a UI test cannot type past it —
    /// see the wave 2 report.
    static var confirmCreate: Bool {
        ProcessInfo.processInfo.arguments.contains("--demo-confirm-create")
    }

    /// `--demo-status-worst` (docs/STATUS-AND-CFG.md §1): render the status
    /// page with EVERY row present and every one in the state whose
    /// right-hand line is the longest it can be.
    ///
    /// A capture seam, not a behaviour change — the same kind as
    /// `--demo-confirm-create`. The demo store happens to hold one particular
    /// combination, and a screenshot of it measures that combination; the
    /// page's real layout risk is the widest line it can ever draw, on the
    /// narrowest phone, at `largest`. The biometric row is the sharpest case:
    /// a simulator with nothing enrolled never shows it at all, so the state
    /// with the longest note on the page is exactly the one a capture of the
    /// ordinary demo can never contain.
    static var statusWorstCase: Bool {
        ProcessInfo.processInfo.arguments.contains("--demo-status-worst")
    }

    /// The input behind that flag: nil unless it was passed.
    @MainActor
    static var statusInput: SecurityStatus.Input? {
        guard statusWorstCase else { return nil }
        var i = SecurityStatus.Input()
        i.biometryLabel = "face id"
        i.biometryEnrolled = true
        i.biometricOn = false         // "on: opens without typing, …"
        i.autoLockMinutes = 15
        i.duressSet = false           // "on: a second password that wipes everything"
        i.deadmanDays = nil           // "on: wipes if unopened for 7, 30 or 90 days"
        i.screenshotShield = false    // "on: captures come out black"
        i.unlockFailures = 7
        i.wakePush = false            // the longest note on the page
        i.proxyOn = true
        i.readReceipts = true
        i.typingIndicators = false    // the longer of that row's two lines
        // §1's `desktop link` row in its wordiest shape: a real browser name
        // and a clock, which is the only row here that can be arbitrarily wide
        i.desktopLinkBrowser = "Firefox Developer Edition"
        // §1's pinned `HH:mm` in the phone's own zone, not a 12-hour clock:
        // a capture seam that rendered a format the app cannot produce would
        // be a screenshot nobody could check the spec against.
        i.desktopLinkSince = "23:54"
        i.contactsTotal = 12
        i.contactsVerified = 0        // "none verified — any of them could be anyone"
        i.lastBackup = Date().addingTimeInterval(-137 * 86_400).timeIntervalSince1970
        i.accessDays = 142
        return i
    }

    /// `--demo-scrub-row`: add an OUTGOING file row carrying a §B2 receipt to
    /// the pyrobot thread, so the details sheet has a
    /// `── what was removed before sending ──` section to show. Opt-in: the
    /// canned dataset is shared with Android's FakeBackend, and the two are
    /// meant to stay identical for the website's shots.
    static var scrubRow: Bool {
        ProcessInfo.processInfo.arguments.contains("--demo-scrub-row")
    }

    /// `--demo-staged-file`: stage an attachment in the composer so §B3's
    /// line (`removed author, gps and 1 more from report.pdf`) is on screen.
    static var stagedScrub: Bool {
        ProcessInfo.processInfo.arguments.contains("--demo-staged-file")
    }

    /// `--demo-recording`: show the composer as it is while a voice note is being recorded
    /// (docs/VOICE-CHANGER.md: the line of voices above the REC line), without opening the
    /// microphone. `[X]` and `[SEND]` find no recording there and do nothing.
    static var recording: Bool {
        ProcessInfo.processInfo.arguments.contains("--demo-recording")
    }

    /// `--demo-changing-voice`: show the line the composer has while a note that was sent is
    /// still being changed into its voice, which otherwise is there for a moment only.
    static var changingVoice: Bool {
        ProcessInfo.processInfo.arguments.contains("--demo-changing-voice")
    }

    /// `--demo-no-failed`: drop the trailing `are you there?` / FAILED row
    /// from the pyrobot thread.
    ///
    /// For App Store screenshots only. The row earns its place in the default
    /// dataset — it is the only FAILED in the app and the website shot wants
    /// every outgoing status — but it is also the last line on the screen, so
    /// a store listing captured with it ends on a red FAILED, which is the
    /// word a browsing stranger takes away about a messenger.
    ///
    /// Opt-in, so the default dataset stays byte-identical to Android's
    /// FakeBackend and the website's shots do not move.
    static var hideFailedRow: Bool {
        ProcessInfo.processInfo.arguments.contains("--demo-no-failed")
    }

    /// §B2's own example.
    static let scrubReport = MetadataScrubber.Report(
        removed: [ScrubField("author", "J. Example"),
                  ScrubField("gps", "45.5017, -73.5673"),
                  ScrubField("producer", "Microsoft Word 16.0")],
        supported: true)

    /// docs/SCRUB-AND-WIPE.md §B2's own example, on the demo's outgoing file.
    static func scrubbedReport(_ message: ChatMessage) -> ChatMessage {
        var out = message
        out.removedMetadata = scrubReport.removed
        out.scrubbable = true
        return out
    }

    static var pyrobotHash: String { sha256Hex("pyrobot") }
    /// The demo group's fixed id (the page tests address it as `thread`).
    static let opsGroupId = "0ps00000-0000-4000-8000-000000000001"

    /// `--demo-pairlink <pyro-link:…>` (+ optional `--demo-relay <ws url>`,
    /// default the dev relay ws://127.0.0.1:7444/ws): join the browser's pair
    /// with the canned dataset exactly as if the QR had been scanned. Only
    /// the mirror's second socket opens — the main relay is never contacted,
    /// the header keeps reading LINK UP from the demo's `.online` state, so a
    /// browser send lands as a row (the fan-out then FAILs, there is no relay)
    /// and echoes with its tempId.
    @MainActor
    static func linkIfAsked(_ service: ChatService) {
        guard let raw = DemoAutopilot.arg("--demo-pairlink") else { return }
        let relay = DemoAutopilot.relayOverride ?? "ws://127.0.0.1:7444/ws"
        guard let url = URL(string: relay) else { print("DEMO bad relay url"); return }
        service.store.update { $0.settings.relayURL = relay }
        do {
            let link = try MirrorLink.parse(raw, expectedRelay: url)
            try service.startMirror(link)
            print("DEMO mirror joining pair \(link.pairId)")
        } catch {
            print("DEMO pairlink parse failed: \(error)")
        }
    }

    /// A fresh, seeded, "online" service. Blocking (PBKDF2 + AAC encode,
    /// well under a second on a simulator) — acceptable at demo launch.
    @MainActor
    static func makeService() -> ChatService {
        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("screenshot-demo", isDirectory: true)
        try? fm.removeItem(at: dir)                       // never accumulate stale demo stores
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: password,
                                at: dir.appendingPathComponent("pyro.store"))

        let now = Date()
        func ago(_ seconds: TimeInterval) -> Date { now.addingTimeInterval(-seconds) }
        func newId() -> String { UUID().uuidString.lowercased() }
        func msg(_ direction: ChatMessage.Direction, _ kind: ChatMessage.Kind, _ text: String,
                 _ secondsAgo: TimeInterval, _ status: ChatMessage.Status,
                 burnAfter: TimeInterval? = nil, burnStartedAt: Date? = nil,
                 voiceData: Data? = nil, voiceDuration: TimeInterval? = nil,
                 attachmentId: String? = nil, fileName: String? = nil, fileMime: String? = nil,
                 fileBytes: Int? = nil, imageWidth: Int? = nil, imageHeight: Int? = nil,
                 viaWeb: Bool? = nil) -> ChatMessage {
            ChatMessage(id: newId(), direction: direction, kind: kind, text: text,
                        timestamp: ago(secondsAgo), status: status,
                        burnAfter: burnAfter, burnStartedAt: burnStartedAt,
                        voiceData: voiceData, voiceDuration: voiceDuration,
                        attachmentId: attachmentId, fileName: fileName, fileMime: fileMime,
                        fileBytes: fileBytes, imageWidth: imageWidth, imageHeight: imageHeight,
                        viaWeb: viaWeb)
        }

        // attachments live in the encrypted sidecar store like real ones
        let imgId = newId()
        let jpeg = demoJPEG(width: 640, height: 480)
        try? store.writeAttachment(id: imgId, data: jpeg)
        let fileId = newId()
        let pdfBytes = 43_112
        try? store.writeAttachment(id: fileId, data: Data((0..<pdfBytes).map { UInt8($0 % 251) }))
        // docs/SCRUB-AND-WIPE.md §B2: an OUTGOING file, so the details sheet
        // has a `── what was removed before sending ──` section to show
        let reportId = newId()
        let reportBytes = 61_204
        try? store.writeAttachment(id: reportId,
                                   data: Data((0..<reportBytes).map { UInt8($0 % 241) }))
        let voice = demoVoice(seconds: 4)

        let bot = Contact(hash: pyrobotHash, name: "pyrobot", addedAt: ago(3 * 86_400),
                          verified: true, burnSeconds: nil)
        let alice = Contact(hash: sha256Hex("alice"), name: "alice", addedAt: ago(2 * 86_400),
                            verified: false, burnSeconds: nil)
        let bob = Contact(hash: sha256Hex("bob"), name: "bob", addedAt: ago(3_600),
                          verified: false, burnSeconds: nil)

        var botLog: [ChatMessage] = [
            msg(.incoming, .text, "hello. i am pyrobot — the demo peer. everything you send here is end-to-end encrypted; the relay only sees ciphertext.", 540, .read),
            msg(.outgoing, .text, "hi pyrobot", 500, .read),
            msg(.incoming, .text, "delivered = it reached my device. read = i looked at it (shown only when read receipts are on in [cfg]).", 480, .read),
            msg(.outgoing, .text, "sent from my desktop link", 420, .read, viaWeb: true),
            msg(.incoming, .voice, "", 400, .read, voiceData: voice, voiceDuration: 4),
            msg(.outgoing, .image, "", 360, .delivered,
                attachmentId: imgId, fileMime: "image/jpeg", fileBytes: jpeg.count,
                imageWidth: 640, imageHeight: 480),
            msg(.incoming, .file, "", 300, .read,
                attachmentId: fileId, fileName: "pyrochat-protocol.pdf",
                fileMime: "application/pdf", fileBytes: pdfBytes),

            msg(.incoming, .system, "\u{26A0} peer took a screenshot", 240, .read),
            msg(.incoming, .text, "this message burns one hour after you read it \u{1F525}", 180, .read,
                burnAfter: 3_600, burnStartedAt: ago(10)),
            msg(.outgoing, .text, "this one self-destructs 5m after you read it", 120, .delivered,
                burnAfter: 300),
            msg(.outgoing, .text, "did this get through?", 90, .sent),
        ] + (hideFailedRow ? [] : [
            msg(.outgoing, .text, "are you there?", 60, .failed),
        ])
        if scrubRow {
            // docs/SCRUB-AND-WIPE.md §B2 — an outgoing file whose hidden
            // fields were stripped before it went
            let row = scrubbedReport(msg(.outgoing, .file, "", 280, .delivered,
                                         attachmentId: reportId, fileName: "report.pdf",
                                         fileMime: "application/pdf",
                                         fileBytes: reportBytes))
            botLog.insert(row, at: min(7, botLog.count))
        }
        if let n = rows, n > 0 {
            // `--demo-rows N`: a long thread. Row i carries a "#i" marker so
            // UI tests can tell which rows are on screen; oldest first, 45 s
            // apart, ending 10 min ago.
            let bank = ["relay", "ciphertext", "handshake", "prekey", "ratchet", "burn",
                        "timer", "queue", "drains", "after", "restart", "tonight",
                        "dashboard", "looks", "good", "from", "here", "ack", "watching",
                        "the", "push", "path", "delivered", "receipt", "epoch", "rotate",
                        "session", "verified", "safety", "number", "matches", "o7"]
            let lengths = [3, 9, 15, 6, 24, 4, 12, 30, 8, 18, 2, 21]
            botLog = (0..<n).map { i in
                let secondsAgo = 600 + Double(n - i) * 45
                let outgoing = i % 3 == 1
                let dir: ChatMessage.Direction = outgoing ? .outgoing : .incoming
                let status: ChatMessage.Status = outgoing
                    ? [.read, .delivered, .sent][(i / 3) % 3] : .read
                if i > 0 && i % 25 == 0 {
                    return msg(dir, .image, "", secondsAgo, status,
                               attachmentId: imgId, fileMime: "image/jpeg", fileBytes: jpeg.count,
                               imageWidth: 640, imageHeight: 480)
                }
                if i > 0 && i % 40 == 0 {
                    return msg(dir, .voice, "", secondsAgo, status,
                               voiceData: voice, voiceDuration: 4)
                }
                let words = (0..<lengths[i % lengths.count]).map { bank[(i * 7 + $0) % bank.count] }
                return msg(dir, .text, "#\(i) " + words.joined(separator: " "), secondsAgo, status,
                           viaWeb: outgoing && i % 11 == 0 ? true : nil)
            }
        }
        let aliceLog: [ChatMessage] = [
            msg(.outgoing, .text, "channel test", 7_200, .read),
            msg(.incoming, .text, "hey, is this thing on?", 200, .delivered),
            msg(.incoming, .text, "ping", 30, .delivered),
        ]

        // `#ops`: a 3-member group (demo_user is the creator; bob joined
        // later, so alice is colour 1 and bob colour 2) with coloured lines
        // from both members, own rows with aggregated receipts (READ 2/2,
        // READ 1/2, DELIVERED 1/2), an image, the system rows the spec
        // defines (GROUPS.md §7) and a burn row — the desktop-link page tests
        // mirror exactly this thread (MIRROR-GROUPS.md §4)
        let myHash = sha256Hex(username)
        let ops = Group(id: opsGroupId, name: "ops", creator: myHash,
                        createdAt: ago(2 * 86_400), epoch: 2, policy: .burnRemove, burnSeconds: nil,
                        members: [
                            Member(hash: myHash, name: username, joinedAt: ago(2 * 86_400)),
                            Member(hash: alice.hash, name: "alice", joinedAt: ago(2 * 86_400)),
                            Member(hash: bob.hash, name: "bob", joinedAt: ago(5_000)),
                        ])
        let opsImgId = newId()
        try? store.writeAttachment(id: opsImgId, data: jpeg)
        func grp(_ m: ChatMessage, sender: String? = nil, receipts: [String: String]? = nil) -> ChatMessage {
            var r = m
            r.group = ops.id
            r.sender = sender
            r.receipts = receipts
            return r
        }
        let opsLog: [ChatMessage] = [
            grp(msg(.incoming, .text, "relay restart at 02:00 UTC, queue drains after", 5_400, .read), sender: alice.hash),
            grp(msg(.incoming, .system, "── <bob> joined ──", 5_000, .delivered)),
            grp(msg(.incoming, .text, "ack. i'll watch the push path", 4_900, .read), sender: bob.hash),
            grp(msg(.outgoing, .text, "welcome. dashboard below", 4_800, .read),
                receipts: [alice.hash: "read", bob.hash: "read"]),
            grp(msg(.outgoing, .image, "", 4_700, .delivered,
                    attachmentId: opsImgId, fileMime: "image/jpeg", fileBytes: jpeg.count,
                    imageWidth: 640, imageHeight: 480),
                receipts: [alice.hash: "read", bob.hash: "delivered"]),
            grp(msg(.incoming, .text, "looks good from here", 3_600, .read), sender: alice.hash),
            grp(msg(.incoming, .system, "\u{26A0} <bob> took a screenshot", 2_400, .delivered)),
            grp(msg(.incoming, .system, "── screenshot policy: burn_remove ──", 2_300, .delivered)),
            grp(msg(.incoming, .text, "burn timers apply here too \u{1F525}", 600, .read,
                    burnAfter: 3_600, burnStartedAt: ago(10)), sender: bob.hash),
            grp(msg(.outgoing, .text, "shipping build 22 tonight", 120, .sent),
                receipts: [alice.hash: "delivered"]),
            grp(msg(.incoming, .text, "o7", 45, .delivered), sender: bob.hash),
        ]

        // docs/MESSAGE-DETAILS.md §3: the demo never touches a relay, so the
        // capture buffer would be empty and EVERY `details` sheet would show
        // the "body: not kept" branch. Seed canned envelopes for two rows —
        // canned exactly like the rest of this dataset, never re-encrypted
        // from anything — and deliberately leave the later rows without one,
        // so the absent-capture sheet is screenshottable too.
        let capturedBotRow = rows == nil ? botLog.first {
            $0.direction == .outgoing && $0.kind == .text && $0.viaWeb != true
        } : nil
        let capturedOpsRow = opsLog.first { $0.direction == .outgoing && $0.kind == .text }

        let today = Int64(now.timeIntervalSince1970 / 86_400)
        store.update { blob in
            blob.usernameDisplay = username
            blob.usernameHash = sha256Hex(username)
            if isFirstRun {
                // exactly what CREATE IDENTITY leaves behind: no groups, no
                // traffic, no access key; `first-run-bot` = pyrobot just added
                // (unverified, like [+] → pyrobot) and nothing said yet
                blob.contacts = screen == .firstRunBot
                    ? [Contact(hash: pyrobotHash, name: FirstRun.demoPeer, addedAt: now)] : []
                blob.messages = [:]
                blob.groups = []
                blob.accessTokens = []
            } else {
                blob.contacts = [bot, alice, bob]
                blob.messages = [bot.hash: botLog, alice.hash: aliceLog, bob.hash: [], ops.id: opsLog]
                blob.groups = [ops]
                blob.settings.sendReadReceipts = true      // READ visible in the thread
                // a live subscription so [cfg] reads "30 days remaining", not a nag
                blob.accessTokens = (0..<30).map {
                    AccessToken(epochDay: today + Int64($0), token: Data(), signature: Data())
                }
            }
            blob.settings.pushEnabled = false              // no permission prompt over the screenshots
            if let size = textSize { blob.settings.textSize = size }
            // a redeem in demo mode must never reach the real billing server:
            // loopback port 1 refuses at once ("billing server unreachable")
            blob.settings.billingURL = "http://127.0.0.1:1"
        }

        // notices live in UserDefaults: use a suite of their own so demo
        // runs never leak into (or read) a real identity's board
        let suite = "screenshot-demo"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        // the demo install IS the newest build, so with `--demo-update` the
        // service is told it runs one build behind the advertised one — the
        // frame then goes through the real `versions` ingest and rule
        let service = ChatService(store: store, defaults: defaults,
                                  runningBuild: update.map { $0.build - 1 } ?? UpdateCheck.runningBuild)
        service.connState = .online                        // "● LINK UP" without a relay
        // a real identity key pair so [qr] MY CODE and the [share] text carry
        // a genuine `pyro-add:v1:demo_user:<key>` code (the demo never talks
        // to a relay, so nothing is registered anywhere)
        _ = try? service.crypto.generateIdentity(username: username)
        // docs/TEXT-SIZE.md §3: a throwaway identity key on record for the bot,
        // so the SAFETY NUMBER screen shows 60 real digits and a real QR
        // instead of "no identity key on record". Nothing is registered
        // anywhere — the demo never talks to a relay.
        if let peerKey = try? IdentityKeyPair.generate().publicKey.serialize() {
            store.update { $0.knownIdentities[pyrobotHash] = Data(peerKey) }
        }
        if isFirstRun { return service }                   // no notices, no update row
        if let m = capturedBotRow {
            service.frameCapture.record(
                CapturedFrame(t: "send", id: m.id, peer: bot.hash, ttl: 172_800,
                              kind: "msg", body: demoCiphertext(seed: m.id, bytes: 412),
                              ciphertextBytes: 412),
                for: m.id)
        }
        if let m = capturedOpsRow {
            // a group row's envelope carries a FRESH relay id and one member's
            // address — the sheet shows that one of the three
            service.frameCapture.record(
                CapturedFrame(t: "send", id: newId(), peer: alice.hash, ttl: 172_800,
                              kind: "msg", body: demoCiphertext(seed: m.id, bytes: 389),
                              ciphertextBytes: 389),
                for: m.id)
        }
        if typing {
            // `--demo-typing`: alice and bob keep typing in #ops (the sweeper
            // would expire them after 6 s of silence)
            let typists = [alice.hash, bob.hash]
            Task { [weak service] in
                while let service {
                    var live = service.groupTyping[ops.id] ?? [:]
                    for h in typists { live[h] = Date() }
                    service.groupTyping[ops.id] = live
                    try? await Task.sleep(for: .seconds(3))
                }
            }
        }
        if let update {
            service.ingestVersions(Wire.In(
                t: "versions",
                ios: Wire.VersionJSON(build: update.build, name: update.name, url: update.url)))
        }
        service.ingestNotice(Wire.In(
            t: "notice", id: "demo-notice-1",
            ts: Int64(ago(900).timeIntervalSince1970),
            text: "relay maintenance tonight 02:00\u{2013}02:10 UTC. messages queue, nothing is lost.",
            url: "https://pyrochat.ca/",
            expires: Int64(now.addingTimeInterval(7 * 86_400).timeIntervalSince1970)))
        return service
    }

    /// Deterministic filler that LOOKS like a Signal envelope body (version
    /// byte 0x33 first, so it base64s to `Mw…`). It is noise: no key, no
    /// session, no message ever went through a cipher to make it.
    static func demoCiphertext(seed: String, bytes: Int) -> String {
        var state = UInt64(truncatingIfNeeded: seed.utf8.reduce(into: 0) { $0 = $0 &* 31 &+ UInt64($1) }) | 1
        var data = Data(capacity: bytes)
        data.append(0x33)
        for _ in 1..<max(bytes, 1) {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            data.append(UInt8(truncatingIfNeeded: state >> 33))
        }
        return data.base64EncodedString()
    }

    // MARK: synthetic media (same picture the Android FakeBackend draws)

    /// Dark gradient with a candle-flame oval — the canned `[IMG 640x480]`.
    static func demoJPEG(width: Int, height: Int) -> Data {
        let size = CGSize(width: width, height: height)
        let fmt = UIGraphicsImageRendererFormat.default()
        fmt.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: fmt).image { ctx in
            let cg = ctx.cgContext
            let colors = [UIColor(red: 0x1A / 255, green: 0x0A / 255, blue: 0x02 / 255, alpha: 1).cgColor,
                          UIColor(red: 0x3B / 255, green: 0x1E / 255, blue: 0x05 / 255, alpha: 1).cgColor]
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                         colors: colors as CFArray, locations: [0, 1]) {
                cg.drawLinearGradient(gradient, start: .zero,
                                      end: CGPoint(x: 0, y: size.height), options: [])
            }
            cg.setFillColor(UIColor(red: 1, green: 0xBD / 255, blue: 0x41 / 255, alpha: 1).cgColor)
            cg.fillEllipse(in: CGRect(x: size.width * 0.40, y: size.height * 0.30,
                                      width: size.width * 0.20, height: size.height * 0.55))
            cg.setFillColor(UIColor(red: 1, green: 0xED / 255, blue: 0x91 / 255, alpha: 1).cgColor)
            cg.fillEllipse(in: CGRect(x: size.width * 0.45, y: size.height * 0.52,
                                      width: size.width * 0.10, height: size.height * 0.31))
        }
        return image.jpegData(compressionQuality: 0.8) ?? Data()
    }

    /// A real AAC voice note (the recorder's exact settings) with a
    /// speech-like burst envelope, so `Waveform.bars` draws varied bars and
    /// the play button is live. nil if CoreAudio refuses (row then renders
    /// without a waveform).
    static func demoVoice(seconds: Double) -> Data? {
        let rate = 24_000.0
        let fm = FileManager.default
        let dir = fm.temporaryDirectory
            .appendingPathComponent("screenshot-demo-voice-\(UUID().uuidString)", isDirectory: true)
        let url = dir.appendingPathComponent("v.m4a")
        defer { try? fm.removeItem(at: dir) }
        // per-125 ms "syllable" loudness — 32 bars over 4 s
        let loudness: [Double] = [0.55, 0.9, 0.7, 0.35, 0.8, 0.25, 0.6, 0.95,
                                  0.4, 0.75, 0.3, 0.85, 0.5, 0.2, 0.65, 0.9,
                                  0.45, 0.3, 0.8, 0.6, 0.95, 0.35, 0.7, 0.25,
                                  0.85, 0.5, 0.4, 0.75, 0.3, 0.6, 0.2, 0.45]
        func write() throws {
            let settings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: rate,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: 32_000,
            ]
            let file = try AVAudioFile(forWriting: url, settings: settings)
            let total = Int(rate * seconds)
            guard let buf = AVAudioPCMBuffer(pcmFormat: file.processingFormat,
                                             frameCapacity: AVAudioFrameCount(total)),
                  let ch = buf.floatChannelData?[0] else {
                throw CocoaError(.featureUnsupported)
            }
            for i in 0..<total {
                let t = Double(i) / rate
                let seg = t * 8
                let phase = seg - seg.rounded(.down)
                let amp = loudness[Int(seg) % loudness.count] * sin(.pi * phase)
                ch[i] = Float(0.6 * amp * sin(2 * .pi * 220 * t))
            }
            buf.frameLength = AVAudioFrameCount(total)
            try file.write(from: buf)
            // `file` deinits on return, which finalises the container
        }
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            try write()
            return try Data(contentsOf: url)
        } catch {
            return nil
        }
    }
}
#endif
