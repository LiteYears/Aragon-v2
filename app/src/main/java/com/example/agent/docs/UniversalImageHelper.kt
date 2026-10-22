package com.example.agent.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Universal image processing engine for Aragon.
 * Provides resilient image decoding across all formats (PNG, JPEG, WebP, GIF, BMP, HEIC),
 * EXIF orientation normalization, multi-angle rotation, and contrast enhancement for OCR.
 */
object UniversalImageHelper {

  const val MAX_OCR_DIMENSION = 2560
  const val MIN_OCR_DIMENSION = 600

  /**
   * Decodes an image file into a normalized, upright Bitmap regardless of source orientation or format.
   */
  fun decodeNormalizedBitmap(imageFile: File, maxDimension: Int = MAX_OCR_DIMENSION): Bitmap? {
    if (!imageFile.exists() || imageFile.length() == 0L) return null

    // 1. Read bounds and EXIF orientation
    val orientation = readExifOrientation(imageFile)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(imageFile.absolutePath, bounds)

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      // Try fallback stream decode
      return decodeViaStream(imageFile, maxDimension, orientation)
    }

    // 2. Compute sample size
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (longest / (sampleSize * 2) >= maxDimension) {
      sampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    var bitmap = try {
      BitmapFactory.decodeFile(imageFile.absolutePath, decodeOpts)
    } catch (_: OutOfMemoryError) {
      try {
        decodeOpts.inSampleSize = sampleSize * 2
        decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565
        BitmapFactory.decodeFile(imageFile.absolutePath, decodeOpts)
      } catch (_: OutOfMemoryError) {
        null
      }
    } ?: return decodeViaStream(imageFile, maxDimension, orientation)

    // 3. Apply EXIF rotation if needed
    if (orientation != 0) {
      val rotated = rotateBitmap(bitmap, orientation)
      if (rotated != bitmap) {
        bitmap.recycle()
        bitmap = rotated
      }
    }

    // 4. Final scale if still exceeds maxDimension
    val curLongest = maxOf(bitmap.width, bitmap.height)
    if (curLongest > maxDimension) {
      val ratio = maxDimension.toFloat() / curLongest.toFloat()
      val newW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
      val newH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
      val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
      if (scaled != bitmap) {
        bitmap.recycle()
        bitmap = scaled
      }
    }

    return bitmap
  }

  /**
   * Reads EXIF orientation in degrees (0, 90, 180, 270).
   */
  fun readExifOrientation(imageFile: File): Int {
    return try {
      val exif = ExifInterface(imageFile.absolutePath)
      when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
      }
    } catch (_: Throwable) {
      0
    }
  }

  /**
   * Rotates a bitmap by [degrees].
   */
  fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
    if (degrees % 360 == 0) return source
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
  }

  /**
   * Enhances image contrast and binarizes for faint text, receipts, scans, or low-contrast backgrounds.
   */
  fun enhanceForOcr(source: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Grayscale + High-Contrast ColorMatrix
    val cm = ColorMatrix().apply {
      setSaturation(0f) // Convert to Grayscale
      val contrast = 1.4f
      val translate = (-128f * (contrast - 1f))
      val contrastMatrix = floatArrayOf(
        contrast, 0f, 0f, 0f, translate,
        0f, contrast, 0f, 0f, translate,
        0f, 0f, contrast, 0f, translate,
        0f, 0f, 0f, 1f, 0f
      )
      postConcat(ColorMatrix(contrastMatrix))
    }
    paint.colorFilter = ColorMatrixColorFilter(cm)
    canvas.drawBitmap(source, 0f, 0f, paint)
    return output
  }

  /**
   * Encodes a bitmap as a JPEG byte array.
   */
  fun toJpegBytes(bitmap: Bitmap, quality: Int = 90): ByteArray {
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
    return baos.toByteArray()
  }

  /**
   * Encodes a bitmap as a Base64 string for Vision LLM APIs.
   */
  fun toBase64Jpeg(bitmap: Bitmap, quality: Int = 88): String {
    val bytes = toJpegBytes(bitmap, quality)
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  /**
   * Saves a bitmap to file.
   */
  fun saveToFile(bitmap: Bitmap, targetFile: File, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 90) {
    targetFile.parentFile?.mkdirs()
    FileOutputStream(targetFile).use { out ->
      bitmap.compress(format, quality, out)
    }
  }

  /**
   * Infers image MIME type from file extension or content.
   */
  fun detectMimeType(file: File): String {
    return when (file.extension.lowercase()) {
      "png" -> "image/png"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      "bmp" -> "image/bmp"
      "heic", "heif" -> "image/heic"
      "svg" -> "image/svg+xml"
      "tiff", "tif" -> "image/tiff"
      else -> "image/jpeg"
    }
  }

  private fun decodeViaStream(file: File, maxDimension: Int, orientation: Int): Bitmap? {
    return try {
      file.inputStream().use { stream ->
        var bmp = BitmapFactory.decodeStream(stream) ?: return null
        if (orientation != 0) {
          val rotated = rotateBitmap(bmp, orientation)
          if (rotated != bmp) {
            bmp.recycle()
            bmp = rotated
          }
        }
        val curLongest = maxOf(bmp.width, bmp.height)
        if (curLongest > maxDimension) {
          val ratio = maxDimension.toFloat() / curLongest.toFloat()
          val newW = (bmp.width * ratio).toInt().coerceAtLeast(1)
          val newH = (bmp.height * ratio).toInt().coerceAtLeast(1)
          val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)
          if (scaled != bmp) {
            bmp.recycle()
            bmp = scaled
          }
        }
        bmp
      }
    } catch (_: Throwable) {
      null
    }
  }
}
