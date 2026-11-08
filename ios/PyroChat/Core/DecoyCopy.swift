import Foundation

/// docs/DECOY-ACCOUNT.md §3 — every word the decoy feature puts on screen, in
/// one place, so both platforms can be held to the same text.
///
/// The hard rule that shapes all of it: **the row reads the same whether or
/// not a decoy exists.** No checkmark on the row, no "configured", no colour
/// change, no disabled state. A row that renders differently once used is a
/// disclosure, and it is the only disclosure this feature could make that the
/// person holding your phone would not need a lab to read.
enum DecoyCopy {
    /// The `[cfg]` SAFETY row.
    static let row = "set decoy password \u{2026}"

    /// §3: "The guidance in the app is one line, and it is the honest advice:
    /// **use it.**" §6.2 supplies the other half of the same sentence —
    /// attachment volume is an argument an examiner can still make, and the
    /// app says so rather than letting someone find out the hard way.
    static let footnote =
        "a second password that opens a different, working account. "
        + "message someone from it now and then: an account with three messages from four "
        + "months ago is a prop, not a decoy, and two hundred attachments behind six "
        + "messages invites the next question."

    /// §3's ONE failure message, for every reason this can fail.
    ///
    /// One string, on purpose. §1 concedes that a decoy's attempt must fail
    /// where the first account's succeeds — "this is the one place it cannot"
    /// behave identically — and the trade is acceptable because the person
    /// holding your phone "sees exactly one session and has nothing to compare
    /// it against". That only holds if the failure says nothing about WHY. A
    /// message distinguishing "no free slot" from "bad username" would hand
    /// the observer exactly the comparison the argument depends on not having.
    static let failed = "\u{2717} could not set decoy password"

    /// Transient, and on the screen only. §3 forbids a mark on the ROW; this
    /// is the ordinary confirmation any action in `[cfg]` gets, and it is gone
    /// the moment the screen is left.
    static let created = "\u{2713} decoy password set"

    // --- the dialog ---
    /// Shown the instant SET is pressed, because the work behind it registers
    /// the decoy on the relay and can take seconds. Without it the dialog
    /// closes onto a screen that looks untouched, which reads as "nothing
    /// happened" — and iOS's own "Save Password?" sheet lands on top of
    /// exactly the spot the outcome line occupies, so even the outcome can be
    /// hidden. Neither is something the user should have to guess at.
    static let working = "\u{2026} setting decoy password"

    /// The outcome is reported in its own dialog rather than a dim line under
    /// a five-line footnote. Same two strings, somewhere they cannot be missed
    /// or covered. §3's invariants are untouched: the ROW never changes, and
    /// failure is still one string for every reason.
    static let resultTitle = "DECOY PASSWORD"
    static let resultDismiss = "OK"

    /// §3 says failure is ONE string for every reason, and that rule earns its
    /// keep only in the case it was written for: an account with no room,
    /// which is what a decoy always is. Making EVERY failure identical also
    /// made every failure undiagnosable — a real user with a valid setup hit
    /// "could not set decoy password" and had no way to learn that their
    /// password was seven characters long.
    ///
    /// So the vague string is reserved for the one case that must stay vague,
    /// and everything past it speaks plainly. That is safe because the checks
    /// below only run once the account has demonstrably shown it owns the
    /// container, and anyone who compelled THAT account already has
    /// everything a decoy could have hidden.
    static let badUsername =
        "\u{2717} username must be 3-24 letters, numbers or underscores"
    static let shortPassword = "\u{2717} decoy password must be at least 8 characters"
    static let samePassword = "\u{2717} that is your real password — the decoy needs a different one"
    static let usernameTaken = "\u{2717} that username is already taken"
    static let relayUnreachable = "\u{2717} could not reach the relay — check your connection"
    static let storeFailed = "\u{2717} could not write the decoy to this device"

    static let title = "DECOY PASSWORD"
    static let action = "SET"
    static let usernameField = "username"
    static let passwordField = "password"
    static let explainer =
        "creates a second, real account on this device, opened by this password instead of "
        + "yours. it has its own username, contacts and history, and nothing in the app says "
        + "it exists. the password must be different from the one you use."

    /// §3: "Replacing may say so plainly once the dialog is open, because only
    /// the account that owns the decoy can reach it, and anyone who has
    /// compelled that account has already won."
    ///
    /// The ROW is identical everywhere and always — that invariant is
    /// untouched. It is only what happens after the tap that may differ, and
    /// only for the one account that could already destroy the decoy anyway.
    /// Without this the row would be a setting-shaped trap: a mistyped decoy
    /// password with no way back except wiping the device.
    static let replaceExplainer =
        "replaces the decoy already on this device with a new one: a new username, a new "
        + "password, and none of its messages. the old one is gone and cannot be recovered. "
        + "the password must be different from the one you use."

    static func explainer(replacing: Bool) -> String {
        replacing ? replaceExplainer : explainer
    }

    /// The same two words whichever it was. §3's row reads the same, and so
    /// does the line under it.
    static let replaced = created
}

/// docs/DECOY-ACCOUNT.md §1a, "A store that still will not fit: never trim
/// without being asked".
///
/// Both lines below tell the user what happened and offer them nothing
/// destructive. There is no "trim to fit" button here on purpose: making
/// migration always succeed by deleting the oldest messages was the tempting
/// answer and it is the wrong one — "this app's whole promise is that messages
/// exist on two phones and nowhere else; deleting some of them to enable a
/// feature the user has not asked for spends the thing the product is for."
/// Surfacing the choice (export a backup, then trim, with the user pressing
/// the button) is later interface work. Until then the condition is reported
/// and nothing is destroyed.
enum StoreFullCopy {
    /// The §1a condition proper. Nothing is wrong with this device: it is a
    /// heavy install that stays in the old format, and "a user who is never
    /// offered the decoy has lost nothing". So the line says what is true and
    /// does not ask for anything.
    static let cannotMigrate =
        "this device holds more text than the new store format can take. "
        + "nothing has been changed or deleted and everything keeps working."

    /// The genuinely bad one: a change is in memory and not on disk. It says
    /// what would fix it without doing any of it.
    static let cannotSave =
        "this store is full — the last change could not be saved. "
        + "nothing has been deleted. clearing some older threads will let it save again."

    static func line(for reason: SecureStore.FullReason) -> String {
        switch reason {
        case .cannotMigrate: return cannotMigrate
        case .cannotSave: return cannotSave
        }
    }
}

/// docs/DECOY-ACCOUNT.md §1, "Creating an identity from cold, which the rules
/// above would otherwise forbid", and §5, "The first screen stops claiming to
/// know".
///
/// The unifying rule for everything here: **nothing on the entry screen may
/// depend on what this device holds.** Not the copy, not the routing, not
/// which actions are offered, and not whether a warning appears. A warning
/// that showed up only when something was there would answer the question the
/// whole design exists to leave unanswered.
enum AuthCopy {
    /// The one line at the top of the app. True on a phone that has never held
    /// an account, on one holding two, and on one wiped ten minutes ago.
    static let root = "> unlock this device, or set up a new identity on it."

    /// §1, verbatim. Shown EVERY time the create button is pressed, on every
    /// device, whatever is on it. Unconditional is what makes it safe to show:
    /// one that always appears carries no information at all, and happens to
    /// be true.
    ///
    /// It is a STATEMENT, not a probability, because a cold create rewrites the
    /// whole container rather than gambling on one slot. That is also what
    /// leaves the device in a fully known state afterwards, where a decoy
    /// simply works — the take-one-at-random version could create an account on
    /// a wiped phone but never a decoy.
    static let coldCreateWarning =
        "creating an identity here erases anything else stored on this device."

    /// §1: "Alongside it, the plain advice: if you already have an identity on
    /// this device, sign in first. From inside an account the app knows which
    /// slots are free and nothing is risked."
    static let coldCreateAdvice =
        "if you already have an identity on this device, sign in first — from inside an "
        + "account nothing is at risk."

    /// iOS raises "Use Strong Password?" on any secure field it decides is a
    /// new-password field, and its own text says the password "will be saved
    /// in Passwords". The app cannot decline on the user's behalf without
    /// claiming the field is something it is not — three honest attempts were
    /// measured and all three still get the sheet.
    ///
    /// A copy there is outside everything this app promises: it syncs to
    /// iCloud, so the password does leave the device; and it survives the
    /// duress PIN, ten wrong passwords, the dead-man switch and
    /// `[ BURN EVERYTHING ]`, so a password the user believes they destroyed
    /// is still sitting somewhere. Better to say so than to let someone find
    /// out after they needed it not to be true.
    static let passwordManagerWarning =
        "if iphone offers to save this password, decline. a copy in the phone's password "
        + "manager syncs to icloud and survives every wipe in this app, including the duress pin."

    static let coldCreateTitle = "ERASE AND CREATE"
    static let coldCreateConfirm = "CONTINUE"
    static let restoreTitle = "ERASE AND RESTORE"

    // Android's `ERR_IDENTITY_EXISTS` has no counterpart here, and needs none:
    // §5 deleted the two places iOS said it ("> this device already has an
    // identity." on the create form, "> nothing to sign in to." on the sign-in
    // form), and §1's cold create can never run out of room because it takes a
    // slot at random rather than looking for a free one. What is left reports
    // what happened — a relay refusal, a disk error — and nothing about what
    // is on the device.
}
