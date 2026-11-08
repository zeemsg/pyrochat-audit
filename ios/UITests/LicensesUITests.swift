import XCTest

/// `[cfg]` → ACCOUNT → licenses, the iOS half of the AGPL obligation in
/// `docs/APP_STORE.md` §"AGPL obligations".
///
/// Worth a UI test rather than a unit test because the failure mode is
/// specifically a *packaging* one: `LicensesView` reads two files out of the
/// app bundle, and if xcodegen ever stops copying `PyroChat/Licenses/` the
/// screen still builds, still compiles and still opens — it just quietly
/// renders "(… is missing from this build)" where the notices should be. A
/// build that ships that is a build that distributes libsignal with its
/// attribution silently dropped, which is the exact thing the screen exists
/// to prevent. Nothing short of opening it and reading the text catches that.
final class LicensesUITests: XCTestCase {

    private func openLicenses() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=settings", "--demo-noshield"]
        app.launch()
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'PROTECTION'"))
            .firstMatch.waitForExistence(timeout: 90), "never reached [cfg]")

        // ACCOUNT sits well below the fold on every device.
        let row = app.buttons["Open source licenses"]
        for _ in 0..<12 where !row.exists {
            app.swipeUp()
        }
        XCTAssertTrue(row.waitForExistence(timeout: 10),
                      "the licenses row is not reachable in [cfg]")
        row.tap()
        return app
    }

    /// The two attributions that are not optional: PyroChat's own AGPL line
    /// and Signal's copyright. Both are required by the licence; neither is
    /// something a future edit should be free to quietly drop.
    func testShowsAgplAndSignalCopyright() {
        let app = openLicenses()
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'Signal Messenger'"))
            .firstMatch.waitForExistence(timeout: 15),
            "libsignal's copyright notice is missing from the licenses screen")
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'Affero'"))
            .firstMatch.exists,
            "the AGPL notice is missing from the licenses screen")
    }

    /// The packaging check. Opening the disclosure must produce libsignal's
    /// notices, NOT the "missing from this build" fallback.
    func testThirdPartyNoticesAreActuallyBundled() {
        let app = openLicenses()

        let show = app.buttons["libsignal third-party notices"]
        for _ in 0..<8 where !show.exists {
            app.swipeUp()
        }
        XCTAssertTrue(show.waitForExistence(timeout: 10),
                      "no disclosure for the third-party notices")
        show.tap()

        let missing = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'missing from this build'")).firstMatch
        XCTAssertFalse(missing.waitForExistence(timeout: 3),
            "libsignal-acknowledgments.txt did not ship in the bundle — "
            + "run deploy/refresh-licenses.sh and check xcodegen copied "
            + "PyroChat/Licenses/")

        // Something from the notices themselves, to prove the file was read
        // and not merely present: the generated header names the pod version.
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'LibSignalClient'"))
            .firstMatch.waitForExistence(timeout: 10),
            "the notices rendered empty")
    }

    /// AGPLv3 §4 asks that recipients get a copy of the licence *with* the
    /// program. A link to gnu.org is a promise about someone else's server;
    /// the text itself ships in the bundle, and this proves it opens.
    func testLicenceTextIsBundled() {
        let app = openLicenses()

        let show = app.buttons["Full AGPL version 3 text"]
        for _ in 0..<10 where !show.exists {
            app.swipeUp()
        }
        XCTAssertTrue(show.waitForExistence(timeout: 10),
                      "no disclosure for the licence text")
        show.tap()

        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'GNU AFFERO GENERAL PUBLIC LICENSE'"))
            .firstMatch.waitForExistence(timeout: 10),
            "AGPL-3.0.txt did not ship in the bundle")
    }
}
