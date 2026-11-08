package com.pyrochat.app.ui

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.platform.VoiceEffect
import com.pyrochat.app.ui.components.VoiceBeingChanged
import com.pyrochat.app.ui.components.VoiceRow
import com.pyrochat.app.ui.theme.PyroTheme
import com.pyrochat.app.ui.theme.TerminalScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/VOICE-CHANGER.md over the REAL composable: the line of voices the chat screen shows while
 * a note is being recorded, wired the way the chat screen wires it, with the canned [FakeBackend]
 * behind it. The one in use is marked, a tap chooses, and the choice is in the settings. iOS's
 * twin is `VoicePickerUITests`, which also sees the line in its place above the composer.
 */
@RunWith(AndroidJUnit4::class)
class VoicePickerScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun shown(description: String): Boolean =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun mount(textSize: String? = null): Backend {
        val backend = FakeBackend(context, scope)
        if (textSize != null) backend.updateSettings { it.copy(textSize = textSize) }
        compose.setContent {
            val settings by backend.settings.collectAsState()
            PyroTheme(settings) {
                TerminalScreen {
                    // exactly what `ChatScreen` does with it
                    VoiceRow(settings.voice) { voice ->
                        backend.updateSettings { it.copy(voiceChanger = if (voice == VoiceEffect.OFF) null else voice.wire) }
                    }
                }
            }
        }
        compose.waitForIdle()
        return backend
    }

    @Test fun theOneInUseIsMarkedAndATapChoosesAndIsKept() {
        val backend = mount()
        // nothing is changed until a voice is chosen
        compose.onNodeWithText("voice").assertIsDisplayed()
        compose.onNodeWithContentDescription("Your own voice, selected").assertIsDisplayed()
        for (voice in listOf("deep voice", "high voice", "robot voice")) compose.onNodeWithContentDescription(voice).assertIsDisplayed()
        for (label in listOf("[OFF]", "[DEEP]", "[HIGH]", "[ROBOT]")) compose.onNodeWithText(label).assertIsDisplayed()

        compose.onNodeWithContentDescription("robot voice").performClick()
        compose.waitUntil(5_000) { shown("robot voice, selected") }
        assertEquals("robot", backend.settings.value.voiceChanger)
        compose.onNodeWithContentDescription("Your own voice").assertIsDisplayed() // and only one is in use
        compose.onAllNodesWithContentDescription("Your own voice, selected").assertCountEquals(0)

        compose.onNodeWithContentDescription("deep voice").performClick()
        compose.waitUntil(5_000) { shown("deep voice, selected") }
        assertEquals("deep", backend.settings.value.voiceChanger)
        compose.onAllNodesWithContentDescription("robot voice, selected").assertCountEquals(0)

        // back to your own: the key goes, it is not stored as a word
        compose.onNodeWithContentDescription("Your own voice").performClick()
        compose.waitUntil(5_000) { shown("Your own voice, selected") }
        assertNull(backend.settings.value.voiceChanger)
    }

    /** What the composer says while a note that was sent is still being changed into its voice. */
    @Test fun theLineForANoteThatIsBeingChangedSaysSo() {
        val backend = FakeBackend(context, scope)
        compose.setContent {
            val settings by backend.settings.collectAsState()
            PyroTheme(settings) { TerminalScreen { VoiceBeingChanged() } }
        }
        compose.waitForIdle()
        compose.onNodeWithText("changing voice…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Changing the voice of your note").assertIsDisplayed()
    }

    /** At the largest text size the four voices are still whole, on one line, inside the screen. */
    @Test fun theLineFitsAtTheLargestTextSize() {
        mount(textSize = "largest")
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        var lastEdge = 0f
        var top: Float? = null
        for (voice in listOf("Your own voice, selected", "deep voice", "high voice", "robot voice")) {
            val node = compose.onNodeWithContentDescription(voice)
            node.assertIsDisplayed()
            val box = node.fetchSemanticsNode().boundsInRoot
            assertEquals("$voice is on the same line", top ?: box.top, box.top, 1f)
            assertTrue("$voice does not overlap the one before it", box.left >= lastEdge - 0.5f)
            assertTrue("$voice is inside the screen", box.right <= screen.right)
            top = box.top
            lastEdge = box.right
        }
    }
}
