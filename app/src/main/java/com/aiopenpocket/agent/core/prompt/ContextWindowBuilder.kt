package com.aiopenpocket.agent.core.prompt

// @trace streaming-llm-chat-6595a1

import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.memory.model.Message

/**
 * Builds a head + [summary of middle] + tail window for long dialogs.
 */
object ContextWindowBuilder {

  data class Window(
    val verbatim: List<PrePromptAssembler.PromptMessage>,
    val hasMiddleGap: Boolean,
    val middleMessageCount: Int,
  )

  fun build(
    rawMessages: List<Message>,
    headCount: Int = LlmContextConfig.HEAD_MESSAGE_COUNT,
    tailCount: Int = LlmContextConfig.TAIL_MESSAGE_COUNT,
  ): Window {
    // Card-only messages carry no text and must not be sent to the LLM as empty
    // assistant turns (some providers reject blank content).
    val allMessages = rawMessages.filter {
      it.content.isNotBlank() || it.toolCallsJson != null
    }
    val total = allMessages.size
    return if (total <= headCount + tailCount) {
      buildFromParts(allMessages, emptyList(), total, headCount, tailCount)
    } else {
      buildFromParts(
        head = allMessages.take(headCount),
        tail = allMessages.takeLast(tailCount),
        totalPromptable = total,
        headCount = headCount,
        tailCount = tailCount,
      )
    }
  }

  /**
   * То же промпт-окно, но из уже готовых частей (head/tail/счётчик из лимитированных SQL-выборок,
   * см. ConversationRepository.getPromptWindowParts) — без загрузки всей истории в память.
   * Части считаются уже отфильтрованными от пустых карточек. Когда диалог целиком помещается в
   * head+tail, вся история приходит в [head], а [tail] пуст.
   */
  fun buildFromParts(
    head: List<Message>,
    tail: List<Message>,
    totalPromptable: Int,
    headCount: Int = LlmContextConfig.HEAD_MESSAGE_COUNT,
    tailCount: Int = LlmContextConfig.TAIL_MESSAGE_COUNT,
    budgetTokens: Int = LlmContextConfig.HISTORY_TOKEN_BUDGET,
  ): Window {
    if (totalPromptable == 0) {
      return Window(emptyList(), hasMiddleGap = false, middleMessageCount = 0)
    }
    val verbatim = (head + tail).map { it.toPromptMessage() }
    // Бюджет токенов — ВСЕГДА, а не только когда диалог длиннее head+tail. Раньше короткий диалог
    // (≤ 48 реплик) уходил целиком без проверки: пара вставленных простыней текста — и запрос
    // превышал окно модели, провайдер отвечал 400, а пользователь видел «нет сети».
    val kept = trimVerbatimToTokenBudget(verbatim, budgetTokens, headCount)
    val dropped = verbatim.size - kept.size
    val skipped = (totalPromptable - headCount - tailCount).coerceAtLeast(0)
    // Выброшенные бюджетом реплики — тоже «середина», о которой модель должна знать: иначе она
    // считает, что видит разговор целиком.
    val middleCount = skipped + dropped
    return Window(
      verbatim = kept,
      hasMiddleGap = dropped > 0 || skipped >= LlmContextConfig.MIN_MIDDLE_MESSAGES_FOR_SUMMARY,
      middleMessageCount = middleCount,
    )
  }

  fun formatMessagesForSummary(messages: List<Message>): String =
    messages.joinToString("\n\n") { msg ->
      "${msg.role}: ${msg.content.trim()}"
    }

  private fun Message.toPromptMessage() =
    PrePromptAssembler.PromptMessage(role, contentForModel(), toolCallsJson)

  /**
   * Ответ рефлексии хранится целиком — черновик, критика, итог: чат рисует все три фазы. Модели в
   * следующих ходах нужен только итог; черновик с критикой утраивали бы сообщение в контексте и
   * подсовывали бы отвергнутый вариант как сказанное.
   */
  private fun Message.contentForModel(): String =
    if (role == "assistant" && content.contains(REFLECTION_FINAL_HEADING)) {
      content.substringAfterLast(REFLECTION_FINAL_HEADING).trim()
    } else {
      content
    }

  /**
   * Обрезает окно под [budgetTokens], выбрасывая реплики в порядке «чем меньше нужна, тем раньше»:
   *
   * 1. старая часть хвоста — между началом диалога и последними [protectedRecent] репликами,
   *    от старых к новым;
   * 2. начало диалога ([headCount] первых реплик) — с конца, так что первое сообщение (тема)
   *    уходит последним из начала;
   * 3. последние [protectedRecent] реплики, от старых к новым;
   * 4. самую последнюю реплику — НИКОГДА: это текущее сообщение пользователя, без него ход теряет
   *    смысл. Если она одна больше бюджета — уходит как есть (пусть лучше откажет провайдер, чем
   *    модель ответит на пустоту).
   *
   * Прежний алгоритм, обрезав хвост до «начало + одна реплика», следующим шагом выбрасывал именно
   * эту одну — текущий вопрос, — сохраняя восемь реплик приветствия.
   */
  internal fun trimVerbatimToTokenBudget(
    verbatim: List<PrePromptAssembler.PromptMessage>,
    budgetTokens: Int = LlmContextConfig.HISTORY_TOKEN_BUDGET,
    headCount: Int = LlmContextConfig.HEAD_MESSAGE_COUNT,
    protectedRecent: Int = LlmContextConfig.PROTECTED_RECENT_MESSAGES,
  ): List<PrePromptAssembler.PromptMessage> {
    if (verbatim.size <= 1) return verbatim

    fun tokensOf(msg: PrePromptAssembler.PromptMessage): Int =
      LlmContextConfig.estimateTokens(msg.content) +
        (msg.toolCallsJson?.let { LlmContextConfig.estimateTokens(it) } ?: 0)

    var total = verbatim.sumOf { tokensOf(it) }
    if (total <= budgetTokens) return verbatim

    val last = verbatim.lastIndex
    val recentStart = maxOf(0, verbatim.size - protectedRecent)
    val headEnd = minOf(headCount, recentStart) // голова — только то, что не входит в защищённый хвост
    val dropOrder = (headEnd until recentStart) + // 1. старая часть хвоста
      (headEnd - 1 downTo 0) + // 2. начало диалога, с конца
      (recentStart until last) // 3. недавние, кроме самой последней
    val dropped = HashSet<Int>()
    for (index in dropOrder) {
      if (total <= budgetTokens) break
      total -= tokensOf(verbatim[index])
      dropped += index
    }
    return verbatim.filterIndexed { index, _ -> index !in dropped }
  }
}

/**
 * Заголовок финальной части сохранённого ответа рефлексии (черновик + критика + итог). Живёт здесь, а не
 * в ReflectionOrchestrator: сборщику истории (слой prompt) нельзя зависеть от оркестраторов (слой loop),
 * а оркестратор ссылается на эту константу.
 */
const val REFLECTION_FINAL_HEADING = "\n\n---\n\n✨ **Итог**\n"
