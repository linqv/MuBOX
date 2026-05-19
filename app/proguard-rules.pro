# These classes are looked up by hard-coded names from the Rust JNI layer.
# R8 must not rename or remove them, and registered native method names must
# stay stable for JNI_OnLoad registration.
-keep class com.example.comicdav.nativebridge.ComicNative { *; }
-keep class com.example.comicdav.nativebridge.ComicNativeFacade { *; }
-keep class com.example.comicdav.nativebridge.RangeProviderRegistry { *; }
-keepclassmembers class com.example.comicdav.nativebridge.** {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep,allowoptimization class is.xyz.mpv.** { public protected *; }
