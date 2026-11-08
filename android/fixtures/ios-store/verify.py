#!/usr/bin/env python3
"""Independent cross-check of the iOS store fixtures (no Swift, no Apple APIs).

    python3 verify.py [fixture-dir]

Reads manifest.json, derives the keys with PBKDF2-HMAC-SHA256 (hashlib), opens
pyro.store / attachments/* / backup.pyrobak with AES-256-GCM and asserts that:
  * pyro.store decrypts to EXACTLY pyro.store.plaintext.json
  * the JSON has the shapes and counts manifest.json promises
  * every attachments/<id> decrypts to the plaintext hashes in the manifest
  * backup.pyrobak = "PYROBAK1" || salt(32) || nonce(12) || ct || tag(16), decrypts
    to EXACTLY backup.plaintext.json, and its `blob` equals the store JSON
    semantically, its `attachments` equal the sidecar plaintexts
  * the Keychain duress verifier = PBKDF2(pin, kdf-salt)
  * session-probe.json carries a well-formed libsignal envelope

Uses the `cryptography` package when installed; otherwise falls back to a small
pure-Python AES-GCM (self-tested against FIPS-197 and GCM-spec vectors).
Exit code 0 = every check passed.
"""
import base64
import hashlib
import json
import os
import sys

# ----------------------------------------------------------------------------
# AES-GCM: cryptography if available, else pure Python
# ----------------------------------------------------------------------------
try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # type: ignore

    def gcm_open(key, nonce, ct_and_tag, aad=b""):
        return AESGCM(key).decrypt(nonce, ct_and_tag, aad or None)

    BACKEND = "cryptography"
except ImportError:  # pragma: no cover - exercised on machines without the package
    BACKEND = "pure-python"

    def _gen_sbox():
        sbox = [0] * 256
        p = q = 1
        while True:
            p = p ^ ((p << 1) & 0xFF) ^ (0x1B if p & 0x80 else 0)
            q ^= q << 1
            q ^= q << 2
            q ^= q << 4
            q &= 0xFF
            if q & 0x80:
                q ^= 0x09
            x = q ^ (q << 1) ^ (q << 2) ^ (q << 3) ^ (q << 4)
            sbox[p] = (x ^ (x >> 8) ^ 0x63) & 0xFF
            if p == 1:
                break
        sbox[0] = 0x63
        return sbox

    _SBOX = _gen_sbox()

    def _xtime(a):
        a <<= 1
        return (a ^ 0x1B) & 0xFF if a & 0x100 else a

    def _expand_key(key):
        nk = len(key) // 4
        nr = nk + 6
        w = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
        rcon = 1
        for i in range(nk, 4 * (nr + 1)):
            t = list(w[i - 1])
            if i % nk == 0:
                t = t[1:] + t[:1]
                t = [_SBOX[b] for b in t]
                t[0] ^= rcon
                rcon = _xtime(rcon)
            elif nk > 6 and i % nk == 4:
                t = [_SBOX[b] for b in t]
            w.append([w[i - nk][j] ^ t[j] for j in range(4)])
        return [sum(w[4 * r:4 * r + 4], []) for r in range(nr + 1)], nr

    def _encrypt_block(round_keys, nr, block):
        s = [b ^ k for b, k in zip(block, round_keys[0])]
        for r in range(1, nr + 1):
            s = [_SBOX[b] for b in s]
            # ShiftRows on column-major state: row r rotates left by r
            s = [s[(i + 4 * (i % 4)) % 16] for i in range(16)]
            if r != nr:
                out = []
                for c in range(4):
                    a0, a1, a2, a3 = s[4 * c:4 * c + 4]
                    out += [
                        _xtime(a0) ^ (_xtime(a1) ^ a1) ^ a2 ^ a3,
                        a0 ^ _xtime(a1) ^ (_xtime(a2) ^ a2) ^ a3,
                        a0 ^ a1 ^ _xtime(a2) ^ (_xtime(a3) ^ a3),
                        (_xtime(a0) ^ a0) ^ a1 ^ a2 ^ _xtime(a3),
                    ]
                s = out
            s = [b ^ k for b, k in zip(s, round_keys[r])]
        return bytes(s)

    _R = 0xE1000000000000000000000000000000

    def _gf_mult(x, y):
        z = 0
        v = x
        for i in range(128):
            if (y >> (127 - i)) & 1:
                z ^= v
            v = (v >> 1) ^ _R if v & 1 else v >> 1
        return z

    def _ghash(h, aad, ct):
        def blocks(b):
            b = b + b"\0" * (-len(b) % 16)
            return [int.from_bytes(b[i:i + 16], "big") for i in range(0, len(b), 16)]

        y = 0
        for blk in blocks(aad) + blocks(ct):
            y = _gf_mult(y ^ blk, h)
        lens = (len(aad) * 8) << 64 | (len(ct) * 8)
        return _gf_mult(y ^ lens, h)

    def gcm_open(key, nonce, ct_and_tag, aad=b""):
        assert len(nonce) == 12, "only 96-bit nonces (CryptoKit default)"
        rk, nr = _expand_key(key)
        enc = lambda b: _encrypt_block(rk, nr, b)  # noqa: E731
        ct, tag = ct_and_tag[:-16], ct_and_tag[-16:]
        h = int.from_bytes(enc(b"\0" * 16), "big")
        j0 = int.from_bytes(nonce + b"\0\0\0\1", "big")
        s = _ghash(h, aad, ct)
        exp_tag = (s ^ int.from_bytes(enc(j0.to_bytes(16, "big")), "big")).to_bytes(16, "big")
        if exp_tag != tag:
            raise ValueError("GCM tag mismatch (wrong key/password or corrupt data)")
        out = bytearray()
        ctr = j0
        for i in range(0, len(ct), 16):
            ctr = (ctr & ~0xFFFFFFFF) | ((ctr + 1) & 0xFFFFFFFF)
            ks = enc(ctr.to_bytes(16, "big"))
            chunk = ct[i:i + 16]
            out += bytes(a ^ b for a, b in zip(chunk, ks))
        return bytes(out)

    def _selftest():
        # FIPS-197 C.1 / C.3
        rk, nr = _expand_key(bytes(range(16)))
        assert _encrypt_block(rk, nr, bytes.fromhex("00112233445566778899aabbccddeeff")).hex() \
            == "69c4e0d86a7b0430d8cdb78070b4c55a", "AES-128 block vector failed"
        rk, nr = _expand_key(bytes(range(32)))
        assert _encrypt_block(rk, nr, bytes.fromhex("00112233445566778899aabbccddeeff")).hex() \
            == "8ea2b7ca516745bfeafc49904b496089", "AES-256 block vector failed"
        # GCM spec test case 14: K=0^256, IV=0^96, P=0^128
        pt = gcm_open(b"\0" * 32, b"\0" * 12,
                      bytes.fromhex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919"))
        assert pt == b"\0" * 16, "AES-256-GCM vector failed"

    _selftest()


# ----------------------------------------------------------------------------
# helpers
# ----------------------------------------------------------------------------
FAILS = []


def check(cond, what):
    print(("  ok   " if cond else "  FAIL ") + what)
    if not cond:
        FAILS.append(what)


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def pbkdf2(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 600_000, 32)


def open_combined(key, blob):
    """CryptoKit AES.GCM.SealedBox.combined = nonce(12) || ct || tag(16)."""
    return gcm_open(key, blob[:12], blob[12:])


def flat_map(arr):
    """Swift [UInt32: Data] encodes as a flat [k1, v1, k2, v2, ...] array."""
    assert isinstance(arr, list) and len(arr) % 2 == 0, "flat array must have even length"
    keys, vals = arr[0::2], arr[1::2]
    assert all(isinstance(k, (int, float)) for k in keys), "flat array keys must be numbers"
    assert all(isinstance(v, str) for v in vals), "flat array values must be base64 strings"
    return {int(k): v for k, v in zip(keys, vals)}


def normalize_blob(b):
    """Make two encodings of the same Blob comparable (flat arrays are order-free)."""
    b = dict(b)
    for k in ("preKeys", "signedPreKeys", "kyberPreKeys"):
        b[k] = flat_map(b[k])
    return b


# ----------------------------------------------------------------------------
def main(root):
    print(f"backend: {BACKEND}")
    m = json.load(open(os.path.join(root, "manifest.json")))
    rd = lambda name: open(os.path.join(root, name), "rb").read()  # noqa: E731

    print("[files] sha256 vs manifest")
    for name, info in sorted(m["files"].items()):
        data = rd(name)
        want_sha = info.get("sha256") or info["sealedSha256"]      # attachments list sealed/plaintext pairs
        want_len = info.get("bytes") or info["sealedBytes"]
        check(sha256(data) == want_sha and len(data) == want_len, name)

    print("[store] KDF + AES-GCM")
    st = m["store"]
    check(st["kdf"] == "PBKDF2-HMAC-SHA256" and st["iterations"] == 600_000, "kdf parameters")
    salt = bytes.fromhex(st["kdfSaltHex"])
    key = pbkdf2(st["password"], salt)
    store_bytes = rd("pyro.store")
    plain = open_combined(key, store_bytes)
    expected = rd("pyro.store.plaintext.json")
    check(plain == expected, "pyro.store decrypts to pyro.store.plaintext.json byte-for-byte")
    check(len(store_bytes) == 12 + len(plain) + 16, "blob length = 12 + |plaintext| + 16")
    try:
        open_combined(pbkdf2("wrong password", salt), store_bytes)
        check(False, "wrong password must fail GCM auth")
    except Exception:
        check(True, "wrong password fails GCM auth")

    print("[store] JSON shape")
    blob = json.loads(plain.decode("utf-8"))
    c = m["counts"]
    check(blob["usernameDisplay"] == m["identity"]["usernameDisplay"], "usernameDisplay")
    check(blob["usernameHash"] == sha256(blob["usernameDisplay"].lower().encode()), "usernameHash = sha256(lowercased username)")
    check(blob["registrationId"] == m["identity"]["registrationId"], "registrationId")
    check(len(base64.b64decode(blob["authPrivateKey"], validate=True)) == 32, "authPrivateKey = 32-byte Ed25519 seed (padded base64)")
    check(len(base64.b64decode(blob["identityKeyPair"], validate=True)) == m["identity"]["identityKeyPairBytes"], "identityKeyPair base64 length")
    for k in ("preKeys", "signedPreKeys", "kyberPreKeys"):
        fm = flat_map(blob[k])
        check(len(fm) == c[k], f"{k}: flat [id, b64, id, b64, …] array with {c[k]} entries")
    check(sorted(flat_map(blob["preKeys"])) == list(range(1, c["preKeys"] + 1)), "preKey ids 1…32")
    check(blob["nextPreKeyId"] == c["nextPreKeyId"], "nextPreKeyId")
    check(list(blob["sessions"]) == [m["peer"]["usernameHash"]], "sessions keyed by peer hash")
    check(len(base64.b64decode(blob["sessions"][m["peer"]["usernameHash"]])) == m["peer"]["sessionRecordBytes"], "session record length")
    ident = base64.b64decode(blob["knownIdentities"][m["peer"]["usernameHash"]])
    check(ident == base64.b64decode(m["peer"]["identityKeyB64"]) and ident[0] == 0x05 and len(ident) == 33, "knownIdentities[peer] = 33-byte 0x05||pub")
    check(len(blob["contacts"]) == c["contacts"], "contacts count")
    check(sum(1 for x in blob["contacts"] if x.get("verified")) == c["contactsVerified"], "verified contacts")
    bs = {x["name"]: x.get("burnSeconds", "absent") for x in blob["contacts"]}
    check(bs.get("bob_fixture") == "absent" and bs.get("carol_fixture") == 0 and bs.get("dave_fixture") == 86400, "contact burnSeconds: nil omitted / 0 / 86400")
    check(any(x["name"].startswith("anon-") for x in blob["contacts"]), "anon-… placeholder contact present")
    rows = [r for t in blob["messages"].values() for r in t]
    check(len(blob["messages"]) == c["threads"] and len(rows) == c["messages"], "threads / messages count")
    check(sum(1 for t in blob["messages"].values() if t == []) == c["emptyThreads"], "empty thread entry")

    def tally(field):
        d = {}
        for r in rows:
            d[r[field]] = d.get(r[field], 0) + 1
        return d

    check(tally("kind") == c["messagesByKind"], f"messages by kind {tally('kind')}")
    check(tally("direction") == c["messagesByDirection"], f"messages by direction {tally('direction')}")
    check(tally("status") == c["messagesByStatus"], f"messages by status {tally('status')}")
    check(sum(1 for r in rows if "burnAfter" in r) == c["messagesWithBurnAfter"], "burnAfter rows")
    check(sum(1 for r in rows if "burnStartedAt" in r) == c["messagesWithBurnStartedAt"], "burnStartedAt rows")
    check(sum(1 for r in rows if r.get("viaWeb") is True) == c["messagesViaWeb"], "viaWeb rows")
    check(sum(1 for r in rows if "attachmentId" in r) == c["messagesWithAttachment"], "attachment rows")
    check(sum(1 for r in rows if "voiceData" in r) == c["messagesWithVoiceData"], "voiceData rows (inline base64)")
    check(all(isinstance(r["timestamp"], (int, float)) for r in rows), "timestamps are JSON numbers (seconds since 2001-01-01)")
    check(all(not any(v is None for v in r.values()) for r in rows), "no JSON nulls: nil optionals are omitted")
    s = blob["settings"]
    check(s == {"theme": "amber", "scanlines": False, "pushEnabled": True, "defaultBurnSeconds": 3600,
                "threadAutoBurnDays": 7, "burnOnScreenshot": True, "sendReadReceipts": True,
                "soundsEnabled": False, "sendTyping": False, "blockScreenshots": False,
                "socksProxy": "127.0.0.1:9050", "wipeAfterDays": 30, "autoLockMinutes": 15,
                "relayURL": "wss://relay.example.test/ws", "billingURL": "https://billing.example.test"},
          "settings: all 15 keys, every value non-default")
    check(len(blob["notes"]) == c["notes"] and sum(1 for n in blob["notes"] if n.get("pinned")) == c["notesPinned"], "notes")
    check(len(blob["drafts"]) == c["drafts"], "drafts")
    check(len(blob["blockedHashes"]) == c["blockedHashes"], "blockedHashes")
    check(len(blob["accessTokens"]) == c["accessTokens"]
          and all(len(base64.b64decode(t["token"])) == 40 and len(base64.b64decode(t["signature"])) == 256
                  for t in blob["accessTokens"]), "accessTokens: token 40 B, signature 256 B")
    ro = blob["receiptOutbox"]
    check({k: sum(len(v) for v in ro[k].values()) for k in ("delivered", "read", "burnStart")} == c["receiptOutbox"], "receiptOutbox lanes")
    esc = next(r for r in rows if r["text"].startswith("escapes:"))
    check("" in esc["text"] and "/slash/" in esc["text"] and "\n" in esc["text"], "escaped text round-trips")
    raw = plain.decode("utf-8")
    check("\\/slash\\/" in raw, "JSONEncoder escapes '/' as '\\/'")
    check("\\u0001" in raw and "café" in raw, "control chars as \\u00XX, non-ASCII emitted raw")

    print("[attachments] sidecar = AES-GCM(storeKey) per file, named by UUID")
    sidecars = {}
    for name, info in sorted(m["files"].items()):
        if not name.startswith("attachments/"):
            continue
        aid = name.split("/", 1)[1]
        pt = open_combined(key, rd(name))
        sidecars[aid] = pt
        check(sha256(pt) == info["plaintextSha256"] and len(pt) == info["plaintextBytes"], f"{name} decrypts to expected plaintext")
    refs = {r["attachmentId"] for r in rows if "attachmentId" in r}
    check(refs == set(sidecars), "every attachmentId in messages has a sidecar and vice versa")

    print("[backup] PYROBAK1")
    bk = m["backup"]
    bak = rd("backup.pyrobak")
    check(bak[:8] == b"PYROBAK1", "magic")
    bsalt = bak[8:40]
    check(bsalt.hex() == bk["saltHex"], "salt bytes 8..40 match manifest")
    bkey = pbkdf2(bk["passphrase"], bsalt)
    bplain = open_combined(bkey, bak[40:])
    check(bplain == rd("backup.plaintext.json"), "backup decrypts to backup.plaintext.json byte-for-byte")
    payload = json.loads(bplain.decode("utf-8"))
    check(set(payload) == {"blob", "attachments", "exportedAt"}, "payload keys {blob, attachments, exportedAt}")
    check(normalize_blob(payload["blob"]) == normalize_blob(blob), "backup.blob == store blob (semantic)")
    check({k: base64.b64decode(v) for k, v in payload["attachments"].items()} == sidecars, "backup.attachments == sidecar plaintexts (base64)")
    check(isinstance(payload["exportedAt"], (int, float)) and payload["exportedAt"] == bk["exportedAt"], "exportedAt")

    print("[keychain]")
    kc = json.load(open(os.path.join(root, "keychain.json")))
    check(kc["items"]["kdf-salt"]["hex"] == st["kdfSaltHex"], "kdf-salt matches manifest")
    dv = kc["items"]["duress-verifier"]
    check(pbkdf2(dv["pin"], salt).hex() == dv["hex"], "duress-verifier = PBKDF2(pin, kdf-salt, 600k, 32)")

    print("[session-probe]")
    sp = json.load(open(os.path.join(root, "session-probe.json")))
    env = json.loads(base64.b64decode(sp["body"]))
    check(env.get("v") == 1 and env.get("type") in (2, 3) and isinstance(env.get("data"), str), "envelope {v:1,type,data}")
    check(env["type"] == sp["envelopeType"] and sp["from"] == m["peer"]["usernameHash"], "probe metadata")
    base64.b64decode(env["data"], validate=True)

    print()
    if FAILS:
        print(f"FAILED ({len(FAILS)}):")
        for f in FAILS:
            print("  - " + f)
        return 1
    print(f"ALL CHECKS PASSED — store {len(store_bytes)} B, plaintext {len(plain)} B, "
          f"{len(rows)} messages / {len(blob['contacts'])} contacts / {len(sidecars)} attachments, "
          f"backup {len(bak)} B")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))))
