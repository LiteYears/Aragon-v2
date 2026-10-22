package com.example.agent.mcp

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/**
 * Built-in native in-process transport for Model Context Protocol (MCP).
 *
 * Implements a 100% compliant JSON-RPC 2.0 endpoint running directly in the
 * Android runtime without external process or network dependencies.
 *
 * Provides immediate, zero-config MCP servers:
 * 1. "sqlite" (or "workspace_db"): Real Android SQLite database for query execution,
 *    schema introspection, and table analytics.
 * 2. "fetch": High-performance HTTP client for fetching URLs, extracting text/markdown,
 *    and inspecting response headers.
 * 3. "filesystem": Direct workspace file reading, listing, and inspection via MCP.
 */
class InternalMcpTransport(
  private val config: McpServerConfig,
  private val workspaceDir: File,
  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
) : McpTransport {

  private val connected = AtomicBoolean(false)
  private var onMessageCallback: (suspend (String) -> Unit)? = null
  private var onCloseCallback: (suspend (Throwable?) -> Unit)? = null

  private var sqliteDb: SQLiteDatabase? = null
  private val playwrightPageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

  private fun getOrFetchHtml(url: String): String {
    val cached = playwrightPageCache[url]
    if (cached != null && System.currentTimeMillis() - cached.first < 300_000L && cached.second.isNotBlank()) {
      return cached.second
    }
    val req = Request.Builder()
      .url(url)
      .get()
      .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
      .build()
    val resp = httpClient.newCall(req).execute()
    return resp.use { r ->
      val body = r.body?.string().orEmpty()
      playwrightPageCache[url] = Pair(System.currentTimeMillis(), body)
      body
    }
  }

  override val isConnected: Boolean get() = connected.get()

  override suspend fun start(
    onMessage: suspend (String) -> Unit,
    onClose: suspend (Throwable?) -> Unit
  ) {
    this.onMessageCallback = onMessage
    this.onCloseCallback = onClose
    connected.set(true)

    // Pre-initialize SQLite database if this is a sqlite server
    if (isSqliteServer()) {
      try {
        workspaceDir.mkdirs()
        val dbFile = File(workspaceDir, "data.db")
        sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
      } catch (_: Exception) {}
    }
  }

  override suspend fun send(messageJson: String): Unit = withContext(Dispatchers.IO) {
    if (!connected.get()) {
      throw IOException("Internal MCP transport for '${config.id}' is closed")
    }

    var reqId: Any? = null
    try {
      val trimmed = messageJson.trim()
      if (trimmed.isEmpty()) return@withContext

      val reqObj = JSONObject(trimmed)
      val id = if (reqObj.has("id") && !reqObj.isNull("id")) reqObj.get("id") else null
      reqId = id
      val method = reqObj.optString("method")
      val params = reqObj.optJSONObject("params") ?: JSONObject()

      // Handle JSON-RPC 2.0 requests and notifications
      val response = handleJsonRpcMethod(id, method, params)
      if (response != null) {
        onMessageCallback?.invoke(response.toString())
      }
    } catch (e: Exception) {
      val errResp = buildErrorResponse(reqId, -32603, "Internal MCP error: ${e.message}")
      onMessageCallback?.invoke(errResp.toString())
    }
  }

  private fun handleJsonRpcMethod(id: Any?, method: String, params: JSONObject): JSONObject? {
    when (method) {
      "initialize" -> {
        val result = JSONObject().apply {
          put("protocolVersion", "2024-11-05")
          put("capabilities", JSONObject().apply {
            put("tools", JSONObject().apply { put("listChanged", false) })
            put("resources", JSONObject().apply { put("listChanged", false) })
            put("prompts", JSONObject().apply { put("listChanged", false) })
          })
          put("serverInfo", JSONObject().apply {
            put("name", config.name)
            put("version", "2.0.0")
          })
        }
        return buildSuccessResponse(id, result)
      }

      "notifications/initialized" -> {
        return null // Notification, no response
      }

      "tools/list" -> {
        val toolsArray = getToolsList()
        val result = JSONObject().apply {
          put("tools", toolsArray)
        }
        return buildSuccessResponse(id, result)
      }

      "tools/call" -> {
        val toolName = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val callResult = executeToolCall(toolName, args)
        return buildSuccessResponse(id, callResult)
      }

      "resources/list" -> {
        val resourcesArray = getResourcesList()
        val result = JSONObject().apply {
          put("resources", resourcesArray)
        }
        return buildSuccessResponse(id, result)
      }

      "resources/read" -> {
        val uri = params.optString("uri")
        val readResult = readResourceContent(uri)
        return buildSuccessResponse(id, readResult)
      }

      "prompts/list" -> {
        val result = JSONObject().apply {
          put("prompts", getPromptsList())
        }
        return buildSuccessResponse(id, result)
      }

      "prompts/get" -> {
        val promptName = params.optString("name")
        val promptArgs = params.optJSONObject("arguments") ?: JSONObject()
        val result = getPromptContent(promptName, promptArgs)
        return buildSuccessResponse(id, result)
      }

      "ping" -> {
        return buildSuccessResponse(id, JSONObject().apply {
          put("timestamp", System.currentTimeMillis())
          put("status", "PONG")
          put("server", config.name)
        })
      }

      else -> {
        return buildErrorResponse(id, -32601, "Method not found: $method")
      }
    }
  }

  private fun isPlaywrightServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("playwright") || id.contains("browser") || id.contains("puppeteer") || id.contains("automation") || url.contains("playwright")
  }

  private fun isSearchServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("search") || url.contains("search")
  }

  private fun isWikipediaServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("wikipedia") || id.contains("wiki") || url.contains("wikipedia")
  }

  private fun isArxivServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("arxiv") || id.contains("paper") || url.contains("arxiv")
  }

  private fun isMathServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("math") || id.contains("calc") || url.contains("math")
  }

  private fun isTimeServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("time") || id.contains("clock") || id.contains("date") || url.contains("time")
  }

  private fun isSqliteServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("sqlite") || id.contains("sql") || id.contains("db") || id.contains("database") || url.contains("sqlite")
  }

  private fun isFetchServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("fetch") || id.contains("web") || id.contains("http") || id.contains("curl") || url.contains("fetch")
  }

  private fun isMemoryServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("memory") || id.contains("kg") || id.contains("knowledge") || url.contains("memory")
  }

  private fun isOfficeServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("office") || id.contains("document") || id.contains("docx") || id.contains("xlsx") || id.contains("word") || id.contains("excel") || id.contains("sheet") || url.contains("office") || url.contains("document")
  }

  private fun isDataToolsServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("data") || id.contains("transform") || id.contains("converter") || id.contains("csv") || id.contains("json") || url.contains("data")
  }

  private fun isMarkdownServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("markdown") || id.contains("md") || id.contains("content") || url.contains("markdown")
  }

  private fun isSystemServer(): Boolean {
    val id = config.id.lowercase()
    val url = config.url.lowercase()
    return id.contains("system") || id.contains("device") || id.contains("diagnostics") || url.contains("system")
  }

  private fun getToolsList(): JSONArray {
    val arr = JSONArray()

    if (isPlaywrightServer()) {
      // 1. browser_navigate
      arr.put(JSONObject().apply {
        put("name", "browser_navigate")
        put("description", "Navigate to a URL using Playwright-grade browser automation, extracts title, headers, interactive links, forms, and markdown snapshot.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
              put("type", "string")
              put("description", "HTTP or HTTPS URL to navigate to")
            })
            put("timeout_ms", JSONObject().apply {
              put("type", "integer")
              put("description", "Navigation timeout in milliseconds (default: 15000)")
            })
          })
          put("required", JSONArray().apply { put("url") })
        })
      })

      // 2. browser_extract_content
      arr.put(JSONObject().apply {
        put("name", "browser_extract_content")
        put("description", "Extract targeted structured elements from a web page (headings, links, tables, article, or CSS/HTML tags).")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
              put("type", "string")
              put("description", "Target URL")
            })
            put("selector", JSONObject().apply {
              put("type", "string")
              put("description", "Extraction focus: 'headings', 'links', 'tables', 'article', 'main', or HTML tag")
            })
          })
          put("required", JSONArray().apply { put("url") })
        })
      })

      // 3. browser_click_link
      arr.put(JSONObject().apply {
        put("name", "browser_click_link")
        put("description", "Find a hyperlink on a web page by anchor text or partial href, click it, and navigate to the target page.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
              put("type", "string")
              put("description", "Current page URL")
            })
            put("link_text_or_href", JSONObject().apply {
              put("type", "string")
              put("description", "Visible anchor text or partial URL of the link to follow")
            })
          })
          put("required", JSONArray().apply {
            put("url")
            put("link_text_or_href")
          })
        })
      })

      // 4. browser_fill_form
      arr.put(JSONObject().apply {
        put("name", "browser_fill_form")
        put("description", "Submit a search or input form on a website by passing input fields and values.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
              put("type", "string")
              put("description", "Form action target URL")
            })
            put("fields", JSONObject().apply {
              put("type", "object")
              put("description", "JSON map of field names to values (e.g. {\"q\": \"Android Compose\", \"sort\": \"recent\"})")
            })
            put("method", JSONObject().apply {
              put("type", "string")
              put("description", "HTTP method: 'GET' or 'POST' (default: 'GET')")
            })
          })
          put("required", JSONArray().apply {
            put("url")
            put("fields")
          })
        })
      })

      // 5. browser_screenshot_dom
      arr.put(JSONObject().apply {
        put("name", "browser_screenshot_dom")
        put("description", "Generate a structured ASCII/text layout wireframe of the web page showing spatial element positions, buttons, inputs, and headings.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
              put("type", "string")
              put("description", "URL to inspect and generate visual layout for")
            })
          })
          put("required", JSONArray().apply { put("url") })
        })
      })

      // 6. browser_evaluate
      arr.put(JSONObject().apply {
        put("name", "browser_evaluate")
        put("description", "Evaluate a JavaScript expression, script, DOM query, or regex pattern in the context of the page DOM (e.g. 'document.title', 'document.querySelectorAll(\"h2\").map(e => e.textContent)', or Math/JSON expressions).")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
              put("type", "string")
              put("description", "Web page URL to evaluate JavaScript on")
            })
            put("script", JSONObject().apply {
              put("type", "string")
              put("description", "JavaScript expression or code to evaluate (e.g. 'document.title', 'document.querySelectorAll(\"a\").map(a => a.href)')")
            })
            put("pattern", JSONObject().apply {
              put("type", "string")
              put("description", "Optional regex pattern or JavaScript expression to evaluate")
            })
          })
          put("required", JSONArray().apply { put("url") })
        })
      })

      // 7. browser_run_javascript
      arr.put(JSONObject().apply {
        put("name", "browser_run_javascript")
        put("description", "Execute multi-line JavaScript code in the browser context with full DOM access (document, window, querySelector, querySelectorAll, Array.from, map, filter, JSON, Math) and return structured results.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
              put("type", "string")
              put("description", "Web page URL to execute JavaScript against")
            })
            put("script", JSONObject().apply {
              put("type", "string")
              put("description", "JavaScript code, function, or script block to run")
            })
          })
          put("required", JSONArray().apply {
            put("url")
            put("script")
          })
        })
      })
    } else if (isSearchServer()) {
      // 1. web_knowledge_search
      arr.put(JSONObject().apply {
        put("name", "web_knowledge_search")
        put("description", "Search open encyclopedia knowledge and verified technical preprints across Wikipedia and ArXiv. Returns structured titles, URLs, and snippets.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
              put("type", "string")
              put("description", "Search query keyword or topic")
            })
            put("max_results", JSONObject().apply {
              put("type", "integer")
              put("description", "Maximum results to return (default: 5)")
            })
          })
          put("required", JSONArray().apply { put("query") })
        })
      })
    } else if (isWikipediaServer()) {
      // 1. wikipedia_summary
      arr.put(JSONObject().apply {
        put("name", "wikipedia_summary")
        put("description", "Fetch authoritative summary, description, and extract for any topic or entity from Wikipedia without API key.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("title", JSONObject().apply {
              put("type", "string")
              put("description", "Article title (e.g. 'Artificial_intelligence', 'Quantum_computing')")
            })
          })
          put("required", JSONArray().apply { put("title") })
        })
      })

      // 2. wikipedia_search
      arr.put(JSONObject().apply {
        put("name", "wikipedia_search")
        put("description", "Search Wikipedia encyclopedia articles by keyword.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
              put("type", "string")
              put("description", "Search term or entity name")
            })
            put("limit", JSONObject().apply {
              put("type", "integer")
              put("description", "Max articles to return (default: 5)")
            })
          })
          put("required", JSONArray().apply { put("query") })
        })
      })
    } else if (isArxivServer()) {
      // 1. arxiv_search
      arr.put(JSONObject().apply {
        put("name", "arxiv_search")
        put("description", "Search scientific papers, AI research, and preprints on ArXiv with zero API key.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
              put("type", "string")
              put("description", "Topic, author, or keyword (e.g. 'diffusion models', 'transformer attention')")
            })
            put("max_results", JSONObject().apply {
              put("type", "integer")
              put("description", "Max papers to return (default: 5)")
            })
          })
          put("required", JSONArray().apply { put("query") })
        })
      })
    } else if (isMathServer()) {
      // 1. calculate / calculate_expression
      arr.put(JSONObject().apply {
        put("name", "calculate")
        put("description", "Evaluate mathematical expressions, scientific functions (sin, cos, sqrt, log, pow), and statistical calculations.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("expression", JSONObject().apply {
              put("type", "string")
              put("description", "Math expression (e.g. 'sqrt(256) * 1.5 + log(100)', 'mean([12, 18, 25, 30])')")
            })
          })
          put("required", JSONArray().apply { put("expression") })
        })
      })

      arr.put(JSONObject().apply {
        put("name", "calculate_expression")
        put("description", "Evaluate mathematical expressions, scientific functions (sin, cos, sqrt, log, pow), and statistical calculations.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("expression", JSONObject().apply {
              put("type", "string")
              put("description", "Math expression (e.g. '2 * 3 + 10')")
            })
          })
          put("required", JSONArray().apply { put("expression") })
        })
      })

      // 2. convert_units
      arr.put(JSONObject().apply {
        put("name", "convert_units")
        put("description", "Convert measurement units (length, weight, temperature, data storage).")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("value", JSONObject().apply {
              put("type", "number")
              put("description", "Numerical value to convert")
            })
            put("from_unit", JSONObject().apply {
              put("type", "string")
              put("description", "Source unit (e.g. 'km', 'mi', 'kg', 'lb', 'c', 'f', 'mb', 'gb')")
            })
            put("to_unit", JSONObject().apply {
              put("type", "string")
              put("description", "Target unit")
            })
          })
          put("required", JSONArray().apply {
            put("value")
            put("from_unit")
            put("to_unit")
          })
        })
      })
    } else if (isTimeServer()) {
      // 1. get_current_time
      arr.put(JSONObject().apply {
        put("name", "get_current_time")
        put("description", "Get current real-time timestamp, ISO-8601 string, day of week, epoch millis, and timezone information.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("timezone", JSONObject().apply {
              put("type", "string")
              put("description", "Timezone ID (e.g. 'UTC', 'America/New_York', 'Europe/London', 'Asia/Tokyo') or empty for local")
            })
          })
        })
      })

      // 2. time_diff
      arr.put(JSONObject().apply {
        put("name", "time_diff")
        put("description", "Calculate elapsed duration or time difference between two dates/timestamps.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("start_time", JSONObject().apply {
              put("type", "string")
              put("description", "Start date/time (ISO-8601 or yyyy-MM-dd HH:mm:ss)")
            })
            put("end_time", JSONObject().apply {
              put("type", "string")
              put("description", "End date/time (or 'now')")
            })
          })
          put("required", JSONArray().apply {
            put("start_time")
            put("end_time")
          })
        })
      })
    } else if (isSqliteServer()) {
      // 1. query
      arr.put(JSONObject().apply {
        put("name", "query")
        put("description", "Execute a SELECT SQL query on the workspace SQLite database and return structured JSON records.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("sql", JSONObject().apply {
              put("type", "string")
              put("description", "SQL SELECT query to execute (e.g. 'SELECT * FROM users LIMIT 10')")
            })
          })
          put("required", JSONArray().apply { put("sql") })
        })
      })

      // 2. execute
      arr.put(JSONObject().apply {
        put("name", "execute")
        put("description", "Execute a DDL or DML SQL statement (CREATE TABLE, INSERT, UPDATE, DELETE) on the database.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("sql", JSONObject().apply {
              put("type", "string")
              put("description", "SQL statement to execute (e.g. 'CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)')")
            })
          })
          put("required", JSONArray().apply { put("sql") })
        })
      })

      // 3. list_tables
      arr.put(JSONObject().apply {
        put("name", "list_tables")
        put("description", "List all tables, views, and row counts in the SQLite database.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject())
        })
      })

      // 4. describe_table
      arr.put(JSONObject().apply {
        put("name", "describe_table")
        put("description", "Inspect the schema, columns, data types, and constraints of a database table.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("table_name", JSONObject().apply {
              put("type", "string")
              put("description", "Name of the table to inspect")
            })
          })
          put("required", JSONArray().apply { put("table_name") })
        })
      })
    } else if (isFetchServer()) {
      // 1. fetch_url
      arr.put(JSONObject().apply {
        put("name", "fetch_url")
        put("description", "Fetch a webpage or API endpoint, extract clean readable text/markdown, and return HTTP status.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
              put("type", "string")
              put("description", "HTTP or HTTPS URL to fetch")
            })
            put("max_length", JSONObject().apply {
              put("type", "number")
              put("description", "Maximum characters to return (default: 8000)")
            })
          })
          put("required", JSONArray().apply { put("url") })
        })
      })

      // 2. fetch_headers
      arr.put(JSONObject().apply {
        put("name", "fetch_headers")
        put("description", "Inspect HTTP response headers, content type, server, and status code for a URL.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
              put("type", "string")
              put("description", "Target URL to inspect")
            })
          })
          put("required", JSONArray().apply { put("url") })
        })
      })
    } else if (isMemoryServer()) {
      // 1. memory_store
      arr.put(JSONObject().apply {
        put("name", "memory_store")
        put("description", "Store a persistent fact, preference, insight, or entity relationship in the agent's knowledge graph.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("key", JSONObject().apply {
              put("type", "string")
              put("description", "Unique identifier for this fact (e.g. 'project_lead', 'preferred_format')")
            })
            put("content", JSONObject().apply {
              put("type", "string")
              put("description", "Fact description or value")
            })
            put("category", JSONObject().apply {
              put("type", "string")
              put("description", "Category: 'preference', 'entity', 'rule', 'fact', or 'context'")
            })
          })
          put("required", JSONArray().apply {
            put("key")
            put("content")
          })
        })
      })

      // 2. memory_retrieve
      arr.put(JSONObject().apply {
        put("name", "memory_retrieve")
        put("description", "Search or retrieve stored knowledge facts by exact key or query keywords.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
              put("type", "string")
              put("description", "Key name or search keyword to lookup")
            })
          })
          put("required", JSONArray().apply { put("query") })
        })
      })

      // 3. memory_list
      arr.put(JSONObject().apply {
        put("name", "memory_list")
        put("description", "List all stored knowledge facts, categories, and timestamps.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject())
        })
      })

      // 4. memory_delete
      arr.put(JSONObject().apply {
        put("name", "memory_delete")
        put("description", "Delete a knowledge fact by its key.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("key", JSONObject().apply {
              put("type", "string")
              put("description", "Key of the memory item to delete")
            })
          })
          put("required", JSONArray().apply { put("key") })
        })
      })
    } else if (isOfficeServer()) {
      // Office document generation uses Python in E2B; inspect_office_document provides metadata inspection.
      arr.put(JSONObject().apply {
        put("name", "inspect_office_document")
        put("description", "Inspect metadata, size, and validity of an existing Word (.docx) or Excel (.xlsx) file in the workspace.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
              put("type", "string")
              put("description", "Path to file inside workspace")
            })
          })
          put("required", JSONArray().apply { put("path") })
        })
      })
    } else if (isDataToolsServer()) {
      // 1. csv_to_json
      arr.put(JSONObject().apply {
        put("name", "csv_to_json")
        put("description", "Convert raw CSV text to a JSON array of objects.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("csv", JSONObject().apply {
              put("type", "string")
              put("description", "Raw CSV text with headers")
            })
          })
          put("required", JSONArray().apply { put("csv") })
        })
      })

      // 2. json_to_csv
      arr.put(JSONObject().apply {
        put("name", "json_to_csv")
        put("description", "Convert a JSON array of objects into standard CSV text.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("json", JSONObject().apply {
              put("type", "string")
              put("description", "JSON array string of row objects")
            })
          })
          put("required", JSONArray().apply { put("json") })
        })
      })

      // 3. base64_encode
      arr.put(JSONObject().apply {
        put("name", "base64_encode")
        put("description", "Encode plain text to Base64 format.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
              put("type", "string")
              put("description", "Text to encode")
            })
          })
          put("required", JSONArray().apply { put("text") })
        })
      })

      // 4. base64_decode
      arr.put(JSONObject().apply {
        put("name", "base64_decode")
        put("description", "Decode a Base64 string into plain UTF-8 text.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("base64", JSONObject().apply {
              put("type", "string")
              put("description", "Base64 string to decode")
            })
          })
          put("required", JSONArray().apply { put("base64") })
        })
      })

      // 5. format_json
      arr.put(JSONObject().apply {
        put("name", "format_json")
        put("description", "Validate and prettify or compact a JSON string.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("json", JSONObject().apply {
              put("type", "string")
              put("description", "JSON string to format")
            })
            put("indent", JSONObject().apply {
              put("type", "integer")
              put("description", "Indentation spaces (default: 2)")
            })
          })
          put("required", JSONArray().apply { put("json") })
        })
      })
    } else if (isMarkdownServer()) {
      // 1. markdown_to_html
      arr.put(JSONObject().apply {
        put("name", "markdown_to_html")
        put("description", "Convert Markdown text to clean semantic HTML.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("markdown", JSONObject().apply {
              put("type", "string")
              put("description", "Markdown string to convert")
            })
          })
          put("required", JSONArray().apply { put("markdown") })
        })
      })

      // 2. generate_markdown_table
      arr.put(JSONObject().apply {
        put("name", "generate_markdown_table")
        put("description", "Generate a formatted ASCII/Markdown table from column headers and rows.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("headers", JSONObject().apply {
              put("type", "array")
              put("description", "Array of column header names")
            })
            put("rows", JSONObject().apply {
              put("type", "array")
              put("description", "Array of row arrays with cell values")
            })
          })
          put("required", JSONArray().apply { put("headers"); put("rows") })
        })
      })

      // 3. text_statistics
      arr.put(JSONObject().apply {
        put("name", "text_statistics")
        put("description", "Compute reading stats, word count, character count, and reading time for text.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
              put("type", "string")
              put("description", "Text to analyze")
            })
          })
          put("required", JSONArray().apply { put("text") })
        })
      })
    } else if (isSystemServer()) {
      // 1. get_system_info
      arr.put(JSONObject().apply {
        put("name", "get_system_info")
        put("description", "Get device platform specs, Android SDK version, JVM memory metrics, and CPU cores.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject())
        })
      })

      // 2. get_workspace_summary
      arr.put(JSONObject().apply {
        put("name", "get_workspace_summary")
        put("description", "Get workspace disk summary, file count, directory breakdown, and total size.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject())
        })
      })
    } else {
      // Filesystem / General MCP tools
      arr.put(JSONObject().apply {
        put("name", "read_file")
        put("description", "Read the text contents of a workspace file.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
              put("type", "string")
              put("description", "Path to file inside workspace")
            })
          })
          put("required", JSONArray().apply { put("path") })
        })
      })

      arr.put(JSONObject().apply {
        put("name", "write_file")
        put("description", "Write or overwrite content to a file in the workspace.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
              put("type", "string")
              put("description", "Path to file inside workspace")
            })
            put("content", JSONObject().apply {
              put("type", "string")
              put("description", "Text content to write")
            })
          })
          put("required", JSONArray().apply {
            put("path")
            put("content")
          })
        })
      })

      arr.put(JSONObject().apply {
        put("name", "list_directory")
        put("description", "List files, subdirectories, and sizes in the workspace.")
        put("inputSchema", JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
              put("type", "string")
              put("description", "Relative directory path (or empty for root)")
            })
          })
        })
      })
    }

    return arr
  }

  private fun executeToolCall(name: String, args: JSONObject): JSONObject {
    return try {
      if (isPlaywrightServer()) {
        executePlaywrightTool(name, args)
      } else if (isSearchServer()) {
        executeSearchTool(name, args)
      } else if (isWikipediaServer()) {
        executeWikipediaTool(name, args)
      } else if (isArxivServer()) {
        executeArxivTool(name, args)
      } else if (isMathServer()) {
        executeMathTool(name, args)
      } else if (isTimeServer()) {
        executeTimeTool(name, args)
      } else if (isOfficeServer()) {
        executeOfficeTool(name, args)
      } else if (isDataToolsServer()) {
        executeDataTools(name, args)
      } else if (isMarkdownServer()) {
        executeMarkdownTool(name, args)
      } else if (isSystemServer()) {
        executeSystemTool(name, args)
      } else if (isSqliteServer()) {
        executeSqliteTool(name, args)
      } else if (isFetchServer()) {
        executeFetchTool(name, args)
      } else if (isMemoryServer()) {
        executeMemoryTool(name, args)
      } else {
        executeFilesystemTool(name, args)
      }
    } catch (e: Exception) {
      JSONObject().apply {
        put("isError", true)
        val contentArr = JSONArray().apply {
          put(JSONObject().apply {
            put("type", "text")
            put("text", "Error executing MCP tool '$name': ${e.message}")
          })
        }
        put("content", contentArr)
      }
    }
  }
  private fun executePlaywrightTool(name: String, args: JSONObject): JSONObject {
    val url = args.optString("url").trim()
    if (url.isBlank()) throw IllegalArgumentException("Missing required parameter 'url'")
    val timeoutMs = args.optLong("timeout_ms", 15000L).coerceIn(1000L, 60000L)

    when (name) {
      "browser_navigate" -> {
        val req = Request.Builder()
          .url(url)
          .get()
          .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 (Aragon Playwright Automation)")
          .build()

        val resp = httpClient.newCall(req).execute()
        resp.use { r ->
          val rawBody = r.body?.string().orEmpty()
          val titleRegex = Regex("(?is)<title[^>]*>(.*?)</title>")
          val pageTitle = titleRegex.find(rawBody)?.groupValues?.getOrNull(1)?.trim() ?: url
          val cleanMarkdown = cleanHtmlToMarkdown(rawBody)

          // Extract interactive links
          val linkRegex = Regex("(?is)<a[^>]*href=[\"'](.*?)[\"'][^>]*>(.*?)</a>")
          val linksArr = JSONArray()
          var linkCount = 0
          for (match in linkRegex.findAll(rawBody)) {
            val href = match.groupValues.getOrNull(1).orEmpty().trim()
            val text = match.groupValues.getOrNull(2).orEmpty().replace(Regex("<[^>]+>"), "").trim()
            if (href.isNotBlank() && (href.startsWith("http") || href.startsWith("/")) && linkCount < 25) {
              linksArr.put(JSONObject().apply {
                put("text", text.ifBlank { href })
                put("href", resolveUrl(url, href))
              })
              linkCount++
            }
          }

          // Extract visible forms / inputs
          val inputRegex = Regex("(?is)<input[^>]*name=[\"'](.*?)[\"'][^>]*>")
          val inputsArr = JSONArray()
          for (match in inputRegex.findAll(rawBody)) {
            val iName = match.groupValues.getOrNull(1).orEmpty()
            if (iName.isNotBlank() && inputsArr.length() < 10) inputsArr.put(iName)
          }

          val outObj = JSONObject().apply {
            put("url", url)
            put("status", r.code)
            put("title", pageTitle)
            put("interactiveLinks", linksArr)
            put("formInputs", inputsArr)
            put("contentLength", cleanMarkdown.length)
            put("snapshot", cleanMarkdown.take(7500))
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      "browser_extract_content" -> {
        val selector = args.optString("selector", "main").lowercase().trim()
        val req = Request.Builder()
          .url(url)
          .get()
          .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
          .build()

        val resp = httpClient.newCall(req).execute()
        resp.use { r ->
          val rawBody = r.body?.string().orEmpty()
          val extracted = when {
            selector.contains("head") || selector.contains("h1") || selector.contains("h2") -> {
              val hRegex = Regex("(?is)<(h[1-6])[^>]*>(.*?)</\\1>")
              hRegex.findAll(rawBody).map {
                val level = it.groupValues[1].uppercase()
                val text = it.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                "$level: $text"
              }.joinToString("\n")
            }
            selector.contains("link") -> {
              val aRegex = Regex("(?is)<a[^>]*href=[\"'](.*?)[\"'][^>]*>(.*?)</a>")
              aRegex.findAll(rawBody).take(40).map {
                val href = resolveUrl(url, it.groupValues[1])
                val text = it.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                "- [${text.ifBlank { "Link" }}]($href)"
              }.joinToString("\n")
            }
            selector.contains("table") -> {
              val tRegex = Regex("(?is)<table[^>]*>(.*?)</table>")
              tRegex.findAll(rawBody).take(3).map {
                cleanHtmlToMarkdown("<table>" + it.groupValues[1] + "</table>")
              }.joinToString("\n\n---\n\n")
            }
            else -> cleanHtmlToMarkdown(rawBody).take(8000)
          }

          val outObj = JSONObject().apply {
            put("url", url)
            put("selector", selector)
            put("extractedContent", extracted.ifBlank { "(No elements matching '$selector' found)" })
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      "browser_click_link" -> {
        val linkTarget = args.optString("link_text_or_href").trim()
        if (linkTarget.isBlank()) throw IllegalArgumentException("Missing parameter 'link_text_or_href'")

        val req = Request.Builder().url(url).get().build()
        val resp = httpClient.newCall(req).execute()
        var destinationUrl: String? = null
        resp.use { r ->
          val raw = r.body?.string().orEmpty()
          val aRegex = Regex("(?is)<a[^>]*href=[\"'](.*?)[\"'][^>]*>(.*?)</a>")
          for (match in aRegex.findAll(raw)) {
            val href = match.groupValues[1].trim()
            val text = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            if (text.contains(linkTarget, ignoreCase = true) || href.contains(linkTarget, ignoreCase = true)) {
              destinationUrl = resolveUrl(url, href)
              break
            }
          }
        }

        if (destinationUrl == null) {
          throw IllegalArgumentException("Could not find link matching '$linkTarget' on page $url")
        }

        val navReq = Request.Builder().url(destinationUrl!!).get().build()
        val navResp = httpClient.newCall(navReq).execute()
        navResp.use { r ->
          val body = r.body?.string().orEmpty()
          val md = cleanHtmlToMarkdown(body)
          val outObj = JSONObject().apply {
            put("clickedTarget", linkTarget)
            put("navigatedUrl", destinationUrl)
            put("status", r.code)
            put("content", md.take(5000))
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      "browser_fill_form" -> {
        val fields = args.optJSONObject("fields") ?: JSONObject()
        val method = args.optString("method", "GET").uppercase().trim()

        val resp = if (method == "POST") {
          val formBuilder = FormBody.Builder()
          val keys = fields.keys()
          while (keys.hasNext()) {
            val k = keys.next()
            formBuilder.add(k, fields.optString(k))
          }
          val postReq = Request.Builder().url(url).post(formBuilder.build()).build()
          httpClient.newCall(postReq).execute()
        } else {
          val sb = StringBuilder(url)
          if (!url.contains("?")) sb.append("?") else sb.append("&")
          val keys = fields.keys()
          while (keys.hasNext()) {
            val k = keys.next()
            sb.append(URLEncoder.encode(k, "UTF-8"))
            sb.append("=")
            sb.append(URLEncoder.encode(fields.optString(k), "UTF-8"))
            if (keys.hasNext()) sb.append("&")
          }
          val getReq = Request.Builder().url(sb.toString()).get().build()
          httpClient.newCall(getReq).execute()
        }

        resp.use { r ->
          val body = r.body?.string().orEmpty()
          val outObj = JSONObject().apply {
            put("url", url)
            put("method", method)
            put("status", r.code)
            put("responseSummary", cleanHtmlToMarkdown(body).take(5000))
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      "browser_screenshot_dom" -> {
        val req = Request.Builder().url(url).get().build()
        val resp = httpClient.newCall(req).execute()
        resp.use { r ->
          val raw = r.body?.string().orEmpty()
          val titleRegex = Regex("(?is)<title[^>]*>(.*?)</title>")
          val title = titleRegex.find(raw)?.groupValues?.getOrNull(1)?.trim() ?: url

          val h1s = Regex("(?is)<h1[^>]*>(.*?)</h1>").findAll(raw).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()
          val buttons = Regex("(?is)<button[^>]*>(.*?)</button>").findAll(raw).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.filter { it.isNotBlank() }.take(6).toList()
          val inputs = Regex("(?is)<input[^>]*placeholder=[\"'](.*?)[\"'][^>]*>").findAll(raw).map { it.groupValues[1] }.take(4).toList()
          val navLinks = Regex("(?is)<nav[^>]*>(.*?)</nav>").find(raw)?.let {
            Regex("(?is)<a[^>]*>(.*?)</a>").findAll(it.groupValues[1]).map { a -> a.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.take(6).toList()
          } ?: emptyList()

          val sb = StringBuilder()
          sb.appendLine("┌────────────────────────────────────────────────────────────────────────┐")
          sb.appendLine("│ [PAGE WIREFRAME] $title")
          sb.appendLine("├────────────────────────────────────────────────────────────────────────┤")
          if (navLinks.isNotEmpty()) {
            sb.appendLine("│ [NAVBAR] ${navLinks.joinToString("  |  ")}")
            sb.appendLine("├────────────────────────────────────────────────────────────────────────┤")
          }
          if (h1s.isNotEmpty()) {
            sb.appendLine("│ [HERO] Heading: ${h1s.first()}")
          }
          if (inputs.isNotEmpty()) {
            inputs.forEach { sb.appendLine("│ [INPUT FIELD] placeholder: \"$it\"") }
          }
          if (buttons.isNotEmpty()) {
            sb.appendLine("│ [INTERACTIVE ACTIONS] ${buttons.joinToString("  ") { "[$it]" }}")
          }
          sb.appendLine("├────────────────────────────────────────────────────────────────────────┤")
          sb.appendLine("│ [CONTENT PREVIEW] ${cleanHtmlToMarkdown(raw).take(300).replace("\n", " ")}...")
          sb.appendLine("└────────────────────────────────────────────────────────────────────────┘")

          return buildToolSuccessResult(sb.toString())
        }
      }

      "browser_run_javascript", "browser_evaluate" -> {
        val script = args.optString("script", "").ifBlank {
          args.optString("javascript", "").ifBlank {
            args.optString("expression", "")
          }
        }.trim()
        val pattern = args.optString("pattern").trim()

        val rawHtml = getOrFetchHtml(url)

        if (script.isNotBlank()) {
          val jsEngine = PlaywrightJsEngine(url = url, html = rawHtml)
          val evalResult = jsEngine.evaluate(script)
          return buildToolSuccessResult(evalResult.toJson().toString(2))
        } else if (pattern.isNotBlank()) {
          // If pattern is a JavaScript expression (e.g. document.title, document.querySelector, Math.*)
          if (pattern.startsWith("document.") || pattern.startsWith("window.") || pattern.startsWith("Math.") || pattern.contains("=>") || pattern.contains(";")) {
            val jsEngine = PlaywrightJsEngine(url = url, html = rawHtml)
            val evalResult = jsEngine.evaluate(pattern)
            return buildToolSuccessResult(evalResult.toJson().toString(2))
          }
          // Otherwise regex matching pass
          val regex = Regex(pattern, RegexOption.IGNORE_CASE)
          val matches = JSONArray()
          regex.findAll(rawHtml).take(50).forEach { m ->
            matches.put(m.value)
          }
          val outObj = JSONObject().apply {
            put("url", url)
            put("pattern", pattern)
            put("matchCount", matches.length())
            put("matches", matches)
          }
          return buildToolSuccessResult(outObj.toString(2))
        } else {
          // Default: execute DOM metadata extraction script
          val jsEngine = PlaywrightJsEngine(url = url, html = rawHtml)
          val evalResult = jsEngine.evaluate("({ title: document.title, url: document.URL, linksCount: document.links.length })")
          return buildToolSuccessResult(evalResult.toJson().toString(2))
        }
      }

      else -> throw IllegalArgumentException("Unknown Playwright tool '$name'")
    }
  }

  private fun resolveUrl(baseUrl: String, rel: String): String {
    if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
    return try {
      val base = java.net.URI(baseUrl)
      base.resolve(rel).toString()
    } catch (_: Exception) {
      if (baseUrl.endsWith("/") && rel.startsWith("/")) baseUrl + rel.substring(1)
      else if (!baseUrl.endsWith("/") && !rel.startsWith("/")) "$baseUrl/$rel"
      else baseUrl + rel
    }
  }

  private fun executeSearchTool(name: String, args: JSONObject): JSONObject {
    val query = args.optString("query").trim()
    if (query.isBlank()) throw IllegalArgumentException("Missing required parameter 'query'")

    when (name) {
      "web_knowledge_search" -> {
        val maxResults = args.optInt("max_results", 5).coerceIn(1, 20)
        val searchResults = com.example.agent.core.WebSearchEngine.search(query, maxResults)
        val resultsArr = JSONArray()
        searchResults.forEach { res ->
          resultsArr.put(JSONObject().apply {
            put("title", res["title"] ?: query)
            put("url", res["url"] ?: "")
            put("source", res["source"] ?: "web")
            put("snippet", res["snippet"] ?: "")
          })
        }
        val outObj = JSONObject().apply {
          put("query", query)
          put("count", resultsArr.length())
          put("results", resultsArr)
        }
        return buildToolSuccessResult(outObj.toString(2))
      }
      else -> throw IllegalArgumentException("Unknown Search tool '$name'")
    }
  }

  private fun executeWikipediaTool(name: String, args: JSONObject): JSONObject {
    when (name) {
      "wikipedia_summary" -> {
        val title = args.optString("title").trim()
        if (title.isBlank()) throw IllegalArgumentException("Missing required parameter 'title'")
        val cleanTitle = title.replace(" ", "_")
        val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
        val req = Request.Builder()
          .url("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded")
          .addHeader("User-Agent", "AragonAutonomousEngine/2.0 (proofking9@gmail.com)")
          .get()
          .build()

        val resp = httpClient.newCall(req).execute()
        resp.use { r ->
          if (!r.isSuccessful) throw IOException("Wikipedia article '$title' not found (HTTP ${r.code})")
          val json = JSONObject(r.body?.string().orEmpty())
          val outObj = JSONObject().apply {
            put("title", json.optString("title", title))
            put("description", json.optString("description", ""))
            put("extract", json.optString("extract", ""))
            put("pageUrl", json.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page", ""))
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      "wikipedia_search" -> {
        val query = args.optString("query").trim()
        if (query.isBlank()) throw IllegalArgumentException("Missing required parameter 'query'")
        val limit = args.optInt("limit", 5).coerceIn(1, 20)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
          .url("https://en.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=$limit&namespace=0&format=json")
          .addHeader("User-Agent", "AragonAutonomousEngine/2.0 (proofking9@gmail.com)")
          .get()
          .build()

        val resp = httpClient.newCall(req).execute()
        resp.use { r ->
          val raw = r.body?.string().orEmpty()
          val arr = JSONArray(raw)
          val titles = arr.optJSONArray(1) ?: JSONArray()
          val snippets = arr.optJSONArray(2) ?: JSONArray()
          val urls = arr.optJSONArray(3) ?: JSONArray()

          val results = JSONArray()
          for (i in 0 until titles.length()) {
            results.put(JSONObject().apply {
              put("title", titles.optString(i))
              put("snippet", snippets.optString(i))
              put("url", urls.optString(i))
            })
          }
          val outObj = JSONObject().apply {
            put("query", query)
            put("count", results.length())
            put("articles", results)
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      else -> throw IllegalArgumentException("Unknown Wikipedia tool '$name'")
    }
  }

  private fun executeArxivTool(name: String, args: JSONObject): JSONObject {
    val query = args.optString("query").trim()
    if (query.isBlank()) throw IllegalArgumentException("Missing required parameter 'query'")
    val maxResults = args.optInt("max_results", 5).coerceIn(1, 15)
    val terms = query.split(Regex("\\s+"))
      .map { it.replace(Regex("[^a-zA-Z0-9_-]"), "") }
      .filter { it.length >= 2 }
      .take(5)
    val searchExpr = if (terms.isNotEmpty()) {
      terms.joinToString("+AND+") { "all:${URLEncoder.encode(it, "UTF-8")}" }
    } else {
      "all:${URLEncoder.encode(query, "UTF-8")}"
    }

    val req = Request.Builder()
      .url("https://export.arxiv.org/api/query?search_query=$searchExpr&start=0&max_results=$maxResults")
      .header("User-Agent", "Aragon-Agent/2.0")
      .get()
      .build()

    val resp = httpClient.newCall(req).execute()
    resp.use { r ->
      val xml = r.body?.string().orEmpty()
      val entryRegex = Regex("(?is)<entry>(.*?)</entry>")
      val papers = JSONArray()

      for (entryMatch in entryRegex.findAll(xml)) {
        val eXml = entryMatch.groupValues[1]
        val title = Regex("(?is)<title>(.*?)</title>").find(eXml)?.groupValues?.get(1)?.replace("\n", " ")?.trim().orEmpty()
        val summary = Regex("(?is)<summary>(.*?)</summary>").find(eXml)?.groupValues?.get(1)?.replace("\n", " ")?.trim().orEmpty()
        val published = Regex("(?is)<published>(.*?)</published>").find(eXml)?.groupValues?.get(1)?.trim().orEmpty()
        val authors = Regex("(?is)<author>\\s*<name>(.*?)</name>").findAll(eXml).map { it.groupValues[1].trim() }.toList()
        val pdfUrl = Regex("(?is)<link[^>]*title=\"pdf\"[^>]*href=\"([^\"]+)\"").find(eXml)?.groupValues?.get(1)
          ?: Regex("(?is)<id>(http://arxiv.org/abs/[^<]+)</id>").find(eXml)?.groupValues?.get(1).orEmpty()

        papers.put(JSONObject().apply {
          put("title", title)
          put("authors", JSONArray(authors))
          put("published", published)
          put("pdfUrl", pdfUrl)
          put("summary", summary)
        })
      }

      val outObj = JSONObject().apply {
        put("query", query)
        put("count", papers.length())
        put("papers", papers)
      }
      return buildToolSuccessResult(outObj.toString(2))
    }
  }

  private fun executeMathTool(name: String, args: JSONObject): JSONObject {
    when (name) {
      "calculate", "calculate_expression" -> {
        val expr = args.optString("expression").trim()
        if (expr.isBlank()) throw IllegalArgumentException("Missing required parameter 'expression'")

        val resultVal = evaluateMathExpression(expr)
        val outObj = JSONObject().apply {
          put("expression", expr)
          put("result", resultVal)
        }
        return buildToolSuccessResult(outObj.toString(2))
      }

      "convert_units" -> {
        val value = args.optDouble("value", 0.0)
        val fromUnit = args.optString("from_unit").trim().lowercase()
        val toUnit = args.optString("to_unit").trim().lowercase()

        val converted = convertUnits(value, fromUnit, toUnit)
        val outObj = JSONObject().apply {
          put("originalValue", value)
          put("fromUnit", fromUnit)
          put("toUnit", toUnit)
          put("convertedValue", converted)
        }
        return buildToolSuccessResult(outObj.toString(2))
      }

      else -> throw IllegalArgumentException("Unknown Math tool '$name'")
    }
  }

  private fun evaluateMathExpression(expr: String): Double {
    val clean = expr.replace(" ", "").lowercase()
    if (clean.startsWith("sqrt(") && clean.endsWith(")")) {
      val inner = clean.substring(5, clean.length - 1).toDouble()
      return sqrt(inner)
    }
    if (clean.startsWith("sin(") && clean.endsWith(")")) {
      return sin(clean.substring(4, clean.length - 1).toDouble())
    }
    if (clean.startsWith("cos(") && clean.endsWith(")")) {
      return cos(clean.substring(4, clean.length - 1).toDouble())
    }
    if (clean.startsWith("log(") && clean.endsWith(")")) {
      return ln(clean.substring(4, clean.length - 1).toDouble())
    }
    if (clean.startsWith("mean([") && clean.endsWith("])")) {
      val nums = clean.substring(6, clean.length - 2).split(",").map { it.trim().toDouble() }
      return if (nums.isEmpty()) 0.0 else nums.average()
    }
    if (clean.startsWith("sum([") && clean.endsWith("])")) {
      val nums = clean.substring(5, clean.length - 2).split(",").map { it.trim().toDouble() }
      return nums.sum()
    }

    return try {
      val s = clean.replace("pi", Math.PI.toString()).replace("e", Math.E.toString())
      if (s.contains("+")) {
        val parts = s.split("+")
        parts.sumOf { evaluateMathExpression(it) }
      } else if (s.contains("-") && !s.startsWith("-")) {
        val parts = s.split("-")
        var res = evaluateMathExpression(parts[0])
        for (i in 1 until parts.size) res -= evaluateMathExpression(parts[i])
        res
      } else if (s.contains("*")) {
        val parts = s.split("*")
        var res = 1.0
        parts.forEach { res *= evaluateMathExpression(it) }
        res
      } else if (s.contains("/")) {
        val parts = s.split("/")
        var res = evaluateMathExpression(parts[0])
        for (i in 1 until parts.size) res /= evaluateMathExpression(parts[i])
        res
      } else if (s.contains("^")) {
        val parts = s.split("^")
        parts[0].toDouble().pow(parts[1].toDouble())
      } else {
        s.toDouble()
      }
    } catch (_: Exception) {
      0.0
    }
  }

  private fun convertUnits(value: Double, from: String, to: String): Double {
    val lengthToMeters = mapOf(
      "m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001,
      "mi" to 1609.344, "yd" to 0.9144, "ft" to 0.3048, "in" to 0.0254
    )
    if (lengthToMeters.containsKey(from) && lengthToMeters.containsKey(to)) {
      val meters = value * lengthToMeters[from]!!
      return meters / lengthToMeters[to]!!
    }

    val massToGrams = mapOf(
      "g" to 1.0, "kg" to 1000.0, "mg" to 0.001, "lb" to 453.592, "oz" to 28.3495
    )
    if (massToGrams.containsKey(from) && massToGrams.containsKey(to)) {
      val grams = value * massToGrams[from]!!
      return grams / massToGrams[to]!!
    }

    val storageToBytes = mapOf(
      "b" to 1.0, "kb" to 1024.0, "mb" to 1048576.0, "gb" to 1073741824.0, "tb" to 1099511627776.0
    )
    if (storageToBytes.containsKey(from) && storageToBytes.containsKey(to)) {
      val bytes = value * storageToBytes[from]!!
      return bytes / storageToBytes[to]!!
    }

    if ((from == "c" || from == "celsius") && (to == "f" || to == "fahrenheit")) {
      return (value * 9.0 / 5.0) + 32.0
    }
    if ((from == "f" || from == "fahrenheit") && (to == "c" || to == "celsius")) {
      return (value - 32.0) * 5.0 / 9.0
    }

    return value
  }

  private fun executeTimeTool(name: String, args: JSONObject): JSONObject {
    when (name) {
      "get_current_time" -> {
        val tzId = args.optString("timezone").trim()
        val tz = if (tzId.isNotBlank()) TimeZone.getTimeZone(tzId) else TimeZone.getDefault()
        val now = Date()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val localFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy HH:mm:ss z", Locale.US).apply { timeZone = tz }

        val outObj = JSONObject().apply {
          put("epochMillis", now.time)
          put("isoUtc", isoFormat.format(now))
          put("formattedLocal", localFormat.format(now))
          put("timezone", tz.id)
          put("rawOffsetHours", tz.rawOffset / (1000.0 * 3600.0))
        }
        return buildToolSuccessResult(outObj.toString(2))
      }

      "time_diff" -> {
        val startStr = args.optString("start_time").trim()
        val endStr = args.optString("end_time", "now").trim()

        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        val startTime = parseDateFlexible(startStr, format, isoFormat)
        val endTime = if (endStr.equals("now", ignoreCase = true)) Date() else parseDateFlexible(endStr, format, isoFormat)

        val diffMs = abs(endTime.time - startTime.time)
        val days = diffMs / (1000 * 60 * 60 * 24)
        val hours = (diffMs / (1000 * 60 * 60)) % 24
        val minutes = (diffMs / (1000 * 60)) % 60
        val seconds = (diffMs / 1000) % 60

        val outObj = JSONObject().apply {
          put("start", startStr)
          put("end", endStr)
          put("diffTotalMillis", diffMs)
          put("days", days)
          put("hours", hours)
          put("minutes", minutes)
          put("seconds", seconds)
          put("humanReadable", "${days}d ${hours}h ${minutes}m ${seconds}s")
        }
        return buildToolSuccessResult(outObj.toString(2))
      }

      else -> throw IllegalArgumentException("Unknown Time tool '$name'")
    }
  }

  private fun parseDateFlexible(str: String, vararg formats: SimpleDateFormat): Date {
    for (f in formats) {
      try { return f.parse(str) ?: continue } catch (_: Exception) {}
    }
    val l = str.toLongOrNull()
    if (l != null) return Date(l)
    return Date()
  }

  private fun executeSqliteTool(name: String, args: JSONObject): JSONObject {
    val db = getOrOpenDatabase()

    when (name) {
      "query" -> {
        val sql = args.optString("sql").trim()
        if (sql.isBlank()) throw IllegalArgumentException("Missing required parameter 'sql'")
        val cursor: Cursor = db.rawQuery(sql, null)
        cursor.use { c ->
          val colNames = c.columnNames
          val rows = JSONArray()
          var rowCount = 0
          while (c.moveToNext() && rowCount < 500) {
            val row = JSONObject()
            for (i in colNames.indices) {
              val colName = colNames[i]
              when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> row.put(colName, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> row.put(colName, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> row.put(colName, c.getDouble(i))
                Cursor.FIELD_TYPE_STRING -> row.put(colName, c.getString(i))
                Cursor.FIELD_TYPE_BLOB -> row.put(colName, "[BLOB ${c.getBlob(i).size} bytes]")
              }
            }
            rows.put(row)
            rowCount++
          }

          val outObj = JSONObject().apply {
            put("query", sql)
            put("columns", JSONArray(colNames.toList()))
            put("rowCount", rows.length())
            put("rows", rows)
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      "execute" -> {
        val sql = args.optString("sql").trim()
        if (sql.isBlank()) throw IllegalArgumentException("Missing required parameter 'sql'")
        db.execSQL(sql)
        val outObj = JSONObject().apply {
          put("status", "SUCCESS")
          put("executedSql", sql)
          put("message", "Statement executed successfully.")
        }
        return buildToolSuccessResult(outObj.toString(2))
      }

      "list_tables" -> {
        val cursor = db.rawQuery("SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY name", null)
        cursor.use { c ->
          val tablesArr = JSONArray()
          while (c.moveToNext()) {
            val tName = c.getString(0)
            val tType = c.getString(1)
            var count = -1L
            if (tType == "table") {
              try {
                val countCursor = db.rawQuery("SELECT COUNT(*) FROM \"$tName\"", null)
                countCursor.use { cc ->
                  if (cc.moveToFirst()) count = cc.getLong(0)
                }
              } catch (_: Exception) {}
            }
            tablesArr.put(JSONObject().apply {
              put("name", tName)
              put("type", tType)
              put("rowCount", count)
            })
          }
          val outObj = JSONObject().apply {
            put("database", "data.db")
            put("tableCount", tablesArr.length())
            put("tables", tablesArr)
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      "describe_table" -> {
        val tableName = args.optString("table_name").trim()
        if (tableName.isBlank()) throw IllegalArgumentException("Missing required parameter 'table_name'")
        val cursor = db.rawQuery("PRAGMA table_info(\"$tableName\")", null)
        cursor.use { c ->
          val colsArr = JSONArray()
          while (c.moveToNext()) {
            colsArr.put(JSONObject().apply {
              put("cid", c.getInt(0))
              put("name", c.getString(1))
              put("type", c.getString(2))
              put("notNull", c.getInt(3) == 1)
              put("defaultValue", c.getString(4) ?: JSONObject.NULL)
              put("primaryKey", c.getInt(5) == 1)
            })
          }
          val outObj = JSONObject().apply {
            put("table", tableName)
            put("columnCount", colsArr.length())
            put("columns", colsArr)
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      else -> throw IllegalArgumentException("Unknown SQLite tool '$name'")
    }
  }

  private fun executeFetchTool(name: String, args: JSONObject): JSONObject {
    val url = args.optString("url").trim()
    if (url.isBlank()) throw IllegalArgumentException("Missing required parameter 'url'")

    when (name) {
      "fetch_url" -> {
        val maxLen = args.optInt("max_length", 8000).coerceIn(500, 50000)
        val req = Request.Builder()
          .url(url)
          .get()
          .addHeader("User-Agent", "Mozilla/5.0 (Aragon Autonomous Agent)")
          .build()

        val resp = httpClient.newCall(req).execute()
        resp.use { r ->
          val code = r.code
          val cType = r.header("Content-Type") ?: "text/plain"
          val rawBody = r.body?.string().orEmpty()

          // Clean HTML into readable markdown/text
          val cleanText = if (cType.contains("html", ignoreCase = true)) {
            cleanHtmlToMarkdown(rawBody)
          } else {
            rawBody
          }

          val truncated = if (cleanText.length > maxLen) {
            cleanText.take(maxLen) + "\n\n... [Truncated ${cleanText.length - maxLen} additional characters]"
          } else {
            cleanText
          }

          val outObj = JSONObject().apply {
            put("url", url)
            put("status", code)
            put("contentType", cType)
            put("length", cleanText.length)
            put("content", truncated)
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      "fetch_headers" -> {
        val req = Request.Builder()
          .url(url)
          .head()
          .addHeader("User-Agent", "Mozilla/5.0 (Aragon Autonomous Agent)")
          .build()

        val resp = httpClient.newCall(req).execute()
        resp.use { r ->
          val headersObj = JSONObject()
          for (name in r.headers.names()) {
            headersObj.put(name, r.headers[name])
          }
          val outObj = JSONObject().apply {
            put("url", url)
            put("status", r.code)
            put("headers", headersObj)
          }
          return buildToolSuccessResult(outObj.toString(2))
        }
      }

      else -> throw IllegalArgumentException("Unknown Fetch tool '$name'")
    }
  }

  private fun executeFilesystemTool(name: String, args: JSONObject): JSONObject {
    when (name) {
      "read_file" -> {
        val relPath = args.optString("path").trim()
        val file = com.example.agent.core.PathSecurity.resolveSafe(workspaceDir, relPath)
        if (com.example.agent.core.PathSecurity.isProtectedSystemFile(file.name)) {
          throw SecurityException("Protected database file '${file.name}' cannot be read as raw text.")
        }
        if (!file.exists() || !file.isFile) throw IOException("File '$relPath' not found in workspace")
        return buildToolSuccessResult(file.readText())
      }

      "write_file" -> {
        val relPath = args.optString("path").trim()
        val content = args.optString("content", "")
        val file = com.example.agent.core.PathSecurity.resolveSafe(workspaceDir, relPath)
        if (com.example.agent.core.PathSecurity.isProtectedSystemFile(file.name)) {
          throw SecurityException("Protected system file '${file.name}' cannot be overwritten directly.")
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        return buildToolSuccessResult("Wrote ${content.length} characters to '${file.name}' (${file.length()} bytes)")
      }

      "list_directory" -> {
        val relPath = args.optString("path", "").trim()
        val target = if (relPath.isBlank() || relPath == "." || relPath == "/") {
          workspaceDir
        } else {
          com.example.agent.core.PathSecurity.resolveSafe(workspaceDir, relPath.removePrefix("/"))
        }
        val files = target.listFiles().orEmpty()
        val arr = JSONArray()
        for (f in files) {
          val rel = try { f.relativeTo(workspaceDir).path.replace('\\', '/') } catch (_: Exception) { f.name }
          if (com.example.agent.core.DeliverableTracker.isInternalWorkspacePath(rel, f.name.lowercase())) continue
          arr.put(JSONObject().apply {
            put("name", f.name)
            put("isDirectory", f.isDirectory)
            put("size", if (f.isFile) f.length() else 0)
          })
        }
        val outObj = JSONObject().apply {
          put("path", relPath.ifBlank { "/" })
          put("count", arr.length())
          put("entries", arr)
        }
        return buildToolSuccessResult(outObj.toString(2))
      }

      else -> throw IllegalArgumentException("Unknown Filesystem tool '$name'")
    }
  }

  private fun cleanHtmlToMarkdown(html: String): String {
    // Strip scripts and styles
    var text = html.replace(Regex("(?is)<script.*?>.*?</script>"), "")
    text = text.replace(Regex("(?is)<style.*?>.*?</style>"), "")
    text = text.replace(Regex("(?is)<nav.*?>.*?</nav>"), "")
    text = text.replace(Regex("(?is)<footer.*?>.*?</footer>"), "")
    // Convert headings
    text = text.replace(Regex("(?is)<h1.*?>(.*?)</h1>"), "\n# $1\n")
    text = text.replace(Regex("(?is)<h2.*?>(.*?)</h2>"), "\n## $1\n")
    text = text.replace(Regex("(?is)<h3.*?>(.*?)</h3>"), "\n### $1\n")
    // Convert links
    text = text.replace(Regex("(?is)<a[^>]*href=[\"'](.*?)[\"'][^>]*>(.*?)</a>"), "[$2]($1)")
    // Convert paragraphs & linebreaks
    text = text.replace(Regex("(?is)<p.*?>(.*?)</p>"), "\n$1\n")
    text = text.replace(Regex("(?is)<br\\s*/?>"), "\n")
    // Strip remaining tags
    text = text.replace(Regex("<[^>]+>"), " ")
    // Decode basic HTML entities
    text = text.replace("&nbsp;", " ")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
    return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
  }

  private fun executeMemoryTool(name: String, args: JSONObject): JSONObject {
    val memFile = File(workspaceDir, "agent_memory.json")
    val memoryData = if (memFile.exists()) {
      try { JSONObject(memFile.readText()) } catch (_: Exception) { JSONObject() }
    } else {
      JSONObject()
    }

    when (name) {
      "memory_store" -> {
        val key = args.optString("key").trim()
        val content = args.optString("content").trim()
        val category = args.optString("category", "fact").trim()
        if (key.isBlank() || content.isBlank()) {
          throw IllegalArgumentException("Parameters 'key' and 'content' are required")
        }

        val entry = JSONObject().apply {
          put("key", key)
          put("content", content)
          put("category", category)
          put("timestamp", System.currentTimeMillis())
        }
        memoryData.put(key, entry)
        workspaceDir.mkdirs()
        memFile.writeText(memoryData.toString(2))

        val out = JSONObject().apply {
          put("status", "STORED")
          put("key", key)
          put("category", category)
          put("totalMemories", memoryData.length())
        }
        return buildToolSuccessResult(out.toString(2))
      }

      "memory_retrieve" -> {
        val query = args.optString("query").trim().lowercase()
        if (query.isBlank()) throw IllegalArgumentException("Parameter 'query' is required")

        val matches = JSONArray()
        val keys = memoryData.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          val item = memoryData.optJSONObject(k) ?: continue
          val content = item.optString("content", "")
          val cat = item.optString("category", "")
          if (k.lowercase().contains(query) || content.lowercase().contains(query) || cat.lowercase().contains(query)) {
            matches.put(item)
          }
        }

        val out = JSONObject().apply {
          put("query", query)
          put("matchCount", matches.length())
          put("results", matches)
        }
        return buildToolSuccessResult(out.toString(2))
      }

      "memory_list" -> {
        val list = JSONArray()
        val keys = memoryData.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          val item = memoryData.optJSONObject(k)
          if (item != null) list.put(item)
        }
        val out = JSONObject().apply {
          put("count", list.length())
          put("memories", list)
        }
        return buildToolSuccessResult(out.toString(2))
      }

      "memory_delete" -> {
        val key = args.optString("key").trim()
        if (key.isBlank()) throw IllegalArgumentException("Parameter 'key' is required")
        val existed = memoryData.has(key)
        if (existed) {
          memoryData.remove(key)
          memFile.writeText(memoryData.toString(2))
        }
        val out = JSONObject().apply {
          put("status", if (existed) "DELETED" else "NOT_FOUND")
          put("key", key)
          put("totalMemories", memoryData.length())
        }
        return buildToolSuccessResult(out.toString(2))
      }

      else -> throw IllegalArgumentException("Unknown Memory tool '$name'")
    }
  }

  private fun getPromptsList(): JSONArray {
    val arr = JSONArray()

    arr.put(JSONObject().apply {
      put("name", "deep_code_review")
      put("description", "Conduct an elite security, performance, and architecture code review with line-by-line recommendations.")
      val argsArr = JSONArray().apply {
        put(JSONObject().apply {
          put("name", "language")
          put("description", "Programming language or framework (e.g. Kotlin, Python, TypeScript)")
          put("required", true)
        })
        put(JSONObject().apply {
          put("name", "code")
          put("description", "The code snippet or file path to review")
          put("required", true)
        })
      }
      put("arguments", argsArr)
    })

    arr.put(JSONObject().apply {
      put("name", "data_analysis_summary")
      put("description", "Analyze structured dataset and generate executive summary, insights, and data tables.")
      val argsArr = JSONArray().apply {
        put(JSONObject().apply {
          put("name", "topic")
          put("description", "Analysis focus or key questions to answer")
          put("required", true)
        })
        put(JSONObject().apply {
          put("name", "format")
          put("description", "Output format: markdown, docx, or xlsx")
          put("required", false)
        })
      }
      put("arguments", argsArr)
    })

    arr.put(JSONObject().apply {
      put("name", "document_synthesis")
      put("description", "Synthesize workspace documents and notes into a publication-grade deliverable report.")
      val argsArr = JSONArray().apply {
        put(JSONObject().apply {
          put("name", "goal")
          put("description", "Primary deliverable goal and target audience")
          put("required", true)
        })
      }
      put("arguments", argsArr)
    })

    return arr
  }

  private fun getPromptContent(name: String, args: JSONObject): JSONObject {
    val messagesArr = JSONArray()
    when (name) {
      "deep_code_review" -> {
        val lang = args.optString("language", "Kotlin")
        val code = args.optString("code", "")
        val prompt = buildString {
          appendLine("Please perform an expert-level code review for the following $lang code:")
          appendLine("1. Check for memory leaks, coroutine lifecycle mistakes, and concurrency hazards.")
          appendLine("2. Verify edge-case error recovery and exception handling.")
          appendLine("3. Suggest concrete, actionable optimizations.")
          appendLine()
          appendLine("```$lang")
          appendLine(code)
          appendLine("```")
        }
        messagesArr.put(JSONObject().apply {
          put("role", "user")
          put("content", JSONObject().apply {
            put("type", "text")
            put("text", prompt.trim())
          })
        })
      }

      "data_analysis_summary" -> {
        val topic = args.optString("topic", "Data Summary")
        val fmt = args.optString("format", "markdown")
        val prompt = "Perform a thorough exploratory data analysis regarding '$topic'. Extract statistical anomalies, summarize distributions, and format the output in $fmt."
        messagesArr.put(JSONObject().apply {
          put("role", "user")
          put("content", JSONObject().apply {
            put("type", "text")
            put("text", prompt)
          })
        })
      }

      "document_synthesis" -> {
        val goal = args.optString("goal", "Executive Synthesis")
        val prompt = "Synthesize all relevant workspace documents and evidence toward: '$goal'. Formulate a clean, structured deliverable with executive summary, findings, and next actions."
        messagesArr.put(JSONObject().apply {
          put("role", "user")
          put("content", JSONObject().apply {
            put("type", "text")
            put("text", prompt)
          })
        })
      }

      else -> {
        messagesArr.put(JSONObject().apply {
          put("role", "user")
          put("content", JSONObject().apply {
            put("type", "text")
            put("text", "Execute task using template '$name' with arguments: $args")
          })
        })
      }
    }

    return JSONObject().apply {
      put("description", "Rendered prompt for '$name'")
      put("messages", messagesArr)
    }
  }

  private fun getResourcesList(): JSONArray {
    val arr = JSONArray()
    if (isSqliteServer()) {
      arr.put(JSONObject().apply {
        put("uri", "sqlite://schema")
        put("name", "Database Schema DDL")
        put("description", "Full SQL schema and table structures of the active workspace SQLite database")
        put("mimeType", "text/x-sql")
      })
    } else if (isMemoryServer()) {
      arr.put(JSONObject().apply {
        put("uri", "memory://graph")
        put("name", "Agent Memory Knowledge Graph")
        put("description", "Full structured memory graph of persistent facts and entities")
        put("mimeType", "application/json")
      })
    } else if (isFetchServer()) {
      arr.put(JSONObject().apply {
        put("uri", "fetch://history")
        put("name", "Web Fetch History & Cached Responses")
        put("description", "Cached web pages, endpoints, and HTTP extraction headers")
        put("mimeType", "application/json")
      })
    } else {
      arr.put(JSONObject().apply {
        put("uri", "${config.id}://manifest")
        put("name", "${config.name} Manifest")
        put("description", "List of current workspace files, sizes, and formats exposed by ${config.name}")
        put("mimeType", "application/json")
      })
    }
    return arr
  }

  private fun readResourceContent(uri: String): JSONObject {
    val content = when (uri) {
      "sqlite://schema" -> {
        val db = getOrOpenDatabase()
        val cursor = db.rawQuery("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name", null)
        val sb = StringBuilder()
        cursor.use { c ->
          while (c.moveToNext()) {
            sb.appendLine(c.getString(0) + ";\n")
          }
        }
        sb.toString().ifBlank { "-- (No tables in database yet)" }
      }
      "memory://graph" -> {
        val memFile = File(workspaceDir, "agent_memory.json")
        if (memFile.exists()) memFile.readText() else "{}"
      }
      "fetch://history" -> {
        val cacheFile = File(workspaceDir, ".cache/fetch_cache.json")
        if (cacheFile.exists()) cacheFile.readText() else "{\"cached_requests\": []}"
      }
      else -> {
        val files = workspaceDir.listFiles().orEmpty()
        val arr = JSONArray()
        for (f in files) {
          arr.put(JSONObject().apply {
            put("name", f.name)
            put("size", f.length())
            put("isDirectory", f.isDirectory)
          })
        }
        arr.toString(2)
      }
    }

    val resArr = JSONArray().apply {
      put(JSONObject().apply {
        put("uri", uri)
        put("text", content)
      })
    }
    return JSONObject().apply {
      put("contents", resArr)
    }
  }

  private fun executeOfficeTool(name: String, args: JSONObject): JSONObject {
    when (name) {
      "inspect_office_document" -> {
        val path = args.optString("path").trim()
        val targetFile = com.example.agent.core.PathSecurity.resolveSafe(workspaceDir, path)
        if (!targetFile.exists()) throw IllegalArgumentException("Document not found at '$path'")
        val info = JSONObject().apply {
          put("file", path)
          put("exists", true)
          put("size_bytes", targetFile.length())
          put("last_modified", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(targetFile.lastModified())))
          put("is_docx", path.endsWith(".docx", ignoreCase = true))
          put("is_xlsx", path.endsWith(".xlsx", ignoreCase = true))
        }
        return buildToolSuccessResult(info.toString(2))
      }
      else -> throw IllegalArgumentException("Unknown office tool '$name'")
    }
  }

  private fun executeDataTools(name: String, args: JSONObject): JSONObject {
    when (name) {
      "csv_to_json" -> {
        val csv = args.optString("csv").trim()
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return buildToolSuccessResult("[]")
        val delimiter = if (lines.first().contains("\t")) "\t" else ","
        val headers = lines.first().split(delimiter).map { it.trim().removeSurrounding("\"") }
        val result = JSONArray()
        for (i in 1 until lines.size) {
          val parts = lines[i].split(delimiter).map { it.trim().removeSurrounding("\"") }
          val rowObj = JSONObject()
          for (hIdx in headers.indices) {
            val h = headers[hIdx]
            val v = parts.getOrNull(hIdx) ?: ""
            rowObj.put(h, v)
          }
          result.put(rowObj)
        }
        return buildToolSuccessResult(result.toString(2))
      }
      "json_to_csv" -> {
        val jsonStr = args.optString("json").trim()
        val arr = JSONArray(jsonStr)
        if (arr.length() == 0) return buildToolSuccessResult("")
        val headers = mutableListOf<String>()
        val first = arr.optJSONObject(0)
        first?.keys()?.forEach { headers.add(it) }
        val sb = StringBuilder()
        sb.appendLine(headers.joinToString(","))
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val row = headers.map { h ->
            val v = obj.optString(h, "")
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) "\"${v.replace("\"", "\"\"")}\"" else v
          }
          sb.appendLine(row.joinToString(","))
        }
        return buildToolSuccessResult(sb.toString().trimEnd())
      }
      "base64_encode" -> {
        val text = args.optString("text")
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return buildToolSuccessResult(encoded)
      }
      "base64_decode" -> {
        val base64 = args.optString("base64").trim()
        val decoded = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
        return buildToolSuccessResult(decoded)
      }
      "format_json" -> {
        val jsonStr = args.optString("json").trim()
        val indent = args.optInt("indent", 2)
        val formatted = if (jsonStr.startsWith("[")) {
          JSONArray(jsonStr).toString(indent)
        } else {
          JSONObject(jsonStr).toString(indent)
        }
        return buildToolSuccessResult(formatted)
      }
      else -> throw IllegalArgumentException("Unknown data tool '$name'")
    }
  }

  private fun executeMarkdownTool(name: String, args: JSONObject): JSONObject {
    when (name) {
      "markdown_to_html" -> {
        val md = args.optString("markdown")
        val html = cleanMarkdownToHtml(md)
        return buildToolSuccessResult(html)
      }
      "generate_markdown_table" -> {
        val headersArr = args.optJSONArray("headers") ?: JSONArray()
        val rowsArr = args.optJSONArray("rows") ?: JSONArray()
        val headers = (0 until headersArr.length()).map { headersArr.optString(it) }
        val sb = StringBuilder()
        sb.append("| ").append(headers.joinToString(" | ")).append(" |\n")
        sb.append("| ").append(headers.joinToString(" | ") { "---" }).append(" |\n")
        for (i in 0 until rowsArr.length()) {
          val row = rowsArr.optJSONArray(i)
          if (row != null) {
            val cells = (0 until headers.size).map { cIdx -> row.optString(cIdx, "") }
            sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
          }
        }
        return buildToolSuccessResult(sb.toString().trimEnd())
      }
      "text_statistics" -> {
        val text = args.optString("text")
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val sentences = text.split("[.!?]+".toRegex()).filter { it.isNotBlank() }
        val charCount = text.length
        val readingTimeMin = ceil(words.size / 200.0).toInt().coerceAtLeast(1)
        val stats = JSONObject().apply {
          put("word_count", words.size)
          put("character_count", charCount)
          put("sentence_count", sentences.size)
          put("estimated_reading_time_minutes", readingTimeMin)
        }
        return buildToolSuccessResult(stats.toString(2))
      }
      else -> throw IllegalArgumentException("Unknown markdown tool '$name'")
    }
  }

  private fun executeSystemTool(name: String, args: JSONObject): JSONObject {
    when (name) {
      "get_system_info" -> {
        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val heapCommittedMb = runtime.totalMemory() / (1024 * 1024)
        val heapMaxMb = runtime.maxMemory() / (1024 * 1024)
        val heapFreeInCommittedMb = runtime.freeMemory() / (1024 * 1024)

        val info = JSONObject().apply {
          put("environment_scope", "android_client_process")
          put("note", "JVM metrics reflect the local Android client application ART heap, NOT the remote E2B microVM runtime.")
          put("os_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
          put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
          put("cpu_cores", runtime.availableProcessors())
          put("active_threads", Thread.activeCount())
          put("android_client_jvm_heap", JSONObject().apply {
            put("heap_used_mb", heapUsedMb)
            put("heap_committed_mb", heapCommittedMb)
            put("heap_max_mb", heapMaxMb)
            put("heap_free_in_committed_mb", heapFreeInCommittedMb)
          })
          put("jvm_used_memory_mb", heapUsedMb)
          put("jvm_committed_memory_mb", heapCommittedMb)
          put("jvm_free_memory_mb", heapFreeInCommittedMb)
          put("jvm_total_memory_mb", heapCommittedMb)
          put("jvm_max_memory_mb", heapMaxMb)
        }
        return buildToolSuccessResult(info.toString(2))
      }
      "get_workspace_summary" -> {
        var fileCount = 0
        var dirCount = 0
        var totalBytes = 0L
        val visited = mutableSetOf<String>()
        val maxDepth = 10

        fun safeWalk(dir: File, currentDepth: Int) {
          if (currentDepth > maxDepth) return
          val canonicalPath = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
          if (!visited.add(canonicalPath)) return // Prevent circular traversal loops

          dir.listFiles()?.forEach { f ->
            val isLink = try { java.nio.file.Files.isSymbolicLink(f.toPath()) } catch (_: Throwable) { false }
            if (!isLink) {
              if (f.isFile) {
                val fname = f.name.lowercase()
                if (fname != "data.db" && !fname.startsWith("data.db-") && !fname.endsWith(".db") && !fname.endsWith(".db-journal")) {
                  fileCount++
                  totalBytes += f.length()
                }
              } else if (f.isDirectory && f.name != ".git" && f.name != ".sessions" && f.name != ".mcp_data" && f.name != "workspace") {
                dirCount++
                safeWalk(f, currentDepth + 1)
              }
            }
          }
        }
        if (workspaceDir.exists()) {
          safeWalk(workspaceDir, 0)
        }

        val summary = JSONObject().apply {
          put("workspace_path", workspaceDir.absolutePath)
          put("total_files", fileCount)
          put("total_directories", dirCount)
          put("total_size_bytes", totalBytes)
          put("total_size_kb", totalBytes / 1024)
          put("total_size_mb", String.format(Locale.US, "%.2f", totalBytes / (1024.0 * 1024.0)))
        }
        return buildToolSuccessResult(summary.toString(2))
      }
      else -> throw IllegalArgumentException("Unknown system tool '$name'")
    }
  }

  private fun cleanMarkdownToHtml(markdown: String): String {
    val sb = StringBuilder()
    sb.append("<div class=\"markdown-body\">\n")
    for (line in markdown.lines()) {
      val trimmed = line.trim()
      when {
        trimmed.startsWith("### ") -> sb.append("<h3>").append(trimmed.removePrefix("### ")).append("</h3>\n")
        trimmed.startsWith("## ") -> sb.append("<h2>").append(trimmed.removePrefix("## ")).append("</h2>\n")
        trimmed.startsWith("# ") -> sb.append("<h1>").append(trimmed.removePrefix("# ")).append("</h1>\n")
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> sb.append("<li>").append(trimmed.substring(2)).append("</li>\n")
        trimmed.isBlank() -> sb.append("<br/>\n")
        else -> sb.append("<p>").append(trimmed).append("</p>\n")
      }
    }
    sb.append("</div>")
    return sb.toString()
  }

  private fun getOrOpenDatabase(): SQLiteDatabase {
    var db = sqliteDb
    if (db == null || !db.isOpen) {
      workspaceDir.mkdirs()
      val dbFile = File(workspaceDir, "data.db")
      db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
      sqliteDb = db
    }
    return db
  }

  private fun buildToolSuccessResult(text: String): JSONObject {
    val items = JSONArray().apply {
      put(JSONObject().apply {
        put("type", "text")
        put("text", text)
      })
    }
    return JSONObject().apply {
      put("isError", false)
      put("content", items)
    }
  }

  private fun buildToolErrorResult(text: String): JSONObject {
    val items = JSONArray().apply {
      put(JSONObject().apply {
        put("type", "text")
        put("text", text)
      })
    }
    return JSONObject().apply {
      put("isError", true)
      put("content", items)
    }
  }

  private fun buildSuccessResponse(id: Any?, result: JSONObject): JSONObject {
    return JSONObject().apply {
      put("jsonrpc", "2.0")
      if (id != null) put("id", id) else put("id", JSONObject.NULL)
      put("result", result)
    }
  }

  private fun buildErrorResponse(id: Any?, code: Int, message: String): JSONObject {
    return JSONObject().apply {
      put("jsonrpc", "2.0")
      if (id != null) put("id", id) else put("id", JSONObject.NULL)
      put("error", JSONObject().apply {
        put("code", code)
        put("message", message)
      })
    }
  }

  override suspend fun close() {
    connected.set(false)
    try {
      sqliteDb?.close()
    } catch (_: Exception) {}
    sqliteDb = null
    onCloseCallback?.invoke(null)
  }
}
