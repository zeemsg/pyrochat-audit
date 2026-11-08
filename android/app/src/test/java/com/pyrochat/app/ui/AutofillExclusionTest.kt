package com.pyrochat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every text field in this app is excluded from autofill, and the two facts that make that true are
 * structural rather than behavioural — so they are guarded here rather than left to a device run.
 *
 * The exposure this defends against, measured on an emulator with Google's autofill service
 * enabled: focusing a password field started an autofill session over the activity, the framework
 * was handed each keystroke through `notifyValueChanged`, and submitting called `commit()` — which
 * is precisely the cue a provider uses to offer "save password?". A provider accepting that would
 * put a cloud copy of the one secret protecting this device somewhere that survives every wipe path
 * in docs/SCRUB-AND-WIPE.md, the duress PIN included, and would make "the password never leaves
 * your device" false.
 *
 * `View.importantForAutofill` is NOT the lever: Compose hosts each field as a virtual view and the
 * framework's virtual-view path never consults it. The lever is the field's own semantics.
 *
 * There is no Compose UI test harness in this module, so this asserts on the source: one field
 * component, and that component opted out. Both are cheap to keep true and expensive to rediscover.
 */
class AutofillExclusionTest {
    private val ui = File("src/main/java/com/pyrochat/app/ui")
    private val terminal = File(ui, "components/Terminal.kt")

    private fun sources(): List<File> = ui.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun everyTextFieldInTheAppGoesThroughTheOneComponent() {
        assertTrue("the ui source tree must be readable from the module dir", terminal.isFile)
        val withRawFields = sources().filter { it.readText().contains("BasicTextField(") }
        assertEquals(
            "a text field outside TerminalTextField would not carry the autofill opt-out: " +
                withRawFields.map { it.name },
            listOf(terminal.name),
            withRawFields.map { it.name },
        )
    }

    @Test fun thatComponentOptsOutOfAutofill() {
        val src = terminal.readText()
        assertTrue(
            "TerminalTextField must mark every field ContentDataType.None — without it the " +
                "autofill framework is handed each keystroke and a commit() on submit",
            src.contains("contentDataType = ContentDataType.Companion.None"),
        )
        // …and it must be on the shared modifier, not behind `secure`: the username is excluded too,
        // because a provider pairs it with the password to make the credential it offers to save.
        val fieldBody = src.substringAfter("fun TerminalTextField(").substringBefore("\n}")
        assertTrue(
            "the opt-out must apply to every field, not only the secure ones",
            fieldBody.contains("contentDataType = ContentDataType.Companion.None") &&
                !fieldBody.substringBefore("contentDataType").trimEnd().endsWith("if (secure)"),
        )
    }

    @Test fun theSecretFieldsAreTheOnesThisProtects() {
        // A roll-call, so a new secret field added outside these screens is noticed: the store
        // password (unlock + create + confirm), the backup passphrase (restore + export), the
        // duress PIN and the decoy password.
        val secureUses = sources().sumOf { f -> Regex("secure\\s*=\\s*true").findAll(f.readText()).count() }
        assertTrue("expected the known secret fields to still be present, found $secureUses", secureUses >= 8)
    }
}
