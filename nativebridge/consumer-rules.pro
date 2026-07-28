# Rust finds ComicNative by its exact JVM name and registers its complete native
# method table from JNI_OnLoad.
-keep,allowoptimization class com.example.comicdav.nativebridge.ComicNative {
    native <methods>;
}

# Rust looks up this registry and its callbacks by exact JVM names.
-keepnames class com.example.comicdav.nativebridge.RangeProviderRegistry
-keepclassmembers,allowoptimization class com.example.comicdav.nativebridge.RangeProviderRegistry {
    public static byte[] readRange(long, long, long);
    public static byte[] readCachedRange(long, long, long);
}
