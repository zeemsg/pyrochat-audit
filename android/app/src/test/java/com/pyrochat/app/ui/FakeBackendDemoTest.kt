package com.pyrochat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DEBUG `--es fakeUpdate "name|build[|url]"` — the canned shell's stand-in for the relay's `versions` frame. */
class FakeBackendDemoTest {
    @Test fun parsesNameBuildAndOptionalUrl() {
        assertEquals(UiUpdate(24, "0.1.0", "https://pyrochat.ca/"), FakeBackend.parseDemoUpdate("0.1.0|24|https://pyrochat.ca/"))
        assertEquals(UiUpdate(24, "0.1.0", null), FakeBackend.parseDemoUpdate("0.1.0|24"))
        assertEquals("http is dropped like the real rule", UiUpdate(24, "0.1.0", null), FakeBackend.parseDemoUpdate(" 0.1.0 | 24 | http://pyrochat.ca/ "))
    }

    @Test fun rejectsWhatTheRealRuleWouldReject() {
        assertNull(FakeBackend.parseDemoUpdate(null))
        assertNull(FakeBackend.parseDemoUpdate(""))
        assertNull(FakeBackend.parseDemoUpdate("0.1.0"))
        assertNull(FakeBackend.parseDemoUpdate("0.1.0|zero"))
        assertNull(FakeBackend.parseDemoUpdate("0.1.0|0"))
        assertNull(FakeBackend.parseDemoUpdate("|24"))
    }
}
