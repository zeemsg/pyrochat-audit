package com.pyrochat.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure, JVM-testable string helpers. Every output here is a word the iOS app
 * renders — see the cited Swift site. No Android resources so unit tests run
 * without a device.
 */
object Format {
    private val clockFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val shortClockFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** `ChatView.time` — `[HH:mm:ss]` row stamp. */
    fun clock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFmt.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** `HomeView.time` — `HH:mm` of the last message. */
    fun shortClock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        shortClockFmt.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /**
     * `ChatView.statusWord(_:showRead:)` — reciprocity: READ renders only when
     * MY read-receipts toggle is on, otherwise it shows as DELIVERED.
     */
    fun statusWord(status: Status, showRead: Boolean): String = when (status) {
        Status.pending -> "…"
        Status.sent -> "SENT"
        Status.delivered -> "DELIVERED"
        Status.read -> if (showRead) "READ" else "DELIVERED"
        Status.failed -> "FAILED"
    }

    /** ChatView trailing `🔥N` — whole seconds left, floored at 0. */
    fun burnCountdown(deadlineMillis: Long, nowMillis: Long): String {
        val remaining = ((deadlineMillis - nowMillis) / 1000).coerceAtLeast(0)
        return "🔥$remaining"
    }

    /** ChatView trailing `🔥…` — armed, countdown not started (peer hasn't read it). */
    const val BURN_ARMED = "🔥…"

    /** SettingsView `default self-destruct` picker value; also the `[ttl]` labels. */
    fun burnLabel(seconds: Long?): String = when (seconds) {
        null -> "off"
        5L -> "5s"
        60L -> "1m"
        3600L -> "1h"
        86400L -> "1d"
        else -> "${seconds}s"
    }

    /** Inverse of [burnLabel] for the picker options. */
    fun burnSeconds(label: String): Long? = when (label) {
        "5s" -> 5L
        "1m" -> 60L
        "1h" -> 3600L
        "1d" -> 86400L
        else -> null
    }

    /** `byteLabel(_:)` in Attachments.swift — `312K`, `2.4M`. */
    fun byteLabel(bytes: Long?): String = when {
        bytes == null -> "?"
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}K"
        else -> String.format(Locale.ROOT, "%.1fM", bytes.toDouble() / (1024 * 1024))
    }

    /** `HomeView.preview(_:)` */
    fun preview(m: ChatMessage): String = when (m.kind) {
        Kind.text -> m.text
        Kind.voice -> "[voice note]"
        Kind.image -> "[image]"
        Kind.file -> "[file] ${m.fileName ?: ""}"
        Kind.system -> m.text
    }

    /** HomeView row second line: `you: ` prefix for outgoing, or the empty-channel text. */
    fun previewLine(last: ChatMessage?): String =
        if (last == null) "channel open — no traffic"
        else (if (last.direction == Direction.outgoing) "you: " else "") + preview(last)

    /** `ChatService.unreadCount(for:)` — incoming, non-system, not yet read. */
    fun unreadCount(log: List<ChatMessage>): Int =
        log.count { it.direction == Direction.incoming && it.kind != Kind.system && it.status != Status.read }

    /** The `<pyrochat>` channel name — the relay's own announcements, never a user. */
    const val NOTICES_CHANNEL = "<pyrochat>"

    /** The notice the `<pyrochat>` row speaks for: the most recently posted one (null when the channel is empty). */
    fun newestNotice(notices: List<UiNotice>): UiNotice? = notices.maxWithOrNull(compareBy({ it.ts }, { it.id }))

    /** HomeView `<pyrochat>` row second line: the newest notice text on ONE line (line breaks and runs of blanks collapse). */
    fun noticeSubtitle(notices: List<UiNotice>): String =
        newestNotice(notices)?.text?.replace(Regex("\\s+"), " ")?.trim() ?: ""

    /** SettingsView `thread auto-burn` / `dead-man wipe` values (`never`/`off`, `Nd`). */
    fun daysLabel(days: Int?, none: String): String = days?.let { "${it}d" } ?: none

    /** SettingsView `auto-lock` value from the stored minutes (nil → 5m, 0 → off). */
    fun autoLockLabel(autoLockMinutes: Int?): String = when (autoLockMinutes) {
        null -> "5m"
        0 -> "off"
        1 -> "1m"
        15 -> "15m"
        else -> "5m"
    }

    /** Inverse of [autoLockLabel] — what gets stored. */
    fun autoLockMinutes(label: String): Int = when (label) {
        "off" -> 0
        "1m" -> 1
        "15m" -> 15
        else -> 5
    }

    /** HomeView uid line: `uid: <username>  [<hash prefix 12>…]` */
    fun uidLine(username: String, hash: String): String = "uid: $username  [${hash.take(12)}…]"

    /** VerifyView.digitBlock — 12 groups of 5, 3 groups per line joined by two spaces. */
    fun safetyNumberLines(digits: String): List<String> =
        digits.chunked(5).chunked(3).map { it.joinToString("  ") }
}

/**
 * docs/FIRST-RUN.md §1–2 home-screen rules — pure, JVM-tested (`HomeRulesTest`). The words are
 * verbatim on both platforms.
 */
object HomeRules {
    /** The demo peer — matched on the contact named EXACTLY this (the local display name). */
    const val PYROBOT = "pyrobot"

    /** The pyrobot row's subtitle while its thread has no messages (instead of `channel open — no traffic`). */
    const val PYROBOT_DEMO_SUBTITLE = "demo peer — say hi for a 60-second tour"

    /** Where a shared friend gets the app. */
    const val APP_URL = "https://pyrochat.ca"

    /** The three-line empty state shows with no contacts AND no groups (blocked contacts still count as contacts). */
    fun showsEmptyState(contacts: List<Contact>, groups: List<UiGroup>): Boolean = contacts.isEmpty() && groups.isEmpty()

    /** Home row second line for a 1:1 channel: the demo line for an empty `pyrobot` thread, else [Format.previewLine]. */
    fun subtitle(contact: Contact, log: List<ChatMessage>): String =
        if (log.isEmpty() && contact.name == PYROBOT) PYROBOT_DEMO_SUBTITLE else Format.previewLine(log.lastOrNull())

    /**
     * `[share]` → the system share sheet text, three lines verbatim (§2): [username] = my username,
     * [code] = my `pyro-add:v1:…` code as shown on the [qr] screen.
     */
    fun shareText(username: String, code: String): String =
        "add me on PyroChat — my username is $username\n" +
            "in the app: [qr] → paste this code → $code\n" +
            "get the app: $APP_URL"
}

/** `TerminalLogMath` — windowed paging constants and pure math (TerminalLog.swift). */
object TerminalLogMath {
    const val INITIAL_WINDOW = 60
    const val PAGE = 40
    const val TRIM_THRESHOLD = 120

    /** Window growth when the user nears the top; never shrinks. */
    fun grown(current: Int, total: Int): Int = minOf(current + PAGE, total)

    /** While the reader is up in history the window widens so the top row stays put. */
    fun appended(current: Int, appended: Int, atBottom: Boolean): Int =
        if (atBottom) current else current + appended
}

/**
 * `HomeView.orderedByActivity`: newest activity first — the latest message in the
 * thread, or the moment the contact was added when nothing has been exchanged yet.
 */
fun orderedByActivity(contacts: List<Contact>, messages: Map<String, List<ChatMessage>>): List<Contact> =
    contacts.sortedByDescending { c -> messages[c.hash]?.maxOfOrNull { it.timestamp } ?: c.addedAt }

/** One home-list row: a 1:1 channel or a group channel, "activity-sorted like everything else" (GROUPS.md §7). */
sealed interface HomeEntry {
    val key: String
    data class Peer(val contact: Contact) : HomeEntry { override val key: String get() = contact.hash }
    data class Channel(val group: UiGroup) : HomeEntry { override val key: String get() = group.id }
}

/** Contacts and groups interleaved by newest activity (a group with no traffic ranks by `createdAt`). */
fun homeEntries(contacts: List<Contact>, groups: List<UiGroup>, messages: Map<String, List<ChatMessage>>): List<HomeEntry> {
    val entries: List<HomeEntry> = contacts.map { HomeEntry.Peer(it) } + groups.map { HomeEntry.Channel(it) }
    return entries.sortedByDescending { e ->
        when (e) {
            is HomeEntry.Peer -> messages[e.contact.hash]?.maxOfOrNull { it.timestamp } ?: e.contact.addedAt
            is HomeEntry.Channel -> messages[e.group.id]?.maxOfOrNull { it.timestamp } ?: e.group.createdAt
        }
    }
}

/** How a group row labels its sender (GROUPS.md §5/§7): `<you>` in flame, `<alice>` in her colour, `<bob>?` dim for a former member. */
data class SenderTag(val text: String, val rgb: Int?, val dim: Boolean)

/** docs/GROUPS.md §5 + §7 rendering rules for group rows — pure, JVM-tested. */
object GroupUi {
    /** The picker labels in order (default = the last). */
    val POLICY_LABELS: List<String> = com.pyrochat.store.model.GroupPolicy.entries.map { it.label }

    fun policyLabel(wire: String): String = com.pyrochat.store.model.GroupPolicy.fromWire(wire)?.label ?: wire
    fun policyWire(label: String): String = (com.pyrochat.store.model.GroupPolicy.fromLabel(label) ?: com.pyrochat.store.model.GroupPolicy.DEFAULT).wire

    /** Join order = `(joinedAt, hash)` over members ∪ former (earliest join per hash); the index feeds the palette (§5). */
    fun joinIndex(group: UiGroup, hash: String): Int? =
        group.colourRoster.sortedWith(compareBy<UiMember> { it.joinedAt }.thenBy { it.hash }).indexOfFirst { it.hash == hash }.takeIf { it >= 0 }

    /** `0xrrggbb` for a current or former member, or null when the hash is in neither list. */
    fun colour(group: UiGroup, hash: String): Int? = joinIndex(group, hash)?.let(com.pyrochat.service.GroupColours::rgb)

    /**
     * The tag for a row: own rows `<you>` (flame — `rgb` null, not dim); a current or former member
     * in her colour (former members keep their slot); a sender in neither list `<name>?` dim, named
     * from contacts, else `anon-<hash>`. [former] may carry extra session history to try first.
     */
    fun senderTag(msg: ChatMessage, group: UiGroup, former: List<UiMember>, contacts: List<Contact>): SenderTag {
        if (msg.direction == Direction.outgoing) return SenderTag("<you>", null, false)
        val hash = msg.sender ?: return SenderTag("<?>", null, true)
        group.member(hash)?.let { return SenderTag("<${it.name}>", colour(group, hash), false) }
        (group.formerMember(hash) ?: former.firstOrNull { it.hash == hash })?.let { f ->
            val withHistory = if (group.formerMember(hash) == null) group.copy(former = group.former + f) else group
            return SenderTag("<${f.name}>", colour(withHistory, hash), false)
        }
        val name = contacts.firstOrNull { it.hash == hash }?.name ?: com.pyrochat.service.GroupRules.anonName(hash)
        return SenderTag("<$name>?", null, true)
    }

    /** Own rows: `READ 7/9` / `DELIVERED 4/9` / `SENT` / `FAILED` / `…` (§1, §7) over the members except self and the gone. */
    fun statusWord(msg: ChatMessage, group: UiGroup, myHash: String, showRead: Boolean): String {
        val recipients = group.recipients(myHash)
        val status = when (msg.status) {
            Status.pending -> com.pyrochat.store.model.ChatMessage.Status.PENDING
            Status.sent -> com.pyrochat.store.model.ChatMessage.Status.SENT
            Status.delivered -> com.pyrochat.store.model.ChatMessage.Status.DELIVERED
            Status.read -> com.pyrochat.store.model.ChatMessage.Status.READ
            Status.failed -> com.pyrochat.store.model.ChatMessage.Status.FAILED
        }
        return com.pyrochat.service.GroupReceipts.aggregate(status, msg.receipts, recipients, showRead)
    }

    /** `alice, bob are typing…` (§1) — names in roster join order. */
    fun typingStrip(group: UiGroup, typing: Set<String>): String? {
        val names = group.members.sortedWith(compareBy<UiMember> { it.joinedAt }.thenBy { it.hash }).filter { it.hash in typing }.map { it.name }
        return com.pyrochat.service.GroupRules.typingStrip(names)
    }

    /** Home row subtitle for a group: `<alice> text` / `you: text` / the system row itself (§7). */
    fun previewLine(last: ChatMessage?, group: UiGroup, former: List<UiMember>, contacts: List<Contact>): String {
        if (last == null) return "channel open — no traffic"
        if (last.kind == Kind.system) return last.text
        if (last.direction == Direction.outgoing) return "you: " + Format.preview(last)
        val tag = senderTag(last, group, former, contacts)
        return "${tag.text} ${Format.preview(last)}"
    }

    /** `joined 12:04` in the members sheet. */
    fun joinedLabel(member: UiMember): String = "joined ${Format.shortClock(member.joinedAt)}"

    /** §6b confirmation title. */
    fun burnTitle(group: UiGroup): String = "BURN #${group.name}"

    const val BURN_MESSAGE = "destroys this channel on every member's phone: every message, every file, the member list. there is no undo."
    const val HONEST_CLIENT_NOTE = "honest-client limitation: a member running a modified app could keep a copy, exactly as with delete-for-both."
    const val MAX_PICKED = com.pyrochat.service.GroupRules.MAX_PICKED
    const val ERR_GROUP_FULL = com.pyrochat.service.GroupRules.ERR_GROUP_FULL
    const val ERR_ONLY_CREATOR = com.pyrochat.service.GroupRules.ERR_ONLY_CREATOR
    const val ERR_NOT_IN_GROUP = com.pyrochat.service.GroupRules.ERR_NOT_IN_GROUP
    const val ERR_LINK_DOWN = com.pyrochat.service.GroupRules.ERR_LINK_DOWN
}
