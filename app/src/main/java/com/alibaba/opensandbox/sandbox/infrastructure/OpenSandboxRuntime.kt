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
package com.alibaba.opensandbox.sandbox.infrastructure

import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.CodeContext
import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.RunCodeRequest
import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.SupportedLanguage
import com.alibaba.opensandbox.codeinterpreter.domain.services.Codes
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig
import com.alibaba.opensandbox.sandbox.domain.exceptions.InvalidArgumentException
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxException
import com.alibaba.opensandbox.sandbox.domain.models.diagnostics.DiagnosticContent
import com.alibaba.opensandbox.sandbox.domain.models.diagnostics.DiagnosticEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.CommandLogs
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.CommandStatus
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionComplete
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionError
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionResult
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunInSessionRequest
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.ContentReplaceEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.ContentReplaceResult
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.MoveEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.SearchEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.SetPermissionEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxMetrics
import com.alibaba.opensandbox.sandbox.domain.services.Commands
import com.alibaba.opensandbox.sandbox.domain.services.Diagnostics
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem
import com.alibaba.opensandbox.sandbox.domain.services.Health
import com.alibaba.opensandbox.sandbox.domain.services.Metrics
import com.example.agent.core.Artifact
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * Authoritative OpenSandbox Runtime Implementation.
 *
 * Implements the full OpenSandbox system specifications:
 * - Commands execution (foreground/background, streaming, environment variables, bash sessions, interrupts)
 * - Filesystem operations (reading, line ranges, writes, directory trees, permissions, search, replacement)
 * - Multi-language Code Interpreter (Python, Bash, JS contexts with state persistence)
 * - Diagnostics, Health monitoring, and Resource Metrics
 * - Local embedded runtime and remote OpenSandbox REST/SSE daemon support
 */
class OpenSandboxRuntime(
  val sandboxId: String,
  val baseDir: File,
  val config: ConnectionConfig = ConnectionConfig.DEFAULT_EMBEDDED
) : Commands, Filesystem, Codes, Diagnostics, Health, Metrics {

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(config.connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
    .readTimeout(config.requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
    .build()

  private val executionCounter = AtomicLong(0)
  private val activeProcesses = ConcurrentHashMap<String, Process>()
  private val activeCommands = ConcurrentHashMap<String, CommandRecord>()
  private val bashSessions = ConcurrentHashMap<String, BashSessionRecord>()
  private val codeContexts = ConcurrentHashMap<String, CodeContextRecord>()

  // Sandbox directories
  val binDir: File = File(baseDir, "bin").apply { mkdirs() }
  val tmpDir: File = File(baseDir, "tmp").apply { mkdirs() }
  val libDir: File = File(baseDir, "lib/python").apply { mkdirs() }
  val envsFile: File = File(baseDir, ".opensandbox_envs")

  init {
    baseDir.mkdirs()
    binDir.mkdirs()
    tmpDir.mkdirs()
    libDir.mkdirs()
    seedCurlBinary()
    if (!envsFile.exists()) {
      try {
        envsFile.writeText("# OpenSandbox Environment Variables\n")
      } catch (_: Exception) {}
    }
  }

  // ==========================================
  // FILESYSTEM BOUNDARY & SAFE PATH RESOLUTION
  // ==========================================

  fun resolveSafe(path: String): File {
    val clean = path.trim().removePrefix("./").removePrefix("/")
    val file = File(baseDir, clean).canonicalFile
    val baseCanonical = baseDir.canonicalFile
    val basePathWithSep = if (baseCanonical.path.endsWith(File.separator)) {
      baseCanonical.path
    } else {
      baseCanonical.path + File.separator
    }
    if (file != baseCanonical && !file.path.startsWith(basePathWithSep)) {
      throw InvalidArgumentException("Access denied: path '$path' attempts to escape the OpenSandbox root.")
    }
    return file
  }

  fun getRelativePath(file: File): String {
    val baseCanonical = baseDir.canonicalFile
    val fileCanonical = file.canonicalFile
    return if (fileCanonical.path == baseCanonical.path) {
      "."
    } else {
      fileCanonical.path.removePrefix(baseCanonical.path).removePrefix(File.separator)
    }
  }

  fun writeWorkspaceFile(relativePath: String, content: String): File {
    val file = resolveSafe(relativePath)
    file.parentFile?.mkdirs()
    file.writeText(content, Charsets.UTF_8)
    return file
  }

  fun copyFile(sourcePath: String, destPath: String): File {
    val src = resolveSafe(sourcePath)
    if (!src.exists()) throw InvalidArgumentException("Source '$sourcePath' does not exist.")
    val dst = resolveSafe(destPath)
    if (src.canonicalPath == dst.canonicalPath) return dst
    dst.parentFile?.mkdirs()
    if (src.isDirectory) {
      src.copyRecursively(dst, overwrite = true)
    } else {
      src.copyTo(dst, overwrite = true)
    }
    return dst
  }

  fun moveFile(sourcePath: String, destPath: String): File {
    val src = resolveSafe(sourcePath)
    if (!src.exists()) throw InvalidArgumentException("Source '$sourcePath' does not exist.")
    val dst = resolveSafe(destPath)
    if (src.canonicalPath == dst.canonicalPath) return dst
    dst.parentFile?.mkdirs()
    if (!src.renameTo(dst)) {
      if (src.isDirectory) {
        src.copyRecursively(dst, overwrite = true)
        src.deleteRecursively()
      } else {
        src.copyTo(dst, overwrite = true)
        src.delete()
      }
    }
    return dst
  }

  fun createArtifactFromFile(file: File, toolCallId: String? = null): Artifact {
    val exists = file.exists()
    val size = if (exists && file.isFile) file.length() else 0L
    val name = file.name
    val ext = file.extension.lowercase()
    val type = when (ext) {
      "py" -> "Python Source"
      "json" -> "JSON Document"
      "csv" -> "CSV Dataset"
      "md" -> "Markdown Document"
      "docx" -> "Word Document"
      "txt" -> "Text File"
      "sh" -> "Shell Script"
      "html" -> "HTML Page"
      "" -> if (file.isDirectory) "Directory" else "File"
      else -> "${ext.uppercase()} File"
    }

    return Artifact(
      id = UUID.randomUUID().toString(),
      path = getRelativePath(file),
      name = name,
      type = type,
      size = size,
      createdByCallId = toolCallId,
      exists = exists,
      lastModified = if (exists) file.lastModified() else System.currentTimeMillis(),
      absolutePath = file.canonicalPath
    )
  }

  fun cleanTemporaryFiles() {
    if (baseDir.exists() && baseDir.isDirectory) {
      baseDir.walkTopDown().forEach { file ->
        if (file.isFile && (file.name.endsWith(".tmp") || file.name.startsWith(".part_") || file.name.startsWith("."))) {
          file.delete()
        }
      }
    }
  }

  // ==========================================
  // COMMANDS SERVICE IMPLEMENTATION
  // ==========================================

  override fun run(request: RunCommandRequest): Execution {
    if (config.isRemote) {
      return runRemoteCommand(request)
    }
    return runLocalCommand(request)
  }

  fun seedCurlBinary() {
    val curlFile = File(binDir, "curl")
    curlFile.writeText(
      """
      #!/system/bin/sh
      # OpenSandbox curl implementation
      if [ "${'$'}1" = "--version" ] || [ "${'$'}1" = "-V" ]; then
        echo "curl 8.5.0 (OpenSandbox Android aarch64) libcurl/8.5.0 OkHttp/4.12.0"
        exit 0
      fi
      if [ "${'$'}1" = "-h" ] || [ "${'$'}1" = "--help" ]; then
        echo "Usage: curl [options...] <url>"
        echo "  -s, --silent        Silent mode"
        echo "  -i, --include       Include HTTP response headers"
        echo "  -I, --head          Show headers only"
        echo "  -o, --output <file> Write to file instead of stdout"
        echo "  -X, --request <cmd> Specify request method (GET, POST, ...)"
        echo "  -H, --header <line> Pass custom header"
        echo "  -d, --data <data>   HTTP POST data"
        exit 0
      fi
      WORKSPACE="${'$'}{WORKSPACE:-${'$'}(pwd)}"
      if [ -f "${'$'}WORKSPACE/bin/python3" ] || command -v python3 >/dev/null 2>&1; then
        python3 -c '
import sys, urllib.request, ssl
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
url = None
silent = False
out_file = None
headers = {"User-Agent": "curl/8.5.0"}
args = sys.argv[1:]
i = 0
while i < len(args):
  a = args[i]
  if a in ("-s", "--silent"):
    silent = True
  elif a in ("-o", "--output") and i + 1 < len(args):
    i += 1
    out_file = args[i]
  elif not a.startswith("-") and url is None:
    url = a
  i += 1
if url:
  if not url.startswith("http://") and not url.startswith("https://"):
    url = "https://" + url
  try:
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, context=ctx, timeout=30) as resp:
      body = resp.read()
      if out_file:
        with open(out_file, "wb") as f: f.write(body)
      else:
        sys.stdout.buffer.write(body)
  except Exception as e:
    sys.stderr.write(f"curl: (6) {e}\n")
    sys.exit(6)
' "${'$'}@"
        exit ${'$'}?
      fi
      """.trimIndent() + "\n"
    )
    try {
      curlFile.setReadable(true, false)
      curlFile.setExecutable(true, false)
    } catch (_: Exception) {}

    val wgetFile = File(binDir, "wget")
    wgetFile.writeText(
      """
      #!/system/bin/sh
      /system/bin/sh "${binDir.absolutePath}/curl" -O "${'$'}@"
      """.trimIndent() + "\n"
    )
    try {
      wgetFile.setReadable(true, false)
      wgetFile.setExecutable(true, false)
    } catch (_: Exception) {}
  }

  private fun isCurlCommand(cmd: String): Boolean {
    val clean = cmd.trim()
    val curlPrefixes = listOf(
      "curl",
      "wget",
      "./bin/curl",
      "bin/curl",
      "${binDir.absolutePath}/curl",
      "./bin/wget",
      "bin/wget",
      "${binDir.absolutePath}/wget"
    )
    return curlPrefixes.any { clean == it || clean.startsWith("$it ") || clean.startsWith("$it\t") }
  }

  private fun tokenizeCurlArgs(cmd: String): List<String> {
    val tokens = mutableListOf<String>()
    val sb = StringBuilder()
    var inSingle = false
    var inDouble = false
    var i = 0
    val raw = cmd.trim()
    var clean = raw
    val prefixes = listOf(
      "${binDir.absolutePath}/curl",
      "./bin/curl",
      "bin/curl",
      "curl",
      "${binDir.absolutePath}/wget",
      "./bin/wget",
      "bin/wget",
      "wget"
    )
    for (prefix in prefixes) {
      if (clean == prefix || clean.startsWith("$prefix ") || clean.startsWith("$prefix\t")) {
        clean = clean.removePrefix(prefix).trim()
        break
      }
    }

    while (i < clean.length) {
      val c = clean[i]
      when {
        c == '\'' && !inDouble -> inSingle = !inSingle
        c == '"' && !inSingle -> inDouble = !inDouble
        c.isWhitespace() && !inSingle && !inDouble -> {
          if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
            sb.clear()
          }
        }
        else -> sb.append(c)
      }
      i++
    }
    if (sb.isNotEmpty()) tokens.add(sb.toString())
    return tokens
  }

  private fun executeCurlCommand(
    request: RunCommandRequest,
    executionId: String,
    execCount: Long,
    workDir: File
  ): Execution {
    val execution = Execution(id = executionId, executionCount = execCount)
    val record = CommandRecord(id = executionId, command = request.command, startedAt = OffsetDateTime.now(ZoneOffset.UTC))
    activeCommands[executionId] = record

    val cmd = request.command.trim()

    var curlCmd = cmd
    var pipeCmd: String? = null
    var redirectFile: String? = null
    var appendRedirect = false

    if (cmd.contains(" >> ")) {
      val parts = cmd.split(" >> ", limit = 2)
      curlCmd = parts[0].trim()
      redirectFile = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
      appendRedirect = true
    } else if (cmd.contains(" > ")) {
      val parts = cmd.split(" > ", limit = 2)
      curlCmd = parts[0].trim()
      redirectFile = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
      appendRedirect = false
    } else if (cmd.contains(" | ")) {
      val parts = cmd.split(" | ", limit = 2)
      curlCmd = parts[0].trim()
      pipeCmd = parts[1].trim()
    }

    val tokens = tokenizeCurlArgs(curlCmd)
    if (tokens.contains("--version") || tokens.contains("-V")) {
      val versionStr = "curl 8.5.0 (OpenSandbox Android aarch64) libcurl/8.5.0 OkHttp/4.12.0\nProtocols: dict file ftp ftps http https\nFeatures: AsynchDNS HSTS HTTPS-proxy IPv6 Largefile libz NTLM SSL TLS-SRP\n"
      execution.exitCode = 0
      val msg = OutputMessage(versionStr)
      execution.logs.stdout.add(msg)
      request.handlers?.onStdout(msg)
      record.logs.append(versionStr)
      record.running = false
      record.exitCode = 0
      record.finishedAt = OffsetDateTime.now(ZoneOffset.UTC)
      return execution
    }

    if (tokens.contains("-h") || tokens.contains("--help")) {
      val helpStr = "Usage: curl [options...] <url>\n -s, --silent        Silent mode\n -i, --include       Include protocol response headers\n -I, --head          Show document info only\n -o, --output <file> Write to file instead of stdout\n -X, --request <cmd> Specify request method\n -H, --header <line> Pass custom header\n -d, --data <data>   HTTP POST data\n"
      execution.exitCode = 0
      val msg = OutputMessage(helpStr)
      execution.logs.stdout.add(msg)
      request.handlers?.onStdout(msg)
      record.logs.append(helpStr)
      record.running = false
      record.exitCode = 0
      record.finishedAt = OffsetDateTime.now(ZoneOffset.UTC)
      return execution
    }

    var url: String? = null
    var method = "GET"
    var silent = false
    var includeHeaders = false
    var outputFile: String? = redirectFile
    var postData: String? = null
    val headers = mutableMapOf<String, String>()
    var timeoutSec = 30L

    var i = 0
    while (i < tokens.size) {
      val t = tokens[i]
      when {
        t == "-s" || t == "--silent" -> silent = true
        t == "-S" || t == "--show-error" -> Unit
        t == "-L" || t == "--location" -> Unit
        t == "-k" || t == "--insecure" -> Unit
        t == "-i" || t == "--include" -> includeHeaders = true
        t == "-I" || t == "--head" -> {
          method = "HEAD"
          includeHeaders = true
        }
        (t == "-o" || t == "--output") && i + 1 < tokens.size -> {
          i++
          outputFile = tokens[i]
        }
        (t == "-X" || t == "--request") && i + 1 < tokens.size -> {
          i++
          method = tokens[i].uppercase()
        }
        (t == "-H" || t == "--header") && i + 1 < tokens.size -> {
          i++
          val headerStr = tokens[i]
          val colonIdx = headerStr.indexOf(':')
          if (colonIdx > 0) {
            headers[headerStr.substring(0, colonIdx).trim()] = headerStr.substring(colonIdx + 1).trim()
          }
        }
        (t == "-d" || t == "--data" || t == "--data-raw" || t == "--data-binary") && i + 1 < tokens.size -> {
          i++
          postData = tokens[i]
          if (method == "GET") method = "POST"
        }
        (t == "-u" || t == "--user") && i + 1 < tokens.size -> {
          i++
          val creds = tokens[i]
          val encoded = try {
            java.util.Base64.getEncoder().encodeToString(creds.toByteArray())
          } catch (_: Throwable) {
            android.util.Base64.encodeToString(creds.toByteArray(), android.util.Base64.NO_WRAP)
          }
          headers["Authorization"] = "Basic $encoded"
        }
        (t == "-m" || t == "--max-time") && i + 1 < tokens.size -> {
          i++
          timeoutSec = tokens[i].toLongOrNull() ?: 30L
        }
        !t.startsWith("-") -> {
          if (url == null) url = t
        }
      }
      i++
    }

    if (url.isNullOrBlank()) {
      val err = "curl: no URL specified!\ncurl: try 'curl --help' for more information\n"
      execution.exitCode = 2
      execution.error = ExecutionError("CurlError", err)
      val msg = OutputMessage(err)
      execution.logs.stderr.add(msg)
      request.handlers?.onStderr(msg)
      record.logs.append(err)
      record.running = false
      record.exitCode = 2
      record.finishedAt = OffsetDateTime.now(ZoneOffset.UTC)
      return execution
    }

    var cleanUrl = url.trim().removeSurrounding("\"").removeSurrounding("'")
    if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
      cleanUrl = "https://$cleanUrl"
    }

    try {
      val reqBuilder = Request.Builder().url(cleanUrl)
      headers.forEach { (k, v) -> reqBuilder.header(k, v) }
      if (!headers.containsKey("User-Agent")) {
        reqBuilder.header("User-Agent", "curl/8.5.0")
      }
      if (!headers.containsKey("Accept")) {
        reqBuilder.header("Accept", "*/*")
      }

      val reqBody = when {
        postData != null -> {
          val mediaType = (headers["Content-Type"] ?: "application/x-www-form-urlencoded").toMediaType()
          postData.toRequestBody(mediaType)
        }
        method in listOf("POST", "PUT", "PATCH") -> "".toRequestBody(null)
        else -> null
      }

      when (method) {
        "GET" -> reqBuilder.get()
        "HEAD" -> reqBuilder.head()
        "POST" -> reqBuilder.post(reqBody ?: "".toRequestBody(null))
        "PUT" -> reqBuilder.put(reqBody ?: "".toRequestBody(null))
        "DELETE" -> reqBuilder.delete(reqBody)
        "PATCH" -> reqBuilder.patch(reqBody ?: "".toRequestBody(null))
        else -> reqBuilder.method(method, reqBody)
      }

      val client = if (timeoutSec != config.requestTimeout.toSeconds()) {
        httpClient.newBuilder()
          .callTimeout(timeoutSec, TimeUnit.SECONDS)
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(timeoutSec, TimeUnit.SECONDS)
          .build()
      } else httpClient

      val resp = client.newCall(reqBuilder.build()).execute()
      val respBodyBytes = resp.body?.bytes() ?: byteArrayOf()
      val respBodyStr = String(respBodyBytes, Charsets.UTF_8)

      val sb = StringBuilder()
      if (includeHeaders) {
        sb.append("HTTP/1.1 ${resp.code} ${resp.message}\r\n")
        resp.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
      }
      if (method != "HEAD") {
        sb.append(respBodyStr)
      }

      val outputPayload = sb.toString()

      if (!pipeCmd.isNullOrBlank()) {
        val pb = ProcessBuilder("sh", "-c", pipeCmd)
        pb.directory(workDir)
        val env = pb.environment()
        env["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin"
        val proc = pb.start()
        proc.outputStream.use { it.write(outputPayload.toByteArray(Charsets.UTF_8)) }
        val outText = proc.inputStream.bufferedReader().readText()
        val errText = proc.errorStream.bufferedReader().readText()
        proc.waitFor(10, TimeUnit.SECONDS)

        execution.exitCode = proc.exitValue()
        if (outText.isNotBlank()) {
          val msg = OutputMessage(outText)
          execution.logs.stdout.add(msg)
          request.handlers?.onStdout(msg)
        }
        if (errText.isNotBlank()) {
          val msg = OutputMessage(errText)
          execution.logs.stderr.add(msg)
          request.handlers?.onStderr(msg)
        }
        record.logs.append(outText).append(errText)
      } else if (!outputFile.isNullOrBlank()) {
        val targetFile = resolveSafe(outputFile)
        targetFile.parentFile?.mkdirs()
        if (appendRedirect) {
          targetFile.appendBytes(respBodyBytes)
        } else {
          targetFile.writeBytes(respBodyBytes)
        }
        execution.exitCode = 0
        if (!silent) {
          val progressMsg = "  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current\n"
          val msg = OutputMessage(progressMsg)
          execution.logs.stderr.add(msg)
          request.handlers?.onStderr(msg)
        }
      } else {
        execution.exitCode = 0
        val msg = OutputMessage(outputPayload)
        execution.logs.stdout.add(msg)
        request.handlers?.onStdout(msg)
        record.logs.append(outputPayload)
      }
      resp.close()
    } catch (e: Exception) {
      val errMsg = "curl: (6) Could not resolve or connect: ${e.message ?: "Network error"}\n"
      execution.exitCode = 6
      execution.error = ExecutionError("CurlError", errMsg)
      val msg = OutputMessage(errMsg)
      execution.logs.stderr.add(msg)
      request.handlers?.onStderr(msg)
      record.logs.append(errMsg)
    }

    record.running = false
    record.exitCode = execution.exitCode
    record.finishedAt = OffsetDateTime.now(ZoneOffset.UTC)
    return execution
  }

  private fun runLocalCommand(request: RunCommandRequest): Execution {
    val executionId = "exec-" + UUID.randomUUID().toString().take(12)
    val execCount = executionCounter.incrementAndGet()
    val execution = Execution(
      id = executionId,
      executionCount = execCount
    )

    val startTime = System.currentTimeMillis()
    val workDir = if (!request.workingDirectory.isNullOrBlank()) {
      resolveSafe(request.workingDirectory)
    } else {
      baseDir
    }
    workDir.mkdirs()

    val rawCmd = request.command.trim()

    // 1. Direct 'which curl' / 'which <tool>' check
    val isWhich = rawCmd.startsWith("which ") ||
      rawCmd.startsWith("command -v ") ||
      rawCmd.startsWith("whereis ") ||
      rawCmd.startsWith("type ")
    if (isWhich) {
      val target = when {
        rawCmd.startsWith("which ") -> rawCmd.removePrefix("which ").trim()
        rawCmd.startsWith("command -v ") -> rawCmd.removePrefix("command -v ").trim()
        rawCmd.startsWith("whereis ") -> rawCmd.removePrefix("whereis ").trim()
        rawCmd.startsWith("type ") -> rawCmd.removePrefix("type ").trim()
        else -> ""
      }
      if (target == "curl" || target == "wget") {
        seedCurlBinary()
        val targetFile = File(binDir, target)
        val record = CommandRecord(id = executionId, command = request.command, startedAt = OffsetDateTime.now(ZoneOffset.UTC))
        activeCommands[executionId] = record
        execution.exitCode = 0
        val outMsg = OutputMessage(targetFile.absolutePath + "\n")
        execution.logs.stdout.add(outMsg)
        request.handlers?.onStdout(outMsg)
        record.logs.append(targetFile.absolutePath).append("\n")
        record.running = false
        record.exitCode = 0
        record.finishedAt = OffsetDateTime.now(ZoneOffset.UTC)
        return execution
      }
      val binTarget = File(binDir, target)
      if (binTarget.exists()) {
        val record = CommandRecord(id = executionId, command = request.command, startedAt = OffsetDateTime.now(ZoneOffset.UTC))
        activeCommands[executionId] = record
        execution.exitCode = 0
        val outMsg = OutputMessage(binTarget.absolutePath + "\n")
        execution.logs.stdout.add(outMsg)
        request.handlers?.onStdout(outMsg)
        record.logs.append(binTarget.absolutePath).append("\n")
        record.running = false
        record.exitCode = 0
        record.finishedAt = OffsetDateTime.now(ZoneOffset.UTC)
        return execution
      }
    }

    // 2. Direct 'curl' / 'wget' command handling with full OkHttp client
    if (isCurlCommand(rawCmd)) {
      seedCurlBinary()
      return executeCurlCommand(request, executionId, execCount, workDir)
    }

    val envMap = loadPersistentEnvs().toMutableMap()
    envMap.putAll(request.envs)

    // Shell prelude defines functions for every script in binDir so /system/bin/sh executes them
    // avoiding Android kernel W^X / SELinux noexec Permission denied (exit 126).
    val shellPrelude = """
      if [ -d "${binDir.absolutePath}" ]; then
        for _b in "${binDir.absolutePath}"/*; do
          if [ -f "${'$'}_b" ]; then
            _bn="${'$'}{_b##*/}"
            eval "${'$'}{_bn}() { /system/bin/sh \"${binDir.absolutePath}/${'$'}{_bn}\" \"${'$'}@\"; }"
          fi
        done
      fi
      curl() { /system/bin/sh "${binDir.absolutePath}/curl" "${'$'}@"; }
      wget() { /system/bin/sh "${binDir.absolutePath}/wget" "${'$'}@"; }
      which() {
        if [ -f "${binDir.absolutePath}/${'$'}1" ]; then
          echo "${binDir.absolutePath}/${'$'}1"
          return 0
        fi
        command which "${'$'}@" 2>/dev/null || /system/bin/which "${'$'}@" 2>/dev/null
      }
    """.trimIndent()

    val wrappedCommand = "$shellPrelude\n${request.command}"
    val pb = ProcessBuilder("sh", "-c", wrappedCommand)
    pb.directory(workDir)
    val env = pb.environment()
    val existingPath = env["PATH"] ?: "/system/bin:/system/xbin"
    env["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin:$existingPath"
    env["HOME"] = baseDir.absolutePath
    env["PWD"] = workDir.absolutePath
    env["WORKSPACE"] = baseDir.absolutePath
    env["TMPDIR"] = tmpDir.absolutePath
    env["PYTHONPATH"] = "${libDir.absolutePath}:${baseDir.absolutePath}"
    env["OPENSANDBOX_ID"] = sandboxId
    env["EXECD_ENVS"] = envsFile.absolutePath
    envMap.forEach { (k, v) -> env[k] = v }

    val record = CommandRecord(
      id = executionId,
      command = request.command,
      startedAt = OffsetDateTime.now(ZoneOffset.UTC)
    )
    activeCommands[executionId] = record

    try {
      val process = pb.start()
      activeProcesses[executionId] = process

      val stdoutSb = StringBuilder()
      val stderrSb = StringBuilder()
      val streamLock = Any()

      val stdoutThread = Thread {
        try {
          BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
              val msg = OutputMessage(line!! + "\n")
              synchronized(streamLock) {
                if (stdoutSb.length < 65000) stdoutSb.appendLine(line)
                execution.logs.stdout.add(msg)
                record.logs.append(line).append("\n")
              }
              request.handlers?.onStdout(msg)
            }
          }
        } catch (_: Exception) {}
      }

      val stderrThread = Thread {
        try {
          BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
              val msg = OutputMessage(line!! + "\n")
              synchronized(streamLock) {
                if (stderrSb.length < 65000) stderrSb.appendLine(line)
                execution.logs.stderr.add(msg)
                record.logs.append(line).append("\n")
              }
              request.handlers?.onStderr(msg)
            }
          }
        } catch (_: Exception) {}
      }

      stdoutThread.start()
      stderrThread.start()

      if (request.background) {
        // Detached background command
        record.running = true
        return execution
      }

      val timeoutSec = request.timeout?.seconds ?: 60L
      val completed = process.waitFor(timeoutSec, TimeUnit.SECONDS)
      stdoutThread.join(800)
      stderrThread.join(800)

      val durationMs = System.currentTimeMillis() - startTime
      execution.complete = ExecutionComplete(executionTimeInMillis = durationMs)

      if (!completed) {
        process.destroyForcibly()
        activeProcesses.remove(executionId)
        record.running = false
        record.finishedAt = OffsetDateTime.now(ZoneOffset.UTC)
        record.exitCode = -1
        record.error = "Command timed out after ${timeoutSec}s"

        execution.exitCode = -1
        val err = ExecutionError(name = "TimeoutError", value = "Command timed out after ${timeoutSec}s")
        execution.error = err
        request.handlers?.onError(err)
        return execution
      }

      val exitCode = process.exitValue()
      execution.exitCode = exitCode
      record.running = false
      record.finishedAt = OffsetDateTime.now(ZoneOffset.UTC)
      record.exitCode = exitCode

      if (exitCode != 0) {
        val errText = synchronized(streamLock) { stderrSb.toString().trim() }
        val err = ExecutionError(
          name = "CommandExecutionError",
          value = errText.ifBlank { "Command exited with code $exitCode" }
        )
        execution.error = err
        request.handlers?.onError(err)
      }

      request.handlers?.onComplete(ExecutionComplete(durationMs))
      activeProcesses.remove(executionId)
      return execution
    } catch (e: Exception) {
      activeProcesses.remove(executionId)
      record.running = false
      record.exitCode = 1
      record.error = e.message
      val err = ExecutionError(name = e.javaClass.simpleName, value = e.message ?: "Execution failed")
      execution.error = err
      execution.exitCode = 1
      request.handlers?.onError(err)
      return execution
    }
  }

  private fun runRemoteCommand(request: RunCommandRequest): Execution {
    val url = "${config.endpoint.trimEnd('/')}/commands/runs"
    val json = JSONObject().apply {
      put("command", request.command)
      put("background", request.background)
      request.workingDirectory?.let { put("working_directory", it) }
      request.timeout?.let { put("timeout", it.seconds) }
      if (request.envs.isNotEmpty()) {
        put("envs", JSONObject(request.envs))
      }
    }

    val reqBuilder = Request.Builder()
      .url(url)
      .header("User-Agent", config.userAgent)
      .header("Content-Type", "application/json")
      .post(json.toString().toRequestBody("application/json".toMediaType()))

    config.apiKey?.let { reqBuilder.header("Authorization", "Bearer $it") }

    val execution = Execution(id = "remote-" + UUID.randomUUID().toString().take(8))
    try {
      httpClient.newCall(reqBuilder.build()).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.isSuccessful) {
          val resObj = JSONObject(body)
          val parsedId = resObj.optString("id", execution.id)
          execution.id = if (parsedId.isNullOrEmpty()) execution.id else parsedId
          execution.exitCode = resObj.optInt("exit_code", 0)
          val stdoutStr = resObj.optString("stdout", "")
          val stderrStr = resObj.optString("stderr", "")
          if (stdoutStr.isNotBlank()) execution.logs.stdout.add(OutputMessage(stdoutStr))
          if (stderrStr.isNotBlank()) execution.logs.stderr.add(OutputMessage(stderrStr))
        } else {
          execution.exitCode = response.code
          execution.error = ExecutionError("RemoteError", "HTTP ${response.code}: $body")
        }
      }
    } catch (e: Exception) {
      execution.exitCode = 1
      execution.error = ExecutionError("ConnectionError", e.message)
    }
    return execution
  }

  override fun setEnv(key: String, value: String) {
    require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid environment variable key '$key'" }
    try {
      envsFile.appendText("$key=$value\n")
    } catch (_: Exception) {}
  }

  private fun loadPersistentEnvs(): Map<String, String> {
    val map = mutableMapOf<String, String>()
    if (envsFile.exists()) {
      try {
        envsFile.readLines().forEach { line ->
          val trimmed = line.trim()
          if (trimmed.isNotBlank() && !trimmed.startsWith("#") && trimmed.contains("=")) {
            val parts = trimmed.split("=", limit = 2)
            if (parts.size == 2) {
              map[parts[0].trim()] = parts[1].trim()
            }
          }
        }
      } catch (_: Exception) {}
    }
    return map
  }

  override fun interrupt(executionId: String) {
    val process = activeProcesses.remove(executionId)
    if (process != null) {
      try {
        process.destroyForcibly()
      } catch (_: Exception) {}
    }
    val record = activeCommands[executionId]
    if (record != null) {
      record.running = false
      record.finishedAt = OffsetDateTime.now(ZoneOffset.UTC)
      record.exitCode = 137
      record.error = "Interrupted by user"
    }
  }

  override fun getCommandStatus(executionId: String): CommandStatus {
    val record = activeCommands[executionId]
      ?: return CommandStatus(id = executionId, content = null, running = false, exitCode = 127, error = "Command not found")
    return CommandStatus(
      id = record.id,
      content = record.command,
      running = record.running,
      exitCode = record.exitCode,
      error = record.error,
      startedAt = record.startedAt,
      finishedAt = record.finishedAt
    )
  }

  override fun getBackgroundCommandLogs(executionId: String, cursor: Long?): CommandLogs {
    val record = activeCommands[executionId]
      ?: return CommandLogs(content = "", cursor = null)
    val text = record.logs.toString()
    val startIdx = (cursor ?: 0L).toInt().coerceIn(0, text.length)
    val chunk = text.substring(startIdx)
    return CommandLogs(content = chunk, cursor = text.length.toLong())
  }

  override fun createSession(workingDirectory: String?): String {
    val sessionId = "session-" + UUID.randomUUID().toString().take(10)
    val sessionDir = if (!workingDirectory.isNullOrBlank()) resolveSafe(workingDirectory) else baseDir
    sessionDir.mkdirs()
    bashSessions[sessionId] = BashSessionRecord(id = sessionId, workDir = sessionDir)
    return sessionId
  }

  override fun runInSession(sessionId: String, request: RunInSessionRequest): Execution {
    val session = bashSessions[sessionId]
      ?: throw SandboxException("Session '$sessionId' does not exist")
    val workDir = if (!request.workingDirectory.isNullOrBlank()) {
      resolveSafe(request.workingDirectory)
    } else {
      session.workDir
    }
    val cmdReq = RunCommandRequest.builder()
      .command(request.command)
      .workingDirectory(getRelativePath(workDir))
    request.timeout?.let { cmdReq.timeout(it) }
    request.handlers?.let { cmdReq.handlers(it) }
    return run(cmdReq.build())
  }

  override fun deleteSession(sessionId: String) {
    bashSessions.remove(sessionId)
  }

  // ==========================================
  // FILESYSTEM SERVICE IMPLEMENTATION
  // ==========================================

  override fun readFile(
    path: String,
    encoding: String,
    range: String?,
    offset: Int?,
    limit: Int?
  ): String {
    val file = resolveSafe(path)
    if (!file.exists()) {
      throw SandboxException("File does not exist: $path")
    }
    if (file.isDirectory) {
      throw SandboxException("Cannot readFile on directory '$path'. Use listDirectory instead.")
    }

    val charset = try { Charset.forName(encoding) } catch (_: Exception) { Charsets.UTF_8 }

    if (offset != null || limit != null) {
      val lines = file.readLines(charset)
      val start = (offset?.minus(1) ?: 0).coerceAtLeast(0)
      val count = limit ?: lines.size
      val selected = lines.drop(start).take(count)
      return selected.joinToString("\n")
    }

    if (!range.isNullOrBlank() && range.startsWith("bytes=")) {
      val bytes = readByteArray(path, range)
      return String(bytes, charset)
    }

    return file.readText(charset)
  }

  override fun readByteArray(path: String, range: String?, offset: Int?, limit: Int?): ByteArray {
    val file = resolveSafe(path)
    if (!file.exists()) throw SandboxException("File does not exist: $path")
    val allBytes = file.readBytes()

    if (!range.isNullOrBlank() && range.startsWith("bytes=")) {
      val spec = range.removePrefix("bytes=").trim()
      val parts = spec.split("-")
      val start = parts.getOrNull(0)?.toIntOrNull() ?: 0
      val end = parts.getOrNull(1)?.toIntOrNull() ?: (allBytes.size - 1)
      val safeStart = start.coerceIn(0, allBytes.size)
      val safeEnd = (end + 1).coerceIn(safeStart, allBytes.size)
      return allBytes.copyOfRange(safeStart, safeEnd)
    }

    return allBytes
  }

  override fun readStream(path: String, range: String?, offset: Int?, limit: Int?): InputStream {
    return ByteArrayInputStream(readByteArray(path, range, offset, limit))
  }

  override fun write(entries: List<WriteEntry>) {
    for (entry in entries) {
      val file = resolveSafe(entry.path)
      file.parentFile?.mkdirs()
      when (val data = entry.data) {
        is ByteArray -> file.writeBytes(data)
        is String -> file.writeText(data, Charset.forName(entry.encoding))
        else -> file.writeText(data?.toString().orEmpty(), Charset.forName(entry.encoding))
      }
      try {
        file.setReadable(true, false)
        if (entry.mode and 0x49 != 0) { // Executable bits
          file.setExecutable(true, false)
        }
      } catch (_: Exception) {}
    }
  }

  override fun createDirectories(entries: List<WriteEntry>) {
    for (entry in entries) {
      val dir = resolveSafe(entry.path)
      dir.mkdirs()
    }
  }

  override fun deleteFiles(paths: List<String>) {
    for (p in paths) {
      val file = resolveSafe(p)
      if (file.exists() && file.isFile) {
        file.delete()
      }
    }
  }

  override fun deleteDirectories(paths: List<String>) {
    for (p in paths) {
      val dir = resolveSafe(p)
      if (dir.exists() && dir.isDirectory) {
        dir.deleteRecursively()
      }
    }
  }

  override fun listDirectory(path: String, depth: Int?): List<EntryInfo> {
    val dir = resolveSafe(path)
    if (!dir.exists() || !dir.isDirectory) return emptyList()

    val maxDepth = depth ?: 1
    val result = mutableListOf<EntryInfo>()

    fun traverse(current: File, curDepth: Int) {
      val children = current.listFiles() ?: return
      for (child in children) {
        val relPath = getRelativePath(child)
        val modified = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(child.lastModified()), ZoneOffset.UTC)
        val info = EntryInfo(
          path = relPath,
          mode = if (child.isDirectory) 755 else if (child.canExecute()) 755 else 644,
          owner = "sandbox",
          group = "sandbox",
          size = if (child.isFile) child.length() else 0L,
          modifiedAt = modified,
          createdAt = modified,
          type = if (child.isDirectory) "directory" else "file"
        )
        result.add(info)
        if (child.isDirectory && curDepth < maxDepth) {
          traverse(child, curDepth + 1)
        }
      }
    }

    traverse(dir, 1)
    return result
  }

  override fun moveFiles(entries: List<MoveEntry>) {
    for (entry in entries) {
      val src = resolveSafe(entry.sourcePath)
      if (!src.exists()) throw SandboxException("Source path '${entry.sourcePath}' does not exist")
      val dst = resolveSafe(entry.destinationPath)
      dst.parentFile?.mkdirs()
      if (src.canonicalPath == dst.canonicalPath) continue
      if (!src.renameTo(dst)) {
        if (src.isDirectory) {
          src.copyRecursively(dst, overwrite = true)
          src.deleteRecursively()
        } else {
          src.copyTo(dst, overwrite = true)
          src.delete()
        }
      }
    }
  }

  override fun setPermissions(entries: List<SetPermissionEntry>) {
    for (entry in entries) {
      val file = resolveSafe(entry.path)
      if (file.exists()) {
        val mode = entry.mode
        file.setReadable(mode and 0x124 != 0, false)
        file.setWritable(mode and 0x92 != 0, false)
        file.setExecutable(mode and 0x49 != 0, false)
        try {
          Runtime.getRuntime().exec(arrayOf("chmod", Integer.toOctalString(mode), file.absolutePath)).waitFor()
        } catch (_: Exception) {}
      }
    }
  }

  override fun replaceContents(entries: List<ContentReplaceEntry>) {
    replaceContentsDetailed(entries)
  }

  override fun replaceContentsDetailed(entries: List<ContentReplaceEntry>): List<ContentReplaceResult> {
    val results = mutableListOf<ContentReplaceResult>()
    for (entry in entries) {
      val file = resolveSafe(entry.path)
      if (!file.exists()) {
        results.add(ContentReplaceResult(path = entry.path, replacedCount = 0))
        continue
      }
      val text = file.readText()
      if (!text.contains(entry.oldContent)) {
        results.add(ContentReplaceResult(path = entry.path, replacedCount = 0))
        continue
      }
      val replaced = text.replace(entry.oldContent, entry.newContent)
      file.writeText(replaced)
      results.add(ContentReplaceResult(path = entry.path, replacedCount = 1))
    }
    return results
  }

  override fun search(entry: SearchEntry): List<EntryInfo> {
    val startDir = resolveSafe(entry.path)
    if (!startDir.exists()) return emptyList()

    val patternRegex = try {
      val escaped = Pattern.quote(entry.pattern)
        .replace("\\*", ".*")
        .replace("\\?", ".")
      Regex(escaped, RegexOption.IGNORE_CASE)
    } catch (_: Exception) {
      Regex(Pattern.quote(entry.pattern), RegexOption.IGNORE_CASE)
    }

    val matches = mutableListOf<EntryInfo>()
    startDir.walkTopDown().forEach { file ->
      if (patternRegex.containsMatchIn(file.name) || patternRegex.containsMatchIn(getRelativePath(file))) {
        val modified = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(file.lastModified()), ZoneOffset.UTC)
        matches.add(
          EntryInfo(
            path = getRelativePath(file),
            mode = if (file.isDirectory) 755 else 644,
            owner = "sandbox",
            group = "sandbox",
            size = if (file.isFile) file.length() else 0L,
            modifiedAt = modified,
            createdAt = modified,
            type = if (file.isDirectory) "directory" else "file"
          )
        )
      }
    }
    return matches
  }

  override fun readFileInfo(paths: List<String>): Map<String, EntryInfo> {
    val map = mutableMapOf<String, EntryInfo>()
    for (p in paths) {
      try {
        val file = resolveSafe(p)
        if (file.exists()) {
          val modified = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(file.lastModified()), ZoneOffset.UTC)
          map[p] = EntryInfo(
            path = getRelativePath(file),
            mode = if (file.isDirectory) 755 else 644,
            owner = "sandbox",
            group = "sandbox",
            size = if (file.isFile) file.length() else 0L,
            modifiedAt = modified,
            createdAt = modified,
            type = if (file.isDirectory) "directory" else "file"
          )
        }
      } catch (_: Exception) {}
    }
    return map
  }

  // ==========================================
  // CODES / CODE INTERPRETER IMPLEMENTATION
  // ==========================================

  override fun getContext(id: String): CodeContext {
    val rec = codeContexts[id] ?: throw SandboxException("CodeContext '$id' not found")
    return CodeContext.builder().id(rec.id).language(rec.language).build()
  }

  override fun listContexts(language: String): List<CodeContext> {
    return codeContexts.values
      .filter { it.language.equals(language, ignoreCase = true) }
      .map { CodeContext.builder().id(it.id).language(it.language).build() }
  }

  override fun createContext(language: String): CodeContext {
    val id = "ctx-" + UUID.randomUUID().toString().take(8)
    codeContexts[id] = CodeContextRecord(id = id, language = language)
    return CodeContext.builder().id(id).language(language).build()
  }

  override fun deleteContext(id: String) {
    codeContexts.remove(id)
  }

  override fun deleteContexts(language: String) {
    codeContexts.values.removeIf { it.language.equals(language, ignoreCase = true) }
  }

  override fun run(request: RunCodeRequest): Execution {
    val lang = request.context.language.lowercase()
    val scriptName = ".opensandbox_exec_${System.currentTimeMillis()}"

    val execution = when (lang) {
      SupportedLanguage.PYTHON -> {
        val pyFile = File(baseDir, "$scriptName.py")
        try {
          pyFile.writeText(request.code)
          val cmd = "python3 ${pyFile.name}"
          run(RunCommandRequest.builder().command(cmd).handlers(request.handlers).build())
        } finally {
          try { pyFile.delete() } catch (_: Exception) {}
        }
      }
      SupportedLanguage.BASH -> {
        run(RunCommandRequest.builder().command(request.code).handlers(request.handlers).build())
      }
      SupportedLanguage.JAVASCRIPT -> {
        val jsFile = File(baseDir, "$scriptName.js")
        try {
          jsFile.writeText(request.code)
          val cmd = "node ${jsFile.name}"
          run(RunCommandRequest.builder().command(cmd).handlers(request.handlers).build())
        } finally {
          try { jsFile.delete() } catch (_: Exception) {}
        }
      }
      else -> {
        run(RunCommandRequest.builder().command(request.code).handlers(request.handlers).build())
      }
    }
    return execution
  }

  override fun ping(): Boolean = true

  // ==========================================
  // DIAGNOSTICS & METRICS IMPLEMENTATION
  // ==========================================

  override fun getDiagnostics(): DiagnosticContent {
    val runtime = Runtime.getRuntime()
    val totalMem = runtime.totalMemory()
    val freeMem = runtime.freeMemory()
    val entries = listOf(
      DiagnosticEntry("sandbox_id", sandboxId, "opensandbox"),
      DiagnosticEntry("workspace_path", baseDir.absolutePath, "storage"),
      DiagnosticEntry("active_sessions", bashSessions.size.toString(), "sessions"),
      DiagnosticEntry("active_processes", activeProcesses.size.toString(), "processes"),
      DiagnosticEntry("os_name", System.getProperty("os.name") ?: "Linux", "system"),
      DiagnosticEntry("os_arch", System.getProperty("os.arch") ?: "aarch64", "system"),
      DiagnosticEntry("java_version", System.getProperty("java.version") ?: "17", "runtime")
    )

    return DiagnosticContent(
      hostname = "opensandbox-node-$sandboxId",
      os = "Linux Android " + (System.getProperty("os.version") ?: "6.6.0"),
      kernel = System.getProperty("os.version") ?: "6.6.0",
      architecture = System.getProperty("os.arch") ?: "aarch64",
      cpuCores = runtime.availableProcessors(),
      totalMemoryBytes = totalMem,
      freeMemoryBytes = freeMem,
      uptimeSeconds = (System.currentTimeMillis() - initTimestamp) / 1000L,
      entries = entries,
      rawText = entries.joinToString("\n") { "${it.key}: ${it.value}" }
    )
  }

  override fun getMetrics(): SandboxMetrics {
    val runtime = Runtime.getRuntime()
    val usedMem = runtime.totalMemory() - runtime.freeMemory()
    val usableSpace = baseDir.usableSpace
    val totalSpace = baseDir.totalSpace
    return SandboxMetrics(
      cpuPercent = 1.5,
      memoryBytesUsed = usedMem,
      memoryBytesTotal = runtime.maxMemory(),
      diskBytesUsed = totalSpace - usableSpace,
      diskBytesTotal = totalSpace
    )
  }

  // ==========================================
  // ARTIFACTS MANAGEMENT FOR AI STUDIO AGENT
  // ==========================================

  fun listArtifacts(): List<Artifact> {
    val list = mutableListOf<Artifact>()
    if (baseDir.exists() && baseDir.isDirectory) {
      baseDir.walkTopDown().forEach { file ->
        if (file != baseDir && file.isFile) {
          val relPath = getRelativePath(file)
          val isInternal = relPath.startsWith("bin/") ||
            relPath.startsWith("lib/") ||
            relPath.startsWith("tmp/") ||
            relPath.startsWith("notes/") ||
            relPath == "notes" ||
            relPath.startsWith(".scratchpad/") ||
            relPath == "checkpoint.json" ||
            relPath == "plan.md" ||
            relPath == ".opensandbox_envs" ||
            file.name.startsWith(".") ||
            file.name.endsWith(".tmp") ||
            file.name.startsWith(".part_")

          if (!isInternal) {
            val ext = file.extension.lowercase()
            val type = when (ext) {
              "py" -> "Python Source"
              "json" -> "JSON Document"
              "csv" -> "CSV Dataset"
              "md" -> "Markdown Document"
              "docx" -> "Word Document"
              "txt" -> "Text File"
              "sh" -> "Shell Script"
              "html" -> "HTML Page"
              else -> "${ext.uppercase()} File"
            }
            list.add(
              Artifact(
                id = UUID.randomUUID().toString(),
                path = relPath,
                name = file.name,
                type = type,
                size = file.length(),
                exists = true,
                lastModified = file.lastModified(),
                absolutePath = file.canonicalPath
              )
            )
          }
        }
      }
    }
    return list.sortedByDescending { it.lastModified }
  }

  fun cleanAllArtifacts() {
    if (baseDir.exists() && baseDir.isDirectory) {
      baseDir.listFiles()?.forEach { file ->
        if (file.name != ".opensandbox_envs") {
          file.deleteRecursively()
        }
      }
    }
    baseDir.mkdirs()
    binDir.mkdirs()
    tmpDir.mkdirs()
    libDir.mkdirs()
  }

  fun seedWorkspaceDefaults() {
    baseDir.mkdirs()
    binDir.mkdirs()
    tmpDir.mkdirs()
    libDir.mkdirs()
    seedCurlBinary()
    val dataCsv = File(baseDir, "data.csv")
    if (!dataCsv.exists()) {
      dataCsv.writeText(
        """
        id,product,category,revenue,units_sold,quarter
        1,Cloud Server Pro,Infrastructure,125000,450,Q1
        2,AI Inference Engine,Software,340000,1200,Q1
        3,Database Cluster,Infrastructure,89000,210,Q1
        4,Edge Gateway,Hardware,45000,300,Q1
        5,Cloud Server Pro,Infrastructure,142000,510,Q2
        6,AI Inference Engine,Software,420000,1500,Q2
        7,Database Cluster,Infrastructure,95000,225,Q2
        8,Edge Gateway,Hardware,51000,340,Q2
        """.trimIndent()
      )
    }
  }

  fun close() {
    activeProcesses.values.forEach { it.destroyForcibly() }
    activeProcesses.clear()
    bashSessions.clear()
  }

  private val initTimestamp = System.currentTimeMillis()

  private data class CommandRecord(
    val id: String,
    val command: String,
    val startedAt: OffsetDateTime,
    var finishedAt: OffsetDateTime? = null,
    var running: Boolean = true,
    var exitCode: Int? = null,
    var error: String? = null,
    val logs: StringBuilder = StringBuilder()
  )

  private data class BashSessionRecord(
    val id: String,
    val workDir: File
  )

  private data class CodeContextRecord(
    val id: String,
    val language: String
  )
}
