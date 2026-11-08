package com.pyrochat.app.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.platform.BiometricInvalidated
import com.pyrochat.app.platform.BiometricKeyBox
import com.pyrochat.app.platform.BiometricVault
import com.pyrochat.app.platform.PrefsBiometricStore
import com.pyrochat.app.platform.StorePaths
import com.pyrochat.app.ui.Stage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * docs/DECOY-ACCOUNT.md §5, "The burn must take the biometric key with it".
 *
 * `[ BURN EVERYTHING ]` is narrowed to the current slot, and it used to spare the stored biometric
 * key whenever that key belonged to a slot it was not touching — a kindness, so a decoy's burn could
 * not quietly disable the real account's face unlock. That was §5's aftermath leak coming back
 * through the one piece of device state §5 had exempted: a phone that still raises a prompt at
 * launch has something left, and a phone with nothing does not, so someone made to wipe reads the
 * answer straight off the launch screen.
 *
 * **The spared case is the whole test.** A burn that destroys its OWN key proves nothing — the old
 * guarded code did that too. So the vault here holds a key belonging to a DIFFERENT slot, which is
 * exactly the case the guard used to protect, and the burn must still take it.
 *
 * No relay: `unlock` opens the store locally and fires `goOnline` into the background, so nothing
 * here needs the dev harness and this never skips.
 */
@RunWith(AndroidJUnit4::class)
class BurnTakesBiometricKeyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "burn-biometric-test")
    private val paths = StorePaths(
        context,
        filesDir = root,
        secretsDir = File(context.noBackupFilesDir, "secrets-burn-biometric-test"),
        secretsAlias = "com.pyrochat.app.secrets.burn-biometric-test",
    )
    private val deadman = DeadmanSwitch(
        context.getSharedPreferences("pyro-deadman-burn-biometric-test", Context.MODE_PRIVATE),
    )

    /**
     * The real [PrefsBiometricStore] over a preferences file of its own — the persistence is what
     * an examiner reads, so it is the real one; only the Keystore is faked, because its keys demand
     * a biometric prompt no test can answer.
     */
    private val prefs = context.getSharedPreferences("pyro-biometric-burn-test", Context.MODE_PRIVATE)
    private val store = PrefsBiometricStore(prefs)
    private val keys = FakeKeys()
    private val vault = BiometricVault(keys, store)

    private var backend: RealBackend? = null

    @Before fun clean() {
        paths.obliterate()
        root.deleteRecursively()
        root.mkdirs()
        deadman.disarm()
        prefs.edit().clear().commit()
        keys.delete()
    }

    @After fun tearDown() {
        backend?.let { runCatching { it.lock() }; runCatching { it.close() } }
        backend = null
        Thread.sleep(300)   // let the save queue finish before the next obliterate
        paths.obliterate()
        root.deleteRecursively()
        prefs.edit().clear().commit()
    }

    @Test fun theNarrowedBurnTakesTheBiometricKeyEvenWhenItBelongsToAnotherSlot(): Unit = runBlocking {
        // an account on this device, authored the way a cold create does (§1)
        val myKey = paths.create(PASSWORD, blind = true).use { s ->
            s.update { it.copy(usernameDisplay = "burner") }
            s.flush()
            s.storeKey
        }

        // the phone unlocks a DIFFERENT slot by face — §6.1's one item, owned by somebody else
        val otherKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        vault.finishEnable(vault.beginEnable(), otherKey)

        assertTrue("precondition: the phone holds a biometric key", vault.enabled)
        assertTrue("precondition: and it belongs to another slot", vault.ownedBy(otherKey))
        assertFalse("precondition: NOT to the account about to burn", vault.ownedBy(myKey))

        val b = RealBackend(context, paths, deadman, haptics = null, biometrics = vault)
            .also { backend = it; it.finishBoot() }
        assertNull("the account opens locally, no relay needed", b.unlock(PASSWORD))
        assertEquals(Stage.Main, b.stage.value)

        // the unlock stamps the 7-day clock (§4); the burn must take that with it too
        assertNotNull("precondition: a password unlock is on record", store.lastPasswordUnlock)

        // §A1's counter, set AFTER the unlock that resets it — the burn must NOT touch this one
        store.unlockFailures = 4

        b.totalWipe()

        // ---- §5: the key is gone, whoever it belonged to -------------------------------------
        assertFalse("the burn must take the biometric key", vault.enabled)
        assertNull("nothing wrapped is left in the preferences file", store.wrapped)
        assertFalse("and it may not still answer for the slot it held", vault.ownedBy(otherKey))
        assertTrue("the Keystore key goes with it", keys.deleted)

        // ---- shred, not destroy: the same bytes as the three total paths ----------------------
        assertNull("the last-password timestamp goes too, or a burn is legible beside a wipe", store.lastPasswordUnlock)
        assertEquals("the biometric failure count goes too", 0, store.failures)

        // ---- §5 "Device-level state a narrowed burn must not touch" ---------------------------
        assertEquals(
            "§A1's wrong-password counter acts on EVERY slot — clearing it from inside a decoy " +
                "would reach into an account the decoy may not know exists",
            4, store.unlockFailures,
        )

        myKey.fill(0)
        otherKey.fill(0)
    }

    /**
     * A [BiometricKeyBox] whose key lives in this JVM. The real one is `KeystoreBiometricKeys`,
     * whose keys are `setUserAuthenticationRequired(true)` and so cannot be driven from a test.
     * Written here rather than shared with `BiometricVaultTest`'s copy: a shared source set is a
     * build change for twenty lines, and this one needs no `BiometricStore` fake at all.
     */
    private class FakeKeys : BiometricKeyBox {
        private var key: SecretKey? = null
        var deleted = false
            private set

        override fun freshEncryptCipher(): Cipher {
            delete()
            deleted = false
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
            deleted = true
        }
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
    }
}
