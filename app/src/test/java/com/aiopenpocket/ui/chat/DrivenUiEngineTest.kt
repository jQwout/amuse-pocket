package com.aiopenpocket.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric provides a real org.json implementation (stubbed in plain JVM unit tests).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DrivenUiEngineTest {

  @Test
  fun `parses todo_list with items`() {
    val spec = """
      {"template":"todo_list","params":{"title":"Дела","items":[
        {"item_text":"Молоко","item_done":false},
        {"item_text":"Хлеб","item_done":true}
      ]}}
    """.trimIndent()

    val card = DrivenUiEngine.parseJsonString(spec)
    assertTrue(card is DrivenUiCard.TodoList)
    card as DrivenUiCard.TodoList
    assertEquals("Дела", card.title)
    assertEquals(2, card.items.size)
    assertEquals("Молоко", card.items[0].text)
    assertEquals(true, card.items[1].done)
  }

  @Test
  fun `parses chart_bar values`() {
    val spec = """
      {"template":"chart_bar","params":{"title":"Расходы","labels":["Янв","Фев"],"values":[10,25]}}
    """.trimIndent()

    val card = DrivenUiEngine.parseJsonString(spec)
    assertTrue(card is DrivenUiCard.ChartBarCard)
    card as DrivenUiCard.ChartBarCard
    assertEquals(listOf("Янв", "Фев"), card.labels)
    assertEquals(2, card.values.size)
    assertEquals(25f, card.values[1])
  }

  @Test
  fun `unknown template yields error card`() {
    val card = DrivenUiEngine.parseJsonString("""{"template":"nope","params":{}}""")
    assertTrue(card is DrivenUiCard.ErrorCard)
  }

  @Test
  fun `malformed json yields error card`() {
    val card = DrivenUiEngine.parseJsonString("not json at all {")
    assertTrue(card is DrivenUiCard.ErrorCard)
  }

  @Test
  fun `timer uses defaults for missing fields`() {
    val card = DrivenUiEngine.parseJsonString("""{"template":"timer","params":{}}""")
    assertTrue(card is DrivenUiCard.TimerCard)
    card as DrivenUiCard.TimerCard
    assertEquals(60, card.durationSec)
  }
}
