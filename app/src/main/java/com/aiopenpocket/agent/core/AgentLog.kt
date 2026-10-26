package com.aiopenpocket.agent.core

// @trace streaming-llm-chat-6595a1

/**
 * A logging port for the domain layer (agent/loop, agent/core/prompt): the same levels as
 * android.util.Log, but without an Android dependency — domain tests stay pure JVM unit tests. The
 * implementation is in di/PlatformModule.
 */
interface AgentLog {
  fun d(tag: String, message: String)
  fun w(tag: String, message: String)
  fun e(tag: String, message: String, error: Throwable? = null)
}
