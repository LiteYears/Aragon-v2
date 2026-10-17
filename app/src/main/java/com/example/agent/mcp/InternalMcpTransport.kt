package com.example.agent.mcp

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    try {
      val trimmed = messageJson.trim()
      if (trimmed.isEmpty()) return@withContext

      val reqObj = JSONObject(trimmed)
      val id = if (reqObj.has("id") && !reqObj.isNull("id")) reqObj.get("id") else null
      val method = reqObj.optString("method")
      val params = reqObj.optJSONObject("params") ?: JSONObject()

      // Handle JSON-RPC 2.0 requests and notifications
      val response = handleJsonRpcMethod(id, method, params)
      if (response != null) {
        onMessageCallback?.invoke(response.toString())
      }
    } catch (e: Exception) {
      val errResp = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", JSONObject.NULL)
        put("error", JSONObject().apply {
          put("code", -32603)
          put("message", "Internal MCP error: ${e.message}")
        })
      }
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

  private fun getToolsList(): JSONArray {
    val arr = JSONArray()

    if (isSqliteServer()) {
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
      if (isSqliteServer()) {
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
        val file = File(workspaceDir, relPath)
        if (!file.exists() || !file.isFile) throw IOException("File '$relPath' not found in workspace")
        return buildToolSuccessResult(file.readText())
      }

      "write_file" -> {
        val relPath = args.optString("path").trim()
        val content = args.optString("content", "")
        val file = File(workspaceDir, relPath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return buildToolSuccessResult("Wrote ${content.length} characters to '${file.name}' (${file.length()} bytes)")
      }

      "list_directory" -> {
        val relPath = args.optString("path", "").trim()
        val target = if (relPath.isBlank()) workspaceDir else File(workspaceDir, relPath)
        val files = target.listFiles().orEmpty()
        val arr = JSONArray()
        for (f in files) {
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
        put("uri", "workspace://manifest")
        put("name", "Workspace Manifest")
        put("description", "List of all current workspace files, sizes, and formats")
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
