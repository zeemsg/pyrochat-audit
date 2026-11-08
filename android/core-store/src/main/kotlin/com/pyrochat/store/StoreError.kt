package com.pyrochat.store

import java.nio.file.Path

/** `SecureStore.StoreError` + `BackupService.BackupError` (`SecureStore.swift:43`, `BackupService.swift:19-27`). */
sealed class StoreError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** GCM authentication failed — a wrong password is indistinguishable from a corrupt file. */
    class BadPassword : StoreError("wrong password (AES-GCM authentication failed)")

    class NoStore(file: Path) : StoreError("no store at $file")

    class Io(message: String, cause: Throwable? = null) : StoreError(message, cause)

    /**
     * The file decrypted but its JSON is not a valid `Blob` (missing required key, unknown enum
     * value, …). iOS surfaces this through the same "bad password" UI path (SPEC.md §5).
     */
    class Corrupt(cause: Throwable) : StoreError("store decrypted but its contents did not decode: ${cause.message}", cause)

    /**
     * The account no longer fits in one container slot (docs/DECOY-ACCOUNT.md §1: `slotBytes` is a
     * fixed 4 MiB and cannot grow, because a growing container would have to re-pad slots it cannot
     * decrypt). The last good copy stays on disk; the save is refused, never truncated.
     */
    class TooLarge(val jsonBytes: Int, val capacityBytes: Int) :
        StoreError("this account is $jsonBytes bytes, more than the $capacityBytes a store slot holds")

    /**
     * docs/DECOY-ACCOUNT.md §1 — there is no slot this account KNOWS to be free, so there is
     * nowhere a second account can safely go. The ordinary outcome inside a decoy, and the app
     * reports it in its ordinary words for a thing that did not work: a slot that cannot be told
     * from noise is never written over on a guess.
     */
    class NoFreeSlot : StoreError("this account knows of no free slot")

    /** Missing magic or truncated — "not a pyrochat backup file". */
    class BadBackupFile : StoreError("not a pyrochat backup file")

    class BadBackupPassphrase : StoreError("wrong backup passphrase")
}
