import Foundation

// Group channels — docs/GROUPS.md. Everything in this file is PURE: the
// persisted models (§3), the colour rule (§5) and the roster state machine
// (§2 + §6) as a reducer over `GroupChange`. ChatService feeds it decrypted
// controls and applies the `Outcome`; the tests drive it directly.

// MARK: - persisted models (§3)

/// Screenshot policy of a group (§1/§6). Stored and sent as the raw string.
enum GroupPolicy: String, Codable, CaseIterable {
    case alert
    case burn
    case burnRemove = "burn_remove"

    /// The picker words (§7).
    var label: String {
        switch self {
        case .alert: return "alert"
        case .burn: return "burn for the screenshotter"
        case .burnRemove: return "burn and remove"
        }
    }

    /// An unknown word from a newer build reads as `alert` — never burn
    /// anything on a policy we don't understand.
    init(lenient raw: String?) {
        self = raw.flatMap(GroupPolicy.init(rawValue:)) ?? .alert
    }
}

struct Member: Codable, Hashable {
    let hash: String
    var name: String
    var joinedAt: Date
    /// iOS-only optional (omitted when nil): the relay answered `unknown_user`
    /// for this member (§4) — skipped by every fan-out until re-added.
    var gone: Bool? = nil

    var isGone: Bool { gone ?? false }
}

extension Member: Identifiable {
    var id: String { hash }
}

extension Member {
    private enum CodingKeys: String, CodingKey { case hash, name, joinedAt, gone }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        hash = try c.decode(String.self, forKey: .hash)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? "anon-" + String(hash.prefix(8))
        joinedAt = try c.decodeIfPresent(Date.self, forKey: .joinedAt) ?? Date(timeIntervalSinceReferenceDate: 0)
        gone = try c.decodeIfPresent(Bool.self, forKey: .gone)
    }
}

struct Group: Codable, Identifiable, Hashable {
    let id: String
    var name: String              // without the `#`
    let creator: String           // hash; fixed for the group's life
    let createdAt: Date
    var epoch: Int
    var policy: GroupPolicy
    var burnSeconds: TimeInterval?
    var members: [Member]
    /// We were removed / left / burned out: read-only thread.
    var left: Bool? = nil
    /// iOS-only optional (omitted when nil): members who left or were removed,
    /// with their ORIGINAL joinedAt — so colour indices never shift (§5) and
    /// their old rows keep a name.
    var former: [Member]? = nil

    var isLeft: Bool { left ?? false }
    func isCreator(_ hash: String) -> Bool { creator == hash }
    func isMember(_ hash: String) -> Bool { members.contains { $0.hash == hash } }
    func member(_ hash: String) -> Member? { members.first { $0.hash == hash } }
    /// Name for a hash from the roster, then from former members.
    func knownName(_ hash: String) -> String? {
        member(hash)?.name ?? former?.first { $0.hash == hash }?.name
    }
}

extension Group {
    private enum CodingKeys: String, CodingKey {
        case id, name, creator, createdAt, epoch, policy, burnSeconds, members, left, former
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        creator = try c.decodeIfPresent(String.self, forKey: .creator) ?? ""
        createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date(timeIntervalSinceReferenceDate: 0)
        epoch = try c.decodeIfPresent(Int.self, forKey: .epoch) ?? 0
        policy = GroupPolicy(lenient: try c.decodeIfPresent(String.self, forKey: .policy))
        burnSeconds = try c.decodeIfPresent(TimeInterval.self, forKey: .burnSeconds)
        members = try c.decodeIfPresent([Member].self, forKey: .members) ?? []
        left = try c.decodeIfPresent(Bool.self, forKey: .left)
        former = try c.decodeIfPresent([Member].self, forKey: .former)
    }
}

// MARK: - pure rules

/// A decrypted group control, already attributed to the pairwise session it
/// arrived on (`sender` is passed to the reducer, never read from a field).
enum GroupChange: Equatable {
    case create(Group, invite: Bool)   // group_create (invite: false) / group_invite (true)
    case add(Member)                   // group_add
    case remove(hash: String)          // group_remove
    case leave                         // group_leave
    case rename(String)                // group_rename
    case policy(GroupPolicy, burnSeconds: TimeInterval?)   // group_policy
    case burn                          // group_burn
    case screenshot                    // screenshot + group (§6, receiver side)
}

enum GroupMath {
    static let maxMembers = 20
    /// Contact-picker cap on the new-group screen: 19 + the creator.
    static let maxPicked = maxMembers - 1

    // MARK: names (§1)

    /// 1–32 graphemes, trimmed, without a leading `#`, no control characters.
    static func normalizeName(_ raw: String) -> String? {
        var s = String(raw.unicodeScalars.filter { $0.value >= 0x20 && $0.value != 0x7F })
        s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasPrefix("#") { s.removeFirst() }
        s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...32).contains(s.count) else { return nil }
        return s
    }

    // MARK: colours (§5)

    /// Index 0–9, hex — distinct on #050805, never alert #ff5940 / flame #ffaf32.
    static let palette: [UInt32] = [
        0x3ef573, 0x4fd8ff, 0xc792ff, 0xffd166, 0xff8fab,
        0x7ee8fa, 0xb5e550, 0xffa64d, 0xe0b3ff, 0x8be9fd,
    ]

    struct RGB: Equatable {
        var r: Double, g: Double, b: Double
        var hex: String {
            String(format: "#%02x%02x%02x", Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded()))
        }
    }

    /// Everyone who ever joined, ordered by (joinedAt, hash). A hash present
    /// twice (re-added later) keeps its EARLIEST join, so it keeps its colour.
    static func joinOrder(members: [Member], former: [Member]? = nil) -> [String] {
        var earliest: [String: Date] = [:]
        for m in members + (former ?? []) {
            if let e = earliest[m.hash], e <= m.joinedAt { continue }
            earliest[m.hash] = m.joinedAt
        }
        return earliest.sorted { ($0.value, $0.key) < ($1.value, $1.key) }.map(\.key)
    }

    static func joinIndex(of hash: String, in group: Group) -> Int? {
        joinOrder(members: group.members, former: group.former).firstIndex(of: hash)
    }

    /// `palette[i % 10]`, dimmed to 70 % for the second lap, 50 % from the third.
    static func colour(joinIndex i: Int) -> RGB {
        let hex = palette[i % palette.count]
        let lap = i / palette.count
        let dim: Double = lap == 0 ? 1 : (lap == 1 ? 0.7 : 0.5)
        return RGB(r: Double((hex >> 16) & 0xff) / 255 * dim,
                   g: Double((hex >> 8) & 0xff) / 255 * dim,
                   b: Double(hex & 0xff) / 255 * dim)
    }

    /// nil for a hash that never joined (rendered dim, no colour).
    static func colour(of hash: String, in group: Group) -> RGB? {
        joinIndex(of: hash, in: group).map(colour(joinIndex:))
    }

    // MARK: fan-out aggregation (§4)

    enum SendState: Equatable { case pending, sent, failed }

    /// Row status from the live per-recipient send states + the receipts map.
    /// failed if any recipient errored; pending while any is in flight; then
    /// read / delivered once EVERY recipient reported; otherwise sent. With no
    /// live states (after a relaunch) a persisted FAILED stays, and receipts
    /// can only upgrade.
    static func rowStatus(sendStates: [SendState], receipts: [String: String],
                          recipients: Int, previous: ChatMessage.Status) -> ChatMessage.Status {
        if sendStates.contains(.failed) { return .failed }
        if sendStates.contains(.pending) { return .pending }
        if sendStates.isEmpty && previous == .failed { return .failed }
        var fromReceipts: ChatMessage.Status = .sent
        if recipients > 0, receipts.count >= recipients {
            fromReceipts = receipts.values.allSatisfy { $0 == "read" } ? .read : .delivered
        }
        if previous == .pending || previous == .failed { return fromReceipts }
        return fromReceipts.receiptRank >= previous.receiptRank ? fromReceipts : previous
    }

    /// The status word on an own row: `READ 7/9`, `DELIVERED 4/9`, `SENT`,
    /// `FAILED`, `…`. Reciprocity as in 1:1 — READ only when my read-receipt
    /// toggle is on (a read then counts as delivered).
    static func statusWord(status: ChatMessage.Status, receipts: [String: String],
                           recipients: Int, showRead: Bool) -> String {
        switch status {
        case .pending: return "\u{2026}"
        case .failed: return "FAILED"
        default: break
        }
        let read = receipts.values.filter { $0 == "read" }.count
        let delivered = receipts.count
        if showRead, read > 0 { return "READ \(read)/\(recipients)" }
        if delivered > 0 { return "DELIVERED \(delivered)/\(recipients)" }
        return "SENT"
    }

    /// Who a retry re-sends to: only the recipients that errored while the
    /// bookkeeping is live; after a relaunch, everyone who never receipted.
    static func retryTargets(states: [String: SendState], recipients: [String],
                             receipts: [String: String]) -> [String] {
        if !states.isEmpty { return recipients.filter { states[$0] == .failed } }
        return recipients.filter { receipts[$0] == nil }
    }

    /// Receipt upgrade for one member: never backwards.
    static func mergedReceipt(existing: String?, incoming: String) -> String {
        if existing == "read" { return "read" }
        return incoming
    }

    // MARK: epochs + authority (§2)

    /// Last writer by (epoch, senderHash) wins — the concurrent-change rule.
    struct Stamp: Hashable, Comparable {
        var epoch: Int
        var writer: String
        static func < (a: Stamp, b: Stamp) -> Bool { (a.epoch, a.writer) < (b.epoch, b.writer) }
    }

    static func wins(_ incoming: Stamp, over current: Stamp?) -> Bool {
        guard let current else { return true }
        return incoming > current
    }

    struct Outcome: Equatable {
        var group: Group?              // resulting state; nil = destroyed (or never existed)
        var applied = false
        var rows: [String] = []        // system rows for the thread
        var debugRows: [String] = []   // DEBUG-build-only rows
        var flash: String?             // home-screen flash (§6b)
        var stamps: [String: Stamp] = [:]   // per-member LWW stamps to remember
        var wipeThread = false         // rows + sidecars are gone
    }

    /// The roster state machine. `sender` = the identity whose session the
    /// control arrived on. `stamps` = per-member (epoch, writer) of the last
    /// applied roster change, RAM-only in the service.
    static func apply(_ change: GroupChange, epoch remoteEpoch: Int, from sender: String,
                      myHash: String, to current: Group?,
                      stamps: [String: Stamp] = [:]) -> Outcome {
        var out = Outcome(group: current)
        switch change {
        case .create(let g, let invite):
            // authority: create ⇒ the sender IS the creator; invite ⇒ the
            // sender is on the roster it carries (any member may add)
            let authorised = invite ? g.isMember(sender) : (g.creator == sender && g.isMember(sender))
            guard authorised, g.isMember(myHash),
                  (1...maxMembers).contains(g.members.count),
                  normalizeName(g.name) != nil else { return out }
            if let cur = current, !cur.isLeft { return out }          // idempotent by id
            var fresh = g
            fresh.left = nil
            if let cur = current {                                     // re-join: keep colour history
                var hist = cur.former ?? []
                for m in cur.members where !hist.contains(where: { $0.hash == m.hash }) { hist.append(m) }
                fresh.former = hist.isEmpty ? nil : hist
            }
            out.group = fresh
            out.applied = true

        case .add(let m):
            guard var g = current, !g.isLeft, g.isMember(sender) else { return out }
            let stamp = Stamp(epoch: remoteEpoch, writer: sender)
            guard wins(stamp, over: stamps[m.hash]) else { return out }
            out.stamps[m.hash] = stamp
            if let i = g.members.firstIndex(where: { $0.hash == m.hash }) {
                if g.members[i].isGone { g.members[i].gone = nil; out.group = g; out.applied = true }
                return out                                             // already on the roster
            }
            guard g.members.count < maxMembers else { return out }
            g.members.append(m)
            g.epoch = max(g.epoch + 1, remoteEpoch)
            out.group = g
            out.applied = true
            out.rows = ["── <\(m.name)> joined ──"]

        case .remove(let hash):
            guard var g = current, !g.isLeft else { return out }
            guard sender == g.creator, g.isMember(sender) else {
                out.debugRows = ["── ignored a removal from <\(g.knownName(sender) ?? "anon-" + String(sender.prefix(8)))> (not the creator) ──"]
                return out
            }
            let stamp = Stamp(epoch: remoteEpoch, writer: sender)
            guard wins(stamp, over: stamps[hash]) else { return out }
            out.stamps[hash] = stamp
            guard let m = g.member(hash) else { return out }
            retire(m, from: &g)
            g.epoch = max(g.epoch + 1, remoteEpoch)
            if hash == myHash { g.left = true }
            out.group = g
            out.applied = true
            out.rows = ["── <\(m.name)> was removed ──"]

        case .leave:
            guard var g = current, !g.isLeft, let m = g.member(sender) else { return out }
            let stamp = Stamp(epoch: remoteEpoch, writer: sender)
            guard wins(stamp, over: stamps[sender]) else { return out }
            out.stamps[sender] = stamp
            retire(m, from: &g)
            g.epoch = max(g.epoch + 1, remoteEpoch)
            out.group = g
            out.applied = true
            out.rows = ["── <\(m.name)> left ──"]

        case .rename(let raw):
            guard var g = current, !g.isLeft, sender == g.creator, g.isMember(sender),
                  let name = normalizeName(raw) else { return out }
            g.name = name
            g.epoch = max(g.epoch + 1, remoteEpoch)
            out.group = g
            out.applied = true
            out.rows = ["── renamed to #\(name) ──"]

        case .policy(let policy, let burn):
            guard var g = current, !g.isLeft, sender == g.creator, g.isMember(sender) else { return out }
            g.policy = policy
            g.burnSeconds = burn
            g.epoch = max(g.epoch + 1, remoteEpoch)
            out.group = g
            out.applied = true
            out.rows = ["── screenshot policy: \(policy.rawValue) ──"]

        case .burn:
            // the creator destroys it everywhere — even on a phone that was
            // already removed (its read-only copy goes too)
            guard let g = current, sender == g.creator, g.isMember(sender) || g.isLeft else { return out }
            out.group = nil
            out.applied = true
            out.wipeThread = true
            out.flash = "#\(g.name) was burned by the creator"

        case .screenshot:
            guard var g = current, !g.isLeft, let m = g.member(sender) else { return out }
            out.rows = ["\u{26A0} <\(m.name)> took a screenshot"]
            if g.policy == .burnRemove {
                // every other phone applies the same rule — no control needed
                retire(m, from: &g)
                g.epoch += 1
                out.stamps[sender] = Stamp(epoch: g.epoch, writer: sender)
                out.rows.append("── <\(m.name)> was removed for taking a screenshot ──")
            }
            out.group = g
            out.applied = true
        }
        return out
    }

    /// Off the roster, into history (keeps the earliest join for colours).
    static func retire(_ m: Member, from g: inout Group) {
        g.members.removeAll { $0.hash == m.hash }
        var hist = g.former ?? []
        if !hist.contains(where: { $0.hash == m.hash }) { hist.append(m) }
        g.former = hist
    }

    // MARK: typing strip (§1)

    /// `alice, bob are typing…` — max 3 names, then `… and N more`.
    static func typingLine(_ names: [String]) -> String? {
        guard !names.isEmpty else { return nil }
        let shown = names.prefix(3)
        var s = shown.joined(separator: ", ")
        if names.count > 3 { s += " \u{2026} and \(names.count - 3) more" }
        s += names.count == 1 ? " is typing\u{2026}" : " are typing\u{2026}"
        return s
    }
}
