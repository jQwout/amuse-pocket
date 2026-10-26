package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.activeLlmProvider
import com.aiopenpocket.ui.theme.ThemeMode

data class SettingsUiState(
  val openRouterKey: String = "",
  /** Ключ Meta Model API. Непустой — запросы идут напрямую в Meta (см. [activeLlmProvider]). */
  val metaKey: String = "",
  /** Модель маршрута OpenRouter ("AUTO" — по приоритету из каталога). */
  val selectedModel: String = "AUTO",
  /** Модель прямого маршрута Meta — свой выбор, см. SettingsRepository.getSelectedModel. */
  val selectedMetaModel: String = "AUTO",
  /** Модели Meta из каталога OpenRouter, отсортированные по приоритету. */
  val openRouterModels: List<String> = emptyList(),
  /** Модели прямого Meta Model API (статичный каталог), отсортированные по приоритету. */
  val metaModels: List<String> = emptyList(),
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
) {
  /**
   * Куда пойдут запросы с ТЕМИ ключами, что сейчас в полях, — ещё до «Сохранить»: экран сразу
   * показывает, что изменит нажатие, и список моделей переключается вместе со строкой маршрута.
   */
  val provider: LlmProvider get() = activeLlmProvider(metaKey)

  /** Опции селектора модели — каталог текущего [provider]. */
  val availableModels: List<String>
    get() = if (provider == LlmProvider.META) metaModels else openRouterModels

  /** Выбранная модель текущего [provider]. */
  val currentModel: String
    get() = if (provider == LlmProvider.META) selectedMetaModel else selectedModel
}

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
  /** `null` — цену не назвал ни один вызов периода (провайдер отдал только токены). */
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
