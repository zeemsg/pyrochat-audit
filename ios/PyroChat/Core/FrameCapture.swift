import Foundation

// docs/MESSAGE-DETAILS.md §3 — "where the frame comes from".
//
// The envelope as it crossed the wire, kept in RAM so the `details` sheet can
// show the buyer what the relay actually received. Hard rules, enforced by the
// shape of these two types:
//
//   * only the envelope — `t`, `id`, `to`/`from`, `ttl`, `kind`, `body`. There
//     is nowhere in `CapturedFrame` to put a key, a payload or a plaintext.
//   * RAM only: never in the store, never in a backup, never mirrored to a
//     linked browser, dropped on lock / auto-lock / wipe / app exit.
//   * bounded: the most recent 50 messages OR 256 KB, whichever comes first,
//     oldest evicted.
//   * nothing is ever re-encrypted to produce one. No capture ⇒ no ciphertext,
//     and the sheet says so.

/// One relay envelope, byte-for-byte as it went out (or came in).
struct CapturedFrame: Equatable {
    /// The relay's frame type: `send` or `deliver` (ours, outbound; `deliver` is the
    /// sealed one, written to the anonymous connection) or `recv` (inbound).
    let t: String
    /// The ENVELOPE's own relay id. Not always the row id it is filed under —
    /// a group fan-out gives every member's envelope a fresh id.
    let id: String
    /// `to` on a send or a deliver, `from` on a recv. An address hash, never a name.
    /// Empty on a sealed recv: that frame names no sender, which is the point of it.
    let peer: String
    /// Only a send carries one.
    let ttl: Int?
    /// The relay's routing word: `msg`, `blob`, `control`.
    let kind: String
    /// Base64 ciphertext, exactly the string the socket carried.
    let body: String
    /// Length of the ciphertext BEHIND the base64 — the `N bytes of
    /// ciphertext` line. Derived from the body's length; nothing is decoded.
    let ciphertextBytes: Int

    var isOutgoing: Bool { t == "send" || t == "deliver" }

    /// What this entry costs the 256 KB bound.
    var byteCost: Int {
        body.utf8.count + id.utf8.count + peer.utf8.count + kind.utf8.count + t.utf8.count + 8
    }

    /// The outgoing frame, taken from the very `Wire.Out` that was sent.
    /// nil for anything that is not a message envelope.
    /// A sealed frame's `access` (the recipient's delivery key) is never kept:
    /// there is nowhere in this type to put it.
    init?(sending out: Wire.Out) {
        guard out.t == "send" || out.t == "deliver", let id = out.id, let to = out.to,
              let kind = out.kind, let body = out.body else { return nil }
        self.init(t: out.t, id: id, peer: to, ttl: out.ttl, kind: kind,
                  body: body, ciphertextBytes: Self.ciphertextLength(ofBase64: body))
    }

    init(t: String, id: String, peer: String, ttl: Int?, kind: String,
         body: String, ciphertextBytes: Int) {
        self.t = t
        self.id = id
        self.peer = peer
        self.ttl = ttl
        self.kind = kind
        self.body = body
        self.ciphertextBytes = ciphertextBytes
    }

    /// The incoming frame, from the `recv` the relay delivered.
    /// `from` is nil for a sealed frame: the relay did not say, and was not told.
    static func received(id: String, from: String?, kind: String, body: String) -> CapturedFrame {
        CapturedFrame(t: "recv", id: id, peer: from ?? "", ttl: nil, kind: kind,
                      body: body, ciphertextBytes: ciphertextLength(ofBase64: body))
    }

    /// Ciphertext length from the base64 length alone — the body is never
    /// decoded, so no second copy of anything is ever made.
    static func ciphertextLength(ofBase64 body: String) -> Int {
        let n = body.count
        guard n > 0 else { return 0 }
        let pad = body.hasSuffix("==") ? 2 : (body.hasSuffix("=") ? 1 : 0)
        if n % 4 == 0 { return max(0, n / 4 * 3 - pad) }
        return max(0, n * 3 / 4)       // an unpadded encoder
    }
}

/// The capture buffer. One owner: `ChatService` holds exactly one, and it dies
/// with the session (§3). Not a store, not a cache with a file behind it —
/// two dictionaries and an insertion order, and `clear()` empties both.
final class FrameCaptureBuffer {
    /// §3: "the most recent 50 messages or 256 KB, whichever comes first".
    static let maxFrames = 50
    static let maxBytes = 256 * 1024

    /// row id -> the one envelope shown for that row.
    private var frames: [String: CapturedFrame] = [:]
    /// row ids, oldest first — the eviction order.
    private var order: [String] = []
    private(set) var byteCount = 0

    var count: Int { order.count }
    var isEmpty: Bool { order.isEmpty }

    /// File an envelope under the ROW it belongs to (a group fan-out's
    /// envelopes all belong to one row; the newest wins, and the sheet shows
    /// that one). Re-recording a row replaces it and makes it the newest.
    func record(_ frame: CapturedFrame, for rowId: String) {
        guard !rowId.isEmpty else { return }
        drop(rowId)
        // one frame bigger than the whole budget would evict everything else
        // to store nothing useful — refuse it instead
        guard frame.byteCost <= Self.maxBytes else { return }
        frames[rowId] = frame
        order.append(rowId)
        byteCount += frame.byteCost
        evictOldest()
    }

    func frame(for rowId: String) -> CapturedFrame? { frames[rowId] }

    /// Lock, auto-lock, wipe, app exit.
    func clear() {
        frames.removeAll()
        order.removeAll()
        byteCount = 0
    }

    private func drop(_ rowId: String) {
        guard let old = frames.removeValue(forKey: rowId) else { return }
        byteCount -= old.byteCost
        if let i = order.firstIndex(of: rowId) { order.remove(at: i) }
    }

    private func evictOldest() {
        while order.count > Self.maxFrames || byteCount > Self.maxBytes {
            guard let oldest = order.first else { break }
            drop(oldest)
        }
    }
}
