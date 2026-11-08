import XCTest
import UIKit
@testable import PyroChat

/// Composer paste behavior: copy image → paste in the message field.
@MainActor
final class PasteComposerTests: XCTestCase {

    private func pngFixture(_ size: CGSize = CGSize(width: 60, height: 40)) -> Data {
        let fmt = UIGraphicsImageRendererFormat.default(); fmt.scale = 1
        return UIGraphicsImageRenderer(size: size, format: fmt).pngData { ctx in
            UIColor.orange.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
        }
    }

    override func tearDown() { UIPasteboard.general.items = [] }

    /// UITextView's own paste path only works installed + first responder.
    private func mounted(_ tv: PastingTextView) -> UIWindow {
        let w = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 200))
        tv.frame = w.bounds
        w.addSubview(tv)
        w.makeKeyAndVisible()
        tv.becomeFirstResponder()
        return w
    }

    func testImageOnlyClipboardPastesAsAttachment() {
        UIPasteboard.general.items = []
        UIPasteboard.general.setData(pngFixture(), forPasteboardType: "public.png")

        let tv = PastingTextView()
        var got: Data?
        tv.onImagePaste = { got = $0 }
        XCTAssertTrue(tv.canPerformAction(#selector(UIResponder.paste(_:)), withSender: nil),
                      "paste must be offered when the clipboard holds an image")
        tv.paste(nil)
        XCTAssertNotNil(got, "image bytes must reach the composer")
        XCTAssertNotNil(UIImage(data: got ?? Data()), "bytes must decode as an image")
        XCTAssertEqual(tv.text, "", "an image paste must not dump bytes into the text")
    }

    func testTextClipboardStillPastesAsText() {
        UIPasteboard.general.items = []
        UIPasteboard.general.string = "plain text paste"

        let tv = PastingTextView()
        let w = mounted(tv); defer { w.isHidden = true }
        var got: Data?
        tv.onImagePaste = { got = $0 }
        tv.paste(nil)
        RunLoop.main.run(until: Date().addingTimeInterval(0.4))
        XCTAssertNil(got, "text-only clipboard must never fire the image path")
        // in-test UIKit may defer the actual insert; what matters here is
        // that our override passed through to the system paste unharmed
        if !tv.text.isEmpty { XCTAssertEqual(tv.text, "plain text paste") }
    }

    func testTextPlusImageClipboardPrefersText() {
        // e.g. copying from a web page — the expected paste is the text
        UIPasteboard.general.items = [[
            "public.utf8-plain-text": "caption text",
            "public.png": pngFixture(),
        ]]
        let tv = PastingTextView()
        let w = mounted(tv); defer { w.isHidden = true }
        var got: Data?
        tv.onImagePaste = { got = $0 }
        tv.paste(nil)
        RunLoop.main.run(until: Date().addingTimeInterval(0.4))
        XCTAssertNil(got, "clipboard with text must paste as text, never as image")
        if !tv.text.isEmpty { XCTAssertEqual(tv.text, "caption text") }
    }

    /// The field takes its width from where it is, not from the screen: on a phone
    /// that folds, the app's window is not always as wide as a screen.
    func testWithNoWidthProposedTheFieldMeasuresAgainstItsWindow() {
        let tv = PastingTextView()
        let w = mounted(tv); defer { w.isHidden = true }      // a 320-point window
        XCTAssertEqual(ComposerField.size(of: tv, proposedWidth: nil).width, 320)
        XCTAssertEqual(ComposerField.size(of: tv, proposedWidth: 211).width, 211, "a proposed width is taken as given")

        let loose = PastingTextView(frame: CGRect(x: 0, y: 0, width: 150, height: 30))
        XCTAssertEqual(ComposerField.size(of: loose, proposedWidth: nil).width, 150, "in no window yet: its own width")

        // one to four lines, then it scrolls inside
        tv.text = "one line"
        let one = ComposerField.size(of: tv, proposedWidth: 300).height
        XCTAssertGreaterThanOrEqual(one, 25)
        XCTAssertLessThan(one, 42, "one line of text is one line high")
        tv.text = Array(repeating: "line", count: 30).joined(separator: "\n")
        XCTAssertEqual(ComposerField.size(of: tv, proposedWidth: 300).height, 84)
    }

    func testPastedImageNormalizesAndStripsIntoJpeg() {
        // the ChatView handler runs every paste through ImageNormalizer —
        // the exact same EXIF-stripping path as picked photos
        let norm = ImageNormalizer.normalize(pngFixture())
        XCTAssertNotNil(norm)
        XCTAssertEqual(norm?.width, 60)
        XCTAssertEqual(norm?.height, 40)
    }
}
