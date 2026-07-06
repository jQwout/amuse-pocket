package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement
import org.json.JSONArray
import org.json.JSONObject

@Inject
@SingleIn(AppScope::class)
class DatabaseQueryTool(
  private val appDatabase: AppDatabase,
) : Tool {

  // Rows may include stored external text (e.g. captured messages).
  override val returnsUntrustedContent = true

  override val name = "database_query"
  override val description =
    "Выполняет SQL-запросы к локальной базе данных. " +
    "Поддерживает SELECT, INSERT, UPDATE, DELETE. DDL-команды (DROP, CREATE, ALTER, TRUNCATE) запрещены."
  override val schema = toolSchema(name, description) {
    string("sql", "SQL-запрос. Разрешены SELECT, INSERT, UPDATE, DELETE.", required = true)
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val sql = arguments.string("sql") ?: return missingArg("sql")

    if (!isAllowed(sql)) {
      return ToolResult.Error(
        "DDL commands are not allowed. Only SELECT, INSERT, UPDATE, DELETE permitted."
      )
    }

    val db = appDatabase.openHelper.writableDatabase
    return try {
      if (sql.trim().startsWith("select", ignoreCase = true)) executeSelect(db, sql)
      else executeWrite(db, sql)
    } catch (e: Exception) {
      ToolResult.Error("SQL error: ${e.message}")
    }
  }

  private fun isAllowed(sql: String): Boolean {
    // Разрешаем одну хвостовую ';', но блокируем мульти-стейтменты.
    val clean = sql.trim().trimEnd(';').trim()
    if (';' in clean) return false
    val firstToken = clean.takeWhile { !it.isWhitespace() }.lowercase()
    return firstToken !in FORBIDDEN_SQL
  }

  private fun executeSelect(db: SupportSQLiteDatabase, sql: String): ToolResult {
    val cursor: Cursor = db.query(sql)
    val result = JSONArray()
    val columns = cursor.columnNames
    var truncated = false
    while (cursor.moveToNext()) {
      if (result.length() >= MAX_ROWS) {
        truncated = true
        break
      }
      val row = JSONObject()
      columns.forEachIndexed { index, col ->
        row.put(col, cursor.getString(index) ?: JSONObject.NULL)
      }
      result.put(row)
    }
    cursor.close()
    // Модель сама пишет SQL и часто не ставит LIMIT — без потолка большой SELECT (вся история
    // сообщений и т.п.) раздул бы результат тула на мегабайты JSON прямо в контекст LLM. Маркер
    // усечения кладём ПОСЛЕДНИМ элементом массива — вывод остаётся валидным JSON целиком.
    if (truncated) {
      result.put(JSONObject().put("_truncated", true).put("_note", "truncated at $MAX_ROWS rows, add LIMIT to your query for fewer"))
    }
    return ToolResult.Success(result.toString(2))
  }

  private fun executeWrite(db: SupportSQLiteDatabase, sql: String): ToolResult {
    val firstToken = sql.trim().takeWhile { !it.isWhitespace() }.lowercase()
    return db.compileStatement(sql).use { statement ->
      if (firstToken == "insert") {
        ToolResult.Success("Inserted row (rowid=${statement.executeInsert()})")
      } else {
        ToolResult.Success("Query executed. Rows affected: ${statement.executeUpdateDelete()}")
      }
    }
  }

  private companion object {
    const val MAX_ROWS = 200
    val FORBIDDEN_SQL = setOf("drop", "create", "alter", "truncate", "grant", "revoke", "pragma")
  }
}
