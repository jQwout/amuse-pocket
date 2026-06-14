package com.aiopenpocket.agent.core.llm

// Экземпляр предоставляется через LlmModule.provideProviderFallbackChain (единственный binding).
class ProviderFallbackChain(
  private val configs: Map<LlmProvider, ProviderConfig>,
) {
  fun resolve(requested: LlmProvider): List<ProviderConfig> = when (requested) {
    LlmProvider.AUTO -> listOfNotNull(
      configs[LlmProvider.NIM],
      configs[LlmProvider.GROQ],
      configs[LlmProvider.GITHUB],
    )
    else -> listOfNotNull(configs[requested])
  }
}
