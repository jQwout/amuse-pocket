package com.aiopenpocket.agent.core.llm

// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

/**
 * Цена вызова прямого Meta Model API в USD по прайсу Meta, или `null` для модели, которой в прайсе
 * нет.
 *
 * Считаем сами, потому что Meta, в отличие от OpenRouter, присылает в usage только токены. Ключ Meta
 * вводит сам пользователь, и тратятся его кредиты — карточка расхода без денег на этом маршруте
 * была бы как раз там, где деньги важнее всего.
 *
 * Кешированная часть промпта (`prompt_tokens_details.cached_tokens`) идёт по своей цене. Отдельной
 * цены у размышления в прайсе нет: по схеме usage `reasoning_tokens` — разбивка внутри
 * `completion_tokens`, так что они уже посчитаны как выход.
 */
internal fun metaListPriceUsd(model: String, usage: SseUsage): Double? {
  val price = META_PRICES[model] ?: return null
  val cached = (usage.promptTokensDetails?.cachedTokens ?: 0).coerceIn(0, usage.promptTokens)
  val fresh = usage.promptTokens - cached
  return (fresh * price.input + cached * price.cachedInput + usage.completionTokens * price.output) / TOKENS_PER_PRICE_UNIT
}

private class MetaPrice(val input: Double, val cachedInput: Double, val output: Double)

/**
 * USD за 1M токенов, стандартный режим — dev.meta.ai/docs/pricing-rate-limits, сверено 26 окт 2026.
 * Contributor-версий тут нет: приложение их не берёт (см. [trainsOnUserData]).
 */
private val META_PRICES = mapOf(
  "muse-spark-1.3" to MetaPrice(input = 1.25, cachedInput = 0.15, output = 4.25),
  "muse-spark-1.2" to MetaPrice(input = 1.25, cachedInput = 0.15, output = 4.25),
  "muse-spark-1.1" to MetaPrice(input = 1.25, cachedInput = 0.15, output = 4.25),
  "muse-wonder-1.0" to MetaPrice(input = 4.00, cachedInput = 0.20, output = 20.00),
)

private const val TOKENS_PER_PRICE_UNIT = 1_000_000.0
