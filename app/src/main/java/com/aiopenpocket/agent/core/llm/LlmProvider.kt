package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

/**
 * Через кого идут запросы к модели. Модели на обоих маршрутах одни и те же — Muse от Meta, разный
 * только путь:
 * - [META] — прямой Meta Model API (`api.meta.ai`). Основной маршрут: отсев хакатона засчитывает
 *   «Meta Model API и/или открытые веса Meta», а OpenRouter буквально ни то, ни другое; и только
 *   здесь есть SAM 3.1 и Voice Transcribe.
 * - [OPENROUTER] — запасной путь к тем же моделям; из его каталога приложение берёт только модели
 *   Meta (см. [isMetaModel]).
 *
 * Какой из них работает, решает ключ (см. [activeLlmProvider]). Было `NIM, OPENROUTER`, а ещё раньше
 * `AUTO, NIM, GROQ, GITHUB`; NVIDIA NIM выпилен 25 окт 2026 — чужой вендор означал чужие модели в
 * курсе про Meta. Кросс-провайдерного автофолбэка нет и не будет: фолбэк — между МОДЕЛЯМИ одного
 * провайдера (см. [ProviderFallbackChain]), иначе запрос мог бы молча уехать другому адресату.
 */
enum class LlmProvider {
  OPENROUTER,
  META,
}

/**
 * Провайдер решает ключ: введён ключ Meta Model API — запросы идут напрямую в Meta, нет — через
 * OpenRouter.
 *
 * Отдельного переключателя нет сознательно. Ключ Meta вводит сам пользователь (вшитого нет, см.
 * LlmModule), и «ввёл ключ — работает напрямую» не требует объяснять, что такое маршрут. А без ключа
 * Meta отладочная сборка продолжает работать на вшитом ключе OpenRouter. ОДНА функция и для цепочки,
 * и для строки «куда идут запросы» в Настройках: разойдись они — экран обещал бы Meta, а ход уходил
 * бы в OpenRouter.
 */
fun activeLlmProvider(metaKey: String): LlmProvider =
  if (metaKey.isNotBlank()) LlmProvider.META else LlmProvider.OPENROUTER

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
 * графа: ключ Meta, введённый в Настройках, переводит на прямой Meta Model API уже следующий ход —
 * и чат, и план-движок, и фоновые heartbeat'ы, без перезапуска.
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
