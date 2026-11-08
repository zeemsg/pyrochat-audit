package com.pyrochat.app.ui

import com.pyrochat.app.platform.BiometricKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/STATUS-AND-CFG.md §4 over [StatusModel] — the status screen's whole content, as a pure
 * function of the session's state.
 *
 * §4's first ask is "driven from a store rather than a fixture: flip each setting, assert the row
 * changes", and that is what every test below does: two states of the same row, asserted to differ
 * AND asserted on what each one says, because a row that changed to the wrong sentence is not
 * better than one that did not change.
 */
class StatusScreenModelTest {

    private val base = AppSettings()
    private val now = 1_800_000_000_000L

    private fun groups(
        settings: AppSettings = base,
        kind: BiometricKind? = BiometricKind.FINGERPRINT,
        biometricOn: Boolean = false,
        duressSet: Boolean = false,
        unlockFailures: Int = 0,
        contacts: List<Contact> = emptyList(),
        accessDays: Int = 0,
        lastBackupAt: Long? = null,
        mirror: MirrorState = MirrorState.Idle,
        senderHidden: SenderHidden = SenderHidden.NOT_OFFERED,
    ) = StatusModel.groups(settings, kind, biometricOn, duressSet, unlockFailures, contacts, accessDays, lastBackupAt, mirror, now, senderHidden = senderHidden)

    // ---- the three groups, in order -----------------------------------------------------

    @Test fun theThreeGroupsInTheOrderTheDocumentGivesThem() {
        assertEquals(
            listOf(StatusModel.PROTECTS_YOU, StatusModel.LEAVES_PHONE, StatusModel.IF_YOU_LOSE_IT),
            groups().map { it.title },
        )
    }

    @Test fun everyRowInTheOrderTheDocumentGivesThem() {
        assertEquals(
            listOf(
                listOf("fingerprint", "auto-lock", "duress pin", "dead-man switch", "screenshot shield", "wrong attempts"),
                listOf("wake push", "proxy", "read receipts", "typing indicators", "desktop link", "sender hidden", "verified contacts"),
                listOf("backup", "access key"),
            ),
            groups().map { g -> g.rows.map { it.label } },
        )
    }

    // ---- flip each setting, assert the row changes ---------------------------------------

    @Test fun theBiometricRowFollowsTheStoredKeyAndTheSensor() {
        val off = StatusModel.biometricRow(BiometricKind.FINGERPRINT, on = false)
        val on = StatusModel.biometricRow(BiometricKind.FINGERPRINT, on = true)
        assertEquals("fingerprint", off.label)
        assertEquals("OFF", off.value)
        assertEquals("on: opens without typing, for 7 days at a time", off.note)
        assertEquals("ON", on.value)
        assertEquals("opens without typing, for 7 days at a time", on.note)
        assertNotEquals("the ON note drops the `on:` the OFF one carries", off.note, on.note)

        // docs/BIOMETRIC-UNLOCK.md §2 — the label follows the sensor; "face id" is the iOS word
        assertEquals("face", StatusModel.biometricRow(BiometricKind.FACE, on = false).label)
        assertEquals("biometrics", StatusModel.biometricRow(BiometricKind.GENERIC, on = false).label)

        // no hardware, or nothing enrolled: the row stays and says so rather than going quiet
        val none = StatusModel.biometricRow(null, on = false)
        assertEquals("—", none.value)
        assertNull("there is no setting to reach", none.target)
    }

    @Test fun theAutoLockRowFollowsTheStoredMinutes() {
        val off = StatusModel.autoLockRow(base.copy(autoLockMinutes = 0))
        assertEquals("OFF", off.value)
        assertEquals("on: relocks after you leave the app", off.note)
        // §1's value column is ON/OFF for this row; the number lives in the note
        assertEquals("ON", StatusModel.autoLockRow(base.copy(autoLockMinutes = null)).value)
        assertEquals("relocks 5 minutes after you leave", StatusModel.autoLockRow(base.copy(autoLockMinutes = null)).note)
        assertEquals("relocks 15 minutes after you leave", StatusModel.autoLockRow(base.copy(autoLockMinutes = 15)).note)
        // §1 plural rule
        assertEquals("relocks 1 minute after you leave", StatusModel.autoLockRow(base.copy(autoLockMinutes = 1)).note)
    }

    @Test fun theDuressRowFollowsWhetherOneIsArmed() {
        assertEquals("NOT SET", StatusModel.duressRow(false).value)
        assertEquals("SET", StatusModel.duressRow(true).value)
        assertEquals("a second password that wipes everything", StatusModel.duressRow(true).note)
        assertEquals("on: a second password that wipes everything", StatusModel.duressRow(false).note)
        assertNotEquals(StatusModel.duressRow(false).note, StatusModel.duressRow(true).note)
    }

    @Test fun theDeadManRowFollowsTheStoredDays() {
        val off = StatusModel.deadManRow(base.copy(wipeAfterDays = null))
        assertEquals("OFF", off.value)
        assertEquals("on: wipes if unopened for 7, 30 or 90 days", off.note)
        for (d in listOf(7, 30, 90)) {
            val on = StatusModel.deadManRow(base.copy(wipeAfterDays = d))
            assertEquals("ON", on.value)
            assertEquals("wipes if unopened for $d days", on.note)
        }
    }

    @Test fun theScreenshotShieldRowFollowsTheToggleAndItsDefaultIsOn() {
        assertEquals("ON", StatusModel.screenshotShieldRow(base).value)      // blockScreenshots nil ⇒ true
        assertEquals("captures come out black", StatusModel.screenshotShieldRow(base).note)
        val off = StatusModel.screenshotShieldRow(base.copy(blockScreenshots = false))
        assertEquals("OFF", off.value)
        assertEquals("on: captures come out black", off.note)
    }

    /** docs/SCRUB-AND-WIPE.md §A — the counter the unlock screen keeps, and the limit it obeys. */
    @Test fun theWrongAttemptsRowCountsTowardsTen() {
        assertEquals("0/10", StatusModel.wrongAttemptsRow(0).value)
        assertEquals("9/10", StatusModel.wrongAttemptsRow(9).value)
        assertEquals("a damaged counter is read as zero, never as a wipe", "0/10", StatusModel.wrongAttemptsRow(-3).value)
        assertEquals("at ten, this device wipes itself", StatusModel.wrongAttemptsRow(0).note)
        assertNull("no setting changes this", StatusModel.wrongAttemptsRow(0).target)
    }

    @Test fun theWakePushRowFollowsTheToggle() {
        val off = StatusModel.wakePushRow(base.copy(pushEnabled = false))
        val on = StatusModel.wakePushRow(base.copy(pushEnabled = true))
        assertEquals("OFF", off.value)
        assertEquals("ON", on.value)
        assertEquals("google is told a message waits. never who, never what", on.note)
        assertEquals("on: a banner when a message arrives while the app is closed", off.note)
        // §1: `<carrier>` is `google` on Android — the push really does go to FCM here
        assertTrue("the ON note names the carrier this platform actually uses", on.note.startsWith("google is told"))
    }

    @Test fun theProxyRowFollowsTheStoredAddress() {
        val off = StatusModel.proxyRow(base.copy(socksProxy = null))
        assertEquals("OFF", off.value)
        assertEquals("on: the relay never learns your address", off.note)
        val on = StatusModel.proxyRow(base.copy(socksProxy = "127.0.0.1:9050"))
        assertEquals("ON", on.value)
        assertEquals("the relay never learns your address", on.note)
        assertEquals("a blank string is not a proxy", "OFF", StatusModel.proxyRow(base.copy(socksProxy = "   ")).value)
    }

    @Test fun theReadReceiptsRowFollowsTheToggleAndSaysWhatOffCosts() {
        assertEquals("OFF", StatusModel.readReceiptsRow(base).value)   // sendReadReceipts nil ⇒ false
        val on = StatusModel.readReceiptsRow(base.copy(sendReadReceipts = true))
        assertEquals("ON", on.value)
        assertEquals("contacts learn when you opened a message", on.note)
        assertEquals("contacts are not told when you read", StatusModel.readReceiptsRow(base).note)
    }

    @Test fun theVerifiedContactsRowCountsTheRealContacts() {
        fun c(name: String, verified: Boolean) = Contact(hash = name, name = name, addedAt = 0, verified = verified)
        assertEquals("none", StatusModel.verifiedContactsRow(emptyList()).value)

        val oneOfFour = StatusModel.verifiedContactsRow(listOf(c("a", true), c("b", false), c("c", false), c("d", false)))
        assertEquals("1 of 4", oneOfFour.value)
        assertEquals("the others could be anyone", oneOfFour.note)

        // §1 plural rule: exactly one unverified
        assertEquals("the other could be anyone", StatusModel.verifiedContactsRow(listOf(c("a", true), c("b", false))).note)

        val noneOfThree = StatusModel.verifiedContactsRow(listOf(c("a", false), c("b", false), c("c", false)))
        assertEquals("0 of 3", noneOfThree.value)
        assertEquals("none verified — any of them could be anyone", noneOfThree.note)

        val allTwo = StatusModel.verifiedContactsRow(listOf(c("a", true), c("b", true)))
        assertEquals("2 of 2", allTwo.value)
        assertEquals("all 2 verified", allTwo.note)

        assertNull("verification lives in a chat's [safety #], not in [cfg]", oneOfFour.target)
    }

    @Test fun theTypingRowFollowsTheToggleAndItsDefaultIsOn() {
        assertEquals("ON", StatusModel.typingRow(base).value)     // sendTyping nil ⇒ true
        assertEquals("contacts see when you are typing", StatusModel.typingRow(base).note)
        val off = StatusModel.typingRow(base.copy(sendTyping = false))
        assertEquals("OFF", off.value)
        assertEquals("contacts are not told when you type", off.note)
        assertEquals(CfgRow.TYPING, off.target)
    }

    /** §1: the largest continuous fact the group exists to report. */
    @Test fun theDesktopLinkRowFollowsTheLiveMirror() {
        val idle = StatusModel.desktopLinkRow(MirrorState.Idle)
        assertEquals("OFF", idle.value)
        assertEquals("on: read and reply from a browser", idle.note)

        val linked = StatusModel.desktopLinkRow(MirrorState.Linked(since = 1_800_000_000_000L, browserName = "chrome", sas = "A1B2"))
        assertEquals("ON", linked.value)
        assertTrue("it names the browser", linked.note.startsWith("linked to chrome, since "))
        assertEquals(CfgRow.DESKTOP_LINK, linked.target)

        assertEquals("an unnamed browser is still a browser", "browser",
            StatusModel.desktopLinkRow(MirrorState.Linked(0L, null, "A1B2")).note.removePrefix("linked to ").substringBefore(","))

        // a FAILED link mirrors nothing, so it reads exactly as idle — no history on a state page
        assertEquals(idle, StatusModel.desktopLinkRow(MirrorState.Failed("hijack guard")).copy())
    }

    @Test fun theBackupRowFollowsWhenOneLastLanded() {
        val never = StatusModel.backupRow(null, now)
        assertEquals("NEVER", never.value)
        assertEquals("there is nothing to restore from", never.note)
        // §1's pinned plural rules for backup age
        assertEquals("today", StatusModel.backupRow(now - 3_600_000L, now).value)
        assertEquals("1 day ago", StatusModel.backupRow(now - 30 * 3_600_000L, now).value)
        assertEquals("12 days ago", StatusModel.backupRow(now - 12 * 86_400_000L, now).value)
        assertTrue("§1's note is `last made <date>`", StatusModel.backupRow(now, now).note.startsWith("last made "))
        assertEquals("a clock that moved backwards is not a negative age", "today", StatusModel.backupRow(now + 86_400_000L, now).value)
        assertNotEquals(never.note, StatusModel.backupRow(now, now).note)
    }

    @Test fun theAccessKeyRowCountsTheDaysLeft() {
        assertEquals("142 days", StatusModel.accessKeyRow(142).value)
        assertEquals("§1: the single deliberate exception to \"every row has a note\"", "", StatusModel.accessKeyRow(142).note)
        val none = StatusModel.accessKeyRow(0)
        assertEquals("EXPIRED", none.value)
        assertEquals("messaging is off", none.note)
    }

    // ---- tapping a row reaches the control that changes it ------------------------------

    @Test fun everyRowWithASettingBehindItNamesARowThatExists() {
        val placed = CfgLayout.rows.toSet()
        for (g in groups()) {
            for (r in g.rows) {
                val target = r.target ?: continue
                assertTrue("`${r.label}` points at a row `[cfg]` does not draw", target in placed)
                assertNotEquals("nothing may point back at the status row itself", CfgRow.STATUS, target)
            }
        }
    }

    @Test fun theRowsThatDoPointSomewherePointAtTheRightSetting() {
        val targets = groups().flatMap { it.rows }.associate { it.label to it.target }
        assertEquals(CfgRow.BIOMETRIC, targets["fingerprint"])
        assertEquals(CfgRow.AUTO_LOCK, targets["auto-lock"])
        assertEquals(CfgRow.DURESS, targets["duress pin"])
        assertEquals(CfgRow.DEADMAN, targets["dead-man switch"])
        assertEquals(CfgRow.BLOCK_SCREENSHOTS, targets["screenshot shield"])
        assertEquals(CfgRow.WAKE_PUSH, targets["wake push"])
        assertEquals(CfgRow.PROXY, targets["proxy"])
        assertEquals(CfgRow.READ_RECEIPTS, targets["read receipts"])
        assertEquals(CfgRow.TYPING, targets["typing indicators"])
        assertEquals(CfgRow.DESKTOP_LINK, targets["desktop link"])
        assertEquals(CfgRow.BACKUP, targets["backup"])
        assertEquals(CfgRow.SUBSCRIPTION, targets["access key"])
        // the three facts with no switch behind them
        assertNull(targets["wrong attempts"])
        assertNull(targets["sender hidden"])
        assertNull(targets["verified contacts"])
    }

    /**
     * docs/SEALED-SENDER.md §5.8: ON carries its limit in the same breath, and the two OFF
     * sentences say why and what the relay learns meanwhile. The same strings as iOS.
     */
    @Test fun theSenderHiddenRowSaysWhichOfTheThreeItIs() {
        val on = StatusModel.senderHiddenRow(SenderHidden.ON)
        assertEquals("sender hidden", on.label)
        assertEquals("ON", on.value)
        assertEquals("the relay is not told who sends, when the other person's app supports it", on.note)
        assertNull("a fact about this login, not a switch", on.target)
        val none = StatusModel.senderHiddenRow(SenderHidden.NOT_OFFERED)
        val failed = StatusModel.senderHiddenRow(SenderHidden.NO_PRIVATE_CONNECTION)
        assertEquals("OFF", none.value)
        assertEquals("OFF", failed.value)
        assertEquals("this relay does not offer it. it is told who sends each message", none.note)
        assertEquals("the private connection could not be opened. the relay is told who sends", failed.note)
        for (state in SenderHidden.entries) {
            val row = StatusModel.senderHiddenRow(state)
            val leaves = groups(senderHidden = state)[1].rows
            assertEquals("after the switches, before the last row", listOf(row, leaves.last()), leaves.takeLast(2))
            assertEquals("verified contacts", leaves.last().label)
            if (state != SenderHidden.ON) assertTrue("OFF says what the relay learns", row.note.contains("told who sends"))
        }
    }

    // ---- no scores, no grades ------------------------------------------------------------

    /**
     * §1: "No scores, no grades, no green ticks — this is a product for people with real
     * adversaries, and a screen that says 'you are 80% secure' is a lie." The screen has no place
     * to put a score, and this is the assertion that it stays that way.
     */
    @Test fun nothingOnThisScreenScoresAnything() {
        // whole words, because `safety number` is the app's own vocabulary and must survive
        val forbidden = listOf("score", "grade", "secure", "safe", "good", "strong", "weak", "risk", "protected")
            .map { Regex("\\b$it\\b") } + listOf(Regex("%"), Regex("[✓✗]"))
        for (state in everyState()) {
            val text = StatusModel.surface(state).lowercase()
            for (word in forbidden) {
                assertFalse("the status screen may never say \"$word\": $text", word.containsMatchIn(text))
            }
        }
    }

    // ---- the decoy is absent, entirely ---------------------------------------------------

    /**
     * docs/DECOY-ACCOUNT.md §3 and docs/STATUS-AND-CFG.md §1: not `SET`, not `NOT SET`, not a
     * greyed row. Asserted over the WHOLE rendered surface — every heading, label, value and note
     * of every row — in every state this screen can be in, rather than over one label, because a
     * page that lists everything is exactly where it would slip out somewhere nobody looked.
     *
     * The structural half matters as much as the string half: [StatusModel.groups] takes no
     * argument that could answer the question, so there is no state of the app in which it could.
     */
    @Test fun theDecoyIsAbsentFromTheWholeSurfaceInEveryState() {
        val forbidden = listOf("decoy", "hidden", "slot", "second account", "other account", "container", "two accounts")
        var checked = 0
        for (state in everyState()) {
            // `sender hidden` is the one place the word appears: the row is about the relay, and it
            // is the same on iOS (docs/SEALED-SENDER.md §5.8). Anywhere else it still fails.
            val text = StatusModel.surface(state).lowercase().replace("sender hidden", "")
            for (word in forbidden) {
                assertFalse("docs/DECOY-ACCOUNT.md §3: the status screen may never say \"$word\": $text", text.contains(word))
            }
            checked++
        }
        assertTrue("the sweep must actually cover states, not zero of them", checked >= 128)
    }

    @Test fun theSurfaceUnderTestIsActuallyTheWholeScreen() {
        // an empty-surface bug would make every assertion above pass forever
        val text = StatusModel.surface(groups())
        for (heading in listOf(StatusModel.PROTECTS_YOU, StatusModel.LEAVES_PHONE, StatusModel.IF_YOU_LOSE_IT)) {
            assertTrue(text.contains(heading))
        }
        for (g in groups()) {
            for (r in g.rows) {
                assertTrue("`${r.label}` is missing from the surface", text.contains(r.label))
                assertTrue("`${r.label}`'s value is missing from the surface", text.contains(r.value))
                assertTrue("`${r.label}`'s note is missing from the surface", text.contains(r.note))
            }
        }
    }

    /**
     * Every combination of the state this screen reads, so a sweep is a sweep: 3 sensors + none,
     * both stored-key states, both duress states, the failure counter across its whole range, four
     * contact rosters, funded and unfunded, backed up and never, both values of each of the five
     * settings the screen reports, and the three things the relay can have said about sealed sender.
     */
    private fun everyState(): List<List<StatusGroup>> {
        fun c(name: String, verified: Boolean) = Contact(hash = name, name = name, addedAt = 0, verified = verified)
        val rosters = listOf(
            emptyList(),
            listOf(c("a", false)),
            listOf(c("a", true)),
            listOf(c("a", true), c("b", false), c("c", false), c("d", false)),
        )
        val settings = listOf(
            base,
            base.copy(
                pushEnabled = true, socksProxy = "127.0.0.1:9050", sendReadReceipts = true,
                blockScreenshots = false, wipeAfterDays = 30, autoLockMinutes = 0, sendTyping = false,
            ),
            base.copy(
                pushEnabled = true, socksProxy = "tor:9050", sendReadReceipts = false,
                blockScreenshots = true, wipeAfterDays = 90, autoLockMinutes = 15,
            ),
        )
        val mirrors = listOf(
            MirrorState.Idle,
            MirrorState.Failed("the relay is gone"),
            MirrorState.Linked(now - 3_600_000L, "chrome", "A1B2"),
            MirrorState.Linked(now - 3_600_000L, null, "A1B2"),
        )
        val out = ArrayList<List<StatusGroup>>()
        for (s in settings) {
            for (kind in listOf(null, BiometricKind.FINGERPRINT, BiometricKind.FACE, BiometricKind.GENERIC)) {
                for (bio in listOf(false, true)) {
                    for (duress in listOf(false, true)) {
                        for (failures in listOf(0, 7, 9)) {
                            for (roster in rosters) {
                                for (days in listOf(0, 142)) {
                                    for (backup in listOf(null, now - 12 * 86_400_000L)) {
                                        for (m in mirrors) {
                                            for (hidden in SenderHidden.entries) {
                                                out += StatusModel.groups(s, kind, bio, duress, failures, roster, days, backup, m, now, senderHidden = hidden)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return out
    }
}
