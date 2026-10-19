package com.example.agent.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared, singleton OkHttpClient to eliminate redundant connection pools,
 * dispatcher thread pools, and unnecessary memory allocations across the app.
 */
object AgentHttp {
  val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }
}
