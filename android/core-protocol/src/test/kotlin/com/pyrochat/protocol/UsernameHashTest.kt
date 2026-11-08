package com.pyrochat.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Expected values computed with Node (`crypto.createHash('sha256').update(s,'utf8').digest('hex')`)
 * over the string iOS produces with `lowercased()` (see the UsernameHash KDoc and
 * docs/android/inventory-critique.md §1.4).
 */
class UsernameHashTest {
    @Test
    fun asciiVectorsMatchNodeReferenceClients() {
        // e2e.mjs: sha256(username.toLowerCase())
        assertEquals("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90", usernameHash("alice"))
        assertEquals("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90", usernameHash("Alice"))
        assertEquals("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90", usernameHash("ALICE"))
        // bot/index.mjs: sha256hex(USERNAME.toLowerCase())
        assertEquals("9c498990dcb14b54cd663400bcda20b200a4b21a56bc006b8cbb4fd6d1422b07", usernameHash("PyroBot"))
        assertEquals("8435f8b330eb3ebd6dc6348e5040464517ee8a22f4d5f6c370e0c49d9507709c", usernameHash("bob_99"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", usernameHash(""))
    }

    @Test
    fun greekFinalSigmaFollowsSwiftNotJavaOrJs() {
        // Swift "ΟΔΥΣΣΕΥΣ".lowercased() == "οδυσσευσ" (U+03C3 everywhere, verified on-device in the critique)
        assertEquals("οδυσσευσ", UsernameHash.lowercaseLikeSwift("ΟΔΥΣΣΕΥΣ"))
        assertEquals("2948cc83118a5f6add05093738b8e97d2357a5e872c29992a8ec50558a3afac3", usernameHash("ΟΔΥΣΣΕΥΣ"))
        // whole-string Java lowering (and JS toLowerCase) would give ς U+03C2 and a DIFFERENT relay address
        assertEquals("οδυσσευς", "ΟΔΥΣΣΕΥΣ".lowercase())
        assertNotEquals("3c70e8e70fb6820b6ae6de04be9249923ba46e8aaa0f6ecfae7a1ee8880f574d", usernameHash("ΟΔΥΣΣΕΥΣ"))
        // a lone or word-initial Σ agrees on every platform
        assertEquals("σ", UsernameHash.lowercaseLikeSwift("Σ"))
    }

    @Test
    fun specialCaseMappingsAgreeWithSwift() {
        // İ U+0130 → i + U+0307 (full mapping; Character.toLowerCase would drop the dot)
        assertEquals("i̇", UsernameHash.lowercaseLikeSwift("İ"))
        assertEquals("fbbdb347060082cf4e72cdc5f02679dd809ebf4e6c601933e858d954a65f5e73", usernameHash("İ"))
        // ẞ U+1E9E → ß
        assertEquals("ß", UsernameHash.lowercaseLikeSwift("ẞ"))
        assertEquals("cd3a7e92a9114307055e4a0583acd6549ec3bb6d7c10ecc6ce11ae3d76b3d7db", usernameHash("ẞ"))
        // Kelvin sign U+212A → k, Ohm sign U+2126 → ω
        assertEquals("k", UsernameHash.lowercaseLikeSwift("K"))
        assertEquals("8254c329a92850f6d539dd376f4816ee2764517da5e0235514af433164480d7a", usernameHash("K"))
        assertEquals("ω", UsernameHash.lowercaseLikeSwift("Ω"))
        assertEquals("faed5da5f84f2ee9ea8e56403800503b3910a735270a7b2e8b39a182d5ffa615", usernameHash("Ω"))
        // combining marks are untouched; no normalization anywhere
        assertEquals("é", UsernameHash.lowercaseLikeSwift("É"))
        assertEquals("bf12767b0f2a56b2190075bae8169f656e3ce8d6357d4aff184bc6c7ea48f9f6", usernameHash("é"))
        // supplementary-plane scalars pass through as one code point
        assertEquals("𝔄", UsernameHash.lowercaseLikeSwift("𝔄")) // 𝔄 has no lowercase mapping
        assertEquals("𐐨", UsernameHash.lowercaseLikeSwift("𐐀")) // DESERET CAPITAL LONG I → small
    }

    @Test
    fun hashShape() {
        val h = usernameHash("someone")
        assertEquals(64, h.length)
        assertTrue(UsernameHash.isValid(h))
        assertFalse(UsernameHash.isValid(h.uppercase()))
        assertFalse(UsernameHash.isValid(h.dropLast(1)))
    }
}
