package com.pyrochat.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/BIOMETRIC-UNLOCK.md §5: "Delete it, unconditionally and before anything else" — on the
 * duress PIN, the dead-man wipe and `TOTAL WIPE` / obliterate. [LocalWipe] is the one place
 * `RealBackend` runs those from, so driving every [LocalWipe.Reason] here covers all three paths
 * (and the registration rollback, which leaves the same debris).
 */
class LocalWipeTest {
    private val ran = mutableListOf<String>()
    private val wipe = LocalWipe(
        biometrics = { ran += "biometrics" },
        obliterate = { ran += "obliterate" },
        notices = { ran += "notices" },
        deadman = { ran += "deadman" },
    )

    @Test fun everyReasonDestroysTheStoredBiometricKey() {
        for (reason in LocalWipe.Reason.entries) {
            ran.clear()
            wipe.wipe(reason)
            assertTrue("$reason must destroy the biometric key", "biometrics" in ran)
            assertEquals("$reason must destroy it BEFORE anything else", 0, ran.indexOf("biometrics"))
            assertEquals(reason, wipe.lastReason)
        }
    }

    @Test fun aWipeTakesTheWholeDeviceWithIt() {
        wipe.wipe(LocalWipe.Reason.TOTAL_WIPE)
        assertEquals(listOf("biometrics", "obliterate", "notices", "deadman"), ran)
    }

    @Test fun duressAndDeadmanAreTheSameShredder() {
        wipe.wipe(LocalWipe.Reason.DURESS)
        val duress = ran.toList()
        ran.clear()
        wipe.wipe(LocalWipe.Reason.DEADMAN)
        assertEquals(duress, ran)
    }

    /**
     * docs/SCRUB-AND-WIPE.md §A3/§A4 — "Exactly the duress path, reused rather than
     * reimplemented", asserted by the same post-conditions the duress case above uses.
     */
    @Test fun tenWrongPasswordsIsTheDuressPath() {
        wipe.wipe(LocalWipe.Reason.DURESS)
        val duress = ran.toList()
        ran.clear()
        wipe.wipe(LocalWipe.Reason.TEN_WRONG_PASSWORDS)
        assertEquals(duress, ran)
        assertEquals(listOf("biometrics", "obliterate", "notices", "deadman"), ran)
        assertEquals(LocalWipe.Reason.TEN_WRONG_PASSWORDS, wipe.lastReason)
    }

    @Test fun autoLockIsNotAReason() {
        // §5 last line: unlocking again with biometry after an auto-lock is the whole point
        assertEquals(
            listOf(
                LocalWipe.Reason.DURESS,
                LocalWipe.Reason.DEADMAN,
                LocalWipe.Reason.TOTAL_WIPE,
                LocalWipe.Reason.TEN_WRONG_PASSWORDS,
                LocalWipe.Reason.REGISTRATION_ROLLBACK,
            ),
            LocalWipe.Reason.entries.toList(),
        )
    }
}
