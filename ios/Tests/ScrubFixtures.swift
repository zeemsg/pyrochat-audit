import Foundation
import ImageIO
import UniformTypeIdentifiers
import AVFoundation
import CoreGraphics
import UIKit
@testable import PyroChat

/// Fixture files for docs/SCRUB-AND-WIPE.md §B5 — "Fixture files per type,
/// each carrying known metadata, asserted gone after scrubbing and the
/// document still opening correctly afterwards".
///
/// Everything here is built, not checked in as a blob, EXCEPT the two Office
/// packages: those were written by python's `zipfile` (scratchpad
/// `make_office_fixtures.py`) precisely so that MiniZip's reader is exercised
/// against a zip implementation that is not its own.
enum ScrubFixtures {

    // MARK: known values — the tests assert on exactly these

    static let author = "J. Example"
    static let producer = "Microsoft Word 16.0"
    static let creator = "PyroChat Fixture"
    static let make = "PyroPhone"
    static let model = "PyroPhone 13 Pro"
    static let software = "PyroCam 4.2"
    static let latitude = 45.5017
    static let longitude = -73.5673
    /// How the receipt spells that pair (§B2).
    static let gpsText = "45.5017, -73.5673"

    // MARK: JPEG

    /// A real JPEG carrying TIFF (artist/make/model/software), EXIF, GPS and
    /// — hand-appended, because ImageIO will not write one — an XMP packet.
    static func jpeg() -> Data {
        let base = solid(width: 64, height: 48, color: .systemTeal, type: UTType.jpeg)
        let out = NSMutableData()
        guard let src = CGImageSourceCreateWithData(base as CFData, nil),
              let dest = CGImageDestinationCreateWithData(
                out, UTType.jpeg.identifier as CFString, 1, nil) else { return base }
        let props: [CFString: Any] = [
            kCGImagePropertyTIFFDictionary: [
                kCGImagePropertyTIFFArtist: author,
                kCGImagePropertyTIFFMake: make,
                kCGImagePropertyTIFFModel: model,
                kCGImagePropertyTIFFSoftware: software,
                kCGImagePropertyTIFFDateTime: "2024:03:01 09:12:00",
            ] as [CFString: Any],
            kCGImagePropertyExifDictionary: [
                kCGImagePropertyExifDateTimeOriginal: "2024:03:01 09:12:00",
                kCGImagePropertyExifBodySerialNumber: "SN-00042",
            ] as [CFString: Any],
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: abs(latitude),
                kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: abs(longitude),
                kCGImagePropertyGPSLongitudeRef: "W",
            ] as [CFString: Any],
        ]
        CGImageDestinationAddImageFromSource(dest, src, 0, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return base }
        return insertXMPSegment(into: out as Data)
    }

    /// `FFE1 <len> http://ns.adobe.com/xap/1.0/\0 <packet>`, straight after SOI.
    static func insertXMPSegment(into jpeg: Data) -> Data {
        guard jpeg.count > 2 else { return jpeg }
        var payload = Data("http://ns.adobe.com/xap/1.0/\0".utf8)
        payload.append(Data(xmpPacket.utf8))
        let length = payload.count + 2
        guard length <= 0xFFFF else { return jpeg }
        var segment = Data([0xFF, 0xE1, UInt8(length >> 8), UInt8(length & 0xFF)])
        segment.append(payload)
        var out = jpeg
        out.insert(contentsOf: segment, at: 2)
        return out
    }

    static let xmpPacket = """
        <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>\
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="PyroFixture 1.0">\
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\
        <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">\
        <dc:creator><rdf:Seq><rdf:li>J. Example</rdf:li></rdf:Seq></dc:creator>\
        </rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>
        """

    // MARK: PNG

    /// A real PNG with hand-written `tEXt` chunks (§B1: "PNG text chunks").
    static func png() -> Data {
        let base = solid(width: 40, height: 30, color: .systemPink, type: UTType.png)
        var out = base
        for (keyword, value) in [("Author", author), ("Software", software),
                                 ("Description", "do not circulate"),
                                 ("Comment", "shot at home"),
                                 ("Copyright", "(c) 2024 J. Example")] {
            out = insertTEXt(into: out, keyword: keyword, value: value)
        }
        return out
    }

    /// Inserts a `tEXt` chunk immediately before IEND.
    static func insertTEXt(into png: Data, keyword: String, value: String) -> Data {
        var payload = Data(keyword.utf8)
        payload.append(0)
        payload.append(Data(value.utf8))
        var chunk = Data()
        let length = UInt32(payload.count)
        chunk.append(contentsOf: [UInt8(length >> 24 & 0xFF), UInt8(length >> 16 & 0xFF),
                                  UInt8(length >> 8 & 0xFF), UInt8(length & 0xFF)])
        var typed = Data("tEXt".utf8)
        typed.append(payload)
        chunk.append(typed)
        let crc = MiniZip.crc32(typed)
        chunk.append(contentsOf: [UInt8(crc >> 24 & 0xFF), UInt8(crc >> 16 & 0xFF),
                                  UInt8(crc >> 8 & 0xFF), UInt8(crc & 0xFF)])
        // BEFORE the first IDAT: ImageIO stops collecting text once the image
        // data starts, and a chunk it never reads is a fixture that proves
        // nothing.
        guard let idat = png.range(of: Data("IDAT".utf8)), idat.lowerBound >= 4 else {
            return png
        }
        var out = png
        out.insert(contentsOf: chunk, at: idat.lowerBound - 4)
        return out
    }

    // MARK: HEIC

    /// A real HEIC carrying TIFF (artist/make/model/software) and GPS, built
    /// on the host with ImageIO (scratchpad `make_heic.swift`): iOS simulators
    /// have no HEIC ENCODER, so a fixture built at runtime would be nil there
    /// and the format §B1 names would go untested.
    static func heic() -> Data {
        Data(base64Encoded: heicBase64, options: [.ignoreUnknownCharacters])!
    }

    static let heicBase64 = """
        AAAAJGZ0eXBoZWljAAAAAG1pZjFNaVBybWlhZk1pSEJoZWljAAACCG1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAHBpY3QAAAAAAAAA
        AAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAAADnBpdG0AAAAAAAEAAABhaWluZgAAAAAAAwAAABVp
        bmZlAgAAAAABAABodmMxAAAAABVpbmZlAgAAAQACAABFeGlmAAAAAClpbmZlAgAAAQADAABtaW1lAGFwcGxpY2F0aW9uL3JkZit4
        bWwAAAAAKGlyZWYAAAAAAAAADmNkc2MAAgABAAEAAAAOY2RzYwADAAEAAQAAAOZpcHJwAAAAxWlwY28AAAATY29scm5jbHgAAgAC
        AAaAAAAADGNsbGkAywBAAAAAFGlzcGUAAAAAAAAAQAAAADAAAAAJaXJvdAAAAAAQcGl4aQAAAAADCAgIAAAAcWh2Y0MBA3AAAACw
        AAAAAAAe8AD8/fj4AAALA6AAAQAXQAEMAf//A3AAAAMAsAAAAwAAAwAecCShAAEAI0IBAQNwAAADALAAAAMAAAMAHqAUIEHBjE4h
        7kWVTcCAgYAgogABAAlEAcBhcshEU2QAAAAZaXBtYQAAAAAAAAABAAEGgQIDhAWGAAAAOmlsb2MAAAAARAAAAwABAAAAAQAADP0A
        AACkAAIAAAABAAACPAAAAO4AAwAAAAEAAAMqAAAJ0wAAAAFtZGF0AAAAAAAAC3UAAAAGRXhpZgAATU0AKgAAAAgABQEPAAIAAAAK
        AAAASgEQAAIAAAARAAAAVAExAAIAAAAMAAAAZgE7AAIAAAALAAAAcoglAAQAAAABAAAAfgAAAABQeXJvUGhvbmUAUHlyb1Bob25l
        IDEzIFBybwAAUHlyb0NhbSA0LjIASi4gRXhhbXBsZQAAAAQAAQACAAAAAk4AAAAAAgAFAAAAAwAAALQAAwACAAAAAlcAAAAABAAF
        AAAAAwAAAMwAAAAAAAAALQAAAAEAAAAeAAAAAQAAAmQAAABkAAAASQAAAAEAAAAiAAAAAQAAAOQAAABkPD94cGFja2V0IGJlZ2lu
        PSLvu78iIGlkPSJXNU0wTXBDZWhpSHpyZVN6TlRjemtjOWQiPz4gPHg6eG1wbWV0YSB4bWxuczp4PSJhZG9iZTpuczptZXRhLyIg
        eDp4bXB0az0iWE1QIENvcmUgNi4wLjAiPiA8cmRmOlJERiB4bWxuczpyZGY9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkvMDIvMjIt
        cmRmLXN5bnRheC1ucyMiPiA8cmRmOkRlc2NyaXB0aW9uIHJkZjphYm91dD0iIiB4bWxuczpkYz0iaHR0cDovL3B1cmwub3JnL2Rj
        L2VsZW1lbnRzLzEuMS8iIHhtbG5zOnhtcD0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wLyIgeG1wOkNyZWF0b3JUb29sPSJQ
        eXJvQ2FtIDQuMiI+IDxkYzpjcmVhdG9yPiA8cmRmOlNlcT4gPHJkZjpsaT5KLiBFeGFtcGxlPC9yZGY6bGk+IDwvcmRmOlNlcT4g
        PC9kYzpjcmVhdG9yPiA8L3JkZjpEZXNjcmlwdGlvbj4gPC9yZGY6UkRGPiA8L3g6eG1wbWV0YT4gICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg
        ICAgICA8P3hwYWNrZXQgZW5kPSJ3Ij8+AAAAAKAoAa+i9kaBfP/89m+v+qX//nGD7Da0T2Mtf/uj1v0XT+n/0ArCLpyokWbMh/Nx
        gBAOVPRT//6ms1utlSnpo1IXxegBp5P8uRnD86q86+qUVNGxqVCEN/8UUD06Fi+lf5i2/EEgkAAkLkVGNK9ZZAucCc/Ec3BNrLOh
        VhKdPkPZRmbDcXyFOhkytlFPentgNVm2f8x6KSZXixFwsfTUYdbg
        """

    // MARK: PDF

    static let pdfBodyText = "PYROFIXTURE"

    /// A hand-written PDF-1.4: one page with real text, an Info dictionary and
    /// an XMP `/Metadata` stream. Hand-written so the bytes are exactly known
    /// and the offsets in the xref table are ours to verify against.
    static func pdf() -> Data {
        let content = "BT /F1 12 Tf 10 120 Td (\(pdfBodyText)) Tj ET\n"
        var objects: [String] = []
        objects.append("<< /Type /Catalog /Pages 2 0 R /Metadata 6 0 R >>")
        objects.append("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        objects.append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] "
                       + "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
        objects.append("<< /Length \(content.utf8.count) >>\nstream\n\(content)endstream")
        objects.append("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        objects.append("<< /Type /Metadata /Subtype /XML /Length \(xmpPacket.utf8.count) >>"
                       + "\nstream\n\(xmpPacket)\nendstream")
        objects.append("<< /Author (\(author)) /Producer (\(producer)) /Creator (\(creator)) "
                       + "/Title (Quarterly report) /Subject (internal) "
                       + "/Keywords (budget draft) "
                       + "/CreationDate (D:20240301091200Z) /ModDate (D:20240304174500Z) >>")

        var out = Data("%PDF-1.4\n".utf8)
        var offsets: [Int] = []
        for (i, body) in objects.enumerated() {
            offsets.append(out.count)
            out.append(Data("\(i + 1) 0 obj\n\(body)\nendobj\n".utf8))
        }
        let xref = out.count
        out.append(Data("xref\n0 \(objects.count + 1)\n0000000000 65535 f \n".utf8))
        for offset in offsets {
            out.append(Data(String(format: "%010d 00000 n \n", offset).utf8))
        }
        let trailer = "trailer\n<< /Size \(objects.count + 1) /Root 1 0 R "
            + "/Info \(objects.count) 0 R >>\nstartxref\n\(xref)\n%%EOF\n"
        out.append(Data(trailer.utf8))
        return out
    }

    // MARK: Office (built by python's zipfile — see the scratchpad script)

    static func docx() -> Data { Data(base64Encoded: docxBase64, options: [.ignoreUnknownCharacters])! }
    static func xlsx() -> Data { Data(base64Encoded: xlsxBase64, options: [.ignoreUnknownCharacters])! }

    // MARK: movies

    /// A real mp4 written by AVAssetWriter, carrying the QuickTime metadata
    /// §B1 names: make, model, software and an ISO-6709 location.
    /// `megabytes` drives the bitrate — `scrubbing a 50 MB video` (§B5) needs
    /// a 50 MB video.
    static func mp4(at url: URL, seconds: Int = 2, width: Int = 320, height: Int = 240,
                    megabytes: Int = 0, fileType: AVFileType = .mp4) async throws -> URL {
        try? FileManager.default.removeItem(at: url)
        let writer = try AVAssetWriter(outputURL: url, fileType: fileType)
        var settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
        ]
        if megabytes > 0 {
            settings[AVVideoCompressionPropertiesKey] = [
                AVVideoAverageBitRateKey: megabytes * 8 * 1_024 * 1_024 / max(1, seconds),
            ]
        }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
            ])
        writer.add(input)
        writer.metadata = movieMetadata()
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)

        let fps = 30
        let total = seconds * fps
        var pool: CVPixelBuffer?
        for frame in 0..<total {
            while !input.isReadyForMoreMediaData {
                try await Task.sleep(nanoseconds: 2_000_000)
            }
            CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA, nil, &pool)
            guard let buffer = pool else { continue }
            fill(buffer, frame: frame, noisy: megabytes > 0)
            adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(frame),
                                                                timescale: CMTimeScale(fps)))
        }
        input.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else {
            throw NSError(domain: "ScrubFixtures", code: 1,
                          userInfo: [NSLocalizedDescriptionKey:
                                        writer.error?.localizedDescription ?? "write failed"])
        }
        return url
    }

    static func movieMetadata() -> [AVMetadataItem] {
        var out: [AVMetadataItem] = []
        func item(_ identifier: AVMetadataIdentifier, _ value: String) {
            let m = AVMutableMetadataItem()
            m.identifier = identifier
            m.value = value as NSString
            m.extendedLanguageTag = "und"
            out.append(m)
        }
        item(.quickTimeMetadataMake, make)
        item(.quickTimeMetadataModel, model)
        item(.quickTimeMetadataSoftware, software)
        item(.quickTimeMetadataLocationISO6709, "+45.5017-073.5673/")
        item(.quickTimeMetadataAuthor, author)
        return out
    }

    private static func fill(_ buffer: CVPixelBuffer, frame: Int, noisy: Bool) {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return }
        let bytes = CVPixelBufferGetBytesPerRow(buffer) * CVPixelBufferGetHeight(buffer)
        let p = base.bindMemory(to: UInt8.self, capacity: bytes)
        if noisy {
            // incompressible content, so the requested bitrate is actually used.
            // One call, not one call per byte: a per-byte Swift RNG turns the
            // fixture into the slowest thing in the suite.
            arc4random_buf(p, bytes)
        } else {
            for i in 0..<bytes { p[i] = UInt8((i &+ frame) % 251) }
        }
    }

    // MARK: the Office blobs (python zipfile output, base64)

    static let docxBase64 = """
        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
        BQYAAAAABQAFADYBAACxBgAAAAA=
        """

    static let xlsxBase64 = """
        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
        """

    // MARK: helpers

    static func solid(width: Int, height: Int, color: UIColor, type: UTType) -> Data {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1                       // pixels, not points: the fixture's size is known
        let image = UIGraphicsImageRenderer(size: CGSize(width: width, height: height),
                                            format: format)
            .image { ctx in
                color.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
                UIColor.black.setFill()
                ctx.fill(CGRect(x: 2, y: 2, width: max(1, width / 4), height: max(1, height / 4)))
            }
        if type == UTType.png, let png = image.pngData() { return png }
        return image.jpegData(compressionQuality: 0.9) ?? Data()
    }
}
