package com.pyrochat.crypto.tokens

import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlindSignerTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bi(hex: String): BigInteger = BigInteger(hex, 16)
    private val random = SecureRandom()

    /** The issuer, verbatim `billing/BlindSigner.blindSign`: `s' = m'^d mod n`, k bytes. */
    private fun issuerBlindSign(blinded: ByteArray, d: BigInteger, n: BigInteger): ByteArray {
        val k = (n.bitLength() + 7) / 8
        require(blinded.size == k)
        val m = BigInteger(1, blinded)
        require(m < n)
        return BigIntegers.asUnsignedByteArray(k, m.modPow(d, n))
    }

    /** The relay, verbatim `RsaPssTokenVerifier`: JDK `RSASSA-PSS` with SHA-384/MGF1-SHA-384/salt 48. */
    private fun relayVerify(pub: RSAPublicKey, token: ByteArray, sig: ByteArray): Boolean {
        val v = Signature.getInstance("RSASSA-PSS")
        v.setParameter(PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, 48, 1))
        v.initVerify(pub)
        v.update(token)
        return v.verify(sig)
    }

    /**
     * Walks one Appendix A vector through every step with the vector's own salt and blinding inverse
     * and demands the RFC's bytes at each boundary: encoded_msg, blinded_msg, blind_sig, sig.
     */
    private fun knownAnswer(v: Rfc9474Vector) {
        val n = bi(v.n)
        val e = bi(v.e)
        val d = bi(v.d)
        assertEquals(n, bi(v.p).multiply(bi(v.q)), "p·q = n")
        assertEquals(BigInteger.valueOf(65537), e)
        val signer = BlindSigner(RSAKeyParameters(false, n, e))
        assertEquals(512, signer.modulusBytes, "the RFC's Appendix A key is RSA-4096 (k = 512); billing's is RSA-2048 (k = 256)")

        val msg = hex(v.msg)
        // Deterministic variants: prepare = identity. The A.1 (Randomized) vector's 32-byte prefix
        // is concatenated here by the test — the same blind/finalize/verify code path.
        val prepared = if (v.msgPrefix.isEmpty()) signer.prepare(msg) else hex(v.msgPrefix) + msg
        assertContentEquals(hex(v.preparedMsg), prepared, "prepared_msg")

        val salt = hex(v.salt)
        assertEquals(48, salt.size)
        val inv = bi(v.inv)
        val r = inv.modInverse(n)

        assertContentEquals(hex(v.encodedMsg), signer.encode(prepared, salt), "encoded_msg (EMSA-PSS-ENCODE)")
        val blinded = signer.blindWith(prepared, salt, r, inv)
        assertContentEquals(hex(v.blindedMsg), blinded.blindedMessage, "blinded_msg")

        val blindSig = issuerBlindSign(blinded.blindedMessage, d, n)
        assertContentEquals(hex(v.blindSig), blindSig, "blind_sig")

        val sig = signer.finalize(blindSig, prepared, blinded.inverse)
        assertContentEquals(hex(v.sig), sig, "sig")
        assertTrue(signer.verify(prepared, sig))
        assertTrue(BlindSigner.verify(signer.publicKey, prepared, sig))

        // and the relay's JDK verify agrees with the RFC's signature
        val jdkPub = KeyFactory.getInstance("RSA").generatePublic(java.security.spec.RSAPublicKeySpec(n, e)) as RSAPublicKey
        assertTrue(relayVerify(jdkPub, prepared, sig), "relay RSASSA-PSS verify of the RFC signature")

        // negative: any other message, a flipped byte, a wrong length
        assertFalse(signer.verify(prepared + byteArrayOf(0), sig))
        assertFalse(signer.verify(prepared, sig.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }))
        assertFalse(signer.verify(prepared, sig.copyOf(sig.size - 1)))
    }

    @Test
    fun `RFC 9474 A_3 RSABSSA-SHA384-PSS-Deterministic vector byte for byte`() =
        knownAnswer(Rfc9474Vectors.A3_PSS_DETERMINISTIC)

    @Test
    fun `RFC 9474 A_1 RSABSSA-SHA384-PSS-Randomized vector byte for byte (prefix applied by the test)`() =
        knownAnswer(Rfc9474Vectors.A1_PSS_RANDOMIZED)

    @Test
    fun `finalize refuses a forged or mis-sized blind signature`() {
        val v = Rfc9474Vectors.A3_PSS_DETERMINISTIC
        val n = bi(v.n)
        val signer = BlindSigner(RSAKeyParameters(false, n, bi(v.e)))
        val prepared = hex(v.preparedMsg)
        val inv = bi(v.inv)
        val blinded = signer.blindWith(prepared, hex(v.salt), inv.modInverse(n), inv)

        assertFailsWith<BlindSignatureException> { signer.finalize(hex(v.blindSig).copyOf(signer.modulusBytes - 1), prepared, blinded.inverse) }
        assertFailsWith<BlindSignatureException> { signer.finalize(hex(v.blindSig) + byteArrayOf(0), prepared, blinded.inverse) }
        val forged = hex(v.blindSig).also { it[7] = (it[7].toInt() xor 0x10).toByte() }
        assertFailsWith<BlindSignatureException> { signer.finalize(forged, prepared, blinded.inverse) }
        // the wrong inverse (another blinding) unblinds to garbage → refused, never returned
        val other = signer.blind(prepared, random)
        assertFailsWith<BlindSignatureException> { signer.finalize(hex(v.blindSig), prepared, other.inverse) }
    }

    @Test
    fun `round trip against a fresh RSA-2048 key verifies like the relay`() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val priv = kp.private as RSAPrivateKey
        val n = pub.modulus

        // both key routes: SPKI DER and the billing PEM (64-column MIME, exactly BlindSigner.pem())
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pub.encoded) +
            "\n-----END PUBLIC KEY-----\n"
        val signer = BlindSigner.fromPem(pem)
        assertEquals(n, signer.modulus)
        assertEquals(pub.publicExponent, signer.exponent)
        assertEquals(n, BlindSigner.fromSpki(pub.encoded).modulus)
        assertEquals(256, signer.modulusBytes)

        // the access token the CLIENT constructs: epochDay || nonce
        val token = ByteBuffer.allocate(40).putLong(20_713L).put(ByteArray(32).also(random::nextBytes)).array()
        val prepared = signer.prepare(token)
        assertContentEquals(token, prepared)
        val blinded = signer.blind(prepared, random)
        assertEquals(256, blinded.blindedMessage.size)

        // issuer never sees `token`
        val blindSig = issuerBlindSign(blinded.blindedMessage, priv.privateExponent, n)
        val sig = signer.finalize(blindSig, prepared, blinded.inverse)
        assertEquals(256, sig.size)

        assertTrue(signer.verify(token, sig), "BouncyCastle PSS verify")
        assertTrue(relayVerify(pub, token, sig), "relay's JDK RSASSA-PSS verify — the wire-compat proof")
        assertFalse(relayVerify(pub, token.copyOf().also { it[39] = (it[39].toInt() xor 1).toByte() }, sig))

        // unlinkability sanity: the issuer-visible value shares nothing with the token or the signature
        assertFalse(blinded.blindedMessage.contentEquals(sig))
        assertFalse(String(blinded.blindedMessage, Charsets.ISO_8859_1).contains(String(token, Charsets.ISO_8859_1)))

        // a second blinding of the same message is unlinkable to the first and yields a different (valid) signature
        val again = signer.blind(prepared, random)
        assertFalse(again.blindedMessage.contentEquals(blinded.blindedMessage))
        val sig2 = signer.finalize(issuerBlindSign(again.blindedMessage, priv.privateExponent, n), prepared, again.inverse)
        assertFalse(sig2.contentEquals(sig), "random 48-byte salt ⇒ different signature")
        assertTrue(relayVerify(pub, token, sig2))

        // a signature by ANOTHER key does not verify
        val kp2 = kpg.generateKeyPair()
        val signer2 = BlindSigner.fromSpki(kp2.public.encoded)
        assertFalse(signer2.verify(token, sig))
    }

    @Test
    fun `key parsing rejects garbage and private keys`() {
        assertFailsWith<IllegalArgumentException> { BlindSigner.fromPem("-----BEGIN PUBLIC KEY-----\nnot base64!\n-----END PUBLIC KEY-----\n") }
        assertFailsWith<IllegalArgumentException> { BlindSigner.fromSpki(byteArrayOf(1, 2, 3)) }
        // an Ed25519 SPKI is a public key, but not RSA
        val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded
        assertFailsWith<IllegalArgumentException> { BlindSigner.fromSpki(ed) }
        val v = Rfc9474Vectors.A3_PSS_DETERMINISTIC
        assertFailsWith<IllegalArgumentException> { BlindSigner(RSAKeyParameters(true, bi(v.n), bi(v.d))) }
        // and the relay-side X509EncodedKeySpec route agrees with ours on the RFC key
        val spki = KeyFactory.getInstance("RSA").generatePublic(java.security.spec.RSAPublicKeySpec(bi(v.n), bi(v.e))).encoded
        assertEquals(bi(v.n), BlindSigner.fromSpki(spki).modulus)
        assertEquals(bi(v.n), (KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki)) as RSAPublicKey).modulus)
    }
}
