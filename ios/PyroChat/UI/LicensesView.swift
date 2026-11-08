import SwiftUI

/// `[cfg]` → ACCOUNT → licenses. The iOS half of the AGPL obligation in
/// `docs/APP_STORE.md` §"AGPL obligations": Signal's copyright and AGPL notice
/// for libsignal, PyroChat's own AGPLv3 line, libsignal's 170 third-party
/// acknowledgments, and the verbatim licence text.
///
/// Android has had `LicensesScreen.kt` since versionCode 13; iOS shipped 36
/// builds with no licences screen at all, which is the part of the obligation
/// that was actually missing rather than merely unpublished.
///
/// Two differences from Android, both deliberate:
///  - no OFL section. Android bundles IBM Plex Mono; iOS draws every glyph
///    with `.system(design: .monospaced)`, so there is no bundled face to
///    attribute and claiming one would be false.
///  - the AGPL text ships in the bundle instead of only linking gnu.org.
///    §4 says to give recipients a copy of the licence *along with* the
///    program, and a URL is not a copy — it is a promise that someone else
///    will keep hosting one.
///
/// Both files live in `PyroChat/Licenses/` and are picked up by xcodegen's
/// `sources: - PyroChat`. They are copies: `ios/Pods` is gitignored, so the
/// pod's own files cannot be referenced from a clean checkout. Refresh them
/// with `deploy/refresh-licenses.sh` whenever libsignal is upgraded.
struct LicensesView: View {
    let settings: AppSettings

    @State private var showAcknowledgments = false
    @State private var showLicenseText = false

    /// Loaded only when the disclosure opens — the acknowledgments are 7,488
    /// lines and the licence another 600, and neither belongs in the memory
    /// footprint of a screen most people open to read four lines at the top.
    @State private var acknowledgments: [String] = []
    @State private var licenseText: [String] = []

    private static let acknowledgmentsFile = "libsignal-acknowledgments"
    private static let licenseFile = "AGPL-3.0"

    private var appVersion: String {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(short) (\(build))"
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                header("pyrochat")
                para("""
                PyroChat for iOS \(appVersion)
                Copyright © 2026 PyroChat
                Licensed under the GNU Affero General Public License, version 3 (AGPLv3). \
                The complete corresponding source for this exact build is available on \
                request from legal@pyrochat.ca, free of charge.
                """)
                dim("""
                This program is free software: you can redistribute it and/or modify it under \
                the terms of the GNU Affero General Public License as published by the Free \
                Software Foundation, either version 3 of the License, or (at your option) any \
                later version. It is distributed in the hope that it will be useful, but \
                WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or \
                FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License \
                for more details.
                """)

                header("libsignal")
                para("""
                libsignal
                Copyright 2020-2026 Signal Messenger, LLC
                Licensed under the GNU AGPLv3
                """)
                dim("""
                the Signal Protocol implementation (X3DH, PQXDH, Double Ratchet) that encrypts \
                every message. PyroChat is not affiliated with or endorsed by Signal \
                Messenger, LLC.
                """)

                disclosure(
                    isOpen: showAcknowledgments,
                    openLabel: "[ SHOW LIBSIGNAL THIRD-PARTY NOTICES \u{2026} ]",
                    closeLabel: "[ HIDE THIRD-PARTY NOTICES ]",
                    accessibility: "libsignal third-party notices"
                ) {
                    showAcknowledgments.toggle()
                    if showAcknowledgments && acknowledgments.isEmpty {
                        acknowledgments = Self.lines(Self.acknowledgmentsFile)
                    }
                }
                if showAcknowledgments { fileBody(acknowledgments, name: Self.acknowledgmentsFile) }

                header("the licence")
                disclosure(
                    isOpen: showLicenseText,
                    openLabel: "[ SHOW THE FULL AGPLv3 TEXT \u{2026} ]",
                    closeLabel: "[ HIDE THE LICENCE TEXT ]",
                    accessibility: "Full AGPL version 3 text"
                ) {
                    showLicenseText.toggle()
                    if showLicenseText && licenseText.isEmpty {
                        licenseText = Self.lines(Self.licenseFile)
                    }
                }
                if showLicenseText { fileBody(licenseText, name: Self.licenseFile) }

                Spacer().frame(height: 32)
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .terminalScreen(settings)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.bg, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("licenses")
                    .font(Theme.monoCapped(14, weight: .semibold))
                    .foregroundStyle(Theme.fg(settings))
            }
        }
    }

    // MARK: - pieces

    private func header(_ title: String) -> some View {
        Text("\u{2500}\u{2500} \(title) \u{2500}\u{2500}")
            .font(Theme.mono(11))
            .foregroundStyle(Theme.fgDim(settings))
            .padding(.top, 18)
            .padding(.bottom, 6)
    }

    private func para(_ text: String) -> some View {
        Text(text)
            .font(Theme.mono(12))
            .foregroundStyle(Theme.fg(settings))
            .padding(.vertical, 4)
    }

    private func dim(_ text: String) -> some View {
        Text(text)
            .font(Theme.mono(11))
            .foregroundStyle(Theme.fgDim(settings))
            .padding(.vertical, 4)
    }

    private func disclosure(
        isOpen: Bool, openLabel: String, closeLabel: String,
        accessibility: String, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(isOpen ? closeLabel : openLabel)
                .font(Theme.mono(12, weight: .bold))
                .foregroundStyle(Theme.flame)
        }
        .accessibilityLabel(accessibility)
        .padding(.vertical, 8)
    }

    /// One `Text` per line, 9 pt and dim — the same treatment Android gives
    /// these two files. `id:` is the index: licence text repeats lines
    /// (blank ones especially), so the line itself is not a stable identity.
    @ViewBuilder
    private func fileBody(_ lines: [String], name: String) -> some View {
        if lines.isEmpty {
            dim("(\(name) is missing from this build)")
        } else {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(Theme.mono(9))
                    .foregroundStyle(Theme.fgDim(settings))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
        }
    }

    /// Reads a bundled `.txt`. Returns `[]` when absent so the screen says so
    /// rather than rendering an empty disclosure that looks like it worked.
    static func lines(_ name: String) -> [String] {
        guard let url = Bundle.main.url(forResource: name, withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8)
        else { return [] }
        return text.components(separatedBy: "\n")
    }
}
