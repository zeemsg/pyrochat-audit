# PyroChat release keep rules (R8, full mode — AGP default). Everything not listed here is
# covered by consumer rules shipped inside the libraries themselves: kotlinx-serialization
# (META-INF/com.android.tools/r8/*.pro in the jar), Firebase, CameraX, ML Kit, OkHttp, Compose.

# ---- libsignal (Rust ↔ JNI) --------------------------------------------------------------
# libsignal-client ships META-INF/proguard/libsignal.pro (native method names, @CalledFromNative,
# *Record/*Store names). The Rust side also resolves classes by name at runtime (exceptions,
# CiphertextMessage subclasses, the store interfaces it calls back into), so the whole Java
# surface stays intact: ~1 MiB of classes next to a ~110 MiB native library is not worth the risk.
-keep class org.signal.libsignal.** { *; }
-keepclassmembers class org.signal.libsignal.** { *; }
# our store implementations (core-crypto InMemorySignalStores) are invoked from Rust by method name
-keepclassmembers class * implements org.signal.libsignal.protocol.state.SessionStore { *; }
-keepclassmembers class * implements org.signal.libsignal.protocol.state.PreKeyStore { *; }
-keepclassmembers class * implements org.signal.libsignal.protocol.state.SignedPreKeyStore { *; }
-keepclassmembers class * implements org.signal.libsignal.protocol.state.KyberPreKeyStore { *; }
-keepclassmembers class * implements org.signal.libsignal.protocol.state.IdentityKeyStore { *; }
-keepclassmembers class * implements org.signal.libsignal.protocol.groups.state.SenderKeyStore { *; }

# ---- NDK KDF (kdf-native/src/main/cpp/pbkdf2.c: Java_com_pyrochat_kdf_NativeKdf_pbkdf2) ------
# JNI resolves the symbol from the class + method name: neither may be renamed or stripped.
-keepclasseswithmembernames,includedescriptorclasses class com.pyrochat.kdf.NativeKdf { native <methods>; }
-keepclassmembers class com.pyrochat.kdf.NativeKdf { *; }

# ---- kotlinx.serialization ----------------------------------------------------------------
# The plugin-generated $$serializer / Companion.serializer() lookups are kept by the library's own
# R8 rules; these keep the @Serializable annotations + nested-class metadata they depend on and the
# generated serializers of every store/wire model whole (Swift-compatible field order matters).
-keepattributes *Annotation*, InnerClasses, RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.pyrochat.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- BouncyCastle (lightweight API only: Ed25519Signer, PSSSigner, HKDF) --------------------
# No JCA provider is registered, so nothing is looked up by string; only silence the JDK-only
# references R8 finds while tracing the provider classes we never touch.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**

# ---- diagnostics ----------------------------------------------------------------------------
# keep line numbers so a release stack trace (from a user's bug report) can be retraced with
# app/build/outputs/mapping/release/mapping.txt
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
