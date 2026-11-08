package com.pyrochat.app.push

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.BuildConfig
import com.pyrochat.app.backend.PushRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties

/** Token plumbing (`onNewToken` → `PushRegistration` → the session callback) and the no-Firebase-config path. */
@RunWith(AndroidJUnit4::class)
class PushRegistrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun newTokenReachesTheSessionCallback() {
        val seen = ArrayList<String>()
        PushRegistration.onToken = { seen += it }
        try {
            PyroFcmService().onNewToken("dEvIcE-token:APA91bExampleExampleExampleExample")
            assertEquals(listOf("dEvIcE-token:APA91bExampleExampleExampleExample"), seen)
            assertEquals("dEvIcE-token:APA91bExampleExampleExampleExample", PushRegistration.currentToken)
            PyroFcmService().onNewToken("   ") // never a blank token
            assertEquals(1, seen.size)
        } finally {
            PushRegistration.onToken = null
            PushRegistration.detach()
        }
        assertNull(PushRegistration.currentToken)
    }

    @Test fun debugExtraInjectsAStandInTokenOnlyInDebugBuilds() {
        val seen = ArrayList<String>()
        PushRegistration.onToken = { seen += it }
        try {
            PushRegistration.debugInject(Intent().putExtra("fcmToken", "fake-fcm-token-for-relay-tests-0001"))
            if (BuildConfig.DEBUG) {
                assertEquals("fake-fcm-token-for-relay-tests-0001", PushRegistration.currentToken)
                PushRegistration.detach()
                assertNull(PushRegistration.currentToken)
                PushRegistration.requestAndRegister() // the toggle back on re-applies the stand-in
                assertEquals("fake-fcm-token-for-relay-tests-0001", PushRegistration.currentToken)
                assertEquals(2, seen.size)
            } else {
                assertNull(PushRegistration.currentToken)
                assertTrue(seen.isEmpty())
            }
        } finally {
            PushRegistration.onToken = null
            PushRegistration.debugClear()
            PushRegistration.detach()
        }
    }

    @Test fun withoutFirebaseConfigEverythingIsASafeNoOp() {
        PushRegistration.debugClear()
        val configured = PushOptions.fromBuildConfig() != null
        assertEquals(configured, Push.available)
        if (configured) return // a real project: the token path is exercised on a device with Play services
        assertFalse(PushRegistration.available)
        // no crash, no fake token
        PushRegistration.onToken = null
        PushRegistration.detach()
        PushRegistration.requestAndRegister()
        assertNull(PushRegistration.currentToken)
        assertFalse(Push.initialize(context, null))
        assertNull(PushOptions.fromProperties(Properties()))
        assertFalse(File(context.filesDir, "firebase.properties").exists())
    }
}
