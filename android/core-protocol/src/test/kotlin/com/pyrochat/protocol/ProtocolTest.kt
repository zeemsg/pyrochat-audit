package com.pyrochat.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolTest {
    @Test fun constantsMatchIos() {
        assertEquals(1, Protocol.ENVELOPE_VERSION)
        assertEquals(2, Protocol.CIPHERTEXT_WHISPER)
        assertEquals(3, Protocol.CIPHERTEXT_PREKEY)
        assertEquals(1, Protocol.DEVICE_ID)
    }
}
