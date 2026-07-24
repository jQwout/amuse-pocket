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

/**
 * Read-only SQL access to the app database (Part A security hardening, `user-agent-builder-848153`:
 * the pre-hardening tool let any agent run arbitrary INSERT/UPDATE/DELETE - including against
 * `user_agents`/`agent_hooks`/`heartbeats`, i.e. an agent could rewrite its OWN definition, hooks or
 * schedule, or another agent's, through "just SQL"). Now the model may only run SELECT statements,
 * and only against [READABLE_TABLES] - [FORBIDDEN_TABLES] (and anything simply absent from the
 * allowlist) are unreachable BY CONSTRUCTION, enforced in [rejectionReason] before any query ever
 * reaches SQLite, not by prompt wording. Structured writes go through [MemoryTools] (facts/episodes)
 * or `StorageTool` (per-agent free-form KV) instead - both scope themselves to the calling agent via
 * [com.aiopenpocket.agent.loop.TurnContextHolder], an isolation guarantee a raw SQL surface like this
 * one structurally cannot offer. That same limitation is why READS of per-agent-namespaced memory
 * (`facts`/`episodes`) and conversation-private data (`messages`/`conversations`) are OFF the read
 * allowlist too (see [READABLE_TABLES]) - a raw SELECT cannot restrict itself to the caller's namespace,
 * so those reads must also go through the scoped memory/conversation tools, never this one.
 */
@Inject
@SingleIn(AppScope::class)
class DatabaseQueryTool(
  private val appDatabase: AppDatabase,
) : Tool {

  // Rows may include stored external text (e.g. captured messages).
  override val returnsUntrustedContent = true

  override val name = "database_query"
  override val description =
    "Выполняет SQL SELECT-запросы (только чтение) к локальной базе данных по фиксированному " +
    "списку таблиц. INSERT/UPDATE/DELETE и системные таблицы агентов запрещены. Чтобы что-то " +
    "записать, используй memory_tool (факты/эпизоды) или storage_tool (произвольное хранилище агента)."
  override val schema = toolSchema(name, description) {
    string(
      "sql",
      "SQL-запрос. Разрешён только SELECT по таблицам: " +
        "${READABLE_TABLES.sorted().joinToString(", ")}.",
      required = true,
    )
  }

  /**
   * Per-call risk (Part A hardening): every statement this tool can actually execute is now a SELECT
   * against an allowlisted table (see [rejectionReason]) - there is no verb left that could ever
   * mutate anything, so the tool-wide ceiling [riskClass] (defaulting to [RiskClass.READ]) is always
   * the correct classification, regardless of what verb the caller's `sql` argument names. Kept as an
   * explicit override rather than simply deleted (which would silently inherit the identical value) so
   * "this tool cannot escalate anymore" stays a documented decision here, not an absence someone has
   * to notice and re-derive later.
   */
  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = riskClass

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val sql = arguments.string("sql") ?: return missingArg("sql")

    rejectionReason(sql)?.let { return ToolResult.Error(it) }

    val db = appDatabase.openHelper.writableDatabase
    return try {
      executeSelect(db, sql)
    } catch (e: Exception) {
      ToolResult.Error("SQL error: ${e.message}")
    }
  }

  /**
   * Null iff [sql] is a single, allowlisted, table-safe SELECT - every other outcome returns a
   * human-readable reason instead of a bare boolean, so [execute] can tell the model exactly why its
   * query was refused. FAIL-CLOSED throughout: any branch that cannot positively PROVE the statement
   * safe returns a rejection reason rather than falling through to "allowed" (an unparseable table
   * reference is treated exactly like a forbidden one - see [extractTables]).
   */
  private fun rejectionReason(sql: String): String? {
    // Разрешаем одну хвостовую ';', но блокируем мульти-стейтменты - один SQL-аргумент обязан быть
    // РОВНО одним запросом, иначе проверки таблиц ниже вообще не увидят второй/третий стейтмент,
    // склеенный через ';'.
    val clean = sql.trim().trimEnd(';').trim()
    if (';' in clean) return "Multiple statements in one call are not allowed."

    val firstToken = firstSqlToken(sql)
    if (firstToken != "select") {
      // DDL keeps its own, more specific message (unchanged from the pre-hardening tool); every
      // other non-SELECT verb (insert/update/delete/attach/pragma/a CTE's leading "with"/...) now
      // falls under the same generic "SELECT only" rejection instead of ever reaching a write path.
      // Note this also means a CTE ("WITH x AS (...) SELECT ...") is rejected outright, full stop -
      // this tool never supports CTEs, since their first token is "with", never "select".
      return if (firstToken in FORBIDDEN_SQL) {
        "DDL commands are not allowed."
      } else {
        "Only SELECT is allowed here. Writes go through memory_tool or storage_tool."
      }
    }

    // Belt-and-suspenders, independent of the FROM/JOIN scan below (Part A): a forbidden table name
    // appearing ANYWHERE in the statement - including inside a subquery body the scan might not walk
    // into, or a shape the scan does not recognize at all - is rejected outright, whether or not
    // extractTables would also have caught it.
    if (containsForbiddenToken(clean)) {
      return "Query references a table this tool can never read."
    }

    val tables = extractTables(clean)
      ?: return "Could not determine which table(s) this query reads - rejected (fail-closed)."
    val disallowed = tables.filterNot { it in READABLE_TABLES }
    if (disallowed.isNotEmpty()) {
      return "Table(s) not allowed: ${disallowed.sorted().joinToString(", ")}."
    }
    return null
  }

  /**
   * The single first-whitespace-delimited keyword of [sql], lowercased - the one signal
   * [rejectionReason] keys its SELECT-only gate off, so nothing else needs (or is tempted) to
   * re-derive it independently. Trailing ';' is stripped first so a single-keyword statement with
   * nothing after it (e.g. "pragma;") still yields "pragma", not "pragma;".
   */
  private fun firstSqlToken(sql: String): String =
    sql.trim().trimEnd(';').trim().takeWhile { !it.isWhitespace() }.lowercase()

  /**
   * True if any [FORBIDDEN_TABLES] name appears anywhere in [sql] as a whole word, case-insensitively
   * - independent of whether it follows FROM/JOIN at all, or of [extractTables] succeeding. See
   * [rejectionReason]'s belt-and-suspenders comment for why this check exists ALONGSIDE the FROM/JOIN
   * scan rather than instead of it. Trade-off, accepted deliberately: a query that merely mentions a
   * forbidden table's name inside a string literal (e.g. `WHERE content = 'heartbeats'`) is also
   * rejected, even though it never actually reads that table - a false rejection is safe, a false
   * allow is not.
   */
  private fun containsForbiddenToken(sql: String): Boolean {
    val lower = sql.lowercase()
    return FORBIDDEN_TABLES.any { table -> Regex("\\b${Regex.escape(table)}\\b").containsMatchIn(lower) }
  }

  /**
   * Every table name [sql] references via FROM/JOIN, normalized (quotes/backticks/brackets stripped,
   * a leading `schema.` qualifier dropped, lowercased) - or null if [sql] contains a FROM/JOIN this
   * scan cannot confidently resolve down to bare identifiers. Null is FAIL-CLOSED, not "no tables
   * read": a query shape this cannot parse is treated exactly like a query naming an unknown table -
   * see [rejectionReason], which rejects on a null result rather than reasoning about the query further.
   *
   * Handles the two ways SQL lists tables:
   * - `JOIN t2 ON ...` - one table per JOIN keyword ([JOIN_REGEX]), never a comma list, so only the
   *   identifier immediately after JOIN matters; a following alias word is simply outside the match.
   * - `FROM t1, t2, t3 ...` - old-style implicit join. [FROM_CLAUSE_REGEX] captures the WHOLE
   *   comma-separated span up to the next clause keyword (WHERE/GROUP/ORDER/HAVING/LIMIT/JOIN/UNION/
   *   INTERSECT/EXCEPT/WINDOW) or end of string; each comma-separated piece is then reduced to its OWN
   *   leading identifier via [LEADING_TABLE_TOKEN_REGEX], discarding any alias word after it. Without
   *   this comma-splitting, `FROM messages m, user_agents u` would only ever surface `messages` -
   *   exactly the kind of gap [containsForbiddenToken] exists to backstop, but worth closing here too:
   *   a second table that is merely unknown (not literally one of [FORBIDDEN_TABLES]) would otherwise
   *   slip past BOTH checks.
   *
   * Deliberately still simple: SQLite's real grammar handles derived tables, table-valued functions and
   * quoting far beyond what regex can fully cover, but this scan was never meant to accept everything
   * real SQLite does - only to recognize the shapes a model's straightforward analytical SELECT
   * actually takes, and to REFUSE, never guess, anything cleverer (a table-less/derived-only FROM like
   * `FROM (SELECT 1)` yields no leading identifier for its one "piece", which is exactly what makes the
   * overall scan fail closed instead of silently treating it as zero tables to check).
   *
   * Because [FROM_CLAUSE_REGEX]/[JOIN_REGEX] scan the WHOLE string for every occurrence rather than
   * parsing a single top-level statement tree, this naturally also walks into subqueries and UNION
   * branches - e.g. `SELECT * FROM (SELECT * FROM user_agents) x` still yields `user_agents` here, not
   * just the outer derived-table alias `x`.
   */
  private fun extractTables(sql: String): Set<String>? {
    val fromMatches = FROM_CLAUSE_REGEX.findAll(sql).toList()
    val joinMatches = JOIN_REGEX.findAll(sql).toList()
    if (fromMatches.isEmpty() && joinMatches.isEmpty()) return null

    val tables = mutableSetOf<String>()
    for (match in fromMatches) {
      for (piece in match.groupValues[1].split(',')) {
        val leading = LEADING_TABLE_TOKEN_REGEX.find(piece)?.groupValues?.get(1) ?: return null
        tables += normalizeTableToken(leading) ?: return null
      }
    }
    for (match in joinMatches) {
      tables += normalizeTableToken(match.groupValues[1]) ?: return null
    }
    return tables
  }

  /**
   * Strips a leading `schema.` qualifier and one layer of quoting (`` ` ``/`"`/`[...]`) off [raw],
   * then lowercases. Null if what remains isn't a bare identifier - fail-closed, see [extractTables].
   */
  private fun normalizeTableToken(raw: String): String? {
    val unqualified = raw.substringAfterLast('.')
    val unquoted = when {
      unqualified.length >= 2 && unqualified.first() == '`' && unqualified.last() == '`' ->
        unqualified.substring(1, unqualified.length - 1)
      unqualified.length >= 2 && unqualified.first() == '"' && unqualified.last() == '"' ->
        unqualified.substring(1, unqualified.length - 1)
      unqualified.length >= 2 && unqualified.first() == '[' && unqualified.last() == ']' ->
        unqualified.substring(1, unqualified.length - 1)
      else -> unqualified
    }
    return unquoted.takeIf { IDENTIFIER_REGEX.matches(it) }?.lowercase()
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

  private companion object {
    const val MAX_ROWS = 200
    val FORBIDDEN_SQL = setOf("drop", "create", "alter", "truncate", "grant", "revoke", "pragma")

    /**
     * Tables the model may SELECT from (Part A hardening, `user-agent-builder-848153`) - SRS/skill/plan
     * data. Deliberately an ALLOWLIST, not a denylist: a table added to the schema later and forgotten
     * here is unreachable by default, the safe failure direction.
     *
     * Per-agent-private data is deliberately ABSENT (cross-agent leak fix, `user-agent-builder-848153`):
     * `facts`/`episodes` are per-agent NAMESPACED memory and `messages`/`conversations`/`conversation_tags`
     * are conversation-private. A raw SQL SELECT here cannot restrict itself to the calling agent's
     * namespace/conversation the way [MemoryTools]/`ConversationRepository` do (they scope via
     * [com.aiopenpocket.agent.loop.TurnContextHolder]) - so an agent granted this tool would otherwise
     * read EVERY other agent's memory and the whole chat history. Those reads MUST go through the scoped
     * memory/conversation tools; they are not on this allowlist at all.
     */
    val READABLE_TABLES = setOf(
      "srs_words", "user_skills",
      "agent_plans", "plan_versions", "plan_step_states", "plan_evidence", "plan_events",
    )

    /**
     * System/control tables no SELECT may ever touch, regardless of [READABLE_TABLES] - `user_agents`
     * (agent definitions, INCLUDING system prompts), `agent_hooks`/`agent_hook_runs` (an agent's own
     * control-flow rules plus their firing journal), `heartbeats` (background schedule/targets). An
     * agent reading its own or another agent's control configuration through "just a SELECT" would
     * defeat the entire point of [READABLE_TABLES] being an allowlist - so this set is enforced TWICE,
     * independently: directly here via [containsForbiddenToken], and implicitly through
     * [extractTables]/[rejectionReason] simply never listing these names in [READABLE_TABLES] either.
     */
    val FORBIDDEN_TABLES = setOf("user_agents", "agent_hooks", "agent_hook_runs", "heartbeats")

    /** One (optionally schema-qualified, optionally quoted) SQL table identifier - see [extractTables]. */
    const val TABLE_TOKEN_PATTERN =
      """(?:[`"\[]?[A-Za-z_][A-Za-z0-9_]*[`"\]]?\s*\.\s*)?[`"\[]?[A-Za-z_][A-Za-z0-9_]*[`"\]]?"""

    /**
     * Everything after a `FROM` keyword up to the next clause keyword (or true end of input, `\z` -
     * used instead of `$` purely so this raw string never contains a bare `$`) - may itself be a
     * comma-separated table list; see [extractTables] for how each piece is reduced to its own table
     * name. `(?is)`: case-insensitive, and `.` spans newlines so a multi-line-formatted query's FROM
     * clause is still captured as one span.
     */
    val FROM_CLAUSE_REGEX = Regex(
      """(?is)\bFROM\s+(.+?)(?=\b(?:WHERE|GROUP|ORDER|HAVING|LIMIT|JOIN|UNION|INTERSECT|EXCEPT|WINDOW)\b|\z)""",
    )

    /** One table reference immediately following a JOIN keyword - see [extractTables]. */
    val JOIN_REGEX = Regex("""(?i)\bJOIN\s+($TABLE_TOKEN_PATTERN)""")

    /** The leading table token of one (possibly comma-separated FROM-list) piece - see [extractTables]. */
    val LEADING_TABLE_TOKEN_REGEX = Regex("""^\s*($TABLE_TOKEN_PATTERN)""")

    /** Plain (unquoted, unqualified) SQL identifier shape - see [normalizeTableToken]. */
    val IDENTIFIER_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
  }
}
