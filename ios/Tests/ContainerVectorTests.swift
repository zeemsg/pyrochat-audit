import XCTest
import CryptoKit
@testable import PyroChat

/// docs/DECOY-ACCOUNT.md §7, "Container fixtures: a test vector, not a 12 MiB
/// file". A container is 12,582,925 bytes of mostly incompressible padding, so
/// nothing is checked in but `android/fixtures/container-vector.json`: the
/// inputs, pinned, and one SHA-256 to compare.
///
/// Every byte of the container is determined by the inputs below, so iOS and
/// Android must agree on `containerSha256` exactly. Two things make that
/// possible and both matter:
///
///  - The byte source is NOT a platform RNG. `SecRandomCopyBytes` and
///    `java.util.Random` are different streams; the counter construction in
///    `FixtureStream` is the same three lines in either language.
///  - The payload is sealed VERBATIM, never re-encoded from a `Blob`. That
///    sidesteps JSON dictionary key ordering, which is exactly why §7 says
///    byte-identity is unreachable for ordinary saves.
final class ContainerVectorTests: XCTestCase {

    // MARK: the vector (android/fixtures/container-vector.json)

    static let seedPhrase = "pyrochat-decoy-fixture-v1"
    static let password = "correct horse battery staple"
    static let saltHex = "00112233445566778899aabbccddeeff102132435465768798a9bacbdcedfe0f"
    static let occupiedSlot = 2
    /// Sealed byte for byte. A valid `Blob`, but the bytes are the contract —
    /// re-encoding this through `JSONEncoder` would NOT reproduce them.
    static let payloadJSON = #"{"usernameDisplay":"fixture_vector","usernameHash":"554182ee8e2a0409bc566851aaf13419f1b31184effe15c1dc034bfdedd12fff","registrationId":4242,"nextPreKeyId":7}"#

    static let seedHex = "60123166e35f21c80ff129bba695c50a7f0f6c0dc533dc424f52a89fb46624d4"
    static let payloadSha256 = "3b3b8742384e5f8b374195fcef2139697ef66299cea7938a246ff05191ed5a9b"
    static let keySha256 = "0b88c917940608474200c92e34f6a88aff53f83080868ec239b369ffde338b42"
    static let headerHex = "5059524f564c54310300400000"
    static let containerBytes = 12_582_925
    static let containerSha256 = "3a5ebe3dd0b39b00208ad8233ab72cfe815c988899e29ed0f1404487a7ddd0a1"

    /// The test-only deterministic byte source §7 pins. ONE stream for the
    /// whole container: the counter runs on across every request, it does not
    /// restart per call.
    ///
    ///     stream(seed, n):
    ///         out, ctr = "", 0
    ///         while len(out) < n:
    ///             out += SHA-256( seed(32 bytes) ‖ ctr(4 bytes, big-endian) )
    ///             ctr += 1
    ///         return out[:n]
    /// One 32-byte block at a time, with a read index — a request here can be
    /// 2 MiB, and accumulating that in a `Data` that is also being sliced from
    /// the front allocates enough to take the test host down mid-suite.
    final class FixtureStream {
        private let seed: Data
        private var counter: UInt32 = 0
        private var block: [UInt8] = []
        private var offset = 0
        init(seed: Data) { self.seed = seed }
        func next(_ n: Int) -> Data {
            var out = [UInt8]()
            out.reserveCapacity(n)
            while out.count < n {
                if offset == block.count {
                    block = Array(SHA256.hash(data: seed + SlotVault.be32(counter)))
                    counter &+= 1
                    offset = 0
                }
                let take = min(n - out.count, block.count - offset)
                out.append(contentsOf: block[offset ..< offset + take])
                offset += take
            }
            return Data(out)
        }
    }

    override func tearDown() {
        SlotVault.randomBytes = SlotVault.systemRandomBytes
        super.tearDown()
    }

    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    private func sha(_ d: Data) -> String { hex(Data(SHA256.hash(data: d))) }
    private func hexData(_ s: String) -> Data {
        var out = Data(); var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            out.append(UInt8(s[i..<j], radix: 16)!)
            i = j
        }
        return out
    }

    // MARK: - the contract

    func testContainerMatchesTheCrossPlatformVector() throws {
        let seed = Data(SHA256.hash(data: Data(Self.seedPhrase.utf8)))
        XCTAssertEqual(hex(seed), Self.seedHex, "the seed phrase must hash to the pinned seed")

        let payload = Data(Self.payloadJSON.utf8)
        XCTAssertEqual(payload.count, 157)
        XCTAssertEqual(sha(payload), Self.payloadSha256)
        // it really is a Blob — a vector over bytes the app cannot read would
        // prove the layout and nothing else
        let blob = try JSONDecoder().decode(SecureStore.Blob.self, from: payload)
        XCTAssertEqual(blob.usernameDisplay, "fixture_vector")
        XCTAssertEqual(blob.registrationId, 4242)
        XCTAssertEqual(blob.nextPreKeyId, 7)

        let key = SecureStore.deriveKey(password: Self.password, salt: hexData(Self.saltHex))
        XCTAssertEqual(sha(key.withUnsafeBytes { Data($0) }), Self.keySha256,
                       "PBKDF2-HMAC-SHA256, 600k, over the pinned salt")

        // ONE stream for the whole build. The draw order is fixed by the
        // format, not by this code: the sealed half's padding, then its nonce,
        // then every remaining half in increasing FILE OFFSET.
        let stream = FixtureStream(seed: seed)
        // `randomBytes` is a global seam, and another test class's `SecureStore`
        // may still have a debounced save in flight on its own queue — that
        // save would seal from THIS stream, on another thread, and both corrupt
        // it and consume bytes the vector depends on. Serve the stream only to
        // the thread building the vector; anything else gets the real CSPRNG.
        let vectorThread = Thread.current
        SlotVault.randomBytes = { n in
            Thread.current === vectorThread ? stream.next(n) : SlotVault.systemRandomBytes(n)
        }

        let sealedHalf = try SlotVault.seal(payload: payload, key: key, seq: SlotVault.firstSeq)
        let slots = (0..<SlotVault.slotCount).map { i -> Data in
            i == Self.occupiedSlot ? sealedHalf + SlotVault.halfNoise() : SlotVault.noise()
        }
        let file = try SlotVault.serialize(SlotVault.Container(slots: slots))

        XCTAssertEqual(hex(file.prefix(13)), Self.headerHex)
        XCTAssertEqual(file.count, Self.containerBytes)
        XCTAssertEqual(file.count, SlotVault.fileBytes)
        XCTAssertEqual(sha(file), Self.containerSha256,
                       "iOS and Android must produce this byte for byte")

        // …and it opens, at the slot the vector pins, on half 0 at seq 1
        let live = try XCTUnwrap(SlotVault.open(try SlotVault.parse(file), key: key))
        XCTAssertEqual(live.slot, Self.occupiedSlot)
        XCTAssertEqual(live.half, 0)
        XCTAssertEqual(live.seq, 1)
        XCTAssertEqual(live.payload, payload, "verbatim — not a re-encoded Blob")
    }

    /// The stream is the cross-platform half of the contract, so it gets its
    /// own pinned values: a mismatch here localises the failure instead of
    /// leaving a bad container hash to explain.
    func testTheFixtureStreamIsThePinnedConstruction() {
        let seed = Data(SHA256.hash(data: Data(Self.seedPhrase.utf8)))
        // block 0 is SHA-256(seed ‖ 00000000), block 1 is SHA-256(seed ‖ 00000001)
        let block0 = Data(SHA256.hash(data: seed + Data([0, 0, 0, 0])))
        let block1 = Data(SHA256.hash(data: seed + Data([0, 0, 0, 1])))
        XCTAssertEqual(FixtureStream(seed: seed).next(64), block0 + block1)

        // the counter runs on across requests: it does not restart per call
        let split = FixtureStream(seed: seed)
        let a = split.next(20), b = split.next(44)
        XCTAssertEqual(a + b, block0 + block1)

        // a request that is not a multiple of 32 keeps the remainder
        let ragged = FixtureStream(seed: seed)
        XCTAssertEqual(ragged.next(1), block0.prefix(1))
        XCTAssertEqual(ragged.next(31), block0.dropFirst())
        XCTAssertEqual(ragged.next(32), block1)
    }

    /// The checked-in file is `android/fixtures/container-vector.json`. It is
    /// the cross-platform contract and it carries exactly the constants at the
    /// top of this class; this class is what enforces them on the iOS side.
    /// If any of them changes, that file changes with it, in the same commit.
    func testTheVectorFileConstantsAreSelfConsistent() {
        XCTAssertEqual(SlotVault.slotBytes, 4_194_304)
        XCTAssertEqual(SlotVault.halfBytes, 2_097_152)
        XCTAssertEqual(SlotVault.halfCapacity, 2_097_124)
        XCTAssertEqual(SlotVault.maxPayloadBytes, 2_097_112)
        XCTAssertEqual(SlotVault.fileBytes, Self.containerBytes)
        XCTAssertEqual((0..<3).flatMap { slot in (0..<2).map { SlotVault.offset(ofSlot: slot, half: $0) } },
                       [13, 2_097_165, 4_194_317, 6_291_469, 8_388_621, 10_485_773],
                       "layout.halfOffsets in the vector file")
        // byteSource.drawOrder step 1
        XCTAssertEqual(SlotVault.halfCapacity - SlotVault.seqBytes - SlotVault.lengthPrefixBytes
                        - Data(Self.payloadJSON.utf8).count, 2_096_955)
    }
}
