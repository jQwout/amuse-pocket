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

  @Test
  fun `parses reminder card`() {
    val spec = """{"template":"reminder","params":{"heartbeat_id":"hb42"}}"""
    val card = DrivenUiEngine.parseJsonString(spec)
    assertTrue(card is DrivenUiCard.ReminderCard)
    assertEquals("hb42", (card as DrivenUiCard.ReminderCard).heartbeatId)
  }

  @Test
  fun `reminder card without id is error card`() {
    val card = DrivenUiEngine.parseJsonString("""{"template":"reminder","params":{}}""")
    assertTrue(card is DrivenUiCard.ErrorCard)
  }

  // --- Task 23: lesson_complete's optional primary_action (AI Academy capstone -> agent builder CTA) ---

  @Test
  fun `lesson_complete parses open_agent_builder primary_action`() {
    // Mirrors exactly what LessonChatController.postCompletion emits for the agent_blueprint capstone.
    val spec = """
      {"template":"lesson_complete","params":{"title":"Урок «Капстоун» пройден!","subtitle":"Отличная работа.","button_label":"Готово",
        "primary_action":{"type":"open_agent_builder","label":"Собрать этого агента"}}}
    """.trimIndent()

    val card = DrivenUiEngine.parseJsonString(spec)
    assertTrue(card is DrivenUiCard.LessonCompleteCard)
    card as DrivenUiCard.LessonCompleteCard
    assertEquals("open_agent_builder", card.primaryAction?.type)
    assertEquals("Собрать этого агента", card.primaryAction?.label)
  }

  @Test
  fun `lesson_complete without primary_action has none (ordinary lesson completion is unchanged)`() {
    // Mirrors what postCompletion emits for every lesson OTHER than the capstone: no primary_action
    // key at all. This is the capstone-only gate observed from the card's own shape - the CTA only
    // ever appears when the producer explicitly put it there.
    val spec = """{"template":"lesson_complete","params":{"title":"Урок «Токены» пройден!","button_label":"Готово"}}"""

    val card = DrivenUiEngine.parseJsonString(spec)
    assertTrue(card is DrivenUiCard.LessonCompleteCard)
    assertEquals(null, (card as DrivenUiCard.LessonCompleteCard).primaryAction)
  }

  @Test
  fun `lesson_complete with unknown primary_action type is rejected`() {
    val spec = """{"template":"lesson_complete","params":{"primary_action":{"type":"delete_everything","label":"?"}}}"""

    val card = DrivenUiEngine.parseJsonString(spec)
    assertTrue(card is DrivenUiCard.ErrorCard)
  }

  @Test
  fun `lesson_complete primary_action missing type is rejected`() {
    val spec = """{"template":"lesson_complete","params":{"primary_action":{"label":"Собрать этого агента"}}}"""

    val card = DrivenUiEngine.parseJsonString(spec)
    assertTrue(card is DrivenUiCard.ErrorCard)
  }
}
