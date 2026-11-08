import Foundation

// DECODE-SAFETY CONTRACT (v0.2): every persisted struct in this file decodes
// via an explicit init(from:) using decodeIfPresent with defaults, so stores
// written by ANY older build keep unlocking. Only identity fields (ids,
// direction, timestamps) decode strictly. New enum cases (.read, .image,
// .file) are v0.2+; downgrading a v0.2 store to a v0.1 build is unsupported
// (dev-stage product, documented).

struct Contact: Codable, Identifiable, Hashable {
    var id: String { hash }
    let hash: String          // sha256(username), the routing address
    var name: String          // display name, stored locally & encrypted only
    var addedAt: Date
    var verified: Bool = false // safety-number verified (future)
    /// Per-conversation self-destruct default; nil = use the global setting.
    var burnSeconds: TimeInterval?
}

extension Contact {
    private enum CodingKeys: String, CodingKey { case hash, name, addedAt, verified, burnSeconds }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        hash = try c.decode(String.self, forKey: .hash)
        name = try c.decode(String.self, forKey: .name)
        addedAt = try c.decode(Date.self, forKey: .addedAt)
        verified = try c.decodeIfPresent(Bool.self, forKey: .verified) ?? false
        burnSeconds = try c.decodeIfPresent(TimeInterval.self, forKey: .burnSeconds)
    }
}

struct ChatMessage: Codable, Identifiable, Hashable {
    enum Direction: String, Codable { case incoming, outgoing }
    enum Kind: String, Codable { case text, voice, image, file, system }
    enum Status: String, Codable { case pending, sent, delivered, read, failed }

    let id: String            // uuid
    let direction: Direction
    var kind: Kind
    var text: String          // plaintext, only ever inside the encrypted store
    var timestamp: Date
    var status: Status
    /// Self-destruct: seconds after first read; nil = keep.
    var burnAfter: TimeInterval?
    /// SYNCED burn: nil until the countdown actually starts — on the reader's
    /// device when the row becomes visible, on the sender's device when the
    /// E2E burn_start control arrives. Both sides then hit zero together.
    var burnStartedAt: Date?
    /// Voice note: decrypted AAC audio + duration (inline; ≤~0.5 MiB).
    var voiceData: Data?
    var voiceDuration: TimeInterval?
    // --- attachments (image/file): bytes live in the encrypted sidecar store ---
    var attachmentId: String?     // uuid key into SecureStore's attachment store
    var fileName: String?         // sanitized display name (file kind)
    var fileMime: String?         // "image/jpeg", "application/pdf", …
    var fileBytes: Int?           // PLAINTEXT size for the size label
    var imageWidth: Int?          // pixels, post-normalization
    var imageHeight: Int?
    var viaWeb: Bool?             // composed on a linked desktop browser
    // --- group channels (GROUPS.md §3) ---
    var group: String?            // group id; nil for 1:1 (the thread key IS this id)
    var sender: String?           // sender hash on incoming group rows (thread key ≠ peer)
    var receipts: [String: String]?   // outgoing group rows only: member hash -> "delivered" | "read"
    // --- attachment scrubbing (docs/SCRUB-AND-WIPE.md §B) ---
    /// What the scrubber took out of this attachment before it was encrypted.
    /// nil on every non-attachment row, and on rows written before the
    /// feature existed. Field names and values only — never file contents.
    var removedMetadata: [ScrubField]?
    /// false = a type the scrubber cannot clean (§B2's honest line). nil =
    /// not an attachment, or nothing was ever claimed about it.
    var scrubbable: Bool?

    var burnDeadline: Date? {
        guard let burnAfter, let burnStartedAt else { return nil }
        return burnStartedAt.addingTimeInterval(burnAfter)
    }
}

extension ChatMessage {
    private enum CodingKeys: String, CodingKey {
        case id, direction, kind, text, timestamp, status
        case burnAfter, burnStartedAt, voiceData, voiceDuration
        case attachmentId, fileName, fileMime, fileBytes, imageWidth, imageHeight
        case viaWeb
        case group, sender, receipts
        case removedMetadata, scrubbable
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        direction = try c.decode(Direction.self, forKey: .direction)
        kind = try c.decode(Kind.self, forKey: .kind)
        text = try c.decode(String.self, forKey: .text)
        timestamp = try c.decode(Date.self, forKey: .timestamp)
        status = try c.decode(Status.self, forKey: .status)
        burnAfter = try c.decodeIfPresent(TimeInterval.self, forKey: .burnAfter)
        burnStartedAt = try c.decodeIfPresent(Date.self, forKey: .burnStartedAt)
        voiceData = try c.decodeIfPresent(Data.self, forKey: .voiceData)
        voiceDuration = try c.decodeIfPresent(TimeInterval.self, forKey: .voiceDuration)
        attachmentId = try c.decodeIfPresent(String.self, forKey: .attachmentId)
        fileName = try c.decodeIfPresent(String.self, forKey: .fileName)
        fileMime = try c.decodeIfPresent(String.self, forKey: .fileMime)
        fileBytes = try c.decodeIfPresent(Int.self, forKey: .fileBytes)
        imageWidth = try c.decodeIfPresent(Int.self, forKey: .imageWidth)
        imageHeight = try c.decodeIfPresent(Int.self, forKey: .imageHeight)
        viaWeb = try c.decodeIfPresent(Bool.self, forKey: .viaWeb)
        group = try c.decodeIfPresent(String.self, forKey: .group)
        sender = try c.decodeIfPresent(String.self, forKey: .sender)
        receipts = try c.decodeIfPresent([String: String].self, forKey: .receipts)
        removedMetadata = try c.decodeIfPresent([ScrubField].self, forKey: .removedMetadata)
        scrubbable = try c.decodeIfPresent(Bool.self, forKey: .scrubbable)
    }
}

extension ChatMessage.Status {
    /// Ordering for receipt application. failed ranks with sent so a receipt
    /// (proof the message actually arrived) upgrades a local failed-guess to
    /// delivered/read, but nothing ever moves backwards.
    var receiptRank: Int {
        switch self {
        case .pending: return 0
        case .sent, .failed: return 1
        case .delivered: return 2
        case .read: return 3
        }
    }
}

struct AccessToken: Codable {
    let epochDay: Int64
    let token: Data           // epochDay(8B BE) || nonce(32B)
    let signature: Data       // unblinded RSA-PSS signature
}

/// A local-only encrypted note ("buffer"). Never serialized to the wire —
/// notes exist only inside the SecureStore blob. No share/export by design:
/// the only way out is the user reading the screen.
struct Note: Codable, Identifiable, Hashable {
    let id: String            // uuid string
    var text: String
    let createdAt: Date
    var modifiedAt: Date
    var pinned: Bool?         // optional ⇒ decodeIfPresent-safe

    var isPinned: Bool { pinned ?? false }

    /// First non-empty line, trimmed, capped at 40 chars. "untitled" fallback.
    var title: String {
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let t = line.trimmingCharacters(in: .whitespaces)
            if !t.isEmpty { return String(t.prefix(40)) }
        }
        return "untitled"
    }

    /// One-line preview: first non-empty line AFTER the title line, else "".
    var preview: String {
        var sawTitle = false
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let t = line.trimmingCharacters(in: .whitespaces)
            guard !t.isEmpty else { continue }
            if sawTitle { return String(t.prefix(80)) }
            sawTitle = true
        }
        return ""
    }
}

/// Receipts (and burn-start syncs) waiting to reach the relay
/// (peerHash -> message ids). Persisted so they survive an offline window or
/// app restart. burnStart is ALWAYS-ON burn-timer sync, independent of the
/// read-receipt privacy toggle.
struct ReceiptOutbox: Codable {
    var delivered: [String: [String]] = [:]
    var read: [String: [String]] = [:]
    var burnStart: [String: [String]] = [:]
    var isEmpty: Bool { delivered.isEmpty && read.isEmpty && burnStart.isEmpty }
}

extension ReceiptOutbox {
    private enum CodingKeys: String, CodingKey { case delivered, read, burnStart }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        delivered = try c.decodeIfPresent([String: [String]].self, forKey: .delivered) ?? [:]
        read = try c.decodeIfPresent([String: [String]].self, forKey: .read) ?? [:]
        burnStart = try c.decodeIfPresent([String: [String]].self, forKey: .burnStart) ?? [:]
    }
}

/// An operator notice — the relay's own announcement (maintenance windows,
/// new versions). There is no peer and no E2E layer: the relay speaks, and
/// nobody can reply. Nothing per-user, nothing sensitive, the same list for
/// everyone — so it lives in UserDefaults (`NoticeStore`), OUTSIDE the
/// encrypted store, and the store format is untouched.
struct Notice: Codable, Identifiable, Hashable {
    let id: String
    let text: String
    /// Always `https://` — anything else is rejected on receipt.
    let url: String?
    let ts: Date
    let expires: Date

    /// Mirrors the relay's cap; longer text is clamped, never dropped.
    static let maxTextLength = 500

    /// Wire → model. nil for anything malformed: missing id or `expires`,
    /// empty text, a non-https url, or an `expires` that is not in the future.
    static func parse(_ f: Wire.In, now: Date = Date()) -> Notice? {
        guard f.t == "notice",
              let id = f.id?.trimmingCharacters(in: .whitespaces), !id.isEmpty,
              let expiresRaw = f.expires else { return nil }
        let expires = Date(timeIntervalSince1970: TimeInterval(expiresRaw))
        guard expires > now else { return nil }
        let text = String((f.text ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxTextLength))
        guard !text.isEmpty else { return nil }
        var url: String?
        if let raw = f.url?.trimmingCharacters(in: .whitespaces), !raw.isEmpty {
            guard isAcceptableURL(raw) else { return nil }
            url = raw
        }
        let ts = f.ts.map { Date(timeIntervalSince1970: TimeInterval($0)) } ?? now
        return Notice(id: id, text: text, url: url, ts: ts, expires: expires)
    }

    /// https only, parseable, with a host, no whitespace.
    static func isAcceptableURL(_ s: String) -> Bool {
        guard s.hasPrefix("https://"), !s.contains(where: \.isWhitespace),
              let u = URL(string: s), u.scheme?.lowercased() == "https",
              let host = u.host, !host.isEmpty else { return false }
        return true
    }

    /// Session-list subtitle: the text collapsed onto one line.
    var preview: String {
        text.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}

/// The relay's newest build of THIS app — one platform's entry of the
/// `versions` frame (PROTOCOL.md "Latest versions"). Same nature as a
/// notice: the relay speaking, the same for everyone, nothing per-user, so
/// it is remembered in UserDefaults (`NoticeStore`) outside the encrypted
/// store and the banner shows when the app opens offline.
struct LatestVersion: Codable, Equatable {
    /// iOS `CFBundleVersion` as an integer, ≥ 1.
    let build: Int
    /// Marketing version, display only, ≤ `maxNameLength`.
    let name: String
    /// Where to get it. Always `https://` — anything else is dropped.
    let url: String?

    static let maxNameLength = 32
    static let maxURLLength = 300

    /// Wire → model. nil = malformed entry: `build` missing, not an integer
    /// or < 1, or `name` missing / blank. Display fields are forgiving —
    /// an over-long name is clamped and a url that is not https (or over
    /// the cap) is dropped, never fatal.
    static func parse(_ j: Wire.VersionJSON) -> LatestVersion? {
        guard let build = j.build, build >= 1, let rawName = j.name else { return nil }
        let name = String(rawName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxNameLength))
        guard !name.isEmpty else { return nil }
        var url: String?
        if let raw = j.url?.trimmingCharacters(in: .whitespaces), !raw.isEmpty,
           raw.count <= maxURLLength, Notice.isAcceptableURL(raw) {
            url = raw
        }
        return LatestVersion(build: build, name: name, url: url)
    }
}

/// The client rule for the `versions` frame, identical on both platforms:
/// read our own entry; the banner shows only for a build strictly greater
/// than the running one; a lower / equal / absent entry clears it; a
/// malformed frame is ignored as a whole (the previous state stays).
enum UpdateCheck {
    enum Platform: String { case ios, android }

    /// What one `versions` frame says about this platform.
    enum Verdict: Equatable {
        /// Ignore the frame; keep whatever is remembered.
        case malformed
        /// No entry for this platform: forget the remembered one, no banner.
        case absent
        /// The relay's newest build (not necessarily newer than ours).
        case latest(LatestVersion)
    }

    /// Every present platform entry must parse — the spec voids the whole
    /// frame on anything malformed, even the other platform's entry.
    /// Unknown keys (a third platform later) never reach here: `Wire.In`
    /// ignores them.
    static func read(_ f: Wire.In, platform: Platform = .ios) -> Verdict {
        guard f.t == "versions" else { return .malformed }
        var mine: LatestVersion?
        for (p, entry) in [(Platform.ios, f.ios), (Platform.android, f.android)] {
            guard let entry else { continue }
            guard let v = LatestVersion.parse(entry) else { return .malformed }
            if p == platform { mine = v }
        }
        return mine.map { .latest($0) } ?? .absent
    }

    /// Greater only: the relay's build must beat the running one.
    static func updateAvailable(latest: LatestVersion?, running: Int) -> LatestVersion? {
        guard let latest, latest.build > running else { return nil }
        return latest
    }

    /// This install's `CFBundleVersion` as an integer. An unreadable value
    /// compares as "newest possible": an install whose build number cannot
    /// be read is never nagged (a stuck banner would be worse than none).
    static let runningBuild: Int = {
        let raw = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        return raw.flatMap { Int($0.trimmingCharacters(in: .whitespaces)) } ?? Int.max
    }()
}

/// UserDefaults persistence for the notice board and the seen-id set.
/// Deliberately outside the encrypted store: the data is public by nature,
/// and keeping it out means the store format stays identical on both
/// platforms. Every wipe path ends in `SecureStore.obliterate()`, which
/// clears these keys along with everything else.
enum NoticeStore {
    static let noticesKey = "pyro.notices"        // JSON array of Notice
    static let seenKey = "pyro.notices.seen"      // [String] notice ids
    static let latestVersionKey = "pyro.versions.latest"   // JSON LatestVersion (this platform)

    private static var encoder: JSONEncoder {
        let e = JSONEncoder(); e.dateEncodingStrategy = .secondsSince1970; return e
    }
    private static var decoder: JSONDecoder {
        let d = JSONDecoder(); d.dateDecodingStrategy = .secondsSince1970; return d
    }

    /// Unexpired notices, oldest first. Unreadable data ⇒ empty board.
    static func loadNotices(from defaults: UserDefaults, now: Date = Date()) -> [Notice] {
        guard let json = defaults.string(forKey: noticesKey),
              let list = try? decoder.decode([Notice].self, from: Data(json.utf8)) else { return [] }
        return list.filter { $0.expires > now }.sorted { ($0.ts, $0.id) < ($1.ts, $1.id) }
    }

    static func saveNotices(_ notices: [Notice], to defaults: UserDefaults) {
        guard !notices.isEmpty, let data = try? encoder.encode(notices) else {
            defaults.removeObject(forKey: noticesKey)
            return
        }
        defaults.set(String(decoding: data, as: UTF8.self), forKey: noticesKey)
    }

    static func loadSeen(from defaults: UserDefaults) -> Set<String> {
        Set(defaults.stringArray(forKey: seenKey) ?? [])
    }

    static func saveSeen(_ seen: Set<String>, to defaults: UserDefaults) {
        if seen.isEmpty { defaults.removeObject(forKey: seenKey) }
        else { defaults.set(Array(seen).sorted(), forKey: seenKey) }
    }

    /// The last accepted `versions` entry for this platform (nil = none
    /// advertised, or unreadable data).
    static func loadLatestVersion(from defaults: UserDefaults) -> LatestVersion? {
        guard let json = defaults.string(forKey: latestVersionKey),
              let v = try? decoder.decode(LatestVersion.self, from: Data(json.utf8)),
              v.build >= 1, !v.name.isEmpty else { return nil }
        return v
    }

    static func saveLatestVersion(_ v: LatestVersion?, to defaults: UserDefaults) {
        guard let v, let data = try? encoder.encode(v) else {
            defaults.removeObject(forKey: latestVersionKey)
            return
        }
        defaults.set(String(decoding: data, as: UTF8.self), forKey: latestVersionKey)
    }

    /// Wipe path: nothing about notices (or the advertised version) outlives
    /// the identity.
    static func wipe(_ defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: noticesKey)
        defaults.removeObject(forKey: seenKey)
        defaults.removeObject(forKey: latestVersionKey)
    }
}

struct AppSettings: Codable {
    var theme: String = "ember"       // ember (green) | amber
    /// docs/TEXT-SIZE.md — app-wide type multiplier on top of the phone's own
    /// Dynamic Type: small | normal | large | larger | largest. nil (and any
    /// unknown name) renders as `normal`, and the key is omitted from the
    /// store until it is changed. Same key and spelling on Android.
    var textSize: String? = nil
    var scanlines: Bool = true
    var pushEnabled: Bool = false
    var defaultBurnSeconds: TimeInterval? = nil
    /// Wipe whole threads after N days of inactivity (nil = never).
    var threadAutoBurnDays: Int? = nil
    /// Auto-burn a thread when the peer screenshots it.
    var burnOnScreenshot: Bool = false
    /// Signal-style reciprocity: send read receipts AND display READ only
    /// when true. nil = false (off by default — a privacy product decision).
    var sendReadReceipts: Bool? = nil
    /// Terminal tx/rx blips. nil = true (on by default).
    var soundsEnabled: Bool? = nil
    /// Hide the relay's `<pyrochat>` operator-notice channel. nil = shown.
    /// Same key on Android — the one store-format addition of this feature.
    var hideNotices: Bool? = nil
    /// Send "typing…" signals (E2E, relay-blind). nil = true.
    var sendTyping: Bool? = nil
    /// Exclude app content from screenshots/recordings (captures come out
    /// black). nil = true (on by default).
    var blockScreenshots: Bool? = nil
    /// "host:port" SOCKS5 proxy for ALL relay traffic (censorship evasion).
    var socksProxy: String? = nil
    /// Dead-man switch: wipe everything if not unlocked for N days. nil = off.
    var wipeAfterDays: Int? = nil
    /// Require the password again after N minutes backgrounded.
    /// nil = default (5). 0 = off.
    var autoLockMinutes: Int? = nil
    var relayURL: String = AppSettings.defaultRelayURL
    var billingURL: String = AppSettings.defaultBillingURL
    /// docs/STATUS-AND-CFG.md §1, "`lastBackupAt` — a new blob field, spelled
    /// once". The `backup` row needs a last-backup time and nothing recorded
    /// one; the row is a lie without it.
    ///
    /// **Seconds since 1970, as a number** — NOT a Swift `Date`. Every other
    /// date in this blob is a `Date`, which `JSONEncoder` writes as seconds
    /// since 2001-01-01; this one is pinned to the Unix epoch because §1 pins
    /// it there for both platforms, and a 31-year offset silently applied on
    /// one side is a `backup` row that reads "last made 1970-01-01".
    ///
    /// It lives in the blob, which is per slot by construction
    /// (docs/DECOY-ACCOUNT.md §6.1: "Everything genuinely per-account …
    /// is already inside the blob"). UserDefaults was the cheaper place and
    /// is the wrong one: it is device-level, so a decoy would read back the
    /// other account's backup date as its own — a page whose whole job is to
    /// state facts, stating a false one, in the direction that reassures.
    ///
    /// Optional and omitted when unset, like `textSize`, so a store written
    /// before this field existed decodes unchanged and a store written after
    /// it is byte-identical until a backup is actually exported — no checked-in
    /// fixture moves. **Android writes this same key with this same spelling**:
    /// a platform drops blob keys it does not recognise on its next save, so a
    /// mismatch means a cross-platform restore silently resets the row.
    var lastBackupAt: Double? = nil
    /// The voice a voice note is sent in (docs/VOICE-CHANGER.md): `deep`, `high` or `robot`.
    /// nil = as spoken. Optional and omitted when unset, like `lastBackupAt`, and **the same
    /// key and the same words on Android**. It is in the blob because it is a safety setting
    /// for whoever relies on it: it has to survive a restore, and a decoy has its own.
    var voiceChanger: String? = nil

    // Every build talks to the real relay behind Cloudflare TLS — except the
    // XCTest host, which drives the local dev harness (relay :7444, billing
    // :8088, pyrobot). Running from Xcode on a simulator or phone therefore
    // behaves exactly like TestFlight. To point an Xcode run at the local
    // harness instead, add the scheme argument:
    //   --demo-relay ws://127.0.0.1:7444/ws
    static let defaultRelayURL = underXCTest ? "ws://127.0.0.1:7444/ws" : "wss://relay.pyrochat.ca/ws"
    static let defaultBillingURL = underXCTest ? "http://127.0.0.1:8088" : "https://pyrochat.ca"
    static let underXCTest =
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        || ProcessInfo.processInfo.environment["XCTestBundlePath"] != nil
        || NSClassFromString("XCTestCase") != nil
    /// Addresses users were pointed at before the domain existed (a raw server
    /// IP, or the loopback dev harness) are migrated to the defaults on decode.
    /// Deliberately a RULE, not a list: no server address is compiled into the
    /// app, so the binary reveals nothing about where the relay actually runs.
    static func isLegacyAddress(_ url: String) -> Bool {
        guard let host = URLComponents(string: url)?.host?.lowercased() else { return true }
        if host == "localhost" || host.hasSuffix(".local") { return true }
        let parts = host.split(separator: ".")
        return parts.count == 4 && parts.allSatisfy { Int($0).map { (0...255).contains($0) } ?? false }
    }

    var readReceiptsOn: Bool { sendReadReceipts ?? false }
    var soundsOn: Bool { soundsEnabled ?? true }
    var noticesShown: Bool { !(hideNotices ?? false) }
    var typingOn: Bool { sendTyping ?? true }
    /// The voice notes go out in. A word this build does not know is still a disguise.
    var voice: VoiceEffect { VoiceEffect.of(voiceChanger) }
    var screenshotBlockOn: Bool { blockScreenshots ?? true }
    /// nil = auto-lock off; otherwise the timeout in minutes (default 5).
    var autoLockAfterMinutes: Int? {
        switch autoLockMinutes {
        case nil: return 5          // secure default
        case 0: return nil          // explicitly off
        case let m?: return m
        }
    }
}

extension AppSettings {
    private enum CodingKeys: String, CodingKey {
        case theme, textSize, scanlines, pushEnabled, defaultBurnSeconds, threadAutoBurnDays
        case burnOnScreenshot, sendReadReceipts, soundsEnabled, sendTyping, autoLockMinutes, relayURL, billingURL
        case blockScreenshots, socksProxy, wipeAfterDays, hideNotices
        case lastBackupAt, voiceChanger
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        theme = try c.decodeIfPresent(String.self, forKey: .theme) ?? "ember"
        textSize = try c.decodeIfPresent(String.self, forKey: .textSize)
        scanlines = try c.decodeIfPresent(Bool.self, forKey: .scanlines) ?? true
        pushEnabled = try c.decodeIfPresent(Bool.self, forKey: .pushEnabled) ?? false
        defaultBurnSeconds = try c.decodeIfPresent(TimeInterval.self, forKey: .defaultBurnSeconds)
        threadAutoBurnDays = try c.decodeIfPresent(Int.self, forKey: .threadAutoBurnDays)
        burnOnScreenshot = try c.decodeIfPresent(Bool.self, forKey: .burnOnScreenshot) ?? false
        sendReadReceipts = try c.decodeIfPresent(Bool.self, forKey: .sendReadReceipts)
        soundsEnabled = try c.decodeIfPresent(Bool.self, forKey: .soundsEnabled)
        hideNotices = try c.decodeIfPresent(Bool.self, forKey: .hideNotices)
        sendTyping = try c.decodeIfPresent(Bool.self, forKey: .sendTyping)
        blockScreenshots = try c.decodeIfPresent(Bool.self, forKey: .blockScreenshots)
        socksProxy = try c.decodeIfPresent(String.self, forKey: .socksProxy)
        wipeAfterDays = try c.decodeIfPresent(Int.self, forKey: .wipeAfterDays)
        autoLockMinutes = try c.decodeIfPresent(Int.self, forKey: .autoLockMinutes)
        lastBackupAt = try c.decodeIfPresent(Double.self, forKey: .lastBackupAt)
        voiceChanger = try c.decodeIfPresent(String.self, forKey: .voiceChanger)
        let storedRelay = try c.decodeIfPresent(String.self, forKey: .relayURL)
        relayURL = (storedRelay == nil || AppSettings.isLegacyAddress(storedRelay!))
            ? AppSettings.defaultRelayURL : storedRelay!
        let storedBilling = try c.decodeIfPresent(String.self, forKey: .billingURL)
        billingURL = (storedBilling == nil || AppSettings.isLegacyAddress(storedBilling!))
            ? AppSettings.defaultBillingURL : storedBilling!
    }
}
