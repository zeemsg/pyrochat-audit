import XCTest
@testable import PyroChat

/// docs/BIOMETRIC-UNLOCK.md §0.
///
/// Some Info.plist declarations are not documentation: iOS terminates the
/// process the instant the matching API is used without them. Build 29 shipped
/// without `NSFaceIDUsageDescription` and crashed for every tester who turned
/// the biometric toggle on, because nothing else catches it —
/// `canEvaluatePolicy` (which decides whether the `[cfg]` row appears) does not
/// need the key, Touch ID does not need it, and the biometric unit tests inject
/// a fake backing so they never evaluate a policy at all.
///
/// `Bundle.main` here is the app under test, so these assert what actually
/// ships, not what `project.yml` says.
final class InfoPlistTests: XCTestCase {

    private func usageDescription(_ key: String) throws -> String {
        let value = try XCTUnwrap(
            Bundle.main.object(forInfoDictionaryKey: key) as? String,
            "\(key) is missing from the built app. iOS kills the process the "
            + "first time the matching API is used. Add it in ios/project.yml.")
        XCTAssertFalse(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                       "\(key) is present but empty, which App Review rejects.")
        return value
    }

    /// The one that crashed build 29.
    func testFaceIDUsageDescriptionIsDeclared() throws {
        let text = try usageDescription("NSFaceIDUsageDescription")
        // The string is shown by iOS in its own alert, so it has to say what we
        // do with the face — and, for this app, what we do NOT do with it.
        XCTAssertTrue(text.lowercased().contains("face id"))
        XCTAssertTrue(text.lowercased().contains("password"),
                      "the point of the sentence is that the password is not stored")
    }

    /// The other two hardware permissions the app asks for, same failure mode.
    func testCameraAndMicrophoneUsageDescriptionsAreDeclared() throws {
        _ = try usageDescription("NSCameraUsageDescription")
        _ = try usageDescription("NSMicrophoneUsageDescription")
    }

    /// Every key whose API the app calls. Extend this list, not a new test,
    /// when a feature starts touching another privacy-gated API.
    func testEveryPrivacyGatedAPIWeUseIsDeclared() throws {
        for key in ["NSFaceIDUsageDescription",
                    "NSCameraUsageDescription",
                    "NSMicrophoneUsageDescription"] {
            _ = try usageDescription(key)
        }
    }
}
