package com.pyrochat.app.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Android 10+ only serves the clipboard to the focused window, so the app's activity is launched first. */
@RunWith(AndroidJUnit4::class)
class ClipboardImagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun png(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun withFocusedActivity(block: (MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = System.currentTimeMillis() + 10_000
            var focused = false
            while (!focused && System.currentTimeMillis() < deadline) {
                scenario.onActivity { focused = it.hasWindowFocus() }
                if (!focused) Thread.sleep(100)
            }
            assertTrue("activity never got window focus", focused)
            scenario.onActivity(block)
        }
    }

    @Test fun imageOnlyClipboardIsInterceptedAndNormalised() {
        val staged = FileAccess.stageExport(context, "clip.png", png(640, 480))
        try {
            withFocusedActivity { activity ->
                val cm = activity.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newUri(activity.contentResolver, "img", staged.uri))
                val ci = ClipboardImages(activity)
                assertTrue(ci.hasImage())
                assertFalse(ci.hasText())
                assertTrue(ci.shouldInterceptPaste())
                val n = ci.pastedImage()
                assertNotNull(n); n!!
                assertEquals(640, n.width); assertEquals(480, n.height)
                assertEquals(0xFF.toByte(), n.jpeg[0])
                cm.clearPrimaryClip()
            }
        } finally {
            staged.cleanup()
        }
    }

    @Test fun textClipboardIsANormalPaste() {
        withFocusedActivity { activity ->
            val cm = activity.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("t", "hello"))
            val ci = ClipboardImages(activity)
            assertFalse(ci.hasImage())
            assertTrue(ci.hasText())
            assertFalse(ci.shouldInterceptPaste())
            assertNull(ci.pastedImage())
            cm.clearPrimaryClip()
            assertFalse(ci.hasImage())
            assertFalse(ci.hasText())
        }
    }
}
