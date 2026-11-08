import XCTest
import ImageIO
import PDFKit
import AVFoundation
import UniformTypeIdentifiers
@testable import PyroChat

/// docs/SCRUB-AND-WIPE.md §B5 — fixtures per type, the metadata asserted gone,
/// the document asserted still openable, the receipt asserted honest.
final class MetadataScrubTests: XCTestCase {

    // MARK: - §B1 dispatch

    func testFamilyDetection() {
        XCTAssertEqual(MetadataScrubber.family(filename: "a.JPG"), .jpeg)
        XCTAssertEqual(MetadataScrubber.family(filename: "a.jpeg"), .jpeg)
        XCTAssertEqual(MetadataScrubber.family(filename: "a.png"), .png)
        XCTAssertEqual(MetadataScrubber.family(filename: "a.HEIC"), .heic)
        XCTAssertEqual(MetadataScrubber.family(filename: "report.pdf"), .pdf)
        XCTAssertEqual(MetadataScrubber.family(filename: "q.docx"), .office)
        XCTAssertEqual(MetadataScrubber.family(filename: "q.xlsx"), .office)
        XCTAssertEqual(MetadataScrubber.family(filename: "q.pptx"), .office)
        XCTAssertEqual(MetadataScrubber.family(filename: "clip.mp4"), .movie)
        XCTAssertEqual(MetadataScrubber.family(filename: "clip.mov"), .movie)
        XCTAssertEqual(MetadataScrubber.family(filename: "voice.m4a"), .movie)
        // §B1: "anything else — nothing"
        XCTAssertEqual(MetadataScrubber.family(filename: "trip.bin"), .unsupported)
        XCTAssertEqual(MetadataScrubber.family(filename: "notes.txt"), .unsupported)
        XCTAssertEqual(MetadataScrubber.family(filename: "archive.zip"), .unsupported)
        // no extension: the MIME decides
        XCTAssertEqual(MetadataScrubber.family(filename: "blob", mime: "application/pdf"), .pdf)
        XCTAssertEqual(MetadataScrubber.family(filename: "blob", mime: "image/png"), .png)
        XCTAssertEqual(MetadataScrubber.family(filename: "blob", mime: "text/plain"), .unsupported)
    }

    // MARK: - §B3 the line under the compose bar

    func testPreSendLineIsTheSpecString() {
        let three = [ScrubField("author", "J. Example"),
                     ScrubField("gps", "45.5017, -73.5673"),
                     ScrubField("producer", "Microsoft Word 16.0")]
        XCTAssertEqual(MetadataScrubber.preSendLine(removed: three, filename: "report.pdf"),
                       "removed author, gps and 1 more from report.pdf")
        XCTAssertEqual(MetadataScrubber.preSendLine(removed: Array(three.prefix(1)),
                                                    filename: "a.jpg"),
                       "removed author from a.jpg")
        XCTAssertEqual(MetadataScrubber.preSendLine(removed: Array(three.prefix(2)),
                                                    filename: "a.jpg"),
                       "removed author and gps from a.jpg")
        XCTAssertEqual(MetadataScrubber.preSendLine(removed: three + three.prefix(2),
                                                    filename: "a.jpg"),
                       "removed author, gps and 3 more from a.jpg")
        // nothing removed ⇒ nothing said
        XCTAssertNil(MetadataScrubber.preSendLine(removed: [], filename: "a.bin"))
    }

    // MARK: - JPEG

    func testJPEGLosesItsMetadataAndStillDecodes() async {
        let original = ScrubFixtures.jpeg()
        let before = ImageMetadata.fields(in: original)
        XCTAssertEqual(before.first(where: { $0.label == "author" })?.value, ScrubFixtures.author)
        XCTAssertEqual(before.first(where: { $0.label == "gps" })?.value, ScrubFixtures.gpsText)
        XCTAssertEqual(before.first(where: { $0.label == "make" })?.value, ScrubFixtures.make)
        XCTAssertEqual(before.first(where: { $0.label == "model" })?.value, ScrubFixtures.model)
        XCTAssertEqual(before.first(where: { $0.label == "software" })?.value,
                       ScrubFixtures.software)
        XCTAssertNotNil(before.first(where: { $0.label == "serial" }))
        XCTAssertNotNil(before.first(where: { $0.label == "xmp" }), "the packet is in there")

        let (clean, report) = await MetadataScrubber.scrub(original, filename: "holiday.jpg")
        XCTAssertTrue(report.supported)
        XCTAssertEqual(report.removed, before, "the receipt is exactly what was found")

        // gone
        XCTAssertTrue(ImageMetadata.fields(in: clean).isEmpty,
                      "the scrubbed file must carry nothing: \(ImageMetadata.fields(in: clean))")
        let props = properties(clean)
        XCTAssertNil(props[kCGImagePropertyGPSDictionary])
        XCTAssertNil(props[kCGImagePropertyExifDictionary])
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        XCTAssertNil(tiff?[kCGImagePropertyTIFFArtist])
        XCTAssertNil(clean.range(of: Data("<?xpacket".utf8)), "no XMP packet survives")
        XCTAssertNil(clean.range(of: Data(ScrubFixtures.author.utf8)),
                     "the author's name is not anywhere in the bytes")

        // still a picture, and the same one: the entropy-coded scan is byte-identical
        let image = UIImage(data: clean)
        XCTAssertNotNil(image)
        XCTAssertEqual(image?.size.width, 64)
        XCTAssertEqual(image?.size.height, 48)
        XCTAssertEqual(scan(of: clean), scan(of: original),
                       "not one byte of image data may be touched")
        // §B5: the original is untouched
        XCTAssertEqual(original, ScrubFixtures.jpeg())
    }

    /// Stripping EXIF must not rotate somebody's photo.
    func testJPEGKeepsItsOrientation() {
        let upright = ScrubFixtures.jpeg()
        var rotated = JPEGScrub.strip(upright) ?? Data()
        rotated.insert(contentsOf: JPEGScrub.orientationSegment(6), at: 2)
        XCTAssertEqual(properties(rotated)[kCGImagePropertyOrientation] as? Int, 6,
                       "the fixture itself must be sideways for this to mean anything")
        let clean = JPEGScrub.stripPreservingOrientation(rotated)
        XCTAssertEqual(properties(clean ?? Data())[kCGImagePropertyOrientation] as? Int, 6)
        XCTAssertTrue(ImageMetadata.fields(in: clean ?? Data()).isEmpty,
                      "…and nothing else came back with it")
    }

    // MARK: - PNG

    func testPNGLosesItsTextChunksAndStillDecodes() async {
        let original = ScrubFixtures.png()
        let before = ImageMetadata.fields(in: original)
        XCTAssertEqual(before.first(where: { $0.label == "author" })?.value, ScrubFixtures.author)
        XCTAssertEqual(before.first(where: { $0.label == "software" })?.value,
                       ScrubFixtures.software)
        XCTAssertEqual(before.first(where: { $0.label == "description" })?.value,
                       "do not circulate")
        XCTAssertEqual(before.first(where: { $0.label == "comment" })?.value, "shot at home")

        let (clean, report) = await MetadataScrubber.scrub(original, filename: "shot.png")
        XCTAssertTrue(report.supported)
        XCTAssertEqual(report.removed, before)
        XCTAssertTrue(ImageMetadata.fields(in: clean).isEmpty)
        XCTAssertNil(clean.range(of: Data("tEXt".utf8)), "no text chunk survives")
        XCTAssertNil(clean.range(of: Data(ScrubFixtures.author.utf8)))

        let image = UIImage(data: clean)
        XCTAssertNotNil(image, "a scrubbed PNG must still decode")
        XCTAssertEqual(image?.size.width, 40)
        XCTAssertEqual(image?.size.height, 30)
        // the pixels are bit-for-bit the pixels: chunk removal, not re-encoding
        XCTAssertEqual(chunks(of: "IDAT", in: clean), chunks(of: "IDAT", in: original))
        XCTAssertEqual(chunks(of: "IHDR", in: clean), chunks(of: "IHDR", in: original))
        XCTAssertEqual(original, ScrubFixtures.png(), "the original is untouched")
    }

    // MARK: - HEIC

    func testHEICLosesItsEXIFOrIsHonestAboutNotBeingCleanable() async {
        let original = ScrubFixtures.heic()
        let before = ImageMetadata.fields(in: original)
        XCTAssertEqual(before.first(where: { $0.label == "author" })?.value,
                       ScrubFixtures.author, "the fixture must carry EXIF: \(before)")
        XCTAssertEqual(before.first(where: { $0.label == "gps" })?.value, ScrubFixtures.gpsText)

        let (clean, report) = await MetadataScrubber.scrub(original, filename: "photo.heic")
        if report.supported {
            XCTAssertEqual(report.removed, before, "the receipt is exactly what was found")
            XCTAssertTrue(ImageMetadata.fields(in: clean).isEmpty,
                          "HEIC EXIF must go: \(ImageMetadata.fields(in: clean))")
            XCTAssertNil(clean.range(of: Data(ScrubFixtures.author.utf8)))
            XCTAssertNotNil(UIImage(data: clean), "and it must still decode")
        } else {
            // This platform has no HEIC encoder (every current iOS SIMULATOR),
            // so the file cannot be re-muxed. §B2: never claim it was cleaned —
            // it goes out untouched and the receipt says the honest thing.
            XCTAssertEqual(clean, original, "passed through byte-for-byte")
            XCTAssertTrue(report.removed.isEmpty)
            XCTAssertNil(HEICScrub.strip(original),
                         "the fallback is only correct when the strip really failed")
        }
        XCTAssertEqual(original, ScrubFixtures.heic(), "the original is untouched")
    }

    // MARK: - PDF

    func testPDFLosesItsInfoDictionaryAndXMPAndStillRenders() async {
        let original = ScrubFixtures.pdf()
        XCTAssertNotNil(PDFDocument(data: original), "the fixture must be a real PDF")
        XCTAssertEqual(PDFDocument(data: original)?.page(at: 0)?.string?
            .trimmingCharacters(in: .whitespacesAndNewlines),
                       ScrubFixtures.pdfBodyText, "the fixture's own text, before anything")

        let (clean, report) = await MetadataScrubber.scrub(original, filename: "report.pdf")
        XCTAssertTrue(report.supported)
        let labels = report.removed.map(\.label)
        XCTAssertEqual(report.removed.first(where: { $0.label == "author" })?.value,
                       ScrubFixtures.author)
        XCTAssertEqual(report.removed.first(where: { $0.label == "producer" })?.value,
                       ScrubFixtures.producer)
        XCTAssertEqual(report.removed.first(where: { $0.label == "creator" })?.value,
                       ScrubFixtures.creator)
        XCTAssertTrue(labels.contains("title"))
        XCTAssertTrue(labels.contains("created"))
        XCTAssertTrue(labels.contains("xmp"), "the XMP packet is reported, not silently dropped")

        // gone, in the bytes
        for secret in [ScrubFixtures.author, ScrubFixtures.producer, ScrubFixtures.creator,
                       "Quarterly report", "budget draft"] {
            XCTAssertNil(clean.range(of: Data(secret.utf8)),
                         "\"\(secret)\" is still in the scrubbed PDF")
        }
        XCTAssertNil(clean.range(of: Data("<?xpacket".utf8)), "the XMP packet is blanked")

        // …and the document still opens, with its page and its words
        let doc = PDFDocument(data: clean)
        XCTAssertNotNil(doc, "a scrubbed PDF must still render")
        XCTAssertEqual(doc?.pageCount, 1)
        XCTAssertTrue(doc?.page(at: 0)?.string?.contains(ScrubFixtures.pdfBodyText) == true,
                      "the page's own text must survive: \(doc?.page(at: 0)?.string ?? "nil")")
        let attrs: [AnyHashable: Any] = doc?.documentAttributes ?? [:]
        for (key, _) in PDFScrub.infoKeys {
            let value = (attrs[key.rawValue] ?? attrs[key]) as? String
            XCTAssertTrue((value ?? "").trimmingCharacters(in: .whitespaces).isEmpty,
                          "\(key.rawValue) came back as \(value ?? "nil")")
        }
        XCTAssertEqual(original, ScrubFixtures.pdf(), "the original is untouched")
    }

    // MARK: - Office

    func testDocxLosesItsPropertiesAndStillUnzips() async {
        let original = ScrubFixtures.docx()
        let (clean, report) = await MetadataScrubber.scrub(original, filename: "report.docx")
        XCTAssertTrue(report.supported)

        let removed = Dictionary(uniqueKeysWithValues: report.removed.map { ($0.label, $0.value) })
        XCTAssertEqual(removed["author"], ScrubFixtures.author)
        XCTAssertEqual(removed["last saved by"], ScrubFixtures.author)
        XCTAssertEqual(removed["application"], "Microsoft Office Word")
        XCTAssertEqual(removed["company"], "Pyro Industries")
        XCTAssertEqual(removed["manager"], "A. Manager")
        XCTAssertEqual(removed["title"], "Quarterly report")
        XCTAssertEqual(removed["description"], "do not circulate")
        XCTAssertEqual(removed["created"], "2024-03-01T09:12:00Z")
        XCTAssertEqual(removed["revision"], "7")
        // §B5: the receipt never carries the file's contents
        for field in report.removed {
            XCTAssertFalse(field.value.contains("PYROCHAT FIXTURE BODY TEXT"),
                           "the receipt must never carry the document's words")
        }

        // the parts are gone, and nothing points at them any more
        let entries = try? XCTUnwrap(MiniZip.entries(in: clean))
        let names = (entries ?? []).map(\.name)
        XCTAssertFalse(names.contains("docProps/core.xml"))
        XCTAssertFalse(names.contains("docProps/app.xml"))
        XCTAssertTrue(names.contains("word/document.xml"))
        XCTAssertTrue(names.contains("[Content_Types].xml"))
        XCTAssertTrue(names.contains("_rels/.rels"))
        XCTAssertNil(clean.range(of: Data(ScrubFixtures.author.utf8)),
                     "the name must not survive anywhere in the package")
        for part in ["[Content_Types].xml", "_rels/.rels"] {
            guard let entry = (entries ?? []).first(where: { $0.name == part }),
                  let xml = MiniZip.read(entry, in: clean) else {
                XCTFail("\(part) unreadable"); continue
            }
            let text = String(decoding: xml, as: UTF8.self)
            XCTAssertFalse(text.contains("docProps/core.xml"), "\(part) still references core.xml")
            XCTAssertFalse(text.contains("docProps/app.xml"), "\(part) still references app.xml")
            XCTAssertTrue(wellFormed(xml), "\(part) must still be well-formed XML")
        }

        // the document itself is untouched, byte for byte, and still inflates
        guard let before = MiniZip.entries(in: original)?
                .first(where: { $0.name == "word/document.xml" }),
              let after = (entries ?? []).first(where: { $0.name == "word/document.xml" }) else {
            return XCTFail("word/document.xml missing")
        }
        XCTAssertEqual(MiniZip.compressedBytes(of: before, in: original),
                       MiniZip.compressedBytes(of: after, in: clean),
                       "the content parts are copied, never recompressed")
        XCTAssertEqual(MiniZip.read(after, in: clean), MiniZip.read(before, in: original))
        XCTAssertTrue(String(decoding: MiniZip.read(after, in: clean) ?? Data(), as: UTF8.self)
            .contains("PYROCHAT FIXTURE BODY TEXT"))
        XCTAssertEqual(original, ScrubFixtures.docx(), "the original is untouched")
    }

    func testXlsxIsScrubbedTheSameWay() async {
        let (clean, report) = await MetadataScrubber.scrub(ScrubFixtures.xlsx(),
                                                          filename: "budget.xlsx")
        XCTAssertTrue(report.supported)
        XCTAssertTrue(report.removed.contains(ScrubField("author", ScrubFixtures.author)))
        let names = (MiniZip.entries(in: clean) ?? []).map(\.name)
        XCTAssertFalse(names.contains("docProps/core.xml"))
        XCTAssertTrue(names.contains("xl/workbook.xml"))
    }

    /// A zip that is not an Office package (no docProps) is left exactly as it
    /// was, and nothing is claimed about it beyond the truth.
    func testAPackageWithNoPropertiesIsLeftAlone() async {
        let zip = MiniZip.write([.stored(name: "a.txt", content: Data("hello".utf8),
                                         modTime: 0, modDate: 0)])
        let (out, report) = await MetadataScrubber.scrub(zip, filename: "thing.docx")
        XCTAssertEqual(out, zip, "nothing to remove ⇒ nothing rewritten")
        XCTAssertTrue(report.supported)
        XCTAssertTrue(report.removed.isEmpty)
    }

    // MARK: - §B1 "anything else"

    func testUnknownTypePassesThroughByteForByte() async {
        var payload = Data(count: 64 * 1024)
        payload.withUnsafeMutableBytes {
            _ = SecRandomCopyBytes(kSecRandomDefault, 64 * 1024, $0.baseAddress!)
        }
        for name in ["trip.bin", "notes.txt", "archive.zip", "noextension"] {
            let (out, report) = await MetadataScrubber.scrub(payload, filename: name)
            XCTAssertEqual(out, payload, "\(name) must go out exactly as it came in")
            XCTAssertFalse(report.supported, "\(name): never claim it was cleaned")
            XCTAssertTrue(report.removed.isEmpty)
            XCTAssertNil(MetadataScrubber.preSendLine(removed: report.removed, filename: name))
        }
    }

    /// A file that claims to be a PDF but is not: the honest line, not a lie.
    func testCorruptFileIsReportedAsNotCleanable() async {
        let junk = Data("%PDF-1.4 but not really".utf8)
        let (out, report) = await MetadataScrubber.scrub(junk, filename: "fake.pdf")
        XCTAssertEqual(out, junk)
        XCTAssertFalse(report.supported)
        XCTAssertTrue(report.removed.isEmpty)
    }

    // MARK: - §B5 the original file is untouched

    func testScrubbingAFileNeverTouchesTheOriginal() async throws {
        let src = try MetadataScrubber.scratchURL(ext: "pdf")
        let dst = try MetadataScrubber.scratchURL(ext: "pdf")
        defer {
            try? FileManager.default.removeItem(at: src.deletingLastPathComponent())
            try? FileManager.default.removeItem(at: dst.deletingLastPathComponent())
        }
        let original = ScrubFixtures.pdf()
        try original.write(to: src)
        let before = try Data(contentsOf: src)
        let report = try await MetadataScrubber.scrub(fileAt: src, to: dst)
        XCTAssertTrue(report.anything)
        XCTAssertEqual(try Data(contentsOf: src), before,
                       "the file the user picked must be bit-for-bit what it was")
        let cleaned = try Data(contentsOf: dst)
        XCTAssertNotEqual(cleaned, before)
        XCTAssertNil(cleaned.range(of: Data(ScrubFixtures.author.utf8)))
    }

    // MARK: - helpers

    /// Every chunk of one type, concatenated — the PNG equivalent of "the
    /// picture itself".
    private func chunks(of type: String, in png: Data) -> Data {
        let bytes = [UInt8](png)
        var out = Data()
        var i = 8
        while i + 8 <= bytes.count {
            let length = Int(bytes[i]) << 24 | Int(bytes[i + 1]) << 16
                | Int(bytes[i + 2]) << 8 | Int(bytes[i + 3])
            guard length >= 0, i + 12 + length <= bytes.count else { break }
            if String(decoding: bytes[(i + 4)..<(i + 8)], as: UTF8.self) == type {
                out.append(png[(i + 8)..<(i + 8 + length)])
            }
            i += 12 + length
        }
        return out
    }

    /// XMLParser, used only as a well-formedness check.
    private func wellFormed(_ xml: Data) -> Bool { XMLParser(data: xml).parse() }

    private func properties(_ data: Data) -> [CFString: Any] {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil)
                as? [CFString: Any] else { return [:] }
        return props
    }

    /// Everything from the start-of-scan marker on — the compressed picture.
    private func scan(of jpeg: Data) -> Data {
        let bytes = [UInt8](jpeg)
        var i = 2
        while i + 3 < bytes.count {
            guard bytes[i] == 0xFF else { return Data() }
            let marker = bytes[i + 1]
            if marker == 0xDA { return jpeg[i...] }
            let length = Int(bytes[i + 2]) << 8 | Int(bytes[i + 3])
            i += 2 + length
        }
        return Data()
    }
}

// MARK: - movies (docs/SCRUB-AND-WIPE.md §B1 and the §B5 memory rule)

/// Separate class: these build real H.264 files, which is slow enough to be
/// worth keeping out of the way of the fast tests.
final class MovieScrubTests: XCTestCase {

    private func scratch(_ ext: String) throws -> URL {
        let url = try MetadataScrubber.scratchURL(ext: ext)
        addTeardownBlock {
            try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        }
        return url
    }

    func testMP4LosesItsAtomsAndStillPlays() async throws {
        // .mov: the container an iPhone actually records into, and the one
        // the QuickTime metadata atoms §B1 names belong to
        let src = try scratch("mov")
        let dst = try scratch("mov")
        _ = try await ScrubFixtures.mp4(at: src, seconds: 1, width: 192, height: 144,
                                        fileType: .mov)
        let sizeBefore = try Data(contentsOf: src).count

        let before = await MovieScrub.fields(of: AVURLAsset(url: src))
        let labels = before.map(\.label)
        XCTAssertTrue(labels.contains("make"), "fixture metadata missing: \(before)")
        XCTAssertTrue(labels.contains("model"))
        XCTAssertTrue(labels.contains("software"))
        XCTAssertTrue(labels.contains("gps"), "the location atom is what matters most")
        XCTAssertEqual(before.first(where: { $0.label == "model" })?.value, ScrubFixtures.model)

        let report = try await MetadataScrubber.scrub(fileAt: src, to: dst)
        XCTAssertTrue(report.supported)
        XCTAssertEqual(report.removed, before, "the receipt is exactly what was there")

        // gone
        let after = AVURLAsset(url: dst)
        let left = await MovieScrub.fields(of: after)
        XCTAssertTrue(left.isEmpty, "metadata atoms survived: \(left)")
        let raw = try Data(contentsOf: dst)
        for secret in [ScrubFixtures.make, ScrubFixtures.model, ScrubFixtures.software,
                       "+45.5017-073.5673/"] {
            XCTAssertNil(raw.range(of: Data(secret.utf8)),
                         "\"\(secret)\" is still in the scrubbed movie")
        }

        // …and it is still a playable movie of the same length
        let tracks = try await after.load(.tracks)
        XCTAssertFalse(tracks.isEmpty, "a scrubbed movie must still have its track")
        let duration = try await after.load(.duration)
        XCTAssertEqual(CMTimeGetSeconds(duration), 1, accuracy: 0.2)
        // pass-through, not a re-encode: the samples are all still there
        XCTAssertGreaterThan(raw.count, sizeBefore / 2)
        // §B5: the original is untouched
        XCTAssertEqual(try Data(contentsOf: src).count, sizeBefore)
    }

    /// §B5: "Scrubbing a 50 MB video does not load it entirely into memory."
    func testLargeVideoIsStreamedNotLoaded() async throws {
        let src = try scratch("mp4")
        let dst = try scratch("mp4")
        _ = try await ScrubFixtures.mp4(at: src, seconds: 3, width: 1_280, height: 720,
                                        megabytes: 60)
        let size = try FileManager.default.attributesOfItem(atPath: src.path)[.size] as? Int ?? 0
        XCTAssertGreaterThan(size, 40 * 1_024 * 1_024,
                             "the fixture has to be big enough for this to mean anything")

        let before = footprint()
        let report = try await MetadataScrubber.scrub(fileAt: src, to: dst)
        let peak = footprint()
        XCTAssertTrue(report.supported)

        let out = try FileManager.default.attributesOfItem(atPath: dst.path)[.size] as? Int ?? 0
        XCTAssertGreaterThan(out, size / 2, "the samples came through")
        let growth = Int(peak) - Int(before)
        XCTAssertLessThan(growth, size / 2,
                          "the scrub grew memory by \(growth / 1_048_576) MB against a "
                          + "\(size / 1_048_576) MB file — that is a whole-file buffer")
    }

    /// Resident footprint, the way the OS accounts for it.
    private func footprint() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size
                                           / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? info.phys_footprint : 0
    }
}

// MARK: - the receipt (docs/SCRUB-AND-WIPE.md §B2, §B4)

final class ScrubReceiptTests: XCTestCase {

    private func fileRow(removed: [ScrubField]?, scrubbable: Bool?) -> MessageDetails.Input {
        var msg = ChatMessage(id: "11111111-2222-3333-4444-555555555555",
                              direction: .outgoing, kind: .file, text: "[FILE] report.pdf",
                              timestamp: Date(timeIntervalSince1970: 1_700_000_000),
                              status: .sent, burnAfter: nil, burnStartedAt: nil,
                              attachmentId: "att", fileName: "report.pdf",
                              fileMime: "application/pdf", fileBytes: 43_112)
        msg.removedMetadata = removed
        msg.scrubbable = scrubbable
        return MessageDetails.Input(message: msg, peerName: "alice",
                                    peerHash: String(repeating: "a", count: 64),
                                    timeZone: TimeZone(identifier: "UTC")!)
    }

    private let spec = [ScrubField("author", "J. Example"),
                        ScrubField("gps", "45.5017, -73.5673"),
                        ScrubField("producer", "Microsoft Word 16.0")]

    /// §B2: "placed directly after `── what it is ──`".
    func testSectionSitsDirectlyAfterWhatItIs() {
        let lines = MessageDetails.lines(fileRow(removed: spec, scrubbable: true))
        let headings = lines.enumerated().filter { $0.element.kind == .heading }.map(\.offset)
        let itIs = lines.firstIndex { $0.value == MessageDetails.headingWhatItIs }
        let removed = lines.firstIndex { $0.value == MessageDetails.headingWhatWasRemoved }
        let relay = lines.firstIndex { $0.value == MessageDetails.headingWhatTheRelayReceived }
        XCTAssertNotNil(itIs); XCTAssertNotNil(removed); XCTAssertNotNil(relay)
        XCTAssertTrue(itIs! < removed!, "the receipt comes after ── what it is ──")
        XCTAssertTrue(removed! < relay!, "…and before ── what the relay received ──")
        // nothing between them but that section's own heading
        XCTAssertEqual(headings.filter { $0 > itIs! && $0 < relay! }, [removed!])
    }

    /// §B2: the spec's own block, rendered.
    func testRowsAreTheSpecBlock() {
        let lines = MessageDetails.whatWasRemoved(fileRow(removed: spec, scrubbable: true))
        XCTAssertEqual(lines.first?.value, "\u{2500}\u{2500} what was removed before sending \u{2500}\u{2500}")
        XCTAssertEqual(lines[1].text, "author        J. Example")
        XCTAssertEqual(lines[2].text, "gps           45.5017, -73.5673")
        XCTAssertEqual(lines[3].text, "producer      Microsoft Word 16.0")
        XCTAssertEqual(lines[1].label, "author", "labels are lower-case")
        XCTAssertEqual(lines.last?.kind, .footer)
        // §B4, verbatim
        XCTAssertEqual(lines.last?.value,
                       "this cleans the file's hidden fields, not its contents: a name written "
                       + "inside the document, or a face in a photo, is still there.")
    }

    /// §B2: the honest line for a type we cannot clean.
    func testUnscrubbableTypeSaysSo() {
        let lines = MessageDetails.whatWasRemoved(fileRow(removed: nil, scrubbable: false))
        XCTAssertEqual(lines.count, 3)
        XCTAssertEqual(lines[0].value, MessageDetails.headingWhatWasRemoved)
        XCTAssertEqual(lines[1].value, "nothing \u{2014} this file type is not one we can clean")
        XCTAssertEqual(lines[2].kind, .footer)
    }

    /// §B2: "only when something was removed" — a clean file of a type we CAN
    /// scrub gets no section at all, because a section would be a claim.
    func testNoSectionWhenNothingWasRemoved() {
        XCTAssertTrue(MessageDetails.whatWasRemoved(fileRow(removed: [], scrubbable: true)).isEmpty)
        XCTAssertTrue(MessageDetails.whatWasRemoved(fileRow(removed: nil, scrubbable: true)).isEmpty)
        // a row written before the feature existed says nothing either
        XCTAssertTrue(MessageDetails.whatWasRemoved(fileRow(removed: nil, scrubbable: nil)).isEmpty)
        let lines = MessageDetails.plainText(fileRow(removed: nil, scrubbable: nil))
        XCTAssertFalse(lines.contains { $0.contains("what was removed") })
    }

    /// §B5: "The receipt never carries the file's contents, only field names
    /// and values."
    func testReceiptCarriesOnlyFieldNamesAndValues() {
        let input = fileRow(removed: spec, scrubbable: true)
        let text = MessageDetails.whatWasRemoved(input).map(\.text).joined(separator: "\n")
        XCTAssertFalse(text.contains("[FILE]"), "not even the row's own label")
        for field in spec {
            XCTAssertTrue(text.contains(field.label))
            XCTAssertTrue(text.contains(field.value))
        }
        // every row is one of ours, and nothing else got in
        for line in MessageDetails.whatWasRemoved(input) where line.kind == .row {
            XCTAssertTrue(spec.contains(ScrubField(line.label, line.value)),
                          "invented row: \(line.text)")
        }
    }

    /// A ScrubField survives the store's JSON round-trip, and an old row
    /// without the keys still decodes.
    func testScrubFieldsRoundTripThroughTheStoresJSON() throws {
        var msg = ChatMessage(id: "id", direction: .outgoing, kind: .file, text: "[FILE] a.pdf",
                              timestamp: Date(), status: .sent)
        msg.removedMetadata = spec
        msg.scrubbable = true
        let data = try JSONEncoder().encode(msg)
        let back = try JSONDecoder().decode(ChatMessage.self, from: data)
        XCTAssertEqual(back.removedMetadata, spec)
        XCTAssertEqual(back.scrubbable, true)

        let old = """
            {"id":"x","direction":"outgoing","kind":"file","text":"[FILE] a.pdf",
             "timestamp":0,"status":"sent"}
            """
        let decoded = try JSONDecoder().decode(ChatMessage.self, from: Data(old.utf8))
        XCTAssertNil(decoded.removedMetadata)
        XCTAssertNil(decoded.scrubbable)
    }
}

// MARK: - the choke point (ChatService.sendFile)

final class ScrubSendPathTests: XCTestCase {

    @MainActor
    private func makeService() -> ChatService {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-scrub-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "scrub-test-pass-123",
                                at: dir.appendingPathComponent("store"))
        return ChatService(store: store)
    }

    /// §B: "Runs on the sender's device, before encryption, on every
    /// attachment." Even when the caller did no scrubbing of its own.
    @MainActor
    func testSendFileScrubsAndRecordsTheReceipt() async {
        let service = makeService()
        let peer = Contact(hash: String(repeating: "b", count: 64), name: "bob", addedAt: Date())
        let original = ScrubFixtures.docx()
        await service.sendFile(data: original, filename: "report.docx",
                               mime: "application/vnd.openxmlformats-officedocument."
                               + "wordprocessingml.document", to: peer)
        guard let row = service.messages[peer.hash]?.first else {
            return XCTFail("nothing was appended")
        }
        XCTAssertEqual(row.scrubbable, true)
        XCTAssertEqual(row.removedMetadata?.first(where: { $0.label == "author" })?.value,
                       ScrubFixtures.author)
        // what is actually stored — and therefore what was encrypted — is clean
        let stored = service.attachmentData(for: row)
        XCTAssertNotNil(stored)
        XCTAssertNotEqual(stored, original)
        XCTAssertNil(stored?.range(of: Data(ScrubFixtures.author.utf8)))
        XCTAssertEqual(row.fileBytes, stored?.count, "the size label is the scrubbed size")
        service.goOffline()
    }

    /// §B1: "anything else — nothing — sent as-is, and the receipt says so."
    @MainActor
    func testSendFileLeavesUnknownTypesAloneAndSaysSo() async {
        let service = makeService()
        let peer = Contact(hash: String(repeating: "c", count: 64), name: "carol", addedAt: Date())
        let payload = Data((0..<4096).map { UInt8($0 % 251) })
        await service.sendFile(data: payload, filename: "trip.bin",
                               mime: "application/octet-stream", to: peer)
        guard let row = service.messages[peer.hash]?.first else {
            return XCTFail("nothing was appended")
        }
        XCTAssertEqual(row.scrubbable, false)
        XCTAssertNil(row.removedMetadata)
        XCTAssertEqual(service.attachmentData(for: row), payload, "byte for byte")
        // and the sheet says the honest thing
        let input = MessageDetails.Input(message: row, peerName: "carol")
        XCTAssertTrue(MessageDetails.plainText(input)
            .contains("nothing \u{2014} this file type is not one we can clean"))
        service.goOffline()
    }
}
