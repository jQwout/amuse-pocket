package com.aiopenpocket.agent.core.expense

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import android.app.Application
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * The one expense book of the Budget agent AND the Budget screen: the `expenses` tool writes here, the
 * screen watches [data], so what the person says in the chat and what the app shows never disagree.
 *
 * A JSON file in `filesDir`, not Room: before the first release a schema bump is a destructive
 * migration that would wipe the owner's phone data. Writes go through a temp file and a rename so a
 * crash mid-write leaves the previous book intact; a [Mutex] serializes the read-modify-write cycles
 * (the tool and the screen can write at the same moment).
 */
@SingleIn(AppScope::class)
@Inject
class ExpenseStore(private val application: Application) {

  private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
  private val mutex = Mutex()
  private val _data = MutableStateFlow(ExpenseData())
  private var loaded = false

  val data: StateFlow<ExpenseData> = _data.asStateFlow()

  private fun file() = File(application.filesDir, FILE_NAME)

  /** Reads the file once. A damaged file is treated as an empty book rather than crashing the screen. */
  suspend fun load() {
    mutex.withLock { ensureLoaded() }
  }

  private suspend fun ensureLoaded() {
    if (loaded) return
    val read = withContext(Dispatchers.IO) {
      val f = file()
      if (!f.isFile) ExpenseData() else runCatching { json.decodeFromString<ExpenseData>(f.readText()) }.getOrElse { ExpenseData() }
    }
    _data.value = read
    loaded = true
  }

  private suspend fun mutate(change: (ExpenseData) -> ExpenseData) {
    mutex.withLock {
      ensureLoaded()
      val next = change(_data.value)
      persist(next)
      _data.value = next
    }
  }

  private suspend fun persist(data: ExpenseData) = withContext(Dispatchers.IO) {
    val target = file()
    val tmp = File(target.parentFile, "$FILE_NAME.tmp")
    tmp.writeText(json.encodeToString(data))
    if (!tmp.renameTo(target)) {
      target.writeText(tmp.readText())
      tmp.delete()
    }
  }

  /** Adds a record; a currency that differs from the book's becomes the new default for the next one. */
  suspend fun add(
    amount: Double,
    category: ExpenseCategory,
    note: String,
    date: Long,
    currency: String? = null,
  ): Expense {
    var created: Expense? = null
    mutate { d ->
      val cur = currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: d.currency
      val e = Expense(UUID.randomUUID().toString().take(8), amount, cur, category.id, note.trim(), date)
      created = e
      d.copy(expenses = d.expenses + e, currency = cur)
    }
    return created!!
  }

  suspend fun update(expense: Expense) {
    mutate { d -> d.copy(expenses = d.expenses.map { if (it.id == expense.id) expense else it }) }
  }

  /** True when a record with [id] existed. */
  suspend fun delete(id: String): Boolean {
    var removed = false
    mutate { d ->
      removed = d.expenses.any { it.id == id }
      d.copy(expenses = d.expenses.filterNot { it.id == id })
    }
    return removed
  }

  /** [amount] null or <= 0 clears the limit. */
  suspend fun setBudget(amount: Double?) {
    mutate { d -> d.copy(monthlyBudget = amount?.takeIf { it > 0 }) }
  }

  companion object {
    const val FILE_NAME = "expenses.json"
  }
}
