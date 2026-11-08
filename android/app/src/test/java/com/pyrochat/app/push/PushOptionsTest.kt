package com.pyrochat.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Properties

/** The `firebase.properties` → `BuildConfig` → `PushOptions` loader, with and without the file. */
class PushOptionsTest {
    private val sender = "123456789012"
    private val appId = "1:$sender:android:0a1b2c3d4e5f6789"

    @Test fun absentFileBakesBlanksWhichMeansUnavailable() {
        // `app/build.gradle.kts` writes "" into every field when the file is missing
        assertNull(PushOptions.from("", "", "", ""))
        assertNull(PushOptions.from(null, null, null, null))
        assertNull(PushOptions.fromProperties(Properties()))
    }

    @Test fun completeFileLoads() {
        val props = Properties().apply {
            setProperty("projectId", " pyrochat-ca ")
            setProperty("applicationId", appId)
            setProperty("apiKey", "AIzaSyExampleExampleExampleExampleExam")
            setProperty("gcmSenderId", sender)
        }
        val o = PushOptions.fromProperties(props)
        assertNotNull(o)
        assertEquals("pyrochat-ca", o!!.projectId) // trimmed
        assertEquals(appId, o.applicationId)
        assertEquals(sender, o.senderId)
    }

    @Test fun anyMissingValueMeansUnavailable() {
        assertNull(PushOptions.from("p", appId, "", sender))
        assertNull(PushOptions.from("", appId, "k", sender))
        assertNull(PushOptions.from("p", "", "k", sender))
        assertNull(PushOptions.from("p", appId, "k", ""))
    }

    @Test fun malformedValuesAreRefused() {
        assertNull(PushOptions.from("p", appId, "k", "not-a-number"))
        assertNull(PushOptions.from("p", "com.pyrochat.app", "k", sender))          // not a Firebase app id
        assertNull(PushOptions.from("p", "1:$sender:ios:0a1b2c3d", "k", sender))    // the iOS app's id
        assertNull(PushOptions.from("p", "1:999:android:0a1b2c3d", "k", sender))    // sender from another project
    }

    @Test fun buildConfigMatchesTheFileOnDisk() {
        // unit tests run with the module dir as cwd — the same file the build script read
        val file = File("firebase.properties")
        val fromDisk = if (file.isFile) PushOptions.fromProperties(Properties().apply { file.inputStream().use(::load) }) else null
        assertEquals(fromDisk, PushOptions.fromBuildConfig())
    }
}
