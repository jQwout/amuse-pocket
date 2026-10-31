package com.aiopenpocket.ui.profile

// @trace profile-screen-7354a7

import com.aiopenpocket.agent.core.memory.model.Fact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure part of the remembered-facts list: which facts are shown, in which group, under which caption. */
class ProfileFactsTest {

  private fun fact(key: String, category: String) = Fact("id-$key", key, "v", category, 1L, 1L)

  @Test
  fun `the six profile rows are fields above, not remembered facts`() {
    val rows = listOf(
      "user.name", "user.preferred_address", "user.age", "user.gender", "user.religion", "user.extra_info",
    ).map { fact(it, "profile") }

    assertTrue(groupUserFacts(rows).isEmpty())
  }

  @Test
  fun `groups follow the category, empty groups are left out, order is fixed`() {
    val sections = groupUserFacts(
      listOf(fact("a", "general"), fact("b", "preference"), fact("c", "profile"), fact("d", "whatever")),
    )

    assertEquals(
      listOf(FactGroup.ABOUT_YOU, FactGroup.PREFERENCES, FactGroup.OTHER),
      sections.map { it.group },
    )
    // An unknown category is "other", not lost.
    assertEquals(listOf("a", "d"), sections.last().facts.map { it.key })
  }

  @Test
  fun `captions read as words, not as keys`() {
    assertEquals("favorite food", factLabel("user.favorite_food"))
    assertEquals("family sister", factLabel("family.sister"))
    assertEquals("user.", factLabel("user."))
  }
}
