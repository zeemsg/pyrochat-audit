package com.pyrochat.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoicePlayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun playTempFiles() = context.cacheDir.listFiles()?.filter { it.name.startsWith("vn-play-") }.orEmpty()

    @Test fun playsToTheEndReportsProgressAndResets() {
        val m4a = WaveformTest.fixture(context)   // 1 s
        val player = onMain { VoicePlayer(context) }
        onMain { player.toggle("m1", m4a) }
        assertEquals("m1", onMain { player.playingId.value })
        var maxProgress = 0f
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            val (id, p) = onMain { player.playingId.value to player.progress.value }
            if (id == null) break
            if (p > maxProgress) maxProgress = p
            Thread.sleep(50)
        }
        assertNull("playback must end on its own", onMain { player.playingId.value })
        assertEquals(0f, onMain { player.progress.value }, 0f)
        assertTrue("progress ticked (max=$maxProgress)", maxProgress > 0.2f)
        assertTrue(playTempFiles().isEmpty())
        onMain { player.release() }
    }

    @Test fun toggleOnThePlayingIdStopsAndSeekClamps() {
        val m4a = WaveformTest.fixture(context)
        val player = onMain { VoicePlayer(context) }
        onMain { player.toggle("m2", m4a) }
        val afterSeek = onMain { player.seek("m2", 0.5f); player.progress.value }
        assertEquals(0.5f, afterSeek, 0f)
        val afterWrongId = onMain { player.seek("other", 0.9f); player.progress.value }   // wrong id: ignored
        assertTrue("progress=$afterWrongId", afterWrongId < 0.85f)
        onMain { player.toggle("m2", m4a) }                       // same id → stop
        assertNull(onMain { player.playingId.value })
        assertEquals(0f, onMain { player.progress.value }, 0f)
        onMain { player.release() }
    }

    @Test fun garbageBytesDoNotStartPlayback() {
        val player = onMain { VoicePlayer(context) }
        onMain { player.toggle("bad", ByteArray(1000) { 7 }) }
        assertNull(onMain { player.playingId.value })
        assertTrue(playTempFiles().isEmpty())
    }
}
