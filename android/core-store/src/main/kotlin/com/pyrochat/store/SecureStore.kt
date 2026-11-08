package com.pyrochat.store

import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.DuressVerifier
import com.pyrochat.store.crypto.Kdf
import com.pyrochat.store.crypto.Pbkdf2HmacSha256
import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.store.model.Blob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Port of `ios/PyroChat/Core/SecureStore.swift`: everything the app persists lives in ONE file,
 * `pyro.store`, plus a sibling `attachments/<uuid>` sidecar per image/file sealed under the SAME
 * key (SPEC.md §1-3, §8).
 *
 * That file is now the three-slot [StoreContainer] of docs/DECOY-ACCOUNT.md §1: one slot holds
 * `AES-256-GCM(length ‖ JSON(Blob) ‖ padding)` under `PBKDF2(password, kdf-salt)` — the same key
 * derivation and the same `JSON(Blob)` as before — and the others are noise. A store opened from a
 * pre-container file (no magic) migrates on unlock, once, in [open] (§7). ONE instance owns ONE
 * slot ([slot]) and, after the container exists, writes ONLY that slot's fixed range: never write to
 * a slot you cannot open, because refreshing noise and destroying a decoy are one operation.
 *
 * Write path (`SecureStore.swift:143-172`): [update] swaps in the new [Blob] on the caller's thread
 * and hands the snapshot to a single-threaded save queue that debounces 0.25 s — the newest
 * snapshot wins, one write per burst — and [flush] writes synchronously (backgrounding, shutdown).
 * The queue is a one-thread executor wrapped as a coroutine dispatcher; the debounce is a
 * `delay(250)` inside a coroutine on it, so `pendingSnapshot`/`savePending` are only ever touched
 * on that thread, exactly like the `DispatchQueue` in Swift. Whole-file writes (creation,
 * migration, every attachment sidecar) are atomic — temp file, fsync, rename; a save into an
 * existing container is a positional overwrite of one slot's range plus an fsync, which cannot
 * change the file's length and cannot reach another slot's bytes. iOS's `completeFileProtection`
 * has no JVM equivalent — at-rest secrecy is the AES-GCM layer itself.
 *
 * Contract inherited from iOS: call [update] from one thread at a time; the app does so from its
 * main thread. Reads of [blob] are lock-free.
 */
class SecureStore private constructor(
    /** The store file; attachments live in `attachments/` next to it. */
    val file: Path,
    private val key: ByteArray,
    initial: Blob,
    private val random: SecureRandom,
    /**
     * The container slot this session owns (docs/DECOY-ACCOUNT.md §1-2). Its own index only: a
     * session can neither name nor count any other slot, and the index is never shown anywhere.
     */
    val slot: Int,
    /** Which half of [slot] holds the live copy; a save writes the other one (§1). */
    liveHalf: Int,
    /** The live half's write counter; a save writes `seq + 1`. */
    liveSeq: Long,
    /**
     * null for an ordinary unlock, which changes nothing. Non-null when THIS call AUTHORED the
     * account, and then true iff it also authored the container — [create], [restore] and the §7
     * migration write the other slots' noise with their own hand and therefore know every index is
     * free, while an account put into a slot someone else freed knows only its own. Either way the
     * record is stamped explicitly, because §1's absent value means "only my own slot" and must
     * keep meaning that.
     */
    authoredContainer: Boolean? = null,
    /**
     * True when this store owns a slot nothing has been written into yet. §1: "a freshly authored
     * slot writes one real half; the other stays noise" — so the FIRST save goes to half A at
     * [StoreContainer.FIRST_SEQ] rather than to the other half at `seq + 1`, whether or not a
     * container already existed when the account was made.
     */
    freshSlot: Boolean = false,
) : AutoCloseable {

    /** save thread only — cleared by the first save (see [freshSlot]). */
    private var pendingFreshSlot = freshSlot

    /** save thread only, after construction */
    @Volatile
    var half: Int = liveHalf
        private set

    /** save thread only, after construction */
    @Volatile
    var seq: Long = liveSeq
        private set

    @Volatile
    var blob: Blob = if (authoredContainer != null) {
        // docs/DECOY-ACCOUNT.md §1: whatever authored this account stamps the record from the
        // situation it is actually in, and OVERWRITES anything that came with the blob — a backup
        // restored onto a new phone must never inherit the old phone's idea of which slots are
        // taken, because on this device those indices are noise.
        initial.copy(
            allocatedSlots = listOf(slot),
            knownSlots = if (authoredContainer) ALL_SLOTS else listOf(slot),
        )
    } else {
        initial
    }
        private set

    /** Last swallowed background write failure, for diagnostics (iOS uses `try?` and drops it). */
    @Volatile
    var lastWriteError: Throwable? = null
        private set

    /**
     * True when this unlock rewrote a pre-container file as a container (docs/DECOY-ACCOUNT.md §7).
     * False when the file was already a container — or when the migration was abandoned, in which
     * case [lastWriteError] says why and the old file is still on disk, intact and openable.
     */
    @Volatile
    var migratedLegacyFile: Boolean = false
        private set

    /**
     * How many inline voice notes this unlock moved out of the blob and into sidecars
     * (docs/DECOY-ACCOUNT.md §1a). Non-zero only on the unlock that migrated or restored.
     */
    @Volatile
    var spilledVoiceNotes: Int = 0
        private set

    /**
     * Non-null when this unlock did NOT migrate because the account is still larger than a slot
     * half after the §1a voice spill — docs/DECOY-ACCOUNT.md §1, "never trim without being asked".
     *
     * Nothing was written and nothing was deleted: the store is open, it is still in its legacy
     * shape on disk, it still saves, and the app works exactly as before. It simply does not gain a
     * decoy until the user decides what to give up, which is wave 2 interface work (export a backup,
     * then trim, with the user pressing the button). This field is that condition, for the UI.
     */
    @Volatile
    var tooLargeToMigrate: StoreError.TooLarge? = null
        private set

    /**
     * True while this store's file is a pre-container single sealed blob: opened without
     * [open]'s `migrateLegacy`, or opened with it and left alone because of [tooLargeToMigrate].
     * Saves keep writing the legacy shape — a store is never silently converted outside the one
     * verified migration path.
     */
    @Volatile
    var legacyFormat: Boolean = false
        private set

    /**
     * A copy of the 32-byte key this store was opened with — the ONE thing
     * docs/BIOMETRIC-UNLOCK.md §1 puts into hardware-backed storage. The password stays where it
     * always was: nowhere. Callers zero the copy once they are done with it.
     */
    val storeKey: ByteArray get() = key.copyOf()

    private val saveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ca.pyrochat.store-save").apply { isDaemon = true }
    }
    private val saveDispatcher = saveExecutor.asCoroutineDispatcher()
    private val saveScope = CoroutineScope(SupervisorJob() + saveDispatcher)
    private var pendingSnapshot: Blob? = null   // save thread only
    private var savePending = false             // save thread only

    /** save thread only — the blob the file already holds, so an unchanged flush writes nothing. */
    private var writtenSnapshot: Blob? = null
    /** save thread only — the [Durability.LAZY] timer, or null when nothing lazy is waiting. */
    private var lazyJob: Job? = null
    /** save thread only — monotonic ms by which the oldest waiting lazy change must be written. */
    private var lazyDeadline = 0L

    /**
     * How many times this store has actually sealed and written its slot, and how many bytes went
     * to disk doing it — docs/DECOY-ACCOUNT.md §1, "What a save costs, measured". The measurement's
     * reachable surface: a test asserts a keystroke moves neither of these.
     */
    @Volatile
    var saveCount: Long = 0L
        private set

    @Volatile
    var bytesWritten: Long = 0L
        private set

    /**
     * MEASUREMENT SEAM, and nothing else: treat every [Durability.LAZY] update as
     * [Durability.PROMPT], which is exactly how this store behaved before docs/DECOY-ACCOUNT.md §1
     * split saves by durability. It exists so the before/after of §1's "What a save costs,
     * measured" stays runnable rather than being a number in a document, and so a regression in the
     * split shows up as the before figure coming back. The app never sets it.
     */
    @Volatile
    var measureWithoutLazyLane: Boolean = false

    /**
     * The §1 idle wait and its cap, per store so a test can drive the timer in milliseconds rather
     * than in half-minutes. The app leaves both at [LAZY_IDLE_MS] / [LAZY_MAX_MS].
     */
    @Volatile
    var lazyIdleMs: Long = LAZY_IDLE_MS

    @Volatile
    var lazyMaxMs: Long = LAZY_MAX_MS

    val attachmentsDir: Path get() = attachmentsDir(file)

    // MARK: write path

    /**
     * Mutate-and-save: [mutate] returns the next blob. [durability] says how soon it has to reach
     * the disk — [Durability.PROMPT] keeps the 250 ms debounce of `SecureStore.swift:143-146`,
     * [Durability.LAZY] waits for a reason to write (docs/DECOY-ACCOUNT.md §1).
     */
    fun update(durability: Durability = Durability.PROMPT, mutate: (Blob) -> Blob) {
        val next: Blob
        synchronized(this) {
            next = mutate(blob)
            blob = next
        }
        when (if (measureWithoutLazyLane) Durability.PROMPT else durability) {
            Durability.PROMPT -> persist(next)
            Durability.LAZY -> persistLazily(next)
        }
    }

    private fun persist(snapshot: Blob) {
        saveScope.launch {
            pendingSnapshot = snapshot          // always keep the newest
            if (savePending) return@launch
            savePending = true
            delay(DEBOUNCE_MS)
            savePending = false
            try {
                writeNow()
            } catch (e: Exception) {
                lastWriteError = e               // swallowed, like iOS `try?`
            }
        }
    }

    /**
     * §1: a lazy change never schedules a write of its own. It rides the next prompt save, the next
     * [flush], or — if nothing else happens — [LAZY_IDLE_MS] of quiet. [LAZY_MAX_MS] caps how long
     * an unbroken run of lazy changes can defer the write, so a user who types for ten minutes
     * without pausing still has a draft on disk.
     */
    private fun persistLazily(snapshot: Blob) {
        saveScope.launch {
            pendingSnapshot = snapshot
            if (savePending) return@launch      // a prompt save is already coming; it carries this too
            val now = System.nanoTime() / 1_000_000L
            if (lazyDeadline == 0L) lazyDeadline = now + lazyMaxMs
            lazyJob?.cancel()
            lazyJob = saveScope.launch {
                delay(minOf(lazyIdleMs, (lazyDeadline - now).coerceAtLeast(0L)))
                lazyJob = null
                lazyDeadline = 0L
                try {
                    writeNow()
                } catch (e: Exception) {
                    lastWriteError = e
                }
            }
        }
    }

    /**
     * save thread only. Rewrites ONE HALF of THIS SESSION'S SLOT and nothing else
     * (docs/DECOY-ACCOUNT.md §1).
     *
     * - Never a slot it cannot open: the other slots are not read, not rewritten, not touched, so a
     *   save from the real account cannot destroy a decoy — nor "refresh" noise, which is the same
     *   operation.
     * - Never the live half: an in-place write cannot be atomic, so the new copy goes to the OTHER
     *   half at `seq + 1`. A power cut mid-write leaves that half torn — and a torn half
     *   authenticates nothing at all — while the half holding the last good copy was never opened
     *   for writing, so the next unlock finds it and the account is intact, one save behind.
     *
     * Only when there is no container yet — the very first save after [create]/[restore] — is the
     * whole file written, with the unused slots and the second half filled with noise this once.
     */
    private fun writeNow() {
        val snapshot = pendingSnapshot ?: return
        pendingSnapshot = null
        // the file already holds exactly this blob — a flush of an untouched store writes nothing
        if (snapshot === writtenSnapshot) return
        lazyJob?.cancel()                        // whatever was waiting lazily is in this write
        lazyJob = null
        lazyDeadline = 0L
        val json = SwiftJson.encodeBlob(snapshot)
        if (legacyFormat) {
            // §1: an unmigrated store keeps working exactly as before — same shape, same atomic write
            val sealed = StoreCrypto.seal(key, json, random)
            atomicWrite(file, sealed)
            noteWrite(snapshot, sealed.size.toLong())
            return
        }
        if (readLayout(file) == null) {
            val container = StoreContainer.buildNew(key, json, slot, random)
            atomicWrite(file, container)
            half = 0
            seq = StoreContainer.FIRST_SEQ
            noteWrite(snapshot, container.size.toLong())
            return
        }
        val nextHalf = if (pendingFreshSlot) 0 else StoreContainer.otherHalf(half)
        val nextSeq = if (pendingFreshSlot) StoreContainer.FIRST_SEQ else seq + 1
        pendingFreshSlot = false
        val region = StoreContainer.sealHalf(key, json, nextSeq, random)   // TooLarge before anything is written
        writeRange(file, StoreContainer.halfRange(slot, nextHalf).first.toLong(), region)
        half = nextHalf
        seq = nextSeq
        noteWrite(snapshot, region.size.toLong())
    }

    /** save thread only: the file now holds [snapshot], and it cost [bytes]. */
    private fun noteWrite(snapshot: Blob, bytes: Long) {
        writtenSnapshot = snapshot
        saveCount += 1
        bytesWritten += bytes
    }

    // MARK: docs/DECOY-ACCOUNT.md §1 — allocation, and the one thing a decoy cannot do

    /**
     * The slot indices this account has allocated: [Blob.allocatedSlots] plus its own, always. §1:
     * "Each account records, inside its own encrypted plaintext, the slot indices it has allocated."
     */
    val allocatedSlots: List<Int> get() = ((blob.allocatedSlots ?: emptyList()) + slot).distinct().sorted()

    /**
     * The slot indices whose state this account knows.
     *
     * **Absent means "only my own slot", which is the least this can mean and therefore the only
     * safe default.** Authoring a container writes the record explicitly ([ALL_SLOTS]); anything
     * that finds it missing assumes the least. The other reading — absent means "I authored this,
     * so I know every index" — is the dangerous one: a record lost in a round trip through a
     * platform that does not know the key would silently re-grant permission to allocate over a
     * slot the account cannot see, which is exactly the coin flip on destroying someone's messages
     * that this whole mechanism exists to prevent.
     */
    val knownSlots: List<Int>
        get() = blob.knownSlots ?: listOf(slot)

    /**
     * Where a new account may safely go: a slot this one knows about and has not allocated.
     *
     * Empty inside a decoy, and that is §1's "a decoy cannot create another" — not a rule written
     * anywhere and not a flag anything renders, just a subtraction that comes out empty. The
     * interface never sees the difference; only the outcome does.
     */
    val freeSlots: List<Int>
        get() = if (allocatedSlots.size >= MAX_ALLOCATIONS) emptyList() else knownSlots.filter { it !in allocatedSlots }

    /**
     * §1/§3 — author a brand-new account into a slot this one KNOWS is free, and record the
     * allocation. Returns the index used.
     *
     * The record is written and flushed BEFORE the slot is touched: a crash between the two costs
     * a free slot, which is nothing, while the other order could let a later allocation land on top
     * of the account just written, which is everything.
     *
     * Refuses when there is no container yet ([legacyFormat]) — there is nowhere to put a slot —
     * and when this account knows of no free slot, which is the ordinary case inside a decoy.
     */
    fun authorSlot(newKey: ByteArray, newBlob: Blob, chooser: (List<Int>) -> Int = { it.first() }): Int {
        require(newKey.size == StoreCrypto.KEY_BYTES) { "a slot key is ${StoreCrypto.KEY_BYTES} bytes" }
        if (legacyFormat) throw StoreError.NoFreeSlot()
        // an account created moments ago may still be inside its 250 ms debounce, and there is
        // nowhere to put a slot until its container is actually on disk
        flush()
        if (readLayout(file) == null) throw StoreError.NoFreeSlot()
        // §3: the row creates OR REPLACES. A slot this account already allocated is one it may
        // write again — it put the account there, so nothing it cannot see can be lost — and
        // without that a mistyped decoy password would be unrecoverable short of wiping the phone.
        val candidates = if (freeSlots.isNotEmpty()) freeSlots else allocatedSlots.filter { it != slot }
        if (candidates.isEmpty()) throw StoreError.NoFreeSlot()
        val target = chooser(candidates)
        require(target in candidates) { "chooser returned a slot this account may not write" }
        // the record first, and on disk, before a single byte of the slot moves
        update { it.copy(allocatedSlots = (allocatedSlots + target).distinct().sorted()) }
        flush()
        try {
            // §1: the new account knows its own index and nothing else
            val json = SwiftJson.encodeBlob(newBlob.copy(allocatedSlots = listOf(target), knownSlots = listOf(target)))
            writeRange(file, StoreContainer.slotRange(target).first.toLong(), StoreContainer.newSlot(newKey, json, random))
        } catch (e: Exception) {
            releaseSlot(target)
            throw e
        }
        return target
    }

    /** §3: the slot this account gave to a decoy, or null. Only the owning account can know this. */
    val allocatedDecoySlot: Int? get() = allocatedSlots.firstOrNull { it != slot }

    /**
     * Undo an [authorSlot] whose account never came to exist — a registration the relay refused.
     * The slot goes back to CSPRNG noise and the allocation record gives the index up again, so a
     * mistyped username does not cost the one decoy this account is allowed.
     */
    fun releaseSlot(target: Int) {
        require(target != slot) { "an account cannot release its own slot" }
        runCatching { writeRange(file, StoreContainer.slotRange(target).first.toLong(), StoreCrypto.randomBytes(StoreContainer.SLOT_BYTES, random)) }
        update { it.copy(allocatedSlots = allocatedSlots.filter { i -> i != target }) }
        flush()
    }

    // MARK: docs/DECOY-ACCOUNT.md §5 — the voluntary wipe narrows to one slot

    /**
     * `[ BURN EVERYTHING ]` (§5): CSPRNG noise over THIS slot's 4 MiB, and every sidecar that opens
     * under THIS key deleted. Nothing else in the file is read or written, so the other slots keep
     * their bytes exactly — a person made to wipe under supervision loses only the account they
     * were made to show.
     *
     * Attribution of sidecars is by trial decryption, which is the only way there is: the ids are
     * opaque and the directory is shared on purpose (§6.2). A file that does not open under this
     * key is either another slot's or noise, and either way it is not ours to delete.
     *
     * The save queue stops first, so nothing can write the slot back afterwards.
     */
    fun burnSlot() {
        close()
        for (id in attachmentIds()) {
            if (readAttachment(id) != null) deleteAttachment(id)
        }
        if (legacyFormat || readLayout(file) == null) {
            // a store that never became a container: replace it with the all-noise container a
            // first run would have written (§5), never with nothing
            runCatching { atomicWrite(file, StoreContainer.buildEmpty(random)) }
            return
        }
        writeRange(file, StoreContainer.slotRange(slot).first.toLong(), StoreCrypto.randomBytes(StoreContainer.SLOT_BYTES, random))
    }

    /**
     * docs/DECOY-ACCOUNT.md §5 — there is deliberately NO "this account is alone, so burn the whole
     * device" case. An earlier draft had one, and it was wrong: behaving differently in the common
     * case is exactly what makes the uncommon case legible. Every burn leaves the same
     * [StoreContainer.FILE_BYTES] behind, and the slots this account cannot open keep their bytes.
     */
    val burnLeavesTheContainer: Boolean get() = true

    /**
     * Synchronous write of the current blob (`SecureStore.swift:169-172`) — and the moment every
     * [Durability.LAZY] change has been waiting for: the composer losing focus, leaving the screen,
     * backgrounding, locking. A debounced write still scheduled afterwards finds nothing pending
     * and is a no-op, and a store whose blob is already on disk writes nothing at all. Throws
     * [StoreError.Io] on failure and [StoreError.TooLarge] when the account outgrew its slot — the
     * file keeps its last good contents either way.
     */
    fun flush() {
        val snapshot = blob
        try {
            runBlocking {
                withContext(saveDispatcher) {
                    pendingSnapshot = snapshot
                    writeNow()
                }
            }
        } catch (e: IOException) {
            throw StoreError.Io("could not write $file", e)
        }
    }

    // MARK: docs/DECOY-ACCOUNT.md §1a — the audio leaves the blob before the container exists

    /**
     * Move every inline `voiceData` into a sidecar. Used on the restore path, where an older backup
     * still carries its audio inline. A sidecar that cannot be written leaves the row inline rather
     * than losing the recording — better an oversized blob, which is refused loudly, than silence.
     */
    private fun spillInlineVoice() {
        val plan = VoiceSpill.plan(blob)
        if (plan.isEmpty) return
        val written = ArrayList<String>()
        try {
            for ((id, audio) in plan.sidecars) {
                writeAttachment(id, audio)
                written += id
            }
        } catch (e: StoreError) {
            for (id in written) deleteAttachment(id)
            lastWriteError = e
            return
        }
        blob = plan.blob
        spilledVoiceNotes = plan.sidecars.size
    }

    /**
     * The one-way migration of docs/DECOY-ACCOUNT.md §7, with §1a's ordering, which is the whole
     * point of §1a:
     *
     * 1. the legacy file is already unlocked, whatever its size — legacy has no limit,
     * 2. every inline `voiceData` moves to a sidecar, in memory,
     * 3. the blob is now small,
     * 4. and only then is the container written.
     *
     * Container-first is the bug this order exists to prevent. Nothing is adopted in memory and no
     * sidecar is left behind until the new file has been written, read back and reopened: on any
     * failure the sidecars are deleted again and the legacy file is still the only copy, intact.
     */
    private fun migrateFromLegacy(legacyJson: ByteArray, verifyKey: ByteArray) {
        // §1: the migration authors the container, so it knows every index and says so explicitly
        val migrated = VoiceSpill.plan(blob).let { it.copy(blob = it.blob.copy(allocatedSlots = listOf(slot), knownSlots = ALL_SLOTS)) }
        val plan = migrated
        val json = SwiftJson.encodeBlob(plan.blob)
        if (json.size > StoreContainer.MAX_JSON_BYTES) {
            // §1 "never trim without being asked": do not migrate it, and do not delete anything.
            // Not one sidecar is written and the blob is left as it was — the spill is rolled back
            // by never having happened.
            tooLargeToMigrate = StoreError.TooLarge(json.size, StoreContainer.MAX_JSON_BYTES)
            return
        }
        val written = ArrayList<String>()
        try {
            for ((id, audio) in plan.sidecars) {
                writeAttachment(id, audio)
                written += id
            }
            migrateLegacyFile(file, key, json, slot, random, verifyKey)
        } catch (e: Exception) {
            for (id in written) deleteAttachment(id)     // no orphans, nothing adopted
            throw e
        }
        blob = plan.blob                                 // in memory == on disk
        migratedLegacyFile = true
        legacyFormat = false
        spilledVoiceNotes = plan.sidecars.size
    }

    /** Stops the save queue WITHOUT flushing (an obliterated store must stay gone). */
    override fun close() {
        saveScope.cancel()
        saveExecutor.shutdown()
    }

    // MARK: attachment sidecars (`SecureStore.swift:174-216`)

    fun attachmentPath(id: String): Path = attachmentsDir.resolve(id)

    /**
     * Seals [data] under the store key and writes `attachments/<id>` synchronously — NOT debounced,
     * so the file exists before the row referencing it is appended. Refuses ids that are not UUID
     * strings ([StoreError.Io]) — the path-traversal guard against a hostile peer.
     */
    fun writeAttachment(id: String, data: ByteArray) {
        if (!isValidAttachmentId(id)) throw StoreError.Io("attachment id must be a UUID string")
        try {
            Files.createDirectories(attachmentsDir)
            atomicWrite(attachmentPath(id), StoreCrypto.seal(key, data, random))
        } catch (e: IOException) {
            throw StoreError.Io("could not write attachment $id", e)
        }
    }

    /** Decrypted sidecar bytes, or null for a bad id, a missing file or a GCM failure. */
    fun readAttachment(id: String): ByteArray? {
        if (!isValidAttachmentId(id)) return null
        return try {
            StoreCrypto.open(key, Files.readAllBytes(attachmentPath(id)))
        } catch (_: IOException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    fun deleteAttachment(id: String) {
        if (!isValidAttachmentId(id)) return
        try {
            Files.deleteIfExists(attachmentPath(id))
        } catch (_: IOException) {
            // like iOS `try? removeItem`
        }
    }

    /** Sidecar file names that parse as UUIDs — what a backup export enumerates (`BackupService.swift:39-46`). */
    fun attachmentIds(): List<String> {
        if (!Files.isDirectory(attachmentsDir)) return emptyList()
        return Files.list(attachmentsDir).use { entries ->
            entries.map { it.fileName.toString() }.filter(::isValidAttachmentId).sorted().toList()
        }
    }

    companion object {
        const val FILE_NAME = "pyro.store"
        const val ATTACHMENTS_DIR = "attachments"
        const val DEBOUNCE_MS = 250L

        /** Every index in the container — what an account that authored the noise itself knows (§1). */
        val ALL_SLOTS: List<Int> = (0 until StoreContainer.SLOT_COUNT).toList()

        /**
         * docs/DECOY-ACCOUNT.md §1 — the "long idle timer" a [Durability.LAZY] change waits on when
         * nothing else writes first. Thirty seconds of quiet, so a draft survives a crash without a
         * keystroke ever costing 2 MiB.
         */
        const val LAZY_IDLE_MS = 30_000L

        /**
         * The cap on [LAZY_IDLE_MS]'s deferral: an unbroken run of lazy changes still reaches the
         * disk this often. Two minutes of typing without a single pause is the worst case, and two
         * minutes of an unsent draft is what §1 calls "not real damage".
         */
        const val LAZY_MAX_MS = 120_000L

        private val UUID_STRING = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** Swift `UUID(uuidString:)` accepts only the canonical 8-4-4-4-12 form (`SecureStore.swift:185-187`). */
        fun isValidAttachmentId(id: String): Boolean = UUID_STRING.matches(id)

        fun attachmentsDir(storeFile: Path): Path = storeFile.toAbsolutePath().parent.resolve(ATTACHMENTS_DIR)

        fun exists(file: Path): Boolean = Files.isRegularFile(file)

        /**
         * docs/DECOY-ACCOUNT.md §1: "**The first account can create one decoy.**" — so an account
         * may hold at most its own slot plus one it gave away, even though the container has three.
         *
         * The third slot therefore stays noise for the life of the device, which is the point: §1
         * fixes [StoreContainer.SLOT_COUNT] at 3 as a constant of the build precisely so the file
         * cannot say how many of them are real. A second decoy would fail for the real account in
         * exactly the words it fails in for a decoy, which is what makes the two cases
         * indistinguishable from the one session a person holding the phone ever sees.
         */
        const val MAX_ALLOCATIONS = 2

        /**
         * The salt for a READ — an unlock attempt. Returns the stored one, or a throwaway that is
         * deliberately NOT kept.
         *
         * A device with no `kdf-salt` has nothing sealed under any key, so an unlock cannot succeed
         * whatever salt is used, and minting one would be pure side effect. Two reasons not to:
         *
         * - docs/DECOY-ACCOUNT.md §1 reads the item's absence as "nothing has been created on this
         *   device since the last wipe", which is what lets a duress-wiped phone create an account
         *   that can still have a decoy. A failed password attempt must not erase that.
         * - §5 wants a wiped device and an untouched one to be identical. Someone typing a wrong
         *   password at a wiped phone should not leave a mark that says someone typed a password.
         */
        fun saltForRead(secrets: SecretStore, random: SecureRandom = SecureRandom()): ByteArray =
            secrets.get(SecretStore.KDF_SALT) ?: StoreCrypto.randomBytes(StoreCrypto.SALT_BYTES, random)

        /** The Keychain salt, created on first use (`SecureStore.swift:82-88`). */
        fun salt(secrets: SecretStore, random: SecureRandom = SecureRandom()): ByteArray {
            secrets.get(SecretStore.KDF_SALT)?.let { return it }
            val fresh = StoreCrypto.randomBytes(StoreCrypto.SALT_BYTES, random)
            secrets.set(SecretStore.KDF_SALT, fresh)
            return fresh
        }

        /** `SecureStore.deriveKey(password:salt:)` */
        fun deriveKey(kdf: Kdf, password: String, salt: ByteArray): ByteArray =
            StoreCrypto.deriveKey(kdf, password, salt)

        /**
         * Registration: a brand-new store holding an empty [Blob], written debounced like iOS
         * (`SecureStore.swift:106-111`). The account lands in a UNIFORMLY RANDOM slot, never slot 0
         * by default (docs/DECOY-ACCOUNT.md §2).
         */
        fun create(
            file: Path,
            password: String,
            secrets: SecretStore,
            kdf: Kdf = Pbkdf2HmacSha256,
            random: SecureRandom = SecureRandom(),
            knownFree: List<Int>? = null,
            blind: Boolean = false,
        ): SecureStore = author(file, Blob(), password, secrets, kdf, random, knownFree, blind) { }

        /**
         * docs/DECOY-ACCOUNT.md §5 — the container exists from first run, before any account, as
         * three slots of noise, and no path ever deletes it. Returns true when THIS call authored
         * it, which is the only moment anything can know every slot is free.
         *
         * Authoring it costs one pass of the CSPRNG over 12 MiB, once per device.
         */
        fun ensureContainer(file: Path, random: SecureRandom = SecureRandom()): Boolean {
            if (readLayout(file) != null) return false
            if (exists(file)) return false            // a pre-container legacy store: §7 migrates it
            atomicWrite(file, StoreContainer.buildEmpty(random))
            return true
        }

        /**
         * The shared body of [create] and [restore]: make sure a container exists, put the account
         * in a slot that is provably free, and let [seed] fill in anything else before the first
         * save.
         *
         * [knownFree] is what a caller may supply ONLY out of an account's own record or a slot it
         * has just burned itself, and only that slot is written. With none, [blind] rewrites the
         * whole container (§1, "Creating an identity from cold"); without [blind] there is nothing
         * that can be safely written and [StoreError.NoFreeSlot] says so in the app's ordinary
         * words, asserting nothing about what the container holds.
         */
        private fun author(
            file: Path,
            blob: Blob,
            password: String,
            secrets: SecretStore,
            kdf: Kdf,
            random: SecureRandom,
            knownFree: List<Int>?,
            blind: Boolean,
            seed: SecureStore.() -> Unit,
        ): SecureStore {
            val authored = ensureContainer(file, random)
            val targeted = !knownFree.isNullOrEmpty()
            // docs/DECOY-ACCOUNT.md §1, "Creating an identity from cold". Two shapes, and which one
            // runs is decided by KNOWLEDGE, never by anything read off the device:
            //
            //  - TARGETED: the caller can name slots it knows are free, out of an account it opened
            //    in this process. Only that slot is written; everything else keeps its bytes, which
            //    is what makes "burn the decoy, make a new one" possible at all.
            //  - WHOLE CONTAINER: a cold create. It rewrites ALL of it, claiming every slot rather
            //    than gambling on one, so the outcome is identical whatever the device held — there
            //    is nothing to compare and no signal to read — and afterwards the device is in a
            //    fully known state where a decoy simply works.
            //
            // The rejected alternative was to take one slot at random and prove the rest empty from
            // the absence of `kdf-salt`. It works, and it re-opens §5 through a different door: a
            // narrowed burn leaves the salt and a total burn destroys it, so whether a decoy can be
            // created AFTERWARDS would answer the question §5 exists to leave unanswered.
            // A file that is not a container at all — missing, or the pre-container single blob of
            // §7 — has no slots to gamble over, and `create` has always replaced it. Everything
            // else needs either a named free slot or the person's confirmation.
            val noContainer = readLayout(file) == null
            if (!targeted && !authored && !noContainer) {
                if (!blind) throw StoreError.NoFreeSlot()
                atomicWrite(file, StoreContainer.buildEmpty(random))   // every other slot is now noise we wrote
            }
            val candidates = if (targeted) knownFree!! else ALL_SLOTS
            val store = SecureStore(
                file,
                deriveKey(kdf, password, salt(secrets, random)),
                blob,
                random,
                candidates[random.nextInt(candidates.size)],
                liveHalf = 0,
                liveSeq = StoreContainer.FIRST_SEQ,
                // whole-container: it wrote the other slots' noise itself, so it knows every index.
                // Targeted: it knows only its own, because it did not touch the rest.
                authoredContainer = !targeted,
                freshSlot = true,
            )
            store.seed()
            store.persist(store.blob)
            return store
        }

        /**
         * docs/DECOY-ACCOUNT.md §5 — a brand-new account in ONE named slot of a container that
         * already exists, without touching any other slot's bytes.
         *
         * The one caller is the app reclaiming the slot its own `[ BURN EVERYTHING ]` just filled
         * with noise: the burn has to leave the create screen behind to be a convincing wipe, and a
         * create screen that answers "identity exists" would be a louder tell than the unlock
         * screen it was hiding. The caller must KNOW [slot] is free — here, because it burned it
         * itself moments ago.
         *
         * Falls back to [create] when there is no container yet.
         */
        fun createInSlot(
            file: Path,
            slot: Int,
            password: String,
            secrets: SecretStore,
            kdf: Kdf = Pbkdf2HmacSha256,
            random: SecureRandom = SecureRandom(),
        ): SecureStore {
            require(slot in 0 until StoreContainer.SLOT_COUNT) { "slot $slot is outside the container" }
            return create(file, password, secrets, kdf, random, knownFree = listOf(slot))
        }

        /**
         * Unlock (`SecureStore.swift:130-139`): [StoreError.NoStore] if the file is missing,
         * [StoreError.BadPassword] if no slot authenticates, [StoreError.Corrupt] if the plaintext
         * is not a valid blob.
         *
         * The derived key is tried against EVERY slot, always, in order — the first that
         * authenticates wins, and the loop still runs to the end so slot 0 is not measurably faster
         * to open than slot 2 (docs/DECOY-ACCOUNT.md §2).
         *
         * Writes nothing UNLESS [migrateLegacy] is set and the file is a pre-container one, in
         * which case this is the one-way migration of §7. It stays opt-in so that reading a
         * historical fixture can never rewrite it; the app passes true.
         */
        fun open(
            file: Path,
            password: String,
            secrets: SecretStore,
            kdf: Kdf = Pbkdf2HmacSha256,
            random: SecureRandom = SecureRandom(),
            migrateLegacy: Boolean = false,
        ): SecureStore {
            val bytes = readStoreFile(file)
            // an unlock READS: it never mints a salt onto a device that has none (see [saltForRead])
            val readSalt = saltForRead(secrets, random)
            val key = deriveKey(kdf, password, readSalt)
            // Re-derived from the typed password for the migration's read-back check, so the
            // verification really is "it reopens with the same password" and not "with this array".
            return openBytes(file, bytes, key, random, if (migrateLegacy) ({ deriveKey(kdf, password, readSalt) }) else null)
        }

        /**
         * Unlock with the key itself instead of a password — docs/BIOMETRIC-UNLOCK.md §3, where
         * biometry has just unwrapped the hardware-held copy. Same failure modes as [open]; nothing
         * here derives anything, so no KDF and no salt are involved. Every slot is tried, exactly
         * as with a password, so biometry behaves identically whichever slot the key belongs to.
         */
        fun openWithKey(
            file: Path,
            key: ByteArray,
            random: SecureRandom = SecureRandom(),
            migrateLegacy: Boolean = false,
        ): SecureStore {
            require(key.size == StoreCrypto.KEY_BYTES) { "store key must be ${StoreCrypto.KEY_BYTES} bytes" }
            val bytes = readStoreFile(file)
            return openBytes(file, bytes, key.copyOf(), random, if (migrateLegacy) ({ key.copyOf() }) else null)
        }

        private fun readStoreFile(file: Path): ByteArray = try {
            Files.readAllBytes(file)
        } catch (_: NoSuchFileException) {
            throw StoreError.NoStore(file)
        } catch (e: IOException) {
            throw StoreError.Io("could not read $file", e)
        }

        /**
         * The shared unlock body: a container is opened by trying every slot; anything without the
         * magic is today's single sealed blob and takes the legacy path (docs/DECOY-ACCOUNT.md §7).
         * [verifyKey], when non-null, both opts into migration and supplies the independently
         * obtained key the rewritten file must reopen with.
         */
        private fun openBytes(
            file: Path,
            bytes: ByteArray,
            key: ByteArray,
            random: SecureRandom,
            verifyKey: (() -> ByteArray)?,
        ): SecureStore {
            if (StoreContainer.isContainer(bytes)) {
                val opened = StoreContainer.open(key, bytes) ?: throw StoreError.BadPassword()
                return SecureStore(file, key, decodeBlob(opened.json), random, opened.slot, opened.half, opened.seq)
            }
            val json = try {
                StoreCrypto.open(key, bytes)
            } catch (_: GeneralSecurityException) {
                throw StoreError.BadPassword()
            }
            val blob = decodeBlob(json)                       // a bad blob is never migrated
            val slot = StoreContainer.randomSlot(random)
            // a migration writes half A at FIRST_SEQ; until one happens the file keeps its legacy
            // shape and so does every save
            val store = SecureStore(file, key, blob, random, slot, liveHalf = 0, liveSeq = StoreContainer.FIRST_SEQ)
            store.legacyFormat = true
            if (verifyKey != null) {
                try {
                    store.migrateFromLegacy(json, verifyKey())
                } catch (e: Exception) {
                    store.lastWriteError = e                  // the legacy file is untouched; retry next unlock
                }
            }
            return store
        }

        private fun decodeBlob(json: ByteArray): Blob = try {
            SwiftJson.decodeBlob(json)
        } catch (e: SerializationException) {
            throw StoreError.Corrupt(e)
        } catch (e: IllegalArgumentException) {
            throw StoreError.Corrupt(e)
        }

        /**
         * docs/DECOY-ACCOUNT.md §7, one way, once: rewrite the single sealed blob as a container
         * with the account in [slot] and the other two slots full of noise.
         *
         * A half-migrated store that no longer opens is the worst outcome this feature could have,
         * so the new file is written to a sibling temp file, fsynced, read back FROM DISK and fully
         * reopened with an independently derived [verifyKey] — same slot, byte-identical JSON,
         * decodes to a [Blob] — before the atomic rename. Any failure leaves the original in place
         * and throws; the caller swallows it and the user is simply unlocked from the old file.
         */
        private fun migrateLegacyFile(
            file: Path,
            key: ByteArray,
            json: ByteArray,
            slot: Int,
            random: SecureRandom,
            verifyKey: ByteArray,
        ) {
            val container = StoreContainer.buildNew(key, json, slot, random)
            val absolute = file.toAbsolutePath()
            val dir = absolute.parent
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, ".${absolute.fileName}.", ".vault")
            try {
                FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                    val buffer = ByteBuffer.wrap(container)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                val readBack = Files.readAllBytes(tmp)
                if (!readBack.contentEquals(container)) throw StoreError.Io("migrated store did not read back byte-identical")
                val reopened = StoreContainer.open(verifyKey, readBack)
                    ?: throw StoreError.Io("migrated store did not reopen with the same password")
                if (reopened.slot != slot) throw StoreError.Io("migrated store opened at slot ${reopened.slot}, expected $slot")
                if (reopened.half != 0 || reopened.seq != StoreContainer.FIRST_SEQ) {
                    throw StoreError.Io("migrated store opened at half ${reopened.half} seq ${reopened.seq}")
                }
                if (!reopened.json.contentEquals(json)) throw StoreError.Io("migrated store did not hold the same blob")
                decodeBlob(reopened.json)
                Files.move(tmp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                Files.deleteIfExists(tmp)
                throw e
            }
        }

        /**
         * Restore from a decoded backup (`SecureStore.swift:117-127`): every attachment is re-sealed
         * synchronously under THIS device's key, then the blob is written and flushed.
         */
        fun restore(
            file: Path,
            blob: Blob,
            attachments: Map<String, ByteArray>,
            password: String,
            secrets: SecretStore,
            kdf: Kdf = Pbkdf2HmacSha256,
            random: SecureRandom = SecureRandom(),
            knownFree: List<Int>? = null,
            blind: Boolean = false,
        ): SecureStore {
            val store = author(file, blob, password, secrets, kdf, random, knownFree, blind) {
                for ((id, bytes) in attachments) {
                    try {
                        writeAttachment(id, bytes)
                    } catch (_: StoreError.Io) {
                        // iOS: `try? writeAttachment` — a bad id or unwritable sidecar does not abort the restore
                    }
                }
                spillInlineVoice()          // §1a: a backup made before the spill carries audio inline
            }
            store.flush()
            return store
        }

        /** `SecureStore.setDuressPin` (`SecureStore.swift:92-95`). */
        fun setDuressPin(secrets: SecretStore, kdf: Kdf, pin: String, random: SecureRandom = SecureRandom()) {
            secrets.set(SecretStore.DURESS_VERIFIER, DuressVerifier.compute(kdf, pin, salt(secrets, random)))
        }

        /** `SecureStore.matchesDuressPin` (`SecureStore.swift:97-101`) — false when no verifier is set. */
        fun matchesDuressPin(secrets: SecretStore, kdf: Kdf, candidate: String, random: SecureRandom = SecureRandom()): Boolean {
            val stored = secrets.get(SecretStore.DURESS_VERIFIER) ?: return false
            return DuressVerifier.matches(kdf, candidate, salt(secrets, random), stored)
        }

        /**
         * Panic wipe (`SecureStore.swift:239-248`): the store file, every sidecar and every secret.
         * The app layers its own tmp sweep and dead-man disarm on top.
         */
        fun obliterate(file: Path, secrets: SecretStore, random: SecureRandom = SecureRandom()) {
            try {
                // docs/DECOY-ACCOUNT.md §5: no path ever deletes the container. Wiped, never used
                // and holding two accounts are the same 12,582,925 bytes, so the launch after a
                // wipe has nothing to read the difference off — and neither has an examiner.
                atomicWrite(file, StoreContainer.buildEmpty(random))
                val dir = attachmentsDir(file)
                if (Files.isDirectory(dir)) {
                    Files.list(dir).use { entries -> entries.forEach { Files.deleteIfExists(it) } }
                    Files.deleteIfExists(dir)
                }
            } catch (_: IOException) {
                // best effort, like iOS `try? removeItem`
            }
            secrets.wipeAll()
        }

        /**
         * The header of an existing container, or null when there is no file yet or the file is a
         * pre-container blob. Throws [StoreError.Corrupt] for a file that claims to be a container
         * and is not self-consistent — a store we do not understand is never written over.
         */
        internal fun readLayout(file: Path): StoreContainer.Layout? {
            val size = try {
                Files.size(file)
            } catch (_: NoSuchFileException) {
                return null
            }
            if (size < StoreContainer.HEADER_BYTES) return null
            val header = ByteArray(StoreContainer.HEADER_BYTES)
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                val buffer = ByteBuffer.wrap(header)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return null
                }
            }
            return StoreContainer.layout(header, size)
        }

        /**
         * Overwrite one range of an existing file in place, then fsync — the save path of
         * docs/DECOY-ACCOUNT.md §1. The file's length never changes, so this rewrites an existing
         * extent and touches no other slot's bytes.
         */
        internal fun writeRange(target: Path, position: Long, bytes: ByteArray) {
            FileChannel.open(target, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                var at = position
                while (buffer.hasRemaining()) at += channel.write(buffer, at)
                channel.force(true)
            }
        }

        /** `Data.write(to:options:[.atomic])`: temp file in the same directory, fsync, rename over the target. */
        internal fun atomicWrite(target: Path, bytes: ByteArray) {
            val absolute = target.toAbsolutePath()
            val dir = absolute.parent
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, ".${absolute.fileName}.", ".tmp")
            try {
                FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(tmp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                Files.deleteIfExists(tmp)
                throw e
            }
        }
    }
}
