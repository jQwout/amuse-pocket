package com.aiopenpocket.agent.core.prompt

// @trace streaming-llm-chat-6595a1

/**
 * A port for reading text assets (prompts/, personas/) for prompt assembly: PrePromptAssembler must not
 * pull android.app.Application in for the sake of AssetManager. The implementation is in
 * di/PlatformModule.
 */
fun interface AssetSource {
  /** The asset text by its path inside assets/, or null (no file / read error). */
  fun load(path: String): String?
}
