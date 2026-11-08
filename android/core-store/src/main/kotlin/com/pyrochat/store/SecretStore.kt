package com.pyrochat.store

import java.util.concurrent.ConcurrentHashMap

/**
 * The few raw secrets that must live OUTSIDE the password-encrypted store because they are needed
 * before it can be decrypted — iOS `KeychainHelper` (`ios/PyroChat/Core/KeychainHelper.swift`,
 * generic-password items, service `ca.pyrochat.app`, `set` = delete-then-add). The Android app
 * provides a Keystore-backed implementation; [InMemorySecretStore] serves tests.
 */
interface SecretStore {
    fun get(name: String): ByteArray?
    fun set(name: String, value: ByteArray)
    fun delete(name: String)

    /** Panic wipe: every item (`KeychainHelper.wipeAll`). */
    fun wipeAll()

    companion object {
        /** 32 random bytes, generated once per install — the PBKDF2 salt/pepper (SPEC.md §6). */
        const val KDF_SALT = "kdf-salt"

        /** `PBKDF2(pin, kdf-salt)`; see `DuressVerifier`. */
        const val DURESS_VERIFIER = "duress-verifier"
    }
}

class InMemorySecretStore : SecretStore {
    private val items = ConcurrentHashMap<String, ByteArray>()

    override fun get(name: String): ByteArray? = items[name]?.copyOf()
    override fun set(name: String, value: ByteArray) { items[name] = value.copyOf() }
    override fun delete(name: String) { items.remove(name) }
    override fun wipeAll() = items.clear()

    /**
     * Which items exist, for docs/DECOY-ACCOUNT.md §6.1: "the device holds a fixed set of stored
     * items whose number is identical on every install". A test asserts this set does not grow with
     * the number of accounts — two of a thing that should be one says two accounts exist, without a
     * password being typed.
     */
    fun names(): Set<String> = items.keys.toSet()
}
