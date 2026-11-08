package com.pyrochat.app.ui

import com.pyrochat.app.platform.BiometricKind
import com.pyrochat.app.platform.BiometricPrompter
import com.pyrochat.app.platform.VoiceEffect
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// ---------------------------------------------------------------------------
// UI-facing models. Names and cases mirror `ios/PyroChat/Core/Models.swift`
// so the real adapter over core-service is a straight field copy. Times are
// epoch millis here (the store codec owns the Swift 2001-epoch conversion).
// ---------------------------------------------------------------------------

/** `ChatMessage.Direction` */
enum class Direction { incoming, outgoing }

/** `ChatMessage.Kind` */
enum class Kind { text, voice, image, file, system }

/** `ChatMessage.Status` */
enum class Status { pending, sent, delivered, read, failed }

/** `ChatMessage` — one log row. */
data class ChatMessage(
    val id: String,
    val direction: Direction,
    val kind: Kind,
    val text: String,
    /** epoch millis (`timestamp: Date`) */
    val timestamp: Long,
    val status: Status,
    /** seconds after first read; null = keep (`burnAfter`) */
    val burnAfter: Long? = null,
    /** epoch millis; null until the SYNCED countdown starts (`burnStartedAt`) */
    val burnStartedAt: Long? = null,
    /** the row has playable audio — a sidecar, or pre-§1a inline `voiceData`; the bytes stay in the service layer */
    val hasVoice: Boolean = false,
    val voiceDuration: Double? = null,
    val attachmentId: String? = null,
    val fileName: String? = null,
    val fileMime: String? = null,
    /** plaintext size for the size label */
    val fileBytes: Long? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    /** composed on a linked desktop browser → `[web]` tag */
    val viaWeb: Boolean? = null,
    /** group id — the row's thread key — for a group row, null in 1:1 (`ChatMessage.group`) */
    val group: String? = null,
    /** sender hash on an incoming group row (`ChatMessage.sender`) */
    val sender: String? = null,
    /** outgoing group rows: member hash → `"delivered"` | `"read"` (`ChatMessage.receipts`) */
    val receipts: Map<String, String>? = null,
) {
    /** `burnDeadline` — epoch millis, null while the timer is armed but not started. */
    val burnDeadline: Long?
        get() = if (burnAfter != null && burnStartedAt != null) burnStartedAt + burnAfter * 1000 else null
}

/** `Contact` */
data class Contact(
    /** sha256(username) hex — the routing address */
    val hash: String,
    val name: String,
    val addedAt: Long,
    val verified: Boolean = false,
    /** per-conversation self-destruct: null = global default, 0 = explicit off */
    val burnSeconds: Long? = null,
)

/** `Member` (docs/GROUPS.md §3) — one roster entry; `joinedAt` epoch millis; `gone` = the relay said `unknown_user`. */
data class UiMember(val hash: String, val name: String, val joinedAt: Long, val gone: Boolean = false)

/**
 * `Group` (docs/GROUPS.md §3). `name` without the `#`; `policy` is the wire word (`alert` /
 * `burn` / `burn_remove`); `burnSeconds` null = global default, 0 = off; `left` = this phone was
 * removed or left (read-only thread).
 */
data class UiGroup(
    val id: String,
    val name: String,
    val creator: String,
    val createdAt: Long,
    val epoch: Int,
    val policy: String,
    val burnSeconds: Long? = null,
    val members: List<UiMember>,
    val left: Boolean = false,
    /** removed/left members with their original `joinedAt` (`Group.former`) — colour slots never shift */
    val former: List<UiMember> = emptyList(),
) {
    fun member(hash: String): UiMember? = members.firstOrNull { it.hash == hash }
    fun isMember(hash: String): Boolean = members.any { it.hash == hash }
    fun formerMember(hash: String): UiMember? = former.firstOrNull { it.hash == hash }
    /** §5: members ∪ former, earliest join per hash — the roster colours are dealt from. */
    val colourRoster: List<UiMember> get() = (members + former).groupBy { it.hash }.map { (_, v) -> v.minBy { it.joinedAt } }
    /** Members a fan-out addresses: everyone but me and the gone. */
    fun recipients(myHash: String): Int = members.count { it.hash != myHash && !it.gone }
    /** `#name · N` — the home row and chat header (§7). */
    val title: String get() = "#$name · ${members.size}"
}

/**
 * `ChatService.Notice` — an operator notice: the relay's own announcement, shown as the pinned
 * `<pyrochat>` channel. Times are epoch millis here (the wire carries seconds).
 */
data class UiNotice(
    val id: String,
    val text: String,
    /** always `https://` when present */
    val url: String?,
    val ts: Long,
    val expires: Long,
)

/**
 * `LatestVersion` — the newer build the relay advertises for this platform (docs/PROTOCOL.md
 * §"Latest versions"): the home screen's `▲ update available: name (build)` row. Present only
 * when `build` is greater than the running `versionCode` (the service applies the rule).
 */
data class UiUpdate(
    /** Play `versionCode` of the advertised build. */
    val build: Int,
    /** the marketing version, display only (≤ 32 chars). */
    val name: String,
    /** always `https://` when present — the row is tappable (opens the browser) only then. */
    val url: String?,
)

/** `AppSettings` — persisted inside the encrypted blob under `settings`. */
data class AppSettings(
    val theme: String = "ember",
    val scanlines: Boolean = true,
    val pushEnabled: Boolean = false,
    val defaultBurnSeconds: Long? = null,
    val threadAutoBurnDays: Int? = null,
    val burnOnScreenshot: Boolean = false,
    val sendReadReceipts: Boolean? = null,
    val soundsEnabled: Boolean? = null,
    val sendTyping: Boolean? = null,
    val blockScreenshots: Boolean? = null,
    val socksProxy: String? = null,
    val wipeAfterDays: Int? = null,
    /** null = default (5), 0 = off */
    val autoLockMinutes: Int? = null,
    val relayURL: String = DEFAULT_RELAY_URL,
    val billingURL: String = DEFAULT_BILLING_URL,
    /** null = show the `<pyrochat>` notices channel (`[cfg]` → `system notices`); a v0.5 store key, identical on iOS. */
    val hideNotices: Boolean? = null,
    /** docs/TEXT-SIZE.md §1 — `[cfg]` → `text size`; null (or an unknown word) reads as `normal`. */
    val textSize: String? = null,
    /** docs/STATUS-AND-CFG.md §1 — epoch millis of the last backup this account exported; null = never. */
    val lastBackupAt: Long? = null,
    /** docs/VOICE-CHANGER.md: the voice a voice note is sent in (`deep`, `high`, `robot`); null = as spoken. */
    val voiceChanger: String? = null,
) {
    /** The voice notes go out in. A word this build does not know is still a disguise. */
    val voice: VoiceEffect get() = VoiceEffect.of(voiceChanger)
    val readReceiptsOn: Boolean get() = sendReadReceipts ?: false
    val noticesOn: Boolean get() = hideNotices != true
    val soundsOn: Boolean get() = soundsEnabled ?: true
    val typingOn: Boolean get() = sendTyping ?: true
    val screenshotBlockOn: Boolean get() = blockScreenshots ?: true
    val isAmber: Boolean get() = theme == "amber"

    companion object {
        const val DEFAULT_RELAY_URL = "wss://relay.pyrochat.ca/ws"
        const val DEFAULT_BILLING_URL = "https://pyrochat.ca"
    }
}

/** `ChatService.ConnState` */
enum class ConnState { offline, connecting, authenticating, online }

/** `AppModel.Stage` + `AppModel.authMode` folded into one value. */
sealed interface Stage {
    data object Boot : Stage
    /**
     * docs/DECOY-ACCOUNT.md §5 — the auth screen, and it no longer claims to know anything.
     *
     * A container exists from first run and no path ever deletes it, so "no identity on this
     * device yet" and "an identity lives on this device" are both false statements: with a
     * container present and no password typed the app genuinely cannot tell. It offers unlocking
     * and creating and asserts neither, which is the same property a wrong password already had,
     * extended from one moment to the whole surface.
     */
    data object Auth : Stage
    data object Main : Stage
}

/** `MirrorEngine.state` as the settings screen needs it. */
sealed interface MirrorState {
    data object Idle : MirrorState
    data class Linked(val since: Long, val browserName: String?, val sas: String) : MirrorState
    /** `MirrorEngine.State.failed(message)` — e.g. the hijack guard; shown on the link screen, idle elsewhere. */
    data class Failed(val message: String) : MirrorState
}

/**
 * docs/MESSAGE-DETAILS.md §3 — one relay envelope as it crossed the wire, captured in RAM as it
 * was sent or as it arrived (`CapturedFrame` in core-service). The six fields ARE the frame: no
 * key material, no session, no plaintext can ride along, and [body] is the base64 ciphertext the
 * relay was handed — never something this app re-encrypted to draw a screen.
 */
data class UiFrame(
    /** `send`, `deliver` (a sealed message, docs/SEALED-SENDER.md §4.2) or `recv`. */
    val t: String,
    /** the relay frame id */
    val id: String,
    /** `to` on a send or a deliver, `from` on a recv — the 64-hex routing address. Empty on a sealed recv, which names no sender. */
    val peer: String,
    /** seconds; sends only */
    val ttl: Int?,
    /** `msg` | `blob` | `control` */
    val kind: String,
    /** base64 of the E2E envelope */
    val body: String,
    /** plain bytes behind [body] — `N bytes of ciphertext` */
    val bodyBytes: Int,
) {
    val outgoing: Boolean get() = t == "send" || t == "deliver"
}

/**
 * docs/SCRUB-AND-WIPE.md §B2 — one field the scrubber took out of an attachment before it was
 * encrypted. [label] is lower-case and comes from a closed vocabulary; [value] is shown as it was
 * found. Never the file's contents: only a field name and the field's own value.
 */
data class UiRemovedField(val label: String, val value: String)

/**
 * docs/SCRUB-AND-WIPE.md §B2 — the receipt for one attachment. Held in RAM beside the captured
 * envelope ([UiFrame]) and for the same reason: it describes something that happened on the wire
 * once, it is not part of the message, and a locked or restarted app has honestly forgotten it.
 *
 * [supported] false is the honest line: this file type is not one we can clean, and the bytes went
 * out exactly as they came in.
 */
data class UiScrubReceipt(
    val fileName: String,
    val removed: List<UiRemovedField>,
    val supported: Boolean,
) {
    /** §B2: a section is worth printing when something went, or when we could not clean at all. */
    val hasReceipt: Boolean get() = removed.isNotEmpty() || !supported
}

/** `ChatService.RxTransfer` — an incoming blob still being reassembled. */
data class RxTransfer(val id: String, val thread: String, val subtype: String, val received: Long, val total: Long)

/** `SoundPlayer.Tone` — the service asks the UI shell to blip. */
enum class Tone { tx, rx }

/** Thrown by actions that iOS models as `throws` (relay error codes are the message). */
class BackendException(message: String) : Exception(message)

// ---------------------------------------------------------------------------
// Unlock with fingerprint / face — docs/BIOMETRIC-UNLOCK.md. OFF by default. The password
// remains the only thing that derives the store key; biometry merely unwraps a copy of that key
// held by the phone's secure hardware.
// ---------------------------------------------------------------------------

/** §3 — what the unlock screen may offer when it appears. */
sealed interface BiometricGate {
    /** No hardware, nothing enrolled, or the feature is off: the screen is exactly as it always was. */
    data object Off : BiometricGate

    /** The `[ UNLOCK WITH … ]` button, tried once automatically. [kind] picks its words. */
    data class Ready(val kind: BiometricKind) : BiometricGate

    /** §4: `── password required ──`, and no biometric button that time. */
    data object PasswordRequired : BiometricGate
}

/** §3 — the outcome of one biometric unlock attempt. */
sealed interface BiometricOutcome {
    /** Unlocked; [Backend.stage] has already moved to [Stage.Main]. */
    data object Ok : BiometricOutcome

    /** The user cancelled: show nothing, the password field is right there. */
    data object Cancelled : BiometricOutcome

    /** `! not recognised — try again or type your password` */
    data object NotRecognised : BiometricOutcome

    /** `── biometrics changed: type your password once ──` — the item is gone, the feature is off. */
    data object Invalidated : BiometricOutcome

    /** §4: the third consecutive failure landed — `── password required ──`. */
    data object PasswordRequired : BiometricOutcome

    /** Anything else (the store would not open); shown as `! <message>`. */
    data class Failed(val message: String) : BiometricOutcome
}

/** §2 — turning the `[cfg]` toggle ON. */
/**
 * docs/DECOY-ACCOUNT.md §1, "A store that still will not fit: never trim without being asked" —
 * this account holds more text than a container slot can, so it was left in its legacy shape.
 * Nothing was written, nothing was deleted, everything still works; it simply does not gain a
 * decoy until the user decides what to give up. The interface says so in one line and offers
 * nothing destructive.
 */
data class UiStoreTooLarge(val jsonBytes: Int, val capacityBytes: Int)

sealed interface BiometricEnable {
    data object Ok : BiometricEnable

    /** Cancelled or not recognised: the toggle stays off and nothing changed. */
    data object Refused : BiometricEnable

    data class Failed(val message: String) : BiometricEnable
}

/**
 * Everything the Compose shell needs from the service layer. Each member names
 * its iOS counterpart (`AppModel` in `App/PyroChatApp.swift`, `ChatService` in
 * `Core/ChatService.swift`, `SecureStore` in `Core/SecureStore.swift`) so the
 * core-service adapter is a thin forwarding layer.
 */
interface Backend {
    // ---- observable state -------------------------------------------------
    /** `AppModel.stage` (+ `authMode`). */
    val stage: StateFlow<Stage>
    /** `service.store.blob.settings` (or `AppModel.bootSettings` pre-auth — same theme). */
    val settings: StateFlow<AppSettings>
    /** `ChatService.connState` */
    val connState: StateFlow<ConnState>
    /** `ChatService.sealedStatus`: whether the relay is being told who sends (docs/SEALED-SENDER.md §5.8). */
    val senderHidden: StateFlow<SenderHidden>
    /** `ChatService.contacts` — insertion order, blocked ones included (Home filters). */
    val contacts: StateFlow<List<Contact>>
    /** `ChatService.messages` keyed by contact hash. */
    val messages: StateFlow<Map<String, List<ChatMessage>>>
    /** `ChatService.typingPeers.keys` — hashes whose typing signal has not expired. */
    val typingPeers: StateFlow<Set<String>>
    /** docs/VOICE-CHANGER.md — threads in which a note that was sent is still being changed into its voice: it has no row yet. */
    val voiceBeingChanged: StateFlow<Set<String>>
    /** `ChatService.blobProgress` — outgoing blob id → 0..1 */
    val blobProgress: StateFlow<Map<String, Float>>
    /** `ChatService.rxTransfers` */
    val rxTransfers: StateFlow<List<RxTransfer>>
    /** `ChatService.blockedHashes` */
    val blockedHashes: StateFlow<Set<String>>
    /** `ChatService.mirror?.state` */
    val mirror: StateFlow<MirrorState>
    /** `ChatService.lastError` */
    val lastError: StateFlow<String?>
    /** `ChatService.notices` — active operator notices, oldest first (the `<pyrochat>` channel). */
    val notices: StateFlow<List<UiNotice>>
    /** `ChatService.unseenNoticeCount` — the `<pyrochat>` row's `[N]` marker. */
    val unseenNotices: StateFlow<Int>
    /** `ChatService.updateAvailable` — the `▲ update available` row under the link line; null when this install is current. */
    val updateAvailable: StateFlow<UiUpdate?>
    /** `SettingsView.backupStatus` after the system save picker closes: "✓ backup saved" / "export cancelled" (null = nothing new). */
    val exportStatus: StateFlow<String?>
    /** `ChatService.accessDaysRemaining` */
    val accessDaysRemaining: StateFlow<Int>
    /** `SettingsView.duressSet` (session-local on iOS; the store knows on Android). */
    val duressPinSet: StateFlow<Boolean>
    /** `SoundPlayer.play(.tx/.rx)` requests. */
    val tones: SharedFlow<Tone>
    /** `ChatService.myUsername` */
    val myUsername: String
    /** `ChatService.myHash` */
    val myHash: String

    // ---- group channels (docs/GROUPS.md) ------------------------------------------
    /** `ChatService.groups` — every group this phone knows, `left` ones included. */
    val groups: StateFlow<List<UiGroup>>
    /** `ChatService.groupTyping` — group id → member hashes whose typing signal has not expired. */
    val groupTyping: StateFlow<Map<String, Set<String>>>
    /** `ChatService.goneMembers` — group id → members who deleted their account (still in the roster, dim). */
    val goneMembers: StateFlow<Map<String, Set<String>>>
    /** `ChatService.formerMembers` — group id → members removed this session (names + colour slots for old rows). */
    val formerMembers: StateFlow<Map<String, List<UiMember>>>
    /** A transient home-screen flash (`#name was burned by the creator`); null when nothing to show. */
    val flash: StateFlow<String?>
    /** `ChatService.createGroup(name, members, policy, burnSeconds)` — returns the new group id; throws [BackendException] (verbatim §7 texts). */
    suspend fun createGroup(name: String, members: List<String>, policy: String, burnSeconds: Long?): String
    /** `ChatService.addMember` — throws [BackendException] (`ERR: group is full (20)`, `ERR: you are no longer in this group`). */
    suspend fun addMember(groupId: String, hash: String)
    /** `ChatService.removeMember` — creator only; throws [BackendException] (`ERR: only the creator can remove members`). */
    suspend fun removeMember(groupId: String, hash: String)
    /** `ChatService.leaveGroup` */
    suspend fun leaveGroup(groupId: String)
    /** `ChatService.renameGroup` — creator only. */
    suspend fun renameGroup(groupId: String, name: String)
    /** `ChatService.setGroupPolicy` — creator only; `policy` is the wire word. */
    suspend fun setGroupPolicy(groupId: String, policy: String, burnSeconds: Long?)
    /** `ChatService.burnGroup` — §6b, creator only. */
    suspend fun burnGroup(groupId: String)
    /** `ChatService.deleteGroup` — drop the group from this phone (leaves first when still a member). */
    fun deleteGroup(groupId: String)

    // ---- lifecycle ----------------------------------------------------------
    /** `AppModel.finishBoot()` */
    fun finishBoot()
    /** `AppModel.submitAuth(username:password:relay:)` in create mode. Error string or null. */
    suspend fun createAccount(username: String, password: String, relay: String): String?
    /** `AppModel.submitAuth` in unlock mode (duress PIN checked first). Error string or null. */
    suspend fun unlock(password: String): String?
    /** `AppModel.submitRestore(backup:passphrase:newPassword:)`. Error string or null. */
    suspend fun restoreBackup(backup: ByteArray, passphrase: String, newPassword: String): String?
    /** `AppModel.lock()` — auto-lock teardown back to the unlock prompt. */
    fun lock()
    /** `ChatService.goOnline()` */
    suspend fun goOnline()
    /** `ChatService.goOffline()` */
    fun goOffline()
    /** SettingsView TOTAL WIPE: stopMirror + deregister + detach + obliterate (+ relaunch as fresh install). */
    suspend fun totalWipe()

    // ---- contacts -------------------------------------------------------------
    /** `ChatService.addContact(username:)` — throws [BackendException] with the relay code. */
    suspend fun addContact(username: String)
    /**
     * `ChatService.addContactViaQR(username:identityKey:)` — adds AND verifies in one step; true = the
     * key matched (VERIFIED ✓). Throws [BackendException] with the code (`qr_key_mismatch`,
     * `identity_mismatch`, `bad_username`, `bad_key`, `cannot_add_self`, relay codes).
     */
    suspend fun addContactViaQR(username: String, identityKey: ByteArray): Boolean
    /** `ChatService.myAddQRPayload()` — `pyro-add:v1:<username>:<b64url(identity key)>`, null before an identity exists. */
    fun myAddQRPayload(): String?
    /** `ChatService.renameContact(_:to:)` */
    fun renameContact(hash: String, name: String)
    /** `ChatService.deleteContact(_:)` */
    fun deleteContact(hash: String)
    /** `ChatService.blockContact(_:)` */
    fun blockContact(hash: String)
    /** `ChatService.unblockContact(_:)` */
    fun unblockContact(hash: String)
    /** `ChatService.reportAndBlock(_:)` */
    suspend fun reportAndBlock(hash: String)
    /** `ChatService.setVerified(_:for:)` */
    fun setVerified(verified: Boolean, hash: String)
    /** `ChatService.safetyNumber(with:)?.digits` — 60 digits, null before a key is known. */
    fun safetyNumber(hash: String): String?
    /** `ChatService.safetyNumber(with:)?.scannable` — the libsignal scannable fingerprint bytes the VerifyView QR encodes. */
    fun safetyNumberQR(hash: String): ByteArray?
    /** `ChatService.setBurnDefault(_:for:)` — null = global default, 0 = off. */
    fun setBurnDefault(seconds: Long?, hash: String)

    // ---- messages ---------------------------------------------------------------
    /** `ChatService.sendText(_:to:)` */
    suspend fun sendText(text: String, hash: String)
    /** `ChatService.sendImage(jpeg:width:height:to:)`; [scrub] is §B2's receipt for the row it creates. */
    suspend fun sendImage(jpeg: ByteArray, width: Int, height: Int, hash: String, scrub: UiScrubReceipt? = null)
    /** `ChatService.sendFile(data:filename:mime:to:)`; [scrub] is §B2's receipt for the row it creates. */
    suspend fun sendFile(data: ByteArray, filename: String, mime: String, hash: String, scrub: UiScrubReceipt? = null)
    /** `ChatService.sendVoice(_:duration:to:)` */
    suspend fun sendVoice(audio: ByteArray, duration: Double, hash: String)
    /** `ChatService.retryText` / `retryBlob` (picked by kind). */
    suspend fun retry(messageId: String, hash: String)
    /** `ChatService.deleteMessage(id:thread:)` — this device only. */
    fun deleteMessage(messageId: String, hash: String)
    /** `ChatService.deleteForBoth(id:thread:)` — false = not retracted (see [lastError]). */
    suspend fun deleteForBoth(messageId: String, hash: String): Boolean
    /** `ChatService.burnThread(_:)` */
    suspend fun burnThread(hash: String)
    /** `ChatService.attachmentData(for:)` — decrypted image/file bytes, null if burned. */
    fun attachmentData(message: ChatMessage): ByteArray?
    /** `ChatService.voiceContent(for:)` — a voice row's AAC bytes for `VoicePlayer`, null if none. */
    fun voiceData(message: ChatMessage): ByteArray?
    /** `Waveform.bars(for:data:)` — 32 RMS bars in 0..1 for a voice row. */
    fun voiceBars(message: ChatMessage): FloatArray
    /**
     * docs/MESSAGE-DETAILS.md §3 — the envelope this row crossed the wire in, or null when there
     * is no capture (an older message, or the app has restarted). Never computed: if nothing was
     * captured there is no ciphertext, and the sheet says so.
     */
    fun capturedFrame(messageId: String): UiFrame?

    /**
     * docs/SCRUB-AND-WIPE.md §B2 — what the scrubber took out of this row's attachment before it
     * was sent, or null when there is nothing on record: a text row, a received row, an older row,
     * or an app that has restarted since. Never computed after the fact — §B2's rule is that a
     * file is never claimed to have been cleaned when it was not.
     */
    fun scrubReceipt(messageId: String): UiScrubReceipt?
    /** `ChatService.setActiveThread(_:)` — null on leave. */
    fun setActiveThread(hash: String?)
    /** `ChatService.markNoticesSeen()` — opening the `<pyrochat>` channel reads every notice. */
    fun markNoticesSeen()
    /** `ChatService.needsVisibilityReport(_:)` */
    fun needsVisibilityReport(message: ChatMessage): Boolean
    /** `ChatService.noteRowVisible(messageID:thread:)` — read receipt + SYNCED burn start. */
    fun noteRowVisible(messageId: String, hash: String)
    /** `ChatService.noteComposerActivity(in:)` — typing signal (throttled inside). */
    fun noteComposerActivity(hash: String)
    /** `ChatService.draft(for:)` */
    fun draft(hash: String): String
    /** `ChatService.setDraft(_:for:)` — docs/DECOY-ACCOUNT.md §1: lazy, so a keystroke writes nothing. */
    fun setDraft(text: String, hash: String)

    /**
     * docs/DECOY-ACCOUNT.md §1 — write whatever the lazy lane has been holding: the composer has
     * lost focus, the screen is being left, the app is backgrounding. A store with nothing pending
     * writes nothing at all, so calling it is always safe and usually free.
     */
    fun persistDeferred()

    // ---- settings ---------------------------------------------------------------
    /** `service.store.update { $0.settings = … }` */
    fun updateSettings(transform: (AppSettings) -> AppSettings)
    /** `SecureStore.setDuressPin(_:)` */
    fun setDuressPin(pin: String)
    /** `ChatService.redeemAccessKey(_:)` — throws [BackendException] with the user-facing text. */
    suspend fun redeemAccessKey(code: String)
    /** `BackupService.export(from:passphrase:)` — returns the backup size in bytes. */
    suspend fun exportBackup(passphrase: String): Int
    /** `MirrorLink.parse` + `ChatService.startMirror(_:)` — error text, or null when linking started. */
    fun startMirror(rawCode: String): String?
    /** `ChatService.stopMirror()` */
    fun stopMirror()
    /** `ChatService.sendPushToken(nil)` after the wake-push toggle (registers/unregisters). */
    suspend fun syncPushToken()

    // ---- unlock with fingerprint / face (docs/BIOMETRIC-UNLOCK.md) ---------------
    /**
     * §2: which words the `[cfg]` row and the unlock button wear — null hides the row entirely
     * (no biometric hardware, or nothing enrolled). Also the moment biometry leaving the device is
     * noticed, so the stored key goes with it (§5).
     */
    fun biometricKind(): BiometricKind?

    /** §2: the toggle's state — derived from whether the stored key exists, never from the blob. */
    val biometricEnabled: StateFlow<Boolean>

    /**
     * docs/SCRUB-AND-WIPE.md §A — consecutive wrong TYPED passwords, read back from the plain
     * preferences file at launch so it survives restarts. The unlock screen hands it to
     * `UnlockFailurePolicy.decide`; it never decides anything itself.
     *
     * A biometric no-match never appears here (that has its own counter), and neither does the
     * duress PIN. On the tenth the device has already wiped and this is back at zero.
     */
    val unlockFailures: StateFlow<Int>

    /**
     * docs/DECOY-ACCOUNT.md §1 — non-null when this account was too large to migrate into a
     * container. `[cfg]` shows it as one line.
     */
    val storeTooLarge: StateFlow<UiStoreTooLarge?>

    /**
     * docs/DECOY-ACCOUNT.md §3 `set decoy password …` — register [username] on the relay and give
     * it [password], as a genuine second account in a slot this one knows is free. null on
     * success; otherwise the app's ordinary words for a thing that did not work, which is also
     * what a decoy gets when it tries (§1: the first account can create one decoy, a decoy cannot
     * create another).
     */
    suspend fun setDecoyPassword(username: String, password: String): String?

    /**
     * docs/DECOY-ACCOUNT.md §3 — whether this account already owns a decoy, so the dialog can say
     * plainly that it is REPLACING one. Read only after the row is tapped: the row itself renders
     * identically everywhere, and only the account that made a decoy can answer this at all.
     */
    fun decoyExists(): Boolean

    /** §2 ON: prompt biometry once to confirm, then store the key. A refused prompt changes nothing. */
    suspend fun enableBiometricUnlock(prompter: BiometricPrompter): BiometricEnable

    /** §2 OFF: delete the stored key immediately. */
    fun disableBiometricUnlock()

    /** §3/§4: what the unlock screen may offer right now — asked before anything is decrypted. */
    fun biometricGate(): BiometricGate

    /** §3: prompt, unwrap the store key, open the store. */
    suspend fun unlockWithBiometrics(prompter: BiometricPrompter): BiometricOutcome
}
