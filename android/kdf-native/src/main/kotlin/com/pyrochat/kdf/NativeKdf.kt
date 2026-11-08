package com.pyrochat.kdf

/** PBKDF2-HMAC-SHA256 in C (see pbkdf2.c). Same output as iOS CommonCrypto for the same inputs. */
object NativeKdf {
    init { System.loadLibrary("pyrokdf") }
    @JvmStatic external fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray
}
