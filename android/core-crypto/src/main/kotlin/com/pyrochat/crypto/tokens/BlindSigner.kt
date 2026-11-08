package com.pyrochat.crypto.tokens

import org.bouncycastle.crypto.AsymmetricBlockCipher
import org.bouncycastle.crypto.CipherParameters
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.engines.RSABlindingEngine
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSABlindingParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.PSSSigner
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64

/** An RFC 9474 protocol failure ("invalid input", "unexpected input size", "invalid signature", "blinding error"). */
class BlindSignatureException(message: String) : GeneralSecurityException(message)

/**
 * RFC 9474 **RSABSSA-SHA384-PSS-Deterministic** — the client and verifier half of the blind-signature
 * protocol iOS runs through swift-crypto (`RedeemService.swift`, `.RSABSSA_SHA384_PSS_Deterministic`)
 * against `billing/BlindSigner.java` (`s' = m'^d mod n`), whose output the relay checks as a plain
 * RSASSA-PSS signature (`RsaPssTokenVerifier.java`: SHA-384, MGF1-SHA-384, salt 48).
 *
 * Variant rules (RFC 9474 §5): *Deterministic* names the message preparation — [prepare] is the
 * identity, no 32-byte random prefix — while *PSS* means EMSA-PSS with a **48-byte random salt**
 * (the "PSSZERO" variants use sLen = 0; that is NOT ours). RSA-2048, e = 65537, so every blinded
 * message, blind signature and signature is exactly k = 256 bytes (I2OSP, big-endian, zero-padded).
 *
 * Everything runs on BouncyCastle's lightweight API (`org.bouncycastle.crypto.*`, no JCA provider):
 * the JDK's `Signature.getInstance("RSASSA-PSS")` the relay uses does not exist on Android.
 *
 *   blind:    EM = EMSA-PSS-ENCODE(msg, bitlen(n) − 1)   — [PSSSigner] over a pass-through cipher
 *             m = OS2IP(EM); gcd(m, n) = 1 else "invalid input"
 *             r ←$ [1, n), inv = r⁻¹ mod n               — retry on "blinding error"
 *             blinded_msg = I2OSP(m · r^e mod n, k)      — [RSABlindingEngine]
 *   finalize: len(blind_sig) = k else "unexpected input size"
 *             sig = I2OSP(OS2IP(blind_sig) · inv mod n, k); verify(sig) else "invalid signature"
 *   verify:   RSASSA-PSS-VERIFY (SHA-384 / MGF1-SHA-384 / sLen 48) — [PSSSigner] over [RSAEngine]
 */
class BlindSigner(val publicKey: RSAKeyParameters) {
    init {
        require(!publicKey.isPrivate) { "BlindSigner takes the issuer's PUBLIC key" }
    }

    val modulus: BigInteger get() = publicKey.modulus
    val exponent: BigInteger get() = publicKey.exponent

    /** k — the modulus length in bytes (256 for the RSA-2048 billing key). */
    val modulusBytes: Int = (publicKey.modulus.bitLength() + 7) / 8

    /** `inv` from RFC 9474 §4.3 — the secret needed to unblind; never leaves the device. */
    class BlindingInverse internal constructor(internal val inv: BigInteger)

    /** `(blinded_msg, inv)` — send [blindedMessage] to the issuer, keep [inverse] for [finalize]. */
    class Blinded(val blindedMessage: ByteArray, val inverse: BlindingInverse)

    /** Deterministic variant: `prepare` is the identity — the message is signed as-is (§4.1). */
    fun prepare(message: ByteArray): ByteArray = message.copyOf()

    /** RFC 9474 §4.3 `Blind(pk, msg)`: fresh 48-byte salt, fresh uniform blinding factor. */
    fun blind(prepared: ByteArray, random: SecureRandom = SecureRandom()): Blinded {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nMinusOne = modulus.subtract(BigInteger.ONE)
        while (true) {
            // r = random_integer_uniform(1, n); inv = inverse_mod(r, n) — a non-invertible r is a
            // "blinding error", which the RFC says to retry (probability ≈ 0 for a sound modulus)
            val r = BigIntegers.createRandomInRange(BigInteger.ONE, nMinusOne, random)
            val inv = try {
                r.modInverse(modulus)
            } catch (_: ArithmeticException) {
                continue
            }
            return blindWith(prepared, salt, r, inv)
        }
    }

    /**
     * The deterministic core of [blind] with the salt and blinding factor supplied — the RFC's
     * Appendix A vectors pin `salt` and `inv` (r = inv⁻¹ mod n) and expect `blinded_msg` byte-for-byte.
     */
    internal fun blindWith(prepared: ByteArray, salt: ByteArray, r: BigInteger, inv: BigInteger): Blinded {
        require(r > BigInteger.ZERO && r < modulus) { "blinding factor out of range" }
        val em = encode(prepared, salt)
        val m = BigInteger(1, em)
        if (m.gcd(modulus) != BigInteger.ONE) throw BlindSignatureException("invalid input")
        val engine = RSABlindingEngine()
        engine.init(true, RSABlindingParameters(publicKey, r))
        // m · r^e mod n, already I2OSP'd to k bytes by RSACoreEngine.convertOutput (encrypt direction)
        val blinded = engine.processBlock(em, 0, em.size)
        check(blinded.size == modulusBytes) { "blinded message is ${blinded.size} B, expected $modulusBytes" }
        return Blinded(blinded, BlindingInverse(inv))
    }

    /**
     * `EMSA-PSS-ENCODE(prepared, emBits = bitlen(n) − 1)` with SHA-384, MGF1-SHA-384 and the given
     * 48-byte [salt] (RFC 8017 §9.1.1) — BouncyCastle's [PSSSigner] does the encoding; the
     * pass-through "cipher" hands the encoded block back untouched so the coprimality check and the
     * blinding can happen on it (the RFC's steps 4-9 are separate from the encoding).
     */
    internal fun encode(prepared: ByteArray, salt: ByteArray): ByteArray {
        require(salt.size == SALT_BYTES) { "PSS salt must be $SALT_BYTES bytes" }
        val encoder = PSSSigner(PassThrough, SHA384Digest(), SHA384Digest(), salt)
        encoder.init(true, publicKey)
        encoder.update(prepared, 0, prepared.size)
        return encoder.generateSignature()
    }

    /**
     * RFC 9474 §4.5 `Finalize(pk, msg, blind_sig, inv)`: unblind, then verify — the issuer is not
     * trusted to have signed correctly, so an unverifiable result is refused, never stored.
     */
    fun finalize(blindSignature: ByteArray, prepared: ByteArray, inverse: BlindingInverse): ByteArray {
        if (blindSignature.size != modulusBytes) throw BlindSignatureException("unexpected input size")
        val z = BigInteger(1, blindSignature)
        val s = z.multiply(inverse.inv).mod(modulus)
        val sig = BigIntegers.asUnsignedByteArray(modulusBytes, s)
        if (!verify(prepared, sig)) throw BlindSignatureException("invalid signature")
        return sig
    }

    /**
     * `RSASSA-PSS-VERIFY` with SHA-384 / MGF1-SHA-384 / sLen 48 — bit-for-bit the relay's
     * `PSSParameterSpec("SHA-384","MGF1",SHA384,48,1)` check, on BouncyCastle's lightweight API.
     */
    fun verify(message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != modulusBytes) return false
        return try {
            val verifier = PSSSigner(RSAEngine(), SHA384Digest(), SHA384Digest(), SALT_BYTES)
            verifier.init(false, publicKey)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** PSS salt length of the RSABSSA-SHA384-PSS-* variants (= SHA-384 output length). */
        const val SALT_BYTES = 48

        /** Bytes of a blinded message / blind signature / signature under the RSA-2048 billing key. */
        const val RSA2048_BYTES = 256

        private val PEM_ARMOR = Regex("-----(BEGIN|END) PUBLIC KEY-----")

        /** `GET /api/billing-pubkey` → `pem`: an SPKI "PUBLIC KEY" PEM (64-column MIME base64). */
        fun fromPem(pem: String): BlindSigner {
            val b64 = pem.replace(PEM_ARMOR, "").replace(Regex("\\s"), "")
            val der = try {
                Base64.getDecoder().decode(b64)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("not a PEM public key", e)
            }
            return fromSpki(der)
        }

        /** X.509 SubjectPublicKeyInfo DER (what `RSAPublicKey.getEncoded()` yields on the billing side). */
        fun fromSpki(der: ByteArray): BlindSigner {
            val key = try {
                PublicKeyFactory.createKey(der)
            } catch (e: Exception) {
                throw IllegalArgumentException("not an SPKI public key", e)
            }
            return BlindSigner(key as? RSAKeyParameters ?: throw IllegalArgumentException("not an RSA public key"))
        }

        /** Stateless verify — what the relay does with the same public key. */
        fun verify(publicKey: RSAKeyParameters, message: ByteArray, signature: ByteArray): Boolean =
            BlindSigner(publicKey).verify(message, signature)

        /** An [AsymmetricBlockCipher] that returns its input: lets [PSSSigner] produce the raw EM. */
        private object PassThrough : AsymmetricBlockCipher {
            override fun init(forEncryption: Boolean, param: CipherParameters) {}
            override fun getInputBlockSize(): Int = 0
            override fun getOutputBlockSize(): Int = 0
            override fun processBlock(input: ByteArray, inOff: Int, len: Int): ByteArray = input.copyOfRange(inOff, inOff + len)
        }
    }
}
