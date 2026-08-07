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

  /**
   * Журнал запросов. Живёт здесь, а не в провайдере, потому что читает его экран, а пишет
   * провайдер: единственная точка, которую видят оба, — сборка сетевой части.
   *
   * Выключен по умолчанию — включается тумблером в настройках.
   */
  val requestJournal = InMemoryRequestJournal()

  private val openRouter = OpenRouterProvider(engine = engine, apiKey = apiKey, journal = requestJournal)

  val provider: GenerationProvider get() = openRouter

  val artifacts: ArtifactStore = ArtifactStore(engine = engine, apiKey = apiKey, paths = paths)

  /**
   * Примеры картинок у моделей. Единственный компонент, который ходит на сайт, а не в API, — почему
   * так, разобрано в KDoc [ModelPreviews]. Ключ ему не передаётся: страница и ассеты публичные, и
   * посылать туда ключ незачем.
   */
  val modelPreviews: ModelPreviews = ModelPreviews(engine = engine, paths = paths)

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
    modelPreviews.close()
    engine.close()
  }
}
