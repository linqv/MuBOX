package org.mubox.reader.video.proxy

internal class FakeMediaProxyNative : MediaProxyNativeFacade {
    val proxyCreateCalls = mutableListOf<ProxyCreateCall>()
    val proxyStartCalls = mutableListOf<Long>()
    val proxyCloseCalls = mutableListOf<Long>()
    val streamCreateCalls = mutableListOf<StreamCreateCall>()
    val streamCloseCalls = mutableListOf<Long>()
    val statsByStream = mutableMapOf<Long, String>()

    var boundPort: Int = 38_421
    var lastError: String = "native test failure"
    var failProxyCreate = false
    var failProxyStart = false
    var failStreamCreateAt: Int? = null
    var streamCloseResult = true

    private var nextProxyHandle = 10L
    private var nextStreamHandle = 100L

    override fun proxyCreateV1(
        cacheBytes: Long,
        portStart: Int,
        portEnd: Int,
        headerTimeout: Int,
        maxHeaderBytes: Int,
        maxRequestsPerConnection: Int,
        maxConnections: Int,
    ): Long {
        proxyCreateCalls += ProxyCreateCall(
            cacheBytes = cacheBytes,
            portStart = portStart,
            portEnd = portEnd,
            headerTimeout = headerTimeout,
            maxHeaderBytes = maxHeaderBytes,
            maxRequestsPerConnection = maxRequestsPerConnection,
            maxConnections = maxConnections,
        )
        return if (failProxyCreate) 0L else nextProxyHandle++
    }

    override fun proxyStartV1(proxy: Long): Int {
        proxyStartCalls += proxy
        return if (failProxyStart) -1 else boundPort
    }

    override fun proxyCloseV1(proxy: Long) {
        proxyCloseCalls += proxy
    }

    override fun streamCreateV1(
        proxy: Long,
        bridge: MediaProxyNetworkBridge,
        routeToken: String,
        size: Long,
        mime: String,
        seekEnabled: Boolean,
        prefetchSegments: Int,
    ): Long {
        val attempt = streamCreateCalls.size + 1
        val handle = if (failStreamCreateAt == attempt) 0L else nextStreamHandle++
        streamCreateCalls += StreamCreateCall(
            proxy = proxy,
            bridge = bridge,
            routeToken = routeToken,
            size = size,
            mime = mime,
            seekEnabled = seekEnabled,
            prefetchSegments = prefetchSegments,
            returnedHandle = handle,
        )
        return handle
    }

    override fun streamCloseV1(stream: Long): Boolean {
        streamCloseCalls += stream
        return streamCloseResult
    }

    override fun streamStatsV1(stream: Long): String = statsByStream[stream].orEmpty()

    override fun lastErrorMessageV1(): String = lastError

    data class ProxyCreateCall(
        val cacheBytes: Long,
        val portStart: Int,
        val portEnd: Int,
        val headerTimeout: Int,
        val maxHeaderBytes: Int,
        val maxRequestsPerConnection: Int,
        val maxConnections: Int,
    )

    data class StreamCreateCall(
        val proxy: Long,
        val bridge: MediaProxyNetworkBridge,
        val routeToken: String,
        val size: Long,
        val mime: String,
        val seekEnabled: Boolean,
        val prefetchSegments: Int,
        val returnedHandle: Long,
    )
}
