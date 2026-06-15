package com.aiopenpocket.agent.core.llm

// Экземпляр предоставляется через LlmModule.provideProviderFallbackChain (единственный binding).
// Каждый провайдер хранит СПИСОК конфигов: для NIM это модели из assets/config/models.json,
// отсортированные по priority. OpenAiCompatClient перебирает их по очереди с фоллбэком при ошибке.
class ProviderFallbackChain(
  private val configs: Map<LlmProvider, List<ProviderConfig>>,
) {
  fun resolve(requested: LlmProvider): List<ProviderConfig> = when (requested) {
    LlmProvider.AUTO -> buildList {
      configs[LlmProvider.NIM]?.let(::addAll)
      configs[LlmProvider.GROQ]?.let(::addAll)
      configs[LlmProvider.GITHUB]?.let(::addAll)
    }
    else -> configs[requested].orEmpty()
  }
}
