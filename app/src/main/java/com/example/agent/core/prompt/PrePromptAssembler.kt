package com.aiopenpocket.agent.core.prompt

import android.app.Application
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

@SingleIn(AppScope::class)
class PrePromptAssembler @Inject constructor(
  private val application: Application,
  private val memoryRepository: MemoryRepository,
  private val conversationRepository: ConversationRepository,
) {

  suspend fun assemble(conversationId: String, currentMessage: String): PromptContext =
    withContext(Dispatchers.IO) {
      val systemBase = try {
        application.assets.open("prompts/system_base.md").bufferedReader().use { it.readText() }
      } catch (_: Exception) {
        DEFAULT_SYSTEM_BASE
      }

      val conversation = conversationRepository.getConversation(conversationId)
      val personaId = conversation?.personaId ?: "sharp_partner"
      val persona = try {
        application.assets.open("personas/$personaId.md").bufferedReader().use { it.readText() }
      } catch (_: Exception) {
        "Характер: Деловой напарник по умолчанию."
      }

      val tutorialPrompt = if (conversation?.isTutorial == true) {
        val levelId = conversation.title
        try {
          application.assets.open("prompts/roadmap/$levelId.md").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
          try {
            application.assets.open("prompts/tutorial.md").bufferedReader().use { it.readText() }
          } catch (_: Exception) {
            null
          }
        }
      } else null

      val profileFacts = memoryRepository.getFactsByCategory("profile")
      val facts = memoryRepository.searchFacts(currentMessage).take(LlmContextConfig.RELEVANT_FACTS_LIMIT)
      val episodes = memoryRepository.getRecentEpisodesForConversation(
        conversationId,
        LlmContextConfig.EPISODES_LIMIT,
      )

      val allMessages = conversationRepository.getAllMessages(conversationId)
      val window = ContextWindowBuilder.build(allMessages)

      val systemPrompt = buildString {
        appendLine(systemBase)
        appendLine()
        if (tutorialPrompt != null) {
          appendLine(tutorialPrompt)
          appendLine()
        }
        appendLine("## Персона")
        appendLine(persona)
        appendLine()
        if (profileFacts.isNotEmpty()) {
          appendLine("## Профиль пользователя")
          profileFacts.forEach { appendLine("- ${it.key}: ${it.value}") }
          appendLine()
        }
        if (facts.isNotEmpty()) {
          appendLine("## Релевантные факты")
          facts.forEach { appendLine("- ${it.key}: ${it.value}") }
          appendLine()
        }
        if (episodes.isNotEmpty()) {
          appendLine("## Недавние события")
          episodes.forEach { appendLine("- [${it.type}] ${it.outcome}") }
          appendLine()
        }
        if (window.hasMiddleGap) {
          appendLine("## Резюме середины диалога")
          val middleSummary = conversation?.summary?.trim().orEmpty()
          if (middleSummary.isNotEmpty()) {
            appendLine(middleSummary)
          } else {
            appendLine(
              "Между началом и недавними сообщениями было ещё ${window.middleMessageCount} " +
                "реплик. Детали пока не сжаты — опирайся на недавний контекст и инструменты памяти.",
            )
          }
          appendLine()
        }
        appendLine("## Инструкции")
        appendLine("Действуй кратко. Используй инструменты когда нужно. Не показывай внутренние ID.")
      }

      PromptContext(
        systemPrompt = systemPrompt,
        history = window.verbatim,
      )
    }

  data class PromptContext(
    val systemPrompt: String,
    val history: List<PromptMessage>,
  )

  data class PromptMessage(
    val role: String,
    val content: String,
    val toolCallsJson: String?,
  )

  companion object {
    private val DEFAULT_SYSTEM_BASE = """
Ты — локальный AI-агент на Android-устройстве пользователя.
Если задача подразумевает визуальный результат — используй render_card.
Если нужен контекст из прошлого — используй search_memory или recall_episodes.
Если пользователь сообщает факт о себе — используй remember_fact.
Файлы — только в песочнице. Уведомления и фоновые задачи пока недоступны.
Отвечай кратко. Подробности — только по запросу.
Никогда не показывай внутренние ID, пути, токены.
    """.trimIndent()
  }
}
