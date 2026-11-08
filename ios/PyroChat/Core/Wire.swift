import Foundation
import CryptoKit

/// JSON frames from PROTOCOL.md. One flexible struct per direction keeps the
/// Codable surface small; optional fields are simply absent on the wire.
enum Wire {
    struct PreKeyJSON: Codable {
        var id: UInt32
        var pub: String
        var sig: String?
    }

    // ---- client -> relay ----
    struct Out: Encodable {
        var t: String              // includes "report" (abuse: hash only, no content)
        var user: String?
        var auth_pub: String?
        var reg_id: UInt32?
        var identity_key: String?
        var signed_prekey: PreKeyJSON?
        var kyber_prekey: PreKeyJSON?
        var one_time_prekeys: [PreKeyJSON]?
        var sig: String?
        var token: String?
        var token_sig: String?
        var push_token: String?
        var pair: String?          // desktop-link pair channel
        var id: String?
        var to: String?
        var ttl: Int?
        var kind: String?
        var body: String?
        // --- sealed sender (docs/SEALED-SENDER.md §4) ---
        /// register / auth: base64 SHA-256 of my delivery key. Present = this build can
        /// receive sealed messages; the relay never sees the key itself.
        var delivery: String?
        /// deliver, key lane: the recipient's delivery key. Absent on the first-contact lane.
        var access: String?
    }

    // ---- relay -> client ----
    struct In: Decodable {
        var t: String
        var code: String?        // error
        var ref: String?
        var nonce: String?       // challenge
        var queued: Int?         // ready
        var n: Int?              // prekey_count
        // prekey_bundle
        var user: String?
        var reg_id: UInt32?
        var identity_key: String?
        var signed_prekey: PreKeyJSON?
        var kyber_prekey: PreKeyJSON?
        var one_time_prekey: PreKeyJSON?
        // recv / sent
        var id: String?
        var from: String?
        var kind: String?
        var body: String?
        var ts: Int64?
        var pair: String?          // pair_opened/joined/closed/msg
        var reason: String?        // pair_closed
        var ttl2: Int?             // unused; keep In flexible
        // notice (operator announcements; `ts` above doubles as its epoch-seconds stamp)
        var text: String?
        var url: String?
        var expires: Int64?        // epoch seconds
        // versions (update banner): the relay's newest build per platform.
        // Absent = that platform has nothing advertised (clears the banner).
        var ios: VersionJSON?
        var android: VersionJSON?
        // --- sealed sender (docs/SEALED-SENDER.md §4.1) ---
        /// ready: the relay offers sealed sender to this login. prekey_bundle (anonymous
        /// fetch): that account can receive sealed messages.
        var sealed: Bool?
        var cert: String?        // ready / cert: my sender certificate
        var bind: String?        // ready / cert: the relay's binding for my full hash
        var cert_ttl: Int?       // seconds the two last
    }

    /// One platform's entry in the `versions` frame (PROTOCOL.md "Latest
    /// versions"). Lenient on purpose: a wrong-typed field, or a value that
    /// is not an object at all, decodes to nil fields instead of failing the
    /// whole `In` decode — `UpdateCheck` then treats the entry as present
    /// but malformed and ignores the frame as a whole, which is what the
    /// spec asks for (a failed decode would look like "absent" and CLEAR).
    struct VersionJSON: Decodable, Equatable {
        var build: Int?
        var name: String?
        var url: String?
        init(build: Int? = nil, name: String? = nil, url: String? = nil) {
            self.build = build; self.name = name; self.url = url
        }
        private enum CodingKeys: String, CodingKey { case build, name, url }
        init(from decoder: Decoder) throws {
            guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
            build = try? c.decodeIfPresent(Int.self, forKey: .build)
            name = try? c.decodeIfPresent(String.self, forKey: .name)
            url = try? c.decodeIfPresent(String.self, forKey: .url)
        }
    }

    /// `group: {id, epoch}` on every group message and control (GROUPS.md §2).
    /// Decodes leniently: a malformed object never fails the whole payload.
    struct GroupRef: Codable, Equatable {
        var id: String?
        var epoch: Int?
        init(id: String, epoch: Int) { self.id = id; self.epoch = epoch }
        private enum CodingKeys: String, CodingKey { case id, epoch }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try? c.decodeIfPresent(String.self, forKey: .id)
            epoch = try? c.decodeIfPresent(Int.self, forKey: .epoch)
        }
    }

    /// Roster entry on the wire (`members[]` of create/invite, `member` of
    /// add/remove — remove carries only `hash`). Lenient like GroupRef.
    struct MemberJSON: Codable, Equatable {
        var hash: String?
        var name: String?
        var joinedAt: Date?
        /// The member's delivery key, when the writer holds it (docs/SEALED-SENDER.md §4.4):
        /// members who never spoke directly can then reach each other without the relay
        /// being told who writes.
        var dk: String?
        init(hash: String, name: String? = nil, joinedAt: Date? = nil, dk: String? = nil) {
            self.hash = hash; self.name = name; self.joinedAt = joinedAt; self.dk = dk
        }
        private enum CodingKeys: String, CodingKey { case hash, name, joinedAt, dk }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            hash = try? c.decodeIfPresent(String.self, forKey: .hash)
            name = try? c.decodeIfPresent(String.self, forKey: .name)
            joinedAt = try? c.decodeIfPresent(Date.self, forKey: .joinedAt)
            dk = try? c.decodeIfPresent(String.self, forKey: .dk)
        }
    }

    /// Inner payload that rides INSIDE the libsignal ciphertext. The relay
    /// never sees any of this. All-optional Codable ⇒ old clients decode new
    /// payloads (unknown kinds fall through to `default: break`).
    struct Payload: Codable {
        var kind: String          // "text" | "blob_manifest" | "control"
        var text: String?
        /// Sender's chosen username, rides INSIDE the ciphertext so the
        /// recipient can label the thread — the relay never sees it.
        var senderName: String?
        var burnAfter: TimeInterval?
        var control: String?      // "burn_thread" | "screenshot" | "screen_record" | "receipt" | "burn_start" | "typing" | "session_reset" | "web_link" | "delete_msg" | "group_*"
        /// text composed on a linked desktop browser ("web"); nil = phone
        var via: String?
        // web_link control: browser label + up/down
        var browser: String?
        var linkUp: Bool?
        /// Group messages: the shared ChatMessage id (the relay id is fresh
        /// per recipient — GROUPS.md §4); receipts refer to this id.
        var refId: String?
        // receipts + burn-start sync (control == "receipt" / "burn_start")
        var receipt: String?      // "delivered" | "read"
        var refIds: [String]?     // batch of message ids this control covers
        // blob manifest (subtype stays invisible to the relay)
        var subtype: String?      // "voice" | "image" | "file"
        var chunks: Int?
        var bytes: Int?           // TOTAL ciphertext bytes being chunked
        var duration: TimeInterval?   // voice
        var filename: String?         // file (sender-sanitized; receiver re-sanitizes)
        var mime: String?             // image/file
        var width: Int?               // image, pixels
        var height: Int?
        // --- group channels (GROUPS.md §2) ---
        var group: GroupRef?          // on every group message and control
        var name: String?             // group_create / group_invite / group_rename
        var creator: String?          // group_create / group_invite
        var members: [MemberJSON]?    // group_create / group_invite: full roster
        var member: MemberJSON?       // group_add (hash,name,joinedAt) / group_remove (hash)
        var policy: String?           // group_create / group_invite / group_policy
        var burnSeconds: TimeInterval?    // group default burn timer (nil = none)
        var createdAt: Date?          // group_create / group_invite
        // --- sealed sender (docs/SEALED-SENDER.md §4.4) ---
        /// My delivery key (b64, 32 bytes): on every payload to a peer until that peer echoes it.
        var dk: String?
        /// First 8 bytes of SHA-256 of the delivery key I hold for you (b64): on every payload
        /// while I hold one.
        var dkh: String?

        init(kind: String, text: String? = nil, senderName: String? = nil,
             burnAfter: TimeInterval? = nil, control: String? = nil, via: String? = nil,
             browser: String? = nil, linkUp: Bool? = nil, refId: String? = nil,
             receipt: String? = nil, refIds: [String]? = nil, subtype: String? = nil,
             chunks: Int? = nil, bytes: Int? = nil, duration: TimeInterval? = nil,
             filename: String? = nil, mime: String? = nil, width: Int? = nil, height: Int? = nil,
             group: GroupRef? = nil, name: String? = nil, creator: String? = nil,
             members: [MemberJSON]? = nil, member: MemberJSON? = nil, policy: String? = nil,
             burnSeconds: TimeInterval? = nil, createdAt: Date? = nil) {
            self.kind = kind; self.text = text; self.senderName = senderName
            self.burnAfter = burnAfter; self.control = control; self.via = via
            self.browser = browser; self.linkUp = linkUp; self.refId = refId
            self.receipt = receipt; self.refIds = refIds; self.subtype = subtype
            self.chunks = chunks; self.bytes = bytes; self.duration = duration
            self.filename = filename; self.mime = mime; self.width = width; self.height = height
            self.group = group; self.name = name; self.creator = creator
            self.members = members; self.member = member; self.policy = policy
            self.burnSeconds = burnSeconds; self.createdAt = createdAt
        }

        private enum CodingKeys: String, CodingKey {
            case kind, text, senderName, burnAfter, control, via, browser, linkUp, refId
            case receipt, refIds, subtype, chunks, bytes, duration, filename, mime, width, height
            case group, name, creator, members, member, policy, burnSeconds, createdAt
            case dk, dkh
        }

        /// The pre-group fields decode exactly as before; the group fields are
        /// lenient (`try?`) so a shape mismatch from the other platform drops
        /// the field, never the payload — a decode failure here would be
        /// mistaken for ratchet damage and archive the session.
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            kind = try c.decode(String.self, forKey: .kind)
            text = try c.decodeIfPresent(String.self, forKey: .text)
            senderName = try c.decodeIfPresent(String.self, forKey: .senderName)
            burnAfter = try c.decodeIfPresent(TimeInterval.self, forKey: .burnAfter)
            control = try c.decodeIfPresent(String.self, forKey: .control)
            via = try c.decodeIfPresent(String.self, forKey: .via)
            browser = try c.decodeIfPresent(String.self, forKey: .browser)
            linkUp = try c.decodeIfPresent(Bool.self, forKey: .linkUp)
            refId = try c.decodeIfPresent(String.self, forKey: .refId)
            receipt = try c.decodeIfPresent(String.self, forKey: .receipt)
            refIds = try c.decodeIfPresent([String].self, forKey: .refIds)
            subtype = try c.decodeIfPresent(String.self, forKey: .subtype)
            chunks = try c.decodeIfPresent(Int.self, forKey: .chunks)
            bytes = try c.decodeIfPresent(Int.self, forKey: .bytes)
            duration = try c.decodeIfPresent(TimeInterval.self, forKey: .duration)
            filename = try c.decodeIfPresent(String.self, forKey: .filename)
            mime = try c.decodeIfPresent(String.self, forKey: .mime)
            width = try c.decodeIfPresent(Int.self, forKey: .width)
            height = try c.decodeIfPresent(Int.self, forKey: .height)
            group = try? c.decodeIfPresent(GroupRef.self, forKey: .group)
            name = try? c.decodeIfPresent(String.self, forKey: .name)
            creator = try? c.decodeIfPresent(String.self, forKey: .creator)
            members = try? c.decodeIfPresent([MemberJSON].self, forKey: .members)
            member = try? c.decodeIfPresent(MemberJSON.self, forKey: .member)
            policy = try? c.decodeIfPresent(String.self, forKey: .policy)
            burnSeconds = try? c.decodeIfPresent(TimeInterval.self, forKey: .burnSeconds)
            createdAt = try? c.decodeIfPresent(Date.self, forKey: .createdAt)
            dk = try? c.decodeIfPresent(String.self, forKey: .dk)
            dkh = try? c.decodeIfPresent(String.self, forKey: .dkh)
        }
    }

    /// Envelope inside the relay `body` field: libsignal ciphertext + its type
    /// (preKey messages bootstrap sessions; whisper messages continue them).
    struct Envelope: Codable {
        var v: Int = 1
        var type: UInt8           // CiphertextMessage.MessageType rawValue
        var data: Data            // serialized ciphertext
    }
}

func sha256Hex(_ s: String) -> String {
    SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
}
