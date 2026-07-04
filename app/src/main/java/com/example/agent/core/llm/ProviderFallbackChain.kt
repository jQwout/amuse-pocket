package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

// Экземпляр предоставляется через LlmModule.provideProviderFallbackChain (единственный binding).
// Каждый провайдер хранит СПИСОК конфигов: для NIM это модели из assets/config/models.json,
// отсортированные по priority. OpenAiCompatClient перебирает их по очереди с фоллбэком при ошибке.
class ProviderFallbackChain(
  private val configs: Map<LlmProvider, List<ProviderConfig>>,
  // Лямбда (не значение) — как apiKeyResolver у ProviderConfig: читается заново на каждый resolve(),
  // поэтому смена модели в Настройках подхватывается со следующего же хода, без пересборки DI-графа.
  private val preferredNimModel: () -> String? = { null },
) {
  fun resolve(requested: LlmProvider): List<ProviderConfig> {
    val base = when (requested) {
      LlmProvider.AUTO -> AUTO_FALLBACK_ORDER.flatMap { configs[it].orEmpty() }
      else -> configs[requested].orEmpty()
    }
    val preferred = preferredNimModel()?.takeIf { it.isNotBlank() && it != "AUTO" } ?: return base
    // Выбранная вручную модель — первой в очереди; остальные остаются фоллбэком в исходном порядке.
    val (match, rest) = base.partition { it.name == LlmProvider.NIM && it.model == preferred }
    return match + rest
  }

  private companion object {
    // Явный порядок фоллбэка AUTO — продуктовое решение, не должно зависеть от порядка объявления
    // констант enum LlmProvider (тот может поменяться по несвязанной причине).
    val AUTO_FALLBACK_ORDER = listOf(LlmProvider.NIM, LlmProvider.GROQ, LlmProvider.GITHUB)
  }
}
