package com.example.agent.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

object ArtifactDownloader {

  /**
   * Resolves the actual physical file for an artifact using authoritative paths,
   * workspace directories, and recursive fallbacks.
   */
  fun resolveArtifactFile(
    context: Context,
    artifact: Artifact,
    workspaceDir: File? = null
  ): File? {
    val cleanPath = artifact.path.trim().removePrefix("./").removePrefix("/")
    val fileName = artifact.name.ifBlank { File(cleanPath).name }

    // 1. Check workspaceDir if explicitly passed
    if (workspaceDir != null && workspaceDir.exists()) {
      val directInWorkspace = File(workspaceDir, cleanPath)
      if (directInWorkspace.exists() && directInWorkspace.isFile) {
        return directInWorkspace
      }
      val byNameInWorkspace = File(workspaceDir, fileName)
      if (byNameInWorkspace.exists() && byNameInWorkspace.isFile) {
        return byNameInWorkspace
      }
      try {
        val foundInWorkspace = workspaceDir.walkTopDown().maxDepth(5)
          .firstOrNull { it.isFile && (it.name == fileName || it.name == artifact.name) }
        if (foundInWorkspace != null && foundInWorkspace.exists()) {
          return foundInWorkspace
        }
      } catch (_: Throwable) {}
    }

    // 2. Check explicit absolutePath if populated
    if (artifact.absolutePath.isNotBlank()) {
      val absFile = File(artifact.absolutePath)
      if (absFile.exists() && absFile.isFile) return absFile
    }

    // 3. Direct check on artifact.path if it exists
    val direct = File(artifact.path)
    if (direct.exists() && direct.isFile) {
      return direct
    }

    // 4. Search in standard workspace and sandbox directories
    val candidateRoots = mutableListOf<File>()

    // App-specific internal workspace
    candidateRoots.add(File(context.filesDir, "agent_workspace"))
    candidateRoots.add(File(context.filesDir, "workspace"))
    candidateRoots.add(context.filesDir)

    // Cache workspace
    candidateRoots.add(File(context.cacheDir, "agent_workspace"))
    candidateRoots.add(context.cacheDir)

    // System temp workspace (used during Robolectric / local execution)
    val tmpBase = System.getProperty("java.io.tmpdir") ?: "."
    candidateRoots.add(File(tmpBase, "agent_workspace"))
    candidateRoots.add(File(tmpBase))

    // Working directory
    candidateRoots.add(File("agent_workspace"))
    candidateRoots.add(File("."))

    // App external files workspace
    try {
      context.getExternalFilesDir(null)?.let { extDir ->
        candidateRoots.add(File(extDir, "agent_workspace"))
        candidateRoots.add(extDir)
      }
    } catch (_: Throwable) {}

    // Test each candidate root against relative path and file name
    for (root in candidateRoots) {
      try {
        if (!root.exists()) continue

        val fileByPath = File(root, cleanPath)
        if (fileByPath.exists() && fileByPath.isFile) {
          return fileByPath
        }

        val fileByName = File(root, fileName)
        if (fileByName.exists() && fileByName.isFile) {
          return fileByName
        }
      } catch (_: Throwable) {}
    }

    // 5. Exhaustive search: walk top-down in filesDir for the file
    try {
      val matchInFiles = context.filesDir.walkTopDown()
        .maxDepth(5)
        .firstOrNull { it.isFile && (it.name == fileName || it.name == artifact.name) }
      if (matchInFiles != null && matchInFiles.exists()) {
        return matchInFiles
      }
    } catch (_: Throwable) {}

    // 6. Exhaustive search in tempDir (handles JUnit TemporaryFolder / Robolectric)
    try {
      val matchInTmp = File(tmpBase).walkTopDown()
        .maxDepth(6)
        .firstOrNull { it.isFile && (it.name == fileName || it.name == artifact.name) }
      if (matchInTmp != null && matchInTmp.exists()) {
        return matchInTmp
      }
    } catch (_: Throwable) {}

    return null
  }

  private fun showSafeToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
    try {
      android.os.Handler(android.os.Looper.getMainLooper()).post {
        try {
          Toast.makeText(context.applicationContext ?: context, message, duration).show()
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
  }

  /**
   * Downloads and saves an artifact to the device's public Downloads directory,
   * falling back cleanly across Scoped Storage, MediaStore, and external storage.
   */
  fun downloadArtifact(
    context: Context,
    artifact: Artifact,
    workspaceDir: File? = null
  ): Boolean {
    return try {
      val sourceFile = resolveArtifactFile(context, artifact, workspaceDir)
      if (sourceFile == null || !sourceFile.exists()) {
        showSafeToast(context, "File not found: ${artifact.name}", Toast.LENGTH_SHORT)
        return false
      }

      val bytes = sourceFile.readBytes()
      val mimeType = getMimeType(artifact.name)
      var saved = false

      // 1. Android Q+ MediaStore API (Scoped Storage compliant)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
          val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AragonOutputs")
          }

          val resolver = context.contentResolver
          val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
          if (uri != null) {
            try {
              resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(bytes)
                outputStream.flush()
              }
              showSafeToast(context, "Saved to Downloads: ${artifact.name}", Toast.LENGTH_LONG)
              saved = true
            } catch (writeErr: Throwable) {
              try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
              saved = false
            }
          }
        } catch (_: Throwable) {
          saved = false
        }
      }

      // 2. Standard Public Downloads folder fallback
      if (!saved) {
        try {
          val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
          val targetDir = File(downloadsDir, "AragonOutputs")
          if (!targetDir.exists()) {
            targetDir.mkdirs()
          }
          if (targetDir.exists() && targetDir.canWrite()) {
            val targetFile = File(targetDir, artifact.name)
            FileOutputStream(targetFile).use { fos ->
              fos.write(bytes)
              fos.flush()
            }
            showSafeToast(context, "Saved to Downloads/AragonOutputs: ${artifact.name}", Toast.LENGTH_LONG)
            saved = true
          }
        } catch (_: Throwable) {
          saved = false
        }
      }

      // 3. App-specific External Files folder fallback (always accessible without permissions)
      if (!saved) {
        try {
          val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(null)
          if (appExtDir != null) {
            val targetDir = File(appExtDir, "AragonOutputs")
            if (!targetDir.exists()) {
              targetDir.mkdirs()
            }
            val targetFile = File(targetDir, artifact.name)
            FileOutputStream(targetFile).use { fos ->
              fos.write(bytes)
              fos.flush()
            }
            showSafeToast(context, "Saved to: ${targetFile.name}", Toast.LENGTH_LONG)
            saved = true
          }
        } catch (_: Throwable) {
          saved = false
        }
      }

      // 4. Final internal exported fallback
      if (!saved) {
        val exportDir = File(context.filesDir, "exported_artifacts")
        exportDir.mkdirs()
        val targetFile = File(exportDir, artifact.name)
        FileOutputStream(targetFile).use { fos ->
          fos.write(bytes)
          fos.flush()
        }
        showSafeToast(context, "Saved to app storage: ${targetFile.name}", Toast.LENGTH_LONG)
        saved = true
      }

      saved
    } catch (e: Exception) {
      e.printStackTrace()
      showSafeToast(context, "Download failed: ${e.message}", Toast.LENGTH_LONG)
      false
    }
  }

  /**
   * Shares an artifact using the Android system share sheet.
   */
  fun shareArtifact(
    context: Context,
    artifact: Artifact,
    workspaceDir: File? = null
  ) {
    try {
      val sourceFile = resolveArtifactFile(context, artifact, workspaceDir)
      if (sourceFile == null || !sourceFile.exists()) {
        showSafeToast(context, "File not found: ${artifact.name}", Toast.LENGTH_SHORT)
        return
      }

      if (isTextFile(artifact.name)) {
        val content = sourceFile.readText(Charsets.UTF_8)
        val sendIntent = Intent().apply {
          action = Intent.ACTION_SEND
          putExtra(Intent.EXTRA_TITLE, artifact.name)
          putExtra(Intent.EXTRA_SUBJECT, artifact.name)
          putExtra(Intent.EXTRA_TEXT, content)
          type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share ${artifact.name}")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
      } else {
        // Binary artifact: initiate download
        downloadArtifact(context, artifact, workspaceDir)
      }
    } catch (e: Exception) {
      showSafeToast(context, "Share error: ${e.message}", Toast.LENGTH_SHORT)
    }
  }

  fun isTextFile(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
      "txt", "md", "json", "csv", "html", "htm", "xml", "py", "js", "ts",
      "sh", "yaml", "yml", "sql", "log", "kt", "java", "properties", "env" -> true
      else -> false
    }
  }

  fun getMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
      "txt", "md" -> "text/plain"
      "json" -> "application/json"
      "csv" -> "text/csv"
      "html", "htm" -> "text/html"
      "xml" -> "application/xml"
      "py" -> "text/x-python"
      "js", "ts" -> "application/javascript"
      "sh" -> "application/x-sh"
      "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      "pdf" -> "application/pdf"
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "zip" -> "application/zip"
      else -> "application/octet-stream"
    }
  }
}
