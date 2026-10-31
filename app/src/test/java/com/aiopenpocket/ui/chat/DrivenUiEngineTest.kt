package com.aiopenpocket.ui.chat

import com.aiopenpocket.agent.core.llm.SegmentBox
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
  fun `image card keeps the lesson prompt for draw-it-for-me`() {
    val card = DrivenUiEngine.parseJsonString(
      """{"template":"image","params":{"asset":"a.webp","caption":"c","image_prompt":"A robot."}}""",
    )
    assertEquals(DrivenUiCard.ImageCard(asset = "a.webp", url = "", caption = "c", prompt = "A robot."), card)

    val plain = DrivenUiEngine.parseJsonString("""{"template":"image","params":{"asset":"a.webp"}}""")
    assertEquals("", (plain as DrivenUiCard.ImageCard).prompt)
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
        "primary_action":{"type":"open_agent_builder","label":"Открыть конструктор"}}}
    """.trimIndent()

    val card = DrivenUiEngine.parseJsonString(spec)
    assertTrue(card is DrivenUiCard.LessonCompleteCard)
    card as DrivenUiCard.LessonCompleteCard
    assertEquals("open_agent_builder", card.primaryAction?.type)
    assertEquals("Открыть конструктор", card.primaryAction?.label)
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
    val spec = """{"template":"lesson_complete","params":{"primary_action":{"label":"Открыть конструктор"}}}"""

    val card = DrivenUiEngine.parseJsonString(spec)
    assertTrue(card is DrivenUiCard.ErrorCard)
  }

  // --- the "segment" lesson step: SAM 3.1 outlines objects in a photo ---

  @Test
  fun `lesson_segment carries the instruction, the default prompt and the sample photo`() {
    val spec = """{"template":"lesson_segment","params":{"instruction":"Назови предмет","segment_prompt":"cup","sample_image":"lessons/videos/sample_desk.webp"}}"""

    val card = DrivenUiEngine.parseJsonString(spec)

    assertEquals(DrivenUiCard.LessonSegmentCard("Назови предмет", "cup", "lessons/videos/sample_desk.webp"), card)
  }

  @Test
  fun `lesson_segment_result parses the boxes with the size the model reported`() {
    val spec = """{"template":"lesson_segment_result","params":{
      "image_url":"file:///cache/segment-1.jpg","image_w":1516,"image_h":1600,"concept":"cup","total":2,
      "boxes":[
        {"id":"0","x1":181,"y1":365,"x2":556,"y2":486,"w":1516,"h":1600,"c":0.96},
        {"id":"10","x1":1,"y1":2,"x2":3,"y2":4,"w":1516,"h":1600}
      ]}}""".trimIndent()

    val card = DrivenUiEngine.parseJsonString(spec) as DrivenUiCard.LessonSegmentResultCard

    assertEquals("file:///cache/segment-1.jpg", card.imageUrl)
    assertEquals(1516, card.imageWidth)
    assertEquals(1600, card.imageHeight)
    assertEquals("cup", card.concept)
    assertEquals(2, card.total)
    assertEquals(listOf("0", "10"), card.boxes.map { it.id })
    assertEquals(SegmentBox(181, 365, 556, 486, 1516, 1600, 0.96), card.boxes[0].box)
    // A box with no confidence stays without one: it is not 0.
    assertEquals(null, card.boxes[1].box.confidence)
  }

  /** "Nothing found" is an answer: the card still parses, with no boxes. */
  @Test
  fun `lesson_segment_result with no boxes is a card, not an error`() {
    val spec = """{"template":"lesson_segment_result","params":{"image_url":"file:///a.jpg","image_w":640,"image_h":427,"concept":"unicorn","total":0,"boxes":[]}}"""

    val card = DrivenUiEngine.parseJsonString(spec) as DrivenUiCard.LessonSegmentResultCard

    assertTrue(card.boxes.isEmpty())
    assertEquals(0, card.total)
  }

  /** A box with no picture size cannot be scaled (the scale divides by it): it is dropped rather than drawn wrong. */
  @Test
  fun `a box without a picture size is dropped`() {
    val spec = """{"template":"lesson_segment_result","params":{"image_url":"file:///a.jpg","image_w":640,"image_h":427,"concept":"cup",
      "boxes":[{"id":"0","x1":1,"y1":2,"x2":3,"y2":4},{"id":"1","x1":1,"y1":2,"x2":3,"y2":4,"w":640,"h":427}]}}""".trimIndent()

    val card = DrivenUiEngine.parseJsonString(spec) as DrivenUiCard.LessonSegmentResultCard

    assertEquals(listOf("1"), card.boxes.map { it.id })
  }

  @Test
  fun `the segment cards have a text form for a chat without widgets`() {
    val step = DrivenUiCard.LessonSegmentCard("Назови предмет", "cup")
    val result = DrivenUiCard.LessonSegmentResultCard("file:///a.jpg", 640, 427, "cup", 3, emptyList())

    assertTrue(step.toMarkdown().contains("Назови предмет"))
    assertTrue(result.toMarkdown().contains("cup") && result.toMarkdown().contains("3"))
  }

  // --- multi_choice / agent_review (the agent builder chat) ---

  @Test
  fun `multi_choice parses options, preselected ticks and the bounds`() {
    val spec = """{"template":"multi_choice","params":{"question":"What may it use?",
      "options":["Memory","Reminders","Calendar"],"preselected":[0,2],"min":1,"max":3}}"""

    val card = DrivenUiEngine.parseJsonString(spec) as DrivenUiCard.MultiChoiceCard

    assertEquals("What may it use?", card.question)
    assertEquals(listOf("Memory", "Reminders", "Calendar"), card.options)
    assertEquals(setOf(0, 2), card.preselected)
    assertEquals(1, card.min)
    assertEquals(3, card.max)
  }

  /** The model fills the numbers freely; a card whose "Next" can never be enabled would dead-end the chat. */
  @Test
  fun `multi_choice repairs bounds and drops ticks that point nowhere`() {
    val spec = """{"template":"multi_choice","params":{"question":"q","options":["a","b","c"],
      "preselected":[1,1,9,-1],"min":7,"max":0}}"""

    val card = DrivenUiEngine.parseJsonString(spec) as DrivenUiCard.MultiChoiceCard

    assertEquals(3, card.min) // clamped to the option count, so "Next" can be reached
    assertEquals(3, card.max)
    assertEquals(setOf(1), card.preselected) // duplicate, out-of-range and negative ticks are gone
  }

  @Test
  fun `multi_choice never preselects more than max`() {
    val spec = """{"template":"multi_choice","params":{"question":"q","options":["a","b","c"],
      "preselected":[0,1,2],"min":1,"max":2}}"""

    val card = DrivenUiEngine.parseJsonString(spec) as DrivenUiCard.MultiChoiceCard

    assertEquals(setOf(0, 1), card.preselected)
  }

  @Test
  fun `multi_choice with fewer than two options is an error card, not a question`() {
    val spec = """{"template":"multi_choice","params":{"question":"q","options":["only one"]}}"""

    assertTrue(DrivenUiEngine.parseJsonString(spec) is DrivenUiCard.ErrorCard)
  }

  @Test
  fun `agent_review parses the agent id and name`() {
    val spec = """{"template":"agent_review","params":{"agent_id":"my-agent-1","name":"Budget buddy"}}"""

    val card = DrivenUiEngine.parseJsonString(spec) as DrivenUiCard.AgentReviewCard

    assertEquals("my-agent-1", card.agentId)
    assertEquals("Budget buddy", card.name)
  }

  @Test
  fun `agent_review without an agent id is an error card`() {
    val spec = """{"template":"agent_review","params":{"name":"x"}}"""

    assertTrue(DrivenUiEngine.parseJsonString(spec) is DrivenUiCard.ErrorCard)
  }

  /** What the tool's finish result looks like to the parser: extra keys for the model must not break it. */
  @Test
  fun `the draft tool finish result parses as the review card`() {
    val finishResult = """{"template":"agent_review","params":{"agent_id":"my-agent-7","name":"N"},
      "status":"ready","next":"Tell the person it is ready."}"""

    assertTrue(DrivenUiEngine.parseJsonString(finishResult) is DrivenUiCard.AgentReviewCard)
  }
}
