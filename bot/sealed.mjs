// The bot's half of sealed sender (docs/SEALED-SENDER.md §3 and §5): everything that is not a
// socket. What a peer's delivery key is and when to send mine, which path a message takes,
// how a message is sealed, and the checks a sealed message has to pass before it is believed.
import crypto from 'node:crypto';
import * as S from '@signalapp/libsignal-client';

const sha256 = (b) => crypto.createHash('sha256').update(b).digest();
const u8 = (b) => new Uint8Array(b);
const same = (a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b)) === 0;
const b64 = (b) => Buffer.from(b).toString('base64');
const DEVICE_ID = 1;

/** What the relay stores for an account: the hash of its delivery key, never the key. */
export const deliveryHash = (deliveryKey) => sha256(deliveryKey);
/** The 8 bytes a peer echoes (`dkh`) to say which of my delivery keys it holds. */
export const fingerprint = (deliveryKey) => sha256(deliveryKey).subarray(0, 8);

/** The certificate names an account by the first 16 bytes of its username hash, as a UUID. */
export function uuidOf(hash32) {
  const h = Buffer.from(hash32).subarray(0, 16).toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

const BINDING_PREFIX = Buffer.from('pyrochat-sender-binding-v1', 'ascii');
/** The 99 bytes the relay signs so the FULL username hash can be trusted (§3). */
export function bindingMessage(hash32, identityKey33, expiresMs) {
  const e = Buffer.alloc(8);
  e.writeBigUInt64BE(BigInt(expiresMs));
  return Buffer.concat([BINDING_PREFIX, Buffer.from(hash32), Buffer.from(identityKey33), e]);
}

/**
 * The identity key inside a serialized PreKey message: one version byte, then protobuf, field 3.
 * The Node binding has no accessor for it (iOS and Android do), and check C7 needs it.
 * Returns null for anything that is not exactly one 33-byte field 3.
 */
export function preKeyIdentity(bytes) {
  const buf = Buffer.from(bytes).subarray(1);
  let i = 0, found = null;
  const varint = () => {
    let v = 0n, shift = 0n;
    for (;;) {
      if (i >= buf.length || shift > 63n) throw new Error('truncated');
      const b = buf[i++];
      v |= BigInt(b & 0x7f) << shift;
      if (!(b & 0x80)) return v;
      shift += 7n;
    }
  };
  try {
    while (i < buf.length) {
      const key = Number(varint()), field = key >> 3, wire = key & 7;
      if (wire === 0) varint();
      else if (wire === 2) {
        const n = Number(varint());
        if (i + n > buf.length) return null;
        if (field === 3) {
          if (found || n !== 33) return null;
          found = buf.subarray(i, i + n);
        }
        i += n;
      } else return null;
    }
  } catch { return null; }
  return found;
}

/** A failed check. `check` is its name in §3 (C0…C7). */
export class Rejected extends Error {
  constructor(check, detail = '') { super(detail ? `${check}: ${detail}` : check); this.check = check; }
}

/**
 * C1–C4 for a certificate and binding that claim `hash`: chained to a root at time `at`, a
 * server key that has not been retired, device 1, and a binding the same relay key signed for
 * exactly this hash, this identity key and this expiry.
 */
export function checkCertificate({ cert, bind, hash, roots, minKeyId = 1, at }) {
  if (!cert.validateWithTrustRoots(roots, at)) throw new Rejected('C1');
  if (cert.serverCertificate().keyId() < minKeyId) throw new Rejected('C2');
  if (cert.senderDeviceId() !== DEVICE_ID) throw new Rejected('C3');
  if (!bind || bind.length !== 64 || !hash || hash.length !== 32) throw new Rejected('C4', 'shape');
  if (uuidOf(hash) !== cert.senderUuid()) throw new Rejected('C4', 'uuid');
  const signed = bindingMessage(hash, cert.key().serialize(), cert.expiration());
  if (!cert.serverCertificate().key().verify(u8(signed), u8(bind))) throw new Rejected('C4', 'binding');
}

/**
 * My own certificate and binding as the relay issued them at login. Checked the way a
 * recipient will check them, so a relay with another root or a retired key shows up here and
 * not as messages that silently never arrive. The time used is one millisecond before expiry:
 * this machine's clock plays no part.
 */
export function takeOwnCertificate({ certB64, bindB64, hash, identityKey, roots, minKeyId = 1 }) {
  const cert = S.SenderCertificate.deserialize(u8(Buffer.from(certB64, 'base64')));
  const bind = Buffer.from(bindB64, 'base64');
  checkCertificate({ cert, bind, hash, roots, minKeyId, at: cert.expiration() - 1 });
  if (!same(cert.key().serialize(), identityKey.serialize())) throw new Rejected('C4', 'not my identity key');
  return { cert, bind };
}

/**
 * What I know about each peer (§5.1), kept in the bot's state file as
 * `{ hash: { dk, ack, sealed } }` with base64 strings.
 *   dk      their delivery key, once learned
 *   ack     the fingerprint of MY key their latest payload echoed; absent if it had none
 *   sealed  true: can receive sealed. false: known not to. absent: not known yet
 */
export class PeerKeys {
  constructor(map, onChange = () => {}) { this.map = map; this.onChange = onChange; }

  get(hex) {
    const p = this.map[hex] ?? {};
    return {
      dk: p.dk ? Buffer.from(p.dk, 'base64') : null,
      ack: p.ack ? Buffer.from(p.ack, 'base64') : null,
      sealed: p.sealed ?? null,
    };
  }
  #edit(hex, fn) {
    const before = JSON.stringify(this.map[hex] ?? {});
    const p = { ...(this.map[hex] ?? {}) };
    fn(p);
    if (JSON.stringify(p) === before) return;
    this.map[hex] = p;
    this.onChange();
  }

  /** Every payload received from a peer, on any path. */
  payloadReceived(hex, payload) {
    this.#edit(hex, (p) => {
      const dk = typeof payload.dk === 'string' ? Buffer.from(payload.dk, 'base64') : null;
      if (dk && dk.length === 32) { p.dk = b64(dk); p.sealed = true; }
      const dkh = typeof payload.dkh === 'string' ? Buffer.from(payload.dkh, 'base64') : null;
      // a payload without `dkh` means the peer does not hold my key (any more): forget the echo
      if (dkh && dkh.length === 8) p.ack = b64(dkh); else delete p.ack;
    });
  }
  /** A sealed message from them passed every check. */
  sealedReceived(hex) { this.#edit(hex, (p) => { p.sealed = true; }); }
  /** Every prekey bundle reply says whether the account can receive sealed messages. */
  bundleSeen(hex, sealedFlag) { this.#edit(hex, (p) => { p.sealed = sealedFlag === true; }); }
  /** The relay said `unsupported`. The key is kept. */
  unsupported(hex) { this.#edit(hex, (p) => { p.sealed = false; }); }
  /**
   * The relay said `denied` to a message that carried `access`: that key is no longer theirs.
   * False when it is not the key held now (another refusal already dropped it, or a newer key
   * arrived meanwhile); only a true answer is a reason to look the peer up again.
   */
  denied(hex, access) {
    const held = this.get(hex).dk;
    if (!held || !access || !same(held, access)) return false;
    this.#edit(hex, (p) => { delete p.dk; });
    return true;
  }

  /** `dk` until they echo my current key; `dkh` while I hold theirs. */
  outgoingFields(hex, myDeliveryKey) {
    const p = this.get(hex), out = {};
    if (!p.ack || !same(p.ack, fingerprint(myDeliveryKey))) out.dk = b64(myDeliveryKey);
    if (p.dk) out.dkh = b64(fingerprint(p.dk));
    return out;
  }

  /**
   * The path for a message to `hex` (§5.2, first match wins).
   * `offered`: the relay offered sealed sender at this login, my certificate passed its
   * self-check and the anonymous connection is usable.
   * Returns 'legacy', 'key', 'first' or 'fetch' (fetch the bundle, then ask again).
   */
  route(hex, { offered, hasSession }) {
    if (!offered) return 'legacy';                    // row 1
    const p = this.get(hex);
    if (p.sealed === false) return 'legacy';          // row 2
    if (p.dk) return 'key';                           // row 3
    if (p.sealed === true) return 'first';            // row 4
    return hasSession ? 'legacy' : 'fetch';           // rows 5 and 6
  }
}

/**
 * Seal `plaintext` for `to`: the Signal ciphertext wrapped so that only the recipient learns
 * it is from `me`. The content carries my certificate and, in `groupId`, my full hash, the
 * relay's binding for it, and the recipient it is meant for (§3).
 */
export async function seal({ plaintext, to, me, cert, bind, sessions, identities }) {
  const address = S.ProtocolAddress.new(to, DEVICE_ID);
  const ct = await S.signalEncrypt(u8(plaintext), address, me.address, sessions, identities);
  const groupId = Buffer.concat([me.hash, bind, Buffer.from(to, 'hex')]);
  const usmc = S.UnidentifiedSenderMessageContent.new(ct, cert, S.ContentHint.Default, u8(groupId));
  return Buffer.from(await S.sealedSenderEncrypt(usmc, address, identities));
}

/**
 * Open sealed bytes: checks C0–C7 of §3, then the ratchet. `ts` is the relay's timestamp on the
 * frame (for a blob, its manifest's). Throws Rejected for C0–C7; `.sender` is set on the error
 * once the sender is certified (C7 and later), which is when a failure may be answered.
 */
export async function open({ sealed, ts, me, roots, minKeyId = 1, stores }) {
  let usmc;
  try { usmc = await S.sealedSenderDecryptToUsmc(u8(sealed), stores.identities); }
  catch (e) { throw new Rejected('C0', e.message); }
  const cert = usmc.senderCertificate();
  const gid = usmc.groupId();
  if (!gid || gid.length !== 128) throw new Rejected('C4', 'shape');
  const hash = Buffer.from(gid.subarray(0, 32)), bind = Buffer.from(gid.subarray(32, 96)), to = Buffer.from(gid.subarray(96));
  checkCertificate({ cert, bind, hash, roots, minKeyId, at: ts });                      // C1–C4
  const sender = hash.toString('hex');
  if (sender === me.hex) throw new Rejected('C5');
  if (!same(to, me.hash)) throw new Rejected('C6');
  const address = S.ProtocolAddress.new(sender, DEVICE_ID);
  const inner = usmc.contents(), type = usmc.msgType();
  const certified = (e) => { e.sender = sender; return e; };
  let plaintext;
  if (type === S.CiphertextMessageType.PreKey) {
    const k = preKeyIdentity(inner);
    if (!k || !same(k, cert.key().serialize())) throw new Rejected('C7', 'prekey identity');
    try {
      plaintext = await S.signalDecryptPreKey(S.PreKeySignalMessage.deserialize(inner), address, me.address,
        stores.sessions, stores.identities, stores.preKeys, stores.signedPreKeys, stores.kyberPreKeys);
    } catch (e) { throw certified(e); }
  } else if (type === S.CiphertextMessageType.Whisper) {
    const known = await stores.identities.getIdentity(address);
    if (!known || !same(known.serialize(), cert.key().serialize())) throw certified(new Rejected('C7', 'whisper identity'));
    try { plaintext = await S.signalDecrypt(S.SignalMessage.deserialize(inner), address, me.address, stores.sessions, stores.identities); }
    catch (e) { throw certified(e); }
  } else throw new Rejected('C7', 'inner type');
  return { sender, plaintext: Buffer.from(plaintext), type };
}

/** The relay `body` for sealed bytes: today's envelope with type 6. */
export const sealedBody = (sealed) => Buffer.from(JSON.stringify({ v: 1, type: 6, data: b64(sealed) })).toString('base64');
/** `body` -> { type, data }. Throws on anything that is not an envelope. */
export function parseBody(body) {
  const env = JSON.parse(Buffer.from(body, 'base64').toString('utf8'));
  if (typeof env.type !== 'number' || typeof env.data !== 'string') throw new Error('not an envelope');
  return { type: env.type, data: Buffer.from(env.data, 'base64') };
}
export const SEALED_TYPE = 6;
