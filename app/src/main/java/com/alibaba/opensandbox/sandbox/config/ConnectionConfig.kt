/*
 * Copyright 2025 The OpenSandbox Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.opensandbox.sandbox.config

import java.time.Duration

/**
 * OpenSandbox connection configuration.
 *
 * Supports both:
 * 1. Embedded local sandbox execution runtime (empty or local endpoint)
 * 2. Remote OpenSandbox daemon/server runtime (e.g., http://localhost:44772 or custom daemon endpoint)
 */
data class ConnectionConfig(
  val endpoint: String = "",
  val apiKey: String? = null,
  val requestTimeout: Duration = Duration.ofSeconds(60),
  val connectTimeout: Duration = Duration.ofSeconds(15),
  val maxIdleConnections: Int = 10,
  val userAgent: String = "OpenSandbox-SDK-Kotlin/1.0.0",
) {
  val isRemote: Boolean get() = endpoint.isNotBlank() && (endpoint.startsWith("http://") || endpoint.startsWith("https://"))

  companion object {
    val DEFAULT_EMBEDDED = ConnectionConfig()
  }
}
