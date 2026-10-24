package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

/**
 * Провайдеры, между которыми переключается пользователь. Ровно два — и это осознанно.
 *
 * Было `AUTO, NIM, GROQ, GITHUB`. GROQ и GITHUB никогда не были подключены к `runAgent` (см. снятый
 * теперь комментарий в TurnManager: «Временно гоним всё на NIM… выпилим позже») — они висели
 * настройками без эффекта и ключами, которые некуда применить. `AUTO` означал кросс-провайдерную
 * цепочку NIM→GROQ→GITHUB; без двух последних он выродился бы в синоним NIM, то есть в
 * режим-обманку.
 *
 * Кросс-провайдерного автофолбэка больше НЕТ вообще: провайдер выбирается явно, и пользователь
 * всегда знает, куда ушли его данные и по чьему прайсу. Фолбэк остался там, где он полезен и
 * безопасен — МЕЖДУ МОДЕЛЯМИ внутри выбранного провайдера (см. [ProviderFallbackChain]).
 */
enum class LlmProvider {
  NIM, OPENROUTER
}

/**
 * Ключ NVIDIA, вшитый в сборку из `.env`, или пустая строка, если его там нет.
 *
 * ОДНА функция, а не два похожих условия: по ней И цепочка провайдеров решает, чем авторизоваться
 * (LlmModule), И Настройки решают, показывать ли предупреждение о пустом ключе. Стоило этим двоим
 * разойтись — и получается худший вариант: плейсхолдер уходит в запрос как настоящий ключ, а
 * предупреждения при этом нет. Плейсхолдер приходит из `.env.example` через secrets-плагин.
 */
fun bundledNvidiaKey(buildKey: String): String =
  buildKey.takeUnless { it.isBlank() || it == NVIDIA_KEY_PLACEHOLDER }.orEmpty()

const val NVIDIA_KEY_PLACEHOLDER = "MY_NVIDIA_API_KEY"

/**
 * Ключ OpenRouter, вшитый в отладочную сборку из `.env`, или пустая строка. Та же схема, что у
 * [bundledNvidiaKey]: без него приложение на Muse не дожило бы до Настроек — онбординг сам ходит в
 * модель. В магазинную сборку живой ключ не пустит проверка в app/build.gradle.kts.
 */
fun bundledOpenRouterKey(buildKey: String): String =
  buildKey.takeUnless { it.isBlank() || it == OPENROUTER_KEY_PLACEHOLDER }.orEmpty()

const val OPENROUTER_KEY_PLACEHOLDER = "MY_OPENROUTER_API_KEY"

/**
 * Провайдер по имени из настроек (`SettingsRepository.getSelectedProvider`).
 *
 * Неизвестное имя (переименовали константу, откатили сборку) — это OpenRouter, провайдер по
 * умолчанию, а не исключение: провайдер читается на каждом ходе, в том числе в фоне, и агент не
 * должен умирать из-за строки в prefs. Одна функция на всех, чтобы «тихий дефолт» не разъехался
 * между вызывающими.
 */
fun llmProviderOf(name: String): LlmProvider =
  name.trim().uppercase().let { normalized -> LlmProvider.entries.firstOrNull { it.name == normalized } }
    ?: LlmProvider.OPENROUTER

/**
 * Текущий выбор пользователя — читается на каждом обращении, а не фиксируется при сборке графа:
 * переключение в Настройках должно действовать со следующего же хода, включая фоновые.
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
  // Дефолт для моделей, не задавших своё значение. Каталоги провайдеров (assets/config/models*.json)
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
