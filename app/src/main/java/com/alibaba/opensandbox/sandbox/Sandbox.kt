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

import com.alibaba.opensandbox.sandbox.config.ConnectionConfig
import com.alibaba.opensandbox.sandbox.domain.models.diagnostics.DiagnosticContent
import com.alibaba.opensandbox.sandbox.domain.models.diagnostics.DiagnosticEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxLifecycle
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxMetrics
import com.alibaba.opensandbox.sandbox.domain.services.Commands
import com.alibaba.opensandbox.sandbox.domain.services.Diagnostics
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem
import com.alibaba.opensandbox.sandbox.domain.services.Health
import com.alibaba.opensandbox.sandbox.domain.services.Metrics
import com.example.agent.core.Artifact
import com.example.agent.core.PathSecurity
import java.io.File
import java.util.UUID

import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.CommandLogs
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.CommandStatus
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunInSessionRequest
import com.alibaba.opensandbox.codeinterpreter.CodeInterpreter
import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.CodeContext
import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.RunCodeRequest
import com.alibaba.opensandbox.codeinterpreter.domain.services.Codes
import com.example.agent.core.ArtifactManager

/**
 * OpenSandbox workspace container.
 * In Aragon, execution is strictly routed to E2B Cloud Firecracker MicroVMs.
 * This class provides workspace filesystem encapsulation and diagnostics.
 */
class Sandbox internal constructor(
  val id: String,
  val baseDir: File,
  val config: ConnectionConfig
) {
  val info: SandboxInfo get() = SandboxInfo(
    id = id,
    image = "opensandbox/runtime:e2b-cloud",
    status = if (health().ping()) "READY" else "UNAVAILABLE",
    lifecycle = SandboxLifecycle(),
    metrics = metrics().getMetrics()
  )

  fun commands(): Commands = object : Commands {
    override fun run(request: RunCommandRequest): Execution {
      throw UnsupportedOperationException("Direct local execution is disabled. Aragon strictly uses E2B Cloud Firecracker MicroVMs.")
    }
    override fun setEnv(key: String, value: String) {}
    override fun interrupt(executionId: String) {}
    override fun getCommandStatus(executionId: String): CommandStatus = CommandStatus(
      id = executionId,
      content = null,
      running = false,
      exitCode = 0,
      error = null
    )
    override fun getBackgroundCommandLogs(executionId: String, cursor: Long?): CommandLogs = CommandLogs(
      content = "",
      cursor = cursor
    )
    override fun createSession(workingDirectory: String?): String = "default"
    override fun runInSession(sessionId: String, request: RunInSessionRequest): Execution {
      throw UnsupportedOperationException("Direct local execution is disabled. Aragon strictly uses E2B Cloud Firecracker MicroVMs.")
    }
    override fun deleteSession(sessionId: String) {}
  }

  fun codeInterpreter(): CodeInterpreter = CodeInterpreter(
    this,
    object : Codes {
      override fun getContext(id: String): CodeContext = CodeContext.builder().id(id).language("python").build()
      override fun listContexts(language: String): List<CodeContext> = emptyList()
      override fun createContext(language: String): CodeContext = CodeContext.builder().id("default").language(language).build()
      override fun deleteContext(id: String) {}
      override fun deleteContexts(language: String) {}
      override fun run(request: RunCodeRequest): Execution {
        throw UnsupportedOperationException("Direct local execution is disabled. Aragon strictly uses E2B Cloud Firecracker MicroVMs.")
      }
      override fun interrupt(executionId: String) {}
      override fun ping(): Boolean = true
    }
  )

  fun listArtifacts(taskId: String? = null): List<Artifact> {
    return ArtifactManager(baseDir).syncWorkspace(taskId, defaultEnvironment = "E2B_CLOUD")
  }

  fun diagnostics(): Diagnostics = object : Diagnostics {
    override fun getDiagnostics(): DiagnosticContent = DiagnosticContent(
      hostname = "e2b-cloud-sandbox",
      os = "Linux",
      kernel = "Firecracker MicroVM",
      architecture = "x86_64",
      cpuCores = 2,
      totalMemoryBytes = 1024L * 1024L * 1024L,
      freeMemoryBytes = 512L * 1024L * 1024L,
      uptimeSeconds = 0L,
      entries = listOf(
        DiagnosticEntry("engine", "E2B Cloud Firecracker MicroVM", "runtime"),
        DiagnosticEntry("backend", "Cloud", "runtime")
      )
    )
  }

  fun metrics(): Metrics = object : Metrics {
    override fun getMetrics(): SandboxMetrics = SandboxMetrics(
      cpuPercent = 0.0,
      memoryBytesUsed = 0L,
      memoryBytesTotal = 1024L * 1024L * 1024L,
      diskBytesUsed = 0L,
      diskBytesTotal = 10L * 1024L * 1024L * 1024L
    )
  }

  fun health(): Health = object : Health {
    override fun ping(): Boolean = true
  }

  fun resolveSafe(relativePath: String): File = PathSecurity.resolveSafe(baseDir, relativePath)
  fun getRelativePath(file: File): String = PathSecurity.getRelativePath(baseDir, file)

  fun writeWorkspaceFile(relativePath: String, content: String): File {
    val file = resolveSafe(relativePath)
    file.parentFile?.mkdirs()
    file.writeText(content, Charsets.UTF_8)
    return file
  }

  fun copyFile(sourcePath: String, destPath: String): File {
    val src = resolveSafe(sourcePath)
    val dst = resolveSafe(destPath)
    dst.parentFile?.mkdirs()
    src.copyTo(dst, overwrite = true)
    return dst
  }

  fun moveFile(sourcePath: String, destPath: String): File {
    val src = resolveSafe(sourcePath)
    val dst = resolveSafe(destPath)
    dst.parentFile?.mkdirs()
    if (!src.renameTo(dst)) {
      src.copyTo(dst, overwrite = true)
      src.delete()
    }
    return dst
  }

  fun cleanTemporaryFiles() {
    try {
      val tmpDir = File(baseDir, "tmp")
      if (tmpDir.exists() && tmpDir.isDirectory) {
        tmpDir.listFiles()?.forEach { it.deleteRecursively() }
      }
    } catch (_: Exception) {}
  }

  fun close() {
    // Session cleanup handled via E2BSandboxProvider
  }

  fun renew() {
    // Session renew handled via E2BSandboxProvider
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
    private var id: String = "e2b-" + UUID.randomUUID().toString().take(12)
    private var baseDir: File? = null
    private var endpoint: String = ""
    private var apiKey: String? = null
    private var image: String = "e2b/microvm:latest"

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
      val targetDir = baseDir ?: File("/tmp/agent_workspace").apply { mkdirs() }
      val config = ConnectionConfig(
        endpoint = endpoint,
        apiKey = apiKey
      )
      return Sandbox(
        id = id,
        baseDir = targetDir,
        config = config
      )
    }
  }
}
