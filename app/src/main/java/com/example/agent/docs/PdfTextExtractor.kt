package com.example.agent.docs

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class PdfPageText(val page: Int, val text: String)

data class PdfTextResult(
  val pageCount: Int,
  val pages: List<PdfPageText>
) {
  val hasTextLayer: Boolean
    get() {
      if (pages.isEmpty()) return false
      var alpha = 0
      for (p in pages) {
        for (c in p.text) {
          if (c.isLetterOrDigit() || DocumentText.isArabicLetter(c)) alpha++
        }
      }
      return alpha.toDouble() / pages.size.toDouble() >= 100.0
    }
}

object PdfTextExtractor {

  private val resourceLoaderInitialized = AtomicBoolean(false)

  fun ensureResourceLoader(context: Context) {
    if (resourceLoaderInitialized.compareAndSet(false, true)) {
      try {
        PDFBoxResourceLoader.init(context.applicationContext)
      } catch (_: Throwable) {
      }
    }
  }

  fun extractText(file: File, maxPages: Int = 300): PdfTextResult {
    PDDocument.load(file).use { document ->
      val pageCount = document.numberOfPages
      val pages = mutableListOf<PdfPageText>()
      val limit = minOf(pageCount, maxPages)
      val stripper = PDFTextStripper()
      stripper.sortByPosition = true
      for (page in 1..limit) {
        val text = try {
          stripper.startPage = page
          stripper.endPage = page
          stripper.getText(document)
        } catch (_: Exception) {
          ""
        }
        pages.add(PdfPageText(page, text))
      }
      return PdfTextResult(pageCount, pages)
    }
  }
}
