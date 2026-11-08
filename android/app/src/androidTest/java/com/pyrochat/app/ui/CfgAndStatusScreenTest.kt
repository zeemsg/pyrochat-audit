package com.pyrochat.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.R
import com.pyrochat.app.ui.screens.SettingsScreen
import com.pyrochat.app.ui.screens.StatusScreen
import com.pyrochat.app.ui.theme.PyroTheme
import com.pyrochat.app.ui.theme.TerminalScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/STATUS-AND-CFG.md §4 over the REAL composables, because two of its asks cannot be answered
 * anywhere else on Android: this module has no Compose harness in its JVM suite (see
 * `AutofillExclusionTest`), so a claim about what a screen *renders* has to be made on a device.
 *
 * Three things live here:
 *
 * 1. **The status screen never mentions a decoy** — asserted over the whole rendered surface, the
 *    semantics tree of the entire page, not over one label. [StatusScreenModelTest] proves the
 *    model cannot say it; this proves nothing on the way to the glass adds it back.
 * 2. **The `[cfg]` decoy row renders identically whatever the device holds** (docs/DECOY-ACCOUNT.md
 *    §3). The row moved section in this work, which is exactly when a row picks up a checkmark,
 *    so the same two-dumps-must-match comparison `AuthScreenFirstScreenTest` makes for the auth
 *    screen is made here for `[cfg]`.
 * 3. **Tapping a status row reaches the setting that changes it**, and the rows with no setting
 *    behind them do not move.
 *
 * The canned [FakeBackend] shell is what is mounted: every screen renders from it, and unlike the
 * real backend it can be put into "this account owns a decoy" without a relay.
 */
@RunWith(AndroidJUnit4::class)
class CfgAndStatusScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The backend currently mounted under the screen; swapping it remounts. */
    private var mounted by mutableStateOf<Backend?>(null)

    @After fun tearDown() {
        FakeBackend.fakeDecoyMade = false
        mounted = null
    }

    private fun backend(): Backend = FakeBackend(context, scope)

    /**
     * Node ids are handed out globally, so a second mount gets fresh ones; the identity hash in a
     * default `toString()` is per-instance. Everything else in the dump — every string, every
     * content description, every action, every bound to the pixel — is what must not differ.
     */
    private fun dump(): String = compose.onRoot().printToString(Int.MAX_VALUE)
        .replace(NODE_ID, "Node #")
        .replace(IDENTITY_HASH, "@")

    // ---- 1. the status screen, whole surface, no decoy ----------------------------------

    @Test fun theStatusScreenNeverMentionsADecoyInAnyState() {
        compose.setContent {
            PyroTheme(AppSettings()) {
                TerminalScreen {
                    // `key` so a swap remounts rather than reusing the previous screen's state
                    mounted?.let { b -> key(b) { StatusScreen(b, onBack = {}, onOpenSetting = {}) } }
                }
            }
        }
        for (decoy in listOf(false, true)) {
            FakeBackend.fakeDecoyMade = decoy
            compose.runOnUiThread { mounted = backend() }
            compose.waitForIdle()
            // `sender hidden` is the one place the word appears: the row is about the relay, and it
            // is the same on iOS (docs/SEALED-SENDER.md §5.8). Anywhere else it still fails.
            val surface = dump().lowercase().replace("sender hidden", "")

            // the dump must actually be the screen — an empty one would pass forever
            assertTrue("the dump must be the status screen", surface.contains("what protects you"))
            assertTrue(surface.contains("what leaves this phone"))
            assertTrue(surface.contains("if you lose it"))

            for (word in FORBIDDEN) {
                assertFalse(
                    "docs/DECOY-ACCOUNT.md §3: the status screen may never render \"$word\" " +
                        "(decoy present = $decoy)\n$surface",
                    surface.contains(word),
                )
            }
        }
    }

    // ---- 2. `[cfg]` is the same screen whether or not a decoy exists ---------------------

    /**
     * docs/DECOY-ACCOUNT.md §3: "The row reads the same whether or not a decoy exists. It never
     * says 'configured', never shows a checkmark, never changes colour. A row that renders
     * differently once used is a disclosure."
     *
     * Asserted over the whole of `[cfg]`, not over the decoy row alone, because the disclosure
     * could equally be a footnote that appears, a status line left behind, or a row that shifts
     * everything under it by a pixel.
     */
    @Test fun theCfgScreenIsTheSameWhetherOrNotThisAccountOwnsADecoy() {
        compose.setContent {
            PyroTheme(AppSettings()) {
                TerminalScreen {
                    mounted?.let { b -> key(b) { SettingsScreen(b, onBack = {}, onStatus = {}, onLinkDesktop = {}, onLicenses = {}) } }
                }
            }
        }

        FakeBackend.fakeDecoyMade = false
        compose.runOnUiThread { mounted = backend() }
        compose.waitForIdle()
        val without = dump()

        FakeBackend.fakeDecoyMade = true
        compose.runOnUiThread { mounted = backend() }
        compose.waitForIdle()
        val with = dump()

        assertTrue("the dump must actually be [cfg]", without.contains("set decoy password …"))
        assertTrue("the two states must both have been rendered", with.contains("set decoy password …"))
        assertEquals(
            "docs/DECOY-ACCOUNT.md §3: `[cfg]` may not differ with whether this account owns a decoy",
            without,
            with,
        )
    }

    /**
     * docs/STATUS-AND-CFG.md §2 "what must not move", as RENDERED. [CfgLayoutTest] asserts the
     * order in the layout; this asserts that the screen draws that order, because a row rendered
     * outside the layout walk would satisfy the first and break the second.
     */
    @Test fun theRowsThatMustNotMoveAreDrawnWhereTheyMustNotMoveFrom() {
        compose.setContent {
            PyroTheme(AppSettings()) {
                TerminalScreen {
                    SettingsScreen(backend(), onBack = {}, onStatus = {}, onLinkDesktop = {}, onLicenses = {})
                }
            }
        }
        compose.waitForIdle()
        val surface = dump()

        // `printToString` merges each row into one node and prints its texts as a list, so the
        // dump reads `Text = '[auto-lock, 5m ↕]'`. Taking them in order gives the screen's order.
        val texts = Regex("Text = '\\[(.*?)]'").findAll(surface).map { it.groupValues[1] }.toList()

        /** Where the first row whose text contains this appears, top to bottom. */
        fun at(token: String): Int {
            val i = texts.indexOfFirst { it.contains(token) }
            assertTrue("`$token` is not on the screen at all. what is: ${texts.joinToString(" | ")}", i >= 0)
            return i
        }
        fun header(name: String) = at("\u2500\u2500 $name \u2500\u2500")

        assertTrue("the dump must actually be [cfg]", texts.size > 20)

        // `[ what protects me ]` is the first row of the screen, above every section
        assertTrue("§2: the status row sits above every section", at("[ what protects me ]") < header("PROTECTION"))
        assertTrue("§2: APPEARANCE second, directly after PROTECTION", header("PROTECTION") < header("APPEARANCE"))
        assertTrue(header("APPEARANCE") < header("WHAT LEAVES THIS PHONE"))
        assertTrue(header("WHAT LEAVES THIS PHONE") < header("ACCOUNT"))
        assertTrue(header("ACCOUNT") < header("DANGER"))

        // docs/TEXT-SIZE.md §1 — `text size` DIRECTLY under `theme`, nothing between them
        assertEquals("docs/TEXT-SIZE.md §1", at("theme") + 1, at("text size"))
        assertTrue(header("APPEARANCE") < at("theme"))
        assertTrue(at("scanlines") < header("WHAT LEAVES THIS PHONE"))

        // docs/BIOMETRIC-UNLOCK.md §2 — the biometric row is directly above `auto-lock`. This
        // emulator has nothing enrolled, so `[cfg]` correctly draws no biometric row at all and
        // `auto-lock` is the first row of PROTECTION; either way nothing else may sit between.
        val bio = texts.indexOfFirst { it.startsWith("unlock with ") }
        if (bio >= 0) assertEquals("docs/BIOMETRIC-UNLOCK.md §2", bio + 1, at("auto-lock"))
        else assertEquals("nothing enrolled: auto-lock leads PROTECTION", header("PROTECTION") + 1, at("auto-lock"))

        // §2 — the decoy row moved into PROTECTION and kept its exact label
        assertTrue(header("PROTECTION") < at("set decoy password …"))
        assertTrue(at("set decoy password …") < header("APPEARANCE"))

        // §1 settles this group's order for both screens: proxy sits second, not fourth
        assertEquals(header("WHAT LEAVES THIS PHONE") + 1, at("wake push"))
        // The wake-push row may carry one footnote of its own: what the banner does, that
        // notifications are blocked, or, in a build made without `app/firebase.properties` (any
        // fresh clone), that push is unavailable. The footnote is part of that row; the next ROW is
        // the proxy, and nothing else may sit between them.
        val underWakePush = texts.subList(at("wake push") + 1, at("socks proxy"))
        val footnotes = listOf(R.string.cfg_wake_push_footnote, R.string.cfg_wake_push_unavailable, R.string.cfg_wake_push_blocked)
            .map { context.getString(it).take(24) }
        assertTrue(
            "only wake push's own footnote may sit between it and the proxy: $underWakePush",
            underWakePush.size <= 1 && underWakePush.all { note -> footnotes.any { note.startsWith(it) } },
        )
        assertEquals(at("socks proxy") + 1, at("read receipts"))
        assertEquals(at("read receipts") + 1, at("typing indicators"))
        assertEquals(at("typing indicators") + 1, at("LINK DESKTOP"))
        assertTrue(at("LINK DESKTOP") < header("ACCOUNT"))

        // §2 — `[ BURN EVERYTHING ]` stays last, alone, in DANGER: the last thing on the screen,
        // and the only thing after the header
        assertEquals("§2: burn everything is the last row of `[cfg]`", texts.size - 1, at("[ BURN EVERYTHING ]"))
        assertEquals("§2: alone in DANGER", header("DANGER") + 1, at("[ BURN EVERYTHING ]"))
    }

    // ---- 3. tapping a row reaches the control that changes it ---------------------------

    @Test fun tappingAStatusRowReachesTheSettingThatChangesIt() {
        val reached = ArrayList<CfgRow>()
        compose.setContent {
            PyroTheme(AppSettings()) {
                TerminalScreen {
                    StatusScreen(backend(), onBack = {}, onOpenSetting = { reached += it })
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("auto-lock").assertIsDisplayed().performClick()
        compose.onNodeWithText("duress pin").performClick()
        compose.onNodeWithText("read receipts").performClick()
        compose.onNodeWithText("backup").performClick()
        assertEquals(listOf(CfgRow.AUTO_LOCK, CfgRow.DURESS, CfgRow.READ_RECEIPTS, CfgRow.BACKUP), reached)

        // the three facts with no setting behind them go nowhere
        compose.onNodeWithText("wrong attempts").performClick()
        compose.onNodeWithText("sender hidden").performClick()
        compose.onNodeWithText("verified contacts").performClick()
        assertEquals("a fact with no switch behind it must not navigate", 4, reached.size)
    }

    private companion object {
        val NODE_ID = Regex("Node #\\d+")
        val IDENTITY_HASH = Regex("@[0-9a-f]{4,}")

        /** Anything the status screen renders that would answer "is there a second account here". */
        val FORBIDDEN = listOf("decoy", "hidden", "slot", "second account", "other account", "container")

    }
}
