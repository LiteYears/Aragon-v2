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
package com.alibaba.opensandbox.sandbox.domain.models.sandboxes

import java.time.OffsetDateTime

/**
 * Endpoint address and port for accessing sandbox services.
 */
data class SandboxEndpoint(
  val host: String,
  val port: Int,
  val protocol: String = "http",
) {
  fun url(): String = "$protocol://$host:$port"
}

/**
 * Sandbox lifecycle status and timing information.
 */
data class SandboxLifecycle(
  val status: String = "RUNNING",
  val createdAt: OffsetDateTime = OffsetDateTime.now(),
  val expiresAt: OffsetDateTime? = null,
  val uptimeSeconds: Long = 0L,
)

/**
 * Resource utilization metrics for a sandbox.
 */
data class SandboxMetrics(
  val cpuPercent: Double = 0.0,
  val memoryBytesUsed: Long = 0L,
  val memoryBytesTotal: Long = 0L,
  val diskBytesUsed: Long = 0L,
  val diskBytesTotal: Long = 0L,
)

/**
 * Complete metadata and status information for a sandbox instance.
 */
data class SandboxInfo(
  val id: String,
  val image: String = "opensandbox/runtime:latest",
  val status: String = "READY",
  val lifecycle: SandboxLifecycle = SandboxLifecycle(),
  val metrics: SandboxMetrics = SandboxMetrics(),
  val endpoints: Map<Int, SandboxEndpoint> = emptyMap(),
  val metadata: Map<String, String> = emptyMap(),
)
