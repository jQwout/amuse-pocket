package com.aiopenpocket.agent.core.expense

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** The fixed set of categories: the same ids in the tool's enum, in the file and on the screen. */
enum class ExpenseCategory(val id: String) {
  FOOD("food"),
  TRANSPORT("transport"),
  HOME("home"),
  FUN("fun"),
  HEALTH("health"),
  SHOPPING("shopping"),
  OTHER("other"),
  ;

  companion object {
    val ids: List<String> = entries.map { it.id }

    /** Unknown or missing ids land in OTHER: a model's slightly-off category must not lose the spend. */
    fun fromId(id: String?): ExpenseCategory =
      entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: OTHER
  }
}

@Serializable
data class Expense(
  val id: String,
  val amount: Double,
  val currency: String,
  val category: String,
  val note: String = "",
  /** Epoch millis of the day the money was spent. */
  val date: Long,
) {
  val categoryEnum: ExpenseCategory get() = ExpenseCategory.fromId(category)
}

/** Everything the file holds: the records, the optional monthly limit and the currency last used. */
@Serializable
data class ExpenseData(
  val expenses: List<Expense> = emptyList(),
  /** Monthly limit in [currency]; null = none set. */
  val monthlyBudget: Double? = null,
  val currency: String = ExpenseLogic.DEFAULT_CURRENCY,
)

enum class ExpensePeriod(val id: String) {
  TODAY("today"),
  WEEK("week"),
  MONTH("month"),
  ;

  companion object {
    fun fromId(id: String?): ExpensePeriod? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
  }
}

/** Pure arithmetic over expenses, kept apart from the store and the tool so JVM tests can pin it. */
object ExpenseLogic {
  const val DEFAULT_CURRENCY = "EUR"

  private fun LocalDate.startMillis(zone: ZoneId) = atStartOfDay(zone).toInstant().toEpochMilli()

  /** Millis range of [period] as seen from [now] in [zone] (week starts on Monday). */
  fun range(period: ExpensePeriod, now: Long, zone: ZoneId): LongRange {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (period) {
      ExpensePeriod.TODAY -> today.startMillis(zone) until today.plusDays(1).startMillis(zone)
      ExpensePeriod.WEEK -> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        monday.startMillis(zone) until monday.plusDays(7).startMillis(zone)
      }
      ExpensePeriod.MONTH -> monthRange(YearMonth.from(today), zone)
    }
  }

  fun monthRange(month: YearMonth, zone: ZoneId): LongRange =
    month.atDay(1).startMillis(zone) until month.plusMonths(1).atDay(1).startMillis(zone)

  /** Expenses inside [range], newest first. */
  fun inRange(expenses: List<Expense>, range: LongRange): List<Expense> =
    expenses.filter { it.date in range }.sortedByDescending { it.date }

  /** Spend per category, biggest first; categories with nothing spent are left out. */
  fun byCategory(expenses: List<Expense>): List<Pair<ExpenseCategory, Double>> =
    expenses.groupBy { it.categoryEnum }
      .map { (cat, list) -> cat to list.sumOf { it.amount } }
      .sortedByDescending { it.second }

  /** Amounts without a trailing ".0": 12.0 -> "12", 12.5 -> "12.50". */
  fun money(amount: Double): String =
    if (amount == Math.floor(amount) && amount < 1e12) amount.toLong().toString() else String.format(Locale.US, "%.2f", amount)
}
