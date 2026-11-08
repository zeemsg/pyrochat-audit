import Foundation
import XCTest

/// Deletes the `pyro-*` scratch directories the suite leaves in the app
/// container's `tmp/`.
///
/// Roughly thirty test files build a working directory as
/// `temporaryDirectory/pyro-<tag>-<UUID>` and almost none of them delete it
/// afterwards. Nothing reaps `tmp/` in a simulator either, so the litter is
/// permanent: by the time anyone looked, this simulator held **10,626** of
/// them and the one app container had reached **98 GB** — enough that the Mac
/// ran out of disk and `codesign` started failing with "internal error in Code
/// Signing subsystem", which looks nothing like a full disk.
///
/// Every `SecureStore` in a test writes the decoy container, and that file is
/// a fixed 12,582,925 bytes whether or not the test cares about decoys
/// (`docs/DECOY-ACCOUNT.md`: the container exists from first run and is never
/// smaller). So each run costs ~12 MB per store it opens. The arithmetic is
/// the whole bug — 8,440 stores, 98 GB.
///
/// Fixed here rather than in thirty `tearDown`s: one sweep catches the files
/// tests forget AND the ones a crashed or cancelled run never reached a
/// teardown for, which is most of them. Registered as the test bundle's
/// `NSPrincipalClass` (`INFOPLIST_KEY_NSPrincipalClass` in `ios/project.yml`),
/// which XCTest instantiates before the first test.
@objc(TempDirJanitor)
final class TempDirJanitor: NSObject, XCTestObservation {
    /// Only directories matching this are ever removed. A test that wants a
    /// scratch directory the janitor cleans up must use this prefix — which
    /// every one of them already does.
    private static let prefix = "pyro-"

    override init() {
        super.init()
        XCTestObservationCenter.shared.addTestObserver(self)
        // Before the first test, not only after the last one: a run killed by
        // a crash, a timeout or ⌃C never reaches `testBundleDidFinish`, and
        // those runs are exactly the ones that leaked the most.
        Self.sweep(reason: "start")
    }

    func testBundleDidFinish(_ testBundle: Bundle) {
        Self.sweep(reason: "finish")
    }

    /// Removes every `pyro-*` entry directly inside the temporary directory.
    /// Deliberately not recursive beyond that: one `removeItem` per scratch
    /// directory, and nothing outside the prefix is examined at all.
    static func sweep(reason: String) {
        let fm = FileManager.default
        let tmp = fm.temporaryDirectory
        guard let entries = try? fm.contentsOfDirectory(
            at: tmp, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]
        ) else { return }

        var removed = 0
        var bytes: Int64 = 0
        for url in entries where url.lastPathComponent.hasPrefix(prefix) {
            // Size first: once it is gone there is nothing left to measure,
            // and the number is the only way anyone notices this regressing.
            bytes += Self.size(of: url, fm: fm)
            if (try? fm.removeItem(at: url)) != nil { removed += 1 }
        }
        if removed > 0 {
            let mb = Double(bytes) / 1_048_576
            print("[TempDirJanitor] \(reason): removed \(removed) scratch "
                  + "director\(removed == 1 ? "y" : "ies"), "
                  + String(format: "%.1f", mb) + " MB")
        }
    }

    /// Allocated size of a directory tree. Best-effort: a file that vanishes
    /// mid-walk contributes nothing rather than throwing, because this runs
    /// only to produce a log line.
    private static func size(of url: URL, fm: FileManager) -> Int64 {
        guard let walker = fm.enumerator(
            at: url, includingPropertiesForKeys: [.totalFileAllocatedSizeKey, .fileAllocatedSizeKey]
        ) else { return 0 }
        var total: Int64 = 0
        for case let child as URL in walker {
            let values = try? child.resourceValues(
                forKeys: [.totalFileAllocatedSizeKey, .fileAllocatedSizeKey])
            if let n = values?.totalFileAllocatedSize ?? values?.fileAllocatedSize {
                total += Int64(n)
            }
        }
        return total
    }
}
