package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import com.aiopenpocket.genai.ApiKeyStore
import com.aiopenpocket.genai.AppPaths
import com.aiopenpocket.genai.GenaiModelCatalog
import com.aiopenpocket.genai.GenaiRuntime
import com.aiopenpocket.genai.GenerationQueue
import com.aiopenpocket.genai.JobStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 * Ручная сборка графа зависимостей. Metro (как в :app) здесь не подключён сознательно: на два
 * экрана и одну очередь он даёт больше конфигурации, чем пользы, а при переезде в общий модуль
 * (фаза 4) граф всё равно будет собираться на стороне :app.
 */
class AppContainer(scope: CoroutineScope) {

  private val paths = AppPaths()
  private val keyStore = ApiKeyStore(paths)

  private val _apiKey = MutableStateFlow(keyStore.read())
  val apiKey: StateFlow<String> = _apiKey.asStateFlow()

  val catalog: GenaiModelCatalog = GenaiModelCatalog.load(paths.modelsOverrideFile)

  // Ключ читается лямбдой, а не значением: пользователь вводит его в настройках уже после того,
  // как рантайм собран, и пересобирать граф на каждое сохранение не нужно.
  private val runtime = GenaiRuntime(apiKey = { _apiKey.value }, paths = paths)

  val queue = GenerationQueue(
    provider = runtime.provider,
    artifacts = runtime.artifacts,
    store = JobStore(paths),
    scope = scope,
  )

  val outputDir: Path get() = paths.outputDir
  val configDir: Path get() = paths.configDir
  val modelsOverrideFile: Path get() = paths.modelsOverrideFile

  init {
    // Видео-задание, отправленное в прошлый запуск, продолжает опрашиваться; прерванные синхронные
    // задания помечаются упавшими, чтобы история не врала про «в работе».
    queue.resumeUnfinished()
  }

  fun saveApiKey(key: String) {
    keyStore.write(key)
    _apiKey.value = key.trim()
  }
}
