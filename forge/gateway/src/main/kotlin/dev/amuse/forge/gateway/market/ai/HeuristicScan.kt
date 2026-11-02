package dev.amuse.forge.gateway.market.ai

import dev.amuse.forge.contract.ReviewConcern

// @trace market-ai-8f3b21

/**
 * A plain pattern scan next to the model review. The model reads the package, and a package can be
 * written to talk the model into "safe"; these patterns cannot be talked into anything. A hit never
 * blocks on its own (false positives happen), it floors the verdict at caution and shows the quote.
 */
object HeuristicScan {

    private class Rule(val pattern: Regex, val explanation: String)

    private val rules = listOf(
        Rule(
            Regex("""(ignore|disregard|forget)\s+(all\s+)?(the\s+)?(previous|prior|above|earlier)\s+(instructions|rules|prompts?)""", RegexOption.IGNORE_CASE),
            "Tries to override the instructions it runs under",
        ),
        Rule(
            Regex("""(do\s*n[o']?t|never)\s+(tell|inform|show|let)\s+(the\s+)?(user|person|owner)""", RegexOption.IGNORE_CASE),
            "Asks to keep something from the person",
        ),
        Rule(
            Regex("""(without|never|do\s*n[o']?t)\s+(asking|ask(ing)?\s+for)\s+(for\s+)?(confirmation|permission|approval)""", RegexOption.IGNORE_CASE),
            "Asks to skip the person's confirmation",
        ),
        Rule(
            Regex("""(send|upload|post|forward|exfiltrate)\b[^.\n]{0,80}\bhttps?://""", RegexOption.IGNORE_CASE),
            "Sends data to a web address",
        ),
        Rule(
            Regex("""(password|passcode|one[- ]time code|2fa|seed phrase|private key|card number|cvv)""", RegexOption.IGNORE_CASE),
            "Mentions credentials or payment secrets",
        ),
        Rule(
            Regex("""(system prompt|developer mode|jailbreak)""", RegexOption.IGNORE_CASE),
            "Talks about the model's own prompt or jailbreaking",
        ),
    )

    private const val MAX_QUOTE = 160

    fun scan(texts: List<String>): List<ReviewConcern> =
        rules.mapNotNull { rule ->
            texts.firstNotNullOfOrNull { rule.pattern.find(it)?.value }
                ?.let { ReviewConcern(kind = "heuristic", quote = it.take(MAX_QUOTE), explanation = rule.explanation) }
        }
}
