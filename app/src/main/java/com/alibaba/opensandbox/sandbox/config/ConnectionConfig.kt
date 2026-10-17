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
 * Supported Sandbox Backend Runtimes in Aragon:
 * - E2B_CLOUD: Cloud Firecracker MicroVMs via E2B (https://github.com/e2b-dev)
 * This is the sole execution sandbox backend.
 */
enum class SandboxBackendType {
  E2B_CLOUD
}

/**
 * Connection and runtime configuration for E2B Cloud Sandbox providers.
 */
data class ConnectionConfig(
  val endpoint: String = "https://api.e2b.app",
  val apiKey: String? = null,
  val requestTimeout: Duration = Duration.ofSeconds(60),
  val connectTimeout: Duration = Duration.ofSeconds(15),
  val maxIdleConnections: Int = 10,
  val userAgent: String = "Aragon-Agent-Kernel/2.0 (E2B)",
  val backendType: SandboxBackendType = SandboxBackendType.E2B_CLOUD,
  val e2bTemplate: String = "base",
  val e2bDomain: String = "e2b.app"
) {
  val isRemote: Boolean get() = true
  val isE2B: Boolean get() = true
  val mode: com.example.agent.core.SandboxMode get() = com.example.agent.core.SandboxMode.E2B_CLOUD

  companion object {
    val DEFAULT_E2B = ConnectionConfig(
      endpoint = "https://api.e2b.app",
      backendType = SandboxBackendType.E2B_CLOUD,
      e2bTemplate = "base",
      e2bDomain = "e2b.app"
    )
  }
}

