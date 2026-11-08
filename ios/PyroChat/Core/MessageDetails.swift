import Foundation

// docs/MESSAGE-DETAILS.md §2 — the `details` sheet, as DATA.
//
// A pure function from (message, optional captured frame, group roster) to the
// sheet's lines. No SwiftUI, no ChatService, no SecureStore, no CryptoEngine:
// `Input` is the entire surface, and there is nowhere in it to put a session
// key, the store key or another message's plaintext. The view is a dumb
// renderer over `[Line]`, and the tests drive this file directly.
//
// It also never emits the message's OWN words: a details sheet is metadata
// about an envelope, not a second copy of the conversation.

enum MessageDetails {

    // MARK: output

    struct Line: Equatable {
        enum Kind: Equatable {
            case heading       // ── what happened ──
            case row           // a label + its value
            case memberRow     // one group member's receipt, in their colour
            case prose         // a sentence
            case frame         // one line of the JSON envelope
            case footer
        }
        var kind: Kind
        var label: String = ""
        var value: String
        /// Set on `.memberRow` only: that member's colour (GROUPS.md §5).
        var colour: GroupMath.RGB? = nil

        /// The line as one string — the label column is 14 wide, as in the
        /// spec's blocks (`sent          14:32:07`).
        var text: String {
            guard kind == .row || kind == .memberRow else { return value }
            return label + String(repeating: " ", count: max(1, Self.labelWidth - label.count)) + value
        }

        static let labelWidth = 14
    }

    // MARK: input

    struct Input {
        /// The row the sheet was opened on.
        var message: ChatMessage
        /// Its envelope, if the capture buffer still has one (§3).
        var frame: CapturedFrame?
        /// The group this row belongs to; nil for a 1:1 row.
        var group: Group?
        /// Display name of the 1:1 peer (no angle brackets).
        var peerName: String = "peer"
        /// The 1:1 peer's address — the `to` of an envelope we no longer have.
        var peerHash: String = ""
        /// My own address, so the group roster can leave me out.
        var myHash: String = ""
        /// Reciprocity: READ renders only when my read receipts are on.
        var showRead: Bool = true
        var timeZone: TimeZone = .current

        init(message: ChatMessage, frame: CapturedFrame? = nil, group: Group? = nil,
             peerName: String = "peer", peerHash: String = "", myHash: String = "",
             showRead: Bool = true, timeZone: TimeZone = .current) {
            self.message = message
            self.frame = frame
            self.group = group
            self.peerName = peerName
            self.peerHash = peerHash
            self.myHash = myHash
            self.showRead = showRead
            self.timeZone = timeZone
        }
    }

    // MARK: verbatim strings (§2)

    static let headingWhatHappened = "\u{2500}\u{2500} what happened \u{2500}\u{2500}"
    static let headingWhatItIs = "\u{2500}\u{2500} what it is \u{2500}\u{2500}"
    static let headingWhatTheRelayReceived = "\u{2500}\u{2500} what the relay received \u{2500}\u{2500}"
    /// docs/SCRUB-AND-WIPE.md §B2 — directly after `── what it is ──`.
    static let headingWhatWasRemoved =
        "\u{2500}\u{2500} what was removed before sending \u{2500}\u{2500}"
    static let headingWhatItCanWorkOut = "\u{2500}\u{2500} what it can still work out \u{2500}\u{2500}"
    static let headingWhatItCannot = "\u{2500}\u{2500} what it cannot \u{2500}\u{2500}"
    /// §B2, for a type we cannot clean. Never claim a file was cleaned when
    /// it was not.
    static let nothingRemovable = "nothing \u{2014} this file type is not one we can clean"
    /// §B4, verbatim: what this feature does NOT do.
    static let scrubFootnote = """
        this cleans the file's hidden fields, not its contents: a name written inside the document, \
        or a face in a photo, is still there.
        """
    /// §3: the absence, said out loud.
    static let bodyNotKept = "body: not kept \u{2014} PyroChat never stores ciphertext"
    /// The ttl every message envelope carries (ChatService).
    static let defaultTTL = 172_800

    // MARK: the sheet

    static func lines(_ input: Input) -> [Line] {
        whatHappened(input) + whatItIs(input) + whatWasRemoved(input)
            + whatTheRelayReceived(input) + whatItCanWorkOut(input)
    }

    /// Convenience for tests and for anything that wants the sheet as text.
    static func plainText(_ input: Input) -> [String] { lines(input).map(\.text) }

    // MARK: ── what happened ──

    static func whatHappened(_ input: Input) -> [Line] {
        let m = input.message
        var out: [Line] = [Line(kind: .heading, value: headingWhatHappened)]
        // No per-status timestamps exist in the store, and none are invented:
        // the one stamp a row carries is when it was sent (ours) or when it
        // landed here (theirs).
        out.append(Line(kind: .row, label: m.direction == .outgoing ? "sent" : "received",
                        value: time(m.timestamp, in: input.timeZone)))
        guard m.direction == .outgoing, m.kind != .system else { return out }
        out.append(Line(kind: .row, label: "status", value: statusValue(input)))
        guard let g = input.group else { return out }
        let receipts = m.receipts ?? [:]
        for member in roster(of: g, excluding: input.myHash) {
            out.append(Line(kind: .memberRow, label: "<\(member.name)>",
                            value: receiptWord(receipts[member.hash], showRead: input.showRead),
                            colour: GroupMath.colour(of: member.hash, in: g)))
        }
        return out
    }

    /// Everyone a fan-out goes to, in join order — the order that fixes the
    /// colours (GROUPS.md §5).
    static func roster(of g: Group, excluding myHash: String) -> [Member] {
        GroupMath.joinOrder(members: g.members, former: g.former)
            .compactMap { g.member($0) }
            .filter { $0.hash != myHash && !$0.isGone }
    }

    static func statusValue(_ input: Input) -> String {
        let m = input.message
        if let g = input.group {
            return GroupMath.statusWord(status: m.status, receipts: m.receipts ?? [:],
                                        recipients: roster(of: g, excluding: input.myHash).count,
                                        showRead: input.showRead)
        }
        return statusWord(m.status, showRead: input.showRead)
    }

    /// The 1:1 status word. Reciprocity: READ renders only when MY
    /// read-receipts toggle is on; otherwise it reads DELIVERED (the upgrade
    /// is still recorded underneath).
    static func statusWord(_ s: ChatMessage.Status, showRead: Bool) -> String {
        switch s {
        case .pending: return "\u{2026}"
        case .sent: return "SENT"
        case .delivered: return "DELIVERED"
        case .read: return showRead ? "READ" : "DELIVERED"
        case .failed: return "FAILED"
        }
    }

    /// One member's line: `delivered`, `read` or `—`, from the receipts
    /// already stored. Nothing else exists to show.
    static func receiptWord(_ raw: String?, showRead: Bool) -> String {
        switch raw {
        case "read": return showRead ? "read" : "delivered"
        case .some: return "delivered"
        case nil: return "\u{2014}"
        }
    }

    // MARK: ── what it is ──

    static func whatItIs(_ input: Input) -> [Line] {
        let m = input.message
        var out: [Line] = [Line(kind: .heading, value: headingWhatItIs)]
        out.append(Line(kind: .row, label: "kind", value: kindValue(m)))
        if let burn = m.burnAfter, burn > 0 {
            out.append(Line(kind: .row, label: "burns",
                            value: "\(duration(burn)) after you read it"))
        }
        if m.viaWeb == true {
            out.append(Line(kind: .row, label: "written on", value: "a linked desktop"))
        }
        return out
    }

    /// `text` / `voice · 4s` / `image 640x480` / `file report.pdf 42 KB` /
    /// `system`. Metadata only — never the words, never the file's contents.
    static func kindValue(_ m: ChatMessage) -> String {
        switch m.kind {
        case .text: return "text"
        case .system: return "system"
        case .voice:
            guard let d = m.voiceDuration, d > 0 else { return "voice" }
            return "voice \u{00B7} \(Int(d.rounded()))s"
        case .image:
            guard let w = m.imageWidth, let h = m.imageHeight, w > 0, h > 0 else { return "image" }
            return "image \(w)x\(h)"
        case .file:
            let name = m.fileName ?? "file"
            guard let bytes = m.fileBytes, bytes > 0 else { return "file \(name)" }
            return "file \(name) \(byteSize(bytes))"
        }
    }

    /// `42 KB` — the spec's spelling, not the log row's compact `42K`.
    static func byteSize(_ bytes: Int) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        if bytes < 1024 * 1024 { return "\(bytes / 1024) KB" }
        return String(format: "%.1f MB", Double(bytes) / (1024 * 1024))
    }

    /// `5s` / `5m` / `1h` / `2d`, with the remainder only when there is one.
    static func duration(_ seconds: TimeInterval) -> String {
        let n = max(0, Int(seconds.rounded()))
        if n < 60 { return "\(n)s" }
        if n < 3_600 { return n % 60 == 0 ? "\(n / 60)m" : "\(n / 60)m\(n % 60)s" }
        if n < 86_400 {
            return n % 3_600 == 0 ? "\(n / 3_600)h" : "\(n / 3_600)h\(n % 3_600 / 60)m"
        }
        return n % 86_400 == 0 ? "\(n / 86_400)d" : "\(n / 86_400)d\(n % 86_400 / 3_600)h"
    }

    // MARK: ── what was removed before sending ── (docs/SCRUB-AND-WIPE.md §B2)

    /// The receipt. Pure, like every other section: it renders what the row
    /// RECORDED at send time and never inspects a file. Absent entirely when
    /// nothing was removed from a type we can clean — a section claiming a
    /// clean file was cleaned would be the one dishonesty this sheet exists
    /// to rule out.
    static func whatWasRemoved(_ input: Input) -> [Line] {
        let removed = input.message.removedMetadata ?? []
        if !removed.isEmpty {
            var out: [Line] = [Line(kind: .heading, value: headingWhatWasRemoved)]
            out += removed.map { Line(kind: .row, label: $0.label, value: $0.value) }
            out.append(Line(kind: .footer, value: scrubFootnote))
            return out
        }
        guard input.message.scrubbable == false else { return [] }
        return [Line(kind: .heading, value: headingWhatWasRemoved),
                Line(kind: .prose, value: nothingRemovable),
                Line(kind: .footer, value: scrubFootnote)]
    }

    // MARK: ── what the relay received ──

    static func whatTheRelayReceived(_ input: Input) -> [Line] {
        let m = input.message
        var out: [Line] = [Line(kind: .heading, value: headingWhatTheRelayReceived)]
        // A system row is written by this device. There was no envelope, and
        // inventing one would be the exact dishonesty this sheet exists to
        // rule out.
        guard m.kind != .system else {
            out.append(Line(kind: .prose, value: systemRowNote))
            return out
        }
        if let g = input.group, m.direction == .outgoing {
            let n = roster(of: g, excluding: input.myHash).count
            out.append(Line(kind: .prose, value: envelopeCountLine(n)))
        }
        out += frameLines(input)
        if let f = input.frame {
            out.append(Line(kind: .prose, value: "\(f.ciphertextBytes) bytes of ciphertext"))
        }
        return out
    }

    static let systemRowNote =
        "nothing \u{2014} this row was written by your own device; it was never an envelope."

    /// §2, verbatim, with the real recipient count.
    static func envelopeCountLine(_ n: Int) -> String {
        "sent as \(n) separate envelopes, one per member \u{2014} each is different ciphertext, "
        + "and the relay is never told they belong together."
    }

    /// The envelope, wrapped the way the spec prints it. With a capture it is
    /// the real frame; without one the fields still known, and the body
    /// replaced by the truth about why it is gone (§3).
    static func frameLines(_ input: Input) -> [Line] {
        let m = input.message
        let outgoing = m.direction == .outgoing
        let t = input.frame?.t ?? (outgoing ? "send" : "recv")
        let id = input.frame?.id ?? m.id
        let peer = input.frame?.peer ?? fallbackPeerHash(input)
        let kind = input.frame?.kind ?? (m.kind == .text ? "msg" : "blob")
        let outbound = t == "send" || t == "deliver"
        let ttl = outbound ? (input.frame?.ttl ?? defaultTTL) : nil

        var first = "{\"t\":\"\(t)\",\"id\":\"\(elide(id, head: 8))\","
        if outbound {
            first += "\"to\":\"\(elideMiddle(peer))\","
        } else if !(input.frame?.peer.isEmpty ?? false) {
            // a sealed frame arrives with no `from`: the line shows it as it came
            first += "\"from\":\"\(elideMiddle(peer))\","
        }
        var second = " "
        if let ttl { second += "\"ttl\":\(ttl)," }
        second += "\"kind\":\"\(kind)\","
        guard let body = input.frame?.body else {
            // the envelope closes properly; the absence is stated underneath it, the way the
            // byte count is when a frame WAS captured. A malformed-looking object on the one
            // screen that exists to be believed would undo the point of the screen.
            return [Line(kind: .frame, value: first),
                    Line(kind: .frame, value: String(second.dropLast()) + "}"),
                    Line(kind: .prose, value: bodyNotKept)]
        }
        second += "\"body\":\"\(elide(body, head: 9))\"}"
        return [Line(kind: .frame, value: first), Line(kind: .frame, value: second)]
    }

    /// The address the envelope carried, when the envelope itself is gone.
    static func fallbackPeerHash(_ input: Input) -> String {
        let m = input.message
        if m.direction == .incoming { return m.sender ?? input.peerHash }
        if let g = input.group {
            return roster(of: g, excluding: input.myHash).first?.hash ?? input.peerHash
        }
        return input.peerHash
    }

    /// `7f3c9a12…` — a prefix and an ellipsis.
    static func elide(_ s: String, head: Int) -> String {
        s.count <= head ? s : String(s.prefix(head)) + "\u{2026}"
    }

    /// `9a1fd4…c2` — an address, recognisable at both ends and readable.
    static func elideMiddle(_ s: String, head: Int = 6, tail: Int = 2) -> String {
        s.count <= head + tail + 1 ? s : String(s.prefix(head)) + "\u{2026}" + String(s.suffix(tail))
    }

    // MARK: ── what it can still work out ── / ── what it cannot ──

    static func whatItCanWorkOut(_ input: Input) -> [Line] {
        [Line(kind: .heading, value: headingWhatItCanWorkOut),
         Line(kind: .prose, value: canLine(input)),
         Line(kind: .heading, value: headingWhatItCannot),
         Line(kind: .prose, value: cannotLine(input))]
    }

    static func canLine(_ input: Input) -> String {
        let m = input.message
        guard m.kind != .system else { return "nothing \u{2014} this row never went to the relay." }
        let who = counterpartyName(input)
        return m.direction == .outgoing
            ? "your address, the time, the size, and that you wrote to <\(who)>"
            : "your address, the time, the size, and that <\(who)> wrote to you"
    }

    static func cannotLine(_ input: Input) -> String {
        let m = input.message
        guard m.kind != .system else { return "anything \u{2014} it never left this device." }
        if let g = input.group {
            return "the words, your name, the burn timer, or that #\(g.name) exists"
        }
        return "the words, your name, the burn timer, or whether this is part of a group"
    }

    /// Who the SHOWN envelope is between — for a group that is the one member
    /// whose copy is on screen, never the group.
    static func counterpartyName(_ input: Input) -> String {
        let m = input.message
        guard let g = input.group else { return input.peerName }
        if m.direction == .incoming {
            return m.sender.flatMap { g.knownName($0) } ?? input.peerName
        }
        let shown = input.frame?.peer
        if let shown, let name = g.knownName(shown) { return name }
        return roster(of: g, excluding: input.myHash).first?.name ?? input.peerName
    }

    // MARK: time

    static func time(_ date: Date, in zone: TimeZone) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = zone
        f.dateFormat = "HH:mm:ss"
        return f.string(from: date)
    }
}
