package com.aiopenpocket.agent.core.prompt

// @trace rezhimy-agenta-plan-i-goal-ee2361

/**
 * Порт чтения текстовых ассетов (prompts/, personas/) для сборки промпта: PrePromptAssembler не
 * должен тянуть android.app.Application ради AssetManager. Реализация — в di/PlatformModule.
 */
fun interface AssetSource {
  /** Текст ассета по пути внутри assets/, либо null (нет файла / ошибка чтения). */
  fun load(path: String): String?
}
