package com.aiopenpocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Активити экспортирована: id диалога из чужого интента не должен доехать до `navigate("chat/$id")`
 * ни с «/», ни в каком-либо формате, кроме UUID, которым ConversationRepository и создаёт диалоги.
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
