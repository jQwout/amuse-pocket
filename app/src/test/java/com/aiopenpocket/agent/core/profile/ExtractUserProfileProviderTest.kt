package com.aiopenpocket.agent.core.profile

// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The user's story about themselves goes to the CHOSEN vendor.
 *
 * There was a live bug here: the call did not pass the provider and silently went to the default
 * `LlmProvider.NIM` from `LlmClient.streamChat`. The default is gone, so "forgot to pass it" no longer
 * compiles — but the compiler does not save you from "passed a literal", so we check by behavior.
 */
class ExtractUserProfileProviderTest {

  private class RecordingLlm : LlmClient {
    val providers = mutableListOf<LlmProvider>()

    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> {
      providers += provider
      return flowOf(
        StreamEvent.TextDelta("""{"name":"Аня","age":"30","goals":"учить язык","interests":"бег"}"""),
        StreamEvent.Finish("stop"),
      )
    }
  }

  @Test
  fun `извлечение профиля идёт к выбранному провайдеру, а не к дефолту`() = runTest {
    val llm = RecordingLlm()

    ExtractUserProfileUseCase(llm) { LlmProvider.OPENROUTER }("Меня зовут Аня, мне 30")

    assertEquals(listOf(LlmProvider.OPENROUTER), llm.providers)
  }
}
