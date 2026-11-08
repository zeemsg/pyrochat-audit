package com.pyrochat.app.platform

import com.pyrochat.store.crypto.Pbkdf2HmacSha256
import com.pyrochat.store.crypto.StoreCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * docs/BIOMETRIC-UNLOCK.md §7 second bullet: enabling stores exactly 32 bytes and no password
 * material; disabling deletes it; obliterate, duress and dead-man all delete it; a password change
 * replaces it.
 *
 * The Keystore needs a device, so [FakeKeys] stands in with a plain AES-GCM key — the vault's own
 * bookkeeping, layout and refusals are what is under test here.
 */
class BiometricVaultTest {

    /** A [BiometricKeyBox] whose key lives in this JVM instead of the secure hardware. */
    private class FakeKeys : BiometricKeyBox {
        var key: SecretKey? = null
        var deletes = 0

        override fun freshEncryptCipher(): Cipher {
            delete()
            val fresh = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            key = fresh
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, fresh) }
        }

        override fun decryptCipher(iv: ByteArray): Cipher {
            val k = key ?: throw BiometricInvalidated("keystore entry is gone")
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        }

        override fun delete() {
            key = null
            deletes++
        }
    }

    /** [BiometricStore] in RAM, recording every byte that was ever handed to it. */
    private class FakeStore : BiometricStore {
        val everWritten = mutableListOf<ByteArray>()
        override var wrapped: ByteArray? = null
            set(value) {
                value?.let { everWritten += it }
                field = value
            }
        override var lastPasswordUnlock: Long? = null
        override var failures: Int = 0
        override var unlockFailures: Int = 0
        override fun clearKey() {
            wrapped = null
            failures = 0
            // docs/SCRUB-AND-WIPE.md §A1: the toggle going off is not an unlock — the count stands
        }
        override fun clearAll() {
            wrapped = null
            lastPasswordUnlock = null
            failures = 0
            unlockFailures = 0
        }
    }

    private val keys = FakeKeys()
    private val store = FakeStore()
    private var now = 1_700_000_000L
    private val vault = BiometricVault(keys, store) { now }

    private val password = "correct horse battery staple"
    private val salt = ByteArray(StoreCrypto.SALT_BYTES) { it.toByte() }
    private val storeKey = StoreCrypto.deriveKey(Pbkdf2HmacSha256, password, salt)

    private fun enable(key: ByteArray = storeKey) = vault.finishEnable(vault.beginEnable(), key)

    /** A second account's key — the decoy's, or the real one's seen from the decoy. */
    private val otherKey = StoreCrypto.deriveKey(Pbkdf2HmacSha256, "a different password entirely", salt)

    // ---- docs/DECOY-ACCOUNT.md §6.1: device-level and singular --------------------------------

    @Test fun theStoredKeyBelongsToExactlyOneAccountAndTheOtherSeesItAsOff() {
        enable(storeKey)
        assertTrue(vault.enabled)
        assertTrue("the row reads ON in the account whose key the phone holds", vault.ownedBy(storeKey))
        assertFalse("and OFF in the other, which is an ordinary state and not a tell", vault.ownedBy(otherKey))
    }

    @Test fun enablingInASecondAccountReplacesWhateverWasThereAndNeverAdds() {
        enable(storeKey)
        val first = store.wrapped!!.copyOf()
        enable(otherKey)
        assertEquals("§6.1: one stored item, always", 1, listOfNotNull(store.wrapped).size)
        assertFalse(store.wrapped!!.contentEquals(first))
        assertFalse("the first account's row now reads off", vault.ownedBy(storeKey))
        assertTrue(vault.ownedBy(otherKey))
        assertArrayEquals("and the phone opens the second account", otherKey, vault.finishUnlock(vault.beginUnlock()))
    }

    @Test fun nothingStoredWhenTheFeatureIsOffBelongsToAnybody() {
        assertFalse(vault.ownedBy(storeKey))
        assertFalse(vault.ownedBy(otherKey))
    }

    @Test fun theOwnerTagIsAHashAndNeverTheKey() {
        enable(storeKey)
        val blob = store.wrapped!!
        assertFalse("the store key must not be readable beside its own ciphertext", blob.containsSlice(storeKey))
        assertTrue(blob.containsSlice(BiometricVault.ownerTag(storeKey)))
        assertFalse(BiometricVault.ownerTag(storeKey).contentEquals(BiometricVault.ownerTag(otherKey)))
    }

    @Test fun aRecordFromBeforeTagsExistedReadsAsOwned() {
        // a wave-1 install: no build that could write one was ever able to hold a second account
        enable(storeKey)
        val tagged = store.wrapped!!
        val untagged = byteArrayOf(BiometricVault.VERSION_UNTAGGED, tagged[1]) +
            tagged.copyOfRange(2 + BiometricVault.OWNER_TAG_BYTES, tagged.size)
        store.wrapped = untagged
        assertTrue(vault.ownedBy(storeKey))
        assertTrue("owner unknown means whoever is open, never a second item", vault.ownedBy(otherKey))
        assertArrayEquals("and it still unwraps", storeKey, vault.finishUnlock(vault.beginUnlock()))
    }

    // ---- enabling stores exactly the 32-byte key and no password material ---------------------

    @Test fun offByDefault() {
        assertFalse(vault.enabled)
        assertNull(store.wrapped)
    }

    @Test fun enablingStoresExactlyTheThirtyTwoByteKey() {
        assertEquals(StoreCrypto.KEY_BYTES, storeKey.size)
        enable()
        assertTrue(vault.enabled)
        val blob = assertNotNull(store.wrapped).let { store.wrapped!! }
        // version(1) + ivLen(1) + ownerTag(32) + iv(12) + ciphertext(32) + tag(16): exactly 32
        // bytes of secret went in, and the owner tag rides inside the SAME item so the number of
        // stored items can never grow with the number of accounts (docs/DECOY-ACCOUNT.md §6.1)
        val ivLen = blob[1].toInt() and 0xFF
        assertEquals(2 + BiometricVault.OWNER_TAG_BYTES + ivLen + StoreCrypto.KEY_BYTES + StoreCrypto.TAG_BYTES, blob.size)
    }

    @Test fun theStoredKeyComesBackByteForByte() {
        enable()
        assertArrayEquals(storeKey, vault.finishUnlock(vault.beginUnlock()))
    }

    @Test fun nothingStoredResemblesThePassword() {
        enable()
        vault.notePasswordUnlock()
        val everything = store.everWritten.fold(ByteArray(0)) { acc, b -> acc + b }
        assertFalse(everything.containsSlice(password.toByteArray(Charsets.UTF_8)))
        assertFalse(everything.containsSlice(salt))
        // and not even the key itself in the clear — only its ciphertext
        assertFalse(everything.containsSlice(storeKey))
    }

    @Test fun onlyAThirtyTwoByteSecretIsAccepted() {
        assertThrows(IllegalArgumentException::class.java) { vault.finishEnable(vault.beginEnable(), ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { vault.finishEnable(vault.beginEnable(), ByteArray(33)) }
        // a password is never 32 bytes by accident, but say it out loud anyway
        assertThrows(IllegalArgumentException::class.java) {
            vault.finishEnable(vault.beginEnable(), password.toByteArray(Charsets.UTF_8))
        }
        assertFalse(vault.enabled)
    }

    // ---- disabling deletes it -----------------------------------------------------------------

    @Test fun disablingDeletesTheKeyAndTheKeystoreEntry() {
        enable()
        val before = keys.deletes
        vault.destroy()
        assertFalse(vault.enabled)
        assertNull(store.wrapped)
        assertEquals(before + 1, keys.deletes)
        assertNull(keys.key)
    }

    @Test fun disablingKeepsTheSevenDayClock() {
        vault.notePasswordUnlock()
        enable()
        vault.destroy()
        assertEquals(now, store.lastPasswordUnlock)   // the identity is still here; only the item went
    }

    @Test fun destroyIsIdempotentOnADeviceThatNeverHadIt() {
        vault.destroy()
        vault.destroy()
        assertFalse(vault.enabled)
    }

    // ---- obliterate, duress and dead-man all delete it (§5) -----------------------------------

    @Test fun shredLeavesNothingAtAll() {
        vault.notePasswordUnlock()
        enable()
        vault.noteFailure()
        vault.shred()
        assertFalse(vault.enabled)
        assertNull(store.wrapped)
        assertNull(store.lastPasswordUnlock)
        assertEquals(0, store.failures)
        assertNull(keys.key)
    }

    // ---- a password change replaces it --------------------------------------------------------

    @Test fun reEnablingUnderANewKeyReplacesTheOldItem() {
        enable()
        val first = store.wrapped!!.copyOf()
        val newKey = StoreCrypto.deriveKey(Pbkdf2HmacSha256, "a completely different password", salt)
        enable(newKey)
        val second = store.wrapped!!
        assertFalse(first.contentEquals(second))
        assertArrayEquals(newKey, vault.finishUnlock(vault.beginUnlock()))
    }

    @Test fun theOldWrappedKeyIsUnreadableAfterReEnabling() {
        enable()
        val stale = store.wrapped!!.copyOf()
        enable(ByteArray(StoreCrypto.KEY_BYTES) { 9 })
        // the previous Keystore key is gone, so the bytes an attacker copied are noise
        store.wrapped = stale
        assertThrows(GeneralSecurityException::class.java) { vault.finishUnlock(vault.beginUnlock()) }
    }

    // ---- the policy state it keeps (§4) --------------------------------------------------------

    @Test fun nothingStoredAlwaysRequiresThePassword() {
        vault.notePasswordUnlock()
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, vault.decide())
    }

    @Test fun failuresAccumulateAndThenDemandThePassword() {
        vault.notePasswordUnlock()
        enable()
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, vault.decide())
        assertEquals(1, vault.noteFailure())
        assertEquals(2, vault.noteFailure())
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, vault.decide())
        assertEquals(3, vault.noteFailure())
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, vault.decide())
    }

    @Test fun anySuccessfulUnlockResetsTheCounter() {
        vault.notePasswordUnlock()
        enable()
        repeat(3) { vault.noteFailure() }
        vault.noteSuccess()
        assertEquals(0, store.failures)
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, vault.decide())
    }

    @Test fun aPasswordUnlockAlsoResetsTheCounterAndTheClock() {
        now = 1L
        vault.notePasswordUnlock()
        enable()
        repeat(3) { vault.noteFailure() }
        now = 2L + BiometricPolicy.PASSWORD_EVERY_SECONDS
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, vault.decide())
        vault.notePasswordUnlock()
        assertEquals(0, store.failures)
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, vault.decide())
    }

    @Test fun sevenDaysLaterTheVaultAsksForThePassword() {
        vault.notePasswordUnlock()
        enable()
        now += BiometricPolicy.PASSWORD_EVERY_SECONDS - 1
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, vault.decide())
        now += 1
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, vault.decide())
    }

    // ---- invalidation surfaces as a checked failure, never as a silent success ------------------

    @Test fun aVanishedKeystoreEntryReportsInvalidation() {
        enable()
        keys.delete()   // what setInvalidatedByBiometricEnrollment(true) does when a finger changes
        assertThrows(BiometricInvalidated::class.java) { vault.beginUnlock() }
    }

    @Test fun aCorruptStoredBlobReportsInvalidation() {
        enable()
        store.wrapped = byteArrayOf(9, 9, 9)
        assertThrows(BiometricInvalidated::class.java) { vault.beginUnlock() }
    }

    private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
