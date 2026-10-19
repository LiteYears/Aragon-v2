package com.example.agent.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.regex.Pattern

/**
 * Lightweight, robust in-process JavaScript and DOM execution engine for Playwright MCP.
 *
 * Provides browser-grade DOM inspection, query selectors, JavaScript expressions,
 * and structured data transformations without external node/browser processes.
 */
class PlaywrightJsEngine(
  val url: String = "",
  val html: String = ""
) {

  private val consoleLogs = mutableListOf<String>()

  /**
   * DOM Element representation accessible in JavaScript evaluations.
   */
  data class DomElement(
    val tagName: String,
    val id: String = "",
    val classNames: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val textContent: String = "",
    val innerHTML: String = "",
    val outerHTML: String = ""
  ) {
    fun toMap(): Map<String, Any?> = mapOf(
      "tagName" to tagName,
      "id" to id,
      "class" to classNames.joinToString(" "),
      "textContent" to textContent.trim(),
      "attributes" to attributes
    )

    fun getAttribute(attr: String): String? = attributes[attr.lowercase()] ?: attributes[attr]
  }

  // Parsed DOM elements
  private val parsedElements: List<DomElement> by lazy { parseHtmlToDom(html, url) }
  private val pageTitle: String by lazy { extractTitle(html, url) }

  /**
   * Evaluates a JavaScript script or expression in the context of the page DOM.
   */
  fun evaluate(scriptOrExpression: String): JsEvaluationResult {
    consoleLogs.clear()
    val trimmed = scriptOrExpression.trim()

    if (trimmed.isBlank()) {
      return JsEvaluationResult(
        result = null,
        type = "undefined",
        url = url,
        pageTitle = pageTitle,
        consoleLogs = consoleLogs
      )
    }

    try {
      val evaluated = executeJs(trimmed)
      val type = when (evaluated) {
        null -> "null"
        is String -> "string"
        is Number -> "number"
        is Boolean -> "boolean"
        is List<*> -> "array"
        is JSONArray -> "array"
        is Map<*, *> -> "object"
        is JSONObject -> "object"
        is DomElement -> "element"
        else -> "object"
      }

      return JsEvaluationResult(
        result = evaluated,
        type = type,
        url = url,
        pageTitle = pageTitle,
        consoleLogs = consoleLogs
      )
    } catch (e: Exception) {
      return JsEvaluationResult(
        result = null,
        type = "error",
        url = url,
        pageTitle = pageTitle,
        error = e.message ?: "JavaScript evaluation error",
        consoleLogs = consoleLogs
      )
    }
  }

  /**
   * Internal JavaScript parser and evaluator.
   */
  private fun executeJs(script: String): Any? {
    // Check for console.log statements
    val logRegex = Regex("""console\.log\((.*?)\)""")
    logRegex.findAll(script).forEach { match ->
      val arg = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
      consoleLogs.add(arg)
    }

    val cleanScript = script
      .replace(Regex("""console\.log\(.*?\);?"""), "")
      .trim()

    // Multi-statement handling: extract return statement if present
    val lines = cleanScript.split(Regex(""";(?=(?:[^"']*["'][^"']*["'])*[^"']*$)|\n"""))
      .map { it.trim() }
      .filter { it.isNotBlank() }

    var finalExpr = if (lines.isNotEmpty()) lines.last() else cleanScript
    val env = mutableMapOf<String, Any?>()

    for (line in lines) {
      if (line.startsWith("var ") || line.startsWith("let ") || line.startsWith("const ")) {
        val decl = line.substringAfter(" ").trim()
        val varName = decl.substringBefore("=").trim()
        val varExpr = decl.substringAfter("=", "").trim()
        if (varName.isNotBlank() && varExpr.isNotBlank()) {
          env[varName] = evalExpr(varExpr, env)
        }
      } else if (line.startsWith("return ")) {
        finalExpr = line.removePrefix("return ").trim()
        break
      } else if (line.contains("=") && !line.contains("==") && !line.contains("===") && !line.contains("<=") && !line.contains(">=")) {
        val varName = line.substringBefore("=").trim()
        val varExpr = line.substringAfter("=").trim()
        if (env.containsKey(varName)) {
          env[varName] = evalExpr(varExpr, env)
        }
      }
    }

    return evalExpr(finalExpr, env)
  }

  private fun evalExpr(expr: String, env: Map<String, Any?>): Any? {
    val trimmed = expr.trim()
    if (trimmed.isEmpty()) return null

    // Check variable environment first
    if (env.containsKey(trimmed)) return env[trimmed]

    // Primitive literals
    if (trimmed == "true") return true
    if (trimmed == "false") return false
    if (trimmed == "null") return null
    if (trimmed == "undefined") return null
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) return trimmed.substring(1, trimmed.length - 1)
    if (trimmed.startsWith("'") && trimmed.endsWith("'")) return trimmed.substring(1, trimmed.length - 1)
    if (trimmed.toIntOrNull() != null) return trimmed.toInt()
    if (trimmed.toDoubleOrNull() != null) return trimmed.toDouble()

    // JSON.stringify(...)
    if (trimmed.startsWith("JSON.stringify(") && trimmed.endsWith(")")) {
      val inner = trimmed.removePrefix("JSON.stringify(").removeSuffix(")").trim()
      val arg = evalExpr(inner.substringBefore(",").trim(), env)
      return when (arg) {
        is Map<*, *> -> JSONObject(arg).toString(2)
        is List<*> -> JSONArray(arg.map { if (it is DomElement) it.toMap() else it }).toString(2)
        is DomElement -> JSONObject(arg.toMap()).toString(2)
        else -> arg.toString()
      }
    }

    // JSON.parse(...)
    if (trimmed.startsWith("JSON.parse(") && trimmed.endsWith(")")) {
      val inner = trimmed.removePrefix("JSON.parse(").removeSuffix(")").trim()
      val strVal = evalExpr(inner, env)?.toString().orEmpty()
      return try {
        if (strVal.trim().startsWith("[")) JSONArray(strVal) else JSONObject(strVal)
      } catch (_: Exception) {
        strVal
      }
    }

    // Math methods
    if (trimmed.startsWith("Math.")) {
      return evalMath(trimmed)
    }

    // document.title
    if (trimmed == "document.title" || trimmed == "window.document.title") {
      return pageTitle
    }

    // document.URL, location.href
    if (trimmed == "document.URL" || trimmed == "document.location" || trimmed == "document.location.href" ||
      trimmed == "location.href" || trimmed == "window.location.href"
    ) {
      return url
    }

    // document.body.innerText, document.body.textContent
    if (trimmed == "document.body.innerText" || trimmed == "document.body.textContent") {
      return cleanHtmlToText(html)
    }

    // document.body.innerHTML
    if (trimmed == "document.body.innerHTML") {
      return extractBodyHtml(html)
    }

    // document.links
    if (trimmed == "document.links" || trimmed == "Array.from(document.links)") {
      return querySelectorAll("a").map { el ->
        mapOf(
          "text" to el.textContent.trim(),
          "href" to (el.getAttribute("href") ?: "")
        )
      }
    }

    // document.forms
    if (trimmed == "document.forms") {
      return querySelectorAll("form").map { it.toMap() }
    }

    // document.images
    if (trimmed == "document.images") {
      return querySelectorAll("img").map { el ->
        mapOf(
          "src" to (el.getAttribute("src") ?: ""),
          "alt" to (el.getAttribute("alt") ?: "")
        )
      }
    }

    // document.querySelector(...)
    if (trimmed.contains("document.querySelector(")) {
      return evalQuerySelector(trimmed, env)
    }

    // document.querySelectorAll(...) or Array.from(document.querySelectorAll(...))
    if (trimmed.contains("document.querySelectorAll(")) {
      return evalQuerySelectorAll(trimmed, env)
    }

    // Array / Object literal check
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      return evalObjectLiteral(trimmed, env)
    }
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
      return evalArrayLiteral(trimmed, env)
    }

    // Arithmetic expressions (e.g. 10 + 20, 5 * 6)
    if (trimmed.matches(Regex("""^[\d\s+\-*/%().]+$"""))) {
      return evalBasicArithmetic(trimmed)
    }

    // String manipulation methods on env variables or literals
    if (trimmed.contains(".") && !trimmed.startsWith(".")) {
      val baseObj = trimmed.substringBefore(".")
      val methodCall = trimmed.substringAfter(".")
      val resolvedBase = env[baseObj] ?: evalExpr(baseObj, env)
      if (resolvedBase != null) {
        return evalMethodOnObject(resolvedBase, methodCall, env)
      }
    }

    return trimmed
  }

  private fun evalQuerySelector(expr: String, env: Map<String, Any?>): Any? {
    val selector = extractSelectorArg(expr, "document.querySelector(")
    val el = querySelector(selector) ?: return null

    // Check for property chaining like document.querySelector('h1').textContent
    val afterCall = expr.substringAfter("document.querySelector($selector)").substringAfter("document.querySelector('$selector')").substringAfter("document.querySelector(\"$selector\")").trim()
    val property = if (afterCall.startsWith(".")) afterCall.removePrefix(".").trim() else ""

    return when {
      property.isEmpty() -> el.toMap()
      property == "textContent" || property == "innerText" -> el.textContent.trim()
      property == "innerHTML" -> el.innerHTML
      property == "outerHTML" -> el.outerHTML
      property == "id" -> el.id
      property == "className" || property == "class" -> el.classNames.joinToString(" ")
      property == "tagName" -> el.tagName
      property.startsWith("getAttribute(") -> {
        val attrName = property.removePrefix("getAttribute(").removeSuffix(")").trim().removeSurrounding("\"").removeSurrounding("'")
        el.getAttribute(attrName)
      }
      property == "href" -> el.getAttribute("href")
      property == "src" -> el.getAttribute("src")
      property == "value" -> el.getAttribute("value") ?: el.textContent.trim()
      else -> el.toMap()[property] ?: el.getAttribute(property)
    }
  }

  private fun evalQuerySelectorAll(expr: String, env: Map<String, Any?>): Any? {
    val clean = expr.removePrefix("Array.from(").removeSuffix(")")
    val selector = extractSelectorArg(clean, "document.querySelectorAll(")
    val elements = querySelectorAll(selector)

    // Check for chained operations like .map(...) or .length or .slice(...)
    val afterCall = clean.substringAfter("document.querySelectorAll($selector)")
      .substringAfter("document.querySelectorAll('$selector')")
      .substringAfter("document.querySelectorAll(\"$selector\")")
      .trim()

    if (afterCall.startsWith(".length")) return elements.size
    if (afterCall.startsWith(".map(")) {
      val mapBody = afterCall.removePrefix(".map(").substringBeforeLast(")").trim()
      return elements.map { el ->
        when {
          mapBody.contains(".textContent") || mapBody.contains(".innerText") -> el.textContent.trim()
          mapBody.contains(".href") -> el.getAttribute("href") ?: ""
          mapBody.contains(".src") -> el.getAttribute("src") ?: ""
          mapBody.contains(".innerHTML") -> el.innerHTML
          mapBody.contains(".outerHTML") -> el.outerHTML
          mapBody.contains(".id") -> el.id
          mapBody.contains(".getAttribute(") -> {
            val attr = mapBody.substringAfter(".getAttribute(").substringBefore(")").removeSurrounding("\"").removeSurrounding("'")
            el.getAttribute(attr) ?: ""
          }
          mapBody.contains("({") || mapBody.contains("return {") -> {
            mapOf(
              "text" to el.textContent.trim(),
              "href" to (el.getAttribute("href") ?: ""),
              "tag" to el.tagName
            )
          }
          else -> el.textContent.trim()
        }
      }
    }
    if (afterCall.startsWith(".filter(")) {
      val filterBody = afterCall.removePrefix(".filter(").substringBeforeLast(")").trim()
      return elements.filter { el ->
        if (filterBody.contains(".textContent") && filterBody.contains(".includes(")) {
          val term = filterBody.substringAfter(".includes(").substringBefore(")").removeSurrounding("\"").removeSurrounding("'")
          el.textContent.contains(term, ignoreCase = true)
        } else if (filterBody.contains(".href")) {
          !el.getAttribute("href").isNullOrBlank()
        } else {
          el.textContent.isNotBlank()
        }
      }.map { it.toMap() }
    }

    return elements.map { it.toMap() }
  }

  private fun extractSelectorArg(expr: String, prefix: String): String {
    val sub = expr.substringAfter(prefix)
    val quote = sub.firstOrNull { it == '\'' || it == '"' } ?: '\''
    return sub.substringAfter(quote).substringBefore(quote)
  }

  private fun evalObjectLiteral(expr: String, env: Map<String, Any?>): Map<String, Any?> {
    val inner = expr.trim().removePrefix("{").removeSuffix("}").trim()
    val map = mutableMapOf<String, Any?>()
    val pairs = inner.split(Regex(""",(?=(?:[^"']*["'][^"']*["'])*[^"']*$)"""))
    for (pair in pairs) {
      val colonIdx = pair.indexOf(':')
      if (colonIdx > 0) {
        val key = pair.substring(0, colonIdx).trim().removeSurrounding("\"").removeSurrounding("'")
        val valExpr = pair.substring(colonIdx + 1).trim()
        map[key] = evalExpr(valExpr, env)
      } else {
        val key = pair.trim()
        if (key.isNotBlank()) map[key] = env[key]
      }
    }
    return map
  }

  private fun evalArrayLiteral(expr: String, env: Map<String, Any?>): List<Any?> {
    val inner = expr.trim().removePrefix("[").removeSuffix("]").trim()
    if (inner.isBlank()) return emptyList()
    val items = inner.split(Regex(""",(?=(?:[^"']*["'][^"']*["'])*[^"']*$)"""))
    return items.map { evalExpr(it.trim(), env) }
  }

  private fun evalMath(expr: String): Any {
    val method = expr.removePrefix("Math.").substringBefore("(").trim()
    val argsStr = expr.substringAfter("Math.$method(").substringBeforeLast(")").trim()
    val args = argsStr.split(",").mapNotNull { it.trim().toDoubleOrNull() }

    return when (method) {
      "round" -> Math.round(args.firstOrNull() ?: 0.0)
      "floor" -> Math.floor(args.firstOrNull() ?: 0.0)
      "ceil" -> Math.ceil(args.firstOrNull() ?: 0.0)
      "abs" -> Math.abs(args.firstOrNull() ?: 0.0)
      "sqrt" -> Math.sqrt(args.firstOrNull() ?: 0.0)
      "max" -> args.maxOrNull() ?: 0.0
      "min" -> args.minOrNull() ?: 0.0
      "pow" -> Math.pow(args.getOrNull(0) ?: 0.0, args.getOrNull(1) ?: 1.0)
      "PI" -> Math.PI
      "E" -> Math.E
      "random" -> Math.random()
      else -> 0.0
    }
  }

  private fun evalMethodOnObject(obj: Any, methodCall: String, env: Map<String, Any?>): Any? {
    val methodName = methodCall.substringBefore("(").trim()
    val argStr = methodCall.substringAfter("(").substringBeforeLast(")").trim()

    if (obj is String) {
      when (methodName) {
        "trim" -> return obj.trim()
        "toLowerCase" -> return obj.lowercase()
        "toUpperCase" -> return obj.uppercase()
        "includes" -> return obj.contains(argStr.removeSurrounding("\"").removeSurrounding("'"))
        "startsWith" -> return obj.startsWith(argStr.removeSurrounding("\"").removeSurrounding("'"))
        "endsWith" -> return obj.endsWith(argStr.removeSurrounding("\"").removeSurrounding("'"))
        "length" -> return obj.length
        "split" -> {
          val delim = argStr.removeSurrounding("\"").removeSurrounding("'")
          return obj.split(delim)
        }
      }
    } else if (obj is List<*>) {
      when (methodName) {
        "length" -> return obj.size
        "slice" -> {
          val parts = argStr.split(",").mapNotNull { it.trim().toIntOrNull() }
          val start = parts.getOrNull(0)?.coerceAtLeast(0) ?: 0
          val end = parts.getOrNull(1)?.coerceAtMost(obj.size) ?: obj.size
          return if (start < obj.size) obj.subList(start, end.coerceAtLeast(start)) else emptyList<Any>()
        }
        "join" -> {
          val sep = argStr.removeSurrounding("\"").removeSurrounding("'")
          return obj.joinToString(sep)
        }
      }
    }
    return obj
  }

  private fun evalBasicArithmetic(expr: String): Double {
    return try {
      val clean = expr.replace(" ", "")
      when {
        clean.contains("+") -> {
          val parts = clean.split("+")
          parts.sumOf { it.toDoubleOrNull() ?: 0.0 }
        }
        clean.contains("-") -> {
          val parts = clean.split("-")
          val first = parts[0].toDoubleOrNull() ?: 0.0
          parts.drop(1).fold(first) { acc, s -> acc - (s.toDoubleOrNull() ?: 0.0) }
        }
        clean.contains("*") -> {
          val parts = clean.split("*")
          parts.fold(1.0) { acc, s -> acc * (s.toDoubleOrNull() ?: 1.0) }
        }
        clean.contains("/") -> {
          val parts = clean.split("/")
          val num = parts[0].toDoubleOrNull() ?: 0.0
          val den = parts[1].toDoubleOrNull() ?: 1.0
          if (den != 0.0) num / den else 0.0
        }
        else -> clean.toDoubleOrNull() ?: 0.0
      }
    } catch (_: Exception) {
      0.0
    }
  }

  fun querySelector(selector: String): DomElement? {
    return querySelectorAll(selector).firstOrNull()
  }

  fun querySelectorAll(selector: String): List<DomElement> {
    val cleanSel = selector.trim().lowercase()
    if (cleanSel.isBlank()) return parsedElements

    // Comma-separated selectors (e.g. "h1, h2, h3")
    if (cleanSel.contains(",")) {
      val subSelectors = cleanSel.split(",").map { it.trim() }
      val results = mutableListOf<DomElement>()
      for (sub in subSelectors) {
        results.addAll(querySelectorAll(sub))
      }
      return results.distinctBy { it.outerHTML }
    }

    // ID selector (e.g. "#header")
    if (cleanSel.startsWith("#")) {
      val targetId = cleanSel.removePrefix("#")
      return parsedElements.filter { it.id.equals(targetId, ignoreCase = true) }
    }

    // Class selector (e.g. ".main-content")
    if (cleanSel.startsWith(".")) {
      val targetClass = cleanSel.removePrefix(".")
      return parsedElements.filter { el -> el.classNames.any { it.equals(targetClass, ignoreCase = true) } }
    }

    // Attribute selector (e.g. "meta[name=description]" or "[href]")
    if (cleanSel.contains("[") && cleanSel.endsWith("]")) {
      val tagPrefix = cleanSel.substringBefore("[").trim()
      val attrClause = cleanSel.substringAfter("[").removeSuffix("]").trim()
      val attrName = attrClause.substringBefore("=").trim()
      val attrVal = if (attrClause.contains("=")) attrClause.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'") else null

      return parsedElements.filter { el ->
        val tagMatch = tagPrefix.isBlank() || el.tagName.equals(tagPrefix, ignoreCase = true)
        val hasAttr = el.attributes.containsKey(attrName)
        val valMatch = if (attrVal != null) el.attributes[attrName]?.equals(attrVal, ignoreCase = true) == true else hasAttr
        tagMatch && valMatch
      }
    }

    // Tag name selector (e.g. "h1", "a", "table", "p")
    return parsedElements.filter { it.tagName.equals(cleanSel, ignoreCase = true) }
  }

  companion object {
    fun parseHtmlToDom(rawHtml: String, baseUrl: String): List<DomElement> {
      val elements = mutableListOf<DomElement>()
      if (rawHtml.isBlank()) return elements

      val tagPattern = Pattern.compile("(?is)<([a-zA-Z0-9]+)([^>]*)>(.*?)</\\1>|<([a-zA-Z0-9]+)([^>]*)/?>")
      val matcher = tagPattern.matcher(rawHtml)

      while (matcher.find()) {
        val tagName = (matcher.group(1) ?: matcher.group(4) ?: "").lowercase().trim()
        val rawAttrs = matcher.group(2) ?: matcher.group(5) ?: ""
        val innerContent = matcher.group(3) ?: ""
        val outer = matcher.group(0) ?: ""

        if (tagName.isBlank() || tagName == "html" || tagName == "head" || tagName == "body") continue

        val attrs = parseAttributes(rawAttrs, baseUrl)
        val id = attrs["id"].orEmpty()
        val classes = attrs["class"]?.split("\\s+".toRegex())?.filter { it.isNotBlank() } ?: emptyList()
        val text = innerContent.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

        elements.add(
          DomElement(
            tagName = tagName,
            id = id,
            classNames = classes,
            attributes = attrs,
            textContent = text,
            innerHTML = innerContent,
            outerHTML = outer
          )
        )
      }
      return elements
    }

    private fun parseAttributes(raw: String, baseUrl: String): Map<String, String> {
      val map = mutableMapOf<String, String>()
      val pattern = Pattern.compile("(?i)([a-zA-Z0-9_-]+)\\s*=\\s*[\"']([^\"']*)[\"']|([a-zA-Z0-9_-]+)\\s*=\\s*([^\\s>]+)")
      val matcher = pattern.matcher(raw)
      while (matcher.find()) {
        val key = (matcher.group(1) ?: matcher.group(3) ?: "").lowercase().trim()
        var value = matcher.group(2) ?: matcher.group(4) ?: ""
        if ((key == "href" || key == "src") && baseUrl.isNotBlank() && !value.startsWith("http") && !value.startsWith("data:")) {
          value = try {
            URI(baseUrl).resolve(value).toString()
          } catch (_: Exception) {
            value
          }
        }
        if (key.isNotBlank()) map[key] = value
      }
      return map
    }

    private fun extractTitle(html: String, fallbackUrl: String): String {
      val m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html)
      if (m.find()) {
        return m.group(1)?.replace(Regex("<[^>]+>"), "")?.trim() ?: fallbackUrl
      }
      return fallbackUrl
    }

    private fun extractBodyHtml(html: String): String {
      val m = Pattern.compile("(?is)<body[^>]*>(.*?)</body>").matcher(html)
      return if (m.find()) m.group(1)?.trim() ?: html else html
    }

    private fun cleanHtmlToText(html: String): String {
      return html
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    }
  }
}

/**
 * Structured result of evaluating JavaScript in Playwright context.
 */
data class JsEvaluationResult(
  val result: Any?,
  val type: String,
  val url: String,
  val pageTitle: String,
  val error: String? = null,
  val consoleLogs: List<String> = emptyList()
) {
  fun toJson(): JSONObject = JSONObject().apply {
    put("url", url)
    put("title", pageTitle)
    put("type", type)
    if (error != null) {
      put("error", error)
      put("success", false)
    } else {
      put("success", true)
      when (result) {
        is Map<*, *> -> put("result", JSONObject(result))
        is List<*> -> put("result", JSONArray(result.map { if (it is PlaywrightJsEngine.DomElement) it.toMap() else it }))
        is JSONArray -> put("result", result)
        is JSONObject -> put("result", result)
        else -> put("result", result)
      }
    }
    if (consoleLogs.isNotEmpty()) {
      put("consoleLogs", JSONArray(consoleLogs))
    }
  }
}
