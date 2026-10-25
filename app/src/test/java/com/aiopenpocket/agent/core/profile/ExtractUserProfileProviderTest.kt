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
 * Рассказ пользователя о себе уходит ВЫБРАННОМУ вендору.
 *
 * Здесь был живой баг: вызов не передавал провайдера и молча ехал на дефолт `LlmProvider.NIM` из
 * `LlmClient.streamChat`. Дефолт убран, так что «забыл прокинуть» теперь не компилируется, — но от
 * «прокинул литерал» компилятор не спасает, поэтому проверяем поведением.
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
