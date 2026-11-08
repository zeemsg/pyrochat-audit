package com.pyrochat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Base64

/**
 * docs/MESSAGE-DETAILS.md §4 — the renderer is a pure function from (message, optional captured
 * frame, group roster) to the sheet's lines. Every kind, both directions, a group with mixed
 * receipts, capture present and absent, the envelope-count line — and, hardest, that nothing the
 * sheet must never show can reach it.
 */
class MessageDetailsTest {
    private val utc = ZoneId.of("UTC")
    private val t0 = 1_700_000_827_000L // 2023-11-14 22:27:07 UTC
    private val me = "1".repeat(64)
    private val aliceHash = "9a1fd4" + "0".repeat(56) + "c2"
    private val bobHash = "b".repeat(64)
    private val carolHash = "c".repeat(64)
    private val alice = Contact(aliceHash, "alice", t0)
    private val ops = UiGroup(
        id = "g1", name = "ops", creator = me, createdAt = t0, epoch = 2, policy = "burn_remove",
        members = listOf(
            UiMember(me, "me", t0), UiMember(aliceHash, "alice", t0 + 1), UiMember(bobHash, "bob", t0 + 2), UiMember(carolHash, "carol", t0 + 3),
        ),
    )

    private fun text(
        dir: Direction = Direction.outgoing,
        body: String = "meet me at the usual place",
        status: Status = Status.read,
        burnAfter: Long? = null,
        viaWeb: Boolean? = null,
    ) = ChatMessage("7f3c9a12-0000-4000-8000-00000000abcd", dir, Kind.text, body, t0, status, burnAfter = burnAfter, viaWeb = viaWeb)

    private fun b64(n: Int) = Base64.getEncoder().encodeToString(ByteArray(n) { (it * 31 + 7).toByte() })

    private fun sendFrame(bytes: Int = 412, peer: String = aliceHash) =
        UiFrame("send", "7f3c9a12-0000-4000-8000-00000000abcd", peer, 172_800, "msg", b64(bytes), bytes)

    private fun recvFrame(bytes: Int = 412, peer: String = aliceHash) =
        UiFrame("recv", "7f3c9a12-0000-4000-8000-00000000abcd", peer, null, "msg", b64(bytes), bytes)

    private fun input(
        message: ChatMessage = text(),
        frame: UiFrame? = sendFrame(),
        group: UiGroup? = null,
        showRead: Boolean = true,
    ) = DetailsInput(
        message = message, frame = frame, group = group, contacts = listOf(alice),
        peerName = "alice", myHash = me, showRead = showRead, zone = utc,
    )

    private fun lines(i: DetailsInput) = MessageDetails.render(i)
    private fun texts(i: DetailsInput) = lines(i).map {
        when (it) {
            is DetailLine.Heading -> it.text
            is DetailLine.Field -> "${it.label}|${it.value}"
            is DetailLine.Member -> "${it.name}|${it.value}"
            is DetailLine.Frame -> it.text
            is DetailLine.Text -> it.text
            is DetailLine.Footer -> it.text
        }
    }

    // ---- §2 the shape of the sheet -------------------------------------------------------

    @Test fun `the five headings appear once each, in spec order, followed by the footer`() {
        val all = lines(input())
        assertEquals(
            listOf(
                "── what happened ──", "── what it is ──", "── what the relay received ──",
                "── what it can still work out ──", "── what it cannot ──",
            ),
            all.filterIsInstance<DetailLine.Heading>().map { it.text },
        )
        assertTrue("no footer line is emitted", all.none { it is DetailLine.Footer })
    }

    @Test fun `what happened — own text row`() {
        val out = texts(input())
        assertTrue(out.contains("sent|22:27:07"))
        assertTrue(out.contains("status|READ"))
    }

    @Test fun `a received row is stamped with the moment it arrived, not a sent time it cannot know`() {
        val out = texts(input(message = text(dir = Direction.incoming), frame = recvFrame()))
        assertTrue(out.contains("received|22:27:07"))
        assertFalse(out.any { it.startsWith("sent|") })
    }

    @Test fun `reciprocity — without my read-receipt toggle a read reads as delivered`() {
        assertTrue(texts(input(showRead = false)).contains("status|DELIVERED"))
    }

    @Test fun `no per-status timestamps are invented — exactly one time in the sheet`() {
        val stamps = texts(input()).count { it.contains("22:27:07") }
        assertEquals(1, stamps)
    }

    // ---- §2 what it is ------------------------------------------------------------------

    @Test fun `kind reads text, voice, image, file and system`() {
        assertEquals("text", MessageDetails.kindValue(text()))
        assertEquals("voice · 4s", MessageDetails.kindValue(text().copy(kind = Kind.voice, hasVoice = true, voiceDuration = 4.2)))
        assertEquals("image 640x480", MessageDetails.kindValue(text().copy(kind = Kind.image, imageWidth = 640, imageHeight = 480)))
        assertEquals(
            "file report.pdf 42 KB",
            MessageDetails.kindValue(text().copy(kind = Kind.file, fileName = "report.pdf", fileBytes = 43_112)),
        )
        assertEquals("system", MessageDetails.kindValue(text().copy(kind = Kind.system)))
        // nothing is guessed when the row does not carry it
        assertEquals("voice", MessageDetails.kindValue(text().copy(kind = Kind.voice)))
        assertEquals("image", MessageDetails.kindValue(text().copy(kind = Kind.image)))
        assertEquals("file", MessageDetails.kindValue(text().copy(kind = Kind.file)))
    }

    @Test fun `sizes and durations read the way the spec spells them`() {
        assertEquals("999 B", MessageDetails.sizeLabel(999))
        assertEquals("42 KB", MessageDetails.sizeLabel(43_112))
        assertEquals("2.4 MB", MessageDetails.sizeLabel(2_516_582))
        assertEquals("5m", MessageDetails.durationLabel(300))
        assertEquals("5s", MessageDetails.durationLabel(5))
        assertEquals("1h", MessageDetails.durationLabel(3600))
        assertEquals("1d", MessageDetails.durationLabel(86_400))
        assertEquals("1m 30s", MessageDetails.durationLabel(90))
    }

    @Test fun `a burn row says when it goes, and a row without a timer says nothing`() {
        assertTrue(texts(input(message = text(burnAfter = 300))).contains("burns|5m after you read it"))
        assertFalse(texts(input()).any { it.startsWith("burns|") })
    }

    @Test fun `written on appears only for a row that carries the web tag`() {
        assertTrue(texts(input(message = text(viaWeb = true))).contains("written on|a linked desktop"))
        assertFalse(texts(input()).any { it.startsWith("written on|") })
        assertFalse(texts(input(message = text(viaWeb = false))).any { it.startsWith("written on|") })
    }

    // ---- §3 the envelope ------------------------------------------------------------------

    @Test fun `a captured send renders the frame and its byte count`() {
        val out = texts(input())
        assertEquals("{\"t\":\"send\",\"id\":\"7f3c9a12…\",\"to\":\"9a1fd4…c2\",", out[out.indexOfFirst { it.startsWith("{") }])
        val tail = out.first { it.startsWith(" \"ttl\"") }
        assertTrue(tail, tail.startsWith(" \"ttl\":172800,\"kind\":\"msg\",\"body\":\""))
        assertTrue(tail.endsWith("…\"}"))
        assertTrue(out.contains("412 bytes of ciphertext"))
    }

    @Test fun `a received message is a recv frame, with no ttl invented for it`() {
        val out = texts(input(message = text(dir = Direction.incoming), frame = recvFrame()))
        assertTrue(out.any { it.startsWith("{\"t\":\"recv\",\"id\":\"7f3c9a12…\",\"from\":\"9a1fd4…c2\",") })
        assertFalse(out.any { it.contains("ttl") })
    }

    @Test fun `with no capture the known fields still show and the body says what is true`() {
        val out = texts(input(frame = null))
        assertTrue(out.any { it.startsWith("{\"t\":\"send\"") && it.contains("\"to\":\"9a1fd4…c2\"") })
        assertTrue(out.contains(" \"ttl\":172800,\"kind\":\"msg\"}"))
        assertTrue(out.contains("body: not kept — PyroChat never stores ciphertext"))
        assertFalse("nothing was captured, so nothing may be counted", out.any { it.endsWith("bytes of ciphertext") })
        assertFalse("a body that was not kept is never drawn", out.any { it.contains("\"body\"") })
    }

    @Test fun `the no-capture note sits outside the braces, on its own line, where the byte count goes`() {
        val rendered = lines(input(frame = null))
        val relay = rendered.indexOfFirst { it is DetailLine.Heading && it.text == MessageDetails.H_RELAY }
        val frameLines = rendered.drop(relay + 1).takeWhile { it is DetailLine.Frame }.map { (it as DetailLine.Frame).text }
        assertEquals(2, frameLines.size)
        assertTrue("the envelope closes properly", frameLines.last().endsWith("}"))
        assertFalse("no body field, and no note inside the braces", frameLines.any { it.contains("body") })
        // the very next line is the note, standing alone exactly where `N bytes of ciphertext` goes
        val note = rendered[relay + 1 + frameLines.size]
        assertEquals(DetailLine.Text(MessageDetails.NO_BODY), note)
        assertEquals("body: not kept — PyroChat never stores ciphertext", MessageDetails.NO_BODY)

        // …and with a capture, that same slot holds the byte count
        val withFrame = lines(input())
        val relay2 = withFrame.indexOfFirst { it is DetailLine.Heading && it.text == MessageDetails.H_RELAY }
        val frame2 = withFrame.drop(relay2 + 1).takeWhile { it is DetailLine.Frame }
        assertEquals(DetailLine.Text("412 bytes of ciphertext"), withFrame[relay2 + 1 + frame2.size])
    }

    @Test fun `with no capture a received row shows recv and no ttl`() {
        val out = texts(input(message = text(dir = Direction.incoming), frame = null))
        assertTrue(out.any { it.startsWith("{\"t\":\"recv\"") })
        assertTrue(out.contains(" \"kind\":\"msg\"}"))
        assertFalse(out.any { it.contains("ttl") })
    }

    @Test fun `an attachment row is a blob on the wire`() {
        val img = text().copy(kind = Kind.image, imageWidth = 640, imageHeight = 480)
        assertTrue(texts(input(message = img, frame = null)).any { it.contains("\"kind\":\"blob\"") })
    }

    @Test fun `a system row never claims to have been sent`() {
        val out = texts(input(message = text().copy(kind = Kind.system, text = "⚠ peer took a screenshot"), frame = null))
        assertTrue(out.contains("nothing — this row never left the phone"))
        assertFalse(out.any { it.startsWith("{\"t\"") })
        assertFalse(out.contains("body: not kept — PyroChat never stores ciphertext"))
    }

    @Test fun `ids and addresses are elided, never printed whole`() {
        val out = texts(input())
        assertFalse("the full routing address never reaches the screen", out.any { it.contains(aliceHash) })
        assertFalse("nor the full frame id", out.any { it.contains("7f3c9a12-0000-4000-8000-00000000abcd") })
        assertFalse("nor the whole ciphertext", out.any { it.contains(sendFrame().body) })
        assertEquals("9a1fd4…c2", MessageDetails.elidePeer(aliceHash))
        assertEquals("7f3c9a12…", MessageDetails.elide("7f3c9a12-0000", 8))
    }

    // ---- §2/§3 groups ----------------------------------------------------------------------

    private fun groupRow(
        dir: Direction = Direction.outgoing,
        receipts: Map<String, String>? = mapOf(aliceHash to "read", bobHash to "delivered"),
        sender: String? = null,
    ) = text(dir = dir).copy(group = "g1", receipts = receipts, sender = sender, status = Status.sent)

    @Test fun `a group row lists one receipt row per member, in that member's colour`() {
        val all = lines(input(message = groupRow(), group = ops, frame = sendFrame(peer = aliceHash)))
        val members = all.filterIsInstance<DetailLine.Member>()
        assertEquals(listOf("<alice>", "<bob>", "<carol>"), members.map { it.name })
        assertEquals("I am never my own recipient", 3, members.size)
        assertEquals(listOf("read", "delivered", "—"), members.map { it.value })
        assertEquals(
            listOf(GroupUi.colour(ops, aliceHash), GroupUi.colour(ops, bobHash), GroupUi.colour(ops, carolHash)),
            members.map { it.rgb },
        )
        assertTrue(texts(input(message = groupRow(), group = ops)).contains("status|READ 1/3"))
    }

    @Test fun `receipt rows honour reciprocity too`() {
        val members = lines(input(message = groupRow(), group = ops, showRead = false)).filterIsInstance<DetailLine.Member>()
        assertEquals(listOf("delivered", "delivered", "—"), members.map { it.value })
    }

    @Test fun `an incoming group row shows no receipt rows — it has none to show`() {
        val row = groupRow(dir = Direction.incoming, receipts = null, sender = aliceHash)
        assertTrue(lines(input(message = row, group = ops, frame = recvFrame())).filterIsInstance<DetailLine.Member>().isEmpty())
    }

    @Test fun `the envelope-count line is verbatim and counts the members, not the roster`() {
        val out = texts(input(message = groupRow(), group = ops, frame = sendFrame(peer = aliceHash)))
        assertTrue(
            out.contains(
                "sent as 3 separate envelopes, one per member — each is different ciphertext, " +
                    "and the relay is never told they belong together.",
            ),
        )
        // ONE envelope is shown, whatever the count says
        assertEquals(1, out.count { it.startsWith("{\"t\"") })
    }

    @Test fun `no envelope-count line on a received group row or a 1 to 1 row`() {
        val incoming = groupRow(dir = Direction.incoming, receipts = null, sender = aliceHash)
        assertFalse(texts(input(message = incoming, group = ops, frame = recvFrame())).any { it.contains("separate envelopes") })
        assertFalse(texts(input()).any { it.contains("separate envelopes") })
    }

    // ---- §2 the two closing sections ---------------------------------------------------------

    @Test fun `the closing sections are verbatim and flip with the direction`() {
        assertEquals(
            "your address, the time, the size, and that you wrote to <alice>",
            MessageDetails.canLine(input()),
        )
        assertEquals(
            "your address, the time, the size, and that <alice> wrote to you",
            MessageDetails.canLine(input(message = text(dir = Direction.incoming), frame = recvFrame())),
        )
        assertEquals("the words, your name, or the burn timer", MessageDetails.cannotLine(input()))
    }

    @Test fun `a group names the channel the relay cannot see`() {
        assertEquals(
            "the words, your name, the burn timer, or that #ops exists",
            MessageDetails.cannotLine(input(message = groupRow(), group = ops)),
        )
        // the envelope shown went to one member, and that is the member named
        assertEquals(
            "your address, the time, the size, and that you wrote to <alice>",
            MessageDetails.canLine(input(message = groupRow(), group = ops, frame = sendFrame(peer = aliceHash))),
        )
        val fromBob = groupRow(dir = Direction.incoming, receipts = null, sender = bobHash)
        assertEquals(
            "your address, the time, the size, and that <bob> wrote to you",
            MessageDetails.canLine(input(message = fromBob, group = ops, frame = recvFrame(peer = bobHash))),
        )
    }

    // ---- §4 what must never reach the sheet ---------------------------------------------------

    @Test fun `the message's own words are never rendered`() {
        val secret = "the drop is at midnight under the bridge"
        for (dir in listOf(Direction.outgoing, Direction.incoming)) {
            for (g in listOf(null, ops)) {
                val row = text(dir = dir, body = secret).copy(group = g?.id, sender = if (dir == Direction.incoming) aliceHash else null)
                for (frame in listOf(sendFrame(), null)) {
                    val out = texts(input(message = row, frame = frame, group = g))
                    assertFalse("$dir/$g/${frame != null}", out.any { it.contains(secret) })
                    assertFalse(out.any { it.contains("drop") })
                }
            }
        }
    }

    @Test fun `a filename is shown but an attachment's bytes and a voice note's audio are not`() {
        val file = text().copy(kind = Kind.file, fileName = "salary.csv", fileBytes = 2048)
        val out = texts(input(message = file))
        assertTrue(out.contains("kind|file salary.csv 2 KB"))
        assertFalse(out.any { it.contains("attachment") })
    }

    @Test fun `only the captured frame's body ever appears as ciphertext, and only elided`() {
        val other = b64(200)
        val out = texts(input())
        assertFalse("another message's envelope cannot reach this sheet", out.any { it.contains(other) })
        val shown = out.filter { it.contains("\"body\":\"") }
        assertEquals(1, shown.size)
        val head = sendFrame().body.take(9)
        assertTrue(shown.first().contains("\"body\":\"$head…\""))
        assertEquals("nine characters is all of the ciphertext that is drawn", 9, head.length)
    }

    @Test fun `the sheet's input is a closed list with no room for a key or a store`() {
        assertEquals(
            setOf("message", "frame", "scrub", "group", "former", "contacts", "peerName", "myHash", "showRead", "zone"),
            DetailsInput::class.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }.toSet(),
        )
        // …and the only ciphertext-shaped thing in it is the envelope itself
        assertEquals(
            setOf("t", "id", "peer", "ttl", "kind", "body", "bodyBytes"),
            UiFrame::class.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }.toSet(),
        )
    }

    // ---- docs/SCRUB-AND-WIPE.md §B2/§B3/§B4 — what was removed before sending -------------------

    private fun receipt(vararg fields: Pair<String, String>, name: String = "report.pdf", supported: Boolean = true) =
        UiScrubReceipt(name, fields.map { UiRemovedField(it.first, it.second) }, supported)

    private val threeFields = arrayOf(
        "author" to "J. Example",
        "gps" to "45.5017, -73.5673",
        "producer" to "Microsoft Word 16.0",
    )

    @Test fun `the removed section sits directly after what it is`() {
        val headings = lines(input().copy(scrub = receipt(*threeFields))).filterIsInstance<DetailLine.Heading>().map { it.text }
        assertEquals(
            listOf(
                "── what happened ──", "── what it is ──", "── what was removed before sending ──",
                "── what the relay received ──", "── what it can still work out ──", "── what it cannot ──",
            ),
            headings,
        )
    }

    @Test fun `the removed section is §B2 verbatim, label and value, in the sheet's own column`() {
        val out = texts(input().copy(scrub = receipt(*threeFields)))
        val at = out.indexOf("── what was removed before sending ──")
        assertTrue(at > 0)
        assertEquals(
            listOf("author|J. Example", "gps|45.5017, -73.5673", "producer|Microsoft Word 16.0"),
            out.subList(at + 1, at + 4),
        )
        // the rows are ordinary two-column Field lines, so they wear the 14-wide column already there
        val rows = lines(input().copy(scrub = receipt(*threeFields))).filterIsInstance<DetailLine.Field>().map { it.label }
        assertTrue(rows.containsAll(listOf("author", "gps", "producer")))
    }

    @Test fun `a type we cannot scrub gets the honest line and no invented rows`() {
        val out = texts(input().copy(scrub = receipt(name = "notes.pyro", supported = false)))
        val at = out.indexOf("── what was removed before sending ──")
        assertTrue(at > 0)
        assertEquals("nothing — this file type is not one we can clean", out[at + 1])
    }

    @Test fun `a file we cleaned that held nothing is never called uncleanable`() {
        // §B2: "Never claim a file was cleaned when it was not" — and never the reverse either
        val out = texts(input().copy(scrub = receipt(name = "bare.png", supported = true)))
        assertFalse(out.contains("── what was removed before sending ──"))
        assertFalse(out.any { it.contains("not one we can clean") })
    }

    @Test fun `a row with no receipt shows no section at all`() {
        val out = texts(input())
        assertFalse(out.contains("── what was removed before sending ──"))
    }

    @Test fun `§B4's caveat is the section's footnote, verbatim`() {
        val all = lines(input().copy(scrub = receipt(*threeFields)))
        val footers = all.filterIsInstance<DetailLine.Footer>()
        assertEquals(1, footers.size)
        assertEquals(
            "this cleans the file's hidden fields, not its contents: a name written inside the document, " +
                "or a face in a photo, is still there.",
            footers.single().text,
        )
        // it closes THIS section — it comes before the relay heading, not at the end of the sheet
        assertTrue(all.indexOf(footers.single()) < all.indexOfFirst { it is DetailLine.Heading && it.text == MessageDetails.H_RELAY })
    }

    @Test fun `§B3's list grammar reads the way the spec prints it`() {
        assertEquals("author, gps and 1 more", MessageDetails.removedSummary(listOf("author", "gps", "producer")))
        assertEquals("author", MessageDetails.removedSummary(listOf("author")))
        assertEquals("author and gps", MessageDetails.removedSummary(listOf("author", "gps")))
        assertEquals("author, gps and 3 more", MessageDetails.removedSummary(listOf("author", "gps", "a", "b", "c")))
        assertEquals("", MessageDetails.removedSummary(emptyList()))
        // the whole §B3 line, as the compose bar composes it
        assertEquals(
            "removed author, gps and 1 more from report.pdf",
            "removed " + MessageDetails.removedSummary(listOf("author", "gps", "producer")) + " from report.pdf",
        )
    }

    @Test fun `the compose-bar sheet and the details sheet render the same words`() {
        val r = receipt(*threeFields)
        val standalone = MessageDetails.removedSection(r)
        val inSheet = lines(input().copy(scrub = r))
        val at = inSheet.indexOfFirst { it is DetailLine.Heading && it.text == MessageDetails.H_REMOVED }
        assertEquals(standalone, inSheet.subList(at, at + standalone.size))
        assertTrue(MessageDetails.removedSection(null).isEmpty())
    }

    @Test fun `a receipt can only ever carry field names and values`() {
        assertEquals(
            setOf("fileName", "removed", "supported"),
            UiScrubReceipt::class.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }.toSet(),
        )
        assertEquals(
            setOf("label", "value"),
            UiRemovedField::class.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }.toSet(),
        )
    }

    // ---- sealed frames (docs/SEALED-SENDER.md §5.8) ----------------------------------

    @Test fun `a sealed frame is shown as it crossed the wire`() {
        // sent sealed: the word the wire carried, the recipient, and a ttl like anything that leaves
        val sent = UiFrame("deliver", "0d2f8e41-0000-4000-8000-000000000009", aliceHash, 172_800, "msg", "M".repeat(552), 412)
        assertTrue(sent.outgoing)
        assertEquals(
            listOf("{\"t\":\"deliver\",\"id\":\"0d2f8e41…\",\"to\":\"9a1fd4…c2\",", " \"ttl\":172800,\"kind\":\"msg\",\"body\":\"MMMMMMMMM…\"}"),
            MessageDetails.frameLines(input(frame = sent)),
        )
        // received sealed: the frame named nobody, and the line does not invent a `from`
        val got = UiFrame("recv", "7f3c9a12-0000-4000-8000-00000000abcd", "", null, "msg", "M".repeat(552), 412)
        assertFalse(got.outgoing)
        assertEquals(
            listOf("{\"t\":\"recv\",\"id\":\"7f3c9a12…\",", " \"kind\":\"msg\",\"body\":\"MMMMMMMMM…\"}"),
            MessageDetails.frameLines(input(message = text(Direction.incoming, status = Status.delivered), frame = got)),
        )
        // a frame that did name its sender still says so
        val named = MessageDetails.frameLines(input(message = text(Direction.incoming, status = Status.delivered), frame = recvFrame()))
        assertTrue(named[0].contains("\"from\":\"9a1fd4…c2\""))
    }

    @Test fun `nothing in the sheet is longer than a line of terminal`() {
        // the JSON wraps the way the spec prints it: two lines, the second indented by one space
        val frameLines = MessageDetails.frameLines(input())
        assertEquals(2, frameLines.size)
        assertTrue(frameLines[0].startsWith("{") && frameLines[0].endsWith(","))
        assertTrue(frameLines[1].startsWith(" ") && frameLines[1].endsWith("}"))
        assertTrue(frameLines.all { it.length <= 56 })
    }
}
