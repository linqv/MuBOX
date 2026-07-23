# Release builds favor the strongest R8 Kotlin optimization. Kotlin/JVM's
# compiler-generated parameter null checks are removed; application-level
# require/check validation remains intact.
-processkotlinnullchecks remove

# Rust finds ComicNative by its exact JVM name and registers its complete native
# method table from JNI_OnLoad. The default native rule only preserves names and
# still allows R8 to remove Java-unused native declarations, which makes the
# all-at-once RegisterNatives call fail on Release builds.
-keep,allowoptimization class com.example.comicdav.nativebridge.ComicNative {
    native <methods>;
}

# Rust also looks up this registry and these two callbacks by exact JVM names.
-keepnames class com.example.comicdav.nativebridge.RangeProviderRegistry
-keepclassmembers,allowoptimization class com.example.comicdav.nativebridge.RangeProviderRegistry {
    public static byte[] readRange(long, long, long);
    public static byte[] readCachedRange(long, long, long);
}

# libplayer.so uses static JNI symbols for MPVLib, calls its event callbacks,
# and constructs MPVNode variants by hard-coded names. Keep only that native
# ABI surface so the rest of the mpv Kotlin wrapper can still be shrunk,
# obfuscated, inlined, and merged.
-keep,allowoptimization class is.xyz.mpv.MPVLib {
    native <methods>;
    public static void eventProperty(...);
    public static void event(...);
    public static void logMessage(...);
}
-keep,allowoptimization class is.xyz.mpv.MPVNode { *; }
-keep,allowoptimization class is.xyz.mpv.MPVNode$* { *; }
