package com.pyrochat.app.backend

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.pyrochat.app.platform.AutoLockTimer
import com.pyrochat.app.platform.BiometricAuth
import com.pyrochat.app.platform.BiometricDecision
import com.pyrochat.app.platform.BiometricInvalidated
import com.pyrochat.app.platform.BiometricKind
import com.pyrochat.app.platform.BiometricPolicy
import com.pyrochat.app.platform.BiometricPrompter
import com.pyrochat.app.platform.BiometricVault
import com.pyrochat.app.platform.Biometrics
import com.pyrochat.app.platform.FileAccess
import com.pyrochat.app.platform.Haptics
import com.pyrochat.app.platform.StorePaths
import com.pyrochat.app.platform.UnlockFailurePolicy
import com.pyrochat.app.platform.VoiceChanger
import com.pyrochat.app.platform.VoiceEffect
import com.pyrochat.app.platform.Waveform
import com.pyrochat.app.push.AppPresence
import com.pyrochat.app.ui.AppSettings
import com.pyrochat.app.ui.UiStoreTooLarge
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.BackendException
import com.pyrochat.app.ui.BiometricEnable
import com.pyrochat.app.ui.BiometricGate
import com.pyrochat.app.ui.BiometricOutcome
import com.pyrochat.app.ui.ChatMessage
import com.pyrochat.app.ui.ConnState
import com.pyrochat.app.ui.SenderHidden
import com.pyrochat.app.ui.Contact
import com.pyrochat.app.ui.Direction
import com.pyrochat.app.ui.HomeRules
import com.pyrochat.app.ui.Kind
import com.pyrochat.app.ui.MirrorState
import com.pyrochat.app.ui.RxTransfer
import com.pyrochat.app.ui.Stage
import com.pyrochat.app.ui.Status
import com.pyrochat.app.ui.Tone
import com.pyrochat.app.ui.UiFrame
import com.pyrochat.app.ui.UiGroup
import com.pyrochat.app.ui.UiMember
import com.pyrochat.app.ui.UiNotice
import com.pyrochat.app.ui.UiScrubReceipt
import com.pyrochat.app.ui.UiUpdate
import com.pyrochat.app.BuildConfig
import kotlinx.coroutines.delay
import com.pyrochat.mirror.MirrorEngine
import com.pyrochat.mirror.MirrorLink
import com.pyrochat.service.ChatService
import com.pyrochat.service.RelayHttp
import com.pyrochat.service.access.AccessKeyClient
import com.pyrochat.service.access.StoredAccessTokens
import com.pyrochat.store.Backup
import com.pyrochat.store.model.Blob
import com.pyrochat.store.SecureStore
import com.pyrochat.store.StoreError
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import com.pyrochat.store.model.ChatMessage as StoreMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The real [Backend]: `AppModel` (`App/PyroChatApp.swift`) over `core-service`'s [ChatService],
 * [StorePaths] (the Keystore-salted store + native KDF), [AccessKeyClient], the desktop-link
 * [MirrorEngine] and the platform services. One instance per process; a [Session] per unlocked
 * identity, exactly like `AppModel.service: ChatService?`.
 *
 * Threading: the UI calls every member from the main thread. Suspend members hop into the
 * service's single-threaded actor (`ChatService.actor`) or onto `Dispatchers.Default` for the
 * KDF; fire-and-forget members are launched on [mainScope] — `Dispatchers.Main.immediate`, so
 * successive calls reach the actor in call order, the way `Task { await service.… }` does on the
 * main actor. Nothing blocks the main thread: the KDF (`unlock`, `setDuressPin`, backup
 * export/restore) and the service teardown (`lock`) run on background dispatchers.
 *
 * State flows re-publish the service's flows in UI types ([Mapping]); they are reset when the
 * session ends so a locked app shows nothing.
 */
class RealBackend(
    context: Context,
    private val paths: StorePaths,
    private val deadman: DeadmanSwitch,
    private val haptics: Haptics?,
    /** docs/BIOMETRIC-UNLOCK.md — the Keystore-wrapped copy of the store key; tests inject a fake. */
    private val biometrics: BiometricVault = BiometricVault(context),
    private val mainScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e(TAG, "backend coroutine failed", e) },
    ),
) : Backend {
    private val appContext = context.applicationContext

    /** `AppModel.service` + its store; `job` collects the service flows into the UI flows. */
    private class Session(val store: SecureStore, val svc: ChatService, val source: ServiceMirrorSource, val job: Job)

    @Volatile
    private var session: Session? = null

    init {
        // The default network came back or switched: the link recovers now instead of waiting out
        // the backoff. This reads the OS's view of its own interfaces and contacts nobody.
        runCatching {
            appContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val s = session ?: return
                        mainScope.launch { runCatching { s.svc.noteNetworkChanged() } }
                    }
                },
            )
        }.onFailure { Log.w(TAG, "network callback unavailable: ${it.javaClass.simpleName}") }
    }

    /** The teardown of the last [lock]/[totalWipe]; a new unlock waits for it. */
    @Volatile
    private var teardown: Job? = null

    /**
     * DEBUG launch overrides (`--es relay … --es billing … --es socks …`), applied INTO the store
     * at every create/unlock like iOS `--demo-relay` / `--demo-socks` (`PyroChatApp.swift:205-231`).
     */
    @Volatile
    var serverOverride: ServerConfig.Override? = null

    /** `AutoLock` — driven by [noteBackground] / [noteForeground] from the activity lifecycle. */
    val autoLock = AutoLockTimer()

    private val mirrorEngine = MirrorEngine(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** Operator notices between launches: plain preferences, outside the encrypted store (see [PrefsNoticeStore]). */
    private val noticeStore = PrefsNoticeStore(appContext)

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
    private val _duressSet = MutableStateFlow(false)
    /** docs/BIOMETRIC-UNLOCK.md §2: derived from whether the wrapped key exists, never from the blob. */
    private val _biometricEnabled = MutableStateFlow(false)
    /** docs/SCRUB-AND-WIPE.md §A2 — consecutive wrong typed passwords, mirrored out of the prefs file for the screen. */
    private val _unlockFailures = MutableStateFlow(0)
    /**
     * docs/DECOY-ACCOUNT.md §1, "A store that still will not fit: never trim without being asked" —
     * non-null when this account is larger than a slot half even after the §1a voice spill, so the
     * store stayed in its legacy shape. Nothing was written and nothing was deleted; `[cfg]` says
     * so in one line and offers nothing destructive.
     */
    private val _storeTooLarge = MutableStateFlow<UiStoreTooLarge?>(null)
    /**
     * §1/§5 — slots this PROCESS knows are free, in RAM and nowhere else: a slot it burned itself,
     * or what an account it had open told it out of that account's own record. Never written down;
     * gone when the process dies, exactly like the key was.
     *
     * It is what lets CREATE IDENTITY work after a burn without the app ever having to say whether
     * anything is there. With no such knowledge, creating refuses in the ordinary words.
     */
    @Volatile
    private var knownFreeSlots: List<Int>? = null
    /** docs/SCRUB-AND-WIPE.md §B2 — message id → what its attachment lost. RAM only, like the frames. */
    private val scrubReceipts = java.util.concurrent.ConcurrentHashMap<String, UiScrubReceipt>()
    private val _tones = MutableSharedFlow<Tone>(extraBufferCapacity = 8)
    private val _groups = MutableStateFlow<List<UiGroup>>(emptyList())
    private val _groupTyping = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val _goneMembers = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val _formerMembers = MutableStateFlow<Map<String, List<UiMember>>>(emptyMap())
    private val _flash = MutableStateFlow<String?>(null)
    private var flashJob: Job? = null
    /** A plaintext the user asked to export: MainActivity opens the system "save as" picker and writes it where they choose. */
    class SaveRequest(val name: String, val mime: String, val bytes: ByteArray)
    private val _saveRequests = MutableSharedFlow<SaveRequest>(extraBufferCapacity = 4)

    override val stage: StateFlow<Stage> = _stage.asStateFlow()
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    override val connState: StateFlow<ConnState> = _conn.asStateFlow()
    private val _senderHidden = MutableStateFlow(SenderHidden.NOT_OFFERED)
    override val senderHidden: StateFlow<SenderHidden> = _senderHidden.asStateFlow()
    override val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    override val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()
    override val typingPeers: StateFlow<Set<String>> = _typing.asStateFlow()
    private val _voiceBeingChanged = MutableStateFlow<Set<String>>(emptySet())
    override val voiceBeingChanged: StateFlow<Set<String>> = _voiceBeingChanged.asStateFlow()

    /** How many notes are being changed right now, by thread. Main thread only, like the rest of `sendVoice`. */
    private val voiceWork = HashMap<String, Int>()
    override val blobProgress: StateFlow<Map<String, Float>> = _blobProgress.asStateFlow()
    override val rxTransfers: StateFlow<List<RxTransfer>> = _rx.asStateFlow()
    override val blockedHashes: StateFlow<Set<String>> = _blocked.asStateFlow()
    override val mirror: StateFlow<MirrorState> = _mirror.asStateFlow()
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()
    override val notices: StateFlow<List<UiNotice>> = _notices.asStateFlow()
    override val unseenNotices: StateFlow<Int> = _unseenNotices.asStateFlow()
    override val updateAvailable: StateFlow<UiUpdate?> = _updateAvailable.asStateFlow()
    override val accessDaysRemaining: StateFlow<Int> = _accessDays.asStateFlow()
    override val duressPinSet: StateFlow<Boolean> = _duressSet.asStateFlow()
    override val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()
    override val unlockFailures: StateFlow<Int> = _unlockFailures.asStateFlow()
    override val storeTooLarge: StateFlow<UiStoreTooLarge?> = _storeTooLarge.asStateFlow()
    override val tones: SharedFlow<Tone> = _tones.asSharedFlow()
    override val groups: StateFlow<List<UiGroup>> = _groups.asStateFlow()
    override val groupTyping: StateFlow<Map<String, Set<String>>> = _groupTyping.asStateFlow()
    override val goneMembers: StateFlow<Map<String, Set<String>>> = _goneMembers.asStateFlow()
    override val formerMembers: StateFlow<Map<String, List<UiMember>>> = _formerMembers.asStateFlow()
    override val flash: StateFlow<String?> = _flash.asStateFlow()

    /** Backup exports: a share-sheet intent the activity launches (iOS `ShareSheet(url:)`). */
    val saveRequests: SharedFlow<SaveRequest> = _saveRequests.asSharedFlow()
    override val exportStatus: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Outcome of the save picker for the backup export (iOS `ExportPicker.onComplete`). */
    fun noteSaveOutcome(saved: Boolean) {
        exportStatus.value = if (saved) "\u2713 backup saved" else "export cancelled"
        // docs/STATUS-AND-CFG.md §1 — the status screen's `backup` row. Recorded HERE and nowhere
        // earlier: `exportBackup` only produces bytes, and a cancelled save picker leaves nothing
        // on disk to restore from. A date written for a backup that was never written would make
        // the one row on that screen whose job is to worry the reader say the reassuring thing.
        if (saved) updateSettings { it.copy(lastBackupAt = System.currentTimeMillis()) }
    }

    override val myUsername: String get() = session?.svc?.myUsername ?: ""
    override val myHash: String get() = session?.svc?.myHash ?: ""

    init {
        mainScope.launch { mirrorEngine.state.collect { _mirror.value = Mapping.mirrorState(it) } }
        mainScope.launch(Dispatchers.IO) { _duressSet.value = runCatching { paths.duressPinSet() }.getOrDefault(false) }
        _biometricEnabled.value = runCatching { biometrics.enabled }.getOrDefault(false)
        // §A1: "It survives app restarts" — the screen reads it back before anything is decrypted
        _unlockFailures.value = runCatching { biometrics.unlockFailures }.getOrDefault(0)
    }

    // ---- lifecycle ------------------------------------------------------------------------

    /**
     * `AppModel.finishBoot()`.
     *
     * docs/DECOY-ACCOUNT.md §5: the container is authored HERE, at first run, before there is any
     * account — three slots of noise, once per device. From then on nothing deletes it, so a
     * device that was wiped, one that was never used and one holding two accounts are the same
     * 12,582,925 bytes, and this screen has nothing to read a difference off. It therefore asserts
     * nothing: [Stage.Auth] carries no claim about whether an identity exists.
     */
    override fun finishBoot() {
        mainScope.launch(Dispatchers.IO) { runCatching { paths.ensureContainer() } }
        _stage.value = Stage.Auth
    }

    /** `submitAuth` create branch (`PyroChatApp.swift:222-243`). */
    override suspend fun createAccount(username: String, password: String, relay: String): String? {
        teardown?.join()
        if (session != null) return null
        val reclaim = knownFreeSlots
        // a new identity never inherits an old identity's biometric item (§5, "a password change")
        disableBiometricUnlock()
        // docs/DECOY-ACCOUNT.md §1/§5: the container outlives every wipe, so "does a store exist"
        // is no longer a question the app may answer — and no longer one worth asking. What it may
        // do is write into a slot it KNOWS is free: every slot of a container it has just authored
        // itself, or the slots an account it opened in this process told it about. With neither it
        // refuses in the ordinary words, asserting nothing about what the container holds.
        // docs/DECOY-ACCOUNT.md §1, "Creating an identity from cold": with no free-slot knowledge
        // the app takes a slot at random rather than refusing, because the container outlives every
        // wipe and refusing would mean a device the duress PIN fired on could never hold an account
        // again. The confirmation that makes that safe is shown by the auth screen, every time and
        // unconditionally, before this is ever called.
        val store = try {
            withContext(Dispatchers.Default) { paths.create(password, knownFree = reclaim, blind = true) }
        } catch (e: StoreError) {
            Log.i(TAG, "create refused: ${e.message}")
            return Mapping.ERR_NO_ROOM
        }
        knownFreeSlots = null
        ServerConfig.relayForNewIdentity(relay)?.let { r -> store.update { it.copy(settings = it.settings.copy(relayURL = r)) } }
        applyOverride(store)
        val svc = newService(store)
        try {
            svc.register(username)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // registration failed (e.g. username taken): roll back — `service = nil; SecureStore.obliterate()`
            Log.w(TAG, "registration failed: ${Mapping.errorText(e)}", e)
            withContext(Dispatchers.IO) {
                runCatching { svc.close() }
                store.close()
                // §5: a container with other slots in it is never obliterated to undo ONE identity
                val burned = store.slot
                runCatching { store.burnSlot() }
                knownFreeSlots = ((reclaim ?: emptyList()) + burned).distinct()
            }
            return Mapping.errorText(e)
        }
        attach(store, svc)
        biometrics.notePasswordUnlock() // §4: the 7-day clock starts at the first password
        _unlockFailures.value = 0       // SCRUB-AND-WIPE.md §A1: creating an identity resets the count
        // FIRST-RUN.md §1: the moment CREATE IDENTITY succeeded — never after sign-in, restore or import —
        // add `pyrobot` exactly as `[+]` → pyrobot would (prekey fetch + session), best effort: a failure
        // (offline, a private relay without a bot) shows nothing and is not retried
        mainScope.launch { runCatching { svc.addContact(HomeRules.PYROBOT) } }
        return null
    }

    /** `submitAuth` unlock branch: duress PIN FIRST (`PyroChatApp.swift:187-221`). */
    override suspend fun unlock(password: String): String? {
        teardown?.join()
        if (session != null) return null
        val duress = withContext(Dispatchers.Default) { paths.matchesDuressPin(password) }
        if (duress) {
            // the cover story: the device now genuinely looks like a fresh install
            PushRegistration.detach()
            withContext(Dispatchers.IO) { wipeLocal(LocalWipe.Reason.DURESS) }
            _duressSet.value = false
            _stage.value = Stage.Auth
            return Mapping.ERR_DURESS_COVER
        }
        val store = try {
            withContext(Dispatchers.Default) { paths.open(password) }
        } catch (e: StoreError) {
            // docs/SCRUB-AND-WIPE.md §A1 — the ONE place the wrong-password counter moves.
            // §A2: the tenth failure lands on the create screen with no message and no explanation.
            return if (withContext(Dispatchers.IO) { noteWrongPassword() }) null else Mapping.unlockErrorText(e)
        }
        applyOverride(store)
        val svc = newService(store)
        attach(store, svc)
        biometrics.notePasswordUnlock() // docs/BIOMETRIC-UNLOCK.md §4 + SCRUB-AND-WIPE.md §A1 (both counters reset)
        _unlockFailures.value = 0
        mainScope.launch { runCatching { svc.goOnline() }.onFailure { Log.w(TAG, "goOnline failed: ${Mapping.errorText(it)}", it) } } // `Task { try? await svc.goOnline() }`
        return null
    }

    /** `AppModel.submitRestore` (`PyroChatApp.swift:139-166`). */
    override suspend fun restoreBackup(backup: ByteArray, passphrase: String, newPassword: String): String? {
        teardown?.join()
        if (session != null) return null
        val free = knownFreeSlots
        val payload = try {
            withContext(Dispatchers.Default) { Backup.decode(backup, passphrase, paths.kdf) }
        } catch (e: StoreError) {
            return Mapping.restoreErrorText(e)
        }
        val blob = payload.blob
        if (blob.usernameHash.isEmpty() || blob.identityKeyPair.bytes.isEmpty()) return Mapping.ERR_BACKUP_NO_IDENTITY
        // the restored identity gets a new password on this device: §5 "a password change replaces it"
        disableBiometricUnlock()
        val store = try {
            withContext(Dispatchers.Default) { paths.restore(blob, payload.attachments.mapValues { it.value.bytes }, newPassword, knownFree = free, blind = true) }
        } catch (e: StoreError.NoFreeSlot) {
            return Mapping.ERR_NO_ROOM      // §5: the same ordinary words, asserting nothing
        } catch (e: StoreError) {
            return Mapping.errorText(e)
        }
        knownFreeSlots = null
        applyOverride(store)
        val svc = newService(store)
        attach(store, svc)
        biometrics.notePasswordUnlock() // §4
        _unlockFailures.value = 0       // SCRUB-AND-WIPE.md §A1: restoring an identity resets the count
        mainScope.launch { runCatching { svc.goOnline() }.onFailure { Log.w(TAG, "goOnline failed: ${Mapping.errorText(it)}", it) } }
        return null
    }

    /** `AppModel.lock()`: shutdown → drop the service → sweep tmp → `.auth` (`PyroChatApp.swift:171-178`). */
    override fun lock() {
        val s = session ?: return
        session = null
        // §1: what this account knew about free slots stays in RAM for this process, so CREATE
        // IDENTITY still works after a lock without the app ever recording occupancy anywhere.
        knownFreeSlots = runCatching { s.store.freeSlots }.getOrNull()?.takeIf { it.isNotEmpty() }
        // §6.6: a badge computed from THIS slot must not keep rendering once the slot is closed
        PushRegistration.clearNotifications()
        mirrorEngine.stop("unlink") // ChatService.shutdown() on iOS: never leave a live mirror
        s.job.cancel()
        _settings.value = Mapping.settings(s.store.blob.settings) // bootSettings: keep the theme on the auth screen
        resetSessionState()
        _stage.value = Stage.Auth
        teardown = mainScope.launch(Dispatchers.IO) {
            runCatching { s.svc.close() } // socket, timers, tasks; flushes the store
            s.store.close()
            paths.sweepPlaintextTmp()
        }
    }

    override suspend fun goOnline() {
        val s = session ?: return
        runCatching { s.svc.goOnline() }
    }

    override fun goOffline() {
        val s = session ?: return
        mainScope.launch { s.svc.goOffline() }
    }

    /**
     * SettingsView TOTAL WIPE → BURN (`SettingsView.swift:376-385`); `exit(0)` becomes a return to
     * boot.
     *
     * docs/DECOY-ACCOUNT.md §5 narrows the ONE voluntary path: `[ BURN EVERYTHING ]` destroys the
     * CURRENT SLOT and reports success in the usual words, so a person made to wipe under
     * supervision loses only the account they were made to show. The three involuntary paths — the
     * duress PIN, ten wrong passwords, the dead-man switch — stay total and are untouched: a
     * partial wipe under duress would leave the interesting slot behind, which is the opposite of
     * what they are for.
     *
     * **There is deliberately no "this account is alone, so take the whole device" case.** An
     * earlier draft had one and it was wrong: behaving differently in the common case is exactly
     * what makes the uncommon case legible. Every burn leaves the same [StoreContainer.FILE_BYTES]
     * behind, every slot this account cannot open keeps its bytes, and the screen afterwards is
     * the one a launch shows — which, since §5, claims nothing either way.
     *
     * Device-level state (the shared salt, the duress verifier, the dead-man deadline, the
     * wrong-password counter) belongs to every slot and is left alone: clearing it here would
     * reach into an account this one is not allowed to know exists.
     *
     * **The stored biometric key is the one exception, and it goes ALWAYS** — see the call below.
     */
    override suspend fun totalWipe() {
        teardown?.join()
        val s = session
        mirrorEngine.stop("unlink") // a wiped phone must not leave a live mirror
        if (s != null) {
            val burned = s.store.slot
            val free = runCatching { s.store.freeSlots }.getOrDefault(emptyList())
            session = null
            runCatching { s.svc.deregister() } // relay forgets keys, queue, push token (online only)
            PushRegistration.detach()
            s.job.cancel()
            resetSessionState()
            withContext(Dispatchers.IO) {
                runCatching { s.svc.close() } // goOffline + flush
                s.store.close()               // stop the save queue BEFORE the slot goes
                runCatching { s.store.burnSlot() }
                // docs/DECOY-ACCOUNT.md §5 "The burn must take the biometric key with it": ALWAYS,
                // even when the key belongs to a slot this burn is not touching.
                //
                // This used to be guarded by `ownsStoredBiometricKey`, so that a decoy's burn could
                // not quietly disable the real account's face unlock. That kindness was §5's
                // aftermath leak coming back through the one piece of device state §5 had exempted:
                // a phone that still raises a fingerprint prompt at launch has something left, and
                // a phone with nothing does not — so someone made to wipe, who then reopens the app,
                // reads the answer straight off the launch screen. Since wave 3 the launch screen is
                // the password form on every device, which closes every other channel there and
                // leaves this one as the whole difference.
                //
                // The cost is re-enabling biometric unlock after burning a decoy — small,
                // recoverable, and reasonable after something that drastic. Behaving differently in
                // the common case is precisely what makes the uncommon case legible.
                //
                // SHRED rather than destroy: the last-password timestamp goes too, so this path
                // leaves the same bytes in `pyro-biometric` as the three total ones. A value that
                // survives one kind of wipe and not another is readable by anyone holding two
                // snapshots of the file, which is the whole class of difference §5 removes. The one
                // key it spares is §A1's wrong-password counter — see the method, and §5's
                // "Device-level state a narrowed burn must not touch".
                runCatching { biometrics.shredSparingWrongPasswordCount() }
            }
            knownFreeSlots = (free + burned).distinct()
        } else {
            PushRegistration.detach()
        }
        _settings.value = AppSettings()
        _accessDays.value = 0
        _biometricEnabled.value = runCatching { biometrics.enabled }.getOrDefault(false)
        _stage.value = Stage.Auth
    }

    // ---- contacts ---------------------------------------------------------------------------

    override suspend fun addContact(username: String) {
        val s = session ?: throw BackendException("locked")
        try {
            s.svc.addContact(username)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackendException(Mapping.errorText(e))
        }
    }

    override fun renameContact(hash: String, name: String) {
        val s = session ?: return
        mainScope.launch { s.svc.renameContact(hash, name) }
    }

    override fun deleteContact(hash: String) {
        val s = session ?: return
        haptics?.warn()
        mainScope.launch { s.svc.deleteContact(hash) }
    }

    override fun blockContact(hash: String) {
        val s = session ?: return
        mainScope.launch { s.svc.blockContact(hash) }
    }

    override fun unblockContact(hash: String) {
        val s = session ?: return
        mainScope.launch { s.svc.unblockContact(hash) }
    }

    override suspend fun reportAndBlock(hash: String) {
        val s = session ?: return
        val contact = s.svc.contacts.value.firstOrNull { it.hash == hash } ?: return
        haptics?.warn()
        s.svc.reportAndBlock(contact)
    }

    override fun setVerified(verified: Boolean, hash: String) {
        val s = session ?: return
        mainScope.launch { s.svc.setVerified(verified, hash) }
    }

    override fun safetyNumber(hash: String): String? = session?.svc?.safetyNumber(hash)?.digits

    override fun safetyNumberQR(hash: String): ByteArray? = session?.svc?.safetyNumber(hash)?.scannable

    override fun myAddQRPayload(): String? = session?.svc?.myAddQRPayload()

    /** `AddByQRView.handle` (`AddByQRView.swift:147-167`): `Haptics.tap()` on success, `Haptics.warn()` on failure. */
    override suspend fun addContactViaQR(username: String, identityKey: ByteArray): Boolean {
        val s = session ?: throw BackendException("locked")
        try {
            val verified = s.svc.addContactViaQR(username, identityKey)
            haptics?.tap()
            return verified
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            haptics?.warn()
            throw BackendException(Mapping.errorText(e))
        }
    }

    override fun setBurnDefault(seconds: Long?, hash: String) {
        val s = session ?: return
        mainScope.launch { s.svc.setBurnDefault(seconds?.toDouble(), hash) }
    }

    // ---- group channels (docs/GROUPS.md) — every refusal surfaces the service's verbatim text ------

    private suspend fun <T> groupCall(block: suspend (ChatService) -> T): T {
        val s = session ?: throw BackendException("locked")
        try {
            return block(s.svc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackendException(Mapping.errorText(e))
        }
    }

    override suspend fun createGroup(name: String, members: List<String>, policy: String, burnSeconds: Long?): String =
        groupCall { it.createGroup(name, members, Mapping.policy(policy), burnSeconds?.toDouble()).id }.also { haptics?.tap() }

    override suspend fun addMember(groupId: String, hash: String) = groupCall { it.addMember(groupId, hash) }

    override suspend fun removeMember(groupId: String, hash: String) {
        haptics?.warn()
        groupCall { it.removeMember(groupId, hash) }
    }

    override suspend fun leaveGroup(groupId: String) {
        haptics?.warn()
        groupCall { it.leaveGroup(groupId) }
    }

    override suspend fun renameGroup(groupId: String, name: String) = groupCall { it.renameGroup(groupId, name) }

    override suspend fun setGroupPolicy(groupId: String, policy: String, burnSeconds: Long?) =
        groupCall { it.setGroupPolicy(groupId, Mapping.policy(policy), burnSeconds?.toDouble()) }

    override suspend fun burnGroup(groupId: String) {
        haptics?.warn()
        groupCall { it.burnGroup(groupId) }
    }

    override fun deleteGroup(groupId: String) {
        val s = session ?: return
        haptics?.warn()
        mainScope.launch { s.svc.deleteGroup(groupId) }
    }

    // ---- messages ---------------------------------------------------------------------------

    /**
     * Runs [work] on the backend's own scope and waits for it. The caller is a screen, and a
     * screen's scope ends when the screen closes; what it started here has to be finished all the
     * same. An upload waits for the socket between chunks, a group message for the keys of a member
     * never written to, a delete-for-both for the relay's word: closed in such a wait, the work used
     * to stop where it stood. Now only the caller's waiting ends. iOS starts the same work from a
     * `Task` of its own, which a closing view does not end.
     */
    private suspend fun <T> outlivingTheScreen(work: suspend () -> T): T = mainScope.async { work() }.await()

    override suspend fun sendText(text: String, hash: String) {
        val s = session ?: return
        haptics?.tap() // ChatView `[TX]`
        outlivingTheScreen { s.svc.sendText(text, hash) }
    }

    /** `ChatService.sendImage(jpeg:width:height:to:)` — the caller hands in an `ImageNormalizer` JPEG. */
    override suspend fun sendImage(jpeg: ByteArray, width: Int, height: Int, hash: String, scrub: UiScrubReceipt?) {
        val s = session ?: return
        if (jpeg.isEmpty()) throw BackendException("ERR: could not read image")
        haptics?.tap()
        outlivingTheScreen { recordingScrub(s, hash, scrub) { s.svc.sendImage(jpeg, width, height, hash) } }
    }

    /** `ChatService.sendFile(data:filename:mime:to:)` — the UI gate (`AttachmentGate`) refuses first; the service re-checks. */
    override suspend fun sendFile(data: ByteArray, filename: String, mime: String, hash: String, scrub: UiScrubReceipt?) {
        val s = session ?: return
        if (data.isEmpty()) throw BackendException("ERR: could not read file")
        haptics?.tap()
        outlivingTheScreen { recordingScrub(s, hash, scrub) { s.svc.sendFile(data, filename, mime, hash) } }
    }

    /**
     * docs/SCRUB-AND-WIPE.md §B2 — attach the receipt to the row the send just created.
     *
     * The service mints the message id inside its actor and does not hand it back, so the row is
     * identified the only honest way available from out here: the one id in this thread that was
     * not there a moment ago. The send has already returned from the actor, so the row exists.
     * Nothing is written to the store — a receipt is RAM, like a captured frame.
     */
    private suspend fun recordingScrub(s: Session, hash: String, scrub: UiScrubReceipt?, send: suspend () -> Unit) {
        if (scrub == null) {
            send()
            return
        }
        val before = s.svc.messages.value[hash].orEmpty().mapTo(HashSet()) { it.id }
        send()
        val fresh = s.svc.messages.value[hash].orEmpty().lastOrNull { it.id !in before } ?: return
        if (scrubReceipts.size >= MAX_SCRUB_RECEIPTS) scrubReceipts.keys.take(scrubReceipts.size - MAX_SCRUB_RECEIPTS + 1).forEach(scrubReceipts::remove)
        scrubReceipts[fresh.id] = scrub
    }

    /**
     * `ChatService.sendVoice(_:duration:to:)` — the recorder's AAC bytes, in the voice the settings
     * name (docs/VOICE-CHANGER.md). An empty capture is refused, never sent; and so is a note whose
     * voice could not be changed: it does not go out as it was spoken.
     */
    override suspend fun sendVoice(audio: ByteArray, duration: Double, hash: String) {
        val s = session ?: return
        if (audio.isEmpty() || duration < 0.5) throw BackendException("ERR: nothing recorded")
        val voice = settings.value.voice // the one chosen as [SEND] is tapped
        // Changing the voice of a long note takes a moment, and that moment is part of the send: it
        // too is done on the backend's own scope, so the note still goes if the chat is closed
        // while its voice is being changed.
        outlivingTheScreen {
            val note = sayingSoMeanwhile(hash, voice) { inVoice(audio, voice) }
            if (session !== s) return@outlivingTheScreen // locked or wiped meanwhile: that account sends nothing more
            haptics?.tap()
            s.svc.sendVoice(note, duration, hash)
        }
    }

    /**
     * [voiceBeingChanged] names [thread] for as long as [change] takes: the note has no row yet,
     * and a long one takes a moment, so the composer says so. A note that goes out as it was
     * spoken is not named.
     */
    private suspend fun sayingSoMeanwhile(thread: String, voice: VoiceEffect, change: suspend () -> ByteArray): ByteArray {
        if (voice == VoiceEffect.OFF) return change()
        voiceWork[thread] = (voiceWork[thread] ?: 0) + 1
        _voiceBeingChanged.value = voiceWork.keys.toSet()
        try {
            return change()
        } finally {
            val left = (voiceWork[thread] ?: 1) - 1
            if (left > 0) voiceWork[thread] = left else voiceWork.remove(thread)
            _voiceBeingChanged.value = voiceWork.keys.toSet()
        }
    }

    /** The recorder's note as it will leave. Throws rather than hand back the note as it was spoken. */
    private suspend fun inVoice(spoken: ByteArray, voice: VoiceEffect): ByteArray {
        if (voice == VoiceEffect.OFF) return spoken
        return try {
            withContext(Dispatchers.Default) { VoiceChanger.change(appContext, spoken, voice) }
        } catch (_: VoiceChanger.Failed) {
            throw BackendException(VoiceChanger.REFUSAL)
        }
    }

    /** FAILED `[RETRY]`: `retryText` for text rows, `retryBlob` for voice/image/file. */
    override suspend fun retry(messageId: String, hash: String) {
        val s = session ?: return
        val row = s.svc.messages.value[hash]?.firstOrNull { it.id == messageId } ?: return
        outlivingTheScreen {
            when (row.kind) {
                StoreMessage.Kind.TEXT -> s.svc.retryText(messageId, hash)
                StoreMessage.Kind.VOICE, StoreMessage.Kind.IMAGE, StoreMessage.Kind.FILE -> s.svc.retryBlob(messageId, hash)
                StoreMessage.Kind.SYSTEM -> {}
            }
        }
    }

    override fun deleteMessage(messageId: String, hash: String) {
        val s = session ?: return
        mainScope.launch { s.svc.deleteMessage(messageId, hash) }
    }

    override suspend fun deleteForBoth(messageId: String, hash: String): Boolean {
        val s = session ?: return false
        haptics?.warn()
        return outlivingTheScreen { s.svc.deleteForBoth(messageId, hash) }
    }

    override suspend fun burnThread(hash: String) {
        val s = session ?: return
        val contact = s.svc.contacts.value.firstOrNull { it.hash == hash } ?: return
        haptics?.warn()
        s.svc.burnThread(contact)
    }

    override fun attachmentData(message: ChatMessage): ByteArray? {
        val s = session ?: return null
        val row = storeRow(s, message.id) ?: return null
        return s.svc.attachmentData(row)
    }

    override fun voiceData(message: ChatMessage): ByteArray? {
        val s = session ?: return null
        val row = storeRow(s, message.id) ?: return null
        return s.svc.voiceContent(row)
    }

    override fun voiceBars(message: ChatMessage): FloatArray {
        val s = session ?: return Waveform.placeholder()
        val row = storeRow(s, message.id) ?: return Waveform.placeholder()
        val bytes = s.svc.voiceContent(row) ?: return Waveform.placeholder()
        return Waveform.bars(message.id, bytes)
    }

    /** docs/MESSAGE-DETAILS.md §3 — the service's RAM capture; null once it is gone (lock, restart, eviction). */
    override fun capturedFrame(messageId: String): UiFrame? =
        session?.svc?.capturedFrame(messageId)?.let(Mapping::frame)

    /** docs/SCRUB-AND-WIPE.md §B2 — this process's receipts; empty again after a lock or a wipe. */
    override fun scrubReceipt(messageId: String): UiScrubReceipt? = scrubReceipts[messageId]

    /**
     * docs/DECOY-ACCOUNT.md §1 — one of the moments a [Durability.LAZY] change is allowed to cost
     * 2 MiB: the composer losing focus, the chat screen being left, the app backgrounding. Runs off
     * the main thread; a store with nothing pending writes nothing at all.
     */
    override fun persistDeferred() {
        val s = session ?: return
        mainScope.launch(Dispatchers.IO) { runCatching { s.store.flush() } }
    }

    override fun setActiveThread(hash: String?) {
        val s = session ?: return
        mainScope.launch { s.svc.setActiveThread(hash) }
    }

    /** Opening the `<pyrochat>` channel: the service persists the seen ids through [noticeStore]. */
    override fun markNoticesSeen() {
        val s = session ?: return
        mainScope.launch { s.svc.markNoticesSeen() }
    }

    override fun needsVisibilityReport(message: ChatMessage): Boolean {
        val s = session ?: return false
        // cheap pre-check on the UI row before touching the service's copy
        if (message.direction != Direction.incoming || message.kind == Kind.system) return false
        if (message.status == Status.read && !(message.burnAfter != null && message.burnStartedAt == null)) return false
        val row = storeRow(s, message.id) ?: return false
        return s.svc.needsVisibilityReport(row)
    }

    override fun noteRowVisible(messageId: String, hash: String) {
        val s = session ?: return
        mainScope.launch { s.svc.noteRowVisible(messageId, hash) }
    }

    override fun noteComposerActivity(hash: String) {
        val s = session ?: return
        mainScope.launch { s.svc.noteComposerActivity(hash) }
    }

    override fun draft(hash: String): String = session?.svc?.draft(hash) ?: ""

    override fun setDraft(text: String, hash: String) {
        val s = session ?: return
        mainScope.launch { s.svc.setDraft(text, hash) }
    }

    // ---- settings ---------------------------------------------------------------------------

    /** `service.store.update { $0.settings = … }` — pre-auth only the in-RAM boot settings change. */
    override fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val s = session
        if (s == null) {
            _settings.update(transform)
            return
        }
        s.store.update { blob -> blob.copy(settings = Mapping.settings(transform(Mapping.settings(blob.settings)))) }
        val next = s.store.blob.settings
        _settings.value = Mapping.settings(next)
        deadman.arm(next.wipeAfterDays) // the EXPLICIT choice, in this account: `arm`, not `notePresence`
    }

    override fun setDuressPin(pin: String) {
        mainScope.launch(Dispatchers.Default) {
            paths.setDuressPin(pin)
            _duressSet.value = true
        }
    }

    /** `ChatService.redeemAccessKey` (`ChatService.swift:431-445`) over the app's ONE proxied client. */
    override suspend fun redeemAccessKey(code: String) {
        val s = session ?: throw BackendException("locked")
        val settings = s.store.blob.settings
        val client = AccessKeyClient(RelayHttp.makeClient(settings.socksProxy))
        val tokens = try {
            client.redeem(code.trim(), settings.billingURL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackendException(Mapping.errorText(e))
        }
        s.store.update { it.copy(accessTokens = StoredAccessTokens.afterRedeem(it.accessTokens, tokens)) }
        _accessDays.value = s.svc.accessDaysRemaining
    }

    /** `BackupService.export` → the system save picker (`SettingsView.swift` ExportPicker); the user picks the folder. */
    override suspend fun exportBackup(passphrase: String): Int {
        val s = session ?: throw BackendException("locked")
        val data = try {
            withContext(Dispatchers.Default) { Backup.export(s.store, passphrase, paths.kdf) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackendException("export failed")
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT).format(LocalDateTime.now())
        // no staging on disk: the bytes go straight to the document the user picks
        _saveRequests.tryEmit(SaveRequest(Mapping.backupFileName(stamp), BACKUP_MIME, data))
        return data.size
    }

    /** `LinkDesktopView.handle` (`LinkDesktopView.swift:122-151`) + `ChatService.startMirror`. */
    override fun startMirror(rawCode: String): String? {
        val s = session ?: return "link failed: locked"
        val raw = rawCode.trim()
        val link = try {
            MirrorLink.parse(raw, s.store.blob.settings.relayURL)
        } catch (e: MirrorLink.ParseError) {
            if (e is MirrorLink.ParseError.WrongRelay) haptics?.warn()
            return Mapping.linkParseErrorText(raw, e)
        }
        return try {
            haptics?.tap()
            mirrorEngine.start(link, s.source)
            null
        } catch (e: Exception) {
            "link failed: ${e.message ?: e}"
        }
    }

    override fun stopMirror() {
        mirrorEngine.stop("unlink")
    }

    /**
     * `sendPushToken(nil)` after the wake-push toggle. On: send the FCM token if it is already
     * known (otherwise `PushRegistration.onToken` sends it the moment `WakePushUi` fetches it).
     * Off: `PushDelegate.detach()` (deleteToken, clear the shade, badge 0) + an empty `push_token`
     * — the relay forgets every platform.
     */
    override suspend fun syncPushToken() {
        val s = session ?: return
        if (s.store.blob.settings.pushEnabled) {
            val token = PushRegistration.currentToken ?: return
            runCatching { s.svc.sendPushToken(token) }
        } else {
            PushRegistration.detach()
            runCatching { s.svc.sendPushToken(null) } // pushEnabled=false ⇒ "" ⇒ unregister
        }
    }

    // ---- the decoy (docs/DECOY-ACCOUNT.md §3) ------------------------------------------------

    /**
     * §3 `set decoy password …`: a genuine second account — real identity keys, real relay
     * registration — in a slot THIS account knows is free. Returns null on success, or the app's
     * ordinary words for a thing that did not work.
     *
     * The order is crash-safe in the one direction that matters. [SecureStore.authorSlot] records
     * the allocation and flushes it BEFORE a byte of the slot moves, so a crash costs a free slot
     * rather than letting a later allocation land on top of the account just written. A relay that
     * refuses the username unwinds both: the slot goes back to noise and the index is given up, so
     * a typo does not cost the one decoy §1 allows.
     *
     * Everything about this is identical in the real account and in a decoy except the outcome:
     * a decoy knows of no free slot, so it gets [Mapping.ERR_DECOY_NO_ROOM] — the same words the
     * real account gets once its one decoy exists.
     */
    /**
     * §3 — does THIS account already own a decoy? Only the account that made one can know, and it
     * is read after the row is tapped, never by the row itself: the row stays identical everywhere.
     */
    override fun decoyExists(): Boolean = runCatching { session?.store?.allocatedDecoySlot != null }.getOrDefault(false)

    override suspend fun setDecoyPassword(username: String, password: String): String? {
        val s = session ?: return Mapping.ERR_DECOY_NO_ROOM
        val name = username.trim()
        if (name.isEmpty() || password.isEmpty()) return Mapping.ERR_DECOY_NO_ROOM
        // §1: an account that never became a container has nowhere to put a second one
        if (runCatching { s.store.legacyFormat }.getOrDefault(true)) return Mapping.ERR_DECOY_STORE_TOO_LARGE
        val decoyKey = withContext(Dispatchers.Default) { paths.deriveKey(password) }
        try {
            val mine = s.store.storeKey
            val same = try {
                MessageDigest.isEqual(decoyKey, mine)
            } finally {
                mine.fill(0)
            }
            // §3: "a password, which must not be the real one"
            if (same) return Mapping.ERR_DECOY_SAME_PASSWORD
            // §4: the decoy is a full PyroChat with its OWN preferences — a theme, a text size or a
            // toggle carried across would be exactly the leak §4 forbids. Only the server addresses
            // come with it, because a decoy showing LINK DOWN fails the one moment it exists for.
            val seed = Blob(
                settings = com.pyrochat.store.model.AppSettings(
                    relayURL = s.store.blob.settings.relayURL,
                    billingURL = s.store.blob.settings.billingURL,
                ),
            )
            val slot = try {
                withContext(Dispatchers.IO) {
                    // §3: the row creates OR REPLACES. `authorSlot` offers the free slots when there
                    // are any and otherwise the one this account already gave away, so a mistyped
                    // decoy password costs a rebuild rather than a total wipe.
                    s.store.authorSlot(decoyKey, seed) { candidates -> candidates[SecureRandom().nextInt(candidates.size)] }
                }
            } catch (e: StoreError) {
                Log.i(TAG, "no room for a decoy: ${e.message}")
                return Mapping.ERR_DECOY_NO_ROOM
            }
            val decoyStore = try {
                withContext(Dispatchers.IO) { paths.openWithKey(decoyKey) }
            } catch (e: StoreError) {
                withContext(Dispatchers.IO) { runCatching { s.store.releaseSlot(slot) } }
                return Mapping.errorText(e)
            }
            // the same DEBUG launch override every other session gets — and the thing that makes a
            // decoy usable on a harness relay at all, since `AppSettings` rewrites a raw-IP or
            // loopback address back to the release default the moment the seeded blob is decoded
            applyOverride(decoyStore)
            val svc = newService(decoyStore)
            try {
                svc.register(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "decoy registration failed: ${Mapping.errorText(e)}", e)
                withContext(Dispatchers.IO) {
                    runCatching { svc.close() }
                    decoyStore.close()
                    runCatching { s.store.releaseSlot(slot) }   // back to noise, index given up
                }
                return Mapping.errorText(e)
            }
            withContext(Dispatchers.IO) {
                runCatching { svc.close() }   // goOffline + flush: the decoy's identity is on disk
                decoyStore.close()
            }
            return null
        } finally {
            decoyKey.fill(0)
        }
    }

    // ---- unlock with fingerprint / face (docs/BIOMETRIC-UNLOCK.md) ---------------------------

    /**
     * §2: the row's words, or null when the row is hidden entirely. Also §5's last clause — when
     * biometry has actually left the device (no hardware, or nothing enrolled at all) the stored
     * key goes with it. A sensor that is merely busy is not a reason to throw the key away.
     */
    override fun biometricKind(): BiometricKind? {
        val kind = runCatching { Biometrics.kind(appContext) }.getOrNull()
        if (kind == null && _biometricEnabled.value && runCatching { Biometrics.gone(appContext) }.getOrDefault(false)) {
            disableBiometricUnlock()
        }
        return kind
    }

    /**
     * §2 turning it ON: prompt biometry once to confirm, then store the key — THAT key, the 32
     * bytes already in memory since the password unlock, and nothing else. A cancelled or failed
     * prompt leaves the toggle off and changes nothing.
     */
    override suspend fun enableBiometricUnlock(prompter: BiometricPrompter): BiometricEnable {
        val s = session ?: return BiometricEnable.Failed(ERR_LOCKED)
        val cipher = try {
            biometrics.beginEnable()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "biometric key generation failed", e)
            return BiometricEnable.Failed(Mapping.errorText(e))
        }
        val auth = prompter.authenticate(cipher) { true } // §4 counts failures only at the unlock screen
        if (auth !is BiometricAuth.Ok) {
            disableBiometricUnlock() // the fresh key must not outlive a refused prompt
            return if (auth is BiometricAuth.Failed) BiometricEnable.Failed(auth.message) else BiometricEnable.Refused
        }
        val key = s.store.storeKey
        return try {
            biometrics.finishEnable(auth.cipher, key)
            _biometricEnabled.value = true
            BiometricEnable.Ok
        } catch (e: GeneralSecurityException) {
            disableBiometricUnlock()
            BiometricEnable.Failed(Mapping.errorText(e))
        } finally {
            key.fill(0)
        }
    }

    /**
     * docs/DECOY-ACCOUNT.md §6.1 — does the ONE key the phone holds belong to THIS slot? Asked
     * with the session's own key, so the answer is per-account without a second stored item.
     */
    private fun ownsStoredBiometricKey(store: SecureStore): Boolean {
        val key = runCatching { store.storeKey }.getOrNull() ?: return false
        return try {
            runCatching { biometrics.enabled && biometrics.ownedBy(key) }.getOrDefault(false)
        } finally {
            key.fill(0)
        }
    }

    /**
     * §2 turning it OFF (and §4/§5 wherever the item must simply cease to exist).
     *
     * §6.1 adds one guard: a session that does NOT own the stored key never destroys it. The row
     * already reads off in that account, so the only way here is a path that means "this account
     * must stop being the one the phone opens" — and an account that was never that one has
     * nothing to stop. Without the guard, turning the row on and off inside a decoy would throw
     * away the real account's biometric unlock, which the decoy is not allowed to observe at all.
     */
    override fun disableBiometricUnlock() {
        val s = session
        if (s != null && !ownsStoredBiometricKey(s.store)) {
            _biometricEnabled.value = false
            return
        }
        runCatching { biometrics.destroy() }
        _biometricEnabled.value = false
    }

    /** §3/§4 — asked by the unlock screen before anything is decrypted. */
    override fun biometricGate(): BiometricGate {
        val kind = biometricKind() ?: return BiometricGate.Off
        if (!_biometricEnabled.value) return BiometricGate.Off
        return if (biometrics.decide() == BiometricDecision.ALLOW_BIOMETRY) BiometricGate.Ready(kind) else BiometricGate.PasswordRequired
    }

    /** §3: prompt once, unwrap the store key, open the store with it. The password is never involved. */
    override suspend fun unlockWithBiometrics(prompter: BiometricPrompter): BiometricOutcome {
        teardown?.join()
        if (session != null) return BiometricOutcome.Ok
        if (!_biometricEnabled.value) return BiometricOutcome.PasswordRequired
        if (biometrics.decide() != BiometricDecision.ALLOW_BIOMETRY) return BiometricOutcome.PasswordRequired
        val cipher = try {
            biometrics.beginUnlock()
        } catch (e: BiometricInvalidated) {
            Log.i(TAG, "stored biometric key invalidated by the OS", e)
            return invalidated()
        } catch (e: GeneralSecurityException) {
            return invalidated()
        }
        // §4: three consecutive no-matches (never cancellations) and the sheet goes away for good
        var spent = false
        var noMatches = 0
        val auth = prompter.authenticate(cipher) {
            noMatches++
            val n = runCatching { biometrics.noteFailure() }.getOrDefault(BiometricPolicy.MAX_CONSECUTIVE_FAILURES)
            if (n >= BiometricPolicy.MAX_CONSECUTIVE_FAILURES) {
                spent = true
                false
            } else {
                true
            }
        }
        if (auth !is BiometricAuth.Ok) {
            return when {
                spent -> BiometricOutcome.PasswordRequired
                // §3: a sheet dismissed after a rejection is "not recognised"; one dismissed
                // without ever failing is a plain cancellation and says nothing at all
                auth is BiometricAuth.Cancelled -> if (noMatches > 0) BiometricOutcome.NotRecognised else BiometricOutcome.Cancelled
                auth is BiometricAuth.NotRecognised -> BiometricOutcome.NotRecognised
                auth is BiometricAuth.Invalidated -> invalidated()
                else -> BiometricOutcome.Failed((auth as BiometricAuth.Failed).message)
            }
        }
        val key = try {
            biometrics.finishUnlock(auth.cipher)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "wrapped store key would not unwrap", e)
            return invalidated()
        }
        val store = try {
            withContext(Dispatchers.Default) { paths.openWithKey(key) }
        } catch (e: StoreError) {
            return BiometricOutcome.Failed(Mapping.unlockErrorText(e))
        } finally {
            key.fill(0)
        }
        biometrics.noteSuccess() // §4 + SCRUB-AND-WIPE.md §A1: both counters reset on any successful unlock
        _unlockFailures.value = 0
        applyOverride(store)
        val svc = newService(store)
        attach(store, svc)
        mainScope.launch { runCatching { svc.goOnline() }.onFailure { Log.w(TAG, "goOnline failed: ${Mapping.errorText(it)}", it) } }
        return BiometricOutcome.Ok
    }

    /** §4 third bullet: the OS invalidated the item — delete it and disable the feature. */
    private fun invalidated(): BiometricOutcome {
        disableBiometricUnlock()
        return BiometricOutcome.Invalidated
    }

    // ---- activity lifecycle (`AppModel.noteScenePhase` + `PyroChatApp.body.onChange(of: scenePhase)`) ----

    /** `.background`: stamp the auto-lock clock, re-arm the dead-man, drop the mirror, flush the store. */
    fun noteBackground() {
        autoLock.noteBackground()
        AppPresence.foreground = false
        val s = session ?: return
        PushRegistration.syncBadge(s.svc.totalUnread) // `setBadgeCount(totalUnread)` as of the moment you left
        deadman.notePresence(s.store.blob.settings.wipeAfterDays)
        mirrorEngine.stop("background")
        mainScope.launch { runCatching { s.svc.noteBackgrounded() } } // the private connection takes nothing new (SEALED-SENDER.md §5.5)
        mainScope.launch(Dispatchers.IO) { runCatching { s.store.flush() } }
        // drop the socket after a grace period when a push can wake us (LinkPolicy)
        backgroundDrop?.cancel()
        if (LinkPolicy.dropsLinkInBackground(s.store.blob.settings.pushEnabled, com.pyrochat.app.push.Push.available)) {
            backgroundDrop = mainScope.launch {
                delay(LinkPolicy.BACKGROUND_DROP_MS)
                if (!AppPresence.foreground) runCatching { s.svc.goOffline() }
            }
        }
    }

    private var backgroundDrop: Job? = null

    /**
     * FCM wake while backgrounded (`PushDelegate.onWake` on iOS): connect, drain the queue so
     * DELIVERED receipts go out and the badge is right, then drop the link again if push can
     * wake us. Null when locked — nothing can be decrypted without the password.
     */
    fun backgroundDrain(): Job? {
        val s = session ?: return null
        if (AppPresence.foreground) return null
        return mainScope.launch {
            runCatching { s.svc.goOnline() }
            delay(6_000)                                     // deliveries + receipt flush (FCM grants ~10 s)
            withContext(Dispatchers.IO) { runCatching { s.store.flush() } }
            PushRegistration.syncBadge(s.svc.totalUnread)
            if (!AppPresence.foreground && LinkPolicy.dropsLinkInBackground(s.store.blob.settings.pushEnabled, com.pyrochat.app.push.Push.available)) runCatching { s.svc.goOffline() }
        }
    }

    /** `.active`: presence re-arms the dead-man; pre-auth it may fire; then auto-lock, else reconnect. */
    fun noteForeground() {
        AppPresence.foreground = true
        backgroundDrop?.cancel(); backgroundDrop = null
        PushRegistration.clearNotifications() // the user is looking: "incoming transmission" has done its job
        val s = session
        if (s != null) {
            deadman.notePresence(s.store.blob.settings.wipeAfterDays)
        } else if (deadman.fireIfExpired()) {
            PushRegistration.detach()
            _duressSet.value = false
            mainScope.launch(Dispatchers.IO) {
                wipeLocal(LocalWipe.Reason.DEADMAN)
                withContext(Dispatchers.Main.immediate) { if (_stage.value is Stage.Auth) _stage.value = Stage.Auth }
            }
        }
        val timeout = s?.store?.blob?.settings?.autoLockAfterMinutes
        if (autoLock.noteForeground(timeout)) {
            lock()
        } else if (s != null) {
            mainScope.launch {
                // a link we dropped ourselves in the background comes back on its own
                if (s.svc.connState.value == com.pyrochat.service.ConnState.OFFLINE) runCatching { s.svc.goOnline() }
                else s.svc.noteForegrounded() // never leave the user at LINK DOWN + RETRY
            }
        }
    }

    /** `ChatView.onReceive(userDidTakeScreenshot / capturedDidChange)` → `reportCapture` for the open thread. */
    fun noteCapture(control: String) {
        val s = session ?: return
        val thread = s.svc.activeThread ?: return
        // a 1:1 thread alerts the peer; a group applies its screenshot policy (GROUPS.md §6)
        mainScope.launch { s.svc.reportCapture(control, thread) }
    }

    /** Tests: wait for a pending lock/wipe teardown. */
    suspend fun awaitTeardown() {
        teardown?.join()
    }

    /** Tests: release everything (the process-wide instance never closes). */
    fun close() {
        lock()
        val t = teardown
        if (t != null) kotlinx.coroutines.runBlocking { t.join() }
        mirrorEngine.stop("unlink")
        mainScope.cancel()
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun newService(store: SecureStore): ChatService =
        // versionCode: PROTOCOL.md §"Latest versions" — the banner shows only for a build greater than this one
        ChatService(
            store, pushTokenProvider = { PushRegistration.currentToken }, noticeStore = noticeStore, versionCode = BuildConfig.VERSION_CODE,
            // docs/SEALED-SENDER.md §7: a debug build trusts the throwaway test root, and the service
            // only for a relay on this machine. In a release build the field is empty.
            sealedTestRoots = listOfNotNull(BuildConfig.SEALED_TEST_ROOT.takeIf { it.isNotEmpty() }),
            // a certificate's remaining life is counted on a clock that keeps running while the phone sleeps
            elapsedRealtimeMs = { android.os.SystemClock.elapsedRealtime() },
            // without push the main link stays up in the background; the private connection does not
            inFront = { AppPresence.foreground },
        ).also { it.debugRows = BuildConfig.DEBUG } // GROUPS.md §1: ignored removals logged in debug builds only

    private fun senderHidden(status: com.pyrochat.service.SealedStatus): SenderHidden = when (status) {
        com.pyrochat.service.SealedStatus.ON -> SenderHidden.ON
        com.pyrochat.service.SealedStatus.NOT_OFFERED -> SenderHidden.NOT_OFFERED
        com.pyrochat.service.SealedStatus.NO_PRIVATE_CONNECTION -> SenderHidden.NO_PRIVATE_CONNECTION
    }

    private fun applyOverride(store: SecureStore) {
        val o = serverOverride ?: return
        store.update { it.copy(settings = o.apply(it.settings)) }
    }

    /** `service = svc; stage = .main; DeadmanSwitch.arm(…)` — collectors run on the main dispatcher. */
    private suspend fun attach(store: SecureStore, svc: ChatService) = withContext(Dispatchers.Main.immediate) {
        svc.soundHook = { tone -> _tones.tryEmit(Mapping.tone(tone)) }
        val source = ServiceMirrorSource(svc, mainScope)
        // seed synchronously so `.main` never renders an empty shell (the collectors below keep them current)
        _contacts.value = svc.contacts.value.map(Mapping::contact)
        _messages.value = Mapping.messages(svc.messages.value)
        _typing.value = svc.typingPeers.value.keys
        _conn.value = Mapping.connState(svc.connState.value)
        _senderHidden.value = senderHidden(svc.sealedStatus.value)
        _blocked.value = svc.blockedHashes.value
        _lastError.value = svc.lastError.value
        _notices.value = svc.notices.value.map(Mapping::notice)
        _unseenNotices.value = svc.unseenNoticeCount.value
        _updateAvailable.value = svc.updateAvailable.value?.let(Mapping::update)
        _groups.value = svc.groups.value.map(Mapping::group)
        val job = mainScope.launch {
            launch { svc.groups.collect { g -> _groups.value = g.map(Mapping::group) } }
            launch { svc.groupTyping.collect { m -> _groupTyping.value = m.mapValues { it.value.keys } } }
            launch { svc.goneMembers.collect { _goneMembers.value = it } }
            launch { svc.formerMembers.collect { m -> _formerMembers.value = m.mapValues { e -> e.value.map(Mapping::member) } } }
            launch {
                svc.flashes.collect { text ->
                    // transient (§6b): shown on the home screen for a few seconds, then gone
                    _flash.value = text
                    flashJob?.cancel()
                    flashJob = launch {
                        delay(FLASH_MS)
                        if (_flash.value == text) _flash.value = null
                    }
                }
            }
            launch { svc.contacts.collect { c -> _contacts.value = c.map(Mapping::contact) } }
            launch { svc.messages.map(Mapping::messages).flowOn(Dispatchers.Default).collect { _messages.value = it } }
            launch { svc.typingPeers.collect { _typing.value = it.keys } }
            launch { svc.connState.collect { _conn.value = Mapping.connState(it) } }
            launch { svc.sealedStatus.collect { _senderHidden.value = senderHidden(it) } }
            launch { svc.blockedHashes.collect { _blocked.value = it } }
            launch { svc.lastError.collect { _lastError.value = it } }
            launch { svc.notices.collect { n -> _notices.value = n.map(Mapping::notice) } }
            launch { svc.unseenNoticeCount.collect { _unseenNotices.value = it } }
            launch { svc.updateAvailable.collect { _updateAvailable.value = it?.let(Mapping::update) } }
            launch { svc.blobProgress.collect { p -> _blobProgress.value = p.mapValues { (_, v) -> v.toFloat() } } }
            launch {
                svc.rxTransfers.collect { m ->
                    _rx.value = m.map { (id, t) -> RxTransfer(id, t.thread, t.subtype, t.received.toLong(), t.total.toLong()) }
                }
            }
        }
        session = Session(store, svc, source, job)
        _settings.value = Mapping.settings(store.blob.settings)
        _accessDays.value = svc.accessDaysRemaining
        deadman.notePresence(store.blob.settings.wipeAfterDays)
        // `PushDelegate.onToken = { tok in Task { await svc?.sendPushToken(tok) } }` + what the wake handler may ask
        PushRegistration.onToken = { tok -> mainScope.launch { runCatching { svc.sendPushToken(tok) } } }
        // §6.1: the row shows the truth for the account you are in — on only when the one key the
        // phone holds is THIS slot's. Inside a decoy whose phone opens the real account by face it
        // reads off, which is an ordinary state and not a tell.
        _biometricEnabled.value = ownsStoredBiometricKey(store)
        _storeTooLarge.value = store.tooLargeToMigrate?.let { UiStoreTooLarge(it.jsonBytes, it.capacityBytes) }
        // §6.6: a wake push carries nothing that names a slot, so the only honest rule is that a
        // session which never registered for wake pushes cannot be the one being woken
        AppPresence.bind(
            online = { _conn.value == ConnState.online },
            totalUnread = { svc.totalUnread },
            wakesThisSlot = { runCatching { store.blob.settings.pushEnabled }.getOrDefault(false) },
        )
        PushRegistration.clearNotifications() // unlocked: the shade entry has done its job
        // the token rides the `auth` frame (pushTokenProvider) or the callback above, whichever is first
        if (store.blob.settings.pushEnabled) PushRegistration.requestAndRegister()
        _stage.value = Stage.Main
    }

    private fun resetSessionState() {
        PushRegistration.onToken = null
        AppPresence.unbind()
        // §6.3: locked, the offer on the unlock screen must look identical whichever slot the
        // stored key belongs to — so it is simply "is something stored", as it always was
        _biometricEnabled.value = runCatching { biometrics.enabled }.getOrDefault(false)
        _storeTooLarge.value = null
        _contacts.value = emptyList()
        _messages.value = emptyMap()
        _typing.value = emptySet()
        _blobProgress.value = emptyMap()
        _rx.value = emptyList()
        _blocked.value = emptySet()
        _conn.value = ConnState.offline
        _senderHidden.value = SenderHidden.NOT_OFFERED
        _lastError.value = null
        _notices.value = emptyList()
        _unseenNotices.value = 0
        _updateAvailable.value = null
        _groups.value = emptyList()
        _groupTyping.value = emptyMap()
        _goneMembers.value = emptyMap()
        _formerMembers.value = emptyMap()
        _flash.value = null
        scrubReceipts.clear()   // SCRUB-AND-WIPE.md §B2: a locked app has honestly forgotten them
    }

    /**
     * `SecureStore.obliterate()`: store, sidecars, secrets, plaintext tmp, the operator notices +
     * remembered version — and the dead-man deadline. docs/BIOMETRIC-UNLOCK.md §5 puts the wrapped
     * biometric key first in that list; [LocalWipe] owns the order.
     */
    private val localWipe = LocalWipe(
        biometrics = { biometrics.shred() },
        obliterate = { paths.obliterate() },
        notices = { noticeStore.clear() },
        deadman = { deadman.disarm() },
    )

    private fun wipeLocal(reason: LocalWipe.Reason) {
        localWipe.wipe(reason)
        _biometricEnabled.value = false
        _unlockFailures.value = 0 // the shredder took the counter with it: a wiped phone is a fresh phone
    }

    /**
     * docs/SCRUB-AND-WIPE.md §A — one wrong typed password. Returns true when that was the tenth
     * and the device destroyed itself; the caller then says nothing at all (§A2).
     *
     * The wipe is [LocalWipe.Reason.TEN_WRONG_PASSWORDS] through the same funnel the duress PIN
     * uses, and the screen it lands on is the same one duress leaves behind (§A3).
     */
    private fun noteWrongPassword(): Boolean {
        val failures = biometrics.noteWrongPassword()
        _unlockFailures.value = failures
        if (UnlockFailurePolicy.decide(failures, System.currentTimeMillis()) !is UnlockFailurePolicy.Decision.Wipe) return false
        PushRegistration.detach()
        wipeLocal(LocalWipe.Reason.TEN_WRONG_PASSWORDS)
        _duressSet.value = false
        _stage.value = Stage.Auth
        return true
    }

    /** The service's row behind a UI row — the open thread first, then every thread. */
    private fun storeRow(s: Session, id: String): StoreMessage? {
        val all = s.svc.messages.value
        s.svc.activeThread?.let { t -> all[t]?.firstOrNull { it.id == id }?.let { return it } }
        for (log in all.values) log.firstOrNull { it.id == id }?.let { return it }
        return null
    }

    companion object {
        private const val TAG = "RealBackend"

        /** docs/SCRUB-AND-WIPE.md §B2 — a RAM cache, bounded like the frame capture it sits beside. */
        private const val MAX_SCRUB_RECEIPTS = 256
        /** The biometric toggle lives in `[cfg]`, so this can only be a bug. */
        private const val ERR_LOCKED = "locked"
        private const val BACKUP_MIME = "application/octet-stream"
        /** How long a home-screen flash (`#name was burned by the creator`) stays up. */
        private const val FLASH_MS = 5_000L
    }
}
