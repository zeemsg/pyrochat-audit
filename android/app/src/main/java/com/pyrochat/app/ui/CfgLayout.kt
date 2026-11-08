package com.pyrochat.app.ui

import androidx.annotation.StringRes
import com.pyrochat.app.R

/**
 * docs/STATUS-AND-CFG.md §2 — the five sections of `[cfg]`, named after what the person is trying
 * to do rather than after the implementation.
 */
enum class CfgSection(@StringRes val title: Int) {
    PROTECTION(R.string.cfg_protection),
    // §2: APPEARANCE sits second. Ordering purely by consequence pushed `theme` and `text size`
    // below the fold, and those are the rows people actually touch; the status row already
    // represents protection at the top.
    APPEARANCE(R.string.cfg_appearance),
    LEAVES(R.string.cfg_leaves),
    ACCOUNT(R.string.cfg_account),
    DANGER(R.string.cfg_danger),
}

/**
 * One row of `[cfg]`, as a name rather than as a position.
 *
 * Two things read this: [CfgLayout], which is the only place the ORDER lives, and the status
 * screen, whose rows each carry the row they jump to (docs/STATUS-AND-CFG.md §1: "tapping one goes
 * to the setting that changes it"). `SettingsScreen` renders by walking [CfgLayout] and dispatching
 * on this enum, so a row cannot be in the layout and missing from the screen — the `when` is
 * exhaustive — and cannot drift out of order without this file saying so.
 */
enum class CfgRow {
    /** §2: `[ what protects me ]`, the first row of the screen, above every section. */
    STATUS,

    // ---- PROTECTION ----
    BIOMETRIC,
    AUTO_LOCK,
    DURESS,
    DEADMAN,
    DECOY,
    BLOCK_SCREENSHOTS,
    BURN_ON_SCREENSHOT,
    DEFAULT_BURN,
    THREAD_AUTO_BURN,

    // ---- APPEARANCE ----
    THEME,
    TEXT_SIZE,
    SCANLINES,
    SOUNDS,
    NOTICES,

    // ---- WHAT LEAVES THIS PHONE ----
    WAKE_PUSH,
    PROXY,
    READ_RECEIPTS,
    TYPING,
    DESKTOP_LINK,

    // ---- ACCOUNT ----
    SUBSCRIPTION,
    ACCESS_KEY,
    BACKUP,
    BLOCKED,
    REPORT_ABUSE,
    SUPPORT,
    LICENSES,

    /** §2: the relay address and the billing address, collapsed behind one row at the bottom. */
    ADVANCED,

    // ---- DANGER ----
    BURN_EVERYTHING,
}

/**
 * docs/STATUS-AND-CFG.md §2 — the order of `[cfg]`, and the ONLY place it lives.
 *
 * It is a value rather than the shape of a composable because §2's "what must not move" list is a
 * set of ORDERING facts, and a reorganisation is exactly the change that breaks them silently. Here
 * they are one `assertEquals` each in the JVM suite; inside a 600-line `Column` they would be
 * whatever the last edit left behind.
 */
object CfgLayout {
    /** §2: above every section, and the only way to the status screen. */
    val firstRow: CfgRow = CfgRow.STATUS

    val sections: List<Pair<CfgSection, List<CfgRow>>> = listOf(
        CfgSection.PROTECTION to listOf(
            // docs/BIOMETRIC-UNLOCK.md §2 — directly above auto-lock, and nothing may come between
            CfgRow.BIOMETRIC,
            CfgRow.AUTO_LOCK,
            CfgRow.DURESS,
            CfgRow.DEADMAN,
            CfgRow.DECOY,
            // §1 pins the pair's order: the shield this phone applies, then what a peer's capture
            // does to the thread. §2 said only "screenshot rows".
            CfgRow.BLOCK_SCREENSHOTS,
            CfgRow.BURN_ON_SCREENSHOT,
            // §2's table does not place these two; they are message-lifetime protections and the
            // old BURN section they sat in is dissolved, so they land here, last, leaving the
            // order the table DOES give untouched.
            CfgRow.DEFAULT_BURN,
            CfgRow.THREAD_AUTO_BURN,
        ),
        CfgSection.APPEARANCE to listOf(
            CfgRow.THEME,
            // docs/TEXT-SIZE.md §1 — directly under `theme`
            CfgRow.TEXT_SIZE,
            CfgRow.SCANLINES,
            CfgRow.SOUNDS,
            CfgRow.NOTICES,
        ),
        CfgSection.LEAVES to listOf(
            // §1 settles this order for BOTH screens: the status sketch and §2's table disagreed
            // about where `proxy` sat and both orders shipped. §2's table still carries the old one.
            CfgRow.WAKE_PUSH,
            CfgRow.PROXY,
            CfgRow.READ_RECEIPTS,
            CfgRow.TYPING,
            CfgRow.DESKTOP_LINK,
        ),
        CfgSection.ACCOUNT to listOf(
            CfgRow.SUBSCRIPTION,
            CfgRow.ACCESS_KEY,
            CfgRow.BACKUP,
            CfgRow.BLOCKED,
            CfgRow.REPORT_ABUSE,
            CfgRow.SUPPORT,
            CfgRow.LICENSES,
            CfgRow.ADVANCED,
        ),
        CfgSection.DANGER to listOf(
            CfgRow.BURN_EVERYTHING,
        ),
    )

    /** Every row in the order the screen renders it, the first row included. */
    val rows: List<CfgRow> = listOf(firstRow) + sections.flatMap { it.second }

    fun rowsIn(section: CfgSection): List<CfgRow> = sections.first { it.first == section }.second

    /** The section a row sits in; null for [CfgRow.STATUS], which sits above all of them. */
    fun sectionOf(row: CfgRow): CfgSection? = sections.firstOrNull { row in it.second }?.first

    /**
     * §2 "consequence gets weight": the rows that destroy data wear the existing alert colour, so
     * `scanlines` and `dead-man wipe` can no longer render identically. No new palette.
     */
    fun destroysData(row: CfgRow): Boolean =
        row == CfgRow.DURESS || row == CfgRow.DEADMAN || row == CfgRow.BURN_EVERYTHING
}
