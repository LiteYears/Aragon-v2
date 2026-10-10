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
    if (src.canonicalPath == dst.canonicalPath) return dst
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
   * Scans user deliverables in the workspace and returns them as Artifacts.
   * Strictly filters out internal system files, scratchpad notes, checkpoints, and temporary files
   * to eliminate state pollution across runs.
   */
  fun listAllArtifacts(): List<Artifact> {
    val list = mutableListOf<Artifact>()
    try {
      if (baseDir.exists() && baseDir.isDirectory) {
        baseDir.walkTopDown().forEach { file ->
          if (file != baseDir && file.isFile) {
            val relPath = getRelativePath(file)
            val isInternal = relPath.startsWith("notes/") ||
              relPath == "notes" ||
              relPath.startsWith(".scratchpad/") ||
              relPath == "checkpoint.json" ||
              relPath == "plan.md" ||
              file.name.startsWith(".") ||
              file.name.endsWith(".tmp") ||
              file.name.startsWith(".part_")

            if (!isInternal) {
              list.add(createArtifactFromFile(file))
            }
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
   * Removes all artifacts and files from the workspace so that the user starts completely fresh and clean.
   */
  fun cleanAllArtifacts() {
    try {
      if (baseDir.exists() && baseDir.isDirectory) {
        baseDir.listFiles()?.forEach { file ->
          file.deleteRecursively()
        }
      }
      baseDir.mkdirs()
    } catch (_: Exception) {}
  }

  /**
   * Removes temporary files (e.g. .tmp, partial files).
   */
  fun cleanTemporaryFiles() {
    try {
      if (baseDir.exists() && baseDir.isDirectory) {
        baseDir.walkTopDown().forEach { file ->
          if (file.isFile && (file.name.endsWith(".tmp") || file.name.startsWith(".part_") || file.name.startsWith("."))) {
            file.delete()
          }
        }
      }
    } catch (_: Exception) {}
  }

  /**
   * Initializes workspace with sample project inputs if empty (e.g. data.csv, sys_info.txt).
   * Does NOT pre-populate fake deliverables or arbitrary news articles to maintain pristine state.
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
=== ARAGON AGENT SANDBOX ENVIRONMENT DIAGNOSTICS ===
Report Generated: October 2026
Hostname: aragon-sandbox-kernel
OS: Android 15 / Linux 6.6.0-generic

1. OPERATING SYSTEM & KERNEL
OS Version: Android 15 (API Level 36)
Kernel Version: Linux 6.6.0-android-x86_64
Architecture: aarch64 / x86_64 compatible
Runtime: ART (Android Runtime 2.1)
Shell: /system/bin/sh (Sandboxed POSIX)

2. CPU & HARDWARE SPECIFICATIONS
Processor: Octa-core ARMv8.2-A / Intel Virtual Host
Cores: 8 Cores (4 Performance @ 2.84 GHz, 4 Efficiency @ 1.80 GHz)
Instruction Sets: arm64-v8a, armeabi-v7a, x86_64
Hardware Concurrency: Enabled

3. MEMORY & STORAGE DIAGNOSTICS
Total System RAM: 8192 MB (8.0 GB)
Available RAM: 5240 MB (64% Free)
Dalvik Heap Limit: 512 MB
Workspace Storage: 64 GB Sandboxed Ext4
I/O Latency: 0.12 ms (Solid State Drive)

4. PYTHON & RUNTIME ENVIRONMENT
Python Version: Python 3.12.2 Native Runtime
Pip Version: Pip 24.0 Package Manager
Active Packages: python-docx (1.1.2), pandas (2.2.1), openpyxl (3.1.2), requests (2.31.0)
Word Document Engine: OpenXML Compliant (.docx / .doc)
Bi-directional RTL Support: Enabled (Arabic & Complex Scripts)

5. NETWORK & SECURITY SUBSYSTEM
Network Connectivity: Active (WiFi / Virtual Ethernet)
TLS Version: TLS 1.3 Strict
Security Sandbox: Linux UID Isolation, App Sandbox Layer 2
Audit Verification: Authoritative Filesystem Integrity Checking
==================================================
          """.trimIndent()
        )
      }
    } catch (_: Exception) {}
  }
}
