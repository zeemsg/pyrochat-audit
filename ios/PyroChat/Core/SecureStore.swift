import Foundation
import CryptoKit
import CommonCrypto

/// Everything PyroChat persists on-device lives in ONE file, encrypted with
/// AES-256-GCM under a key derived from the user's password:
///
///   key = PBKDF2-HMAC-SHA256(password, salt (Keychain), 600_000 iters)
///
/// A wrong password simply fails GCM authentication — indistinguishable from
/// a corrupt or absent store. The password itself never leaves the device.
///
/// Attachments (image/file bytes) live in a sibling `attachments/` directory,
/// one AES-256-GCM-sealed file per attachment under the SAME key, so the
/// message JSON stays small. Panic wipe removes both.
final class SecureStore {
    struct Blob: Codable {
        // --- identity (created at registration, never leaves the device) ---
        var usernameDisplay: String = ""
        var usernameHash: String = ""
        var identityKeyPair: Data = Data()      // libsignal serialized
        var registrationId: UInt32 = 0
        var authPrivateKey: Data = Data()       // Ed25519 raw (CryptoKit)
        // --- libsignal protocol state ---
        var sessions: [String: Data] = [:]      // address -> SessionRecord
        var preKeys: [UInt32: Data] = [:]       // PreKeyRecord
        var signedPreKeys: [UInt32: Data] = [:]
        var kyberPreKeys: [UInt32: Data] = [:]
        var knownIdentities: [String: Data] = [:] // address -> IdentityKey
        var nextPreKeyId: UInt32 = 1
        // --- app data ---
        var contacts: [Contact] = []
        var messages: [String: [ChatMessage]] = [:] // contact hash -> log
        var accessTokens: [AccessToken] = []
        var settings: AppSettings = AppSettings()
        // --- v0.2 (optional ⇒ pre-v0.2 stores keep decoding) ---
        var receiptOutbox: ReceiptOutbox? = nil
        var notes: [Note]? = nil
        var blockedHashes: [String]? = nil
        var drafts: [String: String]? = nil     // thread hash -> unsent composer text
        // --- group channels (GROUPS.md §3; absent on older stores) ---
        var groups: [Group]? = nil
        // --- sealed sender (docs/SEALED-SENDER.md §5.1; absent on older stores) ---
        /// 32 random bytes, created the first time this build offers sealed sender. The relay
        /// holds only its hash; the key goes to peers inside the encryption, and whoever holds
        /// it can write to this account without the relay being told who they are.
        var deliveryKey: Data? = nil
        /// What is known about each peer's delivery key (peer hash -> entry). A build from
        /// before sealed sender drops both keys on its next save; the rules in §5.1 put
        /// them back without anyone doing anything.
        var peerKeys: [String: Sealed.PeerKey]? = nil

        // --- docs/DECOY-ACCOUNT.md §1, allocation ---
        //
        // Because a free slot cannot be told from an occupied one, a slot can
        // only be allocated by an account that KNOWS it is free. These two
        // fields are that knowledge, and they live here — inside this
        // account's own encrypted plaintext — because "anyone who can read it
        // already holds that account". Occupancy is nowhere else: not the
        // header, not the Keychain, not a sidecar. Putting it anywhere an
        // examiner can reach would be the whole feature, given away.

        /// The slot indices this account is entitled to reason about. The
        /// account that authored the container knows all of them; a decoy
        /// knows exactly one, its own, because every other slot is bytes it
        /// cannot tell from noise.
        ///
        /// **ABSENT MEANS "only my own slot", never "all of them"** (§1, "why
        /// an absent one must fail closed"). The generous reading is the
        /// dangerous one: a platform drops blob keys it does not recognise on
        /// its next save, so a record lost in a round trip would silently
        /// re-grant an account permission to allocate over a slot it cannot
        /// see — the coin flip on destroying someone's messages that this
        /// whole mechanism exists to avoid. Authoring a container sets this
        /// EXPLICITLY; every other path that finds it missing assumes the least.
        ///
        /// `free = knownSlots - allocatedSlots`, and "a decoy cannot create
        /// another" is that subtraction coming out empty rather than a rule
        /// written anywhere or a flag anything renders.
        ///
        /// **The key names are a cross-platform contract** (§1): a store that
        /// visits a platform spelling these differently comes back having
        /// forgotten what it allocated. Both platforms spell them
        /// `knownSlots` and `allocatedSlots` or neither ships allocation.
        var knownSlots: [Int]? = nil

        /// The slot indices this account has taken: its own, plus any it has
        /// handed to a decoy. `knownSlots` minus this is what may be allocated
        /// — for the author, the slots it filled with noise; for a decoy, the
        /// empty set, which is §1's "a decoy cannot create another" falling
        /// out of the record rather than being bolted on beside it.
        var allocatedSlots: [Int]? = nil
    }

    enum StoreError: Error { case badPassword, noStore, io }

    private(set) var blob: Blob
    private let key: SymmetricKey
    private let url: URL

    // --- docs/DECOY-ACCOUNT.md §1: this session's place in the container ---

    /// Which slot of the container this session owns. `nil` ONLY while the
    /// file on disk is still today's single sealed blob because migration
    /// could not be verified (see `migrateLegacy`).
    ///
    /// It is never surfaced to the user (§2: no ordering the user can
    /// observe); it exists so a save can seek straight to this slot.
    private(set) var slotIndex: Int?

    /// Which half of that slot currently holds the live copy, and its `seq`.
    /// A save writes the OTHER half at `seq + 1`, so the copy that is good
    /// right now is never the one being overwritten (§1, two halves).
    private(set) var liveHalf: Int
    private(set) var liveSeq: UInt64

    /// Set by the two inits that declare "this path is a NEW store" —
    /// `creatingWithPassword` and `restoring`. Their first write authors the
    /// whole container, replacing whatever was at the path; every later write,
    /// and every write by a session that UNLOCKED a container, touches only
    /// its own slot (§1: never write to a slot you cannot open). Only a caller
    /// that has decided this path is a fresh store may set it — the app does
    /// so on a fresh install and after `obliterate()`.
    private var mustAuthorContainer: Bool

    /// A hard refusal to ever author a container, set by the one init that
    /// writes into a file another account already owns (`creatingDecoyWithPassword`).
    /// Authoring replaces the whole file, so for that store it would mean
    /// putting noise over the account that asked for it — §1's rule violated
    /// in the single place it would hurt most. When this is set and the file
    /// turns out not to be a seekable container, the save is dropped and the
    /// caller finds out through `isStoreFull` / the failed registration,
    /// rather than the store quietly taking the file.
    private var neverAuthor = false

    /// The file on disk is still the pre-container single sealed blob, and
    /// saves must keep writing that shape. Only true when a migration attempt
    /// failed its verification, or when the blob is too big to migrate.
    private(set) var isLegacyFile: Bool

    /// §1: the blob no longer fits in one half, so the last save could
    /// not be written. The file on disk keeps its last good contents and the
    /// snapshot stays queued, so a later save (after the user frees something)
    /// still lands. NOT silent: `onStoreFull` fires every time it happens.
    /// Read it after `flush()`, or through `isStoreFull`.
    private var storeFull = false
    /// `storeFull`, read safely from any thread.
    var isStoreFull: Bool { saveQueue.sync { storeFull } }

    /// Why a store reported itself full. The two are genuinely different
    /// conditions and §1a is explicit that only one of them is a problem.
    enum FullReason: Hashable {
        /// §1a: an existing install whose blob is over the ceiling even after
        /// the voice-note spill. It is NOT migrated and NOTHING is deleted —
        /// the file stays in its legacy shape, keeps opening, keeps saving,
        /// and the app keeps working exactly as before. It simply does not
        /// gain a decoy until the user decides what to give up. Trimming the
        /// oldest messages would make migration always succeed and was the
        /// tempting answer; it is the wrong one, because this app's whole
        /// promise is that messages exist on two phones and nowhere else.
        case cannotMigrate
        /// A container whose blob has outgrown one half. The last change is in
        /// memory and not on disk, and the queued snapshot keeps retrying.
        case cannotSave
    }

    /// Fires on the save queue every time a save cannot fit, with this store's
    /// URL and why. The container cannot grow (`slotBytes` is fixed), so this
    /// is the one condition the app has to surface rather than swallow — §1a,
    /// "the condition is reported and nothing is destroyed".
    static var onStoreFull: ((URL, FullReason) -> Void)?

    private let saveQueue = DispatchQueue(label: "ca.pyrochat.store-save")
    private var savePending = false          // saveQueue only
    private var pendingSnapshot: Blob?       // saveQueue only
    /// set by `discardPendingWrites()`: the store is being obliterated, a
    /// debounced write must not resurrect the file — saveQueue only
    private var dead = false

    // MARK: - durability (docs/DECOY-ACCOUNT.md §1, "What a save costs, measured")

    /// **A keystroke must never reach the store.**
    ///
    /// Every save writes a whole 2 MiB half whatever changed — the price of a
    /// slot whose size never hints at its contents, and not avoidable inside
    /// this format: AES-GCM ciphertext is exactly as long as its plaintext, so
    /// a fixed-size sealed unit is a fixed-size write. The fix is therefore
    /// never "make a save cheaper"; it is "stop making saves that did not have
    /// to happen".
    ///
    /// Android measured the damage: the composer persisted a draft on every
    /// keystroke behind a 250 ms debounce, so one typed sentence cost 19 saves
    /// — 38 MiB for a message that was not sent — and a chatty day ran to
    /// roughly 2 GB of flash writes. So saves are split by what losing one
    /// actually costs, rather than treated alike.
    enum Durability {
        /// A sent or received message, and any key material. Written on the
        /// ordinary 250 ms coalescing timer, because losing it to a crash is
        /// real damage.
        case prompt
        /// A draft, read state, interface state. Held in memory and written at
        /// the next flush point — the composer losing focus, the screen being
        /// left, the app backgrounding, or `idleFlushInterval` of quiet.
        /// Losing the last few seconds of an unsent draft is not damage.
        case lazily
    }

    /// §1: "and on a long idle timer". Long on purpose — the whole point is to
    /// sit out a burst of typing, and what is at risk in the meantime is an
    /// unsent draft. Every other flush point (focus loss, leaving the screen,
    /// backgrounding) fires long before this does in ordinary use; this is the
    /// backstop for a composer left open and untouched.
    static let idleFlushInterval: TimeInterval = 120

    /// A lazy change is sitting in `pendingSnapshot` with no write scheduled.
    private var deferredDirty = false        // saveQueue only
    /// An idle-flush timer is in flight. One at a time — it re-arms itself for
    /// the remaining time if a keystroke landed while it was waiting, so this
    /// really is "idle for N seconds", not "N seconds after the first key".
    private var deferredArmed = false        // saveQueue only
    private var lastDeferredAt = Date.distantPast   // saveQueue only

    /// Test seam: every PHYSICAL write to the store file reports here, with the
    /// bytes it put on disk. §1's measurement lives on this — a half write is
    /// 2 MiB whatever changed, so counting writes IS counting megabytes.
    /// Production never assigns to it.
    static var onWrite: ((URL, Int) -> Void)?

    static var storeURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory,
                                           in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("pyro.store")
    }

    /// Is there a FILE at the store path. Deliberately not named, and never
    /// used, as "does an identity exist here" — see `ensureContainerExists`.
    /// After that has run once it is true forever, on every device.
    static var storeExists: Bool {
        FileManager.default.fileExists(atPath: storeURL.path)
    }

    /// docs/DECOY-ACCOUNT.md §5, "The container is created at first run and is
    /// never deleted."
    ///
    /// The narrowed burn had an aftermath that undid the feature at the exact
    /// moment it was being used: a burn with no decoy could delete the file, a
    /// burn inside a decoy could not, so the next launch differed and a
    /// supervisor who reopened the app learned that something had survived.
    /// It cannot be fixed by making the decoy's burn delete more, so it is
    /// fixed from the other end — **wiped, never used, and holding two
    /// accounts are the same 12,582,925 bytes.**
    ///
    /// Call once at launch, before anything reads the path. Costs 90 ms on the
    /// one launch that finds nothing there and nothing on every launch after.
    ///
    /// A file that already exists is LEFT ALONE, whatever it is: a container
    /// with accounts in it, obviously, but also a pre-container single sealed
    /// blob, which §7 has to be able to migrate. This never destroys anything;
    /// it only fills an absence.
    @discardableResult
    static func ensureContainerExists(at url: URL = SecureStore.storeURL) -> Bool {
        guard !FileManager.default.fileExists(atPath: url.path) else { return false }
        try? writeNoiseContainer(at: url)
        return true
    }

    /// `slotCount` slots of CSPRNG noise and the same 13 header bytes every
    /// container ever written has. This is what a device that has never held
    /// an account looks like, and it is also what every wipe path leaves
    /// behind — the two being identical is the whole point.
    static func writeNoiseContainer(at url: URL) throws {
        let slots = (0..<SlotVault.slotCount).map { _ in SlotVault.noise() }
        let bytes = try SlotVault.serialize(SlotVault.Container(slots: slots))
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try bytes.write(to: url,
                        options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        onWrite?(url, bytes.count)
    }

    // MARK: key derivation

    static func deriveKey(password: String, salt: Data) -> SymmetricKey {
        var derived = Data(count: 32)
        let pw = Array(password.utf8)
        derived.withUnsafeMutableBytes { out in
            salt.withUnsafeBytes { saltBytes in
                _ = CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    pw.map { Int8(bitPattern: $0) }, pw.count,
                    saltBytes.bindMemory(to: UInt8.self).baseAddress, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    600_000,
                    out.bindMemory(to: UInt8.self).baseAddress, 32)
            }
        }
        return SymmetricKey(data: derived)
    }

    /// The shared salt, minted if this device has none. Only paths that are
    /// CREATING something may call this — see `unlockSalt()`.
    private static func salt() -> Data {
        if let s = KeychainHelper.get("kdf-salt") { return s }
        var s = Data(count: 32)
        _ = s.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        KeychainHelper.set(s, key: "kdf-salt")
        return s
    }

    /// docs/DECOY-ACCOUNT.md §1: **an unlock attempt must not mint a salt.**
    ///
    /// A device with none cannot be unlocked under any salt — nothing was ever
    /// sealed — so the attempt derives with a throwaway and keeps nothing. The
    /// derived key authenticates no half, the caller gets `badPassword`, and
    /// the device is byte-for-byte what it was.
    ///
    /// Without this, one wrong password at a wiped phone would leave a salt
    /// behind, and a wiped phone somebody typed at would stop being
    /// indistinguishable from one nobody touched.
    private static func unlockSalt() -> Data {
        if let s = KeychainHelper.get("kdf-salt") { return s }
        var s = Data(count: 32)
        _ = s.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        return s                                    // never stored
    }

    /// Duress-PIN verifier lives in the Keychain (must be checkable BEFORE
    /// the store can be decrypted). Stored as PBKDF2 output — not the PIN.
    static func setDuressPin(_ pin: String) {
        let v = deriveKey(password: pin, salt: salt())
        KeychainHelper.set(v.withUnsafeBytes { Data($0) }, key: "duress-verifier")
    }

    /// Is one armed? docs/STATUS-AND-CFG.md §1 needs a truthful `duress pin
    /// SET / NOT SET`, and `[cfg]`'s own row needs it too: that row derived
    /// its `SET` from a `@State` flag set when the dialog was used, so it read
    /// `set duress pin …` on every fresh entry to the screen however many pins
    /// were armed. Two screens disagreeing about a wipe trigger is worse than
    /// either being wrong alone.
    ///
    /// Device-level, like the verifier itself (docs/DECOY-ACCOUNT.md §6.1:
    /// "The duress verifier … acts on every slot"), so a decoy reads the same
    /// answer the real account does. That discloses nothing: a duress PIN is a
    /// feature of the app anyone can see, and it says nothing about how many
    /// accounts the device holds.
    static var duressPinIsSet: Bool { KeychainHelper.get("duress-verifier") != nil }

    /// Checked on the unlock screen, so it uses `unlockSalt()` too — §1: a
    /// wrong PIN at a wiped phone must leave nothing behind either.
    static func matchesDuressPin(_ candidate: String) -> Bool {
        guard let stored = KeychainHelper.get("duress-verifier") else { return false }
        let v = deriveKey(password: candidate, salt: unlockSalt())
        return v.withUnsafeBytes { Data($0) } == stored
    }

    // MARK: lifecycle

    /// Create a brand-new store (registration). Custom `at` is for tests.
    ///
    /// docs/DECOY-ACCOUNT.md §1, "Creating an identity from cold, which the
    /// rules above would otherwise forbid".
    ///
    /// Free-slot knowledge lives in an account's own plaintext, so it exists
    /// only for a session that has opened one. A cold start holds none, and
    /// every slot is noise it cannot tell from ciphertext — so the rules taken
    /// literally say refuse. That is not survivable: the container is never
    /// deleted, so a device wiped by the duress PIN, ten wrong passwords or the
    /// dead-man switch still has one on the next launch, and refusing there
    /// means a wiped phone can never hold an account again. The feature would
    /// brick exactly the people it is for, at exactly the moment it fired.
    ///
    /// **It rewrites the WHOLE container, claiming every slot**, rather than
    /// gambling on one. The caller has already shown the unconditional
    /// confirmation that makes that the user's to choose — and because the
    /// container is rewritten, that confirmation can be a statement rather
    /// than a probability: `AuthCopy.coldCreateWarning`.
    ///
    /// Taking one slot at random and hoping was the other answer, and it fails
    /// for a reason worth writing down: it leaves the device in a state it
    /// cannot reason about afterwards — one slot claimed, one unknown forever,
    /// and a decoy that may or may not be allocatable. A duress-wiped phone
    /// could then create an account but never a decoy, which is a real loss
    /// for precisely the people this feature is for.
    ///
    /// The rescue for THAT — proving a device is empty from the absence of the
    /// shared salt, which every wipe destroys — is deliberately not taken. It
    /// works, and it re-opens §5 through a different door: a narrowed burn
    /// leaves the salt and a total burn destroys it, so whether decoy creation
    /// succeeds afterwards would answer the question §5 exists to leave
    /// unanswered. A tell that moves from the screen to a behaviour is still a
    /// tell. Rewriting everything removes it at the root: the outcome is
    /// identical whatever the device held, so there is nothing to compare, and
    /// afterwards the device is in a fully known state where a decoy works.
    ///
    /// The cost is real and belongs to the person who confirmed it: a cold
    /// create on a phone that still holds a reachable account destroys it. That
    /// account was reachable only by signing in, which is the path the screen
    /// recommends and the one anybody with a working password takes.
    init(creatingWithPassword password: String, at url: URL = SecureStore.storeURL) {
        self.key = Self.deriveKey(password: password, salt: Self.salt())
        self.blob = Blob()
        self.url = url
        let index = SlotVault.chooseSlot(Array(0..<SlotVault.slotCount))
        self.slotIndex = index
        self.liveHalf = 1                 // so the first (authoring) write lands in half 0
        self.liveSeq = SlotVault.firstSeq - 1
        self.isLegacyFile = false
        // Every slot: this account's ciphertext and fresh noise in the rest.
        self.mustAuthorContainer = true
        self.blob.knownSlots = Array(0..<SlotVault.slotCount)
        self.blob.allocatedSlots = [index]
        persist(snapshot: blob)
    }

    /// Restore from a decoded backup: seals the imported blob (and its
    /// attachments) under a key derived from the password chosen on THIS
    /// device. The old device password is not needed — the backup passphrase
    /// already proved authority over the data.
    ///
    /// This authors a brand-new container (§6.4: a backup covers the slot that
    /// made it and says nothing about the others), so it also takes a
    /// uniformly random slot.
    init(restoring blob: Blob, attachments: [String: Data],
         password: String, at url: URL = SecureStore.storeURL) {
        self.key = Self.deriveKey(password: password, salt: Self.salt())
        self.blob = blob
        self.url = url
        let index = SlotVault.chooseSlot(Array(0..<SlotVault.slotCount))
        self.slotIndex = index
        self.liveHalf = 1
        self.liveSeq = SlotVault.firstSeq - 1
        self.isLegacyFile = false
        self.mustAuthorContainer = true
        // A RESTORE is a cold create carrying somebody's data: it rewrites the
        // whole container the same way, behind the same confirmation.
        //
        // And it must DISCARD the record the backup arrived with. `[own, decoy]`
        // exported from another phone describes indices that are noise here:
        // trusting it would hand this device permission to allocate over a slot
        // nobody has ever looked at. Re-stamped, never adopted.
        self.blob.knownSlots = Array(0..<SlotVault.slotCount)
        self.blob.allocatedSlots = [index]
        for (id, bytes) in attachments {
            try? writeAttachment(id: id, data: bytes)
        }
        // §1a: a backup restored from an older build still carries inline
        // `voiceData`, and the wire format and backups are not changing this
        // wave. Spill it before anything is sealed into a half.
        spillInlineVoiceNotes()
        persist(snapshot: blob)
        flush()
    }

    /// Unlock an existing store. Throws `.badPassword` if no slot authenticates.
    ///
    /// §2: the key is derived ONCE and tried against every slot, always all of
    /// them — see `SlotVault.open`. A file with no magic is today's single
    /// sealed blob and is migrated in place (§7) once it has opened.
    init(unlockingWithPassword password: String, at url: URL = SecureStore.storeURL) throws {
        self.url = url
        // §1: an unlock never mints a salt. A device with none opens nothing.
        self.key = Self.deriveKey(password: password, salt: Self.unlockSalt())
        let opened = try Self.openFile(at: url, key: self.key)
        self.blob = opened.blob
        self.slotIndex = opened.slotIndex
        self.liveHalf = opened.liveHalf
        self.liveSeq = opened.liveSeq
        self.isLegacyFile = (opened.legacyPayload != nil)
        self.mustAuthorContainer = false     // we opened it: only our slot is ours
        adoptOrMigrate(legacyPayload: opened.legacyPayload)
        adoptAllocationRecord()
    }

    /// Unlock from the RAW 32-byte store key (docs/BIOMETRIC-UNLOCK.md §1):
    /// biometry unwrapped the copy the phone's secure hardware was holding.
    /// The key was still derived from the password, once — this path just
    /// skips re-deriving it. Throws `.badPassword` if GCM auth fails, which
    /// for this entry point means the stored key no longer matches the file.
    init(unlockingWithKey keyBytes: Data, at url: URL = SecureStore.storeURL) throws {
        guard keyBytes.count == 32 else { throw StoreError.badPassword }
        self.url = url
        self.key = SymmetricKey(data: keyBytes)
        let opened = try Self.openFile(at: url, key: self.key)
        self.blob = opened.blob
        self.slotIndex = opened.slotIndex
        self.liveHalf = opened.liveHalf
        self.liveSeq = opened.liveSeq
        self.isLegacyFile = (opened.legacyPayload != nil)
        self.mustAuthorContainer = false     // we opened it: only our slot is ours
        // §6.3: biometry must behave identically whichever slot it unwrapped,
        // and it must migrate a legacy file exactly as a typed password does.
        adoptOrMigrate(legacyPayload: opened.legacyPayload)
        adoptAllocationRecord()
    }

    // MARK: - the container (docs/DECOY-ACCOUNT.md §1, §2, §7)

    private struct Opened {
        var blob: Blob
        var slotIndex: Int?
        var liveHalf: Int
        var liveSeq: UInt64
        /// Non-nil when the file had no magic: the plaintext of today's single
        /// sealed blob, ready to be migrated into a container (§7).
        var legacyPayload: Data?
    }

    /// Read the file and open it with `key`, container or legacy. Throws the
    /// SAME `.badPassword` the single-blob store always threw when nothing
    /// authenticates — the caller (and the user) cannot tell the shapes apart.
    private static func openFile(at url: URL, key: SymmetricKey) throws -> Opened {
        guard let raw = try? Data(contentsOf: url) else { throw StoreError.noStore }

        if SlotVault.isContainer(raw) {
            guard let container = try? SlotVault.parse(raw) else { throw StoreError.io }
            // EVERY slot is attempted, always, in order — never early-exit.
            guard let hit = SlotVault.open(container, key: key) else { throw StoreError.badPassword }
            let blob = try JSONDecoder().decode(Blob.self, from: hit.payload)
            // The slots that did not open are NOT recorded, copied or carried:
            // §1, never write to a slot you cannot open. A save seeks past them.
            return Opened(blob: blob, slotIndex: hit.slot,
                          liveHalf: hit.half, liveSeq: hit.seq, legacyPayload: nil)
        }

        // §7: no magic ⇒ the whole file is today's single sealed blob.
        guard let box = try? AES.GCM.SealedBox(combined: raw),
              let plain = try? AES.GCM.open(box, using: key) else { throw StoreError.badPassword }
        let blob = try JSONDecoder().decode(Blob.self, from: plain)
        return Opened(blob: blob, slotIndex: nil, liveHalf: 1,
                      liveSeq: SlotVault.firstSeq - 1, legacyPayload: plain)
    }

    /// docs/DECOY-ACCOUNT.md §1a — **voice notes must leave the blob first.**
    ///
    /// `voiceData` is the one piece of binary held inline in the blob; images
    /// and files already live in sidecar attachments. A 120-second AAC note is
    /// a quarter to half a megabyte and JSON base64 inflates it by a third, so
    /// enough notes overflow a half — and an install already over the cap could
    /// never migrate, failing and retrying on every unlock, forever.
    ///
    /// Moves every inline note to a sidecar under the same key, leaving the row
    /// with an `attachmentId` and its duration. Returns true if anything moved.
    ///
    /// A note whose sidecar cannot be written is LEFT INLINE rather than
    /// dropped: a blob that is too big still opens and still plays, and §1's
    /// `onStoreFull` says so. Losing someone's audio to a full disk would be a
    /// far worse answer than refusing to shrink.
    @discardableResult
    func spillInlineVoiceNotes() -> Bool {
        var movedAny = false
        for (thread, rows) in blob.messages {
            var updated = rows
            var movedHere = false
            for i in updated.indices {
                guard let audio = updated[i].voiceData, !audio.isEmpty,
                      updated[i].attachmentId == nil else { continue }
                let id = UUID().uuidString.lowercased()
                guard (try? writeAttachment(id: id, data: audio)) != nil else { continue }
                updated[i].attachmentId = id
                updated[i].fileBytes = audio.count
                updated[i].voiceData = nil
                movedHere = true
            }
            guard movedHere else { continue }
            blob.messages[thread] = updated
            movedAny = true
        }
        return movedAny
    }

    /// The §1a ordering, which is the whole reason that section exists: spill
    /// the audio while the store is STILL LEGACY (a legacy file has no size
    /// limit; a container does), and only then write the container.
    ///
    /// Container-first is exactly the bug. A blob already over the cap would
    /// fail to convert, retry on the next unlock, and never get anywhere.
    private func adoptOrMigrate(legacyPayload: Data?) {
        let spilled = spillInlineVoiceNotes()
        guard let legacyPayload else {
            // already a container; a save is enough to persist any spill
            if spilled { persist(snapshot: blob) }
            return
        }
        var payload = legacyPayload
        if spilled {
            guard let reencoded = try? JSONEncoder().encode(blob) else {
                // cannot re-encode what we just changed: do NOT migrate a
                // payload that disagrees with the blob this session holds
                return
            }
            payload = reencoded
        }
        migrateLegacy(payload: payload)
        // Whichever way migration went, the file on disk must end up holding
        // the spilled blob: as a container if it converted, as the legacy
        // shape if it did not. Either way the sidecars now beside it match.
        if spilled { persist(snapshot: blob) }
    }

    /// §7: one way, on first successful unlock. The account lands in a
    /// UNIFORMLY RANDOM slot and the other two are filled with noise — the one
    /// and only time those two slots are ever written.
    ///
    /// The original file is replaced ONLY after the container has been written
    /// out in full AND read back off disk and re-opened with the same key,
    /// yielding byte-identical JSON. If anything at all goes wrong the original
    /// is left exactly as it was and this session keeps writing the old shape,
    /// so the next launch simply tries again. A half-migrated store that no
    /// longer opens is the worst outcome this code can have, and every branch
    /// here is bent towards preventing it.
    ///
    /// A blob too large for one half cannot migrate, even after §1a's spill.
    /// It stays legacy, keeps working, and `onStoreFull` says so — losing it
    /// would be far worse.
    private func migrateLegacy(payload: Data) {
        let fm = FileManager.default
        // tmp, with a prefix `sweepPlaintextTmp()` clears: a crash between the
        // two writes must not leave a second sealed copy of the account sitting
        // next to the store, outliving a panic wipe.
        let staging = fm.temporaryDirectory
            .appendingPathComponent("migrate-\(UUID().uuidString)")
        defer { try? fm.removeItem(at: staging) }
        guard payload.count <= SlotVault.maxPayloadBytes else {
            saveQueue.sync { storeFull = true }
            Self.onStoreFull?(url, .cannotMigrate)
            return
        }
        do {
            // Nothing else can be in this file: every slot is free.
            let index = SlotVault.chooseSlot(Array(0..<SlotVault.slotCount))
            let bytes = try Self.authoredContainer(
                mine: try SlotVault.seal(payload: payload, key: key, seq: SlotVault.firstSeq),
                at: index)

            // Verify from DISK, through exactly the code an unlock runs.
            try bytes.write(to: staging,
                            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            let readBack = try Data(contentsOf: staging)
            let container = try SlotVault.parse(readBack)
            guard let hit = SlotVault.open(container, key: key),
                  hit.slot == index, hit.half == 0, hit.seq == SlotVault.firstSeq,
                  hit.payload == payload,
                  (try? JSONDecoder().decode(Blob.self, from: hit.payload)) != nil
            else { return }                       // original untouched

            // Only now. `.atomic` is a rename: the file is the old one or the
            // new one, never a torn mixture of the two.
            try bytes.write(to: url,
                            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            slotIndex = index
            liveHalf = 0
            liveSeq = SlotVault.firstSeq
            isLegacyFile = false
            mustAuthorContainer = false      // the container is authored; seek from here on
            blob.knownSlots = Array(0..<SlotVault.slotCount)
            blob.allocatedSlots = [index]
            // §1: MIGRATION IS AN AUTHORING EVENT, so it stamps the record.
            //
            // It just wrote all three slots itself and filled two of them with
            // noise, so "the other two are free" is a fact it established, not
            // a guess it is making. Leaving the record blank here is what made
            // every migrated account unable to set a decoy: `freeSlots` fell
            // back to "only my own slot", subtracted the slot it had just
            // taken, and came out empty — reported to the user as the one
            // vague failure, which is the answer reserved for a decoy.

        } catch {
            // leave the original alone; the next successful unlock retries
        }
    }

    /// A whole container: the header, this account's sealed half, half a slot
    /// of noise beside it, and a full slot of noise in the other two. Reached
    /// exactly twice in a store's life — when it is created and when a legacy
    /// file migrates — because §1 forbids writing a slot you cannot open, and
    /// after this the other two are slots we cannot open.
    ///
    /// The real half is half 0 at `seq` 1; the first save fills half 1 at 2.
    /// A half of noise and a half of ciphertext are indistinguishable, so the
    /// unwritten half discloses nothing.
    private static func authoredContainer(mine sealedHalf: Data, at index: Int) throws -> Data {
        let slots = (0..<SlotVault.slotCount).map { i -> Data in
            guard i == index else { return SlotVault.noise() }
            return sealedHalf + SlotVault.halfNoise()
        }
        return try SlotVault.serialize(SlotVault.Container(slots: slots))
    }

    /// Is the file on disk a container this build can seek into? Cheap: a
    /// length check and 13 bytes. False means "author the whole thing".
    private func containerIsIntact() -> Bool {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              (attrs[.size] as? NSNumber)?.intValue == SlotVault.fileBytes,
              let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        return (try? handle.read(upToCount: SlotVault.headerBytes)) == SlotVault.header()
    }

    /// §1: "Saves rewrite ONLY the current slot, by seeking to its fixed
    /// offset. Other slots keep their bytes exactly." — and only the half that
    /// is NOT live, so the last good copy is never the one being overwritten.
    ///
    /// Not `.atomic`, and it cannot be: an atomic write is a whole-file
    /// replace, which is precisely the thing that would destroy the slots this
    /// session cannot decrypt. That is what the two halves are for: a tear here
    /// costs the stale copy, and the live one is still on disk.
    private func writeHalfInPlace(_ sealed: Data, slot: Int, half: Int) throws {
        let handle = try FileHandle(forUpdating: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(SlotVault.offset(ofSlot: slot, half: half)))
        try handle.write(contentsOf: sealed)
        try handle.synchronize()
    }

    /// The raw store key. ONLY for handing to the phone's secure hardware
    /// when the user turns on biometric unlock — never written anywhere else,
    /// never logged, never near the password (which this type never keeps).
    var rawKey: Data { key.withUnsafeBytes { Data($0) } }

    /// Where this store's file is. Needed by §3's decoy creation, which puts a
    /// second account into the SAME file, and by the §5 slot scrub.
    var fileURL: URL { url }

    /// Does `password` derive this session's key? §3: "a password, which must
    /// not be the real one (rejected, with the ordinary wrong-value wording)".
    /// The password itself is never kept, so the comparison is on the key the
    /// shared salt derives — which is the same question, exactly.
    func derivesSameKey(as password: String) -> Bool {
        let candidate = Self.deriveKey(password: password, salt: Self.salt())
        let mine = rawKey
        let theirs = candidate.withUnsafeBytes { Data($0) }
        guard mine.count == theirs.count else { return false }
        return zip(mine, theirs).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    // MARK: - allocation (docs/DECOY-ACCOUNT.md §1)

    /// Make sure this session's own slot is in the record, for a store written
    /// before the record existed. Such a store is the only account in its
    /// file — nothing else could have been put there without it knowing — so
    /// it knows the whole container.
    ///
    /// A store that is STILL LEGACY has no container at all and therefore no
    /// slots to know about; it gains the record when it migrates.
    private func adoptAllocationRecord() {
        guard let index = slotIndex else { return }
        var changed = false
        // §1: a path that finds the record missing ASSUMES THE LEAST. It does
        // not materialise `knownSlots` — it cannot tell a store that authored
        // its container from one whose record was dropped by a platform that
        // did not know these keys, and getting that wrong means allocating
        // over an account nobody can see. The consequence is real and worth
        // stating: a container written by a build that predates this record
        // can never allocate a decoy. Authoring one (create, restore) can.
        if blob.allocatedSlots == nil || blob.allocatedSlots?.contains(index) == false {
            blob.allocatedSlots = Array(Set(blob.allocatedSlots ?? []).union([index])).sorted()
            changed = true
        }
        // ONE-TIME REPAIR for containers written by builds 32-34, whose
        // migration authored the container and then failed to stamp
        // `knownSlots`. Those accounts read as "I know only my own slot" and
        // can never set a decoy — which is exactly what a real user reported.
        //
        // Healing is safe HERE and only because of a fact about what has
        // shipped: a missing record could in principle mean "a platform that
        // did not know these keys dropped them", and assuming the least is
        // right in that case. No such build exists. Containers arrived in
        // build 32, the record arrived with them, and both platforms have
        // known both keys ever since — so today an absent `knownSlots` on a
        // container can only mean the migration forgot to write it.
        //
        // If a build ever ships containers WITHOUT this record, delete this
        // block first: it would then hand a store permission to allocate over
        // a slot it has never seen, which §1 forbids absolutely.
        if !isLegacyFile && blob.knownSlots == nil {
            blob.knownSlots = Array(0..<SlotVault.slotCount)
            changed = true
        }
        if changed { persist(snapshot: blob) }
    }

    /// The slots this account may hand to a new one: the ones it knows the
    /// contents of, minus the ones it has already taken.
    ///
    /// Empty for a decoy, always, because a decoy knows exactly one index and
    /// has already taken it. §1: "choosing among the rest would be a coin flip
    /// on destroying the real account, and no privacy property is worth
    /// silently destroying someone's messages."
    var freeSlots: [Int] {
        guard !isLegacyFile, slotIndex != nil else { return [] }
        // §1: absent fails closed — "only my own slot", never "all of them".
        let known = Set(blob.knownSlots ?? [slotIndex].compactMap { $0 })
        let taken = Set(blob.allocatedSlots ?? [slotIndex].compactMap { $0 })
        return known.subtracting(taken).sorted()
    }

    /// §1: "The first account can create one decoy." This account has already
    /// handed one out — so a second is refused, and the row instead REPLACES
    /// the decoy in the slot it already owns (§3).
    var hasAllocatedDecoy: Bool { allocatedDecoySlot != nil }

    /// The slot this account handed to its decoy, if it has one.
    ///
    /// §3: "Used again from the account that owns the decoy, it rebuilds the
    /// decoy in the slot it already allocated." That is only safe because the
    /// slot is in THIS account's own record — it is a slot we allocated, so
    /// §1's rule is satisfied for exactly the same reason it was the first
    /// time. No other account can name it, and a decoy has no record naming
    /// anyone but itself.
    var allocatedDecoySlot: Int? {
        guard let index = slotIndex else { return nil }
        return (blob.allocatedSlots ?? []).first { $0 != index }
    }

    /// Record a slot handed to a decoy. Key material by any reading — losing
    /// it would make this account believe a slot it has already filled is
    /// still free, and the next allocation would overwrite a real account —
    /// so it is written `.prompt` and flushed before the caller returns.
    func noteAllocated(slot: Int) {
        update { $0.allocatedSlots = Array(Set($0.allocatedSlots ?? []).union([slot])).sorted() }
        flush()
    }

    /// Put `slotBytes` of fresh noise over one slot of an existing container.
    ///
    /// Permitted by §1 only because the caller has just proved it can open
    /// that slot — either it authored it seconds ago (a decoy whose relay
    /// registration failed) or it is the slot this very session unlocked
    /// (`[ BURN EVERYTHING ]`). Never call it for a slot whose key is not in
    /// hand: that is the one thing this whole format exists to make impossible.
    static func scrubSlot(_ index: Int, at url: URL) throws {
        guard (0..<SlotVault.slotCount).contains(index) else { throw StoreError.io }
        let handle = try FileHandle(forUpdating: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(SlotVault.offset(ofSlot: index)))
        try handle.write(contentsOf: SlotVault.noise())
        try handle.synchronize()
    }

    // MARK: - creating a decoy (docs/DECOY-ACCOUNT.md §3)

    /// A brand-new, genuine account in a slot the CALLING account knows is
    /// free. Real identity keys and real relay registration follow; this type
    /// only makes the place for them.
    ///
    /// Unlike every other "new store" path this one must NOT author a
    /// container: the file already holds the account that is making this call,
    /// and re-authoring would put noise over it. So `mustAuthorContainer`
    /// stays false and `neverAuthor` makes that a hard rule rather than a
    /// hopeful comment — if the file ever stops being a seekable container,
    /// this store refuses to write at all rather than take the whole file.
    ///
    /// The key comes from the SAME shared `kdf-salt` (§6.1: one value, readable
    /// before any unlock), so the decoy opens by password exactly as the first
    /// account does, through the same six attempts.
    init(creatingDecoyWithPassword password: String, inSlot index: Int,
         at url: URL = SecureStore.storeURL) throws {
        guard (0..<SlotVault.slotCount).contains(index) else { throw StoreError.io }
        self.url = url
        self.key = Self.deriveKey(password: password, salt: Self.salt())
        self.blob = Blob()
        self.slotIndex = index
        self.liveHalf = 1                 // so the first write lands in half 0
        self.liveSeq = SlotVault.firstSeq - 1
        self.isLegacyFile = false
        self.mustAuthorContainer = false  // the container exists; seek into it
        self.neverAuthor = true
        // §1: it knows its own index and nothing else, which is exactly why it
        // will never be able to allocate.
        self.blob.knownSlots = [index]
        self.blob.allocatedSlots = [index]
        guard containerIsIntact() else { throw StoreError.io }
        // Take the WHOLE slot, both halves.
        //
        // §3's row creates OR REPLACES, so this slot may already hold the decoy
        // being replaced — and a slot is two halves that a save alternates
        // between. Writing only half 0 would leave the previous decoy sitting
        // in half 1, still authenticating under its own password, still
        // opening, until this account's SECOND save happened to land on it.
        // The replaced decoy would survive its replacement and then die at a
        // moment nothing announced, and which of those happened would depend on
        // the parity of how many times the old one had saved.
        //
        // Permitted by §1 because the caller has just proved it can open this
        // slot: it is either free in this account's own record, or the one this
        // account allocated to the decoy it is replacing.
        try Self.scrubSlot(index, at: url)
        persist(snapshot: blob)
        flush()
    }

    // MARK: - wipe, narrowed (docs/DECOY-ACCOUNT.md §5)

    /// `[ BURN EVERYTHING ]`, which with a container narrows to **this slot
    /// only**. The three involuntary paths (duress PIN, ten wrong passwords,
    /// dead-man) stay total and still go through `obliterate()`: a partial
    /// wipe under duress leaves the interesting slot behind, which is the
    /// opposite of what they are for.
    ///
    /// **Neither branch deletes the file.** §5: "a burn must NOT delete the
    /// file even when the account can prove it is alone, because behaving
    /// differently in the common case is precisely what makes the uncommon
    /// case legible." An earlier version of this took the whole file when the
    /// account could prove the rest was noise, which was a real optimisation
    /// and exactly the wrong one: it made the decoy's burn — the case this
    /// feature exists for — the only one that left a file behind.
    ///
    /// Returns true when the burn was TOTAL (device-level state went with it),
    /// false when only this slot was scrubbed. The two are indistinguishable
    /// from outside by construction; the flag is for the caller's bookkeeping,
    /// never for anything a user or an examiner can see.
    @discardableResult
    func burnCurrentSlot() -> Bool {
        discardPendingWrites()      // nothing debounced may land after this
        purgeMyAttachments()

        // §5: the burn takes the biometric key, ALWAYS — even when it belongs
        // to a slot this burn is not touching.
        //
        // The narrowed burn used to spare it, to avoid a decoy's burn quietly
        // disabling the real account's face unlock. That kindness was the
        // aftermath leak coming back through the one piece of state §5 had
        // exempted: a device that still raises a face prompt at launch has
        // something left, and a device with nothing does not. Someone made to
        // wipe, who reopens the app, reads the answer off the launch screen.
        //
        // So it goes. The cost is re-enabling face unlock after burning a
        // decoy — small, recoverable, and reasonable after something that
        // drastic. Behaving differently in the common case is precisely what
        // makes the uncommon case legible.
        //
        // `shred`, not `destroy`: the three total wipe paths also clear the
        // last-password timestamp on their way through the keychain, and a
        // value that survives one kind of wipe but not another is the same
        // class of difference this whole subsection exists to remove.
        BiometricUnlock.shred()

        // An account that can PROVE the rest of its container is noise is the
        // last one here, so the device-level state goes too — and the file is
        // re-authored as `slotCount` slots of fresh noise rather than removed.
        // Only an account that KNOWS the whole container is alone in it. §1's
        // fail-closed reading again: an absent record does not prove that, so
        // such a store scrubs its own slot and leaves the device-level state —
        // which is the safe way round, and invisible either way because both
        // branches leave the same 12,582,925 bytes.
        let lastAccountHere = isLegacyFile
            || (slotIndex != nil && !hasAllocatedDecoy
                && Set(blob.knownSlots ?? []) == Set(0..<SlotVault.slotCount))
        if lastAccountHere {
            obliterateWholeDevice()
            return true
        }
        guard let index = slotIndex, !isLegacyFile else {
            obliterateWholeDevice()
            return true
        }
        // §5, kept from the earlier analysis: a slot-only burn leaves the
        // shared salt, the duress verifier, the dead-man deadline and the
        // wrong-password counter alone. Every one of those acts on every slot,
        // so clearing them from inside a decoy would reach into an account the
        // decoy is not allowed to know exists.
        try? Self.scrubSlot(index, at: url)
        Self.sweepPlaintextTmp()
        slotIndex = nil                 // this session can never write again
        blob = Blob()
        return false
    }

    /// The total wipe, on THIS store's file. Identical to `obliterate()` for
    /// the app (whose store is always `storeURL`); the URL indirection is what
    /// lets a test drive it without touching the host's real store.
    private func obliterateWholeDevice() {
        BiometricUnlock.destroy()
        try? Self.writeNoiseContainer(at: url)     // §5: never deleted
        try? FileManager.default.removeItem(at: Self.attachmentsDir(for: url))
        Self.sweepPlaintextTmp()
        KeychainHelper.wipeAll()
        DeadmanSwitch.disarm()
        NoticeStore.wipe()
        slotIndex = nil
        blob = Blob()
    }

    /// Delete every attachment sealed under THIS slot's key, and only those.
    ///
    /// §6.2: one directory, opaque random ids, each file sealed with its own
    /// slot's key. Attribution is therefore exactly "does it open under my
    /// key", and a file that does not open is either another slot's or
    /// corrupt — in both cases not ours to delete. That an unopenable file can
    /// be counted is the residue §6.2 already names and accepts; nothing here
    /// surfaces the count.
    private func purgeMyAttachments() {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: attachmentsDir,
                                                        includingPropertiesForKeys: nil)
        else { return }
        for entry in entries {
            guard let sealed = try? Data(contentsOf: entry),
                  let box = try? AES.GCM.SealedBox(combined: sealed),
                  (try? AES.GCM.open(box, using: key)) != nil else { continue }
            try? fm.removeItem(at: entry)
        }
    }

    /// Mutate-and-save. Contract: call from one thread at a time (in practice
    /// @MainActor — ChatService is; tests comply).
    ///
    /// `durability` defaults to `.prompt`, so a call site that has not thought
    /// about it keeps today's behaviour. Only the call sites that KNOW what
    /// they are writing — a draft, read state, interface state — ask for
    /// `.lazily` (§1).
    func update(_ mutate: (inout Blob) -> Void, durability: Durability = .prompt) {
        mutate(&blob)
        // value copy captured on the CALLER's thread (CoW-safe)
        let snapshot = blob
        switch durability {
        case .prompt: persist(snapshot: snapshot)
        case .lazily: hold(snapshot: snapshot)
        }
    }

    private func persist(snapshot: Blob) {
        saveQueue.async { [self] in schedule(snapshot: snapshot) }
    }

    private func schedule(snapshot: Blob) {  // saveQueue only
        pendingSnapshot = snapshot           // always keep the newest
        if savePending { return }
        savePending = true
        saveQueue.asyncAfter(deadline: .now() + 0.25) { [self] in
            savePending = false
            writeNow()
        }
    }

    /// §1: keep the change in memory and schedule NOTHING. The newest snapshot
    /// is parked where every other path already looks for it, so a later
    /// `.prompt` save carries this change along for free — a lazy write that
    /// rides a message's save costs nothing at all.
    private func hold(snapshot: Blob) {
        saveQueue.async { [self] in
            pendingSnapshot = snapshot
            deferredDirty = true
            lastDeferredAt = Date()
            guard !deferredArmed else { return }   // one timer; it re-arms itself
            deferredArmed = true
            scheduleIdleFlush(after: Self.idleFlushInterval)
        }
    }

    private func scheduleIdleFlush(after delay: TimeInterval) {   // saveQueue only
        saveQueue.asyncAfter(deadline: .now() + delay) { [self] in
            guard deferredArmed else { return }
            // Still typing? Wait out the remainder rather than writing mid-burst.
            let quiet = Date().timeIntervalSince(lastDeferredAt)
            if quiet < Self.idleFlushInterval {
                scheduleIdleFlush(after: Self.idleFlushInterval - quiet)
                return
            }
            deferredArmed = false
            guard deferredDirty, let snap = pendingSnapshot else { return }
            schedule(snapshot: snap)
        }
    }

    /// §1's flush points, other than backgrounding: the composer losing focus
    /// and the screen being left. Writes whatever is held lazily, on the
    /// ordinary debounce; a no-op when nothing is held, so callers may fire it
    /// as liberally as they like.
    func flushDeferred() {
        saveQueue.async { [self] in
            guard deferredDirty, let snap = pendingSnapshot else { return }
            deferredArmed = false
            schedule(snapshot: snap)
        }
    }

    private func writeNow() {                // saveQueue only
        if dead { pendingSnapshot = nil; deferredDirty = false; return }
        guard let snap = pendingSnapshot else { return }
        pendingSnapshot = nil
        // Whatever was held lazily is in this snapshot and is about to land.
        deferredDirty = false
        guard let payload = try? JSONEncoder().encode(snap) else { return }

        if isLegacyFile {
            // Migration could not be verified (or the blob will not fit a
            // slot): keep writing the shape that is already on disk rather
            // than risk a file that no longer opens.
            guard let sealed = try? AES.GCM.seal(payload, using: key).combined else { return }
            // "until first unlock" (not "complete"): a background push wake must be able to persist
            // what it pulled while the phone is locked; the blob is AES-GCM under the password key anyway
            try? sealed.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            Self.onWrite?(url, sealed.count)
            return
        }
        guard let index = slotIndex else { return }

        // The half that is NOT live, one past the live seq.
        let targetHalf = mustAuthorContainer ? 0 : (1 - liveHalf)
        let targetSeq = max(liveSeq + 1, SlotVault.firstSeq)

        let sealed: Data
        do {
            sealed = try SlotVault.seal(payload: payload, key: key, seq: targetSeq)
        } catch {
            // §1: there is no bucket to grow into. Keep the snapshot queued so
            // nothing is lost in memory and the next save retries, leave the
            // last good file on disk alone, and say so out loud.
            pendingSnapshot = snap
            storeFull = true
            Self.onStoreFull?(url, .cannotSave)
            return
        }

        do {
            if !mustAuthorContainer, containerIsIntact() {
                // the common path: 2 MiB at a fixed offset. The other half of
                // this slot, and both other slots, keep their bytes exactly.
                try writeHalfInPlace(sealed, slot: index, half: targetHalf)
                Self.onWrite?(url, SlotVault.halfBytes)
            } else if neverAuthor {
                // A decoy's store, and the file is not a container it can seek
                // into. Authoring here would replace the whole file — the
                // account that created this decoy included. Drop the write and
                // keep the snapshot; nothing on disk is touched.
                pendingSnapshot = snap
                return
            } else {
                // A store that declared itself new, or a file that is not a
                // container this build can seek into (never one we opened: that
                // one is intact, and re-authoring it would destroy slots we
                // cannot read). Both of the other slots get their noise here,
                // once, and are never written again.
                try Self.authoredContainer(mine: sealed, at: index)
                    .write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                mustAuthorContainer = false
                Self.onWrite?(url, SlotVault.fileBytes)
            }
            liveHalf = targetHalf
            liveSeq = targetSeq
            storeFull = false
        } catch {
            // disk full, protection class not yet available: keep the snapshot
            // so the next save retries rather than dropping the edit
            pendingSnapshot = snap
        }
    }

    /// The store is about to be obliterated while its owner may still hold
    /// it (a CREATE whose `register` failed): drop the debounced write that
    /// is still queued and refuse every later one, so a 250 ms timer cannot
    /// re-create the file after `obliterate()` — under a key whose salt is
    /// gone, which would leave an undecryptable zombie store behind.
    func discardPendingWrites() {
        saveQueue.sync {
            dead = true
            pendingSnapshot = nil
            deferredDirty = false
            deferredArmed = false
        }
    }

    /// Flush synchronously. §1's loudest flush point: the app backgrounding,
    /// which takes anything held lazily with it.
    ///
    /// A flush with nothing outstanding is FREE. `pendingSnapshot == nil` means
    /// the last write succeeded with the newest blob, so there is nothing on
    /// disk to correct — and this used to spend 2 MiB proving that on every
    /// single background, lock and shutdown, whether or not anything had
    /// changed. That is the same §1 amplification the drafts had, arriving by
    /// a different door.
    func flush() {
        let snap = blob
        saveQueue.sync {
            deferredArmed = false
            guard pendingSnapshot != nil else { return }
            pendingSnapshot = snap           // always the newest
            writeNow()
        }
    }

    // MARK: attachment sidecar store (image/file bytes)

    /// Sibling "attachments/" next to the store file — test stores at custom
    /// URLs isolate for free.
    static func attachmentsDir(for storeURL: URL) -> URL {
        storeURL.deletingLastPathComponent()
            .appendingPathComponent("attachments", isDirectory: true)
    }
    var attachmentsDir: URL { Self.attachmentsDir(for: url) }

    /// ids MUST be UUID strings — blocks path traversal from a hostile peer.
    private static func validAttachmentId(_ id: String) -> Bool {
        UUID(uuidString: id) != nil
    }

    func attachmentURL(id: String) -> URL {
        attachmentsDir.appendingPathComponent(id)
    }

    /// AES-256-GCM seal under the store key; NOT debounced — the file must
    /// exist before the ChatMessage referencing it is appended.
    func writeAttachment(id: String, data: Data) throws {
        guard Self.validAttachmentId(id) else { throw StoreError.io }
        try FileManager.default.createDirectory(at: attachmentsDir,
                                                withIntermediateDirectories: true)
        guard let sealed = try? AES.GCM.seal(data, using: key).combined else {
            throw StoreError.io
        }
        try sealed.write(to: attachmentURL(id: id), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func readAttachment(id: String) -> Data? {
        guard Self.validAttachmentId(id),
              let sealed = try? Data(contentsOf: attachmentURL(id: id)),
              let box = try? AES.GCM.SealedBox(combined: sealed),
              let plain = try? AES.GCM.open(box, using: key) else { return nil }
        return plain
    }

    func deleteAttachment(id: String) {
        guard Self.validAttachmentId(id) else { return }
        try? FileManager.default.removeItem(at: attachmentURL(id: id))
    }

    // MARK: wipe

    /// Transient decrypted copies (QuickLook previews, share exports) and the
    /// metadata scrubber's working copies (docs/SCRUB-AND-WIPE.md §B) live in
    /// tmp as export-*/preview-*/scrub-* — swept at launch and on obliterate
    /// so a crash mid-preview can never leave plaintext behind a duress wipe.
    static func sweepPlaintextTmp() {
        let tmp = FileManager.default.temporaryDirectory
        guard let entries = try? FileManager.default.contentsOfDirectory(
            at: tmp, includingPropertiesForKeys: nil) else { return }
        for entry in entries where
            entry.lastPathComponent.hasPrefix("export-")
            || entry.lastPathComponent.hasPrefix("preview-")
            || entry.lastPathComponent.hasPrefix("waveform-")
            || entry.lastPathComponent.hasPrefix("vn-")
            || entry.lastPathComponent.hasPrefix("scrub-")
            // docs/DECOY-ACCOUNT.md §7: a container staged for migration and
            // abandoned by a crash before it replaced the original
            || entry.lastPathComponent.hasPrefix("migrate-") {
            try? FileManager.default.removeItem(at: entry)
        }
    }

    /// PANIC WIPE: destroy the store file, every attachment, every keychain
    /// item, and any transient decrypted tmp copies — leaving the device
    /// indistinguishable from a fresh install.
    static func obliterate() {
        // docs/BIOMETRIC-UNLOCK.md §5: the biometrically-held copy of the key
        // goes first, unconditionally, before anything else — a wipe that
        // left it behind would leave the store openable by a face.
        BiometricUnlock.destroy()
        // docs/DECOY-ACCOUNT.md §5: NO path deletes the container. Every slot
        // it held is gone — this is `slotCount` slots of fresh noise — but the
        // file is still there and still 12,582,925 bytes, exactly as it is on
        // a device that has never held an account. A wipe that removed the
        // file would make the next launch say so, and a launch that can say
        // "nothing here" is a launch that can also say "something here".
        try? writeNoiseContainer(at: storeURL)
        try? FileManager.default.removeItem(at: attachmentsDir(for: storeURL))
        sweepPlaintextTmp()
        KeychainHelper.wipeAll()
        // a dead-man deadline must never outlive the identity it guarded —
        // the next identity on this device starts clean, and no future-dated
        // artifact is left behind for forensics
        DeadmanSwitch.disarm()
        // operator notices + seen ids live in UserDefaults (public data, kept
        // out of the store on purpose) — still nothing may outlive the wipe
        NoticeStore.wipe()
    }
}

extension SecureStore.Blob {
    private enum CodingKeys: String, CodingKey {
        case usernameDisplay, usernameHash, identityKeyPair, registrationId, authPrivateKey
        case sessions, preKeys, signedPreKeys, kyberPreKeys, knownIdentities, nextPreKeyId
        case contacts, messages, accessTokens, settings
        case receiptOutbox, notes, blockedHashes, drafts
        case groups
        case deliveryKey, peerKeys
        case knownSlots, allocatedSlots
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        usernameDisplay = try c.decodeIfPresent(String.self, forKey: .usernameDisplay) ?? ""
        usernameHash = try c.decodeIfPresent(String.self, forKey: .usernameHash) ?? ""
        identityKeyPair = try c.decodeIfPresent(Data.self, forKey: .identityKeyPair) ?? Data()
        registrationId = try c.decodeIfPresent(UInt32.self, forKey: .registrationId) ?? 0
        authPrivateKey = try c.decodeIfPresent(Data.self, forKey: .authPrivateKey) ?? Data()
        sessions = try c.decodeIfPresent([String: Data].self, forKey: .sessions) ?? [:]
        preKeys = try c.decodeIfPresent([UInt32: Data].self, forKey: .preKeys) ?? [:]
        signedPreKeys = try c.decodeIfPresent([UInt32: Data].self, forKey: .signedPreKeys) ?? [:]
        kyberPreKeys = try c.decodeIfPresent([UInt32: Data].self, forKey: .kyberPreKeys) ?? [:]
        knownIdentities = try c.decodeIfPresent([String: Data].self, forKey: .knownIdentities) ?? [:]
        nextPreKeyId = try c.decodeIfPresent(UInt32.self, forKey: .nextPreKeyId) ?? 1
        contacts = try c.decodeIfPresent([Contact].self, forKey: .contacts) ?? []
        messages = try c.decodeIfPresent([String: [ChatMessage]].self, forKey: .messages) ?? [:]
        accessTokens = try c.decodeIfPresent([AccessToken].self, forKey: .accessTokens) ?? []
        settings = try c.decodeIfPresent(AppSettings.self, forKey: .settings) ?? AppSettings()
        receiptOutbox = try c.decodeIfPresent(ReceiptOutbox.self, forKey: .receiptOutbox)
        notes = try c.decodeIfPresent([Note].self, forKey: .notes)
        blockedHashes = try c.decodeIfPresent([String].self, forKey: .blockedHashes)
        drafts = try c.decodeIfPresent([String: String].self, forKey: .drafts)
        groups = try c.decodeIfPresent([Group].self, forKey: .groups)
        // lenient: a malformed entry here must never make the store unopenable. A lost key
        // costs one message on the old path; a store that will not open costs everything.
        deliveryKey = (try? c.decodeIfPresent(Data.self, forKey: .deliveryKey)).flatMap { $0.count == 32 ? $0 : nil }
        peerKeys = try? c.decodeIfPresent([String: Sealed.PeerKey].self, forKey: .peerKeys)
        knownSlots = try c.decodeIfPresent([Int].self, forKey: .knownSlots)
        allocatedSlots = try c.decodeIfPresent([Int].self, forKey: .allocatedSlots)
    }
}
