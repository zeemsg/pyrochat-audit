import SwiftUI
import CoreImage
import UIKit

/// SAFETY NUMBER screen: 60-digit numeric fingerprint in 12 five-digit
/// groups + QR of the libsignal scannable encoding. Pure local computation
/// — nothing on this screen touches the relay.
struct VerifyView: View {
    @ObservedObject var service: ChatService
    let contactHash: String
    /// Label when the hash is not a contact (a group member): the roster name.
    var displayName: String? = nil
    @Environment(\.dismiss) private var dismiss

    private var settings: AppSettings { service.store.blob.settings }
    private var contact: Contact? { service.contacts.first { $0.hash == contactHash } }

    var body: some View {
        let name = contact?.name ?? displayName ?? "peer"
        let sn = service.safetyNumber(withHash: contactHash)
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("SAFETY NUMBER").font(Theme.mono(16, weight: .bold))
                    Spacer()
                    Button { dismiss() } label: {
                        Text("[X]").font(Theme.mono(13, weight: .bold))
                            .foregroundStyle(Theme.fgDim(settings))
                    }
                    .accessibilityLabel("Close")
                }
                Text("<\(name)>")
                    .font(Theme.mono(14, weight: .semibold))
                    .foregroundStyle(Theme.flame)

                if let sn {
                    digitBlock(sn.digits)
                    qrBlock(sn.scannable)
                    Text("every pyrochat channel is end-to-end encrypted. these 60 digits are derived from your identity key and <\(name)>'s — nothing else. compare them with <\(name)> over a channel you already trust (in person, a call). if every digit matches, no relay or middleman is between you.")
                        .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
                    Text("the qr encodes the machine-readable fingerprint for future scan-verify. the two devices show DIFFERENT qr codes by design — compare the digits, not the squares.")
                        .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                    if contact != nil {
                        verifyControls
                    } else {
                        Text("<\(name)> is not a contact — open a channel with them to mark the number verified.")
                            .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                    }
                } else {
                    Text("no identity key on record for <\(name)> yet. exchange one message first, then verify.")
                        .font(Theme.mono(12)).foregroundStyle(Theme.alert)
                }
            }
            .padding(16)
        }
        .terminalScreen(settings)
    }

    @ViewBuilder
    private var verifyControls: some View {
        if contact?.verified == true {
            HStack(spacing: 12) {
                Text("STATUS: VERIFIED \u{2713}")
                    .font(Theme.mono(13, weight: .bold))
                Button { service.setVerified(false, for: contactHash) } label: {
                    Text("[UNVERIFY]").font(Theme.mono(12))
                        .foregroundStyle(Theme.fgDim(settings))
                }
                .accessibilityLabel("Remove verification")
            }
        } else {
            Button { service.setVerified(true, for: contactHash) } label: {
                Text("[MARK VERIFIED]").font(Theme.mono(14, weight: .bold))
            }
            .accessibilityLabel("Mark safety number verified")
        }
    }

    /// 12 groups of 5 digits, 3 groups per line — terminal style. The block
    /// assumes a physical width (docs/TEXT-SIZE.md §3), so it is drawn with
    /// `Theme.monoCapped`: it scales, but stops at `large`.
    private func digitBlock(_ digits: String) -> some View {
        let chars = Array(digits)
        let groups = stride(from: 0, to: chars.count, by: 5)
            .map { String(chars[$0..<min($0 + 5, chars.count)]) }
        let lines = stride(from: 0, to: groups.count, by: 3)
            .map { groups[$0..<min($0 + 3, groups.count)].joined(separator: "  ") }
        return VStack(spacing: 6) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line).font(Theme.monoCapped(18, weight: .bold)).kerning(1)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .overlay(RoundedRectangle(cornerRadius: 2)
            .stroke(Theme.fg(settings).opacity(0.35), lineWidth: 1))
    }

    @ViewBuilder
    private func qrBlock(_ data: Data) -> some View {
        if let qr = QRCode.image(for: data) {
            Image(uiImage: qr)
                .interpolation(.none)      // crisp modules, no smoothing
                .resizable()
                .scaledToFit()
                .frame(width: 180, height: 180)
                .padding(8)
                .background(Color.white)   // quiet zone — QR needs light ground
                .frame(maxWidth: .infinity)
                .accessibilityLabel("Safety number QR code")
        }
    }
}

/// CoreImage QR generation. CIQRCodeGenerator encodes arbitrary Data in
/// byte mode, so the raw scannable protobuf goes in unmodified.
enum QRCode {
    static func image(for data: Data, scale: CGFloat = 8) -> UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let ci = filter.outputImage else { return nil }
        let scaled = ci.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
