package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aiopenpocket.agent.core.mcp.RiskClass
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

  /**
   * Per-call risk (Task 9): classifies by the SAME first-token parse [isAllowed]/[executeWrite]
   * already trust to gate/dispatch SQL - never re-derived by a separate, unchecked substring check
   * that a crafted argument (e.g. a string literal containing the word "delete") could fool.
   * DDL (forbidden outright by [isAllowed]) and any other unrecognized verb classify as READ, not
   * WRITE/DESTRUCTIVE: such a call can never actually mutate anything regardless of mode (it always
   * hits [isAllowed]'s rejection once it reaches [execute]), so letting it through as READ means that
   * honest "DDL commands are not allowed" error surfaces in both LIVE and PREVIEW, instead of a
   * misleading Simulate envelope for a statement that was never going to run at all.
   */
  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass {
    val sql = arguments.string("sql") ?: return riskClass
    return when (firstSqlToken(sql)) {
      "select" -> RiskClass.READ
      "insert", "update" -> RiskClass.WRITE
      "delete" -> RiskClass.DESTRUCTIVE
      else -> riskClass
    }
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
    return firstSqlToken(sql) !in FORBIDDEN_SQL
  }

  /**
   * The single first-whitespace-delimited keyword of [sql], lowercased - the one signal [isAllowed]
   * (DDL gate), [execute]/[executeWrite] (SELECT-vs-write dispatch) and [riskFor] (policy
   * classification) all key off, so none of them can silently disagree with what the others do.
   * Trailing ';' is stripped first so a single-keyword statement with nothing after it (e.g.
   * "pragma;") still yields "pragma", not "pragma;".
   */
  private fun firstSqlToken(sql: String): String =
    sql.trim().trimEnd(';').trim().takeWhile { !it.isWhitespace() }.lowercase()

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
    val firstToken = firstSqlToken(sql)
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
