// pyrobot — PyroChat's automated demo peer.
//
// A real PyroChat client written against the official libsignal Node
// bindings: it registers like a phone, speaks the relay wire protocol,
// and holds genuine Double Ratchet sessions. Anyone can add the contact
// `pyrobot`; it replies instantly and walks the person through the app's
// features (receipts, self-destruct, screenshot alerts, delete-for-both).
// Nothing it receives is stored beyond the session state libsignal needs.
//
//   RELAY_URL=ws://127.0.0.1:7443/ws BOT_USERNAME=pyrobot STATE=./state.json node index.mjs
//
// Sealed sender (docs/SEALED-SENDER.md): set SEALED_ROOT to the trust root the apps are built
// with (base64, from deploy/sealed-keys.mjs) and the bot receives and sends messages the relay
// is not told the sender of. Without it the bot behaves exactly as it always has.

import fs from 'node:fs';
import crypto from 'node:crypto';
import WebSocket from 'ws';
import * as S from '@signalapp/libsignal-client';
import { PeerKeys, deliveryHash, takeOwnCertificate, seal, open, sealedBody, parseBody, SEALED_TYPE } from './sealed.mjs';

const RELAY = process.env.RELAY_URL || 'ws://127.0.0.1:7443/ws';
const USERNAME = (process.env.BOT_USERNAME || 'pyrobot').toLowerCase();
const STATE = process.env.STATE || './state.json';
const DEVICE_ID = 1;
const b64 = (u8) => Buffer.from(u8).toString('base64');
const unb64 = (s) => new Uint8Array(Buffer.from(s, 'base64'));
const sha256hex = (s) => crypto.createHash('sha256').update(s, 'utf8').digest('hex');
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

// sealed sender: the root(s) a certificate must chain to, and the oldest relay key still accepted
const ROOTS = (process.env.SEALED_ROOT || '').split(',').map(s => s.trim()).filter(Boolean)
  .map(s => S.PublicKey.deserialize(unb64(s)));
const MIN_KEY_ID = Number(process.env.SEALED_MIN_KEY_ID || 1);
const SEALED_CONFIGURED = ROOTS.length > 0;

// ---------------------------------------------------------------- state
const state = fs.existsSync(STATE) ? JSON.parse(fs.readFileSync(STATE, 'utf8')) : {
  identity: null, regId: 0, authPriv: null, nextPreKeyId: 1,
  sessions: {}, identities: {}, preKeys: {}, signedPreKeys: {}, kyberPreKeys: {},
  welcomed: {}, registered: false,
};
const save = () => fs.writeFileSync(STATE, JSON.stringify(state));
const myHash = sha256hex(USERNAME);
const myAddress = S.ProtocolAddress.new(myHash, DEVICE_ID);
const me = { hex: myHash, hash: Buffer.from(myHash, 'hex'), address: myAddress };

// sealed sender state (§5.1): my delivery key and what I know about each peer's.
// A bot without a root writes nothing new to its state file.
if (SEALED_CONFIGURED) {
  state.peerKeys ??= {};
  if (!state.deliveryKey) { state.deliveryKey = b64(crypto.randomBytes(32)); save(); }
}
const deliveryKey = SEALED_CONFIGURED ? Buffer.from(state.deliveryKey, 'base64') : null;
const DELIVERY = SEALED_CONFIGURED ? b64(deliveryHash(deliveryKey)) : null;   // what the relay is given
const peers = new PeerKeys(state.peerKeys ?? {}, () => save());

// ---------------------------------------------------------------- libsignal stores (JSON-backed)
class Sessions extends S.SessionStore {
  async saveSession(addr, rec) { state.sessions[addr.name()] = b64(rec.serialize()); save(); }
  async getSession(addr) { const s = state.sessions[addr.name()]; return s ? S.SessionRecord.deserialize(unb64(s)) : null; }
  async getExistingSessions(addrs) { const out = []; for (const a of addrs) { const s = await this.getSession(a); if (s) out.push(s); } return out; }
}
class Identities extends S.IdentityKeyStore {
  async getIdentityKey() { return S.PrivateKey.deserialize(unb64(state.identity.priv)); }
  async getLocalRegistrationId() { return state.regId; }
  async saveIdentity(addr, key) {
    const prev = state.identities[addr.name()];
    const next = b64(key.serialize());
    state.identities[addr.name()] = next; save();
    return prev && prev !== next ? S.IdentityChange.ReplacedExisting : S.IdentityChange.NewOrUnchanged;
  }
  async isTrustedIdentity() { return true; }          // accept-and-warn, like the app
  async getIdentity(addr) { const k = state.identities[addr.name()]; return k ? S.PublicKey.deserialize(unb64(k)) : null; }
}
class PreKeys extends S.PreKeyStore {
  async savePreKey(id, rec) { state.preKeys[id] = b64(rec.serialize()); save(); }
  async getPreKey(id) { return S.PreKeyRecord.deserialize(unb64(state.preKeys[id])); }
  async removePreKey(id) { delete state.preKeys[id]; save(); }
}
class SignedPreKeys extends S.SignedPreKeyStore {
  async saveSignedPreKey(id, rec) { state.signedPreKeys[id] = b64(rec.serialize()); save(); }
  async getSignedPreKey(id) { return S.SignedPreKeyRecord.deserialize(unb64(state.signedPreKeys[id])); }
}
class KyberPreKeys extends S.KyberPreKeyStore {
  async saveKyberPreKey(id, rec) { state.kyberPreKeys[id] = b64(rec.serialize()); save(); }
  async getKyberPreKey(id) { return S.KyberPreKeyRecord.deserialize(unb64(state.kyberPreKeys[id])); }
  async markKyberPreKeyUsed() { /* last-resort key: keep it */ }
}
const sessions = new Sessions(), identities = new Identities(), preKeys = new PreKeys(),
      signedPreKeys = new SignedPreKeys(), kyberPreKeys = new KyberPreKeys();

// ---------------------------------------------------------------- identity + registration material
function ensureIdentity() {
  if (state.identity) return;
  const ik = S.IdentityKeyPair.generate();
  const auth = crypto.generateKeyPairSync('ed25519');
  state.identity = { priv: b64(ik.privateKey.serialize()), pub: b64(ik.publicKey.serialize()) };
  state.regId = 1 + crypto.randomInt(16380);
  state.authPriv = auth.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64');
  state.authPub = auth.publicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('base64');
  save();
}
async function makePreKeys(n) {
  const list = [];
  for (let i = 0; i < n; i++) {
    const id = state.nextPreKeyId++;
    const priv = S.PrivateKey.generate();
    const rec = S.PreKeyRecord.new(id, priv.getPublicKey(), priv);
    await preKeys.savePreKey(id, rec);
    list.push({ id, pub: b64(priv.getPublicKey().serialize()) });
  }
  save();
  return list;
}
async function buildRegistration() {
  const idPriv = S.PrivateKey.deserialize(unb64(state.identity.priv));
  const idPub = S.PublicKey.deserialize(unb64(state.identity.pub));
  const now = Date.now();
  // signed prekey
  const spkPriv = S.PrivateKey.generate();
  const spkSig = idPriv.sign(spkPriv.getPublicKey().serialize());
  await signedPreKeys.saveSignedPreKey(1, S.SignedPreKeyRecord.new(1, now, spkPriv.getPublicKey(), spkPriv, spkSig));
  // last-resort kyber prekey
  const kem = S.KEMKeyPair.generate();
  const kyberSig = idPriv.sign(kem.getPublicKey().serialize());
  await kyberPreKeys.saveKyberPreKey(1, S.KyberPreKeyRecord.new(1, now, kem, kyberSig));
  const otks = await makePreKeys(32);
  return {
    t: 'register', user: myHash, auth_pub: state.authPub, reg_id: state.regId,
    identity_key: b64(idPub.serialize()),
    signed_prekey: { id: 1, pub: b64(spkPriv.getPublicKey().serialize()), sig: b64(spkSig) },
    kyber_prekey: { id: 1, pub: b64(kem.getPublicKey().serialize()), sig: b64(kyberSig) },
    one_time_prekeys: otks,
    ...(DELIVERY ? { delivery: DELIVERY } : {}),
  };
}
function signChallenge(nonceB64) {
  const key = crypto.createPrivateKey({ key: Buffer.from(state.authPriv, 'base64'), format: 'der', type: 'pkcs8' });
  return crypto.sign(null, Buffer.from(nonceB64, 'base64'), key).toString('base64');
}

// ---------------------------------------------------------------- envelope crypto (mirrors CryptoEngine.swift)
// One ratchet step at a time. Each step reads a session, changes it and writes it back, with
// awaits in between; two steps for the same peer run interleaved would use one message key twice.
let ratchetTail = Promise.resolve();
function oneAtATime(step) {
  const run = ratchetTail.then(step);
  ratchetTail = run.catch(() => {});
  return run;
}

async function encryptFor(peerHash, payload) {
  const addr = S.ProtocolAddress.new(peerHash, DEVICE_ID);
  const ct = await oneAtATime(() => S.signalEncrypt(new TextEncoder().encode(JSON.stringify(payload)), addr, myAddress, sessions, identities));
  const envelope = { v: 1, type: ct.type(), data: b64(ct.serialize()) };
  return Buffer.from(JSON.stringify(envelope)).toString('base64');
}
async function decryptFrom(peerHash, body) {
  const env = JSON.parse(Buffer.from(body, 'base64').toString('utf8'));
  if (env.type === SEALED_TYPE) throw new Error('a sealed body on a frame that names its sender');
  const addr = S.ProtocolAddress.new(peerHash, DEVICE_ID);
  const data = unb64(env.data);
  const pt = await oneAtATime(() => env.type === S.CiphertextMessageType.PreKey
    ? S.signalDecryptPreKey(S.PreKeySignalMessage.deserialize(data), addr, myAddress,
        sessions, identities, preKeys, signedPreKeys, kyberPreKeys)
    : S.signalDecrypt(S.SignalMessage.deserialize(data), addr, myAddress, sessions, identities));
  return JSON.parse(Buffer.from(pt).toString('utf8'));
}

// ---------------------------------------------------------------- relay connection
let ws = null, ready = false, backoff = 1, pingTimer = null;
const send = (o) => { if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(o)); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** My certificate and binding for this login (RAM only); null = the relay offered none that passes. */
let own = null, certTimer = null;

function takeCertificate(m) {
  clearTimeout(certTimer);
  const life = Math.max(1, Number(m.cert_ttl) || 0) * 1000;
  try {
    const taken = takeOwnCertificate({ certB64: m.cert, bindB64: m.bind, hash: me.hash,
      identityKey: S.PublicKey.deserialize(unb64(state.identity.pub)), roots: ROOTS, minKeyId: MIN_KEY_ID });
    // The recipient judges the certificate by the relay's clock when the message arrives there,
    // so sealing stops a little before the end: a tenth of its life, a minute at most.
    const left = life - Math.min(60000, life / 10);
    own = { ...taken, until: performance.now() + left, wallUntil: Date.now() + left };
    certTimer = setTimeout(askCertificate, life / 2, life);
  } catch (e) {
    own = null;                       // a relay with another root, or a retired key: stay on the old path
    log('sealed sender off: my certificate failed its check', e.message);
  }
}
/** Renewed at half its life, and asked for again every eighth of it until the relay answers. */
function askCertificate(life) {
  send({ t: 'cert' });
  certTimer = setTimeout(askCertificate, life / 8, life);
}
/** Nothing is sealed with a certificate that has run out; both clocks have to agree it has not. */
const certUsable = () => !!own && performance.now() < own.until && Date.now() < own.wallUntil;

function connect() {
  ready = false;
  ws = new WebSocket(RELAY);
  ws.on('open', async () => {
    backoff = 1;
    clearInterval(pingTimer); pingTimer = setInterval(() => send({ t: 'ping' }), 30000);
    if (!state.registered) { send(await buildRegistration()); }
    else send({ t: 'login', user: myHash });
  });
  ws.on('message', (raw, isBinary) => { if (!isBinary) route(JSON.parse(raw.toString())).catch(e => log('route error', e.message)); });
  ws.on('close', () => {
    ready = false; clearInterval(pingTimer);
    own = null; clearTimeout(certTimer); anonCloseAll();  // an anonymous connection never outlives the main link
    log('relay closed; retry in', backoff, 's'); setTimeout(connect, backoff * 1000); backoff = Math.min(backoff * 2, 30);
  });
  ws.on('error', (e) => log('ws error', e.message));
}

async function route(m) {
  switch (m.t) {
    case 'registered': state.registered = true; save(); log('registered as', USERNAME); send({ t: 'login', user: myHash }); break;
    case 'error':
      if (m.code === 'username_taken' && !state.registered) { state.registered = true; save(); send({ t: 'login', user: myHash }); }
      else log('relay error', m.code, m.ref || '');
      break;
    // a sealed-capable client offers the hash of its delivery key at every login
    case 'challenge': send({ t: 'auth', sig: signChallenge(m.nonce), ...(DELIVERY ? { delivery: DELIVERY } : {}) }); break;
    case 'ready':
      ready = true; anonFailed = false;
      if (m.sealed && SEALED_CONFIGURED) takeCertificate(m);
      log('LINK UP, queued:', m.queued ?? 0, own ? '· sealed sender on' : '');
      break;
    case 'cert': if (SEALED_CONFIGURED) takeCertificate(m); break;
    case 'prekey_count': if (m.n < 10) send({ t: 'prekey_upload', one_time_prekeys: await makePreKeys(32) }); break;
    case 'recv': await onRecv(m); break;
    default: break;
  }
}

// ---------------------------------------------------------------- the anonymous connection (§5.5)
// A second socket that never logs in. Sealed messages and prekey fetches go out on it, so the
// relay is not told they are mine. Opened on first need, closed with the main link.
//   { ws, attempts, waiters, ping, retiring }
//   attempts  deliver id -> the message it carries, until the relay's verdict (or 15 s)
//   waiters   peer hash  -> { done, timer } for a prekey bundle that was asked for
/** The connection new work goes out on. */
let anon = null, anonOpening = null;
/** Every anonymous connection still open: the current one, and any that is retiring. */
const anonAll = new Set();
/** Two attempts to open one failed: the old path is used until the next login. */
let anonFailed = false;

/** The connection is gone or going: nothing still on it will be answered. */
function anonEnd(conn) {
  if (!anonAll.delete(conn)) return;
  if (anon === conn) anon = null;
  clearInterval(conn.ping);
  conn.attempts.clear();                              // like a send when the link drops: gone
  for (const peer of [...conn.waiters.keys()]) answer(conn, peer, null);
  try { conn.ws.close(); } catch { /* already closed */ }
}
const anonCloseAll = () => { for (const conn of [...anonAll]) anonEnd(conn); };

/**
 * After a fallback to the old path (§5.2) new work goes to a fresh connection, so the refused
 * attempt and the legacy send that follows are not on record against the socket used for other
 * people. What is already in flight on this one still gets its verdict; then it closes.
 */
function anonRetire(conn) {
  if (anon === conn) anon = null;
  conn.retiring = true;
  anonSettle(conn);
}
function anonSettle(conn) {
  if (conn.retiring && conn.attempts.size === 0 && conn.waiters.size === 0) anonEnd(conn);
}
/** Hand a waiting prekey fetch its reply (null: refused, timed out, or the connection ended). */
function answer(conn, peerHash, frame) {
  const w = conn.waiters.get(peerHash);
  if (!w) return;
  conn.waiters.delete(peerHash);
  clearTimeout(w.timer);
  w.done(frame);
}

function openAnonOnce() {
  return new Promise((resolve, reject) => {
    const w = new WebSocket(RELAY);
    const conn = { ws: w, attempts: new Map(), waiters: new Map(), ping: null, retiring: false };
    const fail = (why) => { clearTimeout(timer); try { w.close(); } catch { /* */ } reject(new Error(why)); };
    const timer = setTimeout(() => fail('no anon_ok in 10 s'), 10000);
    w.on('open', () => w.send(JSON.stringify({ t: 'anon' })));
    w.once('message', (raw) => {
      let m = null;
      try { m = JSON.parse(raw.toString()); } catch { /* not json */ }
      if (m?.t !== 'anon_ok') return fail('relay refused the anonymous connection');
      clearTimeout(timer);
      w.on('message', (data, isBinary) => { if (!isBinary) routeAnon(conn, JSON.parse(data.toString())).catch(e => log('anonymous route error', e.message)); });
      conn.ping = setInterval(() => { if (w.readyState === WebSocket.OPEN) w.send(JSON.stringify({ t: 'ping' })); }, 30000);
      anonAll.add(conn);
      resolve(conn);
    });
    w.on('error', (e) => fail(e.message));
    w.on('close', () => anonEnd(conn));
  });
}

async function ensureAnon() {
  if (anon && anon.ws.readyState === WebSocket.OPEN) return anon;
  if (anonFailed) throw new Error('anonymous connection unavailable');
  anonOpening ??= (async () => {
    for (let attempt = 1; attempt <= 2 && ready; attempt++) {
      try {
        const conn = await openAnonOnce();
        if (!ready) { anonEnd(conn); break; }         // the main link dropped while this one was opening
        return (anon = conn);
      } catch (e) { log('anonymous connection attempt', attempt, 'failed:', e.message); }
    }
    if (ready) {
      anonFailed = true;
      log('sealed sender off until the next login: the anonymous connection could not be opened');
    }
    throw new Error('anonymous connection unavailable');
  })().finally(() => { anonOpening = null; });
  return anonOpening;
}

async function routeAnon(conn, m) {
  switch (m.t) {
    case 'sent': conn.attempts.delete(m.id); break;
    case 'prekey_bundle': answer(conn, m.user, m); break;
    case 'error': {
      const job = conn.attempts.get(m.ref);
      if (job) { conn.attempts.delete(m.ref); await refused(conn, job, m.code); }
      else if (m.user) answer(conn, m.user, null);    // a refused prekey fetch names the account asked for
      else log('relay error on the anonymous connection', m.code);
      break;
    }
    default: break;
  }
  anonSettle(conn);
}

/** True for a session usable for sending (an archived one is not). */
function hasSession(peerHash) {
  const s = state.sessions[peerHash];
  return !!s && S.SessionRecord.deserialize(unb64(s)).hasCurrentState();
}

/** Fetch a peer's bundle anonymously. Resolves with the frame, or null (unknown user, timeout, no connection). */
async function fetchBundle(peerHash) {
  let conn;
  try { conn = await ensureAnon(); } catch { return null; }
  return new Promise((resolve) => {
    answer(conn, peerHash, null);                     // never two waits for one peer on one connection
    const timer = setTimeout(() => { answer(conn, peerHash, null); anonSettle(conn); }, 15000);
    conn.waiters.set(peerHash, { done: resolve, timer });
    conn.ws.send(JSON.stringify({ t: 'prekey_fetch', user: peerHash }));
  });
}

/**
 * Fetch the bundle and record whether the account can receive sealed messages. A session is
 * started from it only when there is none, or the identity key is not the one on record: the
 * same thing an identity change does today. One fetch per peer at a time: three messages
 * refused together must not start three sessions from one bundle.
 */
const refreshing = new Map();
function refreshFromBundle(peerHash) {
  let run = refreshing.get(peerHash);
  if (!run) {
    run = refreshOnce(peerHash).finally(() => refreshing.delete(peerHash));
    refreshing.set(peerHash, run);
  }
  return run;
}
async function refreshOnce(peerHash) {
  const b = await fetchBundle(peerHash);
  if (!b) return false;
  peers.bundleSeen(peerHash, b.sealed === true);
  await oneAtATime(async () => {
    const known = state.identities[peerHash];
    if (hasSession(peerHash) && known && Buffer.compare(unb64(known), unb64(b.identity_key)) === 0) return;
    const otk = b.one_time_prekey;
    const bundle = S.PreKeyBundle.new(b.reg_id, DEVICE_ID, otk ? otk.id : null, otk ? S.PublicKey.deserialize(unb64(otk.pub)) : null,
      b.signed_prekey.id, S.PublicKey.deserialize(unb64(b.signed_prekey.pub)), unb64(b.signed_prekey.sig),
      S.PublicKey.deserialize(unb64(b.identity_key)), b.kyber_prekey.id, S.KEMPublicKey.deserialize(unb64(b.kyber_prekey.pub)), unb64(b.kyber_prekey.sig));
    await S.processPreKeyBundle(bundle, S.ProtocolAddress.new(peerHash, DEVICE_ID), myAddress, sessions, identities);
  });
  return true;
}

// ---------------------------------------------------------------- sending (§5.2, §5.3)
// THE one place a message leaves. It picks the path, adds the delivery-key fields, encrypts,
// seals when the path is not the old one, and writes to the right socket.
async function sendMessage(peer, payload, kind = 'msg') {
  await transmit({ peer, payload, kind, ttl: kind === 'msg' ? 172800 : 86400, ref: crypto.randomUUID(), steps: 0 });
}
const sendText = (peer, text, burnAfter) => sendMessage(peer, { kind: 'text', text, senderName: USERNAME, burnAfter });
const control = (peer, obj) => sendMessage(peer, { kind: 'control', ...obj }, 'control');

async function transmit(job) {
  if (++job.steps > 4) return log('a message was refused on every path; dropped');
  const offered = () => certUsable() && !anonFailed;
  let lane = job.legacyOnly ? 'legacy' : peers.route(job.peer, { offered: offered(), hasSession: hasSession(job.peer) });
  if (lane === 'fetch') {                                   // a peer I have no session with
    if (!await refreshFromBundle(job.peer)) return log('no bundle for a peer; message dropped');
    lane = peers.route(job.peer, { offered: offered(), hasSession: true });
  }
  // `dk` until the peer echoes my key, `dkh` while I hold theirs: on every path, old one included
  const keys = own ? peers.outgoingFields(job.peer, deliveryKey) : {};

  if (lane === 'legacy') {
    const body = await encryptFor(job.peer, { ...job.payload, ...keys });
    send({ t: 'send', id: crypto.randomUUID(), to: job.peer, ttl: job.ttl, kind: job.kind, body });
    return;
  }
  // typing would spend the recipient's first-contact allowance and crowd out the message itself
  if (lane === 'first' && job.payload.control === 'typing') return;
  const access = lane === 'key' ? peers.get(job.peer).dk : null;        // the key as held now: a refusal names it
  let conn;
  try { conn = await ensureAnon(); } catch { return transmit(job); }       // the route is now the old path
  const mine = own;
  if (!certUsable()) return transmit(job);                  // the main link dropped, or the certificate ran out
  // a message that has a row carries its id inside; the id the relay sees is new for every attempt
  const payload = { ...job.payload, ...keys, ...(job.payload.kind === 'text' ? { refId: job.ref } : {}) };
  const sealed = await oneAtATime(() => seal({ plaintext: Buffer.from(JSON.stringify(payload)), to: job.peer, me,
    cert: mine.cert, bind: mine.bind, sessions, identities }));
  if (!anonAll.has(conn)) return transmit(job);             // that connection ended meanwhile: seal again for another
  const id = crypto.randomUUID();
  conn.attempts.set(id, { ...job, lane, access });
  setTimeout(() => { if (conn.attempts.delete(id)) anonSettle(conn); }, 15000);
  conn.ws.send(JSON.stringify({ t: 'deliver', id, to: job.peer, ttl: job.ttl, kind: job.kind, body: sealedBody(sealed),
    ...(access ? { access: b64(access) } : {}) }));
}

/** The relay refused a sealed message: one step down, never back up (§5.2). */
async function refused(conn, job, code) {
  switch (code) {
    case 'denied':                                  // the key that attempt carried is no longer theirs
      if (peers.denied(job.peer, job.access)) await refreshFromBundle(job.peer);   // a changed identity key starts a new session
      else await refreshing.get(job.peer);          // another refusal is already looking them up
      return transmit(job);
    case 'unsupported':                             // an old build has the account
      peers.unsupported(job.peer);
      anonRetire(conn);
      return transmit(job);
    case 'intro_full':
      anonRetire(conn);
      return transmit({ ...job, legacyOnly: true });
    case 'rate_limited':
    case 'queue_full':
      if (job.retried) return;
      await sleep(5000);
      return transmit({ ...job, retried: true });
    default:                                        // intro_limit, unknown_user, bad_frame
      log('the relay refused a message:', code);
  }
}

// ---------------------------------------------------------------- receiving (§5.4)
async function onRecv(m) {
  // the relay wipes its copy the moment we ack — like a phone, ack after handling
  try {
    if ('from' in m) await onLegacy(m); else await onSealed(m);
  } catch (e) {
    log('could not read an incoming message:', e.check ?? e.message);
  } finally {
    send({ t: 'ack', id: m.id });
  }
}

/** Today's frame: the relay names the sender. */
async function onLegacy(m) {
  const { id, from, kind, body } = m;
  if (kind === 'msg' || kind === 'control') {
    const p = await decryptFrom(from, body);
    if (SEALED_CONFIGURED) peers.payloadReceived(from, p);
    log('rx', p.kind === 'control' ? `control:${p.control}` : p.kind);
    await handle(from, id, p);
  } else if (kind === 'blob') {
    // manifests/chunks for attachments: a bot has no screen to show them on
    await handle(from, id, { kind: 'blob_manifest' });
  }
}

/** A frame with no sender: the sender is inside, and has to pass checks C0–C7 before it is believed. */
async function onSealed(m) {
  if (!SEALED_CONFIGURED) throw new Error('a sealed frame, and no root to check it against');
  const env = parseBody(m.body);
  if (env.type !== SEALED_TYPE) throw new Error('a frame without a sender that is not sealed');
  const { sender, plaintext } = await oneAtATime(() => open({ sealed: env.data, ts: m.ts, me, roots: ROOTS, minKeyId: MIN_KEY_ID,
    stores: { sessions, identities, preKeys, signedPreKeys, kyberPreKeys } }));
  peers.sealedReceived(sender);
  const p = JSON.parse(plaintext.toString('utf8'));
  peers.payloadReceived(sender, p);
  log('rx', p.kind === 'control' ? `control:${p.control}` : p.kind, '(sealed)');
  // receipts name the sender's row: its id rides inside, the relay's id is new for every attempt.
  // An attachment's manifest is answered like a legacy one; its chunks are binary frames, ignored.
  await handle(sender, p.refId ?? m.id, m.kind === 'blob' ? { kind: 'blob_manifest' } : p);
}

// ---------------------------------------------------------------- the conversation
const TOUR = (name) => [
  [0,   `hey <${name}> — i'm pyrobot, an automated peer so you can see PyroChat working. everything you send me is end-to-end encrypted with the Signal protocol; the relay in between only ever sees ciphertext.`],
  [1800, `the words on the right of your messages are real: SENT when the relay took it, DELIVERED when it reached my device, READ when i opened it. i just marked your message read.`],
  [2200, { burn: 20, text: `this one self-destructs 20 seconds after you read it — on both of our devices at the same time. watch the countdown.` }],
  [2600, `things to try: take a screenshot of this chat and i'll get an alert. long-press one of your messages → delete for both. [cfg] has the desktop link, burn timers, duress pin and the dead-man switch.`],
  [2400, `send me anything and i'll echo it back so you can watch the statuses. type help to see this tour again. bring a friend: [share] on the home screen sends them your username.`],
];

async function handle(peer, msgId, p) {
  const name = (p.senderName || 'friend').slice(0, 24);
  if (p.kind === 'control') {
    if (p.control === 'screenshot' || p.control === 'screen_record') {
      await sendText(peer, `⚠ got it — your ${p.control === 'screenshot' ? 'screenshot' : 'screen recording'} alert just reached me. a real contact would see the same warning, and could have this thread burn because of it.`);
    }
    return;   // receipts, typing, burn_start, web_link: nothing to say
  }
  if (p.kind === 'blob_manifest') {
    await control(peer, { control: 'receipt', receipt: 'delivered', refIds: [msgId] });
    await sendText(peer, `an attachment arrived, encrypted end to end. i have no screen to open it on, but a real contact would see it right here.`);
    return;
  }
  if (p.kind !== 'text') return;

  // receipts exactly like a phone: delivered, then read
  await control(peer, { control: 'receipt', receipt: 'delivered', refIds: [msgId] });
  await sleep(400);
  await control(peer, { control: 'receipt', receipt: 'read', refIds: [msgId] });
  await control(peer, { control: 'typing' });

  const text = (p.text || '').trim();
  if (!state.welcomed[peer] || /^help$/i.test(text)) {
    state.welcomed[peer] = Date.now(); save();
    for (const [delay, item] of TOUR(name)) {
      await sleep(delay);
      await control(peer, { control: 'typing' });
      if (typeof item === 'string') await sendText(peer, item);
      else await sendText(peer, item.text, item.burn);
    }
    return;
  }
  await sleep(700);
  await sendText(peer, `you said: ${text.slice(0, 400)}`);
}

// ---------------------------------------------------------------- go
ensureIdentity();
log('pyrobot', USERNAME, 'hash', myHash.slice(0, 12) + '…', '→', RELAY);
connect();
