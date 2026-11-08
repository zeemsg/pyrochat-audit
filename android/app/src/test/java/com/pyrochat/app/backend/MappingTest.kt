package com.pyrochat.app.backend

import com.pyrochat.app.ui.ConnState
import com.pyrochat.app.ui.Direction
import com.pyrochat.app.ui.Kind
import com.pyrochat.app.ui.MirrorState
import com.pyrochat.app.ui.Status
import com.pyrochat.app.ui.AppSettings as UiSettings
import com.pyrochat.app.ui.UiNotice
import com.pyrochat.app.ui.UiUpdate
import com.pyrochat.service.LatestVersion
import com.pyrochat.service.ChatService
import com.pyrochat.mirror.MirrorDirection
import com.pyrochat.mirror.MirrorEngine
import com.pyrochat.mirror.MirrorLink
import com.pyrochat.service.ConnState as ServiceConnState
import com.pyrochat.service.ServiceError
import com.pyrochat.store.StoreError
import com.pyrochat.store.model.AppSettings as StoreSettings
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.ChatMessage as StoreMessage
import com.pyrochat.store.model.Contact as StoreContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** Pure store/service ↔ UI conversions and the verbatim iOS error strings. */
class MappingTest {
    private val t0 = Instant.parse("2026-09-17T12:00:00Z")

    @Test fun groupModelsMapFieldForField() {
        val bob = "b".repeat(64)
        val g = com.pyrochat.store.model.Group(
            id = "g1", name = "ops", creator = bob, createdAt = t0, epoch = 4, policy = "burn", burnSeconds = 60.0,
            members = listOf(com.pyrochat.store.model.Member(bob, "bob", t0.plusSeconds(1))), left = true,
        )
        val ui = Mapping.group(g)
        assertEquals("g1", ui.id); assertEquals("ops", ui.name); assertEquals(bob, ui.creator)
        assertEquals(t0.toEpochMilli(), ui.createdAt); assertEquals(4, ui.epoch); assertEquals("burn", ui.policy)
        assertEquals(60L, ui.burnSeconds); assertTrue(ui.left)
        assertEquals(listOf(com.pyrochat.app.ui.UiMember(bob, "bob", t0.plusSeconds(1).toEpochMilli())), ui.members)
        assertFalse(Mapping.group(g.copy(left = null)).left)
        assertNull(Mapping.group(g.copy(burnSeconds = null)).burnSeconds)
        val row = StoreMessage(
            id = "r", direction = StoreMessage.Direction.OUTGOING, kind = StoreMessage.Kind.TEXT, text = "x", timestamp = t0,
            status = StoreMessage.Status.SENT, group = "g1", sender = null, receipts = mapOf(bob to "read"),
        )
        val uiRow = Mapping.message(row)
        assertEquals("g1", uiRow.group); assertNull(uiRow.sender); assertEquals(mapOf(bob to "read"), uiRow.receipts)
        assertNull(Mapping.message(row.copy(group = null, receipts = null)).group)
        assertEquals(com.pyrochat.store.model.GroupPolicy.BURN_REMOVE, Mapping.policy("burn_remove"))
        assertEquals(com.pyrochat.store.model.GroupPolicy.ALERT, Mapping.policy("alert"))
        assertEquals(com.pyrochat.store.model.GroupPolicy.ALERT, Mapping.policy("nonsense"))
        // former + gone travel too
        val history = g.copy(former = listOf(com.pyrochat.store.model.Member("c".repeat(64), "carol", t0)), members = listOf(com.pyrochat.store.model.Member(bob, "bob", t0, gone = true)))
        assertEquals(listOf(com.pyrochat.app.ui.UiMember("c".repeat(64), "carol", t0.toEpochMilli())), Mapping.group(history).former)
        assertTrue(Mapping.group(history).members.single().gone)
        assertTrue(Mapping.group(g).former.isEmpty())
        // the verbatim §7 errors travel as the ServiceError code
        assertEquals("ERR: group is full (20)", Mapping.errorText(ServiceError("ERR: group is full (20)")))
    }

    @Test fun messageFieldsAndUnits() {
        val m = StoreMessage(
            id = "abc", direction = StoreMessage.Direction.OUTGOING, kind = StoreMessage.Kind.TEXT, text = "hi",
            timestamp = t0, status = StoreMessage.Status.DELIVERED, burnAfter = 20.0, burnStartedAt = t0.plusSeconds(5),
            fileBytes = 43_112, viaWeb = true,
        )
        val ui = Mapping.message(m)
        assertEquals("abc", ui.id)
        assertEquals(Direction.outgoing, ui.direction)
        assertEquals(Kind.text, ui.kind)
        assertEquals(t0.toEpochMilli(), ui.timestamp)
        assertEquals(Status.delivered, ui.status)
        assertEquals(20L, ui.burnAfter)
        assertEquals(t0.toEpochMilli() + 5_000, ui.burnStartedAt)
        assertEquals(t0.toEpochMilli() + 25_000, ui.burnDeadline)   // `burnStartedAt + burnAfter`
        assertEquals(43_112L, ui.fileBytes)
        assertEquals(true, ui.viaWeb)
        assertFalse(ui.hasVoice)
    }

    @Test fun viaWebFalseIsAbsentAndVoiceDataBecomesHasVoice() {
        val m = StoreMessage(
            id = "v", direction = StoreMessage.Direction.INCOMING, kind = StoreMessage.Kind.VOICE, text = "",
            timestamp = t0, status = StoreMessage.Status.READ, voiceData = Bytes(byteArrayOf(1, 2, 3)), voiceDuration = 4.5,
        )
        val ui = Mapping.message(m)
        assertNull(ui.viaWeb)          // iOS never writes `false`
        assertTrue(ui.hasVoice)        // bytes stay in the service
        assertEquals(4.5, ui.voiceDuration!!, 0.0)
        assertNull(ui.burnDeadline)
        assertEquals(Kind.system, Mapping.message(m.copy(kind = StoreMessage.Kind.SYSTEM)).kind)
        assertEquals(Status.failed, Mapping.status(StoreMessage.Status.FAILED))
        assertEquals(Status.pending, Mapping.status(StoreMessage.Status.PENDING))
    }

    @Test fun contactAndMirrorContact() {
        val c = StoreContact(hash = "h", name = "pyrobot", addedAt = t0, verified = true, burnSeconds = 0.0)
        val ui = Mapping.contact(c)
        assertEquals(t0.toEpochMilli(), ui.addedAt)
        assertEquals(0L, ui.burnSeconds)   // explicit off survives as 0, not null
        assertTrue(ui.verified)
        assertNull(Mapping.contact(c.copy(burnSeconds = null)).burnSeconds)
        val mc = Mapping.mirrorContact(c)
        assertEquals("pyrobot", mc.name)
        assertEquals(t0.toEpochMilli(), mc.addedAt)
    }

    @Test fun mirrorGroupCarriesJoinOrderColoursGoneLeftAndFormer() {
        val me = "0".repeat(64); val alice = "a".repeat(64); val bob = "b".repeat(64); val carol = "c".repeat(64)
        val g = com.pyrochat.store.model.Group(
            id = "g1", name = "ops", creator = me, createdAt = t0, epoch = 5, policy = "burn_remove",
            // roster order on disk is NOT join order: bob was added last, carol (gone) second
            members = listOf(
                com.pyrochat.store.model.Member(bob, "bob", t0.plusSeconds(20)),
                com.pyrochat.store.model.Member(me, "andro", t0),
                com.pyrochat.store.model.Member(carol, "carol", t0.plusSeconds(10), gone = true),
            ),
            // alice left: she keeps her slot (joined before carol and bob), so nobody recolours
            former = listOf(com.pyrochat.store.model.Member(alice, "alice", t0.plusSeconds(5))),
        )
        val mg = Mapping.mirrorGroup(g)
        assertEquals("g1", mg.id); assertEquals("ops", mg.name); assertEquals("burn_remove", mg.policy)
        assertEquals(t0.toEpochMilli(), mg.createdAt); assertFalse(mg.left)
        assertEquals(listOf(bob, me, carol), mg.members.map { it.hash })   // stored roster order, like iOS groupJSON
        assertEquals(listOf("#ffd166", "#3ef573", "#c792ff"), mg.members.map { it.color })   // palette 3, 0, 2 by join — alice holds 1
        assertEquals(listOf(false, false, true), mg.members.map { it.gone })
        assertEquals(listOf(t0.plusSeconds(20).toEpochMilli(), t0.toEpochMilli(), t0.plusSeconds(10).toEpochMilli()), mg.members.map { it.joinedAt })
        assertEquals(listOf(com.pyrochat.mirror.MirrorMember(alice, "alice", "#4fd8ff", joinedAt = t0.plusSeconds(5).toEpochMilli())), mg.former)
        assertEquals("alice", mg.member(alice)?.name)
        assertTrue(Mapping.mirrorGroup(g.copy(left = true)).left)
        assertTrue(Mapping.mirrorGroup(g.copy(former = null)).former.isEmpty())
        // the second lap dims to 70 %
        val many = (0 until 12).map { com.pyrochat.store.model.Member(it.toString().padStart(64, 'f'), "m$it", t0.plusSeconds(it.toLong())) }
        val colours = Mapping.mirrorGroup(g.copy(members = many, former = null)).members.map { it.color }
        assertEquals("#3ef573", colours[0]); assertEquals(com.pyrochat.service.GroupColours.hex(10), colours[10])
        // row fields: sender hash + raw receipts travel; nothing else changes
        val row = StoreMessage(
            id = "r", direction = StoreMessage.Direction.OUTGOING, kind = StoreMessage.Kind.TEXT, text = "x", timestamp = t0,
            status = StoreMessage.Status.SENT, group = "g1", receipts = mapOf(bob to "read"),
        )
        assertEquals(mapOf(bob to "read"), Mapping.mirrorMessage(row).receipts)
        assertNull(Mapping.mirrorMessage(row).senderHash)
        val incoming = row.copy(direction = StoreMessage.Direction.INCOMING, sender = bob, receipts = null)
        assertEquals(bob, Mapping.mirrorMessage(incoming).senderHash)
        assertNull(Mapping.mirrorMessage(incoming).receipts)
    }

    @Test fun mirrorMessageFlagsBurnRows() {
        val burn = StoreMessage(
            id = "b", direction = StoreMessage.Direction.INCOMING, kind = StoreMessage.Kind.TEXT, text = "secret",
            timestamp = t0, status = StoreMessage.Status.DELIVERED, burnAfter = 60.0,
        )
        val mm = Mapping.mirrorMessage(burn)
        assertTrue(mm.isBurn)
        assertEquals(MirrorDirection.incoming, mm.direction)
        assertFalse(Mapping.mirrorMessage(burn.copy(burnAfter = null)).isBurn)
    }

    @Test fun settingsRoundTripKeepsEveryKey() {
        val store = StoreSettings(
            theme = "amber", scanlines = false, pushEnabled = true, defaultBurnSeconds = 3600.0, threadAutoBurnDays = 7,
            burnOnScreenshot = true, sendReadReceipts = true, soundsEnabled = false, sendTyping = false, blockScreenshots = false,
            socksProxy = "127.0.0.1:9050", wipeAfterDays = 30, autoLockMinutes = 0, relayURL = "ws://10.0.2.2:7444/ws", billingURL = "http://10.0.2.2:8088",
            hideNotices = true, textSize = "largest", voiceChanger = "robot",
        )
        val ui = Mapping.settings(store)
        assertEquals(3600L, ui.defaultBurnSeconds)
        assertTrue(ui.readReceiptsOn)
        assertFalse(ui.soundsOn)
        assertFalse(ui.screenshotBlockOn)
        assertFalse(ui.noticesOn)
        assertEquals(store, Mapping.settings(ui))
        assertEquals("largest", ui.textSize)
        // docs/VOICE-CHANGER.md: the chosen voice travels both ways, and reads as the voice it names
        assertEquals("robot", ui.voiceChanger)
        assertEquals(com.pyrochat.app.platform.VoiceEffect.ROBOT, ui.voice)
        assertEquals(com.pyrochat.app.platform.VoiceEffect.OFF, UiSettings().voice)
        // the later keys stay null (omitted on disk) until the row is touched
        assertEquals(null, Mapping.settings(UiSettings()).hideNotices)
        assertEquals(null, Mapping.settings(UiSettings()).textSize)
        assertEquals(null, Mapping.settings(UiSettings()).voiceChanger)
        assertTrue(Mapping.settings(StoreSettings()).noticesOn)
        // defaults map to defaults (the UI copy of `AppSettings()` equals the store copy)
        assertEquals(StoreSettings(), Mapping.settings(UiSettings()))
        assertEquals(UiSettings(), Mapping.settings(StoreSettings()))
    }

    @Test fun noticeTimesBecomeEpochMillis() {
        val n = ChatService.Notice("id1", "relay maintenance", "https://pyrochat.ca/", Instant.ofEpochSecond(1_760_000_000L), Instant.ofEpochSecond(1_760_000_600L))
        val ui = Mapping.notice(n)
        assertEquals(UiNotice("id1", "relay maintenance", "https://pyrochat.ca/", 1_760_000_000_000L, 1_760_000_600_000L), ui)
        assertEquals(null, Mapping.notice(n.copy(url = null)).url)
    }

    @Test fun latestVersionMapsFieldForField() {
        assertEquals(UiUpdate(24, "0.1.0", "https://pyrochat.ca/android"), Mapping.update(LatestVersion(24, "0.1.0", "https://pyrochat.ca/android")))
        assertNull(Mapping.update(LatestVersion(24, "0.1.0", null)).url)
    }

    @Test fun connStateAndMirrorState() {
        assertEquals(ConnState.online, Mapping.connState(ServiceConnState.ONLINE))
        assertEquals(ConnState.authenticating, Mapping.connState(ServiceConnState.AUTHENTICATING))
        assertEquals(ConnState.connecting, Mapping.connState(ServiceConnState.CONNECTING))
        assertEquals(ConnState.offline, Mapping.connState(ServiceConnState.OFFLINE))

        assertEquals(MirrorState.Idle, Mapping.mirrorState(MirrorEngine.State.Idle))
        assertEquals(MirrorState.Idle, Mapping.mirrorState(MirrorEngine.State.Closed("unlink", "114703")))
        assertEquals(
            MirrorState.Linked(1_000, "Chrome on Mac", "114703"),
            Mapping.mirrorState(MirrorEngine.State.Linked(1_000, "114703", "Chrome on Mac")),
        )
        val joining = Mapping.mirrorState(MirrorEngine.State.Joining("114703")) { 42L }
        assertEquals(MirrorState.Linked(42L, null, "114703"), joining)   // the LINKED pane shows the code at scan time
        assertEquals(MirrorState.Failed(MirrorEngine.HIJACK_MESSAGE), Mapping.mirrorState(MirrorEngine.State.Failed(MirrorEngine.HIJACK_MESSAGE, "1")))
    }

    @Test fun errorTextsAreTheIosStrings() {
        assertEquals("username_taken", Mapping.errorText(ServiceError("username_taken")))
        assertEquals("network connection lost", Mapping.errorText(IOException("network connection lost")))
        assertEquals(Mapping.ERR_WRONG_PASSWORD, Mapping.unlockErrorText(StoreError.BadPassword()))
        assertEquals(Mapping.ERR_WRONG_PASSWORD, Mapping.unlockErrorText(StoreError.Corrupt(IllegalArgumentException("x"))))
        assertEquals("not a pyrochat backup file", Mapping.restoreErrorText(StoreError.BadBackupFile()))
        assertEquals("wrong backup passphrase", Mapping.restoreErrorText(StoreError.BadBackupPassphrase()))
        assertEquals("decrypt failed — wrong password", Mapping.ERR_WRONG_PASSWORD)
        assertEquals("store corrupt — no identity found", Mapping.ERR_DURESS_COVER)
        assertEquals("an identity already exists on this device — burn it first", Mapping.ERR_IDENTITY_EXISTS)
    }

    @Test fun linkParseErrorsMirrorLinkDesktopView() {
        assertNull(Mapping.linkParseErrorText("https://lunch.example/menu", MirrorLink.ParseError.NotPyroLink))   // keep scanning silently
        assertEquals(Mapping.ERR_LINK_CONTACT_CODE, Mapping.linkParseErrorText("pyro-add:v1:bob:AAAA", MirrorLink.ParseError.NotPyroLink))
        assertEquals(Mapping.ERR_LINK_WRONG_RELAY, Mapping.linkParseErrorText("x", MirrorLink.ParseError.WrongRelay("relay.pyrochat.ca", "evil")))
        assertEquals(Mapping.ERR_LINK_MALFORMED, Mapping.linkParseErrorText("x", MirrorLink.ParseError.Malformed))
        assertEquals("link failed: boom", Mapping.linkParseErrorText("x", IllegalStateException("boom")))
        assertEquals("pyrochat-20260917-1200.pyrobak", Mapping.backupFileName("20260917-1200"))
    }
}
