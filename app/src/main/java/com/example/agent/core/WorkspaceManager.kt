package com.example.agent.core

import java.io.File
import java.util.UUID

/**
 * Sandboxed workspace filesystem manager.
 * Isolates all agent file operations to a dedicated workspace directory.
 */
class WorkspaceManager(val baseDir: File) {

  init {
    if (!baseDir.exists()) {
      baseDir.mkdirs()
    }
  }

  /**
   * Resolves a relative or safe path inside the workspace root.
   * Throws an exception or returns null if it tries to escape the sandbox.
   */
  fun resolveSafe(relativePath: String): File {
    val cleanPath = relativePath.trim().removePrefix("./").removePrefix("/")
    val file = File(baseDir, cleanPath).canonicalFile
    val baseCanonical = baseDir.canonicalFile
    val basePathWithSeparator = if (baseCanonical.path.endsWith(File.separator)) {
      baseCanonical.path
    } else {
      baseCanonical.path + File.separator
    }
    if (file != baseCanonical && !file.path.startsWith(basePathWithSeparator)) {
      throw IllegalArgumentException("Access denied: path '$relativePath' attempts to escape the sandboxed workspace.")
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
      lastModified = if (exists) file.lastModified() else System.currentTimeMillis()
    )
  }

  /**
   * Scans all current files in the workspace and returns them as Artifacts.
   */
  fun listAllArtifacts(): List<Artifact> {
    val list = mutableListOf<Artifact>()
    baseDir.walkTopDown().forEach { file ->
      if (file != baseDir && file.isFile) {
        list.add(createArtifactFromFile(file))
      }
    }
    return list.sortedByDescending { it.lastModified }
  }

  /**
   * Cleans and initializes the workspace with useful initial context or reset.
   */
  fun resetWorkspace() {
    baseDir.deleteRecursively()
    baseDir.mkdirs()
  }

  /**
   * Initializes workspace with sample project files if empty (e.g. data.csv).
   */
  fun seedWorkspaceDefaults() {
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
}
