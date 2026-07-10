package com.aiopenpocket.agent.core

// @trace streaming-llm-chat-6595a1

/**
 * Порт логирования для доменного слоя (agent/loop, agent/core/prompt): те же уровни, что у
 * android.util.Log, но без зависимости от Android — доменные тесты остаются чистыми JVM-юнитами.
 * Реализация — в di/PlatformModule.
 */
interface AgentLog {
  fun d(tag: String, message: String)
  fun w(tag: String, message: String)
  fun e(tag: String, message: String, error: Throwable? = null)
}
