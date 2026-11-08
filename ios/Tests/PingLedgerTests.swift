import XCTest
@testable import PyroChat

/// The count behind "the relay has all of it" (docs/SEALED-SENDER.md §5.6): an upload is
/// complete when the pong to a ping written after its last chunk has been routed. The first
/// half needs no relay; the second talks to the dev relay on 127.0.0.1:7444 and skips
/// without it.
///
/// The same cases are in Android's `PingLedgerTest`.
final class PingLedgerTests: XCTestCase {

    private actor Flag {
        private(set) var isSet = false
        func set() { isSet = true }
    }

    /// How a wait ended: nil while it is still waiting. A wait that never ends must fail a
    /// test, not hang it.
    private actor Outcome {
        private(set) var error: Error?
        private(set) var ended = false
        func end(_ error: Error?) { self.error = error; ended = true }
    }

    /// Starts `wait` on its own and hands back where its ending will be noted.
    private func begin(_ wait: @escaping @Sendable () async throws -> Void) -> Outcome {
        let outcome = Outcome()
        Task {
            do { try await wait(); await outcome.end(nil) } catch { await outcome.end(error) }
        }
        return outcome
    }

    /// Gives a wait two seconds to end, one way or the other, and says how it did.
    private func ending(_ outcome: Outcome, file: StaticString = #filePath, line: UInt = #line) async -> Error? {
        let deadline = Date().addingTimeInterval(2)
        while Date() < deadline {
            if await outcome.ended { break }
            try? await Task.sleep(for: .milliseconds(20))
        }
        let ended = await outcome.ended
        XCTAssertTrue(ended, "still waiting after two seconds", file: file, line: line)
        return await outcome.error
    }

    /// Pongs as they arrive, held until the test routes them one at a time, as a consumer does.
    private actor Inbox {
        private var pongs = 0
        func arrived() { pongs += 1 }
        func take(within seconds: Double) async -> Bool {
            let deadline = Date().addingTimeInterval(seconds)
            while pongs == 0, Date() < deadline { try? await Task.sleep(for: .milliseconds(20)) }
            guard pongs > 0 else { return false }
            pongs -= 1
            return true
        }
    }

    func testAPongAnswersThePingWithItsOwnNumberNotTheOneWrittenAfterIt() async throws {
        let ledger = PingLedger()
        _ = ledger.written()                // a keepalive ping, written before the last chunk and not answered yet
        let mine = ledger.written()         // the ping written after the last chunk
        let answered = Flag()
        let waiting = Task {
            try await ledger.wait(for: mine, timeout: .seconds(5))
            await answered.set()
        }
        try await Task.sleep(for: .milliseconds(50))
        ledger.routed()                     // the keepalive's pong
        try await Task.sleep(for: .milliseconds(150))
        let early = await answered.isSet
        XCTAssertFalse(early, "the answer to an earlier ping says nothing about what was written after it")
        ledger.routed()
        try await waiting.value
        let done = await answered.isSet
        XCTAssertTrue(done)
    }

    func testAPongRoutedBeforeAnyoneWaitsForItStillCountsAndSoDoesALaterOne() async throws {
        let ledger = PingLedger()
        let first = ledger.written()
        ledger.routed()
        try await ledger.wait(for: first, timeout: .milliseconds(200))     // it was answered before the wait began
        let second = ledger.written()
        _ = ledger.written()
        ledger.routed()
        ledger.routed()                     // two more: the second one is past what was asked for
        try await ledger.wait(for: second, timeout: .milliseconds(200))
    }

    func testWhenTheSocketEndsWhoeverWaitsFailsAndSoDoesWhoeverAsksAfterwards() async throws {
        let ledger = PingLedger()
        let mine = ledger.written()
        let waiting = begin { try await ledger.wait(for: mine, timeout: .seconds(30)) }
        try await Task.sleep(for: .milliseconds(50))
        ledger.close()
        let first = await ending(waiting)
        XCTAssertNotNil(first, "the socket ended: nothing was answered")
        XCTAssertFalse(first is PingLedger.NoAnswer, "it ended, which is not the same as staying silent")
        let later = await ending(begin { try await ledger.wait(for: ledger.written(), timeout: .seconds(30)) })
        XCTAssertNotNil(later, "nothing can be answered on a socket that is gone")
    }

    func testAnAnswerThatWasRoutedIsKeptEvenAfterTheSocketEnds() async throws {
        let ledger = PingLedger()
        let mine = ledger.written()
        ledger.routed()
        ledger.close()
        try await ledger.wait(for: mine, timeout: .milliseconds(200))       // does not throw: the relay had said so
    }

    func testNoAnswerInTimeIsSaidOnceAndALatePongChangesNothing() async throws {
        let ledger = PingLedger()
        let mine = ledger.written()
        let began = Date()
        do {
            try await ledger.wait(for: mine, timeout: .milliseconds(300))
            XCTFail("nobody answered")
        } catch {
            XCTAssertTrue(error is PingLedger.NoAnswer, "\(error)")
        }
        let took = Date().timeIntervalSince(began)
        XCTAssertTrue((0.25...3).contains(took), "it waited the time it was given (\(took) s)")
        ledger.routed()                     // too late for that wait, and not a crash
        try await ledger.wait(for: mine, timeout: .milliseconds(200))       // but true from now on
    }

    // MARK: against the real relay

    private func connected() async throws -> (RelayClient, Inbox, Task<Void, Never>) {
        let client = RelayClient(url: URL(string: "ws://127.0.0.1:7444/ws")!)
        let inbox = Inbox()
        let reader = Task {
            for await event in client.events {
                if case .frame(let f) = event, f.t == "pong" { await inbox.arrived() }
            }
        }
        client.connect()
        // a first ping, to know there is a relay at all
        try? await client.send(Wire.Out(t: "ping"))
        guard await inbox.take(within: 3) else {
            client.disconnect()
            reader.cancel()
            throw XCTSkip("no dev relay on 127.0.0.1:7444")
        }
        client.pongRouted()
        return (client, inbox, reader)
    }

    func testTheRelaysAnswerComesBackAndOnlyTheAnswerToThisPingCounts() async throws {
        let (client, inbox, reader) = try await connected()
        defer { client.disconnect(); reader.cancel() }
        // a keepalive ping is on its way when the upload ends
        try await client.send(Wire.Out(t: "ping"))
        let answered = Flag()
        let barrier = Task {
            try await client.barrier(timeout: .seconds(5))
            await answered.set()
        }
        let first = await inbox.take(within: 3)
        XCTAssertTrue(first)
        client.pongRouted()
        try await Task.sleep(for: .milliseconds(200))
        let early = await answered.isSet
        XCTAssertFalse(early, "the keepalive's pong is not the answer")
        let second = await inbox.take(within: 3)
        XCTAssertTrue(second)
        client.pongRouted()
        try await barrier.value
    }

    func testNoAnswerInTimeIsSaidAndASocketThatEndsSaysSoAtOnce() async throws {
        let (silent, _, reader) = try await connected()
        // nobody routes its pong: for the one who waits, the relay never answered
        do {
            try await silent.barrier(timeout: .milliseconds(400))
            XCTFail("nobody routed the answer")
        } catch {
            XCTAssertTrue(error is PingLedger.NoAnswer, "\(error)")
        }
        silent.disconnect()
        reader.cancel()

        let (ended, _, reader2) = try await connected()
        defer { reader2.cancel() }
        let barrier = begin { try await ended.barrier(timeout: .seconds(30)) }
        try await Task.sleep(for: .milliseconds(100))
        ended.disconnect()
        let cut = await ending(barrier)         // said at once, not when the thirty seconds ran out
        XCTAssertNotNil(cut, "the socket ended")
        XCTAssertFalse(cut is PingLedger.NoAnswer, "\(String(describing: cut))")
        let after = await ending(begin { try await ended.barrier(timeout: .seconds(30)) })
        XCTAssertNotNil(after, "nothing can be asked on a socket that is gone")
    }
}
