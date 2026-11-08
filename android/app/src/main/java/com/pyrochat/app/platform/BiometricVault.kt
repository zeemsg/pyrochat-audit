package com.pyrochat.app.platform

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import com.pyrochat.store.crypto.StoreCrypto
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * docs/BIOMETRIC-UNLOCK.md §4 third bullet — the OS invalidated the stored key because a face or a
 * fingerprint was added or removed. The caller deletes the item and turns the feature off.
 */
class BiometricInvalidated(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * docs/BIOMETRIC-UNLOCK.md §1/§4 — the three data that must be readable BEFORE the encrypted store
 * is open: the wrapped store key, the timestamp of the last successful PASSWORD unlock and the
 * consecutive biometric failure count. They live in an ordinary preferences file and NEVER inside
 * the encrypted store; they are useless without the Keystore key.
 */
interface BiometricStore {
    /** `version ‖ ivLen ‖ iv ‖ AES-256-GCM(store key)`, or null when the feature is off. */
    var wrapped: ByteArray?

    /** `biometric-last-password` — raw seconds, null when no password unlock is on record. */
    var lastPasswordUnlock: Long?

    /** Consecutive biometric no-matches; reset on any successful unlock of either kind. */
    var failures: Int

    /**
     * docs/SCRUB-AND-WIPE.md §A1 — consecutive wrong TYPED passwords on the unlock screen, the
     * count that wipes the device at ten. It lives in this same plain file because it too must be
     * readable when the store cannot be opened (iOS keeps it in the Keychain as `unlock-failures`).
     *
     * It is NOT [failures]: §A1 keeps the two apart on purpose. A biometric no-match never touches
     * this one, a wrong password never touches that one, and [clearKey] — the toggle going off —
     * leaves this one exactly where it was.
     */
    var unlockFailures: Int

    /** Forget the wrapped key and the biometric failure count; the 7-day clock and §A1's count survive. */
    fun clearKey()

    /** Forget everything — the shredder paths (§5). */
    fun clearAll()
}

/** [BiometricStore] over a plain `SharedPreferences` file of its own. */
class PrefsBiometricStore(private val prefs: SharedPreferences) : BiometricStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override var wrapped: ByteArray?
        get() = prefs.getString(KEY_WRAPPED, null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        set(value) = prefs.edit().run {
            if (value == null) remove(KEY_WRAPPED) else putString(KEY_WRAPPED, Base64.getEncoder().encodeToString(value))
            apply()
        }

    override var lastPasswordUnlock: Long?
        get() = prefs.getLong(KEY_LAST_PASSWORD, 0L).takeIf { it > 0L }
        set(value) = prefs.edit().run {
            if (value == null) remove(KEY_LAST_PASSWORD) else putLong(KEY_LAST_PASSWORD, value)
            apply()
        }

    override var failures: Int
        get() = prefs.getInt(KEY_FAILURES, 0)
        set(value) = prefs.edit().putInt(KEY_FAILURES, value).apply()

    /** §A1: written with `commit`, not `apply` — a count that arms a wipe must not lose a race with a kill. */
    override var unlockFailures: Int
        get() = prefs.getInt(KEY_UNLOCK_FAILURES, 0)
        @android.annotation.SuppressLint("ApplySharedPref")
        set(value) {
            prefs.edit().putInt(KEY_UNLOCK_FAILURES, value).commit()
        }

    override fun clearKey() {
        prefs.edit().remove(KEY_WRAPPED).remove(KEY_FAILURES).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS = "pyro-biometric"

        /** Named after the iOS Keychain item (`biometric-store-key`) so the two read alike. */
        const val KEY_WRAPPED = "biometric-store-key"
        const val KEY_LAST_PASSWORD = "biometric-last-password"
        const val KEY_FAILURES = "biometric-failures"

        /** docs/SCRUB-AND-WIPE.md §A1 — named after the iOS Keychain item (`unlock-failures`). */
        const val KEY_UNLOCK_FAILURES = "unlock-failures"
    }
}

/**
 * docs/BIOMETRIC-UNLOCK.md §1 — the Android Keystore key aliased `pyro.biometric`: AES-256-GCM,
 * `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)`,
 * `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` and StrongBox when the device has it.
 *
 * Split out from [BiometricVault] so the unit suite can drive the vault with a plain AES key: the
 * Keystore needs a device.
 */
interface BiometricKeyBox {
    /** A brand-new key (any predecessor is destroyed) and an ENCRYPT cipher awaiting biometry. */
    fun freshEncryptCipher(): Cipher

    /** A DECRYPT cipher over [iv]; throws [BiometricInvalidated] when the OS dropped the key. */
    fun decryptCipher(iv: ByteArray): Cipher

    /** Destroy the key. Idempotent. */
    fun delete()
}

/** The real thing, on the Android Keystore. */
class KeystoreBiometricKeys(
    context: Context,
    private val alias: String = ALIAS,
) : BiometricKeyBox {
    private val appContext = context.applicationContext

    override fun freshEncryptCipher(): Cipher {
        delete() // a new secret gets a new key: an old wrapped copy must never survive re-enabling
        val key = generate()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher {
        val key = try {
            keyStore().getKey(alias, null) as? SecretKey
        } catch (e: GeneralSecurityException) {
            throw BiometricInvalidated("keystore entry $alias unreadable", e)
        } ?: throw BiometricInvalidated("keystore entry $alias is gone")
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // a face or a finger was added or removed — exactly what `.biometryCurrentSet` means on iOS
            throw BiometricInvalidated("biometric enrolment changed", e)
        }
    }

    override fun delete() {
        try {
            keyStore().deleteEntry(alias)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "could not delete $alias", e)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun generate(): SecretKey {
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (strongBox) {
            try {
                return generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                Log.i(TAG, "StrongBox advertised but unavailable, falling back to TEE", e)
            } catch (e: ProviderException) {
                Log.i(TAG, "StrongBox key generation failed, falling back to TEE", e)
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 0 s validity = authenticate for EVERY use; STRONG only, so a PIN or pattern can never stand in
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // API 29: -1 is the same contract (per-use, biometric only) under the pre-R spelling
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) spec.setIsStrongBoxBacked(strongBox)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec.build())
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "BiometricKeys"

        /** docs/BIOMETRIC-UNLOCK.md §1. */
        const val ALIAS = "pyro.biometric"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}

/**
 * docs/BIOMETRIC-UNLOCK.md — the whole opt-in feature, minus the prompt and the UI.
 *
 * After one successful password unlock the app holds the 32-byte store key in memory. Enabling
 * places THAT KEY, and nothing else, under [BiometricKeyBox]; the ciphertext and IV go into
 * [BiometricStore], a plain file outside the encrypted store. The password itself is never stored,
 * anywhere, in any form — [finishEnable] refuses anything that is not exactly 32 bytes.
 *
 * Enabling and unlocking are two calls each because a BiometricPrompt sits between them: `begin…`
 * produces the cipher the prompt authenticates, `finish…` uses the authenticated cipher.
 */
class BiometricVault(
    private val keys: BiometricKeyBox,
    private val store: BiometricStore,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    constructor(context: Context) : this(KeystoreBiometricKeys(context), PrefsBiometricStore(context))

    /** §2: the toggle's state — whether the stored key exists, never a field in the encrypted blob. */
    val enabled: Boolean get() = store.wrapped != null

    /**
     * docs/DECOY-ACCOUNT.md §6.1 — "the phone can unlock exactly one slot by face".
     *
     * There is ONE stored item, there always was, and it must stay one: two of a thing that should
     * be one says two accounts exist, without a password being typed. So the question a session can
     * ask is not "is something stored" but "is the stored key MINE", and it is answered by a tag
     * sealed inside the one item — [ownerTag], a hash of the store key it holds. Anyone can read
     * the tag; only the account that holds that key can recognise it.
     *
     * The `[cfg]` row then shows the truth for the account you are in: on if this slot is the one
     * the phone holds, off otherwise. Inside a decoy whose phone unlocks the real account by face
     * the row reads off, which is an ordinary state and not a tell. Two slots each claiming a
     * stored key would have been the tell, and there is now no way to express it.
     *
     * A record written before this tag existed ([VERSION_UNTAGGED]) reads as owned: no build that
     * could write one was ever able to hold a second account, so its owner is whoever is open.
     */
    fun ownedBy(storeKey: ByteArray): Boolean {
        val blob = store.wrapped ?: return false
        val tag = tagOf(blob) ?: return true
        return MessageDigest.isEqual(tag, ownerTag(storeKey))
    }

    // ---- turning it on (§2) ------------------------------------------------------------------

    /** Step 1: a fresh Keystore key and the cipher biometry has to authenticate. */
    fun beginEnable(): Cipher = keys.freshEncryptCipher()

    /**
     * Step 2, once the prompt succeeded: seal [storeKey] with the authenticated [cipher]. Exactly
     * 32 bytes go in and nothing else — no password, no salt, no verifier.
     */
    fun finishEnable(cipher: Cipher, storeKey: ByteArray) {
        require(storeKey.size == StoreCrypto.KEY_BYTES) {
            "the biometric item holds the ${StoreCrypto.KEY_BYTES}-byte store key and nothing else"
        }
        val iv = cipher.iv ?: throw GeneralSecurityException("cipher has no IV")
        require(iv.size in 1..255) { "unexpected GCM IV length ${iv.size}" }
        // §6.1: the owner tag rides INSIDE the one item, so enabling in a second account replaces
        // the first rather than adding to it, and the count can never grow
        store.wrapped = byteArrayOf(VERSION, iv.size.toByte()) + ownerTag(storeKey) + iv + cipher.doFinal(storeKey)
        store.failures = 0
    }

    // ---- unlocking with it (§3) --------------------------------------------------------------

    /** Step 1: the cipher for the stored blob. [BiometricInvalidated] when the OS dropped the key. */
    fun beginUnlock(): Cipher = keys.decryptCipher(ivOf(store.wrapped ?: throw BiometricInvalidated("nothing stored")))

    /** Step 2, once the prompt succeeded: the 32-byte store key. Zero it after opening the store. */
    fun finishUnlock(cipher: Cipher): ByteArray {
        val blob = store.wrapped ?: throw BiometricInvalidated("nothing stored")
        val key = cipher.doFinal(bodyOf(blob))
        if (key.size != StoreCrypto.KEY_BYTES) {
            key.fill(0)
            throw GeneralSecurityException("unwrapped ${key.size} bytes, expected ${StoreCrypto.KEY_BYTES}")
        }
        return key
    }

    // ---- the policy state (§4) ---------------------------------------------------------------

    /**
     * A successful unlock of either kind resets the biometric counter — and, per
     * docs/SCRUB-AND-WIPE.md §A1 ("Reset to zero by any successful unlock, typed or biometric"),
     * the wrong-password counter with it.
     */
    fun noteSuccess() {
        store.failures = 0
        store.unlockFailures = 0
    }

    /** A biometric no-match — never a cancellation. Returns the running count. */
    fun noteFailure(): Int = (store.failures + 1).also { store.failures = it }

    /** A successful PASSWORD unlock: the 7-day clock restarts and BOTH counters reset (§A1). */
    fun notePasswordUnlock() {
        store.lastPasswordUnlock = clockSeconds()
        store.failures = 0
        store.unlockFailures = 0
    }

    // ---- wrong typed passwords (docs/SCRUB-AND-WIPE.md §A1) ----------------------------------

    /** The count as it stands, survivor of every restart. Read by the unlock screen before it decides. */
    val unlockFailures: Int get() = store.unlockFailures

    /**
     * §A1: "Incremented ONLY by a wrong typed password on the unlock screen." Returns the running
     * count, which the caller hands straight to [UnlockFailurePolicy.decide].
     */
    fun noteWrongPassword(): Int = (store.unlockFailures + 1).also { store.unlockFailures = it }

    /** §A1: "Reset to zero by … creating or restoring an identity" — and by a biometric success. */
    fun resetUnlockFailures() {
        store.unlockFailures = 0
    }

    /** What the unlock screen may offer right now (§4). REQUIRE_PASSWORD whenever nothing is stored. */
    fun decide(): BiometricDecision =
        if (!enabled) {
            BiometricDecision.REQUIRE_PASSWORD
        } else {
            BiometricPolicy.decide(store.lastPasswordUnlock, store.failures, invalidated = false, now = clockSeconds())
        }

    // ---- destroying it (§5) ------------------------------------------------------------------

    /**
     * The toggle going off, an OS invalidation, biometry removed from the device: the stored key
     * and its Keystore key go, the 7-day clock stays (the identity is still here). Idempotent.
     */
    fun destroy() {
        store.clearKey()
        runCatching { keys.delete() }
    }

    /**
     * The shredder paths — the duress PIN, the dead-man wipe, `TOTAL WIPE` / obliterate: nothing at
     * all is left behind, the timestamp included. Idempotent.
     *
     * This one takes docs/SCRUB-AND-WIPE.md §A1's wrong-password counter too, because it shares the
     * preferences file and because a totally wiped phone is a fresh phone — see `wipeLocal`. That
     * is exactly why the narrowed burn cannot use it; see [shredSparingWrongPasswordCount].
     */
    fun shred() {
        store.clearAll()
        runCatching { keys.delete() }
    }

    /**
     * docs/DECOY-ACCOUNT.md §5, "The burn must take the biometric key with it" — the narrowed
     * burn's shredder. Everything this vault holds goes, the last-password timestamp included, so
     * `[ BURN EVERYTHING ]` leaves the same biometric state behind as the duress PIN, ten wrong
     * passwords and the dead-man switch. A value that survives one kind of wipe and not another is
     * readable by anyone holding two snapshots, and "inert to the app" is not "absent from the file".
     *
     * **It stops one key short of [shred], and that is not an oversight.** §A1's wrong-password
     * counter lives in this same preferences file but is not this vault's to destroy: §5's
     * "Device-level state a narrowed burn must not touch" keeps it alongside the shared salt, the
     * duress verifier and the dead-man deadline, because every one of those acts on EVERY slot.
     * Clearing it from inside a decoy would reach into an account the decoy is not allowed to know
     * exists — and would show up as the real account's wipe countdown silently resetting. Idempotent.
     */
    fun shredSparingWrongPasswordCount() {
        destroy()                       // the wrapped key + the biometric failure count + the Keystore key
        store.lastPasswordUnlock = null // the 7-day clock; `shred` takes it, and so must this
    }

    /** `version(1) ‖ ivLen(1) ‖ [ownerTag(32)] ‖ iv ‖ ciphertext` — where the IV starts. */
    private fun ivStart(blob: ByteArray): Int = if (blob[0] == VERSION) 2 + OWNER_TAG_BYTES else 2

    /** The owner tag, or null for a record written before tags existed. */
    private fun tagOf(blob: ByteArray): ByteArray? {
        if (blob.size < 2 || blob[0] != VERSION) return null
        if (blob.size < 2 + OWNER_TAG_BYTES) return null
        return blob.copyOfRange(2, 2 + OWNER_TAG_BYTES)
    }

    private fun ivOf(blob: ByteArray): ByteArray {
        if (blob.size < 2 || (blob[0] != VERSION && blob[0] != VERSION_UNTAGGED)) throw BiometricInvalidated("bad wrapped-key layout")
        val n = blob[1].toInt() and 0xFF
        val start = ivStart(blob)
        if (blob.size < start + n + StoreCrypto.TAG_BYTES) throw BiometricInvalidated("bad wrapped-key layout")
        return blob.copyOfRange(start, start + n)
    }

    private fun bodyOf(blob: ByteArray): ByteArray {
        val n = blob[1].toInt() and 0xFF
        return blob.copyOfRange(ivStart(blob) + n, blob.size)
    }

    companion object {
        /** `version ‖ ivLen ‖ ownerTag ‖ iv ‖ ciphertext` (docs/DECOY-ACCOUNT.md §6.1). */
        const val VERSION: Byte = 2

        /** The pre-decoy layout, `version ‖ ivLen ‖ iv ‖ ciphertext`. Still unwrapped, never written. */
        const val VERSION_UNTAGGED: Byte = 1

        const val OWNER_TAG_BYTES = 32

        private val OWNER_TAG_DOMAIN = "pyrochat-biometric-owner-v1".toByteArray(Charsets.US_ASCII)

        /**
         * Which slot's key the phone holds, as a value that says nothing to anyone who does not
         * already hold that key. A hash, never the key: the item is readable with root, and a
         * readable copy of the store key would be the end of the feature.
         */
        fun ownerTag(storeKey: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(OWNER_TAG_DOMAIN + storeKey)
    }
}
