package com.pyrochat.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.store.SecretStore
import com.pyrochat.store.StoreError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class StorePathsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "store-paths-test")
    private val paths = StorePaths(
        context,
        filesDir = root,
        secretsDir = File(context.noBackupFilesDir, "secrets-store-paths-test"),
        secretsAlias = "com.pyrochat.app.secrets.store-paths-test",
    )

    @Before fun clean() {
        paths.obliterate()
        root.deleteRecursively()
        root.mkdirs()
    }

    @Test fun layoutIsInternalStorage() {
        assertEquals(File(root, "pyro.store").toPath(), paths.storeFile)
        assertEquals(File(root, "attachments").toPath(), paths.attachmentsDir)
        assertTrue(paths.storeFile.toString().startsWith(context.filesDir.absolutePath))
        assertEquals(context.cacheDir, paths.tmpDir)
        assertTrue(paths.secretsDir.absolutePath.startsWith(context.noBackupFilesDir.absolutePath))
    }

    @Test fun createFlushOpenWithKeystoreSaltAndNativeKdf() {
        assertFalse(paths.storeExists())
        val created = paths.create("correct horse battery staple")
        created.update { it.copy(usernameDisplay = "alice") }
        created.flush()
        created.close()
        assertTrue(paths.storeExists())
        assertNotNull("salt must now live in the Keystore-backed store", paths.secrets.get(SecretStore.KDF_SALT))
        assertTrue(NativeKdfAdapter.usesNative)

        val opened = paths.open("correct horse battery staple")
        assertEquals("alice", opened.blob.usernameDisplay)
        opened.close()

        try {
            paths.open("wrong password"); throw AssertionError("opened with the wrong password")
        } catch (_: StoreError.BadPassword) { }
    }

    @Test fun duressPinRoundTrip() {
        paths.create("pw").close()
        assertFalse(paths.duressPinSet())
        assertFalse(paths.matchesDuressPin("2580"))
        paths.setDuressPin("2580")
        assertTrue(paths.duressPinSet())
        assertTrue(paths.matchesDuressPin("2580"))
        assertFalse(paths.matchesDuressPin("2581"))
    }

    @Test fun obliterateRemovesStoreSidecarsSecretsAndTmp() {
        val store = paths.create("pw")
        store.flush()
        store.writeAttachment("6ba7b810-9dad-11d1-80b4-00c04fd430c8", ByteArray(10))
        store.close()
        paths.setDuressPin("1111")
        for (prefix in StorePaths.PLAINTEXT_PREFIXES) {
            File(context.cacheDir, "${prefix}probe").apply { mkdirs(); File(this, "x").writeBytes(ByteArray(3)) }
        }
        File(context.cacheDir, "keep-me").writeBytes(ByteArray(1))
        val sizeBefore = Files.size(paths.storeFile)

        paths.obliterate()

        // docs/DECOY-ACCOUNT.md §5: no path ever deletes the container. Wiped, never used and
        // holding two accounts are the same number of bytes; what a wipe takes is everything in it
        // and everything that opens it.
        assertTrue(paths.storeExists())
        assertEquals(sizeBefore, Files.size(paths.storeFile))
        assertFalse(Files.exists(paths.attachmentsDir))
        assertNull(paths.secrets.get(SecretStore.KDF_SALT))
        assertNull(paths.secrets.get(SecretStore.DURESS_VERIFIER))
        try {
            paths.open("pw").close()
            fail("the password still opens something after the wipe")
        } catch (_: StoreError.BadPassword) {
            // nothing opens: the wiped container reads like one that never held an account
        }
        for (prefix in StorePaths.PLAINTEXT_PREFIXES) assertFalse(File(context.cacheDir, "${prefix}probe").exists())
        assertTrue(File(context.cacheDir, "keep-me").exists())
        File(context.cacheDir, "keep-me").delete()
    }
}
