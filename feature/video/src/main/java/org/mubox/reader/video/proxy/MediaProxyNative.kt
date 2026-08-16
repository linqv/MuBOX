package org.mubox.reader.video.proxy

/**
 * Injectable JNI boundary for the native media proxy.
 *
 * Keeping the external methods behind this interface lets local JVM tests exercise the complete
 * Kotlin lifecycle without attempting to load the Android shared library.
 */
interface MediaProxyNativeFacade {
    fun proxyCreateV1(
        cacheBytes: Long,
        portStart: Int,
        portEnd: Int,
        headerTimeout: Int,
        maxHeaderBytes: Int,
        maxRequestsPerConnection: Int,
        maxConnections: Int,
    ): Long

    fun proxyStartV1(proxy: Long): Int
    fun proxyCloseV1(proxy: Long)

    fun streamCreateV1(
        proxy: Long,
        bridge: MediaProxyNetworkBridge,
        routeToken: String,
        size: Long,
        mime: String,
        seekEnabled: Boolean,
        prefetchSegments: Int,
    ): Long

    fun streamCloseV1(stream: Long): Boolean
    fun streamStatsV1(stream: Long): String
    fun lastErrorMessageV1(): String
}

object MediaProxyNative : MediaProxyNativeFacade {
    init {
        System.loadLibrary("media_proxy_core")
    }

    external override fun proxyCreateV1(
        cacheBytes: Long,
        portStart: Int,
        portEnd: Int,
        headerTimeout: Int,
        maxHeaderBytes: Int,
        maxRequestsPerConnection: Int,
        maxConnections: Int,
    ): Long

    external override fun proxyStartV1(proxy: Long): Int
    external override fun proxyCloseV1(proxy: Long)

    external override fun streamCreateV1(
        proxy: Long,
        bridge: MediaProxyNetworkBridge,
        routeToken: String,
        size: Long,
        mime: String,
        seekEnabled: Boolean,
        prefetchSegments: Int,
    ): Long

    external override fun streamCloseV1(stream: Long): Boolean
    external override fun streamStatsV1(stream: Long): String
    external override fun lastErrorMessageV1(): String
}

class MediaProxyNativeException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
