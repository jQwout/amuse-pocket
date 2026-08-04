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
) : AutoCloseable {

  private val engine = CIO.create()

  private val openRouter = OpenRouterProvider(engine = engine, apiKey = apiKey)

  val provider: GenerationProvider get() = openRouter

  val artifacts: ArtifactStore = ArtifactStore(engine = engine, apiKey = apiKey, paths = paths)

  /**
   * Живой каталог моделей.
   *
   * Свой файл пользователя приходит сюда дважды, и это не дублирование: как `pinned` он даёт
   * модели, которые живой список отменить не вправе, а как `seed` — порядок, подписи, голоса и
   * запасной вариант, когда сети нет. Разница ровно та, что описана в README: свой список
   * перекрывает встроенный, а не подсказывает наравне с ним.
   */
  val catalogService: GenaiCatalogService = GenaiCatalogService(
    engine = engine,
    cacheFile = paths.modelsCacheFile,
    seed = { GenaiModelCatalog.load(paths.modelsOverrideFile) },
    pinned = { GenaiModelCatalog.loadOverride(paths.modelsOverrideFile) },
  )

  /**
   * Движок закрывается здесь, а не в клиентах: он общий, и `HttpClient`, собранный поверх готового
   * движка, им не владеет — без этого при закрытии окна оставались бы живые потоки CIO.
   */
  override fun close() {
    openRouter.close()
    artifacts.close()
    catalogService.close()
    engine.close()
  }
}
