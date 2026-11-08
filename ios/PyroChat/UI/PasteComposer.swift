import SwiftUI
import UIKit

/// The message input, backed by UIKit so pasting an IMAGE works: a plain
/// SwiftUI TextField only ever accepts text, so "copy photo → long-press →
/// paste" showed no paste option at all. This subclass advertises paste
/// whenever the pasteboard holds an image and hands the raw bytes back to
/// the composer (which normalizes → strips EXIF → queues it for [TX]).
final class PastingTextView: UITextView {
    var onImagePaste: ((Data) -> Void)?

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(paste(_:)), UIPasteboard.general.hasImages { return true }
        return super.canPerformAction(action, withSender: sender)
    }

    override func paste(_ sender: Any?) {
        let pb = UIPasteboard.general
        // image-only clipboard → attachment; text present → normal text paste
        if pb.hasImages && !pb.hasStrings {
            for type in ["public.png", "public.jpeg", "public.heic", "com.compuserve.gif"] {
                if let d = pb.data(forPasteboardType: type), !d.isEmpty {
                    onImagePaste?(d); return
                }
            }
            if let img = pb.image, let d = img.pngData() {
                onImagePaste?(d); return
            }
        }
        super.paste(sender)
    }
}

struct ComposerField: UIViewRepresentable {
    @Binding var text: String
    @Binding var focused: Bool
    let settings: AppSettings
    var onImagePaste: (Data) -> Void

    func makeUIView(context: Context) -> PastingTextView {
        let tv = PastingTextView()
        tv.delegate = context.coordinator
        tv.onImagePaste = onImagePaste
        tv.font = UIFont.monospacedSystemFont(ofSize: 14, weight: .regular)
        tv.backgroundColor = .clear
        tv.keyboardAppearance = .dark
        tv.autocapitalizationType = .none
        tv.textContainerInset = UIEdgeInsets(top: 4, left: 0, bottom: 4, right: 0)
        tv.textContainer.lineFragmentPadding = 0
        tv.accessibilityLabel = "Message"
        return tv
    }

    func updateUIView(_ tv: PastingTextView, context: Context) {
        if tv.text != text { tv.text = text }
        tv.textColor = UIColor(Theme.fg(settings))
        tv.tintColor = UIColor(Theme.fg(settings))
        tv.onImagePaste = onImagePaste
        if focused, !tv.isFirstResponder {
            DispatchQueue.main.async { tv.becomeFirstResponder() }
        } else if !focused, tv.isFirstResponder {
            DispatchQueue.main.async { tv.resignFirstResponder() }
        }
    }

    // grow with content, 1–4 lines, then scroll inside
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: PastingTextView,
                      context: Context) -> CGSize? {
        Self.size(of: uiView, proposedWidth: proposal.width)
    }

    /// With no width proposed the field measures against its own window, never
    /// the screen: the two differ wherever the app does not fill the screen, and
    /// on a phone that folds the window changes size while the app runs.
    static func size(of field: UITextView, proposedWidth: CGFloat?) -> CGSize {
        let width = proposedWidth ?? field.window?.bounds.width ?? field.bounds.width
        let fit = field.sizeThatFits(CGSize(width: width,
                                            height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: min(max(fit.height, 25), 84))
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: ComposerField
        init(_ parent: ComposerField) { self.parent = parent }

        func textViewDidChange(_ tv: UITextView) { parent.text = tv.text }
        func textViewDidBeginEditing(_ tv: UITextView) { parent.focused = true }
        func textViewDidEndEditing(_ tv: UITextView) { parent.focused = false }
    }
}
