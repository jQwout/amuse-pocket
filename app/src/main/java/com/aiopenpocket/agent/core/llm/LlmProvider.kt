package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

/**
 * Через кого идут запросы к модели. Сейчас провайдер один — OpenRouter, и в нём приложение берёт
 * только модели Meta (см. [isMetaModel]): курс и агент работают на Muse.
 *
 * Было `NIM, OPENROUTER`, а ещё раньше `AUTO, NIM, GROQ, GITHUB`. NVIDIA NIM выпилен 25 окт 2026:
 * приложение идёт на Meta-хакатон на моделях Meta, а второй вендор в выборе означал второй ключ,
 * второй каталог и уроки, которые на нём молча показывали бы картинки из сборки вместо Muse.
 *
 * Тип оставлен, а не растворён в константах: следующим провайдером станет прямой Meta Model API —
 * SAM 3.1 и Voice Transcribe есть только там. Кросс-провайдерного автофолбэка нет и не будет:
 * фолбэк — между МОДЕЛЯМИ одного провайдера (см. [ProviderFallbackChain]).
 */
enum class LlmProvider {
  OPENROUTER
}

/**
 * Ключ OpenRouter, вшитый в отладочную сборку из `.env`, или пустая строка. Без него приложение на
 * Muse не дожило бы до Настроек — онбординг сам ходит в модель. ОДНА функция и для цепочки, и для
 * предупреждения в Настройках: разойдись они — плейсхолдер ушёл бы в запрос как настоящий ключ, а
 * предупреждения при этом не было бы. В магазинную сборку живой ключ не пустит проверка в
 * app/build.gradle.kts.
 */
fun bundledOpenRouterKey(buildKey: String): String =
  buildKey.takeUnless { it.isBlank() || it == OPENROUTER_KEY_PLACEHOLDER }.orEmpty()

const val OPENROUTER_KEY_PLACEHOLDER = "MY_OPENROUTER_API_KEY"

/**
 * Провайдер, к которому идёт запрос, — читается на каждом обращении, а не фиксируется при сборке
 * графа. Пока он один, но потребители (петля агента, план-движок, heartbeat'ы) уже спрашивают его
 * так, чтобы появление прямого Meta Model API не потребовало их переписывать.
 *
 * Отдельный тип, а не `SettingsRepository` напрямую: потребителям (петля агента, heartbeat'ы) нужен
 * ровно один факт «куда слать», и тащить ради него хранилище ключей — значит тащить в тесты
 * EncryptedSharedPreferences с Android Keystore.
 */
fun interface SelectedLlmProvider {
  operator fun invoke(): LlmProvider
}

data class ProviderConfig(
  val name: LlmProvider,
  val baseUrl: String,
  val model: String,
  // Дефолт для моделей, не задавших своё значение. Каталог (assets/config/models_openrouter.json)
  // проставляют max_tokens сами. Это лимит ДЛИНЫ ОТВЕТА (output), препромпт сюда не входит.
  val maxTokens: Int = 4096,
  val reasoning: Boolean = false,
  val temperature: Double? = null,
  val topP: Double? = null,
  /**
   * Умеет ли модель звать инструменты (см. [ModelEntry.supportsTools]). Гейт применяет
   * [ProviderFallbackChain.resolve] — одна точка на все способы собрать цепочку.
   */
  val supportsTools: Boolean = true,
  val apiKeyResolver: () -> String,
)
