package com.aiopenpocket.agent.core.llm

// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

/**
 * Price of a direct Meta Model API call in USD by Meta's price list, or `null` for a model that is not
 * on the list.
 *
 * We compute it ourselves because Meta, unlike OpenRouter, sends only tokens in usage. The Meta key is
 * entered by the user, and their credits are being spent — a spend card without money on this route
 * would be missing it exactly where money matters most.
 *
 * The cached part of the prompt (`prompt_tokens_details.cached_tokens`) has its own price. Thinking has
 * no separate price on the list: by the usage schema `reasoning_tokens` is a breakdown inside
 * `completion_tokens`, so they are already counted as output.
 */
internal fun metaListPriceUsd(model: String, usage: SseUsage): Double? {
  val price = META_PRICES[model] ?: return null
  val cached = (usage.promptTokensDetails?.cachedTokens ?: 0).coerceIn(0, usage.promptTokens)
  val fresh = usage.promptTokens - cached
  return (fresh * price.input + cached * price.cachedInput + usage.completionTokens * price.output) / TOKENS_PER_PRICE_UNIT
}

private class MetaPrice(val input: Double, val cachedInput: Double, val output: Double)

/**
 * USD per 1M tokens, standard mode — dev.meta.ai/docs/pricing-rate-limits, checked Oct 26, 2026.
 * There are no contributor versions here: the app does not use them (see [trainsOnUserData]).
 */
private val META_PRICES = mapOf(
  "muse-spark-1.3" to MetaPrice(input = 1.25, cachedInput = 0.15, output = 4.25),
  "muse-spark-1.2" to MetaPrice(input = 1.25, cachedInput = 0.15, output = 4.25),
  "muse-spark-1.1" to MetaPrice(input = 1.25, cachedInput = 0.15, output = 4.25),
  "muse-wonder-1.0" to MetaPrice(input = 4.00, cachedInput = 0.20, output = 20.00),
)

private const val TOKENS_PER_PRICE_UNIT = 1_000_000.0
