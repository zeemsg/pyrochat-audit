# PYROBAK1 backup — byte layout as implemented

Source: `ios/PyroChat/Core/BackupService.swift` (export 37-58, decode 62-74).
Fixture: `backup.pyrobak`, passphrase `backup-pass-1234`, decrypts to
`backup.plaintext.json` byte-for-byte (verified by `verify.py`).

## Byte layout

```
offset  len   field
0       8     magic  "PYROBAK1" (ASCII, no NUL)                       BackupService.magic (line 17)
8       32    salt   random per export (SecRandomCopyBytes)           line 51-52
40      12    nonce  AES-GCM nonce (random, CryptoKit)                 ┐
52      n     ciphertext                                               │ AES.GCM.seal(plain, key).combined
40+12+n 16    tag                                                      ┘ (line 54)
```

* No version field beyond the magic string, no iteration count, no length
  fields, no AAD. Total = 40 + 12 + |plaintext| + 16 (fixture: 44 320 = 68 + 44 252).
* `key = PBKDF2-HMAC-SHA256(passphrase UTF-8, salt[8..40], 600_000, 32)` —
  `SecureStore.deriveKey`, identical primitive and parameters to the store, but
  with the backup's own salt (the Keychain salt is **not** involved, so the
  file is self-contained).
* Decode (62-74): `data.count > 40 && prefix(8) == magic` else `badFile`;
  GCM failure ⇒ `badPassphrase`; then `JSONDecoder().decode(Payload.self)`.
  A well-formed file with an unparseable payload throws the raw decoding error.

## Encrypted payload

`private struct Payload: Codable { blob: SecureStore.Blob; attachments: [String: Data]; exportedAt: Date }` (29-33)

```json
{"blob": { …exactly the store JSON schema (SPEC.md §4)… },
 "attachments": {"<attachmentId>": "<base64 PLAINTEXT bytes>", …},
 "exportedAt": 811333597.8773}
```

* `blob` is the live `store.blob` encoded with the same `JSONEncoder` — the
  same schema as `pyro.store`, but **not the same bytes**: key order differs
  (see SPEC.md §4) and the store file is not embedded verbatim. Compare
  semantically (`verify.py` normalises the flat `[UInt32: Data]` arrays and
  then compares dictionaries).
* `attachments` contains the **decrypted** sidecar bytes (`store.readAttachment`
  per UUID-named file in `attachments/`, 39-46), base64 as JSON strings. Voice
  notes are already inside `blob` (`voiceData`), so they are not listed here.
  Sidecars that fail to decrypt are silently skipped.
* `exportedAt` = `Date()` at export (seconds since 2001-01-01, fractional).
* Every top-level key is required on decode (synthesized `Decodable`); an
  Android-written backup must include all three (use `{}` for no attachments).

## Restore semantics (`ios/PyroChat/App/PyroChatApp.swift:174-186`, `SecureStore.swift:117-127`)

* Refused when a store already exists on the device ("burn it first").
* Requires `blob.usernameHash` and `blob.identityKeyPair` non-empty.
* The blob is re-sealed under a key derived from the **new device password +
  a fresh Keychain salt**; attachments are re-sealed with fresh nonces. The old
  device password is never needed. Identity, sessions, prekeys, contacts,
  messages, notes, drafts, receipt outbox, settings and tokens carry over
  unchanged (settings' legacy URL migration still applies on decode).
* Running the same identity on two devices forks the Double Ratchet — restore
  implies moving.

## Interop notes for Android

* Write: `"PYROBAK1" + salt + iv + Cipher("AES/GCM/NoPadding").doFinal(json)`
  (Java emits ct‖tag; prepend the 12-byte IV).
* The `[UInt32: Data]` maps in `blob` **must** be flat arrays; an object form
  makes iOS report "wrong backup passphrase" (decode error after a successful
  GCM open).
* Dates: seconds since 2001-01-01 (add 978 307 200 to Unix seconds when
  converting *from* iOS, subtract when writing).
* Base64 must be standard alphabet with padding.
