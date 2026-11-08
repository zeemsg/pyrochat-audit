package com.pyrochat.service

import com.pyrochat.protocol.UsernameHash
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop-link group gate (docs/MIRROR-GROUPS.md §4, last bullet + §5), Android side: this JVM
 * `ChatService` is the ONE Android member of `#gate`, created by a real iOS ChatService on the
 * simulator (`ios/Tests/MirrorGateTests.swift`) that also links a real browser page. Protocol:
 *
 *  1. register `andro_<rand>` FIRST and publish the name to `android.txt` next to the names
 *     file (`-PgroupInteropNames=<dir>/ios.txt`; the iOS side polls `addContact` until the
 *     relay knows us);
 *  2. wait for `group_create` (#gate, 2 members), add ios_a as a contact (a real 1:1 thread both
 *     ways) and wait for `linked.txt` (the page acked the link). §5: NOTHING is announced at
 *     link time — no "linked a desktop browser" row in #gate nor in the 1:1 thread, 2 s after
 *     the link; publish `android-linked.txt` (gate.mjs opens #gate only after that);
 *  3. wait for `── <ios_a> linked a desktop browser (…) — this channel may be viewed there ──`:
 *     the page opened #gate (`viewed-gate.txt` = epoch ms) → `view` → the iPhone fans `web_link`
 *     + `group` out. Exactly one, within 10 s of the page's open, in the group thread, and
 *     nothing in the 1:1 thread with ios_a (the page has not opened that one);
 *  4. send `from android #1` into the group;
 *  5. wait for `hello from the browser` — typed on the page, fanned out by the iPhone — attributed
 *     to ios_a, and READ it (per-member receipt → the page shows `READ 1/1`); once the iPhone
 *     confirms our READ (`read.txt`): still one #gate disclosure, 1:1 still untouched → publish
 *     `android-read.txt`; the page then opens the 1:1 thread (`viewed-1to1.txt`) → `view` →
 *     `⚠ peer linked a desktop browser (…) — this conversation may be viewed there` lands in the
 *     1:1 thread within 10 s (exactly one, nothing new in #gate) → publish `android-1to1.txt`;
 *     the page goes back to #gate (a second `view`) — the phone must not disclose it twice;
 *  6. the iPhone unlinks while we are still a member (`unlinked.txt`): wait for
 *     `── <ios_a> unlinked the desktop browser ──` in #gate AND `peer unlinked the desktop
 *     browser` in the 1:1 thread (exactly the two announced threads, one row each) and publish
 *     `android-unlinked.txt`; the page rescans, the iPhone relinks (`relinked.txt`) and the page
 *     reopens #gate → `view` → a SECOND linked row here (a fresh link session announces afresh);
 *     the 1:1 thread, not reopened, gets nothing new;
 *  7. print our colour indices (must equal the iOS `GATE: colour` lines and the page's colours);
 *  8. leave the group: the iPhone gets `group_leave`, the page a `group` upsert + a system row;
 *  9. the iPhone's final unlink (`unlinked2.txt`): we left #gate and the 1:1 thread was never
 *     announced in the second session → nothing arrives here.
 *
 * Skipped unless the names file is given. Launched together with the other two sides by the
 * scratchpad `mirror-gate/run.sh`.
 */
class MirrorGateAndroidTest {
    private val namesFile: Path? = (System.getProperty("pyro.group.interop.names") ?: System.getenv("PYRO_GROUP_INTEROP_NAMES"))
        ?.takeIf { it.isNotBlank() }?.let { Paths.get(it).toAbsolutePath() }

    private fun gate(s: String) = println("GATE: $s")

    private fun ChatService.rows(thread: String): List<ChatMessage> = messages.value[thread] ?: emptyList()

    private fun linkedRow(iosA: String, m: ChatMessage): Boolean =
        m.kind == Kind.SYSTEM && m.text.startsWith("── <$iosA> linked a desktop browser") && m.text.endsWith("— this channel may be viewed there ──")

    private fun publish(dir: Path, name: String, content: String) {
        val tmp = Files.createTempFile(dir, name, ".tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    fun `Mirror gate - Android member of #gate while the iPhone mirrors it to a browser`(): Unit = runBlocking {
        Assumptions.assumeTrue(namesFile != null, "SKIPPED: no names file — pass -PgroupInteropNames=<dir>/ios.txt (and run MirrorGateTests on the simulator)")
        Live.requireRelay()
        val iosNames = namesFile!!
        val dir = iosNames.parent
        val readOn: (Blob) -> Blob = { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
        val me = Live.makeService("andro_", configure = readOn)
        try {
            // ---- 1. register FIRST and publish the name (atomically — the iOS side polls the file)
            me.svc.register(me.username)
            publish(dir, "android.txt", me.username + "\n")
            gate("android <${me.username}> registered, hash=${me.hash.take(12)}… — name published to ${dir.resolve("android.txt")}")

            // ---- 2. wait to be invited: #gate with 2 members (xcodebuild needs minutes to build + boot)
            gate("waiting for #gate (group_create from ios_a) — up to 10 min")
            assertTrue(waitUntil(10 * 60_000) { me.svc.groups.value.any { it.name == "gate" && it.members.size == 2 } }, "group_create from ios_a must land here")
            val g = me.svc.groups.value.first { it.name == "gate" }
            gate("invited to #${g.name} id=${g.id} epoch=${g.epoch} policy=${g.policy} creator=${g.creator.take(12)}… members=${g.members.map { it.name }}")
            assertTrue(g.isMember(me.hash))
            assertTrue(waitUntil(30_000) { Files.isRegularFile(iosNames) && Files.readAllLines(iosNames).any { it.isNotBlank() } }, "the iOS side publishes ios_a to $iosNames")
            val iosA = Files.readAllLines(iosNames).map { it.trim() }.first { it.isNotEmpty() }
            val iosAHash = UsernameHash.of(iosA)
            assertEquals(iosAHash, g.creator, "ios_a is the creator")
            gate("iOS creator: <$iosA> ${iosAHash.take(12)}…")
            // a real 1:1 thread with ios_a (it already added us): §5 says it hears nothing until the
            // browser opens it — the session with ios_a already exists (its group_create was a PreKey message)
            me.svc.addContact(iosA)
            assertTrue(me.svc.contacts.value.any { it.hash == iosAHash && it.name == iosA }, "ios_a is a contact")
            val oneToOne: (ChatMessage) -> Boolean = { it.kind == Kind.SYSTEM && it.text.contains("desktop browser") }
            gate("added <$iosA> as a contact — 1:1 rows: ${me.svc.rows(iosAHash).map { it.text }}")

            // the page must be linked before we speak, so the row crosses the mirror live
            gate("waiting for linked.txt (the page acked the iPhone's link) — up to 5 min")
            assertTrue(waitUntil(5 * 60_000) { Files.isRegularFile(dir.resolve("linked.txt")) }, "the iOS side writes linked.txt once the page acked")
            // §5: NOTHING at link time — the notice only comes once the browser opens #gate
            val atLink = me.svc.rows(g.id).filter { linkedRow(iosA, it) }
            gate("linked.txt seen; #gate rows now: ${me.svc.rows(g.id).map { it.text }}")
            assertTrue(atLink.isEmpty(), "no 'linked a desktop browser' row at link time: ${atLink.map { it.text }}")
            delay(2_000) // anything the phone would still broadcast at link time has had time to land
            assertTrue(me.svc.rows(g.id).none(oneToOne), "still nothing in #gate 2 s after the link: ${me.svc.rows(g.id).map { it.text }}")
            assertTrue(me.svc.rows(iosAHash).none(oneToOne), "and nothing in the 1:1 thread: ${me.svc.rows(iosAHash).map { it.text }}")
            publish(dir, "android-linked.txt", "clean\n")
            gate("nothing disclosed at link time — wrote android-linked.txt (the page may open #gate now)")

            // ---- 3. the browser opens #gate → `view` → `web_link` + group from the iPhone → ONE system row here
            gate("waiting for '── <$iosA> linked a desktop browser (…) ──' (the page opened #gate) — up to 5 min")
            assertTrue(waitUntil(5 * 60_000) { me.svc.rows(g.id).any { linkedRow(iosA, it) } }, "the browser opening #gate must disclose the link to the Android member")
            val arrivedGate = System.currentTimeMillis()
            val openedGate = Files.readString(dir.resolve("viewed-gate.txt")).trim().toLong()
            val linked = me.svc.rows(g.id).filter { linkedRow(iosA, it) }
            gate("linked row: '${linked.first().text}' id=${linked.first().id} group=${linked.first().group} — ${arrivedGate - openedGate} ms after the page opened #gate")
            assertTrue(arrivedGate - openedGate in 0..10_000, "the 'linked' row must land within 10 s of the page opening #gate (took ${arrivedGate - openedGate} ms)")
            assertEquals(1, linked.size, "exactly one disclosure per link session: ${linked.map { it.text }}")
            assertEquals(g.id, linked.first().group)
            assertTrue(me.svc.rows(iosAHash).none(oneToOne), "not in the 1:1 thread with ios_a (the page has not opened it): ${me.svc.rows(iosAHash).map { it.text }}")

            // ---- 4. `from android #1` into the group
            me.svc.sendText("from android #1", g.id)
            gate("sent 'from android #1' (fan-out to ios_a)")
            assertTrue(waitUntil(60_000) { me.svc.rows(g.id).any { it.direction == Direction.OUTGOING && it.text == "from android #1" && it.status == Status.SENT } }, "the row goes SENT on the relay's verdict")

            // ---- 5. the browser's text arrives from ios_a; READ it
            gate("waiting for 'hello from the browser' (typed on the page, fanned out by ios_a) — up to 5 min")
            assertTrue(waitUntil(5 * 60_000) { me.svc.rows(g.id).any { it.text == "hello from the browser" } }, "the browser's send_text must reach the Android member")
            val row = me.svc.rows(g.id).first { it.text == "hello from the browser" }
            assertEquals(Direction.INCOMING, row.direction)
            assertEquals(Kind.TEXT, row.kind)
            assertEquals(iosAHash, row.sender, "attributed to ios_a (the phone fans out, not the browser)")
            assertEquals(g.id, row.group)
            assertTrue(me.svc.rows(iosAHash).none { it.text == "hello from the browser" }, "never in a 1:1 thread")
            gate("'hello from the browser' arrived: id=${row.id} sender=${row.sender!!.take(12)}… (= ios_a) status=${row.status}")
            me.svc.noteRowVisible(row.id, g.id) // read-dwell: READ receipt to ios_a, with `group`
            assertTrue(waitUntil(30_000) { me.svc.rows(g.id).first { it.id == row.id }.status == Status.READ }, "read locally")
            gate("READ the browser row (receipt to ios_a)")
            // receipts flush on a 300 ms debounce and a left member's group receipts are dropped —
            // leave only once the iPhone confirmed it holds our READ (it writes read.txt)
            assertTrue(waitUntil(90_000) { Files.isRegularFile(dir.resolve("read.txt")) }, "the iOS side confirms READ 1/1 before we go on")
            assertEquals(1, me.svc.rows(g.id).count { linkedRow(iosA, it) }, "still one disclosure after a send, typing and a read from the page")
            assertTrue(me.svc.rows(iosAHash).none(oneToOne), "the 1:1 thread is still untouched — the page has not opened it: ${me.svc.rows(iosAHash).map { it.text }}")
            publish(dir, "android-read.txt", "read\n")

            // ---- 5b. the page opens the 1:1 thread with us → `view` → the 1:1 notice lands here, and only here
            gate("waiting for '⚠ peer linked a desktop browser (…)' in the 1:1 thread (the page opens it) — up to 3 min")
            val linked1to1: (ChatMessage) -> Boolean = {
                it.kind == Kind.SYSTEM && it.text.startsWith("⚠ peer linked a desktop browser") && it.text.endsWith("— this conversation may be viewed there")
            }
            assertTrue(waitUntil(3 * 60_000) { me.svc.rows(iosAHash).any(linked1to1) }, "the page opening the 1:1 thread must disclose the link in it; rows=${me.svc.rows(iosAHash).map { it.text }}")
            val arrived1to1 = System.currentTimeMillis()
            val opened1to1 = Files.readString(dir.resolve("viewed-1to1.txt")).trim().toLong()
            gate("1:1 linked row: '${me.svc.rows(iosAHash).first(linked1to1).text}' — ${arrived1to1 - opened1to1} ms after the page opened the 1:1 thread")
            assertTrue(arrived1to1 - opened1to1 in 0..10_000, "within 10 s of the page opening the 1:1 thread (took ${arrived1to1 - opened1to1} ms)")
            assertEquals(1, me.svc.rows(iosAHash).count(linked1to1), "exactly one 1:1 disclosure: ${me.svc.rows(iosAHash).map { it.text }}")
            assertTrue(me.svc.rows(iosAHash).all { it.group == null }, "1:1 rows carry no group")
            assertEquals(1, me.svc.rows(g.id).count { linkedRow(iosA, it) }, "the 1:1 view added nothing to #gate")
            publish(dir, "android-1to1.txt", "linked\n")
            // the page goes back to #gate (a second `view` of it): the phone must not disclose it twice
            delay(3_000)
            assertEquals(1, me.svc.rows(g.id).count { linkedRow(iosA, it) }, "re-viewing #gate on the page is not a second disclosure: ${me.svc.rows(g.id).map { it.text }}")

            // ---- 6. the iPhone unlinks (we are still a member): the retraction reaches the two announced threads, nothing else
            gate("waiting for '── <$iosA> unlinked the desktop browser ──' (the iPhone unlinks) — up to 5 min")
            val unlinkedText = "── <$iosA> unlinked the desktop browser ──"
            assertTrue(waitUntil(5 * 60_000) { me.svc.rows(g.id).any { it.kind == Kind.SYSTEM && it.text == unlinkedText } }, "the unlink must be disclosed to the Android member; rows=${me.svc.rows(g.id).map { it.text }}")
            val unlinked1to1 = "peer unlinked the desktop browser"
            assertTrue(waitUntil(30_000) { me.svc.rows(iosAHash).any { it.kind == Kind.SYSTEM && it.text == unlinked1to1 } }, "the 1:1 thread was announced too, so it is told about the unlink; rows=${me.svc.rows(iosAHash).map { it.text }}")
            delay(2_000) // a stray duplicate would land right behind
            assertEquals(1, me.svc.rows(g.id).count { it.text == unlinkedText }, "one unlink notice in #gate: ${me.svc.rows(g.id).map { it.text }}")
            assertEquals(1, me.svc.rows(iosAHash).count { it.text == unlinked1to1 }, "one unlink notice in the 1:1 thread: ${me.svc.rows(iosAHash).map { it.text }}")
            val sys1to1 = me.svc.rows(iosAHash).filter { it.kind == Kind.SYSTEM }
            assertEquals(2, sys1to1.size, "1:1 system rows: linked, unlinked — nothing else: ${sys1to1.map { it.text }}")
            assertTrue(linked1to1(sys1to1[0]) && sys1to1[1].text == unlinked1to1, "linked then unlinked, in that order: ${sys1to1.map { it.text }}")
            gate("1:1 thread after unlink #1: ${sys1to1.map { it.text }}")
            publish(dir, "android-unlinked.txt", "unlinked\n")
            gate("unlinked row arrived — wrote android-unlinked.txt (iOS unlinked.txt present: ${Files.isRegularFile(dir.resolve("unlinked.txt"))})")
            // the relink: a fresh link session announces nothing by itself; the page's reopen of #gate
            // (`view` after the relink's snapshot) discloses it again → a second linked row here
            gate("waiting for relinked.txt (the page acked the relink) and a second linked row — up to 5 min")
            assertTrue(waitUntil(5 * 60_000) { Files.isRegularFile(dir.resolve("relinked.txt")) }, "the iOS side writes relinked.txt once the page acked the relink")
            assertTrue(waitUntil(5 * 60_000) { me.svc.rows(g.id).count { linkedRow(iosA, it) } >= 2 }, "the page reopening #gate after the relink must disclose the link again; rows=${me.svc.rows(g.id).map { it.text }}")
            val texts = me.svc.rows(g.id).filter { it.kind == Kind.SYSTEM }.map { it.text }
            gate("second linked row after the relink: '${me.svc.rows(g.id).last { linkedRow(iosA, it) }.text}'; system rows=$texts")
            assertEquals(2, me.svc.rows(g.id).count { linkedRow(iosA, it) }, "one disclosure per link session, two sessions")
            assertTrue(texts.indexOf(unlinkedText) in 1 until texts.lastIndex, "linked → unlinked → linked, in that order: $texts")
            assertEquals(sys1to1.map { it.text }, me.svc.rows(iosAHash).filter { it.kind == Kind.SYSTEM }.map { it.text }, "the page did not reopen the 1:1 thread after the relink → nothing new there")

            // ---- 7. colour indices (§5) — must equal the iOS `GATE: colour` lines and the page's colours
            val live = me.svc.group(g.id)!!
            for (m in GroupColours.joinOrder(live.colourRoster)) {
                val idx = GroupColours.joinIndex(live.colourRoster, m.hash)!!
                gate("colour on android: <${m.name}> hash=${m.hash} index=$idx hex=${GroupColours.hex(idx)}")
            }

            // ---- 8. leave: the iPhone gets group_leave, the page a `group` upsert + `── <andro_…> left ──`
            gate("leaving #gate")
            me.svc.leaveGroup(g.id)
            assertTrue(waitUntil(10_000) { me.svc.group(g.id)?.isLeft == true }, "own copy marked left")
            assertTrue(waitUntil(30_000) { me.svc.pendingGroupControlCount() == 0 }, "the leave control confirmed by the relay for ios_a")
            gate("left #gate — own copy left=${me.svc.group(g.id)!!.isLeft}; rows=${me.svc.rows(g.id).map { it.text }}")

            // ---- 9. unlink #2 (after the page finished): we left #gate and the 1:1 thread was not
            //         announced in the second session → nobody here is told anything
            val gateRowsBefore = me.svc.rows(g.id).map { it.text }
            val oneToOneBefore = me.svc.rows(iosAHash).map { it.text }
            gate("waiting for unlinked2.txt (the iPhone's final unlink) — up to 5 min")
            assertTrue(waitUntil(5 * 60_000) { Files.isRegularFile(dir.resolve("unlinked2.txt")) }, "the iOS side writes unlinked2.txt after its final unlink")
            delay(3_000)
            assertEquals(gateRowsBefore, me.svc.rows(g.id).map { it.text }, "a member who left hears nothing at unlink #2")
            assertEquals(oneToOneBefore, me.svc.rows(iosAHash).map { it.text }, "a 1:1 thread not announced in the second session hears nothing at unlink #2")
            gate("unlink #2: nothing arrived here — #gate rows=${me.svc.rows(g.id).map { it.text }}; 1:1 rows=${me.svc.rows(iosAHash).map { it.text }}")
            gate("PASSED")
            me.svc.goOffline()
        } finally {
            me.close()
        }
    }
}
