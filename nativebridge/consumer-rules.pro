# Rust finds ComicNative by its exact JVM name and registers the complete method table in JNI_OnLoad.
-keep,allowoptimization class org.mubox.reader.nativebridge.ComicNative {
    native <methods>;
}

# Rust looks up this registry and its callbacks by exact JVM names.
-keepnames class org.mubox.reader.nativebridge.RangeProviderRegistry
-keepclassmembers,allowoptimization class org.mubox.reader.nativebridge.RangeProviderRegistry {
    public static int fetchRangeIntoV1(long, long, long, long, java.nio.ByteBuffer);
    public static void cancelRangeFetchV1(long, long);
}
