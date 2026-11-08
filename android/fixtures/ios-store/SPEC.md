# PyroChat iOS on-device store — format spec AS IMPLEMENTED

Ground truth for the Android codec. Every statement below was read from the
Swift sources named (paths relative to the repo root; line numbers as of
2026-09-17) and cross-checked against the fixtures in this directory, which
were produced by the real iOS code (`ios/Tests/FixtureExportTests.swift`) and
independently re-decrypted by `verify.py` (pure Python).

Files in this directory:

| file | what |
|---|---|
| `pyro.store` | real store file, password `correct horse battery staple`, Keychain salt `0011…fe0f` |
| `pyro.store.plaintext.json` | the exact bytes `pyro.store` decrypts to (Swift `JSONEncoder` output, untouched) |
| `attachments/<uuid>` | the four sidecar files referenced by the image/file rows (see `attachments.README.md`) |
| `backup.pyrobak` / `backup.plaintext.json` | PYROBAK1 backup of the same data, passphrase `backup-pass-1234` (see `backup.README.md`) |
| `keychain.json` | the Keychain items the store depends on (salt, duress verifier) with their derivation |
| `session-probe.json` | a libsignal envelope encrypted by the peer AFTER the store was written — decryptable only with the persisted session |
| `manifest.json` | machine-readable index: SHA-256 of every file, password/salt/iterations, counts to assert on |
| `verify.py` | stdlib-only cross-check (`python3 verify.py`) |

---

## 1. Key derivation

`ios/PyroChat/Core/SecureStore.swift:65-80` (`deriveKey`)

```
storeKey = PBKDF2-HMAC-SHA256( P = password as raw UTF-8 bytes,
                               S = kdf-salt (32 B, Keychain),
                               c = 600_000,
                               dkLen = 32 )
```

* Implemented with `CCKeyDerivationPBKDF(kCCPBKDF2, …, kCCPRFHmacAlgSHA256, 600_000, out, 32)`.
* Password bytes are `Array(password.utf8)` — **no Unicode normalisation, no trimming**.
  Android must feed the raw UTF-8 of whatever the user typed.
* There is no separate "pepper" value combined with the password: the *salt* is
  the pepper. It is random, generated once per install and stored **only in the
  Keychain** (`salt()`, lines 82-88), never in the store file. Without it the
  file cannot be decrypted (`pyro.store` alone is useless).
* The same function derives: the store key, every attachment key (identical key),
  the duress verifier (section 6) and the backup key (section 7, different salt).

Fixture: password `correct horse battery staple`, salt hex
`00112233445566778899aabbccddeeff102132435465768798a9bacbdcedfe0f`.

## 2. AEAD and blob layout

`SecureStore.swift:160-166` (`writeNow`) and `130-139` (`unlockingWithPassword`)

```
pyro.store = AES-256-GCM.seal(plaintextJSON, storeKey).combined
           = nonce(12 random bytes) ‖ ciphertext(|plaintext|) ‖ tag(16)
```

* CryptoKit `AES.GCM.seal(_:using:)` with a **random 12-byte nonce**, 16-byte tag,
  **no AAD**, no magic, no version byte, no length prefix. File length =
  12 + |plaintext| + 16 (fixture: 30 112 = 12 + 30 084 + 16).
* Decrypt: `AES.GCM.SealedBox(combined:)` then `AES.GCM.open`. A wrong password
  is indistinguishable from corruption — both surface as `StoreError.badPassword`
  (line 136). Missing file ⇒ `StoreError.noStore` (line 133).
* Android (`javax.crypto.Cipher "AES/GCM/NoPadding"`, tag 128) emits
  `ciphertext‖tag`; prepend the 12-byte IV to write, split it off to read.

## 3. Write path

`SecureStore.swift:143-172`

* `update(_:)` mutates `blob` on the caller's thread and hands a **value copy**
  to `persist(snapshot:)`.
* `persist` runs on a serial queue and **debounces 0.25 s** (`asyncAfter(.now()+0.25)`);
  only the newest snapshot is written.
* `writeNow`: `JSONEncoder().encode(snapshot)` → `AES.GCM.seal(...).combined` →
  `Data.write(to:options:[.atomic, .completeFileProtection])` (line 165).
  Atomic = temp file + rename; `completeFileProtection` =
  `NSFileProtectionComplete` (Data Protection class A — unreadable while the
  device is locked). Errors are swallowed (`try?`).
* `flush()` (169-172) writes synchronously (`saveQueue.sync`). Called on
  backgrounding (`ios/PyroChat/App/PyroChatApp.swift:105`, `scenePhase == .background`)
  and in `ChatService.shutdown()` (`ios/PyroChat/Core/ChatService.swift:1126`,
  lock / auto-lock / wipe paths).
* Location: `<Application Support>/pyro.store` (`storeURL`, lines 52-57); the
  directory is created on demand. Attachments: sibling directory `attachments/`
  (section 8).
* Encoder configuration: **default `JSONEncoder()`** — no `.sortedKeys`, no
  `.prettyPrinted`, no `.withoutEscapingSlashes`, default date/data strategies.

### 3.1 Every place the app writes the store

All go through `SecureStore.update` (debounced) unless marked *sync*.

| trigger | site |
|---|---|
| create identity (registration) | `SecureStore(creatingWithPassword:)` writes an empty `Blob` (`SecureStore.swift:106-111`); `CryptoEngine.generateIdentity` then writes identity fields, 1 signed prekey, 1 kyber prekey, 32 one-time prekeys and bumps `nextPreKeyId` 32× (`CryptoEngine.swift:42-48, 55, 61, 67-69`) |
| restore from backup | `SecureStore(restoring:attachments:password:)` writes every attachment (*sync*) then the blob, then `flush()` (`SecureStore.swift:117-127`) |
| unlock | `SecureStore(unlockingWithPassword:)` does **not** write (130-139); DEBUG-only demo overrides may write `settings.relayURL/socksProxy` (`PyroChatApp.swift:233-236, 249-256`) |
| prekey top-up | `CryptoEngine.generateOneTimePreKeys` (`CryptoEngine.swift:263-273`) |
| libsignal callbacks | `SignalStore.saveIdentity` (`knownIdentities`, `SignalStore.swift:39`), `storePreKey`/`removePreKey` (75/79), `storeSignedPreKey` (92), `storeKyberPreKey` (105), `storeSession` (128); `CryptoEngine.archiveSession` (155) |
| contacts | `ChatService.addContact` (461, 470), `addContactViaQR` (512), `ensureContact` (541, 547 — auto `anon-<hash8>` placeholder), `setVerified` (610), `handleIdentityChange` (619, clears verified), `renameContact` (655), `deleteContact` (665, also drops `messages[hash]` and `drafts[hash]`), `setBurnDefault` (823) |
| block list | `blockContact` (567), `unblockContact` (572) → `blockedHashes` |
| messages | `appendMessage` (1736), `replaceMessage` (1745), `setStatus` (1317), `applyReceipt` (1150), `applyBurnStart` (1170), `noteRowVisible` (1209, sets `burnStartedAt`), `deleteMessage` (735), `sweepBurned` (1650, 1 s timer), `burnThread` (1558), `reportAndBlock` (647), `handleControl` (957/960 `burn_thread`, 974 screenshot, 999 `delete_msg`) |
| receipts | `enqueueReceipt` (1218), `flushReceipts` (1275) → `receiptOutbox` |
| drafts | `setDraft` (745) — removed when blank; `drafts` becomes `nil` (key omitted) when the map empties |
| notes | `persistNotes` (1707) |
| access tokens | `redeemAccessKey` (438) — prunes `epochDay < today` then appends |
| settings | every toggle in `ios/PyroChat/UI/SettingsView.swift` (34, 38, 41, 63, 75, 80, 84, 100, 111, 121, 124, 203, 306, 321, 336) |
| synchronous flush | `PyroChatApp.swift:105` (background), `ChatService.swift:1126` (shutdown) |

Attachment sidecars are written **synchronously and before** the row that
references them (`ChatService.swift:1329, 1352, 1500, 1514`) and deleted on
delete/burn/purge (`1548` and `SecureStore.deleteAttachment`).

## 4. Plaintext JSON — root schema

Root object = `SecureStore.Blob` (`SecureStore.swift:17-41`, explicit
`CodingKeys` 252-257, decoder 258-280). Encoding is Swift's synthesized
`Encodable`; decoding is the hand-written `init(from:)`.

Conventions (all verified on the fixture bytes):

* **Key order is arbitrary and NOT stable** — it differed between the store
  encode and the backup encode of the same struct in the same process
  (`manifest.store.reencodeByteIdentical == false`). Never depend on order;
  never expect byte-identical re-encodes.
* No whitespace anywhere (`{"a":1,"b":[…]}`).
* `Date` → JSON number = **seconds since 2001-01-01T00:00:00Z** (`timeIntervalSinceReferenceDate`),
  integral values print without a fraction (`780000000`), fractional as
  shortest round-trip (`780000090.5`, `779999700.75`). Unix time = value + 978307200.
  Decoder also accepts scientific notation.
* `Data` → **standard base64 with `+ /` and `=` padding** as a JSON string
  (`/` inside base64 is escaped `\/` like any other slash). Decoder rejects
  URL-safe or unpadded base64.
* `nil` optionals are **omitted** (no `null` anywhere in the file).
* `[UInt32: Data]` → **flat array** `[id, "b64", id, "b64", …]` (Swift encodes
  non-String/Int-keyed dictionaries as alternating key/value arrays). An object
  `{"1":"…"}` **fails to decode** on iOS. Order arbitrary.
* `[String: X]` → ordinary JSON object.
* Strings: `"` `\` → `\"` `\\`; `/` → **`\/`**; U+0000-U+001F → `\n`,`\t`,`\r`
  or `\u00XX`; **U+007F (DEL) and all non-ASCII are emitted raw UTF-8** (no
  `\uXXXX`, no surrogate escapes).
* Enums encode as their raw strings; an unknown raw value fails the whole decode.
* Unknown keys are **dropped** on decode (explicit `CodingKeys`) and therefore
  vanish on the next iOS save. Android must not add persisted fields.

### 4.1 `Blob` fields

| key | Swift type | JSON | required on decode? | default if absent |
|---|---|---|---|---|
| `usernameDisplay` | String | string | no | `""` |
| `usernameHash` | String | string (64 hex) = sha256(`username.lowercased()`, ICU full case mapping) | no | `""` |
| `identityKeyPair` | Data | b64 — libsignal `IdentityKeyPair.serialize()` (69 B protobuf) | no | empty |
| `registrationId` | UInt32 | number (1…16380) | no | 0 |
| `authPrivateKey` | Data | b64 — **32-byte Ed25519 seed** (CryptoKit `Curve25519.Signing.PrivateKey.rawRepresentation`) | no | empty |
| `sessions` | [String: Data] | object peerHash → b64 `SessionRecord.serialize()` | no | `{}` |
| `preKeys` | [UInt32: Data] | **flat array** id, b64 `PreKeyRecord.serialize()` | no | `{}` |
| `signedPreKeys` | [UInt32: Data] | flat array id, b64 `SignedPreKeyRecord.serialize()` | no | `{}` |
| `kyberPreKeys` | [UInt32: Data] | flat array id, b64 `KyberPreKeyRecord.serialize()` (~4.8 KB) | no | `{}` |
| `knownIdentities` | [String: Data] | object peerHash → b64 `IdentityKey.serialize()` (33 B, `0x05‖pub`) | no | `{}` |
| `nextPreKeyId` | UInt32 | number | no | **1** |
| `contacts` | [Contact] | array | no | `[]` |
| `messages` | [String: [ChatMessage]] | object threadHash → array (may be `[]`: a stranger's `burn_thread` leaves an empty entry with no contact) | no | `{}` |
| `accessTokens` | [AccessToken] | array | no | `[]` |
| `settings` | AppSettings | object | no | `AppSettings()` |
| `receiptOutbox` | ReceiptOutbox? | object | no | absent (nil) |
| `notes` | [Note]? | array | no | absent |
| `blockedHashes` | [String]? | array of hashes | no | absent |
| `drafts` | [String: String]? | object threadHash → text | no | absent |

A blob written by a fresh install (before registration) is exactly the
defaults above encoded (optionals absent).

### 4.2 `Contact` — `ios/PyroChat/Core/Models.swift:10-30`

| key | type | required | notes |
|---|---|---|---|
| `hash` | String | **yes** | routing address |
| `name` | String | **yes** | display name; auto-created placeholders are `"anon-" + first 8 chars of hash` (`ChatService.swift:545`) |
| `addedAt` | Date | **yes** | |
| `verified` | Bool | no → `false` | always written |
| `burnSeconds` | Double? | no → nil | absent = use global default; `0` = explicitly off; else 5/60/3600/86400 |

### 4.3 `ChatMessage` — `Models.swift:32-94`

Enums (raw strings): `direction` ∈ `incoming|outgoing`; `kind` ∈
`text|voice|image|file|system`; `status` ∈ `pending|sent|delivered|read|failed`.
There is **no "burned" status** — a burned row is deleted (and its sidecar).
There is **no persisted `expiresAt`/deadline**: `burnDeadline = burnStartedAt + burnAfter`
is computed (lines 61-64). Read-dwell is runtime-only (`noteRowVisible` sets
`burnStartedAt`).

| key | type | required | notes |
|---|---|---|---|
| `id` | String | **yes** | lowercase UUID string in practice (dedupe is case-sensitive string compare) |
| `direction` | enum | **yes** | |
| `kind` | enum | **yes** | |
| `text` | String | **yes** | placeholders: image `"[IMG]"`, file `"[FILE] <name>"`, voice `"[voice <Int(duration)>s]"` (truncated), system rows = the literal UI strings (see fixture rows 18-23, 25) |
| `timestamp` | Date | **yes** | |
| `status` | enum | **yes** | incoming rows are `delivered` then `read`; system rows `delivered` |
| `burnAfter` | Double? | no | seconds; armed timer |
| `burnStartedAt` | Date? | no | nil until the countdown starts (reader: on visibility; sender: on `burn_start` control) |
| `voiceData` | Data? | no | **inline** AAC bytes (b64 in the JSON), voice only |
| `voiceDuration` | Double? | no | |
| `attachmentId` | String? | no | UUID string; sidecar file name (image/file kinds) |
| `fileName` | String? | no | sanitized; file kind (absent for images) |
| `fileMime` | String? | no | `image/jpeg`, `application/pdf`, … |
| `fileBytes` | Int? | no | **plaintext** size |
| `imageWidth` / `imageHeight` | Int? | no | pixels after normalisation |
| `viaWeb` | Bool? | no | `true` = composed on / received from a linked desktop browser (the `[web]` marker); written as `true` or omitted — never `false` (`ChatService.swift:834, 885`) |

### 4.4 `AccessToken` — `Models.swift:110-114` (all three keys **required**)

`epochDay` Int64 (UTC days since 1970), `token` b64 (40 B = epochDay 8 B big-endian ‖ 32 B nonce),
`signature` b64 (256 B RSA-PSS).

### 4.5 `Note` — `Models.swift:119-148`

`id` String, `text` String, `createdAt` Date, `modifiedAt` Date (all **required**),
`pinned` Bool? (absent / `true` / `false` all occur; absent ⇒ not pinned).

### 4.6 `ReceiptOutbox` — `Models.swift:154-169`

`delivered`, `read`, `burnStart`: each `[peerHash: [messageId]]`, each defaults
to `{}` when absent. Always written when the outbox object itself is present.

### 4.7 `AppSettings` — `Models.swift:171-264`

All 15 keys are written on every save (non-optional ones always; optional ones
when non-nil). Defaults apply when a key is absent.

| key | type | default when absent | domain |
|---|---|---|---|
| `theme` | String | `"ember"` | `ember` \| `amber` |
| `scanlines` | Bool | `true` | |
| `pushEnabled` | Bool | `false` | |
| `defaultBurnSeconds` | Double? | nil (off) | 5, 60, 3600, 86400 |
| `threadAutoBurnDays` | Int? | nil | 1, 7, 30 |
| `burnOnScreenshot` | Bool | `false` | |
| `sendReadReceipts` | Bool? | nil ⇒ false | |
| `soundsEnabled` | Bool? | nil ⇒ true | |
| `sendTyping` | Bool? | nil ⇒ true | |
| `blockScreenshots` | Bool? | nil ⇒ true | |
| `socksProxy` | String? | nil | `"host:port"` |
| `wipeAfterDays` | Int? | nil (off) | 7, 30, 90 |
| `autoLockMinutes` | Int? | nil ⇒ 5; `0` = off | 0, 1, 5, 15 |
| `relayURL` | String | `wss://relay.pyrochat.ca/ws` (under XCTest `ws://127.0.0.1:7444/ws`) | legacy values `ws://72.62.171.10:7443/ws`, `wss://72.62.171.10:7444/ws`, `ws://127.0.0.1:7444/ws` are **replaced by the default on decode** (257-259) |
| `billingURL` | String | `https://pyrochat.ca` (XCTest `http://127.0.0.1:8088`) | legacy `http://72.62.171.10:8088`, `http://127.0.0.1:8088` replaced on decode (260-262) |
| `hideNotices` | Bool? | nil ⇒ false (the `<pyrochat>` operator-notices channel is shown) | v0.5 addition, same key on iOS and Android; written only when non-nil |

The fixture sets every field to a non-default value (`theme:"amber"`,
`scanlines:false`, `pushEnabled:true`, `defaultBurnSeconds:3600`,
`threadAutoBurnDays:7`, `burnOnScreenshot:true`, `sendReadReceipts:true`,
`soundsEnabled:false`, `sendTyping:false`, `blockScreenshots:false`,
`socksProxy:"127.0.0.1:9050"`, `wipeAfterDays:30`, `autoLockMinutes:15`,
`relayURL:"wss://relay.example.test/ws"`, `billingURL:"https://billing.example.test"`).

## 5. Decode-safety rules (what Android must tolerate and reproduce)

* Every persisted struct decodes with `decodeIfPresent` + default **except** the
  identity fields listed as required above (`Contact.hash/name/addedAt`;
  `ChatMessage.id/direction/kind/text/timestamp/status`; `Note.id/text/createdAt/modifiedAt`;
  all of `AccessToken`). A missing required key fails the **entire** store decode
  (the app reports it as a bad password).
* Unknown keys at any level are ignored on decode and dropped on re-save.
* Unknown enum raw values fail the decode.
* JSON `null` for an optional decodes as nil; for a required key it fails.
* Numbers: `Int`/`UInt32` keys of the flat arrays accept `1.0`; dates accept
  exponent notation.
* Legacy relay/billing URLs are rewritten on decode (4.7).

## 6. Keychain items (`keychain.json`)

`ios/PyroChat/Core/KeychainHelper.swift` — generic-password items,
service `"ca.pyrochat.app"`, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`
(line 19), `set` = delete-then-add.

| account | value | derivation / use |
|---|---|---|
| `kdf-salt` | 32 random bytes | created on first `salt()` call (`SecureStore.swift:82-88`); PBKDF2 salt for the store, attachments and duress verifier |
| `duress-verifier` | 32 bytes | `setDuressPin(pin)` = `PBKDF2-HMAC-SHA256(pin UTF-8, kdf-salt, 600_000, 32)` (92-95). `matchesDuressPin(candidate)` recomputes and compares with plain `==` (97-101). Checked **before** any unlock attempt (`PyroChatApp.swift:220`); a match runs `obliterate()` (store, attachments, tmp sweep, `KeychainHelper.wipeAll`, dead-man disarm — `SecureStore.swift:239-248`) and the UI shows "store corrupt — no identity found" |

Android equivalent: Keystore-wrapped 32-byte salt + verifier; the verifier is
simply a second PBKDF2 output under the same salt.

## 7. Backup — see `backup.README.md`

`"PYROBAK1"(8) ‖ salt(32, random per export) ‖ AES-256-GCM.combined(JSON Payload)`,
key = PBKDF2(passphrase, that salt, 600_000, 32). Payload =
`{"blob": <Blob, same schema as section 4>, "attachments": {id: b64 plaintext}, "exportedAt": Date}`.

## 8. Attachment sidecars — see `attachments.README.md`

`<store dir>/attachments/<uuid>` = `AES-256-GCM.seal(plaintext, storeKey).combined`
(same key as the store, random nonce, no AAD). Ids must parse as UUIDs.

## 9. libsignal material inside the blob (Android: libsignal-java 0.102.x)

| blob field | bytes | Java constructor |
|---|---|---|
| `identityKeyPair` | 69 (fixture) | `new IdentityKeyPair(bytes)` |
| `knownIdentities[h]` | 33 | `new IdentityKey(bytes)` (`0x05` DJB type prefix) |
| `sessions[h]` | 4296 (fixture, after one PQXDH handshake + one reply) | `new SessionRecord(bytes)`; `hasCurrentState()` ⇔ iOS `hasSession` |
| `preKeys` values | 71 | `new PreKeyRecord(bytes)` |
| `signedPreKeys` values | 146 | `new SignedPreKeyRecord(bytes)` |
| `kyberPreKeys` values | 4821 | `new KyberPreKeyRecord(bytes)` (last-resort, id 1, reusable — `markKyberPreKeyUsed` is a no-op, `SignalStore.swift:108-112`) |
| `authPrivateKey` | 32 | Ed25519 **seed** (RFC 8032) — NOT libsignal XEdDSA; relay auth = plain Ed25519 over the raw nonce |

`session-probe.json` lets Android prove the persisted session: unlock the
fixture as alice, feed `sessions[from]` to a `SessionStore`, decrypt the
`body` (base64 → JSON `{v:1,type:2,data:b64}` → `SignalMessage(data)`), and
compare the inner JSON with `expectedPayload`.

## 10. Fixture contents (what to assert)

See `manifest.json.counts`. Summary: 4 contacts (bob verified + session;
carol blocked with `burnSeconds:0`; dave `burnSeconds:86400`; `anon-c11801ff`
placeholder), 5 threads (one empty), 27 messages — kinds text 14 / voice 2 /
image 2 / file 2 / system 7; directions in 16 / out 11; statuses delivered 18 /
read 5 / sent 2 / pending 1 / failed 1; 5 rows with `burnAfter`, 3 with
`burnStartedAt`, 2 `viaWeb`, 4 with sidecars, 2 with inline `voiceData`;
3 notes (pinned true / false / absent); 2 drafts; 1 blocked hash; 2 access
tokens; 32 one-time prekeys (ids 1…32, `nextPreKeyId` 33); 1 signed, 1 kyber
prekey; 1 session; 1 known identity; receipt outbox delivered 1 / read 2 /
burnStart 1. All dates are `780000000 + small offset` (2025-09-19T18:40:00Z + n s).

Random per run (compare semantically, never bytewise): every libsignal key
and record, `registrationId`, `authPrivateKey`, all GCM nonces, backup salt
and `exportedAt`, JSON key order, flat-array order.
