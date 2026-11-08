package com.pyrochat.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.backend.DeadmanSwitch
import com.pyrochat.app.backend.RealBackend
import com.pyrochat.app.platform.StorePaths
import com.pyrochat.app.ui.screens.AuthScreen
import com.pyrochat.app.ui.theme.PyroTheme
import com.pyrochat.app.ui.theme.TerminalScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * docs/DECOY-ACCOUNT.md §5 over the real `AuthScreen`, because nothing else on Android can see it.
 *
 * Two facts live here and neither is observable from a JVM unit test, which is why this is the one
 * instrumented UI test in the app module:
 *
 * 1. **A launch reaches the password field with no interaction.** A real tester reported the
 *    opposite against a shipped build — the chooser charged every returning user a tap on every
 *    single launch — and a regression would be silent without this.
 * 2. **The first screen is the same whether or not an identity exists.** That is the property the
 *    fix above must not buy its convenience with, and it is a NEGATIVE one: nothing rendered here
 *    may differ with what the device holds. §5 is explicit that the leak was ever only in
 *    *branching*, so a form shown unconditionally is free — but "unconditionally" has to be checked
 *    rather than asserted in a comment.
 *
 * The comparison is only worth anything if the two devices genuinely differ, so [storeExists] is
 * asserted false and then true around it: without that this test would happily compare two empty
 * devices and pass forever.
 *
 * Everything runs against scratch [StorePaths] under a directory of its own, so the emulator's real
 * store is never touched, and no relay is needed — the screen is pre-unlock and talks to nothing.
 */
@RunWith(AndroidJUnit4::class)
class AuthScreenFirstScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "auth-first-screen-test")
    private val paths = StorePaths(
        context,
        filesDir = root,
        secretsDir = File(context.noBackupFilesDir, "secrets-auth-first-screen-test"),
        secretsAlias = "com.pyrochat.app.secrets.auth-first-screen-test",
    )
    private val deadman = DeadmanSwitch(
        context.getSharedPreferences("pyro-auth-first-screen-test", Context.MODE_PRIVATE),
    )

    /** The backend currently mounted under the screen; swapping it remounts, which is "a launch". */
    private var mounted by mutableStateOf<Backend?>(null)
    private val opened = ArrayList<RealBackend>()

    @Before fun clean() {
        paths.obliterate()
        root.deleteRecursively()
        root.mkdirs()
        deadman.disarm()
    }

    @After fun tearDown() {
        opened.forEach { runCatching { it.lock() }; runCatching { it.close() } }
        opened.clear()
        paths.obliterate()
        root.deleteRecursively()
    }

    /**
     * Mount a FRESH backend over whatever is on disk right now and return the rendered screen.
     *
     * A new backend per disk state rather than one backend whose disk changed underneath: that is
     * what a launch actually is, and it is the only version of this that proves anything about the
     * state the screen starts in.
     */
    private fun launchAndDump(): String {
        val backend = compose.runOnUiThread {
            RealBackend(context, paths, deadman, haptics = null).also {
                it.finishBoot()
                opened += it
            }
        }
        compose.runOnUiThread { mounted = backend }
        awaitContainer()
        compose.waitForIdle()
        // Two things in the dump are per-mount bookkeeping rather than anything on screen, and both
        // are normalised away: semantics node ids, handed out globally so a remount gets fresh ones;
        // and the identity hash in the default `toString()` of property values that do not override
        // it (the autofill data object). Everything else — every string, every content description,
        // every action, every bound to the pixel — is exactly what must not differ.
        return compose.onRoot().printToString(Int.MAX_VALUE)
            .replace(NODE_ID, "Node #")
            .replace(IDENTITY_HASH, "@")
    }

    private fun setUpScreen() {
        compose.setContent {
            PyroTheme(AppSettings()) {
                TerminalScreen {
                    // `key` so a swap remounts rather than reusing the previous screen's state —
                    // the initial pane is the thing under test, and a reused one would never be read
                    mounted?.let { backend -> key(backend) { AuthScreen(backend) } }
                }
            }
        }
    }

    /**
     * §5 + the tester's report: the password field is there, already, with nothing tapped. The
     * chooser's buttons are NOT — reaching them is a deliberate tap now, which is the right way
     * round: signing in happens daily, creating an identity happens once.
     */
    @Test fun aLaunchReachesThePasswordFieldWithNoInteraction() {
        setUpScreen()
        launchAndDump()

        compose.onNode(hasSetTextAction()).assertExists()          // the password field itself
        compose.onNodeWithText("password:").assertExists()
        compose.onNodeWithText("[ UNLOCK ]").assertExists()

        // not the chooser: these are one tap away, not in front of a returning user
        compose.onNodeWithText("[ CREATE IDENTITY ]").assertDoesNotExist()
        compose.onNodeWithText("[ RESTORE BACKUP ]").assertDoesNotExist()

        // and the way to them is offered, worded as a question about the PERSON
        compose.onNodeWithText("[ OTHER OPTIONS ]").assertExists()
    }

    /**
     * The property the convenience must not be bought with. Same screen, byte for byte, on a device
     * that holds no account and on one that holds a real one — so it cannot be used to tell.
     *
     * **`storeExists()` is not the question**, and getting that wrong the first time is worth
     * recording: §5 gives every device a container from first run and never deletes it, so the file
     * is there on a phone that has never held an account, on one holding two, and on one wiped ten
     * minutes ago. The honest pair is a container of pure noise against a container with an account
     * in it, and the preconditions below say so by trying the password: it must fail on the first
     * device and open a real identity on the second. Without that this test would compare two
     * identical devices and pass forever.
     *
     * The two sentences §5 forbids are checked by name as well: they were deleted from
     * `strings.xml`, and this fails if anything puts them back.
     */
    @Test fun theFirstScreenIsTheSameWhetherOrNotAnIdentityExists() {
        setUpScreen()

        // a launch on a device with a container and NO account in it
        val withoutIdentity = launchAndDump()
        assertFalse("precondition: no account opens with this password yet", opens(PASSWORD))

        // a real identity in that container, written by the real store — no relay involved, the
        // screen is pre-unlock and talks to nothing. `blind` is what a cold create from this very
        // screen does (§1): rewrite the whole container rather than gamble on a slot.
        paths.create(PASSWORD, blind = true).use { store ->
            store.update { it.copy(usernameDisplay = "someone") }
            store.flush()
        }
        assertTrue("precondition: this device now holds a real identity", opens(PASSWORD))

        val withIdentity = launchAndDump()

        // an equality between two empty dumps would pass forever and prove nothing
        assertTrue("the dump must actually be the screen", withoutIdentity.contains("[ UNLOCK ]"))

        assertEquals(
            "docs/DECOY-ACCOUNT.md §5: the first screen may not differ with what the device holds",
            withoutIdentity,
            withIdentity,
        )

        for (claim in listOf("an identity lives on this device", "no identity on this device")) {
            assertFalse("the screen may never say \"$claim\"", withIdentity.contains(claim))
        }
    }

    /**
     * §5: `finishBoot` authors the noise container on an IO coroutine, so it lands some time after
     * the screen does. Waited for rather than raced, so it cannot write the store file underneath
     * an account this test has just created.
     */
    private fun awaitContainer() {
        val deadline = System.currentTimeMillis() + 10_000
        while (!paths.storeExists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the container must exist on every device, account or not", paths.storeExists())
    }

    /** Does a real account open with this password? The only honest "is there an identity here". */
    private fun opens(password: String): Boolean =
        runCatching { paths.open(password).use { it.blob.usernameDisplay != null } }.getOrDefault(false)

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val NODE_ID = Regex("Node #\\d+")
        val IDENTITY_HASH = Regex("@[0-9a-f]{4,}")
    }
}
