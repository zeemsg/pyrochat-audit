import XCTest
import AVFoundation
import Combine
@testable import PyroChat

/// The voice changer end to end on real AAC (docs/VOICE-CHANGER.md): a note in the recorder's
/// own format goes in, and what comes out is decoded again and measured. Android's twin is
/// `VoiceChangerTest`, on a device.
final class VoiceChangerTests: XCTestCase {
    private let rate = VoiceDsp.sampleRate

    /// Something voice-like: a fundamental with eight harmonics, breathing in loudness.
    private func voice(_ f0: Double, seconds: Double = 1.0) -> [Float] {
        (0..<Int(seconds * Double(rate))).map { i in
            let t = Double(i) / Double(rate)
            var v = 0.0
            for k in 1...8 { v += sin(2.0 * Double.pi * Double(k) * f0 * t) / Double(k) }
            return Float(0.25 * v * (0.7 + 0.3 * sin(2.0 * Double.pi * 2.0 * t)))
        }
    }

    private func scratch() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("voicetest-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return dir
    }

    /// `samples` as the recorder would have saved them.
    private func note(_ samples: [Float]) throws -> Data {
        let url = try scratch().appendingPathComponent("spoken.m4a")
        try VoiceChanger.encode(samples, to: url)
        return try Data(contentsOf: url)
    }

    private func decode(_ m4a: Data) throws -> [Float] {
        let url = try scratch().appendingPathComponent("heard.m4a")
        try m4a.write(to: url)
        return try VoiceChanger.decode(url)
    }

    /// The period, in samples, at which the middle of `x` repeats best.
    private func period(_ x: [Float], from: Int, to: Int) -> Int {
        let a = x.count / 4, n = x.count / 2
        var best = from
        var bestScore = -Double.infinity
        x.withUnsafeBufferPointer { p in
            for lag in from...to {
                var dot = 0.0
                for i in a..<(a + n - lag) { dot += Double(p[i]) * Double(p[i + lag]) }
                let score = dot / Double(n - lag)
                if score > bestScore {
                    best = lag
                    bestScore = score
                }
            }
        }
        return best
    }

    /// The pitch of `x` in Hz, looked for within a fifth either side of `near`: a made-up voice
    /// repeats just as well every two periods, and that is not the pitch.
    private func heard(_ x: [Float], near: Double) -> Double {
        Double(rate) / Double(period(x, from: Int(Double(rate) / near * 0.8), to: Int(Double(rate) / near * 1.2) + 1))
    }

    private func leftovers() -> Set<String> {
        let entries = (try? FileManager.default.contentsOfDirectory(atPath: FileManager.default.temporaryDirectory.path)) ?? []
        return Set(entries.filter { $0.hasPrefix("vn-") })
    }

    /// The note is what the recorder makes: AAC, 24 kHz, mono, and the player and the waveform take it.
    private func assertIsAVoiceNote(_ m4a: Data, seconds: Double, file: StaticString = #filePath, line: UInt = #line) throws {
        let url = try scratch().appendingPathComponent("check.m4a")
        try m4a.write(to: url)
        let audio = try AVAudioFile(forReading: url)
        XCTAssertEqual(audio.fileFormat.sampleRate, 24_000, file: file, line: line)
        XCTAssertEqual(audio.fileFormat.channelCount, 1, file: file, line: line)
        XCTAssertEqual(audio.fileFormat.streamDescription.pointee.mFormatID, kAudioFormatMPEG4AAC, file: file, line: line)
        XCTAssertEqual(Double(audio.length) / 24_000, seconds, accuracy: seconds * 0.05, "as long as it was spoken", file: file, line: line)
        let player = try AVAudioPlayer(data: m4a)
        XCTAssertEqual(player.duration, seconds, accuracy: seconds * 0.1, file: file, line: line)
        let bars = Waveform.bars(for: UUID().uuidString, data: m4a)
        XCTAssertEqual(bars.count, Waveform.barCount, file: file, line: line)
        XCTAssertTrue(bars.contains { $0 != 0.3 }, "a real waveform, not the placeholder", file: file, line: line)
    }

    func testADeepAndAHighNoteAreTheSameWordsInAnotherVoice() async throws {
        let spoken = try note(voice(150))
        try assertIsAVoiceNote(spoken, seconds: 1)
        for (effect, factor) in [(VoiceEffect.deep, 0.77), (.high, 1.35)] {
            let changed = try await VoiceChanger.change(spoken, to: effect, pick: 0.5)
            XCTAssertNotEqual(changed, spoken)
            try assertIsAVoiceNote(changed, seconds: 1)
            XCTAssertEqual(heard(try decode(changed), near: 150 * factor), 150 * factor, accuracy: 150 * factor * 0.04, "\(effect): 150 Hz times \(factor)")
        }
    }

    func testARobotNoteHasTheRobotsPitchWhoeverSpoke() async throws {
        for f0 in [120.0, 210.0] {
            let changed = try await VoiceChanger.change(try note(voice(f0)), to: .robot)
            try assertIsAVoiceNote(changed, seconds: 1)
            XCTAssertEqual(period(try decode(changed), from: 200, to: 300), 256, "a \(f0) Hz voice")
        }
    }

    func testOffHandsBackTheVeryNote() async throws {
        let spoken = try note(voice(150, seconds: 0.6))
        let same = try await VoiceChanger.change(spoken, to: .off)
        XCTAssertEqual(same, spoken)
    }

    func testEveryNoteIsShiftedByItsOwnAmount() async throws {
        let spoken = try note(voice(150))
        let low = try await VoiceChanger.change(spoken, to: .deep, pick: 0)
        let lower = try await VoiceChanger.change(spoken, to: .deep, pick: 1)
        let a = heard(try decode(low), near: 150 * 0.74)
        let b = heard(try decode(lower), near: 150 * 0.80)
        XCTAssertEqual(a, 150 * 0.74, accuracy: 150 * 0.74 * 0.04)
        XCTAssertEqual(b, 150 * 0.80, accuracy: 150 * 0.80 * 0.04)
        XCTAssertGreaterThan(b - a, 4, "two notes in the same voice are not shifted alike")
    }

    func testWhatCannotBeChangedIsRefusedAndNeverHandedBackAsItWas() async {
        let before = leftovers()
        for bad in [Data(), Data((0..<4096).map { UInt8(truncatingIfNeeded: $0 &* 13) })] {
            for effect in [VoiceEffect.deep, .high, .robot] {
                do {
                    let out = try await VoiceChanger.change(bad, to: effect)
                    XCTFail("\(effect): handed back \(out.count) bytes for a note that could not be changed")
                } catch {
                    XCTAssertTrue(error is VoiceChanger.Failed, "\(error)")
                }
            }
        }
        XCTAssertEqual(leftovers(), before, "and nothing of it stays in tmp")
    }

    func testNothingOfTheNoteStaysInTmp() async throws {
        let before = leftovers()
        let spoken = try note(voice(150, seconds: 0.8))
        for effect in [VoiceEffect.deep, .high, .robot] { _ = try await VoiceChanger.change(spoken, to: effect) }
        XCTAssertEqual(leftovers(), before)
    }

    // MARK: through the one call that sends a voice note (against the dev relay on :7444)

    @MainActor private func service() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pyro-svc-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "voice-test-pass-123", at: dir.appendingPathComponent("store"))
        return (ChatService(store: store), "vc" + UUID().uuidString.prefix(8).lowercased())
    }

    @MainActor private func waitUntil(_ timeout: TimeInterval = 10, _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return cond()
    }

    /// Two people who can write to each other, or a skip when there is no relay to meet on.
    @MainActor private func pair() async throws -> (alice: ChatService, bob: ChatService) {
        let (alice, aliceName) = service(), (bob, bobName) = service()
        do {
            try await alice.register(username: aliceName)
            try await bob.register(username: bobName)
        } catch {
            alice.goOffline(); bob.goOffline()
            throw XCTSkip("no dev relay on 127.0.0.1:7444")
        }
        try await alice.addContact(username: bobName)
        // A first text, delivered, before any note. With sealed sender the first message to
        // someone new is text only and attachments work once it shows DELIVERED
        // (docs/SEALED-SENDER.md, decision D1); without sealed sender it changes nothing here.
        await alice.sendText("hello", to: alice.contacts.first!)
        let met = await waitUntil(20) {
            alice.messages[bob.myHash]?.contains { $0.direction == .outgoing && ($0.status == .delivered || $0.status == .read) } == true
        }
        XCTAssertTrue(met, "the first text was delivered")
        return (alice, bob)
    }

    @MainActor func testANoteGoesOutInTheChosenVoiceAndTheSenderKeepsWhatWasSent() async throws {
        let (alice, bob) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        alice.updateSettings { $0.voiceChanger = VoiceEffect.deep.rawValue }
        let spoken = try note(voice(150))
        let sent = await alice.sendVoice(spoken, duration: 1.0, to: alice.contacts.first!)
        XCTAssertTrue(sent)
        let arrived = await waitUntil { bob.messages[alice.myHash]?.contains { $0.kind == .voice } == true }
        XCTAssertTrue(arrived)
        let received = try XCTUnwrap(bob.messages[alice.myHash]?.first { $0.kind == .voice }.flatMap { bob.voiceData(for: $0) })
        XCTAssertNotEqual(received, spoken, "what crossed the wire is not the voice as it was spoken")
        let pitch = heard(try decode(received), near: 150 * 0.77)
        XCTAssertTrue((150 * 0.74 * 0.97...150 * 0.80 * 1.03).contains(pitch), "a deep voice: \(pitch) Hz for 150 Hz spoken")
        // the sender's own thread holds what was sent, and nothing else
        let own = try XCTUnwrap(alice.messages[bob.myHash]?.first { $0.kind == .voice })
        XCTAssertEqual(alice.voiceData(for: own), received)
        XCTAssertEqual(own.voiceDuration, 1.0)
    }

    @MainActor func testWithNoVoiceChosenTheNoteGoesOutUntouched() async throws {
        let (alice, bob) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        XCTAssertEqual(alice.store.blob.settings.voice, .off)
        let spoken = try note(voice(150, seconds: 0.6))
        let sent = await alice.sendVoice(spoken, duration: 0.6, to: alice.contacts.first!)
        XCTAssertTrue(sent)
        let arrived = await waitUntil { bob.messages[alice.myHash]?.contains { $0.kind == .voice && bob.voiceData(for: $0) == spoken } == true }
        XCTAssertTrue(arrived, "byte for byte, as before there was a voice changer")
    }

    @MainActor func testANoteWhoseVoiceCannotBeChangedIsNotSentAndLeavesNoRow() async throws {
        let (alice, bob) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        alice.updateSettings { $0.voiceChanger = VoiceEffect.robot.rawValue }
        let unreadable = Data((0..<4096).map { UInt8(truncatingIfNeeded: $0 &* 13) })
        let sent = await alice.sendVoice(unreadable, duration: 2.0, to: alice.contacts.first!)
        XCTAssertFalse(sent)
        XCTAssertEqual(alice.lastError, VoiceChanger.refusal)
        XCTAssertNil(alice.messages[bob.myHash]?.first { $0.kind == .voice }, "no row: nothing claims to have been sent")
        try await Task.sleep(for: .seconds(1))
        XCTAssertNil(bob.messages[alice.myHash]?.first { $0.kind == .voice }, "and nothing arrived")
    }

    /// A note that was sent has no row while its voice is being changed, and a long one takes
    /// a moment. The service names its thread for that moment, so the composer can say so,
    /// and not a moment longer; a note that goes out as it was spoken is never named.
    @MainActor func testAThreadIsNamedWhileANoteForItIsBeingChangedAndNoLonger() async throws {
        let (alice, bob) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        let thread = bob.myHash
        let contact = alice.contacts.first!
        let spoken = try note(voice(150))
        var seen: [[String: Int]] = []
        let watching = alice.$voiceBeingChanged.sink { seen.append($0) }
        defer { watching.cancel() }

        alice.updateSettings { $0.voiceChanger = VoiceEffect.high.rawValue }
        let sent = await alice.sendVoice(spoken, duration: 1.0, to: contact)
        XCTAssertTrue(sent)
        XCTAssertEqual(seen, [[:], [thread: 1], [:]], "named while its voice was being changed, and not after")
        XCTAssertEqual(alice.messages[thread]?.filter { $0.kind == .voice }.count, 1)

        // two at once are two, and both are done in the end
        seen.removeAll()
        async let first = alice.sendVoice(spoken, duration: 1.0, to: contact)
        async let second = alice.sendVoice(spoken, duration: 1.0, to: contact)
        let both = await [first, second]
        XCTAssertEqual(both, [true, true])
        XCTAssertEqual(seen, [[thread: 1], [thread: 2], [thread: 1], [:]])

        // refused: named meanwhile, and not afterwards
        seen.removeAll()
        alice.updateSettings { $0.voiceChanger = VoiceEffect.robot.rawValue }
        let refused = await alice.sendVoice(Data([1, 2, 3]), duration: 1.0, to: contact)
        XCTAssertFalse(refused)
        XCTAssertEqual(seen, [[thread: 1], [:]])

        // as spoken: nothing is being changed, so nothing is named
        seen.removeAll()
        alice.updateSettings { $0.voiceChanger = nil }
        let plain = await alice.sendVoice(spoken, duration: 1.0, to: contact)
        XCTAssertTrue(plain)
        XCTAssertEqual(seen, [], "a note that goes out as it was spoken is not named")
    }

    @MainActor func testAGroupNoteGoesOutInTheChosenVoiceToo() async throws {
        let (alice, bob) = try await pair()
        defer { alice.goOffline(); bob.goOffline() }
        let group = try await alice.createGroup(name: "voices", members: [alice.contacts.first!], policy: .alert)
        let joined = await waitUntil { bob.group(id: group.id) != nil }
        XCTAssertTrue(joined)
        alice.updateSettings { $0.voiceChanger = VoiceEffect.high.rawValue }
        let spoken = try note(voice(150))
        var seen: [[String: Int]] = []
        let watching = alice.$voiceBeingChanged.sink { seen.append($0) }
        defer { watching.cancel() }
        let sent = await alice.sendVoice(spoken, duration: 1.0, toGroup: group.id)
        XCTAssertTrue(sent)
        XCTAssertEqual(seen, [[:], [group.id: 1], [:]], "the group's thread is the one named meanwhile")
        let arrived = await waitUntil { bob.messages[group.id]?.contains { $0.kind == .voice } == true }
        XCTAssertTrue(arrived)
        let received = try XCTUnwrap(bob.messages[group.id]?.first { $0.kind == .voice }.flatMap { bob.voiceData(for: $0) })
        XCTAssertNotEqual(received, spoken)
        let pitch = heard(try decode(received), near: 150 * 1.35)
        XCTAssertTrue((150 * 1.30 * 0.97...150 * 1.40 * 1.03).contains(pitch), "a high voice: \(pitch) Hz for 150 Hz spoken")
        // and a note that cannot be changed is refused there as well
        let refused = await alice.sendVoice(Data([1, 2, 3]), duration: 1.0, toGroup: group.id)
        XCTAssertFalse(refused)
        XCTAssertEqual(alice.messages[group.id]?.filter { $0.kind == .voice }.count, 1)
    }

    // MARK: the setting

    func testTheSettingIsWrittenOnlyOnceAVoiceIsChosenAndAnUnknownWordIsNeverYourOwnVoice() throws {
        var settings = AppSettings()
        XCTAssertEqual(settings.voice, .off)
        var json = String(decoding: try JSONEncoder().encode(settings), as: UTF8.self)
        XCTAssertFalse(json.contains("voiceChanger"), "a store that never chose a voice is written as before")
        settings.voiceChanger = VoiceEffect.deep.rawValue
        json = String(decoding: try JSONEncoder().encode(settings), as: UTF8.self)
        XCTAssertTrue(json.contains("\"voiceChanger\":\"deep\""))
        XCTAssertEqual(try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8)).voice, .deep)
        // a word from a newer build is not a voice this one can make: the note still goes out
        // disguised, and the word is kept
        let newer = json.replacingOccurrences(of: "\"deep\"", with: "\"helium\"")
        let read = try JSONDecoder().decode(AppSettings.self, from: Data(newer.utf8))
        XCTAssertEqual(read.voice, .deep)
        XCTAssertEqual(read.voiceChanger, "helium")
        // and a store from before this existed
        let older = String(decoding: try JSONEncoder().encode(AppSettings()), as: UTF8.self)
        XCTAssertEqual(try JSONDecoder().decode(AppSettings.self, from: Data(older.utf8)).voice, .off)
    }
}
