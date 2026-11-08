package com.pyrochat.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.EdECPoint
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayAuthTest {
    /** Verbatim port of relay `Ed25519Verifier` (JDK Ed25519 over a raw 32-byte key) — the relay's exact check. */
    private object RelayVerifier {
        fun verify(rawPublicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
            require(rawPublicKey.size == 32)
            val le = rawPublicKey.clone()
            val xOdd = (le[31].toInt() and 0x80) != 0
            le[31] = (le[31].toInt() and 0x7f).toByte()
            le.reverse()
            val y = BigInteger(1, le)
            val pub = KeyFactory.getInstance("Ed25519").generatePublic(EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint(xOdd, y)))
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(pub)
            sig.update(message)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun rfc8032TestVector1() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pub = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertEquals(pub.toList(), RelayAuth.publicKey(seed).toList())
        assertEquals(sig.toList(), RelayAuth.sign(ByteArray(0), seed).toList())
    }

    @Test
    fun signatureVerifiesWithTheRelaysJdkPath() {
        val identity = Identity.generate()
        val nonce = ByteArray(32) { (it * 7).toByte() }
        val sig = identity.signChallenge(nonce)
        assertEquals(64, sig.size)
        assertEquals(32, identity.authPublicKey().size)
        assertTrue(RelayAuth.verify(identity.authPublicKey(), nonce, sig))
        assertTrue(RelayVerifier.verify(identity.authPublicKey(), nonce, sig), "relay's JDK Ed25519 verify must accept our BouncyCastle signature")
        // base64 path used on the wire
        val nonceB64 = Base64.getEncoder().encodeToString(nonce)
        val sigB64 = RelayAuth.signChallenge(nonceB64, identity.authPrivateKey)
        assertEquals(sig.toList(), Base64.getDecoder().decode(sigB64).toList())
    }

    @Test
    fun wrongKeyAndXEdDsaAreRejected() {
        val identity = Identity.generate()
        val nonce = ByteArray(32) { it.toByte() }
        val other = Identity.generate()
        assertFalse(RelayVerifier.verify(identity.authPublicKey(), nonce, other.signChallenge(nonce)))
        // THE TRAP: libsignal's calculateSignature is XEdDSA over the X25519 identity key — never valid for relay auth
        val xeddsa = identity.identityKeyPair.privateKey.calculateSignature(nonce)
        assertEquals(64, xeddsa.size)
        assertFalse(RelayVerifier.verify(identity.authPublicKey(), nonce, xeddsa))
        assertFalse(RelayVerifier.verify(identity.identityKeySerialized().copyOfRange(1, 33), nonce, xeddsa))
        // signing the base64 TEXT of the nonce instead of the bytes is also wrong
        val textSig = RelayAuth.sign(Base64.getEncoder().encodeToString(nonce).toByteArray(), identity.authPrivateKey)
        assertFalse(RelayVerifier.verify(identity.authPublicKey(), nonce, textSig))
    }
}
