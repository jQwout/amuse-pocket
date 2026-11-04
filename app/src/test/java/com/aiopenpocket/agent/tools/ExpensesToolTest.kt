package com.aiopenpocket.agent.tools

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import com.aiopenpocket.agent.core.expense.ExpenseLogic
import com.aiopenpocket.agent.core.expense.ExpensePeriod
import com.aiopenpocket.agent.core.expense.ExpenseStore
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * The expense book shared by the Budget agent and its screen: what the tool writes must survive a
 * "restart" (a fresh [ExpenseStore] on the same files dir), the totals must add up per category, and
 * delete must be the only DESTRUCTIVE action. Amounts and notes are tokens only this test invents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExpensesToolTest {

  private val application = RuntimeEnvironment.getApplication()
  private val store = ExpenseStore(application)
  private val tool = ExpensesTool(store)

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { it.first to JsonPrimitive(it.second) }

  private fun ok(r: ToolResult): String {
    assertTrue("expected success, got $r", r is ToolResult.Success)
    return (r as ToolResult.Success).data
  }

  @Test
  fun `risk is WRITE for add and set_budget, DESTRUCTIVE for delete, READ otherwise`() {
    assertEquals(RiskClass.WRITE, tool.riskFor(args("action" to "add")))
    assertEquals(RiskClass.WRITE, tool.riskFor(args("action" to "set_budget")))
    assertEquals(RiskClass.DESTRUCTIVE, tool.riskFor(args("action" to "delete")))
    assertEquals(RiskClass.READ, tool.riskFor(args("action" to "list")))
    assertEquals(RiskClass.READ, tool.riskFor(args("action" to "summary")))
    assertEquals(RiskClass.READ, tool.riskFor(emptyMap()))
  }

  @Test
  fun `added expenses persist across a fresh store and sum per category`() = runTest {
    ok(tool.execute(args("action" to "add", "amount" to "12,5", "category" to "food", "note" to "lunch-7QX", "currency" to "rsd")))
    ok(tool.execute(args("action" to "add", "amount" to "30", "category" to "food")))
    ok(tool.execute(args("action" to "add", "amount" to "8", "category" to "bogus-category")))

    val reopened = ExpenseStore(application).also { it.load() }
    val rows = reopened.data.value.expenses
    assertEquals(3, rows.size)
    assertEquals("RSD", reopened.data.value.currency)
    assertEquals("lunch-7QX", rows.first { it.amount == 12.5 }.note)
    assertEquals("other", rows.first { it.amount == 8.0 }.category)

    val summary = ok(ExpensesTool(reopened).execute(args("action" to "summary")))
    assertTrue(summary, "total 50.50 RSD" in summary)
    assertTrue(summary, "- food: 42.50 RSD" in summary)
    assertTrue(summary, "- other: 8 RSD" in summary)
  }

  @Test
  fun `delete removes the record by id and reports an unknown id`() = runTest {
    val added = ok(tool.execute(args("action" to "add", "amount" to "5", "note" to "gone-Z9")))
    val id = Regex("id ([0-9a-f]{8})").find(added)!!.groupValues[1]

    ok(tool.execute(args("action" to "delete", "id" to id)))
    assertTrue(store.data.value.expenses.none { it.id == id })
    assertTrue(tool.execute(args("action" to "delete", "id" to id)) is ToolResult.Error)
  }

  @Test
  fun `set_budget shows up in the summary and zero clears it`() = runTest {
    ok(tool.execute(args("action" to "set_budget", "amount" to "400")))
    ok(tool.execute(args("action" to "add", "amount" to "100")))
    assertTrue(ok(tool.execute(args("action" to "summary"))).contains("left: 300"))
    ok(tool.execute(args("action" to "set_budget", "amount" to "0")))
    assertTrue(ok(tool.execute(args("action" to "summary"))).contains("No monthly budget"))
  }

  @Test
  fun `bad input is rejected and writes nothing`() = runTest {
    assertTrue(tool.execute(args("action" to "add", "amount" to "-3")) is ToolResult.Error)
    assertTrue(tool.execute(args("action" to "add", "amount" to "abc")) is ToolResult.Error)
    assertTrue(tool.execute(args("action" to "add", "amount" to "4", "date" to "yesterday")) is ToolResult.Error)
    assertTrue(tool.execute(args("action" to "nope")) is ToolResult.Error)
    store.load()
    assertTrue(store.data.value.expenses.isEmpty())
    assertTrue(File(application.filesDir, ExpenseStore.FILE_NAME).let { !it.exists() || it.readText().contains("\"expenses\":[]") })
  }

  @Test
  fun `periods are cut at local midnight and weeks start on Monday`() {
    val zone = ZoneId.of("Europe/Belgrade")
    fun at(d: String) = LocalDate.parse(d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val now = at("2026-11-04") // a Wednesday
    val week = ExpenseLogic.range(ExpensePeriod.WEEK, now, zone)
    assertTrue(at("2026-11-02") in week)
    assertTrue(at("2026-11-08") in week)
    assertTrue(at("2026-11-01") !in week)
    assertTrue(at("2026-11-09") !in week)
    val month = ExpenseLogic.range(ExpensePeriod.MONTH, now, zone)
    assertTrue(at("2026-11-30") in month)
    assertTrue(at("2026-10-31") !in month)
    val today = ExpenseLogic.range(ExpensePeriod.TODAY, now, zone)
    assertTrue(at("2026-11-04") in today && at("2026-11-05") !in today)
  }
}
