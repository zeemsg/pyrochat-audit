# Attachment sidecar store — layout and crypto as implemented

Source: `ios/PyroChat/Core/SecureStore.swift:174-216`.

## On-disk layout

```
<Application Support>/
├── pyro.store                      the encrypted JSON blob
└── attachments/                    SecureStore.attachmentsDir (sibling of the store file, line 178-182)
    ├── a1a1a1a1-0000-4000-8000-000000000001
    ├── a1a1a1a1-0000-4000-8000-000000000002
    └── …                           ONE file per attachment, named by ChatMessage.attachmentId, no extension
```

* File name = the `attachmentId` string exactly as stored in the message row
  (the app generates `UUID().uuidString.lowercased()`).
* The id **must parse as a UUID** (`validAttachmentId`, line 185-187) — any
  other id is refused on write (`StoreError.io`), returns `nil` on read and is
  ignored on delete. This is the path-traversal guard against a hostile peer.
* Only image and file kinds use the sidecar. **Voice notes are inline** in the
  JSON (`voiceData` base64) — there is no sidecar for them.
* The directory is created lazily on first write (`createDirectory(withIntermediateDirectories:)`).
  A backup export enumerates it and includes only entries whose name parses as a
  UUID (`BackupService.swift:39-46`).

## Crypto

```
sidecar bytes = AES-256-GCM.seal(plaintext, storeKey).combined
              = nonce(12 random) ‖ ciphertext(|plaintext|) ‖ tag(16)
```

* **Key = the store key** (`SecureStore.key`, PBKDF2 of the device password +
  Keychain salt, see SPEC.md §1). There is **no per-attachment key** and no
  derivation step; the same 32-byte key seals the blob and every sidecar.
* **Nonce**: fresh random 12 bytes per file (CryptoKit default). Files are
  rewritten only by `writeAttachment` (new nonce each time).
* **No AAD**, no header, no magic, no id binding — a sidecar could be renamed
  to another UUID and would still decrypt; integrity is per-file only.
* File length = plaintext length + 28 (fixture: 4096 → 4124, 3000 → 3028,
  2500 → 2528, 700 → 728).
* Written with `[.atomic, .completeFileProtection]` (line 202), **synchronously
  (not debounced)** and always *before* the row referencing it is appended
  (`ChatService.swift:1329, 1352, 1500, 1514`).
* Read: `readAttachment(id:)` returns `nil` on any failure (missing file, bad
  id, GCM auth failure). Delete: `deleteAttachment(id:)` removes the file
  (called on delete / burn / purge / thread burn / contact delete / report).
* Decrypted bytes are cached in RAM only (`NSCache`, 64 MiB, cleared on
  shutdown); transient plaintext copies for QuickLook/share live in tmp with
  `export-`/`preview-`/`waveform-`/`vn-` prefixes and are swept at launch and
  on wipe (`sweepPlaintextTmp`, 223-234).

## Restore path

`SecureStore(restoring:attachments:password:)` (117-127) re-seals every
attachment from a backup under the **new** device key (`writeAttachment` per
id) before writing the blob. Sidecar ciphertext therefore never survives a
migration — only plaintext-equality matters.

## The four fixture files

| file | message | plaintext | sha256 (plaintext) |
|---|---|---|---|
| `…0001` | row 14, outgoing image `[IMG]` 640×480 `image/jpeg` | 4096 B, LCG seed 0x11 | `efb9681e…28b4` |
| `…0002` | row 15, incoming image, burn running | 3000 B, seed 0x12 | `4c92e49e…7d43` |
| `…0003` | row 16, outgoing file `report.pdf` `application/pdf` | 2500 B, seed 0x13 | `0898502c…9f5c` |
| `…0004` | row 17, incoming file `notes.txt` `text/plain` | 700 B, seed 0x14 | `1910f54f…d282` |

Plaintext generator (deterministic, in `FixtureExportTests.pattern`):
`x0 = seed | 0x9E370000; x = x*1664525 + 1013904223 mod 2^32; byte = x >> 24`.
Full hashes in `manifest.json.files`. The bytes are not real JPEG/PDF — the
codec does not care; `fileBytes` in the rows equals the plaintext length.

Decrypt check (Python, see `verify.py`):
`AESGCM(storeKey).decrypt(f[:12], f[12:], None)`.
