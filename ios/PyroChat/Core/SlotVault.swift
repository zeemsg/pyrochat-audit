import Foundation
import CryptoKit
import Security

/// docs/DECOY-ACCOUNT.md §1 — the multi-slot container that replaces the
/// single sealed `pyro.store`.
///
///     file      = magic(8) ‖ slotCount(1) ‖ slotBytes(4, BIG-endian)
///                 ‖ slot[0] ‖ … ‖ slot[slotCount-1]
///     magic     = ASCII "PYROVLT1"
///     slotCount = 3, ALWAYS — a constant of the build, never a function of
///                 how many slots are actually in use
///     slotBytes = the fixed constant 4 MiB. It does not grow, does not
///                 shrink, and does not depend on anything the device holds,
///                 so the file is always exactly 12 MiB + 13 bytes.
///     slot      = half A (2 MiB) ‖ half B (2 MiB)
///     half      = nonce(12) ‖ ciphertext ‖ tag(16), exactly halfBytes total
///                 (AES-GCM "combined" form)
///     plaintext = seq(8, BIG-endian) ‖ length(4, BIG-endian) ‖ JSON(Blob)
///                 ‖ random padding, filled to exactly halfBytes - 28
///     dead slot = slotBytes of CSPRNG bytes, written ONCE when the container
///                 is created and never touched again
///
/// The header is 13 bytes, so a file is always `13 + 3 * 4194304` = 12582925.
///
/// ## Never write to a slot you cannot open
///
/// §1's rule, and everything here bends around it. A slot that does not
/// authenticate under your key is either noise or another account's
/// ciphertext, and nothing can tell the two apart — that indistinguishability
/// IS the feature. So "refresh the unused slots" and "destroy the decoy" are
/// the same instruction, and preserving wins: a save rewrites only the slot it
/// can open, by seeking to that slot's fixed offset. This is also why
/// `slotBytes` is constant — a growing container would have to re-pad slots it
/// cannot decrypt.
///
/// The accepted consequence: an examiner with two snapshots of the file can
/// see which slot changed. What they still cannot see is whether the unchanged
/// slots are noise or a dormant account, which is the question that matters.
///
/// ## Two halves, because a single-slot write cannot be atomic
///
/// An atomic write is a whole-file rename, and the rule above forbids the
/// whole-file rewrite that would make one possible. So a power cut during a
/// save can tear the slot, a torn slot fails its tag, and this design renders
/// that indistinguishable from an empty app — losing a whole account, in a
/// product with no recovery by design.
///
/// So a slot is two 2 MiB halves and a save alternates between them. `open`
/// tries both halves of every slot (SIX attempts, always all six) and takes
/// the authenticating half with the HIGHER `seq`; `save` writes the OTHER half
/// at `seq + 1`. The half holding the last good copy is therefore never the
/// half being written, and a tear costs the stale copy and nothing else.
/// `seq` starts at 1 and does not roll over in any reachable lifetime.
///
/// A freshly authored slot writes one real half and leaves the other as noise;
/// its first save fills it. A half of noise and a half of ciphertext are
/// indistinguishable, so none of this is visible from outside.
///
/// The length prefix lives INSIDE the encrypted region on purpose: a cleartext
/// length would mark the real slots at a glance.
///
/// Android implements the identical layout. Byte-identity between the two is
/// NOT the requirement (§7) — the nonce, the padding and the slot choice are
/// random by design; it is reachable only in tests, by injecting the payload
/// and the randomness through `randomBytes` / `chooseSlot`. The requirement is
/// that each platform opens the other's container with the occupied slot at
/// any index.
enum SlotVault {

    // MARK: - pinned constants

    /// ASCII "PYROVLT1". In the clear on purpose: it says "this app can hold
    /// decoys", which is public. It never says whether this device does.
    static let magic = Data("PYROVLT1".utf8)
    static let magicBytes = 8
    /// §1: "`slotCount` is a constant of the build, never a function of what
    /// the device holds. It is 3."
    static let slotCount = 3
    static let headerBytes = 13              // 8 + 1 + 4
    static let nonceBytes = 12
    static let tagBytes = 16
    /// What AES-GCM's combined form costs on top of the plaintext.
    static let slotOverhead = nonceBytes + tagBytes      // 28
    /// The big-endian length that precedes the JSON inside the plaintext.
    static let lengthPrefixBytes = 4
    /// The big-endian write counter that precedes the length. Starts at 1.
    static let seqBytes = 8

    /// §1: "`slotBytes` is the fixed constant 4 MiB." Changing this is a
    /// format change and needs a new magic — the parser rejects any other
    /// value rather than guessing.
    static let slotBytes = 4 * 1024 * 1024               // 4_194_304

    /// Each slot is two of these. A save alternates between them.
    static let halvesPerSlot = 2
    static let halfBytes = slotBytes / halvesPerSlot     // 2_097_152
    /// The plaintext one half holds: `halfBytes - 28`.
    static let halfCapacity = halfBytes - slotOverhead   // 2_097_124
    /// The largest `JSON(Blob)` that fits, once `seq` and the length prefix
    /// are paid for. §1a moves voice notes out of the blob so this is enough.
    static let maxPayloadBytes = halfCapacity - seqBytes - lengthPrefixBytes  // 2_097_112
    /// Always. On a fresh install and on a heavy one.
    static let fileBytes = headerBytes + slotCount * slotBytes      // 12_582_925
    /// The first `seq` a slot is ever written with.
    static let firstSeq: UInt64 = 1

    /// Byte offset of `slot[i]` in the file — fixed, which is what makes a
    /// single-slot save possible.
    static func offset(ofSlot index: Int) -> Int { headerBytes + index * slotBytes }
    /// Byte offset of one half — fixed too, which is what makes the
    /// alternating write possible.
    static func offset(ofSlot index: Int, half: Int) -> Int {
        offset(ofSlot: index) + half * halfBytes
    }

    enum VaultError: Error {
        /// Not a container this build can read: missing magic, a `slotCount`
        /// or `slotBytes` that is not the pinned constant, or a length that
        /// does not match. NOT `badPassword`: nothing was even attempted.
        case malformed
        /// A `JSON(Blob)` larger than `maxPayloadBytes`. There is no bucket to
        /// grow into; see `SecureStore.isStoreFull`. §1a keeps audio out of the
        /// blob precisely so this stays unreachable in practice.
        case tooLarge
    }

    // MARK: - seams (tests only; production never touches these)

    /// Every GCM attempt reports here, as (slot, half). §9 asserts that all
    /// six are attempted through this, rather than by timing the unlock — a
    /// timing test is flaky on a loaded machine.
    static var onHalfAttempt: ((Int, Int) -> Void)?

    /// The real CSPRNG. Kept separately so a test that swapped `randomBytes`
    /// can put the production one back without re-implementing it.
    static let systemRandomBytes: (Int) -> Data = { n in
        guard n > 0 else { return Data() }
        var out = Data(count: n)
        let ok = out.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!)
        }
        precondition(ok == errSecSuccess, "CSPRNG unavailable")
        return out
    }

    /// CSPRNG. Injected by the cross-platform tests to pin a container's bytes:
    /// EVERY random byte in a slot comes through here — the GCM nonce, the
    /// padding, and a dead slot's contents — so payload + this seam determine
    /// the file completely.
    static var randomBytes: (Int) -> Data = systemRandomBytes

    /// §2: "a new account takes a uniformly random free slot" — never slot 0
    /// by default. The caller passes the indices it KNOWS are free: §1's
    /// allocation rule is that a slot can only be taken by an account that
    /// knows it is free, and an account learns that from the record of what it
    /// allocated, kept inside its own encrypted plaintext.
    static var chooseSlot: ([Int]) -> Int = { free in
        free.randomElement()!            // SystemRandomNumberGenerator is CSPRNG-backed
    }

    // MARK: - the container

    struct Container {
        /// Exactly `slotCount` entries, each exactly `slotBytes` long. Real or
        /// noise — from the outside there is no way to tell, which is the point.
        var slots: [Data]
    }

    // MARK: - one slot

    /// `slotBytes` of fresh CSPRNG bytes. §1: an unused slot "is not a
    /// recognisable empty value, it is noise", written once at creation.
    static func noise() -> Data { randomBytes(slotBytes) }
    /// One half of noise — what the unwritten half of a freshly authored slot
    /// holds until its first save fills it.
    static func halfNoise() -> Data { randomBytes(halfBytes) }

    /// Seal one payload into exactly `halfBytes`:
    /// `nonce(12) ‖ AES-256-GCM(seq(8) ‖ length(4) ‖ payload ‖ padding) ‖ tag(16)`.
    static func seal(payload: Data, key: SymmetricKey, seq: UInt64) throws -> Data {
        guard payload.count <= maxPayloadBytes else { throw VaultError.tooLarge }
        var plain = Data(capacity: halfCapacity)
        plain.append(be64(seq))
        plain.append(be32(UInt32(payload.count)))
        plain.append(payload)
        plain.append(randomBytes(halfCapacity - plain.count))
        // The nonce comes from the seam too: CryptoKit's own generator would
        // leave 12 bytes of a half outside a test's control.
        guard plain.count == halfCapacity,
              let nonce = try? AES.GCM.Nonce(data: randomBytes(nonceBytes)),
              let sealed = try AES.GCM.seal(plain, using: key, nonce: nonce).combined,
              sealed.count == halfBytes else { throw VaultError.malformed }
        return sealed
    }

    /// One GCM attempt against one half. `nil` = did not authenticate, which is
    /// what noise, a torn write and a wrong password all look like.
    static func openHalf(_ raw: Data, slot: Int, half: Int,
                         key: SymmetricKey) -> (seq: UInt64, payload: Data)? {
        onHalfAttempt?(slot, half)
        guard let box = try? AES.GCM.SealedBox(combined: raw),
              let plain = try? AES.GCM.open(box, using: key),
              plain.count >= seqBytes + lengthPrefixBytes else { return nil }
        let bytes = [UInt8](plain)
        let seq = be64(bytes, at: 0)
        let length = Int(be32(bytes, at: seqBytes))
        let start = seqBytes + lengthPrefixBytes
        guard seq >= firstSeq, start + length <= bytes.count else { return nil }
        return (seq, Data(bytes[start ..< start + length]))
    }

    /// The two halves of one slot's bytes.
    static func halves(of slot: Data) -> [Data] {
        let base = slot.startIndex
        return (0..<halvesPerSlot).map {
            Data(slot[base + $0 * halfBytes ..< base + ($0 + 1) * halfBytes])
        }
    }

    // MARK: - unlock

    struct Live {
        var slot: Int
        var half: Int
        var seq: UInt64
        var payload: Data
    }

    /// §2: derive the key once, then try it against EVERY slot — both halves of
    /// each, SIX attempts, always all six, in order. The account is the first
    /// slot that authenticates; within it, the live copy is the half with the
    /// higher `seq` (a tie, which a correct writer never produces, takes the
    /// lower half).
    ///
    /// **Do not add an early exit.** Stopping at the first match makes slot 0
    /// measurably faster to open than slot 2, and that difference is a readable
    /// signal about which account a password belongs to. The extra GCM opens
    /// are noise beside the one PBKDF2 derivation that already happened.
    static func open(_ container: Container, key: SymmetricKey) -> Live? {
        var found: Live?
        for i in container.slots.indices {
            let parts = halves(of: container.slots[i])
            for h in 0..<halvesPerSlot {
                let opened = openHalf(parts[h], slot: i, half: h, key: key)
                guard let opened else { continue }
                // first slot wins; inside it, the higher seq wins
                if let current = found, current.slot != i || current.seq >= opened.seq { continue }
                found = Live(slot: i, half: h, seq: opened.seq, payload: opened.payload)
            }
        }
        return found
    }

    // MARK: - file bytes

    /// The 13 header bytes, which are the same on every container ever written.
    static func header() -> Data {
        var out = Data(capacity: headerBytes)
        out.append(magic)
        out.append(UInt8(slotCount))
        out.append(be32(UInt32(slotBytes)))
        return out
    }

    /// Does this file carry the container magic? A file that does not is
    /// today's single sealed blob (§7, migration).
    static func isContainer(_ data: Data) -> Bool {
        data.count >= headerBytes && data.prefix(magicBytes) == magic
    }

    static func parse(_ data: Data) throws -> Container {
        guard isContainer(data), data.count == fileBytes else { throw VaultError.malformed }
        let bytes = [UInt8](data)
        guard Int(bytes[magicBytes]) == slotCount,
              Int(be32(bytes, at: magicBytes + 1)) == slotBytes else { throw VaultError.malformed }
        let slots = (0..<slotCount).map { i -> Data in
            Data(bytes[offset(ofSlot: i) ..< offset(ofSlot: i) + slotBytes])
        }
        return Container(slots: slots)
    }

    static func serialize(_ container: Container) throws -> Data {
        guard container.slots.count == slotCount,
              container.slots.allSatisfy({ $0.count == slotBytes })
        else { throw VaultError.malformed }
        var out = Data(capacity: fileBytes)
        out.append(header())
        for slot in container.slots { out.append(slot) }
        return out
    }

    // MARK: - big-endian helpers

    static func be32(_ v: UInt32) -> Data {
        Data([UInt8(truncatingIfNeeded: v >> 24), UInt8(truncatingIfNeeded: v >> 16),
              UInt8(truncatingIfNeeded: v >> 8), UInt8(truncatingIfNeeded: v)])
    }

    static func be32(_ bytes: [UInt8], at i: Int) -> UInt32 {
        (UInt32(bytes[i]) << 24) | (UInt32(bytes[i + 1]) << 16)
            | (UInt32(bytes[i + 2]) << 8) | UInt32(bytes[i + 3])
    }

    static func be64(_ v: UInt64) -> Data {
        Data((0..<8).reversed().map { UInt8(truncatingIfNeeded: v >> (8 * UInt64($0))) })
    }

    static func be64(_ bytes: [UInt8], at i: Int) -> UInt64 {
        (0..<8).reduce(UInt64(0)) { ($0 << 8) | UInt64(bytes[i + $1]) }
    }
}
