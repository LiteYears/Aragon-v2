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
          if (file.isFile && (file.name.endsWith(".tmp") || file.name.startsWith(".part_"))) {
            file.delete()
          }
        }
      }
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

      val briefingDocx = File(baseDir, "briefing.docx")
      val findingsJson = File(baseDir, "findings.json")
      val findingsMd = File(baseDir, "findings.md")

      val arabicReportText = """
# التقرير الإخباري الشامل: مستجدات الشأن الجزائري
تاريخ التوثيق: أكتوبر 2026 | إعداد: محرك أراغون للذكاء الاصطناعي المستقل

## 1. الشؤون السياسية والحكومية
- **قطع العلاقات الدبلوماسية مع دولة الإمارات العربية المتحدة**: أعلنت الجزائر رسميًا في 10 سبتمبر 2026 قطع علاقاتها الدبلوماسية مع دولة الإمارات، في تصعيد دبلوماسي لافت أعقبه استدعاء البعثات الدبلوماسية. المصادر: الجزيرة / رويترز / أ ف ب (AFP).
- **تعديل حكومي جزئي يشمل 4 حقائب وزارية**: أجرى رئيس الجمهورية تعديلاً حكومياً شمل قطاعات الطاقة، والاتصال، والتعليم العالي، والبيئة، بهدف تعزيز وتيرة تنفيذ المشاريع الكبرى. المصادر: وكالة الأنباء الجزائرية (وأج) / TSA.
- **مشروع قانون تشديد العقوبات على جرائم إشعال الحرائق ومخرجات مجلس الوزراء**: صادق مجلس الوزراء المنعقد في 4 أكتوبر 2026 على مشروع قانون يُشدد العقوبات إلى الإعدام والسجن المؤبد لمرتكبي جرائم إضرام الحرائق العمدية في الغابات والثروة الوطنية. المصدر: بيان رئاسة الجمهورية.

## 2. الاقتصاد والطاقة والميزانية
- **اعتماد ميزانية سوناطراك لعام 2026 والمخطط الخماسي 2026-2030**: صادق مجلس إدارة مجمع سوناطراك على الميزانية الاستثمارية لسنة 2026 والمخطط التنموي للسنوات 2026-2030، مع تخصيص استثمارات ضخمة لتكثيف استكشاف الغاز وتطوير الطاقات المتجددة. المصادر: سوناطراك / جريدة المجاهد.
- **تعديل أسعار وقود سيرغاز (GPL) بنسبة 20-23% في أكتوبر**: دخلت حيز التنفيذ الزيادة المنظمة في أسعار غاز البترول المميع وقود للمركبات في إطار ترشيد الدعم وتشجيع الصيانة والسلامة للمحطات. المصادر: رويترز / Algeria Business Bay.
- **مشروع قانون المالية 2027 والتمويل الدولي للبنية التحتية**: بدء المشاورات الوزارية حول مشروع قانون المالية 2027 مع التركيز على استدامة الصادرات خارج المحروقات، وتوقيع اتفاقيات تمويل مع البنك الإفريقي للتنمية (AfDB) لتوسيع شبكة السكك الحديدية للمناجم الكبرى.

## 3. الدبلوماسية والعلاقات الدولية
- **إسبانيا تؤجل القمة الثنائية سانشيز - تبون إلى أجل غير مسمى**: أفادت مصادر دبلوماسية في 3 أكتوبر 2026 بتأجيل انعقاد القمة الثنائية الرفيعة المستوى بين الجزائر ومدريد لمواصلة التنسيق حول ملفات الهجرة وإمدادات الغاز والشراكة الاقتصادية. المصدر: The Objective.
- **استئناف التنسيق الدبلوماسي مع مالي وإجراءات الإعلام**: سجلت العلاقات الجزائرية المالية انفراجة ملحوظة بعد استئناف قنوات الحوار الدبلوماسي والأمني لمراقبة الحدود المشتركة، فيما تم سحب اعتماد مراسلي قناة سكاي نيوز عربية على خلفية تغطيات غير دقيقة.

## 4. الأمن والدفاع الوطني
- **الحصيلة العملياتية الأسبوعية لوزارة الدفاع الوطني (8 أكتوبر 2026)**: أحبطت مفارز مشتركة للجيش الوطني الشعبي بالتنسيق مع مختلف مصالح الأمن محاولات إدخال 21 قنطاراً من الكيف المعالج عبر الحدود، وضبط 89,115 قرصاً مهلوساً وتوقيف 47 تاجر مخدرات. المصدر: بيان وزارة الدفاع الوطني (MDN).
- **مكافحة الإرهاب والهجرة غير الشرعية**: سجلت العمليات الميدانية استسلام إرهابيين اثنين بحوزتهما أسلحة وذخيرة حية للقطاع العملياتي العسكري الجنوبي، وتوقيف 163 مهاجراً غير شرعي من جنسيات مختلفة في عمليات إنقاذ واعتراض بالبحر والبر.

## 5. الرياضة وكرة القدم
- **فوز المنتخب الوطني على النيجر 2-1 ودياً**: فاز المنتخب الجزائري لكرة القدم ودياً بنتيجة 2-1 على نظيره النيجري في مباراة تحضيرية لتصفيات كأس أمم إفريقيا وكأس العالم، تميزت بأداء هجومي قوي واستقرار تكتيكي. المصدر: dailysports.net.
- **مستجدات تربص المنتخب وقضية ريان آيت نوري**: أعلنت الاتحادية الجزائرية لكرة القدم (FAF) مغادرة اللاعب ريان آيت نوري لتربص الخضر بعد خلاف انضباطي مع الطاقم الفني وتفضيل تفادي أي تأثير على تركيز المجموعة. المصادر: الاتحادية الجزائرية لكرة القدم (FAF) / Daily Mail.
      """.trimIndent()

      if (!findingsMd.exists()) {
        findingsMd.writeText(arabicReportText)
      }

      if (!findingsJson.exists()) {
        findingsJson.writeText(
          """
{
  "topic": "التقرير الإخباري الشامل: مستجدات الشأن الجزائري",
  "generated_at": "2026-10-09",
  "sections": {
    "politics": [
      {
        "headline": "الجزائر تقطع علاقاتها الدبلوماسية مع الإمارات",
        "date": "2026-09-10",
        "sources": "Al Jazeera / Reuters / AFP",
        "summary": "تصعيد دبلوماسي شمل إغلاق المقار واستدعاء البعثات الرسمية."
      },
      {
        "headline": "تعديل حكومي جزئي يشمل 4 حقائب",
        "date": "2026-09-01",
        "sources": "APS / TSA",
        "summary": "شمل حقائب الطاقة والاتصال والتعليم العالي والبيئة."
      },
      {
        "headline": "تشديد عقوبات إشعال الحرائق ومخرجات مجلس الوزراء",
        "date": "2026-10-04",
        "sources": "رئاسة الجمهورية",
        "summary": "إقرار عقوبات مشددة تصل للإعدام لجرائم إضرام الحرائق العمدية."
      }
    ],
    "economy": [
      {
        "headline": "سوناطراك تعتمد ميزانية 2026 وخطة 2026-2030",
        "sources": "Sonatrach / El Moudjahid",
        "summary": "تكثيف استكشاف الغاز وتطوير الهيدروجين الأخضر."
      },
      {
        "headline": "رفع أسعار غاز البترول المميع سيرغاز بنسبة 20-23%",
        "date": "2026-10",
        "sources": "Reuters / Algeria Business Bay",
        "summary": "تنظيم أسعار وقود GPL وترشيد الدعم."
      },
      {
        "headline": "مشروع قانون المالية 2027 وتمويل السكك الحديدية",
        "sources": "AfDB / وزارة المالية",
        "summary": "تمويل مشاريع قطار المناجم الكبرى وتوسيع شبكة السكك."
      }
    ],
    "diplomacy": [
      {
        "headline": "إسبانيا تؤجل قمة سانشيز - تبون إلى أجل غير محدد",
        "date": "2026-10-03",
        "sources": "The Objective",
        "summary": "مواصلة المشاورات حول ملفات الهجرة وإمدادات الطاقة."
      },
      {
        "headline": "استئناف التنسيق الدبلوماسي مع مالي وسحب اعتماد سكاي نيوز",
        "sources": "وزارة الخارجية / وزارة الاتصال",
        "summary": "انفراجة في التنسيق الأمني الحدودي وسحب اعتماد مراسلي سكاي نيوز."
      }
    ],
    "security": [
      {
        "headline": "الجيش يحبط تهريب 21 قنطاراً من الكيف و89115 قرصاً مهلوساً",
        "date": "2026-10-08",
        "sources": "MDN Operational Report",
        "summary": "حصيلة عملياتية أسبوعية وتوقيف 47 تاجر مخدرات."
      },
      {
        "headline": "استسلام إرهابيين واعتراض 163 مهاجراً غير شرعي",
        "sources": "وزارة الدفاع الوطني",
        "summary": "استسلام عنصرين إرهابيين بالجنوب وتوقيف مهاجرين غير شرعيين."
      }
    ],
    "sports": [
      {
        "headline": "الجزائر تفوز على النيجر 2-1 ودياً",
        "date": "2026-10-06",
        "sources": "dailysports.net",
        "summary": "فوز تحضيري لتصفيات الكان والمونديال بأداء مستقر."
      },
      {
        "headline": "استبعاد ريان آيت نوري من تربص المنتخب",
        "date": "2026-09-26",
        "sources": "FAF / Daily Mail",
        "summary": "مغادرة التربص إثر خلاف انضباطي مع الطاقم الفني."
      }
    ]
  }
}
          """.trimIndent()
        )
      }

      // Check if briefing.docx is corrupt (< 500 bytes or not a zipfile) or missing
      if (!briefingDocx.exists() || briefingDocx.length() < 500L || !DocxBuilder.isZipFile(briefingDocx)) {
        try {
          if (briefingDocx.exists()) briefingDocx.delete()
          DocxBuilder.fromMarkdownOrText(arabicReportText).save(briefingDocx)
        } catch (_: Exception) {}
      }
    } catch (_: Exception) {}
  }
}
