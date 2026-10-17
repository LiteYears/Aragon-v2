package com.example.agent.docs

import org.json.JSONArray
import org.json.JSONObject

object DocumentStructureCodec {

  fun toJson(structure: DocumentStructure): String {
    val root = JSONObject()
    root.put("docId", structure.docId)
    root.put("displayName", structure.displayName)
    root.put("type", structure.type.name)
    root.put("pageCount", structure.pageCount)
    root.put("sheetCount", structure.sheetCount)
    root.put("primaryLanguage", structure.primaryLanguage)
    root.put("direction", structure.direction.name)
    root.put("textLength", structure.textLength)
    root.put("engines", JSONArray(structure.engines))
    root.put("warnings", JSONArray(structure.warnings))

    val blocks = JSONArray()
    for (block in structure.blocks) {
      val obj = JSONObject()
      obj.put("kind", block.kind.name)
      obj.put("text", block.text)
      obj.put("headingLevel", block.headingLevel)
      obj.put("orderedHint", block.orderedHint)
      obj.put("page", block.page ?: JSONObject.NULL)
      obj.put("sheetName", block.sheetName ?: JSONObject.NULL)
      obj.put("rowStart", block.rowStart ?: JSONObject.NULL)
      obj.put("rowEnd", block.rowEnd ?: JSONObject.NULL)
      obj.put("direction", block.direction.name)
      obj.put("language", block.language ?: JSONObject.NULL)
      obj.put("confidence", block.confidence ?: JSONObject.NULL)
      obj.put("uncertain", block.uncertain)
      obj.put("sourceEngine", block.sourceEngine ?: JSONObject.NULL)
      block.table?.let { table ->
        obj.put("table", tableToJson(table))
      }
      blocks.put(obj)
    }
    root.put("blocks", blocks)

    val sheets = JSONArray()
    for (sheet in structure.sheets) {
      val obj = JSONObject()
      obj.put("name", sheet.name)
      obj.put("rtl", sheet.rtl)
      obj.put("columnWidths", JSONArray(sheet.columnWidths))
      obj.put("mergedRanges", JSONArray(sheet.mergedRanges))
      val rows = JSONArray()
      for (row in sheet.rows) {
        val rowArr = JSONArray()
        for (cell in row) {
          val cellObj = JSONObject()
          cellObj.put("text", cell.text)
          cellObj.put("kind", cell.kind.name)
          cellObj.put("formula", cell.formula ?: JSONObject.NULL)
          cellObj.put("dateIso", cell.dateIso ?: JSONObject.NULL)
          rowArr.put(cellObj)
        }
        rows.put(rowArr)
      }
      obj.put("rows", rows)
      sheets.put(obj)
    }
    root.put("sheets", sheets)
    return root.toString()
  }

  private fun tableToJson(table: ExtractedTable): JSONObject {
    val obj = JSONObject()
    obj.put("header", JSONArray(table.header))
    val rows = JSONArray()
    for (row in table.rows) rows.put(JSONArray(row))
    obj.put("rows", rows)
    obj.put("direction", table.direction.name)
    obj.put("confidence", table.confidence)
    obj.put("sourceLocation", table.sourceLocation ?: JSONObject.NULL)
    obj.put("mergedRanges", JSONArray(table.mergedRanges))
    val formulas = JSONObject()
    for ((key, value) in table.formulaCells) formulas.put(key, value)
    obj.put("formulaCells", formulas)
    obj.put("uncertain", table.uncertain)
    return obj
  }

  fun fromJson(json: String): DocumentStructure {
    val root = JSONObject(json)
    val blocks = mutableListOf<DocumentBlock>()
    val blocksArr = root.optJSONArray("blocks") ?: JSONArray()
    for (i in 0 until blocksArr.length()) {
      val obj = blocksArr.getJSONObject(i)
      blocks.add(
        DocumentBlock(
          kind = runCatching { BlockKind.valueOf(obj.getString("kind")) }.getOrDefault(BlockKind.PARAGRAPH),
          text = obj.optString("text"),
          headingLevel = obj.optInt("headingLevel"),
          orderedHint = obj.optBoolean("orderedHint"),
          page = if (obj.isNull("page")) null else obj.optInt("page"),
          sheetName = if (obj.isNull("sheetName")) null else obj.optString("sheetName"),
          rowStart = if (obj.isNull("rowStart")) null else obj.optInt("rowStart"),
          rowEnd = if (obj.isNull("rowEnd")) null else obj.optInt("rowEnd"),
          table = if (obj.has("table") && !obj.isNull("table")) tableFromJson(obj.getJSONObject("table")) else null,
          direction = runCatching { TextDirection.valueOf(obj.optString("direction", "UNKNOWN")) }.getOrDefault(TextDirection.UNKNOWN),
          language = if (obj.isNull("language")) null else obj.optString("language"),
          confidence = if (obj.isNull("confidence")) null else obj.optDouble("confidence").toFloat(),
          uncertain = obj.optBoolean("uncertain"),
          sourceEngine = if (obj.isNull("sourceEngine")) null else obj.optString("sourceEngine")
        )
      )
    }

    val sheets = mutableListOf<ExtractedSheet>()
    val sheetsArr = root.optJSONArray("sheets") ?: JSONArray()
    for (i in 0 until sheetsArr.length()) {
      val obj = sheetsArr.getJSONObject(i)
      val rows = mutableListOf<List<CellValue>>()
      val rowsArr = obj.optJSONArray("rows") ?: JSONArray()
      for (r in 0 until rowsArr.length()) {
        val rowArr = rowsArr.getJSONArray(r)
        val row = mutableListOf<CellValue>()
        for (c in 0 until rowArr.length()) {
          val cellObj = rowArr.getJSONObject(c)
          row.add(
            CellValue(
              text = cellObj.optString("text"),
              kind = runCatching { CellKind.valueOf(cellObj.optString("kind", "TEXT")) }.getOrDefault(CellKind.TEXT),
              formula = if (cellObj.isNull("formula")) null else cellObj.optString("formula"),
              dateIso = if (cellObj.isNull("dateIso")) null else cellObj.optString("dateIso")
            )
          )
        }
        rows.add(row)
      }
      val widths = mutableListOf<Double>()
      obj.optJSONArray("columnWidths")?.let { arr ->
        for (w in 0 until arr.length()) widths.add(arr.optDouble(w))
      }
      val merges = mutableListOf<String>()
      obj.optJSONArray("mergedRanges")?.let { arr ->
        for (w in 0 until arr.length()) merges.add(arr.optString(w))
      }
      sheets.add(
        ExtractedSheet(
          name = obj.optString("name", "Sheet"),
          rows = rows,
          columnWidths = widths,
          rtl = obj.optBoolean("rtl"),
          mergedRanges = merges
        )
      )
    }

    val engines = mutableListOf<String>()
    root.optJSONArray("engines")?.let { arr ->
      for (i in 0 until arr.length()) engines.add(arr.optString(i))
    }
    val warnings = mutableListOf<String>()
    root.optJSONArray("warnings")?.let { arr ->
      for (i in 0 until arr.length()) warnings.add(arr.optString(i))
    }

    return DocumentStructure(
      docId = root.optString("docId"),
      displayName = root.optString("displayName"),
      type = runCatching { DocumentType.valueOf(root.optString("type", "TXT")) }.getOrDefault(DocumentType.TXT),
      blocks = blocks,
      sheets = sheets,
      pageCount = root.optInt("pageCount"),
      sheetCount = root.optInt("sheetCount"),
      primaryLanguage = if (root.isNull("primaryLanguage")) null else root.optString("primaryLanguage"),
      direction = runCatching { TextDirection.valueOf(root.optString("direction", "UNKNOWN")) }.getOrDefault(TextDirection.UNKNOWN),
      engines = engines,
      warnings = warnings,
      textLength = root.optInt("textLength")
    )
  }

  private fun tableFromJson(obj: JSONObject): ExtractedTable {
    val header = mutableListOf<String>()
    obj.optJSONArray("header")?.let { arr ->
      for (i in 0 until arr.length()) header.add(arr.optString(i))
    }
    val rows = mutableListOf<List<String>>()
    obj.optJSONArray("rows")?.let { rowsArr ->
      for (r in 0 until rowsArr.length()) {
        val rowArr = rowsArr.getJSONArray(r)
        val row = mutableListOf<String>()
        for (c in 0 until rowArr.length()) row.add(rowArr.optString(c))
        rows.add(row)
      }
    }
    val merges = mutableListOf<String>()
    obj.optJSONArray("mergedRanges")?.let { arr ->
      for (i in 0 until arr.length()) merges.add(arr.optString(i))
    }
    val formulas = mutableMapOf<String, String>()
    obj.optJSONObject("formulaCells")?.let { f ->
      val keys = f.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        formulas[key] = f.optString(key)
      }
    }
    return ExtractedTable(
      header = header,
      rows = rows,
      direction = runCatching { TextDirection.valueOf(obj.optString("direction", "UNKNOWN")) }.getOrDefault(TextDirection.UNKNOWN),
      confidence = obj.optString("confidence", "MEDIUM"),
      sourceLocation = if (obj.isNull("sourceLocation")) null else obj.optString("sourceLocation"),
      mergedRanges = merges,
      formulaCells = formulas,
      uncertain = obj.optBoolean("uncertain")
    )
  }
}
