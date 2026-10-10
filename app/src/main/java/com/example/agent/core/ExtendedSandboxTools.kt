package com.example.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Precision file editor tool for substring replacement and patching without whole-file rewrites.
 */
class EditFileTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "edit_file"
  override val description: String =
    "Edit an existing file in the workspace by replacing target content with new content. Verifies that the target text exists before modifying."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path of the file to edit relative to the workspace.",
        required = true
      ),
      ToolParameter(
        name = "targetContent",
        type = "string",
        description = "The exact substring in the file to be replaced.",
        required = true
      ),
      ToolParameter(
        name = "replacementContent",
        type = "string",
        description = "The new content that will replace targetContent.",
        required = true
      ),
      ToolParameter(
        name = "replaceAll",
        type = "boolean",
        description = "If true, replaces all occurrences. If false, replaces only the first occurrence (default: false).",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""
      val targetContent = arguments["targetContent"]?.toString() ?: ""
      val replacementContent = arguments["replacementContent"]?.toString() ?: ""
      val replaceAll = arguments["replaceAll"] as? Boolean ?: false

      if (path.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'path' cannot be blank.",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Parameter 'path' cannot be blank.",
          exitCode = 1
        )
      }

      if (targetContent.isEmpty()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'targetContent' cannot be empty.",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Parameter 'targetContent' cannot be empty.",
          exitCode = 1
        )
      }

      try {
        val file = workspace.resolveSafe(path)
        if (!file.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "File does not exist: $path",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "File does not exist: $path",
            exitCode = 1
          )
        }

        val originalText = file.readText()
        if (!originalText.contains(targetContent)) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Target content not found in '$path'. Please ensure exact matching including whitespace.",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "Target content not found in '$path'",
            exitCode = 1
          )
        }

        val newText = if (replaceAll) {
          originalText.replace(targetContent, replacementContent)
        } else {
          val index = originalText.indexOf(targetContent)
          originalText.substring(0, index) + replacementContent + originalText.substring(index + targetContent.length)
        }

        workspace.writeWorkspaceFile(path, newText)
        val updatedArtifact = workspace.createArtifactFromFile(file, callId)

        val successMsg = "Successfully edited '$path'. Replaced ${if (replaceAll) "all occurrences" else "1 occurrence"}. New file size: ${file.length()} bytes."

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = successMsg,
          error = null,
          artifacts = listOf(updatedArtifact),
          duration = System.currentTimeMillis() - startTime,
          stdout = successMsg,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Error editing file '$path': ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Error editing file '$path': ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Workspace file search and grep tool. Searches file contents across directories.
 */
class FileSearchTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "file_search"
  override val description: String =
    "Search for text or regex patterns across files in the workspace. Returns matching file paths, line numbers, and lines."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "query",
        type = "string",
        description = "Text pattern or regex to search for in files.",
        required = true
      ),
      ToolParameter(
        name = "path",
        type = "string",
        description = "Directory or file path to search within (default: '.' for entire workspace).",
        required = false
      ),
      ToolParameter(
        name = "extension",
        type = "string",
        description = "Optional file extension filter (e.g. 'py', 'md', 'json', 'csv').",
        required = false
      ),
      ToolParameter(
        name = "maxMatches",
        type = "number",
        description = "Maximum number of matching lines to return (default: 50).",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val query = arguments["query"]?.toString() ?: ""
      val subPath = arguments["path"]?.toString() ?: "."
      val extension = arguments["extension"]?.toString()?.trimStart('.')
      val maxMatches = (arguments["maxMatches"] as? Number)?.toInt() ?: 50

      if (query.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'query' cannot be blank.",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Parameter 'query' cannot be blank.",
          exitCode = 1
        )
      }

      try {
        val searchRoot = workspace.resolveSafe(subPath)
        if (!searchRoot.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Search path does not exist: $subPath",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "Search path does not exist: $subPath",
            exitCode = 1
          )
        }

        val pattern = try {
          Pattern.compile(query, Pattern.CASE_INSENSITIVE)
        } catch (_: Exception) {
          Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE)
        }

        val filesToSearch = if (searchRoot.isFile) {
          listOf(searchRoot)
        } else {
          searchRoot.walkTopDown()
            .filter { it.isFile }
            .filter { extension == null || it.extension.equals(extension, ignoreCase = true) }
            .toList()
        }

        val matches = mutableListOf<String>()
        var matchCount = 0

        for (file in filesToSearch) {
          if (matchCount >= maxMatches) break
          val relPath = file.relativeTo(workspace.baseDir).path
          try {
            file.useLines { lines ->
              lines.forEachIndexed { idx, line ->
                if (matchCount < maxMatches && pattern.matcher(line).find()) {
                  matches.add("$relPath:${idx + 1}: ${line.trim()}")
                  matchCount++
                }
              }
            }
          } catch (_: Exception) {
            // Skip binary or unreadable files gracefully
          }
        }

        val output = if (matches.isEmpty()) {
          "No matches found for query: '$query' across ${filesToSearch.size} file(s)."
        } else {
          "Found $matchCount match(es) for '$query':\n" + matches.joinToString("\n")
        }

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = output,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = output,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Search failed: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Search failed: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Raw HTTP client tool for querying REST APIs, downloading data, and making network requests.
 */
class HttpRequestTool : Tool {
  override val name: String = "http_request"
  override val description: String =
    "Perform an HTTP request (GET, POST, PUT, DELETE, PATCH) to an external API or URL. Returns status code, headers, and response body."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "url",
        type = "string",
        description = "Target HTTP or HTTPS URL to request.",
        required = true
      ),
      ToolParameter(
        name = "method",
        type = "string",
        description = "HTTP method (GET, POST, PUT, DELETE, PATCH). Default: 'GET'.",
        required = false,
        enumValues = listOf("GET", "POST", "PUT", "DELETE", "PATCH")
      ),
      ToolParameter(
        name = "headers",
        type = "object",
        description = "Optional key-value map of HTTP headers (e.g. {'Accept': 'application/json'}).",
        required = false
      ),
      ToolParameter(
        name = "body",
        type = "string",
        description = "Request body payload for POST/PUT/PATCH requests.",
        required = false
      ),
      ToolParameter(
        name = "timeoutSeconds",
        type = "number",
        description = "Request timeout in seconds (default: 20).",
        required = false
      )
    )
  )

  private val client = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val url = arguments["url"]?.toString() ?: ""
      val method = arguments["method"]?.toString()?.uppercase() ?: "GET"
      val bodyStr = arguments["body"]?.toString()
      val timeoutSeconds = (arguments["timeoutSeconds"] as? Number)?.toLong() ?: 20L

      if (url.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'url' cannot be blank.",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Parameter 'url' cannot be blank.",
          exitCode = 1
        )
      }

      try {
        val reqBuilder = Request.Builder().url(url)

        // Parse headers
        val headersRaw = arguments["headers"]
        if (headersRaw is Map<*, *>) {
          for ((k, v) in headersRaw) {
            if (k != null && v != null) {
              reqBuilder.addHeader(k.toString(), v.toString())
            }
          }
        }
        reqBuilder.addHeader("User-Agent", "Aragon-AgentKernel/2.4 (Android; Autonomous Research Runtime)")

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = if (method in listOf("POST", "PUT", "PATCH")) {
          (bodyStr ?: "").toRequestBody(mediaType)
        } else null

        when (method) {
          "GET" -> reqBuilder.get()
          "POST" -> reqBuilder.post(requestBody ?: "".toRequestBody(null))
          "PUT" -> reqBuilder.put(requestBody ?: "".toRequestBody(null))
          "DELETE" -> reqBuilder.delete(requestBody)
          "PATCH" -> reqBuilder.patch(requestBody ?: "".toRequestBody(null))
          else -> reqBuilder.get()
        }

        val request = reqBuilder.build()
        val customClient = if (timeoutSeconds != 20L) {
          client.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        } else client

        val (responseSummary, isSuccess, code) = customClient.newCall(request).execute().use { response ->
          val code = response.code
          val rawBody = response.body?.string() ?: ""

          val truncatedBody = if (rawBody.length > 24000) {
            rawBody.take(24000) + "\n...[response body truncated at 24,000 characters]..."
          } else {
            rawBody
          }

          val summary = """
          HTTP Status: $code ${response.message}
          URL: ${response.request.url}
          Headers:
          - content-type: ${response.header("content-type", "none")}
          - content-length: ${response.header("content-length", rawBody.length.toString())}

          Body:
          $truncatedBody
          """.trimIndent()

          Triple(summary, response.isSuccessful, code)
        }

        ToolResult(
          callId = callId,
          toolName = name,
          status = if (isSuccess) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
          arguments = arguments,
          output = responseSummary,
          error = if (!isSuccess) "HTTP request failed with status code $code" else null,
          duration = System.currentTimeMillis() - startTime,
          stdout = responseSummary,
          stderr = if (!isSuccess) "HTTP status: $code" else null,
          exitCode = if (isSuccess) 0 else 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "HTTP request exception: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "HTTP request exception: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * In-memory and file-based JSON processor tool (format, filter, path-query, summarize).
 */
class JsonProcessorTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "json_processor"
  override val description: String =
    "Parse, validate, format, or query JSON data from text or workspace files. Supports key querying, item counting, and structural summarization."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "input",
        type = "string",
        description = "Raw JSON string to process (optional if 'path' is provided).",
        required = false
      ),
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path to a JSON file in the workspace to read and process.",
        required = false
      ),
      ToolParameter(
        name = "action",
        type = "string",
        description = "Operation to perform: 'format' (pretty-print), 'query' (get key/path), 'keys' (list keys), 'count' (item count), 'validate'. Default: 'format'.",
        required = false,
        enumValues = listOf("format", "query", "keys", "count", "validate")
      ),
      ToolParameter(
        name = "query",
        type = "string",
        description = "Dot-notation property path to extract when action is 'query' (e.g. 'users.0.name' or 'status').",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val inputRaw = arguments["input"]?.toString()
      val path = arguments["path"]?.toString()
      val action = arguments["action"]?.toString() ?: "format"
      val query = arguments["query"]?.toString()

      try {
        val jsonStr = when {
          !inputRaw.isNullOrBlank() -> inputRaw.trim()
          !path.isNullOrBlank() -> {
            val file = workspace.resolveSafe(path)
            if (!file.exists()) {
              return@withContext ToolResult(
                callId = callId,
                toolName = name,
                status = ToolStatus.FAILED,
                arguments = arguments,
                output = null,
                error = "File does not exist: $path",
                duration = System.currentTimeMillis() - startTime,
                stdout = null,
                stderr = "File does not exist: $path",
                exitCode = 1
              )
            }
            file.readText().trim()
          }
          else -> {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Must provide either 'input' JSON text or 'path' to a JSON file.",
              duration = System.currentTimeMillis() - startTime,
              stdout = null,
              stderr = "Missing input or path",
              exitCode = 1
            )
          }
        }

        // Parse as object or array
        val parsed: Any = try {
          if (jsonStr.startsWith("[")) JSONArray(jsonStr) else JSONObject(jsonStr)
        } catch (e: Exception) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Invalid JSON syntax: ${e.message}",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "JSON parse error: ${e.message}",
            exitCode = 1
          )
        }

        val resultText = when (action) {
          "validate" -> "Valid JSON syntax. Type: ${if (parsed is JSONArray) "Array[${parsed.length()}]" else "Object"}"
          "format" -> if (parsed is JSONObject) parsed.toString(2) else (parsed as JSONArray).toString(2)
          "count" -> when (parsed) {
            is JSONArray -> "Array length: ${parsed.length()}"
            is JSONObject -> "Object keys count: ${parsed.length()}"
            else -> "Count: 1"
          }
          "keys" -> when (parsed) {
            is JSONObject -> {
              val keys = mutableListOf<String>()
              val it = parsed.keys()
              while (it.hasNext()) keys.add(it.next())
              "Object Keys (${keys.size}):\n" + keys.joinToString(", ")
            }
            is JSONArray -> "Root is a JSONArray of ${parsed.length()} elements."
            else -> "Not an object."
          }
          "query" -> {
            if (query.isNullOrBlank()) {
              "Missing 'query' parameter."
            } else {
              val extracted = queryJsonPath(parsed, query)
              if (extracted == null) "Path '$query' not found." else extracted.toString()
            }
          }
          else -> if (parsed is JSONObject) parsed.toString(2) else (parsed as JSONArray).toString(2)
        }

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = resultText,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = resultText,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "JSON processing error: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "JSON processing error: ${e.message}",
          exitCode = 1
        )
      }
    }

  private fun queryJsonPath(root: Any, path: String): Any? {
    val segments = path.split(".").filter { it.isNotBlank() }
    var current: Any? = root

    for (seg in segments) {
      if (current == null || current == JSONObject.NULL) return null
      current = when (current) {
        is JSONObject -> current.opt(seg)
        is JSONArray -> {
          val idx = seg.toIntOrNull()
          if (idx != null && idx in 0 until current.length()) {
            current.opt(idx)
          } else null
        }
        else -> null
      }
    }
    return if (current == JSONObject.NULL) null else current
  }
}

/**
 * Safely copies a file or directory within the workspace.
 */
class CopyFileTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "copy_file"
  override val description: String =
    "Copy a file or directory to a new location in the workspace. Automatically verifies write on disk."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "source",
        type = "string",
        description = "Relative path of source file or directory to copy.",
        required = true
      ),
      ToolParameter(
        name = "destination",
        type = "string",
        description = "Relative destination path in the workspace.",
        required = true
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val source = arguments["source"]?.toString() ?: ""
      val destination = arguments["destination"]?.toString() ?: ""

      if (source.isBlank() || destination.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Both 'source' and 'destination' parameters are required.",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Missing required parameters",
          exitCode = 1
        )
      }

      try {
        val destFile = workspace.copyFile(source, destination)
        val artifact = workspace.createArtifactFromFile(destFile, callId)
        val msg = "Successfully copied '$source' to '$destination' (${destFile.length()} bytes)."

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = msg,
          error = null,
          artifacts = listOf(artifact),
          duration = System.currentTimeMillis() - startTime,
          stdout = msg,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Copy operation failed: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Copy operation failed: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Safely moves or renames a file or directory within the workspace.
 */
class MoveFileTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "move_file"
  override val description: String =
    "Move or rename a file or directory within the workspace. Verifies filesystem state authoritatively."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "source",
        type = "string",
        description = "Relative path of file or directory to move/rename.",
        required = true
      ),
      ToolParameter(
        name = "destination",
        type = "string",
        description = "Relative destination path in the workspace.",
        required = true
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val source = arguments["source"]?.toString() ?: ""
      val destination = arguments["destination"]?.toString() ?: ""

      if (source.isBlank() || destination.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Both 'source' and 'destination' parameters are required.",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Missing required parameters",
          exitCode = 1
        )
      }

      try {
        val destFile = workspace.moveFile(source, destination)
        val artifact = workspace.createArtifactFromFile(destFile, callId)
        val msg = "Successfully moved '$source' to '$destination'."

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = msg,
          error = null,
          artifacts = listOf(artifact),
          duration = System.currentTimeMillis() - startTime,
          stdout = msg,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Move operation failed: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Move operation failed: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Downloads a remote URL or asset directly into the workspace with verification.
 */
class DownloadFileTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "download_file"
  override val description: String =
    "Download a remote file or web asset directly into the workspace via HTTP/HTTPS. Verifies file creation and byte size."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "url",
        type = "string",
        description = "Remote HTTP or HTTPS URL to download.",
        required = true
      ),
      ToolParameter(
        name = "destination",
        type = "string",
        description = "Relative path where file should be saved in the workspace (e.g. 'data/dataset.csv').",
        required = true
      ),
      ToolParameter(
        name = "timeoutSeconds",
        type = "number",
        description = "Timeout in seconds (default: 30).",
        required = false
      )
    )
  )

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val url = arguments["url"]?.toString() ?: ""
      val destination = arguments["destination"]?.toString() ?: ""
      val timeoutSeconds = (arguments["timeoutSeconds"] as? Number)?.toLong() ?: 30L

      if (url.isBlank() || destination.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameters 'url' and 'destination' are both required.",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Missing required parameters",
          exitCode = 1
        )
      }

      val destFile = workspace.resolveSafe(destination)
      destFile.parentFile?.mkdirs()
      val tmpFile = File(destFile.parentFile, "${destFile.name}.${System.currentTimeMillis()}.tmp")

      try {
        val client = if (timeoutSeconds != 30L) {
          httpClient.newBuilder().readTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        } else httpClient

        val req = Request.Builder()
          .url(url)
          .header("User-Agent", "Aragon-AgentKernel/2.4 (Android; Asset Downloader)")
          .get()
          .build()

        val (isSuccessful, responseCode, responseMsg) = client.newCall(req).execute().use { response ->
          if (!response.isSuccessful) {
            Triple(false, response.code, response.message)
          } else {
            val body = response.body ?: throw IllegalStateException("Empty response body from $url")
            tmpFile.outputStream().use { out ->
              body.byteStream().copyTo(out)
            }
            Triple(true, response.code, response.message)
          }
        }

        if (!isSuccessful) {
          if (tmpFile.exists()) tmpFile.delete()
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Download failed with HTTP $responseCode: $responseMsg",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "HTTP $responseCode",
            exitCode = 1
          )
        }

        if (destFile.exists()) destFile.delete()
        if (!tmpFile.renameTo(destFile)) {
          tmpFile.copyTo(destFile, overwrite = true)
          tmpFile.delete()
        }

        val bytes = destFile.length()
        val artifact = workspace.createArtifactFromFile(destFile, callId)
        val successMsg = "Successfully downloaded $bytes bytes from '$url' to '$destination'."

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = successMsg,
          error = null,
          artifacts = listOf(artifact),
          duration = System.currentTimeMillis() - startTime,
          stdout = successMsg,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Download error: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Download error: ${e.message}",
          exitCode = 1
        )
      } finally {
        if (tmpFile.exists()) {
          tmpFile.delete()
        }
      }
    }
}

/**
 * Native CSV processor tool for inspecting, filtering, aggregating, and formatting CSV tables.
 */
class CsvProcessorTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "csv_processor"
  override val description: String =
    "Inspect, filter, summarize, or format CSV datasets in the workspace. Supports row counts, column inspection, numerical summaries, and Markdown table generation."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path to the CSV file in the workspace.",
        required = true
      ),
      ToolParameter(
        name = "action",
        type = "string",
        description = "Action: 'summary' (stats & row count), 'columns' (header names), 'markdown' (Markdown table preview), 'filter' (filter by column value). Default: 'summary'.",
        required = false,
        enumValues = listOf("summary", "columns", "markdown", "filter")
      ),
      ToolParameter(
        name = "column",
        type = "string",
        description = "Target column name when filtering or calculating column stats.",
        required = false
      ),
      ToolParameter(
        name = "filterValue",
        type = "string",
        description = "Value to match when action is 'filter'.",
        required = false
      ),
      ToolParameter(
        name = "maxRows",
        type = "number",
        description = "Maximum rows to include in table output (default: 15).",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""
      val action = arguments["action"]?.toString() ?: "summary"
      val targetCol = arguments["column"]?.toString()
      val filterVal = arguments["filterValue"]?.toString()
      val maxRows = ((arguments["maxRows"] as? Number)?.toInt() ?: 15).coerceIn(1, 100)

      if (path.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'path' is required.",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Parameter 'path' is required.",
          exitCode = 1
        )
      }

      try {
        val file = workspace.resolveSafe(path)
        if (!file.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "CSV file does not exist: $path",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "File not found: $path",
            exitCode = 1
          )
        }

        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = "CSV file '$path' is empty (0 lines).",
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = "CSV file is empty",
            stderr = null,
            exitCode = 0
          )
        }

        val headers = parseCsvLine(lines.first())
        val dataRows = lines.drop(1).map { parseCsvLine(it) }

        val outputText = when (action) {
          "columns" -> "Columns (${headers.size}):\n" + headers.mapIndexed { i, h -> "${i + 1}. $h" }.joinToString("\n")
          "markdown" -> {
            val previewRows = dataRows.take(maxRows)
            buildMarkdownTable(headers, previewRows) +
              if (dataRows.size > maxRows) "\n\n*(showing $maxRows of ${dataRows.size} total rows)*" else ""
          }
          "filter" -> {
            val colIndex = headers.indexOfFirst { it.equals(targetCol, ignoreCase = true) }
            if (colIndex == -1) {
              "Column '$targetCol' not found in headers: ${headers.joinToString(", ")}"
            } else {
              val filtered = dataRows.filter { row ->
                val cell = row.getOrNull(colIndex) ?: ""
                cell.contains(filterVal ?: "", ignoreCase = true)
              }
              val preview = filtered.take(maxRows)
              "Filtered by $targetCol = '$filterVal' (${filtered.size} match(es)):\n\n" +
                buildMarkdownTable(headers, preview)
            }
          }
          else -> { // summary
            val sb = StringBuilder()
            sb.appendLine("CSV Summary for '$path':")
            sb.appendLine("- Total Rows: ${dataRows.size}")
            sb.appendLine("- Total Columns: ${headers.size}")
            sb.appendLine("- Columns: ${headers.joinToString(", ")}")
            sb.appendLine()
            sb.appendLine("Sample Table Preview (first ${minOf(maxRows, dataRows.size)} rows):")
            sb.appendLine(buildMarkdownTable(headers, dataRows.take(maxRows)))
            sb.toString().trim()
          }
        }

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = outputText,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = outputText,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "CSV processing failed: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "CSV processing failed: ${e.message}",
          exitCode = 1
        )
      }
    }

  private fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    for (ch in line) {
      if (ch == '\"') {
        inQuotes = !inQuotes
      } else if (ch == ',' && !inQuotes) {
        result.add(cur.toString().trim())
        cur.clear()
      } else {
        cur.append(ch)
      }
    }
    result.add(cur.toString().trim())
    return result
  }

  private fun buildMarkdownTable(headers: List<String>, rows: List<List<String>>): String {
    val sb = StringBuilder()
    sb.append("| ").append(headers.joinToString(" | ")).append(" |\n")
    sb.append("| ").append(headers.joinToString(" | ") { "---" }).append(" |\n")
    for (row in rows) {
      val paddedRow = headers.indices.map { i -> row.getOrNull(i) ?: "" }
      sb.append("| ").append(paddedRow.joinToString(" | ")).append(" |\n")
    }
    return sb.toString().trim()
  }
}
