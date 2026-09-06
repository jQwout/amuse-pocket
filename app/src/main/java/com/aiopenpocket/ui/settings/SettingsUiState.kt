package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.ui.theme.ThemeMode

data class SettingsUiState(
  val nvidiaKey: String = "",
  val openRouterKey: String = "",
  /** Куда уходят запросы агента. Меняется только руками — молчаливого переезда к другому вендору нет. */
  val provider: LlmProvider = LlmProvider.NIM,
  /** Конкретная модель ВЫБРАННОГО провайдера ("AUTO" — по приоритету из его каталога). */
  val selectedModel: String = "AUTO",
  /** Модели каталога выбранного провайдера, отсортированные по приоритету — опции для выбора. */
  val availableModels: List<String> = emptyList(),
  val personaId: String = "sharp_partner",
  val personas: List<String> = emptyList(),
  /** Лимит витков гоал-мода (петля ReAct до достижения цели). */
  val goalMaxIterations: Int = 3,
  /** Пресет размера шрифта сообщений в чате. */
  val chatFontScale: ChatFontScale = ChatFontScale.DEFAULT,
  /** Режим темы: Светлая/Тёмная/Системная. */
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  /** Расход за последнюю неделю. `null` — ещё не прочитан с диска. */
  val usage: UsageSummary? = null,
  val saved: Boolean = false,
)

/**
 * Расход за период — то, что видит пользователь и по чему считаются тарифы.
 *
 * Фон вынесен отдельным полем, а не спрятан в общей сумме: вызовы вне хода (аннотатор диалога,
 * извлечение профиля, heartbeat) пользователь не заказывал и не видит, и именно их доля отвечает на
 * вопрос «почему счёт растёт, когда я ничего не делаю».
 */
data class UsageSummary(
  val calls: Int,
  val promptTokens: Long,
  val completionTokens: Long,
  /** `null` — цену не назвал ни один вызов периода (так работает NIM: он отдаёт только токены). */
  val costUsd: Double?,
  /** Сколько вызовов посчитаны оценкой, а не замером провайдера — мера доверия к цифрам выше. */
  val estimatedCalls: Int,
  val backgroundCalls: Int,
  val backgroundTokens: Long,
  val byModel: List<ModelUsage>,
) {
  val totalTokens: Long get() = promptTokens + completionTokens

  data class ModelUsage(
    val model: String,
    val calls: Int,
    val tokens: Long,
    val costUsd: Double?,
  )
}
