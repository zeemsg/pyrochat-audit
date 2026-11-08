package com.pyrochat.store

/**
 * How soon a change has to reach the disk — docs/DECOY-ACCOUNT.md §1, "What a save costs, measured".
 *
 * Every save writes a whole 2 MiB half, because the plaintext is padded to fill it and AES-GCM
 * ciphertext is exactly as long as its plaintext. That is the honest price of a slot whose size
 * never hints at its contents, and it is not avoidable inside this format. What IS avoidable is
 * paying it for things that do not need paying for, so the call site declares which it is:
 *
 * - [PROMPT] — a sent or received message, any key material, anything whose loss to a crash is
 *   real damage. Written on the 250 ms debounce, exactly as before this split existed.
 * - [LAZY] — a draft, read state, interface state. Losing the last few seconds of an unsent draft
 *   is not damage; writing 2 MiB per keystroke is. A lazy change sits in memory until the app has
 *   a reason to write anyway (any [PROMPT] save carries it along, since a save writes the whole
 *   blob), until something flushes ([SecureStore.flush] — the composer losing focus, leaving the
 *   screen, backgrounding, locking), or until the idle timer fires.
 *
 * **A keystroke must never reach the store.** That is the mandate this enum exists to keep.
 */
enum class Durability {
    /** Write promptly: the debounced save of `SecureStore.swift:143-146`. */
    PROMPT,

    /** Write when something else is writing anyway, when flushed, or when idle. */
    LAZY,
}
