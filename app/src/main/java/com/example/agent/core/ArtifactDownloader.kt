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

  fun downloadArtifact(context: Context, artifact: Artifact): Boolean {
    return try {
      val sourceFile = File(artifact.path)
      if (!sourceFile.exists()) {
        Toast.makeText(context, "File not found: ${artifact.name}", Toast.LENGTH_SHORT).show()
        return false
      }

      val bytes = sourceFile.readBytes()
      val mimeType = getMimeType(artifact.name)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.name)
          put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
          put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AgentOutputs")
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
          resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(bytes)
            outputStream.flush()
          }
          Toast.makeText(context, "Saved to Downloads: ${artifact.name}", Toast.LENGTH_LONG).show()
          return true
        }
      }

      // Fallback for earlier versions or standard external storage
      val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      val targetDir = File(downloadsDir, "AgentOutputs")
      if (!targetDir.exists()) {
        targetDir.mkdirs()
      }
      val targetFile = File(targetDir, artifact.name)
      FileOutputStream(targetFile).use { it.write(bytes) }

      Toast.makeText(context, "Saved to: ${targetFile.absolutePath}", Toast.LENGTH_LONG).show()
      true
    } catch (e: Exception) {
      e.printStackTrace()
      Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
      false
    }
  }

  fun shareArtifact(context: Context, artifact: Artifact) {
    try {
      val sourceFile = File(artifact.path)
      if (!sourceFile.exists()) {
        Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
        return
      }

      val content = sourceFile.readText()
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
    } catch (e: Exception) {
      Toast.makeText(context, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
  }

  private fun getMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
      "txt", "md" -> "text/plain"
      "json" -> "application/json"
      "csv" -> "text/csv"
      "html" -> "text/html"
      "py" -> "text/x-python"
      "js", "ts" -> "application/javascript"
      "sh" -> "application/x-sh"
      else -> "application/octet-stream"
    }
  }
}
