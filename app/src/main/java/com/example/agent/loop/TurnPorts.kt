package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d

import com.aiopenpocket.agent.core.llm.LlmProvider
import kotlinx.coroutines.flow.Flow

/**
 * Порт исполнения хода — узкий шов между TurnManager и AgentLoopOrchestrator,
 * чтобы менеджер тестировался скриптовым фейком без LLM/Room.
 */
fun interface TurnExecutor {
  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider,
    mode: AgentMode,
    planApproved: Boolean,
    goalMaxIterations: Int,
  ): Flow<AgentEvent>
}

/** То немногое из ConversationRepository, что нужно ходу: записать реплики и узнать заголовок. */
interface TurnPersistence {
  suspend fun addUserMessage(conversationId: String, text: String): String
  suspend fun addAssistantMessage(conversationId: String, text: String, cardSpec: String?): String
  suspend fun conversationTitle(conversationId: String): String?
}

/** Приложение сейчас на экране? Гейт для пуша об окончании фонового хода. */
fun interface ForegroundState {
  fun isInForeground(): Boolean
}

/** Показ уведомления «ход завершился, пока приложение в фоне». */
fun interface TurnNotifier {
  fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean)
}
