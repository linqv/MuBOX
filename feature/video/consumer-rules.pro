# Rust registers the native method table against this exact JVM class name.
-keep,allowoptimization class org.mubox.reader.video.proxy.MediaProxyNative {
    native <methods>;
}

# Rust invokes these stream-scoped callbacks by exact method name and signature.
-keepclassmembers,allowoptimization class org.mubox.reader.video.proxy.MediaProxyNetworkBridge {
    public long[] headV1();
    public long[] openFetchV1(long, long, long, int);
    public int readFetchIntoV1(long, java.nio.ByteBuffer);
    public void cancelFetchV1(long);
    public void closeFetchV1(long);
}
