package com.pyrochat.service

import com.pyrochat.protocol.UsernameHash
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The mixed-platform group gate (docs/GROUPS.md §8), Android side. The counterpart
 * `ios/Tests/GroupInteropTests.swift` runs TWO real iOS ChatServices (`ios_a_*`, `ios_b_*`) on
 * the simulator; this JVM service is the third member. Protocol of the run:
 *
 *  1. this side registers `andro_<rand>` FIRST and publishes the name to `groups-gate-android.txt`
 *     next to the names file (the iOS side polls `addContact` until the relay knows it);
 *  2. the iOS side publishes `ios_a\nios_b\n` to the names file (`-PgroupInteropNames=<file>` /
 *     `PYRO_GROUP_INTEROP_NAMES`, e.g. `<scratchpad>/groups-gate-ios.txt`) and creates `#gate`
 *     (policy `burn_remove`) with both of us;
 *  3. `from ios_a #1` arrives here attributed to ios_a; we read it (READ receipt to ios_a), answer
 *     `from android #1` + a small JPEG, and wait for READ from BOTH iOS members on both rows;
 *  4. we print the colour index we compute for every member (must match the iOS lines);
 *  5. we report a screenshot in the group: our copy burns + turns `left` (`burn_remove`), and the
 *     two iPhones drop us from the roster.
 *
 * Skipped unless the names file is given. Run both concurrently:
 *   cd ios && TEST_RUNNER_PYRO_GROUP_INTEROP=<scratch>/groups-gate-android.txt \
 *             TEST_RUNNER_PYRO_GROUP_NAMES_FILE=<scratch>/groups-gate-ios.txt \
 *             xcodebuild test … -only-testing:PyroChatTests/GroupInteropTests
 *   cd android && ./gradlew :core-service:test --tests '*GroupInteropWithIosTest*' \
 *             -PgroupInteropNames=<scratch>/groups-gate-ios.txt
 */
class GroupInteropWithIosTest {
    private val namesFile: Path? = (System.getProperty("pyro.group.interop.names") ?: System.getenv("PYRO_GROUP_INTEROP_NAMES"))
        ?.takeIf { it.isNotBlank() }?.let { Paths.get(it).toAbsolutePath() }

    /**
     * docs/SEALED-SENDER.md §9 (X3), a group with one member on an old build: `PYRO_INTEROP_LEGACY=true`
     * makes this member one. Its copies travel the old way in both directions; the two iPhones keep
     * talking sealed to each other.
     */
    private val legacy: Boolean = (System.getProperty("pyro.interop.legacy") ?: System.getenv("PYRO_INTEROP_LEGACY")) == "true"

    private fun gate(s: String) = println("GATE: $s")

    /** A real (tiny) JPEG from the JDK's encoder — the iOS side checks the SOI marker and byte-identity on both phones. */
    private fun smallJpeg(w: Int, h: Int): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 0x40)
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, "jpeg", out)) { "no JPEG writer in this JDK" }
        return out.toByteArray()
    }

    @Test
    fun `Groups gate - Android member of a mixed group with two real iOS ChatServices`(): Unit = runBlocking {
        Assumptions.assumeTrue(namesFile != null, "SKIPPED: no names file — pass -PgroupInteropNames=<file> (and run GroupInteropTests on the simulator)")
        Live.requireRelay()
        val iosNames = namesFile!!
        val myNameFile = iosNames.resolveSibling("groups-gate-android.txt")
        val readOn: (Blob) -> Blob = { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
        val me = Live.makeService("andro_", legacy = legacy, configure = readOn)
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        me.svc.wireTap = { if (it is com.pyrochat.protocol.Wire.Out.Send) sent += "send" }
        me.svc.anonWireTap = { if (it is com.pyrochat.protocol.Wire.Out.Deliver) sent += "deliver" }
        try {
            // ---- 1. register FIRST and publish the name (atomically — the iOS side polls the file)
            me.svc.register(me.username)
            val tmp = Files.createTempFile(iosNames.parent, "groups-gate-android", ".tmp")
            Files.writeString(tmp, me.username + "\n")
            Files.move(tmp, myNameFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            gate("android <${me.username}> registered, hash=${me.hash.take(12)}… — name published to $myNameFile")

            // ---- 2. wait to be invited: #gate with 3 members (xcodebuild needs minutes to build + boot)
            gate("waiting for #gate (group_create from ios_a) — up to 10 min")
            assertTrue(waitUntil(10 * 60_000) { me.svc.groups.value.any { it.name == "gate" && it.members.size == 3 } }, "group_create from ios_a must land here")
            val g = me.svc.groups.value.first { it.name == "gate" }
            gate("invited to #${g.name} id=${g.id} epoch=${g.epoch} policy=${g.policy} creator=${g.creator.take(12)}… members=${g.members.map { it.name }}")
            assertEquals("burn_remove", g.policy)
            assertEquals(0, g.epoch)
            assertTrue(g.isMember(me.hash))
            assertTrue(waitUntil(30_000) { Files.isRegularFile(iosNames) && Files.readAllLines(iosNames).count { it.isNotBlank() } >= 2 }, "the iOS side publishes ios_a + ios_b to $iosNames")
            val (iosA, iosB) = Files.readAllLines(iosNames).map { it.trim() }.filter { it.isNotEmpty() }
            val iosAHash = UsernameHash.of(iosA)
            val iosBHash = UsernameHash.of(iosB)
            gate("iOS members: ios_a=<$iosA> ${iosAHash.take(12)}… ios_b=<$iosB> ${iosBHash.take(12)}…")
            assertEquals(iosAHash, g.creator, "ios_a is the creator")
            assertTrue(g.isMember(iosBHash))
            assertEquals(setOf(iosA, iosB, me.username), g.members.map { it.name }.toSet())

            // ---- 3. `from ios_a #1`: attributed to ios_a, filed under its row id, in the GROUP thread
            assertTrue(waitUntil(60_000) { me.svc.messages.value[g.id]?.any { it.text == "from ios_a #1" } == true }, "'from ios_a #1' must arrive")
            val row = me.svc.messages.value[g.id]!!.first { it.text == "from ios_a #1" }
            assertEquals(Direction.INCOMING, row.direction)
            assertEquals(iosAHash, row.sender, "sender = ios_a's hash (the pairwise session it arrived on)")
            assertEquals(g.id, row.group)
            assertEquals(Kind.TEXT, row.kind)
            assertTrue(me.svc.messages.value[iosAHash]?.any { it.text == "from ios_a #1" } != true, "never in a 1:1 thread")
            gate("'from ios_a #1' arrived: id=${row.id} sender=${row.sender!!.take(12)}… (= ios_a) status=${row.status}")
            me.svc.noteRowVisible(row.id, g.id) // read-dwell: READ receipt to ios_a, with `group`

            me.svc.sendText("from android #1", g.id)
            val jpeg = smallJpeg(24, 16)
            assertEquals(0xFF, jpeg[0].toInt() and 0xff); assertEquals(0xD8, jpeg[1].toInt() and 0xff)
            me.svc.sendImage(jpeg, 24, 16, g.id)
            gate("sent 'from android #1' + a ${jpeg.size} B JPEG 24x16 (fan-out to ios_a + ios_b, blob pipeline once per recipient)")
            assertTrue(waitUntil(60_000) { me.svc.messages.value[g.id]!!.filter { it.direction == Direction.OUTGOING }.let { it.size == 2 && it.all { r -> r.status == Status.SENT } } }, "both rows SENT once every recipient's `sent` arrived")
            gate("both outgoing rows SENT (relay verdicts from both recipients)")

            // READ from BOTH iOS members on BOTH rows
            fun outgoing() = me.svc.messages.value[g.id]!!.filter { it.direction == Direction.OUTGOING }
            assertTrue(waitUntil(120_000) { outgoing().all { it.receipts?.get(iosAHash) == "read" && it.receipts?.get(iosBHash) == "read" } }, "READ receipts from ios_a and ios_b on both rows; got ${outgoing().map { it.receipts }}")
            for (r in outgoing()) {
                val word = GroupReceipts.aggregate(r.status, r.receipts, 2, true)
                assertEquals("READ 2/2", word)
                gate("row ${r.kind} '${r.text}' → $word receipts=${r.receipts}")
            }

            // ---- 4. colour indices (§5) — must equal the `GATE: colour` lines the iOS side prints
            val live = me.svc.group(g.id)!!
            for (m in GroupColours.joinOrder(live.colourRoster)) {
                val idx = GroupColours.joinIndex(live.colourRoster, m.hash)!!
                gate("colour on android: <${m.name}> hash=${m.hash} index=$idx hex=${GroupColours.hex(idx)}")
            }
            assertEquals(3, live.members.size)

            // ---- 5. screenshot: our copy burns + left; the iPhones drop us
            gate("reporting a screenshot in #gate (policy burn_remove)")
            me.svc.reportCapture("screenshot", g.id)
            assertTrue(waitUntil(10_000) { me.svc.group(g.id)?.isLeft == true }, "burn_remove: the offender's own copy is marked left")
            assertEquals(listOf("── this channel burned: you took a screenshot ──"), me.svc.messages.value[g.id]!!.map { it.text })
            assertTrue(waitUntil(30_000) { me.svc.pendingGroupControlCount() == 0 }, "the screenshot control confirmed by the relay for both iOS members")
            me.svc.sendText("still here?", g.id)
            assertEquals(GroupRules.ERR_NOT_IN_GROUP, me.svc.lastError.value)
            gate("own copy burned + left=${me.svc.group(g.id)!!.isLeft}; rows=${me.svc.messages.value[g.id]!!.map { it.text }}; a further send → '${me.svc.lastError.value}'")
            assertNotNull(me.svc.group(g.id))
            // which way this member's copies left: all sealed on a new build with sealed sender, none on an old one
            gate("this member's messages left: ${sent.groupingBy { it }.eachCount()} (old build: $legacy, sealed sender: ${me.svc.sealedStatus.value})")
            if (legacy) assertTrue(sent.none { it == "deliver" }, "an old build seals nothing")
            else if (me.svc.sealedStatus.value == SealedStatus.ON) assertTrue(sent.none { it == "send" }, "with sealed sender on, nothing named its sender: $sent")
            gate("PASSED")
            me.svc.goOffline()
        } finally {
            me.close()
        }
    }
}
