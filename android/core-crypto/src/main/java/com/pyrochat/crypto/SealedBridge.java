package com.pyrochat.crypto;

import org.signal.libsignal.internal.Native;
import org.signal.libsignal.metadata.protocol.UnidentifiedSenderMessageContent;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.state.IdentityKeyStore;

/**
 * The one call libsignal-java does not offer in public: open the OUTER layer of a sealed message
 * and stop there (docs/SEALED-SENDER.md §2). The library's own {@code SealedSessionCipher.decrypt}
 * goes on to decrypt the inner message under the certificate's UUID, and PyroChat's sessions are
 * named by the username hash, so the two steps have to be taken apart.
 *
 * These two lines are the first two of that method. {@code Native} is {@code internal} to the
 * library's Kotlin, which Kotlin code outside it may not call and Java may; that is the whole
 * reason this file is Java. {@code SealedCryptoTest.theTwoLibraryEntryPointsExistWithTheExpectedSignatures}
 * fails if a libsignal upgrade moves either entry point.
 */
final class SealedBridge {
    private SealedBridge() {}

    static UnidentifiedSenderMessageContent openOuter(byte[] sealed, IdentityKeyStore identityStore) throws Exception {
        long handle = Native.SealedSessionCipher_DecryptToUsmc(sealed, SessionCipher._bridge(identityStore));
        return new UnidentifiedSenderMessageContent(handle);
    }
}
