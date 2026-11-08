import Foundation
import Combine
import CryptoKit
import SwiftUI
import UIKit

/// The phone side of the desktop link: owns a SECOND, unauthenticated relay
/// socket, the pair-channel crypto, and a shadow copy of what the browser
/// has seen. Everything is RAM-only — auto-lock, backgrounding, unlink, and
/// wipe all kill the link; relinking means rescanning a fresh QR.
///
/// Schema: docs/MIRROR-PROTOCOL.md §5 (frozen crypto/framing in §1–4) plus
/// the additive group channel events of docs/MIRROR-GROUPS.md §1 and the
/// contextual browser-link disclosure of §5 (`view`, `announced`).
@MainActor
final class MirrorEngine: ObservableObject {
    enum State: Equatable {
        case joining
        case linked(since: Date)
        case failed(String)
        case closed(String)
    }

    @Published private(set) var state: State = .joining
    let sas: String

    private unowned let service: ChatService
    private let link: MirrorLink
    private let keys: MirrorCrypto.Keys
    private let phonePub: Data
    private var client: RelayClient?
    private var rxTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private var sendChain: Task<Void, Never>?
    private var txSeq: UInt64 = 0
    private var rxSeq: UInt64 = 0
    private var gotAck = false
    /// Threads whose peer(s) heard `web_link {linkUp:true}` in THIS link
    /// session (MIRROR-GROUPS.md §5) — RAM only, gone with the link.
    private(set) var announced: Set<String> = []
    /// which browser is on the other end ("Chrome on Mac"), from hello_ack
    /// (settable so the disclosure tests can label a tapped engine)
    @Published var browserName: String?
    private var diffCancellable: AnyCancellable?
    private var shadow: [String: [MirrorRowStamp]] = [:]   // thread -> mirrored window
    private var shadowContacts: [String: (name: String, verified: Bool)] = [:]
    /// group id -> what the browser knows of the group (name, roster with
    /// colours, policy — `lastTs` is deliberately not part of it)
    private var shadowGroups: [String: String] = [:]
    /// group id -> the `who` list last sent (absent = typing off)
    private var shadowGroupTyping: [String: [String]] = [:]
    private var shadowTyping: Set<String> = []
    private var shadowConn = false
    private var thumbSent: Set<String> = []
    private var thumbBudget = 2_000_000
    /// thread -> browser tempIds FIFO, each stamped with when the command
    /// arrived: a tempId may only attach to a row CREATED after it, so an
    /// older row sliding into the window (after a deletion) can't steal it.
    private var pendingTempIds: [String: [(id: String, since: Date)]] = [:]
    private var cmdStamps: [Date] = []
    private var bgObserver: NSObjectProtocol?
    private var stopped = false
    /// Unit-test tap: when set, every event is handed here instead of being
    /// sealed onto the wire, and the engine behaves as if the browser had
    /// acked (no relay, no browser needed to inspect the schema).
    var emitTap: (([String: Any]) -> Void)?

    struct MirrorRowStamp: Equatable {
        let id: String
        let status: ChatMessage.Status
        let isBurn: Bool
        /// Group rows: the aggregated receipt counts move without the status
        /// word changing (`1/2` → `2/2`) and the browser must follow.
        let delivered: Int
        let read: Int
    }

    static let maxThreads = 40
    static let rowsPerThread = 50
    static let plaintextCap = 32_768
    /// `who` on a group typing event names at most this many members.
    static let maxTypingNames = 3

    init(service: ChatService, link: MirrorLink) throws {
        self.service = service
        self.link = link
        let priv = Curve25519.KeyAgreement.PrivateKey()
        self.phonePub = priv.publicKey.rawRepresentation
        let browserKey = try Curve25519.KeyAgreement.PublicKey(
            rawRepresentation: link.browserPub)
        let ss = try priv.sharedSecretFromKeyAgreement(with: browserKey)
        self.keys = MirrorCrypto.deriveKeys(sharedSecret: ss, pairId: link.pairId,
                                            phonePub: phonePub,
                                            browserPub: link.browserPub)
        self.sas = keys.sas
    }

    // MARK: lifecycle

    func start() {
        guard let url = URL(string: service.store.blob.settings.relayURL) else {
            state = .failed("bad relay url")
            return
        }
        // the mirror's second socket must follow the same proxy policy —
        // a direct connection here would leak the user's IP to the relay
        let c = RelayClient(url: url,
                            socksProxy: service.store.blob.settings.socksProxy)
        client = c
        rxTask = Task { [weak self] in
            for await event in c.events {
                await self?.handle(event)
            }
        }
        c.connect()
        bgObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.stop(reason: "background") }
        }
    }

    func stop(reason: String) {
        guard !stopped else { return }
        stopped = true
        // best-effort goodbye so the browser shows LINK LOST immediately
        if case .linked = state {
            emit(["e": "bye", "reason": reason], evenIfStopped: true)
        }
        // link down: ONLY the conversations that were told about the browser
        for thread in announced.sorted() {
            service.sendWebLink(thread: thread, up: false, browser: nil)
        }
        announced = []
        rxTask?.cancel(); rxTask = nil
        pingTask?.cancel(); pingTask = nil
        diffCancellable = nil
        if let o = bgObserver { NotificationCenter.default.removeObserver(o) }
        let c = client, chain = sendChain, pid = link.pairId
        Task { [weak self] in
            await chain?.value   // let the bye drain first
            try? await c?.send(Wire.Out(t: "pair_close", pair: pid))
            c?.disconnect()
            await MainActor.run { if self?.client === c { self?.client = nil } }
        }
        state = .closed(reason)
        service.objectWillChange.send()
    }

    private func handle(_ event: RelayClient.Event) async {
        switch event {
        case .connected:
            try? await client?.send(Wire.Out(t: "pair_join", pair: link.pairId))
        case .disconnected:
            if !stopped { stop(reason: "relay_gone") }
        case .frame(let f):
            await route(f)
        case .binary:
            break
        }
    }

    private func route(_ f: Wire.In) async {
        switch f.t {
        case "pair_joined":
            state = .linked(since: Date())
            service.objectWillChange.send()
            startPing()
            sendHello()
        case "pair_msg":
            if let body = f.body { handleRecord(body) }
        case "pair_closed":
            if !stopped { stop(reason: "browser_gone") }
        case "error":
            if f.code == "unknown_pair" && !stopped {
                // QR already claimed — possible hijack; surface loudly
                state = .failed("code already claimed — reload the browser page and rescan")
                stop(reason: "hijack_guard")
            }
        default:
            break
        }
    }

    private func startPing() {
        pingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                guard let self, !self.stopped else { return }
                try? await self.client?.send(Wire.Out(t: "ping"))
            }
        }
    }

    /// Browser-supplied string: keep it printable and short before showing
    /// it anywhere in the UI.
    static func sanitizeBrowserName(_ raw: String) -> String? {
        let cleaned = raw.filter { $0.isLetter || $0.isNumber || $0 == " " }
            .trimmingCharacters(in: .whitespaces)
        guard !cleaned.isEmpty else { return nil }
        return String(cleaned.prefix(32))
    }

    // MARK: outbound records

    private func sendHello() {
        // 0x01: pPub ‖ GCM(hello). MUST await hello_ack before anything else.
        let hello: [String: Any] = [
            "v": 1,
            "name": service.myUsername,
            "showRead": service.store.blob.settings.readReceiptsOn,
            "sas": sas,
        ]
        guard let plain = try? JSONSerialization.data(withJSONObject: hello),
              let record = try? MirrorCrypto.seal(
                key: keys.p2b, dir: "p2b", pairId: link.pairId,
                type: 0x01, seq: 0, prefix: phonePub, plaintext: plain) else {
            stop(reason: "seal_failed")
            return
        }
        let body = record.base64EncodedString()
        Task { try? await client?.send(Wire.Out(t: "pair_msg", pair: link.pairId, body: body)) }
    }

    /// A tapped engine (unit tests) counts as acked: it never touches a socket.
    private var live: Bool { gotAck || emitTap != nil }

    /// FIFO emit for 0x03 data records. Both the seq assignment AND the
    /// socket send happen inside the chain — a detached send per record
    /// would race on the wire and trip the browser's strict seq check.
    private func emit(_ obj: [String: Any], evenIfStopped: Bool = false) {
        if let tap = emitTap { tap(obj); return }
        let prev = sendChain
        sendChain = Task { [weak self] in
            await prev?.value
            guard let self, self.gotAck, evenIfStopped || !self.stopped else { return }
            await self.sendRecord(obj)
        }
    }

    private func sendRecord(_ obj: [String: Any]) async {
        guard let plain = try? JSONSerialization.data(withJSONObject: obj) else { return }
        txSeq += 1
        guard let record = try? MirrorCrypto.seal(
            key: keys.p2b, dir: "p2b", pairId: link.pairId,
            type: 0x03, seq: txSeq, plaintext: plain) else {
            stop(reason: "seal_failed")
            return
        }
        let body = record.base64EncodedString()
        try? await client?.send(Wire.Out(t: "pair_msg", pair: link.pairId, body: body))
    }

    // MARK: inbound records

    private func handleRecord(_ b64: String) {
        guard let record = Data(base64Encoded: b64), record.count > 9 else {
            return protoFail()
        }
        let type = record[record.startIndex]
        let seq = record.subdata(in: 1..<9).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        let sealed = record.subdata(in: 9..<record.count)

        if type == 0x02 {
            guard !gotAck, seq == 0,
                  let plain = try? MirrorCrypto.open(
                    key: keys.b2p, dir: "b2p", pairId: link.pairId,
                    type: 0x02, seq: 0, sealed: sealed),
                  let ack = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else {
                return protoFail()
            }
            if let b = ack["browser"] as? String {
                browserName = Self.sanitizeBrowserName(b)
            }
            gotAck = true
            // NOTHING is announced at link time (MIRROR-GROUPS.md §5): the
            // conversations the browser actually touches hear about it one
            // by one, from announce(_:)
            attachDiffing()
            sendSnapshot()
            return
        }
        guard type == 0x03, gotAck, seq == rxSeq + 1,
              let plain = try? MirrorCrypto.open(
                key: keys.b2p, dir: "b2p", pairId: link.pairId,
                type: 0x03, seq: seq, sealed: sealed),
              let obj = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else {
            return protoFail()
        }
        rxSeq = seq
        handleCommand(obj)
    }

    private func protoFail() {
        // any gap, replay, or GCM failure = tampering or a broken peer
        stop(reason: "proto")
    }

    // MARK: browser-link disclosure (MIRROR-GROUPS.md §5)

    /// The first time the browser touches a thread in this link session —
    /// `view`, `send_text`, `typing` or `mark_read` — its peer, or every
    /// member of the group, hears `web_link {linkUp:true, browser}`; every
    /// later touch is silent, and nobody the browser never looked at learns
    /// about it. Only a thread the browser can see counts (a left group, an
    /// unknown or blocked hash: nothing), and only while the phone is online
    /// — an announcement the relay could not carry is not remembered as
    /// made, so the next touch retries.
    func announce(_ thread: String) {
        guard !announced.contains(thread), service.connState == .online else { return }
        if let g = service.group(id: thread) {
            guard !g.isLeft else { return }
        } else {
            guard service.contacts.contains(where: { $0.hash == thread }),
                  !service.isBlocked(thread) else { return }
        }
        announced.insert(thread)
        service.sendWebLink(thread: thread, up: true, browser: browserName)
    }

    // MARK: commands from the browser

    /// `thread` is a contact hash or a group id (MIRROR-GROUPS.md §1): a
    /// group send is the existing fan-out (`viaWeb: true`, the echo carries
    /// the tempId), a group read goes down the per-member receipt path, a
    /// group typing signal reaches every member — and a group this phone
    /// has left answers `cmd_err left`. `view` (§5) only discloses.
    func handleCommand(_ obj: [String: Any]) {
        let tempId = obj["tempId"] as? String ?? ""
        func err(_ code: String) {
            emit(["e": "cmd_err", "tempId": tempId, "code": code])
        }
        switch obj["c"] as? String {
        case "view":
            // the page's active thread changed (also right after the
            // snapshot, and again after a relink): disclosure, nothing else
            if let thread = obj["thread"] as? String { announce(thread) }
        case "send_text":
            guard let thread = obj["thread"] as? String,
                  let text = obj["text"] as? String else { return err("bad_cmd") }
            let group = service.group(id: thread)
            let contact = group == nil ? service.contacts.first(where: { $0.hash == thread }) : nil
            guard group != nil || contact != nil else { return err("unknown_thread") }
            if let g = group, g.isLeft { return err("left") }
            if contact != nil, service.isBlocked(thread) { return err("blocked") }
            guard service.connState == .online else { return err("offline") }
            guard text.count <= 4000 else { return err("too_long") }
            let now = Date()
            cmdStamps.removeAll { now.timeIntervalSince($0) > 10 }
            guard cmdStamps.count < 10 else { return err("rate_limited") }
            cmdStamps.append(now)
            announce(thread)
            if !tempId.isEmpty {
                pendingTempIds[thread, default: []].append((tempId, Date()))
            }
            if let g = group {
                Task { await service.sendText(text, toGroup: g.id, viaWeb: true) }
            } else if let contact {
                Task { await service.sendText(text, to: contact, viaWeb: true) }
            }
        case "mark_read":
            guard let thread = obj["thread"] as? String,
                  let ids = obj["ids"] as? [String], ids.count <= 50 else { return }
            announce(thread)
            let mirrored = Set((shadow[thread] ?? []).filter { !$0.isBurn }.map(\.id))
            let wanted = Set(ids)
            for m in service.messages[thread] ?? []
            where wanted.contains(m.id) && mirrored.contains(m.id) && service.needsVisibilityReport(m) {
                // burn rows are never in `mirrored`, so a desktop read can
                // never start a burn timer or leak a burn receipt; the
                // phone's own visibility rule then skips system rows and
                // anything already read, and a group row's receipt goes to
                // whoever wrote it (noteRowVisible) — exactly an on-phone read
                service.noteRowVisible(messageID: m.id, thread: thread)
            }
        case "typing":
            if let thread = obj["thread"] as? String {
                announce(thread)
                service.noteComposerActivity(in: thread)
            }
        default:
            err("bad_cmd")
        }
    }

    // MARK: schema builders (MIRROR-PROTOCOL.md §5 + MIRROR-GROUPS.md §1)

    /// 1:1 contact threads: not blocked, newest activity first. Group
    /// threads are keyed by the group id — never a contact hash — and come
    /// from `mirrorableGroups()`.
    func mirrorableContacts() -> [Contact] {
        service.contacts
            .filter { !service.isBlocked($0.hash) && !service.isGroupThread($0.hash) }
            .sorted { lastTs($0.hash, fallback: $0.addedAt) > lastTs($1.hash, fallback: $1.addedAt) }
    }

    /// Group channels the browser may see: every group this phone is still
    /// in, newest activity first. A left / removed group is never listed
    /// (it produces `group_rm` the moment it goes); a burned group has
    /// already vanished from `service.groups`.
    func mirrorableGroups() -> [Group] {
        service.groups
            .filter { !$0.isLeft }
            .sorted { lastTs($0.id, fallback: $0.createdAt) > lastTs($1.id, fallback: $1.createdAt) }
    }

    private func lastTs(_ thread: String, fallback: Date) -> Int {
        Int((service.messages[thread]?.last?.timestamp ?? fallback).timeIntervalSince1970 * 1000)
    }

    private func contactJSON(_ c: Contact) -> [String: Any] {
        ["hash": c.hash, "name": c.name, "verified": c.verified,
         "lastTs": lastTs(c.hash, fallback: c.addedAt)]
    }

    /// `Group` (MIRROR-GROUPS.md §1): id, name without the `#`, the roster
    /// with the phone's own per-member colour (GROUPS.md §5, already dimmed
    /// by lap — the page never recomputes join order), `gone` only when
    /// true, `lastTs` and the screenshot policy. `left` only when true — and
    /// a left group is never mirrored, so it never actually appears.
    func groupJSON(_ g: Group) -> [String: Any] {
        let members: [[String: Any]] = g.members.map { m in
            var d: [String: Any] = [
                "hash": m.hash,
                "name": service.displayName(for: m.hash, in: g),
                "color": (GroupMath.colour(of: m.hash, in: g) ?? GroupMath.colour(joinIndex: 0)).hex,
            ]
            if m.isGone { d["gone"] = true }
            return d
        }
        var d: [String: Any] = [
            "id": g.id,
            "name": g.name,
            "members": members,
            "lastTs": lastTs(g.id, fallback: g.createdAt),
            "policy": g.policy.rawValue,
        ]
        if g.isLeft { d["left"] = true }
        return d
    }

    /// What a `group` upsert re-sends on: any roster / name / policy change
    /// — everything in `groupJSON` except `lastTs`.
    private func groupStamp(_ g: Group) -> String {
        var d = groupJSON(g)
        d["lastTs"] = nil
        let data = (try? JSONSerialization.data(withJSONObject: d, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    /// `who` for a group typing event: the members typing right now, in
    /// roster order, at most `maxTypingNames` (the page adds `… and N more`
    /// from nothing — it only ever sees up to three).
    func typingWho(in g: Group) -> [String] {
        Array(service.typingNames(in: g).prefix(Self.maxTypingNames))
    }

    /// `Row`. Pass the group for a group thread: incoming rows then carry
    /// `sender` (hash, roster name, the phone's own colour — omitted for a
    /// sender who was never on the roster, whom the phone shows as
    /// `<name>?`), own rows `receipts` (delivered / read / total = members
    /// minus me) while `status` stays the aggregate word. System rows cross
    /// with the phone's own text. Burn rows are content-free everywhere.
    func row(from m: ChatMessage, group g: Group? = nil) -> [String: Any] {
        // FLAGSHIP GUARANTEE: burn-after content never crosses to a browser —
        // in a group not even who wrote it or who has read it
        if m.burnAfter != nil {
            return ["id": m.id, "dir": m.direction == .incoming ? "in" : "out",
                    "kind": "burn", "ts": Int(m.timestamp.timeIntervalSince1970 * 1000),
                    "status": m.status.rawValue]
        }
        var r: [String: Any] = [
            "id": m.id,
            "dir": m.direction == .incoming ? "in" : "out",
            "kind": m.kind.rawValue,
            "ts": Int(m.timestamp.timeIntervalSince1970 * 1000),
            "status": m.status.rawValue,
        ]
        if m.kind == .text || m.kind == .system { r["text"] = m.text }
        if m.viaWeb == true { r["via"] = "web" }
        if let w = m.imageWidth { r["w"] = w }
        if let h = m.imageHeight { r["h"] = h }
        if m.kind == .image { r["th"] = thumbSent.contains(m.id) ? "sent" : "pending" }
        if let f = m.fileName { r["fileName"] = f }
        if let b = m.fileBytes { r["fileBytes"] = b }
        if let mm = m.fileMime { r["mime"] = mm }
        if let d = m.voiceDuration { r["duration"] = d }
        if let g {
            if m.direction == .incoming, let s = m.sender {
                var sender: [String: Any] = ["hash": s, "name": service.displayName(for: s, in: g)]
                if let c = GroupMath.colour(of: s, in: g) { sender["color"] = c.hex }
                r["sender"] = sender
            } else if m.direction == .outgoing, m.kind != .system {
                let receipts = m.receipts ?? [:]
                r["receipts"] = [
                    "delivered": receipts.count,
                    "read": receipts.values.filter { $0 == "read" }.count,
                    "total": max(g.members.count - 1, 0),
                ]
            }
        }
        return r
    }

    private func stamp(from m: ChatMessage) -> MirrorRowStamp {
        let receipts = m.receipts ?? [:]
        return MirrorRowStamp(id: m.id, status: m.status, isBurn: m.burnAfter != nil,
                              delivered: receipts.count,
                              read: receipts.values.filter { $0 == "read" }.count)
    }

    private func window(_ thread: String) -> [ChatMessage] {
        Array((service.messages[thread] ?? []).suffix(Self.rowsPerThread))
    }

    // MARK: snapshot + diffing

    func sendSnapshot() {
        let contacts = mirrorableContacts()
        let groups = mirrorableGroups()
        emit(["e": "snapshot_begin",
              "contacts": contacts.map { contactJSON($0) },
              "groups": groups.map { groupJSON($0) }])
        for c in contacts.prefix(Self.maxThreads) {
            emitThread(c.hash, group: nil)
            shadowContacts[c.hash] = (c.name, c.verified)
        }
        for c in contacts.dropFirst(Self.maxThreads) {
            shadowContacts[c.hash] = (c.name, c.verified)
        }
        for g in groups.prefix(Self.maxThreads) {
            emitThread(g.id, group: g)
            shadowGroups[g.id] = groupStamp(g)
        }
        for g in groups.dropFirst(Self.maxThreads) {
            shadowGroups[g.id] = groupStamp(g)
        }
        shadowConn = service.connState == .online
        emit(["e": "conn", "up": shadowConn])
        emit(["e": "snapshot_end"])
        sendThumbs()
    }

    /// One thread's window as `thread` batches packed under the plaintext
    /// cap, then remembered in the shadow.
    private func emitThread(_ thread: String, group: Group?) {
        let now = window(thread)
        var batch: [[String: Any]] = []
        var size = 0
        for r in now.map({ row(from: $0, group: group) }) {
            let rSize = (try? JSONSerialization.data(withJSONObject: r))?.count ?? 200
            if size + rSize > Self.plaintextCap - 200, !batch.isEmpty {
                emit(["e": "thread", "thread": thread, "rows": batch])
                batch = []; size = 0
            }
            batch.append(r); size += rSize
        }
        emit(["e": "thread", "thread": thread, "rows": batch])
        shadow[thread] = now.map { stamp(from: $0) }
    }

    /// Threads whose rows (and thumbs) the browser follows, in order.
    private func mirroredThreads() -> [(thread: String, group: Group?)] {
        mirrorableContacts().prefix(Self.maxThreads).map { ($0.hash, nil) }
            + mirrorableGroups().prefix(Self.maxThreads).map { ($0.id, $0) }
    }

    private func sendThumbs() {
        for t in mirroredThreads() {
            for m in window(t.thread).suffix(20)
            where m.kind == .image && m.burnAfter == nil && !thumbSent.contains(m.id) {
                guard thumbBudget > 0 else { return }
                thumbSent.insert(m.id)
                guard let data = service.attachmentData(for: m),
                      let norm = ImageNormalizer.normalize(data, maxDimension: 256,
                                                           quality: 0.5),
                      norm.jpeg.count <= 24_000 else { continue }
                thumbBudget -= norm.jpeg.count
                emit(["e": "thumb", "thread": t.thread, "id": m.id,
                      "jpeg": norm.jpeg.base64EncodedString()])
            }
        }
    }

    private func attachDiffing() {
        diffCancellable = service.objectWillChange
            .debounce(for: .milliseconds(400), scheduler: RunLoop.main)
            .sink { [weak self] _ in self?.tick() }
    }

    func tick() {
        guard !stopped, live else { return }
        // contacts diff
        let current = mirrorableContacts()
        let currentMap = Dictionary(uniqueKeysWithValues: current.map {
            ($0.hash, (name: $0.name, verified: $0.verified))
        })
        for (hash, old) in shadowContacts where currentMap[hash] == nil {
            _ = old
            emit(["e": "contact_rm", "hash": hash])
            emit(["e": "thread_rm", "thread": hash])
            shadowContacts[hash] = nil
            shadow[hash] = nil
            announced.remove(hash)
        }
        for c in current {
            let prev = shadowContacts[c.hash]
            if prev == nil || prev! != (c.name, c.verified) {
                var ev = contactJSON(c)
                ev["e"] = "contact"
                emit(ev)
                shadowContacts[c.hash] = (c.name, c.verified)
            }
        }
        // groups diff: gone (left / removed / burned) → group_rm, the thread
        // goes with it; any roster / name / policy change → group upsert
        let groupsNow = mirrorableGroups()
        let groupIds = Set(groupsNow.map(\.id))
        for gid in shadowGroups.keys where !groupIds.contains(gid) {
            emit(["e": "group_rm", "id": gid])
            shadowGroups[gid] = nil
            shadow[gid] = nil
            shadowGroupTyping[gid] = nil
            pendingTempIds[gid] = nil
            announced.remove(gid)
        }
        for g in groupsNow {
            let s = groupStamp(g)
            if shadowGroups[g.id] != s {
                var ev = groupJSON(g)
                ev["e"] = "group"
                emit(ev)
                shadowGroups[g.id] = s
            }
        }
        // per-thread row diff (only threads we snapshotted or that got rows)
        for t in mirroredThreads() {
            diffThread(t.thread, group: t.group)
        }
        // typing diff — 1:1 on/off, groups with `who`
        let typingNow = Set(service.typingPeers.keys)
        for t in typingNow.subtracting(shadowTyping) {
            emit(["e": "typing", "thread": t, "on": true])
        }
        for t in shadowTyping.subtracting(typingNow) {
            emit(["e": "typing", "thread": t, "on": false])
        }
        shadowTyping = typingNow
        for g in groupsNow {
            let who = typingWho(in: g)
            if who != (shadowGroupTyping[g.id] ?? []) {
                emit(["e": "typing", "thread": g.id, "on": !who.isEmpty, "who": who])
                shadowGroupTyping[g.id] = who.isEmpty ? nil : who
            }
        }
        // conn diff
        let up = service.connState == .online
        if up != shadowConn {
            shadowConn = up
            emit(["e": "conn", "up": up])
        }
        sendThumbs()
    }

    private func diffThread(_ thread: String, group: Group?) {
        let now = window(thread)
        let nowStamps = now.map { stamp(from: $0) }
        let old = shadow[thread] ?? []
        if old == nowStamps { return }
        let oldIds = Set(old.map(\.id))
        let nowIds = Set(nowStamps.map(\.id))
        if now.isEmpty && !old.isEmpty {
            emit(["e": "thread_rm", "thread": thread])
        } else {
            for s in old where !nowIds.contains(s.id) {
                emit(["e": "msg_rm", "thread": thread, "id": s.id])
            }
            let oldById = Dictionary(uniqueKeysWithValues: old.map { ($0.id, $0) })
            for m in now {
                let s = stamp(from: m)
                if !oldIds.contains(m.id) {
                    var r = row(from: m, group: group)
                    if m.direction == .outgoing,
                       var temps = pendingTempIds[thread], !temps.isEmpty,
                       m.timestamp >= temps[0].since.addingTimeInterval(-1) {
                        r["tempId"] = temps.removeFirst().id
                        pendingTempIds[thread] = temps
                    }
                    emit(["e": "msg", "thread": thread, "row": r])
                } else if oldById[m.id] != s {
                    emit(["e": "msg", "thread": thread, "row": row(from: m, group: group)])
                }
            }
        }
        shadow[thread] = nowStamps
    }
}
