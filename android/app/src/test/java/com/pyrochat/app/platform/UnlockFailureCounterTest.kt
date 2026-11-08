package com.pyrochat.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * docs/SCRUB-AND-WIPE.md §A4 second bullet — the counter "survives a restart; resets on a typed
 * success and on a biometric success; is untouched by a biometric failure; is untouched by the
 * duress PIN."
 *
 * §A1 keeps this count and the three-failure BIOMETRIC count in the same plain preferences file
 * and strictly apart, so every assertion below checks both numbers, not just the new one.
 */
class UnlockFailureCounterTest {

    /** The preferences file itself: one map, two counters, surviving a process that went away. */
    private class Disk {
        var wrapped: ByteArray? = null
        var lastPasswordUnlock: Long? = null
        var failures = 0
        var unlockFailures = 0
    }

    /** [BiometricStore] over [Disk] — a relaunch is simply a second store over the same disk. */
    private class DiskStore(private val disk: Disk) : BiometricStore {
        override var wrapped: ByteArray?
            get() = disk.wrapped
            set(v) { disk.wrapped = v }
        override var lastPasswordUnlock: Long?
            get() = disk.lastPasswordUnlock
            set(v) { disk.lastPasswordUnlock = v }
        override var failures: Int
            get() = disk.failures
            set(v) { disk.failures = v }
        override var unlockFailures: Int
            get() = disk.unlockFailures
            set(v) { disk.unlockFailures = v }

        override fun clearKey() {
            disk.wrapped = null
            disk.failures = 0
        }

        override fun clearAll() {
            disk.wrapped = null
            disk.lastPasswordUnlock = null
            disk.failures = 0
            disk.unlockFailures = 0
        }
    }

    private class FakeKeys : BiometricKeyBox {
        private var key: SecretKey? = null
        override fun freshEncryptCipher(): Cipher {
            val fresh = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            key = fresh
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, fresh) }
        }
        override fun decryptCipher(iv: ByteArray): Cipher {
            val k = key ?: throw BiometricInvalidated("gone")
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        }
        override fun delete() { key = null }
    }

    private val disk = Disk()
    private fun vault() = BiometricVault(FakeKeys(), DiskStore(disk)) { 1_700_000_000L }

    // ---- §A1: incremented ONLY by a wrong typed password -------------------------------------

    @Test fun aWrongTypedPasswordIsTheOnlyThingThatIncrementsIt() {
        val v = vault()
        assertEquals(0, v.unlockFailures)
        assertEquals(1, v.noteWrongPassword())
        assertEquals(2, v.noteWrongPassword())
        assertEquals(2, v.unlockFailures)
        assertEquals("the biometric counter never moved", 0, disk.failures)
    }

    @Test fun aBiometricFailureNeverTouchesIt() {
        val v = vault()
        v.noteWrongPassword()
        v.noteWrongPassword()
        repeat(5) { v.noteFailure() }
        assertEquals("§A1: the two counters are separate", 2, v.unlockFailures)
        assertEquals(5, disk.failures)
    }

    @Test fun theBiometricCounterIsNotTheWipeCounter() {
        val v = vault()
        repeat(BiometricPolicy.MAX_CONSECUTIVE_FAILURES) { v.noteFailure() }
        // three biometric failures require the password; they do not spend a single one of the ten
        assertEquals(0, v.unlockFailures)
        assertEquals(UnlockFailurePolicy.Decision.Allow, UnlockFailurePolicy.decide(v.unlockFailures, 0L))
    }

    // ---- §A1: survives app restarts -----------------------------------------------------------

    @Test fun itSurvivesARestart() {
        repeat(7) { vault().noteWrongPassword() }       // seven separate processes, one guess each
        assertEquals(7, vault().unlockFailures)
        assertEquals(UnlockFailurePolicy.Decision.Warn(3), UnlockFailurePolicy.decide(vault().unlockFailures, 0L))
    }

    // ---- §A1: reset by any successful unlock --------------------------------------------------

    @Test fun aTypedSuccessResetsIt() {
        val v = vault()
        repeat(9) { v.noteWrongPassword() }
        v.notePasswordUnlock()
        assertEquals(0, v.unlockFailures)
        assertEquals(0, disk.failures)
        assertEquals("a restart still sees zero", 0, vault().unlockFailures)
    }

    @Test fun aBiometricSuccessResetsIt() {
        val v = vault()
        repeat(9) { v.noteWrongPassword() }
        v.noteFailure()
        v.noteSuccess()
        assertEquals("§A1: reset by any successful unlock, typed or biometric", 0, v.unlockFailures)
        assertEquals(0, disk.failures)
    }

    @Test fun creatingOrRestoringAnIdentityResetsIt() {
        val v = vault()
        repeat(4) { v.noteWrongPassword() }
        v.resetUnlockFailures()
        assertEquals(0, v.unlockFailures)
    }

    // ---- §A1: the toggle and the shredder -----------------------------------------------------

    @Test fun turningBiometryOffLeavesTheCountWhereItWas() {
        val v = vault()
        v.finishEnable(v.beginEnable(), ByteArray(32) { 7 })
        repeat(8) { v.noteWrongPassword() }
        v.destroy()
        assertEquals("disabling the toggle is not an unlock", 8, v.unlockFailures)
        assertNotEquals(UnlockFailurePolicy.Decision.Allow, UnlockFailurePolicy.decide(v.unlockFailures, 0L))
    }

    @Test fun theShredderTakesItToo() {
        val v = vault()
        repeat(9) { v.noteWrongPassword() }
        v.shred()   // the duress PIN / dead-man / TOTAL WIPE path — a wiped phone is a fresh phone
        assertEquals(0, v.unlockFailures)
        assertEquals(0, vault().unlockFailures)
    }

    @Test fun theDuressPinNeverSpendsAnAttempt() {
        // §A1: "The duress PIN never touches it: entering the duress PIN wipes immediately, as
        // today" — the duress branch runs before the store is ever opened, so nothing counts it,
        // and the shred that follows leaves the file at zero either way.
        val v = vault()
        repeat(3) { v.noteWrongPassword() }
        v.shred()
        assertEquals(0, v.unlockFailures)
    }
}
