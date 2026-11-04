package com.aiopenpocket.agent.tools

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import com.aiopenpocket.agent.core.expense.ExpenseCategory
import com.aiopenpocket.agent.core.expense.ExpenseLogic
import com.aiopenpocket.agent.core.expense.ExpensePeriod
import com.aiopenpocket.agent.core.expense.ExpenseStore
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The Budget agent's hand on the expense book it shares with the Budget screen ([ExpenseStore]): what it
 * records here the person sees in the app at once, and what the person adds or deletes in the app the
 * agent sees in its next `list` or `summary`. The book is global to the app, not per agent: it is the
 * person's money, and the only agent that carries this tool is Budget.
 *
 * Per-call risk: add and set_budget WRITE, delete DESTRUCTIVE (a record is gone, no trash), the rest READ.
 * An unknown or missing action fails closed to READ - execute() rejects it before anything runs.
 */
@Inject
@SingleIn(AppScope::class)
class ExpensesTool(
  private val store: ExpenseStore,
) : Tool {

  // Notes are text the model took from the person or from an email: treat like any stored external text.
  override val returnsUntrustedContent = true

  override val name = "expenses"
  override val description =
    "Учёт расходов человека, общий с экраном приложения «Бюджет»: add — записать трату, " +
    "list — показать траты за today/week/month, summary — суммы по категориям за месяц, " +
    "delete — удалить трату по id, set_budget — задать месячный лимит."
  override val schema = toolSchema(name, description) {
    string(
      "action", "Действие: add, list, summary, delete, set_budget", required = true,
      enum = listOf("add", "list", "summary", "delete", "set_budget"),
    )
    string("amount", "Сумма (для add; для set_budget — лимит на месяц, 0 снимает лимит)")
    string("category", "Категория (для add)", enum = ExpenseCategory.ids)
    string("note", "Что куплено, коротко (для add)")
    string("date", "Дата траты YYYY-MM-DD (для add; по умолчанию сегодня)")
    string("currency", "Код валюты, например EUR или RSD (для add; по умолчанию как в прошлой трате)")
    string("period", "Период для list: today, week, month (по умолчанию month)", enum = ExpensePeriod.entries.map { it.id })
    string("month", "Месяц для summary в виде YYYY-MM (по умолчанию текущий)")
    string("id", "Идентификатор траты из list (для delete)")
  }

  override val riskClass = RiskClass.DESTRUCTIVE

  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = when (arguments.string("action")) {
    "add", "set_budget" -> RiskClass.WRITE
    "delete" -> RiskClass.DESTRUCTIVE
    else -> RiskClass.READ
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult =
    when (val action = arguments.string("action") ?: return missingArg("action")) {
      "add" -> add(arguments)
      "list" -> list(arguments)
      "summary" -> summary(arguments)
      "delete" -> delete(arguments)
      "set_budget" -> setBudget(arguments)
      else -> ToolResult.Error("Unknown action: $action")
    }

  private suspend fun add(args: Map<String, JsonElement>): ToolResult {
    val amount = args.number("amount") ?: return missingArg("amount")
    if (amount <= 0.0 || amount > MAX_AMOUNT) return ToolResult.Error("'amount' must be a positive number below $MAX_AMOUNT.")
    val date = args.string("date")?.let { parseDate(it) ?: return ToolResult.Error("'date' must be YYYY-MM-DD.") }
      ?: System.currentTimeMillis()
    val expense = store.add(
      amount = amount,
      category = ExpenseCategory.fromId(args.string("category")),
      note = args.string("note").orEmpty().take(MAX_NOTE_CHARS),
      date = date,
      currency = args.string("currency")?.take(MAX_CURRENCY_CHARS),
    )
    return ToolResult.Success(
      "Recorded: ${ExpenseLogic.money(expense.amount)} ${expense.currency}, ${expense.category}" +
        (if (expense.note.isNotEmpty()) ", \"${expense.note}\"" else "") + " (id ${expense.id}). The person sees it in the Budget app.",
    )
  }

  private suspend fun list(args: Map<String, JsonElement>): ToolResult {
    store.load()
    val period = if (args.string("period") == null) ExpensePeriod.MONTH else ExpensePeriod.fromId(args.string("period"))
      ?: return ToolResult.Error("'period' must be today, week or month.")
    val data = store.data.value
    val rows = ExpenseLogic.inRange(data.expenses, ExpenseLogic.range(period, System.currentTimeMillis(), zone()))
    if (rows.isEmpty()) return ToolResult.Success("No expenses for ${period.id}.")
    val shown = rows.take(MAX_LIST_ROWS)
    val lines = shown.joinToString("\n") {
      val day = java.time.Instant.ofEpochMilli(it.date).atZone(zone()).toLocalDate()
      "- ${it.id}: $day ${ExpenseLogic.money(it.amount)} ${it.currency} ${it.category}" + (if (it.note.isNotEmpty()) " \"${it.note}\"" else "")
    }
    val more = if (rows.size > shown.size) "\n(${rows.size - shown.size} older not shown)" else ""
    return ToolResult.Success(lines + more)
  }

  private suspend fun summary(args: Map<String, JsonElement>): ToolResult {
    store.load()
    val month = args.string("month")?.let { runCatching { YearMonth.parse(it) }.getOrNull() ?: return ToolResult.Error("'month' must be YYYY-MM.") }
      ?: YearMonth.now(zone())
    val data = store.data.value
    val rows = ExpenseLogic.inRange(data.expenses, ExpenseLogic.monthRange(month, zone()))
    val limit = data.monthlyBudget
    val limitLine = limit?.let { "Monthly budget: ${ExpenseLogic.money(it)} ${data.currency}, left: ${ExpenseLogic.money(it - rows.sumOf { e -> e.amount })} ${data.currency}." }
      ?: "No monthly budget set."
    if (rows.isEmpty()) return ToolResult.Success("No expenses in $month. $limitLine")
    val total = rows.sumOf { it.amount }
    val byCat = ExpenseLogic.byCategory(rows).joinToString("\n") { (cat, sum) -> "- ${cat.id}: ${ExpenseLogic.money(sum)} ${data.currency}" }
    return ToolResult.Success("Month $month: total ${ExpenseLogic.money(total)} ${data.currency} in ${rows.size} records.\n$byCat\n$limitLine")
  }

  private suspend fun delete(args: Map<String, JsonElement>): ToolResult {
    val id = args.string("id") ?: return missingArg("id")
    return if (store.delete(id)) ToolResult.Success("Deleted $id.") else ToolResult.Error("No expense with id '$id'. Call list to see the ids.")
  }

  private suspend fun setBudget(args: Map<String, JsonElement>): ToolResult {
    val amount = args.number("amount") ?: return missingArg("amount")
    if (amount < 0.0 || amount > MAX_AMOUNT) return ToolResult.Error("'amount' must be 0 or a positive number below $MAX_AMOUNT.")
    store.setBudget(amount)
    return ToolResult.Success(if (amount == 0.0) "Monthly budget cleared." else "Monthly budget set to ${ExpenseLogic.money(amount)}.")
  }

  private fun zone(): ZoneId = ZoneId.systemDefault()

  private fun parseDate(raw: String): Long? =
    runCatching { LocalDate.parse(raw.trim()).atStartOfDay(zone()).toInstant().toEpochMilli() }.getOrNull()

  companion object {
    const val MAX_AMOUNT = 1e9
    private const val MAX_NOTE_CHARS = 200
    private const val MAX_CURRENCY_CHARS = 8
    private const val MAX_LIST_ROWS = 50
  }
}

/** A number the model may send as 12, "12", "12.5" or "12,5". */
internal fun Map<String, JsonElement>.number(key: String): Double? =
  (this[key] as? JsonPrimitive)?.content?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }
