# Release builds favor the strongest R8 Kotlin optimization. Kotlin/JVM's
# compiler-generated parameter null checks are removed; application-level
# require/check validation remains intact.
-processkotlinnullchecks remove

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
