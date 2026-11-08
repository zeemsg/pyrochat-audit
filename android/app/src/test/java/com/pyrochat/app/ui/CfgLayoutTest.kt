package com.pyrochat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * docs/STATUS-AND-CFG.md §2 — the regrouping, and §2's "what must not move" list.
 *
 * That list is not a style note: it is four facts that a reorganisation is exactly the change that
 * breaks, each of them silently, because nothing about a screen that still renders says which
 * order it renders in. So each gets an assertion here, against [CfgLayout], which is the only place
 * the order lives.
 */
class CfgLayoutTest {

    // ---- the shape §2 asks for ----------------------------------------------------------

    @Test fun theFiveSectionsInTheOrderTheDocumentGivesThem() {
        assertEquals(
            // §2: APPEARANCE sits second, directly after PROTECTION — `theme` and `text size` are
            // the rows people actually touch, and ordering purely by consequence buried them.
            listOf(CfgSection.PROTECTION, CfgSection.APPEARANCE, CfgSection.LEAVES, CfgSection.ACCOUNT, CfgSection.DANGER),
            CfgLayout.sections.map { it.first },
        )
    }

    @Test fun whatProtectsMeIsTheFirstRowOfTheScreenAboveEverySection() {
        assertEquals(CfgRow.STATUS, CfgLayout.firstRow)
        assertEquals(CfgRow.STATUS, CfgLayout.rows.first())
        assertNull("it sits above every section, not inside one", CfgLayout.sectionOf(CfgRow.STATUS))
    }

    @Test fun everyRowIsPlacedExactlyOnce() {
        val placed = CfgLayout.sections.flatMap { it.second }
        assertEquals("a row in two sections, or in none", placed.toSet().size, placed.size)
        assertEquals(
            "every row this screen can draw has a section",
            CfgRow.entries.toSet() - CfgRow.STATUS,
            placed.toSet(),
        )
    }

    @Test fun eachSectionHoldsWhatTheTableSaysItHolds() {
        assertEquals(
            listOf(
                CfgRow.BIOMETRIC, CfgRow.AUTO_LOCK, CfgRow.DURESS, CfgRow.DEADMAN, CfgRow.DECOY,
                CfgRow.BLOCK_SCREENSHOTS, CfgRow.BURN_ON_SCREENSHOT, CfgRow.DEFAULT_BURN, CfgRow.THREAD_AUTO_BURN,
            ),
            CfgLayout.rowsIn(CfgSection.PROTECTION),
        )
        // §1 settles this order for BOTH screens. §2's own table still lists the superseded one
        // (`wake push, read receipts, typing indicators, proxy, desktop link`); §1 says which wins.
        assertEquals(
            listOf(CfgRow.WAKE_PUSH, CfgRow.PROXY, CfgRow.READ_RECEIPTS, CfgRow.TYPING, CfgRow.DESKTOP_LINK),
            CfgLayout.rowsIn(CfgSection.LEAVES),
        )
        assertEquals(
            listOf(CfgRow.THEME, CfgRow.TEXT_SIZE, CfgRow.SCANLINES, CfgRow.SOUNDS, CfgRow.NOTICES),
            CfgLayout.rowsIn(CfgSection.APPEARANCE),
        )
        assertEquals(
            listOf(
                CfgRow.SUBSCRIPTION, CfgRow.ACCESS_KEY, CfgRow.BACKUP, CfgRow.BLOCKED,
                CfgRow.REPORT_ABUSE, CfgRow.SUPPORT, CfgRow.LICENSES, CfgRow.ADVANCED,
            ),
            CfgLayout.rowsIn(CfgSection.ACCOUNT),
        )
    }

    @Test fun theSelfHosterSettingsCollapseBehindOneRowAtTheBottomOfAccount() {
        assertEquals(CfgRow.ADVANCED, CfgLayout.rowsIn(CfgSection.ACCOUNT).last())
    }

    // ---- §2 "what must not move" --------------------------------------------------------

    /** docs/TEXT-SIZE.md §1: `text size` stays directly under `theme`. */
    @Test fun textSizeStaysDirectlyUnderTheme() {
        val appearance = CfgLayout.rowsIn(CfgSection.APPEARANCE)
        assertEquals(
            "docs/TEXT-SIZE.md §1 — nothing may come between them",
            appearance.indexOf(CfgRow.THEME) + 1,
            appearance.indexOf(CfgRow.TEXT_SIZE),
        )
        assertEquals(CfgSection.APPEARANCE, CfgLayout.sectionOf(CfgRow.TEXT_SIZE))
    }

    /** docs/BIOMETRIC-UNLOCK.md §2: the biometric row stays directly above `auto-lock`. */
    @Test fun theBiometricRowStaysDirectlyAboveAutoLock() {
        val protection = CfgLayout.rowsIn(CfgSection.PROTECTION)
        assertEquals(
            "docs/BIOMETRIC-UNLOCK.md §2 — directly above, with nothing between",
            protection.indexOf(CfgRow.BIOMETRIC) + 1,
            protection.indexOf(CfgRow.AUTO_LOCK),
        )
        assertEquals(CfgSection.PROTECTION, CfgLayout.sectionOf(CfgRow.BIOMETRIC))
    }

    /** docs/DECOY-ACCOUNT.md §3: it moves section; it does not change. Here: it moved. */
    @Test fun theDecoyRowMovedIntoProtectionAndIsStillOneRow() {
        assertEquals(CfgSection.PROTECTION, CfgLayout.sectionOf(CfgRow.DECOY))
        assertEquals(1, CfgLayout.rows.count { it == CfgRow.DECOY })
    }

    /** §2: `[ BURN EVERYTHING ]` stays last, alone, in DANGER. */
    @Test fun burnEverythingIsLastAndAlone() {
        val (section, rows) = CfgLayout.sections.last()
        assertEquals(CfgSection.DANGER, section)
        assertEquals("alone", listOf(CfgRow.BURN_EVERYTHING), rows)
        assertEquals("last", CfgRow.BURN_EVERYTHING, CfgLayout.rows.last())
    }

    /** §2 "consequence gets weight": exactly the three rows that destroy data, and no others. */
    @Test fun onlyTheRowsThatDestroyDataWearTheAlertColour() {
        assertEquals(
            setOf(CfgRow.DURESS, CfgRow.DEADMAN, CfgRow.BURN_EVERYTHING),
            CfgRow.entries.filter(CfgLayout::destroysData).toSet(),
        )
    }

    // ---- §2: "this is a reorganisation, not a rewrite" -----------------------------------

    /**
     * Every row keeps its current wording. The rows below all changed section in this work, which
     * is exactly when a label gets "tidied" on the way past, so the strings are nailed down here —
     * read from the resource file the way `AutofillExclusionTest` reads the source, because there
     * is no Compose harness in this module.
     */
    @Test fun everyMovedRowKeptItsWording() {
        val xml = File("src/main/res/values/strings.xml").readText()
        val expected = mapOf(
            // moved into PROTECTION
            "cfg_bio_fingerprint" to "unlock with fingerprint",
            "cfg_auto_lock" to "auto-lock",
            "cfg_duress_unset" to "set duress pin …",
            "cfg_duress_set" to "duress pin: SET",
            "cfg_deadman" to "dead-man wipe",
            "cfg_decoy" to "set decoy password …",
            "cfg_burn_on_screenshot" to "burn thread on peer screenshot",
            "cfg_block_screenshots" to "block screenshots (capture = black)",
            "cfg_default_burn" to "default self-destruct",
            "cfg_thread_auto_burn" to "thread auto-burn",
            // moved into WHAT LEAVES THIS PHONE
            "cfg_wake_push" to "wake push (zero-content)",
            "cfg_read_receipts" to "read receipts (send + show)",
            "cfg_typing" to "typing indicators",
            "cfg_socks_proxy" to "socks proxy",
            "cfg_link_desktop" to "LINK DESKTOP …",
            // moved into APPEARANCE
            "cfg_theme" to "theme",
            "cfg_text_size" to "text size",
            "cfg_scanlines" to "scanlines",
            "cfg_sounds" to "tx/rx sounds",
            "cfg_notices" to "system notices",
            // moved into ACCOUNT
            "cfg_subscription" to "subscription",
            "cfg_enter_access_key" to "ENTER ACCESS KEY …",
            "cfg_export_backup" to "[ EXPORT ENCRYPTED BACKUP … ]",
            "cfg_blocked_users" to "blocked users",
            "cfg_report_abuse" to "report abuse",
            "cfg_support" to "support",
            "cfg_licenses" to "licenses",
            // DANGER, unmoved and unchanged
            "cfg_burn_everything" to "[ BURN EVERYTHING ]",
        )
        assertTrue("the resource file must be readable from the module dir", xml.contains("cfg_theme"))
        for ((name, text) in expected) {
            assertEquals(
                "§2: this is a reorganisation, not a rewrite — `$name` must still read exactly this",
                text,
                Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1),
            )
        }
    }

    /** The section names §2 gives, and the six implementation-named ones gone from the file. */
    @Test fun theSectionsAreNamedAfterThePersonAndTheOldNamesAreGone() {
        val xml = File("src/main/res/values/strings.xml").readText()
        for (name in listOf("cfg_display", "cfg_burn", "cfg_link", "cfg_access", "cfg_safety", "cfg_backup")) {
            assertTrue(
                "§0.2: `$name` was named after the implementation and no longer names a section",
                !xml.contains("<string name=\"$name\">"),
            )
        }
        for ((name, text) in listOf(
            "cfg_what_protects_me" to "[ what protects me ]",
            "cfg_protection" to "PROTECTION",
            "cfg_leaves" to "WHAT LEAVES THIS PHONE",
            "cfg_appearance" to "APPEARANCE",
            "cfg_account" to "ACCOUNT",
            "cfg_danger" to "DANGER",
            "cfg_advanced" to "advanced …",
        )) {
            assertTrue("`$name` must read `$text`", xml.contains("<string name=\"$name\">$text</string>"))
        }
    }
}
