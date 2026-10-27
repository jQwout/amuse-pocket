package com.aiopenpocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The activity is exported: a conversation id from someone else's intent must not reach
 * `navigate("chat/$id")` with a "/" or in any format other than a UUID, which is what ConversationRepository
 * creates conversations with.
 */
class MainActivityIntentTest {

  @Test
  fun `a uuid conversation id passes`() {
    val id = "3f1c2a9e-8b7d-4e6f-9a0b-1c2d3e4f5a6b"
    assertEquals(id, MainActivity.validConversationIdOrNull(id))
    assertEquals(id, MainActivity.validConversationIdOrNull("  $id\n"))
  }

  @Test
  fun `anything that is not a uuid is dropped`() {
    assertNull(MainActivity.validConversationIdOrNull(null))
    assertNull(MainActivity.validConversationIdOrNull(""))
    assertNull(MainActivity.validConversationIdOrNull("new"))
    assertNull(MainActivity.validConversationIdOrNull("a/b"))
    assertNull(MainActivity.validConversationIdOrNull("../settings"))
    assertNull(MainActivity.validConversationIdOrNull("3f1c2a9e-8b7d-4e6f-9a0b-1c2d3e4f5a6b/extra"))
  }
}
