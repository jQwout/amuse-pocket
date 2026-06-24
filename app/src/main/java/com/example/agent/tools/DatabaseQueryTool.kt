package com.aiopenpocket.agent.tools

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

@Inject
@SingleIn(AppScope::class)
class DatabaseQueryTool(
  private val appDatabase: AppDatabase,
) : Tool {

  override val name = "database_query"
  override val description =
    "Выполняет SQL-запросы к локальной базе данных. " +
    "Поддерживает SELECT, INSERT, UPDATE, DELETE. DDL-команды (DROP, CREATE, ALTER, TRUNCATE) запрещены."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "sql" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("SQL-запрос. Разрешены SELECT, INSERT, UPDATE, DELETE."),
          )),
        )),
        "required" to JsonArray(listOf(JsonPrimitive("sql"))),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val sql = (arguments["sql"] as? JsonPrimitive)?.content
      ?: return missingArg("sql")

    if (!isAllowed(sql)) {
      return ToolResult.Error(
        "DDL commands are not allowed. Only SELECT, INSERT, UPDATE, DELETE permitted."
      )
    }

    val db = appDatabase.openHelper.writableDatabase

    return try {
      when {
        sql.trim().startsWith("select", ignoreCase = true) -> executeSelect(db, sql)
        else -> executeWrite(db, sql)
      }
    } catch (e: Exception) {
      ToolResult.Error("SQL error: ${e.message}")
    }
  }

  private fun isAllowed(sql: String): Boolean {
    // Разрешаем одну хвостовую ';', но блокируем мульти-стейтменты.
    val clean = sql.trim().trimEnd(';').trim()
    if (';' in clean) return false
    val forbidden = setOf("drop", "create", "alter", "truncate", "grant", "revoke", "pragma")
    val firstToken = clean
      .substringBefore(" ")
      .substringBefore("\n")
      .substringBefore("\t")
      .lowercase()
    return firstToken !in forbidden
  }

  private fun executeSelect(db: SupportSQLiteDatabase, sql: String): ToolResult {
    val cursor: Cursor = db.query(sql)
    val result = JSONArray()
    val columns = cursor.columnNames

    while (cursor.moveToNext()) {
      val row = JSONObject()
      columns.forEachIndexed { index, col ->
        row.put(col, cursor.getString(index) ?: JSONObject.NULL)
      }
      result.put(row)
    }
    cursor.close()
    return ToolResult.Success(result.toString(2))
  }

  private fun executeWrite(db: SupportSQLiteDatabase, sql: String): ToolResult {
    val firstToken = sql.trim().substringBefore(" ").lowercase()
    return db.compileStatement(sql).use { statement ->
      if (firstToken == "insert") {
        val rowId = statement.executeInsert()
        ToolResult.Success("Inserted row (rowid=$rowId)")
      } else {
        val affected = statement.executeUpdateDelete()
        ToolResult.Success("Query executed. Rows affected: $affected")
      }
    }
  }
}
