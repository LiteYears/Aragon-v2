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
package com.alibaba.opensandbox.sandbox

import com.alibaba.opensandbox.codeinterpreter.CodeInterpreter
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxLifecycle
import com.alibaba.opensandbox.sandbox.domain.services.Commands
import com.alibaba.opensandbox.sandbox.domain.services.Diagnostics
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem
import com.alibaba.opensandbox.sandbox.domain.services.Health
import com.alibaba.opensandbox.sandbox.domain.services.Metrics
import com.alibaba.opensandbox.sandbox.infrastructure.OpenSandboxRuntime
import com.example.agent.core.Artifact
import java.io.File
import java.util.UUID

/**
 * Main entry point for the OpenSandbox SDK providing secure, isolated execution environments.
 */
class Sandbox internal constructor(
  val id: String,
  val baseDir: File,
  val config: ConnectionConfig,
  private val runtime: OpenSandboxRuntime
) {
  val info: SandboxInfo get() = SandboxInfo(
    id = id,
    image = "opensandbox/runtime:latest",
    status = if (health().ping()) "READY" else "UNAVAILABLE",
    lifecycle = SandboxLifecycle(),
    metrics = metrics().getMetrics()
  )

  fun commands(): Commands = runtime
  fun filesystem(): Filesystem = runtime
  fun diagnostics(): Diagnostics = runtime
  fun metrics(): Metrics = runtime
  fun health(): Health = runtime

  private val codeInterpreterInstance: CodeInterpreter by lazy {
    CodeInterpreter(this, runtime)
  }

  fun codeInterpreter(): CodeInterpreter = codeInterpreterInstance

  fun listArtifacts(): List<Artifact> = runtime.listArtifacts()
  fun cleanAllArtifacts() = runtime.cleanAllArtifacts()
  fun seedWorkspaceDefaults() = runtime.seedWorkspaceDefaults()
  fun resolveSafe(relativePath: String): File = runtime.resolveSafe(relativePath)
  fun writeWorkspaceFile(relativePath: String, content: String): File = runtime.writeWorkspaceFile(relativePath, content)
  fun copyFile(sourcePath: String, destPath: String): File = runtime.copyFile(sourcePath, destPath)
  fun moveFile(sourcePath: String, destPath: String): File = runtime.moveFile(sourcePath, destPath)
  fun getRelativePath(file: File): String = runtime.getRelativePath(file)
  fun createArtifactFromFile(file: File, toolCallId: String? = null): Artifact = runtime.createArtifactFromFile(file, toolCallId)
  fun listAllArtifacts(): List<Artifact> = runtime.listArtifacts()
  fun cleanTemporaryFiles() = runtime.cleanTemporaryFiles()

  fun close() {
    runtime.close()
  }

  fun renew() {
    // Keep alive
  }

  fun getEndpoint(port: Int): SandboxEndpoint {
    val host = if (config.isRemote) {
      val uri = java.net.URI(config.endpoint)
      uri.host ?: "127.0.0.1"
    } else "127.0.0.1"
    return SandboxEndpoint(host = host, port = port)
  }

  companion object {
    @JvmStatic
    fun builder(): Builder = Builder()
  }

  class Builder {
    private var id: String = "osb-" + UUID.randomUUID().toString().take(12)
    private var baseDir: File? = null
    private var endpoint: String = ""
    private var apiKey: String? = null
    private var image: String = "opensandbox/runtime:latest"

    fun id(id: String): Builder {
      this.id = id
      return this
    }

    fun baseDir(baseDir: File): Builder {
      this.baseDir = baseDir
      return this
    }

    fun endpoint(endpoint: String): Builder {
      this.endpoint = endpoint
      return this
    }

    fun apiKey(apiKey: String?): Builder {
      this.apiKey = apiKey
      return this
    }

    fun image(image: String): Builder {
      this.image = image
      return this
    }

    fun build(): Sandbox {
      val targetDir = baseDir ?: File("/tmp/opensandbox_workspace").apply { mkdirs() }
      val config = ConnectionConfig(
        endpoint = endpoint,
        apiKey = apiKey
      )
      val runtime = OpenSandboxRuntime(sandboxId = id, baseDir = targetDir, config = config)
      return Sandbox(
        id = id,
        baseDir = targetDir,
        config = config,
        runtime = runtime
      )
    }
  }
}
