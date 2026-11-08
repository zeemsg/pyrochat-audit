package com.pyrochat.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {
    @Test fun dropsOnlyWhenPushCanWakeUs() {
        assertTrue(LinkPolicy.dropsLinkInBackground(pushEnabled = true, pushAvailable = true))
        assertFalse(LinkPolicy.dropsLinkInBackground(pushEnabled = true, pushAvailable = false))   // no firebase config in this build
        assertFalse(LinkPolicy.dropsLinkInBackground(pushEnabled = false, pushAvailable = true))   // user turned push off: the socket is our only ear
        assertEquals(60_000L, LinkPolicy.BACKGROUND_DROP_MS)
    }
}
