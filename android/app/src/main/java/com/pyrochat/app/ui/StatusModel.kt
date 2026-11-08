package com.pyrochat.app.ui

import com.pyrochat.app.BuildConfig
import com.pyrochat.app.platform.BiometricKind
import com.pyrochat.app.platform.UnlockFailurePolicy

/**
 * One row of the status screen: a fact, never a control.
 *
 * [note] is the point of the whole screen (docs/STATUS-AND-CFG.md §1): `auto-lock OFF` teaches
 * nothing, "on: relocks after you leave the app" tells someone whether they want it. [target] is
 * the `[cfg]` row that changes this fact, or null when no setting does — a count of wrong password
 * attempts and a count of verified contacts are both real facts with no switch behind them.
 */
data class StatusRow(val label: String, val value: String, val note: String, val target: CfgRow? = null)

/** One `── HEADING ──` and the rows under it. */
data class StatusGroup(val title: String, val rows: List<StatusRow>)

/**
 * docs/STATUS-AND-CFG.md §1 — "what is protecting me right now", as a pure function of the state
 * the session already holds.
 *
 * **The words live here rather than in `strings.xml`** because on this screen the words ARE the
 * logic: every row's right-hand column is a different sentence in each state, and §4 asks for a
 * test that flips each setting and watches the row change. That test is a JVM one against this
 * object, the same way `HomeRules` and `GroupUi` already hold their verbatim texts. Nothing here
 * touches an Android resource, so it runs without a device.
 *
 * ### The rule the notes follow
 *
 * A protection that is OFF says `on: …` and what it would do. A protection that is ON says what it
 * does now, and what that costs. Neither ever scolds, and nothing on this screen scores, grades or
 * ticks — §1: a screen that says "you are 80% secure" is a lie.
 *
 * ### The decoy is absent, entirely
 *
 * Not `SET`, not `NOT SET`, not a greyed row. Nothing this object can return, in any state, under
 * any password, may mention one — see docs/DECOY-ACCOUNT.md §3, and `StatusScreenModelTest`, which
 * asserts it over the whole surface rather than over one label. A page that lists everything is
 * exactly where that would slip out, so the safest shape is the one taken here: there is no input
 * to this function that could answer the question.
 */
object StatusModel {
    /** The `[cfg]` row that reaches this screen, and the screen's own title. */
    const val TITLE = "what protects me"

    const val PROTECTS_YOU = "WHAT PROTECTS YOU"
    const val LEAVES_PHONE = "WHAT LEAVES THIS PHONE"
    const val IF_YOU_LOSE_IT = "IF YOU LOSE IT"

    /**
     * `last made <date>`. The phone's own zone, not UTC: the age beside it is elapsed days as this
     * phone counts them, and `desktop link`'s `since <time>` is local too ([Format.shortClock]), so
     * a UTC date here would be the one time on the page that disagreed with the others.
     */
    private val DAY: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.ROOT)
            .withZone(java.time.ZoneId.systemDefault())

    private const val ON = "ON"
    private const val OFF = "OFF"

    /**
     * Everything the screen shows. [lastBackupAt] and [now] are epoch millis; a null
     * [lastBackupAt] is a phone that has never exported one.
     */
    fun groups(
        settings: AppSettings,
        biometricKind: BiometricKind?,
        biometricOn: Boolean,
        duressSet: Boolean,
        unlockFailures: Int,
        contacts: List<Contact>,
        accessDays: Int,
        lastBackupAt: Long?,
        mirror: MirrorState,
        now: Long,
        accessKeyUi: Boolean = BuildConfig.ACCESS_KEY_UI,
        senderHidden: SenderHidden = SenderHidden.NOT_OFFERED,
    ): List<StatusGroup> = listOf(
        StatusGroup(
            PROTECTS_YOU,
            listOf(
                biometricRow(biometricKind, biometricOn),
                autoLockRow(settings),
                duressRow(duressSet),
                deadManRow(settings),
                screenshotShieldRow(settings),
                wrongAttemptsRow(unlockFailures),
            ),
        ),
        StatusGroup(
            LEAVES_PHONE,
            listOf(
                // §1: `wake push, proxy, read receipts, typing indicators, desktop link`, the same
                // on both screens, then here only `sender hidden` and, last, `verified contacts`.
                wakePushRow(settings),
                proxyRow(settings),
                readReceiptsRow(settings),
                typingRow(settings),
                desktopLinkRow(mirror),
                senderHiddenRow(senderHidden),
                verifiedContactsRow(contacts),
            ),
        ),
        StatusGroup(
            IF_YOU_LOSE_IT,
            listOf(
                backupRow(lastBackupAt, now),
            ) + if (accessKeyUi) listOf(accessKeyRow(accessDays)) else emptyList(),
        ),
    )

    /** Every line the screen renders, headings included — what a "whole surface" assertion reads. */
    fun surface(groups: List<StatusGroup>): String =
        groups.joinToString("\n") { g ->
            "── ${g.title} ──\n" + g.rows.joinToString("\n") { "${it.label}\t${it.value}\t${it.note}" }
        }

    // ---- WHAT PROTECTS YOU -------------------------------------------------------------

    /**
     * docs/BIOMETRIC-UNLOCK.md §2 — the label follows the sensor, exactly as the `[cfg]` row does;
     * "face id" is the iOS word and this phone may have neither a face nor a finger.
     *
     * A phone with no biometric hardware (or nothing enrolled) hides the row in `[cfg]`. Here it
     * stays and says so: a page whose whole job is answering "what is protecting me" must not go
     * quiet about a protection that is simply not available, and "unavailable" is not a state
     * anyone can act on by accident.
     */
    fun biometricRow(kind: BiometricKind?, on: Boolean): StatusRow {
        val label = when (kind) {
            BiometricKind.FINGERPRINT -> "fingerprint"
            BiometricKind.FACE -> "face"
            BiometricKind.GENERIC, null -> "biometrics"
        }
        return when {
            kind == null -> StatusRow(label, "—", "this phone has no fingerprint or face enrolled")
            on -> StatusRow(label, ON, "opens without typing, for 7 days at a time", CfgRow.BIOMETRIC)
            else -> StatusRow(label, OFF, "on: opens without typing, for 7 days at a time", CfgRow.BIOMETRIC)
        }
    }

    /** §1 plural rule: `relocks 1 minute`, `relocks 5 minutes`. */
    fun autoLockRow(settings: AppSettings): StatusRow {
        val minutes = settings.autoLockMinutes ?: 5
        if (minutes == 0) return StatusRow("auto-lock", OFF, "on: relocks after you leave the app", CfgRow.AUTO_LOCK)
        val unit = if (minutes == 1) "minute" else "minutes"
        return StatusRow("auto-lock", ON, "relocks $minutes $unit after you leave", CfgRow.AUTO_LOCK)
    }

    fun duressRow(set: Boolean): StatusRow = if (set) {
        StatusRow("duress pin", "SET", "a second password that wipes everything", CfgRow.DURESS)
    } else {
        StatusRow("duress pin", "NOT SET", "on: a second password that wipes everything", CfgRow.DURESS)
    }

    fun deadManRow(settings: AppSettings): StatusRow {
        val days = settings.wipeAfterDays
        return if (days == null) {
            StatusRow("dead-man switch", OFF, "on: wipes if unopened for 7, 30 or 90 days", CfgRow.DEADMAN)
        } else {
            StatusRow("dead-man switch", ON, "wipes if unopened for $days days", CfgRow.DEADMAN)
        }
    }

    fun screenshotShieldRow(settings: AppSettings): StatusRow = if (settings.screenshotBlockOn) {
        StatusRow("screenshot shield", ON, "captures come out black", CfgRow.BLOCK_SCREENSHOTS)
    } else {
        StatusRow("screenshot shield", OFF, "on: captures come out black", CfgRow.BLOCK_SCREENSHOTS)
    }

    /**
     * docs/SCRUB-AND-WIPE.md §A — the counter is the one on the unlock screen, and it has no
     * setting behind it, so this row does not go anywhere when tapped.
     */
    fun wrongAttemptsRow(failures: Int): StatusRow = StatusRow(
        "wrong attempts",
        "${failures.coerceAtLeast(0)}/${UnlockFailurePolicy.MAX_FAILURES}",
        "at ten, this device wipes itself",
    )

    // ---- WHAT LEAVES THIS PHONE --------------------------------------------------------

    /** §1: `<carrier>` is `google` on Android and `apple` on iOS — the push really does go to FCM here. */
    fun wakePushRow(settings: AppSettings): StatusRow = if (settings.pushEnabled) {
        StatusRow("wake push", ON, "google is told a message waits. never who, never what", CfgRow.WAKE_PUSH)
    } else {
        StatusRow("wake push", OFF, "on: a banner when a message arrives while the app is closed", CfgRow.WAKE_PUSH)
    }

    fun proxyRow(settings: AppSettings): StatusRow {
        val proxy = settings.socksProxy
        return if (proxy.isNullOrBlank()) {
            StatusRow("proxy", OFF, "on: the relay never learns your address", CfgRow.PROXY)
        } else {
            StatusRow("proxy", ON, "the relay never learns your address", CfgRow.PROXY)
        }
    }

    fun readReceiptsRow(settings: AppSettings): StatusRow = if (settings.readReceiptsOn) {
        StatusRow("read receipts", ON, "contacts learn when you opened a message", CfgRow.READ_RECEIPTS)
    } else {
        StatusRow("read receipts", OFF, "contacts are not told when you read", CfgRow.READ_RECEIPTS)
    }

    /**
     * Verification is per contact and lives in a chat's `[safety #]`, not in `[cfg]`, so this row
     * is a fact with nowhere to send anyone.
     */
    fun verifiedContactsRow(contacts: List<Contact>): StatusRow {
        val total = contacts.size
        val verified = contacts.count { it.verified }
        return when {
            total == 0 -> StatusRow("verified contacts", "none", "no contacts on this phone yet")
            verified == total -> StatusRow("verified contacts", "$verified of $total", "all $total verified")
            verified == 0 -> StatusRow("verified contacts", "0 of $total", "none verified — any of them could be anyone")
            // §1 plural rule: `the other could be anyone` with exactly one unverified
            total - verified == 1 -> StatusRow("verified contacts", "$verified of $total", "the other could be anyone")
            else -> StatusRow("verified contacts", "$verified of $total", "the others could be anyone")
        }
    }

    /** §1: `contacts see when you are typing` has no pinned wording; this mirrors `read receipts`. */
    fun typingRow(settings: AppSettings): StatusRow = if (settings.typingOn) {
        StatusRow("typing indicators", ON, "contacts see when you are typing", CfgRow.TYPING)
    } else {
        StatusRow("typing indicators", OFF, "contacts are not told when you type", CfgRow.TYPING)
    }

    /**
     * §1: "a linked browser is a live mirror of your messages on another machine, and it is the
     * largest continuous fact that group exists to report".
     *
     * A link that FAILED is not a link: nothing is mirrored, so it reads exactly as idle. `[cfg]`
     * keeps the reason on the link screen; this page reports state, not history.
     */
    fun desktopLinkRow(mirror: MirrorState): StatusRow = when (mirror) {
        is MirrorState.Linked -> StatusRow(
            "desktop link", ON,
            "linked to ${mirror.browserName ?: "browser"}, since ${Format.shortClock(mirror.since)}",
            CfgRow.DESKTOP_LINK,
        )
        MirrorState.Idle, is MirrorState.Failed ->
            StatusRow("desktop link", OFF, "on: read and reply from a browser", CfgRow.DESKTOP_LINK)
    }

    /**
     * docs/SEALED-SENDER.md §5.8. Like `verified contacts` it has no `[cfg]` control: it reports
     * what this login got from the relay. ON carries its limit in the same breath, because a row
     * that only said "hidden" would overstate it for every contact on an older build; the two OFF
     * sentences say why, and what the relay learns meanwhile. The same strings as iOS.
     */
    fun senderHiddenRow(state: SenderHidden): StatusRow = when (state) {
        SenderHidden.ON ->
            StatusRow("sender hidden", ON, "the relay is not told who sends, when the other person's app supports it")
        SenderHidden.NOT_OFFERED ->
            StatusRow("sender hidden", OFF, "this relay does not offer it. it is told who sends each message")
        SenderHidden.NO_PRIVATE_CONNECTION ->
            StatusRow("sender hidden", OFF, "the private connection could not be opened. the relay is told who sends")
    }

    // ---- IF YOU LOSE IT ----------------------------------------------------------------

    fun backupRow(lastBackupAt: Long?, now: Long): StatusRow {
        if (lastBackupAt == null) return StatusRow("backup", "NEVER", "there is nothing to restore from", CfgRow.BACKUP)
        val days = ((now - lastBackupAt) / 86_400_000L).coerceAtLeast(0L)
        // §1's plural rules: `today`, `1 day ago`, `12 days ago`.
        val value = when (days) {
            0L -> "today"
            1L -> "1 day ago"
            else -> "$days days ago"
        }
        // §1: the value carries the age, the note the actual day, so no date is printed twice.
        return StatusRow("backup", value, "last made ${DAY.format(java.time.Instant.ofEpochMilli(lastBackupAt))}", CfgRow.BACKUP)
    }

    /**
     * docs/PROTOCOL.md §7 — one blind-signed token per UTC day, presented at login; with none left
     * the app cannot sign in at all, which is the fact worth printing when the count reaches zero.
     */
    fun accessKeyRow(days: Int): StatusRow = if (days > 0) {
        // §1: the single row whose note is empty when funded, and deliberately so
        StatusRow("access key", "$days days", "", CfgRow.SUBSCRIPTION)
    } else {
        StatusRow("access key", "EXPIRED", "messaging is off", CfgRow.SUBSCRIPTION)
    }
}

/**
 * The three states of the `sender hidden` row (docs/SEALED-SENDER.md §5.8). A fact about this
 * login, not a switch: there is no control behind it.
 */
enum class SenderHidden {
    /** Messages go out sealed wherever the other person's app can take them. */
    ON,

    /** The relay did not offer sealed sender, or its certificate did not pass this phone's check. */
    NOT_OFFERED,

    /** It was offered, and the second connection it needs could not be opened. */
    NO_PRIVATE_CONNECTION,
}
