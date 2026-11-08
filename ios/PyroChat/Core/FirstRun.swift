import Foundation

// docs/FIRST-RUN.md — the words here are verbatim on both platforms.
// Everything in this file is pure (no UI, no store, no network) so the
// rules are unit-testable and identical to the Android port.

/// §1 + §2: the demo peer, the home empty state and the [share] text.
enum FirstRun {
    /// The contact added right after CREATE IDENTITY (best effort, never
    /// retried) and the only name that gets the demo subtitle.
    static let demoPeer = "pyrobot"

    /// Home empty state (no contacts, no groups) — three lines.
    static let emptyStateLines = [
        "no channels yet.",
        "press [+] and add pyrobot for a 60-second tour,",
        "or [share] your username to bring a friend.",
    ]

    /// Row subtitle for the pyrobot thread while it has no messages.
    static let demoPeerSubtitle = "demo peer \u{2014} say hi for a 60-second tour"
    /// Row subtitle for any other contact whose thread has no messages.
    static let emptyThreadSubtitle = "channel open \u{2014} no traffic"

    /// Shown (then the key is dropped) when a key arrives with no identity.
    static let createIdentityFirst = "! create your identity first, then scan the key again"

    static func showsEmptyState(contacts: Int, groups: Int) -> Bool {
        contacts == 0 && groups == 0
    }

    /// Subtitle for a contact row whose thread has no messages: only the
    /// contact named exactly `pyrobot` gets the tour hint.
    static func emptySubtitle(forContactNamed name: String) -> String {
        name == demoPeer ? demoPeerSubtitle : emptyThreadSubtitle
    }

    /// Row subtitle: the last message's preview when there is one, else the
    /// empty-thread rule above.
    static func subtitle(forContactNamed name: String, lastPreview: String?) -> String {
        lastPreview ?? emptySubtitle(forContactNamed: name)
    }

    /// §2: the system share sheet text. `addCode` is my `pyro-add:v1:…`
    /// code exactly as the [qr] screen encodes it.
    static func shareText(username: String, addCode: String) -> String {
        [
            "add me on PyroChat \u{2014} my username is \(username)",
            "in the app: [qr] \u{2192} paste this code \u{2192} \(addCode)",
            "get the app: https://pyrochat.ca",
        ].joined(separator: "\n")
    }
}

#if ACCESS_KEY_UI   // ios/project.yml: Debug only until access keys unlock something again
/// §3: an access key `PYRO-XXXX-XXXX-XXXX-XXXX` carried as the payload
/// `pyro-key:v1:<CODE>` or the deep link `pyrochat://redeem/<CODE>`.
enum AccessKeyLink {
    enum Parse: Equatable {
        /// A well-formed key, normalised (upper-case, `A–Z0–9-` only).
        case key(String)
        /// Looked like a key (right prefix) but the code has the wrong shape
        /// — refuse with `refusal`.
        case malformed
        /// Not an access key at all (a `pyro-add` contact code, a desktop
        /// link, free text): let the other parsers have it.
        case none
    }

    static let refusal = "! not an access key"

    static let payloadPrefix = "pyro-key:v1:"
    static let deepLinkPrefix = "pyrochat://redeem/"

    static func parse(_ raw: String) -> Parse {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        let body: String
        if lower.hasPrefix(payloadPrefix) {
            body = String(trimmed.dropFirst(payloadPrefix.count))
        } else if lower.hasPrefix(deepLinkPrefix) {
            body = String(trimmed.dropFirst(deepLinkPrefix.count))
        } else if lower.hasPrefix("pyro-key:") || lower.hasPrefix("pyrochat:") {
            // our prefix, wrong version / host / no code
            return .malformed
        } else {
            return .none
        }
        let code = normalise(body)
        return isWellFormed(code) ? .key(code) : .malformed
    }

    /// Same normalisation as the redeem endpoint: trim, upper-case, keep
    /// only `A–Z`, `0–9` and `-`.
    static func normalise(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
            .filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
    }

    /// `PYRO(-[0-9A-Z]{4}){4}` on an already-normalised string.
    static func isWellFormed(_ code: String) -> Bool {
        let parts = code.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 5, parts[0] == "PYRO" else { return false }
        return parts.dropFirst().allSatisfy { p in
            p.count == 4 && p.allSatisfy { $0.isASCII && (("A"..."Z").contains($0) || ("0"..."9").contains($0)) }
        }
    }

    static func deepLink(for code: String) -> String { deepLinkPrefix + code }
    static func payload(for code: String) -> String { payloadPrefix + code }
}

#endif

/// A `pyro-add:v1:<user>:<b64url(identityKey)>` contact code (the [qr]
/// screen's own payload). Unchanged by §3 — a key never parses as a contact
/// and a contact never parses as a key.
enum ContactCode {
    enum Parse: Equatable {
        case contact(username: String, identityKey: Data)
        /// `pyro-add:` prefix but unreadable
        case malformed
        case none
    }

    static let prefix = "pyro-add:"

    /// Accepts the bare code and also the whole [share] message pasted as
    /// one string (the token runs from `pyro-add:` to the next whitespace).
    static func parse(_ raw: String) -> Parse {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let start = trimmed.range(of: prefix)?.lowerBound else { return .none }
        let token = trimmed[start...].prefix { !$0.isWhitespace }
        let parts = token.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, parts[0] == "pyro-add", parts[1] == "v1",
              !parts[2].isEmpty, let key = Data(base64urlNoPad: parts[3]), !key.isEmpty else {
            return .malformed
        }
        return .contact(username: parts[2], identityKey: key)
    }
}

#if ACCESS_KEY_UI
/// §3 "behaviour on receiving a key": applied at once when unlocked, kept in
/// memory (never on disk, never logged) while locked / on the auth screen and
/// applied right after unlock, dropped with a message when there is no
/// identity to apply it to. Pure and injectable — `AppModel` owns one.
struct PendingAccessKey: Equatable {
    enum Stage: Equatable {
        /// identity unlocked, the ACCESS KEY dialog can open now
        case unlocked
        /// an identity exists but the store is locked (boot / unlock prompt)
        case locked
        /// no identity on this device (fresh install, after a wipe)
        case noIdentity
    }

    enum Action: Equatable {
        /// open the ACCESS KEY dialog with this code and submit it
        case apply(String)
        /// kept in memory until unlock
        case held
        /// dropped; show this message
        case dropped(String)
        /// a held key silently forgotten (identity vanished before unlock)
        case discarded
        case none
    }

    private(set) var held: String?

    /// A key arrived (scanner, paste field, OS deep link) while at `stage`.
    mutating func receive(_ code: String, at stage: Stage) -> Action {
        switch stage {
        case .unlocked:
            held = nil
            return .apply(code)
        case .locked:
            held = code                      // latest wins
            return .held
        case .noIdentity:
            held = nil
            return .dropped(FirstRun.createIdentityFirst)
        }
    }

    /// The app moved to `stage` (unlock, lock, wipe).
    mutating func stageChanged(to stage: Stage) -> Action {
        guard let code = held else { return .none }
        switch stage {
        case .unlocked:
            held = nil
            return .apply(code)
        case .locked:
            return .none                     // still waiting for the password
        case .noIdentity:
            held = nil                       // wiped while waiting: nothing to apply to
            return .discarded
        }
    }
}
#endif
