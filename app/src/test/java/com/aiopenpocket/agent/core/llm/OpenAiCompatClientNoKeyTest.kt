package com.aiopenpocket.agent.core.llm

// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.i18n.AppLanguage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The HTTP client's last-resort answer to "no Meta key" (the turn gate in `TurnManager` and the heartbeat
 * check come first; this covers everything that reaches the client without passing through them, such as
 * the conversation annotator or the profile parsing in onboarding).
 *
 * No key is bundled into any build, so a fresh install has none. What the person must get is the plain
 * "add your key" message, and Meta must not be contacted at all: a keyless request is a guaranteed
 * HTTP 401, and the raw 401 JSON is exactly what used to reach the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenAiCompatClientNoKeyTest {

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private fun clientWithKey(key: String, requests: MutableList<String>) = OpenAiCompatClient(
    fallbackChain = ProviderFallbackChain(
      mapOf(
        LlmProvider.META to listOf(
          ProviderConfig(
            name = LlmProvider.META,
            baseUrl = "https://api.meta.ai/v1/chat/completions",
            model = "muse-spark-1.3",
            apiKeyResolver = { key },
          ),
        ),
      ),
    ),
    engine = MockEngine { request ->
      requests += request.url.toString()
      respond("""{"error":{"message":"Invalid API key"}}""", HttpStatusCode.Unauthorized)
    },
    usageRecorder = NoOpLlmUsageRecorder,
  )

  @Test
  fun `a blank key gives the add-your-key message and sends no request`() = runTest {
    AppLanguage.code = AppLanguage.EN
    val requests = mutableListOf<String>()

    val events = clientWithKey("", requests).streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertTrue("Meta was contacted without a key: $requests", requests.isEmpty())
    val error = events.single() as StreamEvent.Error
    assertEquals(missingKeyMessage(), error.message)
    assertTrue(error.message, error.message.contains("Add your Meta API key"))
    assertFalse("a raw HTTP code reached the person: ${error.message}", error.message.contains("401"))
  }

  /** Whitespace is not a key: pasting a stray newline must not send "Bearer \n" to Meta. */
  @Test
  fun `a key of spaces is no key`() = runTest {
    val requests = mutableListOf<String>()

    val events = clientWithKey("   ", requests).streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertTrue(requests.toString(), requests.isEmpty())
    assertTrue(events.single() is StreamEvent.Error)
  }

  /** The other side of the same coin: a rejected key keeps its own, different message (check the key in Settings). */
  @Test
  fun `a rejected key is a different message from a missing one`() = runTest {
    AppLanguage.code = AppLanguage.EN
    val requests = mutableListOf<String>()

    val events = clientWithKey("sk-not-a-real-key", requests).streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertEquals(1, requests.size)
    val error = events.single() as StreamEvent.Error
    assertFalse(error.message, error.message == missingKeyMessage())
    assertTrue(error.message, error.message.contains("turned the key down"))
  }
}
