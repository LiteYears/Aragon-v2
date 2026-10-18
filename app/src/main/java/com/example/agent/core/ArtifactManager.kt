package com.example.agent.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative manager for workspace artifacts based strictly on filesystem ground truth.
 * An artifact is registered ONLY when verified to exist on disk with a valid size and type.
 * Persists artifact metadata across app restarts and task resumptions in .artifacts/registry.jsonl.
 */
class ArtifactManager(
  val workspaceDir: File
) {
  private val registeredArtifacts = ConcurrentHashMap<String, Artifact>()
  private val persistenceStore = ArtifactPersistenceStore(workspaceDir)

  /**
   * Optional hook invoked when an artifact is verified to register it into the document database (Room).
   */
  var databaseRegistrar: ((Artifact) -> Unit)? = null

  init {
    loadPersistedRegistry()
  }

  /**
   * Loads previously persisted artifact metadata from .artifacts/registry.jsonl.
   */
  private fun loadPersistedRegistry() {
    val persisted = persistenceStore.loadAll()
    for ((path, artifact) in persisted) {
      val file = resolveFile(path)
      if (file.exists() && file.isFile) {
        val length = file.length()
        val lastMod = file.lastModified()
        val isReadable = file.canRead()
        val validation = ArtifactValidatorRegistry.validateExistingFile(file)
        val status = when {
          length == 0L -> ArtifactCreationStatus.EMPTY
          !validation.isValid -> ArtifactCreationStatus.CORRUPTED
          isReadable -> ArtifactCreationStatus.VERIFIED
          else -> ArtifactCreationStatus.CORRUPTED
        }
        val canonical = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        registeredArtifacts[path] = artifact.copy(
          size = length,
          lastModified = lastMod,
          exists = true,
          isReadable = isReadable,
          absolutePath = canonical,
          creationStatus = status,
          integrity = ArtifactIntegrityInfo(
            sha256 = validation.sha256 ?: artifact.integrity.sha256,
            byteCount = length,
            isFormatValid = validation.isValid,
            formatDescription = validation.detectedFormat,
            validationError = validation.errorMessage,
            verifiedAt = System.currentTimeMillis()
          )
        )
      } else {
        registeredArtifacts[path] = artifact.copy(
          exists = false,
          isReadable = false,
          creationStatus = ArtifactCreationStatus.MISSING
        )
      }
    }
  }

  /**
   * Generates a stable, deterministic artifact ID based on taskId and canonical relative path.
   */
  fun generateStableId(taskId: String?, cleanRelPath: String): String {
    val existing = registeredArtifacts[cleanRelPath]
    if (existing != null && existing.id.isNotBlank()) return existing.id
    val seed = "${taskId ?: "global"}:$cleanRelPath"
    val hash = UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString().replace("-", "").take(16)
    return "art_$hash"
  }

  /**
   * Registers a file as an artifact after verifying that it actually exists on disk.
   * Authoritatively canonicalizes paths across E2B (/home/user), relative, and workspace representations.
   */
  fun registerArtifact(
    relativePath: String,
    createdByCallId: String? = null,
    taskId: String? = null,
    originatingEnvironment: String = "E2B_CLOUD",
    customMimeType: String? = null,
    checksum: String? = null
  ): Artifact {
    val cleanRelPath = PathSecurity.toCanonicalRelativePath(workspaceDir, relativePath)
    val file = resolveFile(cleanRelPath)

    val exists = file.exists() && file.isFile
    val isReadable = exists && file.canRead()
    val length = if (exists) file.length() else 0L
    val type = determineFileType(file.name)
    val mime = customMimeType ?: determineMimeType(file.name)
    val canonicalPath = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }

    // Structural validation
    val validation = if (exists) {
      ArtifactValidatorRegistry.validateExistingFile(file)
    } else {
      ArtifactValidationResult(isValid = false, errorMessage = "File does not exist on disk: $cleanRelPath", isCorrupted = false)
    }

    val creationStatus = when {
      !exists -> ArtifactCreationStatus.MISSING
      length == 0L -> ArtifactCreationStatus.EMPTY
      !validation.isValid -> ArtifactCreationStatus.CORRUPTED
      isReadable -> ArtifactCreationStatus.VERIFIED
      else -> ArtifactCreationStatus.CORRUPTED
    }

    val category = when {
      cleanRelPath.startsWith(".observations/") -> ArtifactCategory.INTERNAL_OBSERVATION
      cleanRelPath.startsWith(".tmp_") || cleanRelPath.endsWith(".tmp") || cleanRelPath.endsWith(".part") || cleanRelPath.startsWith("tmp/") -> ArtifactCategory.TEMPORARY_SCRATCH
      else -> ArtifactCategory.USER_FACING_DELIVERABLE
    }

    val effectiveChecksum = checksum ?: validation.sha256 ?: if (exists && isReadable) computeSha256(file) else null
    val existing = registeredArtifacts[cleanRelPath]
    val stableId = existing?.id ?: generateStableId(taskId, cleanRelPath)
    val currentVersion = if (existing != null && existing.size != length) existing.version + 1 else (existing?.version ?: 1)

    val artifact = Artifact(
      id = stableId,
      path = cleanRelPath,
      name = file.name,
      type = type,
      size = length,
      createdByCallId = createdByCallId ?: existing?.createdByCallId,
      taskId = taskId ?: existing?.taskId,
      exists = exists,
      isReadable = isReadable,
      lastModified = if (exists) file.lastModified() else 0L,
      absolutePath = canonicalPath,
      originatingEnvironment = originatingEnvironment,
      mimeType = mime,
      checksum = effectiveChecksum,
      creationStatus = creationStatus,
      integrity = ArtifactIntegrityInfo(
        sha256 = effectiveChecksum,
        byteCount = length,
        isFormatValid = validation.isValid,
        formatDescription = validation.detectedFormat,
        validationError = validation.errorMessage,
        verifiedAt = System.currentTimeMillis()
      ),
      category = category,
      version = currentVersion
    )

    if (exists && isReadable) {
      registeredArtifacts[cleanRelPath] = artifact
      persistenceStore.save(artifact)
      if (creationStatus == ArtifactCreationStatus.VERIFIED && artifact.isUserFacing) {
        try {
          databaseRegistrar?.invoke(artifact)
        } catch (_: Exception) {}
      }
    } else {
      registeredArtifacts[cleanRelPath] = artifact
      persistenceStore.save(artifact)
    }
    return artifact
  }

  /**
   * Computes authoritative SHA-256 checksum from actual file bytes on disk.
   */
  fun computeSha256(file: File): String? {
    return ArtifactValidatorRegistry.computeSha256(file)
  }

  fun registerArtifact(
    file: File,
    createdByCallId: String? = null,
    taskId: String? = null,
    originatingEnvironment: String = "E2B_CLOUD",
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
    val cleanRelPath = PathSecurity.toCanonicalRelativePath(workspaceDir, relativePath)
    registeredArtifacts.remove(cleanRelPath)
    persistenceStore.delete(cleanRelPath)
  }

  /**
   * Scans the workspace directory, verifies all existing files, prunes deleted files,
   * updates persistent storage, and returns the authoritative list of existing artifacts.
   */
  fun syncWorkspace(taskId: String? = null, defaultEnvironment: String = "E2B_CLOUD"): List<Artifact> {
    if (!workspaceDir.exists()) {
      workspaceDir.mkdirs()
      registeredArtifacts.clear()
      persistenceStore.clear()
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
    toRemove.forEach { path ->
      registeredArtifacts.remove(path)
      persistenceStore.delete(path)
    }

    // Scan directory for valid deliverable files, ignoring internal/session metadata and active databases
    workspaceDir.walkTopDown()
      .maxDepth(5)
      .filter { file ->
        val name = file.name.lowercase()
        val parentName = file.parentFile?.name.orEmpty().lowercase()
        val isLink = PathSecurity.isSymbolicLink(file)
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
          parentName != ".observations" &&
          parentName != ".artifacts" &&
          parentName != "workspace" &&
          parentName != "venv" &&
          parentName != ".venv" &&
          parentName != "node_modules" &&
          parentName != "bin" &&
          parentName != "tmp" &&
          parentName != "notes"
      }
      .forEach { file ->
        val rel = PathSecurity.getRelativePath(workspaceDir, file)
        val cleanRel = PathSecurity.toCanonicalRelativePath(workspaceDir, rel)
        if (!registeredArtifacts.containsKey(cleanRel)) {
          registerArtifact(cleanRel, createdByCallId = null, taskId = taskId, originatingEnvironment = defaultEnvironment)
        } else {
          // Re-verify existing artifact against disk state
          val existing = registeredArtifacts[cleanRel]!!
          if (existing.size != file.length() || existing.lastModified != file.lastModified()) {
            val validation = ArtifactValidatorRegistry.validateExistingFile(file)
            val updated = existing.copy(
              size = file.length(),
              lastModified = file.lastModified(),
              exists = true,
              isReadable = file.canRead(),
              checksum = validation.sha256 ?: computeSha256(file),
              creationStatus = if (validation.isValid) ArtifactCreationStatus.VERIFIED else ArtifactCreationStatus.CORRUPTED,
              integrity = ArtifactIntegrityInfo(
                sha256 = validation.sha256 ?: existing.integrity.sha256,
                byteCount = file.length(),
                isFormatValid = validation.isValid,
                formatDescription = validation.detectedFormat,
                validationError = validation.errorMessage,
                verifiedAt = System.currentTimeMillis()
              ),
              version = existing.version + 1
            )
            registeredArtifacts[cleanRel] = updated
            persistenceStore.save(updated)
          }
        }
      }

    return getUserFacingArtifacts(taskId)
  }

  fun determineMimeType(fileName: String): String {
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
   * Returns current list of registered, verified, readable user-facing deliverables.
   */
  fun getUserFacingArtifacts(taskId: String? = null): List<Artifact> {
    return registeredArtifacts.values.filter {
      val f = File(it.absolutePath)
      it.isUserFacing &&
        it.creationStatus == ArtifactCreationStatus.VERIFIED &&
        f.exists() && f.isFile && f.canRead() &&
        (taskId == null || it.taskId == null || it.taskId == taskId)
    }.sortedBy { it.path }
  }

  /**
   * Returns all deliverable artifacts (backward-compatible alias for getUserFacingArtifacts).
   */
  fun getArtifacts(taskId: String? = null): List<Artifact> = getUserFacingArtifacts(taskId)

  /**
   * Returns all registered artifacts, including internal observation spillovers and scratch files.
   */
  fun getAllArtifacts(): List<Artifact> {
    return registeredArtifacts.values.sortedBy { it.path }
  }

  /**
   * Returns a registered artifact by canonical relative path if verified and readable on disk.
   */
  fun getArtifact(relativePath: String): Artifact? {
    val clean = PathSecurity.toCanonicalRelativePath(workspaceDir, relativePath)
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
    return f.exists() && f.canRead() && f.length() > 0L && art.creationStatus == ArtifactCreationStatus.VERIFIED
  }

  /**
   * Cleans all deliverable files from the workspace.
   */
  fun cleanAll() {
    registeredArtifacts.clear()
    persistenceStore.clear()
    if (workspaceDir.exists()) {
      workspaceDir.listFiles()?.forEach { file ->
        val name = file.name.lowercase()
        val isProtected = name == "bin" || name == "tmp" || name == "lib" ||
          name.startsWith(".e2b_session") || name.startsWith(".sessions") ||
          name == "data.db" || name.startsWith("data.db-") || name == ".mcp_data" ||
          name == ".artifacts" ||
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

/**
 * Atomic persistent disk store for artifact metadata in .artifacts/registry.jsonl.
 */
class ArtifactPersistenceStore(
  private val workspaceDir: File
) {
  private val artifactsDir: File
    get() = File(workspaceDir, ".artifacts").apply { mkdirs() }

  private val registryFile: File
    get() = File(artifactsDir, "registry.jsonl")

  @Synchronized
  fun save(artifact: Artifact) {
    try {
      val existing = loadAll().toMutableMap()
      existing[artifact.path] = artifact
      persistAll(existing.values)
    } catch (_: Exception) {}
  }

  @Synchronized
  fun delete(cleanRelPath: String) {
    try {
      val existing = loadAll().toMutableMap()
      if (existing.remove(cleanRelPath) != null) {
        persistAll(existing.values)
      }
    } catch (_: Exception) {}
  }

  @Synchronized
  fun loadAll(): Map<String, Artifact> {
    if (!registryFile.exists()) return emptyMap()
    val result = mutableMapOf<String, Artifact>()
    try {
      registryFile.readLines(StandardCharsets.UTF_8).forEach { line ->
        if (line.isNotBlank()) {
          val art = Artifact.fromJsonString(line.trim())
          if (art != null && art.path.isNotBlank()) {
            result[art.path] = art
          }
        }
      }
    } catch (_: Exception) {}
    return result
  }

  @Synchronized
  fun clear() {
    try {
      if (registryFile.exists()) registryFile.delete()
    } catch (_: Exception) {}
  }

  private fun persistAll(artifacts: Collection<Artifact>) {
    val tmpFile = File(artifactsDir, ".tmp_registry_${System.currentTimeMillis()}.jsonl")
    try {
      tmpFile.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
        for (art in artifacts) {
          writer.write(art.toJsonString())
          writer.newLine()
        }
      }
      if (!tmpFile.renameTo(registryFile)) {
        java.nio.file.Files.move(
          tmpFile.toPath(),
          registryFile.toPath(),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
      }
    } catch (_: Exception) {
      try { tmpFile.delete() } catch (_: Exception) {}
    }
  }
}
