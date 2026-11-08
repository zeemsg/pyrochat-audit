import Foundation
import SwiftUI
import UIKit
import LibSignalClient

/// The app's engine room: owns the store, crypto, and relay connection;
/// drives registration/login, the send/receive pipeline, receipts, burn
/// sync, blob transfers, and notes. UI observes this.
@MainActor
final class ChatService: ObservableObject {
    enum ConnState: String { case offline, connecting, authenticating, online }

    @Published var connState: ConnState = .offline
    @Published var contacts: [Contact] = []
    @Published var messages: [String: [ChatMessage]] = [:]
    @Published var notes: [Note] = []
    @Published var lastError: String?
    /// Outgoing chunk-upload progress, message id -> 0…1 (transient).
    @Published var blobProgress: [String: Double] = [:]
    /// Incoming reassembly progress, message id -> state (transient).
    @Published var rxTransfers: [String: RxTransfer] = [:]
    /// Operator notices — the relay's own announcements, shown as the pinned
    /// `<pyrochat>` channel. Active only, oldest first. Persisted in
    /// UserDefaults (NoticeStore), never in the encrypted store.
    @Published private(set) var notices: [Notice] = []
    /// Active notices this device has not opened the channel for yet.
    @Published private(set) var unseenNoticeCount = 0
    private var seenNoticeIds: Set<String> = []
    /// The relay's newest build of this app when it beats the running one
    /// (`versions` frame, PROTOCOL.md "Latest versions") — drives the
    /// home-screen update row. nil = no banner. Independent of the notices
    /// toggle; remembered next to the notices, never in the encrypted store.
    @Published private(set) var updateAvailable: LatestVersion?
    /// Last accepted `versions` entry for this platform, newer than ours or
    /// not — the banner is `updateAvailable(latest:running:)` over it.
    private(set) var latestVersion: LatestVersion?
    /// This install's CFBundleVersion (tests inject one).
    let runningBuild: Int
    /// Home of the notice board + seen ids (tests pass an isolated suite).
    private let defaults: UserDefaults

    struct RxTransfer {
        let thread: String
        let subtype: String
        var received: Int
        let total: Int
    }

    private(set) var store: SecureStore
    let crypto: CryptoEngine
    var relay: RelayClient?
    private var eventTask: Task<Void, Never>?
    private var reconnectAttempts = 0
    private var reconnectTask: Task<Void, Never>?
    private var wantOnline = false
    /// True while `register` runs: a network change must not restart that
    /// attempt, because its failure rolls the new identity back.
    private var registering = false
    private var networkWatch: NetworkWatch?

    /// How long the link waits before calling a connection dead. Tests shorten these.
    struct LinkTiming {
        var keepalive: Duration = .seconds(30)
        /// Long enough for a Tor circuit's round trip. A wrong call only costs a reconnect.
        var pongTimeout: Duration = .seconds(15)
        var loginDeadline: Duration = .seconds(30)
    }
    var linkTiming = LinkTiming()
    /// Continuations for request/response frames (challenge, bundles, etc.)
    /// Waiters per peer hash: concurrent fetches for the same peer share one
    /// round trip (a second fetch used to overwrite the first continuation,
    /// which then never resumed).
    private var pendingBundleFetches: [String: [CheckedContinuation<Wire.In, Error>]] = [:]
    /// Which fetch a deadline was armed for: a later fetch for the same peer has its own.
    private var bundleFetchSerial: [String: Int] = [:]
    private var bundleFetchCount = 0
    /// How long a prekey bundle is waited for. Tests shorten it.
    static var bundleFetchTimeout: Duration = .seconds(15)
    /// Generation counter guarding the login-stage deadlines below.
    private var authWaitGeneration = 0
    /// control-send id -> waiter for the relay's `sent` / `error` verdict
    var pendingControlAcks: [String: CheckedContinuation<Bool, Never>] = [:]
    private var pendingRegister: CheckedContinuation<Wire.In, Error>?
    private var pendingChallenge: CheckedContinuation<Wire.In, Error>?
    private var pendingReady: CheckedContinuation<Wire.In, Error>?
    private(set) var burnTimer: Timer?

    // receipts / burn sync
    enum ReceiptKind: String { case delivered, read, burnStart = "burn_start" }
    /// Thread whose ChatView is currently on screen (nil = none).
    private(set) var activeThread: String?
    /// control-send id -> receipts in flight, awaiting the relay "sent" ack.
    private var inflightReceipts: [String: (peer: String, kind: ReceiptKind, ids: [String])] = [:]
    private var receiptFlushTask: Task<Void, Never>?
    private var keepaliveTask: Task<Void, Never>?
    private var pongTask: Task<Void, Never>?
    /// Session-scoped read-visibility dedupe (never persisted; the status
    /// field is the durable record).
    private var visibleReported: Set<String> = []

    // blob transfers
    enum BlobSubtype: String { case voice, image, file }
    static let blobChunkSize = 64 * 1024
    static let maxBlobCiphertextBytes = 16 * 1024 * 1024   // mirrors relay limit.maxBlobBytes
    static let maxFilePlaintextBytes = 12 * 1024 * 1024
    static let maxBlobChunks = 257

    private struct IncomingBlob {
        let from: String
        let senderName: String?
        let total: Int
        var chunks: [Int: Data]
        let subtype: String
        let burnAfter: TimeInterval?
        let duration: TimeInterval?
        let filename: String?
        let mime: String?
        let width: Int?
        let height: Int?
        let bytes: Int
        let startedAt: Date
        // group channels: the shared row id + the group this blob belongs to
        let refId: String?
        let group: String?
        /// Set when the manifest came sealed: the relay's timestamp on that frame, which
        /// the blob's certificate is judged at (docs/SEALED-SENDER.md §5.6). nil = the
        /// manifest named its sender, and so must the blob.
        let sealedTs: Int64?
    }
    private var blobIncoming: [String: IncomingBlob] = [:]
    private let attachmentCache = NSCache<NSString, NSData>()

    // MARK: group channels (docs/GROUPS.md) — state lives here, logic in ChatService+Groups
    @Published var groups: [Group] = []
    /// group id -> member hash -> last typing signal (sweeper expires after 6 s)
    @Published var groupTyping: [String: [String: Date]] = [:]
    /// Transient home-screen flash (`#name was burned by the creator`, §6b).
    @Published var homeFlash: String?
    #if ACCESS_KEY_UI
    /// docs/FIRST-RUN.md §3: an access key that arrived by QR / paste field /
    /// deep link, waiting for the ACCESS KEY dialog to pick it up. RAM only —
    /// never persisted, never logged; the dialog sets it back to nil the
    /// moment it opens (consumed exactly once).
    @Published var pendingAccessKey: String?
    #endif
    /// relay id -> the group row + recipient that envelope carries (RAM only)
    var groupSends: [String: GroupSend] = [:]
    /// message id -> per-recipient send state while a fan-out is live (RAM only)
    var groupOutbound: [String: [String: GroupMath.SendState]] = [:]
    /// group id -> member -> (epoch, writer) of the last applied roster change (RAM only)
    var rosterStamps: [String: [String: GroupMath.Stamp]] = [:]
    var lastGroupTypingSent: [String: Date] = [:]

    @Published private(set) var blockedHashes: Set<String> = []
    /// Desktop link (QR-paired browser mirror). One at a time, RAM-only.
    @Published private(set) var mirror: MirrorEngine?
    /// peer hash -> last "typing" signal; rows expire after 6 s (sweeper).
    @Published private(set) var typingPeers: [String: Date] = [:]
    /// Threads in which a note that was sent is still being changed into its voice, and how
    /// many of them (docs/VOICE-CHANGER.md). Such a note has no row yet and a long one takes
    /// a moment, so the composer says so.
    @Published private(set) var voiceBeingChanged: [String: Int] = [:]
    private var lastTypingSent: [String: Date] = [:]
    /// message ids already auto-retried once after rate_limited.
    var rateLimitRetried: Set<String> = []
    // trust & heal bookkeeping (transient — deliberately NOT persisted)
    private var identityRowAt: [String: Date] = [:]     // last ⚠ identity row per peer
    private var decryptFailures: [String: Int] = [:]    // consecutive failures per peer
    private var lastHealAt: [String: Date] = [:]        // heal/adopt throttle per peer
    static let healThrottle: TimeInterval = 60

    var myUsername: String { store.blob.usernameDisplay }
    var myHash: String { store.blob.usernameHash }

    /// docs/MESSAGE-DETAILS.md §3: the envelopes this session has actually put
    /// on (or taken off) the wire, so `details` can show one. RAM only,
    /// bounded, and emptied by `shutdown()` / `goOffline()` — which is every
    /// lock, auto-lock, wipe and app exit.
    let frameCapture = FrameCaptureBuffer()

    /// Remember an outgoing envelope against the ROW it belongs to. Only the
    /// frame — the payload it was built from never comes near this. `out` is
    /// what `dispatch` returned: the frame that was actually written.
    func captureFrame(_ out: Outbound, for rowId: String) {
        if capturedRows.count > 200 { capturedRows.removeAll() }   // bookkeeping only; never grows without bound
        capturedRows[out.callerId] = rowId
        guard let frame = CapturedFrame(sending: out.frame) else { return }
        frameCapture.record(frame, for: rowId)
    }

    /// The same for a bare frame.
    func captureFrame(_ out: Wire.Out, for rowId: String) {
        guard let frame = CapturedFrame(sending: out) else { return }
        frameCapture.record(frame, for: rowId)
    }

    // MARK: sealed sender (docs/SEALED-SENDER.md) — state lives here, logic in ChatService+Sealed

    /// What the status page says about it (§5.8).
    enum SealedStatus: Equatable { case on, notOffered, noPrivateConnection }
    @Published var sealedStatus: SealedStatus = .notOffered
    /// Tests only: be, on the wire, a build from before sealed sender.
    var legacyWireOnly = false
    /// The roots a certificate from this login's relay may chain to. Empty: this build
    /// does not offer sealed sender there, and nothing about it is ever sent.
    var sealedRoots: [PublicKey] = []
    /// My certificate and binding for this login (RAM only); nil = the relay offered
    /// none that passes its check.
    var sealedOwn: SealedCertificate?
    var certRenewal: Task<Void, Never>?
    /// The anonymous connection new work goes out on.
    var anonLink: AnonLink?
    /// Every anonymous connection still open: the current one and any that is retiring.
    var anonLinks: [AnonLink] = []
    var anonOpening: Task<AnonLink, Error>?
    /// Two attempts to open one failed: the legacy path until the next login (§5.5).
    var anonFailed = false
    /// Tests only: where the anonymous connection dials instead of the relay, to see what
    /// happens when it cannot be opened.
    var anonRelayURLForTest: URL?
    /// An anonymous connection ended with messages in flight: they go again when the next opens.
    var anonLostWork = false
    /// Connections opened in a row because one died with work on it, with no verdict in between.
    var anonReopens = 0
    /// Relay ids of the attachments whose chunks are going out, and what the relay answered
    /// for one of them when it refused a chunk (it drops the whole attachment then).
    var uploading: Set<String> = []
    var uploadRefused: [String: String] = [:]
    /// How long the relay may take to answer the ping written after an attachment's last
    /// chunk, once that ping is written (§5.6). Its answer is what makes the upload complete.
    static var uploadAnswerTimeout: Duration = .seconds(60)
    /// The opening in flight is the engine's own, to send lost work again: its failure settles nothing.
    var anonOpeningForLostWork = false
    /// peer hash -> the bundle lookup in flight after a `denied` (one per peer at a time)
    var bundleRefresh: [String: Task<Void, Never>] = [:]
    /// caller id -> the row its frame is filed under, so a later attempt replaces it there
    var capturedRows: [String: String] = [:]

    init(store: SecureStore, defaults: UserDefaults = .standard,
         runningBuild: Int = UpdateCheck.runningBuild) {
        self.store = store
        self.defaults = defaults
        self.runningBuild = runningBuild
        self.crypto = CryptoEngine(store: store)
        self.contacts = store.blob.contacts
        self.messages = store.blob.messages
        self.notes = store.blob.notes ?? []
        self.blockedHashes = Set(store.blob.blockedHashes ?? [])
        self.groups = store.blob.groups ?? []
        self.notices = NoticeStore.loadNotices(from: defaults)
        self.seenNoticeIds = NoticeStore.loadSeen(from: defaults)
        self.latestVersion = NoticeStore.loadLatestVersion(from: defaults)
        self.updateAvailable = UpdateCheck.updateAvailable(latest: latestVersion, running: runningBuild)
        attachmentCache.totalCostLimit = 64 * 1024 * 1024
        // docs/TEXT-SIZE.md §2: hand the stored multiplier to Theme.mono the
        // moment a store is opened (unlock, restore, register, demo) — that is
        // the only other way settings.textSize can change under us.
        Theme.applyTextSize(store.blob.settings)
        pruneSeenNotices()
        recountUnseenNotices()
        startBurnSweeper()
        crypto.onIdentityReplaced = { [weak self] peer in
            // Invariant: CryptoEngine is only ever driven synchronously from
            // this @MainActor class, so this closure runs on the main actor.
            MainActor.assumeIsolated { self?.handleIdentityChange(for: peer) }
        }
        if !AppSettings.underXCTest {
            networkWatch = NetworkWatch { [weak self] in
                Task { @MainActor in self?.noteNetworkChanged() }
            }
        }
    }

    // MARK: relay connection

    private func openRelay() -> RelayClient {
        let url = URL(string: store.blob.settings.relayURL)!
        sealedRoots = legacyWireOnly ? [] : SealedTrust.roots(forRelay: url)
        let client = RelayClient(url: url, socksProxy: store.blob.settings.socksProxy)
        relay?.disconnect()   // an attempt hanging on a dead network must not linger
        relay = client
        eventTask?.cancel()
        eventTask = Task { [weak self] in
            for await event in client.events {
                // counted as it is reached: everything that arrived before it has been routed
                if case .frame(let f) = event, f.t == "pong" { client.pongRouted() }
                await self?.handle(event)
            }
        }
        client.connect()
        return client
    }

    private func handle(_ event: RelayClient.Event) async {
        switch event {
        case .connected:
            break   // yielded before the socket opens: only a login resets the backoff
        case .disconnected:
            linkLost()
        case .frame(let frame):
            await route(frame)
        case .binary(let data):
            await receiveBlobChunk(data)
        }
    }

    /// The socket is gone, whether the OS reported it or we judged it dead:
    /// fail what waited on it and go back into the retry loop.
    private func linkLost() {
        connState = .offline
        stopKeepalive()
        sealedLinkDown()      // an anonymous connection never outlives the main link
        failAllPending()
        guard wantOnline else { return }
        let delay = min(pow(2.0, Double(reconnectAttempts)), 30)
        reconnectAttempts += 1
        let lost = relay
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            // a newer attempt (retry tap, foreground, network back) replaced the socket
            guard let self, !Task.isCancelled, self.wantOnline, self.relay === lost else { return }
            try? await self.goOnline()
        }
    }

    /// Tests only: hand a relay frame to the receive pipeline as if the
    /// socket had delivered it (the Android `routeForTest` twin).
    func routeForTest(_ f: Wire.In) async { await route(f) }

    private func route(_ f: Wire.In) async {
        switch f.t {
        case "registered", "error" where pendingRegister != nil:
            pendingRegister?.resume(returning: f); pendingRegister = nil
            if f.t == "error", let c = pendingChallenge { c.resume(returning: f); pendingChallenge = nil }
        case "challenge":
            pendingChallenge?.resume(returning: f); pendingChallenge = nil
        case "ready":
            pendingReady?.resume(returning: f); pendingReady = nil
        case "prekey_bundle":
            if let user = f.user, let conts = pendingBundleFetches.removeValue(forKey: user) {
                for cont in conts { cont.resume(returning: f) }
            }
        case "recv":
            await receiveMessage(f)
        case "sent":
            if let id = f.id { relaySent(id) }
        case "cert":
            takeCertificate(f)
        case "prekey_count":
            if let n = f.n, n < 10 { await topUpPreKeys() }
        case "notice":
            ingestNotice(f)
        case "notice_gone":
            if let id = f.id { removeNotice(id: id) }
        case "versions":
            ingestVersions(f)
        case "error":
            lastError = f.code
            // an error naming a user answers that user's bundle fetch
            // (unknown / deregistered account) instead of leaving it waiting
            if let user = f.user, let conts = pendingBundleFetches.removeValue(forKey: user) {
                for cont in conts { cont.resume(returning: f) }
            }
            // (1) an error at the challenge / ready stage must resolve the login
            // wait instead of leaving goOnline() suspended forever
            if let c = pendingChallenge { c.resume(returning: f); pendingChallenge = nil }
            if let c = pendingReady { c.resume(returning: f); pendingReady = nil }
            if let ref = f.ref {
                if uploading.contains(ref) { uploadRefused[ref] = f.code ?? "refused" }     // its upload hears of it too
                relayRefused(code: f.code, ref: ref)
            }
        default:
            break
        }
    }

    /// The relay took a message. `id` is the caller's id for it: the row, the control
    /// or the group envelope. On the legacy path that is the relay id too; on a sealed
    /// lane the relay saw a fresh id, mapped back before this is called (§5.3).
    func relaySent(_ id: String) {
        if let cont = pendingControlAcks.removeValue(forKey: id) {
            cont.resume(returning: true)
        }
        if inflightReceipts.removeValue(forKey: id) != nil { return }  // receipt confirmed
        if let gs = groupSends.removeValue(forKey: id) { noteGroupSent(gs); return }
        markSent(id: id)
    }

    /// The relay refused a message, or a sealed one never got a verdict
    /// (`noVerdict`). `ref` is the caller's id, as in `relaySent`.
    func relayRefused(code: String?, ref: String) {
        if let cont = pendingControlAcks.removeValue(forKey: ref) {
            cont.resume(returning: false)
        } else if inflightReceipts[ref] != nil {
            // transient -> retry later; permanent -> drop
            if code == "rate_limited" || code == "queue_full" || code == Self.noVerdict {
                requeueInflightReceipt(id: ref)
                scheduleReceiptFlush(after: 5)
            } else {
                inflightReceipts.removeValue(forKey: ref)
            }
        } else if let gs = groupSends.removeValue(forKey: ref) {
            noteGroupSendError(gs, code: code)      // one recipient of a fan-out
        } else if code == "rate_limited",
                  !rateLimitRetried.contains(ref),
                  let (thread, msg) = findMessage(id: ref),
                  msg.kind == .text, msg.direction == .outgoing {
            // one silent retry after the limiter window clears
            rateLimitRetried.insert(ref)
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(5))
                await self?.resendText(id: ref, thread: thread)
            }
        } else if code == "unknown_user", let (thread, _) = findMessage(id: ref) {
            markFailed(id: ref)
            noteAccountGone(thread)
        } else {
            markFailed(id: ref)
        }
    }

    /// A prekey bundle, or the relay's refusal naming the account asked for, on
    /// whichever connection the fetch went out.
    func answerBundleFetch(_ f: Wire.In, for user: String) {
        guard let conts = pendingBundleFetches.removeValue(forKey: user) else { return }
        for cont in conts { cont.resume(returning: f) }
    }

    /// The connection a bundle was asked on is gone: its answer will never come.
    func failBundleFetch(for user: String, with error: Error) {
        guard let conts = pendingBundleFetches.removeValue(forKey: user) else { return }
        for cont in conts { cont.resume(throwing: error) }
    }

    /// The peer deleted their account: relay forgot their keys. Leave one
    /// system row so the sender stops staring at endless SENT, and never
    /// duplicate it.
    private var accountGoneNoted: Set<String> = []
    private func noteAccountGone(_ thread: String) {
        guard !accountGoneNoted.contains(thread) else { return }
        accountGoneNoted.insert(thread)
        let name = contacts.first { $0.hash == thread }?.name ?? "this contact"
        let row = ChatMessage(id: UUID().uuidString, direction: .incoming, kind: .system,
                              text: "\u{26A0} <\(name)> deleted their account — messages can no longer be delivered.",
                              timestamp: Date(), status: .delivered, burnAfter: nil, burnStartedAt: nil)
        appendMessage(row, thread: thread)
    }

    /// Account kill from THIS device: tell the relay to forget our keys, drop
    /// our queued messages, and stop waking us. Best-effort — the caller
    /// wipes the local store right after regardless. Only possible while
    /// authenticated (an unlocked, online session); duress/dead-man wipes run
    /// pre-auth with the keys still encrypted and cannot reach here.
    func deregister() async {
        guard connState == .online, let relay else { return }
        try? await relay.send(Wire.Out(t: "deregister"))
        // give the frame time to flush before the socket is torn down
        try? await Task.sleep(for: .milliseconds(300))
    }

    /// A login-stage wait (register / challenge / ready) never outlives 30 s:
    /// a relay that accepts the socket but never answers must not leave
    /// goOnline() suspended forever. The generation check makes sure a
    /// deadline armed for an older wait cannot fire on a newer one.
    private func armAuthDeadline(_ take: @escaping @MainActor (ChatService) -> CheckedContinuation<Wire.In, Error>?) {
        authWaitGeneration &+= 1
        let gen = authWaitGeneration
        let deadline = linkTiming.loginDeadline
        Task { [weak self] in
            try? await Task.sleep(for: deadline)
            guard let self, self.authWaitGeneration == gen else { return }
            if let c = take(self) {
                self.connState = .offline
                c.resume(throwing: ServiceError.relay("timeout"))
            }
        }
    }

    private func failAllPending() {
        let err = URLError(.networkConnectionLost)
        pendingRegister?.resume(throwing: err); pendingRegister = nil
        pendingChallenge?.resume(throwing: err); pendingChallenge = nil
        pendingReady?.resume(throwing: err); pendingReady = nil
        for (_, conts) in pendingBundleFetches { for c in conts { c.resume(throwing: err) } }
        pendingBundleFetches.removeAll()
        for id in Array(inflightReceipts.keys) { requeueInflightReceipt(id: id) }
    }

    // MARK: registration & login

    /// Full signup: generate identity, register with relay, then authenticate.
    func register(username: String) async throws {
        registering = true
        defer { registering = false }
        wantOnline = true
        connState = .connecting
        let bundle = try crypto.generateIdentity(username: username)
        let client = openRelay()
        let delivery = sealedDeliveryOffer()

        let reply: Wire.In = try await withCheckedThrowingContinuation { cont in
            pendingRegister = cont
            armAuthDeadline { s in let c = s.pendingRegister; s.pendingRegister = nil; return c }
            Task {
                try? await client.send(Wire.Out(
                    t: "register",
                    user: self.myHash,
                    auth_pub: bundle.authPub.base64EncodedString(),
                    reg_id: bundle.regId,
                    identity_key: bundle.identityKey.base64EncodedString(),
                    signed_prekey: bundle.signedPreKey,
                    kyber_prekey: bundle.kyberPreKey,
                    one_time_prekeys: bundle.oneTimePreKeys,
                    delivery: delivery))
            }
        }
        guard reply.t == "registered" else {
            wantOnline = false
            connState = .offline
            throw ServiceError.relay(reply.code ?? "register_failed")
        }
        try await authenticateOrRetry(via: client)
    }

    /// Login on an existing identity (app relaunch / reconnect).
    func goOnline() async throws {
        guard !store.blob.usernameHash.isEmpty else { return }
        wantOnline = true
        connState = .connecting
        failAllPending()   // an attempt this one replaces must not wait forever
        let client = openRelay()
        try await authenticateOrRetry(via: client)
    }

    /// A login that got no reply was started on a network that is failing, and
    /// its socket can hang for minutes without reporting anything. Close it and
    /// keep retrying, so the user never has to tap [retry].
    private func authenticateOrRetry(via client: RelayClient) async throws {
        do {
            try await authenticate(via: client)
        } catch {
            if let e = error as? ServiceError, case .relay("timeout") = e, relay === client {
                client.disconnect()
                linkLost()
            }
            throw error
        }
    }

    /// Background wake (push): connect, drain the queue (which sends DELIVERED
    /// receipts), flush, then close the socket while still "wanting" the link
    /// so the next foreground reconnects on its own. Returns true when new
    /// rows arrived. Budget: the ~30 s iOS grants a content-available push.
    func backgroundDrain() async -> Bool {
        guard !store.blob.usernameHash.isEmpty else { return false }
        let before = totalUnread
        wantOnline = false                 // a dead suspended socket must not trigger reconnect loops
        stopKeepalive()
        relay?.disconnect(); connState = .offline
        do { try await goOnline() } catch { wantOnline = true; return false }
        try? await Task.sleep(for: .seconds(6))          // deliveries + receipt flush
        store.flush()
        wantOnline = false
        stopKeepalive()
        sealedLinkDown()       // a receipt still in flight goes back to the saved outbox
        relay?.disconnect(); connState = .offline
        try? await Task.sleep(for: .milliseconds(500))   // let the close callback see wantOnline == false
        wantOnline = true                                // foreground brings the link back (noteForegrounded)
        return totalUnread != before
    }

    func goOffline() {
        wantOnline = false
        reconnectTask?.cancel()
        reconnectTask = nil
        stopKeepalive()
        sealedLinkDown()
        relay?.disconnect()
        connState = .offline
        // MESSAGE-DETAILS.md §3: the TOTAL WIPE path comes through here before
        // obliterate() — no captured envelope may survive it
        frameCapture.clear()
    }

    private func authenticate(via client: RelayClient) async throws {
        connState = .authenticating
        let challenge: Wire.In = try await withCheckedThrowingContinuation { cont in
            pendingChallenge = cont
            armAuthDeadline { s in let c = s.pendingChallenge; s.pendingChallenge = nil; return c }
            Task { try? await client.send(Wire.Out(t: "login", user: self.myHash)) }
        }
        guard challenge.t == "challenge",
              let nonceB64 = challenge.nonce, let nonce = Data(base64Encoded: nonceB64) else {
            connState = .offline
            throw ServiceError.relay(challenge.code ?? "login_failed")
        }
        let sig = try crypto.signChallenge(nonce)
        let token = currentAccessToken()
        let pushToken = store.blob.settings.pushEnabled ? PushDelegate.currentToken : ""
        // a sealed-capable build offers the hash of its delivery key at EVERY login: a login
        // without it tells the relay an old build has the account (§4.1)
        let delivery = sealedDeliveryOffer()
        let ready: Wire.In = try await withCheckedThrowingContinuation { cont in
            pendingReady = cont
            armAuthDeadline { s in let c = s.pendingReady; s.pendingReady = nil; return c }
            Task {
                try? await client.send(Wire.Out(
                    t: "auth",
                    sig: sig.base64EncodedString(),
                    token: token?.token.base64EncodedString(),
                    token_sig: token?.signature.base64EncodedString(),
                    push_token: pushToken,
                    delivery: delivery))
            }
        }
        guard ready.t == "ready" else {
            connState = .offline
            throw ServiceError.relay(ready.code ?? "auth_failed")
        }
        connState = .online
        reconnectAttempts = 0
        sealedLoggedIn(ready)    // my certificate for this login, or sealed sender off
        scheduleReceiptFlush()   // drain anything accumulated offline
        retryRecentFailures()    // auto-resend what failed while unreachable
        startKeepalive()
        // The relay keeps push tokens in RAM only: a relay restart forgets
        // them, and after an app relaunch no token is known in-process until
        // iOS delivers one. Re-register at every login while push is on, so
        // the token reaches the relay again (onToken -> sendPushToken).
        if store.blob.settings.pushEnabled, !AppSettings.underXCTest {
            PushDelegate.requestAndRegister()
        }
    }

    /// Push the APNs token to the relay while online (RAM-only there; an
    /// empty token unregisters). Called when iOS delivers/rotates the token
    /// and when the wake-push toggle changes.
    func sendPushToken(_ token: String?) async {
        guard connState == .online else { return }   // login re-sends it anyway
        let tok = store.blob.settings.pushEnabled ? (token ?? PushDelegate.currentToken ?? "") : ""
        try? await relay?.send(Wire.Out(t: "push_token", push_token: tok))
    }

    /// Call on scene .active: iOS suspends sockets in the background, so the
    /// backoff loop may still be asleep and a dead socket can go undetected.
    /// Skip the wait and force the link back up NOW.
    func noteForegrounded() {
        sweepExpiredNotices()   // the 1 s sweeper was suspended with the app
        guard wantOnline else { return }
        if connState == .online {
            // the suspended socket may be silently dead — a ping either
            // proves it alive or surfaces the failure, which reconnects
            probeLink()
            return
        }
        if connState == .offline {
            reconnectAttempts = 0
            Task { try? await goOnline() }
        }
    }

    /// The phone's network came back or switched. Online, the socket may have
    /// stayed on the old network, so probe it. Otherwise connect now: an
    /// attempt in progress may be hanging on the network that just went away.
    func noteNetworkChanged() {
        guard wantOnline, !registering else { return }
        reconnectAttempts = 0
        closeAnonLinks()      // it may have stayed on the old network; the next message opens another
        if connState == .online {
            probeLink()
        } else {
            Task { try? await goOnline() }
        }
    }

    /// The app left the foreground, where iOS will suspend its sockets. The anonymous
    /// connection takes nothing new from here and closes once what is in flight has its
    /// verdict; the next message opens another (§5.5).
    func noteBackgrounded() {
        for link in anonLinks { anonRetire(link) }
    }

    /// Auto-retry: outgoing rows that FAILED while the relay was unreachable
    /// re-send themselves when the link comes back. Only recent failures
    /// (15 min) retry on their own — older ones stay FAILED so history can't
    /// silently replay after hours offline. Same message id both times, and
    /// the receiver dedupes by id, so a peer can never see a duplicate.
    func retryRecentFailures() {
        let cutoff = Date().addingTimeInterval(-15 * 60)
        for (thread, log) in messages {
            if let g = group(id: thread) {
                guard !g.isLeft else { continue }
                for msg in log where msg.direction == .outgoing
                    && msg.status == .failed && msg.timestamp > cutoff {
                    Task { await self.retryGroupMessage(messageId: msg.id, groupID: thread) }
                }
                continue
            }
            guard let contact = contacts.first(where: { $0.hash == thread }),
                  !blockedHashes.contains(thread) else { continue }
            for msg in log where msg.direction == .outgoing
                && msg.status == .failed && msg.timestamp > cutoff {
                switch msg.kind {
                case .text:
                    Task { await self.resendText(msg, text: msg.text, to: contact) }
                case .voice, .image, .file:
                    Task { await self.retryBlob(messageId: msg.id, thread: thread) }
                default:
                    break
                }
            }
        }
    }

    // MARK: outbound choke point (docs/SEALED-SENDER.md §5.3)

    /// One message, encrypted and addressed, ready to leave: a `send` frame for the
    /// logged-in socket, or a `deliver` frame for the anonymous one.
    struct Outbound {
        let frame: Wire.Out
        let lane: Sealed.Lane
        /// What the caller passed, kept so that a refusal can take the message one
        /// step down: a retry always encrypts and seals again.
        let payload: Wire.Payload
        let peer: String
        let kind: String
        let ttl: Int
        /// The caller's id: the row, the control or the group envelope this is. On the
        /// legacy path it is the relay id too. On a sealed lane the relay sees a fresh
        /// id for every attempt, so that no id ever appears on both sockets.
        let callerId: String
        /// The delivery key a key-lane attempt carried.
        let access: Data?
        /// 1 for the first try; a message makes at most `Sealed.maxAttempts`.
        let attempt: Int
    }

    /// THE one place a payload becomes a relay frame. Synchronous on purpose:
    /// the ratchet step happens here, in the caller's order, and the write is a
    /// separate step (`dispatch`) that the caller awaits or detaches as before.
    /// It picks the path for this peer (§5.2), adds the delivery-key fields, and
    /// seals when the path is not the legacy one.
    func prepare(_ payload: Wire.Payload, to peer: String, kind: String, ttl: Int,
                 id: String) throws -> Outbound {
        try prepare(payload, to: peer, kind: kind, ttl: ttl, id: id, lane: nil, attempt: 1)
    }

    /// `lane` forces a path: the legacy one after a refusal, or the one an
    /// attachment's blob was already encrypted for.
    func prepare(_ payload: Wire.Payload, to peer: String, kind: String, ttl: Int, id: String,
                 lane forced: Sealed.Lane?, attempt: Int) throws -> Outbound {
        var lane = forced ?? sealedLane(for: peer)
        var out = payload
        // `dk` until the peer echoes my key, `dkh` while I hold theirs: on every path,
        // the legacy one included, for as long as this login has sealed sender
        if sealedOwn != nil, let mine = store.blob.deliveryKey {
            let fields = Sealed.outgoingFields(peerKey(peer), myDeliveryKey: mine)
            out.dk = fields.dk
            out.dkh = fields.dkh
        }
        guard lane != .legacy, lane != .fetch, let own = sealedOwn else {
            lane = .legacy
            let body = try crypto.encrypt(out, for: peer)
            return Outbound(frame: Wire.Out(t: "send", id: id, to: peer, ttl: ttl, kind: kind, body: body),
                            lane: lane, payload: payload, peer: peer, kind: kind, ttl: ttl,
                            callerId: id, access: nil, attempt: attempt)
        }
        if lane == .first {
            // typing would spend the recipient's first-contact allowance and crowd out the
            // message itself; an attachment waits until the first text is delivered (§5.2).
            // Both are refused here, before the ratchet moves.
            if out.control == "typing" { throw ServiceError.relay("first_contact_typing") }
            if kind == "blob" { throw ServiceError.relay(Sealed.firstContactRefusal(name: peerName(peer))) }
        }
        // a message that has a row carries its id inside: the id the relay sees is new
        // for every attempt (group messages already carry theirs)
        if out.refId == nil, out.kind == "text" || out.kind == "blob_manifest" { out.refId = id }
        let body = try crypto.seal(out, for: peer, own: own.material)
        if lane == .first, body.count > Sealed.firstContactMaxBody {
            throw ServiceError.relay(Sealed.firstContactTooLong(name: peerName(peer)))
        }
        let access = lane == .key ? peerKey(peer)?.dk : nil
        var frame = Wire.Out(t: "deliver", id: UUID().uuidString.lowercased(), to: peer,
                             ttl: ttl, kind: kind, body: body)
        frame.access = access?.base64EncodedString()
        return Outbound(frame: frame, lane: lane, payload: payload, peer: peer, kind: kind,
                        ttl: ttl, callerId: id, access: access, attempt: attempt)
    }

    /// Attachment bytes for a peer: what goes out as chunks after their manifest. The
    /// blob takes the same path as its manifest, so the caller names it.
    func prepareBlob(_ content: Data, to peer: String, lane: Sealed.Lane) throws -> Data {
        guard lane != .legacy, let own = sealedOwn else {
            return try crypto.encryptBlob(content, for: peer)
        }
        return try crypto.sealBlob(content, for: peer, own: own.material)
    }

    /// THE one place a prepared message is written. On the legacy path it is like
    /// `relay?.send`: with no link it does nothing and does not throw, so a caller
    /// that must fail offline checks the link first; `client` is for a caller that
    /// pinned the socket it started on before suspending. A sealed message goes to
    /// the anonymous connection, opened here on first need. Returns what was
    /// actually written: the legacy re-encryption, when no anonymous connection
    /// could be had.
    @discardableResult
    func dispatch(_ out: Outbound, via client: RelayClient? = nil) async throws -> Outbound {
        guard out.lane != .legacy else {
            try await (client ?? relay)?.send(out.frame)
            return out
        }
        return try await dispatchSealed(out, legacyVia: client)
    }

    /// Manual retry from the FAILED [RETRY] tap on a text row.
    func retryText(messageId: String, thread: String) async {
        guard let contact = contacts.first(where: { $0.hash == thread }),
              let msg = messages[thread]?.first(where: { $0.id == messageId }),
              msg.status == .failed, msg.kind == .text else { return }
        await resendText(msg, text: msg.text, to: contact)
    }

    private func resendText(_ msg: ChatMessage, text: String, to contact: Contact) async {
        setStatus(id: msg.id, to: .pending) { $0 == .failed }
        do {
            if !crypto.hasSession(with: contact.hash) {
                try await fetchAndProcessBundle(for: contact.hash)
            }
            var payload = Wire.Payload(kind: "text", text: text,
                                       senderName: myUsername, burnAfter: msg.burnAfter)
            payload.via = msg.viaWeb == true ? "web" : nil
            let out = try prepare(payload, to: contact.hash, kind: "msg", ttl: 172_800, id: msg.id)
            guard let relay else { throw URLError(.notConnectedToInternet) }   // no socket = FAILED, retried on reconnect
            captureFrame(try await dispatch(out, via: relay), for: msg.id)
        } catch {
            setStatus(id: msg.id, to: .failed) { $0 == .pending }
        }
    }

    /// The relay idle-closes silent connections after 90 s — a ping every
    /// 30 s keeps the link (and any NAT in the path) alive, so the status
    /// stops cycling LINK DOWN → SYNC → LINK UP while you're just reading.
    private func startKeepalive() {
        stopKeepalive()
        let interval = linkTiming.keepalive
        keepaliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                guard let self, !Task.isCancelled, self.connState == .online else { return }
                self.probeLink()
                self.pingAnonLinks()
            }
        }
    }

    private func stopKeepalive() {
        keepaliveTask?.cancel()
        keepaliveTask = nil
        pongTask?.cancel()
        pongTask = nil
    }

    /// One ping with a deadline. A socket on a dead network can report nothing
    /// for minutes while the app says online, so no frame back in time means
    /// the link is gone. The clock starts once the ping is written, so a blob
    /// chunk ahead of it is not mistaken for a dead link.
    private func probeLink() {
        // the ping already out keeps its deadline
        guard pongTask == nil, let client = relay, connState == .online else { return }
        let seen = client.framesReceived
        let timeout = linkTiming.pongTimeout
        pongTask = Task { [weak self] in
            try? await client.send(Wire.Out(t: "ping"))
            try? await Task.sleep(for: timeout)
            guard let self, !Task.isCancelled else { return }
            self.pongTask = nil
            guard self.relay === client, self.connState == .online,
                  client.framesReceived == seen else { return }
            client.disconnect()
            self.linkLost()
        }
    }

    /// Days of paid access remaining (0 = none / dev relay).
    var accessDaysRemaining: Int {
        let today = Int64(Date().timeIntervalSince1970 / 86_400)
        return store.blob.accessTokens.filter { $0.epochDay >= today }.count
    }

    /// Mutate settings from the UI. `SecureStore` is not observable, so a bare
    /// `store.update` leaves every screen showing the old theme/toggles until some
    /// unrelated publish (a link state change, a typing event) happens to re-render
    /// it — the "theme doesn't change until later" bug. Publishing here makes the
    /// change land in the same frame on every view observing this service.
    /// `durability` defaults to `.prompt`, because most of `AppSettings` is
    /// safety configuration — the dead-man switch's days, auto-lock, screenshot
    /// blocking, the relay address — and losing one of those to a crash is
    /// real damage. The four DISPLAY rows pass `.lazily`: §1's "interface
    /// state", where the worst case is a theme that comes back the old colour.
    func updateSettings(_ mutate: (inout AppSettings) -> Void,
                        durability: SecureStore.Durability = .prompt) {
        store.update({ mutate(&$0.settings) }, durability: durability)
        // docs/TEXT-SIZE.md §2: the multiplier Theme.mono reads is refreshed
        // BEFORE the publish, so the redraw this triggers already draws at the
        // new size — no leaving and re-entering the screen.
        Theme.applyTextSize(store.blob.settings)
        objectWillChange.send()
    }

    #if ACCESS_KEY_UI
    func redeemAccessKey(_ code: String) async throws {
        guard let url = URL(string: store.blob.settings.billingURL) else {
            throw ServiceError.relay("bad_billing_url")
        }
        let tokens = try await RedeemService.redeem(
            code: code, billingURL: url,
            session: RelayClient.makeSession(socksProxy: store.blob.settings.socksProxy))
        store.update {
            let today = Int64(Date().timeIntervalSince1970 / 86_400)
            var all = $0.accessTokens.filter { $0.epochDay >= today }
            all.append(contentsOf: tokens)
            $0.accessTokens = all
        }
        objectWillChange.send()
    }
    #endif

    private func currentAccessToken() -> AccessToken? {
        let today = Int64(Date().timeIntervalSince1970 / 86_400)
        return store.blob.accessTokens.first { $0.epochDay == today }
    }

    // MARK: contacts

    func addContact(username: String) async throws {
        let hash = sha256Hex(username.lowercased())
        guard hash != myHash else { throw ServiceError.relay("cannot_add_self") }
        // if they messaged us first, an auto-created placeholder exists —
        // adopt the real username instead of keeping "anon-…"
        if let i = contacts.firstIndex(where: { $0.hash == hash }) {
            contacts[i].name = username
            store.update { $0.contacts = self.contacts }
            return
        }
        // establish the session up front so the first send is instant
        if !crypto.hasSession(with: hash) {
            try await fetchAndProcessBundle(for: hash)
        }
        // a concurrent add of the same user (the first-run pyrobot add and a
        // [+] → pyrobot a second later, or a double-tapped CONNECT) must not
        // leave two rows: the row may have appeared while we awaited
        if contacts.contains(where: { $0.hash == hash }) { return }
        let contact = Contact(hash: hash, name: username, addedAt: Date())
        contacts.append(contact)
        store.update { $0.contacts = self.contacts }
    }

    /// Add via a scanned "pyro-add" QR: adds the contact AND — because the
    /// QR carries the owner's identity key — verifies the safety number in
    /// the same step. Returns true when the key matched (verified badge).
    /// A MISMATCH is a loud failure: someone may sit between you.
    func addContactViaQR(username: String, identityKey: Data) async throws -> Bool {
        // the username lands in a trust-bearing label and is hashed as-is —
        // enforce the registration charset (no format/control characters)
        guard Self.isValidUsername(username) else {
            throw ServiceError.relay("bad_username")
        }
        guard identityKey.count >= 32 else { throw ServiceError.relay("bad_key") }
        let hash = sha256Hex(username.lowercased())
        guard hash != myHash else { throw ServiceError.relay("cannot_add_self") }

        // KNOWN contact with a key on record: a QR is an untrusted input and
        // may only CONFIRM what we hold — it can never demote a verified
        // badge or reset a working session (relay-side key changes are the
        // job of onIdentityReplaced, not of whoever shows us a code)
        if contacts.contains(where: { $0.hash == hash }),
           let known = crypto.knownIdentity(of: hash) {
            if known == identityKey {
                setVerified(true, for: hash)
                return true
            }
            throw ServiceError.relay("qr_key_mismatch")
        }

        let wasKnown = contacts.contains { $0.hash == hash }
        try await addContact(username: username)
        guard let known = crypto.knownIdentity(of: hash) else { return false }
        if known == identityKey {
            setVerified(true, for: hash)
            return true
        }
        // MITM-suspect on a FRESH channel: never leave it messageable. Roll
        // back the contact this scan created and archive the suspect session
        // so a later add refetches from scratch.
        if !wasKnown {
            contacts.removeAll { $0.hash == hash }
            store.update { $0.contacts = self.contacts }
        }
        crypto.archiveSession(with: hash)
        throw ServiceError.relay("identity_mismatch")
    }

    /// Registration rule (mirrors AuthView): letters/digits/underscore, 3–24.
    static func isValidUsername(_ u: String) -> Bool {
        (3...24).contains(u.count)
            && u.allSatisfy { $0.isLetter || $0.isNumber || $0 == "_" }
    }

    /// My add-me QR payload: username + serialized identity public key.
    func myAddQRPayload() -> String? {
        guard let key = try? crypto.myIdentityPublic() else { return nil }
        return "pyro-add:v1:\(myUsername):\(MirrorCrypto.b64url(key))"
    }

    /// Auto-create a contact for a first-contact sender. If the sender
    /// declared a name inside the ciphertext, adopt it (sanitized) — it's a
    /// self-chosen label, like a caller ID; the cryptographic identity is
    /// the hash + safety number, never the name.
    private func ensureContact(for from: String, suggestedName: String? = nil) {
        let clean = Self.sanitizeDisplayName(suggestedName)
        if let i = contacts.firstIndex(where: { $0.hash == from }) {
            // upgrade only auto-generated placeholders — never overwrite a
            // name the user typed or edited themselves
            if let clean, contacts[i].name.hasPrefix("anon-") {
                contacts[i].name = clean
                store.update { $0.contacts = self.contacts }
            }
            return
        }
        let name = clean ?? "anon-" + String(from.prefix(8))
        contacts.append(Contact(hash: from, name: name, addedAt: Date()))
        store.update { $0.contacts = self.contacts }
    }

    /// Same charset the registration screen enforces — a peer cannot smuggle
    /// UI-confusing characters into their label.
    static func sanitizeDisplayName(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let clean = String(raw.filter { $0.isLetter || $0.isNumber || $0 == "_" }.prefix(24))
        return clean.count >= 3 ? clean : nil
    }

    // MARK: safety — block & report (Apple guideline 1.2)

    func isBlocked(_ hash: String) -> Bool { blockedHashes.contains(hash) }

    /// Block: every future frame from this hash is silently dropped (acked so
    /// the relay wipes its copy — nothing accumulates anywhere). History is
    /// kept; the thread is hidden from the session list until unblocked.
    func blockContact(_ hash: String) {
        blockedHashes.insert(hash)
        store.update { $0.blockedHashes = Array(self.blockedHashes) }
    }

    func unblockContact(_ hash: String) {
        blockedHashes.remove(hash)
        store.update { $0.blockedHashes = Array(self.blockedHashes) }
    }

    // MARK: desktop link

    @discardableResult
    func startMirror(_ link: MirrorLink) throws -> MirrorEngine {
        mirror?.stop(reason: "replaced")
        let engine = try MirrorEngine(service: self, link: link)
        mirror = engine
        engine.start()
        return engine
    }

    func stopMirror() {
        mirror?.stop(reason: "unlink")
        mirror = nil
    }

    // MARK: safety numbers / verification

    struct SafetyNumber {
        let digits: String     // 60 decimal digits — identical on both devices
        let scannable: Data    // libsignal scannable encoding; differs per side by design
    }

    /// nil until the peer's identity key is on record (first bundle fetch or
    /// first received message).
    func safetyNumber(with contact: Contact) -> SafetyNumber? {
        guard let fp = try? crypto.fingerprint(with: contact.hash) else { return nil }
        return SafetyNumber(digits: fp.displayable.formatted,
                            scannable: fp.scannable.encoding)
    }

    func setVerified(_ verified: Bool, for hash: String) {
        guard let i = contacts.firstIndex(where: { $0.hash == hash }),
              contacts[i].verified != verified else { return }
        contacts[i].verified = verified
        store.update { $0.contacts = self.contacts }
    }

    // MARK: identity-change warning

    private func handleIdentityChange(for peer: String) {
        // verified clears ALWAYS (idempotent)…
        if let i = contacts.firstIndex(where: { $0.hash == peer }), contacts[i].verified {
            contacts[i].verified = false
            store.update { $0.contacts = self.contacts }
        }
        // …the loud row is deduped: one per peer per 60 s (a reinstall storm
        // — failed decrypts + heal + refetch — collapses to one warning)
        let now = Date()
        if let t = identityRowAt[peer], now.timeIntervalSince(t) < Self.healThrottle { return }
        identityRowAt[peer] = now
        ensureContact(for: peer)
        let name = contacts.first { $0.hash == peer }?.name ?? "peer"
        appendSystem("\u{26A0} <\(name)>'s identity key changed — re-verify before trusting",
                     thread: peer)
    }

    func appendSystem(_ text: String, thread: String) {
        let row = ChatMessage(id: UUID().uuidString, direction: .incoming, kind: .system,
                              text: text, timestamp: Date(), status: .delivered,
                              burnAfter: nil, burnStartedAt: nil)
        appendMessage(row, thread: thread)
    }

    /// Report abuse + block + burn the local thread. E2E-honest: the ONLY
    /// thing sent to the operator is the reported routing hash — no message
    /// content exists server-side to attach, and the reporter's identity is
    /// not recorded with the report.
    func reportAndBlock(_ contact: Contact) async {
        try? await relay?.send(Wire.Out(t: "report", user: contact.hash))
        purgeAttachments(of: messages[contact.hash] ?? [])
        messages[contact.hash] = []
        store.update { $0.messages[contact.hash] = [] }
        blockContact(contact.hash)
    }

    func renameContact(_ hash: String, to name: String) {
        guard let i = contacts.firstIndex(where: { $0.hash == hash }),
              !name.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        contacts[i].name = name.trimmingCharacters(in: .whitespaces)
        store.update { $0.contacts = self.contacts }
    }

    /// Remove a contact and everything local about the conversation
    /// (messages, attachments, draft). The Signal session is kept so a
    /// future re-add resumes cleanly.
    func deleteContact(_ hash: String) {
        purgeAttachments(of: messages[hash] ?? [])
        messages[hash] = nil
        contacts.removeAll { $0.hash == hash }
        store.update {
            $0.messages[hash] = nil
            $0.contacts = self.contacts
            $0.drafts?[hash] = nil
        }
    }

    /// Delete one of MY messages on BOTH devices: local removal plus an
    /// E2E control telling the peer's app to drop it too. Only outgoing
    /// messages qualify — nobody can reach into a peer's phone for
    /// messages the peer wrote.
    /// Returns false (and touches nothing) when the retraction cannot be
    /// delivered right now — a "delete for both" that only deletes for me
    /// would be a lie the UI must not tell.
    @discardableResult
    func deleteForBoth(id: String, thread: String) async -> Bool {
        guard let msg = messages[thread]?.first(where: { $0.id == id }),
              msg.direction == .outgoing else { return false }
        guard connState == .online, let relay else {
            lastError = "offline — delete for both needs the link up"
            return false
        }
        do {
            if !crypto.hasSession(with: thread) {
                try await fetchAndProcessBundle(for: thread)
            }
            var payload = Wire.Payload(kind: "control", control: "delete_msg")
            payload.refIds = [id]
            let controlId = UUID().uuidString.lowercased()
            let out = try prepare(payload, to: thread, kind: "control", ttl: 172_800, id: controlId)
            // a socket write is not delivery: wait for the relay's `sent`
            // (queued for the peer) or `error` (rate_limited / queue_full /
            // unknown_user) before touching the local row
            let accepted: Bool = await withCheckedContinuation { cont in
                pendingControlAcks[controlId] = cont
                Task { [weak self] in
                    do {
                        try await self?.dispatch(out, via: relay)
                    } catch {
                        self?.pendingControlAcks.removeValue(forKey: controlId)?
                            .resume(returning: false)
                        return
                    }
                    try? await Task.sleep(for: .seconds(8))
                    self?.pendingControlAcks.removeValue(forKey: controlId)?
                        .resume(returning: false)       // no verdict = not delivered
                }
            }
            guard accepted else {
                lastError = "delete for both refused by relay — try again"
                return false
            }
        } catch {
            lastError = "delete for both failed — try again"
            return false
        }
        deleteMessage(id: id, thread: thread)
        return true
    }

    /// Delete ONE message locally ("delete for me").
    func deleteMessage(id: String, thread: String) {
        guard var log = messages[thread] else { return }
        if let victim = log.first(where: { $0.id == id }) {
            purgeAttachments(of: [victim])
        }
        log.removeAll { $0.id == id }
        messages[thread] = log
        let snapshot = messages
        store.update { $0.messages = snapshot }
    }

    // MARK: drafts

    func draft(for thread: String) -> String {
        store.blob.drafts?[thread] ?? ""
    }

    /// docs/DECOY-ACCOUNT.md §1: **a keystroke must never reach the store.**
    ///
    /// The composer calls this on every character, which is right — the draft
    /// has to be in the blob the moment the screen could go away. What is
    /// wrong is writing it there. `.lazily` puts it in memory and schedules
    /// nothing; `flushDeferredWrites()` below is the composer losing focus and the
    /// screen being left, `store.flush()` is the app backgrounding, and
    /// `SecureStore.idleFlushInterval` is the backstop. Before this, one typed
    /// sentence cost 19 saves and 38 MiB for a message that was never sent.
    func setDraft(_ text: String, for thread: String) {
        store.update({
            var d = $0.drafts ?? [:]
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                d[thread] = nil
            } else {
                d[thread] = text
            }
            $0.drafts = d.isEmpty ? nil : d
        }, durability: .lazily)
    }

    /// docs/DECOY-ACCOUNT.md §1's flush points, other than backgrounding: the
    /// composer losing focus, and a screen that held lazy state being left.
    /// A no-op when nothing is held, so it is safe to call liberally.
    func flushDeferredWrites() { store.flushDeferred() }

    // MARK: typing indicators (E2E control — the relay sees only "control")

    /// Called on every composer keystroke; throttled to one signal per 4 s.
    func noteComposerActivity(in thread: String) {
        if let g = group(id: thread) { noteGroupComposerActivity(g); return }
        guard store.blob.settings.typingOn,
              connState == .online,
              crypto.hasSession(with: thread),
              !blockedHashes.contains(thread) else { return }
        let now = Date()
        if let last = lastTypingSent[thread], now.timeIntervalSince(last) < 4 { return }
        lastTypingSent[thread] = now
        Task {
            if let out = try? prepare(
                Wire.Payload(kind: "control", control: "typing"), to: thread,
                kind: "control", ttl: 60, id: UUID().uuidString.lowercased()) {
                _ = try? await dispatch(out)
            }
        }
    }

    /// Incoming rows not yet read, for the HomeView [N] chip.
    func unreadCount(for hash: String) -> Int {
        (messages[hash] ?? []).filter {
            $0.direction == .incoming && $0.kind != .system && $0.status != .read
        }.count
    }

    /// Springboard badge source: unread across every conversation
    /// (blocked threads don't count — they're hidden).
    var totalUnread: Int {
        messages.keys.filter { !blockedHashes.contains($0) }
            .reduce(0) { $0 + unreadCount(for: $1) }
    }

    func fetchAndProcessBundle(for hash: String) async throws {
        let bundle = try await fetchBundle(for: hash)
        guard bundle.t == "prekey_bundle" else {
            throw ServiceError.relay(bundle.code ?? "unknown_user")
        }
        // ONE bootstrap per fetch: every caller checked `!hasSession` before
        // awaiting, so a session that exists now was built by a waiter ahead
        // of us on this very reply (or by the peer's own PreKey message).
        // Processing the same bundle twice would mint a second session from
        // the same one-time prekey — the peer decrypts the first envelope,
        // deletes the prekey, and the second (a text!) is lost to a heal
        // storm. The desktop link's `web_link` + the text it announces hit
        // this back-to-back on any peer without a current session.
        if crypto.hasSession(with: hash) { return }
        try crypto.processBundle(bundle, for: hash)
    }

    /// One prekey bundle, or the relay's refusal. Whenever this login has sealed
    /// sender the question goes out on the anonymous connection, so the relay is
    /// not told who is about to write to whom (docs/SEALED-SENDER.md §5.2); the
    /// answer then also says whether that account can receive sealed messages.
    func fetchBundle(for hash: String) async throws -> Wire.In {
        guard let main = relay else { throw URLError(.notConnectedToInternet) }
        var socket = main
        var asked: AnonLink?
        if sealedOwn != nil, !anonFailed, let link = try? await ensureAnon() {
            socket = link.client
            asked = link
        }
        return try await withCheckedThrowingContinuation { cont in
            let first = pendingBundleFetches[hash] == nil
            pendingBundleFetches[hash, default: []].append(cont)
            guard first else { return }          // piggyback on the fetch in flight
            // the answer is owed on this connection: it stays open for it, even while retiring
            asked?.fetches.insert(hash)
            bundleFetchCount += 1
            let serial = bundleFetchCount
            bundleFetchSerial[hash] = serial
            Task { try? await socket.send(Wire.Out(t: "prekey_fetch", user: hash)) }
            // hard deadline: a lost reply must never hang an add forever
            Task { [weak self, weak asked] in
                try? await Task.sleep(for: Self.bundleFetchTimeout)
                // only the fetch this deadline was armed for. An answered fetch followed by a
                // new one for the same peer used to be cut short by the first one's deadline.
                guard let self, self.bundleFetchSerial[hash] == serial else { return }
                self.bundleFetchSerial[hash] = nil
                if let conts = self.pendingBundleFetches.removeValue(forKey: hash) {
                    for c in conts { c.resume(throwing: ServiceError.relay("timeout")) }
                }
                if let asked, asked.fetches.remove(hash) != nil { self.anonSettle(asked) }
            }
        }
    }

    // MARK: text messaging

    /// Self-destruct default for a thread: per-contact override, else global.
    func burnDefault(for hash: String) -> TimeInterval? {
        if let c = contacts.first(where: { $0.hash == hash }), let s = c.burnSeconds {
            return s == 0 ? nil : s      // 0 = explicit "off" override
        }
        return store.blob.settings.defaultBurnSeconds
    }

    func setBurnDefault(_ seconds: TimeInterval?, for hash: String) {
        guard let i = contacts.firstIndex(where: { $0.hash == hash }) else { return }
        contacts[i].burnSeconds = seconds
        store.update { $0.contacts = self.contacts }
    }

    func sendText(_ text: String, to contact: Contact, viaWeb: Bool = false) async {
        let id = UUID().uuidString.lowercased()
        let burn = burnDefault(for: contact.hash)
        // SYNCED BURN: burnStartedAt stays nil until the peer's burn_start
        // control arrives — both devices then count down together.
        var msg = ChatMessage(id: id, direction: .outgoing, kind: .text, text: text,
                              timestamp: Date(), status: .pending,
                              burnAfter: burn, burnStartedAt: nil)
        msg.viaWeb = viaWeb ? true : nil
        appendMessage(msg, thread: contact.hash)
        SoundPlayer.play(.tx, settings: store.blob.settings)
        do {
            if !crypto.hasSession(with: contact.hash) {
                try await fetchAndProcessBundle(for: contact.hash)
            }
            var payload = Wire.Payload(kind: "text", text: text,
                                       senderName: myUsername, burnAfter: burn)
            payload.via = viaWeb ? "web" : nil
            let out = try prepare(payload, to: contact.hash, kind: "msg", ttl: 172_800, id: id)
            guard let relay else { throw URLError(.notConnectedToInternet) }
            captureFrame(try await dispatch(out, via: relay), for: id)
        } catch {
            msg.status = .failed
            replaceMessage(msg, thread: contact.hash)
            lastError = (error as? ServiceError)?.description ?? error.localizedDescription
        }
    }

    // MARK: receive pipeline

    private func receiveMessage(_ f: Wire.In) async {
        guard let id = f.id, let body = f.body else { return }
        // WHO IT IS FROM. Today's frame says so, and the relay vouches for it. A
        // sealed frame has no `from`: the sender is inside, behind checks C0–C6
        // (docs/SEALED-SENDER.md §3). Until those pass there is nobody to answer and
        // nothing to heal, so a failure is acked and dropped in silence. A frame
        // without `from` must carry type 6, and a frame with `from` must not.
        let type = crypto.envelopeType(of: body)
        let from: String
        var opened: CryptoEngine.Opened?
        if let named = f.from {
            guard type != Sealed.envelopeType else {
                try? await relay?.send(Wire.Out(t: "ack", id: id))
                return
            }
            from = named
        } else {
            guard type == Sealed.envelopeType, let ts = f.ts, ts > 0,
                  let o = try? crypto.openSealed(body: body, ts: UInt64(ts), roots: sealedRoots,
                                                 minKeyId: SealedTrust.minServerKeyId) else {
                try? await relay?.send(Wire.Out(t: "ack", id: id))
                return
            }
            from = o.sender
            opened = o
        }
        // blocked sender: ack (relay wipes) and drop without decrypting —
        // skipped ciphertexts are safe, the ratchet fast-forwards on unblock.
        // For a sealed frame this is before C7 and before the ratchet runs.
        if blockedHashes.contains(from) {
            try? await relay?.send(Wire.Out(t: "ack", id: id))
            return
        }
        // dedupe (relay redelivers until acked)
        if messages[from]?.contains(where: { $0.id == id }) == true {
            try? await relay?.send(Wire.Out(t: "ack", id: id))
            return
        }
        do {
            let payload = try opened.map { try crypto.decryptSealed($0) }
                ?? crypto.decrypt(body, from: from)
            learnKeys(from: from, payload: payload, sealed: opened != nil)
            // THE ROW THIS BECOMES. On the legacy path a 1:1 row's id is the relay id.
            // On a sealed lane the relay id is new for every attempt and the row's id
            // rides inside as `refId` (§5.3) — so a retry of something already here is
            // recognised only now, after decrypting.
            let rowId = opened != nil ? (payload.refId ?? id) : id
            if opened != nil, payload.group?.id == nil,
               payload.kind == "text" || payload.kind == "blob_manifest",
               messages[from]?.contains(where: { $0.id == rowId }) == true {
                try? await relay?.send(Wire.Out(t: "ack", id: id))
                return
            }
            // blob manifests are acked by assembleBlob() once every chunk has
            // arrived — acking now would tear down the relay's chunk route
            if payload.kind != "blob_manifest" {
                try? await relay?.send(Wire.Out(t: "ack", id: id))
            }
            // docs/MESSAGE-DETAILS.md §3: keep the frame as it arrived, filed
            // under the row it becomes. Controls never surface as rows, so
            // nothing about a receipt or a roster change is kept.
            if payload.kind == "text" || payload.kind == "blob_manifest" {
                frameCapture.record(
                    .received(id: id, from: opened == nil ? from : nil,
                              kind: f.kind ?? (payload.kind == "text" ? "msg" : "blob"),
                              body: body),
                    for: payload.group?.id == nil ? rowId : (payload.refId ?? id))
            }

            switch payload.kind {
            case "text":
                if let gid = payload.group?.id {
                    receiveGroupText(payload, groupID: gid, from: from, relayId: id)
                    break
                }
                typingPeers[from] = nil
                ensureContact(for: from, suggestedName: payload.senderName)
                var msg = ChatMessage(id: rowId, direction: .incoming, kind: .text,
                                      text: payload.text ?? "", timestamp: Date(),
                                      status: .delivered, burnAfter: payload.burnAfter,
                                      burnStartedAt: nil)   // synced burn: starts on read
                msg.viaWeb = payload.via == "web" ? true : nil
                appendMessage(msg, thread: from)
                enqueueReceipt(.delivered, ids: [rowId], to: from)
                SoundPlayer.play(.rx, settings: store.blob.settings)
            case "blob_manifest":
                guard let chunks = payload.chunks, (1...Self.maxBlobChunks).contains(chunks),
                      let bytes = payload.bytes, bytes <= Self.maxBlobCiphertextBytes,
                      let sub = payload.subtype, ["voice", "image", "file"].contains(sub) else {
                    try? await relay?.send(Wire.Out(t: "ack", id: id))  // junk: wipe
                    return
                }
                // The sender is trying again: a new relay id for the same row (a group copy
                // always, a sealed 1:1 one too). The attempt it replaces will never finish.
                // Let it go here and at the relay, or it would sit there "receiving" for two days.
                if let row = payload.refId, payload.group != nil || opened != nil {
                    for (old, entry) in blobIncoming
                    where old != id && entry.from == from && entry.refId == row && entry.group == payload.group?.id {
                        blobIncoming[old] = nil
                        rxTransfers[old] = nil
                        try? await relay?.send(Wire.Out(t: "ack", id: old))
                    }
                }
                // a re-delivered manifest intentionally resets a partial entry
                // (the relay replays manifest + all chunks on reconnect)
                blobIncoming[id] = IncomingBlob(
                    from: from, senderName: payload.senderName,
                    total: chunks, chunks: [:], subtype: sub,
                    burnAfter: payload.burnAfter, duration: payload.duration,
                    filename: payload.filename, mime: payload.mime,
                    width: payload.width, height: payload.height,
                    bytes: bytes, startedAt: Date(),
                    refId: payload.refId, group: payload.group?.id,
                    sealedTs: opened != nil ? f.ts : nil)
                rxTransfers[id] = RxTransfer(thread: payload.group?.id ?? from, subtype: sub,
                                             received: 0, total: chunks)
            case "control":
                if let gref = payload.group, let gid = gref.id {
                    handleGroupControl(payload, groupID: gid, epoch: gref.epoch ?? 0,
                                       from: from, relayTs: f.ts)
                    break
                }
                handleControl(payload, from: from, relayTs: f.ts)
            default:
                break   // unknown kind from a newer client: ignore
            }
        } catch let rejected as Sealed.Rejected where !rejected.heal {
            // C7 with a PreKey message inside: someone else's message in this
            // sender's envelope. Nothing was decrypted and there is nothing to heal.
            try? await relay?.send(Wire.Out(t: "ack", id: id))
        } catch let error as SignalError {
            // a redelivered control (lost ack) re-decrypts as a DUPLICATE —
            // that is a healthy ratchet doing its job, never heal on it. A
            // redelivered blob MANIFEST whose chunks are still arriving
            // (socket drop mid-transfer: the relay replays manifest + chunks
            // on reconnect) must NOT be acked — the relay drops the chunk
            // route on ack, chunks and all. assembleBlob acks it once the
            // last chunk lands.
            if case .duplicatedMessage = error {
                if blobIncoming[id] == nil {
                    try? await relay?.send(Wire.Out(t: "ack", id: id))
                }
                return
            }
            // undecryptable — never store ciphertext we can't read; ack so
            // the relay wipes it
            try? await relay?.send(Wire.Out(t: "ack", id: id))
            lastError = "decrypt_failed"
            noteDecryptFailure(from: from)
        } catch {
            // includes a sealed Whisper whose sender's key is not the one on
            // record (C7): from here the sender is certified, so it heals as a
            // failed decrypt always has
            try? await relay?.send(Wire.Out(t: "ack", id: id))
            lastError = "decrypt_failed"
            noteDecryptFailure(from: from)
        }
    }

    func handleControl(_ payload: Wire.Payload, from: String, relayTs: Int64? = nil) {
        switch payload.control {
        case "receipt":
            guard let kindStr = payload.receipt,
                  let kind = ReceiptKind(rawValue: kindStr),
                  kind != .burnStart,
                  let refIds = payload.refIds else { break }
            applyReceipt(kind, refIds: refIds, from: from)
            applyGroupReceipt(kind, refIds: refIds, from: from, groupID: nil)
        case "burn_start":
            guard let refIds = payload.refIds else { break }
            applyBurnStart(refIds: refIds, from: from)
            applyGroupBurnStart(refIds: refIds, from: from, groupID: nil)
        case "session_reset":
            Task { [weak self] in await self?.adoptSessionReset(from: from) }
        case "typing":
            guard contacts.contains(where: { $0.hash == from }) else { break }
            // a typing signal is only meaningful live; one replayed from the
            // relay queue after a reconnect must not show a phantom "typing…"
            if let ts = relayTs, Date().timeIntervalSince1970 * 1000 - Double(ts) > 10_000 { break }
            typingPeers[from] = Date()
        case "burn_thread":
            purgeAttachments(of: messages[from] ?? [])
            // the trace note only for peers we know — a stranger's control
            // must never conjure a phantom thread
            if contacts.contains(where: { $0.hash == from }) {
                let trace = ChatMessage(id: UUID().uuidString, direction: .incoming,
                                        kind: .system, text: "\u{1F525} thread burned by peer",
                                        timestamp: Date(), status: .delivered,
                                        burnAfter: nil, burnStartedAt: nil)
                messages[from] = [trace]
                store.update { $0.messages[from] = [trace] }
            } else {
                messages[from] = []
                store.update { $0.messages[from] = [] }
            }
        case "screenshot", "screen_record":
            // an alert from a stranger we share no thread with is meaningless
            guard contacts.contains(where: { $0.hash == from }) else { break }
            let text = payload.control == "screenshot"
                ? "\u{26A0} peer took a screenshot"
                : "\u{26A0} peer may be recording the screen"
            let note = ChatMessage(id: UUID().uuidString, direction: .incoming, kind: .system,
                                   text: text, timestamp: Date(),
                                   status: .delivered, burnAfter: nil, burnStartedAt: nil)
            if store.blob.settings.burnOnScreenshot {
                purgeAttachments(of: messages[from] ?? [])
                messages[from] = [note]
                store.update { $0.messages[from] = [note] }
                // burn the OTHER side too — the screenshotter's copy wipes
                // as well, not just the victim's
                if let out = try? prepare(
                    Wire.Payload(kind: "control", control: "burn_thread"), to: from,
                    kind: "control", ttl: 86_400, id: UUID().uuidString.lowercased()) {
                    Task { [weak self] in try? await self?.dispatch(out) }
                }
            } else {
                appendMessage(note, thread: from)
            }
        case "delete_msg":
            guard let refIds = payload.refIds, refIds.count <= 50,
                  var log = messages[from] else { break }
            // AUTH BOUNDARY: the sender may retract only messages THEY
            // authored — incoming rows in this thread. My own messages are
            // untouchable by any remote control.
            let victims = log.filter { refIds.contains($0.id) && $0.direction == .incoming }
            guard !victims.isEmpty else { break }
            purgeAttachments(of: victims)
            let ids = Set(victims.map(\.id))
            log.removeAll { ids.contains($0.id) }
            messages[from] = log
            let snapshot = messages
            store.update { $0.messages = snapshot }
        case "web_link":
            // without `group` (MIRROR-GROUPS.md §5): the 1:1 thread, as
            // always — a group's `web_link` is routed by handleGroupControl
            guard contacts.contains(where: { $0.hash == from }) else { break }
            let browser = payload.browser.flatMap(MirrorEngine.sanitizeBrowserName)
            let text = payload.linkUp == true
                ? "\u{26A0} peer linked a desktop browser"
                    + (browser.map { " (\($0))" } ?? "")
                    + " — this conversation may be viewed there"
                : "peer unlinked the desktop browser"
            let note = ChatMessage(id: UUID().uuidString, direction: .incoming,
                                   kind: .system, text: text, timestamp: Date(),
                                   status: .delivered, burnAfter: nil, burnStartedAt: nil)
            appendMessage(note, thread: from)
        default:
            break
        }
    }

    // MARK: session auto-heal

    private func noteDecryptFailure(from peer: String) {
        decryptFailures[peer, default: 0] += 1
        // archive IMMEDIATELY (independent of the online guard below): with
        // the session archived, hasSession is false, so even if the heal
        // task bails the next outbound send self-heals via a bundle refetch
        crypto.archiveSession(with: peer)
        // DEADLOCK GUARD: healSession awaits a prekey_bundle frame resolved
        // by the same relay event loop that called us — heal in its own
        // MainActor task, never inline.
        Task { [weak self] in await self?.healSession(with: peer) }
    }

    /// Bootstrap a fresh outbound session from a new PQXDH bundle and tell
    /// the peer to do the same. Throttled to one attempt per peer per 60 s —
    /// a reconnect replaying N dead ciphertexts collapses into one heal.
    private func healSession(with peer: String) async {
        guard connState == .online else { return }  // archived already; next send refetches
        let now = Date()
        if let last = lastHealAt[peer], now.timeIntervalSince(last) < Self.healThrottle { return }
        lastHealAt[peer] = now                      // set BEFORE first await
        let lost = max(decryptFailures[peer] ?? 0, 1)
        decryptFailures[peer] = 0
        do {
            try await fetchAndProcessBundle(for: peer)  // may fire onIdentityReplaced
        } catch {
            lastError = "heal_failed"
            return   // session stays archived => every send path refetches
        }
        // cosmetics + the reset control only for peers we actually know —
        // a stranger's garbage frame must not manufacture a contact or an
        // alarming "messages lost" row
        guard contacts.contains(where: { $0.hash == peer }) else { return }
        if let out = try? prepare(
            Wire.Payload(kind: "control", control: "session_reset"), to: peer,
            kind: "control", ttl: 86_400, id: UUID().uuidString.lowercased()) {
            _ = try? await dispatch(out)
        }
        let name = contacts.first { $0.hash == peer }?.name ?? "peer"
        appendSystem("\u{26A0} channel re-keyed — \(lost) incoming message\(lost == 1 ? " was" : "s were") lost and cannot be recovered. ask <\(name)> to resend.",
                     thread: peer)
    }

    /// Peer told us they rebuilt the channel. Our receiving side is already
    /// fixed (their control arrived as a preKey message); we also archive +
    /// refetch so OUR outbound direction rides a brand-new handshake.
    /// LOOP GUARD: never sends session_reset back, and shares the 60 s stamp
    /// with healSession — simultaneous mutual resets converge in one round.
    private func adoptSessionReset(from peer: String) async {
        let now = Date()
        if let last = lastHealAt[peer], now.timeIntervalSince(last) < Self.healThrottle { return }
        lastHealAt[peer] = now
        crypto.archiveSession(with: peer)
        try? await fetchAndProcessBundle(for: peer)  // best-effort
        guard contacts.contains(where: { $0.hash == peer }) else { return }
        let name = contacts.first { $0.hash == peer }?.name ?? "peer"
        appendSystem("\u{26A0} <\(name)> re-keyed the channel — messages you sent recently may not have arrived. resend anything unanswered.",
                     thread: peer)
    }

    /// AUTO-LOCK teardown: after this returns the instance owns no socket,
    /// no run-loop timer, and no live task — dropping the last reference
    /// deinits it (and with it SecureStore's key material). Idempotent.
    /// The relay sees only a normal socket close — it learns nothing.
    func shutdown() {
        stopMirror()   // auto-lock / wipe must never leave a live browser mirror
        wantOnline = false
        networkWatch?.stop()
        networkWatch = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        stopKeepalive()
        // stop consuming relay events FIRST (releases the retained RelayClient)
        eventTask?.cancel()
        eventTask = nil
        relay?.disconnect()
        relay = nil
        connState = .offline
        sealedLinkDown()       // the anonymous connection closes on lock, like the main one
        // explicit disconnect never yields .disconnected — resolve
        // continuations + requeue in-flight receipts into the PERSISTED
        // outbox ourselves
        failAllPending()
        receiptFlushTask?.cancel()
        receiptFlushTask = nil
        // the RunLoop retains live repeating timers forever — without
        // invalidate() every lock/unlock cycle leaks one
        burnTimer?.invalidate()
        burnTimer = nil
        // a still-.pending outgoing row never got its relay ack — surface as
        // failed (receiptRank ranks failed with sent, so a receipt arriving
        // next session still upgrades it)
        var snapshotNeeded = false
        for (thread, log) in messages {
            var l = log
            var dirty = false
            for i in l.indices
            where l[i].direction == .outgoing && l[i].status == .pending {
                l[i].status = .failed
                dirty = true
            }
            if dirty { messages[thread] = l; snapshotNeeded = true }
        }
        if snapshotNeeded {
            let snapshot = messages
            store.update { $0.messages = snapshot }
        }
        blobProgress = [:]
        rxTransfers = [:]
        blobIncoming = [:]
        typingPeers = [:]
        lastTypingSent = [:]
        groupTyping = [:]
        lastGroupTypingSent = [:]
        groupSends = [:]
        groupOutbound = [:]
        inflightReceipts = [:]                 // all requeued by failAllPending
        attachmentCache.removeAllObjects()     // decrypted bytes out of RAM
        frameCapture.clear()                   // MESSAGE-DETAILS.md §3: RAM only
        store.flush()
    }

    // MARK: receipts + burn sync

    /// Apply an incoming receipt. Containment: only the sender's own thread,
    /// only OUTGOING non-system rows, upgrades only (rank-monotone), unknown
    /// ids ignored (burned messages -> no-op). Idempotent.
    private func applyReceipt(_ kind: ReceiptKind, refIds: [String], from peer: String) {
        guard var log = messages[peer] else { return }
        let target: ChatMessage.Status = (kind == .delivered) ? .delivered : .read
        var changed = false
        let idSet = Set(refIds)
        for i in log.indices {
            let m = log[i]
            guard idSet.contains(m.id), m.direction == .outgoing, m.kind != .system,
                  target.receiptRank > m.status.receiptRank else { continue }
            log[i].status = target
            blobProgress[m.id] = nil
            changed = true
        }
        guard changed else { return }
        messages[peer] = log
        let snapshot = messages
        store.update { $0.messages = snapshot }
    }

    /// SYNCED BURN, sender side: the reader's device announced (always-on,
    /// independent of the read-receipt toggle) that these messages became
    /// visible — start our copies' countdowns now so both burn together.
    private func applyBurnStart(refIds: [String], from peer: String) {
        guard var log = messages[peer] else { return }
        var changed = false
        let idSet = Set(refIds)
        for i in log.indices {
            let m = log[i]
            guard idSet.contains(m.id), m.direction == .outgoing,
                  m.burnAfter != nil, m.burnStartedAt == nil else { continue }
            log[i].burnStartedAt = Date()
            changed = true
        }
        guard changed else { return }
        messages[peer] = log
        let snapshot = messages
        store.update { $0.messages = snapshot }
    }

    /// True while a row should carry a visibility reporter.
    func needsVisibilityReport(_ msg: ChatMessage) -> Bool {
        guard msg.direction == .incoming, msg.kind != .system,
              !visibleReported.contains(msg.id) else { return false }
        let unread = msg.status != .read
        let burnPending = msg.burnAfter != nil && msg.burnStartedAt == nil
        return unread || burnPending
    }

    /// THE read trigger: called by TerminalLogView after the row has been
    /// continuously on-screen >= 0.5 s with the chat open and the app active.
    /// Marks the row read locally, starts its burn countdown, and enqueues
    /// the E2E controls (read receipt only if the reciprocity toggle is on;
    /// burn_start always).
    func noteRowVisible(messageID: String, thread: String) {
        guard !visibleReported.contains(messageID) else { return }
        guard var log = messages[thread],
              let i = log.firstIndex(where: { $0.id == messageID && $0.direction == .incoming })
        else { return }
        visibleReported.insert(messageID)
        // a group row's receipts go to whoever wrote it (thread key = group id)
        let peer = log[i].sender ?? thread
        var changed = false
        if log[i].status != .read {
            log[i].status = .read
            changed = true
            if store.blob.settings.readReceiptsOn {
                enqueueReceipt(.read, ids: [messageID], to: peer)
            }
        }
        if log[i].burnAfter != nil && log[i].burnStartedAt == nil {
            log[i].burnStartedAt = Date()
            changed = true
            enqueueReceipt(.burnStart, ids: [messageID], to: peer)
        }
        guard changed else { return }
        messages[thread] = log
        let snapshot = messages
        // §1: READ STATE, written lazily. Opening a thread with forty unread
        // rows used to cost forty saves — 80 MiB to mark messages seen. What
        // is at risk if this is lost to a crash is the read mark and the burn
        // countdown, and both are re-derived the moment the row is on screen
        // again (`visibleReported` is session-scoped on purpose), so the loss
        // heals itself. The RECEIPTS above stay `.prompt`: the outbox is
        // protocol state the peer is waiting on.
        store.update({ $0.messages = snapshot }, durability: .lazily)
    }

    /// ChatView.onAppear / .onDisappear.
    func setActiveThread(_ thread: String?) {
        activeThread = thread
    }

    func enqueueReceipt(_ kind: ReceiptKind, ids: [String], to peer: String) {
        store.update {
            var box = $0.receiptOutbox ?? ReceiptOutbox()
            func add(_ lane: inout [String: [String]]) {
                let existing = Set(lane[peer] ?? [])
                lane[peer, default: []] += ids.filter { !existing.contains($0) }
            }
            switch kind {
            case .delivered:
                add(&box.delivered)
            case .read:
                // read implies delivered on the sender side
                box.delivered[peer]?.removeAll { ids.contains($0) }
                if box.delivered[peer]?.isEmpty == true { box.delivered[peer] = nil }
                add(&box.read)
            case .burnStart:
                add(&box.burnStart)
            }
            $0.receiptOutbox = box
        }
        scheduleReceiptFlush()
    }

    /// Debounce 300 ms so a burst of stored/read messages becomes one control
    /// frame per peer per lane — 40 unread on chat open never trips the
    /// relay's 30-sends/10 s limiter.
    private func scheduleReceiptFlush(after seconds: Double = 0.3) {
        receiptFlushTask?.cancel()
        receiptFlushTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            await self?.flushReceipts()
        }
    }

    private func flushReceipts() async {
        guard connState == .online, let snapshot = store.blob.receiptOutbox,
              !snapshot.isEmpty else { return }
        for kind in [ReceiptKind.delivered, .read, .burnStart] {
            let map: [String: [String]]
            switch kind {
            case .delivered: map = snapshot.delivered
            case .read: map = snapshot.read
            case .burnStart: map = snapshot.burnStart
            }
            for (peer, allIds) in map where !allIds.isEmpty {
                guard crypto.hasSession(with: peer) else { continue }  // leave in outbox
              // group rows ride in their own control carrying `group` (GROUPS.md §2)
              for (groupID, ids) in partitionByGroup(allIds) {
                var payload: Wire.Payload = (kind == .burnStart)
                    ? Wire.Payload(kind: "control", control: "burn_start", refIds: ids)
                    : Wire.Payload(kind: "control", control: "receipt",
                                   receipt: kind.rawValue, refIds: ids)
                if let groupID, let g = group(id: groupID) {
                    payload.group = Wire.GroupRef(id: g.id, epoch: g.epoch)
                }
                let sendId = UUID().uuidString.lowercased()
                guard let out = try? prepare(payload, to: peer, kind: "control", ttl: 86_400,
                                             id: sendId) else { continue }
                inflightReceipts[sendId] = (peer, kind, ids)
                // remove ONLY the dispatched ids, synchronously BEFORE the
                // await — a receipt enqueued while the send suspends must
                // survive (a whole-lane overwrite after the loop would
                // silently drop it)
                store.update {
                    guard var live = $0.receiptOutbox else { return }
                    func strip(_ lane: inout [String: [String]]) {
                        lane[peer]?.removeAll { ids.contains($0) }
                        if lane[peer]?.isEmpty == true { lane[peer] = nil }
                    }
                    switch kind {
                    case .delivered: strip(&live.delivered)
                    case .read: strip(&live.read)
                    case .burnStart: strip(&live.burnStart)
                    }
                    $0.receiptOutbox = live.isEmpty ? nil : live
                }
                _ = try? await dispatch(out)
              }
            }
        }
    }

    /// Split a receipt batch by the group thread its rows live in (nil = 1:1
    /// rows and ids whose row is already gone). Order: 1:1 first, then groups
    /// in first-seen order.
    private func partitionByGroup(_ ids: [String]) -> [(group: String?, ids: [String])] {
        var plain: [String] = []
        var byGroup: [String: [String]] = [:]
        var order: [String] = []
        for id in ids {
            if let (thread, _) = findMessage(id: id), isGroupThread(thread) {
                if byGroup[thread] == nil { order.append(thread) }
                byGroup[thread, default: []].append(id)
            } else {
                plain.append(id)
            }
        }
        var out: [(group: String?, ids: [String])] = []
        if !plain.isEmpty { out.append((nil, plain)) }
        for g in order { out.append((g, byGroup[g] ?? [])) }
        return out
    }

    /// Return in-flight receipts to the outbox (disconnect / transient error).
    private func requeueInflightReceipt(id: String) {
        guard let (peer, kind, ids) = inflightReceipts.removeValue(forKey: id) else { return }
        enqueueReceipt(kind, ids: ids, to: peer)
    }

    private func markSent(id: String) {
        setStatus(id: id, to: .sent) { $0 == .pending }
    }

    private func markFailed(id: String) {
        blobProgress[id] = nil
        setStatus(id: id, to: .failed) { $0 == .pending || $0 == .sent }
    }

    func setStatus(id: String, to status: ChatMessage.Status,
                           when allowed: (ChatMessage.Status) -> Bool) {
        for (thread, var log) in messages {
            if let i = log.firstIndex(where: { $0.id == id }) {
                guard allowed(log[i].status) else { return }
                log[i].status = status
                messages[thread] = log
                let snapshot = messages
                store.update { $0.messages = snapshot }
                return
            }
        }
    }

    // MARK: blob transfers (voice / image / file)

    /// UI hands in an ALREADY normalized JPEG (see ImageNormalizer), and — when
    /// it kept the original around — what that re-encode threw away
    /// (docs/SCRUB-AND-WIPE.md §B1: re-rasterizing IS the strip for photos;
    /// the receipt names what was in the file before it).
    func sendImage(jpeg: Data, width: Int, height: Int, to contact: Contact,
                   removed: [ScrubField]? = nil) async {
        let id = UUID().uuidString.lowercased()
        let attId = UUID().uuidString.lowercased()
        guard (try? store.writeAttachment(id: attId, data: jpeg)) != nil else {
            lastError = "store_io"; return
        }
        let burn = burnDefault(for: contact.hash)
        var msg = ChatMessage(id: id, direction: .outgoing, kind: .image, text: "[IMG]",
                              timestamp: Date(), status: .pending,
                              burnAfter: burn, burnStartedAt: nil,
                              attachmentId: attId, fileMime: "image/jpeg",
                              fileBytes: jpeg.count, imageWidth: width, imageHeight: height)
        msg.removedMetadata = (removed?.isEmpty == false) ? removed : nil
        msg.scrubbable = true
        appendMessage(msg, thread: contact.hash)
        SoundPlayer.play(.tx, settings: store.blob.settings)
        await sendBlob(id: id, content: jpeg, subtype: .image,
                       mime: "image/jpeg", width: width, height: height, to: contact)
    }

    /// docs/SCRUB-AND-WIPE.md §B: every attachment is scrubbed here, before
    /// encryption, on a copy — the file the user picked is never touched. The
    /// compose bar scrubs at staging time so it can show §B3's line before the
    /// message goes, and hands the result in; any other caller gets the scrub
    /// done here, so the guarantee holds at the choke point.
    func sendFile(data: Data, filename: String, mime: String, to contact: Contact,
                  scrubbed: MetadataScrubber.Report? = nil) async {
        guard !data.isEmpty else { lastError = "empty_file"; return }
        guard data.count <= Self.maxFilePlaintextBytes else {
            lastError = "file_too_large"; return
        }
        let name = sanitizeFilename(filename)
        var payload = data
        var report = scrubbed
        if report == nil {
            let (clean, made) = await MetadataScrubber.scrub(data, filename: name, mime: mime)
            payload = clean
            report = made
        }
        guard payload.count <= Self.maxFilePlaintextBytes else {
            lastError = "file_too_large"; return
        }
        let id = UUID().uuidString.lowercased()
        let attId = UUID().uuidString.lowercased()
        guard (try? store.writeAttachment(id: attId, data: payload)) != nil else {
            lastError = "store_io"; return
        }
        let burn = burnDefault(for: contact.hash)
        var msg = ChatMessage(id: id, direction: .outgoing, kind: .file,
                              text: "[FILE] \(name)", timestamp: Date(), status: .pending,
                              burnAfter: burn, burnStartedAt: nil,
                              attachmentId: attId, fileName: name, fileMime: mime,
                              fileBytes: payload.count)
        msg.removedMetadata = report?.anything == true ? report?.removed : nil
        msg.scrubbable = report?.supported
        appendMessage(msg, thread: contact.hash)
        SoundPlayer.play(.tx, settings: store.blob.settings)
        await sendBlob(id: id, content: payload, subtype: .file, filename: name, mime: mime,
                       to: contact)
    }

    /// The recorder's note as it will leave: in the voice the settings name
    /// (docs/VOICE-CHANGER.md). Nil when that voice could not be made. The caller then sends
    /// nothing: a note is never sent as it was spoken because changing it failed. Meanwhile
    /// `voiceBeingChanged` names `thread`, where the note will be.
    func inChosenVoice(_ spoken: Data, in thread: String) async -> Data? {
        let voice = store.blob.settings.voice
        guard voice != .off else { return spoken }
        voiceBeingChanged[thread, default: 0] += 1
        defer {
            let left = (voiceBeingChanged[thread] ?? 1) - 1
            voiceBeingChanged[thread] = left > 0 ? left : nil
        }
        do {
            return try await VoiceChanger.change(spoken, to: voice)
        } catch {
            lastError = VoiceChanger.refusal
            return nil
        }
    }

    /// False only when the voice could not be changed: nothing was sent, and no row was made.
    @discardableResult
    func sendVoice(_ spoken: Data, duration: TimeInterval, to contact: Contact) async -> Bool {
        guard let audio = await inChosenVoice(spoken, in: contact.hash) else { return false }
        let id = UUID().uuidString.lowercased()
        let burn = burnDefault(for: contact.hash)
        var msg = ChatMessage(id: id, direction: .outgoing, kind: .voice,
                              text: "[voice \(Int(duration))s]", timestamp: Date(),
                              status: .pending, burnAfter: burn, burnStartedAt: nil,
                              voiceDuration: duration)
        // docs/DECOY-ACCOUNT.md §1a: the audio goes to a sidecar, sealed under
        // the same key, exactly where images and files already live. Inline is
        // only ever read, never written.
        applyVoiceSidecar(&msg, audio: audio)
        appendMessage(msg, thread: contact.hash)
        SoundPlayer.play(.tx, settings: store.blob.settings)
        await sendBlob(id: id, content: audio, subtype: .voice, duration: duration, to: contact)
        return true
    }

    /// Re-run a failed blob send (same message id; full re-encrypt + manifest
    /// + chunks — the receiver resets its partial entry on the fresh manifest).
    func retryBlob(messageId: String, thread: String) async {
        guard let contact = contacts.first(where: { $0.hash == thread }),
              let msg = messages[thread]?.first(where: { $0.id == messageId }) else { return }
        let content: Data?
        let subtype: BlobSubtype
        switch msg.kind {
        case .voice: content = voiceData(for: msg); subtype = .voice
        case .image: content = attachmentData(for: msg); subtype = .image
        case .file: content = attachmentData(for: msg); subtype = .file
        default: return
        }
        guard let content else { return }
        setStatus(id: messageId, to: .pending) { $0 == .failed }
        await sendBlob(id: messageId, content: content, subtype: subtype,
                       duration: msg.voiceDuration, filename: msg.fileName,
                       mime: msg.fileMime, width: msg.imageWidth, height: msg.imageHeight,
                       to: contact)
    }

    private func sendBlob(id: String, content: Data, subtype: BlobSubtype,
                          duration: TimeInterval? = nil, filename: String? = nil,
                          mime: String? = nil, width: Int? = nil, height: Int? = nil,
                          to contact: Contact) async {
        do {
            let burn = messages[contact.hash]?.first { $0.id == id }?.burnAfter
            try await uploadBlob(relayId: id, content: content, subtype: subtype,
                                 duration: duration, filename: filename, mime: mime,
                                 width: width, height: height, burn: burn,
                                 to: contact.hash, refId: nil, group: nil) { frac in
                blobProgress[id] = frac
            }
            blobProgress[id] = nil   // done ⇒ status word takes over
        } catch {
            blobProgress[id] = nil
            markFailed(id: id)
            let code = (error as? ServiceError)?.description
            // a sealed manifest's refusal is handed back to this call and never reaches the
            // relay's `error` path, so the row that path leaves for a deleted account is left here
            if code == "unknown_user" { noteAccountGone(contact.hash) }
            lastError = code ?? (error as? AnonLinkLost)?.description ?? error.localizedDescription
            if error is AnonLinkLost { lostWithConnection() }     // the row is FAILED: it goes again when a connection is back
        }
    }

    /// The blob pipeline for ONE peer: session, encrypt, manifest, chunks.
    /// `relayId` is the manifest's id as the caller knows it (= the row id for 1:1;
    /// fresh per recipient for a group fan-out, whose shared row id rides as `refId`).
    /// `progress` reports 0…1 as chunks go out. Throws on any failure.
    ///
    /// On a sealed lane (docs/SEALED-SENDER.md §5.6) the blob is sealed like its
    /// manifest, the chunks wait for the relay to take the manifest and follow it on
    /// the same anonymous connection under the id the relay saw, and a refusal that
    /// changes the path starts over: blob and manifest are encrypted again for it.
    func uploadBlob(relayId: String, content: Data, subtype: BlobSubtype,
                    duration: TimeInterval?, filename: String?, mime: String?,
                    width: Int?, height: Int?, burn: TimeInterval?,
                    to hash: String, refId: String?, group: Wire.GroupRef?,
                    progress: (Double) -> Void) async throws {
        if !crypto.hasSession(with: hash) {
            try await fetchAndProcessBundle(for: hash)
        }
        var attempt = 1
        while true {
            let lane = sealedLane(for: hash)
            // the first message to someone new is text only: refused before anything is encrypted
            if lane == .first {
                throw ServiceError.relay(Sealed.firstContactRefusal(name: peerName(hash)))
            }
            let blob = try prepareBlob(content, to: hash, lane: lane)
            guard blob.count <= Self.maxBlobCiphertextBytes else {
                throw ServiceError.relay("file_too_large")
            }
            let chunkCount = (blob.count + Self.blobChunkSize - 1) / Self.blobChunkSize
            var manifest = Wire.Payload(kind: "blob_manifest",
                                        senderName: myUsername, burnAfter: burn,
                                        subtype: subtype.rawValue, chunks: chunkCount,
                                        bytes: blob.count, duration: duration,
                                        filename: filename, mime: mime,
                                        width: width, height: height)
            manifest.refId = refId
            manifest.group = group
            let out = try prepare(manifest, to: hash, kind: "blob", ttl: 172_800, id: relayId,
                                  lane: lane, attempt: attempt)
            // the id the chunks carry, and the connection they go out on: one connection for the
            // manifest, every chunk and the ping after them, whose answer speaks for all of it
            var chunkId = relayId
            var link: AnonLink?
            let main = out.lane == .legacy ? relay : nil
            if out.lane == .legacy {
                try await dispatch(out, via: main)
            } else {
                switch await dispatchForVerdict(out) {
                case .sent(let on, let id):
                    link = on
                    chunkId = id
                    relaySent(relayId)
                case .refused(let code, let on):
                    attempt += 1
                    guard attempt <= Sealed.maxAttempts else { throw ServiceError.relay(code) }
                    switch code {
                    case "denied":
                        await keyDenied(out)
                    case "unsupported":
                        setPeerKey(Sealed.unsupported(peerKey(hash)), for: hash)
                        if let on { anonRetire(on) }
                    case "no_private_connection":
                        break       // the path is the legacy one now, or the link is down
                    default:
                        throw ServiceError.relay(code)
                    }
                    continue
                }
            }
            // the manifest IS the envelope for this row (the chunks that follow
            // are raw binary, not frames) — file it under the row's own id
            captureFrame(out, for: refId ?? relayId)
            guard let uuid = UUID(uuidString: chunkId) else { return }
            let header16 = withUnsafeBytes(of: uuid.uuid) { Data($0) }
            progress(0)
            link?.uploads += 1
            uploading.insert(chunkId)
            defer {
                uploading.remove(chunkId)
                uploadRefused[chunkId] = nil
                if let link {
                    link.uploads -= 1
                    if link.retiring { anonRetire(link) }      // it was waiting for these chunks
                }
            }
            /// The relay refused a chunk and has dropped the attachment: the upload ends there.
            func refusal() throws {
                if let code = uploadRefused[chunkId] { throw ServiceError.relay(code) }
            }
            for i in 0..<chunkCount {
                let lo = i * Self.blobChunkSize
                let hi = min(lo + Self.blobChunkSize, blob.count)
                var frame = header16
                var seq = UInt32(i).bigEndian
                var total = UInt32(chunkCount).bigEndian
                frame.append(Data(bytes: &seq, count: 4))
                frame.append(Data(bytes: &total, count: 4))
                frame.append(blob[lo..<hi])
                if let link {
                    do {
                        guard !link.ended else { throw AnonLinkLost() }
                        try await link.client.sendBinary(frame)
                    } catch {
                        // the connection the chunks were going out on is gone: this upload is
                        // lost with it, like a message in flight
                        anonEnd(link)
                        throw AnonLinkLost()
                    }
                } else if let main {
                    try await main.sendBinary(frame)      // throws once this socket is gone or replaced
                }       // no link at all: nothing is written, as for any legacy send (see `dispatch`)
                try refusal()
                progress(Double(i + 1) / Double(chunkCount))
            }
            // The chunks were handed to the system, not to the relay. It has them all once it
            // answers a ping written after the last one (§5.6). Until then the upload is in
            // flight: a connection that ends first takes it along, like a message without
            // its verdict, and it is sent again.
            if let link {
                do {
                    try await link.client.barrier(timeout: Self.uploadAnswerTimeout)
                } catch {
                    anonEnd(link)
                    throw AnonLinkLost()
                }
            } else if let main {
                do {
                    try await main.barrier(timeout: Self.uploadAnswerTimeout)
                } catch {
                    // no answer is a dead link, as for a keepalive ping: drop it, so the login
                    // that follows sends this again
                    if error is PingLedger.NoAnswer, relay === main, connState == .online {
                        main.disconnect()
                        linkLost()
                    }
                    throw error
                }
            }
            try refusal()
            return
        }
    }

    private func receiveBlobChunk(_ frame: Data) async {
        guard frame.count > 24 else { return }
        let uuid = frame.prefix(16).withUnsafeBytes { $0.load(as: uuid_t.self) }
        let id = UUID(uuid: uuid).uuidString.lowercased()
        let seq = Int(UInt32(bigEndian: frame.subdata(in: 16..<20).withUnsafeBytes { $0.load(as: UInt32.self) }))
        guard var entry = blobIncoming[id] else { return }
        entry.chunks[seq] = frame.subdata(in: 24..<frame.count)
        blobIncoming[id] = entry
        rxTransfers[id]?.received = entry.chunks.count
        if entry.chunks.count == entry.total {
            await assembleBlob(id: id)
        }
    }

    /// Only called once chunks.count == total.
    private func assembleBlob(id: String) async {
        guard let entry = blobIncoming.removeValue(forKey: id) else { return }
        var blob = Data()
        for i in 0..<entry.total {
            guard let c = entry.chunks[i] else {
                // gap ⇒ duplicate seq overwrote another: unrecoverable
                rxTransfers[id] = nil
                try? await relay?.send(Wire.Out(t: "ack", id: id))
                lastError = "decrypt_failed"
                return
            }
            blob.append(c)
        }
        guard blob.count == entry.bytes else {
            // transport damage (lost/duplicated chunks) — NOT ratchet damage
            rxTransfers[id] = nil
            try? await relay?.send(Wire.Out(t: "ack", id: id))
            lastError = "decrypt_failed"
            return
        }
        let plain: Data
        do {
            plain = try decryptIncoming(blob, entry)
        } catch let rejected as Sealed.Rejected where !rejected.heal {
            // not the manifest's sender, or not sealed the way its manifest was:
            // nobody to answer, nothing to heal
            rxTransfers[id] = nil
            try? await relay?.send(Wire.Out(t: "ack", id: id))
            return
        } catch {
            rxTransfers[id] = nil
            try? await relay?.send(Wire.Out(t: "ack", id: id))   // permanent: wipe queue
            lastError = "decrypt_failed"
            noteDecryptFailure(from: entry.from)
            return
        }

        // group blobs, and every blob on a sealed lane: the row id is the
        // sender's own message id, carried inside (§4; SEALED-SENDER.md §5.3)
        let rowId = entry.group != nil || entry.sealedTs != nil ? (entry.refId ?? id) : id
        if entry.group == nil, entry.sealedTs != nil,
           messages[entry.from]?.contains(where: { $0.id == rowId }) == true {
            rxTransfers[id] = nil                               // a retry of one already here
            try? await relay?.send(Wire.Out(t: "ack", id: id))
            return
        }
        let msg: ChatMessage
        switch entry.subtype {
        case "voice":
            // §1a: spill an inbound note on receipt. The wire format is not
            // changing this wave — the bytes arrive the same way — but they
            // never touch the blob.
            var voice = ChatMessage(id: rowId, direction: .incoming, kind: .voice,
                                    text: "[voice \(Int(entry.duration ?? 0))s]",
                                    timestamp: Date(), status: .delivered,
                                    burnAfter: entry.burnAfter, burnStartedAt: nil,
                                    voiceDuration: entry.duration ?? 0)
            applyVoiceSidecar(&voice, audio: plain)
            msg = voice
        case "image":
            let attId = UUID().uuidString.lowercased()
            guard (try? store.writeAttachment(id: attId, data: plain)) != nil else {
                rxTransfers[id] = nil
                lastError = "store_io"
                return
            }
            msg = ChatMessage(id: rowId, direction: .incoming, kind: .image, text: "[IMG]",
                              timestamp: Date(), status: .delivered,
                              burnAfter: entry.burnAfter, burnStartedAt: nil,
                              attachmentId: attId, fileMime: entry.mime ?? "image/jpeg",
                              fileBytes: plain.count, imageWidth: entry.width,
                              imageHeight: entry.height)
        default: // "file"
            let name = sanitizeFilename(entry.filename ?? "file")
            let attId = UUID().uuidString.lowercased()
            guard (try? store.writeAttachment(id: attId, data: plain)) != nil else {
                rxTransfers[id] = nil
                lastError = "store_io"
                return
            }
            msg = ChatMessage(id: rowId, direction: .incoming, kind: .file,
                              text: "[FILE] \(name)", timestamp: Date(), status: .delivered,
                              burnAfter: entry.burnAfter, burnStartedAt: nil,
                              attachmentId: attId, fileName: name, fileMime: entry.mime,
                              fileBytes: plain.count)
        }
        if let gid = entry.group {
            guard let g = group(id: gid), !g.isLeft,
                  messages[gid]?.contains(where: { $0.id == rowId }) != true else {
                purgeAttachments(of: [msg])                    // unknown/left group or duplicate: drop
                rxTransfers[id] = nil
                try? await relay?.send(Wire.Out(t: "ack", id: id))
                return
            }
            var row = msg
            row.group = gid
            row.sender = entry.from
            appendMessage(row, thread: gid)
        } else {
            ensureContact(for: entry.from, suggestedName: entry.senderName)
            appendMessage(msg, thread: entry.from)
        }
        rxTransfers[id] = nil
        try? await relay?.send(Wire.Out(t: "ack", id: id))   // wipes relay queue
        enqueueReceipt(.delivered, ids: [rowId], to: entry.from)
        SoundPlayer.play(.rx, settings: store.blob.settings)
    }

    /// A blob takes the path its manifest took: sealed by the same sender when the
    /// manifest was, and judged at the manifest's timestamp; named otherwise.
    private func decryptIncoming(_ blob: Data, _ entry: IncomingBlob) throws -> Data {
        let sealedBlob = blob.first == Sealed.envelopeType
        guard let ts = entry.sealedTs else {
            guard !sealedBlob else { throw Sealed.Rejected(check: "C0") }
            return try crypto.decryptBlob(blob, from: entry.from)
        }
        guard sealedBlob, ts > 0 else { throw Sealed.Rejected(check: "C0") }
        return try crypto.decryptSealedBlob(blob, from: entry.from, ts: UInt64(ts),
                                            roots: sealedRoots,
                                            minKeyId: SealedTrust.minServerKeyId)
    }

    /// Decrypt-on-demand with NSCache; nil if the file is gone (burned/wiped).
    /// docs/DECOY-ACCOUNT.md §1a: a voice note's audio, from the sidecar it now
    /// lives in — falling back to inline `voiceData` for a row written by an
    /// older build, a restored backup or a peer that has not moved yet.
    /// Decoding stays tolerant; only writing changed.
    func voiceData(for message: ChatMessage) -> Data? {
        attachmentData(for: message) ?? message.voiceData
    }

    /// Seal `audio` into a sidecar and point the row at it. If the sidecar
    /// cannot be written the audio stays inline rather than being lost — a
    /// blob that is too large still opens, and `SecureStore.onStoreFull` says
    /// so; silently dropping someone's note would be the worse failure.
    func applyVoiceSidecar(_ msg: inout ChatMessage, audio: Data) {
        let attId = UUID().uuidString.lowercased()
        guard (try? store.writeAttachment(id: attId, data: audio)) != nil else {
            msg.voiceData = audio
            return
        }
        msg.attachmentId = attId
        msg.fileBytes = audio.count
        attachmentCache.setObject(audio as NSData, forKey: attId as NSString, cost: audio.count)
    }

    func attachmentData(for message: ChatMessage) -> Data? {
        guard let attId = message.attachmentId else { return nil }
        if let cached = attachmentCache.object(forKey: attId as NSString) {
            return cached as Data
        }
        guard let data = store.readAttachment(id: attId) else { return nil }
        attachmentCache.setObject(data as NSData, forKey: attId as NSString, cost: data.count)
        return data
    }

    // MARK: burn actions

    func purgeAttachments(of msgs: [ChatMessage]) {
        for m in msgs {
            if let a = m.attachmentId {
                store.deleteAttachment(id: a)
                attachmentCache.removeObject(forKey: a as NSString)
            }
        }
    }

    /// Manual thread burn: wipes local log and tells the peer to do the same.
    func burnThread(_ contact: Contact) async {
        purgeAttachments(of: messages[contact.hash] ?? [])
        messages[contact.hash] = []
        store.update { $0.messages[contact.hash] = [] }
        if let out = try? prepare(
            Wire.Payload(kind: "control", control: "burn_thread"), to: contact.hash,
            kind: "control", ttl: 86_400, id: UUID().uuidString.lowercased()) {
            _ = try? await dispatch(out)
        }
    }

    // MARK: desktop-link disclosure (MIRROR-GROUPS.md §5, contextual)

    /// One outbound `web_link` frame: the recipient and the E2E payload.
    typealias WebLinkFrame = (to: String, payload: Wire.Payload)

    /// The `web_link` frames for ONE thread — the audience rule, pure: the
    /// peer of a 1:1 thread (a contact, not blocked), or every fan-out
    /// recipient of a group (never me, never a gone or blocked member), each
    /// with `group:{id,epoch}` exactly like any other group control. A left
    /// or unknown group and an unknown or blocked hash yield nothing. The
    /// browser label rides only on link-up.
    func webLinkFrames(thread: String, up: Bool, browser: String?) -> [WebLinkFrame] {
        var payload = Wire.Payload(kind: "control", control: "web_link")
        payload.linkUp = up
        payload.browser = up ? browser : nil
        if let g = group(id: thread) {
            guard !g.isLeft else { return [] }
            payload.group = Wire.GroupRef(id: g.id, epoch: g.epoch)
            return recipients(of: g).map { ($0.hash, payload) }
        }
        guard contacts.contains(where: { $0.hash == thread }),
              !blockedHashes.contains(thread) else { return [] }
        return [(thread, payload)]
    }

    /// Unit-test tap: when set, the frames of every `sendWebLink` are handed
    /// here instead of being encrypted and sent (no relay, no sessions needed).
    var webLinkTap: ((_ thread: String, _ frames: [WebLinkFrame]) -> Void)?

    /// Tell ONE conversation the desktop link went up or down (E2E, relay-
    /// blind): the browser is looking at — or writing into — this thread, so
    /// its peer, or every member of the group, deserves to know. Nobody else
    /// hears about the browser (v2.1: the link-time broadcast is gone). A
    /// recipient without a session gets one first (the browser may open a
    /// thread nobody has written in yet); an unreachable one is skipped, a
    /// deregistered group member is marked gone (GROUPS.md §4).
    func sendWebLink(thread: String, up: Bool, browser: String?) {
        let frames = webLinkFrames(thread: thread, up: up, browser: browser)
        if let tap = webLinkTap { tap(thread, frames); return }
        for f in frames {
            Task { [weak self] in
                guard let self else { return }
                do {
                    if !crypto.hasSession(with: f.to) {
                        try await fetchAndProcessBundle(for: f.to)
                    }
                    let out = try prepare(f.payload, to: f.to, kind: "control", ttl: 86_400,
                                          id: UUID().uuidString.lowercased())
                    try await dispatch(out)
                } catch {
                    if "\(error)".contains("unknown_user"), let gid = f.payload.group?.id {
                        noteMemberGone(f.to, in: gid)
                    }
                }
            }
        }
    }

    /// Screenshot or screen-recording detected locally: alert the peer
    /// (E2E, relay-blind). `control` is "screenshot" or "screen_record".
    func reportCapture(_ control: String, in contact: Contact) async {
        if let out = try? prepare(
            Wire.Payload(kind: "control", control: control), to: contact.hash,
            kind: "control", ttl: 86_400, id: UUID().uuidString.lowercased()) {
            _ = try? await dispatch(out)
        }
    }

    private func topUpPreKeys() async {
        guard let fresh = try? crypto.generateOneTimePreKeys(32) else { return }
        try? await relay?.send(Wire.Out(t: "prekey_upload", one_time_prekeys: fresh))
    }

    // MARK: burn sweeper

    private func startBurnSweeper() {
        burnTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.sweepBurned() }
        }
    }

    private func sweepBurned() {
        let now = Date()
        var changed = false
        for (thread, log) in messages {
            let expired = log.filter { msg in
                guard let deadline = msg.burnDeadline else { return false }
                return deadline <= now
            }
            if !expired.isEmpty {
                purgeAttachments(of: expired)
                messages[thread] = log.filter { msg in
                    guard let deadline = msg.burnDeadline else { return true }
                    return deadline > now
                }
                changed = true
            }
        }
        // thread-level auto-burn after N days of silence
        if let days = store.blob.settings.threadAutoBurnDays {
            let cutoff = now.addingTimeInterval(-Double(days) * 86_400)
            for (thread, log) in messages {
                if let last = log.map(\.timestamp).max(), last < cutoff, !log.isEmpty {
                    purgeAttachments(of: log)
                    messages[thread] = []
                    changed = true
                }
            }
        }
        // typing indicators expire after 6 s of silence
        let typingCutoff = now.addingTimeInterval(-6)
        for (peer, at) in typingPeers where at < typingCutoff {
            typingPeers[peer] = nil
        }
        for (gid, members) in groupTyping {
            let live = members.filter { $0.value >= typingCutoff }
            if live.count != members.count { groupTyping[gid] = live.isEmpty ? nil : live }
        }
        // stale partial transfers (sender vanished mid-blob)
        let staleCutoff = now.addingTimeInterval(-48 * 3600)
        for (id, entry) in blobIncoming where entry.startedAt < staleCutoff {
            blobIncoming[id] = nil
            rxTransfers[id] = nil
        }
        if changed {
            let snapshot = messages
            store.update { $0.messages = snapshot }
        }
        sweepExpiredNotices(now: now)
    }

    // MARK: operator notices (relay-native: no peer, no E2E, nothing per-user)

    /// `notice` frame: upsert by id — the relay replays every active notice
    /// at login, so a known id is normal. Malformed frames (empty text,
    /// non-https url, already expired) are ignored. Returns whether the
    /// board changed; internal so tests can feed frames without a relay.
    @discardableResult
    func ingestNotice(_ f: Wire.In, now: Date = Date()) -> Bool {
        guard let n = Notice.parse(f, now: now) else { return false }
        let known = notices.contains { $0.id == n.id }
        var list = notices.filter { $0.id != n.id && $0.expires > now }
        list.append(n)
        list.sort { ($0.ts, $0.id) < ($1.ts, $1.id) }
        guard list != notices else { return false }
        notices = list
        NoticeStore.saveNotices(notices, to: defaults)
        pruneSeenNotices()
        recountUnseenNotices()
        // a genuinely new notice sounds like an incoming message; the login
        // replay of notices this device already holds stays silent
        if !known, !seenNoticeIds.contains(n.id) {
            SoundPlayer.play(.rx, settings: store.blob.settings)
        }
        return true
    }

    /// `notice_gone` frame: the operator deleted it.
    func removeNotice(id: String) {
        guard notices.contains(where: { $0.id == id }) else { return }
        notices.removeAll { $0.id == id }
        NoticeStore.saveNotices(notices, to: defaults)
        pruneSeenNotices()
        recountUnseenNotices()
    }

    /// Drop notices past `expires` (sweeper tick + re-foreground).
    func sweepExpiredNotices(now: Date = Date()) {
        let live = notices.filter { $0.expires > now }
        guard live.count != notices.count else { return }
        notices = live
        NoticeStore.saveNotices(notices, to: defaults)
        pruneSeenNotices()
        recountUnseenNotices()
    }

    /// NoticesView open: everything on the board counts as seen.
    func markNoticesSeen() {
        let unseen = Set(notices.map(\.id)).subtracting(seenNoticeIds)
        guard !unseen.isEmpty else { return }
        seenNoticeIds.formUnion(unseen)
        NoticeStore.saveSeen(seenNoticeIds, to: defaults)
        recountUnseenNotices()
    }

    // MARK: latest versions (update banner — relay-native like a notice)

    /// `versions` frame: the relay's newest build per platform, sent after
    /// the notice replay at every login and live whenever the operator
    /// changes it. Our entry replaces the remembered one (absent ⇒ forgotten)
    /// and the banner is recomputed against the running build. Returns
    /// false for a malformed frame, which is ignored as a whole — nothing
    /// changes, the previous banner stays.
    @discardableResult
    func ingestVersions(_ f: Wire.In) -> Bool {
        switch UpdateCheck.read(f) {
        case .malformed: return false
        case .absent: setLatestVersion(nil)
        case .latest(let v): setLatestVersion(v)
        }
        return true
    }

    private func setLatestVersion(_ v: LatestVersion?) {
        if v != latestVersion {
            latestVersion = v
            NoticeStore.saveLatestVersion(v, to: defaults)
        }
        let banner = UpdateCheck.updateAvailable(latest: v, running: runningBuild)
        if banner != updateAvailable { updateAvailable = banner }
    }

    /// Seen ids only ever refer to active notices (ids never come back).
    private func pruneSeenNotices() {
        let pruned = seenNoticeIds.intersection(notices.map(\.id))
        guard pruned != seenNoticeIds else { return }
        seenNoticeIds = pruned
        NoticeStore.saveSeen(seenNoticeIds, to: defaults)
    }

    private func recountUnseenNotices() {
        let n = notices.filter { !seenNoticeIds.contains($0.id) }.count
        if n != unseenNoticeCount { unseenNoticeCount = n }
    }

    // MARK: notes (local-only; never touches the relay)

    @discardableResult
    func createNote() -> Note {
        let n = Note(id: UUID().uuidString, text: "",
                     createdAt: Date(), modifiedAt: Date(), pinned: nil)
        notes.append(n)
        persistNotes()
        return n
    }

    /// No-ops (and doesn't bump modifiedAt) when text is unchanged.
    func updateNoteText(id: String, text: String) {
        guard let i = notes.firstIndex(where: { $0.id == id }),
              notes[i].text != text else { return }
        notes[i].text = text
        notes[i].modifiedAt = Date()
        persistNotes()
    }

    func togglePin(id: String) {
        guard let i = notes.firstIndex(where: { $0.id == id }) else { return }
        notes[i].pinned = !(notes[i].pinned ?? false)
        persistNotes()
    }

    func deleteNote(id: String) {
        notes.removeAll { $0.id == id }
        persistNotes()
    }

    /// Editor-close hook: a brand-new buffer that never received real text is
    /// discarded silently. Existing notes are never auto-deleted.
    func finalizeNote(id: String, isNew: Bool) {
        guard isNew,
              let n = notes.first(where: { $0.id == id }),
              n.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return }
        deleteNote(id: id)
    }

    /// Pinned first; within each group newest-modified first; stable ties.
    var sortedNotes: [Note] {
        notes.sorted {
            if $0.isPinned != $1.isPinned { return $0.isPinned }
            if $0.modifiedAt != $1.modifiedAt { return $0.modifiedAt > $1.modifiedAt }
            if $0.createdAt != $1.createdAt { return $0.createdAt > $1.createdAt }
            return $0.id < $1.id
        }
    }

    private func persistNotes() {
        let snapshot = notes
        store.update { $0.notes = snapshot }
    }

    // MARK: helpers

    func findMessage(id: String) -> (thread: String, msg: ChatMessage)? {
        for (thread, log) in messages {
            if let m = log.first(where: { $0.id == id }) { return (thread, m) }
        }
        return nil
    }

    /// Re-encrypt and re-send a text message under its ORIGINAL id — the
    /// receiver dedupes by id, so a race with the first copy is harmless.
    private func resendText(id: String, thread: String) async {
        guard let log = messages[thread],
              let msg = log.first(where: { $0.id == id }),
              msg.status == .pending || msg.status == .sent else { return }
        guard let out = try? prepare(
            Wire.Payload(kind: "text", text: msg.text, senderName: myUsername,
                         burnAfter: msg.burnAfter),
            to: thread, kind: "msg", ttl: 172_800, id: id) else { markFailed(id: id); return }
        _ = try? await dispatch(out)
    }

    func appendMessage(_ msg: ChatMessage, thread: String) {
        messages[thread, default: []].append(msg)
        let snapshot = messages
        store.update { $0.messages = snapshot }
    }

    private func replaceMessage(_ msg: ChatMessage, thread: String) {
        guard var log = messages[thread],
              let i = log.firstIndex(where: { $0.id == msg.id }) else { return }
        log[i] = msg
        messages[thread] = log
        let snapshot = messages
        store.update { $0.messages = snapshot }
    }

    enum ServiceError: Error, CustomStringConvertible {
        case relay(String)
        var description: String {
            if case .relay(let code) = self { return code }
            return "unknown"
        }
    }
}

/// Display-name hygiene: strip path separators/control chars/leading dots,
/// clamp to 64 chars preserving the extension; empty ⇒ "file".
func sanitizeFilename(_ raw: String) -> String {
    var name = String(raw
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "\\", with: "_")
        .unicodeScalars
        .filter { $0.value >= 0x20 && $0 != "\u{7F}" })
        .trimmingCharacters(in: .whitespaces)
    while name.hasPrefix(".") { name.removeFirst() }
    if name.isEmpty { return "file" }
    if name.count > 64 {
        let ext = (name as NSString).pathExtension
        if !ext.isEmpty && ext.count < 12 {
            let base = String(name.prefix(64 - ext.count - 1))
            name = base + "." + ext
        } else {
            name = String(name.prefix(64))
        }
    }
    return name
}

// MARK: - the decoy (docs/DECOY-ACCOUNT.md §3)

extension ChatService {

    /// Everything the `[cfg]` row needs to know, and nothing it does not.
    ///
    /// §3: "The row reads the same whether or not a decoy exists. It never
    /// says 'configured', never shows a checkmark, never changes colour." So
    /// there is deliberately no `decoyExists` here — nothing in the interface
    /// may ask that question, because a row that renders differently once used
    /// is a disclosure. `canCreateDecoy` is consulted only AFTER the user has
    /// typed a username and a password and pressed the button, at which point
    /// the answer is a failure message like any other.
    var canCreateDecoy: Bool {
        store.hasAllocatedDecoy || !store.freeSlots.isEmpty
    }

    /// §3: "Replacing may say so plainly once the dialog is open, because only
    /// the account that owns the decoy can reach it, and anyone who has
    /// compelled that account has already won."
    ///
    /// The ROW never consults this — it reads the same everywhere, and that
    /// invariant is unchanged. Only the dialog's explainer does, after the tap.
    var decoyDialogIsReplacement: Bool { store.hasAllocatedDecoy }

    /// §3. Asks for a username and a password, rejects a password equal to
    /// this account's, and creates a GENUINE account — real identity keys,
    /// real relay registration — in a slot this account knows is free.
    ///
    /// Returns nil on success, or `DecoyCopy.failed` — see there for why that
    /// is one string and not several.
    ///
    /// Order matters and is the same order registration has always used, for
    /// the same reason: the slot is authored first because `register` needs a
    /// store to write identity keys into, and if the relay then refuses, the
    /// slot is scrubbed back to noise and never recorded as allocated. A
    /// failed attempt therefore leaves the container byte-identical to one
    /// where nothing was tried, except for `slotBytes` of fresh noise in a
    /// slot that was already noise.
    /// How long decoy creation will wait on the relay before giving up.
    static let decoyRegisterTimeout: TimeInterval = 20

    /// Run `work`, or throw once `seconds` have passed. Whichever finishes
    /// first wins and the other is cancelled.
    static func withTimeout<T: Sendable>(seconds: TimeInterval,
                                         _ work: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await work() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw ServiceError.relay("decoy_register_timeout")
            }
            guard let first = try await group.next() else {
                throw ServiceError.relay("decoy_register_timeout")
            }
            group.cancelAll()
            return first
        }
    }

    func createDecoy(username: String, password: String) async -> String? {
        let failed = DecoyCopy.failed
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines)
        // §3: create OR REPLACE. The row is never a one-shot — without this a
        // mistyped decoy password is unrecoverable, because the account cannot
        // reach a slot whose key it does not hold and there can be no "remove
        // decoy" row (the row would itself be the disclosure). The only way
        // out would be wiping the device, and a setting-shaped row that is
        // really an irreversible one-time action is a trap.
        let slot: Int
        if let existing = store.allocatedDecoySlot {
            // A slot in THIS account's own record: allocating it again is safe
            // for exactly the reason allocating it the first time was.
            slot = existing
        } else {
            // §1: only a slot this account KNOWS is free.
            let free = store.freeSlots
            guard !free.isEmpty else { return failed }
            let chosen = SlotVault.chooseSlot(free)
            // The seam is injectable; a slot outside `free` would mean writing
            // over an account we cannot open, which is the one thing §1 forbids
            // absolutely. Refuse rather than trust the seam.
            guard free.contains(chosen) else { return failed }
            slot = chosen
        }

        // EVERYTHING ABOVE returns the one vague string, because "no room" is
        // the decoy's answer and it must not be distinguishable. EVERYTHING
        // BELOW may speak plainly: reaching here proves this account owns the
        // container, and anyone who compelled it already holds what a decoy
        // would have concealed. §3's single-string rule protects the decoy,
        // not the owner — applying it to the owner only made a valid setup
        // fail with no way to find out why.
        guard Self.isValidUsername(name) else { return DecoyCopy.badUsername }
        guard password.count >= 8 else { return DecoyCopy.shortPassword }
        // §3: "a password, which must not be the real one".
        guard !store.derivesSameKey(as: password) else { return DecoyCopy.samePassword }

        let url = store.fileURL

        // `ChatService.init` hands the store's text size to `Theme`, which is
        // process-global. Building a second service for the decoy therefore
        // re-points the running app's type size at the decoy's defaults —
        // §4's leak, running backwards. Put it back whichever way this goes.
        defer { Theme.applyTextSize(self.store.blob.settings) }

        let decoyStore: SecureStore
        do {
            decoyStore = try SecureStore(creatingDecoyWithPassword: password,
                                         inSlot: slot, at: url)
        } catch {
            return DecoyCopy.storeFailed
        }
        // Replacing leaves the previous decoy's attachments behind: they are
        // sealed under a key nobody holds any more, so they cannot be
        // attributed, opened or deleted by anything that could also be trusted
        // to delete them. §6.2 already accepts that files can be counted but
        // not attributed; this adds to the count and nothing else.

        // The network the DEVICE talks to, copied across so the decoy reaches
        // the same relay by the same route. Nothing else is: §4's leak is a
        // theme or a text size chosen in one account showing up in another,
        // and none of those come over. A decoy that dialled a different host
        // than the account beside it would be a far louder tell than any of
        // them.
        decoyStore.update {
            $0.settings.relayURL = self.store.blob.settings.relayURL
            $0.settings.billingURL = self.store.blob.settings.billingURL
            $0.settings.socksProxy = self.store.blob.settings.socksProxy
        }
        decoyStore.flush()

        let decoy = ChatService(store: decoyStore)
        do {
            // BOUNDED. This is a network round trip to the relay, and an
            // unbounded await here is what "I pressed SET and nothing happened"
            // actually looks like: the dialog closes and the app waits, with no
            // ceiling, on a host that may be slow, blocked or gone. Better to
            // answer wrongly-but-promptly than to hang with nothing to read.
            try await Self.withTimeout(seconds: Self.decoyRegisterTimeout) {
                try await decoy.register(username: name)
            }
        } catch {
            decoy.shutdown()
            decoyStore.discardPendingWrites()
            // We hold this slot's key and authored it seconds ago, so putting
            // noise back is permitted by §1 — and leaving a half-registered
            // account there would be worse than either outcome.
            try? SecureStore.scrubSlot(slot, at: url)
            if case ServiceError.relay(let code) = error, code == "username_taken" {
                return DecoyCopy.usernameTaken
            }
            return DecoyCopy.relayUnreachable
        }
        // §3's "use it" advice is only honest if the decoy looks like every
        // other install of this app, and every other install has this contact.
        // Best-effort and also bounded: a decoy without pyrobot is odd but
        // usable, and is a far better outcome than a spinner that never ends.
        try? await Self.withTimeout(seconds: Self.decoyRegisterTimeout) {
            try await decoy.addContact(username: FirstRun.demoPeer)
        }
        decoy.shutdown()

        // Only now, and promptly: this account must never believe a slot it
        // has already filled is still free. Idempotent, so a replacement
        // records exactly what was already there.
        store.noteAllocated(slot: slot)
        return nil
    }
}
