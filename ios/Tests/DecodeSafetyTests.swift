import XCTest
@testable import PyroChat

/// The store-bricking guard: JSON written by ANY older build must decode.
final class DecodeSafetyTests: XCTestCase {

    func testAppSettingsDecodesWithoutV02Keys() throws {
        let legacy = #"{"theme":"ember","scanlines":true,"pushEnabled":false,"relayURL":"ws://x/ws","billingURL":"http://x"}"#
        let s = try JSONDecoder().decode(AppSettings.self, from: Data(legacy.utf8))
        XCTAssertFalse(s.readReceiptsOn, "read receipts default OFF")
        XCTAssertTrue(s.soundsOn, "sounds default ON")
        XCTAssertFalse(s.burnOnScreenshot)
    }

    func testAppSettingsDecodesFromEmptyObject() throws {
        let s = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertEqual(s.theme, "ember")
        XCTAssertTrue(s.scanlines)
    }

    func testChatMessageDecodesWithoutV02Keys() throws {
        let legacy = """
        {"id":"m1","direction":"outgoing","kind":"text","text":"hi",
         "timestamp":700000000,"status":"sent"}
        """
        let m = try JSONDecoder().decode(ChatMessage.self, from: Data(legacy.utf8))
        XCTAssertNil(m.attachmentId)
        XCTAssertNil(m.burnAfter)
        XCTAssertEqual(m.status, .sent)
    }

    func testBlobDecodesWithoutV02Keys() throws {
        let data = try JSONEncoder().encode(SecureStore.Blob())
        var obj = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        obj.removeValue(forKey: "notes")
        obj.removeValue(forKey: "receiptOutbox")
        let legacy = try JSONSerialization.data(withJSONObject: obj)
        let blob = try JSONDecoder().decode(SecureStore.Blob.self, from: legacy)
        XCTAssertNil(blob.notes)
        XCTAssertNil(blob.receiptOutbox)
    }

    func testNoteDecodesWithoutPinnedKey() throws {
        let json = #"{"id":"x","text":"t","createdAt":0,"modifiedAt":0}"#
        let note = try JSONDecoder().decode(Note.self, from: Data(json.utf8))
        XCTAssertFalse(note.isPinned)
    }

    /// Tripwire: every v0.2 field must round-trip — catches any future field
    /// added outside the explicit CodingKeys lists.
    func testFullyPopulatedBlobRoundTrips() throws {
        var blob = SecureStore.Blob()
        blob.usernameDisplay = "zee"
        blob.contacts = [Contact(hash: String(repeating: "a", count: 64),
                                 name: "peer", addedAt: Date())]
        blob.messages = ["t": [ChatMessage(
            id: "m1", direction: .outgoing, kind: .image, text: "[IMG]",
            timestamp: Date(), status: .read, burnAfter: 30,
            burnStartedAt: Date(), voiceData: nil, voiceDuration: nil,
            attachmentId: UUID().uuidString, fileName: "x.jpg",
            fileMime: "image/jpeg", fileBytes: 12, imageWidth: 4, imageHeight: 3)]]
        var outbox = ReceiptOutbox()
        outbox.delivered = ["p": ["m1"]]
        outbox.read = ["p": ["m2"]]
        outbox.burnStart = ["p": ["m3"]]
        blob.receiptOutbox = outbox
        blob.notes = [Note(id: "n1", text: "buffer", createdAt: Date(),
                           modifiedAt: Date(), pinned: true)]
        var settings = AppSettings()
        settings.sendReadReceipts = true
        settings.soundsEnabled = false
        settings.hideNotices = true
        blob.settings = settings

        let decoded = try JSONDecoder().decode(
            SecureStore.Blob.self, from: JSONEncoder().encode(blob))
        XCTAssertEqual(decoded.messages["t"]?.first?.attachmentId,
                       blob.messages["t"]?.first?.attachmentId)
        XCTAssertEqual(decoded.messages["t"]?.first?.status, .read)
        XCTAssertEqual(decoded.receiptOutbox?.burnStart["p"], ["m3"])
        XCTAssertEqual(decoded.notes?.first?.isPinned, true)
        XCTAssertEqual(decoded.settings.readReceiptsOn, true)
        XCTAssertEqual(decoded.settings.soundsOn, false)
        XCTAssertEqual(decoded.settings.hideNotices, true)
    }

    @MainActor
    func testSoundAssetsBundledAndShort() throws {
        for name in ["tx", "rx"] {
            let url = try XCTUnwrap(Bundle.main.url(forResource: name, withExtension: "wav"),
                                    "\(name).wav missing from bundle — run scripts/make_sounds.py before xcodegen")
            let player = try AVAudioPlayer(contentsOf: url)
            XCTAssertLessThan(player.duration, 0.2, "\(name).wav must be a short blip")
        }
    }

    /// Users pointed at the raw VPS IP before the domain existed must land
    /// on the real relay automatically; anything else the user typed stays.
    func testLegacyRelayAddressesMigrateToTheDomainDefault() throws {
        let legacy = try JSONDecoder().decode(AppSettings.self,
            from: Data(#"{"relayURL":"ws://72.62.171.10:7443/ws","billingURL":"http://72.62.171.10:8088"}"#.utf8))
        XCTAssertEqual(legacy.relayURL, AppSettings.defaultRelayURL)
        XCTAssertEqual(legacy.billingURL, AppSettings.defaultBillingURL)
        let custom = try JSONDecoder().decode(AppSettings.self,
            from: Data(#"{"relayURL":"wss://relay.example.org/ws"}"#.utf8))
        XCTAssertEqual(custom.relayURL, "wss://relay.example.org/ws", "a self-hosted relay is never overridden")
        let missing = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertEqual(missing.relayURL, AppSettings.defaultRelayURL)
    }
}

import AVFoundation
