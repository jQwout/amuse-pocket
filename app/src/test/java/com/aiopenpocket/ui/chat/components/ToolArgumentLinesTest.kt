package com.aiopenpocket.ui.chat.components

// @trace mcp-tools-f77d9b
// @trace english-ui-on-the-demo-path-223241

import com.aiopenpocket.i18n.AppLanguage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the approval bar says about a call it asks permission for.
 *
 * On a device (orchestrator/evidence/M3) the card for an email draft showed `body: Hi Masha, …` and
 * nothing else: the recipient and the subject were below the cut. EXTERNAL always asks
 * ([com.aiopenpocket.agent.core.policy.ExecutionPolicy]), and asking without the facts is worse than
 * not asking at all.
 */
class ToolArgumentLinesTest {

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    linkedMapOf(*pairs.map { (k, v) -> k to (JsonPrimitive(v) as JsonElement) }.toTypedArray())

  /** The model's own order — body first, as it arrived in the evidence — must not decide the screen. */
  @Test
  fun `the recipient and the subject come before the letter`() {
    AppLanguage.code = AppLanguage.EN
    val lines = toolArgumentLines(
      "email",
      args(
        "action" to "compose",
        "body" to "Hi Masha, here are the notes from our meetings this week.",
        "to" to "masha@example.com",
        "subject" to "Meeting notes",
      ),
    )
    assertEquals(listOf("To", "Subject", "Body"), lines.map { it.label })
    assertEquals("masha@example.com", lines[0].value)
    assertEquals("Meeting notes", lines[1].value)
  }

  /** The title says the action already; repeating `compose` under it tells the reader nothing. */
  @Test
  fun `the action and a skill id are not repeated under the title`() {
    val email = toolArgumentLines("email", args("action" to "compose", "to" to "a@b.c"))
    assertEquals(1, email.size)
    val skill = toolArgumentLines("load_skill", args("action" to "load", "id" to "budget"))
    assertTrue("load_skill's id is already in the title: $skill", skill.isEmpty())
    // The same `id` in another tool is an ordinary argument and stays.
    assertEquals(1, toolArgumentLines("storage_tool", args("id" to "42")).size)
  }

  /**
   * A tool that is not about sending anything: nothing is dropped and nothing is reordered beyond the
   * rule — `term` is what the call is about, the rest keeps the model's own order, and an argument with
   * no label of its own (`lang`) is still shown.
   */
  @Test
  fun `a tool without a recipient shows every argument`() {
    AppLanguage.code = AppLanguage.EN
    val lines = toolArgumentLines(
      "srs_tool",
      args("action" to "add_word", "lang" to "de", "term" to "Haus", "translation" to "house"),
    )
    assertEquals(listOf("Word", "lang", "Translation"), lines.map { it.label })
    assertEquals(listOf("Haus", "de", "house"), lines.map { it.value })
  }

  /** The body arrives with line breaks; four of its own lines used to push everything else off the card. */
  @Test
  fun `a value is flattened to one line and a long one is cut`() {
    AppLanguage.code = AppLanguage.EN
    val body = "Hi Masha,\n\nhere are the notes.\n\n" + "and more ".repeat(60)
    val line = toolArgumentLines("email", args("body" to body)).single()
    assertTrue("line breaks survived: ${line.value}", !line.value.contains("\n"))
    assertTrue("nothing was cut off a very long body", line.value.endsWith("…"))
    assertTrue("cut too late: ${line.value.length}", line.value.length <= 201)
  }

  /** Labels are interface text, so they follow the language at the moment the card is drawn. */
  @Test
  fun `labels follow the interface language`() {
    val arguments = args("to" to "a@b.c", "subject" to "s", "body" to "b")
    AppLanguage.code = AppLanguage.RU
    assertEquals(listOf("Кому", "Тема", "Письмо"), toolArgumentLines("email", arguments).map { it.label })
    AppLanguage.code = AppLanguage.EN
    assertEquals(listOf("To", "Subject", "Body"), toolArgumentLines("email", arguments).map { it.label })
  }

  /** One argument per line: a long body cannot swallow the line above it. */
  @Test
  fun `the text block puts every argument on its own line`() {
    AppLanguage.code = AppLanguage.EN
    val text = formatToolArguments("email", args("body" to "Hi Masha", "to" to "masha@example.com"))
    assertEquals("To: masha@example.com\nBody: Hi Masha", text)
  }

  @Test
  fun `no arguments means no block at all`() {
    assertEquals("", formatToolArguments("email", args("action" to "compose")))
  }
}
