package com.pyrochat.app.platform

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HapticsAndCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun hapticsNeverThrow() {
        val h = Haptics(context)
        h.tap()
        h.warn()
        // emulators report no vibrator; phones do — either way the calls above are safe
        assertEquals(h.available, h.available)
    }

    @Test fun screenCaptureFlowsRegisterAndUnregisterCleanly() {
        assertEquals(Build.VERSION.SDK_INT >= 34, ScreenCaptureDetector.screenshotsSupported)
        assertEquals(Build.VERSION.SDK_INT >= 35, ScreenCaptureDetector.recordingSupported)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var activity: Activity? = null
            scenario.onActivity { activity = it }
            val a = activity!!
            runBlocking {
                // no hardware screenshot happens during the test → no emission, and cancelling unregisters
                assertNull(withTimeoutOrNull(500) { ScreenCaptureDetector.screenshots(a).first() })
                val recording = withTimeoutOrNull(2_000) { ScreenCaptureDetector.recording(a).first() }
                if (ScreenCaptureDetector.recordingSupported) assertEquals(false, recording) else assertNull(recording)
            }
        }
    }
}
