package com.example.agent.core

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative manager for workspace artifacts based strictly on filesystem ground truth.
 * An artifact is registered ONLY when verified to exist on disk with a valid size and type.
 */
class ArtifactManager(
  val workspaceDir: File
) {
  private val registeredArtifacts = ConcurrentHashMap<String, Artifact>()

  /**
   * Registers a file as an artifact after verifying that it actually exists on disk.
   * Returns the registered Artifact, or null if the file does not exist.
   */
  fun registerArtifact(
    relativePath: String,
    createdByCallId: String? = null,
    taskId: String? = null,
    originatingEnvironment: String = "e2b_cloud",
    customMimeType: String? = null,
    checksum: String? = null
  ): Artifact {
    val cleanRelPath = relativePath.trim().removePrefix("./").removePrefix("/")
    val file = resolveFile(cleanRelPath)

    val exists = file.exists() && file.isFile
    val isReadable = exists && file.canRead()
    val length = if (exists) file.length() else 0L
    val type = determineFileType(file.name)
    val mime = customMimeType ?: determineMimeType(file.name)
    val effectiveChecksum = checksum ?: if (exists && isReadable) computeSha256(file) else null
    val canonicalPath = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }

    val artifact = Artifact(
      id = registeredArtifacts[cleanRelPath]?.id ?: UUID.randomUUID().toString(),
      path = cleanRelPath,
      name = file.name,
      type = type,
      size = length,
      createdByCallId = createdByCallId,
      taskId = taskId ?: registeredArtifacts[cleanRelPath]?.taskId,
      exists = exists,
      isReadable = isReadable,
      lastModified = if (exists) file.lastModified() else 0L,
      absolutePath = canonicalPath,
      originatingEnvironment = originatingEnvironment,
      mimeType = mime,
      checksum = effectiveChecksum
    )

    if (exists && isReadable) {
      registeredArtifacts[cleanRelPath] = artifact
    } else {
      registeredArtifacts.remove(cleanRelPath)
    }
    return artifact
  }

  /**
   * Computes authoritative SHA-256 checksum from actual file bytes on disk.
   */
  fun computeSha256(file: File): String? {
    if (!file.exists() || !file.isFile || !file.canRead()) return null
    return try {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var bytesRead = input.read(buffer)
        while (bytesRead != -1) {
          digest.update(buffer, 0, bytesRead)
          bytesRead = input.read(buffer)
        }
      }
      digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
      null
    }
  }

  fun registerArtifact(
    file: File,
    createdByCallId: String? = null,
    taskId: String? = null,
    originatingEnvironment: String = "e2b_cloud",
    customMimeType: String? = null,
    checksum: String? = null
  ): Artifact {
    val relPath = PathSecurity.getRelativePath(workspaceDir, file)
    return registerArtifact(relPath, createdByCallId, taskId, originatingEnvironment, customMimeType, checksum)
  }

  /**
   * Removes an artifact from tracking when its file has been deleted.
   */
  fun unregisterArtifact(relativePath: String) {
    val cleanRelPath = relativePath.trim().removePrefix("./").removePrefix("/")
    registeredArtifacts.remove(cleanRelPath)
  }

  /**
   * Scans the workspace directory, verifies all existing files, prunes deleted files,
   * and returns the authoritative list of existing artifacts.
   */
  fun syncWorkspace(taskId: String? = null, defaultEnvironment: String = "e2b_cloud"): List<Artifact> {
    if (!workspaceDir.exists()) {
      workspaceDir.mkdirs()
      registeredArtifacts.clear()
      return emptyList()
    }

    // Prune registered files that no longer exist
    val toRemove = mutableListOf<String>()
    for ((path, artifact) in registeredArtifacts) {
      val f = File(artifact.absolutePath.ifBlank { resolveFile(path).canonicalPath })
      if (!f.exists() || !f.isFile) {
        toRemove.add(path)
      }
    }
    toRemove.forEach { registeredArtifacts.remove(it) }

    // Scan directory for valid deliverable files, ignoring internal/session metadata and active databases
    workspaceDir.walkTopDown()
      .maxDepth(5)
      .filter { file ->
        val name = file.name.lowercase()
        val parentName = file.parentFile?.name.orEmpty().lowercase()
        val isLink = try { java.nio.file.Files.isSymbolicLink(file.toPath()) } catch (_: Throwable) { false }
        file.isFile &&
          !isLink &&
          !name.startsWith(".tmp_") &&
          !name.startsWith(".e2b_session") &&
          !name.startsWith(".sessions") &&
          !name.startsWith(".git") &&
          !name.endsWith(".db") &&
          !name.endsWith(".db-wal") &&
          !name.endsWith(".db-shm") &&
          !name.endsWith(".db-journal") &&
          name != "data.db" &&
          parentName != ".git" &&
          parentName != ".sessions" &&
          parentName != ".mcp_data" &&
          parentName != "workspace" &&
          parentName != "venv" &&
          parentName != ".venv" &&
          parentName != "node_modules" &&
          parentName != "bin" &&
          parentName != "tmp"
      }
      .forEach { file ->
        val rel = getRelativePath(file)
        if (!registeredArtifacts.containsKey(rel)) {
          registerArtifact(rel, createdByCallId = null, taskId = taskId, originatingEnvironment = defaultEnvironment)
        } else {
          // Update length and last modified
          val existing = registeredArtifacts[rel]!!
            if (existing.size != file.length() || existing.lastModified != file.lastModified()) {
              registeredArtifacts[rel] = existing.copy(
                size = file.length(),
                lastModified = file.lastModified(),
                exists = true,
                checksum = computeSha256(file)
              )
            }
        }
      }

    return registeredArtifacts.values.sortedBy { it.path }
  }

  private fun determineMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
      "md", "markdown" -> "text/markdown"
      "txt" -> "text/plain"
      "json" -> "application/json"
      "jsonl" -> "application/x-ndjson"
      "csv" -> "text/csv"
      "docx", "doc" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      "xlsx", "xls" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      "py" -> "text/x-python"
      "sh" -> "application/x-sh"
      "html", "htm" -> "text/html"
      "pdf" -> "application/pdf"
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      else -> "application/octet-stream"
    }
  }

  /**
   * Returns current list of registered, verified, and readable artifacts.
   */
  fun getArtifacts(taskId: String? = null): List<Artifact> {
    return registeredArtifacts.values.filter {
      val f = File(it.absolutePath)
      f.exists() && f.isFile && f.canRead() && (taskId == null || it.taskId == null || it.taskId == taskId)
    }.sortedBy { it.path }
  }

  /**
   * Returns a registered artifact by relative path if verified and readable on disk.
   */
  fun getArtifact(relativePath: String): Artifact? {
    val clean = relativePath.trim().removePrefix("./").removePrefix("/")
    val art = registeredArtifacts[clean] ?: return null
    val f = File(art.absolutePath)
    return if (f.exists() && f.isFile && f.canRead()) art else null
  }

  /**
   * Returns whether a target artifact exists, is readable, and is non-empty on disk.
   */
  fun isArtifactReadable(relativePath: String): Boolean {
    val art = getArtifact(relativePath) ?: return false
    val f = File(art.absolutePath)
    return f.exists() && f.canRead() && f.length() > 0L
  }

  /**
   * Cleans all deliverable files from the workspace.
   */
  fun cleanAll() {
    registeredArtifacts.clear()
    if (workspaceDir.exists()) {
      workspaceDir.listFiles()?.forEach { file ->
        val name = file.name.lowercase()
        val isProtected = name == "bin" || name == "tmp" || name == "lib" ||
          name.startsWith(".e2b_session") || name.startsWith(".sessions") ||
          name == "data.db" || name.startsWith("data.db-") || name == ".mcp_data" ||
          name.endsWith(".db") || name.endsWith(".db-wal") || name.endsWith(".db-shm") || name.endsWith(".db-journal")
        if (!isProtected) {
          file.deleteRecursively()
        }
      }
    }
  }

  /**
   * Cleans temporary or intermediate scratch files.
   */
  fun cleanTemporaryFiles() {
    if (workspaceDir.exists()) {
      workspaceDir.walkTopDown().maxDepth(3).forEach { file ->
        if (file.isFile && (file.name.startsWith(".tmp_") || file.name.endsWith(".tmp") || file.name.endsWith(".part"))) {
          try { file.delete() } catch (_: Exception) {}
        }
      }
    }
  }

  private fun resolveFile(relativePath: String): File {
    return PathSecurity.resolveSafe(workspaceDir, relativePath)
  }

  private fun getRelativePath(file: File): String {
    return PathSecurity.getRelativePath(workspaceDir, file)
  }

  fun determineFileType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
      "md", "markdown" -> "Markdown Document"
      "docx", "doc" -> "Word Document"
      "xlsx", "xls" -> "Excel Spreadsheet"
      "json" -> "JSON Document"
      "jsonl" -> "JSON Lines"
      "csv" -> "CSV Spreadsheet"
      "py" -> "Python Script"
      "sh" -> "Shell Script"
      "txt" -> "Text Document"
      "html", "htm" -> "HTML Webpage"
      "pdf" -> "PDF Document"
      "png", "jpg", "jpeg", "webp" -> "Image"
      else -> "Deliverable File"
    }
  }
}
