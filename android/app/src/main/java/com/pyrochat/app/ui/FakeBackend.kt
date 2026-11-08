package com.pyrochat.app.ui

import android.content.Context
import com.pyrochat.app.backend.Mapping
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import com.pyrochat.app.platform.BiometricAuth
import com.pyrochat.app.platform.BiometricDecision
import com.pyrochat.app.platform.BiometricKind
import com.pyrochat.app.platform.BiometricPolicy
import com.pyrochat.app.platform.BiometricPrompter
import com.pyrochat.app.platform.BiometricVault
import com.pyrochat.app.platform.Biometrics
import com.pyrochat.app.platform.KeystoreBiometricKeys
import com.pyrochat.app.platform.PrefsBiometricStore
import com.pyrochat.app.platform.UnlockFailurePolicy
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Canned, in-memory [Backend] so every screen is navigable before
 * core-service lands. Simulates the relay round-trips (pending → SENT →
 * DELIVERED → READ), a chatty `pyrobot` peer (typing, echo replies, a burn
 * message), blob progress, and the auth state machine. Only the identity
 * (username, password hash, duress hash) is persisted — in plain
 * SharedPreferences — so a relaunch lands on the `[ UNLOCK ]` prompt like the
 * real app; everything else resets per process.
 */
class FakeBackend(context: Context, private val scope: CoroutineScope) : Backend {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("pyro-fake-shell", Context.MODE_PRIVATE)

    /**
     * docs/BIOMETRIC-UNLOCK.md — the real vault on its own Keystore alias and its own preferences
     * file, wrapping a canned 32-byte "store key". The demo shell therefore drives a genuine
     * BiometricPrompt and a genuine hardware-backed key; only the store behind it is fake.
     */
    private val biometrics = BiometricVault(
        KeystoreBiometricKeys(context, alias = "pyro.biometric.fake"),
        PrefsBiometricStore(context.getSharedPreferences("pyro-fake-biometric", Context.MODE_PRIVATE)),
    )

    private val _stage = MutableStateFlow<Stage>(Stage.Boot)
    private val _settings = MutableStateFlow(AppSettings())
    private val _conn = MutableStateFlow(ConnState.offline)
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val _typing = MutableStateFlow<Set<String>>(emptySet())
    private val _blobProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val _rx = MutableStateFlow<List<RxTransfer>>(emptyList())
    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    private val _mirror = MutableStateFlow<MirrorState>(MirrorState.Idle)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _notices = MutableStateFlow<List<UiNotice>>(emptyList())
    private val _unseenNotices = MutableStateFlow(0)
    private val _updateAvailable = MutableStateFlow<UiUpdate?>(null)
    private val _accessDays = MutableStateFlow(0)
    private val _duressSet = MutableStateFlow(prefs.contains(KEY_DURESS))
    private val _biometricEnabled = MutableStateFlow(false)
    /** docs/SCRUB-AND-WIPE.md §A2 — the real counter, in the demo shell's own prefs file. */
    private val _unlockFailures = MutableStateFlow(0)
    private val _tones = MutableSharedFlow<Tone>(extraBufferCapacity = 8)
    private val _groups = MutableStateFlow<List<UiGroup>>(emptyList())
    private val _groupTyping = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val _gone = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val _former = MutableStateFlow<Map<String, List<UiMember>>>(emptyMap())
    private val _flash = MutableStateFlow<String?>(null)

    override val stage: StateFlow<Stage> = _stage.asStateFlow()
    override val groups: StateFlow<List<UiGroup>> = _groups.asStateFlow()
    override val groupTyping: StateFlow<Map<String, Set<String>>> = _groupTyping.asStateFlow()
    override val goneMembers: StateFlow<Map<String, Set<String>>> = _gone.asStateFlow()
    override val formerMembers: StateFlow<Map<String, List<UiMember>>> = _former.asStateFlow()
    override val flash: StateFlow<String?> = _flash.asStateFlow()
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    override val connState: StateFlow<ConnState> = _conn.asStateFlow()
    override val senderHidden: StateFlow<SenderHidden> = MutableStateFlow(SenderHidden.NOT_OFFERED)
    override val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    override val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()
    override val typingPeers: StateFlow<Set<String>> = _typing.asStateFlow()
    override val voiceBeingChanged: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).asStateFlow() // the canned notes are not changed
    override val blobProgress: StateFlow<Map<String, Float>> = _blobProgress.asStateFlow()
    override val rxTransfers: StateFlow<List<RxTransfer>> = _rx.asStateFlow()
    override val blockedHashes: StateFlow<Set<String>> = _blocked.asStateFlow()
    override val mirror: StateFlow<MirrorState> = _mirror.asStateFlow()
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()
    override val notices: StateFlow<List<UiNotice>> = _notices.asStateFlow()
    override val unseenNotices: StateFlow<Int> = _unseenNotices.asStateFlow()
    override val updateAvailable: StateFlow<UiUpdate?> = _updateAvailable.asStateFlow()
    override val exportStatus: MutableStateFlow<String?> = MutableStateFlow(null)
    override val accessDaysRemaining: StateFlow<Int> = _accessDays.asStateFlow()
    override val duressPinSet: StateFlow<Boolean> = _duressSet.asStateFlow()
    override val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()
    override val unlockFailures: StateFlow<Int> = _unlockFailures.asStateFlow()
    /** docs/DECOY-ACCOUNT.md §1 — `--ez fakeTooLarge true` renders the one line for a UI check. */
    override val storeTooLarge: StateFlow<UiStoreTooLarge?> =
        MutableStateFlow(if (demoTooLarge) UiStoreTooLarge(2_300_000, 2_097_112) else null).asStateFlow()
    override val tones: SharedFlow<Tone> = _tones.asSharedFlow()

    override val myUsername: String get() = prefs.getString(KEY_USER, "") ?: ""
    override val myHash: String get() = sha256Hex(myUsername.lowercase())

    /**
     * docs/MESSAGE-DETAILS.md §3 — the demo shell's stand-in for the service's capture buffer:
     * RAM only, dropped on lock and wipe, and filled only for rows this process put on / took off
     * the (imaginary) wire. The canned history deliberately keeps its OLDEST rows uncaptured, so
     * the `body: not kept` case is reachable in the demo exactly as it is in the real app.
     */
    private val frames = HashMap<String, UiFrame>()

    private val drafts = HashMap<String, String>()
    private val attachments = HashMap<String, ByteArray>()
    /** docs/SCRUB-AND-WIPE.md §B2 — message id → what its attachment lost, RAM only. */
    private val scrubReceipts = HashMap<String, UiScrubReceipt>()
    private var activeThread: String? = null
    private var sweeper: Job? = null
    private var connectJob: Job? = null
    private val storeExists: Boolean get() = prefs.contains(KEY_USER)

    // ---- lifecycle ---------------------------------------------------------------

    override fun finishBoot() {
        _biometricEnabled.value = runCatching { biometrics.enabled }.getOrDefault(false)
        _unlockFailures.value = runCatching { biometrics.unlockFailures }.getOrDefault(0)   // §A1: survives restarts
        _stage.value = Stage.Auth
    }

    override suspend fun createAccount(username: String, password: String, relay: String): String? {
        delay(900)
        if (username.lowercase() == "pyrobot") return "username_taken"   // relay error code, verbatim like iOS `"\(error)"`
        prefs.edit().putString(KEY_USER, username).putString(KEY_PW, sha256Hex(password)).apply()
        biometrics.notePasswordUnlock()   // docs/BIOMETRIC-UNLOCK.md §4 + SCRUB-AND-WIPE.md §A1
        _unlockFailures.value = 0
        if (relay.isNotEmpty()) _settings.update { it.copy(relayURL = relay) }
        enterMain(fresh = true)
        // FIRST-RUN.md §1: right after CREATE IDENTITY, best effort, add `pyrobot` exactly as `[+]` → pyrobot would
        scope.launch { runCatching { addContact(HomeRules.PYROBOT) } }
        return null
    }

    override suspend fun unlock(password: String): String? {
        delay(600)
        val duress = prefs.getString(KEY_DURESS, null)
        if (duress != null && duress == sha256Hex(password)) {
            obliterate()
            _stage.value = Stage.Auth
            return "store corrupt — no identity found"
        }
        if (prefs.getString(KEY_PW, null) != sha256Hex(password)) {
            // docs/SCRUB-AND-WIPE.md §A — the tenth wrong password destroys the store and says nothing
            val failures = biometrics.noteWrongPassword()
            _unlockFailures.value = failures
            if (UnlockFailurePolicy.decide(failures, System.currentTimeMillis()) is UnlockFailurePolicy.Decision.Wipe) {
                obliterate()
                _stage.value = Stage.Auth
                return null
            }
            return "decrypt failed — wrong password"
        }
        biometrics.notePasswordUnlock()   // docs/BIOMETRIC-UNLOCK.md §4 + SCRUB-AND-WIPE.md §A1
        _unlockFailures.value = 0
        enterMain(fresh = false)
        return null
    }

    override suspend fun restoreBackup(backup: ByteArray, passphrase: String, newPassword: String): String? {
        delay(700)
        // docs/DECOY-ACCOUNT.md §5: the canned shell may not say what the real one is forbidden
        // to say. `Mapping.ERR_IDENTITY_EXISTS` is kept as an iOS-parity record and is
        // unreachable in RealBackend; rendering it here would put the assertion back on screen.
        if (storeExists) return Mapping.ERR_NO_ROOM
        val magic = "PYROBAK1".toByteArray()
        if (backup.size < magic.size || !backup.copyOfRange(0, magic.size).contentEquals(magic)) return "not a pyrochat backup file"
        return "wrong backup passphrase"   // the fake holds no key material to decrypt with
    }

    override fun lock() {
        shutdown()
        _stage.value = Stage.Auth
    }

    override suspend fun goOnline() {
        if (_conn.value != ConnState.offline) return
        connectJob?.cancel()
        connectJob = scope.launch {
            _conn.value = ConnState.connecting
            delay(600)
            _conn.value = ConnState.authenticating
            delay(500)
            _conn.value = ConnState.online
        }
    }

    override fun goOffline() {
        connectJob?.cancel()
        _conn.value = ConnState.offline
        _mirror.value = MirrorState.Idle
    }

    override suspend fun totalWipe() {
        _mirror.value = MirrorState.Idle
        delay(300)          // deregister round-trip
        shutdown()
        obliterate()
        _stage.value = Stage.Boot   // iOS exit(0): the relaunch presents a fresh install
    }

    // ---- contacts ----------------------------------------------------------------

    override suspend fun addContact(username: String) {
        delay(700)
        val name = username.trim()
        if (name.equals(myUsername, ignoreCase = true)) throw BackendException("cannot_add_self")
        if (name.length < 3 || name.length > 24 || !name.all { it.isLetterOrDigit() || it == '_' }) throw BackendException("bad_username")
        if (name.startsWith("nobody")) throw BackendException("unknown_user")
        val hash = sha256Hex(name.lowercase())
        if (_contacts.value.any { it.hash == hash }) return
        _contacts.update { it + Contact(hash, name, now()) }
        _messages.update { it + (hash to emptyList()) }
        // the real bot speaks only when spoken to (`demoFirstRun`); the canned shell greets right away
        if (name.equals("pyrobot", ignoreCase = true) && !demoFirstRun) scope.launch { pyrobotWelcome(hash) }
    }

    override fun renameContact(hash: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        _contacts.update { list -> list.map { if (it.hash == hash) it.copy(name = clean) else it } }
    }

    override fun deleteContact(hash: String) {
        _contacts.update { list -> list.filterNot { it.hash == hash } }
        _messages.update { it - hash }
        drafts.remove(hash)
    }

    override fun blockContact(hash: String) {
        _blocked.update { it + hash }
    }

    override fun unblockContact(hash: String) {
        _blocked.update { it - hash }
    }

    override suspend fun reportAndBlock(hash: String) {
        delay(200)
        _messages.update { it + (hash to emptyList()) }
        blockContact(hash)
    }

    override fun setVerified(verified: Boolean, hash: String) {
        _contacts.update { list -> list.map { if (it.hash == hash) it.copy(verified = verified) else it } }
    }

    override fun safetyNumber(hash: String): String? {
        val log = _messages.value[hash] ?: return null
        if (log.isEmpty()) return null   // no identity key on record yet
        val pair = listOf(myHash, hash).sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(pair.toByteArray()) +
            MessageDigest.getInstance("SHA-256").digest((pair + "2").toByteArray())
        return digest.joinToString("") { ((it.toInt() and 0xff) % 10).toString() }.take(60)
    }

    override fun setBurnDefault(seconds: Long?, hash: String) {
        _contacts.update { list -> list.map { if (it.hash == hash) it.copy(burnSeconds = seconds) else it } }
    }

    override fun safetyNumberQR(hash: String): ByteArray? =
        safetyNumber(hash)?.let { MessageDigest.getInstance("SHA-256").digest(("qr|" + it).toByteArray()) }

    /** A canned 33-byte `IdentityKey.serialize()` (0x05 ‖ 32) per username, so MY CODE and SCAN agree. */
    private fun fakeIdentityKey(username: String): ByteArray =
        byteArrayOf(0x05) + MessageDigest.getInstance("SHA-256").digest(("id|" + username.lowercase()).toByteArray())

    override fun myAddQRPayload(): String? =
        myUsername.takeIf { it.isNotEmpty() }?.let { com.pyrochat.app.ui.qr.AddQrPayload.encode(it, fakeIdentityKey(it)) }

    override suspend fun addContactViaQR(username: String, identityKey: ByteArray): Boolean {
        val name = username.trim()
        if (name.length < 3 || name.length > 24 || !name.all { it.isLetterOrDigit() || it == '_' }) throw BackendException("bad_username")
        if (identityKey.size < 32) throw BackendException("bad_key")
        if (name.equals(myUsername, ignoreCase = true)) throw BackendException("cannot_add_self")
        val hash = sha256Hex(name.lowercase())
        val matches = identityKey.contentEquals(fakeIdentityKey(name))
        if (_contacts.value.any { it.hash == hash }) {
            if (!matches) throw BackendException("qr_key_mismatch")
            setVerified(true, hash)
            return true
        }
        addContact(name)
        if (matches) {
            setVerified(true, hash)
            return true
        }
        deleteContact(hash)
        throw BackendException("identity_mismatch")
    }

    // ---- group channels (docs/GROUPS.md) — local simulation of the roster rules -----------

    private fun groupOf(id: String): UiGroup? = _groups.value.firstOrNull { it.id == id }

    private fun replaceGroup(g: UiGroup) {
        _groups.update { list -> list.map { if (it.id == g.id) g else it } }
    }

    private fun systemRow(gid: String, text: String) =
        ChatMessage(newId(), Direction.incoming, Kind.system, text, now(), Status.read, group = gid)

    override suspend fun createGroup(name: String, members: List<String>, policy: String, burnSeconds: Long?): String {
        val clean = name.trim().trimStart('#').trim()
        if (clean.isEmpty() || clean.length > 32) throw BackendException("ERR: name must be 1–32 characters")
        if (members.distinct().size > GroupUi.MAX_PICKED) throw BackendException(GroupUi.ERR_GROUP_FULL)
        delay(300)
        val t = now()
        val id = newId()
        val roster = listOf(UiMember(myHash, myUsername, t)) +
            members.distinct().mapNotNull { h -> _contacts.value.firstOrNull { it.hash == h }?.let { UiMember(h, it.name, t) } }
        _groups.update { it + UiGroup(id, clean, myHash, t, 0, policy, burnSeconds, roster) }
        _messages.update { it + (id to emptyList()) }
        return id
    }

    override suspend fun addMember(groupId: String, hash: String) {
        val g = groupOf(groupId) ?: return
        if (g.left) throw BackendException(GroupUi.ERR_NOT_IN_GROUP)
        if (g.isMember(hash)) return
        if (g.members.size >= 20) throw BackendException(GroupUi.ERR_GROUP_FULL)
        val name = _contacts.value.firstOrNull { it.hash == hash }?.name ?: ("anon-" + hash.take(8))
        val joinedAt = g.formerMember(hash)?.joinedAt ?: now() // a re-added member keeps the earliest join (§5)
        replaceGroup(g.copy(members = g.members + UiMember(hash, name, joinedAt), epoch = g.epoch + 1, former = g.former.filterNot { it.hash == hash }))
        append(groupId, systemRow(groupId, "── <$name> joined ──"))
    }

    override suspend fun removeMember(groupId: String, hash: String) {
        val g = groupOf(groupId) ?: return
        if (g.left) throw BackendException(GroupUi.ERR_NOT_IN_GROUP)
        if (g.creator != myHash) throw BackendException(GroupUi.ERR_ONLY_CREATOR)
        val m = g.member(hash) ?: return
        replaceGroup(g.copy(members = g.members.filterNot { it.hash == hash }, epoch = g.epoch + 1, former = g.former.filterNot { it.hash == hash } + m))
        _former.update { it + (groupId to ((it[groupId] ?: emptyList()) + m)) }
        append(groupId, systemRow(groupId, "── <${m.name}> was removed ──"))
    }

    override suspend fun leaveGroup(groupId: String) {
        val g = groupOf(groupId) ?: return
        if (g.left) return
        val me = g.member(myHash)
        replaceGroup(g.copy(members = g.members.filterNot { it.hash == myHash }, epoch = g.epoch + 1, left = true, former = g.former + listOfNotNull(me)))
        append(groupId, systemRow(groupId, "── <$myUsername> left ──"))
    }

    override suspend fun renameGroup(groupId: String, name: String) {
        val g = groupOf(groupId) ?: return
        val clean = name.trim().trimStart('#').trim()
        if (clean.isEmpty() || clean.length > 32) throw BackendException("ERR: name must be 1–32 characters")
        if (clean == g.name) return
        replaceGroup(g.copy(name = clean, epoch = g.epoch + 1))
        append(groupId, systemRow(groupId, "── renamed to #$clean ──"))
    }

    override suspend fun setGroupPolicy(groupId: String, policy: String, burnSeconds: Long?) {
        val g = groupOf(groupId) ?: return
        if (g.policy == policy && g.burnSeconds == burnSeconds) return
        replaceGroup(g.copy(policy = policy, burnSeconds = burnSeconds, epoch = g.epoch + 1))
        if (g.policy != policy) append(groupId, systemRow(groupId, "── screenshot policy: $policy ──"))
    }

    override suspend fun burnGroup(groupId: String) {
        delay(300)
        deleteGroup(groupId)
    }

    override fun deleteGroup(groupId: String) {
        _groups.update { list -> list.filterNot { it.id == groupId } }
        _messages.update { it - groupId }
        _groupTyping.update { it - groupId }
        drafts.remove(groupId)
    }

    /** A group text: fan-out simulated as SENT, then per-member receipts (some read), then a coloured reply. */
    private fun sendGroupText(text: String, g: UiGroup) {
        val id = newId()
        append(g.id, ChatMessage(id, Direction.outgoing, Kind.text, text, now(), Status.pending, burnAfter = groupBurn(g), group = g.id, receipts = emptyMap()))
        _tones.tryEmit(Tone.tx)
        scope.launch {
            delay(350)
            if (_conn.value != ConnState.online) { setStatus(id, g.id, Status.failed); return@launch }
            setStatus(id, g.id, Status.sent)
            val others = g.members.filter { it.hash != myHash }
            for (m in others) {
                delay(300)
                receipt(id, g.id, m.hash, "delivered")
            }
            others.firstOrNull()?.let { first ->
                delay(700)
                receipt(id, g.id, first.hash, "read")
                if (_settings.value.typingOn) _groupTyping.update { it + (g.id to setOf(first.hash)) }
                delay(1500)
                _groupTyping.update { it - g.id }
                append(g.id, ChatMessage(newId(), Direction.incoming, Kind.text, "copy that: $text", now(), Status.delivered, group = g.id, sender = first.hash))
                _tones.tryEmit(Tone.rx)
            }
        }
    }

    private fun groupBurn(g: UiGroup): Long? = when (val s = g.burnSeconds) {
        null -> _settings.value.defaultBurnSeconds
        0L -> null
        else -> s
    }

    private fun receipt(id: String, gid: String, member: String, kind: String) {
        _messages.update { all ->
            all + (gid to (all[gid] ?: emptyList()).map { m ->
                if (m.id != id) m
                else {
                    val cur = m.receipts ?: emptyMap()
                    if (cur[member] == "read") m else m.copy(receipts = cur + (member to kind))
                }
            })
        }
    }

    // ---- messages ------------------------------------------------------------------

    override suspend fun sendText(text: String, hash: String) {
        groupOf(hash)?.let { g ->
            if (g.left) { _lastError.value = GroupUi.ERR_NOT_IN_GROUP; return }
            sendGroupText(text, g)
            return
        }
        val id = newId()
        append(hash, ChatMessage(id, Direction.outgoing, Kind.text, text, now(), Status.pending, burnAfter = burnDefault(hash)))
        _tones.tryEmit(Tone.tx)
        scope.launch { deliver(id, hash, echo = text) }
    }

    override suspend fun sendImage(jpeg: ByteArray, width: Int, height: Int, hash: String, scrub: UiScrubReceipt?) {
        val id = newId()
        attachments[id] = jpeg
        scrub?.let { scrubReceipts[id] = it }   // docs/SCRUB-AND-WIPE.md §B2
        append(hash, ChatMessage(id, Direction.outgoing, Kind.image, "", now(), Status.pending, burnAfter = burnDefault(hash),
            attachmentId = id, fileMime = "image/jpeg", fileBytes = jpeg.size.toLong(), imageWidth = width, imageHeight = height))
        _tones.tryEmit(Tone.tx)
        scope.launch { transfer(id); deliver(id, hash, echo = null) }
    }

    override suspend fun sendFile(data: ByteArray, filename: String, mime: String, hash: String, scrub: UiScrubReceipt?) {
        val id = newId()
        attachments[id] = data
        scrub?.let { scrubReceipts[id] = it }   // docs/SCRUB-AND-WIPE.md §B2
        append(hash, ChatMessage(id, Direction.outgoing, Kind.file, "", now(), Status.pending, burnAfter = burnDefault(hash),
            attachmentId = id, fileName = filename, fileMime = mime, fileBytes = data.size.toLong()))
        _tones.tryEmit(Tone.tx)
        scope.launch { transfer(id); deliver(id, hash, echo = null) }
    }

    override suspend fun sendVoice(audio: ByteArray, duration: Double, hash: String) {
        val id = newId()
        append(hash, ChatMessage(id, Direction.outgoing, Kind.voice, "", now(), Status.pending, burnAfter = burnDefault(hash),
            hasVoice = true, voiceDuration = duration))
        _tones.tryEmit(Tone.tx)
        scope.launch { transfer(id); deliver(id, hash, echo = null) }
    }

    override suspend fun retry(messageId: String, hash: String) {
        setStatus(messageId, hash, Status.pending)
        scope.launch { deliver(messageId, hash, echo = null) }
    }

    override fun deleteMessage(messageId: String, hash: String) {
        _messages.value[hash]?.firstOrNull { it.id == messageId }?.attachmentId?.let(attachments::remove)
        _messages.update { all -> all + (hash to (all[hash] ?: emptyList()).filterNot { it.id == messageId }) }
    }

    override suspend fun deleteForBoth(messageId: String, hash: String): Boolean {
        if (_conn.value != ConnState.online) {
            _lastError.value = "offline — delete for both needs the link up"
            return false
        }
        delay(400)
        deleteMessage(messageId, hash)
        return true
    }

    override suspend fun burnThread(hash: String) {
        delay(150)
        _messages.update { it + (hash to emptyList()) }
    }

    override fun attachmentData(message: ChatMessage): ByteArray? = message.attachmentId?.let { attachments[it] }

    override fun voiceData(message: ChatMessage): ByteArray? = null   // the canned rows carry no audio; the UI sweeps a placeholder

    override fun voiceBars(message: ChatMessage): FloatArray {
        val d = MessageDigest.getInstance("SHA-256").digest(message.id.toByteArray())
        return FloatArray(32) { i -> 0.15f + 0.85f * ((d[i].toInt() and 0xff) / 255f) }
    }

    override fun capturedFrame(messageId: String): UiFrame? = frames[messageId]

    override fun scrubReceipt(messageId: String): UiScrubReceipt? = scrubReceipts[messageId]

    override fun setActiveThread(hash: String?) {
        activeThread = hash
    }

    override fun markNoticesSeen() {
        _unseenNotices.value = 0
    }

    override fun needsVisibilityReport(message: ChatMessage): Boolean =
        message.direction == Direction.incoming && message.kind != Kind.system &&
            (message.status != Status.read || (message.burnAfter != null && message.burnStartedAt == null))

    override fun noteRowVisible(messageId: String, hash: String) {
        if (activeThread != hash) return
        _messages.update { all ->
            val log = all[hash] ?: return@update all
            all + (hash to log.map { m ->
                if (m.id != messageId || m.direction != Direction.incoming) m
                else m.copy(
                    status = if (m.kind == Kind.system) m.status else Status.read,
                    burnStartedAt = if (m.burnAfter != null && m.burnStartedAt == null) now() else m.burnStartedAt,
                )
            })
        }
    }

    override fun noteComposerActivity(hash: String) { /* typing control: relay-bound, nothing to show locally */ }

    override fun draft(hash: String): String = drafts[hash] ?: ""

    override fun setDraft(text: String, hash: String) {
        if (text.isEmpty()) drafts.remove(hash) else drafts[hash] = text
    }

    /** The canned shell has no disk, so there is nothing deferred to write. */
    override fun persistDeferred() = Unit

    /**
     * docs/DECOY-ACCOUNT.md §3 — the demo shell has one slot and no relay, so the row behaves the
     * way it does in an account that has already used its one decoy: the ordinary words, every
     * time. Set `FakeBackend.demoDecoyError` to drive the other outcomes for UI work.
     */
    override fun decoyExists(): Boolean = fakeDecoyMade

    override suspend fun setDecoyPassword(username: String, password: String): String? = when {
        username.isBlank() || password.isBlank() -> Mapping.ERR_DECOY_NO_ROOM
        sha256Hex(password) == prefs.getString(KEY_PW, null) -> Mapping.ERR_DECOY_SAME_PASSWORD
        demoDecoyOk -> { fakeDecoyMade = true; null }
        else -> Mapping.ERR_DECOY_NO_ROOM
    }

    // ---- settings ------------------------------------------------------------------

    override fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
    }

    override fun setDuressPin(pin: String) {
        prefs.edit().putString(KEY_DURESS, sha256Hex(pin)).apply()
        _duressSet.value = true
    }

    override suspend fun redeemAccessKey(code: String) {
        delay(3500) // pubkey fetch + 31 blind signatures + verify: a few seconds, like the real client
        val ok = Regex("PYRO(-[A-Z0-9]{4}){4}").matches(code.trim().uppercase())
        if (!ok) throw BackendException("invalid or already-used access key")
        _accessDays.value = 31
    }

    override suspend fun exportBackup(passphrase: String): Int {
        delay(500)
        // the demo shell has no save picker, so the export IS the moment the file lands
        _settings.update { it.copy(lastBackupAt = now()) }
        exportStatus.value = "\u2713 backup saved"
        return 184_320 + passphrase.length
    }

    override fun startMirror(rawCode: String): String? {
        val raw = rawCode.trim()
        if (raw.startsWith("pyro-add:")) return "that's a CONTACT code — scan it from [qr] on the home screen"
        if (!raw.startsWith("pyro-link:v1:")) return null   // someone's lunch QR — keep scanning silently
        val parts = raw.split(":")
        if (parts.size != 5 || parts[2].length != 22 || parts[3].isEmpty()) return "unreadable link QR — reload the browser page"
        val host = parts[4].substringBefore("/").substringBefore(":")
        val expected = _settings.value.relayURL.removePrefix("wss://").removePrefix("ws://").substringBefore("/").substringBefore(":")
        if (host != expected) return "this code belongs to a different server — open pyrochat.ca/link and scan that page instead."
        val sas = (sha256Hex(parts[2]).take(8).toLong(16) % 1_000_000).toString().padStart(6, '0')
        _mirror.value = MirrorState.Linked(now(), "Chrome", sas)
        return null
    }

    override fun stopMirror() {
        _mirror.value = MirrorState.Idle
    }

    // ---- unlock with fingerprint / face (docs/BIOMETRIC-UNLOCK.md) -------------------

    override fun biometricKind(): BiometricKind? = runCatching { Biometrics.kind(appContext) }.getOrNull()

    override suspend fun enableBiometricUnlock(prompter: BiometricPrompter): BiometricEnable {
        val cipher = try {
            biometrics.beginEnable()
        } catch (e: GeneralSecurityException) {
            return BiometricEnable.Failed(e.message ?: "keystore refused")
        }
        val auth = prompter.authenticate(cipher) { true }
        if (auth !is BiometricAuth.Ok) {
            disableBiometricUnlock()
            return if (auth is BiometricAuth.Failed) BiometricEnable.Failed(auth.message) else BiometricEnable.Refused
        }
        return try {
            biometrics.finishEnable(auth.cipher, DEMO_STORE_KEY.copyOf())
            _biometricEnabled.value = true
            BiometricEnable.Ok
        } catch (e: GeneralSecurityException) {
            disableBiometricUnlock()
            BiometricEnable.Failed(e.message ?: "could not store the key")
        }
    }

    override fun disableBiometricUnlock() {
        runCatching { biometrics.destroy() }
        _biometricEnabled.value = false
    }

    override fun biometricGate(): BiometricGate {
        val kind = biometricKind() ?: return BiometricGate.Off
        if (!biometrics.enabled) return BiometricGate.Off
        return if (biometrics.decide() == BiometricDecision.ALLOW_BIOMETRY) BiometricGate.Ready(kind) else BiometricGate.PasswordRequired
    }

    override suspend fun unlockWithBiometrics(prompter: BiometricPrompter): BiometricOutcome {
        if (!biometrics.enabled) return BiometricOutcome.PasswordRequired
        if (biometrics.decide() != BiometricDecision.ALLOW_BIOMETRY) return BiometricOutcome.PasswordRequired
        val cipher = try {
            biometrics.beginUnlock()
        } catch (e: GeneralSecurityException) {
            disableBiometricUnlock()
            return BiometricOutcome.Invalidated
        }
        var spent = false
        var noMatches = 0
        val auth = prompter.authenticate(cipher) {
            noMatches++
            val n = biometrics.noteFailure()
            if (n >= BiometricPolicy.MAX_CONSECUTIVE_FAILURES) { spent = true; false } else true
        }
        if (auth !is BiometricAuth.Ok) {
            return when {
                spent -> BiometricOutcome.PasswordRequired
                // §3: a sheet dismissed after a rejection is "not recognised"; one dismissed
                // without ever failing is a plain cancellation and says nothing at all
                auth is BiometricAuth.Cancelled -> if (noMatches > 0) BiometricOutcome.NotRecognised else BiometricOutcome.Cancelled
                auth is BiometricAuth.NotRecognised -> BiometricOutcome.NotRecognised
                auth is BiometricAuth.Invalidated -> { disableBiometricUnlock(); BiometricOutcome.Invalidated }
                else -> BiometricOutcome.Failed((auth as BiometricAuth.Failed).message)
            }
        }
        val key = try {
            biometrics.finishUnlock(auth.cipher)
        } catch (e: GeneralSecurityException) {
            Log.w("FakeBackend", "demo key would not unwrap", e)
            disableBiometricUnlock()
            return BiometricOutcome.Invalidated
        }
        if (!key.contentEquals(DEMO_STORE_KEY)) return BiometricOutcome.Failed("demo key mismatch")
        biometrics.noteSuccess()
        _unlockFailures.value = 0   // §A1: any successful unlock, typed or biometric
        enterMain(fresh = false)
        return BiometricOutcome.Ok
    }

    override suspend fun syncPushToken() {
        delay(100)
    }

    // ---- simulation --------------------------------------------------------------------

    private fun enterMain(fresh: Boolean) {
        frames.clear()
        _contacts.value = emptyList()
        _messages.value = emptyMap()
        _blocked.value = emptySet()
        _groups.value = emptyList()
        _groupTyping.value = emptyMap()
        if (!fresh && !demoFirstRun) seedCannedState() // `fakeFirstRun`: the home screen of a brand-new identity
        seedNotice() // the relay replays its notices at every login, contacts or not
        _updateAvailable.value = parseDemoUpdate(demoUpdate) // …and its `versions` frame right after them
        _stage.value = Stage.Main
        scope.launch { goOnline() }
        sweeper?.cancel()
        sweeper = scope.launch {
            while (isActive) {
                delay(1000)
                sweepBurned()
            }
        }
    }

    private fun shutdown() {
        frames.clear() // §3: the capture is dropped on lock and auto-lock
        sweeper?.cancel(); sweeper = null
        goOffline()
        _typing.value = emptySet()
        _blobProgress.value = emptyMap()
        activeThread = null
        // pending outgoing → failed, like ChatService.failAllPending
        _messages.update { all ->
            all.mapValues { (_, log) -> log.map { if (it.status == Status.pending) it.copy(status = Status.failed) else it } }
        }
    }

    private fun obliterate() {
        // docs/BIOMETRIC-UNLOCK.md §5: the duress PIN and TOTAL WIPE destroy the stored key FIRST
        runCatching { biometrics.shred() }
        _biometricEnabled.value = false
        _unlockFailures.value = 0   // §A: a wiped phone is a fresh phone, counter included
        prefs.edit().clear().apply()
        _duressSet.value = false
        _contacts.value = emptyList()
        _messages.value = emptyMap()
        _blocked.value = emptySet()
        _accessDays.value = 0
        _settings.value = AppSettings()
        _notices.value = emptyList()
        _unseenNotices.value = 0
        _updateAvailable.value = null
        drafts.clear()
        attachments.clear()
        scrubReceipts.clear()
        frames.clear() // §3: and on wipe
    }

    /** One canned operator notice — what `POST /admin/notices` on the dev relay would deliver. */
    private fun seedNotice() {
        val t = now()
        _notices.value = listOf(
            UiNotice(
                id = "b5b0b6e2-0f5c-4d2b-9e0f-6c1e2a3d4f50",
                text = "relay maintenance tonight 02:00–02:10 UTC. messages queue, nothing is lost.",
                url = "https://pyrochat.ca/",
                ts = t - 1_800_000L,
                expires = t + 7L * 86_400_000L,
            ),
        )
        _unseenNotices.value = 1
    }

    private fun sweepBurned() {
        val t = now()
        var changed = false
        val next = _messages.value.mapValues { (_, log) ->
            val kept = log.filter { m -> m.burnDeadline?.let { it > t } ?: true }
            if (kept.size != log.size) { changed = true; log.filterNot { kept.contains(it) }.forEach { m -> m.attachmentId?.let(attachments::remove) } }
            kept
        }
        if (changed) _messages.value = next
    }

    /** pending → SENT → DELIVERED; pyrobot then types and echoes, which marks ours READ. */
    private suspend fun deliver(id: String, hash: String, echo: String?) {
        delay(350)
        if (_conn.value != ConnState.online) { setStatus(id, hash, Status.failed); return }
        setStatus(id, hash, Status.sent)
        delay(700)
        setStatus(id, hash, Status.delivered)
        val peer = _contacts.value.firstOrNull { it.hash == hash } ?: return
        if (!peer.name.equals("pyrobot", ignoreCase = true) || echo == null) return
        delay(500)
        if (_settings.value.typingOn) _typing.update { it + hash }
        delay(1600)
        _typing.update { it - hash }
        // the bot read it: READ receipt (shown only if the reciprocity toggle is on)
        _messages.update { all ->
            all + (hash to (all[hash] ?: emptyList()).map { if (it.direction == Direction.outgoing && it.status == Status.delivered) it.copy(status = Status.read) else it })
        }
        receive(hash, "echo: $echo")
    }

    private suspend fun transfer(id: String) {
        var p = 0f
        while (p < 1f) {
            delay(120)
            p = (p + 0.14f).coerceAtMost(1f)
            _blobProgress.update { it + (id to p) }
        }
        _blobProgress.update { it - id }
    }

    private fun receive(hash: String, text: String, burnAfter: Long? = null) {
        append(hash, ChatMessage(newId(), Direction.incoming, Kind.text, text, now(), Status.delivered, burnAfter = burnAfter))
        _tones.tryEmit(Tone.rx)
    }

    private suspend fun pyrobotWelcome(hash: String) {
        delay(1200)
        _typing.update { it + hash }
        delay(1800)
        _typing.update { it - hash }
        receive(hash, "hello. i am pyrobot — the demo peer. everything you send here is end-to-end encrypted; the relay only sees ciphertext.")
        delay(2500)
        receive(hash, "say anything and i echo it back. watch the status word: SENT → DELIVERED → READ (READ shows once you turn on read receipts in [cfg]).")
        delay(2500)
        receive(hash, "this one self-destructs 20s after you read it 🔥", burnAfter = 20)
    }

    private fun seedCannedState() {
        val t = now()
        val bot = Contact(sha256Hex("pyrobot"), "pyrobot", t - 86_400_000L * 3, verified = true)
        val alice = Contact(sha256Hex("alice"), "alice", t - 86_400_000L * 2)
        val bob = Contact(sha256Hex("bob"), "bob", t - 3_600_000L)
        _contacts.value = listOf(bot, alice, bob)
        val imgId = newId()
        attachments[imgId] = demoJpeg(640, 480)
        val fileId = newId()
        attachments[fileId] = ByteArray(43_112) { (it % 251).toByte() }
        val botLog = listOf(
            ChatMessage(newId(), Direction.incoming, Kind.text, "hello. i am pyrobot — the demo peer. everything you send here is end-to-end encrypted; the relay only sees ciphertext.", t - 540_000, Status.read),
            ChatMessage(newId(), Direction.outgoing, Kind.text, "hi pyrobot", t - 500_000, Status.read),
            ChatMessage(newId(), Direction.incoming, Kind.text, "delivered = it reached my device. read = i looked at it (shown only when read receipts are on in [cfg]).", t - 480_000, Status.read),
            ChatMessage(newId(), Direction.outgoing, Kind.text, "sent from my desktop link", t - 420_000, Status.read, viaWeb = true),
            ChatMessage(newId(), Direction.incoming, Kind.voice, "", t - 400_000, Status.read, hasVoice = true, voiceDuration = 4.0),
            ChatMessage(newId(), Direction.outgoing, Kind.image, "", t - 360_000, Status.delivered, attachmentId = imgId, fileMime = "image/jpeg", fileBytes = attachments[imgId]!!.size.toLong(), imageWidth = 640, imageHeight = 480),
            ChatMessage(newId(), Direction.incoming, Kind.file, "", t - 300_000, Status.read, attachmentId = fileId, fileName = "pyrochat-protocol.pdf", fileMime = "application/pdf", fileBytes = 43_112),
            ChatMessage(newId(), Direction.incoming, Kind.system, "⚠ peer took a screenshot", t - 240_000, Status.read),
            ChatMessage(newId(), Direction.incoming, Kind.text, "this message burns one hour after you read it 🔥", t - 180_000, Status.read, burnAfter = 3600, burnStartedAt = t - 10_000),
            ChatMessage(newId(), Direction.outgoing, Kind.text, "this one self-destructs 5m after you read it", t - 120_000, Status.delivered, burnAfter = 300),
            ChatMessage(newId(), Direction.outgoing, Kind.text, "did this get through?", t - 90_000, Status.sent),
            ChatMessage(newId(), Direction.outgoing, Kind.text, "are you there?", t - 60_000, Status.failed),
        )
        val aliceLog = listOf(
            ChatMessage(newId(), Direction.outgoing, Kind.text, "channel test", t - 7_200_000, Status.read),
            ChatMessage(newId(), Direction.incoming, Kind.text, "hey, is this thing on?", t - 200_000, Status.delivered),
            ChatMessage(newId(), Direction.incoming, Kind.text, "ping", t - 30_000, Status.delivered),
        )
        // `#ops` — 4 members (me, alice, bob and carol, who is not a contact); every line in its member's colour
        val gid = "6f6f7073-0000-4000-8000-00000000006f"
        val carol = sha256Hex("carol")
        val ops = UiGroup(
            id = gid, name = "ops", creator = myHash, createdAt = t - 86_400_000L * 3, epoch = 3, policy = "burn_remove",
            members = listOf(
                UiMember(myHash, myUsername, t - 86_400_000L * 3),
                UiMember(alice.hash, "alice", t - 86_400_000L * 3),
                UiMember(bob.hash, "bob", t - 86_400_000L * 2),
                UiMember(carol, "carol", t - 3_600_000L),
            ),
        )
        val opsLog = listOf(
            ChatMessage(newId(), Direction.incoming, Kind.text, "relay maintenance at 02:00 utc — who is on call?", t - 3_500_000, Status.read, group = gid, sender = alice.hash),
            ChatMessage(newId(), Direction.incoming, Kind.text, "me. i will watch the queue.", t - 3_400_000, Status.read, group = gid, sender = bob.hash),
            ChatMessage(newId(), Direction.incoming, Kind.system, "── <carol> joined ──", t - 3_300_000, Status.read, group = gid),
            ChatMessage(newId(), Direction.incoming, Kind.text, "hi all, carol from the night shift", t - 3_200_000, Status.read, group = gid, sender = carol),
            ChatMessage(newId(), Direction.outgoing, Kind.text, "welcome. rollback plan is pinned in [txt]", t - 3_000_000, Status.sent, group = gid, receipts = mapOf(alice.hash to "read", bob.hash to "read", carol to "delivered")),
            ChatMessage(newId(), Direction.incoming, Kind.text, "ack 🔥", t - 240_000, Status.delivered, group = gid, sender = alice.hash),
        )
        _groups.value = listOf(ops)
        // paging stress: N older rows of mixed kinds ahead of the canned tour
        val stress = (0 until demoRows).map { i ->
            val ts = t - 90_000_000L + i * 60_000L
            when {
                i % 25 == 24 -> ChatMessage(newId(), Direction.incoming, Kind.image, "", ts, Status.read, attachmentId = imgId, fileMime = "image/jpeg", fileBytes = attachments[imgId]!!.size.toLong())
                i % 40 == 39 -> ChatMessage(newId(), Direction.incoming, Kind.voice, "", ts, Status.read, hasVoice = true, voiceDuration = 3.0)
                i % 2 == 0 -> ChatMessage(newId(), Direction.incoming, Kind.text, "row $i: " + "lorem ipsum ".repeat(1 + i % 5).trim(), ts, Status.read)
                else -> ChatMessage(newId(), Direction.outgoing, Kind.text, "row $i: " + "reply ".repeat(1 + i % 4).trim(), ts, Status.read)
            }
        }
        _messages.value = mapOf(bot.hash to stress + botLog, alice.hash to aliceLog, bob.hash to emptyList(), gid to opsLog)
        // §3: the buffer holds the RECENT rows — the opening lines of each thread are old enough
        // to have been evicted, which is what a real unlocked session looks like
        seedCaptures(bot.hash, botLog, dropOldest = 2)
        seedCaptures(alice.hash, aliceLog, dropOldest = 1)
        seedCaptures(gid, opsLog, dropOldest = 2)
        _settings.update { it.copy(sendReadReceipts = true) } // the canned identity shows READ words (reciprocity on)
    }

    private fun seedCaptures(thread: String, log: List<ChatMessage>, dropOldest: Int) {
        log.drop(dropOldest).forEach { capture(thread, it) }
    }

    private fun burnDefault(hash: String): Long? {
        val c = _contacts.value.firstOrNull { it.hash == hash }
        return when (val s = c?.burnSeconds) {
            null -> _settings.value.defaultBurnSeconds
            0L -> null
            else -> s
        }
    }

    private fun append(hash: String, m: ChatMessage) {
        capture(hash, m)
        _messages.update { it + (hash to (it[hash] ?: emptyList()) + m) }
    }

    /** One plausible envelope for a row, deterministic in its id so a redraw never flickers. */
    private fun capture(thread: String, m: ChatMessage) {
        if (m.kind == Kind.system) return // a system row never crossed the wire
        val g = groupOf(thread)
        val peer = when {
            g == null -> thread
            m.direction == Direction.outgoing ->
                g.members.sortedWith(compareBy<UiMember> { it.joinedAt }.thenBy { it.hash }).firstOrNull { it.hash != myHash }?.hash ?: thread
            else -> m.sender ?: thread
        }
        val outgoing = m.direction == Direction.outgoing
        // a fanned-out group envelope carries its own relay id, not the row's (GROUPS.md §4)
        val relayId = if (g != null && outgoing) uuidFrom(m.id) else m.id
        val bytes = (if (m.kind == Kind.text) 96 + m.text.toByteArray().size else 232).coerceIn(72, 4096)
        frames[m.id] = UiFrame(
            t = if (outgoing) "send" else "recv",
            id = relayId,
            peer = peer,
            ttl = if (outgoing) 172_800 else null,
            kind = if (m.kind == Kind.text) "msg" else "blob",
            body = fakeCiphertext(m.id, bytes),
            bodyBytes = bytes,
        )
    }

    /** base64 of [n] bytes derived from [seed] — shaped like an envelope, made of nothing. */
    private fun fakeCiphertext(seed: String, n: Int): String {
        val out = ByteArray(n)
        var block = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        var i = 0
        while (i < n) {
            val take = minOf(block.size, n - i)
            System.arraycopy(block, 0, out, i, take)
            i += take
            block = MessageDigest.getInstance("SHA-256").digest(block)
        }
        out[0] = 0x33 // libsignal whisper-message version byte, so the head reads like one
        return java.util.Base64.getEncoder().encodeToString(out)
    }

    private fun uuidFrom(seed: String): String {
        val h = sha256Hex(seed)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-4${h.substring(13, 16)}-8${h.substring(17, 20)}-${h.substring(20, 32)}"
    }

    private fun setStatus(id: String, hash: String, status: Status) {
        _messages.update { all -> all + (hash to (all[hash] ?: emptyList()).map { if (it.id == id) it.copy(status = status) else it }) }
    }

    /** A synthetic photo for the canned `[IMG 640x480]` row (no real picker yet). */
    private fun demoJpeg(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1A0A02.toInt(), 0xFF3B1E05.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val flame = Paint().apply { color = 0xFFFFBD41.toInt(); isAntiAlias = true }
        c.drawOval(w * 0.40f, h * 0.30f, w * 0.60f, h * 0.85f, flame)
        val core = Paint().apply { color = 0xFFFFED91.toInt(); isAntiAlias = true }
        c.drawOval(w * 0.45f, h * 0.52f, w * 0.55f, h * 0.83f, core)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString().lowercase()

    companion object {
        /** DEBUG `--ei fakeRows N`: prepend N generated rows to the pyrobot thread (paging stress). */
        @Volatile var demoRows: Int = 0
        /** DEBUG `--es fakeUpdate "0.1.0|24|https://pyrochat.ca/"` (`name|build[|url]`): what the relay's `versions` frame would show. */
        @Volatile var demoUpdate: String? = null
        /**
         * DEBUG `--ez fakeFirstRun true` (docs/FIRST-RUN.md): sign in to an EMPTY home — the three-line
         * empty state — and a `pyrobot` that stays silent until spoken to, so `[+]` → pyrobot shows the
         * `demo peer — say hi for a 60-second tour` row.
         */
        @Volatile var demoFirstRun: Boolean = false

        /** DEBUG `--ez fakeDecoyOk true`: the §3 flow succeeds, for a UI pass over the happy path. */
        @Volatile var demoDecoyOk: Boolean = false

        /** DEBUG `--ez fakeTooLarge true`: render §1's one line for an account that will not fit. */
        @Volatile var demoTooLarge: Boolean = false

        /** Whether the canned shell's one account has "made" a decoy this session (§3 replace copy). */
        @Volatile var fakeDecoyMade: Boolean = false

        /** `name|build[|url]` → the banner; null for anything that would not pass [com.pyrochat.service.UpdateRules] either. */
        fun parseDemoUpdate(spec: String?): UiUpdate? {
            val parts = spec?.split('|') ?: return null
            if (parts.size < 2) return null
            val name = parts[0].trim().take(32)
            val build = parts[1].trim().toIntOrNull() ?: return null
            if (name.isEmpty() || build < 1) return null
            val url = parts.getOrNull(2)?.trim()?.takeIf { it.startsWith("https://") && it.none(Char::isWhitespace) }
            return UiUpdate(build = build, name = name, url = url)
        }
        /** The canned 32 bytes the demo shell wraps instead of a real store key. */
        private val DEMO_STORE_KEY = ByteArray(32) { (it * 7 + 3).toByte() }
        const val KEY_USER = "user"
        const val KEY_PW = "pw"
        const val KEY_DURESS = "duress"

        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
