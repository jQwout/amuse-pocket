package com.aiopenpocket.ui.settings

// @trace app-settings-beb470

import com.aiopenpocket.agent.core.llm.usage.LlmUsageDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The summary window: a week. Enough to see weekdays and weekends, and short enough for "now". */
private const val USAGE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Spend over the last week, or `null` when the accounting table cannot be read.
 *
 * One reader for both screens that show the spend card (Settings and Profile): two copies of these four
 * queries would drift, and then the same week would show two different sums. A read failure is `null`, i.e.
 * the "nothing to report yet" card — statistics are not a reason to show the user an error.
 */
internal suspend fun LlmUsageDao.weeklySummary(now: Long = System.currentTimeMillis()): UsageSummary? =
  withContext(Dispatchers.IO) {
    runCatching {
      val since = now - USAGE_WINDOW_MS
      val totals = totalsSince(since)
      val background = backgroundTotalsSince(since)
      UsageSummary(
        calls = totals.calls,
        promptTokens = totals.promptTokens,
        completionTokens = totals.completionTokens,
        costUsd = totals.costUsd,
        estimatedCalls = totals.estimatedCalls,
        backgroundCalls = background.calls,
        backgroundTokens = background.promptTokens + background.completionTokens,
        byModel = totalsByModelSince(since).map {
          UsageSummary.ModelUsage(
            model = it.model,
            calls = it.calls,
            tokens = it.promptTokens + it.completionTokens,
            costUsd = it.costUsd,
          )
        },
      )
    }.getOrNull()
  }
