package com.pyrochat.app.backend

import com.pyrochat.mirror.MirrorContact
import com.pyrochat.mirror.MirrorDirection
import com.pyrochat.mirror.MirrorEngine
import com.pyrochat.mirror.MirrorGroup
import com.pyrochat.mirror.MirrorKind
import com.pyrochat.mirror.MirrorLink
import com.pyrochat.mirror.MirrorMember
import com.pyrochat.mirror.MirrorMessage
import com.pyrochat.mirror.MirrorStatus
import com.pyrochat.service.GroupColours
import com.pyrochat.service.ConnState as ServiceConnState
import com.pyrochat.service.ServiceError
import com.pyrochat.service.Tone as ServiceTone
import com.pyrochat.service.access.AccessKeyClient
import com.pyrochat.store.StoreError
import com.pyrochat.store.model.AppSettings as StoreSettings
import com.pyrochat.store.model.ChatMessage as StoreMessage
import com.pyrochat.store.model.Contact as StoreContact
import com.pyrochat.store.model.Group as StoreGroup
import com.pyrochat.store.model.GroupPolicy
import com.pyrochat.store.model.Member as StoreMember
import com.pyrochat.app.ui.AppSettings
import com.pyrochat.app.ui.UiGroup
import com.pyrochat.app.ui.UiMember
import com.pyrochat.app.ui.ChatMessage
import com.pyrochat.app.ui.ConnState
import com.pyrochat.app.ui.Contact
import com.pyrochat.app.ui.Direction
import com.pyrochat.app.ui.Kind
import com.pyrochat.app.ui.MirrorState
import com.pyrochat.app.ui.Status
import com.pyrochat.app.ui.Tone
import com.pyrochat.app.ui.UiNotice
import com.pyrochat.app.ui.UiUpdate
import com.pyrochat.app.ui.UiFrame
import com.pyrochat.service.CapturedFrame
import com.pyrochat.service.ChatService
import com.pyrochat.service.LatestVersion
import java.time.Instant

/**
 * Pure conversions between the persisted / service models (`core-store`, `core-service`,
 * `mirror`) and the UI models in `ui/Backend.kt`, plus the user-facing error strings iOS shows.
 * No Android classes here — every function is JVM-unit-tested (`MappingTest`).
 *
 * Times: the store keeps `Instant` (Swift `Date`), the UI wants epoch millis. Durations: the
 * store keeps Swift `TimeInterval` (`Double` seconds), the UI wants whole seconds (`Long`).
 */
object Mapping {
    // ---- store → UI -------------------------------------------------------------------

    fun message(m: StoreMessage): ChatMessage = ChatMessage(
        id = m.id,
        direction = when (m.direction) {
            StoreMessage.Direction.INCOMING -> Direction.incoming
            StoreMessage.Direction.OUTGOING -> Direction.outgoing
        },
        kind = when (m.kind) {
            StoreMessage.Kind.TEXT -> Kind.text
            StoreMessage.Kind.VOICE -> Kind.voice
            StoreMessage.Kind.IMAGE -> Kind.image
            StoreMessage.Kind.FILE -> Kind.file
            StoreMessage.Kind.SYSTEM -> Kind.system
        },
        text = m.text,
        timestamp = m.timestamp.toEpochMilli(),
        status = status(m.status),
        burnAfter = m.burnAfter?.toLong(),
        burnStartedAt = m.burnStartedAt?.toEpochMilli(),
        hasVoice = m.voiceData != null || (m.kind == StoreMessage.Kind.VOICE && m.attachmentId != null),
        voiceDuration = m.voiceDuration,
        attachmentId = m.attachmentId,
        fileName = m.fileName,
        fileMime = m.fileMime,
        fileBytes = m.fileBytes?.toLong(),
        imageWidth = m.imageWidth,
        imageHeight = m.imageHeight,
        // iOS writes `true` or omits the key (`ChatService.swift:834, 885`) — never `false`
        viaWeb = if (m.viaWeb) true else null,
        group = m.group,
        sender = m.sender,
        receipts = m.receipts,
    )

    /**
     * docs/MESSAGE-DETAILS.md §3 — the captured envelope as the details sheet sees it. A straight
     * field copy: there is nothing else in a `CapturedFrame` to copy.
     */
    fun frame(f: CapturedFrame): UiFrame =
        UiFrame(t = f.t, id = f.id, peer = f.peer, ttl = f.ttl, kind = f.kind, body = f.body, bodyBytes = f.bodyBytes)

    fun member(m: StoreMember): UiMember = UiMember(hash = m.hash, name = m.name, joinedAt = m.joinedAt.toEpochMilli(), gone = m.isGone)

    fun group(g: StoreGroup): UiGroup = UiGroup(
        id = g.id, name = g.name, creator = g.creator, createdAt = g.createdAt.toEpochMilli(), epoch = g.epoch,
        policy = g.policy, burnSeconds = g.burnSeconds?.toLong(), members = g.members.map(::member), left = g.isLeft,
        former = g.former?.map(::member) ?: emptyList(),
    )

    /** The UI's policy word → the service enum (an unknown word reads as `alert`, like the store accessor). */
    fun policy(wire: String): GroupPolicy = GroupPolicy.fromWire(wire) ?: GroupPolicy.ALERT

    fun status(s: StoreMessage.Status): Status = when (s) {
        StoreMessage.Status.PENDING -> Status.pending
        StoreMessage.Status.SENT -> Status.sent
        StoreMessage.Status.DELIVERED -> Status.delivered
        StoreMessage.Status.READ -> Status.read
        StoreMessage.Status.FAILED -> Status.failed
    }

    fun messages(all: Map<String, List<StoreMessage>>): Map<String, List<ChatMessage>> =
        all.mapValues { (_, log) -> log.map(::message) }

    fun contact(c: StoreContact): Contact = Contact(
        hash = c.hash,
        name = c.name,
        addedAt = c.addedAt.toEpochMilli(),
        verified = c.verified,
        burnSeconds = c.burnSeconds?.toLong(),
    )

    fun settings(s: StoreSettings): AppSettings = AppSettings(
        theme = s.theme,
        scanlines = s.scanlines,
        pushEnabled = s.pushEnabled,
        defaultBurnSeconds = s.defaultBurnSeconds?.toLong(),
        threadAutoBurnDays = s.threadAutoBurnDays,
        burnOnScreenshot = s.burnOnScreenshot,
        sendReadReceipts = s.sendReadReceipts,
        soundsEnabled = s.soundsEnabled,
        sendTyping = s.sendTyping,
        blockScreenshots = s.blockScreenshots,
        socksProxy = s.socksProxy,
        wipeAfterDays = s.wipeAfterDays,
        autoLockMinutes = s.autoLockMinutes,
        relayURL = s.relayURL,
        billingURL = s.billingURL,
        hideNotices = s.hideNotices,
        textSize = s.textSize,
        lastBackupAt = s.lastBackupAt?.let { (it * 1000).toLong() },   // §1: stored as seconds since 1970, epoch millis in the UI
        voiceChanger = s.voiceChanger,
    )

    /** UI → store (`updateSettings`): every key travels (the 15 iOS ones + `hideNotices`, `textSize`, `lastBackupAt`, `voiceChanger`), so nothing persisted is lost. */
    fun settings(s: AppSettings): StoreSettings = StoreSettings(
        theme = s.theme,
        scanlines = s.scanlines,
        pushEnabled = s.pushEnabled,
        defaultBurnSeconds = s.defaultBurnSeconds?.toDouble(),
        threadAutoBurnDays = s.threadAutoBurnDays,
        burnOnScreenshot = s.burnOnScreenshot,
        sendReadReceipts = s.sendReadReceipts,
        soundsEnabled = s.soundsEnabled,
        sendTyping = s.sendTyping,
        blockScreenshots = s.blockScreenshots,
        socksProxy = s.socksProxy,
        wipeAfterDays = s.wipeAfterDays,
        autoLockMinutes = s.autoLockMinutes,
        relayURL = s.relayURL,
        billingURL = s.billingURL,
        hideNotices = s.hideNotices,
        textSize = s.textSize,
        lastBackupAt = s.lastBackupAt?.let { it / 1000.0 },
        voiceChanger = s.voiceChanger,
    )

    /** `ChatService.Notice` → the `<pyrochat>` channel row (epoch millis). */
    fun notice(n: ChatService.Notice): UiNotice =
        UiNotice(id = n.id, text = n.text, url = n.url, ts = n.ts.toEpochMilli(), expires = n.expires.toEpochMilli())

    /** `LatestVersion` → the home screen's `▲ update available` row (a straight field copy). */
    fun update(v: LatestVersion): UiUpdate = UiUpdate(build = v.build, name = v.name, url = v.url)

    fun connState(s: ServiceConnState): ConnState = when (s) {
        ServiceConnState.OFFLINE -> ConnState.offline
        ServiceConnState.CONNECTING -> ConnState.connecting
        ServiceConnState.AUTHENTICATING -> ConnState.authenticating
        ServiceConnState.ONLINE -> ConnState.online
    }

    fun tone(t: ServiceTone): Tone = when (t) {
        ServiceTone.TX -> Tone.tx
        ServiceTone.RX -> Tone.rx
    }

    /**
     * `MirrorEngine.State` → the settings/link screens' view of it. `Joining` already shows the
     * LINKED pane with `code <sas>` (iOS `linked = true` right after `startMirror`); `Closed`
     * reads as idle (iOS `mirror = nil` after a stop); `Failed` keeps its text so the hijack
     * warning is displayed (the deliberate deviation documented in `MirrorEngine.stopOnSerial`).
     */
    fun mirrorState(s: MirrorEngine.State, now: () -> Long = System::currentTimeMillis): MirrorState = when (s) {
        MirrorEngine.State.Idle, is MirrorEngine.State.Closed -> MirrorState.Idle
        is MirrorEngine.State.Joining -> MirrorState.Linked(since = now(), browserName = null, sas = s.sas)
        is MirrorEngine.State.Linked -> MirrorState.Linked(since = s.since, browserName = s.browserName, sas = s.sas)
        is MirrorEngine.State.Failed -> MirrorState.Failed(s.message)
    }

    // ---- service → mirror -------------------------------------------------------------

    fun mirrorContact(c: StoreContact): MirrorContact =
        MirrorContact(hash = c.hash, name = c.name, verified = c.verified, addedAt = c.addedAt.toEpochMilli())

    /**
     * `Group` → what the browser sees (docs/MIRROR-GROUPS.md §1): the roster as stored (iOS
     * `groupJSON` maps `g.members` in order), each member with the phone's own colour —
     * `GroupColours.hex(joinIndex)` over members ∪ former (GROUPS.md §5), so a departed member
     * keeps her slot and nobody recolours. `joinedAt` and `former` ride along unserialized: the
     * engine orders `who` by join and resolves `sender` on history rows from departed members.
     */
    fun mirrorGroup(g: StoreGroup): MirrorGroup {
        val roster = g.colourRoster
        fun member(m: StoreMember) = MirrorMember(
            hash = m.hash, name = m.name, gone = m.isGone, joinedAt = m.joinedAt.toEpochMilli(),
            color = GroupColours.hex(GroupColours.joinIndex(roster, m.hash) ?: 0),
        )
        return MirrorGroup(
            id = g.id, name = g.name, policy = g.policy, createdAt = g.createdAt.toEpochMilli(), left = g.isLeft,
            members = g.members.map(::member),
            former = (g.former ?: emptyList()).map(::member),
        )
    }

    fun mirrorMessage(m: StoreMessage): MirrorMessage = MirrorMessage(
        id = m.id,
        direction = when (m.direction) {
            StoreMessage.Direction.INCOMING -> MirrorDirection.incoming
            StoreMessage.Direction.OUTGOING -> MirrorDirection.outgoing
        },
        kind = when (m.kind) {
            StoreMessage.Kind.TEXT -> MirrorKind.text
            StoreMessage.Kind.VOICE -> MirrorKind.voice
            StoreMessage.Kind.IMAGE -> MirrorKind.image
            StoreMessage.Kind.FILE -> MirrorKind.file
            StoreMessage.Kind.SYSTEM -> MirrorKind.system
        },
        text = m.text,
        timestamp = m.timestamp.toEpochMilli(),
        status = when (m.status) {
            StoreMessage.Status.PENDING -> MirrorStatus.pending
            StoreMessage.Status.SENT -> MirrorStatus.sent
            StoreMessage.Status.DELIVERED -> MirrorStatus.delivered
            StoreMessage.Status.READ -> MirrorStatus.read
            StoreMessage.Status.FAILED -> MirrorStatus.failed
        },
        isBurn = m.burnAfter != null,
        viaWeb = m.viaWeb,
        imageWidth = m.imageWidth,
        imageHeight = m.imageHeight,
        fileName = m.fileName,
        fileBytes = m.fileBytes?.toLong(),
        fileMime = m.fileMime,
        voiceDuration = m.voiceDuration,
        senderHash = m.sender,
        receipts = m.receipts,
    )

    // ---- error strings (verbatim iOS) ---------------------------------------------------

    /** `AppModel.submitAuth`'s catch clauses (`PyroChatApp.swift:243-247`). */
    const val ERR_WRONG_PASSWORD = "decrypt failed — wrong password"

    /** The duress cover story (`PyroChatApp.swift:194`). */
    const val ERR_DURESS_COVER = "store corrupt — no identity found"

    /**
     * `AppModel.submitRestore` (`PyroChatApp.swift:143`).
     *
     * NO LONGER REACHABLE on Android: docs/DECOY-ACCOUNT.md §5 made the auth screen stop asserting
     * what the device holds, and this string is that assertion. [ERR_NO_ROOM] replaced it. Kept as
     * the iOS-parity record of what the text used to be, so a future port does not re-introduce it
     * by accident.
     */
    const val ERR_IDENTITY_EXISTS = "an identity already exists on this device — burn it first"

    /** `AppModel.submitRestore` (`PyroChatApp.swift:148`). */
    const val ERR_BACKUP_NO_IDENTITY = "backup contains no identity"

    // ---- docs/DECOY-ACCOUNT.md §3 ----------------------------------------------------------

    /**
     * §3: "a password, which must not be the real one (rejected, with the ordinary wrong-value
     * wording)". The ordinary wording, and nothing about slots, accounts or what else exists.
     */
    const val ERR_DECOY_SAME_PASSWORD = "that is this account's password — choose a different one"

    /**
     * §1/§3: there is no slot this account knows to be free. The ordinary words for a thing that
     * did not work, identical whether that is because a decoy already exists or because this IS
     * the decoy — the person holding the phone sees one session and has nothing to compare it to.
     */
    const val ERR_DECOY_NO_ROOM = "could not set a decoy password"

    /**
     * docs/DECOY-ACCOUNT.md §5 — creating or restoring when the app knows of no free slot. It says
     * what happened and nothing else: not that an identity exists, not that one does not. The old
     * [ERR_IDENTITY_EXISTS] was exactly the assertion §5 removed from this screen.
     */
    const val ERR_NO_ROOM = "could not create an identity on this device"

    /**
     * §1, "never trim without being asked" — this account never became a container, so there is
     * nowhere to put a second one. Nothing is offered, and nothing is destroyed.
     */
    const val ERR_DECOY_STORE_TOO_LARGE = "could not set a decoy password — this account is too large"

    /** `LinkDesktopView.handle` (`LinkDesktopView.swift:133-149`) — public build texts. */
    const val ERR_LINK_CONTACT_CODE = "that's a CONTACT code — scan it from [qr] on the home screen"
    const val ERR_LINK_WRONG_RELAY = "this code belongs to a different server — open pyrochat.ca/link and scan that page instead."
    const val ERR_LINK_MALFORMED = "unreadable link QR — reload the browser page"

    /**
     * Swift `"\(error)"`: a `ServiceError.relay(code)` prints the bare code; anything else its
     * description. Used for registration failures and `addContact` (`could not open <x>: …`).
     */
    fun errorText(e: Throwable): String = when (e) {
        is ServiceError -> e.code
        is AccessKeyClient.RedeemError -> e.message ?: e.code
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.toString()
    }

    /** `submitAuth` unlock path: bad password AND a corrupt blob share the wrong-password text (SPEC.md §5). */
    fun unlockErrorText(e: Throwable): String = when (e) {
        is StoreError.BadPassword, is StoreError.Corrupt -> ERR_WRONG_PASSWORD
        else -> errorText(e)
    }

    /** `submitRestore`: the `BackupError.errorDescription` texts, else `localizedDescription`. */
    fun restoreErrorText(e: Throwable): String = when (e) {
        is StoreError.BadBackupFile -> "not a pyrochat backup file"
        is StoreError.BadBackupPassphrase -> "wrong backup passphrase"
        else -> errorText(e)
    }

    /**
     * `LinkDesktopView.handle`: null = keep scanning silently (someone's lunch QR); otherwise
     * the text shown after `! `. [raw] is needed for the pyro-add hint.
     */
    fun linkParseErrorText(raw: String, e: Throwable): String? = when (e) {
        is MirrorLink.ParseError.NotPyroLink -> if (raw.trim().startsWith("pyro-add:")) ERR_LINK_CONTACT_CODE else null
        is MirrorLink.ParseError.WrongRelay -> ERR_LINK_WRONG_RELAY
        is MirrorLink.ParseError.Malformed -> ERR_LINK_MALFORMED
        else -> "link failed: ${e.message ?: e}"
    }

    /** `SettingsView` export filename: `pyrochat-<yyyyMMdd-HHmm>.pyrobak` (`SettingsView.swift:267-270`). */
    fun backupFileName(stamp: String): String = "pyrochat-$stamp.pyrobak"

    fun instant(epochMillis: Long): Instant = Instant.ofEpochMilli(epochMillis)
}
