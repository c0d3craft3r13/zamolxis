# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Hilt classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Room entities
-keep class network.zamolxis.app.data.local.entities.** { *; }

# Preserve attributes needed for debugging
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# ===== AIDL Interface Protection (CRITICAL) =====
# AIDL-generated classes must not be obfuscated or removed
# Without these rules, IPC between app and ReticulumService will fail
-keep class * implements android.os.IInterface { *; }
-keep class * extends android.os.Binder { *; }
-keep class network.zamolxis.app.I** { *; }
-keepclassmembers class * implements android.os.IInterface {
    public *;
}

# ===== Service Protection =====
# ReticulumService runs in a separate process and uses IPC
-keep class network.zamolxis.app.service.** { *; }
-keepclassmembers class network.zamolxis.app.service.** { *; }

# ===== Android IPC Components =====
-keep class android.os.RemoteCallbackList { *; }
-keep class android.os.IBinder { *; }

# ===== Native Methods (JNI) =====
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# LXST Codec2/Opus JNI bridges register their natives from JNI_OnLoad via
# FindClass + RegisterNatives against a fixed method table. The rule above keeps
# the NAMES of surviving natives but ALLOWS SHRINKING, and R8 can't trace the
# capture path (Oboe native callbacks / Chaquopy), so it removed the `encode`
# native method as unused — RegisterNatives then fails and JNI_OnLoad returns
# JNI_ERR, crashing outbound calls (Sentry COLUMBA-B1 / COLUMBA-B2). Pin both
# classes fully so every method in the native table is present.
#
# Do NOT remove these: zamolxis consumes LXST-kt from JitPack, so the app's own
# R8 config is the authoritative copy. An equivalent keep belongs in LXST-kt's
# consumer-rules.pro for other consumers, but that is a separate, not-yet-shipped
# follow-up — these lines must stay regardless of any LXST-kt-side change.
-keep class tech.torlando.lxst.codec.NativeCodec2 { *; }
-keep class tech.torlando.lxst.codec.NativeOpus { *; }

# ===== Kotlin Coroutines =====
# Used extensively in service for async operations
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ===== Reflectively-invoked bridge classes (Chaquopy / JNI-by-name) =====
# Classes invoked by name across a boundary R8 can't see (Python via Chaquopy,
# JNI) carry @network.zamolxis.app.rns.api.annotation.ReflectivelyKept. Keeping
# them at class level preserves all current AND future members, so adding a
# method to a bridge needs no rule change. The ReflectivelyKeptRequired detekt
# rule enforces the annotation on Chaquopy bridge shapes (fun interfaces in
# :rns-backend-py + Kotlin*Bridge classes) so a new bridge can't merge
# unprotected.
#
# (Replaces the previous per-class androidx.annotation.Keep convention; the old
# `-keep class network.zamolxis.app.reticulum.protocol.** { *; }` glob was
# already removed — it protected only test classes after a package rename.)
-keep @network.zamolxis.app.rns.api.annotation.ReflectivelyKept class * { *; }

# ===== Chaquopy (Python runtime) =====
# Restored from release/v0.10.x: the com.lxmf.messenger -> network.zamolxis.app
# rename dropped these rules, which regressed minified release builds — the
# Python backend fails at interpreter startup with an asset AssertionError
# (dumping assets/chaquopy/build.json). The -keepattributes below are the
# critical part: Chaquopy's reflection / PyObject.toJava() Java<->Python type
# bridging needs generic-signature and inner-class metadata that R8 strips by
# default, and that no -keep class rule restores.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
# Keep classes used with PyObject.toJava()
-keepclassmembers class * {
    *** toJava(...);
}

# ===== MessagePack Serialization =====
# MessagePack uses reflection to load buffer implementations
# Without these rules, LXMF message deserialization crashes
-keep class org.msgpack.** { *; }
-keepclassmembers class org.msgpack.** { *; }
-dontwarn org.msgpack.**

# ===== Java 16+ Unix Domain Sockets =====
# reticulum-kt's LocalClientInterface / LocalServerInterface reference
# java.net.UnixDomainSocketAddress for its local-IPC transport. The class
# is only available on Android API 31+; minSdk is 24 so R8 can't find it
# in the bootclasspath at link time. The code path is guarded by runtime
# API-level checks so it never executes on older devices; this just
# silences the build-time warning.
-dontwarn java.net.UnixDomainSocketAddress

# ===== Strip debug logging from release builds =====
# There are ~1,370 Log.d / Log.v calls in production code, most of them tracing BLE,
# threading and link state. They are the right thing to have while debugging and the
# wrong thing to ship: on a device they cost string building on hot paths, and logcat
# on a messenger is a place user data leaks to any app holding READ_LOGS on older
# releases.
#
# `-assumenosideeffects` lets R8 delete the calls and, because the arguments are pure,
# the string interpolation that feeds them. Verified before adding: no Log.d/Log.v call
# in this codebase passes an argument that mutates anything, so deleting the call cannot
# change behaviour.
#
# Deliberately only d and v. Log.i, .w and .e stay — they are what makes a user-supplied
# bug report readable, and they are few enough not to matter.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# ===== ProGuard Debugging (Optional) =====
# Uncomment these to see what R8 is removing in build/outputs/mapping/release/
# -printconfiguration build/outputs/mapping/release/configuration.txt
# -printusage build/outputs/mapping/release/usage.txt
