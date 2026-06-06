package com.example.comicdav.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {
    val webDav: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()
}