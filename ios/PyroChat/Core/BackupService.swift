import Foundation
import CryptoKit
import CommonCrypto

/// Encrypted device-migration backups.
///
/// FORMAT (PYROBAK1):  magic(8) ‖ salt(32) ‖ AES-256-GCM(combined)
/// key = PBKDF2-HMAC-SHA256(passphrase, salt, 600k) — same strength as the
/// store itself. The sealed payload carries the ENTIRE identity: keys,
/// sessions, contacts, messages, notes, settings, and attachment bytes.
///
/// The file is exactly as sensitive as an unlocked phone — protected only by
/// the passphrase chosen at export. Restore implies MOVING to the new
/// device: running the same identity on two devices forks the ratchet
/// (auto-heal repairs it, but messages in flight are lost).
enum BackupService {
    static let magic = Data("PYROBAK1".utf8)

    enum BackupError: LocalizedError {
        case badFile, badPassphrase
        var errorDescription: String? {
            switch self {
            case .badFile: return "not a pyrochat backup file"
            case .badPassphrase: return "wrong backup passphrase"
            }
        }
    }

    private struct Payload: Codable {
        var blob: SecureStore.Blob
        var attachments: [String: Data]
        var exportedAt: Date
    }

    // MARK: export

    static func export(from store: SecureStore, passphrase: String) throws -> Data {
        var attachments: [String: Data] = [:]
        if let ids = try? FileManager.default.contentsOfDirectory(
            atPath: store.attachmentsDir.path) {
            for id in ids where UUID(uuidString: id) != nil {
                if let bytes = store.readAttachment(id: id) {
                    attachments[id] = bytes
                }
            }
        }
        let payload = Payload(blob: store.blob, attachments: attachments,
                              exportedAt: Date())
        let plain = try JSONEncoder().encode(payload)

        var salt = Data(count: 32)
        _ = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        let key = SecureStore.deriveKey(password: passphrase, salt: salt)
        guard let sealed = try? AES.GCM.seal(plain, using: key).combined else {
            throw BackupError.badFile
        }
        return magic + salt + sealed
    }

    // MARK: import

    static func decode(_ data: Data, passphrase: String) throws
        -> (blob: SecureStore.Blob, attachments: [String: Data]) {
        guard data.count > 40, data.prefix(8) == magic else { throw BackupError.badFile }
        let salt = data.subdata(in: 8..<40)
        let sealed = data.subdata(in: 40..<data.count)
        let key = SecureStore.deriveKey(password: passphrase, salt: salt)
        guard let box = try? AES.GCM.SealedBox(combined: sealed),
              let plain = try? AES.GCM.open(box, using: key) else {
            throw BackupError.badPassphrase
        }
        let payload = try JSONDecoder().decode(Payload.self, from: plain)
        return (payload.blob, payload.attachments)
    }
}
