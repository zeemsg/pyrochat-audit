package com.pyrochat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** docs/FIRST-RUN.md §3 — the pending-key machine: hold while locked, drop without an identity, apply once unlocked. */
class PendingKeyRulesTest {
    private val code = "PYRO-ABCD-EFGH-JKMN-PQRS"
    private val key = IncomingKey.Key(code)

    @Test fun stepsPerStage() {
        assertSame(KeyStep.Hold, PendingKeyRules.step(Stage.Boot, key))
        assertSame(KeyStep.Hold, PendingKeyRules.step(Stage.Boot, IncomingKey.Refused))
        // docs/DECOY-ACCOUNT.md §5: locked, the app cannot tell whether an identity exists, so a
        // key HOLDS rather than being dropped with "create one first" — the message was an
        // assertion about the device that this screen no longer gets to make
        assertSame(KeyStep.Hold, PendingKeyRules.step(Stage.Auth, key))
        assertEquals(KeyStep.Drop("! not an access key"), PendingKeyRules.step(Stage.Auth, IncomingKey.Refused))
        assertEquals(KeyStep.Apply(key), PendingKeyRules.step(Stage.Main, key))
        assertEquals(KeyStep.Apply(IncomingKey.Refused), PendingKeyRules.step(Stage.Main, IncomingKey.Refused))
    }

    @Test fun aKeyWaitsThroughBootAndUnlockThenAppliesOnce() {
        val intake = AccessKeyIntake()
        assertNull(intake.pending.value)
        intake.offer("pyrochat://redeem/pyro-abcd-efgh-jkmn-pqrs")
        assertEquals(key, intake.pending.value)
        assertNull("boot: held", intake.resolve(Stage.Boot))
        assertEquals(key, intake.pending.value)
        assertNull("locked with an identity: held", intake.resolve(Stage.Auth))
        assertEquals(key, intake.pending.value)
        assertNull(intake.notice.value)
        assertEquals("unlocked: applied", key, intake.resolve(Stage.Main))
        assertNull("…and forgotten", intake.pending.value)
        assertNull("nothing left to apply", intake.resolve(Stage.Main))
    }

    @Test fun aKeyHeldOnTheAuthScreenSurvivesUntilTheIdentityIsThere() {
        // §5: whether this device holds an identity is not knowable while locked, so the key waits
        // in RAM — for the next unlock, or for the identity the user is about to create
        val intake = AccessKeyIntake()
        intake.offer("pyro-key:v1:$code")
        assertNull(intake.resolve(Stage.Auth))
        assertEquals("still waiting, and nothing was said about the device", key, intake.pending.value)
        assertNull(intake.notice.value)
        assertEquals(key, intake.resolve(Stage.Main))
        assertNull(intake.pending.value)
    }

    @Test fun aBrokenLinkIsRefusedWhereverTheUserIs() {
        val intake = AccessKeyIntake()
        intake.offer("pyrochat://redeem/not-a-key")
        assertEquals(IncomingKey.Refused, intake.pending.value)
        assertNull(intake.resolve(Stage.Boot)) // still booting: nowhere to say it yet
        assertEquals(IncomingKey.Refused, intake.pending.value)
        assertNull(intake.resolve(Stage.Auth))
        assertEquals("! not an access key", intake.notice.value)
        assertNull(intake.pending.value)
        // unlocked: the settings screen says it
        intake.offer("pyro-key:v1:garbage")
        assertNull("a new offer clears the old notice", intake.notice.value)
        assertEquals(IncomingKey.Refused, intake.resolve(Stage.Main))
        // something that is not even one of our links
        intake.offer("https://example.com/")
        assertEquals(IncomingKey.Refused, intake.pending.value)
    }

    @Test fun theSameCodeDeliveredTwiceAppliesTwice() {
        val intake = AccessKeyIntake()
        intake.offer("pyrochat://redeem/$code")
        assertEquals(key, intake.resolve(Stage.Main))
        intake.offer("pyrochat://redeem/$code") // the gate's second delivery → the used-key error comes from billing
        assertEquals(key, intake.resolve(Stage.Main))
    }

    @Test fun aKeyNeverRidesAToString() {
        assertEquals("Key(…)", key.toString())
        assertEquals("Key(…)", IncomingKey.Key("PYRO-0000-1111-2222-3333").toString())
        assertEquals(key, IncomingKey.Key(code)) // equality untouched
    }

    @Test fun aNewerLinkReplacesAHeldOne() {
        val intake = AccessKeyIntake()
        intake.offer("pyrochat://redeem/$code")
        intake.offer("pyrochat://redeem/PYRO-0000-1111-2222-3333")
        assertEquals(IncomingKey.Key("PYRO-0000-1111-2222-3333"), intake.resolve(Stage.Main))
        assertNull(intake.pending.value)
    }
}
