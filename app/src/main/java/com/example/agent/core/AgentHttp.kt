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
      .connectTimeout(25, TimeUnit.SECONDS)
      .readTimeout(90, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }
}
