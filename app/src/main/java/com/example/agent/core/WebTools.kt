package com.example.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Hyperlink discovered on a webpage.
 */
data class DiscoveredLink(
  val text: String,
  val url: String,
  val isInternal: Boolean
)

/**
 * Structured document extracted from a web source.
 */
data class WebDocument(
  val url: String,
  val title: String,
  val sourceIdentity: String,
  val publicationDate: String?,
  val retrievedContent: String,
  val relevantExcerpt: String,
  val links: List<DiscoveredLink> = emptyList(),
  val error: String? = null
) {
  fun toJsonObject(): JSONObject {
    val obj = JSONObject()
    obj.put("url", url)
    obj.put("title", title)
    obj.put("source", sourceIdentity)
    if (publicationDate != null) {
      obj.put("publicationDate", publicationDate)
    }
    obj.put("relevantExcerpt", relevantExcerpt)
    obj.put("retrievedContent", retrievedContent)
    if (error != null) {
      obj.put("error", error)
    }
    return obj
  }
}

/**
 * Pure HTML parsing and structured conversion utility.
 */
object WebHtmlParser {

  fun cleanHtmlToMarkdown(rawHtml: String, baseUrl: String): Pair<String, List<DiscoveredLink>> {
    val doc = parse(rawHtml, baseUrl)
    return Pair(doc.retrievedContent, doc.links)
  }

  fun parse(rawHtml: String, baseUrl: String, maxChars: Int = 8000): WebDocument {
    var html = rawHtml

    // Extract title
    val titlePattern = Pattern.compile("(?i)<title[^>]*>(.*?)</title>")
    val titleMatcher = titlePattern.matcher(html)
    val title = if (titleMatcher.find()) {
      decodeEntities(titleMatcher.group(1)?.trim() ?: "")
    } else {
      try { URI(baseUrl).host ?: baseUrl } catch (_: Exception) { baseUrl }
    }

    // Extract publication date from metadata
    val pubDate = extractPubDate(html)

    // Discover links before stripping tags
    val links = extractLinks(html, baseUrl)

    // Strip boilerplate elements
    html = html.replace(Regex("(?is)<script.*?</script>"), " ")
    html = html.replace(Regex("(?is)<style.*?</style>"), " ")
    html = html.replace(Regex("(?is)<noscript.*?</noscript>"), " ")
    html = html.replace(Regex("(?is)<svg.*?</svg>"), " ")
    html = html.replace(Regex("(?is)<iframe.*?</iframe>"), " ")
    html = html.replace(Regex("(?is)<nav.*?</nav>"), " ")
    html = html.replace(Regex("(?is)<header.*?</header>"), " ")
    html = html.replace(Regex("(?is)<footer.*?</footer>"), " ")

    // Convert headings
    html = html.replace(Regex("(?i)<h1[^>]*>(.*?)</h1>")) { "\n\n# ${it.groupValues[1].trim()}\n\n" }
    html = html.replace(Regex("(?i)<h2[^>]*>(.*?)</h2>")) { "\n\n## ${it.groupValues[1].trim()}\n\n" }
    html = html.replace(Regex("(?i)<h3[^>]*>(.*?)</h3>")) { "\n\n### ${it.groupValues[1].trim()}\n\n" }
    html = html.replace(Regex("(?i)<h4[^>]*>(.*?)</h4>")) { "\n\n#### ${it.groupValues[1].trim()}\n\n" }

    // Convert lists
    html = html.replace(Regex("(?i)<li[^>]*>(.*?)</li>")) { "\n- ${it.groupValues[1].trim()}" }
    html = html.replace(Regex("(?i)</?[uo]l[^>]*>"), "\n")

    // Convert breaks and paragraphs
    html = html.replace(Regex("(?i)<br\\s*/?>"), "\n")
    html = html.replace(Regex("(?i)<p[^>]*>(.*?)</p>"), "\n\n$1\n\n")

    // Format text
    html = html.replace(Regex("(?i)<(strong|b)[^>]*>(.*?)</\\1>"), "**$2**")
    html = html.replace(Regex("(?i)<(em|i)[^>]*>(.*?)</\\1>"), "*$2*")
    html = html.replace(Regex("(?i)<code[^>]*>(.*?)</code>"), "`$1`")
    html = html.replace(Regex("(?is)<pre[^>]*>(.*?)</pre>"), "\n```\n$1\n```\n")

    // Strip all remaining HTML tags
    html = html.replace(Regex("<[^>]+>"), " ")
    html = decodeEntities(html)

    val cleanedLines = html.lines()
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .joinToString("\n\n")

    val truncatedContent = if (cleanedLines.length > maxChars) {
      cleanedLines.take(maxChars) + "\n\n...[content truncated at $maxChars chars]..."
    } else {
      cleanedLines
    }

    val excerpt = cleanedLines.take(400).replace("\n", " ")
    val domain = try { URI(baseUrl).host ?: baseUrl } catch (_: Exception) { baseUrl }

    return WebDocument(
      url = baseUrl,
      title = title,
      sourceIdentity = domain,
      publicationDate = pubDate,
      retrievedContent = truncatedContent,
      relevantExcerpt = excerpt,
      links = links
    )
  }

  fun extractLinks(html: String, baseUrl: String): List<DiscoveredLink> {
    val results = mutableListOf<DiscoveredLink>()
    val pattern = Pattern.compile("(?i)<a\\s+[^>]*href=[\"']([^\"'#]+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL)
    val matcher = pattern.matcher(html)
    val baseUri = try { URI(baseUrl) } catch (_: Exception) { null }
    val seen = HashSet<String>()

    while (matcher.find()) {
      val rawHref = matcher.group(1)?.trim() ?: continue
      val textRaw = matcher.group(2)?.replace(Regex("<[^>]+>"), " ")?.trim() ?: ""

      if (rawHref.startsWith("javascript:") || rawHref.startsWith("mailto:") || rawHref.startsWith("tel:")) {
        continue
      }

      val absoluteUrl = try {
        if (baseUri != null) baseUri.resolve(rawHref).toString() else rawHref
      } catch (_: Exception) {
        rawHref
      }

      if (absoluteUrl.startsWith("http") && seen.add(absoluteUrl)) {
        val isInternal = baseUri?.host?.let { host -> absoluteUrl.contains(host) } ?: false
        val displayTitle = if (textRaw.isNotBlank()) textRaw.take(60) else absoluteUrl
        results.add(DiscoveredLink(text = decodeEntities(displayTitle), url = absoluteUrl, isInternal = isInternal))
      }
    }
    return results
  }

  fun extractTables(html: String): List<String> {
    val tables = mutableListOf<String>()
    val tablePattern = Pattern.compile("(?is)<table[^>]*>(.*?)</table>")
    val rowPattern = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>")
    val cellPattern = Pattern.compile("(?is)<(?:td|th)[^>]*>(.*?)</(?:td|th)>")

    val matcher = tablePattern.matcher(html)
    while (matcher.find()) {
      val tableContent = matcher.group(1) ?: continue
      val rowMatcher = rowPattern.matcher(tableContent)
      val rows = mutableListOf<List<String>>()

      while (rowMatcher.find()) {
        val rowHtml = rowMatcher.group(1) ?: continue
        val cellMatcher = cellPattern.matcher(rowHtml)
        val cells = mutableListOf<String>()
        while (cellMatcher.find()) {
          val cellText = cellMatcher.group(1)?.replace(Regex("<[^>]+>"), " ")?.trim() ?: ""
          cells.add(decodeEntities(cellText))
        }
        if (cells.isNotEmpty()) rows.add(cells)
      }

      if (rows.isNotEmpty()) {
        val sb = StringBuilder()
        rows.forEachIndexed { idx, row ->
          sb.append("| ").append(row.joinToString(" | ")).append(" |\n")
          if (idx == 0) {
            sb.append("| ").append(row.joinToString(" | ") { "---" }).append(" |\n")
          }
        }
        tables.add(sb.toString().trim())
      }
    }
    return tables
  }

  private fun extractPubDate(html: String): String? {
    val patterns = listOf(
      Pattern.compile("(?i)<meta\\s+[^>]*property=[\"']article:published_time[\"'][^>]*content=[\"']([^\"']*)[\"']"),
      Pattern.compile("(?i)<meta\\s+[^>]*name=[\"']date[\"'][^>]*content=[\"']([^\"']*)[\"']"),
      Pattern.compile("(?i)<meta\\s+[^>]*name=[\"']pubdate[\"'][^>]*content=[\"']([^\"']*)[\"']")
    )
    for (p in patterns) {
      val m = p.matcher(html)
      if (m.find()) {
        val d = m.group(1)?.trim()
        if (!d.isNullOrBlank()) return d
      }
    }
    return null
  }

  fun decodeEntities(input: String): String {
    return input.replace("&nbsp;", " ")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&mdash;", "—")
      .replace("&ndash;", "–")
  }
}

/**
 * Shared live web search engine.
 */
object WebSearchEngine {
  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

  fun search(query: String, maxCount: Int = 5): List<Map<String, String>> {
    val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())

    // Tier 1: Wikipedia Knowledge API (instant, high accuracy, zero rate limits, full metadata)
    try {
      val wikiUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedQuery&utf8=&format=json"
      val wikiReq = Request.Builder()
        .url(wikiUrl)
        .header("User-Agent", "Aragon-Agent/2.0 (Android; Open Knowledge)")
        .get()
        .build()
      val wikiJsonStr = httpClient.newCall(wikiReq).execute().use { resp ->
        if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
      }
      if (wikiJsonStr.isNotBlank()) {
        val wikiObj = JSONObject(wikiJsonStr)
        val searchArr = wikiObj.optJSONObject("query")?.optJSONArray("search")
        if (searchArr != null && searchArr.length() > 0) {
          val wikiResults = mutableListOf<Map<String, String>>()
          for (i in 0 until searchArr.length()) {
            if (wikiResults.size >= maxCount) break
            val item = searchArr.getJSONObject(i)
            val title = item.optString("title", "")
            val snippet = item.optString("snippet", "").replace(Regex("<[^>]+>"), "")
            val articleUrl = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
            wikiResults.add(
              mapOf(
                "title" to title,
                "url" to articleUrl,
                "source" to "wikipedia.org",
                "snippet" to WebHtmlParser.decodeEntities(snippet)
              )
            )
          }
          if (wikiResults.isNotEmpty()) return wikiResults
        }
      }
    } catch (_: Exception) {}

    // Tier 2: ArXiv Preprints & Technical Research Search
    try {
      val arxivUrl = "https://export.arxiv.org/api/query?search_query=all:$encodedQuery&max_results=$maxCount"
      val arxivReq = Request.Builder()
        .url(arxivUrl)
        .header("User-Agent", "Aragon-Agent/2.0")
        .get()
        .build()
      val arxivXml = httpClient.newCall(arxivReq).execute().use { resp ->
        if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
      }
      if (arxivXml.isNotBlank() && arxivXml.contains("<entry>")) {
        val results = mutableListOf<Map<String, String>>()
        val entryPattern = Pattern.compile("(?is)<entry>(.*?)</entry>")
        val titlePattern = Pattern.compile("(?is)<title>(.*?)</title>")
        val summaryPattern = Pattern.compile("(?is)<summary>(.*?)</summary>")
        val idPattern = Pattern.compile("(?is)<id>(.*?)</id>")
        val matcher = entryPattern.matcher(arxivXml)
        while (matcher.find() && results.size < maxCount) {
          val entryText = matcher.group(1) ?: continue
          val tMatcher = titlePattern.matcher(entryText)
          val sMatcher = summaryPattern.matcher(entryText)
          val idMatcher = idPattern.matcher(entryText)
          val title = if (tMatcher.find()) tMatcher.group(1)?.trim()?.replace("\n", " ") ?: "" else ""
          val summary = if (sMatcher.find()) sMatcher.group(1)?.trim()?.replace("\n", " ") ?: "" else ""
          val link = if (idMatcher.find()) idMatcher.group(1)?.trim() ?: "" else ""
          if (title.isNotBlank() && link.isNotBlank()) {
            results.add(
              mapOf(
                "title" to title,
                "url" to link,
                "source" to "arxiv.org",
                "snippet" to summary.take(280)
              )
            )
          }
        }
        if (results.isNotEmpty()) return results
      }
    } catch (_: Exception) {}

    return emptyList()
  }
}

/**
 * Searches the live web for authoritative sources and articles.
 */
class WebSearchTool(private val isConfigured: Boolean = true) : Tool {
  override val name: String = "web_search"
  override val description: String =
    "Search the live web for relevant documentation, sources, or articles. Returns structured results with URL, title, source, snippet, and rank."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "query",
        type = "string",
        description = "Keywords to search on the live web.",
        required = true,
        aliases = listOf("q", "search", "term", "prompt")
      ),
      ToolParameter(
        name = "maxResults",
        type = "number",
        description = "Maximum number of search results to return (default: 5, max: 10).",
        required = false,
        defaultValue = 5,
        aliases = listOf("max_results", "limit")
      ),
      ToolParameter(
        name = "domain",
        type = "string",
        description = "Optional domain filter (e.g. 'github.com', 'developer.android.com').",
        required = false
      )
    )
  )

  override val category: ToolCategory = ToolCategory.WEB
  override val target: ToolTarget = ToolTarget.HOST_NETWORK
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.ANDROID_NATIVE
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)
  override val defaultTimeoutMs: Long = 25000L

  override fun isAvailable(): Boolean = isConfigured

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val rawQuery = arguments["query"]?.toString()?.trim() ?: ""
      val maxResults = ((arguments["maxResults"] as? Number)?.toInt() ?: 5).coerceIn(1, 10)
      val domain = arguments["domain"]?.toString()?.trim()

      if (rawQuery.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'query' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      val effectiveQuery = if (!domain.isNullOrBlank() && !rawQuery.contains("site:")) {
        "$rawQuery site:$domain"
      } else rawQuery

      try {
        val results = WebSearchEngine.search(effectiveQuery, maxResults)

        if (results.isEmpty()) {
          val emptyMsg = "Web search for '$effectiveQuery' completed: 0 results found."
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = emptyMsg,
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = emptyMsg,
            exitCode = 0,
            outcome = ToolExecutionOutcome.CONDITION_NOT_FOUND,
            responseMetadata = mapOf("resultsCount" to 0, "query" to effectiveQuery)
          )
        }

        val jsonArray = JSONArray()
        val sb = StringBuilder()
        sb.appendLine("=== WEB SEARCH RESULTS for '$effectiveQuery' (${results.size} sources) ===")

        results.forEachIndexed { i, item ->
          val rank = i + 1
          val itemObj = JSONObject().apply {
            put("rank", rank)
            put("title", item["title"])
            put("url", item["url"])
            put("source", item["source"])
            put("snippet", item["snippet"])
          }
          jsonArray.put(itemObj)

          sb.appendLine("[$rank] ${item["title"]}")
          sb.appendLine("    URL: ${item["url"]}")
          sb.appendLine("    Source: ${item["source"]}")
          sb.appendLine("    Snippet: ${item["snippet"]}")
          sb.appendLine()
        }

        val out = sb.toString().trim()
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = out,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = out,
          exitCode = 0,
          outcome = ToolExecutionOutcome.SUCCESS_WITH_RESULTS,
          metadata = mapOf("resultsCount" to results.size),
          responseMetadata = mapOf("resultsCount" to results.size, "query" to effectiveQuery)
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Web search request failed: ${e.message}",
          errorType = ToolErrorCode.NETWORK_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }

}

/**
 * Opens and inspects a webpage, returning clean Markdown content and discovered links.
 */
class WebOpenTool(private val isConfigured: Boolean = true, var workspaceDir: File? = null) : Tool {
  override val name: String = "web_open"
  override val description: String =
    "Open and fetch a webpage URL, extracting clean Markdown text, publication date, metadata, and outgoing links for research."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "url",
        type = "string",
        description = "Full HTTP or HTTPS URL to open and read.",
        required = true,
        aliases = listOf("uri", "link", "target_url", "address")
      ),
      ToolParameter(
        name = "maxChars",
        type = "number",
        description = "Maximum length of Markdown content to return (default: 8000).",
        required = false,
        defaultValue = 8000,
        aliases = listOf("max_chars", "limit")
      ),
      ToolParameter(
        name = "extractLinks",
        type = "boolean",
        description = "Whether to extract discovered hyperlinks on the page (default: true).",
        required = false,
        defaultValue = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.WEB
  override val target: ToolTarget = ToolTarget.HOST_NETWORK
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.ANDROID_NATIVE
  override val defaultTimeoutMs: Long = 30000L

  override fun isAvailable(): Boolean = isConfigured

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val url = arguments["url"]?.toString()?.trim() ?: ""
      val maxChars = ((arguments["maxChars"] as? Number)?.toInt() ?: 8000).coerceIn(500, 32000)
      val extractLinks = arguments["extractLinks"] as? Boolean ?: true

      if (url.isBlank() || !url.startsWith("http")) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Invalid URL '$url'. Must be an absolute http:// or https:// URL.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      try {
        val request = Request.Builder()
          .url(url)
          .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
          .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
          .get()
          .build()

        val html = httpClient.newCall(request).execute().use { resp ->
          if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
          }
          resp.body?.string().orEmpty()
        }

        val doc = WebHtmlParser.parse(html, url, maxChars)

        val sb = StringBuilder()
        sb.appendLine("=== WEB DOCUMENT: ${doc.title} ===")
        sb.appendLine("URL: ${doc.url}")
        sb.appendLine("Source: ${doc.sourceIdentity}")
        if (doc.publicationDate != null) {
          sb.appendLine("Publication Date: ${doc.publicationDate}")
        }
        sb.appendLine("Relevant Excerpt: ${doc.relevantExcerpt}")
        sb.appendLine()
        sb.appendLine("--- RETRIEVED CONTENT ---")
        sb.appendLine(doc.retrievedContent)

        if (extractLinks && doc.links.isNotEmpty()) {
          sb.appendLine()
          sb.appendLine("--- DISCOVERED LINKS (${doc.links.size}) ---")
          doc.links.take(15).forEach { link ->
            sb.appendLine("- [${link.text}](${link.url}) ${if (link.isInternal) "(internal)" else "(external)"}")
          }
        }

        val out = sb.toString().trim()
        val isTruncated = doc.retrievedContent.contains("...[content truncated at")
        val isContentBlank = doc.retrievedContent.isBlank()

        var spilloverPath: String? = null
        if (isTruncated && workspaceDir != null) {
          try {
            val tmpDir = File(workspaceDir, "tmp").apply { mkdirs() }
            val spillFile = File(tmpDir, "web_${callId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.md")
            spillFile.writeText(html, StandardCharsets.UTF_8)
            spilloverPath = spillFile.absolutePath
          } catch (_: Exception) {}
        }

        val outcome = when {
          isContentBlank -> ToolExecutionOutcome.SUCCESS_EMPTY
          isTruncated -> ToolExecutionOutcome.TRUNCATED_OR_PARTIAL
          else -> ToolExecutionOutcome.SUCCESS_WITH_RESULTS
        }

        val truncDetails = TruncationDetails(
          isTruncated = isTruncated,
          originalBytes = html.toByteArray(StandardCharsets.UTF_8).size,
          capturedBytes = out.toByteArray(StandardCharsets.UTF_8).size,
          spilloverPath = spilloverPath,
          inspectionHint = if (spilloverPath != null) "Full raw content saved at: $spilloverPath. Use read_file to inspect." else null
        )

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = out,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = out,
          exitCode = 0,
          outcome = outcome,
          truncation = truncDetails,
          metadata = mapOf("url" to url, "title" to doc.title, "linksCount" to doc.links.size),
          responseMetadata = mapOf("url" to url, "title" to doc.title, "linksCount" to doc.links.size)
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to open '$url': ${e.message}",
          errorType = ToolErrorCode.NETWORK_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}

/**
 * Extracts specific structured elements (tables, metadata, links) from a webpage.
 */
class WebExtractTool(private val isConfigured: Boolean = true) : Tool {
  override val name: String = "web_extract"
  override val description: String =
    "Extract specific structured data from a webpage: HTML tables (converted to Markdown), metadata tags, or link structures."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "url",
        type = "string",
        description = "URL to extract structured data from.",
        required = true,
        aliases = listOf("uri", "link", "target_url", "address")
      ),
      ToolParameter(
        name = "extractionType",
        type = "string",
        description = "Type of data: 'tables', 'metadata', 'links'. Default: 'tables'.",
        required = false,
        enumValues = listOf("tables", "metadata", "links"),
        defaultValue = "tables",
        aliases = listOf("target", "extract", "mode")
      )
    )
  )

  override val category: ToolCategory = ToolCategory.WEB
  override val target: ToolTarget = ToolTarget.HOST_NETWORK
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.ANDROID_NATIVE
  override val defaultTimeoutMs: Long = 25000L

  override fun isAvailable(): Boolean = isConfigured

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val url = arguments["url"]?.toString()?.trim() ?: ""
      val extractionType = arguments["extractionType"]?.toString()?.lowercase() ?: "tables"

      if (url.isBlank() || !url.startsWith("http")) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Invalid URL '$url'.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      try {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()
        val html = httpClient.newCall(request).execute().use { resp ->
          if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
          resp.body?.string().orEmpty()
        }

        val out = when (extractionType) {
          "tables" -> {
            val tables = WebHtmlParser.extractTables(html)
            if (tables.isEmpty()) {
              "No HTML tables found on '$url'."
            } else {
              "Extracted ${tables.size} table(s) from '$url':\n\n" + tables.joinToString("\n\n")
            }
          }
          "metadata" -> {
            val doc = WebHtmlParser.parse(html, url)
            """
            Page Metadata for '$url':
            - Title: ${doc.title}
            - Source: ${doc.sourceIdentity}
            - Publication Date: ${doc.publicationDate ?: "None detected"}
            - Relevant Excerpt: ${doc.relevantExcerpt}
            """.trimIndent()
          }
          "links" -> {
            val links = WebHtmlParser.extractLinks(html, url)
            "Extracted ${links.size} link(s) from '$url':\n" + links.take(30).joinToString("\n") { "- [${it.text}](${it.url})" }
          }
          else -> "Unsupported extractionType '$extractionType'."
        }

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = out,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = out,
          exitCode = 0
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Extraction failed: ${e.message}",
          errorType = ToolErrorCode.NETWORK_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}

/**
 * Multi-page web crawler following internal links and compiling research data.
 */
class WebCrawlTool(private val isConfigured: Boolean = true) : Tool {
  override val name: String = "web_crawl"
  override val description: String =
    "Crawl websites across multiple linked pages. Follows internal links up to a depth limit and aggregates research findings."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "startUrl",
        type = "string",
        description = "Starting URL to begin crawling from.",
        required = true,
        aliases = listOf("start_url", "url")
      ),
      ToolParameter(
        name = "maxPages",
        type = "number",
        description = "Maximum number of pages to crawl (default: 3, max: 6).",
        required = false,
        defaultValue = 3,
        aliases = listOf("max_pages")
      ),
      ToolParameter(
        name = "sameDomainOnly",
        type = "boolean",
        description = "Restrict crawl to the same domain (default: true).",
        required = false,
        defaultValue = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.WEB
  override val target: ToolTarget = ToolTarget.HOST_NETWORK
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.ANDROID_NATIVE
  override val defaultTimeoutMs: Long = 45000L

  override fun isAvailable(): Boolean = isConfigured

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val startUrl = arguments["startUrl"]?.toString()?.trim() ?: ""
      val maxPages = ((arguments["maxPages"] as? Number)?.toInt() ?: 3).coerceIn(1, 6)
      val sameDomainOnly = arguments["sameDomainOnly"] as? Boolean ?: true

      if (startUrl.isBlank() || !startUrl.startsWith("http")) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Invalid startUrl '$startUrl'.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      val queue = ArrayDeque<String>()
      val visited = LinkedHashSet<String>()
      val crawledDocs = mutableListOf<WebDocument>()
      val errors = mutableListOf<String>()

      queue.add(startUrl)

      while (queue.isNotEmpty() && visited.size < maxPages) {
        val currentUrl = queue.removeFirst()
        if (!visited.add(currentUrl)) continue

        try {
          val req = Request.Builder().url(currentUrl).header("User-Agent", "Aragon-Crawler/2.0").get().build()
          val html = httpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string().orEmpty() else null
          } ?: continue

          val doc = WebHtmlParser.parse(html, currentUrl, maxChars = 3000)
          crawledDocs.add(doc)

          for (link in doc.links) {
            if (sameDomainOnly && !link.isInternal) continue
            if (!visited.contains(link.url) && !queue.contains(link.url) && queue.size < maxPages * 2) {
              queue.add(link.url)
            }
          }
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          errors.add("Error crawling '$currentUrl': ${e.message}")
        }
      }

      val sb = StringBuilder()
      sb.appendLine("=== WEB CRAWL DOSSIER (${crawledDocs.size} pages crawled) ===")
      sb.appendLine("Root URL: $startUrl")
      sb.appendLine()

      crawledDocs.forEachIndexed { i, doc ->
        sb.appendLine("## [Page ${i + 1}] ${doc.title}")
        sb.appendLine("URL: ${doc.url}")
        sb.appendLine("Source: ${doc.sourceIdentity}")
        sb.appendLine()
        sb.appendLine(doc.retrievedContent)
        sb.appendLine()
        sb.appendLine("---")
      }

      if (errors.isNotEmpty()) {
        sb.appendLine("\nCrawl warnings/errors:")
        errors.forEach { sb.appendLine("- $it") }
      }

      val out = sb.toString().trim()
      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = out,
        error = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = out,
        exitCode = 0,
        metadata = mapOf("crawledCount" to crawledDocs.size)
      )
    }
}

/**
 * Raw HTTP client tool for REST APIs and web services.
 */
class WebFetchTool(private val isConfigured: Boolean = true, var workspaceDir: File? = null) : Tool {
  override val name: String = "web_fetch"
  override val description: String =
    "Execute an HTTP request (GET, POST, PUT, DELETE, HEAD) to an external URL or REST API. Returns status code, headers, and body with configurable limits and JSON path extraction."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "url",
        type = "string",
        description = "Target HTTP or HTTPS URL.",
        required = true,
        aliases = listOf("uri", "link", "target_url", "address")
      ),
      ToolParameter(
        name = "method",
        type = "string",
        description = "HTTP method (GET, POST, PUT, DELETE, HEAD). Default: GET.",
        required = false,
        enumValues = listOf("GET", "POST", "PUT", "DELETE", "HEAD"),
        defaultValue = "GET"
      ),
      ToolParameter(
        name = "body",
        type = "string",
        description = "Request body payload for POST/PUT/PATCH.",
        required = false
      ),
      ToolParameter(
        name = "max_bytes",
        type = "number",
        description = "Optional byte limit for response body returned in context (default: 16384). Full response is saved to disk if larger.",
        required = false,
        defaultValue = 16384,
        aliases = listOf("maxBytes")
      ),
      ToolParameter(
        name = "json_path",
        type = "string",
        description = "Optional JSON key or path (e.g. 'data.user', 'items[0].id') to extract specifically from a JSON response.",
        required = false,
        aliases = listOf("jsonPath")
      ),
      ToolParameter(
        name = "search_query",
        type = "string",
        description = "Optional search keyword to extract only lines matching the query.",
        required = false,
        aliases = listOf("searchQuery", "query", "filter")
      )
    )
  )

  override val category: ToolCategory = ToolCategory.NETWORK
  override val target: ToolTarget = ToolTarget.HOST_NETWORK
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.ANDROID_NATIVE
  override val defaultTimeoutMs: Long = 30000L

  override fun isAvailable(): Boolean = isConfigured

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val url = arguments["url"]?.toString()?.trim() ?: ""
      val method = arguments["method"]?.toString()?.uppercase() ?: "GET"
      val body = arguments["body"]?.toString()
      val maxBytes = (arguments["max_bytes"] as? Number)?.toInt() ?: 16384
      val jsonPath = arguments["json_path"]?.toString()?.trim()
      val searchQuery = arguments["search_query"]?.toString()?.trim()

      if (url.isBlank() || !url.startsWith("http")) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Invalid URL '$url'.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1,
          outcome = ToolExecutionOutcome.EXECUTION_FAILED
        )
      }

      try {
        val reqBuilder = Request.Builder().url(url)
        val reqBody = if (body != null) body.toRequestBody("application/json".toMediaType()) else null

        when (method) {
          "POST" -> reqBuilder.post(reqBody ?: "".toRequestBody(null))
          "PUT" -> reqBuilder.put(reqBody ?: "".toRequestBody(null))
          "DELETE" -> reqBuilder.delete(reqBody)
          "HEAD" -> reqBuilder.head()
          else -> reqBuilder.get()
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
          val code = resp.code
          val message = resp.message
          val contentType = resp.header("Content-Type").orEmpty()
          val contentLength = resp.header("Content-Length")
          val respBody = resp.body?.string().orEmpty()

          val headersMap = mutableMapOf<String, Any?>()
          headersMap["statusCode"] = code
          headersMap["contentType"] = contentType
          if (contentLength != null) headersMap["contentLength"] = contentLength

          if (code == 404) {
            val notFoundMsg = "HTTP 404 Not Found: Target resource '$url' was not found on server."
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = notFoundMsg,
              error = notFoundMsg,
              errorType = ToolErrorCode.HTTP_ERROR,
              duration = System.currentTimeMillis() - startTime,
              stdout = "",
              stderr = notFoundMsg,
              exitCode = 1,
              outcome = ToolExecutionOutcome.CONDITION_NOT_FOUND,
              responseMetadata = headersMap
            )
          }

          if (code == 204 || (resp.isSuccessful && respBody.isBlank())) {
            val emptyMsg = "HTTP $code $message: Success with no response body (0 bytes)."
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.SUCCEEDED,
              arguments = arguments,
              output = emptyMsg,
              error = null,
              duration = System.currentTimeMillis() - startTime,
              stdout = emptyMsg,
              exitCode = 0,
              outcome = ToolExecutionOutcome.SUCCESS_EMPTY,
              responseMetadata = headersMap
            )
          }

          if (!resp.isSuccessful) {
            val errMsg = "HTTP $code $message: ${respBody.take(300)}"
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = errMsg,
              error = errMsg,
              errorType = ToolErrorCode.HTTP_ERROR,
              duration = System.currentTimeMillis() - startTime,
              stdout = "",
              stderr = errMsg,
              exitCode = 1,
              outcome = ToolExecutionOutcome.EXECUTION_FAILED,
              responseMetadata = headersMap
            )
          }

          // Successful response with body
          // 1. Check targeted JSON extraction if requested
          if (!jsonPath.isNullOrBlank()) {
            val extractedJson = ObservationPayloadPolicy.extractJsonPath(respBody, jsonPath)
            if (extractedJson != null) {
              val out = "HTTP $code $message\n--- EXTRACTED JSON PATH '$jsonPath' ---\n$extractedJson"
              return@withContext ToolResult(
                callId = callId,
                toolName = name,
                status = ToolStatus.SUCCEEDED,
                arguments = arguments,
                output = out,
                error = null,
                duration = System.currentTimeMillis() - startTime,
                stdout = out,
                exitCode = 0,
                outcome = ToolExecutionOutcome.SUCCESS_WITH_RESULTS,
                responseMetadata = headersMap + mapOf("extractedJsonPath" to jsonPath)
              )
            } else {
              val out = "HTTP $code $message\nCondition not found: JSON path '$jsonPath' did not match any elements in response body."
              return@withContext ToolResult(
                callId = callId,
                toolName = name,
                status = ToolStatus.SUCCEEDED,
                arguments = arguments,
                output = out,
                error = null,
                duration = System.currentTimeMillis() - startTime,
                stdout = out,
                exitCode = 0,
                outcome = ToolExecutionOutcome.CONDITION_NOT_FOUND,
                responseMetadata = headersMap + mapOf("failedJsonPath" to jsonPath)
              )
            }
          }

          // 2. Check targeted line search if requested
          if (!searchQuery.isNullOrBlank()) {
            val matched = ObservationPayloadPolicy.searchLines(respBody, searchQuery)
            val hasMatches = !matched.startsWith("No lines matching")
            val out = "HTTP $code $message\n$matched"
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.SUCCEEDED,
              arguments = arguments,
              output = out,
              error = null,
              duration = System.currentTimeMillis() - startTime,
              stdout = out,
              exitCode = 0,
              outcome = if (hasMatches) ToolExecutionOutcome.SUCCESS_WITH_RESULTS else ToolExecutionOutcome.CONDITION_NOT_FOUND,
              responseMetadata = headersMap + mapOf("searchQuery" to searchQuery)
            )
          }

          // 3. Normal response formatting with configurable output limits and spillover
          val targetDir = workspaceDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
          val isJson = contentType.contains("json", ignoreCase = true) || respBody.trim().startsWith("{") || respBody.trim().startsWith("[")
          val ext = if (isJson) "json" else "txt"

          val formatted = ObservationPayloadPolicy.processOutput(
            workspaceDir = targetDir,
            stepId = "http_$callId",
            rawStdout = respBody,
            rawStderr = "",
            customMaxBytes = maxBytes,
            fileExtension = ext
          )

          val headerText = buildString {
            appendLine("HTTP $code $message")
            resp.headers.take(8).forEach { (k, v) -> appendLine("$k: $v") }
            if (resp.headers.size > 8) appendLine("... (${resp.headers.size - 8} additional headers omitted)")
            appendLine()
          }

          val finalPayload = headerText + formatted.contextPayload
          val outcome = if (formatted.isTruncated) ToolExecutionOutcome.TRUNCATED_OR_PARTIAL else ToolExecutionOutcome.SUCCESS_WITH_RESULTS

          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = finalPayload,
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = finalPayload,
            exitCode = 0,
            outcome = outcome,
            truncation = formatted.truncationDetails,
            responseMetadata = headersMap + mapOf(
              "isTruncated" to formatted.isTruncated,
              "totalBytes" to formatted.totalBytes
            )
          )
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "HTTP request failed: ${e.message}",
          errorType = ToolErrorCode.NETWORK_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1,
          outcome = ToolExecutionOutcome.EXECUTION_FAILED
        )
      }
    }
}
