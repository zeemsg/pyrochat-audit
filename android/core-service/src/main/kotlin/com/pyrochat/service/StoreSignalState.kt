package com.pyrochat.service

import com.pyrochat.crypto.SignalState
import com.pyrochat.store.SecureStore
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.Bytes

/**
 * The port of iOS `SignalStore.swift`'s persistence: every libsignal callback lands in the
 * encrypted store synchronously on the caller's thread (`store.update { … }` at
 * `SignalStore.swift:39, 75, 79, 92, 105, 128`). Here the five record maps of a [SignalState]
 * are [StoreBackedMap]s that read `store.blob` and write through `store.update`, so
 * `CryptoEngine` never knows the difference and the store is written at exactly the iOS moments
 * (debounced 250 ms by `SecureStore`, like iOS).
 *
 * The scalar fields (`usernameHash`, `identityKeyPair`, `registrationId`, `authPrivateKey`,
 * `nextPreKeyId`) are plain vars on [SignalState]; iOS writes them in
 * `CryptoEngine.generateIdentity` (`CryptoEngine.swift:42-48, 67`) and `generateOneTimePreKeys`
 * (`:267`) — `ChatService` calls [persistScalars] right after those two calls.
 */
internal object StoreSignalState {
    fun bind(store: SecureStore): SignalState {
        val b = store.blob
        return SignalState(
            usernameHash = b.usernameHash,
            identityKeyPair = b.identityKeyPair.bytes,
            registrationId = b.registrationId,
            authPrivateKey = b.authPrivateKey.bytes,
            sessions = StoreBackedMap(store, { it.sessions }) { blob, m -> blob.copy(sessions = m) },
            preKeys = StoreBackedMap(store, { it.preKeys }) { blob, m -> blob.copy(preKeys = m) },
            signedPreKeys = StoreBackedMap(store, { it.signedPreKeys }) { blob, m -> blob.copy(signedPreKeys = m) },
            kyberPreKeys = StoreBackedMap(store, { it.kyberPreKeys }) { blob, m -> blob.copy(kyberPreKeys = m) },
            knownIdentities = StoreBackedMap(store, { it.knownIdentities }) { blob, m -> blob.copy(knownIdentities = m) },
            nextPreKeyId = b.nextPreKeyId,
        )
    }

    /** `CryptoEngine.swift:42-48` (+ `usernameDisplay`) and the `nextPreKeyId` bumps of `:67` / `:267`. */
    fun persistScalars(store: SecureStore, state: SignalState, usernameDisplay: String? = null) {
        store.update {
            it.copy(
                usernameDisplay = usernameDisplay ?: it.usernameDisplay,
                usernameHash = state.usernameHash,
                identityKeyPair = Bytes(state.identityKeyPair.copyOf()),
                registrationId = state.registrationId,
                authPrivateKey = Bytes(state.authPrivateKey.copyOf()),
                nextPreKeyId = state.nextPreKeyId,
            )
        }
    }
}

/**
 * A `MutableMap<K, ByteArray>` view over one `Map<K, Bytes>` field of the persisted [Blob]:
 * reads see the live blob, `put`/`remove`/`clear` go through `store.update`. [entries] is a
 * snapshot (libsignal only ever uses get/put/remove/containsKey/values through the stores).
 */
internal class StoreBackedMap<K>(
    private val store: SecureStore,
    private val read: (Blob) -> Map<K, Bytes>,
    private val write: (Blob, Map<K, Bytes>) -> Blob,
) : AbstractMutableMap<K, ByteArray>() {

    override val entries: MutableSet<MutableMap.MutableEntry<K, ByteArray>>
        get() = read(store.blob).mapValues { it.value.bytes }.toMutableMap().entries

    override val size: Int get() = read(store.blob).size

    override fun containsKey(key: K): Boolean = read(store.blob).containsKey(key)

    override fun get(key: K): ByteArray? = read(store.blob)[key]?.bytes

    override fun put(key: K, value: ByteArray): ByteArray? {
        var previous: ByteArray? = null
        store.update { blob ->
            val current = read(blob)
            previous = current[key]?.bytes
            write(blob, current + (key to Bytes(value.copyOf())))
        }
        return previous
    }

    override fun remove(key: K): ByteArray? {
        var previous: ByteArray? = null
        store.update { blob ->
            val current = read(blob)
            previous = current[key]?.bytes
            if (previous == null) blob else write(blob, current - key)
        }
        return previous
    }

    override fun clear() {
        store.update { write(it, emptyMap()) }
    }
}
