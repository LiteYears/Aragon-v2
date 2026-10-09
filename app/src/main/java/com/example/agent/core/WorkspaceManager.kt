package com.example.agent.core

import java.io.File
import java.util.UUID

/**
 * Sandboxed workspace filesystem manager.
 * Isolates all agent file operations to a dedicated workspace directory.
 */
class WorkspaceManager(val baseDir: File) {

  init {
    try {
      if (!baseDir.exists()) {
        baseDir.mkdirs()
      }
    } catch (_: Exception) {}
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

  /**
   * Safely writes text content to a relative workspace path, creating directories as needed.
   */
  fun writeWorkspaceFile(relativePath: String, content: String): File {
    val file = resolveSafe(relativePath)
    file.parentFile?.mkdirs()
    file.writeText(content, Charsets.UTF_8)
    return file
  }

  /**
   * Safely copies a file or directory within the workspace.
   */
  fun copyFile(sourcePath: String, destPath: String): File {
    val src = resolveSafe(sourcePath)
    if (!src.exists()) throw IllegalArgumentException("Source '$sourcePath' does not exist.")
    val dst = resolveSafe(destPath)
    dst.parentFile?.mkdirs()
    if (src.isDirectory) {
      src.copyRecursively(dst, overwrite = true)
    } else {
      src.copyTo(dst, overwrite = true)
    }
    return dst
  }

  /**
   * Safely moves or renames a file or directory within the workspace.
   */
  fun moveFile(sourcePath: String, destPath: String): File {
    val src = resolveSafe(sourcePath)
    if (!src.exists()) throw IllegalArgumentException("Source '$sourcePath' does not exist.")
    val dst = resolveSafe(destPath)
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
      lastModified = if (exists) file.lastModified() else System.currentTimeMillis(),
      absolutePath = file.canonicalPath
    )
  }

  /**
   * Scans all current files in the workspace and returns them as Artifacts.
   */
  fun listAllArtifacts(): List<Artifact> {
    val list = mutableListOf<Artifact>()
    try {
      if (baseDir.exists() && baseDir.isDirectory) {
        baseDir.walkTopDown().forEach { file ->
          if (file != baseDir && file.isFile) {
            list.add(createArtifactFromFile(file))
          }
        }
      }
    } catch (_: Exception) {}
    return list.sortedByDescending { it.lastModified }
  }

  /**
   * Cleans and initializes the workspace with useful initial context or reset.
   */
  fun resetWorkspace() {
    try {
      baseDir.deleteRecursively()
      baseDir.mkdirs()
    } catch (_: Exception) {}
  }

  /**
   * Initializes workspace with sample project files if empty (e.g. data.csv).
   */
  fun seedWorkspaceDefaults() {
    try {
      if (!baseDir.exists()) {
        baseDir.mkdirs()
      }
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

      val sysInfoTxt = File(baseDir, "sys_info.txt")
      if (!sysInfoTxt.exists()) {
        sysInfoTxt.writeText(
          """
          ================================================================
          SYSTEM ENVIRONMENT DIAGNOSTICS REPORT
          ================================================================
          Generated : 2026-10-09 12:47 CET
          Host      : localhost
          Platform  : Android (isolated mobile app sandbox)
          Workspace : ${baseDir.absolutePath}

          ----------------------------------------------------------------
          1. OPERATING SYSTEM & KERNEL
          ----------------------------------------------------------------
          Kernel          : Linux localhost 6.12.38-android16
          Architecture    : aarch64 (ARM 64-bit)
          Android version : 16
          Android SDK     : 36
          Userland        : Toybox + Native Python 3.12 Runtime

          ----------------------------------------------------------------
          2. CPU / PROCESSOR
          ----------------------------------------------------------------
          Core count      : 8 (nproc)
          CPU architecture: ARM Cortex-A55 (Cortex-A78)
          BogoMIPS        : 26.00 per core

          ----------------------------------------------------------------
          3. MEMORY & STORAGE
          ----------------------------------------------------------------
          MemTotal        : 3,757,964 kB (~3.6 GB)
          MemAvailable    : 1,233,704 kB (~1.2 GB)
          App storage     : 45 GB free on user data partition
          Workspace state : Active

          ----------------------------------------------------------------
          4. RUNTIME RUNTIMES & PACKAGES
          ----------------------------------------------------------------
          Python 3        : Python 3.12.2 (Native Engine)
          Pip             : pip 24.0 (python-docx, pandas, openpyxl, requests)
          Shell           : sh (Toybox)

          ================================================================
          END OF REPORT
          ================================================================
          """.trimIndent()
        )
      }
    } catch (_: Exception) {}
  }
}
