package com.pyrochat.app.platform

import android.util.Log
import com.pyrochat.kdf.NativeKdf
import com.pyrochat.store.crypto.Kdf
import com.pyrochat.store.crypto.Pbkdf2HmacSha256

/**
 * The app's [Kdf]: PBKDF2-HMAC-SHA256 from `kdf-native` (C, ~10× faster than `javax.crypto.Mac`
 * for the 600 000 store iterations), falling back to the pure-JVM [Pbkdf2HmacSha256] from
 * `core-store` if `libpyrokdf.so` cannot be loaded (logged once). Both take the RAW password
 * bytes — no `char[]`/`PBEKeySpec` round trip, so non-UTF-8 input derives the same key iOS would.
 */
object NativeKdfAdapter : Kdf {
    private const val TAG = "NativeKdfAdapter"

    @Volatile
    private var nativeBroken = false

    /** True once the native path has served a call (false after a load failure, or before first use). */
    @Volatile
    var usesNative: Boolean = false
        private set

    override fun derive(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        if (!nativeBroken) {
            try {
                val out = NativeKdf.pbkdf2(password, salt, iterations, keyLen)
                usesNative = true
                return out
            } catch (e: UnsatisfiedLinkError) {
                fallBack(e)
            } catch (e: ExceptionInInitializerError) {
                fallBack(e)
            } catch (e: NoClassDefFoundError) {
                fallBack(e)
            }
        }
        return Pbkdf2HmacSha256.derive(password, salt, iterations, keyLen)
    }

    private fun fallBack(error: Throwable) {
        if (!nativeBroken) {
            nativeBroken = true
            usesNative = false
            Log.w(TAG, "libpyrokdf unavailable, using javax.crypto PBKDF2 (slower, same output)", error)
        }
    }
}
