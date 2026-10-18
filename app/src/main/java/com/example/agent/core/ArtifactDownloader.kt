package com.example.agent.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

object ArtifactDownloader {

  /**
   * Global default workspace directory fallback initialized by the engine or view model.
   */
  var defaultWorkspaceDir: File? = null

  /**
   * Resolves the actual physical file for an artifact using authoritative paths,
   * workspace directories, and recursive fallbacks.
   */
  fun resolveArtifactFile(
    context: Context,
    artifact: Artifact,
    workspaceDir: File? = null
  ): File? {
    val effectiveWorkspace = workspaceDir ?: defaultWorkspaceDir
    val cleanPath = PathSecurity.toCanonicalRelativePath(effectiveWorkspace, artifact.path)
    val fileName = artifact.name.ifBlank { File(cleanPath).name }

    // 1. Check explicit canonicalPath / absolutePath first
    val explicitPath = artifact.canonicalPath.ifBlank { artifact.absolutePath }
    if (explicitPath.isNotBlank()) {
      val absFile = File(explicitPath)
      if (absFile.exists() && absFile.isFile) return absFile
    }

    // 2. Check effective workspace directory
    if (effectiveWorkspace != null && effectiveWorkspace.exists()) {
      val directInWorkspace = File(effectiveWorkspace, cleanPath)
      if (directInWorkspace.exists() && directInWorkspace.isFile) {
        return directInWorkspace
      }
      val byNameInWorkspace = File(effectiveWorkspace, fileName)
      if (byNameInWorkspace.exists() && byNameInWorkspace.isFile) {
        return byNameInWorkspace
      }
      try {
        val foundInWorkspace = effectiveWorkspace.walkTopDown().maxDepth(5)
          .firstOrNull { it.isFile && (it.name == fileName || it.name == artifact.name) }
        if (foundInWorkspace != null && foundInWorkspace.exists()) {
          return foundInWorkspace
        }
      } catch (_: Throwable) {}
    }

    // 3. Direct check on artifact.path if it exists
    val direct = File(artifact.path)
    if (direct.exists() && direct.isFile) {
      return direct
    }

    // 4. Search in standard workspace and sandbox directories
    val candidateRoots = mutableListOf<File>()
    candidateRoots.add(File(context.filesDir, "agent_workspace"))
    candidateRoots.add(File(context.filesDir, "workspace"))
    candidateRoots.add(File(context.cacheDir, "agent_workspace"))
    candidateRoots.add(File("/data/local/tmp/agent_workspace"))
    try {
      context.getExternalFilesDir(null)?.let { extDir ->
        candidateRoots.add(File(extDir, "agent_workspace"))
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
      val tmpBase = System.getProperty("java.io.tmpdir") ?: "/tmp"
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
      if (sourceFile.length() == 0L) {
        showSafeToast(context, "Artifact '${artifact.name}' is empty (0 bytes).", Toast.LENGTH_SHORT)
        return false
      }

      val mimeType = getMimeType(artifact.name)
      var saved = false

      // Helper function to stream file content safely without loading entire byte array into memory
      fun copySourceTo(outputStream: java.io.OutputStream) {
        sourceFile.inputStream().use { input ->
          input.copyTo(outputStream, bufferSize = 16384)
        }
        outputStream.flush()
      }

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
                copySourceTo(outputStream)
              }
              showSafeToast(context, "Saved to Downloads: ${artifact.name}", Toast.LENGTH_SHORT)
              saved = true
              openDownloadedFile(context, uri = uri, file = null, mimeType = mimeType, fileName = artifact.name)
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
              copySourceTo(fos)
            }
            showSafeToast(context, "Saved to Downloads/AragonOutputs: ${artifact.name}", Toast.LENGTH_SHORT)
            saved = true
            openDownloadedFile(context, uri = null, file = targetFile, mimeType = mimeType, fileName = artifact.name)
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
              copySourceTo(fos)
            }
            showSafeToast(context, "Saved to: ${targetFile.name}", Toast.LENGTH_SHORT)
            saved = true
            openDownloadedFile(context, uri = null, file = targetFile, mimeType = mimeType, fileName = artifact.name)
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
          copySourceTo(fos)
        }
        showSafeToast(context, "Saved to app storage: ${targetFile.name}", Toast.LENGTH_SHORT)
        saved = true
        openDownloadedFile(context, uri = null, file = targetFile, mimeType = mimeType, fileName = artifact.name)
      }

      saved
    } catch (e: Exception) {
      e.printStackTrace()
      showSafeToast(context, "Download failed: ${e.message}", Toast.LENGTH_LONG)
      false
    }
  }

  /**
   * Opens the downloaded artifact file or URI automatically via the system viewer.
   */
  fun openDownloadedFile(
    context: Context,
    uri: Uri?,
    file: File?,
    mimeType: String,
    fileName: String
  ) {
    try {
      val targetUri: Uri? = uri ?: file?.let { f ->
        if (!f.exists()) null
        else {
          try {
            androidx.core.content.FileProvider.getUriForFile(
              context,
              "${context.packageName}.fileprovider",
              f
            )
          } catch (e: Throwable) {
            Uri.fromFile(f)
          }
        }
      }

      if (targetUri != null) {
        val resolvedMime = if (mimeType.isNotBlank() && mimeType != "application/octet-stream") {
          mimeType
        } else {
          getMimeType(fileName).ifBlank { "*/*" }
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(targetUri, resolvedMime)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Grant URI permissions explicitly to all potential resolving components
        try {
          val resInfoList = context.packageManager.queryIntentActivities(
            viewIntent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
          )
          for (resolveInfo in resInfoList) {
            val packageName = resolveInfo.activityInfo.packageName
            context.grantUriPermission(packageName, targetUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
        } catch (_: Throwable) {}

        // 1. Direct launch first for instant automatic opening without an extra prompt when possible
        try {
          context.startActivity(viewIntent)
        } catch (_: Throwable) {
          // 2. Fallback to system Chooser with specific MIME
          try {
            val chooser = Intent.createChooser(viewIntent, "Open $fileName").apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
          } catch (_: Throwable) {
            // 3. Ultimate wildcard fallback (*/*) to ensure any available system handler can open it
            try {
              val wildcardIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(targetUri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
              }
              val wildcardChooser = Intent.createChooser(wildcardIntent, "Open $fileName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
              context.startActivity(wildcardChooser)
            } catch (finalErr: Throwable) {
              android.util.Log.w("ArtifactDownloader", "All attempts to auto-open file failed: ${finalErr.message}")
            }
          }
        }
      }
    } catch (e: Throwable) {
      android.util.Log.w("ArtifactDownloader", "Unable to open file automatically: ${e.message}")
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
      if (sourceFile.length() == 0L) {
        showSafeToast(context, "Artifact '${artifact.name}' is empty (0 bytes).", Toast.LENGTH_SHORT)
        return
      }

      if (isTextFile(artifact.name)) {
        // Enforce maximum text payload to prevent Binder TransactionTooLargeException crashes (1MB system limit)
        val maxShareChars = 150_000
        val content = if (sourceFile.length() > maxShareChars) {
          sourceFile.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(maxShareChars)
            val read = reader.read(buf)
            String(buf, 0, read) + "\n\n...[Content truncated for system share sheet. Full file saved in workspace]..."
          }
        } else {
          sourceFile.readText(Charsets.UTF_8)
        }
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
      "docx", "doc" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      "xls" -> "application/vnd.ms-excel"
      "pdf" -> "application/pdf"
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "zip" -> "application/zip"
      else -> "application/octet-stream"
    }
  }
}
