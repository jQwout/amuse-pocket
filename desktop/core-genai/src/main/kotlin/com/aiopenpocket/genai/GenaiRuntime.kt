package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import io.ktor.client.engine.cio.CIO

/**
 * Прод-сборка сетевой части. Существует, чтобы выбор HTTP-движка не протекал в UI-модуль:
 * тесты подменяют движок через конструкторы [OpenRouterProvider]/[ArtifactStore], а приложению
 * достаточно сказать «дай рабочий провайдер».
 *
 * Движок один на оба потребителя — пул соединений и так общий, а держать два незачем.
 */
class GenaiRuntime(
  apiKey: () -> String,
  paths: AppPaths = AppPaths(),
) {
  private val engine = CIO.create()

  val provider: GenerationProvider = OpenRouterProvider(engine = engine, apiKey = apiKey)

  val artifacts: ArtifactStore = ArtifactStore(engine = engine, apiKey = apiKey, paths = paths)
}
