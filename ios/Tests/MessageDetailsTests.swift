import XCTest
@testable import PyroChat

/// docs/MESSAGE-DETAILS.md §4 — the `details` sheet, tested without any UI.
///
/// Two things are pinned here: the renderer is a pure function from (message,
/// optional captured frame, group roster) to the sheet's lines, and NOTHING
/// else can reach it — no session key, no store key, no plaintext (not even
/// the row's own words, let alone another message's).
final class MessageDetailsTests: XCTestCase {

    // fixed identities; hashes sort A < B < C
    let me = String(repeating: "a", count: 64)
    let aliceHash = String(repeating: "b", count: 64)
    let bobHash = String(repeating: "c", count: 64)
    let t0 = Date(timeIntervalSince1970: 1_700_000_000)   // 2023-11-14 22:13:20 UTC
    let utc = TimeZone(identifier: "UTC")!

    // MARK: fixtures

    func text(_ direction: ChatMessage.Direction, id: String = "7f3c9a12-0000-4000-8000-000000000001",
              status: ChatMessage.Status = .read, at offset: TimeInterval = 0) -> ChatMessage {
        ChatMessage(id: id, direction: direction, kind: .text,
                    text: "TOPSECRET-PLAINTEXT-nobody-may-see-this",
                    timestamp: t0.addingTimeInterval(offset), status: status)
    }

    func frame(_ t: String = "send", id: String = "7f3c9a12-0000-4000-8000-000000000001",
               peer: String? = nil, kind: String = "msg", bytes: Int = 412) -> CapturedFrame {
        let body = String(repeating: "M", count: 4 * (bytes / 3 + 1))
        return CapturedFrame(t: t, id: id, peer: peer ?? aliceHash,
                             ttl: t == "send" ? 172_800 : nil, kind: kind,
                             body: body, ciphertextBytes: bytes)
    }

    func group(name: String = "ops") -> Group {
        Group(id: "0ps00000-0000-4000-8000-000000000001", name: name, creator: me,
              createdAt: t0, epoch: 2, policy: .burnRemove, burnSeconds: nil,
              members: [Member(hash: me, name: "demo_user", joinedAt: t0),
                        Member(hash: aliceHash, name: "alice", joinedAt: t0),
                        Member(hash: bobHash, name: "bob", joinedAt: t0.addingTimeInterval(60))])
    }

    func input(_ m: ChatMessage, frame f: CapturedFrame? = nil, group g: Group? = nil,
               showRead: Bool = true) -> MessageDetails.Input {
        MessageDetails.Input(message: m, frame: f, group: g, peerName: "alice",
                             peerHash: aliceHash, myHash: me, showRead: showRead,
                             timeZone: utc)
    }

    /// A label/value line as the sheet prints it. The padding itself is pinned
    /// by `testLabelColumnIsFourteenWide` and by the golden sheet below.
    func padded(_ label: String, _ value: String) -> String {
        MessageDetails.Line(kind: .row, label: label, value: value).text
    }

    // MARK: §2 — the whole sheet, own 1:1 text, capture present

    func testOwnTextWithCaptureIsTheWholeSpecSheet() {
        let lines = MessageDetails.plainText(input(text(.outgoing), frame: frame()))
        XCTAssertEqual(lines, [
            "\u{2500}\u{2500} what happened \u{2500}\u{2500}",
            "sent          22:13:20",
            "status        READ",
            "\u{2500}\u{2500} what it is \u{2500}\u{2500}",
            "kind          text",
            "\u{2500}\u{2500} what the relay received \u{2500}\u{2500}",
            "{\"t\":\"send\",\"id\":\"7f3c9a12\u{2026}\",\"to\":\"bbbbbb\u{2026}bb\",",
            " \"ttl\":172800,\"kind\":\"msg\",\"body\":\"MMMMMMMMM\u{2026}\"}",
            "412 bytes of ciphertext",
            "\u{2500}\u{2500} what it can still work out \u{2500}\u{2500}",
            "your address, the time, the size, and that you wrote to <alice>",
            "\u{2500}\u{2500} what it cannot \u{2500}\u{2500}",
            "the words, your name, the burn timer, or whether this is part of a group",
        ])
    }

    func testHeadingsAreVerbatim() {
        // the strings the spec prints, character for character
        XCTAssertEqual(MessageDetails.headingWhatHappened, "── what happened ──")
        XCTAssertEqual(MessageDetails.headingWhatItIs, "── what it is ──")
        XCTAssertEqual(MessageDetails.headingWhatTheRelayReceived, "── what the relay received ──")
        XCTAssertEqual(MessageDetails.headingWhatItCanWorkOut, "── what it can still work out ──")
        XCTAssertEqual(MessageDetails.headingWhatItCannot, "── what it cannot ──")
        XCTAssertEqual(MessageDetails.bodyNotKept,
                       "body: not kept — PyroChat never stores ciphertext")
        // the sections come in the spec's order, exactly once each
        let headings = MessageDetails.lines(input(text(.outgoing), frame: frame()))
            .filter { $0.kind == .heading }.map(\.value)
        XCTAssertEqual(headings, [MessageDetails.headingWhatHappened,
                                  MessageDetails.headingWhatItIs,
                                  MessageDetails.headingWhatTheRelayReceived,
                                  MessageDetails.headingWhatItCanWorkOut,
                                  MessageDetails.headingWhatItCannot])
        XCTAssertFalse(MessageDetails.lines(input(text(.outgoing))).contains { $0.kind == .footer },
                       "no footer line is emitted")
    }

    func testLabelColumnIsFourteenWide() {
        let l = MessageDetails.Line(kind: .row, label: "written on", value: "a linked desktop")
        XCTAssertEqual(l.text, "written on    a linked desktop")
        XCTAssertEqual(l.text.prefix(14).count, 14)
        // a label longer than the column still gets one separating space
        let long = MessageDetails.Line(kind: .memberRow, label: "<averyverylongname>", value: "read")
        XCTAssertEqual(long.text, "<averyverylongname> read")
        // headings/prose/frames are not padded
        XCTAssertEqual(MessageDetails.Line(kind: .prose, value: "x").text, "x")
    }

    // MARK: §2 — received rows read from the relay's point of view

    func testReceivedTextFlipsTheFrameAndBothClosingSections() {
        let m = text(.incoming, status: .delivered)
        let lines = MessageDetails.plainText(
            input(m, frame: frame("recv", peer: aliceHash)))
        XCTAssertTrue(lines.contains(padded("received", "22:13:20")))
        XCTAssertFalse(lines.contains { $0.hasPrefix("status") },
                       "a received row has no send status to report")
        XCTAssertTrue(lines.contains("{\"t\":\"recv\",\"id\":\"7f3c9a12\u{2026}\",\"from\":\"bbbbbb\u{2026}bb\","))
        XCTAssertTrue(lines.contains(" \"kind\":\"msg\",\"body\":\"MMMMMMMMM\u{2026}\"}"),
                      "a recv frame carries no ttl")
        XCTAssertTrue(lines.contains("your address, the time, the size, and that <alice> wrote to you"))
    }

    // MARK: §2 — what it is

    func testKindLine() {
        func kind(_ m: ChatMessage) -> String? {
            MessageDetails.lines(input(m)).first { $0.label == "kind" }?.value
        }
        XCTAssertEqual(kind(text(.outgoing)), "text")

        var voice = text(.outgoing); voice.kind = .voice; voice.voiceDuration = 4
        XCTAssertEqual(kind(voice), "voice · 4s")
        voice.voiceDuration = nil
        XCTAssertEqual(kind(voice), "voice", "no duration ⇒ no invented one")

        var image = text(.outgoing); image.kind = .image
        image.imageWidth = 640; image.imageHeight = 480
        XCTAssertEqual(kind(image), "image 640x480")
        image.imageWidth = nil
        XCTAssertEqual(kind(image), "image")

        var file = text(.outgoing); file.kind = .file
        file.fileName = "report.pdf"; file.fileBytes = 43_112
        XCTAssertEqual(kind(file), "file report.pdf 42 KB")
        file.fileBytes = nil
        XCTAssertEqual(kind(file), "file report.pdf")

        var system = text(.incoming); system.kind = .system
        XCTAssertEqual(kind(system), "system")
    }

    func testByteAndDurationSpelling() {
        XCTAssertEqual(MessageDetails.byteSize(512), "512 B")
        XCTAssertEqual(MessageDetails.byteSize(43_112), "42 KB")
        XCTAssertEqual(MessageDetails.byteSize(3_500_000), "3.3 MB")
        XCTAssertEqual(MessageDetails.duration(5), "5s")
        XCTAssertEqual(MessageDetails.duration(60), "1m")
        XCTAssertEqual(MessageDetails.duration(300), "5m")
        XCTAssertEqual(MessageDetails.duration(3_600), "1h")
        XCTAssertEqual(MessageDetails.duration(86_400), "1d")
        XCTAssertEqual(MessageDetails.duration(90), "1m30s")
        XCTAssertEqual(MessageDetails.duration(5_400), "1h30m")
    }

    func testBurnRowOnlyWhenThereIsATimerAndWebRowOnlyWhenTagged() {
        var m = text(.outgoing)
        XCTAssertFalse(MessageDetails.plainText(input(m)).contains { $0.hasPrefix("burns") })
        XCTAssertFalse(MessageDetails.plainText(input(m)).contains { $0.hasPrefix("written on") })

        m.burnAfter = 300
        XCTAssertTrue(MessageDetails.plainText(input(m)).contains(padded("burns", "5m after you read it")))
        m.burnAfter = 0
        XCTAssertFalse(MessageDetails.plainText(input(m)).contains { $0.hasPrefix("burns") },
                       "an explicit off is not a timer")

        m.burnAfter = 3_600
        m.burnStartedAt = t0                      // a running burn row is still just `burns`
        m.viaWeb = true
        let lines = MessageDetails.plainText(input(m))
        XCTAssertTrue(lines.contains(padded("burns", "1h after you read it")))
        XCTAssertTrue(lines.contains(padded("written on", "a linked desktop")))
    }

    // MARK: §2 — groups

    func testGroupRowHasPerMemberReceiptsInColourAndTheEnvelopeCount() {
        let g = group()
        var m = text(.outgoing, status: .read)
        m.group = g.id
        m.receipts = [aliceHash: "read"]          // bob has not reported at all
        let lines = MessageDetails.lines(input(m, frame: frame(peer: aliceHash), group: g))
        let plain = lines.map(\.text)

        XCTAssertTrue(plain.contains(padded("status", "READ 1/2")))
        XCTAssertTrue(plain.contains(padded("<alice>", "read")))
        XCTAssertTrue(plain.contains(padded("<bob>", "\u{2014}")), "no receipt ⇒ an em dash, not a guess")
        XCTAssertFalse(plain.contains { $0.hasPrefix("<demo_user>") }, "I am not a recipient of my own row")

        let members = lines.filter { $0.kind == .memberRow }
        XCTAssertEqual(members.map(\.label), ["<alice>", "<bob>"], "join order, so colours never shift")
        XCTAssertEqual(members[0].colour, GroupMath.colour(of: aliceHash, in: g))
        XCTAssertEqual(members[1].colour, GroupMath.colour(of: bobHash, in: g))
        XCTAssertNotEqual(members[0].colour, members[1].colour)

        XCTAssertTrue(plain.contains(
            "sent as 2 separate envelopes, one per member \u{2014} each is different ciphertext, "
            + "and the relay is never told they belong together."))
        // exactly one envelope on screen
        XCTAssertEqual(lines.filter { $0.kind == .frame }.count, 2, "one envelope, wrapped over two lines")
        XCTAssertTrue(plain.contains("the words, your name, the burn timer, or that #ops exists"))
        XCTAssertTrue(plain.contains("your address, the time, the size, and that you wrote to <alice>"),
                      "the shown envelope's own recipient, never the group")
    }

    func testGroupMixedReceiptsAndDeliveredOnly() {
        let g = group()
        var m = text(.outgoing, status: .delivered)
        m.group = g.id
        m.receipts = [aliceHash: "read", bobHash: "delivered"]
        let plain = MessageDetails.plainText(input(m, group: g))
        XCTAssertTrue(plain.contains(padded("<alice>", "read")))
        XCTAssertTrue(plain.contains(padded("<bob>", "delivered")))
        XCTAssertTrue(plain.contains(padded("status", "READ 1/2")))
    }

    func testReciprocityHidesReadEverywhereAtOnce() {
        let g = group()
        var m = text(.outgoing, status: .read)
        m.group = g.id
        m.receipts = [aliceHash: "read", bobHash: "read"]
        let plain = MessageDetails.plainText(input(m, group: g, showRead: false))
        XCTAssertTrue(plain.contains(padded("<alice>", "delivered")))
        XCTAssertTrue(plain.contains(padded("<bob>", "delivered")))
        XCTAssertFalse(plain.contains { $0.contains("read") })

        // …and on a 1:1 row
        XCTAssertTrue(MessageDetails.plainText(input(text(.outgoing), showRead: false))
            .contains(padded("status", "DELIVERED")))
    }

    func testIncomingGroupRowNamesTheSenderAndCountsNoEnvelopes() {
        let g = group()
        var m = text(.incoming, status: .delivered)
        m.group = g.id
        m.sender = bobHash
        let plain = MessageDetails.plainText(input(m, frame: frame("recv", peer: bobHash), group: g))
        XCTAssertTrue(plain.contains("your address, the time, the size, and that <bob> wrote to you"))
        XCTAssertFalse(plain.contains { $0.hasPrefix("sent as ") },
                       "one envelope arrived; nothing was fanned out")
        XCTAssertFalse(plain.contains { $0.hasPrefix("<alice>") }, "receipts are an own-row thing")
        XCTAssertTrue(plain.contains("the words, your name, the burn timer, or that #ops exists"))
    }

    func testGoneAndFormerMembersAreNotRecipients() {
        var g = group()
        g.members[2].gone = true                    // bob: the relay forgot his keys
        g.former = [Member(hash: String(repeating: "d", count: 64), name: "carol", joinedAt: t0)]
        var m = text(.outgoing, status: .delivered)
        m.group = g.id
        m.receipts = [aliceHash: "delivered"]
        let plain = MessageDetails.plainText(input(m, group: g))
        XCTAssertTrue(plain.contains("sent as 1 separate envelopes, one per member \u{2014} each is different ciphertext, and the relay is never told they belong together."))
        XCTAssertTrue(plain.contains(padded("<alice>", "delivered")))
        XCTAssertFalse(plain.contains { $0.hasPrefix("<bob>") })
        XCTAssertFalse(plain.contains { $0.hasPrefix("<carol>") })
    }

    // MARK: §3 — no capture

    func testNoCaptureShowsTheKnownFieldsAndSaysWhyTheBodyIsGone() {
        let lines = MessageDetails.lines(input(text(.outgoing)))
        let frameLines = lines.filter { $0.kind == .frame }.map(\.value)
        XCTAssertEqual(frameLines, [
            "{\"t\":\"send\",\"id\":\"7f3c9a12\u{2026}\",\"to\":\"bbbbbb\u{2026}bb\",",
            " \"ttl\":172800,\"kind\":\"msg\"}",
        ], "the envelope closes cleanly and carries no body field")
        let plain = lines.map(\.text)
        XCTAssertTrue(plain.contains("body: not kept \u{2014} PyroChat never stores ciphertext"),
                      "the absence is stated under the envelope, not inside it")
        XCTAssertFalse(plain.contains { $0.hasSuffix("bytes of ciphertext") },
                       "no capture ⇒ no size to claim")
        XCTAssertFalse(plain.contains { $0.contains("\"body\":\"") })
    }

    // MARK: sealed frames (SEALED-SENDER.md §5.8)

    func testASealedFrameIsShownAsItCrossedTheWire() {
        let body = String(repeating: "M", count: 552)
        func frameLines(_ m: ChatMessage, _ f: CapturedFrame) -> [String] {
            MessageDetails.lines(input(m, frame: f)).filter { $0.kind == .frame }.map(\.value)
        }
        // sent sealed: the word the wire carried, the recipient, and a ttl like anything that leaves
        let sent = CapturedFrame(t: "deliver", id: "0d2f8e41-0000-4000-8000-000000000009", peer: aliceHash,
                                 ttl: 172_800, kind: "msg", body: body, ciphertextBytes: 412)
        XCTAssertEqual(frameLines(text(.outgoing), sent), [
            "{\"t\":\"deliver\",\"id\":\"0d2f8e41\u{2026}\",\"to\":\"bbbbbb\u{2026}bb\",",
            " \"ttl\":172800,\"kind\":\"msg\",\"body\":\"MMMMMMMMM\u{2026}\"}",
        ])
        // received sealed: the frame named nobody, and the line does not invent a `from`
        let got = CapturedFrame(t: "recv", id: "7f3c9a12-0000-4000-8000-000000000001", peer: "",
                                ttl: nil, kind: "msg", body: body, ciphertextBytes: 412)
        XCTAssertEqual(frameLines(text(.incoming, status: .delivered), got), [
            "{\"t\":\"recv\",\"id\":\"7f3c9a12\u{2026}\",",
            " \"kind\":\"msg\",\"body\":\"MMMMMMMMM\u{2026}\"}",
        ])
        // a frame that did name its sender still says so
        let named = frameLines(text(.incoming, status: .delivered), frame("recv"))
        XCTAssertTrue(named[0].contains("\"from\":\"bbbbbb\u{2026}bb\""))
    }

    func testNoCaptureOnAnIncomingBlobStillNamesTheRightKindAndSender() {
        var m = text(.incoming, status: .delivered)
        m.kind = .image
        m.imageWidth = 640; m.imageHeight = 480
        m.sender = nil
        let plain = MessageDetails.plainText(input(m))
        XCTAssertTrue(plain.contains("{\"t\":\"recv\",\"id\":\"7f3c9a12\u{2026}\",\"from\":\"bbbbbb\u{2026}bb\","))
        XCTAssertTrue(plain.contains(" \"kind\":\"blob\"}"))
        XCTAssertTrue(plain.contains("body: not kept \u{2014} PyroChat never stores ciphertext"))
        XCTAssertFalse(plain.contains("ciphertext}"), "the note sits outside the envelope, which closes cleanly")
    }

    func testEllipsisRules() {
        XCTAssertEqual(MessageDetails.elide("7f3c9a1234567", head: 8), "7f3c9a12\u{2026}")
        XCTAssertEqual(MessageDetails.elide("short", head: 8), "short", "nothing to hide, nothing elided")
        XCTAssertEqual(MessageDetails.elideMiddle("9a1fd4aaaaaaaac2"), "9a1fd4\u{2026}c2")
        XCTAssertEqual(MessageDetails.elideMiddle("abc"), "abc")
    }

    // MARK: §2 — system rows (menu entry appears for them too)

    func testSystemRowIsHonestAboutNeverHavingBeenAnEnvelope() {
        var m = text(.incoming)
        m.kind = .system
        m.text = "\u{26A0} peer took a screenshot"
        let plain = MessageDetails.plainText(input(m))
        XCTAssertTrue(plain.contains(padded("kind", "system")))
        XCTAssertTrue(plain.contains(MessageDetails.systemRowNote))
        XCTAssertTrue(plain.contains("nothing \u{2014} this row never went to the relay."))
        XCTAssertTrue(plain.contains("anything \u{2014} it never left this device."))
        XCTAssertFalse(plain.contains { $0.hasPrefix("{\"t\"") }, "no invented envelope")
        XCTAssertFalse(plain.contains { $0.hasPrefix("status") })
        XCTAssertEqual(plain.last, "anything \u{2014} it never left this device.",
                       "the sheet ends on the last section; there is no footer")
    }

    // MARK: §4 — nothing secret can reach the sheet

    func testTheSheetNeverPrintsTheMessagesOwnWords() {
        let secret = "TOPSECRET-PLAINTEXT-nobody-may-see-this"
        for kind in [ChatMessage.Kind.text, .voice, .image, .file, .system] {
            for direction in [ChatMessage.Direction.outgoing, .incoming] {
                var m = text(direction)
                m.kind = kind
                m.text = secret
                m.burnAfter = 300
                m.viaWeb = true
                m.voiceDuration = 4
                m.imageWidth = 640; m.imageHeight = 480
                m.fileBytes = 1_024
                for g in [nil, group()] as [Group?] {
                    var row = m
                    row.group = g?.id
                    row.sender = direction == .incoming ? bobHash : nil
                    row.receipts = direction == .outgoing ? [aliceHash: "read"] : nil
                    let joined = MessageDetails.plainText(
                        input(row, frame: frame(), group: g)).joined(separator: "\n")
                    XCTAssertFalse(joined.contains(secret),
                                   "\(kind)/\(direction)/group=\(g != nil) leaked the plaintext")
                }
            }
        }
    }

    func testAnotherMessagesPlaintextCannotReachTheSheet() {
        // the renderer only ever sees ONE message: there is no log, no store
        // and no service in `Input` to reach a second one from
        let labels = Mirror(reflecting: input(text(.outgoing), frame: frame(), group: group()))
            .children.compactMap(\.label).sorted()
        XCTAssertEqual(labels, ["frame", "group", "message", "myHash", "peerHash",
                                "peerName", "showRead", "timeZone"])
        for label in labels {
            XCTAssertFalse(["store", "crypto", "service", "key", "sessions", "messages"]
                .contains(label), "\(label) has no business in the sheet model")
        }
    }

    func testACapturedFrameHasNowhereToPutKeyMaterial() {
        let labels = Mirror(reflecting: frame()).children.compactMap(\.label).sorted()
        XCTAssertEqual(labels, ["body", "ciphertextBytes", "id", "kind", "peer", "t", "ttl"])
        // and the only two ways to make one are a Wire.Out that was sent and a
        // recv that arrived — neither carries a key
        XCTAssertNil(CapturedFrame(sending: Wire.Out(t: "ack", id: "x")))
        XCTAssertNil(CapturedFrame(sending: Wire.Out(t: "send", id: "x", to: "y",
                                                     ttl: 1, kind: "msg")), "no body ⇒ no frame")
        let out = Wire.Out(t: "send", id: "i", to: "p", ttl: 172_800, kind: "msg", body: "AAAA")
        XCTAssertEqual(CapturedFrame(sending: out)?.body, "AAAA")
        XCTAssertEqual(CapturedFrame(sending: out)?.ciphertextBytes, 3)
    }

    func testCiphertextLengthFromBase64Alone() {
        XCTAssertEqual(CapturedFrame.ciphertextLength(ofBase64: ""), 0)
        XCTAssertEqual(CapturedFrame.ciphertextLength(ofBase64: Data([1, 2, 3]).base64EncodedString()), 3)
        XCTAssertEqual(CapturedFrame.ciphertextLength(
            ofBase64: Data(repeating: 7, count: 412).base64EncodedString()), 412)
        XCTAssertEqual(CapturedFrame.ciphertextLength(
            ofBase64: Data(repeating: 7, count: 413).base64EncodedString()), 413)
        XCTAssertEqual(CapturedFrame.ciphertextLength(
            ofBase64: Data(repeating: 7, count: 414).base64EncodedString()), 414)
    }

    // MARK: §3/§4 — the capture buffer's bounds

    func makeFrame(_ n: Int, bodyBytes: Int = 16) -> CapturedFrame {
        CapturedFrame(t: "send", id: "id-\(n)", peer: aliceHash, ttl: 172_800, kind: "msg",
                      body: String(repeating: "x", count: bodyBytes), ciphertextBytes: bodyBytes)
    }

    func testBufferKeepsFiftyAndEvictsOldestFirst() {
        let buf = FrameCaptureBuffer()
        for i in 0..<60 { buf.record(makeFrame(i), for: "row-\(i)") }
        XCTAssertEqual(buf.count, 50)
        for i in 0..<10 { XCTAssertNil(buf.frame(for: "row-\(i)"), "row-\(i) should have aged out") }
        for i in 10..<60 { XCTAssertNotNil(buf.frame(for: "row-\(i)")) }
        XCTAssertEqual(buf.frame(for: "row-59")?.id, "id-59")
    }

    func testBufferHonoursTheByteBoundBeforeTheFrameBound() {
        let buf = FrameCaptureBuffer()
        // 8 frames of 64 KB would be 512 KB — well under 50 frames, over 256 KB
        for i in 0..<8 { buf.record(makeFrame(i, bodyBytes: 64 * 1024), for: "row-\(i)") }
        XCTAssertLessThan(buf.count, 8)
        XCTAssertLessThanOrEqual(buf.byteCount, FrameCaptureBuffer.maxBytes)
        XCTAssertNil(buf.frame(for: "row-0"), "oldest first")
        XCTAssertNotNil(buf.frame(for: "row-7"), "newest survives")
    }

    func testARecordBiggerThanTheWholeBudgetIsRefusedNotAccommodated() {
        let buf = FrameCaptureBuffer()
        buf.record(makeFrame(1), for: "keep-me")
        buf.record(makeFrame(2, bodyBytes: FrameCaptureBuffer.maxBytes + 1), for: "too-big")
        XCTAssertNil(buf.frame(for: "too-big"))
        XCTAssertNotNil(buf.frame(for: "keep-me"), "an oversized frame evicts nothing")
    }

    func testRerecordingARowReplacesItAndMakesItNewest() {
        let buf = FrameCaptureBuffer()
        buf.record(makeFrame(1), for: "row")
        buf.record(makeFrame(2), for: "other")
        buf.record(makeFrame(3), for: "row")          // a group fan-out's second envelope
        XCTAssertEqual(buf.count, 2, "one row, one envelope")
        XCTAssertEqual(buf.frame(for: "row")?.id, "id-3")
        // "other" is now the oldest: fill to the cap and it goes first
        for i in 10..<59 { buf.record(makeFrame(i), for: "fill-\(i)") }
        XCTAssertNil(buf.frame(for: "other"))
        XCTAssertNotNil(buf.frame(for: "row"))
    }

    func testClearEmptiesEverything() {
        let buf = FrameCaptureBuffer()
        for i in 0..<10 { buf.record(makeFrame(i), for: "row-\(i)") }
        buf.clear()
        XCTAssertEqual(buf.count, 0)
        XCTAssertEqual(buf.byteCount, 0)
        XCTAssertTrue(buf.isEmpty)
        XCTAssertNil(buf.frame(for: "row-5"))
        // and it still works afterwards
        buf.record(makeFrame(99), for: "row-99")
        XCTAssertEqual(buf.count, 1)
    }

    func testAFreshBufferIsEmptyWhichIsWhatARestartLeaves() {
        // nothing is loaded from anywhere: a new session starts with no frames,
        // so an older message's sheet always takes the "not kept" branch
        XCTAssertTrue(FrameCaptureBuffer().isEmpty)
        XCTAssertNil(FrameCaptureBuffer().frame(for: "any"))
        XCTAssertEqual(FrameCaptureBuffer.maxFrames, 50)
        XCTAssertEqual(FrameCaptureBuffer.maxBytes, 256 * 1024)
    }
}

/// The capture's lifecycle against a real (offline) service: the buffer is
/// emptied by lock and by wipe, and nothing about it is ever persisted.
@MainActor
final class FrameCaptureLifecycleTests: XCTestCase {

    private func makeService() -> ChatService {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-capture-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "capture-test-pass-123",
                                at: dir.appendingPathComponent("store"))
        return ChatService(store: store)
    }

    private let body = "ZmFrZS1jaXBoZXJ0ZXh0LWZvci10aGUtY2FwdHVyZS10ZXN0"

    func testLockEmptiesTheBuffer() {
        let svc = makeService()
        svc.captureFrame(Wire.Out(t: "send", id: "envelope-1", to: "peer", ttl: 172_800,
                                  kind: "msg", body: body), for: "row-1")
        XCTAssertNotNil(svc.frameCapture.frame(for: "row-1"))
        svc.shutdown()                             // exactly what AUTO-LOCK / lock() runs
        XCTAssertTrue(svc.frameCapture.isEmpty)
        XCTAssertNil(svc.frameCapture.frame(for: "row-1"))
    }

    func testWipeEmptiesTheBuffer() {
        let svc = makeService()
        svc.captureFrame(Wire.Out(t: "send", id: "envelope-1", to: "peer", ttl: 172_800,
                                  kind: "msg", body: body), for: "row-1")
        svc.goOffline()                            // the TOTAL WIPE path's last service call
        XCTAssertTrue(svc.frameCapture.isEmpty)
    }

    func testNothingCapturedIsEverWrittenToTheStore() throws {
        let svc = makeService()
        svc.captureFrame(Wire.Out(t: "send", id: "envelope-1", to: "peer", ttl: 172_800,
                                  kind: "msg", body: body), for: "row-1")
        svc.store.flush()
        let encoded = try JSONEncoder().encode(svc.store.blob)
        let json = String(decoding: encoded, as: UTF8.self)
        XCTAssertFalse(json.contains(body), "ciphertext must never reach the store")
        XCTAssertFalse(json.lowercased().contains("capturedframe"))
        XCTAssertFalse(json.lowercased().contains("framecapture"))
        // the buffer is not part of the encodable surface at all
        XCTAssertFalse(Mirror(reflecting: svc.store.blob).children
            .compactMap(\.label).contains { $0.lowercased().contains("frame") })
    }

    func testOnlyMessageEnvelopesAreCapturedNotControls() {
        let svc = makeService()
        // a control (receipt, typing, roster change) is not a row and never
        // gets a details sheet — nothing of it is kept
        svc.captureFrame(Wire.Out(t: "ack", id: "a"), for: "row-1")
        XCTAssertTrue(svc.frameCapture.isEmpty)
        svc.captureFrame(Wire.Out(t: "send", id: "e", to: "p", ttl: 86_400, kind: "control",
                                  body: body), for: "")
        XCTAssertTrue(svc.frameCapture.isEmpty, "an empty row id files nothing")
    }
}
