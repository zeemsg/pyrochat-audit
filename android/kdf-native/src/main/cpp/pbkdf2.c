// PBKDF2-HMAC-SHA256 for PyroChat's SecureStore key derivation (600 000 iterations).
// Pure-Java PBKDF2 takes several seconds on mid-range phones; this runs in ~0.3 s.
// Byte-identical to CommonCrypto's CCKeyDerivationPBKDF(kCCPBKDF2, kCCPRFHmacAlgSHA256) on iOS.
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

typedef struct { uint32_t h[8]; uint64_t len; uint8_t buf[64]; size_t buflen; } sha256_ctx;

static const uint32_t K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2 };

#define ROTR(x,n) (((x) >> (n)) | ((x) << (32 - (n))))
#define CH(x,y,z) (((x) & (y)) ^ (~(x) & (z)))
#define MAJ(x,y,z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define EP0(x) (ROTR(x,2) ^ ROTR(x,13) ^ ROTR(x,22))
#define EP1(x) (ROTR(x,6) ^ ROTR(x,11) ^ ROTR(x,25))
#define SIG0(x) (ROTR(x,7) ^ ROTR(x,18) ^ ((x) >> 3))
#define SIG1(x) (ROTR(x,17) ^ ROTR(x,19) ^ ((x) >> 10))

static void sha256_init(sha256_ctx *c) {
    static const uint32_t iv[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    memcpy(c->h, iv, sizeof iv); c->len = 0; c->buflen = 0;
}
static void sha256_block(sha256_ctx *c, const uint8_t *p) {
    uint32_t w[64], a, b, cc, d, e, f, g, h, t1, t2; int i;
    for (i = 0; i < 16; i++) w[i] = ((uint32_t)p[4*i] << 24) | ((uint32_t)p[4*i+1] << 16) | ((uint32_t)p[4*i+2] << 8) | p[4*i+3];
    for (; i < 64; i++) w[i] = SIG1(w[i-2]) + w[i-7] + SIG0(w[i-15]) + w[i-16];
    a = c->h[0]; b = c->h[1]; cc = c->h[2]; d = c->h[3]; e = c->h[4]; f = c->h[5]; g = c->h[6]; h = c->h[7];
    for (i = 0; i < 64; i++) {
        t1 = h + EP1(e) + CH(e, f, g) + K[i] + w[i]; t2 = EP0(a) + MAJ(a, b, cc);
        h = g; g = f; f = e; e = d + t1; d = cc; cc = b; b = a; a = t1 + t2;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d; c->h[4] += e; c->h[5] += f; c->h[6] += g; c->h[7] += h;
}
static void sha256_update(sha256_ctx *c, const uint8_t *p, size_t n) {
    c->len += n;
    if (c->buflen) {
        size_t take = 64 - c->buflen; if (take > n) take = n;
        memcpy(c->buf + c->buflen, p, take); c->buflen += take; p += take; n -= take;
        if (c->buflen == 64) { sha256_block(c, c->buf); c->buflen = 0; }
    }
    while (n >= 64) { sha256_block(c, p); p += 64; n -= 64; }
    if (n) { memcpy(c->buf, p, n); c->buflen = n; }
}
static void sha256_final(sha256_ctx *c, uint8_t out[32]) {
    uint64_t bits = c->len * 8; size_t i;
    uint8_t pad = 0x80; sha256_update(c, &pad, 1);
    uint8_t z = 0; while (c->buflen != 56) sha256_update(c, &z, 1);
    uint8_t lenb[8]; for (i = 0; i < 8; i++) lenb[i] = (uint8_t)(bits >> (56 - 8*i));
    sha256_update(c, lenb, 8);
    for (i = 0; i < 8; i++) { out[4*i] = c->h[i] >> 24; out[4*i+1] = c->h[i] >> 16; out[4*i+2] = c->h[i] >> 8; out[4*i+3] = c->h[i]; }
}

/* HMAC with precomputed inner/outer states — the per-iteration cost is two compressions. */
typedef struct { sha256_ctx inner, outer; } hmac_key;
static void hmac_key_init(hmac_key *k, const uint8_t *key, size_t keylen) {
    uint8_t kb[64] = {0}, pad[64]; size_t i;
    if (keylen > 64) { sha256_ctx t; sha256_init(&t); sha256_update(&t, key, keylen); sha256_final(&t, kb); }
    else memcpy(kb, key, keylen);
    for (i = 0; i < 64; i++) pad[i] = kb[i] ^ 0x36; sha256_init(&k->inner); sha256_update(&k->inner, pad, 64);
    for (i = 0; i < 64; i++) pad[i] = kb[i] ^ 0x5c; sha256_init(&k->outer); sha256_update(&k->outer, pad, 64);
    memset(kb, 0, sizeof kb); memset(pad, 0, sizeof pad);
}
static void hmac_do(const hmac_key *k, const uint8_t *msg, size_t n, uint8_t out[32]) {
    sha256_ctx c = k->inner; sha256_update(&c, msg, n); sha256_final(&c, out);
    c = k->outer; sha256_update(&c, out, 32); sha256_final(&c, out);
}

static void pbkdf2_sha256(const uint8_t *pw, size_t pwlen, const uint8_t *salt, size_t saltlen,
                          uint32_t iters, uint8_t *out, size_t dklen) {
    hmac_key k; hmac_key_init(&k, pw, pwlen);
    uint8_t *sb = malloc(saltlen + 4); memcpy(sb, salt, saltlen);
    uint32_t block = 1; size_t done = 0;
    while (done < dklen) {
        uint8_t u[32], t[32]; size_t i; uint32_t j;
        sb[saltlen] = block >> 24; sb[saltlen+1] = block >> 16; sb[saltlen+2] = block >> 8; sb[saltlen+3] = block;
        hmac_do(&k, sb, saltlen + 4, u); memcpy(t, u, 32);
        for (j = 1; j < iters; j++) { hmac_do(&k, u, 32, u); for (i = 0; i < 32; i++) t[i] ^= u[i]; }
        size_t take = dklen - done; if (take > 32) take = 32;
        memcpy(out + done, t, take); done += take; block++;
        memset(u, 0, 32); memset(t, 0, 32);
    }
    memset(sb, 0, saltlen + 4); free(sb); memset(&k, 0, sizeof k);
}

JNIEXPORT jbyteArray JNICALL
Java_com_pyrochat_kdf_NativeKdf_pbkdf2(JNIEnv *env, jclass cls, jbyteArray password, jbyteArray salt, jint iterations, jint keyLen) {
    (void)cls;
    if (iterations < 1 || keyLen < 1 || keyLen > 4096) return NULL;
    jsize pwlen = (*env)->GetArrayLength(env, password), saltlen = (*env)->GetArrayLength(env, salt);
    jbyte *pw = (*env)->GetByteArrayElements(env, password, NULL);
    jbyte *sa = (*env)->GetByteArrayElements(env, salt, NULL);
    uint8_t *out = malloc((size_t)keyLen);
    pbkdf2_sha256((const uint8_t *)pw, (size_t)pwlen, (const uint8_t *)sa, (size_t)saltlen, (uint32_t)iterations, out, (size_t)keyLen);
    (*env)->ReleaseByteArrayElements(env, password, pw, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, salt, sa, JNI_ABORT);
    jbyteArray result = (*env)->NewByteArray(env, keyLen);
    (*env)->SetByteArrayRegion(env, result, 0, keyLen, (const jbyte *)out);
    memset(out, 0, (size_t)keyLen); free(out);
    return result;
}
